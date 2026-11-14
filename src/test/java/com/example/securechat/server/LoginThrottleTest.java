package com.example.securechat.server;

import com.example.securechat.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class LoginThrottleTest {
    private MutableClock clock;
    private LoginThrottle throttle;

    @BeforeEach void setUp() {
        clock = new MutableClock();
        throttle = new LoginThrottle(clock, 5, Duration.ofMinutes(5), Duration.ofSeconds(60));
    }

    private void fail(int n, String ip, String user) {
        for (int i = 0; i < n; i++) throttle.recordFailure(ip, user);
    }

    @Test void locksAfterFiveFailures() {
        fail(4, "1.2.3.4", "an");
        assertFalse(throttle.isLocked("1.2.3.4", "an"));
        throttle.recordFailure("1.2.3.4", "an");
        assertTrue(throttle.isLocked("1.2.3.4", "an"));
    }

    @Test void lockExpiresAfterLockout() {
        fail(5, "1.2.3.4", "an");
        assertTrue(throttle.isLocked("1.2.3.4", "an"));
        clock.advance(Duration.ofSeconds(61));
        assertFalse(throttle.isLocked("1.2.3.4", "an"));
    }

    @Test void failuresOutsideWindowIgnored() {
        fail(4, "1.2.3.4", "an");
        clock.advance(Duration.ofMinutes(6));
        throttle.recordFailure("1.2.3.4", "an");
        assertFalse(throttle.isLocked("1.2.3.4", "an"));
    }

    @Test void keyedByIpAndUsername() {
        fail(5, "1.2.3.4", "an");
        assertTrue(throttle.isLocked("1.2.3.4", "an"));
        assertFalse(throttle.isLocked("1.2.3.4", "binh"));
        assertFalse(throttle.isLocked("5.6.7.8", "an"));
        assertTrue(throttle.isLocked("1.2.3.4", " AN "));
    }

    @Test void successResetsCounter() {
        fail(4, "1.2.3.4", "an");
        throttle.recordSuccess("1.2.3.4", "an");
        throttle.recordFailure("1.2.3.4", "an");
        assertFalse(throttle.isLocked("1.2.3.4", "an"));
    }
}
