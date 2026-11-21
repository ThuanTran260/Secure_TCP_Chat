package com.example.securechat.model;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Canonical conversation identifiers; untrusted identifiers must be validated before use. */
public final class ConversationIds {
    public static final String LOBBY = "lobby";

    private ConversationIds() { }

    public static String dm(String u1, String u2) {
        String a = normalize(u1);
        String b = normalize(u2);
        if (!validUsername(a) || !validUsername(b) || a.equals(b)) {
            throw new IllegalArgumentException("Direct conversation requires two distinct valid usernames");
        }
        return a.compareTo(b) < 0 ? "dm:" + a + ":" + b : "dm:" + b + ":" + a;
    }

    public static boolean isDm(String id) {
        if (id == null || !id.startsWith("dm:")) return false;
        String[] parts = id.split(":", -1);
        return parts.length == 3 && validUsername(parts[1]) && validUsername(parts[2])
                && normalize(parts[1]).equals(parts[1]) && normalize(parts[2]).equals(parts[2])
                && parts[1].compareTo(parts[2]) < 0;
    }

    public static boolean isGroup(String id) {
        return id != null && id.startsWith("group:") && isValidFileId(id.substring(6));
    }

    public static boolean isLobby(String id) {
        return LOBBY.equals(id);
    }

    public static List<String> dmMembers(String id) {
        if (!isDm(id)) throw new IllegalArgumentException("Invalid direct conversation identifier");
        String[] parts = id.split(":", -1);
        return List.of(parts[1], parts[2]);
    }

    public static String newGroupId() {
        return "group:" + UUID.randomUUID();
    }

    public static boolean isValidFileId(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean validUsername(String username) {
        return !username.isEmpty() && username.chars().noneMatch(c -> c == ':' || c == '/' || c == '\\'
                || Character.isWhitespace(c) || Character.isISOControl(c));
    }
}
