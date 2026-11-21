package com.example.securechat.gui;

import com.example.securechat.client.ChatClient;
import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.server.ChatServer;
import com.example.securechat.server.LoginThrottle;
import com.example.securechat.server.ServerOptions;
import com.example.securechat.testsupport.TestCerts;
import com.example.securechat.testsupport.TestUsers;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.SSLContext;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class MediaCacheTest {
    private static SSLContext serverContext;
    private static SSLContext clientContext;
    @TempDir Path temporary;

    @BeforeAll static void tls(@TempDir Path certificates) throws Exception {
        TestCerts certs = TestCerts.create(certificates);
        serverContext = TlsContexts.server(certs.serverP12, TestCerts.PASSWORD.toCharArray());
        clientContext = TlsContexts.client(certs.serverCrt);
    }

    @Test void pathForSanitizesName() {
        MediaCache cache = new MediaCache(temporary);
        String id = UUID.randomUUID().toString();
        assertEquals(temporary.resolve(id + "_a.png"), cache.pathFor(id, "../a.png"));
        assertEquals(temporary.resolve(id + "_a.png"), cache.pathFor(id, "..\\..\\a.png"));
        assertTrue(cache.pathFor(id, "../../").startsWith(temporary));
        assertThrows(IllegalArgumentException.class, () -> cache.pathFor("../escape", "a.png"));
    }

    @Test void concurrentGetSharesOneDownload() throws Exception {
        ChatServer server = new ChatServer(serverContext, TestUsers.store(), 0,
                new ServerOptions(Duration.ofSeconds(10), 256, LoginThrottle.defaults(), temporary.resolve("data")));
        int port = server.start();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (ChatClient client = new ChatClient(clientContext)) {
            client.connect("localhost", port, Protocol.CONNECT_TIMEOUT_MS);
            assertTrue(Boolean.TRUE.equals(client.login("an", "An@12345".toCharArray()).getSuccess()));
            BlockingQueue<ChatMessage> inbox = new LinkedBlockingQueue<>();
            client.startReceiving(inbox::add, error -> { });
            byte[] source = new byte[300_000]; new Random(42).nextBytes(source);
            Path upload = temporary.resolve("sample.png"); Files.write(upload, source);
            String fileId = client.uploadFile("lobby", upload, "image");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean announced = false;
            while (System.nanoTime() < deadline) {
                ChatMessage message = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (message != null && fileId.equals(message.getFileId())) { announced = true; break; }
            }
            assertTrue(announced, "Server must finish storing upload before download");
            MediaCache cache = new MediaCache(temporary.resolve("cache"));
            CyclicBarrier start = new CyclicBarrier(2);
            Callable<CompletableFuture<Path>> get = () -> { start.await(); return cache.get(client, fileId, "sample.png"); };
            Future<CompletableFuture<Path>> callerOne = callers.submit(get);
            Future<CompletableFuture<Path>> callerTwo = callers.submit(get);
            CompletableFuture<Path> first = callerOne.get(5, TimeUnit.SECONDS);
            CompletableFuture<Path> second = callerTwo.get(5, TimeUnit.SECONDS);
            assertSame(first, second, "Concurrent requests must share the one transfer future");
            Path firstPath = first.get(15, TimeUnit.SECONDS);
            assertEquals(firstPath, second.get(15, TimeUnit.SECONDS));
            assertArrayEquals(source, Files.readAllBytes(firstPath));
            try (var paths = Files.list(firstPath.getParent())) { assertEquals(List.of(firstPath), paths.toList()); }
            client.close();
            assertEquals(firstPath, new MediaCache(firstPath.getParent()).get(client, fileId, "sample.png").get(5, TimeUnit.SECONDS),
                    "Completed cache entries must work without another network request");
        } finally { callers.shutdownNow(); server.stop(); }
    }
}
