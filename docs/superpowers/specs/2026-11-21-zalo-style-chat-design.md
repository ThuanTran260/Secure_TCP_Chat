# Zalo-style Secure Chat — Design

**Ngày:** 2026-11-21
**Nền tảng:** project Secure TCP Chat hiện tại (plan `docs/superpowers/plans/2026-11-14-secure-tcp-chat.md`, 75 test).

## Mục đích

Nâng bản chat console thành ứng dụng chat kiểu Zalo có giao diện đồ họa đẹp, để đạt đủ 11 tiêu chí chấm điểm:

| Tiêu chí | Điểm | Nghĩa đã chốt với người dùng |
|---|---|---|
| P2P | 0.5 | Chat riêng 1-1 giữa hai tài khoản |
| Group | 0.5 | Tạo nhóm, chat trong nhóm (giữ thêm "Phòng chung" cho mọi người) |
| File | 0.5 | Gửi/nhận file bất kỳ, bấm để lưu về máy |
| Emoji | 0.5 | Bảng chọn emoji chèn vào tin nhắn |
| History | 0.5 | Lịch sử lưu ở server; mở hội thoại thấy tin cũ, kể cả tin gửi lúc mình offline |
| Image | 0.25 | Gửi ảnh, hiện thumbnail trong khung chat, bấm để xem to |
| Video | 0.25 | Gửi file video, **xem ngay trong khung chat** (không phải gọi video) |
| Sound | 0.25 | **Ghi âm tin nhắn thoại**, bấm để nghe |
| State | 0.5 | **Online/offline** của từng người (chấm xanh/xám), cập nhật tức thời |
| Time | 0.5 | Giờ gửi do server đóng dấu, hiện dưới mỗi tin + vạch ngăn theo ngày |
| Sender | 0.25 | Tên + avatar chữ cái của người gửi; tin của mình nằm bên phải |

## Goals / Non-goals / Anti-goals

- **Goals:** đủ 11 tiêu chí; giao diện hai cột giống Zalo (danh sách hội thoại trái, khung chat phải, màu xanh `#0068FF`); giữ nguyên lớp bảo mật đã có (TLS 1.2/1.3, PBKDF2, throttle, log không lộ mật khẩu).
- **Non-goals:** gọi thoại/gọi video realtime; "đang gõ…", "đã xem"; đăng ký tài khoản mới từ GUI; mã hóa đầu-cuối; sửa/thu hồi tin; nhiều server.
- **Anti-goals:** bỏ TCP socket để chuyển sang HTTP/WebSocket (lệch chủ đề môn); làm hỏng client console và 75 test cũ; thêm bất kỳ đường "trust all" nào; lưu mật khẩu hay key vào repo.

## Approaches (đã chọn: 1)

1. **JavaFX desktop client trên giao thức TCP/TLS hiện có — CHỌN.** `MediaView` phát MP4 trong khung chat, `javax.sound.sampled` ghi âm WAV, CSS cho giao diện. Chỉ thêm OpenJFX qua Maven, giữ toàn bộ server và test cũ. Đánh đổi: video phải là MP4 H.264/AAC; emoji trên JavaFX có thể hiển thị đơn sắc.
2. Web client + WebSocket: giao diện đẹp nhất, media dễ, nhưng bỏ TCP socket thô và phần lớn client cũ → lệch chủ đề môn. Loại.
3. Swing: muốn phát video phải cài VLC (vlcj) → khó demo. Loại.

## Thiết kế

### Hội thoại
- `lobby` = "Phòng chung" (mọi người). Message không có `to` được hiểu là `lobby` → client console cũ vẫn chạy.
- `dm:<a>:<b>` = chat riêng, hai username đã normalize, sắp xếp tăng dần.
- `group:<uuid>` = nhóm do người dùng tạo (tên nhóm + danh sách thành viên, người tạo luôn là thành viên).
- Server kiểm tra quyền mọi thao tác: người gửi phải thuộc hội thoại (`NOT_MEMBER` nếu không).

### Giao thức (mở rộng, vẫn JSON 1 dòng ≤ 4096 byte)
- Server đóng dấu `timestamp` (epoch ms) cho mọi tin chat và lưu vào lịch sử trước khi gửi đi.
- File/ảnh/video/ghi âm đi qua cùng kết nối TLS, chia đoạn 2048 byte raw, mã hóa base64 trong `file_chunk`. Giới hạn 25 MB/file.
- Tải xuống theo yêu cầu (`file_get`), server đẩy chunk có điều tiết để không lấp đầy hàng đợi gửi (giữ ít nhất nửa hàng đợi cho tin thường).
- Presence: khi đăng nhập client nhận `user_list` (mọi tài khoản + ai đang online) và `group_info` các nhóm của mình; sau đó mỗi lần ai vào/ra server phát `presence`.

### Lưu trữ phía server (thư mục `data/`, không commit)
- `data/history/<conversation>.jsonl`: mỗi dòng một tin đã đóng dấu thời gian.
- `data/groups.json`: các nhóm.
- `data/files/<fileId>.bin` + `<fileId>.json` (tên, mime, kích thước, hội thoại, người gửi).

### Client
- Mở rộng `ChatClient` (gửi theo hội thoại, lịch sử, tạo nhóm, upload/download file).
- Package mới `gui` (JavaFX 21): màn hình đăng nhập (host, port, đường dẫn `server.crt`, username, password) → màn hình chính hai cột.
- `ChatState` là model thuần Java (không phụ thuộc JavaFX) để test được: danh sách hội thoại, tin nhắn, presence, số tin chưa đọc.

## Ràng buộc
- Mọi ràng buộc trong "Global Constraints" của plan 2026-11-14 vẫn giữ nguyên.
- `maven.compiler.release=17`; OpenJFX 21.0.5 (chạy trên JDK 17+).
- 75 test cũ phải tiếp tục pass; client console vẫn dùng được ở Phòng chung.

## Phần để builder tự quyết
- Bố cục pixel, màu phụ, icon (dùng ký tự Unicode hoặc SVGPath, không thêm thư viện icon).
- Bộ emoji (khoảng 40 emoji thông dụng).
- Cách hiển thị lỗi trong GUI (label đỏ hoặc Alert).

## Quyết định do Claude đưa ra (người dùng có thể sửa)
- Lưu trữ bằng file JSONL thay vì database, để không thêm dependency và dễ xem khi chấm bài.
- Giới hạn 25 MB/file, ghi âm tối đa 60 giây (WAV 16 kHz mono).
- Giữ "Phòng chung" như một nhóm mặc định để tương thích client console.
- GUI được nghiệm thu thủ công theo checklist; phần giao thức của cả 11 tiêu chí có test tự động không cần GUI.
