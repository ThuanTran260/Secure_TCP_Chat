package com.example.securechat.gui;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.image.ImageView;
import org.bytedeco.javacpp.Pointer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class VideoPlayerTest {
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "test-io"); t.setDaemon(true); return t; });
    @TempDir static Path dir;
    private static Path av2, av1, video2, audio2;
    private VideoPlayer player;

    @BeforeAll static void setUp() throws Exception {
        try { Platform.startup(() -> {}); } catch (IllegalStateException alreadyStarted) { }
        av2 = MediaFixtures.mp4(dir, true, true, 2);
        av1 = MediaFixtures.mp4(dir, true, true, 1);
        video2 = MediaFixtures.mp4(dir, true, false, 2);
        audio2 = MediaFixtures.mp4(dir, false, true, 2);
    }

    @AfterEach void tearDown() throws InterruptedException {
        if (player != null) { VideoPlayer p = player; runFx(p::dispose); }
        // The grabber is released off the FX thread; Windows cannot delete the temp clips until then.
        waitFor(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && "media-release".equals(t.getName())), 5_000);
    }

    @Test @Timeout(20) void opensPosterAndDuration() throws Exception {
        player = open(av2, 320, 320);
        assertTrue(fx(player::hasVideo));
        ImageView view = fx(player::view);
        assertEquals(160, view.getFitWidth(), 0.5); assertEquals(120, view.getFitHeight(), 0.5);
        long duration = fx(player::durationMicros);
        assertTrue(duration >= 1_800_000 && duration <= 2_200_000, "duration " + duration);
        assertNotNull(view.getImage()); assertTrue(view.getImage().getWidth() > 0);
    }

    /** Extra (not in plan): proves the grabber really scales down to the bound. */
    @Test @Timeout(20) void posterScaledDownInsideBound() throws Exception {
        player = open(av2, 80, 80);
        ImageView view = fx(player::view);
        assertEquals(80, view.getFitWidth(), 0.5); assertEquals(60, view.getFitHeight(), 0.5);
        assertEquals(80, view.getImage().getWidth(), 0.5); assertEquals(60, view.getImage().getHeight(), 0.5);
    }

    @Test @Timeout(20) void playAdvancesPositionThenPause() throws Exception {
        player = open(av2, 320, 320);
        runFx(player::play);
        waitFor(() -> position() > 500_000, 5_000);
        runFx(player::pause);
        Thread.sleep(100);
        long paused = position(); Thread.sleep(300);
        assertEquals(paused, position(), 100_000);
        assertFalse(fx(() -> player.playingProperty().get()));
    }

    /** Extra (not in plan): a portrait phone video must be turned upright and occupy the swapped size. */
    @Test @Timeout(20) void phonePortraitVideoIsRotatedClockwise() throws Exception {
        player = open(MediaFixtures.rotatedMp4(dir), 320, 320);
        ImageView view = fx(player::view);
        assertEquals(90, view.getRotate(), 0.5);
        Bounds shown = fx(() -> new Group(view).getLayoutBounds());
        assertEquals(120, shown.getWidth(), 0.5); assertEquals(160, shown.getHeight(), 0.5);
    }

    /** Extra (not in plan): embedded cover art is not a video track. */
    @Test @Timeout(20) void mp3WithCoverArtIsAudioOnly() throws Exception {
        player = open(MediaFixtures.mp3WithCover(dir, 2), 320, 320);
        assertFalse(fx(player::hasVideo));
        assertNull(fx(player::view));
        runFx(() -> player.seek(1_000_000));
        runFx(player::play);
        waitFor(() -> position() > 1_300_000, 5_000);
    }

    /** Extra (not in plan): decoded frames are native memory and must be freed during playback, not left to the GC. */
    @Test @Timeout(20) void playbackReleasesDecodedFrames() throws Exception {
        player = open(video2, 320, 320);
        long before = Pointer.totalBytes();
        runFx(player::play);
        waitFor(() -> position() >= 1_900_000 && !fx(() -> player.playingProperty().get()), 8_000);
        long kept = Pointer.totalBytes() - before; // 50 frames of 160x120 BGRA are about 3.8 MB
        assertTrue(kept < 1_000_000, "native bytes still held: " + kept);
    }

    @Test @Timeout(20) void seekWhilePausedUpdatesFrameWithoutPlaying() throws Exception {
        player = open(av2, 320, 320);
        int posterPixel = centerPixel(player);
        runFx(() -> player.seek(1_500_000));
        waitFor(() -> position() >= 1_400_000, 2_000);
        waitFor(() -> centerPixel(player) != posterPixel, 2_000); // fixture colour changes each second
        Thread.sleep(300);
        assertFalse(fx(() -> player.playingProperty().get()));
        assertTrue(position() >= 1_400_000 && position() < 1_700_000, "position " + position());
    }

    @Test @Timeout(20) void playAfterEndRestartsFromZero() throws Exception {
        player = open(av1, 320, 320);
        runFx(player::play);
        waitFor(() -> fx(() -> player.playingProperty().get()), 2_000);
        waitFor(() -> !fx(() -> player.playingProperty().get()), 8_000);
        assertTrue(position() >= 900_000, "ended at " + position());
        runFx(player::play);
        Thread.sleep(150);
        assertTrue(position() < 500_000, "restart position " + position());
        assertTrue(fx(() -> player.playingProperty().get()));
    }

    @Test @Timeout(20) void videoWithoutAudioPlaysOnWallClock() throws Exception {
        player = open(video2, 320, 320);
        assertTrue(fx(player::hasVideo));
        runFx(player::play);
        waitFor(() -> position() > 500_000, 2_000);
    }

    @Test @Timeout(20) void audioOnlyHasNoView() throws Exception {
        player = open(audio2, 320, 320);
        assertFalse(fx(player::hasVideo));
        assertNull(fx(player::view));
        runFx(player::play);
        waitFor(() -> position() > 500_000, 5_000);
    }

    @Test @Timeout(20) void openGarbageFails() throws Exception {
        CompletableFuture<VideoPlayer> future = VideoPlayer.open(MediaFixtures.garbage(dir), 320, 320, IO);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertNotNull(failure.getCause());
    }

    @Test @Timeout(20) void disposeStopsDecodeThread() throws Exception {
        VideoPlayer p = open(av2, 320, 320);
        runFx(p::play);
        waitFor(() -> position(p) > 100_000, 5_000);
        runFx(p::dispose);
        waitFor(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && "media-decode".equals(t.getName())), 2_000);
        assertDoesNotThrow(() -> runFx(p::dispose));
    }

    @Test @Timeout(20) void idleBubblesHoldNoDecoder() throws Exception {
        List<VideoPlayer> players = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) players.add(open(av2, 320, 320));
            for (VideoPlayer p : players) assertFalse(p.decoderOpen());
        } finally { players.forEach(p -> runFx(p::dispose)); }
    }

    @Test @Timeout(20) void playReopensAfterRelease() throws Exception {
        player = open(av2, 320, 320);
        runFx(player::play);
        waitFor(() -> position() > 300_000, 5_000);
        runFx(player::pause);
        runFx(() -> player.seek(0));
        runFx(player::play);
        waitFor(() -> position() > 300_000, 5_000);
    }

    @Test @Timeout(20) void suspendReleasesDecoderAndPlayResumesThere() throws Exception {
        player = open(av2, 320, 320);
        runFx(player::play);
        waitFor(() -> position() > 800_000, 5_000);
        runFx(player::suspend);
        waitFor(() -> !player.decoderOpen(), 2_000);
        assertFalse(fx(() -> player.playingProperty().get()));
        long paused = position();
        runFx(player::play);
        Thread.sleep(150);
        assertTrue(position() >= paused - 100_000, "resumed at " + position() + " after pausing at " + paused);
        waitFor(() -> position() > paused + 200_000, 5_000);
    }

    @Test @Timeout(20) void endReleasesDecoder() throws Exception {
        player = open(av1, 320, 320);
        runFx(player::play);
        waitFor(() -> position() >= 900_000 && !fx(() -> player.playingProperty().get()), 8_000);
        assertFalse(player.decoderOpen());
    }

    @Test @Timeout(20) void reopenFailureReportsError() throws Exception {
        Path copy = Files.copy(av2, dir.resolve("vanishing.mp4"));
        player = open(copy, 320, 320);
        Files.delete(copy); // possible only because the idle player holds no decoder
        AtomicReference<Throwable> reported = new AtomicReference<>();
        runFx(() -> { player.setOnError(reported::set); player.play(); });
        waitFor(() -> reported.get() != null, 5_000);
        assertFalse(fx(() -> player.playingProperty().get()));
    }

    private static VideoPlayer open(Path file, int w, int h) throws Exception { return VideoPlayer.open(file, w, h, IO).get(10, TimeUnit.SECONDS); }
    private static int centerPixel(VideoPlayer p) { return fx(() -> p.view().getImage().getPixelReader().getArgb(80, 60)); }
    private long position() { return position(player); }
    private static long position(VideoPlayer p) { return fx(() -> p.positionMicrosProperty().get()); }

    static void runFx(Runnable action) { fx(() -> { action.run(); return null; }); }
    static <T> T fx(Supplier<T> action) {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> { try { result.set(action.get()); } catch (Throwable e) { error.set(e); } finally { done.countDown(); } });
        try { if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("FX thread timed out"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }
    static void waitFor(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("condition not met within " + millis + " ms");
            Thread.sleep(20);
        }
    }
}
