package com.example.securechat.gui;

import com.example.securechat.model.ChatMessage;
import javafx.application.Platform;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.securechat.gui.VideoPlayerTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Chat bubbles driven through their real controls, with attachments already present in the media cache. */
class MediaBubblesPlaybackTest {
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "test-io"); t.setDaemon(true); return t; });
    @TempDir static Path dir;
    private static Path video, audio;
    private MediaCache cache;
    private MediaBubbles bubbles;

    @BeforeAll static void setUp() throws Exception {
        try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
        video = MediaFixtures.mp4(dir, true, true, 3);
        audio = MediaFixtures.mp4(dir, false, true, 2);
    }

    @BeforeEach void create() {
        cache = new MediaCache(dir.resolve("cache-" + UUID.randomUUID()));
        bubbles = fx(() -> new MediaBubbles(null, cache, IO, null, error -> { }));
    }

    @AfterEach void dispose() throws Exception {
        runFx(bubbles::dispose);
        waitFor(() -> noThread("media-decode") && noThread("media-release"), 5_000);
    }

    @Test @Timeout(20) void videoBubbleShowsPosterAndControls() throws Exception {
        Node bubble = bubble("video", "clip.mp4", video);
        waitForControls(bubble);
        assertEquals(1, find(bubble, ImageView.class).size());
        assertEquals("▶", playButton(bubble).getText());
        assertEquals(3, fx(() -> find(bubble, Slider.class).get(0).getMax()), 0.3);
        assertTrue(texts(bubble).contains("0:03"), texts(bubble).toString());
    }

    @Test @Timeout(20) void audioBubbleHasTitleAndNoPicture() throws Exception {
        Node bubble = bubble("audio", "voice.m4a", audio);
        waitForControls(bubble);
        assertTrue(find(bubble, ImageView.class).isEmpty());
        assertTrue(texts(bubble).contains("Tin nhắn thoại"), texts(bubble).toString());
    }

    @Test @Timeout(20) void startingSecondItemPausesTheFirst() throws Exception {
        Node first = bubble("video", "one.mp4", video), second = bubble("audio", "two.m4a", audio);
        waitForControls(first); waitForControls(second);
        runFx(() -> playButton(first).fire());
        waitFor(() -> text(first).equals("⏸"), 3_000);
        runFx(() -> playButton(second).fire());
        waitFor(() -> text(second).equals("⏸") && text(first).equals("▶"), 3_000);
        runFx(() -> playButton(second).fire());
        waitFor(() -> text(second).equals("▶"), 3_000);
    }

    @Test @Timeout(20) void disposingStopsPlayback() throws Exception {
        Node bubble = bubble("video", "clip.mp4", video);
        waitForControls(bubble);
        runFx(() -> playButton(bubble).fire());
        waitFor(() -> !noThread("media-decode"), 3_000);
        runFx(bubbles::dispose);
        waitFor(() -> noThread("media-decode"), 2_000);
    }

    @Test @Timeout(20) void unplayableVideoOffersSaving() throws Exception {
        Node bubble = bubble("video", "bad.mp4", MediaFixtures.garbage(dir));
        waitFor(() -> texts(bubble).contains("Không phát được video này"), 8_000);
        assertEquals(List.of("Lưu về máy"), fx(() -> find(bubble, Button.class).stream().map(Button::getText).toList()));
    }

    @Test @Timeout(20) void unplayableAudioOffersSaving() throws Exception {
        Node bubble = bubble("audio", "bad.mp3", MediaFixtures.garbage(dir));
        waitFor(() -> texts(bubble).contains("Không phát được âm thanh này"), 8_000);
        assertEquals(List.of("Lưu về máy"), fx(() -> find(bubble, Button.class).stream().map(Button::getText).toList()));
    }

    @Test @Timeout(20) void draggingOrClickingTheSeekBarSeeksOnce() {
        AtomicInteger seeks = new AtomicInteger();
        Slider slider = fx(() -> { Slider s = new Slider(0, 10, 0); MediaBubbles.seekOnRelease(s, seeks::incrementAndGet); return s; });
        runFx(() -> { // drag the thumb
            slider.fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
            slider.setValueChanging(true); slider.setValue(4); slider.setValueChanging(false);
            slider.fireEvent(mouse(MouseEvent.MOUSE_RELEASED));
        });
        assertEquals(1, seeks.get());
        runFx(() -> { // click on the track
            slider.fireEvent(mouse(MouseEvent.MOUSE_PRESSED)); slider.setValue(7); slider.fireEvent(mouse(MouseEvent.MOUSE_RELEASED));
        });
        assertEquals(2, seeks.get());
    }

    private static MouseEvent mouse(EventType<MouseEvent> type) {
        return new MouseEvent(type, 0, 0, 0, 0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, false, null);
    }

    private Node bubble(String kind, String name, Path content) throws Exception {
        String id = UUID.randomUUID().toString();
        Path cached = cache.pathFor(id, name);
        Files.createDirectories(cached.getParent()); Files.copy(content, cached);
        ChatMessage message = ChatMessage.fileStart(id, "lobby", name, "application/octet-stream", Files.size(cached), kind);
        return fx(() -> bubbles.create(message));
    }

    private static void waitForControls(Node bubble) throws InterruptedException {
        waitFor(() -> fx(() -> playButton(bubble) != null), 8_000);
    }
    private static Button playButton(Node root) {
        return find(root, Button.class).stream().filter(b -> b.getStyleClass().contains("media-play")).findFirst().orElse(null);
    }
    private static String text(Node bubble) { return fx(() -> playButton(bubble).getText()); }
    private static List<String> texts(Node bubble) { return fx(() -> find(bubble, Label.class).stream().map(Label::getText).toList()); }
    private static <T> List<T> find(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof Parent parent && !(root instanceof javafx.scene.control.Control)) parent.getChildrenUnmodifiable().forEach(child -> found.addAll(find(child, type)));
        return found;
    }
    private static boolean noThread(String name) {
        return Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && name.equals(t.getName()));
    }
}
