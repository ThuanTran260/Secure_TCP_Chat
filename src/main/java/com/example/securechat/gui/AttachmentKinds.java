package com.example.securechat.gui;

import java.util.Locale;

public final class AttachmentKinds {
    private AttachmentKinds() {}
    public static String kindFor(String fileName) {
        if (fileName == null) return "file";
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "png", "jpg", "jpeg", "gif", "bmp" -> "image";
            case "mp4", "m4v" -> "video";
            case "wav", "mp3", "m4a" -> "audio";
            default -> "file";
        };
    }
}
