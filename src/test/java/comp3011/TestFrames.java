/*
 * COMP3011 Assignment 2 - multi-threaded video player.
 *
 * Authors:
 *   1. <Student Name> (<Student Number>), in collaboration with Claude (Anthropic)
 *
 * New file. Test helpers.
 */
package comp3011;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

/** Shared helpers for tests: frame fingerprints and generated test videos. */
final class TestFrames {
    private TestFrames() {
    }

    /**
     * A fingerprint of a frame's visible pixels: equal hashes mean pixel-identical frames.
     *
     * <p>
     * Only the first {@code width * channels} bytes of each row are hashed. Rows may be padded out to
     * {@code imageStride} for memory alignment (e.g. a 480 pixel wide video has 96 bytes of padding per row), and
     * that padding holds whatever was in memory before - it is never drawn, never touched by effects, and can
     * legitimately differ between two decodes of the same frame.
     * </p>
     */
    static String sha1(Frame frame) {
        try {
            ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
            int visibleRowBytes = frame.imageWidth * frame.imageChannels;
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (int y = 0; y < frame.imageHeight; y++) {
                int rowStart = y * frame.imageStride;
                pixels.clear().position(rowStart).limit(rowStart + visibleRowBytes);
                digest.update(pixels);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes a test video (24 fps, MPEG-4 video, optional stereo AAC audio) with a moving, high-contrast pattern.
     * Generated at test time so no binary media needs to be checked in.
     */
    static File generateVideo(File file, int width, int height, int frames, boolean withAudio) throws Exception {
        if (file.isFile()) {
            return file;
        }
        file.getParentFile().mkdirs();
        int sampleRate = 44_100;
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file, width, height, withAudio ? 2 : 0)) {
            recorder.setFormat("mp4");
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(24);
            recorder.setVideoQuality(3);
            if (withAudio) {
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                recorder.setSampleRate(sampleRate);
                recorder.setAudioChannels(2);
            }
            recorder.start();

            Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
            ByteBuffer pixels = (ByteBuffer) frame.image[0];
            int samplesPerFrame = sampleRate / 24;
            short[] tone = new short[samplesPerFrame * 2];
            for (int i = 0; i < frames; i++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int base = y * frame.imageStride + x * 3;
                        boolean bright = ((x / 64 + y / 64 + i / 6) & 1) == 0;
                        pixels.put(base, (byte) (bright ? 250 : x + 4 * i));
                        pixels.put(base + 1, (byte) (bright ? 245 : y + 2 * i));
                        pixels.put(base + 2, (byte) (bright ? 240 : (x ^ y) - 3 * i));
                    }
                }
                recorder.record(frame, avutil.AV_PIX_FMT_BGR24);
                if (withAudio) {
                    for (int s = 0; s < samplesPerFrame; s++) {
                        short value = (short) (8000 * Math.sin(2 * Math.PI * 440 * (i * samplesPerFrame + s)
                                / sampleRate));
                        tone[2 * s] = value;
                        tone[2 * s + 1] = value;
                    }
                    recorder.recordSamples(sampleRate, 2, ShortBuffer.wrap(tone));
                }
            }
            recorder.stop();
        }
        return file;
    }
}
