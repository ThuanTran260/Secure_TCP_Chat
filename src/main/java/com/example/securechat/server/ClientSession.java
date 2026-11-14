package com.example.securechat.server;

import com.example.securechat.common.Protocol;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.JsonCodec;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Server-side state of one connected client plus its outbound send queue.
 *
 * <p>A single daemon writer thread is the only code that ever writes to the stream, so
 * messages can never interleave their bytes. Producers only call {@link #enqueue}, which
 * never blocks: a slow client fills its own bounded queue and the caller decides to drop it.
 */
public final class ClientSession {

    /** Sentinel (compared by identity) telling the writer thread to finish after pending lines. */
    private static final String POISON = new String("<poison>");

    private final int id;
    private final String remoteAddress;
    private final OutputStream out;
    private final Closeable connection;
    private final BlockingQueue<String> queue;

    private volatile boolean authenticated;
    private volatile String username;

    /** Set once closeGracefully() begins; no new messages are accepted afterwards. */
    private volatile boolean closing;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private volatile Thread writer;

    public ClientSession(int id, String remoteAddress, OutputStream out, Closeable connection,
                         int queueCapacity) {
        this.id = id;
        this.remoteAddress = remoteAddress;
        this.out = out;
        this.connection = connection;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    public int id() {
        return id;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public String username() {
        return username;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    /** Publishes the username before the volatile flag so readers of the flag see it. */
    public void markAuthenticated(String username) {
        this.username = username;
        this.authenticated = true;
    }

    /**
     * Starts the writer thread. {@code onWriteFailure} is invoked at most once, from the
     * writer thread, when a write fails on its own (not when we aborted the connection).
     */
    public void start(Consumer<IOException> onWriteFailure) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Session already started");
        }
        Thread t = new Thread(() -> writeLoop(onWriteFailure), "writer-" + id);
        t.setDaemon(true);
        writer = t;
        t.start();
    }

    private void writeLoop(Consumer<IOException> onWriteFailure) {
        try {
            while (true) {
                String line = queue.take();
                if (line == POISON) {
                    return;
                }
                out.write(Protocol.frame(line));
                out.flush();
            }
        } catch (IOException e) {
            // A failure caused by our own abort() is expected; only report genuine ones.
            if (!aborted.get() && failureReported.compareAndSet(false, true)) {
                onWriteFailure.accept(e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Never blocks. Returns false when the queue is full or the session is closing/closed. */
    public boolean enqueue(String jsonLine) {
        if (closing || aborted.get()) {
            return false;
        }
        return queue.offer(jsonLine);
    }

    public boolean send(ChatMessage m) {
        return enqueue(JsonCodec.encode(m));
    }

    /**
     * Stops accepting messages, lets the writer flush what is already queued for up to
     * {@code maxWait}, then closes the connection. Safe to call from the writer thread
     * itself (it then skips waiting for itself).
     */
    public void closeGracefully(Duration maxWait) {
        closing = true;
        long deadline = System.nanoTime() + maxWait.toNanos();
        Thread w = writer;
        boolean flushed = true;
        try {
            if (w != null && w.isAlive() && Thread.currentThread() != w) {
                // The queue may be full behind a stuck writer, so even the pill is bounded by maxWait.
                flushed = false;
                if (queue.offer(POISON, remaining(deadline), TimeUnit.NANOSECONDS)) {
                    w.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining(deadline))));
                    flushed = !w.isAlive();
                }
            }
        } catch (InterruptedException e) {
            flushed = false;
            Thread.currentThread().interrupt();
        } finally {
            // Everything queued was written, so the peer should still get it: close normally (FIN).
            // If the writer is stuck, close hard so we do not hang behind it.
            close(!flushed);
        }
    }

    private static long remaining(long deadlineNanos) {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    /**
     * Closes the connection immediately, without waiting for pending output. Idempotent; I/O errors
     * are swallowed. Never blocks, even when the writer thread is stuck in a socket write.
     */
    public void abort() {
        close(true);
    }

    private void close(boolean hard) {
        if (!aborted.compareAndSet(false, true)) {
            return;
        }
        closing = true;
        if (hard && connection instanceof Socket) {
            // SSLSocket.close() waits for the lock held by a writer blocked in write() to send close_notify,
            // so it would hang for as long as the peer does not read. With SO_LINGER 0 the JDK gives up on
            // the alert, closes the TCP connection at once and thereby unblocks that writer.
            try {
                ((Socket) connection).setSoLinger(true, 0);
            } catch (IOException | RuntimeException ignored) {
                // Already closed or not supported; the close below still runs.
            }
        }
        try {
            connection.close();
        } catch (IOException ignored) {
            // Nothing useful to do when closing a dead connection.
        }
        // Closing the connection frees a writer stuck in write(), but one idle in queue.take() would
        // park forever; interrupt it so an aborted session never leaks its thread. Skipped when called
        // on the writer itself (write-failure callback): it is already on its way out.
        Thread w = writer;
        if (w != null && w != Thread.currentThread()) {
            w.interrupt();
        }
    }
}
