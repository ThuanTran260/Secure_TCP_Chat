package com.example.securechat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.JsonCodec;
import com.example.securechat.testsupport.LogCapture;
import com.example.securechat.testsupport.RawTlsClient;
import com.example.securechat.testsupport.TestCerts;
import com.example.securechat.testsupport.TestUsers;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ChatServerChatTest {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration QUIET = Duration.ofMillis(500);

    private static SSLContext serverCtx;
    private static SSLContext clientCtx;

    private final List<RawTlsClient> clients = new CopyOnWriteArrayList<>();
    private final List<ChatServer> servers = new ArrayList<>();
    private Set<Thread> writersBefore;
    private ChatServer server;
    private LogCapture logs;
    private int port;

    private RawTlsClient an;
    private RawTlsClient binh;
    private RawTlsClient chi;
    private int anId;
    private int binhId;
    private int chiId;

    @BeforeAll
    static void createTls(@TempDir Path dir) throws Exception {
        TestCerts certs = TestCerts.create(dir);
        serverCtx = TlsContexts.server(certs.serverP12, TestCerts.PASSWORD.toCharArray());
        clientCtx = TlsContexts.client(certs.serverCrt);
    }

    @BeforeEach
    void startDefaultServer() throws Exception {
        writersBefore = liveWriterThreads();
        logs = LogCapture.attach(ChatServer.LOG);
        startServer(Protocol.DEFAULT_SEND_QUEUE_CAPACITY);
    }

    @AfterEach
    void stopEverything() throws Exception {
        for (RawTlsClient c : clients) {
            c.close();
        }
        for (ChatServer s : servers) {
            s.stop();
        }
        logs.detach();
        // stop() joins its own threads and aborts every session, so neither handlers nor writers may survive.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((!liveChatThreads().isEmpty() || !newWriterThreads().isEmpty())
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of(), liveChatThreads(), "server threads leaked after stop()");
        assertEquals(List.of(), newWriterThreads(), "writer threads leaked after stop()");
    }

    private void startServer(int queueCapacity) throws IOException {
        LoginThrottle throttle = LoginThrottle.defaults();
        ServerOptions options = new ServerOptions(Duration.ofSeconds(5), queueCapacity, throttle);
        server = new ChatServer(serverCtx, TestUsers.store(), 0, options);
        servers.add(server);
        port = server.start();
    }

    private static Set<Thread> liveWriterThreads() {
        Set<Thread> result = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("writer-")) {
                result.add(t);
            }
        }
        return result;
    }

    private List<String> newWriterThreads() {
        List<String> names = new ArrayList<>();
        for (Thread t : liveWriterThreads()) {
            if (!writersBefore.contains(t)) {
                names.add(t.getName());
            }
        }
        return names;
    }

    private static List<String> liveChatThreads() {
        List<String> names = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("chat-")) {
                names.add(t.getName());
            }
        }
        return names;
    }

    private RawTlsClient connect() throws IOException {
        RawTlsClient c = RawTlsClient.connect(clientCtx, port);
        clients.add(c);
        return c;
    }

    private RawTlsClient connectSlow() throws IOException {
        RawTlsClient c = RawTlsClient.connectSlowReader(clientCtx, port);
        clients.add(c);
        return c;
    }

    /** Logs in and returns the clientId; reads exactly the login_result. */
    private static int login(RawTlsClient c, String user, String password) throws IOException {
        c.send(ChatMessage.login(user, password.toCharArray()));
        ChatMessage reply = c.read(READ_TIMEOUT);
        assertNotNull(reply, "connection closed before login_result");
        assertEquals("login_result", reply.getType());
        assertEquals(Boolean.TRUE, reply.getSuccess(), "login of " + user + " failed: " + reply.getContent());
        return reply.getClientId();
    }

    private void connectAndLoginAll() throws IOException {
        an = connect();
        anId = login(an, "an", "An@12345");
        binh = connect();
        binhId = login(binh, "binh", "Binh@12345");
        chi = connect();
        chiId = login(chi, "chi", "Chi@12345");
    }

    /** Logs everyone in and consumes the join notices, so tests start from a quiet state. */
    private void loginAll() throws IOException {
        connectAndLoginAll();
        // Each queue is ordered, so once the last join notice arrived every earlier one has too.
        for (RawTlsClient c : List.of(an, binh, chi)) {
            ChatMessage joined = readUntil(c, "system", "chi đã tham gia");
            assertEquals("system", joined.getType());
        }
    }

    /** Reads until a message of the given type (and, if not null, content) arrives; skips the rest. */
    private static ChatMessage readUntil(RawTlsClient c, String type, String content) throws IOException {
        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ChatMessage m = c.read(READ_TIMEOUT);
            assertNotNull(m, "connection closed while waiting for " + type + " " + content);
            if (type.equals(m.getType()) && (content == null || content.equals(m.getContent()))) {
                return m;
            }
        }
        throw new AssertionError("timed out waiting for " + type + " " + content);
    }

    private static ChatMessage readOfType(RawTlsClient c, String type) throws IOException {
        return readUntil(c, type, null);
    }

    private static void assertNothingWithin(RawTlsClient c, Duration quiet) throws IOException {
        try {
            ChatMessage m = c.read(quiet);
            fail("expected silence but got " + (m == null ? "EOF" : JsonCodec.encode(m)));
        } catch (SocketTimeoutException expected) {
            // silence is what we want
        }
    }

    /** Polls for a log line that is written asynchronously to what the client observes. */
    private boolean awaitLog(String... fragments) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            for (String line : logs.messages()) {
                boolean all = true;
                for (String f : fragments) {
                    all &= line.contains(f);
                }
                if (all) {
                    return true;
                }
            }
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
    }

    @Test
    void tc01_broadcastReachesAllThree() throws Exception {
        loginAll();
        an.send(ChatMessage.message("Xin chào mọi người!"));

        for (RawTlsClient c : List.of(an, binh, chi)) {
            ChatMessage m = readOfType(c, "message");
            assertEquals("an", m.getFrom());
            assertEquals(anId, m.getClientId());
            assertEquals("Xin chào mọi người!", m.getContent());
        }
    }

    @Test
    void joinNoticeSentToOthers() throws Exception {
        connectAndLoginAll();
        ChatMessage joined = readUntil(an, "system", "chi đã tham gia");
        assertEquals("system", joined.getType());
    }

    @Test
    void tc06_abruptDisconnect() throws Exception {
        loginAll();
        binh.close(); // no disconnect message

        for (RawTlsClient c : List.of(an, chi)) {
            ChatMessage left = readOfType(c, "user_left");
            assertEquals(binhId, left.getClientId());
            assertEquals("binh", left.getFrom());
        }
        assertEquals(2, server.registry().size());
        assertTrue(awaitLog("[INFO] Client " + binhId + " disconnected"), logs.messages().toString());

        an.send(ChatMessage.message("còn ai không?"));
        assertEquals("còn ai không?", readOfType(chi, "message").getContent());
    }

    @Test
    void gracefulDisconnectNotifiesOnce() throws Exception {
        loginAll();
        chi.send(ChatMessage.disconnect());

        ChatMessage left = readOfType(an, "user_left");
        assertEquals(chiId, left.getClientId());
        assertEquals("chi", left.getFrom());
        assertTrue(awaitLog("[INFO] Client " + chiId + " disconnected", "client requested"),
                logs.messages().toString());
        // A second notice (e.g. from the handler's finally block) would show up as another message.
        assertNothingWithin(an, Duration.ofSeconds(1));
    }

    @Test
    void invalidJsonKeepsConnection() throws Exception {
        loginAll();
        an.sendRaw("{oops");
        ChatMessage error = readOfType(an, "error");
        assertEquals("INVALID_JSON", error.getContent());

        an.send(ChatMessage.message("vẫn còn kết nối"));
        assertEquals("vẫn còn kết nối", readOfType(binh, "message").getContent());
    }

    @Test
    void unknownTypeRejected() throws Exception {
        loginAll();
        an.sendRaw("{\"type\":\"ping\"}");
        assertEquals("UNKNOWN_TYPE", readOfType(an, "error").getContent());
    }

    @Test
    void emptyMessageRejected() throws Exception {
        loginAll();
        an.send(ChatMessage.message("   "));
        assertEquals("EMPTY_MESSAGE", readOfType(an, "error").getContent());
        assertNothingWithin(binh, QUIET);

        // The connection is still fine afterwards.
        an.send(ChatMessage.message("sau đó"));
        assertEquals("sau đó", readOfType(binh, "message").getContent());
    }

    /** Largest k such that a chat line carrying k three-byte characters still fits the 4096-byte limit. */
    private int maxEnrichedChars() {
        int k = 1;
        while (Protocol.fitsLine(JsonCodec.encode(ChatMessage.chat(anId, "an", "ệ".repeat(k + 1))))) {
            k++;
        }
        return k;
    }

    @Test
    void enrichedMessageAtLimitIsDelivered() throws Exception {
        loginAll();
        int k = maxEnrichedChars();
        System.out.println("enrichedMessage boundary: k = " + k);
        String content = "ệ".repeat(k);
        assertTrue(Protocol.fitsLine(JsonCodec.encode(ChatMessage.chat(anId, "an", content))));

        an.send(ChatMessage.message(content));
        ChatMessage m = readOfType(binh, "message");
        assertEquals(content, m.getContent());
        assertEquals("an", m.getFrom());
    }

    @Test
    void enrichedMessageOverLimitIsRejected() throws Exception {
        loginAll();
        int k = maxEnrichedChars();
        String tooLong = "ệ".repeat(k + 1);
        // The client's own line is shorter than the enriched one, so only the server-side check can catch this.
        assertTrue(Protocol.fitsLine(JsonCodec.encode(ChatMessage.message(tooLong))));

        an.send(ChatMessage.message(tooLong));
        assertEquals("MESSAGE_TOO_LARGE", readOfType(an, "error").getContent());
        assertNothingWithin(binh, QUIET);

        // an stays connected and the room still works.
        an.send(ChatMessage.message("ngắn"));
        assertEquals("ngắn", readOfType(an, "message").getContent());
        assertEquals("ngắn", readOfType(binh, "message").getContent());
    }

    @Test
    void oversizedIncomingLineClosesOnlyThatClient() throws Exception {
        loginAll();
        an.sendRaw("x".repeat(5000));

        assertEquals("MESSAGE_TOO_LARGE", readOfType(an, "error").getContent());
        assertTrue(an.isClosedByPeer(Duration.ofSeconds(3)));

        binh.send(ChatMessage.message("vẫn chat được"));
        assertEquals("vẫn chat được", readOfType(binh, "message").getContent());
        assertEquals("vẫn chat được", readOfType(chi, "message").getContent());
    }

    @Test
    void secondLoginRejected() throws Exception {
        loginAll();
        an.send(ChatMessage.login("an", "An@12345".toCharArray()));
        assertEquals("ALREADY_AUTHENTICATED", readOfType(an, "error").getContent());
    }

    @Test
    void usernameFreedAfterDisconnect() throws Exception {
        loginAll();
        an.send(ChatMessage.disconnect());
        // The name is released before the user_left broadcast, so seeing it means "an" is free again.
        assertEquals("an", readOfType(binh, "user_left").getFrom());

        RawTlsClient again = connect();
        login(again, "an", "An@12345");
    }

    /** How many messages the slow-client test sends. */
    private static final int SLOW_TEST_MESSAGES = 2000;

    /** Max messages the slow-client test's sender keeps unread by the healthy clients (queue capacity is 8). */
    private static final int IN_FLIGHT_WINDOW = 6;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void slowClientDoesNotBlockOthers() throws Exception {
        startServer(8);
        an = connect();
        anId = login(an, "an", "An@12345");
        binh = connect();
        binhId = login(binh, "binh", "Binh@12345");
        RawTlsClient slow = connectSlow();
        chiId = login(slow, "chi", "Chi@12345"); // logs in, then never reads again
        System.out.println("slowClient test: messages = " + SLOW_TEST_MESSAGES);

        int total = SLOW_TEST_MESSAGES;
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            // Index prefix makes every payload unique so ordering can be checked; total length stays 3500.
            expected.add(String.format("%05d", i) + "x".repeat(3500 - 5));
        }

        // Blocking socket I/O ignores interrupts, so the sender and readers run off the test thread and
        // every wait is bounded; the finally block closes the clients to unblock whatever is left.
        ExecutorService workers = Executors.newFixedThreadPool(3);
        AtomicInteger anReceived = new AtomicInteger();
        AtomicInteger binhCount = new AtomicInteger();
        try {
            // an must drain its own copies too, otherwise its own bounded queue (8) would overflow.
            Future<?> anDrain = workers.submit(() -> {
                for (int i = 0; i < total; i++) {
                    readOfType(an, "message");
                    anReceived.incrementAndGet();
                }
                return null;
            });
            Future<List<String>> binhReceived = workers.submit(() -> {
                List<String> got = new ArrayList<>();
                while (got.size() < total) {
                    got.add(readOfType(binh, "message").getContent());
                    binhCount.incrementAndGet();
                }
                return got;
            });

            long start = System.nanoTime();
            Future<?> sender = workers.submit(() -> {
                long stallDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                for (int i = 0; i < total; i++) {
                    // Keep fewer messages in flight than the queue holds (8). A reader thread that is merely
                    // slower than a tight sending loop is not what this test is about: only chi must be dropped.
                    while (i - Math.min(anReceived.get(), binhCount.get()) >= IN_FLIGHT_WINDOW) {
                        if (System.nanoTime() > stallDeadline) {
                            throw new AssertionError("readers stalled at message " + i);
                        }
                        LockSupport.parkNanos(20_000);
                    }
                    an.send(ChatMessage.message(expected.get(i)));
                }
                return null;
            });
            List<String> got = binhReceived.get(40, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            System.out.println("slowClient test: binh received all " + total + " in " + elapsedMs + " ms");
            sender.get(10, TimeUnit.SECONDS);
            anDrain.get(10, TimeUnit.SECONDS);

            assertTrue(elapsedMs < 20_000, "binh needed " + elapsedMs + " ms for " + total + " messages");
            assertEquals(expected, got);

            assertTrue(awaitLog("[INFO] Client " + chiId + " disconnected", "send queue full"),
                    logs.messages().toString());
            for (ClientSession s : server.registry().authenticatedSnapshot()) {
                assertFalse(s.id() == chiId, "slow client is still registered");
            }
            assertEquals(2, server.registry().size());
        } finally {
            for (RawTlsClient c : clients) {
                c.close();
            }
            workers.shutdownNow();
        }
    }
}
