package com.example.securechat.gui;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FfmpegAvailabilityTest {
    @Test void nativesLoad() { assertDoesNotThrow(FFmpegFrameGrabber::tryLoad); }
}
