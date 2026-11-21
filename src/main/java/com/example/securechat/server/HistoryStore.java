package com.example.securechat.server;

import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.ConversationIds;
import com.example.securechat.model.InvalidMessageException;
import com.example.securechat.model.JsonCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Base64;

/** Append-only conversation history. Only the requested tail is retained in memory. */
public final class HistoryStore {
    private final Path dir;

    public HistoryStore(Path dir) throws IOException {
        this.dir = dir.toAbsolutePath().normalize();
        Files.createDirectories(this.dir);
    }

    public synchronized void append(String conversationId, ChatMessage message) throws IOException {
        Path file = path(conversationId);
        if (message.getTo() != null && !conversationId.equals(message.getTo())) {
            throw new IllegalArgumentException("Message belongs to another conversation");
        }
        ChatMessage stored = message.copy();
        stored.setTo(conversationId);
        Files.writeString(file, JsonCodec.encode(stored) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized List<ChatMessage> recent(String conversationId, int limit) throws IOException {
        Path file = path(conversationId);
        if (limit <= 0 || !Files.exists(file)) return List.of();
        ArrayDeque<ChatMessage> tail = new ArrayDeque<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    ChatMessage m = JsonCodec.decode(line);
                    if (!"message".equals(m.getType())) continue;
                    if (!conversationId.equals(m.getTo())) continue;
                    if (tail.size() == limit) tail.removeFirst();
                    tail.addLast(m);
                } catch (InvalidMessageException ignored) {
                    // A damaged or incomplete record must not hide the rest of the history.
                }
            }
        }
        return List.copyOf(tail);
    }

    private Path path(String id) {
        if (!(ConversationIds.isLobby(id) || ConversationIds.isDm(id) || ConversationIds.isGroup(id))) {
            throw new IllegalArgumentException("Invalid conversation id");
        }
        // Preserve readable legacy names only when replacing colons is injective; usernames can
        // contain underscores and Windows-reserved characters, so encode every other identifier.
        String name = id.matches("[a-z0-9:-]+") ? id.replace(':', '_')
                : "encoded_" + Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
        Path result = dir.resolve(name + ".jsonl").normalize();
        if (!result.getParent().equals(dir)) throw new IllegalArgumentException("Invalid conversation path");
        return result;
    }
}
