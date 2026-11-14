package com.example.securechat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.JsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// Every test is bounded so a stuck writer thread can never hang the whole suite.
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ClientRegistryTest {

    /** Counts how many times the connection Closeable was invoked. */
    private static final class CountingCloseable implements java.io.Closeable {
        final AtomicInteger closes = new AtomicInteger();
        private final Runnable onClose;

        CountingCloseable(Runnable onClose) {
            this.onClose = onClose;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            onClose.run();
        }
    }

    /** write() blocks until the stream is closed, like a socket whose peer stopped reading. */
    private static final class BlockingOutputStream extends OutputStream {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch writeEnded = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            writeEntered.countDown();
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                writeEnded.countDown();
            }
            throw new IOException("stream closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private ClientRegistry reg;
    private ClientSession s1;
    private ClientSession s2;

    private static ClientSession plainSession(int id) {
        return new ClientSession(id, "127.0.0.1:" + id, new ByteArrayOutputStream(), () -> { }, 8);
    }

    @BeforeEach
    void setUp() {
        reg = new ClientRegistry();
        s1 = plainSession(1);
        s2 = plainSession(2);
    }

    @Test
    void removeIsIdempotent() {
        reg.add(s1);
        assertSame(s1, reg.remove(1));
        assertNull(reg.remove(1));
    }

    @Test
    void snapshotOnlyAuthenticated() {
        reg.add(s1);
        reg.add(s2);
        s1.markAuthenticated("an");
        assertEquals(List.of(s1), reg.authenticatedSnapshot());
    }

    @Test
    void usernameClaimIsExclusive() {
        assertTrue(reg.tryClaimUsername("an", 1));
        assertFalse(reg.tryClaimUsername("an", 2));
        reg.releaseUsername("an", 2); // not the owner: must not release
        assertFalse(reg.tryClaimUsername("an", 3));
        reg.releaseUsername("an", 1);
        assertTrue(reg.tryClaimUsername("an", 3));
    }

    @Test
    void concurrentEnqueuesNeverInterleave() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ClientSession s = new ClientSession(1, "127.0.0.1:1", out, () -> { }, 2000);
        s.start(e -> { });

        int threads = 8;
        int perThread = 200;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();
        AtomicInteger rejected = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            int tid = t;
            Thread p = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    if (!s.send(ChatMessage.message("T" + tid + "-" + i + "-" + "x".repeat(500)))) {
                        rejected.incrementAndGet();
                    }
                }
            });
            producers.add(p);
            p.start();
        }
        go.countDown();
        for (Thread p : producers) {
            p.join();
        }
        s.closeGracefully(Duration.ofSeconds(5));

        assertEquals(0, rejected.get());
        String text = out.toString(StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        // The trailing '\n' leaves one empty element after the last line.
        assertEquals(threads * perThread + 1, lines.length);
        assertEquals("", lines[lines.length - 1]);
        for (int i = 0; i < lines.length - 1; i++) {
            ChatMessage m = JsonCodec.decode(lines[i]);
            assertTrue(m.getContent().startsWith("T"));
        }
    }

    @Test
    void fullQueueRejectsWithoutBlocking() throws Exception {
        BlockingOutputStream out = new BlockingOutputStream();
        ClientSession s = new ClientSession(1, "127.0.0.1:1", out, out::close, 4);
        s.start(e -> { });

        assertTrue(s.send(ChatMessage.message("first")));
        // Make sure the writer holds the first message inside the blocked write().
        assertTrue(out.writeEntered.await(5, TimeUnit.SECONDS));

        int accepted = 1;
        for (int i = 0; i < 8; i++) {
            if (s.send(ChatMessage.message("m" + i))) {
                accepted++;
            }
        }
        // 1 in flight + capacity 4 queued = at most 5 accepted.
        assertEquals(5, accepted);

        long start = System.nanoTime();
        boolean tenth = s.send(ChatMessage.message("tenth"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertFalse(tenth);
        assertTrue(elapsedMs < 100, "enqueue blocked for " + elapsedMs + " ms");

        s.abort(); // unblocks and ends the writer thread
        assertTrue(out.writeEnded.await(5, TimeUnit.SECONDS));
    }

    @Test
    void closeGracefullyFlushesPending() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CountingCloseable conn = new CountingCloseable(() -> { });
        ClientSession s = new ClientSession(1, "127.0.0.1:1", out, conn, 8);
        s.start(e -> { });

        assertTrue(s.send(ChatMessage.message("one")));
        assertTrue(s.send(ChatMessage.message("two")));
        assertTrue(s.send(ChatMessage.message("three")));
        s.closeGracefully(Duration.ofSeconds(2));

        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(3, lines.length);
        assertEquals("one", JsonCodec.decode(lines[0]).getContent());
        assertEquals("three", JsonCodec.decode(lines[2]).getContent());
        assertEquals(1, conn.closes.get());
        // A closed session accepts nothing more.
        assertFalse(s.send(ChatMessage.message("late")));
    }

    @Test
    void closeGracefullyGivesUpOnStuckWriter() throws Exception {
        BlockingOutputStream out = new BlockingOutputStream();
        CountingCloseable conn = new CountingCloseable(out::close);
        AtomicInteger failures = new AtomicInteger();
        ClientSession s = new ClientSession(1, "127.0.0.1:1", out, conn, 8);
        s.start(e -> failures.incrementAndGet());

        assertTrue(s.send(ChatMessage.message("stuck")));
        assertTrue(out.writeEntered.await(5, TimeUnit.SECONDS));

        long start = System.nanoTime();
        s.closeGracefully(Duration.ofMillis(500));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 1000, "closeGracefully took " + elapsedMs + " ms");
        assertEquals(1, conn.closes.get());
        // abort() closed the connection, which unblocks and ends the writer thread.
        assertTrue(out.writeEnded.await(5, TimeUnit.SECONDS));
        // The failure was caused by our own abort, so it is not reported as a peer failure.
        assertEquals(0, failures.get());
    }

    @Test
    void writeFailureInvokesCallbackOnce() throws Exception {
        IOException boom = new IOException("boom");
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw boom;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw boom;
            }
        };
        CountingCloseable conn = new CountingCloseable(() -> { });
        AtomicInteger callbacks = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<IOException> received =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        ClientSession s = new ClientSession(1, "127.0.0.1:1", failing, conn, 8);
        s.start(e -> {
            callbacks.incrementAndGet();
            received.set(e);
            called.countDown();
        });

        s.enqueue("{\"type\":\"system\",\"content\":\"a\"}");
        s.enqueue("{\"type\":\"system\",\"content\":\"b\"}");
        s.enqueue("{\"type\":\"system\",\"content\":\"c\"}");

        assertTrue(called.await(2, TimeUnit.SECONDS), "callback was not invoked");
        assertSame(boom, received.get());

        // The writer has stopped; later enqueues are harmless and never re-trigger the callback.
        s.enqueue("{\"type\":\"system\",\"content\":\"d\"}");
        s.closeGracefully(Duration.ofMillis(200));
        assertEquals(1, callbacks.get());
        assertEquals(1, conn.closes.get());
    }

    @Test
    void abortIsIdempotent() {
        CountingCloseable conn = new CountingCloseable(() -> { });
        ClientSession s = new ClientSession(1, "127.0.0.1:1", new ByteArrayOutputStream(), conn, 8);
        s.abort();
        s.abort();
        assertEquals(1, conn.closes.get());
    }
}
