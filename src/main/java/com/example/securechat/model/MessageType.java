package com.example.securechat.model;

import java.util.Optional;

/** Known values of the {@code type} field in the JSON protocol. */
public enum MessageType {
    LOGIN("login"),
    LOGIN_RESULT("login_result"),
    MESSAGE("message"),
    SYSTEM("system"),
    USER_LEFT("user_left"),
    ERROR("error"),
    DISCONNECT("disconnect");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Maps a wire string to a type; empty when the string is null or unknown. */
    public static Optional<MessageType> fromValue(String v) {
        if (v == null) {
            return Optional.empty();
        }
        for (MessageType t : values()) {
            if (t.value.equals(v)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
