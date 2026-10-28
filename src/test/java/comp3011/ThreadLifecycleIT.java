/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Evidence that threads are launched and terminated in a timely manner, run by "mvn verify".
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.jupiter.api.Test;

/**
 * Do the player's threads really go away, promptly, however playback is stopped?
 *
 * <p>
 * A thread that outlives its playback session is a leak: it holds memory and native decoder resources, and after
 * enough stops and restarts it would exhaust the machine. Worse, a thread blocked somewhere that shutdown does not
 * reach makes "stop" hang. This test stops playback at the moments that are hardest to stop from - each one leaves
 * the threads blocked in a <i>different</i> place - and checks that every thread is gone within
 * {@value #TIMELY_SHUTDOWN_MS} ms.
 * </p>
 *
 * <h2>How threads are counted: a census by family</h2>
 * <p>
 * Every thread the player creates is named after its job ({@code frame-worker-...}, {@code video-decoder},
 * {@code audio-output}), which is what makes a census possible. One is taken before playback (the baseline), one at
 * the moment of the cut (the peak) and then repeatedly after shutdown until the counts are back to the baseline. The
 * peak census is also checked, as a negative control: a test that never saw the threads running would "prove"
 * nothing by finding none afterwards.
 * </p>
 *
 * <h2>The cuts</h2>
 * <p>
 * Each {@link Cut} is a different moment to stop - see their descriptions. Between them they cover every place
 * the threads can be waiting when {@code close()} arrives: a worker mid-effect, the decoder inside a blocking
 * {@code put} on a full queue, the decoder parked waiting for a seek after the end of the media, and the audio thread
 * waiting on a clock that never starts (there is no display in this test to start it).
 * </p>
 */
class ThreadLifecycleIT {
    private static final int WORKERS = 4;
    private static final int QUEUE_CAPACITY = 8;
    private static final long TIMELY_SHUTDOWN_MS = 2_000;
    private static final int REEL_CHANGES = 12;

    @Test
    void everyThreadIsGonePromptlyWhicheverMomentPlaybackIsCut() throws Exception {
        List<String> reportRows = new ArrayList<>();
        for (Cut cut : Cut.values()) {
            Census baseline = Census.take();
            Rig rig = Rig.assemble(video());

            cut.leadUpTo(rig);
            Census peak = Census.take();
            long shutdownMs = rig.shutDownAndTimeUntilSettled(baseline);
            Census after = Census.take();

            assertEquals(baseline.of(ThreadFamily.FRAME_WORKERS) + WORKERS, peak.of(ThreadFamily.FRAME_WORKERS),
                    cut + ": all frame workers should have been running at the cut");
            assertEquals(baseline.of(ThreadFamily.DECODER) + 1, peak.of(ThreadFamily.DECODER),
                    cut + ": the decoder should have been running at the cut");
            assertEquals(baseline.of(ThreadFamily.AUDIO) + 1, peak.of(ThreadFamily.AUDIO),
                    cut + ": the audio thread should have been running at the cut");

            assertTrue(after.isNoLargerThan(baseline), cut + ": threads left behind: " + after.excessOver(baseline));
            assertTrue(shutdownMs <= TIMELY_SHUTDOWN_MS,
                    cut + ": shutdown took " + shutdownMs + " ms, more than " + TIMELY_SHUTDOWN_MS + " ms");

            reportRows.add(String.format(Locale.ROOT, "  %-16s %-58s %5d ms   peak %s",
                    cut.name().toLowerCase(Locale.ROOT).replace('_', ' '), cut.description, shutdownMs,
                    peak.excessOver(baseline)));
        }
        writeReport(reportRows);
    }

    @Test
    void repeatedReelChangesNeverAccumulateThreads() throws Exception {
        Census baseline = Census.take();
        for (int change = 0; change < REEL_CHANGES; change++) {
            Rig rig = Rig.assemble(video());
            Cut.MID_REEL.leadUpTo(rig);
            rig.shutDownAndTimeUntilSettled(baseline);
        }
        Census after = Census.take();
        assertTrue(after.isNoLargerThan(baseline),
                "after " + REEL_CHANGES + " start/stop cycles, threads left behind: " + after.excessOver(baseline));
    }

    private static File video() throws Exception {
        // Same file and shape as MultiThreadedPlaybackIT, so the generated media is shared rather than regenerated.
        return TestFrames.generateVideo(new File("target/test-media/accuracy.mp4"), 480, 270, 60, true);
    }

    private static void writeReport(List<String> rows) throws Exception {
        String report = "Thread lifecycle: time from close() until every playback thread has terminated\n"
                + String.join("\n", rows) + "\n";
        System.out.print(report);
        Files.writeString(new File("target/thread-lifecycle-report.txt").toPath(), report);
    }

    /** The moments at which playback is stopped, each leaving the threads blocked in a different place. */
    private enum Cut {
        MID_REEL("frames in flight: workers busy, decoder running") {
            @Override
            void leadUpTo(Rig rig) throws Exception {
                // The pool starts a worker per frame handed to it until all exist, so watching at least WORKERS
                // frames guarantees every worker has been started - however busy the machine is.
                rig.watchFrames(WORKERS + 2);
            }
        },
        STALLED_DISPLAY("queue full: decoder blocked in put(), as when paused") {
            @Override
            void leadUpTo(Rig rig) throws Exception {
                rig.waitUntilQueueIsFull();
            }
        },
        RAN_OFF_THE_END("end of media: decoder parked waiting for a seek") {
            @Override
            void leadUpTo(Rig rig) throws Exception {
                rig.watchToTheEnd();
            }
        };

        final String description;

        Cut(String description) {
            this.description = description;
        }

        abstract void leadUpTo(Rig rig) throws Exception;
    }

    /** The player's thread families, recognised by the names the player gives its threads. */
    private enum ThreadFamily {
        FRAME_WORKERS("frame-worker-"),
        DECODER("video-decoder"),
        AUDIO("audio-output");

        private final String namePrefix;

        ThreadFamily(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        boolean includes(Thread thread) {
            return thread.isAlive() && thread.getName().startsWith(namePrefix);
        }
    }

    /** How many live threads of each family exist at one instant. */
    private record Census(Map<ThreadFamily, Integer> headcount) {
        static Census take() {
            Map<ThreadFamily, Integer> counts = new EnumMap<>(ThreadFamily.class);
            for (ThreadFamily family : ThreadFamily.values()) {
                counts.put(family, 0);
            }
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                for (ThreadFamily family : ThreadFamily.values()) {
                    if (family.includes(thread)) {
                        counts.merge(family, 1, Integer::sum);
                    }
                }
            }
            return new Census(counts);
        }

        int of(ThreadFamily family) {
            return headcount.get(family);
        }

        boolean isNoLargerThan(Census baseline) {
            for (ThreadFamily family : ThreadFamily.values()) {
                if (of(family) > baseline.of(family)) {
                    return false;
                }
            }
            return true;
        }

        String excessOver(Census baseline) {
            StringBuilder excess = new StringBuilder();
            for (ThreadFamily family : ThreadFamily.values()) {
                int extra = of(family) - baseline.of(family);
                excess.append(String.format(Locale.ROOT, "%s +%d ", family.namePrefix.replace("-", ""), extra));
            }
            return excess.toString().trim();
        }
    }

    /**
     * The playback threads, assembled and shut down exactly as {@link PlaybackSession} does - but rendering to a
     * pixel fingerprint instead of a JavaFX image, and with an audio device that is never opened, so the test needs
     * neither a screen nor a sound card. The threads, queues and shutdown order are the real ones.
     */
    private static final class Rig {
        private final FFmpegFrameGrabber grabber;
        private final FrameProcessingPipeline<String> pipeline;
        private final AudioOutput audioOutput;
        private final VideoDecoder<String> decoder;
        private final BlockingQueue<VideoDecoder.PresentationItem<String>> queue;

        private Rig(File video) throws Exception {
            grabber = MultiThreadedPlaybackIT.ReferencePlayer.openGrabber(video);
            pipeline = new FrameProcessingPipeline<>(FrameProcessingPipelineTest.effects("nb"), WORKERS,
                    TestFrames::sha1);
            audioOutput = new AudioOutput(new AudioPlayer(), new PlaybackClock(), () -> true);
            queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
            decoder = new VideoDecoder<>(grabber, MultiThreadedPlaybackIT.ReferencePlayer.streamInfo(video, grabber),
                    grabber.hasAudio(), pipeline, audioOutput, queue, 1);
        }

        static Rig assemble(File video) throws Exception {
            Rig rig = new Rig(video);
            rig.audioOutput.start();
            rig.decoder.start();
            return rig;
        }

        void watchFrames(int frames) throws Exception {
            for (int seen = 0; seen < frames; seen++) {
                if (!(next() instanceof VideoDecoder.DecodedFrame<String> frame)) {
                    return;
                }
                frame.processed().get(30, TimeUnit.SECONDS);
            }
        }

        void waitUntilQueueIsFull() throws InterruptedException {
            long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (queue.remainingCapacity() > 0 && System.nanoTime() < giveUp) {
                Thread.sleep(5);
            }
            assertEquals(0, queue.remainingCapacity(), "the decoder never filled the queue");
            Thread.sleep(50); // Let the decoder reach its blocking put() with the next frame.
        }

        void watchToTheEnd() throws Exception {
            VideoDecoder.PresentationItem<String> item;
            do {
                item = next();
            } while (item instanceof VideoDecoder.DecodedFrame);
            assertTrue(item instanceof VideoDecoder.EndOfStream, "expected the end of the media, got " + item);
            Thread.sleep(50); // Let the decoder park in its wait for a seek.
        }

        /** Shuts down in PlaybackSession's order; returns ms until the census is back to {@code baseline}. */
        long shutDownAndTimeUntilSettled(Census baseline) throws Exception {
            long started = System.nanoTime();
            decoder.close();
            pipeline.close();
            audioOutput.close();
            grabber.stop();
            grabber.close();
            long giveUp = started + TimeUnit.MILLISECONDS.toNanos(TIMELY_SHUTDOWN_MS * 2);
            while (!Census.take().isNoLargerThan(baseline) && System.nanoTime() < giveUp) {
                Thread.sleep(1); // A thread can still be on its way out for a moment after join/awaitTermination.
            }
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        }

        private VideoDecoder.PresentationItem<String> next() throws InterruptedException {
            VideoDecoder.PresentationItem<String> item = queue.poll(30, TimeUnit.SECONDS);
            assertTrue(item != null, "the decoder stalled");
            return item;
        }
    }
}
