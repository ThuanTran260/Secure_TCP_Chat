package com.example.securechat.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe registry of connected sessions and of the usernames currently logged in. */
public final class ClientRegistry {
    private final ConcurrentHashMap<Integer, ClientSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> usernames = new ConcurrentHashMap<>();

    public void add(ClientSession s) {
        sessions.put(s.id(), s);
    }

    /** Returns the removed session, or null if it was already removed. */
    public ClientSession remove(int id) {
        return sessions.remove(id);
    }

    /** Point-in-time copy of the authenticated sessions, safe to iterate while others change. */
    public List<ClientSession> authenticatedSnapshot() {
        List<ClientSession> result = new ArrayList<>();
        for (ClientSession s : sessions.values()) {
            if (s.isAuthenticated()) {
                result.add(s);
            }
        }
        return result;
    }

    /** Atomically claims the username for the session id; false if somebody else holds it. */
    public boolean tryClaimUsername(String username, int id) {
        return usernames.putIfAbsent(username, id) == null;
    }

    /** Releases the username only if it is still held by {@code id}. */
    public void releaseUsername(String username, int id) {
        usernames.remove(username, id);
    }

    public int size() {
        return sessions.size();
    }

    public Optional<ClientSession> sessionOf(String username) {
        Integer id = usernames.get(username);
        ClientSession session = id == null ? null : sessions.get(id);
        return session != null && session.isAuthenticated() ? Optional.of(session) : Optional.empty();
    }
}
