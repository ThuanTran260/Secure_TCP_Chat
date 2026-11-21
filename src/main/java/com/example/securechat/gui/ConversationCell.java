package com.example.securechat.gui;

import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import java.time.*;
import java.time.format.DateTimeFormatter;

public final class ConversationCell extends ListCell<ChatState.Conversation> {
    private final ChatState state;
    public ConversationCell(ChatState state) { this.state = state; }
    @Override protected void updateItem(ChatState.Conversation conversation, boolean empty) {
        super.updateItem(conversation, empty); setText(null);
        if (empty || conversation == null) { setGraphic(null); return; }
        StackPane avatar = Avatars.circle(conversation.title(), 46);
        if (!conversation.group()) {
            Circle dot = new Circle(5, Color.web(state.isOnline(conversation.title()) ? "#2DC26B" : "#AEB7C2"));
            dot.setStroke(Color.WHITE); dot.setStrokeWidth(2);
            avatar.getChildren().add(dot); StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
        }
        Label title = new Label(conversation.title()); title.getStyleClass().add("conversation-title");
        title.setMaxWidth(175); title.setTextOverrun(OverrunStyle.ELLIPSIS);
        String preview = state.last(conversation.id()).map(entry -> {
            String content = entry.message().getContent();
            String prefix = entry.mine() ? "Bạn: " : conversation.group() ? entry.message().getFrom() + ": " : "";
            return prefix + (content == null ? "Tệp đính kèm" : content.replace('\n', ' '));
        }).orElse(conversation.group() ? "Bắt đầu trò chuyện cùng mọi người" : "Gửi lời chào đầu tiên");
        Label snippet = new Label(preview); snippet.getStyleClass().add("conversation-preview");
        snippet.setMaxWidth(175); snippet.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox labels = new VBox(6, title, snippet); HBox.setHgrow(labels, Priority.ALWAYS);
        String time = state.last(conversation.id()).map(entry -> entry.message().getTimestamp())
                .map(timestamp -> DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))).orElse("");
        Label clock = new Label(time); clock.getStyleClass().add("conversation-clock");
        VBox details = new VBox(8, clock); details.setAlignment(Pos.TOP_RIGHT);
        int unread = state.unread(conversation.id());
        if (unread > 0) { Label badge = new Label(unread > 99 ? "99+" : String.valueOf(unread)); badge.getStyleClass().add("unread-badge"); details.getChildren().add(badge); }
        HBox row = new HBox(12, avatar, labels, details); row.setAlignment(Pos.CENTER_LEFT);
        setGraphic(row);
    }
}
