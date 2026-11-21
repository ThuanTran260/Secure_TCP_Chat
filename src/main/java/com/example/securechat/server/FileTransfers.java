package com.example.securechat.server;

import com.example.securechat.common.Protocol;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.ConversationIds;
import com.example.securechat.model.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Handles attachment control messages without blocking a session reader on downloads. */
public final class FileTransfers {
    private static final Set<String> KINDS = Set.of("file", "image", "video", "audio");
    private static final Duration BULK_WAIT = Duration.ofSeconds(30);
    private final ChatServer server;
    private final Map<Integer, FileStore.Upload> uploads = new ConcurrentHashMap<>();
    private final Set<String> downloads = ConcurrentHashMap.newKeySet();
    private final ExecutorService senders;
    private boolean stopped;

    FileTransfers(ChatServer server) {
        this.server = server;
        AtomicInteger counter = new AtomicInteger();
        senders = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "file-sender-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized void onStart(ClientSession session, ChatMessage message) {
        if (stopped || server.registry().sessionOf(session.username()).filter(s -> s.id() == session.id()).isEmpty()) return;
        String to = message.getTo() == null ? ConversationIds.LOBBY : message.getTo();
        if (uploads.containsKey(session.id())) { error(session, "FILE_INVALID", message, to); return; }
        String acl = server.conversationError(session.username(), to);
        if (acl != null) { error(session, acl, message, to); return; }
        if (message.getSize() != null && message.getSize() > Protocol.MAX_FILE_BYTES) {
            error(session, "FILE_TOO_LARGE", message, to); return;
        }
        if (!ConversationIds.isValidFileId(message.getFileId()) || message.getSize() == null
                || message.getSize() <= 0 || !KINDS.contains(message.getKind() == null ? "" : message.getKind())
                || message.getFileName() == null || message.getMime() == null || message.getMime().length() > 128) {
            error(session, "FILE_INVALID", message, to); return;
        }
        FileStore.FileMeta meta = new FileStore.FileMeta(message.getFileId(), to, session.username(),
                FileStore.sanitizeName(message.getFileName()), message.getMime(), message.getSize(), message.getKind());
        // Reject metadata whose server enrichment would make the final attachment announcement invalid.
        ChatMessage announced = attachment(session, meta);
        announced.setHistory(true);
        if (!Protocol.fitsLine(JsonCodec.encode(announced))) { error(session, "FILE_INVALID", message, to); return; }
        try {
            FileStore.Upload upload = server.files().begin(meta);
            FileStore.Upload previous = uploads.putIfAbsent(session.id(), upload);
            if (previous != null) { upload.abort(); error(session, "FILE_INVALID", message, to); }
        } catch (IllegalArgumentException | IOException e) { error(session, "FILE_INVALID", message, to); }
    }

    public void onChunk(ClientSession session, ChatMessage message) {
        FileStore.Upload upload = uploads.get(session.id());
        try {
            if (upload == null || !upload.metadata().fileId().equals(message.getFileId())
                    || message.getIndex() == null || message.getData() == null) throw new IllegalStateException();
            byte[] decoded = Base64.getDecoder().decode(message.getData());
            upload.write(message.getIndex(), decoded);
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            abort(session.id());
            error(session, "FILE_INVALID", message, upload == null ? null : upload.metadata().conversationId());
        }
    }

    public void onEnd(ClientSession session, ChatMessage message) {
        FileStore.Upload upload = uploads.get(session.id());
        try {
            if (upload == null || !upload.metadata().fileId().equals(message.getFileId())) throw new IllegalStateException();
            FileStore.FileMeta meta = upload.complete();
            uploads.remove(session.id(), upload);
            if (!server.publish(attachment(session, meta))) throw new IllegalStateException();
            ChatServer.LOG.info("[INFO] File " + meta.fileId() + " uploaded by client " + session.id() + " (" + meta.size() + " bytes)");
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            abort(session.id());
            error(session, "FILE_INVALID", message, upload == null ? null : upload.metadata().conversationId());
        }
    }

    public void onGet(ClientSession session, ChatMessage message) {
        FileStore.FileMeta meta;
        try {
            meta = server.files().meta(message.getFileId()).orElse(null);
        } catch (IOException e) {
            error(session, "FILE_NOT_FOUND", message, null); return;
        }
        if (meta == null) { error(session, "FILE_NOT_FOUND", message, null); return; }
        if (!server.canAccess(session.username(), meta.conversationId())) {
            error(session, "NOT_MEMBER", message, meta.conversationId()); return;
        }
        String key = session.id() + ":" + meta.fileId();
        if (!downloads.add(key)) { downloadError(session, message, meta.conversationId()); return; }
        try {
            senders.execute(() -> {
                try (InputStream in = server.files().open(meta.fileId())) {
                    byte[] buffer = new byte[Protocol.FILE_CHUNK_BYTES];
                    int index = 0;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        String data = Base64.getEncoder().encodeToString(n == buffer.length ? buffer : Arrays.copyOf(buffer, n));
                        if (!session.enqueueBulk(JsonCodec.encode(ChatMessage.fileChunk(meta.fileId(), index++, data)), BULK_WAIT)) {
                            downloadError(session, message, meta.conversationId());
                            return;
                        }
                    }
                    if (!session.enqueueBulk(JsonCodec.encode(ChatMessage.fileEnd(meta.fileId())), BULK_WAIT)) {
                        downloadError(session, message, meta.conversationId());
                    }
                } catch (IOException e) {
                    downloadError(session, message, meta.conversationId());
                } finally { downloads.remove(key); }
            });
        } catch (RejectedExecutionException e) {
            downloads.remove(key);
            downloadError(session, message, meta.conversationId());
        }
    }

    private ChatMessage attachment(ClientSession session, FileStore.FileMeta meta) {
        ChatMessage result = ChatMessage.chatIn(meta.conversationId(), session.id(), session.username(), meta.fileName(), server.clock().millis());
        result.setKind(meta.kind());
        result.setFileId(meta.fileId());
        result.setFileName(meta.fileName());
        result.setMime(meta.mime());
        result.setSize(meta.size());
        return result;
    }

    private void error(ClientSession session, String code, ChatMessage request, String to) {
        ChatMessage reply = ChatMessage.error(code, request.getFileId(), to);
        String line = JsonCodec.encode(reply);
        if (!Protocol.fitsLine(line)) line = JsonCodec.encode(ChatMessage.error(code));
        if (!session.enqueue(line)) server.removeClient(session.id(), ChatServer.REASON_SEND_QUEUE_FULL);
    }

    private static void downloadError(ClientSession session, ChatMessage request, String to) {
        // Bulk traffic leaves room for this control frame. A download failure must not eject a
        // slow reader, and a closing session will fail pending client downloads via onClosed.
        session.send(ChatMessage.error("FILE_INVALID", request.getFileId(), to));
    }

    private void abort(int id) {
        FileStore.Upload upload = uploads.remove(id);
        if (upload != null) upload.abort();
    }

    public synchronized void sessionClosed(int sessionId) { abort(sessionId); }

    public void shutdown() {
        synchronized (this) {
            stopped = true;
            uploads.keySet().forEach(this::abort);
        }
        senders.shutdownNow();
        try { senders.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
