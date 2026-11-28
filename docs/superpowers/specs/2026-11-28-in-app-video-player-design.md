# Trình phát video trong khung chat (FFmpeg) — Design

**Ngày:** 2026-11-28
**Nền tảng:** JavaFX chat hiện tại (`docs/superpowers/specs/2026-11-21-zalo-style-chat-design.md`), tiêu chí **Video: "xem ngay trong khung chat"**.

## Mục đích và lý do

Video gửi trong chat phải phát **ngay trong bong bóng tin nhắn**: hiện khung hình đầu làm ảnh xem trước, có nút ▶/⏸, thanh tua và thời gian. Không được mở trình phát của Windows hay phần mềm bên thứ ba (VLC…), vì tiêu chí chấm yêu cầu xem trong khung chat.

Hiện tại `MediaView` của JavaFX không phát được **bất kỳ MP4 nào** trên máy phát triển (Windows 11 build 26300 / 26H2). Log debug báo `dshowwrapper: Failed to decode stream → ERROR_MEDIA_INVALID`, cả với track H.264 lẫn AAC. JavaFX 21.0.5, 24.0.1, 25.0.1 và 26.0.1 đều lỗi y hệt, vì JavaFX giải mã H.264/AAC qua bộ giải mã DirectShow của Windows. Nút "▶ Mở bằng trình phát" vừa thêm (mở bằng ứng dụng mặc định của Windows) bị người dùng **từ chối**.

Probe JavaCV 1.5.11 / FFmpeg 7.1 trên chính máy này đã giải mã thành công cả hai file thử: H.264 Main + AAC-LC 640×360 (YouTube) và H.264 High + HE-AACv2 576×842 (TikTok). Lần đầu nạp thư viện native mất khoảng 7 giây, các lần sau nhanh.

## Goals / Non-goals / Anti-goals

- **Goals:**
  - Video MP4/M4V/MOV phát trong bong bóng chat trên Windows 64-bit, kể cả máy có DirectShow hỏng.
  - Có ảnh xem trước (khung hình đầu), ▶/⏸, tua, thời gian `0:12 / 3:22`, và tiếng đồng bộ với hình.
  - Thoát hội thoại hoặc đóng cửa sổ thì giải phóng hết tài nguyên native.
- **Non-goals:**
  - macOS/Linux (chỉ đóng gói native Windows x86_64).
  - Phóng to video ra cửa sổ riêng hay toàn màn hình.
  - Chỉnh âm lượng, tốc độ phát, phụ đề.
  - Nén hay chuyển mã video phía người gửi.
  - Sửa `run-gui.bat` (việc riêng).
- **Anti-goals:**
  - Mở bất kỳ trình phát ngoài nào (Windows, VLC, trình duyệt).
  - Tải codec từ mạng lúc chạy.
  - Làm hỏng tin nhắn thoại WAV, ảnh, file và 150 test hiện có.
  - Đổi giao thức TCP hay server.

## Ràng buộc

- Java 17 target, JavaFX 21.0.5, Maven, jar shade (`target/secure-tcp-chat.jar`) chạy bằng `run-gui.bat` với `--enable-native-access=ALL-UNNAMED`.
- Jar tăng thêm khoảng 29 MB (ffmpeg windows-x86_64 25.5 MB, javacpp windows 2 MB, phần Java khoảng 1.2 MB), tức từ khoảng 13 MB lên khoảng 42 MB. Người dùng đã chấp nhận vì chỉ cần Windows.
- `javacv` kéo theo rất nhiều module transitive (opencv, flycapture, librealsense…). Phải loại hết, chỉ giữ `javacpp` + `ffmpeg` (bản Java và bản `windows-x86_64`).
- FFmpeg của bytedeco là bản LGPL. Ghi chú giấy phép vào README.

## Approaches

1. **FFmpeg (JavaCV) cho mọi video — CHỌN.** Video luôn giải mã bằng FFmpeg đóng gói trong jar. Hình được vẽ lên `ImageView`, tiếng phát qua `javax.sound.sampled`.
   - Ưu: một đường code duy nhất, hành vi giống nhau mọi máy, phát được cả HEVC/VP9 từ điện thoại.
   - Đánh đổi: jar lớn hơn khoảng 29 MB và phải tự viết đồng bộ hình/tiếng.
2. **JavaFX `MediaView` trước, lỗi thì chuyển sang FFmpeg.** Máy tốt dùng giải mã phần cứng của JavaFX.
   - Đánh đổi: hai đường code, giao diện và hành vi khác nhau giữa các máy, khó test, mà jar vẫn phải mang FFmpeg. Bị loại.
3. **Đã loại:**
   - Mở trình phát ngoài, hoặc dùng vlcj: cần cài VLC, người dùng từ chối.
   - `WebView` với `<video>`: dùng chung backend GStreamer/DirectShow nên lỗi y hệt.
   - Chuyển mã sang FLV/VP6 mà JavaFX tự giải mã: không có encoder VP6.

## Thiết kế

**Phụ thuộc (`pom.xml`):**
- `org.bytedeco:javacv:1.5.11`, exclusion `*:*`.
- `org.bytedeco:javacpp:1.5.11`, cả bản thường và classifier `windows-x86_64`.
- `org.bytedeco:ffmpeg:7.1-1.5.11`, cả bản thường và classifier `windows-x86_64`.
- Kiểm tra jar shade còn chứa native (`org/bytedeco/ffmpeg/windows-x86_64/*.dll`) và vẫn chạy được bằng `java -jar`.

**`VideoPlayer` (class mới, package `gui`):** mỗi bong bóng video có một instance, cung cấp node hiển thị và các lệnh `play()`, `pause()`, `seek(Duration)`, `dispose()`.
- **Mở file:** `FFmpegFrameGrabber` mở file đã cache, trên thread nền.
  - Ảnh ra định dạng BGRA, thu nhỏ trong FFmpeg về khung tối đa 320×180, giữ tỉ lệ. Video dọc (TikTok) có thể cao hơn, nhưng tối đa khoảng 320 px.
  - Tiếng ra PCM 16-bit theo sample rate và số kênh gốc.
- **Ảnh xem trước:** mở file xong thì lấy ngay khung hình đầu, hiện vào `ImageView` và đặt thời lượng từ `getLengthInTime()`.
- **Phát:**
  - Một thread giải mã cho mỗi video đang phát. Mẫu tiếng ghi vào `SourceDataLine`.
  - **Đồng hồ chính là vị trí phát của audio line** (`getMicrosecondPosition()` cộng mốc của lần tua gần nhất). Video không có tiếng thì dùng đồng hồ thật.
  - Một khung hình được hiện khi timestamp của nó ≤ đồng hồ. Khung trễ quá nhiều thì bỏ, khung sớm thì chờ.
  - Đẩy khung lên FX thread bằng cách gộp: chỉ giữ khung mới nhất (`AtomicReference`) và có tối đa một `Platform.runLater` đang chờ, để không làm ngập FX thread.
- **Dừng và tua:**
  - Pause: dừng thread giải mã và `line.stop()`. Play: tiếp tục.
  - Tua: `grabber.setTimestamp(...)` trên thread giải mã, `line.flush()`, đặt lại mốc đồng hồ, và hiện ngay khung tại vị trí mới kể cả khi đang pause.
  - Hết video: về trạng thái ▶. Bấm ▶ lại thì phát từ đầu.
- **Chỉ một video phát tại một thời điểm:** bấm ▶ video khác thì video đang phát tự pause.
- **Giải phóng:** `dispose()` dừng thread, `grabber.release()` và đóng line. `MediaBubbles.dispose()` (gọi khi đổi hội thoại hoặc đóng cửa sổ) dispose mọi `VideoPlayer`.
- **Nạp trước native:** khi app khởi động, nạp FFmpeg trên thread nền (ví dụ `FFmpegFrameGrabber.tryLoad()`) để video đầu tiên không bị khựng khoảng 7 giây. Trong lúc chờ, bong bóng vẫn hiện "Đang tải …".

**`MediaBubbles`:**
- `kind == "video"`: dùng `VideoPlayer` với giao diện điều khiển giống hiện tại (nút `media-play`, `Slider`, nhãn thời gian).
- `kind == "audio"` (WAV, MP3, M4A): **cũng dùng `VideoPlayer` ở chế độ chỉ có tiếng** (không có `ImageView`, tiêu đề "Tin nhắn thoại" như cũ). Lý do: `AttachmentKinds` xếp `mp3`/`m4a` vào `audio`, mà MP3/AAC trên JavaFX cũng đi qua DirectShow nên lỗi như video. Dùng một đường FFmpeg cho mọi media thì hành vi đồng nhất. Bỏ hẳn JavaFX `MediaPlayer`/`MediaView` khỏi `MediaBubbles`. *(Quyết định của người viết design sau builder check.)*
- **Xoay video điện thoại:** đọc `grabber.getDisplayRotation()`. Nếu là ±90/180 độ thì xoay `ImageView`, và tính kích thước thu nhỏ hay khung hiển thị theo kích thước **sau khi xoay**. *(Quyết định của người viết design.)*

**Phía gửi (`AttachmentKinds`, `MainView` FileChooser):** thêm `mov` vào nhóm `video`. Bộ lọc chọn file video đổi thành "Video (MP4, MOV)" với `*.mp4`, `*.m4v`, `*.mov`. Không đổi giao thức. *(Quyết định của người viết design, để khớp goal MOV.)*
- **Gỡ bỏ** nút "▶ Mở bằng trình phát", `openExternally` và `canOpenExternally`, kèm test `MediaBubblesTest` tương ứng.
- Khi lỗi (FFmpeg không mở được file, file hỏng, codec lạ): hiện "Không phát được video này" kèm thẻ file "Lưu về máy" như cũ. Lỗi giữa chừng lúc phát thì dừng và hiện thông báo đó.

**Kiểm thử:**
- Unit test cho phần thuần, không cần FX hay native: quyết định khung hình (hiện / chờ / bỏ theo đồng hồ), tính kích thước thu nhỏ giữ tỉ lệ, và định dạng thời gian.
- Test tích hợp: test tự tạo một MP4 ngắn vài giây (có hình và tiếng) bằng `FFmpegFrameRecorder`, với codec có sẵn trong bản LGPL, ví dụ `mpeg4` + `aac`. Sau đó kiểm tra `VideoPlayer` mở được, lấy được khung đầu đúng kích thước thu nhỏ, có thời lượng, tua được, và dispose không rò thread. Không dùng file mẫu tải từ mạng.
- `mvn -q test` phải xanh hoàn toàn.
- Thêm test cho `AttachmentKinds`: `.mov` là `video`.
- Chạy GUI thật:
  - Gửi `videoplayback.mp4` (H.264 + AAC, 202 giây) và một video dọc, kiểm tra ảnh xem trước, phát có tiếng, tua và chuyển hội thoại.
  - Ghi âm và nghe lại một tin nhắn thoại WAV, và phát một file MP3.

## Để lại cho người làm

- Cách tổ chức class bên trong `VideoPlayer` (tách `PlaybackClock` hay không), ngưỡng bỏ khung (gợi ý > 100 ms), kích thước buffer của `SourceDataLine`.
- Dùng `Java2DFrameConverter` hay tự copy buffer BGRA vào `WritableImage`. Ưu tiên ghi thẳng `PixelWriter.setPixels(..., PixelFormat.getByteBgraPreInstance(), ...)` để tránh qua AWT.
- Cách nạp trước native và nơi gọi (`ChatApp` hoặc `MainView`).
