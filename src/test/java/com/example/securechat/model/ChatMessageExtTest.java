package com.example.securechat.model;

import com.example.securechat.common.Protocol;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChatMessageExtTest {
    @Test void fullFileChunkFitsOneLine() {
        String b64 = Base64.getEncoder().encodeToString(new byte[Protocol.FILE_CHUNK_BYTES]);
        String line = JsonCodec.encode(ChatMessage.fileChunk(UUID.randomUUID().toString(), Integer.MAX_VALUE, b64));
        assertTrue(Protocol.fitsLine(line));
    }

    @Test void newFieldsRoundTripAndNullsOmitted() throws Exception {
        ChatMessage m = ChatMessage.chatIn("dm:an:binh", 3, "an", "hi", 1700000000000L);
        ChatMessage back = JsonCodec.decode(JsonCodec.encode(m));
        assertEquals("dm:an:binh", back.getTo());
        assertEquals(1700000000000L, back.getTimestamp());
        assertFalse(JsonCodec.encode(ChatMessage.message("x")).contains("timestamp"));
        assertEquals(MessageType.FILE_GET, MessageType.fromValue("file_get").orElseThrow());
    }

    @Test void factoriesCarryTheirProtocolFields() throws Exception {
        assertEquals("lobby", ChatMessage.messageTo("lobby", "hello").getTo());
        assertTrue(ChatMessage.presence("an", true).getOnline());
        assertEquals(List.of("an"), ChatMessage.userList(List.of("an"), List.of("an")).getOnlineUsers());
        assertEquals("Nhóm A", ChatMessage.groupCreate("Nhóm A", List.of("an")).getGroupName());
        assertEquals("group:id", ChatMessage.groupInfo("group:id", "Nhóm A", List.of("an")).getTo());
        assertEquals("lobby", ChatMessage.historyRequest("lobby").getTo());
        assertEquals("history_end", ChatMessage.historyEnd("lobby").getType());
        ChatMessage file = ChatMessage.fileStart("id", "lobby", "ảnh.png", "image/png", 12, "image");
        ChatMessage back = JsonCodec.decode(JsonCodec.encode(file));
        assertEquals("id", back.getFileId()); assertEquals("ảnh.png", back.getFileName());
        assertEquals("image/png", back.getMime()); assertEquals(12, back.getSize()); assertEquals("image", back.getKind());
        assertEquals("file_end", ChatMessage.fileEnd("id").getType());
        assertEquals("file_get", ChatMessage.fileGet("id").getType());
        assertEquals("id", ChatMessage.error("FILE_INVALID", "id", "lobby").getFileId());
    }

    @Test void copyPreservesEveryFieldWithoutSharingTheMessageObject() {
        ChatMessage m = ChatMessage.login("an", "hidden".toCharArray());
        m.setType("message"); m.setContent("hello"); m.setFrom("an"); m.setClientId(3); m.setSuccess(true);
        m.setTo("lobby"); m.setTimestamp(10L); m.setKind("file"); m.setFileId("id");
        m.setFileName("a.txt"); m.setMime("text/plain"); m.setSize(12L); m.setIndex(2); m.setData("secret-data");
        m.setOnline(true); m.setMembers(List.of("an")); m.setOnlineUsers(List.of("an")); m.setGroupName("group"); m.setHistory(true);
        ChatMessage copy = m.copy();
        assertNotSame(m, copy); assertEquals(JsonCodec.encode(m), JsonCodec.encode(copy));
        copy.setContent("changed"); assertEquals("hello", m.getContent());
        assertFalse(m.toString().contains("hidden")); assertFalse(m.toString().contains("secret-data"));
        assertTrue(m.toString().contains("data=<11 chars>"));
    }
}
