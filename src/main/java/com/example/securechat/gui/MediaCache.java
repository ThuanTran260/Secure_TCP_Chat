package com.example.securechat.gui;

import com.example.securechat.client.ChatClient;
import com.example.securechat.model.ConversationIds;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.*;

/** Coalesces downloads and exposes only complete files, never partial media. */
public final class MediaCache {
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "media-cache"); thread.setDaemon(true); return thread;
    });
    private final Path root;
    private final ConcurrentHashMap<String, CompletableFuture<Path>> downloads = new ConcurrentHashMap<>();

    public MediaCache(Path root) { this.root = Objects.requireNonNull(root).toAbsolutePath().normalize(); }

    public Path pathFor(String fileId, String fileName) {
        if (!ConversationIds.isValidFileId(fileId)) throw new IllegalArgumentException("File không hợp lệ");
        String name = fileName == null ? "attachment" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_");
        if (name.isBlank() || name.equals(".") || name.equals("..")) name = "attachment";
        if (name.length() > 120) name = name.substring(0, 120);
        Path result = root.resolve(fileId + "_" + name).normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("File không hợp lệ");
        return result;
    }

    public CompletableFuture<Path> get(ChatClient client, String fileId, String fileName) {
        Path target = pathFor(fileId, fileName);
        CompletableFuture<Path> result = downloads.computeIfAbsent(fileId, unused ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        Files.createDirectories(root);
                        return Files.isRegularFile(target);
                    } catch (IOException e) { throw new CompletionException(e); }
                }, IO).thenCompose(exists -> {
                    if (exists) return CompletableFuture.completedFuture(target);
                    Path part = target.resolveSibling(target.getFileName() + "." + java.util.UUID.randomUUID() + ".part");
                    return client.download(fileId, part).thenApplyAsync(downloaded -> {
                        try {
                            try { Files.move(downloaded, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                            catch (AtomicMoveNotSupportedException e) { Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING); }
                            return target;
                        } catch (IOException e) { throw new CompletionException(e); }
                    }, IO).whenCompleteAsync((path, error) -> {
                        if (error != null) try { Files.deleteIfExists(part); } catch (IOException ignored) { }
                    }, IO);
                }));
        result.whenComplete((path, error) -> { if (error != null) downloads.remove(fileId, result); });
        return result;
    }
}
