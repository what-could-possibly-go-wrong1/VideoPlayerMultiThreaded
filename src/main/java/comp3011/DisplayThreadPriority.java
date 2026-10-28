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
// keeps thread priority configuration separate from the playback logic.
package comp3011;

/**
 * Raises the priority of the two JavaFX threads that put frames on screen.
 *
 * <p>
 * Frames prepared ahead of time are wasted if the screen is not redrawn when they are due. JavaFX redraws on two
 * threads: the <i>JavaFX Application Thread</i> (our display logic runs here) and its <i>render thread</i>
 * ({@code QuantumRenderer-0}), which turns the scene into pixels. Both use little CPU but must get it promptly.
 * </p>
 *
 * <p>
 * The player already runs its own heavy threads below normal priority (see {@link FrameProcessingPipeline} and
 * {@link VideoDecoder}), which keeps them out of the display's way. But on a shared machine the competition also comes
 * from <i>other programs</i>, whose threads run at normal priority. Raising the two display threads above normal lets
 * them win that competition too. On Windows, which honours Java thread priorities, the boost maps to
 * {@code THREAD_PRIORITY_HIGHEST}; elsewhere it is a harmless hint.
 * </p>
 *
 * <p>
 * The render thread is not exposed by any JavaFX API, so it is found by its name. If a future JavaFX renames it, the
 * boost simply does not apply to it: nothing breaks, and the application thread is still boosted.
 * </p>
 */
final class DisplayThreadPriority {
    // Highest short of MAX_PRIORITY (which Windows may treat as time-critical). These threads use very little CPU,
    // so ranking them above every normal thread on the machine cannot starve anything else.
    static final int DISPLAY_PRIORITY = Thread.MAX_PRIORITY - 1;
    private static final String RENDER_THREAD_PREFIX = "QuantumRenderer";

    private DisplayThreadPriority() {
    }

    /** Must be called on the JavaFX Application Thread, after the toolkit has started. */
    static void boostDisplayThreads() {
        Thread.currentThread().setPriority(DISPLAY_PRIORITY);
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith(RENDER_THREAD_PREFIX)) {
                thread.setPriority(DISPLAY_PRIORITY);
            }
        }
    }
}
