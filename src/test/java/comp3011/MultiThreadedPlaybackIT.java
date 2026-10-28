/*
 * ATTRIBUTION HEADING
 * Authors:
 *   1. In some files: Simon Ratcliffe, in collaboration with GPT-5.6 Terra 
 *   2. Loran Webber a1826273, production helped by Claude, which did:
 *   (a) File structure with project folder (correct nesting)
 *   (b) Debugging after multiple failed TITAN tests with no way fowards
 *   (c) Final check of all SOLID principles that may have been missed after own review
 *   (d) Formatting this header structure, and for comments that had visual/ascii components
 *   (e) ReadMe file writeup
 *   Claude was not used in any other way
 *
 * New file. Integration tests, run by "mvn verify" (maven-failsafe-plugin).
 */
// checks the real decoder and pipeline deliver every frame in order and identical to the single threaded reference.
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.Test;

/**
 * End-to-end frame accuracy of the multi-threaded decode/effects path on a real (generated) media file with sound,
 * compared against the single threaded reference algorithm (the same grab/initialise/process loop as
 * {@link SingleThreadedVideoPlayerModel}). Covers in-order delivery, end of stream, audio routing and seeking.
 */
class MultiThreadedPlaybackIT {
    private static final String EFFECTS = "nssnfwyvdjmbp";
    private static final int FRAMES = 60;

    private static File video() throws Exception {
        return TestFrames.generateVideo(new File("target/test-media/accuracy.mp4"), 480, 270, FRAMES, true);
    }

    @Test
    void everyFrameMatchesTheSingleThreadedReferenceAndArrivesInOrder() throws Exception {
        List<FrameProcessor> processors = FrameProcessingPipelineTest.effects(EFFECTS);
        List<String> reference = ReferencePlayer.decodeAndProcess(video(), processors, 0, Integer.MAX_VALUE);
        assertEquals(FRAMES, reference.size());

        AtomicInteger audioChunks = new AtomicInteger();
        try (Harness harness = new Harness(video(), processors, 4, (epoch, ts, frame) -> audioChunks.incrementAndGet())) {
            List<String> multiThreaded = harness.takeFrames(1, Integer.MAX_VALUE);
            assertEquals(reference, multiThreaded);
            assertTrue(harness.lastItem instanceof VideoDecoder.EndOfStream, "end of stream must be signalled");
        }
        assertTrue(audioChunks.get() > 0, "sound frames must be routed to the audio sink");
    }

    @Test
    void afterASeekOnlyFramesFromTheNewPositionAreDelivered() throws Exception {
        List<FrameProcessor> processors = FrameProcessingPipelineTest.effects(EFFECTS);
        long seekToUs = 1_000_000L;
        List<String> reference = ReferencePlayer.decodeAndProcess(video(), processors, seekToUs, 12);

        try (Harness harness = new Harness(video(), processors, 4, (epoch, ts, frame) -> {
        })) {
            harness.takeFrames(1, 5); // Play a little, then seek while frames are in flight.
            harness.decoder.seek(2, seekToUs);
            assertEquals(reference, harness.takeFrames(2, 12));
        }
    }

    /** Runs the real VideoDecoder + FrameProcessingPipeline, consuming the presentation queue like the display. */
    private static final class Harness implements AutoCloseable {
        final FFmpegFrameGrabber grabber;
        final FrameProcessingPipeline<String> pipeline;
        final BlockingQueue<VideoDecoder.PresentationItem<String>> queue = new ArrayBlockingQueue<>(8);
        final VideoDecoder<String> decoder;
        VideoDecoder.PresentationItem<String> lastItem;

        Harness(File video, List<FrameProcessor> processors, int workers, VideoDecoder.AudioSink audio)
                throws Exception {
            grabber = ReferencePlayer.openGrabber(video);
            pipeline = new FrameProcessingPipeline<>(processors, workers, TestFrames::sha1);
            decoder = new VideoDecoder<>(grabber, ReferencePlayer.streamInfo(video, grabber), grabber.hasAudio(),
                    pipeline, audio, queue, 1);
            decoder.start();
        }

        /** Takes up to {@code count} frames of {@code epoch} (skipping stale ones), stopping at end of stream. */
        List<String> takeFrames(long epoch, int count) throws Exception {
            List<String> hashes = new ArrayList<>();
            while (hashes.size() < count) {
                VideoDecoder.PresentationItem<String> item = queue.poll(30, TimeUnit.SECONDS);
                lastItem = item;
                if (item == null) {
                    throw new AssertionError("decoder stalled");
                }
                if (item.epoch() != epoch) {
                    continue; // Stale: from before a seek.
                }
                if (item instanceof VideoDecoder.DecodedFrame<String> frame) {
                    hashes.add(frame.processed().get(30, TimeUnit.SECONDS).output());
                } else if (item instanceof VideoDecoder.DecodeFailure<String> failure) {
                    throw new AssertionError("decode failed", failure.error());
                } else {
                    break;
                }
            }
            return hashes;
        }

        @Override
        public void close() {
            decoder.close();
            pipeline.close();
            try {
                grabber.stop();
                grabber.close();
            } catch (FrameGrabber.Exception e) {
                // Closing anyway; nothing useful to do.
            }
        }
    }

    /** The single threaded reference: SingleThreadedVideoPlayerModel's decode + effects loop, minus the GUI. */
    static final class ReferencePlayer {
        private ReferencePlayer() {
        }

        static FFmpegFrameGrabber openGrabber(File video) throws Exception {
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video);
            grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();
            return grabber;
        }

        static VideoDecoder.StreamInfo streamInfo(File video, FFmpegFrameGrabber grabber) {
            String name = video.getName();
            return new VideoDecoder.StreamInfo(name.substring(0, name.lastIndexOf('.')),
                    grabber.getLengthInVideoFrames(), grabber.getFrameRate(),
                    (int) Math.round(grabber.getFrameRate()), (int) Math.round(1_000_000.0 / grabber.getFrameRate()));
        }

        static List<String> decodeAndProcess(File video, List<FrameProcessor> processors, long startUs, int limit)
                throws Exception {
            FFmpegFrameGrabber grabber = openGrabber(video);
            try {
                if (startUs > 0) {
                    grabber.setTimestamp(startUs);
                }
                VideoDecoder.StreamInfo stream = streamInfo(video, grabber);
                boolean initialised = false;
                List<String> hashes = new ArrayList<>();
                while (hashes.size() < limit) {
                    Frame frame = grabber.hasAudio() ? grabber.grab() : grabber.grabImage();
                    if (frame == null) {
                        break;
                    }
                    long timestampUs = grabber.getTimestamp();
                    if (frame.image == null) {
                        continue;
                    }
                    if (!initialised) {
                        InfoVideo info = new InfoVideo(stream.mediaName(), stream.totalVideoFrames(),
                                frame.imageWidth, frame.imageHeight, frame.imageDepth, frame.imageChannels,
                                frame.imageStride, stream.frameRate(), stream.intFrameRate(),
                                stream.frameDurationUs(), grabber.getPixelFormat());
                        for (FrameProcessor processor : processors) {
                            processor.initialise(info);
                        }
                        initialised = true;
                    }
                    InfoFrame info = new InfoFrame(grabber.getFrameNumber(), timestampUs);
                    for (FrameProcessor processor : processors) {
                        processor.process(frame, info);
                    }
                    hashes.add(TestFrames.sha1(frame));
                }
                return hashes;
            } finally {
                grabber.stop();
                grabber.close();
            }
        }
    }
}
