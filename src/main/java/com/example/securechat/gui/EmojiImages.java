package com.example.securechat.gui;

import javafx.scene.image.*;
import javafx.scene.text.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Bundled Twemoji (CC-BY 4.0) color images for the picker emoji; nothing is loaded from the network. */
public final class EmojiImages {
    public static final List<String> PICKER = List.of("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🙂", "🙃", "😉", "😊", "😍", "🥰", "😘", "😎", "🤩", "🤔", "😴", "😭", "😢", "😮", "😡", "🥳", "😇", "👍", "👎", "👏", "🙌", "🙏", "💪", "👋", "🤝", "❤️", "💙", "💚", "🔥", "⭐", "🎉", "✅", "☕");
    public record Segment(String text, boolean emoji) {}
    private static final Map<String, String> KEYS = new HashMap<>();
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_LENGTH;
    static {
        for (String emoji : PICKER) { KEYS.put(emoji, emoji); KEYS.put(emoji.replace("️", ""), emoji); }
        MAX_LENGTH = KEYS.keySet().stream().mapToInt(String::length).max().orElse(1);
    }
    private EmojiImages() {}

    /** Twemoji file name: lowercase hex code points joined by '-', without U+FE0F. */
    public static String fileName(String emoji) {
        return emoji.codePoints().filter(cp -> cp != 0xFE0F).mapToObj(Integer::toHexString).collect(Collectors.joining("-")) + ".png";
    }
    public static URL resource(String emoji) { return EmojiImages.class.getResource("emoji/" + fileName(emoji)); }
    public static boolean isKnown(String emoji) { return KEYS.containsKey(emoji); }

    /** Splits text into plain runs and known-emoji runs (longest match; a trailing U+FE0F is kept with its emoji). */
    public static List<Segment> split(String text) {
        List<Segment> segments = new ArrayList<>(); if (text == null || text.isEmpty()) return segments;
        StringBuilder plain = new StringBuilder(); int i = 0;
        while (i < text.length()) {
            int match = 0;
            for (int length = Math.min(MAX_LENGTH, text.length() - i); length > 0 && match == 0; length--)
                if (KEYS.containsKey(text.substring(i, i + length))) match = length;
            if (match == 0) { int cp = text.codePointAt(i); plain.appendCodePoint(cp); i += Character.charCount(cp); continue; }
            int end = i + match; if (end < text.length() && text.charAt(end) == '️') end++;
            if (plain.length() > 0) { segments.add(new Segment(plain.toString(), false)); plain.setLength(0); }
            segments.add(new Segment(text.substring(i, end), true)); i = end;
        }
        if (plain.length() > 0) segments.add(new Segment(plain.toString(), false));
        return segments;
    }

    public static Image image(String emoji) {
        String key = KEYS.get(emoji); if (key == null) key = KEYS.get(emoji.replace("️", ""));
        if (key == null) return null;
        return CACHE.computeIfAbsent(key, k -> { URL url = resource(k); return url == null ? null : new Image(url.toExternalForm(), 72, 72, true, true); });
    }
    public static ImageView view(String emoji, double size) {
        Image image = image(emoji); if (image == null) return null;
        ImageView view = new ImageView(image); view.setFitWidth(size); view.setFitHeight(size); view.setPreserveRatio(true); view.setSmooth(true);
        return view;
    }

    /** Text with known emoji shown as color images; unknown emoji stay as text. */
    public static TextFlow flow(String content, double emojiSize, String flowStyleClass, String textStyleClass) {
        TextFlow flow = new TextFlow(); if (flowStyleClass != null) flow.getStyleClass().add(flowStyleClass);
        for (Segment segment : split(content)) {
            ImageView view = segment.emoji() ? view(segment.text(), emojiSize) : null;
            if (view != null) { view.setTranslateY(emojiSize * 0.2); flow.getChildren().add(view); }
            else { Text text = new Text(segment.text()); if (textStyleClass != null) text.getStyleClass().add(textStyleClass); flow.getChildren().add(text); }
        }
        return flow;
    }
}
