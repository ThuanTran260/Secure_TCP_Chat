package com.example.securechat.gui;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;

/** Captures at most sixty seconds of 16 kHz, signed 16-bit mono PCM. */
public final class VoiceRecorder implements AutoCloseable {
    private static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);
    private static final int MAX_BYTES = 16000 * 2 * 60;
    private volatile boolean recording;
    private TargetDataLine line;
    private Thread reader;
    private ByteArrayOutputStream pcm;

    public synchronized void start() throws LineUnavailableException {
        if (reader != null) throw new IllegalStateException("Đang ghi âm");
        TargetDataLine opened = AudioSystem.getTargetDataLine(FORMAT);
        try { opened.open(FORMAT); opened.start(); }
        catch (LineUnavailableException | RuntimeException e) { opened.close(); throw e; }
        line = opened;
        pcm = new ByteArrayOutputStream();
        recording = true;
        reader = new Thread(() -> {
            byte[] bytes = new byte[4096];
            try {
                while (recording && pcm.size() < MAX_BYTES) {
                    int count = opened.read(bytes, 0, Math.min(bytes.length, MAX_BYTES - pcm.size()));
                    if (count <= 0) break;
                    pcm.write(bytes, 0, count);
                }
            } finally { recording = false; opened.stop(); opened.close(); }
        }, "voice-recorder");
        reader.setDaemon(true); reader.start();
    }

    public Path stop() throws IOException {
        Thread capture;
        TargetDataLine source;
        synchronized (this) {
            if (reader == null) throw new IOException("Chưa ghi âm");
            recording = false; capture = reader; source = line;
        }
        source.stop(); source.close();
        try { capture.join(2000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Ghi âm bị gián đoạn", e); }
        if (capture.isAlive()) throw new IOException("Không thể dừng ghi âm");
        byte[] bytes;
        synchronized (this) { bytes = pcm.toByteArray(); reader = null; line = null; pcm = null; }
        Path target = Files.createTempFile("voice-" + System.currentTimeMillis() + "-", ".wav");
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(bytes), FORMAT, bytes.length / FORMAT.getFrameSize())) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, target.toFile());
        } catch (IOException | RuntimeException e) { Files.deleteIfExists(target); throw e; }
        return target;
    }

    public boolean isRecording() { return recording; }
    @Override public synchronized void close() {
        recording = false;
        if (line != null) { line.stop(); line.close(); }
    }
}
