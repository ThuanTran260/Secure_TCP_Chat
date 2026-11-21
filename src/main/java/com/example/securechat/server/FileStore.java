package com.example.securechat.server;

import com.example.securechat.common.Protocol;
import com.example.securechat.model.ConversationIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** UUID-addressed attachments with exact-length, sequential uploads. */
public final class FileStore {
    public record FileMeta(String fileId, String conversationId, String uploader, String fileName,
                           String mime, long size, String kind) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path dir;

    public FileStore(Path dir) throws IOException {
        this.dir = dir.toAbsolutePath().normalize();
        Files.createDirectories(this.dir);
        // No upload can survive a process restart; remove abandoned temporary data.
        try (var paths = Files.list(this.dir)) {
            for (Path p : paths.filter(p -> p.getFileName().toString().endsWith(".part")).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    public synchronized Upload begin(FileMeta meta) throws IOException {
        path(meta.fileId(), ".part");
        if (meta.size() <= 0 || meta.size() > Protocol.MAX_FILE_BYTES
                || Files.exists(path(meta.fileId(), ".part")) || Files.exists(path(meta.fileId(), ".bin"))
                || Files.exists(path(meta.fileId(), ".json"))) throw new IllegalArgumentException("Invalid upload");
        String name = sanitizeName(meta.fileName());
        FileMeta clean = new FileMeta(meta.fileId(), meta.conversationId(), meta.uploader(), name, meta.mime(), meta.size(), meta.kind());
        return new Upload(clean, Files.newOutputStream(path(meta.fileId(), ".part"), StandardOpenOption.CREATE_NEW));
    }

    public synchronized Optional<FileMeta> meta(String fileId) throws IOException {
        if (!ConversationIds.isValidFileId(fileId)) return Optional.empty();
        Path file = path(fileId, ".json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(path(fileId, ".bin"))) return Optional.empty();
        FileMeta meta = JSON.readValue(file.toFile(), FileMeta.class);
        return fileId.equals(meta.fileId()) ? Optional.of(meta) : Optional.empty();
    }

    public InputStream open(String fileId) throws IOException { return Files.newInputStream(path(fileId, ".bin")); }

    private Path path(String id, String suffix) {
        if (!ConversationIds.isValidFileId(id)) throw new IllegalArgumentException("Invalid file id");
        return dir.resolve(id + suffix);
    }

    static String sanitizeName(String input) {
        String name = input == null ? "file" : input.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank() || name.equals(".") || name.equals("..")) name = "file";
        return name.substring(0, Math.min(120, name.length()));
    }

    public final class Upload {
        private final FileMeta meta;
        private final OutputStream out;
        private long written;
        private int nextIndex;
        private boolean closed;
        private Upload(FileMeta meta, OutputStream out) { this.meta = meta; this.out = out; }
        public FileMeta metadata() { return meta; }

        public synchronized void write(int index, byte[] bytes) throws IOException {
            if (closed || index != nextIndex || bytes == null || bytes.length == 0
                    || bytes.length > Protocol.FILE_CHUNK_BYTES || written + bytes.length > meta.size()) {
                throw new IllegalStateException("Invalid upload chunk");
            }
            out.write(bytes);
            written += bytes.length;
            nextIndex++;
        }

        public synchronized FileMeta complete() throws IOException {
            if (closed || written != meta.size()) throw new IllegalStateException("Incomplete upload");
            out.close();
            try {
                Files.move(path(meta.fileId(), ".part"), path(meta.fileId(), ".bin"));
                JSON.writeValue(path(meta.fileId(), ".json").toFile(), meta);
                closed = true;
                return meta;
            } catch (IOException e) {
                Files.deleteIfExists(path(meta.fileId(), ".bin"));
                Files.deleteIfExists(path(meta.fileId(), ".json"));
                throw e;
            }
        }

        public synchronized void abort() {
            if (closed) return;
            closed = true;
            try { out.close(); } catch (IOException ignored) { }
            try { Files.deleteIfExists(path(meta.fileId(), ".part")); } catch (IOException ignored) { }
        }
    }
}
