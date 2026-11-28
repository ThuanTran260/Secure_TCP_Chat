package com.example.securechat.gui;

import com.example.securechat.gui.MediaTiming.FrameAction;
import com.example.securechat.gui.MediaTiming.Size;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaTimingTest {
    @Test void decidesFrameAgainstClock() {
        assertEquals(FrameAction.WAIT, MediaTiming.decide(1_000_000, 900_000, 100_000));
        assertEquals(FrameAction.SHOW, MediaTiming.decide(1_000_000, 1_050_000, 100_000));
        assertEquals(FrameAction.DROP, MediaTiming.decide(1_000_000, 1_200_001, 100_000));
        assertEquals(100_000, MediaTiming.DROP_AFTER_MICROS);
    }

    @Test void normalizesRotation() {
        assertEquals(270, MediaTiming.normalizeRotation(-90));
        assertEquals(90, MediaTiming.normalizeRotation(90.4));
        assertEquals(0, MediaTiming.normalizeRotation(Double.NaN));
        assertEquals(90, MediaTiming.normalizeRotation(450));
        assertEquals(180, MediaTiming.normalizeRotation(-180));
    }

    @Test void fitsDisplaySizeKeepingAspect() {
        assertEquals(new Size(320, 180), MediaTiming.displaySize(640, 360, 0, 320, 180));
        assertEquals(new Size(219, 320), MediaTiming.displaySize(576, 842, 0, 320, 320));
        assertEquals(new Size(320, 180), MediaTiming.displaySize(640, 360, 0, 320, 320));
        assertEquals(new Size(180, 320), MediaTiming.displaySize(1920, 1080, 90, 320, 320));
        assertEquals(new Size(320, 180), MediaTiming.decodeSize(new Size(180, 320), 90));
        assertEquals(new Size(180, 320), MediaTiming.decodeSize(new Size(180, 320), 0));
        assertEquals(new Size(100, 50), MediaTiming.displaySize(100, 50, 0, 320, 180));
        assertEquals(new Size(320, 180), MediaTiming.displaySize(0, 0, 0, 320, 180));
        assertEquals(new Size(320, 1), MediaTiming.displaySize(10000, 1, 0, 320, 320));
    }

    @Test void formatsMinutesAndSeconds() {
        assertEquals("3:22", MediaTiming.format(202_300_000));
        assertEquals("0:00", MediaTiming.format(-5));
        assertEquals("0:00", MediaTiming.format(Long.MIN_VALUE));
        assertEquals("0:59", MediaTiming.format(59_999_999));
    }
}
