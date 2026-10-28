package comp3011;
 
import static org.junit.jupiter.api.Assertions.*;
 
import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
 
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
 
/*
 * experiment: does "scanline fusion" make effects faster?
 *
 * normal:  effect1 does whole frame, then effect2 does whole frame, ...   (frame goes thru memory N times)
 * fused:   do ALL effects on 8 rows (fits in cache), then next 8 rows, ... (frame goes thru memory once)
 *
 * only valid for PER-PIXEL effects that dont care where the pixel is (flicker, b&w, yellow)
 * vignette DOES care where it is (dist from centre) -> cant fuse like this -> 2nd test proves that
 *
 * trick: dont rewrite the effects. just hand the REAL effects a fake small Frame ("band view")
 * that points into the same memory (zero copy). init them with height=8.
 *
 * result: ~0.96x - 1.15x. basically nothing. effects are compute bound (lookup/maths per byte),
 * NOT memory bound. so not worth a new effect api + duplicate code -> rejected. (see README)
 * timings only printed, not asserted (differs per machine). correctness IS asserted.
 */
class ScanlineFusionExperimentIT {
    static final int BAND = 8;          // 8 HD rows ~ 46KB, fits L2 easy
    static final int REEL_LEN = 24;
    static final int PASSES = 3;
 
    static final String OK_TO_FUSE = "fwy";     // flicker, b&w, yellow  (bit of titans "...sfwyvd...")
    static final String NOT_OK = "fwyv";        // + vignette = position dependent
 
    @Test
    void scanlineFusionIsPixelIdenticalButGainsLittle() throws Exception
    {
        EffectCostLedgerIT.Reel reel = hdReel();
        List<FrameProcessor> fx = FrameProcessingPipelineTest.effects(OK_TO_FUSE);
 
        Outcome normal = wholeFrames(fx, reel);
        Outcome fused  = inBands(fx, reel);
 
        assertEquals(normal.prints(), fused.prints(), "fusing per-pixel effects band by band must not change a single pixel");
 
        double x = normal.ms() / fused.ms();
        String rep = String.format(Locale.ROOT, """
                Scanline fusion experiment, effects -%s on %dx%d frames (%d-row bands, best of %d passes):
                  each effect sweeps the whole frame : %6.2f ms/frame
                  all effects per %d-row band        : %6.2f ms/frame
                  speed-up                           : %4.2fx  (pixel-identical: yes)
                  conclusion: compute-bound, not memory-bound - too small a gain to justify a new effect API.
                """, OK_TO_FUSE, reel.width(), reel.height(), BAND, PASSES, normal.ms(), BAND, fused.ms(), x);
        System.out.print(rep);
        Files.writeString(new File("target/scanline-fusion-report.txt").toPath(), rep);
    }
 
    // negative control!! if this ever comes out EQUAL the experiment isnt testing what it says
    @Test
    void bandViewsCannotFusePositionDependentEffects() throws Exception {
        EffectCostLedgerIT.Reel reel = hdReel();
        List<FrameProcessor> fx = FrameProcessingPipelineTest.effects(NOT_OK);
        assertNotEquals(wholeFrames(fx, reel).prints(), inBands(fx, reel).prints(),
                "vignette depends on row position so band views must change it");
    }
 
    static EffectCostLedgerIT.Reel hdReel() throws Exception {
        File vid = TestFrames.generateVideo(new File("target/test-media/hd-load-test.mp4"), 1920, 800, 96, true);
        EffectCostLedgerIT.Reel r = EffectCostLedgerIT.Reel.decode(vid, REEL_LEN);
        assertTrue(r.height() % BAND == 0, "height must split into whole bands");
        return r;
    }
 
    // normal way
    static Outcome wholeFrames(List<FrameProcessor> fx, EffectCostLedgerIT.Reel reel) throws Exception {
        for (FrameProcessor e : fx) e.initialise(reel.videoInfo());
        return bestOf(reel, i -> {
            Frame f = reel.workingFrame(i); InfoFrame inf = reel.frameInfo(i);
            for (FrameProcessor e : fx) e.process(f, inf);
        });
    }
 
    // fused way. SAME effect objects -> same random seeds (instanceIndex), just re-init'd as 8 rows tall
    static Outcome inBands(List<FrameProcessor> fx, EffectCostLedgerIT.Reel reel) throws Exception
    {
        InfoVideo v = reel.videoInfo();
        InfoVideo bandSized = new InfoVideo(v.mediaName(), v.totalVideoFrames(), v.imageWidth(), BAND,
                v.imageDepth(), v.imageChannels(), v.imageStride(), v.frameRate(), v.intFrameRate(),
                v.frameDurationUs(), v.pixelFormat());
        for (FrameProcessor e : fx) e.initialise(bandSized);
 
        return bestOf(reel, i -> {
            Frame f = reel.workingFrame(i); InfoFrame inf = reel.frameInfo(i);
            for (int row = 0; row < f.imageHeight; row += BAND) {
                Frame band = bandView(f, row);
                for (FrameProcessor e : fx) e.process(band, inf);
            }
        });
    }
 
    // fake little Frame that SHARES the big frame's memory (no copying)
    static Frame bandView(Frame f, int row) {
        ByteBuffer px = ((ByteBuffer) f.image[0]).duplicate();
        px.clear().position(row * f.imageStride).limit((row + BAND) * f.imageStride);
        Frame b = new Frame();
        b.imageWidth = f.imageWidth;  b.imageHeight = BAND;
        b.imageDepth = f.imageDepth;  b.imageChannels = f.imageChannels;  b.imageStride = f.imageStride;
        b.image = new Buffer[]{ px.slice() };
        return b;
    }
 
    // warmup, then fastest of PASSES. fingerprints taken after timing stops
    static Outcome bestOf(EffectCostLedgerIT.Reel reel, Step step) throws Exception {
        reel.rewind(); all(reel, step);
        double best = Double.MAX_VALUE;
        for (int p=0; p<PASSES; p++) {
            reel.rewind();
            long t0 = System.nanoTime();
            all(reel, step);
            best = Math.min(best, (System.nanoTime()-t0)/1e6);
        }
        List<String> prints = new ArrayList<>();
        for (int i=0; i<reel.length(); i++) prints.add(TestFrames.sha1(reel.workingFrame(i)));
        return new Outcome(best / reel.length(), prints);
    }
 
    static void all(EffectCostLedgerIT.Reel reel, Step step) throws Exception { for (int i=0;i<reel.length();i++) step.apply(i); }
 
    interface Step { void apply(int i) throws Exception; }
    record Outcome(double ms, List<String> prints) {}
}
 
