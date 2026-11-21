package com.example.securechat.server;

import com.example.securechat.common.Protocol;
import java.time.Duration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Tunables of {@link ChatServer}.
 *
 * @param loginTimeout      how long a TLS-connected client may stay unauthenticated
 * @param sendQueueCapacity maximum queued outbound messages per client before it is dropped
 * @param throttle          failed-login limiter shared by all connections
 * @param dataDir           persistent history, group, and attachment directory
 */
public record ServerOptions(Duration loginTimeout, int sendQueueCapacity, LoginThrottle throttle, Path dataDir) {

    public ServerOptions(Duration loginTimeout, int sendQueueCapacity, LoginThrottle throttle) {
        this(loginTimeout, sendQueueCapacity, throttle, temporaryDataDir());
    }

    private static Path temporaryDataDir() {
        try { return Files.createTempDirectory("securechat-data"); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public ServerOptions {
        Objects.requireNonNull(loginTimeout, "loginTimeout");
        Objects.requireNonNull(throttle, "throttle");
        Objects.requireNonNull(dataDir, "dataDir");
        if (loginTimeout.isZero() || loginTimeout.isNegative()) {
            throw new IllegalArgumentException("loginTimeout must be positive");
        }
        if (sendQueueCapacity < 1) {
            throw new IllegalArgumentException("sendQueueCapacity must be at least 1");
        }
    }

    public static ServerOptions defaults() {
        return new ServerOptions(Protocol.DEFAULT_LOGIN_TIMEOUT, Protocol.DEFAULT_SEND_QUEUE_CAPACITY,
                LoginThrottle.defaults(), Path.of("data"));
    }
}
