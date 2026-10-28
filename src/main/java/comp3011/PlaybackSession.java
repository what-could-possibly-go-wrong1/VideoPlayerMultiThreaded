/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Takes over openPlaybackResources / closePlaybackResources from the single threaded design.
 */
// groups playback resources and worker threads so startup, seeking and shutdown have one owner instead of being scattered through the model.
package comp3011;

import java.io.File;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BooleanSupplier;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.scene.image.Image;

/**
 * Everything that exists for one continuous run of the media file - from opening it until stop, error or end of
 * playback - and the threads that work on it:
 *
 * <pre>
 *                      +--------------------+   AudioChunk    +------------------+
 *                 +--->|   audio samples    |---------------->|  audio-output    |---> speakers
 *                 |    +--------------------+   (queue)       |  thread          |
 *  +-----------------+                                        +------------------+
 *  |  video-decoder  |   pooled frame copy   +-----------------------+                   ^
 *  |  thread         |---------------------->|  frame-worker-N       |  effects + Image  |  PlaybackClock
 *  |  (owns FFmpeg)  |       (executor)      |  threads (one per     |------+            |  (shared)
 *  +-----------------+                       |  core)                |      |            v
 *           |                                +-----------------------+      |   +------------------+
 *           |  DecodedFrame(future), in decode order                        +-->|  JavaFX thread   |---> screen
 *           +---------------------------------------------------------------->  |  (display, GUI)  |
 *                         bounded presentation queue                           +------------------+
 * </pre>
 *
 * <p>
 * The JavaFX application thread, which owns this object, does no decoding, no effects and no audio: it only takes
 * finished frames off the head of the presentation queue when they are due and hands them to the view, which keeps
 * the GUI responsive whatever the effect load.
 * </p>
 *
 * <p>
 * All public methods are called on the JavaFX application thread.
 * </p>
 */
final class PlaybackSession implements AutoCloseable {
    // Frames decoded ahead of display, per worker thread. Two per worker keeps every core busy while also absorbing
    // variation in per-frame effect cost; the cap bounds memory use (each buffered HD frame is several MB).
    private static final int BUFFERED_FRAMES_PER_WORKER = 2;
    private static final int MIN_BUFFERED_FRAMES = 6;
    private static final int MAX_BUFFERED_FRAMES = 24; // About one second of 24 fps video.

    private final FFmpegFrameGrabber grabber;
    private final FrameProcessingPipeline<Image> pipeline;
    private final AudioOutput audioOutput; // null when the media has no sound track
    private final VideoDecoder<Image> decoder;
    private final BlockingQueue<VideoDecoder.PresentationItem<Image>> presentationQueue;

    private final int imageWidth;
    private final int imageHeight;
    private final double frameRate;
    private final int frameDurationUs;
    private final long durationUs;

    private PlaybackSession(
            FFmpegFrameGrabber grabber,
            String mediaName,
            List<FrameProcessor> frameProcessors,
            int workerCount,
            PlaybackClock clock,
            long epoch,
            BooleanSupplier audioEnabled) throws Exception {
        this.grabber = grabber;
        imageWidth = grabber.getImageWidth();
        imageHeight = grabber.getImageHeight();
        frameRate = grabber.getFrameRate();
        frameDurationUs = frameRate > 0 ? (int) Math.round(1_000_000.0 / frameRate) : 0;
        durationUs = grabber.getLengthInTime();

        // JavaFXFrameConverter holds no state (it copies the pixels into a new WritableImage), so one instance can be
        // shared by all workers; creating Images off the JavaFX thread is permitted - only showing them is not.
        JavaFXFrameConverter converter = new JavaFXFrameConverter();
        pipeline = new FrameProcessingPipeline<>(frameProcessors, workerCount, converter::convert);

        int bufferedFrames = Math.clamp(
                (long) workerCount * BUFFERED_FRAMES_PER_WORKER, MIN_BUFFERED_FRAMES, MAX_BUFFERED_FRAMES);
        presentationQueue = new ArrayBlockingQueue<>(bufferedFrames);

        boolean hasAudio = grabber.hasAudio();
        AudioPlayer audioPlayer = hasAudio ? openAudioPlayer(grabber) : null;
        VideoDecoder.AudioSink audioSink;
        if (audioPlayer != null) {
            audioOutput = new AudioOutput(audioPlayer, clock, audioEnabled);
            audioSink = audioOutput;
        } else {
            audioOutput = null;
            audioSink = (_, _, _) -> {
            };
        }

        VideoDecoder.StreamInfo streamInfo = new VideoDecoder.StreamInfo(
                mediaName,
                grabber.getLengthInVideoFrames(),
                frameRate,
                (int) Math.round(frameRate),
                frameDurationUs);
        decoder = new VideoDecoder<>(grabber, streamInfo, hasAudio, pipeline, audioSink, presentationQueue, epoch);
    }

    /**
     * Opens the media and prepares (but does not start) the session's threads.
     *
     * @param startTimestampUs where to start decoding
     * @param epoch the clock epoch the first decoded frames belong to
     */
    static PlaybackSession open(
            File videoFile,
            long startTimestampUs,
            List<FrameProcessor> frameProcessors,
            int workerCount,
            PlaybackClock clock,
            long epoch,
            BooleanSupplier audioEnabled) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();
        try {
            if (startTimestampUs > 0) {
                grabber.setTimestamp(startTimestampUs);
            }
            return new PlaybackSession(
                    grabber, mediaName(videoFile), frameProcessors, workerCount, clock, epoch, audioEnabled);
        } catch (Exception e) {
            closeQuietly(grabber);
            throw e;
        }
    }

    /** Starts the decoder and audio threads. From here on the grabber belongs to the decoder thread. */
    void start() {
        if (audioOutput != null) {
            audioOutput.start();
        }
        decoder.start();
    }

    /** Continue playback from {@code timestampUs}; everything from earlier epochs is discarded. */
    void seek(long epoch, long timestampUs) {
        decoder.seek(epoch, timestampUs);
        presentationQueue.clear(); // Stale anyway, and frees the decoder if it is blocked on a full queue.
        if (audioOutput != null) {
            audioOutput.flush();
        }
    }

    /** Silences sound already sent to the device, e.g. on pause. Upcoming sound stays queued. */
    void flushAudioDevice() {
        if (audioOutput != null) {
            audioOutput.flushDevice();
        }
    }

    /** Drops all pending sound, e.g. when audio is muted. */
    void flushAudio() {
        if (audioOutput != null) {
            audioOutput.flush();
        }
    }

    BlockingQueue<VideoDecoder.PresentationItem<Image>> presentationQueue() {
        return presentationQueue;
    }

    int imageWidth() {
        return imageWidth;
    }

    int imageHeight() {
        return imageHeight;
    }

    double frameRate() {
        return frameRate;
    }

    int frameDurationUs() {
        return frameDurationUs;
    }

    long durationUs() {
        return durationUs;
    }

    int bufferedFrameCount() {
        return presentationQueue.size();
    }

    /**
     * Stops every thread of this session, then releases its resources. The order matters: the decoder thread must
     * have finished before its grabber is closed (it may be inside a native grab call), and the workers must have
     * finished before the effects can be safely re-initialised by a following session.
     */
    @Override
    public void close() {
        decoder.close();
        pipeline.close();
        if (audioOutput != null) {
            audioOutput.close();
        }
        presentationQueue.clear();
        closeQuietly(grabber);
    }

    // A machine without a usable sound device (e.g. a headless test server) should still play the video, silently,
    // rather than failing playback altogether.
    private static AudioPlayer openAudioPlayer(FFmpegFrameGrabber grabber) {
        AudioPlayer audioPlayer = new AudioPlayer();
        try {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
            return audioPlayer;
        } catch (Exception | LinkageError e) {
            System.err.println("WARNING: no audio output available, playing without sound: " + e.getMessage());
            audioPlayer.close();
            return null;
        }
    }

    private static void closeQuietly(FFmpegFrameGrabber grabber) {
        try {
            grabber.stop();
        } catch (Exception e) {
            // The resource is being closed; there is no useful recovery action.
        }
        try {
            grabber.close();
        } catch (Exception e) {
            // The resource is being closed; there is no useful recovery action.
        }
    }

    private static String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }
}
