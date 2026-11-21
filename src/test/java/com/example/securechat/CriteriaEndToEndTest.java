package com.example.securechat;

import static org.junit.jupiter.api.Assertions.*;

import com.example.securechat.model.ChatMessage;
import com.example.securechat.testsupport.ChatPeers;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Verifies all eleven criteria at the real client/TLS/server boundary. */
@Timeout(45)
class CriteriaEndToEndTest {
    @TempDir Path dir;
    ChatPeers env;
    ChatPeers.Peer an;
    ChatPeers.Peer binh;
    ChatPeers.Peer chi;

    @BeforeEach void start() throws Exception {
        env = new ChatPeers(dir.resolve("data"));
        an = env.login("an");
        binh = env.login("binh");
        chi = env.login("chi");
    }

    @AfterEach void stop() { if (env != null) env.close(); }

    @Test @DisplayName("p2p") void directConversationIsPrivate() throws Exception {
        an.client.sendTo("dm:an:binh", "Chỉ hai người");
        assertEquals("Chỉ hai người", binh.awaitType("message").getContent());
        assertEquals("dm:an:binh", an.awaitType("message").getTo());
        chi.assertNoMessage(m -> "message".equals(m.getType()));
    }

    @Test @DisplayName("group") void groupIncludesCreatorAndSelectedMembers() throws Exception {
        an.client.createGroup("Nhóm học Java", List.of("binh"));
        ChatMessage group = an.awaitType("group_info");
        assertEquals(List.of("an", "binh"), group.getMembers());
        assertEquals(group.getTo(), binh.awaitType("group_info").getTo());
        an.client.sendTo(group.getTo(), "Họp nhóm");
        assertEquals("Họp nhóm", binh.awaitType("message").getContent());
        chi.assertNoMessage(m -> "group_info".equals(m.getType()) || "message".equals(m.getType()));
    }

    @Test @DisplayName("file") void arbitraryFileRoundTrips() throws Exception {
        byte[] data = new byte[12_345];
        new Random(7).nextBytes(data);
        transfer("report.dat", data, "file");
    }

    @Test @DisplayName("emoji") void unicodeEmojiSurvivesTransport() throws Exception {
        String text = "Chào 😀👍❤️";
        an.client.sendTo("dm:an:binh", text);
        String received = binh.awaitType("message").getContent();
        assertEquals(text, received);
        assertArrayEquals(text.codePoints().toArray(), received.codePoints().toArray());
    }

    @Test @DisplayName("history") void offlineRecipientCanReadPersistedHistory() throws Exception {
        binh.client.close();
        an.await(m -> "presence".equals(m.getType()) && "binh".equals(m.getFrom())
                && Boolean.FALSE.equals(m.getOnline()));
        an.client.sendTo("dm:an:binh", "Tin khi bạn offline");
        an.awaitType("message");
        binh = env.login("binh");
        binh.client.requestHistory("dm:an:binh");
        ChatMessage history = binh.awaitType("message");
        assertTrue(Boolean.TRUE.equals(history.getHistory()));
        assertEquals("Tin khi bạn offline", history.getContent());
        assertEquals("dm:an:binh", binh.awaitType("history_end").getTo());
    }

    @Test @DisplayName("image") void pngImageRoundTrips() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x0068FF);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        transfer("image.png", bytes.toByteArray(), "image");
    }

    @Test @DisplayName("video") void videoAttachmentRoundTrips() throws Exception {
        // Transport sample only; codec playback is covered by the manual acceptance checklist.
        byte[] bytes = new byte[4096];
        byte[] header = "\u0000\u0000\u0000\u0018ftypmp42".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(header, 0, bytes, 0, header.length);
        transfer("video.mp4", bytes, "video");
    }

    @Test @DisplayName("sound") void oneSecondWavRoundTrips() throws Exception {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        byte[] pcm = new byte[32000];
        for (int sample = 0; sample < 16000; sample++) {
            short value = (short) (Math.sin(2 * Math.PI * 440 * sample / 16000) * 4000);
            pcm[sample * 2] = (byte) value;
            pcm[sample * 2 + 1] = (byte) (value >>> 8);
        }
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm), format, 16000)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, wav);
        }
        transfer("voice.wav", wav.toByteArray(), "audio");
    }

    @Test @DisplayName("state") void presenceReflectsDisconnectAndReconnect() throws Exception {
        chi.client.close();
        an.await(m -> "presence".equals(m.getType()) && "chi".equals(m.getFrom())
                && Boolean.FALSE.equals(m.getOnline()));
        chi = env.login("chi");
        ChatMessage online = an.await(m -> "presence".equals(m.getType()) && "chi".equals(m.getFrom())
                && Boolean.TRUE.equals(m.getOnline()));
        assertTrue(online.getOnline());
    }

    @Test @DisplayName("time") void serverTimestampMatchesSendWindow() throws Exception {
        long before = System.currentTimeMillis();
        an.client.sendTo("dm:an:binh", "Đúng giờ");
        ChatMessage message = binh.awaitType("message");
        long after = System.currentTimeMillis();
        assertNotNull(message.getTimestamp());
        assertTrue(message.getTimestamp() >= before && message.getTimestamp() <= after);
    }

    @Test @DisplayName("sender") void serverSetsAuthenticatedSender() throws Exception {
        binh.client.sendTo("dm:an:binh", "Bình gửi");
        ChatMessage message = an.awaitType("message");
        assertEquals("binh", message.getFrom());
        assertNotNull(message.getClientId());
    }

    private void transfer(String name, byte[] bytes, String kind) throws Exception {
        Path source = Files.write(dir.resolve(name), bytes);
        String id = an.client.uploadFile("dm:an:binh", source, kind);
        ChatMessage message = binh.await(m -> "message".equals(m.getType()) && id.equals(m.getFileId()));
        assertEquals(kind, message.getKind());
        assertEquals(bytes.length, message.getSize());
        assertEquals("an", message.getFrom());
        assertEquals("dm:an:binh", message.getTo());
        Path saved = binh.client.download(id, dir.resolve("received-" + name)).get(8, TimeUnit.SECONDS);
        assertArrayEquals(bytes, Files.readAllBytes(saved));
        chi.assertNoMessage(m -> id.equals(m.getFileId()));
    }
}
