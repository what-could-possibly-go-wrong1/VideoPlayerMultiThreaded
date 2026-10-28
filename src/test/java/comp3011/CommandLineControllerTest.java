// checks command line controller test. These tests target a specific behaviour instead of relying on a large collection of unrelated assertions.
package comp3011;

import static comp3011.FrameProcessorOption.BLACK_AND_WHITE;
import static comp3011.FrameProcessorOption.BLEED_FRAMES;
import static comp3011.FrameProcessorOption.DUST_FRAMES;
import static comp3011.FrameProcessorOption.FLICKER_FRAMES;
import static comp3011.FrameProcessorOption.JITTER_FRAMES;
import static comp3011.FrameProcessorOption.MOTTLE_FRAMES;
import static comp3011.FrameProcessorOption.NUMBER_FRAMES;
import static comp3011.FrameProcessorOption.PEPPER_FRAMES;
import static comp3011.FrameProcessorOption.SCRATCH_FRAMES;
import static comp3011.FrameProcessorOption.VIGNETTE_FRAMES;
import static comp3011.FrameProcessorOption.YELLOW_FRAMES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Checks the command line interface against the specification: exact help text, and that every equivalent way of
 * writing the same effects (long, short, stacked, mixed, file anywhere) yields the same ordered effect list.
 */
class CommandLineControllerTest {

    // the help text exactly as specified (with the staff correction making every long option plural).
    private static final String EXPECTED_HELP = String.join(System.lineSeparator(),
            "Usage: VideoPlayer [options] [video-file]",
            "",
            "Options:",
            "  -h, --help         Show this help message",
            "  -a, --audio        Play audio",
            "  -x, --maximise     Open the player maximised",
            "  -1, --monitor-1    Open the player on display 1",
            "  -2, --monitor-2    Open the player on display 2",
            "",
            "Frame processors:",
            "  -n, --number-frames    Render the frame number onto each frame",
            "  -s, --scratch-frames   Render vertical film scratches",
            "  -f, --flicker-frames   Randomly dim frames",
            "  -w, --black-and-white  Convert frames to black and white",
            "  -y, --yellow-frames    Apply a warmer colour temperature",
            "  -v, --vignette-frames  Darken the frame edges",
            "  -d, --dust-frames      Render dust and hair marks",
            "  -j, --jitter-frames    Randomly displace frames by a few pixels",
            "  -m, --mottle-frames    Add cloudy emulsion mottling",
            "  -b, --bleed-frames     Bleed light into frames",
            "  -p, --pepper-frames    Pepper frames with dark spots/blotches",
            "",
            "Frame processors are applied in command-line order and may be repeated.",
            "Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,",
            "converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.") + System.lineSeparator();

    private static String movie;

    private static String movie() throws IOException {
        if (movie == null) {
            File file = Files.createTempFile("movies", ".mp4").toFile();
            file.deleteOnExit();
            movie = file.getPath();
        }
        return movie;
    }

    private static CommandLineController parse(String... args) {
        return new CommandLineController(args, new PrintStream(new ByteArrayOutputStream(), true));
    }

    private static String output(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new CommandLineController(args, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void helpTextMatchesSpecificationLetterForLetter() {
        assertEquals(EXPECTED_HELP, output("--help"));
        assertEquals(EXPECTED_HELP, output("-h"));
    }

    @Test
    void hiddenTestingOptionIsNotAdvertisedInHelp() {
        assertFalse(output("--help").contains("single"));
    }

    @Test
    void specificationExamplesAreAllEquivalent() throws IOException {
        List<FrameProcessorOption> expected = List.of(
                JITTER_FRAMES, FLICKER_FRAMES, DUST_FRAMES, DUST_FRAMES, NUMBER_FRAMES);

        assertEquals(expected, parse("--jitter-frames", "--flicker-frames", "--dust-frames", "--dust-frames",
                "--number-frames", movie()).getFrameProcessorOptions());
        assertEquals(expected, parse("-j", "-f", "-d", "-d", "-n", movie()).getFrameProcessorOptions());
        assertEquals(expected, parse("-jfddn", movie()).getFrameProcessorOptions());
        assertEquals(expected, parse("-jf", "--dust-frames", "-d", "--number-frames", movie())
                .getFrameProcessorOptions());
    }

    @Test
    void helpTextExampleAppliesEveryEffectInOrderWithRepeats() throws IOException {
        assertEquals(List.of(
                NUMBER_FRAMES, SCRATCH_FRAMES, SCRATCH_FRAMES, NUMBER_FRAMES, FLICKER_FRAMES, BLACK_AND_WHITE,
                YELLOW_FRAMES, VIGNETTE_FRAMES, DUST_FRAMES, JITTER_FRAMES, MOTTLE_FRAMES, BLEED_FRAMES,
                PEPPER_FRAMES),
                parse("-nssnfwyvdjmbp", movie()).getFrameProcessorOptions());
    }

    @Test
    void videoFileMayAppearAnywhere() throws IOException {
        CommandLineController controller = parse("-j", "-f", "-d", "-d", "-n", movie(), "-jfd");
        assertNull(controller.getErrorMessage());
        assertEquals(new File(movie()), controller.getVideoFile());
        assertEquals(List.of(JITTER_FRAMES, FLICKER_FRAMES, DUST_FRAMES, DUST_FRAMES, NUMBER_FRAMES,
                JITTER_FRAMES, FLICKER_FRAMES, DUST_FRAMES), controller.getFrameProcessorOptions());
    }

    @Test
    void launchOptionsCanBeStackedAndMixedWithEffects() throws IOException {
        CommandLineController controller = parse("-axb2", movie());
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(2, controller.getDisplayId());
        assertEquals(List.of(BLEED_FRAMES), controller.getFrameProcessorOptions());
        assertTrue(controller.shouldLaunchApplication());
    }

    @Test
    void everyEffectHasMatchingShortAndLongForms() throws IOException {
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            assertEquals(List.of(option), parse("-" + option.shortFlag(), movie()).getFrameProcessorOptions());
            assertEquals(List.of(option), parse(option.longFlag(), movie()).getFrameProcessorOptions());
        }
    }

    @Test
    void noEffectsByDefault() throws IOException {
        assertEquals(List.of(), parse(movie()).getFrameProcessorOptions());
    }

    @Test
    void unknownOptionsAreRejected() throws IOException {
        assertEquals("Unknown option: -q", parse("-jqd", movie()).getErrorMessage());
        assertEquals("Unknown option: --dust-frame", parse("--dust-frame", movie()).getErrorMessage());
        assertFalse(parse("--wobble", movie()).shouldLaunchApplication());
    }

    @Test
    void originalBehaviourIsPreserved() throws IOException {
        assertEquals("No video file specified.", parse("-d").getErrorMessage());
        assertEquals("Usage: VideoPlayer [options] [video-file]", parse(movie(), movie()).getErrorMessage());
        assertEquals("Only one display option can be used", parse("-12", movie()).getErrorMessage());
        assertTrue(parse("-z", "nonexistent.mp4").getErrorMessage() != null);
        assertEquals("File not found: nonexistent.mp4", parse("nonexistent.mp4").getErrorMessage());
    }

    @Test
    void singleThreadedReferenceModelIsSelectableButOffByDefault() throws IOException {
        assertFalse(parse(movie()).isSingleThreadedRequested());
        assertTrue(parse("--single-threaded", movie()).isSingleThreadedRequested());
    }

    @Test
    void effectsAreInstantiatedInCommandLineOrder() throws IOException {
        List<FrameProcessor> processors = FrameProcessorOption.instantiate(
                parse("-ndn", movie()).getFrameProcessorOptions());
        assertEquals(3, processors.size());
        assertTrue(processors.get(0) instanceof FrameNumberer);
        assertTrue(processors.get(1) instanceof FrameDuster);
        assertTrue(processors.get(2) instanceof FrameNumberer);
    }
}
