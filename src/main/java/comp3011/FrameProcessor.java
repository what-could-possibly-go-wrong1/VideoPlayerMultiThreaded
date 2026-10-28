/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *      - Documented the thread-safety contract that effects must meet for the multi-threaded player.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.bytedeco.javacv.Frame;

/**
 * Base class for all the video player's frame-based compositing effects.
 *
 * <p>
 * The playback model initialises each processor once with video metadata,
 * then invokes {@link #process(Frame, InfoFrame)} for every image frame in a set order
 * before it is converted for display. Note that all FrameProcessors should be written
 * to be deterministic - that is they apply the same effect to the same frame number of
 * the same media name, but otherwise can look 'random'. This allows for the reliable
 * determination of accurately applied effects in the correct order free of any race
 * conditions, frame overwrites or frame tearing etc. Some helper methods are provided
 * for this.
 * </p>
 *
 * <h2>Thread-safety contract (multi-threaded player)</h2>
 * <p>
 * The {@link FrameProcessingPipeline} processes several frames at once, on different worker threads, and every
 * worker shares the <i>same</i> effect instances. So implementations must obey these rules:
 * </p>
 * <ol>
 * <li><b>Construction</b> happens on a single thread, in command-line order (several effects number their instances
 * from a static, unsynchronised {@code nextInstanceIndex}; the stacking order determines each instance's random
 * seed). See {@link FrameProcessorOption#instantiate}.</li>
 * <li><b>{@link #initialise(InfoVideo)}</b> is never called concurrently with {@code process}. It may build any
 * fields it likes; those fields must be treated as read-only afterwards. Safe publication to the workers is provided
 * by the executor hand-off that follows initialisation.</li>
 * <li><b>{@link #process(Frame, InfoFrame)}</b> may be called concurrently from several threads, each with a
 * different frame. It may freely modify the frame it was given (that frame is owned by the calling thread) but must
 * not write to shared fields. Per-frame working storage must be local to the call, or per-thread (e.g. via
 * {@link ThreadLocal}) when it is too large to allocate on every frame.</li>
 * <li>Output must depend only on the frame, its {@link InfoFrame} and the initialised state - never on which thread
 * runs it or in what order frames arrive. That is what makes multi-threaded output pixel-identical to single
 * threaded output.</li>
 * </ol>
 */
public abstract class FrameProcessor {
    protected InfoVideo videoInfo;

    public FrameProcessor() {
    }

    public void initialise(InfoVideo videoInfo) throws Exception {
        this.videoInfo = videoInfo;
    }

    // Obtain a unique seed for this media name and effect instance index. That is, for a given media name, say
    // 'movie.mp4' and a given position in the list of effects being applied, this will return a unique and different
    // seed for each effect. This is useful for creating the illusion of randomness whilst keeping everything
    // deterministic.
    protected int saltedMediaSeed(String mediaName, int instanceIndex) {
        return hashMediaName(mediaName) + instanceIndex;
    }

    private int hashMediaName(String mediaName) {
        CRC32 crc = new CRC32();
        byte[] bytes = mediaName.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    public abstract void process(Frame frame, InfoFrame info) throws Exception;
}
