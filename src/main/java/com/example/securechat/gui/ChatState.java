package com.example.securechat.gui;

import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.ConversationIds;
import com.example.securechat.model.MessageType;
import java.util.*;

/** UI-independent state; mutations and listeners belong to the UI owner thread. */
public final class ChatState {
    public record Conversation(String id, String title, boolean group, List<String> members) {
        public Conversation { members = List.copyOf(members); }
    }
    public record Entry(ChatMessage message, boolean mine) {}
    private record MessageKey(Long timestamp, String from, String content, String fileId) {}
    private final String me;
    private final Map<String, Conversation> conversations = new LinkedHashMap<>();
    private final Map<String, List<Entry>> messages = new HashMap<>();
    private final Map<String, Set<MessageKey>> seen = new HashMap<>();
    private final Set<String> online = new HashSet<>();
    private final Map<String, Integer> unread = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private String opened = ConversationIds.LOBBY;

    public ChatState(String me) {
        this.me = Objects.requireNonNull(me).trim().toLowerCase(Locale.ROOT);
        conversations.put(ConversationIds.LOBBY,
                new Conversation(ConversationIds.LOBBY, "Phòng chung", true, List.of()));
    }

    public void apply(ChatMessage message) {
        MessageType type = message.messageType().orElse(null);
        if (type == null) return;
        switch (type) {
            case USER_LIST -> {
                List<String> users = message.getMembers() == null ? List.of() : message.getMembers();
                conversations.put(ConversationIds.LOBBY,
                        new Conversation(ConversationIds.LOBBY, "Phòng chung", true, users));
                for (String user : users) addDm(user);
                online.clear();
                if (message.getOnlineUsers() != null) online.addAll(message.getOnlineUsers());
            }
            case PRESENCE -> {
                if (message.getFrom() == null) return;
                addDm(message.getFrom());
                if (Boolean.TRUE.equals(message.getOnline())) online.add(message.getFrom());
                else online.remove(message.getFrom());
            }
            case USER_LEFT -> online.remove(message.getFrom());
            case GROUP_INFO -> {
                if (!ConversationIds.isGroup(message.getTo()) || message.getMembers() == null) return;
                conversations.put(message.getTo(), new Conversation(message.getTo(),
                        message.getGroupName(), true, message.getMembers()));
            }
            case MESSAGE -> {
                String id = message.getTo() == null ? ConversationIds.LOBBY : message.getTo();
                if (!conversations.containsKey(id) && ConversationIds.isDm(id)) {
                    for (String member : ConversationIds.dmMembers(id)) addDm(member);
                }
                MessageKey key = new MessageKey(message.getTimestamp(), message.getFrom(),
                        message.getContent(), message.getFileId());
                if (!seen.computeIfAbsent(id, unused -> new HashSet<>()).add(key)) return;
                boolean mine = me.equals(message.getFrom());
                messages.computeIfAbsent(id, unused -> new ArrayList<>()).add(new Entry(message.copy(), mine));
                messages.get(id).sort(Comparator.comparingLong(e -> timestamp(e.message())));
                if (!mine && !id.equals(opened) && !Boolean.TRUE.equals(message.getHistory())) {
                    unread.merge(id, 1, Integer::sum);
                }
            }
            default -> { return; }
        }
        notifyListeners();
    }

    private void addDm(String user) {
        if (user == null || me.equals(user)) return;
        try {
            String id = ConversationIds.dm(me, user);
            conversations.putIfAbsent(id, new Conversation(id, user, false, ConversationIds.dmMembers(id)));
        } catch (IllegalArgumentException ignored) {
            // Malformed remote state must not break the receiving callback.
        }
    }

    public List<Conversation> conversations() {
        List<Conversation> result = new ArrayList<>(conversations.values());
        result.sort(Comparator.comparing((Conversation c) -> !ConversationIds.LOBBY.equals(c.id()))
                .thenComparing(Comparator.comparingLong((Conversation c) ->
                        last(c.id()).map(e -> timestamp(e.message())).orElse(0L)).reversed())
                .thenComparing(c -> !c.group()).thenComparing(Conversation::title));
        return List.copyOf(result);
    }
    public List<Entry> messages(String id) { return List.copyOf(messages.getOrDefault(id, List.of())); }
    public boolean isOnline(String username) { return online.contains(username); }
    public int unread(String id) { return unread.getOrDefault(id, 0); }
    public void open(String id) { opened = id; unread.remove(id); notifyListeners(); }
    public Optional<Entry> last(String id) {
        List<Entry> entries = messages.get(id);
        return entries == null || entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1));
    }
    public void addListener(Runnable listener) { listeners.add(Objects.requireNonNull(listener)); }
    private void notifyListeners() { List.copyOf(listeners).forEach(Runnable::run); }
    private static long timestamp(ChatMessage message) { return message.getTimestamp() == null ? 0L : message.getTimestamp(); }
}
