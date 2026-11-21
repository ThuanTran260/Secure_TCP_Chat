package com.example.securechat.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ClientSessionTest {
    @Test void enqueueBulkKeepsHalfFree() {
        ClientSession session = session(8);
        try {
            for (int i = 0; i < 4; i++) assertTrue(session.enqueueBulk("bulk-" + i, Duration.ZERO));
            assertFalse(session.enqueueBulk("bulk-full", Duration.ofMillis(50)));
            for (int i = 0; i < 4; i++) assertTrue(session.enqueue("normal-" + i));
            assertFalse(session.enqueue("normal-full"));
        } finally {
            session.abort();
        }
    }

    @Test @Timeout(3) void closingSessionWakesBulkWaiter() throws Exception {
        ClientSession session = session(8);
        for (int i = 0; i < 4; i++) assertTrue(session.enqueueBulk("bulk", Duration.ZERO));
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);
        Thread waiting = new Thread(() -> {
            started.countDown();
            accepted.set(session.enqueueBulk("late", Duration.ofSeconds(20)));
        });
        waiting.setDaemon(true);
        waiting.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        session.abort();
        waiting.join(1000);
        assertFalse(waiting.isAlive(), "Closing must wake blocked bulk producers");
        assertFalse(accepted.get());
        assertFalse(session.enqueueBulk("closed", Duration.ZERO));
    }

    private ClientSession session(int capacity) {
        return new ClientSession(123456, "127.0.0.1", new ByteArrayOutputStream(), () -> { }, capacity);
    }
}
