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
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Scrubs the timeline the way an impatient person does - dozens of seeks a few milliseconds apart while frames are
 * still being decoded and processed - and checks that playback always lands cleanly.
 *
 * <p>
 * Seeking is the most delicate concurrency in the player: each seek starts a new <i>epoch</i>, the decoder may be
 * blocked on a full queue or parked at the end of the media when it arrives, workers may be mid-effect on frames
 * that have just become stale, and the display is consuming at its own pace throughout (see {@link VideoDecoder}).
 * The README argues this cannot deadlock or show a wrong frame; this test tries hard to make it do both.
 * </p>
 *
 * <h2>Set-up</h2>
 * <p>
 * Two threads contend, as in the real player. The test thread is the <i>scrubber</i>, firing seeks following a
 * {@link Gesture}. A <i>projectionist</i> thread plays the display's part, taking frames off the presentation queue
 * at an uneven pace. Every gesture ends by landing on the same position, so the frames after the landing can be
 * compared with one trusted, single threaded decode from that position. Pauses between seeks come from a seeded
 * random generator, so a failing run can be reproduced.
 * </p>
 *
 * <h2>Rules of the timeline (asserted for every gesture)</h2>
 * <ol>
 * <li><b>It always lands:</b> after the last seek, frames from the landing position arrive - no deadlock, no
 * stall.</li>
 * <li><b>Time never runs backwards:</b> epochs appear in the presentation queue in non-decreasing order, because
 * the decoder applies seeks in the order they were requested.</li>
 * <li><b>It lands on the right frames:</b> the first frames after landing are consecutive and pixel-identical to
 * the trusted reference.</li>
 * <li><b>Every frame wears the right label:</b> for <i>every</i> seek, not only the landing, the first frame
 * tagged with that seek's epoch is exactly the frame a clean, single threaded seek to that position lands on, and
 * the frames under one epoch are consecutive. (FFmpeg does not always land exactly on the requested time - with
 * interleaved audio it can land a frame or two early - so the trusted answer is "where a clean seek lands", which the
 * original single threaded player would also show.) A frame decoded at the old position but labelled with the new epoch would slip past rules 1-3
 * whenever the timing is lucky; checking all forty-odd seeks per gesture gives it nowhere to hide. (This rule was
 * added after a planted bug of exactly that kind passed the first three.)</li>
 * <li><b>Nothing breaks along the way:</b> no decode failure is ever published.</li>
 * </ol>
 */
class TimelineScrubbingIT {
    private static final long LANDING_US = 1_000_000L; // Every gesture ends here: 1 s into the 2.5 s test video.
    private static final int LANDING_FRAMES_CHECKED = 12;
    private static final int WORKERS = 4;
    private static final int QUEUE_CAPACITY = 8;
    private static final long MAX_SETTLE_MS = 5_000;
    private static final long SCRUB_SEED = 3011L;
    private static final String EFFECTS = "nj"; // Frame numbers plus jitter, which keeps per-thread working state.

    /** Ways a person can abuse a timeline. Each yields the positions to seek to, in order, before the landing. */
    private enum Gesture {
        ZIGZAG("drag back and forth across the whole timeline") {
            @Override
            List<Long> positionsUs(Random random) {
                List<Long> positions = new ArrayList<>();
                for (int sweep = 0; sweep < 40; sweep++) {
                    positions.add((sweep % 2 == 0 ? 200_000L : 2_200_000L) + sweep * 1_000L);
                }
                return positions;
            }
        },
        HAMMER_ONE_SPOT("mash the same position over and over") {
            @Override
            List<Long> positionsUs(Random random) {
                List<Long> positions = new ArrayList<>();
                for (int press = 0; press < 40; press++) {
                    positions.add(500_000L);
                }
                return positions;
            }
        },
        WILD_JUMPS("random jumps, some past the end of the media") {
            @Override
            List<Long> positionsUs(Random random) {
                List<Long> positions = new ArrayList<>();
                for (int jump = 0; jump < 40; jump++) {
                    positions.add((long) random.nextInt(3_500_000)); // Up to 1 s beyond the 2.5 s of media.
                }
                return positions;
            }
        };

        final String description;

        Gesture(String description) {
            this.description = description;
        }

        abstract List<Long> positionsUs(Random random);
    }

    @Test
    void everyScrubLandsCleanlyOnTheRightFrames() throws Exception {
        File video = TestFrames.generateVideo(new File("target/test-media/accuracy.mp4"), 480, 270, 60, true);
        List<FrameProcessor> effects = FrameProcessingPipelineTest.effects(EFFECTS);
        List<String> trustedLanding = MultiThreadedPlaybackIT.ReferencePlayer.decodeAndProcess(
                video, effects, LANDING_US, LANDING_FRAMES_CHECKED);

        CleanLandings cleanLandings = new CleanLandings(video);
        List<String> reportRows = new ArrayList<>();
        for (Gesture gesture : Gesture.values()) {
            ScrubResult result = scrub(video, effects, gesture);

            assertTrue(result.settleMs() <= MAX_SETTLE_MS,
                    gesture + ": landing frames took " + result.settleMs() + " ms to arrive (stall or deadlock?)");
            assertTrue(result.epochsNeverWentBackwards(), gesture + ": an older epoch appeared after a newer one");
            assertEquals(0, result.failuresSeen(), gesture + ": the decoder published a failure");
            List<String> landed = withoutRepeatedLandingFrame(result.landingFingerprints());
            assertEquals(trustedLanding.subList(0, landed.size()), landed,
                    gesture + ": the frames after landing differ from a clean decode of that position");
            assertTrue(result.landingFramesAreConsecutive(), gesture + ": landing frames skipped or repeated");
            String mislabelled = result.firstMislabelledFrame(positions(gesture), cleanLandings);
            assertTrue(mislabelled == null, gesture + ": " + mislabelled);

            reportRows.add(String.format(Locale.ROOT, "  %-16s %-46s %3d seeks  settled in %4d ms  "
                    + "%3d stale frames dropped, %3d of them never processed",
                    gesture.name().toLowerCase(Locale.ROOT).replace('_', ' '), gesture.description,
                    result.seeksFired(), result.settleMs(), result.staleFramesSeen(), result.staleFramesSkipped()));
        }
        writeReport(reportRows);
    }

    // FFmpeg can deliver the landing frame twice when a seek repeats the previous one (see followsOn below). The
    // repeat is the library's, not the player's, so it is dropped before comparing pixels with a clean decode.
    private static List<String> withoutRepeatedLandingFrame(List<String> fingerprints) {
        if (fingerprints.size() >= 2 && fingerprints.get(0).equals(fingerprints.get(1))) {
            return fingerprints.subList(1, fingerprints.size());
        }
        return fingerprints;
    }

    private static ScrubResult scrub(File video, List<FrameProcessor> effects, Gesture gesture) throws Exception {
        Random pacing = new Random(SCRUB_SEED + gesture.ordinal());
        FFmpegFrameGrabber grabber = MultiThreadedPlaybackIT.ReferencePlayer.openGrabber(video);
        BlockingQueue<VideoDecoder.PresentationItem<String>> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        try (FrameProcessingPipeline<String> pipeline =
                new FrameProcessingPipeline<>(effects, WORKERS, TestFrames::sha1);
                VideoDecoder<String> decoder = new VideoDecoder<>(grabber,
                        MultiThreadedPlaybackIT.ReferencePlayer.streamInfo(video, grabber), grabber.hasAudio(),
                        pipeline, (epoch, timestampUs, frame) -> {
                        }, queue, 0)) {
            decoder.start();

            List<Long> positions = positions(gesture);
            long landingEpoch = positions.size();
            Projectionist projectionist = new Projectionist(queue, landingEpoch, new Random(SCRUB_SEED * 31));
            CompletableFuture<Void> watching = CompletableFuture.runAsync(projectionist::watch);

            long landedAtNs = 0;
            for (int i = 0; i < positions.size(); i++) {
                long epoch = i + 1;
                if (epoch == landingEpoch) {
                    landedAtNs = System.nanoTime();
                }
                decoder.seek(epoch, positions.get(i));
                Thread.sleep(pacing.nextInt(4)); // 0-3 ms between seeks: faster than any decoder can keep up.
            }

            watching.get(MAX_SETTLE_MS * 2, TimeUnit.MILLISECONDS);
            long settleMs = TimeUnit.NANOSECONDS.toMillis(projectionist.firstLandingFrameAtNs() - landedAtNs);
            return projectionist.result(positions.size(), Math.max(0, settleMs));
        } finally {
            grabber.stop();
            grabber.close();
        }
    }

    // The seek targets in order, epoch n seeking to element n-1. Deterministic per gesture (seeded), so the checks
    // can recompute exactly the positions the scrub used.
    private static List<Long> positions(Gesture gesture) {
        List<Long> positions = new ArrayList<>(gesture.positionsUs(new Random(SCRUB_SEED + gesture.ordinal())));
        positions.add(LANDING_US);
        return positions;
    }

    /**
     * Where a clean seek lands: the first frame a fresh, single threaded decoder shows after seeking to a position.
     * Answers are remembered, as the gestures revisit positions.
     */
    private static final class CleanLandings {
        private final File video;
        private final Map<Long, Integer> frameNumberByPositionUs = new HashMap<>();

        CleanLandings(File video) {
            this.video = video;
        }

        /** The frame number a clean seek to {@code positionUs} lands on, or -1 if it is past the end. */
        int frameNumberAt(long positionUs) throws Exception {
            Integer known = frameNumberByPositionUs.get(positionUs);
            if (known != null) {
                return known;
            }
            FFmpegFrameGrabber grabber = MultiThreadedPlaybackIT.ReferencePlayer.openGrabber(video);
            try {
                grabber.setTimestamp(positionUs);
                Frame frame;
                do {
                    frame = grabber.grab();
                } while (frame != null && frame.image == null);
                int landed = frame == null ? -1 : grabber.getFrameNumber();
                frameNumberByPositionUs.put(positionUs, landed);
                return landed;
            } finally {
                grabber.stop();
                grabber.close();
            }
        }
    }

    private static void writeReport(List<String> rows) throws Exception {
        String report = "Timeline scrubbing: rapid seeks while frames are in flight\n" + String.join("\n", rows) + "\n";
        System.out.print(report);
        Files.writeString(new File("target/timeline-scrubbing-report.txt").toPath(), report);
    }

    /**
     * Plays the display's part: takes everything off the presentation queue at an uneven pace, keeps a log of what
     * it saw, and stops once it has the landing frames it needs.
     */
    private static final class Projectionist {
        private final BlockingQueue<VideoDecoder.PresentationItem<String>> queue;
        private final long landingEpoch;
        private final Random pace;
        private final List<Long> epochsInArrivalOrder = new ArrayList<>();
        private final List<VideoDecoder.DecodedFrame<String>> staleFrames = new ArrayList<>();
        private final List<VideoDecoder.DecodedFrame<String>> landingFrames = new ArrayList<>();
        private final List<VideoDecoder.DecodedFrame<String>> everyFrame = new ArrayList<>();
        private int failuresSeen;
        private volatile long firstLandingFrameAtNs;

        Projectionist(BlockingQueue<VideoDecoder.PresentationItem<String>> queue, long landingEpoch, Random pace) {
            this.queue = queue;
            this.landingEpoch = landingEpoch;
            this.pace = pace;
        }

        void watch() {
            try {
                while (landingFrames.size() < LANDING_FRAMES_CHECKED) {
                    VideoDecoder.PresentationItem<String> item = queue.poll(MAX_SETTLE_MS, TimeUnit.MILLISECONDS);
                    if (item == null) {
                        return; // Nothing for too long: reported as a stall by the settle-time assertion.
                    }
                    log(item);
                    if (pace.nextInt(4) == 0) {
                        Thread.sleep(pace.nextInt(3)); // An occasionally slow display lets the queue fill up.
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void log(VideoDecoder.PresentationItem<String> item) {
            epochsInArrivalOrder.add(item.epoch());
            if (item instanceof VideoDecoder.DecodeFailure) {
                failuresSeen++;
            } else if (item instanceof VideoDecoder.DecodedFrame<String> frame) {
                everyFrame.add(frame);
                if (item.epoch() == landingEpoch) {
                    if (landingFrames.isEmpty()) {
                        firstLandingFrameAtNs = System.nanoTime();
                    }
                    landingFrames.add(frame);
                } else {
                    staleFrames.add(frame);
                }
            }
        }

        long firstLandingFrameAtNs() {
            return firstLandingFrameAtNs == 0 ? Long.MAX_VALUE / 2 : firstLandingFrameAtNs;
        }

        ScrubResult result(int seeksFired, long settleMs) throws Exception {
            List<String> fingerprints = new ArrayList<>();
            List<Integer> frameNumbers = new ArrayList<>();
            for (VideoDecoder.DecodedFrame<String> frame : landingFrames) {
                fingerprints.add(frame.processed().get(30, TimeUnit.SECONDS).output());
                frameNumbers.add(frame.frameNumber());
            }
            int skipped = 0;
            for (VideoDecoder.DecodedFrame<String> stale : staleFrames) {
                if (stale.processed().get(30, TimeUnit.SECONDS) == null) {
                    skipped++; // Became stale before a worker reached it, so its effects never ran.
                }
            }
            return new ScrubResult(seeksFired, settleMs, List.copyOf(epochsInArrivalOrder), failuresSeen,
                    fingerprints, frameNumbers, staleFrames.size(), skipped, List.copyOf(everyFrame));
        }
    }

    /** Everything one scrub observed, plus the checks that interpret it. */
    private record ScrubResult(
            int seeksFired,
            long settleMs,
            List<Long> epochsInArrivalOrder,
            int failuresSeen,
            List<String> landingFingerprints,
            List<Integer> landingFrameNumbers,
            int staleFramesSeen,
            int staleFramesSkipped,
            List<VideoDecoder.DecodedFrame<String>> everyFrame) {

        boolean epochsNeverWentBackwards() {
            for (int i = 1; i < epochsInArrivalOrder.size(); i++) {
                if (epochsInArrivalOrder.get(i) < epochsInArrivalOrder.get(i - 1)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Checks every frame's epoch label against the seek that epoch stands for. Returns a description of the
         * first offending frame, or null if every label is right.
         */
        String firstMislabelledFrame(List<Long> seekPositionsUs, CleanLandings cleanLandings) throws Exception {
            long currentEpoch = -1;
            int previousFrameNumber = -1;
            int framesInEpoch = 0;
            for (VideoDecoder.DecodedFrame<String> frame : everyFrame) {
                if (frame.epoch() < 1) {
                    continue; // Decoded before the first seek: playback from the start, not part of the scrub.
                }
                if (frame.epoch() != currentEpoch) {
                    currentEpoch = frame.epoch();
                    long requestedUs = seekPositionsUs.get((int) currentEpoch - 1);
                    int cleanLanding = cleanLandings.frameNumberAt(requestedUs);
                    if (frame.frameNumber() != cleanLanding) {
                        return String.format(Locale.ROOT,
                                "epoch %d sought %d us: its first frame is #%d, but a clean seek lands on #%d",
                                currentEpoch, requestedUs, frame.frameNumber(), cleanLanding);
                    }
                    framesInEpoch = 0;
                } else if (!followsOn(previousFrameNumber, frame.frameNumber(), framesInEpoch)) {
                    return String.format(Locale.ROOT, "epoch %d jumps from frame #%d to #%d",
                            currentEpoch, previousFrameNumber, frame.frameNumber());
                }
                previousFrameNumber = frame.frameNumber();
                framesInEpoch++;
            }
            return null;
        }

        boolean landingFramesAreConsecutive() {
            for (int i = 1; i < landingFrameNumbers.size(); i++) {
                if (!followsOn(landingFrameNumbers.get(i - 1), landingFrameNumbers.get(i), i)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether {@code next} may follow {@code previous} within one seek's run of frames, where {@code position}
         * is how many frames of the run came before {@code next}. Frames must be consecutive - with one exception,
         * which is a quirk of the FFmpeg library rather than of this player: seeking straight back to the position
         * just seeked to can deliver that landing frame twice. That was reproduced with a plain decoder on a single
         * thread ("seek 0.5s: #12 #12 #13"), so the original player shows it too. So the second frame of a run may
         * repeat the first; nothing else may repeat or skip.
         */
        private static boolean followsOn(int previous, int next, int position) {
            return next == previous + 1 || (position == 1 && next == previous);
        }
    }
}
