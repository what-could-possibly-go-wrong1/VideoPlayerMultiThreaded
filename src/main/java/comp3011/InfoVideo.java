// stores video metadata shared by the frame processors so they can initialise from one consistent source.
package comp3011;

public record InfoVideo(
        String mediaName,
        int totalVideoFrames,
        int imageWidth,
        int imageHeight,
        int imageDepth,
        int imageChannels,
        int imageStride,
        double frameRate,
        int intFrameRate,
        int frameDurationUs,
        int pixelFormat) {
}
