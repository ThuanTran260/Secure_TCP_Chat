package com.example.securechat.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JsonCodecTest {

    @Test void encodeOmitsNullFields() {
        assertEquals("{\"type\":\"message\",\"content\":\"Xin chào!\"}", JsonCodec.encode(ChatMessage.message("Xin chào!")));
    }

    @Test void roundTripLoginResult() throws Exception {
        ChatMessage m = JsonCodec.decode(JsonCodec.encode(ChatMessage.loginResult(true, 1, null)));
        assertEquals("login_result", m.getType()); assertTrue(m.getSuccess()); assertEquals(1, m.getClientId());
    }

    @Test void userLeftCarriesClientIdAndName() throws Exception {
        ChatMessage m = JsonCodec.decode(JsonCodec.encode(ChatMessage.userLeft(3, "chi")));
        assertEquals("user_left", m.getType()); assertEquals(3, m.getClientId());
        assertEquals("chi", m.getFrom()); assertEquals("chi đã rời phòng", m.getContent());
    }

    @Test void decodeRejectsBrokenJson() {
        assertThrows(InvalidMessageException.class, () -> JsonCodec.decode("{\"type\":"));
    }

    @Test void decodeRejectsMissingType() {
        assertThrows(InvalidMessageException.class, () -> JsonCodec.decode("{\"content\":\"x\"}"));
    }

    @Test void decodeKeepsUnknownTypeString() throws Exception {
        ChatMessage m = JsonCodec.decode("{\"type\":\"ping\"}");
        assertEquals("ping", m.getType()); assertTrue(m.messageType().isEmpty());
    }

    @Test void toStringMasksPassword() {
        String s = ChatMessage.login("an", "Secret#1".toCharArray()).toString();
        assertFalse(s.contains("Secret#1")); assertTrue(s.contains("***"));
    }
}
