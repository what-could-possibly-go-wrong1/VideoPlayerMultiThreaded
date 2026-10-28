/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <Student Name> (<Student Number>)
 *      - Added a one-line summary of the class's role.
 *
 * Copyright 2026 Simon Ratcliffe
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
