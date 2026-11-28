package com.example.securechat.gui;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA;
import static org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

/**
 * Plays one video or audio attachment inside a chat bubble using the bundled FFmpeg.
 * Public methods are called on the FX thread and never block it; all grabber work runs on
 * short-lived "media-decode" threads that take {@link #decodeLock} one at a time.
 */
public final class VideoPlayer {
    private static final AtomicBoolean PRELOADED = new AtomicBoolean();
    private static final int MAX_QUEUED_FRAMES = 30;
    /** Audio clock considered stalled (line starved or drained) after this long without movement. */
    private static final long STALL_NANOS = 60_000_000L;

    /** Loads the FFmpeg natives in the background so the first bubble does not stall. Only the first call loads. */
    public static void preload() {
        if (!PRELOADED.compareAndSet(false, true)) return;
        Thread loader = new Thread(() -> {
            try { FFmpegFrameGrabber.tryLoad(); }
            catch (Throwable e) { System.err.println("FFmpeg preload failed: " + e); }
        }, "ffmpeg-preload");
        loader.setDaemon(true); loader.start();
    }

    // Immutable after open.
    private final Path file;
    private final long durationMicros;
    private final boolean video, audio;
    private final int frameWidth, frameHeight, sampleRate, channels;
    private final WritableImage image;
    private final ImageView view;

    // FX-thread state.
    private final ReadOnlyLongWrapper position = new ReadOnlyLongWrapper(0);
    private final ReadOnlyBooleanWrapper playing = new ReadOnlyBooleanWrapper(false);
    private Consumer<Throwable> onError = error -> { };
    private volatile int generation;
    private volatile boolean disposed;

    // Shared between decode threads and the FX pump.
    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>();
    private volatile long latestPosition = -1;
    private volatile int latestPositionGeneration;
    private final AtomicBoolean pumpPending = new AtomicBoolean();
    private final AtomicReference<Long> pendingSeek = new AtomicReference<>();
    private volatile boolean atEnd;

    // Owned by whichever decode thread holds decodeLock.
    private final ReentrantLock decodeLock = new ReentrantLock();
    private final ArrayDeque<Frame> queued = new ArrayDeque<>();
    private FFmpegFrameGrabber grabber; // null while idle: an open decoder costs threads and tens of megabytes
    private volatile boolean decoderOpen;
    private SourceDataLine line;
    private boolean lineFailed, eof;
    private byte[] pendingAudio; private int pendingAudioOffset;
    private long audioBase, wallBase, wallStartNanos, clockMicros, lastAudio = Long.MIN_VALUE, lastAudioNanos;

    private VideoPlayer(Path file, long durationMicros, int sampleRate, int channels, MediaTiming.Size decode, int rotation, byte[] poster) {
        this.file = file; this.durationMicros = Math.max(0, durationMicros); this.audio = channels > 0;
        this.video = decode != null;
        this.sampleRate = sampleRate; this.channels = channels;
        if (video) {
            frameWidth = decode.width(); frameHeight = decode.height();
            image = new WritableImage(frameWidth, frameHeight);
            image.getPixelWriter().setPixels(0, 0, frameWidth, frameHeight, PixelFormat.getByteBgraPreInstance(), poster, 0, frameWidth * 4);
            view = new ImageView(image); view.setFitWidth(frameWidth); view.setFitHeight(frameHeight); view.setPreserveRatio(true); view.setSmooth(true);
            view.setRotate(rotation);
        } else { frameWidth = frameHeight = 0; image = null; view = null; }
    }

    /**
     * Reads the metadata and the first frame (the poster) of {@code file} on {@code io}, then closes the decoder
     * again until playback needs it. Fails if the file cannot be decoded.
     */
    public static CompletableFuture<VideoPlayer> open(Path file, int maxWidth, int maxHeight, Executor io) {
        return CompletableFuture.supplyAsync(() -> {
            FFmpegFrameGrabber grabber = newGrabber(file);
            try {
                grabber.start();
                boolean audio = grabber.hasAudio() && grabber.getSampleRate() > 0 && grabber.getAudioChannels() > 0;
                int sampleRate = audio ? grabber.getSampleRate() : 0;
                int channels = audio ? Math.min(2, grabber.getAudioChannels()) : 0;
                MediaTiming.Size decode = null; int rotation = 0; byte[] poster = null;
                if (hasMovingPicture(grabber) && grabber.getImageWidth() > 0 && grabber.getImageHeight() > 0) {
                    // FFmpeg reports the angle counterclockwise; JavaFX rotates clockwise.
                    rotation = MediaTiming.normalizeRotation(-grabber.getDisplayRotation());
                    MediaTiming.Size display = MediaTiming.displaySize(grabber.getImageWidth(), grabber.getImageHeight(), rotation, maxWidth, maxHeight);
                    decode = MediaTiming.decodeSize(display, rotation);
                    grabber.setImageWidth(decode.width()); grabber.setImageHeight(decode.height());
                    Frame first = grabber.grabImage();
                    if (first == null || first.image == null) throw new IllegalStateException("no video frame");
                    poster = copyPixels(first, decode.width(), decode.height());
                }
                if (decode == null && !audio) throw new IllegalStateException("no audio or video stream");
                return new VideoPlayer(file, grabber.getLengthInTime(), sampleRate, channels, decode, rotation, poster);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            } finally {
                release(grabber);
            }
        }, io);
    }

    private static FFmpegFrameGrabber newGrabber(Path file) {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile());
        grabber.setPixelFormat(AV_PIX_FMT_BGRA);
        grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
        grabber.setVideoOption("threads", "2"); // FFmpeg would otherwise start one per core
        return grabber;
    }

    private static void release(FFmpegFrameGrabber grabber) {
        try { grabber.release(); } catch (Exception ignored) { }
    }

    /** True when the file has a real video stream; cover art embedded in an audio file does not count. */
    private static boolean hasMovingPicture(FFmpegFrameGrabber grabber) {
        if (!grabber.hasVideo()) return false;
        AVFormatContext context = grabber.getFormatContext();
        for (int i = 0; i < context.nb_streams(); i++) {
            AVStream stream = context.streams(i);
            if (stream.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO && (stream.disposition() & AV_DISPOSITION_ATTACHED_PIC) == 0)
                return true;
        }
        return false;
    }

    public boolean hasVideo() { return video; }
    public ImageView view() { return view; }
    public long durationMicros() { return durationMicros; }
    public ReadOnlyLongProperty positionMicrosProperty() { return position.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty playingProperty() { return playing.getReadOnlyProperty(); }
    public void setOnError(Consumer<Throwable> handler) { onError = handler == null ? error -> { } : handler; }

    public void play() {
        if (disposed || playing.get()) return;
        if (atEnd || (durationMicros > 0 && position.get() >= durationMicros)) { atEnd = false; pendingSeek.set(0L); position.set(0); }
        playing.set(true);
        start(true);
    }

    public void pause() {
        if (disposed || !playing.get()) return;
        playing.set(false);
        generation++;
    }

    public void seek(long micros) {
        if (disposed) return;
        long target = Math.max(0, durationMicros > 0 ? Math.min(micros, durationMicros) : micros);
        atEnd = false; pendingSeek.set(target); position.set(target);
        start(playing.get());
    }

    /** Pauses and frees the decoder; the next play or seek reopens it where this one stopped. */
    void suspend() {
        if (disposed) return;
        playing.set(false);
        releaseInBackground(++generation);
    }

    /** True while a grabber is held for playback. */
    boolean decoderOpen() { return decoderOpen; }

    public void dispose() {
        if (disposed) return;
        disposed = true; playing.set(false);
        releaseInBackground(++generation);
    }

    private void releaseInBackground(int session) {
        Thread thread = new Thread(() -> {
            decodeLock.lock();
            try {
                // A play or seek issued after a suspend keeps the decoder.
                if (disposed || session == generation) closeDecoder();
            } finally { decodeLock.unlock(); }
        }, "media-release");
        thread.setDaemon(true); thread.start();
    }

    /** Called with {@link #decodeLock} held. */
    private void openDecoder() throws Exception {
        FFmpegFrameGrabber opened = newGrabber(file);
        try {
            if (video) { opened.setImageWidth(frameWidth); opened.setImageHeight(frameHeight); }
            opened.start();
            if (audio) opened.setAudioChannels(channels);
        } catch (Exception e) {
            release(opened);
            throw e;
        }
        grabber = opened; decoderOpen = true; eof = false;
    }

    /** Called with {@link #decodeLock} held. Frees the grabber, the audio line and any buffered media. */
    private void closeDecoder() {
        if (line != null) { line.stop(); line.flush(); line.close(); line = null; }
        discardQueued(); pendingAudio = null;
        if (grabber != null) { release(grabber); grabber = null; }
        decoderOpen = false;
    }

    private void start(boolean play) {
        int session = ++generation;
        Thread thread = new Thread(() -> run(session, play), "media-decode");
        thread.setDaemon(true); thread.start();
    }

    private boolean current(int session) { return session == generation && !disposed; }

    private void run(int session, boolean play) {
        decodeLock.lock();
        try {
            if (!current(session)) return;
            Long seekTo = pendingSeek.getAndSet(null);
            if (grabber == null) {
                openDecoder();
                if (seekTo == null && clockMicros > 0) seekTo = clockMicros; // resume where the released decoder was
            }
            if (seekTo != null) seekTo(session, seekTo);
            if (play && current(session)) playLoop(session);
        } catch (Throwable e) {
            closeDecoder();
            if (!current(session)) return;
            Platform.runLater(() -> {
                if (disposed || session != generation) return;
                playing.set(false); generation++; onError.accept(e);
            });
        } finally {
            if (line != null) line.stop();
            decodeLock.unlock();
        }
    }

    private void seekTo(int session, long target) throws Exception {
        // Without a picture, seek on the audio stream so cover art is never waited for.
        if (video) grabber.setTimestamp(target); else grabber.setAudioTimestamp(target);
        discardQueued(); pendingAudio = null; eof = false;
        if (line != null) { line.flush(); audioBase = target - line.getMicrosecondPosition(); }
        lastAudio = Long.MIN_VALUE; wallBase = target; wallStartNanos = System.nanoTime(); clockMicros = target;
        if (video) {
            // Show the frame at the new position right away, keeping audio decoded on the way.
            while (current(session)) {
                Frame frame = grabber.grabFrame(audio, true, true, false);
                if (frame == null) { eof = true; break; }
                if (frame.samples != null) appendAudio(frame);
                else if (frame.image != null) { latestFrame.set(copyPixels(frame, frameWidth, frameHeight)); break; }
            }
        }
        publish(session, target);
    }

    private void playLoop(int session) throws Exception {
        if (audio && line == null && !lineFailed) openLine();
        if (line != null) line.start();
        wallBase = clockMicros; wallStartNanos = System.nanoTime(); lastAudio = Long.MIN_VALUE;
        while (current(session)) {
            boolean busy = false;
            long clock = clock();
            // Present due frames: show the newest one whose time has come, drop the stale ones.
            Frame show = null;
            while (!queued.isEmpty()) {
                MediaTiming.FrameAction action = MediaTiming.decide(queued.peekFirst().timestamp, clock, MediaTiming.DROP_AFTER_MICROS);
                if (action == MediaTiming.FrameAction.WAIT) break;
                Frame frame = queued.pollFirst();
                if (action == MediaTiming.FrameAction.SHOW) {
                    if (show != null) show.close();
                    show = frame;
                } else frame.close();
            }
            if (show != null) { latestFrame.set(copyPixels(show, frameWidth, frameHeight)); show.close(); busy = true; }
            if (pendingAudio != null && line != null) {
                int frameBytes = channels * 2;
                int room = Math.min(line.available(), pendingAudio.length - pendingAudioOffset) / frameBytes * frameBytes;
                if (room > 0) {
                    line.write(pendingAudio, pendingAudioOffset, room); pendingAudioOffset += room; busy = true;
                    if (pendingAudioOffset >= pendingAudio.length) pendingAudio = null;
                }
            } else if (line == null) pendingAudio = null;
            if (!eof && pendingAudio == null && queued.size() < MAX_QUEUED_FRAMES) {
                Frame frame = grabber.grabFrame(audio, video, true, false);
                busy = true;
                if (frame == null) eof = true;
                else if (frame.samples != null) appendAudio(frame);
                else if (frame.image != null) queued.addLast(frame.clone());
            }
            publish(session, Math.min(Math.max(0, clock), durationMicros > 0 ? durationMicros : Long.MAX_VALUE));
            if (eof && queued.isEmpty() && pendingAudio == null && audioDrained()) { closeDecoder(); finish(session); return; }
            if (!busy) Thread.sleep(5);
        }
    }

    /** Queued frames are clones that own native memory. */
    private void discardQueued() {
        for (Frame frame; (frame = queued.pollFirst()) != null; ) frame.close();
    }

    private void openLine() {
        try {
            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            SourceDataLine opened = AudioSystem.getSourceDataLine(format);
            opened.open(format, sampleRate * channels * 2); // about one second
            line = opened; audioBase = clockMicros - line.getMicrosecondPosition();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            lineFailed = true; line = null; // fall back to the wall clock
        }
    }

    /** Master clock: the audio line's played position when there is one, otherwise elapsed wall time. */
    private long clock() {
        long now = System.nanoTime();
        if (line != null) {
            long position = line.getMicrosecondPosition() + audioBase;
            if (position != lastAudio) { lastAudio = position; lastAudioNanos = now; clockMicros = position; }
            else if (now - lastAudioNanos > STALL_NANOS) clockMicros = lastAudio + (now - lastAudioNanos) / 1000; // starved or drained
            else clockMicros = position;
        } else clockMicros = wallBase + (now - wallStartNanos) / 1000;
        return clockMicros;
    }

    private boolean audioDrained() {
        return line == null || line.available() >= line.getBufferSize() || (lastAudio != Long.MIN_VALUE && System.nanoTime() - lastAudioNanos > STALL_NANOS);
    }

    private void finish(int session) {
        Platform.runLater(() -> {
            if (disposed || session != generation) return;
            atEnd = true; generation++; playing.set(false); position.set(durationMicros);
        });
    }

    private void appendAudio(Frame frame) {
        if (line == null && (lineFailed || !audio)) return;
        if (!(frame.samples[0] instanceof ShortBuffer)) return;
        ShortBuffer samples = ((ShortBuffer) frame.samples[0]).duplicate(); samples.rewind();
        int count = samples.remaining(), old = pendingAudio == null ? 0 : pendingAudio.length - pendingAudioOffset;
        byte[] bytes = new byte[old + count * 2];
        if (old > 0) System.arraycopy(pendingAudio, pendingAudioOffset, bytes, 0, old);
        for (int i = 0; i < count; i++) { short s = samples.get(); bytes[old + 2 * i] = (byte) s; bytes[old + 2 * i + 1] = (byte) (s >> 8); }
        pendingAudio = bytes; pendingAudioOffset = 0;
    }

    /** Coalesces frame and position updates: at most one runLater is pending at any time. */
    private void publish(int session, long micros) {
        latestPosition = micros; latestPositionGeneration = session;
        if (!pumpPending.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            pumpPending.set(false);
            if (disposed) return;
            byte[] frame = latestFrame.getAndSet(null);
            if (frame != null && image != null)
                image.getPixelWriter().setPixels(0, 0, frameWidth, frameHeight, PixelFormat.getByteBgraPreInstance(), frame, 0, frameWidth * 4);
            if (latestPositionGeneration == generation && latestPosition >= 0) position.set(latestPosition);
        });
    }

    private static byte[] copyPixels(Frame frame, int width, int height) {
        ByteBuffer source = ((ByteBuffer) frame.image[0]).duplicate();
        int stride = frame.imageStride, w = Math.min(width, frame.imageWidth), h = Math.min(height, frame.imageHeight);
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < h; y++) { source.position(y * stride); source.get(pixels, y * width * 4, w * 4); }
        return pixels;
    }
}
