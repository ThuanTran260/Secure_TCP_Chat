package com.example.securechat.testsupport;

import com.example.securechat.common.LineReader;
import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.InvalidMessageException;
import com.example.securechat.model.JsonCodec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Minimal TLS test client that speaks the line protocol without any chat logic, so tests can
 * send malformed or out-of-order traffic. It dials 127.0.0.1 but verifies the certificate against
 * the host name "localhost", exactly like the real client.
 */
public final class RawTlsClient implements AutoCloseable {

    private final SSLSocket socket;
    private final OutputStream out;
    private final LineReader reader;

    private RawTlsClient(SSLSocket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.reader = new LineReader(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
    }

    /** Connects and completes the TLS handshake. */
    public static RawTlsClient connect(SSLContext ctx, int port) throws IOException {
        return open(ctx, port, false);
    }

    /**
     * Like {@link #connect} but with a tiny receive buffer, and the caller is expected never to read.
     * The server's writes to this client therefore stall quickly, which models a slow consumer.
     */
    public static RawTlsClient connectSlowReader(SSLContext ctx, int port) throws IOException {
        return open(ctx, port, true);
    }

    private static RawTlsClient open(SSLContext ctx, int port, boolean tinyReceiveBuffer) throws IOException {
        Socket raw = new Socket();
        try {
            if (tinyReceiveBuffer) {
                raw.setReceiveBufferSize(4096); // must be set before connect to take effect
            }
            raw.connect(new InetSocketAddress("127.0.0.1", port), Protocol.CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(Protocol.HANDSHAKE_TIMEOUT_MS);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, "localhost", port, true);
            TlsContexts.hardenClient(s);
            s.startHandshake();
            return new RawTlsClient(s);
        } catch (IOException | RuntimeException e) {
            try {
                raw.close();
            } catch (IOException ignored) {
                // The original failure is what matters.
            }
            throw e;
        }
    }

    public void send(ChatMessage m) throws IOException {
        sendRaw(JsonCodec.encode(m));
    }

    /** Sends the text followed by '\n' without any validation. */
    public void sendRaw(String line) throws IOException {
        out.write(Protocol.frame(line));
        out.flush();
    }

    /**
     * Reads the next message, or returns null at end of stream.
     *
     * @throws SocketTimeoutException if nothing arrives within {@code timeout}
     */
    public ChatMessage read(Duration timeout) throws IOException {
        socket.setSoTimeout(toMillis(timeout));
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        try {
            return JsonCodec.decode(line);
        } catch (InvalidMessageException e) {
            throw new IOException("Server sent an invalid message: " + line, e);
        }
    }

    /**
     * Waits up to {@code timeout} for the server to close the connection, discarding anything it
     * still sends before that. A reset or TLS error also counts as closed.
     */
    public boolean isClosedByPeer(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                socket.setSoTimeout(toMillis(Duration.ofNanos(remaining)));
                if (reader.readLine() == null) {
                    return true;
                }
            }
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Test cleanup only.
        }
    }

    private static int toMillis(Duration d) {
        // soTimeout 0 means "wait forever", which a test must never do.
        long ms = Math.max(1, TimeUnit.NANOSECONDS.toMillis(d.toNanos()));
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }
}
