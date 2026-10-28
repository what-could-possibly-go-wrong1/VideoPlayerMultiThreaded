/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file (Specification Part 1).
 */
package comp3011;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The catalogue of frame processors (compositing effects) that can be requested on the command line.
 *
 * <p>
 * Each constant binds together everything the rest of the program needs to know about one effect: its short
 * (single-letter) flag, its long flag, its help description and how to construct it. Keeping these in one table means
 * that the help text, the parser and the effect construction can never drift out of step with each other.
 * </p>
 *
 * <p>
 * <b>Adding a new effect:</b> write the new {@link FrameProcessor} subclass, then add one constant here (in the
 * position it should appear in the help). Nothing else needs to change - {@link CommandLineController} parses and
 * prints help from this table, and the playback models instantiate effects through {@link #instantiate(List)}.
 * </p>
 *
 * <p>
 * The declaration order below is the order the effects are listed in the help text, which TITAN checks letter for
 * letter - do not reorder.
 * </p>
 */
public enum FrameProcessorOption {
    NUMBER_FRAMES('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
    SCRATCH_FRAMES('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
    FLICKER_FRAMES('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
    YELLOW_FRAMES('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
    VIGNETTE_FRAMES('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
    DUST_FRAMES('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
    JITTER_FRAMES('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
    MOTTLE_FRAMES('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
    BLEED_FRAMES('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
    PEPPER_FRAMES('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

    private final char shortFlag;
    private final String longName;
    private final String description;
    private final Supplier<FrameProcessor> factory;

    FrameProcessorOption(char shortFlag, String longName, String description, Supplier<FrameProcessor> factory) {
        this.shortFlag = shortFlag;
        this.longName = longName;
        this.description = description;
        this.factory = factory;
    }

    public char shortFlag() {
        return shortFlag;
    }

    /** The long flag including its leading double dash, e.g. {@code --number-frames}. */
    public String longFlag() {
        return "--" + longName;
    }

    public String description() {
        return description;
    }

    public static Optional<FrameProcessorOption> fromShortFlag(char flag) {
        for (FrameProcessorOption option : values()) {
            if (option.shortFlag == flag) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    /** Looks up an option by its full long flag, e.g. {@code --dust-frames}. */
    public static Optional<FrameProcessorOption> fromLongFlag(String flag) {
        for (FrameProcessorOption option : values()) {
            if (option.longFlag().equals(flag)) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    /**
     * Constructs one new {@link FrameProcessor} per requested option, in exactly the requested order.
     *
     * <p>
     * Order matters for two reasons: effects are applied in list order, and several effects number their instances
     * from a static counter at construction time (so the second {@code -d} gets a different, but reproducible, random
     * seed from the first). This method must therefore be called once, from a single thread, before playback starts -
     * both playback models do so from their constructor on the JavaFX application thread.
     * </p>
     */
    public static List<FrameProcessor> instantiate(List<FrameProcessorOption> options) {
        List<FrameProcessor> processors = new ArrayList<>(options.size());
        for (FrameProcessorOption option : options) {
            processors.add(option.factory.get());
        }
        return Collections.unmodifiableList(processors);
    }
}
