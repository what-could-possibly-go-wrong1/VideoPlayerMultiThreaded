package comp3011;
import java.io.File;
import java.util.*;
import org.bytedeco.javacv.*;
/** Times each effect on real HD frames: ms per frame. */
public class EffectCost { public static void main(String[] a) throws Exception {
  FFmpegFrameGrabber g = MultiThreadedPlaybackIT.ReferencePlayer.openGrabber(new File(a[0]));
  List<Frame> frames = new ArrayList<>(); Frame f; while (frames.size() < 48 && (f = g.grab()) != null) if (f.image != null) frames.add(f.clone());
  InfoVideo vi = new InfoVideo("movie", g.getLengthInVideoFrames(), frames.get(0).imageWidth, frames.get(0).imageHeight, frames.get(0).imageDepth, frames.get(0).imageChannels, frames.get(0).imageStride, 24, 24, 41667, g.getPixelFormat());
  JavaFXFrameConverter conv = new JavaFXFrameConverter();
  for (FrameProcessorOption o : FrameProcessorOption.values()) {
    FrameProcessor p = FrameProcessorOption.instantiate(List.of(o)).get(0); p.initialise(vi);
    for (int w = 0; w < 2; w++) for (int i = 0; i < frames.size(); i++) p.process(frames.get(i), new InfoFrame(i, 0)); // warm-up
    long t = System.nanoTime(); for (int r = 0; r < 2; r++) for (int i = 0; i < frames.size(); i++) p.process(frames.get(i), new InfoFrame(i, 0));
    System.out.printf("%-18s %7.2f ms/frame%n", o.longFlag(), (System.nanoTime()-t)/1e6/(2*frames.size()));
  }
  long t = System.nanoTime(); for (int i = 0; i < frames.size(); i++) { FramePool.copyPixels(frames.get(i), frames.get((i+1)%frames.size())); } System.out.printf("%-18s %7.2f ms/frame%n", "(pool copy)", (System.nanoTime()-t)/1e6/frames.size());
  t = System.nanoTime(); for (int i = 0; i < frames.size(); i++) TestFrames.sha1(frames.get(i)); System.out.printf("%-18s %7.2f ms/frame%n", "(sha1 test only)", (System.nanoTime()-t)/1e6/frames.size());
  g.stop(); g.close(); } }
