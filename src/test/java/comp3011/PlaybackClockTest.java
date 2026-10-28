/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Unit tests of the clock shared by the display and audio threads. */
class PlaybackClockTest {
    private static final long MS = 1_000_000L;

    @Test
    void framesAreScheduledRelativeToTheAnchorFrame() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(1);
        assertFalse(clock.isDue(0, 0), "a reset clock is not running");

        clock.startAt(5_000_000, 1_000 * MS);
        assertEquals(1_000 * MS, clock.targetTimeNs(5_000_000));
        assertEquals(1_000 * MS + 41_667_000L, clock.targetTimeNs(5_041_667));
        assertTrue(clock.isDue(5_041_667, 1_042 * MS));
        assertFalse(clock.isDue(5_041_667, 1_041 * MS));
    }

    @Test
    void pausingShiftsTheScheduleByThePausedDuration() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(1);
        clock.startAt(0, 0);
        clock.pause(100 * MS);
        assertFalse(clock.isDue(50_000, 200 * MS), "nothing is due while paused");
        clock.resume(600 * MS);
        assertEquals(500 * MS + 50 * MS, clock.targetTimeNs(50_000));
    }

    @Test
    void delayingSlipsTheWholeScheduleLater() {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(1);
        clock.startAt(0, 0);
        clock.delay(30 * MS); // A frame was shown 30ms late...
        assertEquals(30 * MS + 41 * MS, clock.targetTimeNs(41_000), "...so later frames keep their full spacing");
    }

    @Test
    void audioWaiterIsReleasedWhenDue() throws Exception {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(7);
        clock.startAt(0, System.nanoTime());
        long start = System.nanoTime();
        assertTrue(clock.awaitDue(7, 50_000, 0)); // 50ms of media
        long waitedMs = (System.nanoTime() - start) / MS;
        assertTrue(waitedMs >= 45 && waitedMs < 1_000, "waited " + waitedMs + "ms");
    }

    @Test
    void audioWaiterIsReleasedAsStaleBySeek() throws Exception {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(1); // Not started: a waiter blocks indefinitely...
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return clock.awaitDue(1, 0, 0);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(50);
        assertFalse(waiter.isDone());
        clock.reset(2); // ...until a seek moves to a new epoch, which makes its audio stale.
        assertFalse(waiter.get(1, TimeUnit.SECONDS));
    }

    @Test
    void audioWaiterBlocksWhilePausedAndResumesAfter() throws Exception {
        PlaybackClock clock = new PlaybackClock();
        clock.reset(1);
        clock.startAt(0, System.nanoTime());
        clock.pause(System.nanoTime());
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return clock.awaitDue(1, 0, 0);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(100);
        assertFalse(waiter.isDone(), "audio must not play while paused");
        clock.resume(System.nanoTime());
        assertTrue(waiter.get(1, TimeUnit.SECONDS));
    }
}
