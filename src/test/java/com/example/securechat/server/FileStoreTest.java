package com.example.securechat.server;

import com.example.securechat.common.Protocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileStoreTest {
    @TempDir Path dir;
    private FileStore.FileMeta meta(long size, String name) {
        return new FileStore.FileMeta(UUID.randomUUID().toString(), "dm:an:binh", "an", name, "application/octet-stream", size, "file");
    }
    @Test void uploadCompletesOnlyWithExactSizeAndSurvivesReload() throws Exception {
        FileStore store = new FileStore(dir);
        var meta = meta(3, "x.bin");
        var upload = store.begin(meta);
        upload.write(0, new byte[]{1, 2});
        assertThrows(IllegalStateException.class, upload::complete);
        upload.abort();
        upload = store.begin(meta);
        upload.write(0, new byte[]{1, 2, 3});
        assertEquals(meta, upload.complete());
        FileStore reload = new FileStore(dir);
        assertEquals(meta, reload.meta(meta.fileId()).orElseThrow());
        try (var in = reload.open(meta.fileId())) { assertArrayEquals(new byte[]{1, 2, 3}, in.readAllBytes()); }
        assertThrows(IllegalArgumentException.class, () -> store.begin(meta));
    }
    @Test void outOfOrderChunkRejected() throws Exception {
        var upload = new FileStore(dir).begin(meta(3, "x"));
        assertThrows(IllegalStateException.class, () -> upload.write(1, new byte[]{1}));
        upload.abort();
    }
    @Test void overSizeRejected() throws Exception {
        var upload = new FileStore(dir).begin(meta(1, "x"));
        assertThrows(IllegalStateException.class, () -> upload.write(0, new byte[]{1, 2}));
        upload.abort();
    }
    @Test void abortDeletesPart() throws Exception {
        var upload = new FileStore(dir).begin(meta(2, "x"));
        upload.write(0, new byte[]{1});
        upload.abort();
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
    }
    @Test void invalidFileIdRejected() throws Exception {
        FileStore store = new FileStore(dir);
        assertThrows(IllegalArgumentException.class, () -> store.begin(new FileStore.FileMeta("../../oops", "lobby", "an", "x", "x", 1, "file")));
        assertThrows(IllegalArgumentException.class, () -> store.begin(meta(Protocol.MAX_FILE_BYTES + 1L, "x")));
        assertThrows(IllegalArgumentException.class, () -> store.open("../../oops"));
    }
    @Test void fileNameSanitized() throws Exception {
        var upload = new FileStore(dir).begin(meta(1, "..\\..\\evil.txt"));
        upload.write(0, new byte[]{1});
        assertEquals("evil.txt", upload.complete().fileName());
    }
}
