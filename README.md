# COMP3011 Assignment 2 - Multi-threaded Video Player

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

Starter code by Simon Ratcliffe (in collaboration with GPT-5.6 Terra). Modifications and new files by
`<Student Name> (<Student Number>)`, in collaboration with Claude (Anthropic). Every modified source file says what
changed in its header; every new file is marked as new.

---

## Quick start

```
mvn compile javafx:run "-Djavafx.args=--help"
mvn compile javafx:run "-Djavafx.args=-a -nssnfwyvdjmbp movie.mp4"
mvn verify                      # unit tests, frame-accuracy tests and the HD load test
```

| Maven phase | What it does here |
|---|---|
| `mvn validate` / `compile` | As before. |
| `mvn package` | Runs the unit tests (`*Test.java`, Surefire) and builds the jar. |
| `mvn verify` | Additionally runs the integration tests (`*IT.java`, Failsafe): frame accuracy against the single threaded reference on real decoded video, and the HD load test. The load test writes `target/performance-report.txt`. |

Tune the load test with `-Dperf.effects=bbmmpp -Dperf.frames=96 -Dperf.workers=8`, e.g.
`mvn verify -Dit.test=PlaybackPerformanceIT -Dperf.effects=nssnfwyvdjmbp`.

---

## Part 1 - Frame processors on the command line

```
Frame processors:
  -n, --number-frames    Render the frame number onto each frame
  ...
  -p, --pepper-frames    Pepper frames with dark spots/blotches
```

* **`FrameProcessorOption`** (new enum) is the single table of effects: short flag, long flag, help description and
  constructor. The help text, the parser and effect construction all read from it, so they cannot drift apart.
  **To add an effect:** write the `FrameProcessor` subclass and add one line to this enum - nothing else changes.
* **`CommandLineController`** turns arguments into an ordered `List<FrameProcessorOption>`. It supports long forms,
  short forms, stacked short forms (`-jfddn`), any mix of them, repeats, and the video file in any position
  (`-j movie.mp4 -fd`). Launch options can be stacked too (`-ax`).
* Ownership: the list flows `VideoPlayerApp` -> `VideoPlayerController` -> the model, whose constructor instantiates
  the effects **once, on one thread, in command-line order**. That order matters because stacked effects number
  themselves from a static counter, which seeds each instance's (reproducible) randomness.
* `CommandLineControllerTest` checks the help text letter for letter and that all the specification's equivalent
  command lines produce identical effect lists.

Hidden, out-of-spec switch (not in the help, as TITAN requires): `--single-threaded` plays with the original
single threaded model for side-by-side comparison.

---

## Part 2 - Threading architecture

### The problem

In the original, one thread (the JavaFX application thread) decoded every frame, applied every effect, converted it
for display, fed the sound card and ran the GUI. Once effects take longer than one frame period (41.7 ms at 24 fps)
everything stalls together: video falls behind, audio stutters and the GUI becomes sluggish, while the other cores
sit idle.

### The design

```
                     +--------------------+   AudioChunk    +------------------+
                +--->|   audio samples    |---------------->|  audio-output    |---> speakers
                |    +--------------------+   (queue)       |  thread          |
 +-----------------+                                        +------------------+
 |  video-decoder  |   pooled frame copy   +-----------------------+                  ^
 |  thread         |---------------------->|  frame-worker-N       |  effects + Image |  PlaybackClock
 |  (owns FFmpeg)  |       (executor)      |  threads (per core)   |------+           |  (shared)
 +-----------------+                       +-----------------------+      |           v
          |                                                               |  +------------------+
          |  DecodedFrame(future), in decode order                        +->|  JavaFX thread   |---> screen
          +--------------------------------------------------------------->  |  (display, GUI)  |
                        bounded presentation queue                           +------------------+
```

| Thread | Class | Job |
|---|---|---|
| JavaFX application thread | `VideoPlayerModel` | GUI and user commands; on each heartbeat, shows the head frame of the presentation queue if it is finished and due. Never decodes or processes. |
| `video-decoder` | `VideoDecoder` | Sole owner of the FFmpeg grabber. Decodes, copies each picture into a pooled frame, submits it to the workers, publishes a `DecodedFrame` (holding a future) **in decode order**. Routes sound to the audio thread. Serves seeks. |
| `frame-worker-N` (one per core, less 1-2 kept free) | `FrameProcessingPipeline` | Runs the **whole** effect chain, in command-line order, on one frame, then converts it to a JavaFX `Image`. Different frames run on different cores at once. |
| `audio-output` | `AudioOutput` | Waits on the clock until each sound chunk is due, then writes it (blocking writes are fine on its own thread). |

**Keeping the screen smooth.** Preparing frames quickly is not enough: JavaFX must also get CPU time to draw
them, or frames are visibly dropped even though they were ready. So the design leaves cores free for everything else: two on machines with
8+ cores, one on 4-7 cores. (On TITAN's 8-core machine under heavy outside load, keeping only one core free let
overload show up as dropped frames; the second free core is there so that overload makes the player fall behind
gracefully instead, with every frame still shown.) On all machines it runs the effect workers below normal priority and the decoder just below normal, and gives the
(lightweight) audio thread above-normal priority. The GUI and render threads therefore always win the CPU when
they need it, and the effects soak up whatever is left. The look-ahead buffer is capped at about one second
(24 frames) to bound memory use.

Two display rules complete this (`VideoPlayerModel.prerollComplete` and `slipIfLate`):

* **Pre-roll:** the clock starts only once two frames per worker are ready. Each batch is processed in parallel,
  so this costs only a fraction of a second, but it cushions a slow start (busy machine, cold JIT): the first frames
  are no longer due before their workers have finished them.
* **Display threads first:** the JavaFX application and render threads are raised to the highest priority short of
  `MAX_PRIORITY`
  (`DisplayThreadPriority`). Lowering our own heavy threads keeps *them* out of the way, but on a shared machine
  other programs compete too; this lets drawing win against those as well. It was added after TITAN, on a busy
  evening, reported dropped frames that our own threads could not have caused.
* **Slip, don't rush:** if a frame is ever shown more than half a frame late, the schedule slips back by the
  lateness instead of racing through the backlog. Racing would put frames on screen for a single 16.7 ms refresh,
  which looks (and to TITAN's screen capture, measures) like dropped frames. Measured under overload on a 2-core
  machine: frames shown for one refresh or less went from 55 to 0, and in no case is a frame number ever skipped.

`PlaybackSession` bundles everything that lives for one open of the media (grabber, decoder, worker pool, audio)
and shuts it down in a safe order. `PlaybackClock` maps media time to wall-clock time and is shared by the display
and audio threads.

### Why frame-level (data) parallelism

Three ways to split the work were considered:

* **One thread per effect (task pipeline):** throughput is capped by the slowest effect (bleed is many times slower
  than black-and-white), it cannot use more cores than there are effects, and it costs a hand-off per effect per
  frame.
* **Split each frame into tiles:** effects that read neighbouring pixels (jitter, the bleed blur) would need
  rewriting, and every effect would need a barrier.
* **Whole frames on different cores (chosen):** scales with the number of cores, leaves the effect algorithms
  untouched, and preserves the exact per-frame order of effects, so output is **pixel-identical** to the original.
  The cost is latency (each frame takes the full chain time), which is hidden by decoding ahead into a bounded
  buffer and by starting the playback clock only when the first processed frame is ready (a short pre-roll, which
  also follows each seek).

### Performance

The integration load test (`PlaybackPerformanceIT`, 1920x800 at 24 fps, effects `-nssnfwyvdjmbp`, same work and
same output on both paths), measured on a 2-core cloud VM:

| | frames per second | fraction of real time |
|---|---|---|
| single threaded reference | 20.4 | 0.85x (falls behind) |
| multi-threaded | 36.8 | 1.53x |
| **speed-up** | **1.80x** | |

With 2 cores the speed-up cannot exceed 2x. It grows with core count, because the workers scale with
`availableProcessors()` (less one core kept free for rendering - see below). In the running player with the same 13 effects, the single threaded model's headroom fell
steadily (−1.1 s to −1.7 s and growing) while the multi-threaded player held a steady +0.2 s on the same machine.

---

## Optimisations evaluated

Before optimising, measure. Two integration tests record the evidence (run by `mvn verify`; reports are written
to `target/`).

**Where the time goes (`EffectCostLedgerIT`):** milliseconds of one core per 1920x800 frame, measured on a 2-core
cloud VM (numbers vary by machine; the proportions are what matter):

| effect | ms | share |
|---|---|---|
| bleed | 27.7 | 53% |
| vignette | 8.1 | 15% |
| black-and-white | 5.5 | 10% |
| mottle | 5.4 | 10% |
| yellow | 3.8 | 7% |
| flicker | 1.8 | 3% |
| jitter, pepper, dust, number, scratch | < 0.4 each | < 1% each |

**All 11 effects applied once cost 52.7 ms, which is 126% of a single core's 41.7 ms budget per frame at 24 fps.** So
even one pass of the full set cannot play in real time on one core, whatever the code quality, which is the case
for spreading frames across cores. TITAN's stress ladder stacks these four times over.

**Scanline fusion, evaluated and rejected (`ScanlineFusionExperimentIT`):** flicker, black-and-white and yellow
are *per-pixel* effects, so a run of them could be applied to one cache-sized band of 8 rows at a time instead of
each sweeping the whole 4.6 MB frame (loop fusion / cache blocking). The experiment runs the real, unmodified
effects on zero-copy band views of the frame and proves the result pixel-identical. It also proves that the
vignette, which depends on each pixel's position, cannot be fused this way. Result: **0.96x to 1.15x** (the
latter for a hand-written fused loop in an earlier prototype). The effects are compute-bound (a lookup or
arithmetic per byte), not memory-bound, so this was not worth a new effect API and a second implementation of
every per-pixel effect. The parallel design already gets its speed-up from using more cores, which is where the
headroom actually is.

## Concurrency correctness

### Shared state and how each item is protected

| Shared item | Threads | Protection |
|---|---|---|
| FFmpeg grabber and its reused frame buffer | decoder | **Thread confinement.** Only the decoder thread touches it after `start()`. Seeks arrive as messages. The grabber is closed only after the decoder thread has been joined. |
| Picture pixels | decoder -> one worker | **Copy, then ownership hand-off.** Every picture is copied out of the grabber's reused buffer into a `FramePool` frame (otherwise the next grab would overwrite pixels mid-effect: frame tearing). Each pooled frame has exactly one owner at a time: pool -> decoder -> one worker -> pool. Hand-offs go through a `BlockingQueue`/executor, which give the necessary happens-before ordering. |
| Effect instances (`FrameProcessor`s) | all workers | **Immutable after initialisation, plus per-thread scratch.** `initialise` runs on the decoder thread before any frame is submitted. `process` writes only to its own frame and to locals. Three effects kept per-frame working storage in fields and were **real races**: `FrameJitterer` (copy buffer), `FrameBleeder` (mask buffers) and `FrameMottler` (damage-mask cache, plus block scales passed between methods via fields). These now use `ThreadLocal` scratch or call-local variables, with identical arithmetic. The contract is documented on `FrameProcessor`. |
| Static `nextInstanceIndex` counters | constructor | **Single-threaded construction.** All effects are built once, on the JavaFX thread, before any worker exists (`FrameProcessorOption.instantiate`). |
| Processed frames | worker -> JavaFX | `CompletableFuture` (safe publication). The JavaFX thread consumes them strictly in decode order from the FIFO presentation queue, so out-of-order completion cannot reorder frames. |
| Presentation queue, audio chunk queue | decoder -> JavaFX / audio | `ArrayBlockingQueue` / `LinkedBlockingQueue`. Both are bounded by the frame pool and presentation queue, giving backpressure. |
| `PlaybackClock` | JavaFX, audio | `synchronized` methods, with `notifyAll` on every change so the waiting audio thread reacts instantly to pause, seek and resume. |
| `audioOutputEnabled`, requested epoch, stop flags | several | `volatile` single-writer flags. |

### Seeking without locks: epochs

Each start or seek begins a new **epoch** (`VideoPlayerModel.beginEpoch`). Everything the decoder produces is
tagged with the epoch it was decoded in. After a seek, stale items are discarded wherever they appear: by the
display, by the audio thread (via the clock) and by the workers (a queued stale frame skips its effects entirely).
Seeks therefore never have to wait for in-flight work, and rapid seeks coalesce to the newest.

### Why it cannot deadlock

* There is only **one lock** (the clock's monitor). It is never held while acquiring anything else, and nothing
  blocks while holding it except `wait()`, which releases it.
* Blocking is one-directional: the decoder may block on the consumers (full queue or empty pool), but no consumer
  ever blocks waiting for the decoder. The JavaFX thread only ever `peek`/`poll`s. Workers never block. With no
  cycle in the wait-for graph, deadlock is impossible.
* Shutdown: stop flag, then `interrupt()`, then clear the queue (in case the decoder is blocked on `put`), then
  `join` (with timeout). Workers are stopped with `shutdownNow` + `awaitTermination` before the effects are ever
  re-initialised.

### Evidence

* `FrameProcessingPipelineTest` runs all 11 effects, with jitter, mottle and bleed repeated, on 8 threads, three
  times, and requires every frame to be pixel-identical to sequential processing. **Against the original effect
  classes this test fails**: in one run 171 of 200 HD frames were corrupted by jitter/bleed alone, and the original
  mottler crashed outright with a `NullPointerException` on a half-written cache.
* `MultiThreadedPlaybackIT` decodes a real video with sound through the real `VideoDecoder` and compares every frame
  with the single threaded reference algorithm. It also checks end of stream, audio routing and that after a
  mid-stream seek only correct new-position frames arrive.
* `ThreadLifecycleIT` stops playback at the three moments hardest to stop from (frames in flight on the workers,
  the decoder blocked on a full queue, the decoder parked after the end of the media) and checks, by a census of the
  player's thread families, that every playback thread terminates within 2 s (measured: 1-50 ms). It also checks that
  12 start/stop cycles leave no threads behind. Its peak census is a negative control, and the test was verified to
  catch a deliberately leaked worker pool.
* `TimelineScrubbingIT` scrubs the timeline (40 seeks a few ms apart, in three gestures including jumps past the end)
  while a second thread consumes frames like the display. It asserts no deadlock, that epochs never run backwards,
  that the landing frames are pixel-identical to a clean decode, and that the first frame of *every* seek is exactly
  where a clean single-threaded seek lands. A planted bug (labelling frames with the latest requested epoch instead of
  the one they were decoded in) is caught on every run. The test also exposed an FFmpeg library quirk, reproduced
  without threads: re-seeking to the position just seeked to delivers that frame twice, which the original player
  shares.
* During development, 200 HD frames of `-nssnfwyvdjmbp` and of `-jjmmbbddpp` were also hashed with the
  **unmodified starter code** and matched exactly by the new player on 2 and 8 workers.

---

## The single threaded reference

The original model is kept, renamed to `SingleThreadedVideoPlayerModel`, with its playback logic unchanged, so the
new design can always be compared against it. It is switched in with the hidden `--single-threaded` option.
Both models implement `PlaybackModel`, and `VideoPlayerController` chooses between them. No code of the old design
is mixed into the new classes.

## Behaviour notes

* Functional behaviour (keys, buttons, seeking rules, pause, start over, end of playback, window placement and
  sizing) is unchanged.
* Console trace: the headroom line is kept and now also shows how many frames are buffered ahead. Because frames are
  prepared in advance, headroom can exceed one frame period.
* If the machine has no usable sound device, the multi-threaded player now plays silently with a warning instead of
  failing (the reference model is unchanged).
