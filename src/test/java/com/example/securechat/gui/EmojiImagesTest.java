package com.example.securechat.gui;

import com.example.securechat.gui.EmojiImages.Segment;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EmojiImagesTest {
    private static Segment t(String s) { return new Segment(s, false); }
    private static Segment e(String s) { return new Segment(s, true); }

    @Test void plainTextIsOneSegment() {
        assertEquals(List.of(t("Xin chào bạn")), EmojiImages.split("Xin chào bạn"));
        assertEquals(List.of(), EmojiImages.split("")); assertEquals(List.of(), EmojiImages.split(null));
    }
    @Test void emojiInTheMiddle() { assertEquals(List.of(t("Hi "), e("😀"), t(" there")), EmojiImages.split("Hi 😀 there")); }
    @Test void consecutiveEmoji() { assertEquals(List.of(e("😂"), e("😂"), e("🔥")), EmojiImages.split("😂😂🔥")); }
    @Test void heartWithAndWithoutVariationSelector() {
        assertEquals(List.of(t("I "), e("❤️"), t(" u")), EmojiImages.split("I ❤️ u"));
        assertEquals(List.of(e("❤")), EmojiImages.split("❤"));
        assertEquals("2764.png", EmojiImages.fileName("❤️"));
    }
    @Test void thumbsUpNextToText() { assertEquals(List.of(t("ok"), e("👍"), t("nhé")), EmojiImages.split("ok👍nhé")); }
    @Test void unknownEmojiStaysText() {
        assertEquals(List.of(t("🦄 và "), e("⭐"), t("🫠")), EmojiImages.split("🦄 và ⭐🫠"));
        assertFalse(EmojiImages.isKnown("🦄"));
    }
    @Test void everyPickerEmojiHasABundledPng() throws Exception {
        assertEquals(40, EmojiImages.PICKER.size());
        for (String emoji : EmojiImages.PICKER) {
            assertTrue(EmojiImages.isKnown(emoji), emoji);
            assertEquals(List.of(e(emoji)), EmojiImages.split(emoji), emoji);
            URL url = EmojiImages.resource(emoji); assertNotNull(url, emoji + " -> " + EmojiImages.fileName(emoji));
            try (InputStream in = url.openStream()) {
                byte[] head = in.readNBytes(8);
                assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, head, emoji);
            }
        }
    }
}
