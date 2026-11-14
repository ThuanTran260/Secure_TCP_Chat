package com.example.securechat.server;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Read-only username to password-hash store. Usernames are case-insensitive. */
public final class UserStore {
    private final Map<String, String> users;
    /** Verified against when the user is unknown, so timing does not reveal which usernames exist. */
    private final String dummyHash;

    private UserStore(Map<String, String> users) {
        this.users = users;
        int iterations = PasswordHasher.DEFAULT_ITERATIONS;
        for (String encoded : users.values()) {
            int n = PasswordHasher.iterationsOf(encoded);
            if (n > 0) {
                iterations = n;
                break;
            }
        }
        this.dummyHash = PasswordHasher.hash("dummy-password-for-timing".toCharArray(), iterations);
    }

    public static UserStore load(Path propertiesFile) throws IOException {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        Map<String, String> m = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            m.put(name, props.getProperty(name).trim());
        }
        return of(m);
    }

    public static UserStore of(Map<String, String> usernameToEncoded) {
        Map<String, String> normalized = new HashMap<>();
        usernameToEncoded.forEach((k, v) -> normalized.put(normalize(k), v));
        return new UserStore(Map.copyOf(normalized));
    }

    public boolean verify(String username, char[] password) {
        String key = normalize(username);
        String encoded = key.isEmpty() ? null : users.get(key);
        if (encoded == null) {
            PasswordHasher.verify(password, dummyHash);
            return false;
        }
        return PasswordHasher.verify(password, encoded);
    }

    public static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
