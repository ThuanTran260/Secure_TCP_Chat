package com.example.securechat.server;

import com.example.securechat.common.TlsContexts;
import com.example.securechat.common.Protocol;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.ConversationIds;
import com.example.securechat.model.JsonCodec;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * TLS chat server: accepts connections, hands each to its own {@link ClientHandler} thread and
 * owns the shared {@link ClientRegistry}.
 */
public final class ChatServer {

    static final Logger LOG = Logger.getLogger("securechat.server");

    /** Disconnect reasons that mean the peer cannot be flushed to, so we cut the connection at once. */
    static final String REASON_SEND_QUEUE_FULL = "send queue full";
    static final String REASON_SEND_FAILED = "send failed";

    private static final Duration GRACEFUL_CLOSE_WAIT = Duration.ofSeconds(2);

    private final SSLContext sslContext;
    private final UserStore users;
    private final int requestedPort;
    private final ServerOptions options;
    private final Clock clock;
    private final HistoryStore history;
    private final GroupStore groups;
    private final FileStore files;
    private final FileTransfers fileTransfers;
    private final ClientRegistry registry = new ClientRegistry();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /** Every accepted socket until its handler finishes, so stop() can also cut clients still handshaking. */
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();

    private final Object lifecycleLock = new Object();
    private SSLServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService handlers;
    private volatile boolean stopped;

    public ChatServer(SSLContext sslContext, UserStore users, int port, ServerOptions options) {
        this(sslContext, users, port, options, Clock.systemUTC());
    }

    ChatServer(SSLContext sslContext, UserStore users, int port, ServerOptions options, Clock clock) {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
        this.users = Objects.requireNonNull(users, "users");
        this.requestedPort = port;
        this.options = Objects.requireNonNull(options, "options");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            history = new HistoryStore(options.dataDir().resolve("history"));
            groups = new GroupStore(options.dataDir().resolve("groups.json"));
            files = new FileStore(options.dataDir().resolve("files"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open server data directory", e);
        }
        fileTransfers = new FileTransfers(this);
    }

    /** Binds 0.0.0.0:port, starts accepting on a background thread and returns the actual port. */
    public int start() throws IOException {
        synchronized (lifecycleLock) {
            if (serverSocket != null || stopped) {
                throw new IllegalStateException("Server already started or stopped");
            }
            SSLServerSocket ss = (SSLServerSocket) sslContext.getServerSocketFactory()
                    .createServerSocket(requestedPort, 50, InetAddress.getByName("0.0.0.0"));
            TlsContexts.hardenServer(ss);
            serverSocket = ss;
            handlers = Executors.newCachedThreadPool(namedDaemonThreads("chat-handler-"));
            int port = ss.getLocalPort();
            LOG.info("Server listening on port " + port);

            acceptThread = new Thread(() -> acceptLoop(ss), "chat-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            return port;
        }
    }

    /** Stops accepting, drops every client and waits for the server's threads. Safe to call repeatedly. */
    public void stop() {
        Thread accepter;
        ExecutorService pool;
        synchronized (lifecycleLock) {
            if (stopped) {
                return;
            }
            stopped = true;
            accepter = acceptThread;
            pool = handlers;
            closeQuietly(serverSocket);
        }
        for (ClientSession s : registry.authenticatedSnapshot()) {
            s.abort();
        }
        fileTransfers.shutdown();
        for (Socket s : openSockets) {
            closeQuietly(s);
        }
        if (pool != null) {
            // Every socket is closed, so handlers finish on their own. They must not be interrupted
            // while in removeClient(): that would skip the writer thread's shutdown signal and leak it.
            pool.shutdown();
        }
        try {
            if (accepter != null) {
                accepter.join(TimeUnit.SECONDS.toMillis(2));
            }
            if (pool != null && !pool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warning("Handler threads did not stop within 5s; interrupting");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            if (pool != null) {
                pool.shutdownNow();
            }
            Thread.currentThread().interrupt();
        }
    }

    public ClientRegistry registry() {
        return registry;
    }

    ServerOptions options() {
        return options;
    }

    UserStore users() {
        return users;
    }

    Clock clock() { return clock; }
    HistoryStore history() { return history; }
    GroupStore groups() { return groups; }
    FileStore files() { return files; }
    FileTransfers fileTransfers() { return fileTransfers; }

    boolean canAccess(String username, String conversationId) {
        if (ConversationIds.isLobby(conversationId)) return true;
        if (ConversationIds.isDm(conversationId)) {
            List<String> members = ConversationIds.dmMembers(conversationId);
            return members.contains(username) && users.usernames().containsAll(members);
        }
        return ConversationIds.isGroup(conversationId)
                && groups.find(conversationId).map(g -> g.members().contains(username)).orElse(false);
    }

    String conversationError(String username, String conversationId) {
        if (!(ConversationIds.isLobby(conversationId) || ConversationIds.isDm(conversationId)
                || ConversationIds.isGroup(conversationId))) return "UNKNOWN_CONVERSATION";
        if (ConversationIds.isGroup(conversationId) && groups.find(conversationId).isEmpty()) return "UNKNOWN_CONVERSATION";
        return canAccess(username, conversationId) ? null : "NOT_MEMBER";
    }

    void deliver(String conversationId, String line) {
        if (stopped) return;
        if (!Protocol.fitsLine(line)) throw new IllegalArgumentException("Message exceeds line limit");
        if (ConversationIds.isLobby(conversationId)) { broadcastLine(line); return; }
        List<String> members = ConversationIds.isDm(conversationId) ? ConversationIds.dmMembers(conversationId)
                : groups.find(conversationId).map(GroupStore.Group::members).orElse(List.of());
        for (String username : members) registry.sessionOf(username).ifPresent(session -> {
            if (!session.enqueue(line)) removeClient(session.id(), REASON_SEND_QUEUE_FULL);
        });
    }

    /** Persists first, then echoes to every online member, including the sender. */
    boolean publish(ChatMessage message) throws IOException {
        String line = JsonCodec.encode(message);
        if (!Protocol.fitsLine(line)) return false;
        ChatMessage replay = message.copy();
        replay.setHistory(true);
        if (!Protocol.fitsLine(JsonCodec.encode(replay))) return false;
        synchronized (history) {
            history.append(message.getTo(), message);
            deliver(message.getTo(), line);
        }
        return true;
    }

    /**
     * Sends the message to every authenticated session. The line is encoded once and queued, so this
     * never blocks on a client socket; a session whose queue is full is dropped.
     */
    void broadcast(ChatMessage message) {
        String line = JsonCodec.encode(message);
        if (Protocol.fitsLine(line)) broadcastLine(line);
    }

    /** Same as {@link #broadcast(ChatMessage)} for a line the caller has already encoded (and size-checked). */
    void broadcastLine(String line) {
        if (stopped) {
            // stop() aborts every session, so enqueues would fail and be misreported as "send queue full".
            return;
        }
        for (ClientSession s : registry.authenticatedSnapshot()) {
            if (!s.enqueue(line)) {
                removeClient(s.id(), REASON_SEND_QUEUE_FULL);
            }
        }
    }

    /**
     * Removes the client and closes its connection. Idempotent: only the first call for an id does
     * anything, whichever thread (reader, writer, broadcaster, stop) gets there first.
     */
    void removeClient(int id, String reason) {
        ClientSession session = registry.remove(id);
        if (session == null) {
            return;
        }
        fileTransfers.sessionClosed(id);
        String username = session.username();
        if (username != null) {
            // Free the name before the (possibly slow) graceful close so the user can reconnect.
            registry.releaseUsername(username, id);
        }
        // Logged before closing so the line is already there once the peer notices the close.
        LOG.info("[INFO] Client " + id + " disconnected (" + session.remoteAddress() + ") - " + reason);
        if (session.isAuthenticated()) {
            // After the registry removal, so the leaver is not in the snapshot; before the (possibly
            // slow) graceful close so the others hear about it promptly. Only enqueues, never blocks.
            broadcast(ChatMessage.userLeft(id, username));
            broadcast(ChatMessage.presence(username, false));
        }
        if (REASON_SEND_QUEUE_FULL.equals(reason) || REASON_SEND_FAILED.equals(reason)) {
            // The peer is not draining its socket, so waiting for a flush would only stall us.
            session.abort();
        } else {
            session.closeGracefully(GRACEFUL_CLOSE_WAIT);
        }
    }

    void socketDone(Socket socket) {
        openSockets.remove(socket);
    }

    private void acceptLoop(SSLServerSocket ss) {
        while (!stopped) {
            Socket socket;
            try {
                socket = ss.accept();
            } catch (IOException e) {
                if (stopped || ss.isClosed()) {
                    return;
                }
                LOG.warning("Accept failed: " + e.getClass().getSimpleName());
                pauseBriefly();
                continue;
            }
            int id = nextId.getAndIncrement();
            openSockets.add(socket);
            if (stopped) {
                // stop() may have swept openSockets just before this socket was added.
                closeQuietly(socket);
                socketDone(socket);
                return;
            }
            try {
                handlers.execute(new ClientHandler(this, id, (SSLSocket) socket));
            } catch (RejectedExecutionException e) {
                closeQuietly(socket);
                socketDone(socket);
                return;
            }
        }
    }

    private static void pauseBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedDaemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // Nothing useful to do when closing a dead connection.
        }
    }
}
