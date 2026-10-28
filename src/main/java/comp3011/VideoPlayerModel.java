/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *      - Re-engineered as the multi-threaded model. The original single threaded implementation now lives,
 *        unchanged in behaviour, in SingleThreadedVideoPlayerModel. Public behaviour (seeking rules, pause,
 *        start over, end of playback, audio toggling, status messages) is preserved from the original; decoding,
 *        effects and audio output have moved to PlaybackSession's threads, leaving this class to coordinate them
 *        and display frames on the JavaFX application thread.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture: the multi-threaded player.
 *
 * <p>
 * This class is the <b>coordinator</b>. It lives entirely on the JavaFX application thread: it receives user
 * commands from the {@link VideoPlayerController}, owns the {@link PlaybackClock}, and owns at most one
 * {@link PlaybackSession} - the bundle of threads that decode, apply effects and play sound. Its only per-frame
 * job is {@link #pumpPlayback(long)}: on each JavaFX heartbeat, put the next frame on screen if it is finished and
 * due. Everything expensive happens elsewhere, so the GUI stays responsive no matter how many effects are stacked.
 * See {@link PlaybackSession} for a diagram of the threads.
 * </p>
 *
 * <h2>Concurrency notes</h2>
 * <ul>
 * <li>All fields except {@link #audioOutputEnabled} are confined to the JavaFX thread, so need no locking.</li>
 * <li>{@link #audioOutputEnabled} is read by the decoder and audio threads, so it is {@code volatile}.</li>
 * <li>Cross-thread data flows only through thread-safe hand-offs: the session's blocking presentation queue,
 * {@link java.util.concurrent.CompletableFuture}s for processed frames, and the synchronised clock.</li>
 * <li>Seeks never wait for in-flight work. Instead a new <i>epoch</i> begins, and frames or audio from older
 * epochs are recognised and discarded wherever they turn up (see {@link VideoDecoder}).</li>
 * <li>Every frame stays on screen for a full frame period: if processing ever falls behind, the clock slips
 * rather than rushing through the backlog (which would make frames flash past unseen), and playback only starts
 * once a first batch of frames is ready (pre-roll).</li>
 * <li>Frames are displayed strictly in decode order, one at a time, so frame correctness does not depend on which
 * worker finishes first.</li>
 * </ul>
 */
public class VideoPlayerModel implements PlaybackModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    // Longest we wait for the pre-roll (see prerollComplete) before starting playback anyway.
    private static final long MAX_PREROLL_WAIT_NS = 2_000_000_000L;
    // How many frames per worker must be ready before playback starts; see prerollComplete.
    private static final int PREROLL_BATCHES = 2;

    private final List<FrameProcessor> frameProcessors;
    private final int workerCount;
    private final PlaybackClock clock = new PlaybackClock();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // The JavaFX heartbeat, as in the original: calls pumpPlayback once per pulse (typically 60 times a second).
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private PlaybackSession session; // The threads and resources of the current playback, or null.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled; // Read by decoder and audio threads.
    private long epoch; // Increments on every (re)start or seek; see PlaybackClock.
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private int intFrameRate;
    private long logicalPlaybackBaseUs;
    private long epochFirstMediaUs; // Media timestamp of the first frame shown in the current epoch.
    private long epochStartedNs; // When the current epoch began, for the pre-roll time limit.
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessorOption> frameProcessorOptions,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        // Effects are constructed here, once, on this single thread and in command-line order (see
        // FrameProcessorOption.instantiate for why the order and the single thread matter). They are shared by
        // every worker thread of every playback session.
        this.frameProcessors = FrameProcessorOption.instantiate(frameProcessorOptions);
        this.workerCount = FrameProcessingPipeline.defaultWorkerCount();
        // The constructor runs on the JavaFX Application Thread, once the toolkit is up: the moment to make sure the
        // threads that draw the frames always get CPU time when they need it.
        DisplayThreadPriority.boostDisplayThreads();
    }

    @Override
    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    @Override
    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    @Override
    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    @Override
    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    @Override
    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        long now = System.nanoTime();
        if (pauseRequested) {
            clock.pause(now);
            session.flushAudioDevice();
        } else {
            clock.resume(now);
        }

        notifyPlaybackStateChanged();
    }

    @Override
    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        if (session != null) {
            session.flushAudio();
        }
        notifyAudioOutputStateChanged();
    }

    @Override
    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    @Override
    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    @Override
    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        beginEpoch(logicalTimestampUs);
        session.seek(epoch, grabTimestampUs);
        notifyPlaybackStateChanged();
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        long now = System.nanoTime();
        if (pauseRequested) {
            clock.pause(now);
        } else {
            clock.resume(now);
        }

        long logicalStartUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        beginEpoch(logicalStartUs);
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            session = PlaybackSession.open(
                    videoFile,
                    startTimestampUs,
                    frameProcessors,
                    workerCount,
                    clock,
                    epoch,
                    () -> audioOutputEnabled);
            readMediaProperties(session);
            notifyVideoSizeChanged(session.imageWidth(), session.imageHeight());
            session.start();
            playbackOpen = true;
            playbackTimer.start();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void readMediaProperties(PlaybackSession openedSession) {
        double frameRate = openedSession.frameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = openedSession.frameDurationUs();
        long durationUs = openedSession.durationUs();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;
    }

    // Starts a new epoch at the given logical position: the clock stops until the first frame of the new epoch
    // is ready, and anything still in flight from earlier epochs will be discarded.
    private void beginEpoch(long logicalTimestampUs) {
        epoch++;
        clock.reset(epoch);
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        logicalPlaybackBaseUs = logicalTimestampUs;
        epochStartedNs = System.nanoTime();
    }

    // Called on every JavaFX heartbeat. Unlike the single threaded model this never decodes or processes - it only
    // looks at the head of the presentation queue, so it always returns control to the GUI within microseconds.
    // Frames are taken strictly in decode order; at most one is shown per heartbeat.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        BlockingQueue<VideoDecoder.PresentationItem<Image>> queue = session.presentationQueue();
        VideoDecoder.PresentationItem<Image> head = queue.peek();
        while (head != null && head.epoch() != epoch) {
            queue.poll(); // Left over from before a seek.
            head = queue.peek();
        }
        if (head == null) {
            return; // Decoder has not caught up yet (e.g. just after a seek).
        }

        switch (head) {
            case VideoDecoder.EndOfStream<Image> _ -> {
                queue.poll();
                finishPlayback();
            }
            case VideoDecoder.DecodeFailure<Image> failure -> handlePlaybackError(failure.error());
            case VideoDecoder.DecodedFrame<Image> frame -> presentIfDue(queue, frame, now);
        }
    }

    private void presentIfDue(
            BlockingQueue<VideoDecoder.PresentationItem<Image>> queue,
            VideoDecoder.DecodedFrame<Image> frame,
            long now) {
        if (!frame.processed().isDone()) {
            return; // Its effects are still being applied; it will be shown (late) on a following heartbeat.
        }

        if (!clock.isStarted()) {
            // First frame since start/seek. When paused, show it straight away, as the original did. Otherwise wait
            // for the pre-roll, then anchor the clock so this frame is due right now and later frames follow it.
            if (!pauseRequested && !prerollComplete(queue, now)) {
                return;
            }
            epochFirstMediaUs = frame.mediaTimestampUs();
            clock.startAt(frame.mediaTimestampUs(), now);
        } else if (!clock.isDue(frame.mediaTimestampUs(), now)) {
            return;
        } else {
            slipIfLate(frame, now);
        }

        queue.poll();
        FrameProcessingPipeline.ProcessedFrame<Image> processed;
        try {
            processed = frame.processed().join();
        } catch (CompletionException e) {
            handlePlaybackError(e.getCause() instanceof Exception cause ? cause : e);
            return;
        }
        if (processed != null) {
            displayFrame(frame, processed);
        }
    }

    // Pre-roll: start the clock only once the first PREROLL_BATCHES batches of frames - one frame per worker per
    // batch - are ready, not just the first frame. Each batch is processed in parallel, so this costs only a fraction
    // of a second, but it gives the display a cushion against a slow start (a busy machine, a cold JIT): otherwise
    // the first few frames can fall due before their workers have finished them. Gives up waiting after
    // MAX_PREROLL_WAIT_NS, or when the queue holds the end of the media.
    private boolean prerollComplete(BlockingQueue<VideoDecoder.PresentationItem<Image>> queue, long now) {
        if (now - epochStartedNs > MAX_PREROLL_WAIT_NS) {
            return true;
        }
        int wanted = Math.min(workerCount * PREROLL_BATCHES, queue.size() + queue.remainingCapacity());
        int ready = 0;
        for (VideoDecoder.PresentationItem<Image> item : queue) { // Weakly consistent iteration: thread-safe.
            if (item.epoch() != epoch) {
                continue;
            }
            if (!(item instanceof VideoDecoder.DecodedFrame<Image> frame)) {
                return true; // End of media (or an error) is already queued: nothing more to wait for.
            }
            if (!frame.processed().isDone()) {
                break;
            }
            if (++ready >= wanted) {
                return true;
            }
        }
        return false;
    }

    // A frame shown later than its slot must still stay on screen for a full frame period. If we instead raced
    // through the backlog to catch up, the frames behind it would each flash up for a single screen refresh and,
    // to a viewer (or a screen-capturing test), be dropped. So when we are more than half a frame late, the clock
    // slips back by the lateness: playback is momentarily delayed but every frame is shown properly. (Lateness under
    // half a frame is normal: frames can only change on a screen refresh, which at 60Hz is every 16.7ms.)
    private void slipIfLate(VideoDecoder.DecodedFrame<Image> frame, long now) {
        long frameDurationNs = videoFrameDurationUs * 1_000L;
        long lateNs = now - clock.targetTimeNs(frame.mediaTimestampUs());
        if (frameDurationNs > 0 && lateNs > frameDurationNs / 2) {
            clock.delay(lateNs);
        }
    }

    private void displayFrame(
            VideoDecoder.DecodedFrame<Image> frame,
            FrameProcessingPipeline.ProcessedFrame<Image> processed) {
        currentTimestampUs = logicalPlaybackBaseUs + Math.max(0, frame.mediaTimestampUs() - epochFirstMediaUs);
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored. As in
        // the original, headroom is how long before its display deadline the frame was ready. Because frames are now
        // processed ahead of time this can exceed one frame period - the buffered count shows how far ahead.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (clock.targetTimeNs(frame.mediaTimestampUs()) - processed.readyAtNs()) / 1_000L;
            String colour = remainingUs < 0 ? "\u001B[31m" : "\u001B[32m";
            System.out.printf("Frame headroom is %s%5dus\u001B[0m out of %dus spare. %d frames buffered on %d threads.%n",
                    colour,
                    remainingUs,
                    videoFrameDurationUs,
                    session.bufferedFrameCount(),
                    workerCount);
        }

        notifyFrameReady(processed.output());
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        if (session != null) {
            session.close(); // Joins the session's threads before returning.
            session = null;
        }
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }
}
