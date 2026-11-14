package com.example.securechat.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits failed logins per (ip, username): {@code maxFailures} failures within {@code window}
 * lock that pair for {@code lockout}, counted from the last failure.
 */
public final class LoginThrottle {
    private static final class State {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant lockedUntil;
    }

    private final Clock clock;
    private final int maxFailures;
    private final Duration window;
    private final Duration lockout;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public LoginThrottle(Clock clock, int maxFailures, Duration window, Duration lockout) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
        this.lockout = lockout;
    }

    public static LoginThrottle defaults() {
        return new LoginThrottle(Clock.systemUTC(), 5, Duration.ofMinutes(5), Duration.ofSeconds(60));
    }

    public boolean isLocked(String ip, String username) {
        Instant now = clock.instant();
        boolean[] locked = {false};
        states.computeIfPresent(key(ip, username), (k, s) -> {
            if (s.lockedUntil != null) {
                if (now.isBefore(s.lockedUntil)) {
                    locked[0] = true;
                    return s;
                }
                s.lockedUntil = null;
            }
            prune(s, now);
            return s.failures.isEmpty() ? null : s; // drop idle entries so the map cannot grow forever
        });
        return locked[0];
    }

    public void recordFailure(String ip, String username) {
        Instant now = clock.instant();
        states.compute(key(ip, username), (k, s) -> {
            if (s == null) {
                s = new State();
            }
            if (s.lockedUntil != null && now.isBefore(s.lockedUntil)) {
                return s; // already locked; callers should check isLocked before verifying
            }
            s.lockedUntil = null;
            prune(s, now);
            s.failures.addLast(now);
            if (s.failures.size() >= maxFailures) {
                s.lockedUntil = now.plus(lockout);
                s.failures.clear(); // fresh count once the lock expires
            }
            return s;
        });
    }

    public void recordSuccess(String ip, String username) {
        states.remove(key(ip, username));
    }

    private void prune(State s, Instant now) {
        Instant cutoff = now.minus(window);
        while (!s.failures.isEmpty() && !s.failures.peekFirst().isAfter(cutoff)) {
            s.failures.removeFirst();
        }
    }

    private static String key(String ip, String username) {
        return ip + "|" + UserStore.normalize(username);
    }
}
