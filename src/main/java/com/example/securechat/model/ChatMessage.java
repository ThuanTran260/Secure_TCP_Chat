package com.example.securechat.model;

import java.util.Optional;
import java.util.List;

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
    private String to;
    private Long timestamp;
    private String kind;
    private String fileId;
    private String fileName;
    private String mime;
    private Long size;
    private Integer index;
    private String data;
    private Boolean online;
    private List<String> members;
    private List<String> onlineUsers;
    private String groupName;
    private Boolean history;

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

    public static ChatMessage messageTo(String to, String content) {
        ChatMessage m = message(content);
        m.to = to;
        return m;
    }

    public static ChatMessage chatIn(String to, int clientId, String from, String content, long timestamp) {
        ChatMessage m = chat(clientId, from, content);
        m.to = to;
        m.timestamp = timestamp;
        return m;
    }

    public static ChatMessage presence(String username, boolean online) {
        ChatMessage m = new ChatMessage(MessageType.PRESENCE);
        m.from = username;
        m.online = online;
        return m;
    }

    public static ChatMessage userList(List<String> members, List<String> onlineUsers) {
        ChatMessage m = new ChatMessage(MessageType.USER_LIST);
        m.members = members;
        m.onlineUsers = onlineUsers;
        return m;
    }

    public static ChatMessage groupCreate(String name, List<String> members) {
        ChatMessage m = new ChatMessage(MessageType.GROUP_CREATE);
        m.groupName = name;
        m.members = members;
        return m;
    }

    public static ChatMessage groupInfo(String groupId, String name, List<String> members) {
        ChatMessage m = new ChatMessage(MessageType.GROUP_INFO);
        m.to = groupId;
        m.groupName = name;
        m.members = members;
        return m;
    }

    public static ChatMessage historyRequest(String to) {
        ChatMessage m = new ChatMessage(MessageType.HISTORY_REQUEST);
        m.to = to;
        return m;
    }

    public static ChatMessage historyEnd(String to) {
        ChatMessage m = new ChatMessage(MessageType.HISTORY_END);
        m.to = to;
        return m;
    }

    public static ChatMessage fileStart(String fileId, String to, String fileName, String mime, long size, String kind) {
        ChatMessage m = new ChatMessage(MessageType.FILE_START);
        m.fileId = fileId;
        m.to = to;
        m.fileName = fileName;
        m.mime = mime;
        m.size = size;
        m.kind = kind;
        return m;
    }

    public static ChatMessage fileChunk(String fileId, int index, String base64) {
        ChatMessage m = new ChatMessage(MessageType.FILE_CHUNK);
        m.fileId = fileId;
        m.index = index;
        m.data = base64;
        return m;
    }

    public static ChatMessage fileEnd(String fileId) {
        ChatMessage m = new ChatMessage(MessageType.FILE_END);
        m.fileId = fileId;
        return m;
    }

    public static ChatMessage fileGet(String fileId) {
        ChatMessage m = new ChatMessage(MessageType.FILE_GET);
        m.fileId = fileId;
        return m;
    }

    public static ChatMessage error(String code, String fileId, String to) {
        ChatMessage m = error(code);
        m.fileId = fileId;
        m.to = to;
        return m;
    }

    /** Returns an independent message object while sharing immutable field values and lists. */
    public ChatMessage copy() {
        ChatMessage m = new ChatMessage();
        m.type = type;
        m.username = username;
        m.password = password;
        m.content = content;
        m.from = from;
        m.success = success;
        m.clientId = clientId;
        m.to = to;
        m.timestamp = timestamp;
        m.kind = kind;
        m.fileId = fileId;
        m.fileName = fileName;
        m.mime = mime;
        m.size = size;
        m.index = index;
        m.data = data;
        m.online = online;
        m.members = members;
        m.onlineUsers = onlineUsers;
        m.groupName = groupName;
        m.history = history;
        return m;
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

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public Integer getIndex() { return index; }
    public void setIndex(Integer index) { this.index = index; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public Boolean getOnline() { return online; }
    public void setOnline(Boolean online) { this.online = online; }

    public List<String> getMembers() { return members; }
    public void setMembers(List<String> members) { this.members = members; }

    public List<String> getOnlineUsers() { return onlineUsers; }
    public void setOnlineUsers(List<String> onlineUsers) { this.onlineUsers = onlineUsers; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public Boolean getHistory() { return history; }
    public void setHistory(Boolean history) { this.history = history; }

    /** Never prints passwords or attachment payloads. */
    @Override
    public String toString() {
        return "ChatMessage{type=" + type
                + ", username=" + username
                + (password != null ? ", password=***" : "")
                + ", content=" + content
                + ", from=" + from
                + ", success=" + success
                + ", clientId=" + clientId
                + ", to=" + to
                + ", timestamp=" + timestamp
                + (data != null ? ", data=<" + data.length() + " chars>" : "") + "}";
    }
}
