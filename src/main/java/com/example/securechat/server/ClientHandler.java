package com.example.securechat.server;

import static com.example.securechat.server.ChatServer.LOG;

import com.example.securechat.common.LineReader;
import com.example.securechat.common.MessageTooLargeException;
import com.example.securechat.common.Protocol;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.InvalidMessageException;
import com.example.securechat.model.JsonCodec;
import com.example.securechat.model.MessageType;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
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
        server.broadcast(ChatMessage.system(username + " đã tham gia"));
        return null;
    }

    /**
     * Task 7 seam: message routing for authenticated sessions (message, login, disconnect, unknown
     * types, size checks). Until then input from authenticated clients is read and ignored.
     */
    private String handleAuthenticated(ClientSession session, ChatMessage message) {
        return null;
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do when closing a dead connection.
        }
    }
}
