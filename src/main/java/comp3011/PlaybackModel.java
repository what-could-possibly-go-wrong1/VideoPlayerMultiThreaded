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
 * New file. Extracted from the public methods of the original VideoPlayerModel.
 */
// defines the controls shared by both playback models so the view and controller don't depend on a specific threading design.
package comp3011;

import java.io.File;

/**
 * The model part of the video player model-view-controller architecture, as seen by the
 * {@link VideoPlayerController}.
 *
 * <p>
 * There are two implementations:
 * </p>
 * <ul>
 * <li>{@link VideoPlayerModel} - the multi-threaded player. This is the default.</li>
 * <li>{@link SingleThreadedVideoPlayerModel} - the original co-operative single threaded player, kept unchanged in
 * behaviour as a reference for performance and frame-correctness comparison. Selected with the hidden
 * {@code --single-threaded} command line switch.</li>
 * </ul>
 *
 * <p>
 * <b>Threading contract:</b> every method here must be called on the JavaFX application thread, and every callback
 * an implementation makes back to the controller is also made on the JavaFX application thread. Implementations are
 * free to use other threads internally but must not leak them through this interface.
 * </p>
 */
public interface PlaybackModel {

    void play(File file);

    void startOver();

    void backFiveSeconds();

    void forwardFiveSeconds();

    void togglePause();

    void toggleAudioOutput();

    boolean isAudioOutputEnabled();

    void stopPlayback();

    /** Stops playback and releases every resource, including any threads the model owns. */
    void shutdown();
}
