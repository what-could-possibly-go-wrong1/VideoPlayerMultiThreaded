/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Record of an optimisation that was evaluated and rejected, run by "mvn verify".
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * An experiment, kept as a test: would <i>scanline fusion</i> make the effects faster?
 *
 * <h2>The idea</h2>
 * <p>
 * Each effect normally sweeps the whole frame (4.6 MB for HD) before the next one starts, so a chain such as
 * {@code -fwy} streams the frame through memory three times. Several effects are <i>per-pixel</i>: a pixel's new
 * value depends only on its own old value. For those, the chain can instead be applied to one narrow band of
 * scanlines at a time - small enough to stay in the CPU's cache - before moving to the next band, so the frame
 * crosses the memory bus once instead of once per effect. This is the classic loop-fusion / cache-blocking
 * optimisation.
 * </p>
 *
 * <h2>How it is tested without touching the effects</h2>
 * <p>
 * The real, unmodified effect classes are run on <i>band views</i>: {@link Frame}s whose pixel buffer is a
 * zero-copy slice of the full frame covering {@value #BAND_ROWS} rows. The effects are simply initialised with
 * the band's height. No effect code is duplicated, so the fused result must be pixel-identical - which this test
 * asserts.
 * </p>
 *
 * <h2>Where it is not valid</h2>
 * <p>
 * Band views hide each row's true position. That is harmless for flicker, black-and-white and yellow, but the
 * vignette darkens by distance from the frame centre, so in a band view every band would be vignetted as if it were
 * a whole frame. The second test proves the experiment can tell the difference: fusing the vignette this way
 * changes the output. A real implementation would need position-aware effects - a new effect API.
 * </p>
 *
 * <h2>Finding (recorded in README.md, "Optimisations evaluated")</h2>
 * <p>
 * Measured on HD frames there is little or nothing to gain: from about 0.96x (slightly slower - calling every effect
 * once per band costs as much as the cache saves) to about 1.15x for a hand-written fused loop. These effects are
 * compute-bound (a table lookup or arithmetic per byte), not memory-bound, so streaming the frame through memory
 * was never the bottleneck. That does not justify a new effect API and a second code path through every per-pixel
 * effect, so fusion was not adopted.
 * The test asserts correctness only; the timings are reported (and written to
 * {@code target/scanline-fusion-report.txt}) rather than asserted, as they vary from machine to machine.
 * </p>
 */
class ScanlineFusionExperimentIT {
    private static final int BAND_ROWS = 8; // 8 HD rows = 46 KB: comfortably inside a typical per-core L2 cache.
    private static final int REEL_LENGTH = 24;
    private static final int TIMED_PASSES = 3;

    // Per-pixel and position-independent: the run "fwy" from TITAN's stress chains "...sfwyvd...".
    private static final String FUSABLE_RUN = "fwy";
    // Same run plus the vignette, which depends on each pixel's position in the full frame.
    private static final String POSITION_DEPENDENT_RUN = "fwyv";

    @Test
    void scanlineFusionIsPixelIdenticalButGainsLittle() throws Exception {
        EffectCostLedgerIT.Reel reel = hdReel();
        List<FrameProcessor> effects = FrameProcessingPipelineTest.effects(FUSABLE_RUN);

        Outcome wholeFrame = sweepWholeFrames(effects, reel);
        Outcome banded = sweepScanlineBands(effects, reel);

        assertEquals(wholeFrame.fingerprints(), banded.fingerprints(),
                "fusing per-pixel effects band by band must not change a single pixel");

        double speedUp = wholeFrame.millisPerFrame() / banded.millisPerFrame();
        String report = String.format(Locale.ROOT, """
                Scanline fusion experiment, effects -%s on %dx%d frames (%d-row bands, best of %d passes):
                  each effect sweeps the whole frame : %6.2f ms/frame
                  all effects per %d-row band        : %6.2f ms/frame
                  speed-up                           : %4.2fx  (pixel-identical: yes)
                  conclusion: compute-bound, not memory-bound - too small a gain to justify a new effect API.
                """, FUSABLE_RUN, reel.width(), reel.height(), BAND_ROWS, TIMED_PASSES,
                wholeFrame.millisPerFrame(), BAND_ROWS, banded.millisPerFrame(), speedUp);
        System.out.print(report);
        Files.writeString(new File("target/scanline-fusion-report.txt").toPath(), report);
    }

    @Test
    void bandViewsCannotFusePositionDependentEffects() throws Exception {
        EffectCostLedgerIT.Reel reel = hdReel();
        List<FrameProcessor> effects = FrameProcessingPipelineTest.effects(POSITION_DEPENDENT_RUN);

        Outcome wholeFrame = sweepWholeFrames(effects, reel);
        Outcome banded = sweepScanlineBands(effects, reel);

        assertNotEquals(wholeFrame.fingerprints(), banded.fingerprints(),
                "the vignette depends on each row's position, so band views must change its output - if this "
                        + "ever passes as equal, the experiment has stopped testing what it claims to");
    }

    private static EffectCostLedgerIT.Reel hdReel() throws Exception {
        File video = TestFrames.generateVideo(new File("target/test-media/hd-load-test.mp4"), 1920, 800, 96, true);
        EffectCostLedgerIT.Reel reel = EffectCostLedgerIT.Reel.decode(video, REEL_LENGTH);
        assertTrue(reel.height() % BAND_ROWS == 0, "test video height must be a whole number of bands");
        return reel;
    }

    // The normal way: each effect processes the entire frame before the next effect starts.
    private static Outcome sweepWholeFrames(List<FrameProcessor> effects, EffectCostLedgerIT.Reel reel)
            throws Exception {
        for (FrameProcessor effect : effects) {
            effect.initialise(reel.videoInfo());
        }
        return timeBestPass(reel, frameIndex -> {
            Frame frame = reel.workingFrame(frameIndex);
            InfoFrame info = reel.frameInfo(frameIndex);
            for (FrameProcessor effect : effects) {
                effect.process(frame, info);
            }
        });
    }

    // The fused way: every effect processes one cache-sized band before the next band is touched.
    private static Outcome sweepScanlineBands(List<FrameProcessor> effects, EffectCostLedgerIT.Reel reel)
            throws Exception {
        InfoVideo full = reel.videoInfo();
        InfoVideo bandShaped = new InfoVideo(full.mediaName(), full.totalVideoFrames(), full.imageWidth(), BAND_ROWS,
                full.imageDepth(), full.imageChannels(), full.imageStride(), full.frameRate(), full.intFrameRate(),
                full.frameDurationUs(), full.pixelFormat());
        for (FrameProcessor effect : effects) {
            effect.initialise(bandShaped); // Same instances, so the same per-instance random seeds.
        }
        return timeBestPass(reel, frameIndex -> {
            Frame frame = reel.workingFrame(frameIndex);
            InfoFrame info = reel.frameInfo(frameIndex);
            for (int firstRow = 0; firstRow < frame.imageHeight; firstRow += BAND_ROWS) {
                Frame band = bandView(frame, firstRow);
                for (FrameProcessor effect : effects) {
                    effect.process(band, info);
                }
            }
        });
    }

    // A Frame that looks like a short image but shares (does not copy) the full frame's pixel memory.
    private static Frame bandView(Frame frame, int firstRow) {
        ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
        pixels.clear()
                .position(firstRow * frame.imageStride)
                .limit((firstRow + BAND_ROWS) * frame.imageStride);
        Frame band = new Frame();
        band.imageWidth = frame.imageWidth;
        band.imageHeight = BAND_ROWS;
        band.imageDepth = frame.imageDepth;
        band.imageChannels = frame.imageChannels;
        band.imageStride = frame.imageStride;
        band.image = new Buffer[] { pixels.slice() };
        return band;
    }

    // Warm-up pass, then the fastest of several timed passes. Every pass starts from the pristine reel. The
    // fingerprints are taken from the last pass, after timing has stopped.
    private static Outcome timeBestPass(EffectCostLedgerIT.Reel reel, FrameStep step) throws Exception {
        reel.rewind();
        runPass(reel, step);
        double fastestMs = Double.MAX_VALUE;
        for (int pass = 0; pass < TIMED_PASSES; pass++) {
            reel.rewind();
            long started = System.nanoTime();
            runPass(reel, step);
            fastestMs = Math.min(fastestMs, (System.nanoTime() - started) / 1e6);
        }
        List<String> fingerprints = new ArrayList<>();
        for (int i = 0; i < reel.length(); i++) {
            fingerprints.add(TestFrames.sha1(reel.workingFrame(i)));
        }
        return new Outcome(fastestMs / reel.length(), fingerprints);
    }

    private static void runPass(EffectCostLedgerIT.Reel reel, FrameStep step) throws Exception {
        for (int i = 0; i < reel.length(); i++) {
            step.apply(i);
        }
    }

    @FunctionalInterface
    private interface FrameStep {
        void apply(int frameIndex) throws Exception;
    }

    private record Outcome(double millisPerFrame, List<String> fingerprints) {
    }
}
