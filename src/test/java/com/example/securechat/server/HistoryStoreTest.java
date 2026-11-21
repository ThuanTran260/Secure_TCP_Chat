package com.example.securechat.server;

import com.example.securechat.model.ChatMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoryStoreTest {
    @TempDir Path dir;

    @Test void historyKeepsOrderAndLimit() throws Exception {
        HistoryStore store = new HistoryStore(dir);
        for (int i = 1; i <= 60; i++) store.append("dm:an:binh", ChatMessage.chatIn("dm:an:binh", 1, "an", "" + i, i));
        var recent = store.recent("dm:an:binh", 50);
        assertEquals(50, recent.size());
        assertEquals("11", recent.get(0).getContent());
        assertEquals("60", recent.get(49).getContent());
    }

    @Test void historySurvivesReloadAndSkipsCorruptLines() throws Exception {
        new HistoryStore(dir).append("lobby", ChatMessage.chatIn("lobby", 1, "an", "Chào", 12));
        Files.writeString(dir.resolve("lobby.jsonl"), "garbage\n", StandardOpenOption.APPEND);
        assertEquals("Chào", new HistoryStore(dir).recent("lobby", 50).get(0).getContent());
    }

    @Test void pathTraversalIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new HistoryStore(dir).recent("../../secret", 50));
    }

    @Test void underscoreConversationIdsCannotLeakEachOthersHistory() throws Exception {
        HistoryStore store = new HistoryStore(dir);
        store.append("dm:a_b:c", ChatMessage.chatIn("dm:a_b:c", 1, "a_b", "private one", 1));
        store.append("dm:a:b_c", ChatMessage.chatIn("dm:a:b_c", 2, "a", "private two", 2));
        assertEquals(List.of("private one"), store.recent("dm:a_b:c", 50).stream().map(ChatMessage::getContent).toList());
        assertEquals(List.of("private two"), store.recent("dm:a:b_c", 50).stream().map(ChatMessage::getContent).toList());
    }

    @Test void storesDestinationAndRejectsCrossConversationRecords() throws Exception {
        HistoryStore store = new HistoryStore(dir);
        store.append("lobby", ChatMessage.message("hello"));
        assertEquals("lobby", store.recent("lobby", 1).get(0).getTo());
        assertThrows(IllegalArgumentException.class, () -> store.append("dm:an:binh", ChatMessage.messageTo("lobby", "wrong")));
    }
}
