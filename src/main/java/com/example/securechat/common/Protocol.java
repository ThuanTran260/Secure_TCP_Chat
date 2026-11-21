package com.example.securechat.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Wire-level constants and framing helpers shared by server and client.
 * Each message is one JSON document encoded as UTF-8 and terminated by '\n'.
 */
public final class Protocol {

    public static final int DEFAULT_PORT = 5000;

    /** Maximum bytes per line (excluding the trailing '\n'), enforced in both directions. */
    public static final int MAX_LINE_BYTES = 4096;

    public static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    public static final int FILE_CHUNK_BYTES = 2048;
    public static final int HISTORY_LIMIT = 50;

    public static final String[] TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    public static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    public static final int CONNECT_TIMEOUT_MS = 5_000;

    public static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofSeconds(30);

    public static final int DEFAULT_SEND_QUEUE_CAPACITY = 256;

    private Protocol() {
    }

    /** Returns the JSON document followed by '\n', encoded as UTF-8. */
    public static byte[] frame(String json) {
        return (json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** True when the UTF-8 byte length of the JSON document is at most {@link #MAX_LINE_BYTES}. */
    public static boolean fitsLine(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length <= MAX_LINE_BYTES;
    }
}
