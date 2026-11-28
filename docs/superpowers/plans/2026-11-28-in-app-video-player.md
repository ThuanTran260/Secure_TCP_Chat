# In-app Video Player (FFmpeg) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Play video and audio attachments inside the chat bubble with a bundled FFmpeg decoder, never an external player.

**Architecture:** JavaCV's `FFmpegFrameGrabber` decodes on a background thread per playing item. Frames are written into a JavaFX `WritableImage` (BGRA) and PCM audio goes to a `javax.sound.sampled.SourceDataLine`. The audio line's position is the master clock. Pure timing and size math lives in `MediaTiming`, so it can be unit-tested without native code. `MediaBubbles` swaps JavaFX `MediaPlayer`/`MediaView` for `VideoPlayer`.

**Tech Stack:** Java 17, JavaFX 21.0.5, JavaCV 1.5.11, FFmpeg 7.1 (bytedeco, LGPL, windows-x86_64), JUnit 5, Maven shade.

**Spec:** `docs/superpowers/specs/2026-11-28-in-app-video-player-design.md`

## Global Constraints

- Windows x86_64 natives only: `org.bytedeco:javacv:1.5.11` with exclusion `*:*`; `org.bytedeco:javacpp:1.5.11` plain + classifier `windows-x86_64`; `org.bytedeco:ffmpeg:7.1-1.5.11` plain + classifier `windows-x86_64`. No other bytedeco modules (no opencv etc.).
- Never open an external player (no `java.awt.Desktop`, no VLC, no browser). Never download codecs at runtime.
- Do not change the TCP protocol or the server. Do not touch `config/users.properties`.
- The bubble video frame fits within 320×320 with the aspect ratio kept (16:9 landscape → 320×180; portrait up to 320 px high).
- Time label text: `m:ss` for the current position and `m:ss / m:ss` while playing (matches the current `format(Duration)` output, e.g. `0:12 / 3:22`).
- Error copy: `Không phát được video này` / `Không phát được âm thanh này`, followed by the existing `fileCard(box, message, "Lưu về máy")`.
- Only one item plays at a time across the open conversation.
- The working tree has uncommitted emoji and video-fallback work the user has not committed yet. **Do not commit.** Each task ends with a `git diff --stat` checkpoint instead.
- `mvn -q test` must stay green (150 tests at plan time, minus the removed `MediaBubblesTest`, plus the new ones).

## Review Focus

1. **Switching conversation while a video plays:** the decode thread stops and the audio line closes. There must be no sound afterwards and no leaked `media-decode` threads. Owner: Task 3 (`disposeStopsDecodeThread`) and Task 4.
2. **Seeking while paused:** the frame at the new position shows immediately and playback does not start. Owner: Task 3 (`seekWhilePausedUpdatesFrameWithoutPlaying`).
3. **Pressing ▶ after the end:** playback restarts from 0 instead of doing nothing. Owner: Task 3 (`playAfterEndRestartsFromZero`).
4. **Corrupt or truncated file, or a non-media file with .mp4 extension:** the open fails cleanly into the error card. No exception escapes to the FX thread and no thread hangs. Owner: Task 3 (`openGarbageFails`).
5. **Audio-only file and a video with no audio track:** both play. The video-only file runs on the wall clock. Owner: Task 3 (`videoWithoutAudioPlaysOnWallClock`, `audioOnlyHasNoView`).

---

### Task 1: FFmpeg dependencies, shaded natives, background preload

**Files:**
- Modify: `pom.xml` (dependencies)
- Modify: `src/main/java/com/example/securechat/gui/ChatApp.java` (call preload in `start`)
- Create: `src/main/java/com/example/securechat/gui/VideoPlayer.java` (only the `preload` method for now)
- Test: `src/test/java/com/example/securechat/gui/FfmpegAvailabilityTest.java`

**Interfaces:**
- Produces: `VideoPlayer.preload()`, a `public static void` that starts a daemon thread named `ffmpeg-preload` and calls `FFmpegFrameGrabber.tryLoad()`, swallowing and logging any exception. It is safe to call more than once; only the first call loads.

- [ ] **Step 1: Write the failing test** `FfmpegAvailabilityTest.nativesLoad()`: `assertDoesNotThrow(FFmpegFrameGrabber::tryLoad)`.
- [ ] **Step 2: Run it** with `mvn -q test -Dtest=FfmpegAvailabilityTest`. Expected: compile FAIL (`org.bytedeco` missing).
- [ ] **Step 3: Add the five dependencies** from Global Constraints to `pom.xml`. Add `VideoPlayer.preload()` and call it first in `ChatApp.start`.
- [ ] **Step 4: Run** `mvn -q test -Dtest=FfmpegAvailabilityTest`. Expected: PASS.
- [ ] **Step 5: Verify the shaded jar.** Run `mvn -q package -DskipTests`, then `unzip -l target/secure-tcp-chat.jar | grep -c "org/bytedeco/ffmpeg/windows-x86_64/.*\.dll"`. Expected: ≥ 7. The jar must be about 40–45 MB and contain no `org/bytedeco/opencv`.
- [ ] **Step 6: Checkpoint** with `git diff --stat`. Do not commit.

### Task 2: `MediaTiming` (pure timing, size and format helpers)

**Files:**
- Create: `src/main/java/com/example/securechat/gui/MediaTiming.java`
- Test: `src/test/java/com/example/securechat/gui/MediaTimingTest.java`

**Interfaces:**
- Produces (all `public static` on `final class MediaTiming`):
  - `enum FrameAction { SHOW, WAIT, DROP }`
  - `FrameAction decide(long frameMicros, long clockMicros, long dropAfterMicros)`: `WAIT` if `frameMicros > clockMicros`; `DROP` if `clockMicros - frameMicros > dropAfterMicros`; otherwise `SHOW`. Callers pass `DROP_AFTER_MICROS = 100_000`.
  - `int normalizeRotation(double degrees)`: rounds to the nearest of 0/90/180/270 (−90 → 270, 450 → 90, NaN → 0).
  - `record Size(int width, int height)`
  - `Size displaySize(int width, int height, int rotation, int maxWidth, int maxHeight)`: swaps width and height when rotation is 90 or 270, then scales down to fit inside max × max with the aspect ratio kept. It never upscales and returns a minimum of 1×1. Width or height ≤ 0 → `Size(maxWidth, maxHeight)`.
  - `Size decodeSize(Size display, int rotation)`: the pre-rotation size handed to the grabber (swapped back for 90/270).
  - `String format(long micros)`: `m:ss`. Negative values or `Long.MIN_VALUE` → `0:00`.

- [ ] **Step 1: Write failing tests:**
  - `decide(1_000_000, 900_000, 100_000) == WAIT`
  - `decide(1_000_000, 1_050_000, 100_000) == SHOW`
  - `decide(1_000_000, 1_200_001, 100_000) == DROP`
  - `normalizeRotation(-90) == 270`, `normalizeRotation(90.4) == 90`, `normalizeRotation(Double.NaN) == 0`
  - `displaySize(640, 360, 0, 320, 180) == Size(320, 180)`
  - `displaySize(576, 842, 0, 320, 320) == Size(219, 320)`
  - `displaySize(1920, 1080, 90, 320, 320) == Size(180, 320)` and `decodeSize(Size(180, 320), 90) == Size(320, 180)`
  - `displaySize(100, 50, 0, 320, 180) == Size(100, 50)` (no upscaling)
  - `displaySize(0, 0, 0, 320, 180) == Size(320, 180)`
  - `format(202_300_000) == "3:22"`, `format(-5) == "0:00"`, `format(59_999_999) == "0:59"`
- [ ] **Step 2: Run** `mvn -q test -Dtest=MediaTimingTest`. Expected: compile FAIL.
- [ ] **Step 3: Implement `MediaTiming`.** Round the scaled dimensions with `Math.round`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Checkpoint** with `git diff --stat`.

### Task 3: `VideoPlayer` (decode, poster, clock, play, pause, seek, dispose)

**Files:**
- Modify: `src/main/java/com/example/securechat/gui/VideoPlayer.java`
- Test: `src/test/java/com/example/securechat/gui/VideoPlayerTest.java`
- Test helper: `src/test/java/com/example/securechat/gui/MediaFixtures.java`

**Interfaces:**
- Consumes: `MediaTiming.*` (Task 2), `VideoPlayer.preload()` (Task 1).
- Produces on `public final class VideoPlayer`:
  - `static CompletableFuture<VideoPlayer> open(Path file, int maxWidth, int maxHeight, Executor io)`. This runs on `io`: it starts the grabber with pixel format `AV_PIX_FMT_BGRA` and sample format `AV_SAMPLE_FMT_S16`, sets the image size from `MediaTiming.decodeSize(displaySize(...))`, and reads the first video frame as the poster. It then completes, and the instance is only touched on the FX thread after that. On failure it completes exceptionally and the grabber is released.
  - `boolean hasVideo()`; `ImageView view()` (null when `!hasVideo()`; already rotated and sized to `displaySize`)
  - `long durationMicros()`
  - `ReadOnlyLongProperty positionMicrosProperty()` and `ReadOnlyBooleanProperty playingProperty()`, both updated on the FX thread
  - `void play()`, `void pause()`, `void seek(long micros)`, `void dispose()`. All are called from the FX thread and must never block it. `dispose()` is idempotent.
  - `void setOnError(Consumer<Throwable> handler)` is called on the FX thread when decoding fails mid-play.
- Behavior from the spec:
  - Each playing item has one decode thread named `media-decode`, which is a daemon.
  - The master clock is `line.getMicrosecondPosition() + seekBaseMicros`. Without an audio stream it is `System.nanoTime()` elapsed + base.
  - Each video frame goes through `MediaTiming.decide`.
  - Frames are delivered through an `AtomicReference<byte[]>` with at most one pending `Platform.runLater`, writing via `PixelWriter.setPixels(0, 0, w, h, PixelFormat.getByteBgraPreInstance(), buf, 0, w*4)`.
  - Pause stops the thread and calls `line.stop()`. Seek calls `grabber.setTimestamp`, then `line.flush()`, resets the base and pushes the frame at the new position even while paused.
  - At the end, `playing` becomes false and the position stays at the duration. `play()` at the end seeks to 0 first.

- [ ] **Step 1: Write `MediaFixtures`:**
  - `static Path mp4(Path dir, boolean video, boolean audio, int seconds)` writes a file with `FFmpegFrameRecorder`: `mp4` format; video `AV_CODEC_ID_MPEG4`, 160×120, 25 fps, solid colour frames whose colour changes each second; audio `AV_CODEC_ID_AAC`, 44100 Hz, 2 ch, a sine tone. For `video=false`, write `.m4a`.
  - `static Path garbage(Path dir)` writes 4 KB of random bytes named `bad.mp4`.
  - Start the FX toolkit once in `@BeforeAll` with `Platform.startup(() -> {})`, ignoring `IllegalStateException` if it is already started.
- [ ] **Step 2: Write failing tests in `VideoPlayerTest`.** Use `@Timeout(20)` on each and run FX-thread calls through a `runFx` helper that waits on a latch.
  - `opensPosterAndDuration`: 2 s video + audio, `open(file, 320, 320, io)`. Assert `hasVideo()`, view fit size `160×120` (no upscaling), `durationMicros()` within 1.8–2.2 s, poster image non-null with width > 0.
  - `playAdvancesPositionThenPause`: `play()`, wait until position > 500 ms; `pause()`; the position stays within ±100 ms over 300 ms.
  - `seekWhilePausedUpdatesFrameWithoutPlaying`: `seek(1_500_000)`. Position goes to ≥ 1.4 s within 2 s and `playing` stays false.
  - `playAfterEndRestartsFromZero`: 1 s file, play to the end (`playing` false). `play()` again → position < 500 ms shortly after and `playing` true.
  - `videoWithoutAudioPlaysOnWallClock`: `mp4(dir, true, false, 2)`; play; position > 500 ms within 2 s.
  - `audioOnlyHasNoView`: `mp4(dir, false, true, 2)` → `hasVideo()` false, `view()` null, play advances.
  - `openGarbageFails`: `open(garbage)` completes exceptionally within 5 s.
  - `disposeStopsDecodeThread`: play, then `dispose()`. Within 2 s no live thread is named `media-decode` (`Thread.getAllStackTraces()`). Calling `dispose()` twice does not throw.
- [ ] **Step 3: Run** `mvn -q test -Dtest=VideoPlayerTest`. Expected: FAIL (methods missing).
- [ ] **Step 4: Implement `VideoPlayer`** per the Interfaces and Behavior above.
  - Read rotation with `MediaTiming.normalizeRotation(grabber.getDisplayRotation())` and apply it as `view.setRotate(rotation)`.
  - When there is no audio line available (`LineUnavailableException`), fall back to the wall clock and do not fail.
- [ ] **Step 5: Run** the same command. Expected: PASS. Then run it 3 more times. Expected: PASS every time (no flakes).
- [ ] **Step 6: Checkpoint** with `git diff --stat`.

### Task 4: Wire `VideoPlayer` into `MediaBubbles`, drop JavaFX media and the external player, add MOV

**Files:**
- Modify: `src/main/java/com/example/securechat/gui/MediaBubbles.java`
- Modify: `src/main/java/com/example/securechat/gui/AttachmentKinds.java` (`mp4`, `m4v`, `mov` → `video`)
- Modify: `src/main/java/com/example/securechat/gui/MainView.java:181` (filter `new FileChooser.ExtensionFilter("Video (MP4, MOV)", "*.mp4", "*.m4v", "*.mov")`)
- Delete: `src/test/java/com/example/securechat/gui/MediaBubblesTest.java`
- Test: `src/test/java/com/example/securechat/gui/AttachmentKindsTest.java`

**Interfaces:**
- Consumes: `VideoPlayer.open/play/pause/seek/dispose/view/hasVideo/durationMicros/positionMicrosProperty/playingProperty/setOnError`, `MediaTiming.format`.

- [ ] **Step 1: Write the failing test** `AttachmentKindsTest.movIsVideo`: `assertEquals("video", AttachmentKinds.kindFor("clip.MOV"))`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=AttachmentKindsTest`. Expected: FAIL.
- [ ] **Step 3: Update `AttachmentKinds`** and the `MainView` filter.
- [ ] **Step 4: Rewrite `MediaBubbles.player(...)`:**
  - Show `"Đang tải …"`, then `VideoPlayer.open(path, 320, 320, work)`. A 320×320 bound yields 320×180 for 16:9 landscape and keeps portrait at up to 320 px high. Rejoin the FX thread with `Platform.runLater` and check `disposed`; if the bubble was disposed meanwhile, dispose the player.
  - Video: add `view()`. Audio: add the existing `Tin nhắn thoại` title.
  - Keep the same controls (`media-play` ▶/⏸, a `Slider` with max = duration in seconds, the time label). Bind them to `positionMicrosProperty`/`playingProperty`, and seek on slider release or when `valueChanging` turns false (same as today).
  - `List<MediaPlayer> players` becomes `List<VideoPlayer> players`. Add a field `VideoPlayer active`: `play` pauses the other `active` one first.
  - An open failure or `setOnError` → `Không phát được video này` / `Không phát được âm thanh này` + `fileCard(box, message, "Lưu về máy")`.
  - Remove `playbackFailed`'s open button, `openExternally`, `canOpenExternally`, `EXTERNAL_MEDIA` and every `javafx.scene.media` import. Replace `format(Duration)` with `MediaTiming.format`.
  - `dispose()` disposes every `VideoPlayer`.
- [ ] **Step 5: Delete `MediaBubblesTest.java`** (it only covered `canOpenExternally`).
- [ ] **Step 6: Run** `mvn -q test`. Expected: all PASS, and `grep -rn "javafx.scene.media\|java.awt.Desktop" src/main` prints nothing.
- [ ] **Step 7: Checkpoint** with `git diff --stat`.

### Task 5: README note, packaged-app verification

**Files:**
- Modify: `README.md` (a "Video/âm thanh" section: playback uses the bundled FFmpeg 7.1 via JavaCV 1.5.11, LGPL; Windows x86_64 only; the jar is about 42 MB)

- [ ] **Step 1: Add the README section.**
- [ ] **Step 2: Run** `mvn -q package` (tests included). Expected: BUILD SUCCESS.
- [ ] **Step 3: Smoke-test the packaged jar off the FX app.** Run a scratch `main` (in the scratchpad, not in `src`) on the classpath `target/secure-tcp-chat.jar`. It opens `%TEMP%\securechat-cache\*\*videoplayback.mp4` with `FFmpegFrameGrabber` and prints the width, height and a frame count. Expected: `640x360` and more than 0 frames. This proves the natives load from the shaded jar.
- [ ] **Step 4: Manual GUI check** (if the server and accounts are available; otherwise report it as not done):
  - `run-server.bat`, plus two `run-gui.bat` windows (rebuild first: delete `target\secure-tcp-chat.jar` or run `mvn -q package -DskipTests`).
  - Send `videoplayback.mp4`: the poster appears; ▶ plays with sound in sync; the seek bar works; switching conversation stops the sound.
  - Send a portrait video: it is shown upright within 320 px.
  - Record and play a voice message.
  - Play an `.mp3`.
- [ ] **Step 5: Checkpoint** with `git diff --stat`. Report: test counts, jar size, smoke output, manual results.
