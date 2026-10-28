package comp3011;
 
import static org.junit.jupiter.api.Assertions.*;
 
import java.io.File;
import java.nio.file.Files;
import java.util.*;
 
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
 
/*
 * how much does each effect cost?? (ms of ONE core per HD frame)
 *
 * why care: 24fps = 41.7ms per frame budget. if effects add up past that, one core cant keep up
 * -> thats the whole reason we spread frames over cores
 *
 * how:
 *   - decode a short "reel" of frames, keep a clean master copy
 *   - per effect: warm up once (JIT), then time a few passes, keep the fastest one
 *   - reset the working frames from master before every pass so input is always the same
 *   - copying isnt timed
 */
class EffectCostLedgerIT {
    static final int REEL_LEN = 24;
    static final int PASSES = 3;
    static final double BUDGET_MS = 1000.0/24.0;   // 41.67
    static final int BAR = 40;
 
    @Test
    void everyEffectIsPricedPerHighDefinitionFrame() throws Exception
    {
        File vid = TestFrames.generateVideo(new File("target/test-media/hd-load-test.mp4"), 1920, 800, 96, true);
        Reel reel = Reel.decode(vid, REEL_LEN);
 
        List<EffectPrice> ledger = new ArrayList<>();
        for (FrameProcessorOption opt : FrameProcessorOption.values())
            ledger.add(price(opt, reel));
 
        // dearest first
        ledger.sort(Comparator.comparingDouble(EffectPrice::millisPerFrame).reversed());
 
        String rep = render(ledger, reel);
        System.out.print(rep);
        Files.writeString(new File("target/effect-cost-ledger.txt").toPath(), rep);
 
        assertEquals(FrameProcessorOption.values().length, ledger.size());
        for (EffectPrice p : ledger) assertTrue(Double.isFinite(p.millisPerFrame()) && p.millisPerFrame() >= 0, p.toString());
    }
 
    static EffectPrice price(FrameProcessorOption opt, Reel reel) throws Exception {
        FrameProcessor fx = FrameProcessorOption.instantiate(List.of(opt)).get(0);
        fx.initialise(reel.videoInfo());
 
        reel.rewind(); runThrough(fx, reel);    // warmup, not timed
 
        double best = Double.MAX_VALUE;
        for (int p = 0; p < PASSES; p++) {
            reel.rewind();
            long t0 = System.nanoTime();
            runThrough(fx, reel);
            best = Math.min(best, (System.nanoTime()-t0)/1e6);
        }
        return new EffectPrice(opt, best / reel.length());   // per frame
    }
 
    static void runThrough(FrameProcessor fx, Reel reel) throws Exception {
        for (int i=0; i<reel.length(); i++) fx.process(reel.workingFrame(i), reel.frameInfo(i));
    }
 
    // the pretty table w/ ### bars
    static String render(List<EffectPrice> ledger, Reel reel)
    {
        double total = ledger.stream().mapToDouble(EffectPrice::millisPerFrame).sum();
        double worst = ledger.get(0).millisPerFrame();
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
            "Effect cost ledger: ms of one core per %dx%d frame (best of %d passes over %d frames)%n",
            reel.width(), reel.height(), PASSES, reel.length()));
        for (EffectPrice p : ledger) {
            int n = (int) Math.round(BAR * p.millisPerFrame() / worst);
            sb.append(String.format(Locale.ROOT, "  %-18s %7.2f ms  %5.1f%%  %s%n",
                p.effect().longFlag(), p.millisPerFrame(), 100.0*p.millisPerFrame()/total, "#".repeat(n)));
        }
        // the punchline -> all 11 once is ~126% of one core on my test box
        sb.append(String.format(Locale.ROOT,
            "  all %d effects once: %.1f ms = %.0f%% of a single core's %.1f ms budget per frame at 24 fps%n",
            ledger.size(), total, 100.0*total/BUDGET_MS, BUDGET_MS));
        return sb.toString();
    }
 
    record EffectPrice(FrameProcessorOption effect, double millisPerFrame) {}
 
    // ---- Reel: clean master frames + working frames we scribble on (reset w/ rewind) ----
    static final class Reel {
        final List<Frame> master;
        final List<Frame> working;
        final InfoVideo info;
 
        private Reel(List<Frame> master, InfoVideo info) {
            this.master = master; this.info = info;
            working = new ArrayList<>(master.size());
            for (Frame f : master)
                working.add(new Frame(f.imageWidth, f.imageHeight, f.imageDepth, f.imageChannels, f.imageStride));
        }
 
        static Reel decode(File vid, int howMany) throws Exception {
            FFmpegFrameGrabber g = MultiThreadedPlaybackIT.ReferencePlayer.openGrabber(vid);
            try {
                VideoDecoder.StreamInfo s = MultiThreadedPlaybackIT.ReferencePlayer.streamInfo(vid, g);
                List<Frame> got = new ArrayList<>();
                Frame f;
                while (got.size() < howMany && (f = g.grabImage()) != null)
                    got.add(f.clone());   // grabber REUSES its buffer so must copy!!
                Frame a = got.get(0);
                InfoVideo iv = new InfoVideo(s.mediaName(), s.totalVideoFrames(), a.imageWidth, a.imageHeight,
                        a.imageDepth, a.imageChannels, a.imageStride, s.frameRate(), s.intFrameRate(),
                        s.frameDurationUs(), g.getPixelFormat());
                return new Reel(got, iv);
            } finally { g.stop(); g.close(); }
        }
 
        void rewind() { for (int i=0;i<master.size();i++) FramePool.copyPixels(master.get(i), working.get(i)); }
 
        Frame workingFrame(int i) { return working.get(i); }
        InfoFrame frameInfo(int i) { return new InfoFrame(i, i * (long) info.frameDurationUs()); }
        InfoVideo videoInfo() { return info; }
        int length() { return master.size(); }
        int width()  { return info.imageWidth(); }
        int height() { return info.imageHeight(); }
    }
}