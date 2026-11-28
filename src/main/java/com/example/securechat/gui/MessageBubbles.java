package com.example.securechat.gui;

import com.example.securechat.model.ChatMessage;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.text.TextFlow;
import java.time.*;
import java.time.format.DateTimeFormatter;

public final class MessageBubbles {
    private MessageBubbles() {}
    public static Node create(ChatState.Entry entry, boolean group, MediaBubbles media) {
        ChatMessage message = entry.message();
        VBox bubble = new VBox(7); bubble.getStyleClass().addAll("message-bubble", entry.mine() ? "message-mine" : "message-other");
        bubble.setMaxWidth(470);
        if (!entry.mine() && group) { Label from = new Label(message.getFrom()); from.getStyleClass().add("sender-name"); bubble.getChildren().add(from); }
        if (message.getKind() == null || "text".equals(message.getKind())) {
            TextFlow text = EmojiImages.flow(message.getContent(), 20, "message-text", "message-text-run"); text.setMaxWidth(430);
            bubble.getChildren().add(text);
        } else bubble.getChildren().add(media.create(message));
        if (message.getTimestamp() != null) {
            Label time = new Label(DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(message.getTimestamp()).atZone(ZoneId.systemDefault())));
            time.getStyleClass().add("message-time"); bubble.getChildren().add(time);
        }
        HBox row = new HBox(8); row.getStyleClass().add("message-row");
        row.setAlignment(entry.mine() ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        if (!entry.mine() && group) row.getChildren().add(Avatars.circle(message.getFrom(), 32));
        row.getChildren().add(bubble);
        return row;
    }
    public static Node daySeparator(LocalDate day) {
        LocalDate today = LocalDate.now();
        String text = day.equals(today) ? "Hôm nay" : day.equals(today.minusDays(1)) ? "Hôm qua" : day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        Label label = new Label(text); label.getStyleClass().add("day-label");
        HBox row = new HBox(label); row.setAlignment(Pos.CENTER); row.getStyleClass().add("day-separator"); return row;
    }
}
