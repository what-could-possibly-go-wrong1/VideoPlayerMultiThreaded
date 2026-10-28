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
// provides a simple application entry point for packaged launches.
package comp3011;

/**
 * Plain Java entry point for the packaged application. Seems overly complicated hopping through a main function that
 * calls a main function, but JavaFX likes it this way when the app is bundled into a fat .jar (still don't quite
 * understand why).
 *
 * <p>
 * The runnable jar manifest points here instead of directly at
 * {@link VideoPlayerApp}, because {@code VideoPlayerApp} extends JavaFX
 * {@code Application}. Keeping the manifest entry point as an ordinary class
 * avoids JavaFX launcher special cases in shaded jars while still delegating all
 * real startup work to {@link VideoPlayerApp#main(String[])}.
 */
public class VideoPlayerAppLauncher {

    public static void main(String[] args) {
        VideoPlayerApp.main(args);
    }
}
