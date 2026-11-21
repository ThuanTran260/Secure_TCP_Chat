package com.example.securechat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.server.ChatServer;
import com.example.securechat.server.ServerOptions;
import com.example.securechat.testsupport.LogCapture;
import com.example.securechat.testsupport.TestCerts;
import com.example.securechat.testsupport.TestUsers;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ChatClientTest {

    private static TestCerts certs;
    private static SSLContext goodServerCtx;
    private static SSLContext goodClientCtx;

    private final List<ChatClient> clients = new CopyOnWriteArrayList<>();
    private final List<ChatServer> servers = new ArrayList<>();
    private LogCapture logs;
    @TempDir Path dataDir;

    @BeforeAll
    static void createTls(@TempDir Path dir) throws Exception {
        certs = TestCerts.create(dir);
        goodServerCtx = TlsContexts.server(certs.serverP12, TestCerts.PASSWORD.toCharArray());
        goodClientCtx = TlsContexts.client(certs.serverCrt);
    }

    @BeforeEach
    void attachLogs() {
        logs = LogCapture.attach(Logger.getLogger("securechat.server"));
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (ChatClient c : clients) {
            c.close();
        }
        for (ChatServer s : servers) {
            s.stop();
        }
        logs.detach();
        // Receive threads are named "chat-receive"; none may outlive the test.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!liveReceiveThreads().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of(), liveReceiveThreads(), "chat-receive threads leaked after close()");
    }

    private int startServer(SSLContext serverCtx) throws IOException {
        ChatServer server = new ChatServer(serverCtx, TestUsers.store(), 0, isolatedOptions());
        servers.add(server);
        return server.start();
    }

    private ServerOptions isolatedOptions() {
        ServerOptions defaults = ServerOptions.defaults();
        return new ServerOptions(defaults.loginTimeout(), defaults.sendQueueCapacity(), defaults.throttle(),
                dataDir.resolve("server-" + servers.size()));
    }

    private ChatClient newClient(SSLContext ctx) {
        ChatClient c = new ChatClient(ctx);
        clients.add(c);
        return c;
    }

    private static List<String> liveReceiveThreads() {
        List<String> names = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().equals("chat-receive")) {
                names.add(t.getName());
            }
        }
        return names;
    }

    private ChatClient loggedIn(int port, String host, String user, String pass) throws IOException {
        ChatClient c = newClient(goodClientCtx);
        c.connect(host, port, Protocol.CONNECT_TIMEOUT_MS);
        ChatMessage r = c.login(user, pass.toCharArray());
        assertTrue(Boolean.TRUE.equals(r.getSuccess()), "login failed: " + r);
        return c;
    }

    @Test
    void connectLoginAndReceiveOwnBroadcast() throws Exception {
        int port = startServer(goodServerCtx);
        ChatClient c = loggedIn(port, "localhost", "an", "An@12345");
        assertTrue(List.of("TLSv1.3", "TLSv1.2").contains(c.negotiatedProtocol()), c.negotiatedProtocol());

        BlockingQueue<ChatMessage> received = new LinkedBlockingQueue<>();
        c.startReceiving(received::add, e -> { });
        c.send("Xin chào!");

        // The join notice may arrive first; wait for the chat message itself.
        ChatMessage chat = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (chat == null && System.nanoTime() < deadline) {
            ChatMessage m = received.poll(100, TimeUnit.MILLISECONDS);
            if (m != null && "message".equals(m.getType())) {
                chat = m;
            }
        }
        assertNotNull(chat, "own broadcast not received within 2s");
        assertEquals("an", chat.getFrom());
        assertEquals("Xin chào!", chat.getContent());
    }

    @Test
    void connectByIpMatchesSan() throws Exception {
        int port = startServer(goodServerCtx);
        ChatClient c = newClient(goodClientCtx);
        c.connect("127.0.0.1", port, Protocol.CONNECT_TIMEOUT_MS);
        assertNotNull(c.negotiatedProtocol());
    }

    @Test
    void tc03_wrongHostCertRejected() throws Exception {
        int port = startServer(TlsContexts.server(certs.wrongHostP12, TestCerts.PASSWORD.toCharArray()));
        ChatClient c = newClient(TlsContexts.client(certs.wrongHostCrt));
        assertThrows(SSLHandshakeException.class,
                () -> c.connect("localhost", port, Protocol.CONNECT_TIMEOUT_MS));
        assertFalse(logs.contains("Login"), "no login may be sent after a failed handshake: " + logs.messages());
    }

    @Test
    void tc04_expiredCertRejected() throws Exception {
        int port = startServer(TlsContexts.server(certs.expiredP12, TestCerts.PASSWORD.toCharArray()));
        ChatClient c = newClient(TlsContexts.client(certs.expiredCrt));
        assertThrows(SSLHandshakeException.class,
                () -> c.connect("localhost", port, Protocol.CONNECT_TIMEOUT_MS));
    }

    @Test
    void tc04_untrustedCertRejected() throws Exception {
        int port = startServer(TlsContexts.server(certs.untrustedP12, TestCerts.PASSWORD.toCharArray()));
        ChatClient c = newClient(goodClientCtx);
        assertThrows(SSLHandshakeException.class,
                () -> c.connect("localhost", port, Protocol.CONNECT_TIMEOUT_MS));
    }

    @Test
    void tooLongMessageRejectedLocally() throws Exception {
        int port = startServer(goodServerCtx);
        ChatClient c = loggedIn(port, "localhost", "an", "An@12345");
        BlockingQueue<ChatMessage> received = new LinkedBlockingQueue<>();
        c.startReceiving(received::add, e -> { });

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.send("x".repeat(5000)));
        assertEquals("Tin nhắn quá dài", e.getMessage());

        c.send("vẫn dùng được");
        ChatMessage chat = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (chat == null && System.nanoTime() < deadline) {
            ChatMessage m = received.poll(100, TimeUnit.MILLISECONDS);
            if (m != null && "message".equals(m.getType())) {
                chat = m;
            }
        }
        assertNotNull(chat, "connection should still be usable");
        assertEquals("vẫn dùng được", chat.getContent());
    }

    @Test
    void connectionRefusedIsConnectException() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        ChatClient c = newClient(goodClientCtx);
        assertThrows(ConnectException.class, () -> c.connect("localhost", freePort, 2000));
    }

    @Test
    void clientNotifiedWhenServerStops() throws Exception {
        ChatServer server = new ChatServer(goodServerCtx, TestUsers.store(), 0, isolatedOptions());
        servers.add(server);
        int port = server.start();
        ChatClient c = loggedIn(port, "localhost", "an", "An@12345");
        AtomicInteger closedCalls = new AtomicInteger();
        BlockingQueue<Exception> closed = new LinkedBlockingQueue<>();
        c.startReceiving(m -> { }, e -> {
            closedCalls.incrementAndGet();
            closed.add(e);
        });

        server.stop();
        assertNotNull(closed.poll(3, TimeUnit.SECONDS), "onClosed not invoked within 3s");
        Thread.sleep(200);
        assertEquals(1, closedCalls.get(), "onClosed must be invoked exactly once");
    }

    @Test
    void closeIsIdempotent() throws Exception {
        int port = startServer(goodServerCtx);
        ChatClient c = loggedIn(port, "localhost", "an", "An@12345");
        c.close();
        c.close();
    }
}
