package com.example.securechat.testsupport;

import com.example.securechat.client.ChatClient;
import com.example.securechat.common.Protocol;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.server.ChatServer;
import com.example.securechat.server.LoginThrottle;
import com.example.securechat.server.ServerOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import javax.net.ssl.SSLContext;

/** Real TLS peers for client API and criteria tests; every wait has a deadline. */
public final class ChatPeers implements AutoCloseable {
    public final ChatServer server;
    public final int port;
    private final SSLContext clientContext;
    private final List<Peer> peers = new ArrayList<>();

    public ChatPeers(Path data) throws Exception {
        clientContext = TestTls.client();
        server = new ChatServer(TestTls.server(),
                TestUsers.store(), 0, new ServerOptions(Duration.ofSeconds(5), 256,
                LoginThrottle.defaults(), data));
        port = server.start();
    }

    public Peer login(String username) throws Exception {
        ChatClient client = new ChatClient(clientContext);
        Peer peer = new Peer(client);
        peers.add(peer);
        client.connect("localhost", port, Protocol.CONNECT_TIMEOUT_MS);
        char[] password = switch (username) {
            case "an" -> "An@12345".toCharArray();
            case "binh" -> "Binh@12345".toCharArray();
            case "chi" -> "Chi@12345".toCharArray();
            default -> throw new IllegalArgumentException(username);
        };
        try {
            ChatMessage login = client.login(username, password);
            if (!Boolean.TRUE.equals(login.getSuccess())) {
                throw new AssertionError("Login failed: " + login.getContent());
            }
        } finally {
            Arrays.fill(password, '\0');
        }
        client.startReceiving(peer.messages::add, peer.closed::add);
        return peer;
    }

    @Override public void close() {
        for (Peer peer : peers) peer.client.close();
        server.stop();
    }

    public static final class Peer {
        public final ChatClient client;
        public final LinkedBlockingQueue<ChatMessage> messages = new LinkedBlockingQueue<>();
        public final LinkedBlockingQueue<Exception> closed = new LinkedBlockingQueue<>();
        private Peer(ChatClient client) { this.client = client; }

        public ChatMessage await(Predicate<ChatMessage> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                ChatMessage message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (message != null && predicate.test(message)) return message;
            }
            throw new AssertionError("Timed out waiting for message; connection errors: " + closed);
        }

        public ChatMessage awaitType(String type) throws InterruptedException {
            return await(m -> type.equals(m.getType()));
        }

        public void assertNoMessage(Predicate<ChatMessage> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350);
            while (System.nanoTime() < deadline) {
                ChatMessage message = messages.poll(25, TimeUnit.MILLISECONDS);
                if (message != null && predicate.test(message)) {
                    throw new AssertionError("Unexpected message: " + message);
                }
            }
        }
    }
}
