package com.example.securechat.testsupport;

import com.example.securechat.server.PasswordHasher;
import com.example.securechat.server.UserStore;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pre-built user stores for tests; low iteration count keeps tests fast. */
public final class TestUsers {
    private static final int ITERATIONS = 1000;

    private TestUsers() {}

    public static UserStore store() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("an", PasswordHasher.hash("An@12345".toCharArray(), ITERATIONS));
        m.put("binh", PasswordHasher.hash("Binh@12345".toCharArray(), ITERATIONS));
        m.put("chi", PasswordHasher.hash("Chi@12345".toCharArray(), ITERATIONS));
        return UserStore.of(m);
    }

    /** Users u1..un with password "Pass@<i>". */
    public static UserStore storeWithUsers(int n) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
            m.put("u" + i, PasswordHasher.hash(("Pass@" + i).toCharArray(), ITERATIONS));
        }
        return UserStore.of(m);
    }
}
