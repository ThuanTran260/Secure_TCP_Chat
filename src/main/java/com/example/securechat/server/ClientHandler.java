package com.example.securechat.server;

import static com.example.securechat.server.ChatServer.LOG;

import com.example.securechat.common.LineReader;
import com.example.securechat.common.MessageTooLargeException;
import com.example.securechat.common.Protocol;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.ConversationIds;
import com.example.securechat.model.InvalidMessageException;
import com.example.securechat.model.JsonCodec;
import com.example.securechat.model.MessageType;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;

/**
 * Runs one client connection on its own thread: TLS handshake, login, then the read loop.
 * Every way out of the loop ends in {@link ChatServer#removeClient}.
 */
final class ClientHandler implements Runnable {

    static final String LOGIN_BAD_CREDENTIALS = "Username hoặc Password không đúng";
    static final String LOGIN_ALREADY_ONLINE = "Tài khoản đang online";
    static final String LOGIN_THROTTLED = "Đăng nhập sai quá nhiều lần, thử lại sau";

    private final ChatServer server;
    private final int id;
    private final SSLSocket socket;
    private final String ip;

    /** Name this connection claimed in the registry; only touched by the handler thread. */
    private String claimedUsername;

    ClientHandler(ChatServer server, int id, SSLSocket socket) {
        this.server = server;
        this.id = id;
        this.socket = socket;
        this.ip = socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        try {
            serve();
        } finally {
            server.socketDone(socket);
        }
    }

    private void serve() {
        ClientSession session;
        LineReader reader;
        String protocol;
        try {
            socket.setSoTimeout(Protocol.HANDSHAKE_TIMEOUT_MS);
            socket.startHandshake();
            protocol = socket.getSession().getProtocol();
            reader = new LineReader(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
            session = new ClientSession(id, ip, socket.getOutputStream(), socket,
                    server.options().sendQueueCapacity());
        } catch (IOException | RuntimeException e) {
            // Plain TCP, a bad protocol version or a stalled handshake all end here, before any registry entry.
            LOG.warning("[BLOCK] TLS handshake failed: client " + id + " (" + ip + ")");
            closeQuietly();
            return;
        }

        session.start(e -> server.removeClient(id, ChatServer.REASON_SEND_FAILED));
        server.registry().add(session);
        LOG.info("[PASS] TLS authenticated: client " + id + " (" + ip + ") " + protocol);

        String reason = "internal error";
        try {
            reason = readLoop(session, reader);
        } catch (RuntimeException e) {
            // Log the type only; the message of an exception raised while parsing could echo client input.
            LOG.warning("Unexpected error in client " + id + ": " + e.getClass().getSimpleName());
            reason = "internal error: " + e.getClass().getSimpleName();
        } finally {
            // Idempotent: the reason is only logged if nobody (e.g. the writer) removed the client first.
            server.removeClient(id, reason);
            if (claimedUsername != null) {
                // Covers a failure between claiming the name and markAuthenticated(); no-op otherwise.
                server.registry().releaseUsername(claimedUsername, id);
            }
        }
    }

    /** Reads until the connection should end and returns the reason for the disconnect log. */
    private String readLoop(ClientSession session, LineReader reader) {
        // One overall budget for logging in: it is not renewed by garbage sent before authenticating.
        long loginDeadline = System.nanoTime() + server.options().loginTimeout().toNanos();
        while (true) {
            try {
                if (!session.isAuthenticated()) {
                    long remainingNanos = loginDeadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return "login timeout";
                    }
                    // soTimeout 0 would mean "wait forever", so never round down to 0.
                    socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE,
                            Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
                }
                String line = reader.readLine();
                if (line == null) {
                    return "connection closed";
                }
                ChatMessage message;
                try {
                    message = JsonCodec.decode(line);
                } catch (InvalidMessageException e) {
                    if (!session.send(ChatMessage.error("INVALID_JSON"))) {
                        return ChatServer.REASON_SEND_QUEUE_FULL;
                    }
                    continue;
                }
                String closeReason = session.isAuthenticated()
                        ? handleAuthenticated(session, message)
                        : handleUnauthenticated(session, message);
                if (closeReason != null) {
                    return closeReason;
                }
            } catch (MessageTooLargeException e) {
                // The stream cannot be re-synchronised after an overlong line, so reply and close.
                session.send(ChatMessage.error("MESSAGE_TOO_LARGE"));
                return "message too large";
            } catch (SocketTimeoutException e) {
                return session.isAuthenticated() ? "io error: SocketTimeoutException" : "login timeout";
            } catch (IOException e) {
                return "io error: " + e.getClass().getSimpleName();
            }
        }
    }

    /** Returns null to keep reading, or the reason to close the connection. */
    private String handleUnauthenticated(ClientSession session, ChatMessage message) throws IOException {
        if (message.messageType().orElse(null) != MessageType.LOGIN) {
            return session.send(ChatMessage.error("NOT_AUTHENTICATED")) ? null : ChatServer.REASON_SEND_QUEUE_FULL;
        }
        return handleLogin(session, message);
    }

    private String handleLogin(ClientSession session, ChatMessage message) throws IOException {
        String username = UserStore.normalize(message.getUsername());
        LoginThrottle throttle = server.options().throttle();

        if (throttle.isLocked(ip, username)) {
            // Checked before verifying so a locked pair cannot be used to keep guessing.
            LOG.warning("[BLOCK] Login throttled: client " + id);
            session.send(ChatMessage.loginResult(false, null, LOGIN_THROTTLED));
            return "login throttled";
        }

        char[] password = message.getPassword() == null ? new char[0] : message.getPassword().toCharArray();
        boolean valid;
        try {
            valid = server.users().verify(message.getUsername(), password);
        } finally {
            Arrays.fill(password, '\0');
            message.setPassword(null);
        }

        if (!valid) {
            throttle.recordFailure(ip, username);
            LOG.warning("[BLOCK] Login failed: client " + id);
            session.send(ChatMessage.loginResult(false, null, LOGIN_BAD_CREDENTIALS));
            return "login failed";
        }
        if (!server.registry().tryClaimUsername(username, id)) {
            session.send(ChatMessage.loginResult(false, null, LOGIN_ALREADY_ONLINE));
            return "username already online";
        }

        claimedUsername = username;
        throttle.recordSuccess(ip, username);
        // login_result is queued before the session becomes visible to broadcasts, otherwise another
        // client's join notice could reach this client ahead of its own login_result.
        if (!session.send(ChatMessage.loginResult(true, id, null))) {
            return ChatServer.REASON_SEND_QUEUE_FULL;
        }
        session.markAuthenticated(username);
        socket.setSoTimeout(0);
        ChatMessage roster = ChatMessage.userList(server.users().usernames(), server.registry().authenticatedSnapshot()
                .stream().map(ClientSession::username).sorted().toList());
        if (!sendChecked(session, roster)) return ChatServer.REASON_SEND_QUEUE_FULL;
        for (GroupStore.Group group : server.groups().groupsOf(username)) {
            if (!sendChecked(session, ChatMessage.groupInfo(group.id(), group.name(), group.members()))) {
                return ChatServer.REASON_SEND_QUEUE_FULL;
            }
        }
        server.broadcast(ChatMessage.presence(username, true));
        server.broadcast(ChatMessage.system(username + " đã tham gia"));
        return null;
    }

    /** Routes one message of an authenticated session. Returns null to keep reading, or the close reason. */
    private String handleAuthenticated(ClientSession session, ChatMessage message) throws IOException {
        MessageType type = message.messageType().orElse(null);
        if (type == null) {
            return reply(session, "UNKNOWN_TYPE");
        }
        switch (type) {
            case MESSAGE:
                return handleChat(session, message);
            case GROUP_CREATE:
                return handleGroupCreate(session, message);
            case HISTORY_REQUEST:
                return handleHistory(session, message);
            case FILE_START:
                server.fileTransfers().onStart(session, message); return null;
            case FILE_CHUNK:
                server.fileTransfers().onChunk(session, message); return null;
            case FILE_END:
                server.fileTransfers().onEnd(session, message); return null;
            case FILE_GET:
                server.fileTransfers().onGet(session, message); return null;
            case LOGIN:
                return reply(session, "ALREADY_AUTHENTICATED");
            case DISCONNECT:
                return "client requested";
            default:
                // login_result, system, user_left and error only ever travel server -> client.
                return reply(session, "UNKNOWN_TYPE");
        }
    }

    private String handleChat(ClientSession session, ChatMessage message) throws IOException {
        String to = conversation(message);
        String error = server.conversationError(session.username(), to);
        if (error != null) return reply(session, error, message.getFileId(), to);
        String content = message.getContent();
        if (content == null || content.isBlank()) {
            return reply(session, "EMPTY_MESSAGE");
        }
        // The server adds clientId and from, so the outgoing line is longer than the incoming one and
        // must be checked on its own: an oversized line would break the 4096-byte limit for receivers.
        ChatMessage chat = ChatMessage.chatIn(to, id, session.username(), content, server.clock().millis());
        if (!server.publish(chat)) {
            return reply(session, "MESSAGE_TOO_LARGE");
        }
        return null;
    }

    private String handleGroupCreate(ClientSession session, ChatMessage message) throws IOException {
        String name = message.getGroupName();
        if (name == null || name.isBlank() || name.length() > 50 || message.getMembers() == null) {
            return reply(session, "INVALID_GROUP");
        }
        TreeSet<String> members = new TreeSet<>();
        members.add(session.username());
        for (String member : message.getMembers()) {
            String user = UserStore.normalize(member);
            if (!server.users().usernames().contains(user)) return reply(session, "INVALID_GROUP");
            members.add(user);
        }
        List<String> membership = List.copyOf(members);
        if (!Protocol.fitsLine(JsonCodec.encode(ChatMessage.groupInfo(ConversationIds.newGroupId(), name.trim(), membership)))) {
            return reply(session, "INVALID_GROUP");
        }
        GroupStore.Group group = server.groups().create(name, membership);
        server.deliver(group.id(), JsonCodec.encode(ChatMessage.groupInfo(group.id(), group.name(), group.members())));
        return null;
    }

    private String handleHistory(ClientSession session, ChatMessage message) throws IOException {
        String to = conversation(message);
        String error = server.conversationError(session.username(), to);
        if (error != null) return reply(session, error, null, to);
        for (ChatMessage stored : server.history().recent(to, Protocol.HISTORY_LIMIT)) {
            ChatMessage history = stored.copy();
            history.setHistory(true);
            String line = JsonCodec.encode(history);
            if (!Protocol.fitsLine(line)) return reply(session, "MESSAGE_TOO_LARGE", null, to);
            if (!session.enqueueBulk(line, Duration.ofSeconds(30))) return ChatServer.REASON_SEND_QUEUE_FULL;
        }
        return sendChecked(session, ChatMessage.historyEnd(to)) ? null : ChatServer.REASON_SEND_QUEUE_FULL;
    }

    private static String conversation(ChatMessage message) {
        return message.getTo() == null ? ConversationIds.LOBBY : message.getTo();
    }

    private static boolean sendChecked(ClientSession session, ChatMessage message) {
        String line = JsonCodec.encode(message);
        return session.enqueue(Protocol.fitsLine(line) ? line : JsonCodec.encode(ChatMessage.error("MESSAGE_TOO_LARGE")));
    }

    /** Queues an error reply; a full queue means the client is too slow and gets dropped. */
    private String reply(ClientSession session, String errorCode) {
        return session.send(ChatMessage.error(errorCode)) ? null : ChatServer.REASON_SEND_QUEUE_FULL;
    }

    private String reply(ClientSession session, String code, String fileId, String to) {
        return sendChecked(session, ChatMessage.error(code, fileId, to)) ? null : ChatServer.REASON_SEND_QUEUE_FULL;
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do when closing a dead connection.
        }
    }
}
