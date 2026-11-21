package com.example.securechat.gui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import java.util.Locale;

public final class Avatars {
    private static final String[] COLORS = {"#0068FF", "#7655D9", "#00A396", "#E88D37", "#D75B83", "#3E91C9"};
    private Avatars() {}
    public static StackPane circle(String name, double size) {
        String value = name == null || name.isBlank() ? "?" : name.strip();
        Circle circle = new Circle(size / 2, Color.web(COLORS[Math.floorMod(value.hashCode(), COLORS.length)]));
        String initial = new String(Character.toChars(value.codePointAt(0))).toUpperCase(Locale.ROOT);
        Label letter = new Label(initial); letter.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: " + size * .42 + "px;");
        StackPane avatar = new StackPane(circle, letter); avatar.setAlignment(Pos.CENTER);
        avatar.setMinSize(size, size); avatar.setMaxSize(size, size);
        return avatar;
    }
}
