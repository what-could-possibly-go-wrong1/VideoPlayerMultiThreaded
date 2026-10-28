// checks frame processing pipeline test. These tests target a specific behaviour instead of relying on a large collection of unrelated assertions.
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Concurrency correctness of the effects + worker pool, on synthetic frames (fast, and needs no video decoder).
 *
 * <p>
 * The central property of the multi threaded design is that it is a pure performance optimisation: for the same
 * effects in the same order, every frame must be <b>pixel identical</b> to sequential, single threaded processing.
 * These tests run the whole effect catalogue   with the effects that keep per frame working state (jitter, mottle,
 * bleed) repeated, so instances are shared and stacked   on many more worker threads than frames in flight would
 * normally need, to maximise interleaving. With the original (unsafe) jitter/mottle/bleed implementations this test
 * fails: most frames differ, or the mottler crashes on a half initialised cache.
 * </p>
 */
class FrameProcessingPipelineTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final int FRAME_COUNT = 96; // > one mottle damage mask segment (72 frames)
    private static final String EFFECTS = "nssnfwyvdjmbpjjmmbb";

    @Test
    void parallelOutputIsPixelIdenticalToSequential() throws Exception {
        List<FrameProcessor> processors = effects(EFFECTS);
        InfoVideo info = videoInfo(syntheticFrame(0));

        List<String> sequential = processSequentially(processors, info);

        for (int run = 0; run < 3; run++) {
            try (FrameProcessingPipeline<String> pipeline =
                    new FrameProcessingPipeline<>(processors, 8, TestFrames::sha1)) {
                pipeline.initialise(info);
                List<CompletableFuture<FrameProcessingPipeline.ProcessedFrame<String>>> futures = new ArrayList<>();
                for (int i = 0; i < FRAME_COUNT; i++) {
                    futures.add(pipeline.submit(syntheticFrame(i), new InfoFrame(i, i * 41_667L), null));
                }
                for (int i = 0; i < FRAME_COUNT; i++) {
                    assertEquals(sequential.get(i), futures.get(i).get(30, TimeUnit.SECONDS).output(),
                            "frame " + i + " differs from sequential processing (run " + run + ")");
                }
            }
        }
    }

    @Test
    void framesNoLongerWantedAreSkipped() throws Exception {
        try (FrameProcessingPipeline<String> pipeline =
                new FrameProcessingPipeline<>(effects("b"), 2, TestFrames::sha1)) {
            pipeline.initialise(videoInfo(syntheticFrame(0)));
            assertEquals(null, pipeline.submit(syntheticFrame(0), new InfoFrame(0, 0), null, () -> false).get());
        }
    }

    @Test
    void pooledFramesAreReturnedAfterRendering() throws Exception {
        Frame template = syntheticFrame(0);
        FramePool pool = new FramePool(template, 1);
        try (FrameProcessingPipeline<String> pipeline =
                new FrameProcessingPipeline<>(effects("w"), 2, TestFrames::sha1)) {
            pipeline.initialise(videoInfo(template));
            Frame pooled = pool.acquireCopyOf(template);
            pipeline.submit(pooled, new InfoFrame(0, 0), pool).get();
            assertTrue(pool.acquire() == pooled, "the frame should be back in the pool");
        }
    }

    @Test
    void poolCopiesExactPixelsAndAppliesBackpressure() throws Exception {
        Frame source = syntheticFrame(3);
        FramePool pool = new FramePool(source, 1);
        Frame copy = pool.acquireCopyOf(source);
        assertEquals(TestFrames.sha1(source), TestFrames.sha1(copy));
        assertTrue(copy.image[0] != source.image[0], "the copy must not share the decoder's buffer");

        CompletableFuture<Frame> blocked = CompletableFuture.supplyAsync(() -> {
            try {
                return pool.acquire();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(50);
        assertFalse(blocked.isDone(), "acquire must wait while every frame is in use");
        pool.release(copy);
        assertTrue(blocked.get(1, TimeUnit.SECONDS) == copy);
    }

    private static List<String> processSequentially(List<FrameProcessor> processors, InfoVideo info)
            throws Exception {
        for (FrameProcessor processor : processors) {
            processor.initialise(info);
        }
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < FRAME_COUNT; i++) {
            Frame frame = syntheticFrame(i);
            InfoFrame frameInfo = new InfoFrame(i, i * 41_667L);
            for (FrameProcessor processor : processors) {
                processor.process(frame, frameInfo);
            }
            hashes.add(TestFrames.sha1(frame));
        }
        return hashes;
    }

    static List<FrameProcessor> effects(String shortFlags) {
        List<FrameProcessorOption> options = new ArrayList<>();
        for (char flag : shortFlags.toCharArray()) {
            options.add(FrameProcessorOption.fromShortFlag(flag).orElseThrow());
        }
        return FrameProcessorOption.instantiate(options);
    }

    private static InfoVideo videoInfo(Frame frame) {
        return new InfoVideo("synthetic", FRAME_COUNT, frame.imageWidth, frame.imageHeight, frame.imageDepth,
                frame.imageChannels, frame.imageStride, 24.0, 24, 41_667, 3);
    }

    // A moving pattern with bright and dark areas, so every effect has something to do.
    private static Frame syntheticFrame(int frameNumber) {
        Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int base = y * frame.imageStride + x * 3;
                pixels.put(base, (byte) (x + 3 * frameNumber));
                pixels.put(base + 1, (byte) (2 * y + frameNumber));
                pixels.put(base + 2, (byte) ((x ^ y) + 5 * frameNumber));
            }
        }
        return frame;
    }
}
