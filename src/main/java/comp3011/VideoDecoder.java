/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Takes over VideoPlayerModel.readNextVideoFrame and the decoder side of seeking from the single
 * threaded design, moving them off the JavaFX application thread.
 */
package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * The {@code video-decoder} thread: pulls frames out of FFmpeg as fast as the downstream buffers allow, routes sound
 * to the {@link AudioSink}, and dispatches each picture to the {@link FrameProcessingPipeline} - publishing a
 * {@link PresentationItem} per frame, <b>in decode order</b>, to a bounded presentation queue read by the display.
 *
 * <h2>Thread confinement of the decoder</h2>
 * <p>
 * {@link FFmpegFrameGrabber} is not thread-safe and, worse, reuses one frame buffer for every grab. So the grabber is
 * <i>confined</i> to this thread: after {@link #start()} no other thread touches it until {@link #close()} has joined
 * this thread. Requests from the JavaFX thread (seeks) arrive as messages on a queue rather than as direct calls, and
 * every picture is copied into a {@link FramePool} frame before it leaves this thread.
 * </p>
 *
 * <h2>Seeking and epochs</h2>
 * <p>
 * Every seek is tagged with a new, increasing <i>epoch</i>. Everything this thread publishes carries the epoch it was
 * decoded in, so consumers can recognise and discard work made stale by a later seek - including frames still in
 * flight in the worker pool at the moment of the seek (those skip their effects entirely; see {@link #isCurrent}).
 * Seeks arriving faster than they can be served are coalesced: only the newest matters.
 * </p>
 *
 * <h2>Backpressure</h2>
 * <p>
 * The presentation queue and the frame pool are both bounded, so when the display is paused, or effects are slower
 * than real time, this thread simply blocks instead of decoding the whole film into memory.
 * </p>
 *
 * @param <T> the rendered frame type produced by the pipeline
 */
public final class VideoDecoder<T> implements AutoCloseable {

    /** Receives decoded audio frames (on the decoder thread). */
    public interface AudioSink {
        void accept(long epoch, long timestampUs, Frame frame);
    }

    /** One entry of the presentation queue. */
    public sealed interface PresentationItem<T> permits DecodedFrame, EndOfStream, DecodeFailure {
        long epoch();
    }

    /** A picture, whose effects may still be being applied by a worker when it is published. */
    public record DecodedFrame<T>(
            long epoch,
            int frameNumber,
            long mediaTimestampUs,
            CompletableFuture<FrameProcessingPipeline.ProcessedFrame<T>> processed) implements PresentationItem<T> {
    }

    /** The decoder has reached the end of the media. */
    public record EndOfStream<T>(long epoch) implements PresentationItem<T> {
    }

    /** Decoding failed; playback cannot continue. */
    public record DecodeFailure<T>(long epoch, Exception error) implements PresentationItem<T> {
    }

    /** Stream-level facts needed to build {@link InfoVideo} for the effects. */
    public record StreamInfo(
            String mediaName,
            int totalVideoFrames,
            double frameRate,
            int intFrameRate,
            int frameDurationUs) {
    }

    private record SeekRequest(long epoch, long timestampUs) {
    }

    private final FFmpegFrameGrabber grabber;
    private final StreamInfo streamInfo;
    private final boolean grabAudio;
    private final FrameProcessingPipeline<T> pipeline;
    private final AudioSink audioSink;
    private final BlockingQueue<PresentationItem<T>> presentationQueue;
    private final int framePoolCapacity;
    private final BlockingQueue<SeekRequest> seekRequests = new LinkedBlockingQueue<>();
    private final Thread thread;

    // Written by the JavaFX thread on seek, read by worker threads: volatile for visibility.
    private volatile long requestedEpoch;
    private volatile boolean stopping;

    // Confined to the decoder thread.
    private long decodeEpoch;
    private FramePool framePool;

    /**
     * @param grabber an already started grabber; ownership passes to this decoder's thread on {@link #start()}
     * @param grabAudio grab audio as well as video (true if the media has sound)
     * @param initialEpoch the epoch of the first frames decoded
     * @param presentationQueue bounded queue the decoded frames are published to, in decode order
     */
    public VideoDecoder(
            FFmpegFrameGrabber grabber,
            StreamInfo streamInfo,
            boolean grabAudio,
            FrameProcessingPipeline<T> pipeline,
            AudioSink audioSink,
            BlockingQueue<PresentationItem<T>> presentationQueue,
            long initialEpoch) {
        this.grabber = grabber;
        this.streamInfo = streamInfo;
        this.grabAudio = grabAudio;
        this.pipeline = pipeline;
        this.audioSink = audioSink;
        this.presentationQueue = presentationQueue;
        // Enough frames that every queued presentation item can hold one while being processed, plus the one
        // being filled, so the pool itself never becomes the bottleneck.
        this.framePoolCapacity = presentationQueue.remainingCapacity() + 1;
        this.requestedEpoch = initialEpoch;
        this.decodeEpoch = initialEpoch;
        this.thread = new Thread(this::run, "video-decoder");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /** JavaFX thread: ask the decoder to continue from {@code timestampUs} under a new epoch. */
    public void seek(long epoch, long timestampUs) {
        requestedEpoch = epoch;
        seekRequests.offer(new SeekRequest(epoch, timestampUs));
    }

    /** Stops the decoder thread and waits for it to finish. Afterwards the grabber may be closed by the caller. */
    @Override
    public void close() {
        stopping = true;
        thread.interrupt();
        // A full presentation queue could be blocking a put; make room so the thread can see it is stopping.
        presentationQueue.clear();
        try {
            thread.join(5000);
            if (thread.isAlive()) {
                System.err.println("WARNING: video decoder did not stop within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isCurrent(long epoch) {
        return epoch == requestedEpoch;
    }

    private void run() {
        try {
            while (!stopping) {
                applyNewestSeek(seekRequests.poll());

                Frame frame = grabAudio ? grabber.grab() : grabber.grabImage();
                if (frame == null) {
                    publish(new EndOfStream<>(decodeEpoch));
                    // Nothing more to decode until the user seeks back into the media (or playback is closed).
                    applyNewestSeek(seekRequests.take());
                    continue;
                }

                long timestampUs = grabber.getTimestamp();
                if (frame.samples != null) {
                    audioSink.accept(decodeEpoch, timestampUs, frame);
                }
                if (frame.image != null) {
                    dispatchPicture(frame, timestampUs);
                }
            }
        } catch (InterruptedException e) {
            // Interrupted by close(): exit quietly.
        } catch (Exception e) {
            if (!stopping) {
                presentationQueue.offer(new DecodeFailure<>(decodeEpoch, e));
            }
        }
    }

    private void dispatchPicture(Frame frame, long timestampUs) throws Exception {
        if (framePool == null) {
            // First picture of this playback session: now we know its exact layout. Effects are initialised here,
            // on this thread, before any frame is submitted, so no worker can be running an effect yet; the executor
            // hand-off in submit() then makes the initialised state visible to the workers.
            pipeline.initialise(videoInfoFor(frame));
            framePool = new FramePool(frame, framePoolCapacity);
        } else if (!framePool.matches(frame)) {
            throw new Exception("Video frame layout changed mid-stream; this is not supported");
        }

        InfoFrame info = new InfoFrame(grabber.getFrameNumber(), timestampUs);
        long frameEpoch = decodeEpoch;
        Frame privateCopy = framePool.acquireCopyOf(frame);
        CompletableFuture<FrameProcessingPipeline.ProcessedFrame<T>> processed =
                pipeline.submit(privateCopy, info, framePool, () -> isCurrent(frameEpoch));
        publish(new DecodedFrame<>(frameEpoch, info.frameNumber(), timestampUs, processed));
    }

    private void applyNewestSeek(SeekRequest request) throws Exception {
        if (request == null) {
            return;
        }
        SeekRequest newest = request;
        for (SeekRequest next = seekRequests.poll(); next != null; next = seekRequests.poll()) {
            newest = next;
        }
        grabber.setTimestamp(newest.timestampUs());
        decodeEpoch = newest.epoch();
    }

    private void publish(PresentationItem<T> item) throws InterruptedException {
        presentationQueue.put(item); // Blocks while the display is this far behind: this is the backpressure.
    }

    private InfoVideo videoInfoFor(Frame frame) {
        return new InfoVideo(
                streamInfo.mediaName(),
                streamInfo.totalVideoFrames(),
                frame.imageWidth,
                frame.imageHeight,
                frame.imageDepth,
                frame.imageChannels,
                frame.imageStride,
                streamInfo.frameRate(),
                streamInfo.intFrameRate(),
                streamInfo.frameDurationUs(),
                grabber.getPixelFormat());
    }
}
