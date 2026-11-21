package com.example.securechat.testsupport;

import com.example.securechat.common.LineReader;
import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.JsonCodec;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Authenticates one real TLS client and deliberately holds every file_get response. */
public final class ControlledTlsServer implements AutoCloseable {
    private final SSLServerSocket listener;
    private final LinkedBlockingQueue<ChatMessage> requests = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile SSLSocket connection;
    private volatile boolean closing;
    private volatile Exception failure;

    public ControlledTlsServer() throws IOException {
        listener = (SSLServerSocket) TestTls.server().getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        TlsContexts.hardenServer(listener);
        listener.setSoTimeout(10_000);
        worker = new Thread(this::serve, "controlled-tls-server");
        worker.setDaemon(true);
        worker.start();
    }

    public int port() { return listener.getLocalPort(); }

    private void serve() {
        try (SSLSocket socket = (SSLSocket) listener.accept()) {
            connection = socket;
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            LineReader reader = new LineReader(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
            String line;
            while ((line = reader.readLine()) != null) {
                ChatMessage request = JsonCodec.decode(line);
                if ("login".equals(request.getType())) {
                    socket.getOutputStream().write(Protocol.frame(JsonCodec.encode(ChatMessage.loginResult(true, 1, null))));
                    socket.getOutputStream().flush();
                } else {
                    requests.add(request);
                    if ("disconnect".equals(request.getType())) return;
                }
            }
        } catch (Exception e) {
            if (!closing) failure = e;
        }
    }

    public ChatMessage awaitRequest(String type) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (failure != null) throw new AssertionError("Controlled TLS peer failed", failure);
            ChatMessage message = requests.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && type.equals(message.getType())) return message;
        }
        throw new AssertionError("Client did not send " + type);
    }

    @Override public void close() throws Exception {
        closing = true;
        listener.close();
        SSLSocket socket = connection;
        if (socket != null) socket.close();
        worker.join(2_000);
        if (worker.isAlive()) throw new AssertionError("Controlled TLS peer thread leaked");
    }
}
