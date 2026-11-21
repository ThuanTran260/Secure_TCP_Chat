package com.example.securechat.server;

import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.ConversationIds;
import com.example.securechat.testsupport.RawTlsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ChatServerConversationTest extends ServerFeatureTestSupport {
    @Test void dmReachesOnlyTheTwoMembers() throws Exception {
        RawTlsClient an = loginReady("an"), binh = loginReady("binh"), chi = loginReady("chi");
        an.send(ChatMessage.messageTo("dm:an:binh", "riêng tư"));
        for (RawTlsClient client : List.of(an, binh)) {
            ChatMessage reply = message(client, "riêng tư");
            assertEquals("dm:an:binh", reply.getTo());
            assertEquals(clock.millis(), reply.getTimestamp());
            assertEquals("an", reply.getFrom());
            assertNotNull(reply.getClientId());
        }
        assertNoMessage(chi, m -> "message".equals(m.getType()));
    }

    @Test void outsiderGetsNotMemberForSendAndHistory() throws Exception {
        RawTlsClient chi = loginReady("chi");
        chi.send(ChatMessage.messageTo("dm:an:binh", "leak"));
        assertEquals("dm:an:binh", error(chi, "NOT_MEMBER").getTo());
        chi.send(ChatMessage.historyRequest("dm:an:binh"));
        error(chi, "NOT_MEMBER");
        assertNoMessage(chi, m -> "message".equals(m.getType()) || "history_end".equals(m.getType()));
    }

    @Test void groupCreateAndChat() throws Exception {
        RawTlsClient an = loginReady("an"), binh = loginReady("binh"), chi = loginReady("chi");
        an.send(ChatMessage.groupCreate("Nhóm A", List.of("binh")));
        ChatMessage group = await(an, m -> "group_info".equals(m.getType()));
        assertTrue(ConversationIds.isGroup(group.getTo()));
        assertEquals(List.of("an", "binh"), group.getMembers());
        assertEquals("Nhóm A", group.getGroupName());
        assertEquals(group.getTo(), await(binh, m -> "group_info".equals(m.getType())).getTo());
        chi.send(ChatMessage.messageTo(group.getTo(), "không được gửi"));
        error(chi, "NOT_MEMBER");
        chi.send(ChatMessage.historyRequest(group.getTo()));
        error(chi, "NOT_MEMBER");
        an.send(ChatMessage.messageTo(group.getTo(), "chào nhóm"));
        assertEquals(group.getTo(), message(an, "chào nhóm").getTo());
        assertEquals(group.getTo(), message(binh, "chào nhóm").getTo());
        assertNoMessage(chi, m -> "message".equals(m.getType()) || "group_info".equals(m.getType()));
    }

    @Test void offlineDmAppearsInHistory() throws Exception {
        RawTlsClient an = loginReady("an");
        an.send(ChatMessage.messageTo("dm:an:binh", "tin offline"));
        message(an, "tin offline");
        RawTlsClient binh = loginReady("binh");
        binh.send(ChatMessage.historyRequest("dm:an:binh"));
        ChatMessage old = message(binh, "tin offline");
        assertEquals(Boolean.TRUE, old.getHistory());
        assertEquals(clock.millis(), old.getTimestamp());
        assertEquals("dm:an:binh", await(binh, m -> "history_end".equals(m.getType())).getTo());
    }

    @Test void loginSendsUserListAndPresence() throws Exception {
        RawTlsClient an = loginReady("an"), binh = connect();
        login(binh, "binh");
        ChatMessage users = await(binh, m -> "user_list".equals(m.getType()));
        assertEquals(List.of("an", "binh", "chi"), users.getMembers());
        assertTrue(users.getOnlineUsers().containsAll(List.of("an", "binh")));
        ChatMessage online = await(an, m -> "presence".equals(m.getType()) && "binh".equals(m.getFrom()));
        assertTrue(online.getOnline());
        binh.close();
        ChatMessage offline = await(an, m -> "presence".equals(m.getType()) && "binh".equals(m.getFrom())
                && Boolean.FALSE.equals(m.getOnline()));
        assertFalse(offline.getOnline());
    }

    @Test void lobbyStillWorksWithoutTo() throws Exception {
        RawTlsClient an = loginReady("an"), binh = loginReady("binh"), chi = loginReady("chi");
        an.send(ChatMessage.message("chào phòng chung"));
        for (RawTlsClient client : List.of(an, binh, chi))
            assertEquals("lobby", message(client, "chào phòng chung").getTo());
    }

    @Test void invalidDestinationsAndGroupsAreRejectedWithoutDisconnect() throws Exception {
        RawTlsClient an = loginReady("an");
        an.send(ChatMessage.messageTo("../../evil", "no"));
        error(an, "UNKNOWN_CONVERSATION");
        an.send(ChatMessage.groupCreate(" ", List.of("binh")));
        error(an, "INVALID_GROUP");
        an.send(ChatMessage.groupCreate("Nhóm", List.of("unknown")));
        error(an, "INVALID_GROUP");
        an.send(ChatMessage.message("vẫn dùng được"));
        message(an, "vẫn dùng được");
    }

    @Test void groupAndHistorySurviveServerRestart() throws Exception {
        RawTlsClient an = loginReady("an");
        an.send(ChatMessage.groupCreate("Nhóm bền", List.of("binh")));
        String groupId = await(an, m -> "group_info".equals(m.getType())).getTo();
        an.send(ChatMessage.messageTo(groupId, "trước khởi động lại"));
        message(an, "trước khởi động lại");
        an.close();
        restartServer(256);
        RawTlsClient binh = connect();
        login(binh, "binh");
        assertEquals(groupId, await(binh, m -> "group_info".equals(m.getType())).getTo());
        ready(binh, "binh");
        binh.send(ChatMessage.historyRequest(groupId));
        assertTrue(message(binh, "trước khởi động lại").getHistory());
        await(binh, m -> "history_end".equals(m.getType()));
    }
}
