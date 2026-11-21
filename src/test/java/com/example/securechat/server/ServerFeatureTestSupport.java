package com.example.securechat.server;

import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.testsupport.MutableClock;
import com.example.securechat.testsupport.RawTlsClient;
import com.example.securechat.testsupport.TestCerts;
import com.example.securechat.testsupport.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Real TLS fixture for conversation and file integration scenarios. */
abstract class ServerFeatureTestSupport {
    static final Duration WAIT = Duration.ofSeconds(8);
    static SSLContext serverCtx;
    static SSLContext clientCtx;
    @TempDir Path dataDir;
    final MutableClock clock = new MutableClock();
    final List<RawTlsClient> clients = new ArrayList<>();
    ChatServer server;
    int port;

    @BeforeAll static void tls(@TempDir Path dir) throws Exception {
        TestCerts certs = TestCerts.create(dir);
        serverCtx = TlsContexts.server(certs.serverP12, TestCerts.PASSWORD.toCharArray());
        clientCtx = TlsContexts.client(certs.serverCrt);
    }

    @BeforeEach void startServer() throws Exception {
        restartServer(Protocol.DEFAULT_SEND_QUEUE_CAPACITY);
    }

    void restartServer(int capacity) throws Exception {
        if (server != null) server.stop();
        server = new ChatServer(serverCtx, TestUsers.store(), 0,
                new ServerOptions(Duration.ofSeconds(5), capacity, LoginThrottle.defaults(), dataDir), clock);
        port = server.start();
    }

    @AfterEach void cleanup() {
        for (RawTlsClient client : clients) client.close();
        if (server != null) server.stop();
    }

    RawTlsClient connect() throws IOException {
        RawTlsClient client = RawTlsClient.connect(clientCtx, port);
        clients.add(client);
        return client;
    }

    RawTlsClient connectSlow() throws IOException {
        RawTlsClient client = RawTlsClient.connectSlowReader(clientCtx, port);
        clients.add(client);
        return client;
    }

    ChatMessage login(RawTlsClient client, String user) throws IOException {
        String password = Character.toUpperCase(user.charAt(0)) + user.substring(1) + "@12345";
        client.send(ChatMessage.login(user, password.toCharArray()));
        ChatMessage reply = await(client, m -> "login_result".equals(m.getType()));
        assertTrue(reply.getSuccess(), reply.getContent());
        return reply;
    }

    RawTlsClient loginReady(String user) throws IOException {
        RawTlsClient client = connect();
        login(client, user);
        ready(client, user);
        return client;
    }

    void ready(RawTlsClient client, String user) throws IOException {
        await(client, m -> "system".equals(m.getType()) && (user + " đã tham gia").equals(m.getContent()));
    }

    ChatMessage await(RawTlsClient client, Predicate<ChatMessage> predicate) throws IOException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            ChatMessage message = client.read(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            assertNotNull(message, "Connection closed before expected message");
            if (predicate.test(message)) return message;
        }
        throw new AssertionError("Expected message did not arrive");
    }

    ChatMessage message(RawTlsClient client, String content) throws IOException {
        return await(client, m -> "message".equals(m.getType()) && content.equals(m.getContent()));
    }

    ChatMessage error(RawTlsClient client, String code) throws IOException {
        return await(client, m -> "error".equals(m.getType()) && code.equals(m.getContent()));
    }

    void assertNoMessage(RawTlsClient client, Predicate<ChatMessage> forbidden) throws IOException {
        long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ChatMessage next = client.read(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
                assertNotNull(next, "Connection unexpectedly closed");
                assertFalse(forbidden.test(next), "Private content leaked: " + next);
            } catch (SocketTimeoutException expected) {
                return;
            }
        }
    }
}
