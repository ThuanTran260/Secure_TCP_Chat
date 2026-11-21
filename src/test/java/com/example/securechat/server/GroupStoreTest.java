package com.example.securechat.server;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GroupStoreTest {
    @TempDir Path dir;
    @Test void groupPersistsAndListsByMember() throws Exception {
        Path file = dir.resolve("groups.json");
        var group = new GroupStore(file).create("Nhóm A", List.of("an", "binh"));
        GroupStore reloaded = new GroupStore(file);
        assertEquals(group, reloaded.find(group.id()).orElseThrow());
        assertEquals(List.of(group), reloaded.groupsOf("an"));
        assertTrue(reloaded.groupsOf("chi").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> group.members().add("chi"));
    }
}
