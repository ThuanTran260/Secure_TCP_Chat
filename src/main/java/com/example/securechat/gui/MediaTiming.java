package com.example.securechat.gui;

import java.util.Locale;

/** Pure timing, size and format helpers for in-bubble playback; no FX or native code. */
public final class MediaTiming {
    /** A frame later than this behind the clock is dropped instead of shown. */
    public static final long DROP_AFTER_MICROS = 100_000;

    public enum FrameAction { SHOW, WAIT, DROP }
    public record Size(int width, int height) {}

    private MediaTiming() {}

    public static FrameAction decide(long frameMicros, long clockMicros, long dropAfterMicros) {
        if (frameMicros > clockMicros) return FrameAction.WAIT;
        if (clockMicros - frameMicros > dropAfterMicros) return FrameAction.DROP;
        return FrameAction.SHOW;
    }

    /** Rounds to the nearest of 0/90/180/270. */
    public static int normalizeRotation(double degrees) {
        if (Double.isNaN(degrees) || Double.isInfinite(degrees)) return 0;
        long quarter = Math.round(degrees / 90.0) % 4;
        return (int) (((quarter + 4) % 4) * 90);
    }

    /** Size on screen after rotation, scaled down (never up) to fit within max × max. */
    public static Size displaySize(int width, int height, int rotation, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0) return new Size(maxWidth, maxHeight);
        boolean sideways = rotation == 90 || rotation == 270;
        double w = sideways ? height : width, h = sideways ? width : height;
        double scale = Math.min(1.0, Math.min(maxWidth / w, maxHeight / h));
        return new Size((int) Math.max(1, Math.round(w * scale)), (int) Math.max(1, Math.round(h * scale)));
    }

    /** The pre-rotation size the decoder must produce so that rotating it yields {@code display}. */
    public static Size decodeSize(Size display, int rotation) {
        return rotation == 90 || rotation == 270 ? new Size(display.height(), display.width()) : display;
    }

    public static String format(long micros) {
        long seconds = micros < 0 ? 0 : micros / 1_000_000;
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }
}
