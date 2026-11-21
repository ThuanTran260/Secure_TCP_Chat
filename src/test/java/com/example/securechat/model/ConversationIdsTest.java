package com.example.securechat.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConversationIdsTest {
    @Test void dmIdIsSortedAndNormalized() {
        assertEquals("dm:an:binh", ConversationIds.dm("Binh ", "an"));
        assertEquals(List.of("an", "binh"), ConversationIds.dmMembers("dm:an:binh"));
    }

    @Test void rejectsSelfDmAndBadIds() {
        assertThrows(IllegalArgumentException.class, () -> ConversationIds.dm("an", "AN"));
        assertThrows(IllegalArgumentException.class, () -> ConversationIds.dm(null, "an"));
        assertThrows(IllegalArgumentException.class, () -> ConversationIds.dm("an:chi", "binh"));
        assertFalse(ConversationIds.isDm("dm:binh:an"));
        assertFalse(ConversationIds.isDm("dm:An:binh"));
        assertFalse(ConversationIds.isDm("dm:an:an"));
        assertFalse(ConversationIds.isDm("dm:an:binh:chi"));
        assertFalse(ConversationIds.isDm("dm:../an:binh"));
        assertThrows(IllegalArgumentException.class, () -> ConversationIds.dmMembers("dm:binh:an"));
    }

    @Test void onlyCanonicalUuidsAreAccepted() {
        String id = UUID.randomUUID().toString();
        assertTrue(ConversationIds.isValidFileId(id));
        assertFalse(ConversationIds.isValidFileId("../../etc/passwd"));
        assertFalse(ConversationIds.isValidFileId("1-1-1-1-1"));
        assertFalse(ConversationIds.isValidFileId(id.toUpperCase()));
        assertFalse(ConversationIds.isValidFileId(null));
        assertTrue(ConversationIds.isGroup(ConversationIds.newGroupId()));
        assertFalse(ConversationIds.isGroup("group:../../evil"));
        assertTrue(ConversationIds.isLobby("lobby"));
        assertFalse(ConversationIds.isLobby(null));
    }
}
