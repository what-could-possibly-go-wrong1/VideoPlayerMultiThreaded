// STUDY COPY - same logic as DisplayThreadPriority.java, scruffier. neat one goes to gradescope
package comp3011;

/*
 * bumps up the priority of the 2 javafx threads that actually put frames on screen
 *
 * why: frames we prepared early are WASTED if the screen doesnt get redrawn on time.
 *   - "JavaFX Application Thread" = our display logic (pump etc)
 *   - "QuantumRenderer-0"         = javafx's render thread, turns the scene into pixels
 * both use barely any cpu but they need it PROMPTLY
 *
 * we already run our own heavy threads BELOW normal (workers 3, decoder 4) so they stay out of the way.
 * but on a shared machine (titan on deadline night...) OTHER programs compete too, at normal priority.
 * display threads above normal -> they win vs those as well.
 * windows actually honours java priorities (-> THREAD_PRIORITY_ABOVE_NORMAL). elsewhere just a hint, harmless
 *
 * no javafx api gives you the render thread so we find it by NAME. if javafx ever renames it, the boost just
 * doesnt apply to it - nothing breaks
 */
final class DisplayThreadPriority {
    static final int DISPLAY_PRIORITY = Thread.NORM_PRIORITY + 2;   // = 7
    static final String RENDER_THREAD = "QuantumRenderer";

    private DisplayThreadPriority() {}   // just a static helper

    // call ON the javafx thread, after the toolkit started (VideoPlayerModel's constructor does)
    static void boostDisplayThreads() {
        Thread.currentThread().setPriority(DISPLAY_PRIORITY);
        for (Thread t : Thread.getAllStackTraces().keySet())
            if (t.getName().startsWith(RENDER_THREAD)) t.setPriority(DISPLAY_PRIORITY);
    }
}
