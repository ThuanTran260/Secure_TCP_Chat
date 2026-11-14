package com.example.securechat.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test-only clock whose time only moves when {@link #advance(Duration)} is called. */
public final class MutableClock extends Clock {
    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock() {
        this(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    private MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public synchronized void advance(Duration d) {
        now = now.plus(d);
    }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId z) { return new MutableClock(now, z); }

    @Override public Instant instant() { return now; }
}
