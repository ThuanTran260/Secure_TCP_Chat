package com.example.securechat.server;

import com.example.securechat.common.Protocol;
import java.time.Duration;
import java.util.Objects;

/**
 * Tunables of {@link ChatServer}.
 *
 * @param loginTimeout      how long a TLS-connected client may stay unauthenticated
 * @param sendQueueCapacity maximum queued outbound messages per client before it is dropped
 * @param throttle          failed-login limiter shared by all connections
 */
public record ServerOptions(Duration loginTimeout, int sendQueueCapacity, LoginThrottle throttle) {

    public ServerOptions {
        Objects.requireNonNull(loginTimeout, "loginTimeout");
        Objects.requireNonNull(throttle, "throttle");
        if (loginTimeout.isZero() || loginTimeout.isNegative()) {
            throw new IllegalArgumentException("loginTimeout must be positive");
        }
        if (sendQueueCapacity < 1) {
            throw new IllegalArgumentException("sendQueueCapacity must be at least 1");
        }
    }

    public static ServerOptions defaults() {
        return new ServerOptions(Protocol.DEFAULT_LOGIN_TIMEOUT, Protocol.DEFAULT_SEND_QUEUE_CAPACITY,
                LoginThrottle.defaults());
    }
}
