package com.example.securechat.gui;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Random;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/** Generates small media files locally so tests never depend on downloads. */
final class MediaFixtures {
    static final int WIDTH = 160, HEIGHT = 120, FPS = 25, RATE = 44100, CHANNELS = 2;
    private MediaFixtures() {}

    static Path mp4(Path dir, boolean video, boolean audio, int seconds) throws Exception {
        if (!video && !audio) throw new IllegalArgumentException("need a track");
        Path file = dir.resolve((video ? "clip-" + (audio ? "av" : "v") : "tone") + "-" + seconds + "s" + (video ? ".mp4" : ".m4a"));
        FFmpegFrameRecorder recorder = video
                ? new FFmpegFrameRecorder(file.toFile(), WIDTH, HEIGHT, audio ? CHANNELS : 0)
                : new FFmpegFrameRecorder(file.toFile(), CHANNELS);
        recorder.setFormat("mp4");
        if (video) { recorder.setVideoCodec(AV_CODEC_ID_MPEG4); recorder.setFrameRate(FPS); recorder.setPixelFormat(AV_PIX_FMT_YUV420P); }
        if (audio) { recorder.setAudioCodec(AV_CODEC_ID_AAC); recorder.setSampleRate(RATE); recorder.setAudioChannels(CHANNELS); recorder.setAudioBitrate(128_000); }
        recorder.start();
        try {
            int frames = seconds * FPS, samplesPerFrame = RATE / FPS; long sample = 0;
            for (int i = 0; i < frames; i++) {
                if (video) {
                    Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
                    ByteBuffer pixels = (ByteBuffer) frame.image[0];
                    int second = i / FPS;
                    byte b = (byte) (second % 2 == 0 ? 200 : 20), g = (byte) (second * 70), r = (byte) (255 - second * 60);
                    for (int p = 0; p + 2 < frame.imageStride * HEIGHT; p += 3) { pixels.put(p, b); pixels.put(p + 1, g); pixels.put(p + 2, r); }
                    recorder.record(frame, AV_PIX_FMT_BGR24);
                }
                if (audio) {
                    ShortBuffer tone = ShortBuffer.allocate(samplesPerFrame * CHANNELS);
                    for (int s = 0; s < samplesPerFrame; s++, sample++) {
                        short value = (short) (Math.sin(2 * Math.PI * 440 * sample / RATE) * 8000);
                        tone.put(value); tone.put(value);
                    }
                    tone.flip(); recorder.recordSamples(RATE, CHANNELS, tone);
                }
            }
        } finally { recorder.stop(); recorder.release(); }
        return file;
    }

    /** A video-only MP4 tagged like a portrait phone recording: stored 160x120, to be shown rotated 90 degrees clockwise. */
    static Path rotatedMp4(Path dir) throws Exception {
        byte[] bytes = Files.readAllBytes(mp4(Files.createDirectories(dir.resolve("rotated")), true, false, 1));
        int box = 0;
        while (!(bytes[box] == 't' && bytes[box + 1] == 'k' && bytes[box + 2] == 'h' && bytes[box + 3] == 'd')) box++;
        // Version 0 track header: the 3x3 display matrix starts 44 bytes after the box type.
        ByteBuffer matrix = ByteBuffer.wrap(bytes, box + 44, 36);
        for (int value : new int[]{0, 0x10000, 0, -0x10000, 0, 0, HEIGHT << 16, 0, 0x40000000}) matrix.putInt(value);
        return Files.write(dir.resolve("portrait.mp4"), bytes);
    }

    /** An MP3 carrying embedded cover art (ID3v2.3 APIC), which FFmpeg exposes as an extra "video" stream. */
    static Path mp3WithCover(Path dir, int seconds) throws Exception {
        Path plain = dir.resolve("plain-" + seconds + "s.mp3");
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(plain.toFile(), CHANNELS);
        recorder.setFormat("mp3"); recorder.setAudioCodec(AV_CODEC_ID_MP3); recorder.setSampleRate(RATE);
        recorder.start();
        try {
            ShortBuffer tone = ShortBuffer.allocate(RATE * CHANNELS);
            for (int second = 0; second < seconds; second++) {
                tone.clear();
                for (int s = 0; s < RATE; s++) {
                    short value = (short) (Math.sin(2 * Math.PI * 440 * s / RATE) * 8000);
                    tone.put(value); tone.put(value);
                }
                tone.flip(); recorder.recordSamples(RATE, CHANNELS, tone);
            }
        } finally { recorder.stop(); recorder.release(); }

        ByteArrayOutputStream picture = new ByteArrayOutputStream();
        picture.write(0); // Latin-1 text encoding
        picture.write("image/png\0".getBytes(StandardCharsets.ISO_8859_1));
        picture.write(3); picture.write(0); // front cover, empty description
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", picture);

        int size = picture.size() + 10;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'D', '3', 3, 0, 0,
                (byte) (size >> 21 & 127), (byte) (size >> 14 & 127), (byte) (size >> 7 & 127), (byte) (size & 127)});
        out.write(new byte[]{'A', 'P', 'I', 'C'});
        out.write(ByteBuffer.allocate(4).putInt(picture.size()).array());
        out.write(new byte[]{0, 0});
        picture.writeTo(out);
        out.write(Files.readAllBytes(plain));
        return Files.write(dir.resolve("cover.mp3"), out.toByteArray());
    }

    static Path garbage(Path dir) throws Exception {
        byte[] bytes = new byte[4096]; new Random(42).nextBytes(bytes);
        return Files.write(dir.resolve("bad.mp4"), bytes);
    }
}
