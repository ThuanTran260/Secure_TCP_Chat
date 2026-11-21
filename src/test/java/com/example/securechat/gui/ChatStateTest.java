package com.example.securechat.gui;

import com.example.securechat.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatStateTest {
    @Test void userListBuildsLobbyGroupsAndDms() {
        ChatState state = new ChatState("an");
        state.apply(ChatMessage.userList(List.of("an", "binh", "chi"), List.of("an")));
        assertEquals(List.of("lobby", "dm:an:binh", "dm:an:chi"),
                state.conversations().stream().map(ChatState.Conversation::id).toList());
    }
    @Test void presenceUpdatesOnline() {
        ChatState state = new ChatState("an");
        state.apply(ChatMessage.presence("binh", true));
        assertTrue(state.isOnline("binh"));
        state.apply(ChatMessage.presence("binh", false));
        assertFalse(state.isOnline("binh"));
    }
    @Test void messageIncrementsUnreadUnlessOpen() {
        ChatState state = new ChatState("an");
        state.apply(ChatMessage.chatIn("dm:an:binh", 2, "binh", "hello", 1));
        assertEquals(1, state.unread("dm:an:binh"));
        state.open("dm:an:binh");
        state.apply(ChatMessage.chatIn("dm:an:binh", 2, "binh", "hi again", 2));
        assertEquals(0, state.unread("dm:an:binh"));
    }
    @Test void historyDeduplicated() {
        ChatState state = new ChatState("an");
        ChatMessage live = ChatMessage.chatIn("lobby", 2, "binh", "hello", 1);
        state.apply(live);
        ChatMessage history = live.copy();
        history.setHistory(true);
        state.apply(history);
        assertEquals(1, state.messages("lobby").size());
        assertEquals(0, state.unread("lobby"));
    }
    @Test void historyDoesNotIncreaseUnread() {
        ChatState state = new ChatState("an");
        ChatMessage history = ChatMessage.chatIn("dm:an:binh", 2, "binh", "old", 1);
        history.setHistory(true);
        state.apply(history);
        assertEquals(0, state.unread("dm:an:binh"));
    }
    @Test void mineFlagByFrom() {
        ChatState state = new ChatState("an");
        state.apply(ChatMessage.chatIn("lobby", 1, "an", "hi", 1));
        assertTrue(state.messages("lobby").get(0).mine());
    }
    @Test void messagesSortedByTimestamp() {
        ChatState state = new ChatState("an");
        state.apply(ChatMessage.chatIn("lobby", 1, "an", "later", 20));
        state.apply(ChatMessage.chatIn("lobby", 2, "binh", "earlier", 10));
        assertEquals(List.of("earlier", "later"), state.messages("lobby").stream()
                .map(e -> e.message().getContent()).toList());
    }
    @Test void groupInfoAddsGroup() {
        ChatState state = new ChatState("an");
        state.apply(ChatMessage.groupInfo("group:00000000-0000-0000-0000-000000000001", "Nhóm A", List.of("an", "binh")));
        assertEquals("Nhóm A", state.conversations().get(1).title());
        assertTrue(state.conversations().get(1).group());
    }
}
