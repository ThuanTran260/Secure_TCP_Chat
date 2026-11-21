package com.example.securechat.client;

import static org.junit.jupiter.api.Assertions.*;

import com.example.securechat.common.Protocol;
import com.example.securechat.testsupport.ChatPeers;
import com.example.securechat.testsupport.ControlledTlsServer;
import com.example.securechat.testsupport.TestTls;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(45)
class ChatClientExtTest {
    @TempDir Path dir;

    @Test void sendToDmDelivered() throws Exception {
        try (ChatPeers env = new ChatPeers(dir.resolve("data"))) {
            var an = env.login("an");
            var binh = env.login("binh");
            an.client.sendTo("dm:an:binh", "Riêng tư");
            var message = binh.awaitType("message");
            assertEquals("Riêng tư", message.getContent());
            assertEquals("dm:an:binh", message.getTo());
        }
    }

    @Test void uploadAndDownloadMatchesBytes() throws Exception {
        try (ChatPeers env = new ChatPeers(dir.resolve("data"))) {
            var an = env.login("an");
            var binh = env.login("binh");
            byte[] bytes = new byte[300_000];
            new Random(42).nextBytes(bytes);
            Path input = Files.write(dir.resolve("input.bin"), bytes);
            String id = an.client.uploadFile("dm:an:binh", input, "file");
            assertEquals(id, binh.awaitType("message").getFileId());
            Path output = dir.resolve("nested/output.bin");
            assertEquals(output, binh.client.download(id, output).get(10, TimeUnit.SECONDS));
            assertArrayEquals(bytes, Files.readAllBytes(output));
            binh.assertNoMessage(m -> "file_chunk".equals(m.getType()) || "file_end".equals(m.getType()));
        }
    }

    @Test void downloadUnknownFileFails() throws Exception {
        try (ChatPeers env = new ChatPeers(dir.resolve("data"))) {
            var peer = env.login("an");
            Path target = dir.resolve("missing.bin");
            var download = peer.client.download(UUID.randomUUID().toString(), target);
            var error = assertThrows(ExecutionException.class, () -> download.get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("FILE_NOT_FOUND"));
            assertFalse(Files.exists(target));
        }
    }

    @Test void uploadTooLargeRejectedLocally() throws Exception {
        Path large = dir.resolve("large.bin");
        try (RandomAccessFile file = new RandomAccessFile(large.toFile(), "rw")) {
            file.setLength(Protocol.MAX_FILE_BYTES + 1L);
        }
        try (ChatPeers env = new ChatPeers(dir.resolve("data"))) {
            var peer = env.login("an");
            var error = assertThrows(IllegalArgumentException.class,
                    () -> peer.client.uploadFile("lobby", large, "file"));
            assertEquals("File vượt quá 25 MB", error.getMessage());
            peer.client.send("Vẫn chat được");
            assertEquals("Vẫn chat được", peer.awaitType("message").getContent());
        }
    }

    @Test void pendingDownloadFailsOnClose() throws Exception {
        try (ControlledTlsServer server = new ControlledTlsServer(); ChatClient client = new ChatClient(TestTls.client())) {
            client.connect("localhost", server.port(), Protocol.CONNECT_TIMEOUT_MS);
            assertTrue(client.login("an", "unused".toCharArray()).getSuccess());
            var closed = new CountDownLatch(1);
            client.startReceiving(m -> fail("Unexpected download frame delivered to application: " + m), e -> closed.countDown());
            String firstId = UUID.randomUUID().toString(), secondId = UUID.randomUUID().toString();
            Path firstTarget = dir.resolve("pending-first.bin"), secondTarget = dir.resolve("pending-second.bin");
            var first = client.download(firstId, firstTarget);
            var second = client.download(secondId, secondTarget);
            assertEquals(firstId, server.awaitRequest("file_get").getFileId());
            assertEquals(secondId, server.awaitRequest("file_get").getFileId());
            assertFalse(first.isDone(), "Server holds the first response, so its future must still be pending");
            assertFalse(second.isDone(), "Server holds the second response, so its future must still be pending");
            assertTrue(Files.exists(firstTarget)); assertTrue(Files.exists(secondTarget));
            client.close();
            for (var future : java.util.List.of(first, second)) {
                var failure = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof java.io.IOException);
                assertTrue(failure.getCause().getMessage().contains("closed"));
            }
            assertFalse(Files.exists(firstTarget)); assertFalse(Files.exists(secondTarget));
            assertTrue(closed.await(3, TimeUnit.SECONDS), "Receive thread must observe the close");
        }
    }

    @Test void downloadNeverOverwritesAnExistingTarget() throws Exception {
        try (ChatPeers env = new ChatPeers(dir.resolve("data"))) {
            var peer = env.login("an");
            byte[] original = "existing document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path target = Files.write(dir.resolve("existing.txt"), original);
            var download = peer.client.download(UUID.randomUUID().toString(), target);
            assertThrows(ExecutionException.class, () -> download.get(5, TimeUnit.SECONDS));
            assertArrayEquals(original, Files.readAllBytes(target));
            peer.client.sendTo("lobby", "sau khi từ chối ghi đè");
            assertEquals("sau khi từ chối ghi đè", peer.awaitType("message").getContent());
        }
    }

    @Test void unauthorizedDownloadFailsAndDeletesPartialTarget() throws Exception {
        try (ChatPeers env = new ChatPeers(dir.resolve("data"))) {
            var an = env.login("an");
            var chi = env.login("chi");
            Path source = Files.write(dir.resolve("private.txt"), new byte[] {1, 2, 3});
            String id = an.client.uploadFile("dm:an:binh", source, "file");
            an.await(m -> id.equals(m.getFileId()) && "message".equals(m.getType()));
            Path target = dir.resolve("denied.bin");
            var error = assertThrows(ExecutionException.class,
                    () -> chi.client.download(id, target).get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("NOT_MEMBER"));
            assertFalse(Files.exists(target));
            chi.assertNoMessage(m -> "file_chunk".equals(m.getType()) || "file_end".equals(m.getType()));
        }
    }

    @Test void simultaneousUploadsFromOneClientRemainSeparate() throws Exception {
        try (ChatPeers env = new ChatPeers(dir.resolve("data"))) {
            var an = env.login("an");
            var binh = env.login("binh");
            byte[] bytes = new byte[100_000];
            new Random(41).nextBytes(bytes);
            Path first = Files.write(dir.resolve("first.bin"), bytes);
            Path second = Files.write(dir.resolve("second.bin"), bytes);
            var executor = Executors.newFixedThreadPool(2);
            var go = new CountDownLatch(1);
            try {
                var upload1 = executor.submit(() -> { go.await(); return an.client.uploadFile("dm:an:binh", first, "file"); });
                var upload2 = executor.submit(() -> { go.await(); return an.client.uploadFile("dm:an:binh", second, "file"); });
                go.countDown();
                String id1 = upload1.get(10, TimeUnit.SECONDS), id2 = upload2.get(10, TimeUnit.SECONDS);
                assertNotEquals(id1, id2);
                var seen = new java.util.HashSet<String>();
                seen.add(binh.awaitType("message").getFileId());
                seen.add(binh.awaitType("message").getFileId());
                assertEquals(java.util.Set.of(id1, id2), seen);
                Path output1 = dir.resolve("first-copy.bin"), output2 = dir.resolve("second-copy.bin");
                var download1 = binh.client.download(id1, output1);
                var download2 = binh.client.download(id2, output2);
                download1.get(10, TimeUnit.SECONDS); download2.get(10, TimeUnit.SECONDS);
                assertArrayEquals(bytes, Files.readAllBytes(output1));
                assertArrayEquals(bytes, Files.readAllBytes(output2));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test void sendToRejectsAnOversizedLineLocally() throws Exception {
        try (ChatPeers env = new ChatPeers(dir.resolve("data"))) {
            var an = env.login("an");
            var error = assertThrows(IllegalArgumentException.class,
                    () -> an.client.sendTo("dm:an:binh", "x".repeat(Protocol.MAX_LINE_BYTES)));
            assertEquals("Tin nhắn quá dài", error.getMessage());
            an.client.sendTo("lobby", "sau tin quá dài");
            assertEquals("sau tin quá dài", an.awaitType("message").getContent());
        }
    }
}
