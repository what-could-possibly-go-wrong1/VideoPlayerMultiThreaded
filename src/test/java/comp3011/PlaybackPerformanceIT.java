/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Automated load test, run by "mvn verify" (maven-failsafe-plugin).
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.jupiter.api.Test;

/**
 * Load test comparing the multi-threaded decode + effects path against the original single threaded algorithm on
 * a High Definition (1920x800, 24 fps) video with a heavy stack of effects, for future developers to track
 * performance over time.
 *
 * <p>
 * Both paths do identical work per frame - decode, apply the effect chain, and render (here: fingerprint) the result
 * - and both outputs are checked to be identical, so the comparison is like for like. The report shows sustained
 * frames per second, the fraction of real time (1.0 = exactly fast enough for 24 fps playback) and the speed-up.
 * It is written to {@code target/performance-report.txt} as well as the console.
 * </p>
 *
 * <p>
 * Tunable with system properties: {@code -Dperf.effects=nssnfwyvdjmbp} (short flags, as on the command line),
 * {@code -Dperf.frames=96}, {@code -Dperf.workers=<cores>}. For example:
 * {@code mvn verify -Dit.test=PlaybackPerformanceIT -Dperf.effects=bbmmpp}.
 * </p>
 */
class PlaybackPerformanceIT {
    private static final double REAL_TIME_FPS = 24.0;

    @Test
    void multiThreadedPlaybackOutperformsSingleThreadedReference() throws Exception {
        String effects = System.getProperty("perf.effects", "nssnfwyvdjmbp");
        int frames = Integer.getInteger("perf.frames", 96);
        int workers = Integer.getInteger("perf.workers", FrameProcessingPipeline.defaultWorkerCount());
        int cores = Runtime.getRuntime().availableProcessors();
        File video = TestFrames.generateVideo(new File("target/test-media/hd-load-test.mp4"), 1920, 800, frames, true);
        List<FrameProcessor> processors = FrameProcessingPipelineTest.effects(effects);

        // Warm up the JIT on both paths so neither is measured cold.
        MultiThreadedPlaybackIT.ReferencePlayer.decodeAndProcess(video, processors, 0, 12);
        runMultiThreaded(video, processors, workers, 12);

        long start = System.nanoTime();
        List<String> reference = MultiThreadedPlaybackIT.ReferencePlayer.decodeAndProcess(
                video, processors, 0, Integer.MAX_VALUE);
        double singleSeconds = (System.nanoTime() - start) / 1e9;

        start = System.nanoTime();
        List<String> multi = runMultiThreaded(video, processors, workers, Integer.MAX_VALUE);
        double multiSeconds = (System.nanoTime() - start) / 1e9;

        assertEquals(reference, multi, "multi-threaded output must be pixel-identical to the reference");

        double singleFps = reference.size() / singleSeconds;
        double multiFps = multi.size() / multiSeconds;
        double speedUp = multiFps / singleFps;
        String report = String.format(Locale.ROOT, """
                Playback load test: %d HD frames (1920x800), effects -%s, %d cores, %d workers
                  single threaded reference : %6.1f fps  (%4.2fx real time)
                  multi-threaded            : %6.1f fps  (%4.2fx real time)
                  speed-up                  : %4.2fx
                """, reference.size(), effects, cores, workers,
                singleFps, singleFps / REAL_TIME_FPS, multiFps, multiFps / REAL_TIME_FPS, speedUp);
        System.out.print(report);
        writeReport(report);

        // Never slower than the original. Thresholds are deliberately conservative so a busy build machine does not
        // produce false failures; the report above is the real measurement.
        assertTrue(speedUp > 0.9, "multi-threaded playback is slower than single threaded:\n" + report);
        if (cores >= 4) {
            assertTrue(speedUp > 1.5, "expected a clear speed-up on " + cores + " cores:\n" + report);
        }
    }

    private static List<String> runMultiThreaded(File video, List<FrameProcessor> processors, int workers, int limit)
            throws Exception {
        FFmpegFrameGrabber grabber = MultiThreadedPlaybackIT.ReferencePlayer.openGrabber(video);
        BlockingQueue<VideoDecoder.PresentationItem<String>> queue = new ArrayBlockingQueue<>(Math.max(6, 2 * workers));
        List<String> hashes = new ArrayList<>();
        try (FrameProcessingPipeline<String> pipeline =
                new FrameProcessingPipeline<>(processors, workers, TestFrames::sha1);
                VideoDecoder<String> decoder = new VideoDecoder<>(grabber,
                        MultiThreadedPlaybackIT.ReferencePlayer.streamInfo(video, grabber), grabber.hasAudio(),
                        pipeline, (epoch, timestampUs, frame) -> {
                        }, queue, 1)) {
            decoder.start();
            while (hashes.size() < limit) {
                VideoDecoder.PresentationItem<String> item = queue.poll(60, TimeUnit.SECONDS);
                if (!(item instanceof VideoDecoder.DecodedFrame<String> frame)) {
                    break; // End of stream (or a stall/failure, which the equality assertion will expose).
                }
                hashes.add(frame.processed().get(60, TimeUnit.SECONDS).output());
            }
        } finally {
            grabber.stop();
            grabber.close();
        }
        return hashes;
    }

    private static void writeReport(String report) {
        try {
            Files.writeString(new File("target/performance-report.txt").toPath(), report);
        } catch (IOException e) {
            System.err.println("Could not write performance report: " + e.getMessage());
        }
    }
}
