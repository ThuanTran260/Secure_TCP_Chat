package com.example.securechat.server;

import com.example.securechat.common.Protocol;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.testsupport.RawTlsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChatServerFileTest extends ServerFeatureTestSupport {
    @Test void uploadThenDownloadRoundTrip() throws Exception {
        RawTlsClient an = loginReady("an"), binh = loginReady("binh"), chi = loginReady("chi");
        byte[] original = bytes(100_000);
        String fileId = upload(an, "dm:an:binh", original);
        ChatMessage sender = fileMessage(an, fileId), receiver = fileMessage(binh, fileId);
        assertEquals("file", sender.getKind());
        assertEquals("an", receiver.getFrom());
        assertEquals("dm:an:binh", receiver.getTo());
        assertEquals(clock.millis(), receiver.getTimestamp());
        assertEquals((long) original.length, receiver.getSize());
        binh.send(ChatMessage.fileGet(fileId));
        assertArrayEquals(original, download(binh, fileId));
        chi.send(ChatMessage.fileGet(fileId));
        assertEquals(fileId, error(chi, "NOT_MEMBER").getFileId());
        assertNoMessage(chi, m -> "file_chunk".equals(m.getType()) || "message".equals(m.getType()));
    }

    @Test void oversizeDeclaredRejectedAndConnectionRemainsUsable() throws Exception {
        RawTlsClient an = loginReady("an");
        String id = UUID.randomUUID().toString();
        an.send(ChatMessage.fileStart(id, "lobby", "large.bin", "application/octet-stream",
                Protocol.MAX_FILE_BYTES + 1L, "file"));
        assertEquals(id, error(an, "FILE_TOO_LARGE").getFileId());
        an.send(ChatMessage.message("sau lỗi quá lớn"));
        message(an, "sau lỗi quá lớn");
    }

    @Test void wrongTotalRejectedAndCleaned() throws Exception {
        RawTlsClient an = loginReady("an");
        String id = UUID.randomUUID().toString();
        an.send(ChatMessage.fileStart(id, "lobby", "wrong.bin", "application/octet-stream", 5000, "file"));
        sendChunk(an, id, 0, bytes(2048));
        sendChunk(an, id, 1, bytes(952));
        an.send(ChatMessage.fileEnd(id));
        error(an, "FILE_INVALID");
        assertNoParts();
        an.send(ChatMessage.message("sau lỗi thiếu byte"));
        message(an, "sau lỗi thiếu byte");
    }

    @Test void disconnectMidUploadCleansUp() throws Exception {
        RawTlsClient an = loginReady("an");
        String id = UUID.randomUUID().toString();
        an.send(ChatMessage.fileStart(id, "lobby", "partial.bin", "application/octet-stream", 5000, "file"));
        sendChunk(an, id, 0, bytes(2048));
        // An echo proves the preceding upload messages were processed before disconnecting.
        an.send(ChatMessage.message("upload đang dở"));
        message(an, "upload đang dở");
        an.close();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (hasParts() && System.nanoTime() < deadline) Thread.sleep(10);
        assertNoParts();
    }

    @Test void malformedAndOutOfOrderChunksAbortAndAllowRetry() throws Exception {
        RawTlsClient an = loginReady("an");
        String id = UUID.randomUUID().toString();
        an.send(ChatMessage.fileStart(id, "lobby", "bad.bin", "application/octet-stream", 10, "file"));
        an.send(ChatMessage.fileChunk(id, 0, "%%%"));
        error(an, "FILE_INVALID"); assertNoParts();
        String second = UUID.randomUUID().toString();
        an.send(ChatMessage.fileStart(second, "lobby", "order.bin", "application/octet-stream", 10, "file"));
        sendChunk(an, second, 1, bytes(10));
        error(an, "FILE_INVALID"); assertNoParts();
        String third = UUID.randomUUID().toString();
        an.send(ChatMessage.fileStart(third, "lobby", "over.bin", "application/octet-stream", 5, "file"));
        sendChunk(an, third, 0, bytes(6));
        error(an, "FILE_INVALID"); assertNoParts();
        String valid = upload(an, "lobby", bytes(10));
        assertEquals(valid, fileMessage(an, valid).getFileId());
    }

    @Test void unauthorizedUploadAndInvalidFileIdsAreRejected() throws Exception {
        RawTlsClient chi = loginReady("chi");
        chi.send(ChatMessage.fileStart(UUID.randomUUID().toString(), "dm:an:binh", "secret.bin",
                "application/octet-stream", 10, "file"));
        error(chi, "NOT_MEMBER");
        chi.send(ChatMessage.fileStart("../../outside", "lobby", "bad.bin", "application/octet-stream", 10, "file"));
        error(chi, "FILE_INVALID");
        chi.send(ChatMessage.fileGet(UUID.randomUUID().toString()));
        error(chi, "FILE_NOT_FOUND");
        assertNoParts();
    }

    @Test void downloadDoesNotStarveChat() throws Exception {
        restartServer(16);
        RawTlsClient an = loginReady("an"), binh = connectSlow();
        login(binh, "binh"); ready(binh, "binh");
        byte[] original = bytes(2 * 1024 * 1024);
        String id = upload(an, "lobby", original);
        fileMessage(an, id); fileMessage(binh, id);
        binh.send(ChatMessage.fileGet(id));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch downloadStarted = new CountDownLatch(1);
        try {
            Future<DownloadWithChat> receiving = executor.submit(() -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Set<String> chats = new HashSet<>();
                int index = 0;
                boolean ended = false;
                boolean chatBeforeEnd = false;
                while (!ended || chats.size() < 20) {
                    ChatMessage next = binh.read(Duration.ofSeconds(20));
                    assertNotNull(next, "Download disconnected a slow chat client");
                    if ("file_chunk".equals(next.getType()) && id.equals(next.getFileId())) {
                        assertEquals(index++, next.getIndex());
                        out.writeBytes(Base64.getDecoder().decode(next.getData()));
                        downloadStarted.countDown();
                        Thread.sleep(1);
                    } else if ("file_end".equals(next.getType()) && id.equals(next.getFileId())) {
                        ended = true;
                    } else if ("message".equals(next.getType()) && next.getContent().startsWith("during-")) {
                        chats.add(next.getContent());
                        if (!ended) chatBeforeEnd = true;
                    }
                }
                return new DownloadWithChat(out.toByteArray(), chats.size(), chatBeforeEnd);
            });
            assertTrue(downloadStarted.await(8, TimeUnit.SECONDS), "Download must start before concurrent chat is sent");
            for (int i = 0; i < 20; i++) {
                an.send(ChatMessage.message("during-" + i));
                message(an, "during-" + i);
                Thread.sleep(15);
            }
            DownloadWithChat result = receiving.get(40, TimeUnit.SECONDS);
            assertArrayEquals(original, result.bytes());
            assertEquals(20, result.chatCount());
            assertTrue(result.chatBeforeEnd(), "Chat must arrive while the file download is in progress");
            binh.send(ChatMessage.message("vẫn online"));
            message(binh, "vẫn online");
        } finally {
            executor.shutdownNow();
        }
    }

    private record DownloadWithChat(byte[] bytes, int chatCount, boolean chatBeforeEnd) { }

    private String upload(RawTlsClient client, String conversation, byte[] data) throws Exception {
        String id = UUID.randomUUID().toString();
        client.send(ChatMessage.fileStart(id, conversation, "data.bin", "application/octet-stream", data.length, "file"));
        for (int offset = 0, index = 0; offset < data.length; offset += Protocol.FILE_CHUNK_BYTES, index++)
            sendChunk(client, id, index, Arrays.copyOfRange(data, offset, Math.min(data.length, offset + Protocol.FILE_CHUNK_BYTES)));
        client.send(ChatMessage.fileEnd(id));
        return id;
    }

    private void sendChunk(RawTlsClient client, String id, int index, byte[] data) throws Exception {
        client.send(ChatMessage.fileChunk(id, index, Base64.getEncoder().encodeToString(data)));
    }

    private ChatMessage fileMessage(RawTlsClient client, String id) throws Exception {
        return await(client, m -> "message".equals(m.getType()) && id.equals(m.getFileId()));
    }

    private byte[] download(RawTlsClient client, String id) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int index = 0;
        while (true) {
            ChatMessage m = await(client, next -> id.equals(next.getFileId()));
            if ("file_end".equals(m.getType())) return out.toByteArray();
            assertEquals("file_chunk", m.getType());
            assertEquals(index++, m.getIndex());
            byte[] chunk = Base64.getDecoder().decode(m.getData());
            assertTrue(chunk.length <= Protocol.FILE_CHUNK_BYTES);
            out.writeBytes(chunk);
        }
    }

    private byte[] bytes(int length) {
        byte[] data = new byte[length];
        new Random(84).nextBytes(data);
        return data;
    }

    private boolean hasParts() throws Exception {
        if (!Files.exists(dataDir.resolve("files"))) return false;
        try (var paths = Files.list(dataDir.resolve("files"))) {
            return paths.anyMatch(p -> p.getFileName().toString().endsWith(".part"));
        }
    }

    private void assertNoParts() throws Exception {
        assertFalse(hasParts(), "Partial uploads must be removed");
    }
}
