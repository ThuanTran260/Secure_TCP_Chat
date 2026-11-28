package com.example.securechat.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentKindsTest {
    @Test void movIsVideo() {
        assertEquals("video", AttachmentKinds.kindFor("clip.MOV"));
    }

    @Test void recognizesSupportedExtensionsIgnoringCase() {
        assertEquals("video", AttachmentKinds.kindFor("A.MP4"));
        assertEquals("image", AttachmentKinds.kindFor("x.jpeg"));
        assertEquals("audio", AttachmentKinds.kindFor("r.wav"));
        assertEquals("file", AttachmentKinds.kindFor("doc.pdf"));
        assertEquals("file", AttachmentKinds.kindFor("no-extension"));
    }
}
