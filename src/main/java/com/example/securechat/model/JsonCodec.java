package com.example.securechat.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Encodes and decodes {@link ChatMessage} as single-line JSON. */
public final class JsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private JsonCodec() {
    }

    public static String encode(ChatMessage m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            // A plain POJO of strings/numbers cannot fail to serialize.
            throw new IllegalStateException("Cannot encode message", e);
        }
    }

    public static ChatMessage decode(String line) throws InvalidMessageException {
        ChatMessage m;
        try {
            m = MAPPER.readValue(line, ChatMessage.class);
        } catch (JsonProcessingException e) {
            throw new InvalidMessageException("Malformed JSON", e);
        }
        if (m == null || m.getType() == null || m.getType().isEmpty()) {
            throw new InvalidMessageException("Missing message type");
        }
        return m;
    }
}
