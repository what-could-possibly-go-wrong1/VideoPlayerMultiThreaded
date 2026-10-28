package comp3011;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;
import org.bytedeco.javacv.*;
/** Prototype: fused row-wise flicker+bw+yellow+vignette vs sequential process(). */
public class FusionProto { public static void main(String[] a) throws Exception {
  FFmpegFrameGrabber g = MultiThreadedPlaybackIT.ReferencePlayer.openGrabber(new File(a[0]));
  List<Frame> src = new ArrayList<>(); Frame f; while (src.size() < 48 && (f = g.grab()) != null) if (f.image != null) src.add(f.clone());
  Frame t0 = src.get(0); int W=t0.imageWidth,H=t0.imageHeight,C=t0.imageChannels,S=t0.imageStride;
  InfoVideo vi = new InfoVideo("movie", 480, W,H,t0.imageDepth,C,S, 24, 24, 41667, 0);
  FrameFlickerer fl = new FrameFlickerer(); FrameBlackAndWhiter bw = new FrameBlackAndWhiter(); FrameYellower ye = new FrameYellower(); FrameVignetter vg = new FrameVignetter();
  for (FrameProcessor p : List.of(fl,bw,ye,vg)) p.initialise(vi);
  // grab private LUTs reflectively for the prototype only
  java.lang.reflect.Field fr=FrameYellower.class.getDeclaredField("redLookup"),fg=FrameYellower.class.getDeclaredField("greenLookup"),fb=FrameYellower.class.getDeclaredField("blueLookup"); fr.setAccessible(true);fg.setAccessible(true);fb.setAccessible(true);
  byte[] R=(byte[])fr.get(ye),G=(byte[])fg.get(ye),B=(byte[])fb.get(ye);
  java.lang.reflect.Field fx=FrameVignetter.class.getDeclaredField("xLookups"),fy=FrameVignetter.class.getDeclaredField("yLookups"); fx.setAccessible(true);fy.setAccessible(true);
  byte[][] XL=(byte[][])fx.get(vg), YL=(byte[][])fy.get(vg);
  java.lang.reflect.Field fs=FrameFlickerer.class.getDeclaredField("mediaSeed"); fs.setAccessible(true); int seed=(int)fs.get(fl);
  double bestSeq=1e9,bestFused=1e9; for (int rep=0; rep<8; rep++) {
    List<Frame> a1=new ArrayList<>(), a2=new ArrayList<>(); for (Frame s: src){a1.add(s.clone());a2.add(s.clone());}
    long t=System.nanoTime();
    for (int i=0;i<a1.size();i++){ InfoFrame inf=new InfoFrame(i,0); for (FrameProcessor p: List.of(fl,bw,ye,vg)) p.process(a1.get(i),inf);} 
    double seq=(System.nanoTime()-t)/1e6/a1.size();
    t=System.nanoTime(); byte[] row=new byte[W*C];
    for (int i=0;i<a2.size();i++) fuse((ByteBuffer)a2.get(i).image[0], i, seed, row, W, H, C, S, B, G, R, XL, YL);
    double fused=(System.nanoTime()-t)/1e6/a2.size();
    boolean same=true; for (int i=0;i<a1.size();i++) same &= TestFrames.sha1(a1.get(i)).equals(TestFrames.sha1(a2.get(i)));
    bestSeq=Math.min(bestSeq,seq); bestFused=Math.min(bestFused,fused); if(!same) System.out.println("MISMATCH");
  } System.out.printf("best of 8: sequential fwyv %.2f ms/frame, fused %.2f ms/frame => %.2fx%n", bestSeq, bestFused, bestSeq/bestFused); g.stop(); g.close(); }
  static void fuse(ByteBuffer px, int i, int seed, byte[] row, int W, int H, int C, int S, byte[] B, byte[] G, byte[] R, byte[][] XL, byte[][] YL) {
      Random rnd=new Random((long)seed+i); double dim=Math.abs(rnd.nextGaussian())*5.0; double sc=Math.max(0.0,100.0-dim)/100.0;
      byte[] L=new byte[256]; for(int v=0;v<256;v++) L[v]=(byte)Math.max(0,Math.min((int)Math.round(v*sc),255));
      for (int y=0;y<H;y++){ int off=y*S; px.get(off,row,0,row.length);
        flick(row,L); bw(row,C); yel(row,C,B,G,R); vig(row,C,W,XL,YL[y]);
        px.put(off,row,0,row.length);}
  }
  static void flick(byte[] row, byte[] L){ for (int k=0;k<row.length;k++) row[k]=L[row[k]&0xFF]; }
  static void bw(byte[] row,int C){ for (int b=0;b<row.length;b+=C){int bb=row[b]&0xFF,gg=row[b+1]&0xFF,rr=row[b+2]&0xFF; byte lum=(byte)((54*rr+183*gg+19*bb)/256); for(int c=0;c<C;c++) row[b+c]=lum;} }
  static void yel(byte[] row,int C,byte[] B,byte[] G,byte[] R){ for (int b=0;b<row.length;b+=C){row[b]=B[row[b]&0xFF];row[b+1]=G[row[b+1]&0xFF];row[b+2]=R[row[b+2]&0xFF];} }
  static void vig(byte[] row,int C,int W,byte[][] XL,byte[] yl){ for (int x=0,b=0;x<W;x++,b+=C){byte[] xl=XL[x]; for(int c=0;c<C;c++){int v=xl[row[b+c]&0xFF]&0xFF; row[b+c]=yl[v];}} }
}
