package com.example.securechat.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test void hashThenVerify() {
        String h = PasswordHasher.hash("An@12345".toCharArray(), 1000);
        assertTrue(h.startsWith("pbkdf2$1000$"));
        assertTrue(PasswordHasher.verify("An@12345".toCharArray(), h));
        assertFalse(PasswordHasher.verify("wrong".toCharArray(), h));
    }

    @Test void sameInputDifferentSalt() {
        assertNotEquals(PasswordHasher.hash("x".toCharArray(), 1000), PasswordHasher.hash("x".toCharArray(), 1000));
    }

    @Test void malformedEncodedReturnsFalse() {
        assertFalse(PasswordHasher.verify("x".toCharArray(), "garbage"));
    }
}
