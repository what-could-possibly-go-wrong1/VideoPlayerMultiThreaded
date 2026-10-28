/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *      - Spec Part 1: frame processor options (long, short and stacked short forms), applied in command-line
 *        order and repeatable; help text extended exactly as specified.
 *      - Parsing split into small single-purpose methods; output stream injectable for regression testing.
 *      - Hidden, out-of-spec --single-threaded switch selecting the original reference playback model.
 *
 * Copyright 2026 Simon Ratcliffe
 */
// parses launch options and retains the effect order, including repeated options, so the processing sequence matches what the user requested.
package comp3011;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 *
 * <p>
 * Command-line conventions supported:
 * </p>
 * <ul>
 * <li>Every option has a short form ({@code -d}) and a long form ({@code --dust-frames}).</li>
 * <li>Short forms may be stacked behind a single dash ({@code -jfddn}) and mixed freely with long forms.</li>
 * <li>Options and the video file may appear in any order, e.g. {@code -j movie.mp4 -fd}.</li>
 * <li>Frame processor options are collected in the order given, including repeats, because effects are applied in
 * command-line order and repeating an effect stacks (intensifies) it. The table of available effects lives in
 * {@link FrameProcessorOption}; this class only deals with turning text into that ordered list.</li>
 * </ul>
 *
 * <p>
 * Out-of-spec options for testing/configuration are deliberately <b>not</b> shown in the help text (TITAN checks the
 * help letter for letter). There is currently one: {@value #SINGLE_THREADED_FLAG} plays using the original single
 * threaded reference model, for side-by-side performance and correctness comparison.
 * </p>
 */
public class CommandLineController {
    /** Hidden option: play with the original single threaded model. Not listed in the help text. */
    static final String SINGLE_THREADED_FLAG = "--single-threaded";

    private static final String USAGE = "Usage: VideoPlayer [options] [video-file]";

    private final String[] args;
    private final PrintStream out;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private boolean singleThreadedRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private final List<FrameProcessorOption> frameProcessorOptions = new ArrayList<>();

    public CommandLineController(String[] args) {
        this(args, System.out);
    }

    // Lets tests capture what would have been printed to the console.
    CommandLineController(String[] args, PrintStream out) {
        this.args = args.clone();
        this.out = out;
        parse();
        if (errorMessage != null) {
            out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean isSingleThreadedRequested() {
        return singleThreadedRequested;
    }

    /** The requested effects in command-line order, repeats included. */
    public List<FrameProcessorOption> getFrameProcessorOptions() {
        return Collections.unmodifiableList(frameProcessorOptions);
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    /** The complete help text exactly as printed by {@code --help}, one line per element. */
    static List<String> helpLines() {
        List<String> lines = new ArrayList<>();
        lines.add(USAGE);
        lines.add("");
        lines.add("Options:");
        lines.add("  -h, --help         Show this help message");
        lines.add("  -a, --audio        Play audio");
        lines.add("  -x, --maximise     Open the player maximised");
        lines.add("  -1, --monitor-1    Open the player on display 1");
        lines.add("  -2, --monitor-2    Open the player on display 2");
        lines.add("");
        lines.add("Frame processors:");
        for (FrameProcessorOption option : FrameProcessorOption.values()) {
            lines.add(String.format("  -%c, %-19s%s", option.shortFlag(), option.longFlag(), option.description()));
        }
        lines.add("");
        lines.add("Frame processors are applied in command-line order and may be repeated.");
        lines.add("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        lines.add("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
        return lines;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                parseLongOption(arg);
            } else if (arg.startsWith("-") && arg.length() > 1) {
                parseShortOptions(arg);
            } else if (arg.startsWith("-")) {
                errorMessage = "Unknown option: " + arg;
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = USAGE;
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    private void parseLongOption(String arg) {
        switch (arg) {
            case "--help" -> helpRequested = true;
            case "--audio" -> audioRequested = true;
            case "--maximise" -> maximiseRequested = true;
            case "--monitor-1" -> setDisplayId(1);
            case "--monitor-2" -> setDisplayId(2);
            case SINGLE_THREADED_FLAG -> singleThreadedRequested = true;
            default -> {
                Optional<FrameProcessorOption> option = FrameProcessorOption.fromLongFlag(arg);
                if (option.isPresent()) {
                    frameProcessorOptions.add(option.get());
                } else {
                    errorMessage = "Unknown option: " + arg;
                }
            }
        }
    }

    // A single-dash argument is one or more short flags stacked together, e.g. "-a" or "-jfddn". Each letter is
    // handled exactly as if it had been given on its own, left to right, so "-jf" is identical to "-j -f".
    private void parseShortOptions(String arg) {
        for (int i = 1; i < arg.length(); i++) {
            parseShortOption(arg.charAt(i));
        }
    }

    private void parseShortOption(char flag) {
        switch (flag) {
            case 'h' -> helpRequested = true;
            case 'a' -> audioRequested = true;
            case 'x' -> maximiseRequested = true;
            case '1' -> setDisplayId(1);
            case '2' -> setDisplayId(2);
            default -> {
                Optional<FrameProcessorOption> option = FrameProcessorOption.fromShortFlag(flag);
                if (option.isPresent()) {
                    frameProcessorOptions.add(option.get());
                } else {
                    errorMessage = "Unknown option: -" + flag;
                }
            }
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        for (String line : helpLines()) {
            out.println(line);
        }
    }
}
