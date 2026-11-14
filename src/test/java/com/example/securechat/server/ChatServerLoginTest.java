package com.example.securechat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.testsupport.LogCapture;
import com.example.securechat.testsupport.MutableClock;
import com.example.securechat.testsupport.RawTlsClient;
import com.example.securechat.testsupport.TestCerts;
import com.example.securechat.testsupport.TestUsers;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ChatServerLoginTest {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final String BAD_CREDENTIALS = "Username hoặc Password không đúng";
    private static final String ALREADY_ONLINE = "Tài khoản đang online";
    private static final String THROTTLED = "Đăng nhập sai quá nhiều lần, thử lại sau";

    private static SSLContext serverCtx;
    private static SSLContext clientCtx;

    private final List<RawTlsClient> clients = new CopyOnWriteArrayList<>();
    private final List<ChatServer> servers = new ArrayList<>();
    private MutableClock clock;
    private ChatServer server;
    private LogCapture logs;
    private int port;

    @BeforeAll
    static void createTls(@TempDir Path dir) throws Exception {
        TestCerts certs = TestCerts.create(dir);
        serverCtx = TlsContexts.server(certs.serverP12, TestCerts.PASSWORD.toCharArray());
        clientCtx = TlsContexts.client(certs.serverCrt);
    }

    @BeforeEach
    void startServer() throws Exception {
        logs = LogCapture.attach(ChatServer.LOG);
        clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(clock, 5, Duration.ofMinutes(5), Duration.ofSeconds(60));
        ServerOptions options = new ServerOptions(Duration.ofSeconds(1), 256, throttle);
        server = newServer(TestUsers.store(), options);
        port = server.start();
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
        // stop() must join its own threads; a surviving "chat-" thread would be a leak.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (liveChatThreads().size() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of(), liveChatThreads(), "server threads leaked after stop()");
    }

    private ChatServer newServer(UserStore users, ServerOptions options) {
        ChatServer s = new ChatServer(serverCtx, users, 0, options);
        servers.add(s);
        return s;
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

    /** Polls for a log line that is written asynchronously to what the client observes. */
    private boolean awaitLog(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!logs.contains(fragment)) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    private RawTlsClient connect() throws IOException {
        return connect(port);
    }

    private RawTlsClient connect(int serverPort) throws IOException {
        RawTlsClient c = RawTlsClient.connect(clientCtx, serverPort);
        clients.add(c);
        return c;
    }

    private static ChatMessage login(RawTlsClient c, String user, String password) throws IOException {
        c.send(ChatMessage.login(user, password.toCharArray()));
        ChatMessage reply = c.read(READ_TIMEOUT);
        assertNotNull(reply, "connection closed before login_result");
        assertEquals("login_result", reply.getType());
        return reply;
    }

    @Test
    void tc01_validLoginGetsClientId() throws Exception {
        RawTlsClient c = connect();
        ChatMessage reply = login(c, "an", "An@12345");

        assertEquals(Boolean.TRUE, reply.getSuccess());
        assertNotNull(reply.getClientId());
        assertTrue(reply.getClientId() >= 1);
        assertTrue(logs.contains("Server listening on port " + port), logs.messages().toString());
        assertTrue(logs.contains("[PASS] TLS authenticated: client " + reply.getClientId() + " ("),
                logs.messages().toString());
    }

    @Test
    void tc02_wrongPasswordRejectedAndClosed() throws Exception {
        RawTlsClient c = connect();
        ChatMessage reply = login(c, "an", "wrong");

        assertEquals(Boolean.FALSE, reply.getSuccess());
        assertEquals(BAD_CREDENTIALS, reply.getContent());
        assertTrue(c.isClosedByPeer(Duration.ofSeconds(2)));
        assertEquals(0, server.registry().authenticatedSnapshot().size());
        assertTrue(logs.contains("[BLOCK] Login failed"), logs.messages().toString());
    }

    @Test
    void unknownUserSameMessageAsWrongPassword() throws Exception {
        RawTlsClient c = connect();
        ChatMessage reply = login(c, "ghost", "x");

        assertEquals(Boolean.FALSE, reply.getSuccess());
        assertEquals(BAD_CREDENTIALS, reply.getContent());
    }

    @Test
    void bruteForceIsThrottled() throws Exception {
        for (int i = 0; i < 5; i++) {
            RawTlsClient c = connect();
            ChatMessage reply = login(c, "an", "wrong");
            assertEquals(BAD_CREDENTIALS, reply.getContent());
        }

        // Even the correct password is refused while the (ip, username) pair is locked.
        ChatMessage locked = login(connect(), "an", "An@12345");
        assertEquals(Boolean.FALSE, locked.getSuccess());
        assertEquals(THROTTLED, locked.getContent());
        assertTrue(logs.contains("[BLOCK] Login throttled"), logs.messages().toString());

        clock.advance(Duration.ofSeconds(61));
        ChatMessage afterLockout = login(connect(), "an", "An@12345");
        assertEquals(Boolean.TRUE, afterLockout.getSuccess());
    }

    @Test
    void messageBeforeLoginIsRejected() throws Exception {
        RawTlsClient c = connect();
        c.send(ChatMessage.message("hi"));
        ChatMessage error = c.read(READ_TIMEOUT);
        assertNotNull(error);
        assertEquals("error", error.getType());
        assertEquals("NOT_AUTHENTICATED", error.getContent());

        // The connection stays open, so the client can still log in.
        assertEquals(Boolean.TRUE, login(c, "an", "An@12345").getSuccess());
    }

    @Test
    void tc05_plainTcpIsRejected() throws Exception {
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            plain.setSoTimeout(5000);
            OutputStream out = plain.getOutputStream();
            out.write(Protocol.frame("{\"type\":\"login\",\"username\":\"an\",\"password\":\"An@12345\"}"));
            out.flush();

            // The server may answer with a TLS alert before closing, so drain until EOF or reset.
            InputStream in = plain.getInputStream();
            try {
                while (in.read() != -1) {
                    // discard
                }
            } catch (SocketTimeoutException e) {
                throw new AssertionError("server kept the plain TCP connection open", e);
            } catch (IOException e) {
                // A reset is also a closed connection.
            }
        }
        // The JDK closes the socket while failing the handshake, i.e. possibly before our catch block logs.
        assertTrue(awaitLog("[BLOCK] TLS handshake failed"), logs.messages().toString());
        assertEquals(0, server.registry().size());

        assertEquals(Boolean.TRUE, login(connect(), "an", "An@12345").getSuccess());
    }

    @Test
    void duplicateLoginIsRejected() throws Exception {
        RawTlsClient a = connect();
        ChatMessage first = login(a, "an", "An@12345");
        assertEquals(Boolean.TRUE, first.getSuccess());

        RawTlsClient b = connect();
        ChatMessage second = login(b, "an", "An@12345");
        assertEquals(Boolean.FALSE, second.getSuccess());
        assertEquals(ALREADY_ONLINE, second.getContent());
        assertTrue(b.isClosedByPeer(Duration.ofSeconds(2)));

        List<ClientSession> online = server.registry().authenticatedSnapshot();
        assertEquals(1, online.size());
        assertEquals(first.getClientId().intValue(), online.get(0).id());
        assertEquals("an", online.get(0).username());
        assertFalse(a.isClosedByPeer(Duration.ofMillis(300)), "first session must not be affected");
    }

    @Test
    void idleUnauthenticatedClientIsDisconnected() throws Exception {
        RawTlsClient c = connect();
        assertTrue(c.isClosedByPeer(Duration.ofSeconds(3)));
        assertTrue(logs.contains("login timeout"), logs.messages().toString());
    }

    @Test
    void passwordNeverLogged() throws Exception {
        assertEquals(Boolean.TRUE, login(connect(), "an", "An@12345").getSuccess());
        RawTlsClient bad = connect();
        assertEquals(Boolean.FALSE, login(bad, "binh", "wrong").getSuccess());
        assertTrue(bad.isClosedByPeer(Duration.ofSeconds(2)));

        assertFalse(logs.messages().isEmpty());
        for (String line : logs.messages()) {
            assertFalse(line.contains("An@12345"), line);
            assertFalse(line.contains("wrong"), line);
        }
    }

    @Test
    void manyClientsAcceptedConcurrently() throws Exception {
        int n = 10;
        ServerOptions options = new ServerOptions(Duration.ofSeconds(10), 256,
                new LoginThrottle(clock, 5, Duration.ofMinutes(5), Duration.ofSeconds(60)));
        ChatServer many = newServer(TestUsers.storeWithUsers(n), options);
        int manyPort = many.start();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<ChatMessage>> tasks = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                int idx = i;
                tasks.add(() -> login(connect(manyPort), "u" + idx, "Pass@" + idx));
            }
            Set<Integer> ids = new HashSet<>();
            for (Future<ChatMessage> f : pool.invokeAll(tasks)) {
                ChatMessage reply = f.get(15, TimeUnit.SECONDS);
                assertEquals(Boolean.TRUE, reply.getSuccess());
                ids.add(reply.getClientId());
            }
            assertEquals(n, ids.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
