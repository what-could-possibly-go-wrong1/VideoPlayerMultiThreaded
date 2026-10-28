/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Profiling evidence, run by "mvn verify" (maven-failsafe-plugin).
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Prices every effect: how many milliseconds of one core it costs per High Definition frame.
 *
 * <p>
 * Optimisation effort should go where the time goes, so this ledger is the starting point for any future
 * performance work on the effects. It also explains the shape of the threading design: a 24 fps video gives each
 * frame a budget of 41.7 ms, and the ledger shows how quickly a stack of effects exhausts one core's budget - which
 * is why frames are spread across all cores. The table is printed and written to
 * {@code target/effect-cost-ledger.txt}.
 * </p>
 *
 * <p>
 * Method: a short reel of decoded HD frames is kept as a pristine master. For each effect, a fresh copy of the reel
 * is processed once to warm up the JIT compiler, then several more times on fresh copies, keeping the fastest
 * (least disturbed) pass. Copying the master is not timed.
 * </p>
 */
class EffectCostLedgerIT {
    private static final int REEL_LENGTH = 24;
    private static final int TIMED_PASSES = 3;
    private static final double FRAME_BUDGET_MS = 1000.0 / 24.0;
    private static final int BAR_WIDTH = 40;

    @Test
    void everyEffectIsPricedPerHighDefinitionFrame() throws Exception {
        File video = TestFrames.generateVideo(new File("target/test-media/hd-load-test.mp4"), 1920, 800, 96, true);
        Reel reel = Reel.decode(video, REEL_LENGTH);

        List<EffectPrice> ledger = new ArrayList<>();
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            ledger.add(price(option, reel));
        }
        ledger.sort(Comparator.comparingDouble(EffectPrice::millisPerFrame).reversed());

        String report = render(ledger, reel);
        System.out.print(report);
        Files.writeString(new File("target/effect-cost-ledger.txt").toPath(), report);

        assertEquals(FrameProcessorOption.values().length, ledger.size());
        for (EffectPrice price : ledger) {
            assertTrue(Double.isFinite(price.millisPerFrame()) && price.millisPerFrame() >= 0, price.toString());
        }
    }

    private static EffectPrice price(FrameProcessorOption option, Reel reel) throws Exception {
        FrameProcessor effect = FrameProcessorOption.instantiate(List.of(option)).get(0);
        effect.initialise(reel.videoInfo());

        reel.rewind();
        runThrough(effect, reel); // Warm-up: lets the JIT compile the effect before it is timed.

        double fastestPassMs = Double.MAX_VALUE;
        for (int pass = 0; pass < TIMED_PASSES; pass++) {
            reel.rewind();
            long started = System.nanoTime();
            runThrough(effect, reel);
            fastestPassMs = Math.min(fastestPassMs, (System.nanoTime() - started) / 1e6);
        }
        return new EffectPrice(option, fastestPassMs / reel.length());
    }

    private static void runThrough(FrameProcessor effect, Reel reel) throws Exception {
        for (int i = 0; i < reel.length(); i++) {
            effect.process(reel.workingFrame(i), reel.frameInfo(i));
        }
    }

    private static String render(List<EffectPrice> ledger, Reel reel) {
        double wholeCatalogueMs = ledger.stream().mapToDouble(EffectPrice::millisPerFrame).sum();
        double dearest = ledger.get(0).millisPerFrame();
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "Effect cost ledger: ms of one core per %dx%d frame (best of %d passes over %d frames)%n",
                reel.width(), reel.height(), TIMED_PASSES, reel.length()));
        for (EffectPrice price : ledger) {
            int bar = (int) Math.round(BAR_WIDTH * price.millisPerFrame() / dearest);
            out.append(String.format(Locale.ROOT, "  %-18s %7.2f ms  %5.1f%%  %s%n",
                    price.effect().longFlag(),
                    price.millisPerFrame(),
                    100.0 * price.millisPerFrame() / wholeCatalogueMs,
                    "#".repeat(bar)));
        }
        out.append(String.format(Locale.ROOT,
                "  all %d effects once: %.1f ms = %.0f%% of a single core's %.1f ms budget per frame at 24 fps%n",
                ledger.size(), wholeCatalogueMs, 100.0 * wholeCatalogueMs / FRAME_BUDGET_MS, FRAME_BUDGET_MS));
        return out.toString();
    }

    private record EffectPrice(FrameProcessorOption effect, double millisPerFrame) {
    }

    /**
     * A short run of decoded frames held in memory: an untouched master copy, and working frames that are reset
     * from the master ({@link #rewind()}) before each pass, so every pass processes identical input.
     */
    static final class Reel {
        private final List<Frame> master;
        private final List<Frame> working;
        private final InfoVideo videoInfo;

        private Reel(List<Frame> master, InfoVideo videoInfo) {
            this.master = master;
            this.videoInfo = videoInfo;
            this.working = new ArrayList<>(master.size());
            for (Frame frame : master) {
                working.add(new Frame(frame.imageWidth, frame.imageHeight, frame.imageDepth, frame.imageChannels,
                        frame.imageStride));
            }
        }

        static Reel decode(File video, int frames) throws Exception {
            FFmpegFrameGrabber grabber = MultiThreadedPlaybackIT.ReferencePlayer.openGrabber(video);
            try {
                VideoDecoder.StreamInfo stream = MultiThreadedPlaybackIT.ReferencePlayer.streamInfo(video, grabber);
                List<Frame> decoded = new ArrayList<>();
                Frame frame;
                while (decoded.size() < frames && (frame = grabber.grabImage()) != null) {
                    decoded.add(frame.clone()); // The grabber reuses its frame buffer, so keep a copy.
                }
                Frame first = decoded.get(0);
                InfoVideo info = new InfoVideo(stream.mediaName(), stream.totalVideoFrames(), first.imageWidth,
                        first.imageHeight, first.imageDepth, first.imageChannels, first.imageStride,
                        stream.frameRate(), stream.intFrameRate(), stream.frameDurationUs(), grabber.getPixelFormat());
                return new Reel(decoded, info);
            } finally {
                grabber.stop();
                grabber.close();
            }
        }

        void rewind() {
            for (int i = 0; i < master.size(); i++) {
                FramePool.copyPixels(master.get(i), working.get(i));
            }
        }

        Frame workingFrame(int index) {
            return working.get(index);
        }

        InfoFrame frameInfo(int index) {
            return new InfoFrame(index, index * (long) videoInfo.frameDurationUs());
        }

        InfoVideo videoInfo() {
            return videoInfo;
        }

        int length() {
            return master.size();
        }

        int width() {
            return videoInfo.imageWidth();
        }

        int height() {
            return videoInfo.imageHeight();
        }
    }
}
