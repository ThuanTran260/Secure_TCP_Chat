package com.example.securechat.client;

import com.example.securechat.common.LineReader;
import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.InvalidMessageException;
import com.example.securechat.model.JsonCodec;
import com.example.securechat.model.MessageType;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * TLS chat client. The calling thread does connect/login/send; once {@link #startReceiving} is called
 * a single daemon thread ("chat-receive") is the only reader of the stream.
 */
public final class ChatClient implements AutoCloseable {

    static final int LOGIN_TIMEOUT_MS = 10_000;
    private static final long LOCK_WAIT_MS = 1_000;

    private final SSLContext ctx;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closedNotified = new AtomicBoolean();

    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private volatile LineReader reader;
    private volatile Thread receiveThread;

    public ChatClient(SSLContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /**
     * Opens the TCP connection (bounded by {@code timeoutMs}) and completes a hardened TLS handshake.
     * Certificate chain, validity period and host name are verified before this method returns.
     */
    public void connect(String host, int port, int timeoutMs) throws IOException {
        if (socket != null) {
            throw new IllegalStateException("Already connected");
        }
        Socket raw = new Socket();
        SSLSocket tls = null;
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            // Bound the handshake too, so a server that accepts TCP but never answers cannot hang us.
            raw.setSoTimeout(Protocol.HANDSHAKE_TIMEOUT_MS);
            tls = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            TlsContexts.hardenClient(tls);
            tls.startHandshake();
            tls.setSoTimeout(0);
            this.out = tls.getOutputStream();
            this.reader = new LineReader(tls.getInputStream(), Protocol.MAX_LINE_BYTES);
            this.socket = tls;
        } catch (IOException | RuntimeException e) {
            closeQuietly(tls != null ? tls : raw);
            throw e;
        }
    }

    public String negotiatedProtocol() {
        SSLSocket s = requireSocket();
        return s.getSession().getProtocol();
    }

    /**
     * Sends the login request and synchronously waits (up to 10 s) for {@code login_result},
     * ignoring any other message that arrives first. Call before {@link #startReceiving}.
     */
    public ChatMessage login(String username, char[] password) throws IOException {
        SSLSocket s = requireSocket();
        writeLine(JsonCodec.encode(ChatMessage.login(username, password)));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOGIN_TIMEOUT_MS);
        try {
            while (true) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    throw new SocketTimeoutException("Login timed out");
                }
                s.setSoTimeout((int) remainingMs);
                String line = reader.readLine();
                if (line == null) {
                    throw new EOFException("Server closed the connection");
                }
                ChatMessage m;
                try {
                    m = JsonCodec.decode(line);
                } catch (InvalidMessageException e) {
                    continue;
                }
                if (m.messageType().orElse(null) == MessageType.LOGIN_RESULT) {
                    return m;
                }
            }
        } finally {
            if (!closed.get()) {
                try {
                    s.setSoTimeout(0);
                } catch (IOException ignored) {
                    // Socket already broken; the next read reports it.
                }
            }
        }
    }

    /**
     * Starts the single reader thread. {@code onClosed} runs exactly once when the stream ends,
     * fails, or the client is closed locally.
     */
    public void startReceiving(Consumer<ChatMessage> onMessage, Consumer<Exception> onClosed) {
        requireSocket();
        Thread t = new Thread(() -> receiveLoop(onMessage, onClosed), "chat-receive");
        t.setDaemon(true);
        synchronized (this) {
            if (receiveThread != null) {
                throw new IllegalStateException("Already receiving");
            }
            receiveThread = t;
        }
        t.start();
    }

    private void receiveLoop(Consumer<ChatMessage> onMessage, Consumer<Exception> onClosed) {
        Exception reason = null;
        try {
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    reason = new EOFException("Server closed the connection");
                    break;
                }
                ChatMessage m;
                try {
                    m = JsonCodec.decode(line);
                } catch (InvalidMessageException e) {
                    continue; // skip a malformed line; framing is still intact
                }
                onMessage.accept(m);
            }
        } catch (IOException e) {
            reason = e;
        } catch (RuntimeException e) {
            reason = e;
        } finally {
            closeQuietly(socket);
            if (closedNotified.compareAndSet(false, true)) {
                onClosed.accept(reason);
            }
        }
    }

    /** Sends a chat line; rejects content whose encoded form would exceed the protocol line limit. */
    public void send(String content) throws IOException {
        String encoded = JsonCodec.encode(ChatMessage.message(content));
        if (!Protocol.fitsLine(encoded)) {
            throw new IllegalArgumentException("Tin nhắn quá dài");
        }
        writeLine(encoded);
    }

    private void writeLine(String json) throws IOException {
        requireSocket();
        writeLock.lock();
        try {
            if (closed.get()) {
                throw new IOException("Client is closed");
            }
            out.write(Protocol.frame(json));
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    /** Best-effort {@code disconnect} followed by closing the socket; safe to call repeatedly. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        SSLSocket s = socket;
        if (s == null) {
            return;
        }
        boolean locked = false;
        try {
            // Do not wait forever behind a writer stuck on a full socket buffer.
            locked = writeLock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
            if (locked) {
                out.write(Protocol.frame(JsonCodec.encode(ChatMessage.disconnect())));
                out.flush();
            }
        } catch (IOException ignored) {
            // Best effort: the peer may already be gone.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked) {
                writeLock.unlock();
            }
            closeQuietly(s);
        }
        Thread t = receiveThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private SSLSocket requireSocket() {
        SSLSocket s = socket;
        if (s == null) {
            throw new IllegalStateException("Not connected");
        }
        return s;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}
