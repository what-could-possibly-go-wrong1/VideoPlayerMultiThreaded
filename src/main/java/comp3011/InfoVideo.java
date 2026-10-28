/*
 * ATTRIBUTION HEADING
 * Authors:
 *   1. In some files: Simon Ratcliffe, in collaboration with GPT-5.6 Terra 
 *   2. Loran Webber a1826273, production helped by Claude, which did:
 *   (a) File structure with project folder (correct nesting)
 *   (b) Debugging after multiple failed TITAN tests with no way fowards
 *   (c) Final check of all SOLID principles that may have been missed after own review
 *   (d) Formatting this header structure, and for comments that had visual/ascii components
 *   (e) ReadMe file writeup
 *   Claude was not used in any other way
 *
 */
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
