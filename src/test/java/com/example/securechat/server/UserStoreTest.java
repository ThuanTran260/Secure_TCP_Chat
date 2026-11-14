package com.example.securechat.server;

import com.example.securechat.testsupport.TestUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UserStoreTest {
    @Test void verifyIsCaseInsensitiveOnUsername() {
        assertTrue(TestUsers.store().verify(" AN ", "An@12345".toCharArray()));
    }

    @Test void unknownUserFails() {
        assertFalse(TestUsers.store().verify("ghost", "An@12345".toCharArray()));
    }

    @Test void nullUsernameFails() {
        assertFalse(TestUsers.store().verify(null, "x".toCharArray()));
    }

    @Test void loadFromProperties(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("users.properties");
        Files.writeString(f, "an=" + PasswordHasher.hash("An@12345".toCharArray(), 1000) + "\n", StandardCharsets.UTF_8);
        UserStore store = UserStore.load(f);
        assertTrue(store.verify("an", "An@12345".toCharArray()));
        assertFalse(store.verify("an", "bad".toCharArray()));
    }
}
