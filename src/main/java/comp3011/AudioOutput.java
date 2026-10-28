/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Replaces the queueAudio / writeDueAudio cooperative scheduling of the single threaded model.
 */
// keeps audio output behind its own interface so the rest of playback doesn't need to know the device details.
package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BooleanSupplier;

import org.bytedeco.javacv.Frame;

/**
 * Plays a media file's sound track on its own dedicated thread, kept in sync with the video by the shared
 * {@link PlaybackClock}.
 *
 * <p>
 * In the single threaded player, audio was written from the GUI heartbeat using non-blocking writes, so any stall of
 * the GUI thread (heavy effects, window dragging or resizing) was immediately audible as a gap. Here a dedicated
 * {@code audio-output} thread waits on the clock until each chunk is due and then performs ordinary blocking writes -
 * blocking is now harmless because nothing else runs on this thread.
 * </p>
 *
 * <h2>Threads and shared state</h2>
 * <ul>
 * <li><b>Decoder thread</b> calls {@link #accept}: converts samples (using the {@link AudioPlayer}'s conversion
 * buffer, which only the decoder thread touches) and enqueues an immutable {@link AudioChunk}.</li>
 * <li><b>Audio thread</b> takes chunks in order, waits until due, and writes them to the line.</li>
 * <li><b>JavaFX thread</b> calls {@link #flush()} on pause/seek/mute and {@link #close()} at the end.</li>
 * </ul>
 * <p>
 * The hand-off is a {@link LinkedBlockingQueue} (thread-safe, and it gives happens-before from producer to
 * consumer). Chunks are tagged with the clock epoch in which they were decoded; after a seek the clock's epoch moves
 * on and stale chunks are dropped instead of being played over the new position.
 * </p>
 */
public final class AudioOutput implements AutoCloseable, VideoDecoder.AudioSink {
    // Write audio slightly ahead of its due time so the sound device never runs dry (same lead as the original).
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    // Write in modest slices so a pause or seek takes effect within a few milliseconds of sound.
    private static final int WRITE_SLICE_BYTES = 4096;

    private final AudioPlayer audioPlayer;
    private final PlaybackClock clock;
    private final BooleanSupplier audioEnabled;
    private final BlockingQueue<AudioChunk> chunks = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean stopping;

    public AudioOutput(AudioPlayer audioPlayer, PlaybackClock clock, BooleanSupplier audioEnabled) {
        this.audioPlayer = audioPlayer;
        this.clock = clock;
        this.audioEnabled = audioEnabled;
        this.thread = new Thread(this::run, "audio-output");
        this.thread.setDaemon(true);
        // Above normal: very little CPU, but must be prompt - a late write is an audible gap.
        this.thread.setPriority(Thread.NORM_PRIORITY + 2);
    }

    public void start() {
        thread.start();
    }

    /** Decoder thread: queue this frame's samples for playback. */
    @Override
    public void accept(long epoch, long timestampUs, Frame frame) {
        if (!audioEnabled.getAsBoolean()) {
            return;
        }
        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            chunks.offer(new AudioChunk(epoch, timestampUs, samples));
        }
    }

    /** Discards sound already handed to the device (so pausing is instant). Queued chunks are kept. */
    public void flushDevice() {
        audioPlayer.flush();
    }

    /** Discards both queued chunks and sound already handed to the device (seek or mute). */
    public void flush() {
        chunks.clear();
        audioPlayer.flush();
    }

    @Override
    public void close() {
        stopping = true;
        thread.interrupt();
        audioPlayer.flush(); // Unblocks a write in progress.
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        chunks.clear();
        audioPlayer.close();
    }

    private void run() {
        try {
            while (!stopping) {
                play(chunks.take());
            }
        } catch (InterruptedException e) {
            // close() was called; fall out and let the thread end.
        }
    }

    private void play(AudioChunk chunk) throws InterruptedException {
        int offset = 0;
        while (offset < chunk.samples().length) {
            if (!audioEnabled.getAsBoolean()) {
                return;
            }
            // Re-checked before every slice, so we stop promptly on pause (waits) or seek (stale - dropped).
            if (!clock.awaitDue(chunk.epoch(), chunk.timestampUs(), AUDIO_LEAD_NS)) {
                return;
            }
            int length = Math.min(WRITE_SLICE_BYTES, chunk.samples().length - offset);
            int written = audioPlayer.writeBlocking(chunk.samples(), offset, length);
            if (written <= 0) {
                return; // Line closed or flushed under us.
            }
            offset += written;
        }
    }

    private record AudioChunk(long epoch, long timestampUs, byte[] samples) {
    }
}
