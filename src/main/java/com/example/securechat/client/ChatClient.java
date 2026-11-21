package com.example.securechat.client;

import com.example.securechat.common.LineReader;
import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.ConversationIds;
import com.example.securechat.model.InvalidMessageException;
import com.example.securechat.model.JsonCodec;
import com.example.securechat.model.MessageType;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * TLS chat client. The calling thread does connect/login/send; once {@link #startReceiving} is called
 * a single daemon thread ("chat-receive") is the only reader of the stream.
 */
public final class ChatClient implements AutoCloseable {

    static final int LOGIN_TIMEOUT_MS = 10_000;
    private static final long LOCK_WAIT_MS = 1_000;

    private final SSLContext ctx;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock uploadLock = new ReentrantLock();
    private final ConcurrentHashMap<String, Download> downloads = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closedNotified = new AtomicBoolean();

    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private volatile LineReader reader;
    private volatile Thread receiveThread;

    public ChatClient(SSLContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    /**
     * Opens the TCP connection (bounded by {@code timeoutMs}) and completes a hardened TLS handshake.
     * Certificate chain, validity period and host name are verified before this method returns.
     */
    public void connect(String host, int port, int timeoutMs) throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
        if (socket != null) {
            throw new IllegalStateException("Already connected");
        }
        Socket raw = new Socket();
        SSLSocket tls = null;
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            // Bound the handshake too, so a server that accepts TCP but never answers cannot hang us.
            raw.setSoTimeout(Protocol.HANDSHAKE_TIMEOUT_MS);
            tls = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            TlsContexts.hardenClient(tls);
            tls.startHandshake();
            tls.setSoTimeout(0);
            synchronized (this) {
                if (closed.get()) throw new IOException("Client is closed");
                this.out = tls.getOutputStream();
                this.reader = new LineReader(tls.getInputStream(), Protocol.MAX_LINE_BYTES);
                this.socket = tls;
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(tls != null ? tls : raw);
            throw e;
        }
    }

    public String negotiatedProtocol() {
        SSLSocket s = requireSocket();
        return s.getSession().getProtocol();
    }

    /**
     * Sends the login request and synchronously waits (up to 10 s) for {@code login_result},
     * ignoring any other message that arrives first. Call before {@link #startReceiving}.
     */
    public ChatMessage login(String username, char[] password) throws IOException {
        SSLSocket s = requireSocket();
        writeLine(JsonCodec.encode(ChatMessage.login(username, password)));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOGIN_TIMEOUT_MS);
        try {
            while (true) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    throw new SocketTimeoutException("Login timed out");
                }
                s.setSoTimeout((int) remainingMs);
                String line = reader.readLine();
                if (line == null) {
                    throw new EOFException("Server closed the connection");
                }
                ChatMessage m;
                try {
                    m = JsonCodec.decode(line);
                } catch (InvalidMessageException e) {
                    continue;
                }
                if (m.messageType().orElse(null) == MessageType.LOGIN_RESULT) {
                    return m;
                }
            }
        } finally {
            if (!closed.get()) {
                try {
                    s.setSoTimeout(0);
                } catch (IOException ignored) {
                    // Socket already broken; the next read reports it.
                }
            }
        }
    }

    /**
     * Starts the single reader thread. {@code onClosed} runs exactly once when the stream ends,
     * fails, or the client is closed locally.
     */
    public void startReceiving(Consumer<ChatMessage> onMessage, Consumer<Exception> onClosed) {
        requireSocket();
        Thread t = new Thread(() -> receiveLoop(onMessage, onClosed), "chat-receive");
        t.setDaemon(true);
        synchronized (this) {
            if (receiveThread != null) {
                throw new IllegalStateException("Already receiving");
            }
            receiveThread = t;
        }
        t.start();
    }

    private void receiveLoop(Consumer<ChatMessage> onMessage, Consumer<Exception> onClosed) {
        Exception reason = null;
        try {
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    reason = new EOFException("Server closed the connection");
                    break;
                }
                ChatMessage m;
                try {
                    m = JsonCodec.decode(line);
                } catch (InvalidMessageException e) {
                    continue; // skip a malformed line; framing is still intact
                }
                if (!receiveDownload(m)) {
                    onMessage.accept(m);
                }
            }
        } catch (IOException e) {
            reason = e;
        } catch (RuntimeException e) {
            reason = e;
        } finally {
            closed.set(true);
            closeQuietly(socket);
            failDownloads(reason == null ? new IOException("Client is closed") : reason);
            if (closedNotified.compareAndSet(false, true)) {
                onClosed.accept(reason);
            }
        }
    }

    /** Sends a chat line; rejects content whose encoded form would exceed the protocol line limit. */
    public void send(String content) throws IOException {
        String encoded = JsonCodec.encode(ChatMessage.message(content));
        if (!Protocol.fitsLine(encoded)) {
            throw new IllegalArgumentException("Tin nhắn quá dài");
        }
        writeLine(encoded);
    }

    public void sendTo(String conversationId, String content) throws IOException {
        writeLine(JsonCodec.encode(ChatMessage.messageTo(conversationId, content)));
    }

    public void requestHistory(String conversationId) throws IOException {
        writeLine(JsonCodec.encode(ChatMessage.historyRequest(conversationId)));
    }

    public void createGroup(String name, List<String> members) throws IOException {
        writeLine(JsonCodec.encode(ChatMessage.groupCreate(name, members)));
    }

    /** Sends chunks on the caller's thread; text sends may continue between chunks. */
    public String uploadFile(String conversationId, Path file, String kind) throws IOException {
        long size = Files.size(file);
        if (size > Protocol.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File vượt quá 25 MB");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("File rỗng");
        }
        if (!List.of("image", "video", "audio", "file").contains(kind)) {
            throw new IllegalArgumentException("Loại file không hợp lệ");
        }
        String fileId = UUID.randomUUID().toString();
        String mime = Files.probeContentType(file);
        if (mime == null) mime = "application/octet-stream";
        String name = file.getFileName().toString();
        if (name.length() > 120) name = name.substring(0, 120);
        uploadLock.lock();
        boolean started = false;
        boolean ended = false;
        try (InputStream input = Files.newInputStream(file)) {
            writeLine(JsonCodec.encode(ChatMessage.fileStart(fileId, conversationId, name, mime, size, kind)));
            started = true;
            byte[] buffer = new byte[Protocol.FILE_CHUNK_BYTES];
            long total = 0;
            int index = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > size) {
                    // An invalid chunk also tells the server to abort and remove its partial upload.
                    writeLine(JsonCodec.encode(ChatMessage.fileChunk(fileId, -1, "")));
                    throw new IOException("File đã thay đổi trong lúc gửi");
                }
                String data = Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, count));
                writeLine(JsonCodec.encode(ChatMessage.fileChunk(fileId, index++, data)));
            }
            writeLine(JsonCodec.encode(ChatMessage.fileEnd(fileId)));
            ended = true;
            if (total != size) throw new IOException("File đã thay đổi trong lúc gửi");
            return fileId;
        } catch (IOException | RuntimeException error) {
            if (started && !ended) {
                try {
                    writeLine(JsonCodec.encode(ChatMessage.fileChunk(fileId, -1, "")));
                } catch (IOException | RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        } finally {
            uploadLock.unlock();
        }
    }

    /** The receiver consumes download frames internally and completes the future on file_end. */
    public CompletableFuture<Path> download(String fileId, Path target) {
        if (!ConversationIds.isValidFileId(fileId)) {
            return CompletableFuture.failedFuture(new IOException("FILE_INVALID"));
        }
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("Client is closed"));
        Download transfer;
        try {
            requireSocket();
            transfer = new Download(target);
        } catch (IOException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        Download existing = downloads.putIfAbsent(fileId, transfer);
        if (existing != null) {
            transfer.fail(new IOException("File đang được tải"));
            return transfer.future;
        }
        transfer.future.whenComplete((path, error) -> {
            if (transfer.future.isCancelled() && downloads.remove(fileId, transfer)) {
                transfer.fail(new IOException("Download cancelled"));
            }
        });
        try {
            writeLine(JsonCodec.encode(ChatMessage.fileGet(fileId)));
        } catch (IOException | RuntimeException e) {
            downloads.remove(fileId, transfer);
            transfer.fail(e);
        }
        return transfer.future;
    }

    private boolean receiveDownload(ChatMessage message) {
        String id = message.getFileId();
        Download transfer = id == null ? null : downloads.get(id);
        MessageType type = message.messageType().orElse(null);
        if (transfer == null || (type != MessageType.FILE_CHUNK && type != MessageType.FILE_END
                && type != MessageType.ERROR)) return false;
        try {
            if (type == MessageType.ERROR) {
                throw new IOException(message.getContent());
            }
            if (type == MessageType.FILE_END) {
                downloads.remove(id, transfer);
                transfer.complete();
            } else {
                transfer.write(message);
            }
        } catch (IOException | RuntimeException e) {
            downloads.remove(id, transfer);
            transfer.fail(e);
        }
        return true;
    }

    private void failDownloads(Exception reason) {
        downloads.forEach((id, transfer) -> {
            if (downloads.remove(id, transfer)) transfer.fail(reason);
        });
    }

    private static final class Download {
        private final Path target;
        private final OutputStream stream;
        private final CompletableFuture<Path> future = new CompletableFuture<>();
        private long total;
        private int nextIndex;
        private boolean finished;

        Download(Path target) throws IOException {
            this.target = Objects.requireNonNull(target, "target");
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            stream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        synchronized void write(ChatMessage message) throws IOException {
            if (finished) throw new IOException("Download closed");
            if (message.getIndex() == null || message.getIndex() != nextIndex || message.getData() == null) {
                throw new IOException("FILE_INVALID: chunk index");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(message.getData());
            } catch (IllegalArgumentException e) {
                throw new IOException("FILE_INVALID: base64", e);
            }
            if (bytes.length == 0 || bytes.length > Protocol.FILE_CHUNK_BYTES
                    || total + bytes.length > Protocol.MAX_FILE_BYTES) {
                throw new IOException("FILE_INVALID: chunk size");
            }
            stream.write(bytes);
            total += bytes.length;
            nextIndex++;
        }

        synchronized void complete() throws IOException {
            if (finished) return;
            stream.close();
            finished = true;
            future.complete(target);
        }

        synchronized void fail(Throwable reason) {
            if (finished) return;
            finished = true;
            try { stream.close(); } catch (IOException e) { reason.addSuppressed(e); }
            try { Files.deleteIfExists(target); } catch (IOException e) { reason.addSuppressed(e); }
            future.completeExceptionally(reason);
        }
    }

    private void writeLine(String json) throws IOException {
        if (!Protocol.fitsLine(json)) {
            throw new IllegalArgumentException("Tin nhắn quá dài");
        }
        requireSocket();
        writeLock.lock();
        try {
            if (closed.get()) {
                throw new IOException("Client is closed");
            }
            out.write(Protocol.frame(json));
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    /** Best-effort {@code disconnect} followed by closing the socket; safe to call repeatedly. */
    @Override
    public void close() {
        SSLSocket s;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            s = socket;
        }
        failDownloads(new IOException("Client is closed"));
        if (s == null) {
            return;
        }
        boolean locked = false;
        try {
            // Do not wait forever behind a writer stuck on a full socket buffer.
            locked = writeLock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
            if (locked) {
                out.write(Protocol.frame(JsonCodec.encode(ChatMessage.disconnect())));
                out.flush();
            }
        } catch (IOException ignored) {
            // Best effort: the peer may already be gone.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked) {
                writeLock.unlock();
            }
            closeQuietly(s);
        }
        Thread t = receiveThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private SSLSocket requireSocket() {
        SSLSocket s = socket;
        if (s == null) {
            throw new IllegalStateException("Not connected");
        }
        return s;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}
