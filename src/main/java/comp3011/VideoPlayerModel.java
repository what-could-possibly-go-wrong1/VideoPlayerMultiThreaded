// STUDY COPY - same logic as VideoPlayerModel.java, just scruffier. neat one goes to gradescope
package comp3011;

import java.io.File;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/*
 * the MT player's "coordinator". lives ONLY on the javafx thread.
 *   - gets user commands from the controller (play/pause/seek/...)
 *   - owns the PlaybackClock + at most 1 PlaybackSession (= the decoder/worker/audio threads)
 *   - per frame job = pump(): every javafx heartbeat, show the next frame IF its done + due. thats it.
 *   - nothing heavy happens here -> GUI stays snappy however many effects
 *
 * concurrency notes:
 *   - everything here is javafx-thread only (no locks needed)... EXCEPT audioOn, which decoder + audio threads
 *     read -> volatile
 *   - data crosses threads only thru safe hand-offs: the session's blocking queue, CompletableFutures, sync'd clock
 *   - seeks never wait. new EPOCH starts, anything from older epochs gets binned wherever it shows up
 *   - every frame gets a FULL frame period on screen: fall behind -> clock slips (dont rush = no flashing frames).
 *     + playback only starts once a batch of frames is ready (pre-roll)
 *   - frames shown strictly in decode order, 1 at a time -> doesnt matter which worker finishes first
 */
public class VideoPlayerModel implements PlaybackModel {
    static final long NONE = -1;                    // "no seek position" marker
    static final long FIVE_SEC_US = 5_000_000L;
    static final long PREROLL_GIVE_UP_NS = 2_000_000_000L;   // wait max 2s for pre-roll then just go
    static final int PREROLL_BATCHES = 2;           // frames per worker that must be ready before we start

    final List<FrameProcessor> effects;
    final int workers;
    final PlaybackClock clock = new PlaybackClock();
    final BiConsumer<Integer, Integer> onVideoSize;
    final Consumer<Image> onFrame;
    final Consumer<String> onStatus;
    final BiConsumer<Boolean, Boolean> onPlayState;
    final Consumer<Boolean> onAudioState;

    // javafx heartbeat (~60/sec), same as the original. calls pump() each pulse
    final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) { pump(now); }
    };

    File file;                  // from the command line
    PlaybackSession session;    // threads + resources of current playback, or null
    boolean open;               // playing?
    boolean paused;
    volatile boolean audioOn;   // read by decoder + audio threads!!
    long epoch;                 // ++ on every start/seek
    long posUs;                 // current position
    long durationUs = NONE;
    int frameDurUs;
    int fps;
    long baseUs;                // logical position the current epoch started at
    long firstMediaUs;          // media ts of the 1st frame shown this epoch
    long epochStartNs;          // when this epoch began (for pre-roll timeout)
    long relSeekBaseUs = NONE;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessorOption> frameProcessorOptions,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler)
    {
        audioOn = audioEnabled;
        onVideoSize = videoSizeChangedHandler; onFrame = frameReadyHandler; onStatus = statusChangedHandler;
        onPlayState = playbackStateChangedHandler; onAudioState = audioOutputStateChangedHandler;
        // build effects ONCE, on THIS thread, in cmd line order (order + 1 thread matter, see
        // FrameProcessorOption.instantiate). shared by every worker of every session
        effects = FrameProcessorOption.instantiate(frameProcessorOptions);
        workers = FrameProcessingPipeline.defaultWorkerCount();
        // we're on the javafx thread + toolkit is up -> good moment to bump the drawing threads' priority
        DisplayThreadPriority.boostDisplayThreads();
    }

    @Override public void play(File f) {
        file = f;
        durationUs = NONE;
        frameDurUs = 0;
        startPlayback(0, false, NONE);
    }

    @Override public void startOver() { if (file != null) seekTo(0); }
    @Override public void backFiveSeconds() { seekBy(-FIVE_SEC_US); }
    @Override public void forwardFiveSeconds() { seekBy(FIVE_SEC_US); }

    @Override
    public void togglePause() {
        if (!open) {   // finished/stopped -> pressing play restarts from where we were
            if (file != null) startPlayback(displayable(posUs), false, posUs);
            return;
        }
        paused = !paused;
        relSeekBaseUs = NONE;

        long now = System.nanoTime();
        if (paused) { clock.pause(now); session.flushAudioDevice(); }   // silence NOW, keep queued sound
        else clock.resume(now);

        tellPlayState();
    }

    @Override
    public void toggleAudioOutput() {
        audioOn = !audioOn;
        if (session != null) session.flushAudio();
        onAudioState.accept(audioOn);
    }

    @Override public boolean isAudioOutputEnabled() { return audioOn; }

    @Override
    public void stopPlayback() {
        closeSession();
        posUs = 0;
        paused = false;
        onStatus.accept("Stopped");
        onFrame.accept(null);
        tellPlayState();
    }

    @Override public void shutdown() { closeSession(); }

    void seekBy(long deltaUs) {
        if (file == null) return;
        long base = relSeekBaseUs != NONE ? relSeekBaseUs : posUs;   // so pressing -> -> -> stacks up properly
        seekTo(base + deltaUs);
    }

    void seekTo(long tsUs) {
        long logical = clamp(tsUs);
        long grabAt = displayable(logical);

        if (!open) { startPlayback(grabAt, paused, logical); return; }

        newEpoch(logical);
        session.seek(epoch, grabAt);   // decoder deals w/ it on its own thread
        tellPlayState();
    }

    void startPlayback(long startUs, boolean startPaused, long initialRelBaseUs)
    {
        closeSession();

        paused = startPaused;
        long now = System.nanoTime();
        if (paused) clock.pause(now); else clock.resume(now);

        newEpoch(initialRelBaseUs != NONE ? initialRelBaseUs : startUs);
        relSeekBaseUs = initialRelBaseUs;

        onStatus.accept(file.getName());

        try {
            session = PlaybackSession.open(file, startUs, effects, workers, clock, epoch, () -> audioOn);
            readMedia(session);
            onVideoSize.accept(session.imageWidth(), session.imageHeight());
            session.start();
            open = true;
            timer.start();
            tellPlayState();
        } catch (Exception e) {
            fail(e);
        }
    }

    void readMedia(PlaybackSession s) {
        fps = (int) Math.round(s.frameRate());
        frameDurUs = s.frameDurationUs();
        long d = s.durationUs();
        durationUs = d > 0 ? d : NONE;
    }

    // new epoch at this logical position: clock stops till 1st frame of the new epoch is ready,
    // + anything still in flight from older epochs gets binned
    void newEpoch(long logicalUs) {
        epoch++;
        clock.reset(epoch);
        posUs = logicalUs;
        relSeekBaseUs = logicalUs;
        baseUs = logicalUs;
        epochStartNs = System.nanoTime();
    }

    // every javafx heartbeat. NEVER decodes/processes - just peeks at the head of the queue -> back to the GUI in
    // microseconds. strictly decode order, max 1 frame per heartbeat
    void pump(long now) {
        if (!open) return;

        BlockingQueue<VideoDecoder.PresentationItem<Image>> q = session.presentationQueue();
        VideoDecoder.PresentationItem<Image> head = q.peek();
        while (head != null && head.epoch() != epoch) { q.poll(); head = q.peek(); }   // leftovers from before a seek
        if (head == null) return;   // decoder hasnt caught up yet (eg just after a seek)

        switch (head) {
            case VideoDecoder.EndOfStream<Image> _ -> { q.poll(); finished(); }
            case VideoDecoder.DecodeFailure<Image> f -> fail(f.error());
            case VideoDecoder.DecodedFrame<Image> fr -> showIfDue(q, fr, now);
        }
    }

    void showIfDue(BlockingQueue<VideoDecoder.PresentationItem<Image>> q, VideoDecoder.DecodedFrame<Image> fr, long now)
    {
        if (!fr.processed().isDone()) return;   // effects still running -> show it (late) next heartbeat

        if (!clock.isStarted()) {
            // 1st frame since start/seek. paused -> show straight away (like the original).
            // otherwise wait for pre-roll, then anchor the clock so THIS frame is due now
            if (!paused && !prerollDone(q, now)) return;
            firstMediaUs = fr.mediaTimestampUs();
            clock.startAt(fr.mediaTimestampUs(), now);
        } else if (!clock.isDue(fr.mediaTimestampUs(), now)) {
            return;
        } else {
            slipIfLate(fr, now);
        }

        q.poll();
        FrameProcessingPipeline.ProcessedFrame<Image> done;
        try {
            done = fr.processed().join();
        } catch (CompletionException e) {
            fail(e.getCause() instanceof Exception c ? c : e);
            return;
        }
        if (done != null) show(fr, done);   // null = skipped cos it went stale
    }

    // PRE-ROLL: dont start the clock on frame 1 alone - wait till PREROLL_BATCHES x workers frames are ready.
    // each batch runs in parallel so it costs a fraction of a second, but cushions a slow start (busy machine,
    // cold JIT) so the first frames arent due before their workers finish them.
    // give up after 2s, or if end-of-media is already queued
    boolean prerollDone(BlockingQueue<VideoDecoder.PresentationItem<Image>> q, long now) {
        if (now - epochStartNs > PREROLL_GIVE_UP_NS) return true;
        int want = Math.min(workers * PREROLL_BATCHES, q.size() + q.remainingCapacity());
        int ready = 0;
        for (VideoDecoder.PresentationItem<Image> it : q) {   // weakly consistent iteration = thread safe
            if (it.epoch() != epoch) continue;
            if (!(it instanceof VideoDecoder.DecodedFrame<Image> fr)) return true;   // EOS/error queued, nothing to wait for
            if (!fr.processed().isDone()) break;
            if (++ready >= want) return true;
        }
        return false;
    }

    // SLIP DONT RUSH. a late frame still gets a full frame period on screen. racing thru the backlog would flash
    // each frame for 1 refresh (16.7ms) = looks dropped (+ titan's screen capture counts it as missed).
    // so > half a frame late -> push the whole schedule back by the lateness.
    // (< half a frame late is normal - frames can only change on a 60Hz refresh)
    void slipIfLate(VideoDecoder.DecodedFrame<Image> fr, long now) {
        long frameNs = frameDurUs * 1_000L;
        long lateNs = now - clock.targetTimeNs(fr.mediaTimestampUs());
        if (frameNs > 0 && lateNs > frameNs / 2) clock.delay(lateNs);
    }

    void show(VideoDecoder.DecodedFrame<Image> fr, FrameProcessingPipeline.ProcessedFrame<Image> done) {
        posUs = baseUs + Math.max(0, fr.mediaTimestampUs() - firstMediaUs);
        relSeekBaseUs = NONE;

        // once a sec: headroom = how long before its deadline the frame was ready. can be > 1 frame now cos we
        // work ahead (buffered count shows how far)
        if (fps > 0 && fr.frameNumber() % fps == 0) {
            long spareUs = (clock.targetTimeNs(fr.mediaTimestampUs()) - done.readyAtNs()) / 1_000L;
            String col = spareUs < 0 ? "\u001B[31m" : "\u001B[32m";
            System.out.printf("Frame headroom is %s%5dus\u001B[0m out of %dus spare. %d frames buffered on %d threads.%n",
                    col, spareUs, frameDurUs, session.bufferedFrameCount(), workers);
        }
        onFrame.accept(done.output());
    }

    void finished() {
        long endUs = durationUs != NONE ? durationUs : posUs;
        closeSession();
        paused = true;
        posUs = endUs;
        relSeekBaseUs = endUs;
        onStatus.accept("Playback finished");
        tellPlayState();
    }

    void fail(Exception e) {
        e.printStackTrace();
        closeSession();
        paused = false;
        onStatus.accept("Error: " + e.getMessage());
        tellPlayState();
    }

    void closeSession() {
        timer.stop();
        open = false;
        if (session != null) { session.close(); session = null; }   // close() JOINS the threads before returning
    }

    long clamp(long tsUs) {
        long c = Math.max(0, tsUs);
        if (durationUs != NONE) c = Math.min(c, durationUs);
        return c;
    }

    // seeking to the very end -> back off one frame so theres something to show
    long displayable(long logicalUs) {
        long d = durationUs;
        if (d == NONE || logicalUs < d) return logicalUs;
        if (frameDurUs <= 0) return Math.max(0, d - 1);
        return Math.max(0, d - frameDurUs);
    }

    void tellPlayState() { onPlayState.accept(open, paused); }
}
