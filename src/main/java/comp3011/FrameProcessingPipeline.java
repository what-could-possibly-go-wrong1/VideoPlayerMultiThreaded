/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Replaces VideoPlayerModel.processFrame / converter.convert from the single threaded design.
 */
package comp3011;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.bytedeco.javacv.Frame;

/**
 * Applies the ordered chain of {@link FrameProcessor} effects to video frames on a pool of worker threads, and
 * renders each finished frame into the form the consumer wants (a JavaFX {@code Image} for the player, or a pixel
 * hash in the tests).
 *
 * <h2>Design: frame-level data parallelism</h2>
 * <p>
 * Each submitted frame becomes one task that runs <i>the whole effect chain, in command-line order</i>, on one worker
 * thread. Different frames are processed simultaneously on different cores. This was chosen over the alternatives:
 * </p>
 * <ul>
 * <li><b>Task (effect) pipelining</b> - one thread per effect, frames flowing between them - is limited by the
 * slowest effect (e.g. bleeding is many times slower than black-and-white), cannot use more cores than there are
 * effects, and costs a hand-off per effect per frame.</li>
 * <li><b>Intra-frame tiling</b> - splitting one frame into strips - does not work for effects that read
 * neighbouring pixels (jitter, bleed blur) without rewriting them, and synchronises every effect.</li>
 * </ul>
 * <p>
 * Frame-level parallelism scales with the number of cores, leaves effect code nearly untouched, and keeps the exact
 * per-frame order of effects - so output is pixel-identical to the single threaded player. It adds latency (a frame
 * takes the full chain time to emerge), which the player hides by buffering ahead and by anchoring its clock only
 * once the first frame is ready (see {@link PlaybackClock}).
 * </p>
 *
 * <h2>Ordering</h2>
 * <p>
 * Tasks may finish out of order. {@link #submit} returns a {@link CompletableFuture} immediately, and the caller
 * keeps the futures in decode order (the player uses a FIFO presentation queue) and consumes them in that order. This
 * re-sequences frames without any locking in this class.
 * </p>
 *
 * <h2>Thread-safety requirements on effects</h2>
 * <p>
 * Because two workers may call {@code process} on the <i>same</i> effect instance for different frames at the same
 * time, every {@link FrameProcessor} must be safe for concurrent {@code process} calls once initialised (see the
 * contract on {@link FrameProcessor}). {@link #initialise(InfoVideo)} must only be called while no tasks are
 * running; the player calls it once, from the decoder thread, before the first submission.
 * </p>
 *
 * @param <T> what each processed frame is rendered into
 */
public final class FrameProcessingPipeline<T> implements AutoCloseable {
    private final List<FrameProcessor> processors;
    private final Function<Frame, T> renderer;
    private final ExecutorService workers;
    private final int workerCount;

    /**
     * @param processors effects in application order; shared, not copied
     * @param workerCount number of worker threads
     * @param renderer converts a processed frame into its output; it must copy what it needs, because the frame is
     *        recycled as soon as the renderer returns
     */
    public FrameProcessingPipeline(List<FrameProcessor> processors, int workerCount, Function<Frame, T> renderer) {
        this.processors = List.copyOf(processors);
        this.renderer = renderer;
        this.workerCount = Math.max(1, workerCount);
        this.workers = Executors.newFixedThreadPool(this.workerCount, new WorkerThreadFactory());
    }

    /** A sensible default: one worker per available core. */
    public static int defaultWorkerCount() {
        return Runtime.getRuntime().availableProcessors();
    }

    public int workerCount() {
        return workerCount;
    }

    /** Initialises every effect for the given video. Must not be called while frames are being processed. */
    public void initialise(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : processors) {
            processor.initialise(videoInfo);
        }
    }

    /**
     * Queues a frame for processing and rendering.
     *
     * @param frame a frame owned by the caller, normally from {@code pool}; ownership passes to the worker
     * @param info per-frame information passed to every effect
     * @param pool where the frame is returned once rendered (may be {@code null} if the frame is not pooled)
     * @param stillWanted checked just before work starts; if it returns false (e.g. the user seeked away while the
     *        frame was queued) the effects are skipped and the future completes with {@code null}
     */
    public CompletableFuture<ProcessedFrame<T>> submit(
            Frame frame, InfoFrame info, FramePool pool, BooleanSupplier stillWanted) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!stillWanted.getAsBoolean()) {
                    return null;
                }
                applyEffects(frame, info);
                T rendered = renderer.apply(frame);
                return new ProcessedFrame<>(rendered, System.nanoTime());
            } catch (Exception e) {
                throw new FrameProcessingException(info, e);
            } finally {
                if (pool != null) {
                    pool.release(frame);
                }
            }
        }, workers);
    }

    /** Overload for callers that always want the frame processed. */
    public CompletableFuture<ProcessedFrame<T>> submit(Frame frame, InfoFrame info, FramePool pool) {
        return submit(frame, info, pool, () -> true);
    }

    // Runs on a worker thread. Exactly the loop the single threaded model ran inline.
    private void applyEffects(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : processors) {
            processor.process(frame, info);
        }
    }

    /**
     * Stops the workers and waits for any frame still being processed to finish, so that on return no effect is
     * running (making it safe to re-initialise the effects for a new playback session).
     */
    @Override
    public void close() {
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("WARNING: frame workers did not stop within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A rendered frame plus the time processing finished, for real time headroom tracing. */
    public record ProcessedFrame<T>(T output, long readyAtNs) {
    }

    /** Wraps an effect failure with the frame it happened on. */
    public static final class FrameProcessingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FrameProcessingException(InfoFrame info, Exception cause) {
            super("Frame " + info.frameNumber() + ": " + cause.getMessage(), cause);
        }
    }

    // Named daemon threads: easy to identify in a debugger/profiler, and never keep the JVM alive on exit.
    private static final class WorkerThreadFactory implements ThreadFactory {
        private static final AtomicInteger POOL_NUMBER = new AtomicInteger(1);
        private final int poolNumber = POOL_NUMBER.getAndIncrement();
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "frame-worker-" + poolNumber + "-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
