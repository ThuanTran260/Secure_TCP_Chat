package com.example.securechat.gui;

import javafx.geometry.Bounds;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Popup;

public final class EmojiPicker extends Popup {
    public EmojiPicker(TextField input) {
        GridPane grid = new GridPane(); grid.getStyleClass().add("emoji-picker");
        grid.getStylesheets().add(ChatApp.class.getResource("zalo.css").toExternalForm());
        String[] emojis = {"😀", "😃", "😄", "😁", "😆", "😅", "😂", "🙂", "🙃", "😉", "😊", "😍", "🥰", "😘", "😎", "🤩", "🤔", "😴", "😭", "😢", "😮", "😡", "🥳", "😇", "👍", "👎", "👏", "🙌", "🙏", "💪", "👋", "🤝", "❤️", "💙", "💚", "🔥", "⭐", "🎉", "✅", "☕"};
        for (int i = 0; i < emojis.length; i++) {
            String emoji = emojis[i]; Button button = new Button(emoji); button.getStyleClass().add("emoji-button");
            button.setOnAction(event -> { input.insertText(input.getCaretPosition(), emoji); hide(); input.requestFocus(); });
            grid.add(button, i % 8, i / 8);
        }
        getContent().add(grid); setAutoHide(true); setHideOnEscape(true);
    }
    public void showAbove(Control anchor) {
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) show(anchor, bounds.getMinX(), bounds.getMinY() - 240);
    }
}
