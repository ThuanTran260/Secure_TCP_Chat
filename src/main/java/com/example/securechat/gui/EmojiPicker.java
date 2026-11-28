package com.example.securechat.gui;

import javafx.geometry.Bounds;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.stage.Popup;

public final class EmojiPicker extends Popup {
    public EmojiPicker(TextField input) {
        GridPane grid = new GridPane(); grid.getStyleClass().add("emoji-picker");
        grid.getStylesheets().add(ChatApp.class.getResource("zalo.css").toExternalForm());
        for (int i = 0; i < EmojiImages.PICKER.size(); i++) {
            String emoji = EmojiImages.PICKER.get(i); Button button = new Button(); button.getStyleClass().add("emoji-button");
            ImageView image = EmojiImages.view(emoji, 24); if (image != null) button.setGraphic(image); else button.setText(emoji);
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
