package com.example.securechat.model;

import java.util.Optional;

/**
 * One protocol message. Public no-arg constructor and accessors exist for Jackson;
 * null fields are omitted when serialized (see {@link JsonCodec}).
 */
public final class ChatMessage {
    private String type;
    private String username;
    private String password;
    private String content;
    private String from;
    private Boolean success;
    private Integer clientId;

    public ChatMessage() {
    }

    private ChatMessage(MessageType type) {
        this.type = type.value();
    }

    public static ChatMessage login(String username, char[] password) {
        ChatMessage m = new ChatMessage(MessageType.LOGIN);
        m.username = username;
        m.password = password == null ? null : new String(password);
        return m;
    }

    public static ChatMessage loginResult(boolean success, Integer clientId, String content) {
        ChatMessage m = new ChatMessage(MessageType.LOGIN_RESULT);
        m.success = success;
        m.clientId = clientId;
        m.content = content;
        return m;
    }

    /** Client-to-server chat text. */
    public static ChatMessage message(String content) {
        ChatMessage m = new ChatMessage(MessageType.MESSAGE);
        m.content = content;
        return m;
    }

    /** Server-to-client chat text enriched with the sender. */
    public static ChatMessage chat(int clientId, String from, String content) {
        ChatMessage m = new ChatMessage(MessageType.MESSAGE);
        m.clientId = clientId;
        m.from = from;
        m.content = content;
        return m;
    }

    public static ChatMessage system(String content) {
        ChatMessage m = new ChatMessage(MessageType.SYSTEM);
        m.content = content;
        return m;
    }

    public static ChatMessage userLeft(int clientId, String from) {
        ChatMessage m = new ChatMessage(MessageType.USER_LEFT);
        m.clientId = clientId;
        m.from = from;
        m.content = from + " đã rời phòng";
        return m;
    }

    public static ChatMessage error(String code) {
        ChatMessage m = new ChatMessage(MessageType.ERROR);
        m.content = code;
        return m;
    }

    public static ChatMessage disconnect() {
        return new ChatMessage(MessageType.DISCONNECT);
    }

    /** Parsed type, or empty when {@code type} is null or not a known value. */
    public Optional<MessageType> messageType() {
        return MessageType.fromValue(type);
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public Integer getClientId() { return clientId; }
    public void setClientId(Integer clientId) { this.clientId = clientId; }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "ChatMessage{type=" + type
                + ", username=" + username
                + (password != null ? ", password=***" : "")
                + ", content=" + content
                + ", from=" + from
                + ", success=" + success
                + ", clientId=" + clientId + "}";
    }
}
