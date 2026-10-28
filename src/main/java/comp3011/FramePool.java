// reuses frame buffers so the decoder doesnt overwrite pixels while effect workers are still using them. The bounded pool also prevents unlimited decoding ahead.
package comp3011;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.bytedeco.javacv.Frame;

/**
 * A fixed size pool of reusable image frames, used to hand decoded pictures from the decoder thread to the effect
 * worker threads.
 *
 * <h2>Why a copy is needed at all</h2>
 * <p>
 * {@code FFmpegFrameGrabber} returns the <i>same</i> {@link Frame} object, backed by the same native buffer, from
 * every call to {@code grab()}. In the single threaded design that was harmless because each frame was fully
 * processed before the next grab. With effects running concurrently on other threads it would be a textbook race:
 * the decoder would overwrite pixels while a worker is still applying effects to them (frame tearing). So the decoder
 * copies every picture into a frame it exclusively owns before passing it on.
 * </p>
 *
 * <h2>Why a pool</h2>
 * <p>
 * Allocating (and later garbage collecting) a fresh multi megabyte native buffer 24+ times a second is wasteful. The
 * pool allocates its frames once. It is also the decoders <b>backpressure</b>: {@link #acquire()} blocks when every
 * frame is in use, so the decoder can never run unboundedly far ahead of the effect workers.
 * </p>
 *
 * <h2>Ownership protocol (the critical region)</h2>
 * <p>
 * A frame is owned by exactly one thread at a time: the pool, then the decoder (between {@link #acquire()} and
 * submitting it for processing), then one worker (until it calls {@link #release(Frame)} after rendering). Hand offs
 * go through a {@link BlockingQueue} or an executor, both of which establish the happens before ordering required for
 * the next owner to see the previous owners writes.
 * </p>
 */
public final class FramePool {
    private final BlockingQueue<Frame> freeFrames;
    private final int width;
    private final int height;
    private final int depth;
    private final int channels;
    private final int stride;

    /** Creates a pool of {@code capacity} frames shaped exactly like {@code template}. */
    public FramePool(Frame template, int capacity) {
        width = template.imageWidth;
        height = template.imageHeight;
        depth = template.imageDepth;
        channels = template.imageChannels;
        stride = template.imageStride;
        freeFrames = new ArrayBlockingQueue<>(capacity);
        for (int i = 0; i < capacity; i++) {
            freeFrames.add(new Frame(width, height, depth, channels, stride));
        }
    }

    /** True if {@code frame} has the shape this pools frames were created with. */
    public boolean matches(Frame frame) {
        return frame.imageWidth == width
                && frame.imageHeight == height
                && frame.imageDepth == depth
                && frame.imageChannels == channels
                && frame.imageStride == stride;
    }

    /** Takes a free frame, waiting if all frames are currently in use. */
    public Frame acquire() throws InterruptedException {
        return freeFrames.take();
    }

    /** Returns a frame to the pool. Its owner must not touch it afterwards. */
    public void release(Frame frame) {
        freeFrames.offer(frame);
    }

    /** Takes a pooled frame and fills it with an exact copy of {@code source}s pixels. */
    public Frame acquireCopyOf(Frame source) throws InterruptedException {
        Frame copy = acquire();
        copyPixels(source, copy);
        return copy;
    }

    static void copyPixels(Frame source, Frame destination) {
        ByteBuffer from = ((ByteBuffer) source.image[0]).duplicate();
        ByteBuffer to = ((ByteBuffer) destination.image[0]).duplicate();
        int byteCount = source.imageStride * source.imageHeight;
        from.clear().limit(byteCount);
        to.clear();
        to.put(from);
    }
}

