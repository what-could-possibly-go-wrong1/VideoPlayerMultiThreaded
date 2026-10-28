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
// stores frame information needed by the effects without making each effect query the decoder.
package comp3011;

public record InfoFrame(
        int frameNumber,
        long timestampUs) {
}
