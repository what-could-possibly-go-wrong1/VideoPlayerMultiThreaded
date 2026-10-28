/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Generalises the playbackStartNs / firstTimestampUs / pauseStartedNs bookkeeping of the original
 * single threaded model so it can be shared safely between the display and audio threads.
 */
// tracks playback time independently from effect processing so frames can be presented in order without using worker completion time as the timeline.
package comp3011;

/**
 * Maps media timestamps to wall-clock time for one playback run, and is the single point of agreement between the
 * threads that must stay in sync: the JavaFX application thread (which puts video frames on screen) and the
 * {@link AudioOutput} thread (which writes sound to the speakers).
 *
 * <h2>Lifecycle</h2>
 * <ol>
 * <li>{@link #reset(long)} - at the start of playback and on every seek. Each reset opens a new <i>epoch</i>: work
 * tagged with an older epoch is stale and must be discarded.</li>
 * <li>{@link #startAt(long, long)} - when the first frame of the epoch is ready for display. Anchoring the clock to
 * the moment the first processed frame is <i>available</i> (rather than when decoding started) means the processing
 * pipeline gets to pre-roll before the deadline clock starts ticking.</li>
 * <li>{@link #pause(long)} / {@link #resume(long)} - shifts the anchor by the paused duration.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>
 * Every method is {@code synchronized} on this object; the state is small and the critical sections are a handful
 * of arithmetic operations, so contention is negligible. State changes call {@link #notifyAll()} so the audio thread
 * blocked in {@link #awaitDue(long, long, long)} re-evaluates immediately on pause, resume, start or seek, rather
 * than oversleeping.
 * </p>
 */
public final class PlaybackClock {
    private long epoch;
    private boolean started;
    private boolean paused;
    private long anchorMediaUs;
    private long anchorNs;
    private long pauseStartedNs;

    /** Begins a new epoch: the clock stops until the first frame of the new epoch is shown. */
    public synchronized void reset(long newEpoch) {
        epoch = newEpoch;
        started = false;
        notifyAll();
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** Anchors media time {@code mediaUs} to wall-clock {@code nowNs}. */
    public synchronized void startAt(long mediaUs, long nowNs) {
        anchorMediaUs = mediaUs;
        anchorNs = nowNs;
        started = true;
        if (paused) {
            pauseStartedNs = nowNs;
        }
        notifyAll();
    }

    public synchronized void pause(long nowNs) {
        if (paused) {
            return;
        }
        paused = true;
        pauseStartedNs = nowNs;
        notifyAll();
    }

    public synchronized void resume(long nowNs) {
        if (!paused) {
            return;
        }
        paused = false;
        if (started) {
            anchorNs += nowNs - pauseStartedNs;
        }
        notifyAll();
    }

    /**
     * Shifts the whole schedule later by {@code delayNs}. Used when a frame is shown late, so that the frames after
     * it keep their full display time instead of being rushed through (see VideoPlayerModel.slipIfLate).
     */
    public synchronized void delay(long delayNs) {
        anchorNs += delayNs;
        notifyAll();
    }

    /** The media timestamp the clock was most recently anchored to. */
    public synchronized long anchorMediaUs() {
        return anchorMediaUs;
    }

    /** When a frame with the given media timestamp should be on screen. Only meaningful once started. */
    public synchronized long targetTimeNs(long mediaUs) {
        return anchorNs + (mediaUs - anchorMediaUs) * 1_000L;
    }

    /** True if the clock is running (started and not paused) and {@code mediaUs} is due at {@code nowNs}. */
    public synchronized boolean isDue(long mediaUs, long nowNs) {
        return started && !paused && targetTimeNs(mediaUs) <= nowNs;
    }

    /**
     * Blocks the calling thread until media time {@code mediaUs} is due, less {@code leadNs}, with the clock running.
     *
     * @return {@code true} when due; {@code false} if the clock moved on to another epoch while waiting, meaning the
     *         caller's data is stale and must be dropped.
     */
    public synchronized boolean awaitDue(long expectedEpoch, long mediaUs, long leadNs) throws InterruptedException {
        while (true) {
            if (epoch != expectedEpoch) {
                return false;
            }
            if (!started || paused) {
                wait();
                continue;
            }
            long waitNs = targetTimeNs(mediaUs) - leadNs - System.nanoTime();
            if (waitNs <= 0) {
                return true;
            }
            // Timed wait: wakes when due, or earlier if pause/seek/reset notifies us.
            wait(Math.max(1, waitNs / 1_000_000L), (int) (waitNs % 1_000_000L));
        }
    }
}
