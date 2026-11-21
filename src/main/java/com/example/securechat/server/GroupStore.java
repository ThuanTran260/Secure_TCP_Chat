package com.example.securechat.server;

import com.example.securechat.model.ConversationIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persists group membership as one atomically replaced JSON document. */
public final class GroupStore {
    public record Group(String id, String name, List<String> members) {
        public Group { members = List.copyOf(members); }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final Map<String, Group> groups = new LinkedHashMap<>();

    public GroupStore(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        Files.createDirectories(this.file.getParent());
        if (Files.exists(this.file)) {
            List<Group> stored = JSON.readValue(this.file.toFile(), new TypeReference<List<Group>>() {});
            for (Group group : stored) {
                if (!ConversationIds.isGroup(group.id())) throw new IOException("Invalid stored group id");
                groups.put(group.id(), group);
            }
        }
    }

    public synchronized Group create(String name, List<String> members) throws IOException {
        if (name == null || name.isBlank() || name.length() > 50 || members == null || members.isEmpty()) {
            throw new IllegalArgumentException("Invalid group");
        }
        Group group = new Group(ConversationIds.newGroupId(), name.trim(), members.stream().distinct().sorted().toList());
        groups.put(group.id(), group);
        try {
            Path temporary = Files.createTempFile(file.getParent(), "groups-", ".tmp");
            try {
                JSON.writeValue(temporary.toFile(), List.copyOf(groups.values()));
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) {
            groups.remove(group.id());
            throw e;
        }
        return group;
    }

    public synchronized Optional<Group> find(String id) { return Optional.ofNullable(groups.get(id)); }
    public synchronized List<Group> groupsOf(String username) {
        return groups.values().stream().filter(g -> g.members().contains(username)).toList();
    }
}
