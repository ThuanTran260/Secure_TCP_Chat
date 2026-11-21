# Secure TCP Chat (Java)

Ứng dụng chat TCP nhiều client với **giao diện JavaFX kiểu Zalo** và client console. Hỗ trợ chat riêng, nhóm, lịch sử, trạng thái online, emoji, file, ảnh, video và tin nhắn thoại. Mọi dữ liệu đi qua **TLS 1.2/1.3**, client phải đăng nhập trước khi chat; log không chứa mật khẩu.

- Giao thức: JSON UTF-8, mỗi dòng một message, tối đa 4096 byte/dòng (cả hai chiều).
- Server: `SSLServerSocket` cổng `5000`, bind `0.0.0.0`, một thread đọc và một thread ghi cho mỗi client.
- Mật khẩu người dùng lưu dạng PBKDF2 (`config/users.properties`), không lưu plaintext.

## 1. Yêu cầu môi trường

- JDK 17 trở lên, có sẵn `keytool` trong `PATH` (bản nâng cấp được kiểm tra trên JDK 25)
- Maven 3.9+
- PowerShell (để chạy `scripts/gen-certs.ps1`)

## 2. Tạo chứng chỉ

Keystore của server (`server.p12`) và chứng chỉ công khai cho client (`server.crt`) được tạo bằng `keytool`. Mật khẩu keystore lấy từ biến môi trường `CHAT_CERT_PASSWORD`; thiếu biến này script báo `Thiếu CHAT_CERT_PASSWORD`.

```powershell
$env:CHAT_CERT_PASSWORD = "<mật-khẩu-keystore-của-bạn>"
./scripts/gen-certs.ps1
```

Kết quả (nằm trong thư mục `certificates/`, đã được `.gitignore`):

- `certificates/server/server.p12`: keystore của server (RSA 2048, hiệu lực 365 ngày, SAN `dns:localhost,ip:127.0.0.1`)
- `certificates/client/server.crt`: chứng chỉ công khai để client tin cậy

Tham số khác của script: `-OutDir <thư mục>`, `-AddIp <IP LAN>`, `-StartDate <giá trị keytool, ví dụ -400d>`.

## 3. Build

```powershell
mvn package
```

Lệnh này chạy toàn bộ test rồi tạo `target/secure-tcp-chat.jar` (jar đã gộp dependency). Bỏ qua test: `mvn package -DskipTests`.

## 4. Chạy server

```powershell
$env:CHAT_CERT_PASSWORD = "<mật-khẩu-keystore-của-bạn>"
java -cp target/secure-tcp-chat.jar com.example.securechat.server.ServerMain
```

Tham số tùy chọn (mỗi tham số kèm một giá trị):

- `--port <số>`: mặc định `5000`
- `--keystore <đường dẫn>`: mặc định `certificates/server/server.p12`
- `--users <đường dẫn>`: mặc định `config/users.properties`
- `--data <thư mục>`: mặc định `data/`, lưu nhóm, lịch sử và file đã gửi; dùng lại thư mục này để giữ dữ liệu sau khi khởi động lại.

Log ghi ra **stderr** (`java.util.logging`), nên muốn lưu file thì chuyển hướng stderr, ví dụ `2> server.log`. Dừng server bằng Ctrl+C (shutdown hook gọi `server.stop()` để đóng các kết nối).

## 5. Chạy client

Mở mỗi client trong một cửa sổ terminal riêng:

```powershell
java -cp target/secure-tcp-chat.jar com.example.securechat.client.ClientMain localhost 5000
```

Cú pháp: `ClientMain [host=localhost] [port=5000] [--cert certificates/client/server.crt]`.

- Nhập username, rồi password (không hiện ký tự khi chạy trong terminal thật).
- Gõ nội dung rồi Enter để gửi cho cả phòng; gõ `/quit` để thoát.
- Client cũng nhận được stdin dạng pipe (username, password, các dòng chat, `/quit`), tiện cho kiểm thử tự động.
- Dòng hiển thị: `[tên] nội dung` cho tin nhắn, `* ...` cho thông báo hệ thống (vào/rời phòng), `! ...` cho lỗi.

## 6. Tài khoản demo

> **Chỉ dùng cho bài thực hành.** Không dùng các mật khẩu này ở bất kỳ môi trường thật nào.

| Username | Password |
| --- | --- |
| `an` | `An@12345` |
| `binh` | `Binh@12345` |
| `chi` | `Chi@12345` |

File `config/users.properties` chỉ chứa hash PBKDF2 (định dạng `username=pbkdf2$<vòng lặp>$<salt>$<hash>`), không chứa mật khẩu gốc.

## 7. Thêm user bằng `HashPasswordTool`

```powershell
java -cp target/secure-tcp-chat.jar com.example.securechat.server.HashPasswordTool
```

Nhập password (ẩn khi chạy trong terminal; hoặc pipe một dòng qua stdin). Tool in ra một dòng hash `pbkdf2$...` trên stdout. Thêm vào `config/users.properties` một dòng:

```properties
ten_moi=pbkdf2$600000$<salt>$<hash>
```

Khởi động lại server để nạp user mới.

## 8. Chạy qua LAN

1. Tạo chứng chỉ có thêm IP LAN của máy chạy server (client kiểm tra hostname nên IP phải nằm trong SAN):
   ```powershell
   ./scripts/gen-certs.ps1 -AddIp 192.168.1.50
   ```
2. Mở firewall cho TCP 5000 trên máy server (cần quyền Administrator):
   ```powershell
   New-NetFirewallRule -DisplayName "Secure Chat 5000" -Direction Inbound -Protocol TCP -LocalPort 5000 -Action Allow
   ```
3. Chạy server như mục 4.
4. Trên máy client, **chỉ copy `server.crt`** (không bao giờ copy `server.p12`), rồi chạy:
   ```powershell
   java -cp secure-tcp-chat.jar com.example.securechat.client.ClientMain 192.168.1.50 5000 --cert server.crt
   ```

## 9. Kiểm thử

Chạy toàn bộ **142 test** tự động, gồm 75 test gốc và các test hội thoại, media, client và 11 tiêu chí đầu-cuối:

```powershell
mvn -q test
```

### Bảng TC01–TC06 (slide 35) ↔ test tự động

| TC | Kịch bản | Kỳ vọng | Test tự động |
| --- | --- | --- | --- |
| TC01 | Ba client an/binh/chi đăng nhập, an gửi tin | Cả ba thấy `[an] ...` | `ChatServerChatTest.tc01_broadcastReachesAllThree`, `ChatServerLoginTest.tc01_validLoginGetsClientId` |
| TC02 | Đăng nhập sai password | `Username hoặc Password không đúng`, log `[BLOCK] Login failed`, server đóng kết nối | `ChatServerLoginTest.tc02_wrongPasswordRejectedAndClosed` |
| TC03 | Kết nối bằng IP/host không có trong SAN | Client từ chối: `Chứng chỉ server không hợp lệ: ...` | `ChatClientTest.tc03_wrongHostCertRejected` |
| TC04 | Chứng chỉ hết hạn / không tin cậy | Client từ chối ở bước handshake | `ChatClientTest.tc04_expiredCertRejected`, `ChatClientTest.tc04_untrustedCertRejected` |
| TC05 | Kết nối TCP thường (không TLS) rồi gửi JSON login | Log `[BLOCK] TLS handshake failed`, không có JSON trả về | `ChatServerLoginTest.tc05_plainTcpIsRejected` |
| TC06 | Một client thoát đột ngột | Các client còn lại thấy `* <tên> đã rời phòng` và vẫn chat được | `ChatServerChatTest.tc06_abruptDisconnect` |

Kết quả chạy thủ công từng TC (lệnh, output client, log server) nằm trong [`docs/acceptance-log.md`](docs/acceptance-log.md).

## 10. Giao diện kiểu Zalo

Sau khi tạo chứng chỉ và chạy server, mở một GUI cho mỗi tài khoản bằng:

```powershell
mvn javafx:run
```

Hoặc chạy từ jar đã build:

```powershell
java -cp target/secure-tcp-chat.jar com.example.securechat.gui.GuiLauncher
```

`java -jar target/secure-tcp-chat.jar` cũng mở GUI. Jar chứa thư viện JavaFX và native dependency của nền tảng đã build; build lại bằng Maven trên máy có hệ điều hành/kiến trúc khác.

Nhập **Máy chủ**, **Cổng**, đường dẫn **Chứng chỉ server**, **Tên đăng nhập** và **Mật khẩu**. Mặc định kết nối `localhost:5000` và tin `certificates/client/server.crt`. Các tài khoản demo ở mục 6. Có thể đồng thời dùng GUI và client console.

- Cột trái có **Phòng chung**, người dùng và nhóm. Chọn một người để chat riêng; nút **+** cạnh tên mình mở hộp **Tạo nhóm**.
- Chọn hội thoại để nạp tối đa 50 tin gần nhất từ server, kể cả tin gửi khi mình offline. Tin mới có số chưa đọc; chấm xanh/xám thể hiện online/offline.
- Nhập tin rồi Enter hoặc **Gửi**. Nút **😊** chèn emoji tại vị trí con trỏ.
- Nút đính kèm **+** cạnh ô nhập chọn **Ảnh**, **Video** hoặc **File**. File tối đa **25 MB**, không gửi file rỗng. Bấm ảnh để xem lớn; bấm **Tải về/Lưu về máy** để lưu bản gốc.
- Bấm micro để bắt đầu ghi, bấm lần nữa để gửi tin nhắn thoại. Ghi tối đa **60 giây**, WAV PCM 16 kHz, 16-bit mono; cần micro khả dụng.
- Video phát trong khung chat bằng JavaFX; dùng **MP4 H.264/AAC** để có khả năng tương thích tốt. Codec không hỗ trợ sẽ hiện thông báo cùng nút lưu file. Emoji có thể hiển thị đơn sắc tùy font và hệ điều hành.

| Tiêu chí | Tính năng | Test tự động |
| --- | --- | --- |
| P2P | Hội thoại riêng; chỉ hai thành viên được gửi, nhận, xin lịch sử và tải file | `CriteriaEndToEndTest.directConversationIsPrivate`, `ChatServerConversationTest` |
| Group | Tạo nhóm, tự thêm người tạo; nhóm tồn tại sau restart | `CriteriaEndToEndTest.groupIncludesCreatorAndSelectedMembers`, `GroupStoreTest` |
| File | Upload/download theo chunk trên TLS, giữ chỗ hàng đợi cho chat | `CriteriaEndToEndTest.arbitraryFileRoundTrips`, `ChatServerFileTest` |
| Emoji | Bảng emoji; giữ nguyên code point UTF-8 | `CriteriaEndToEndTest.unicodeEmojiSurvivesTransport` |
| History | JSONL trên server; tối đa 50 tin khi mở hội thoại | `CriteriaEndToEndTest.offlineRecipientCanReadPersistedHistory`, `HistoryStoreTest` |
| Image | Thumbnail, mở ảnh lớn, lưu ảnh gốc | `CriteriaEndToEndTest.pngImageRoundTrips` |
| Video | Video trong bong bóng, điều khiển phát và thanh thời gian | `CriteriaEndToEndTest.videoAttachmentRoundTrips` |
| Sound | Ghi âm WAV và phát tin nhắn thoại | `CriteriaEndToEndTest.oneSecondWavRoundTrips` |
| State | Danh sách user và presence online/offline tức thời | `CriteriaEndToEndTest.presenceReflectsDisconnectAndReconnect`, `ChatStateTest` |
| Time | Server đóng dấu epoch ms; GUI hiển thị giờ và vạch ngày | `CriteriaEndToEndTest.serverTimestampMatchesSendWindow` |
| Sender | Tên/avatar chữ cái; tin mình bên phải | `CriteriaEndToEndTest.serverSetsAuthenticatedSender`, `ChatStateTest` |

Test image/video/sound kiểm tra truyền đúng byte và metadata. Việc hiển thị, phát codec, thu micro và tương tác GUI được ghi riêng tại [`docs/acceptance-zalo.md`](docs/acceptance-zalo.md); kết quả chưa kiểm tra được không được xem là PASS.

`data/history/` chứa lịch sử, `data/groups.json` chứa nhóm, `data/files/` chứa file gốc và metadata. Không commit dữ liệu này. FileId luôn là UUID chuẩn; tên file người gửi không được dùng làm đường dẫn server. Message có khoảng trống cho metadata lịch sử để cả tin trực tiếp và replay luôn tuân giới hạn 4096 byte. File cache của GUI nằm trong `%TEMP%/securechat-cache/<username>`.

Phân công agent và các quyết định tích hợp nằm tại [`docs/agent-workflow.md`](docs/agent-workflow.md).

## 11. Cảnh báo về chứng chỉ tự ký

- `server.crt` là chứng chỉ **tự ký**, không có CA nào xác nhận. Client tin đúng chứng chỉ này (không có chế độ "trust all"), nên **phải lấy `server.crt` qua kênh đáng tin** (copy trực tiếp, so khớp fingerprint SHA-256 bằng `keytool -printcert -file server.crt`). Ai thay được `server.crt` trên máy client thì có thể giả mạo server.
- Client bắt buộc kiểm tra hostname (SAN), chuỗi tin cậy và thời hạn chứng chỉ. Chứng chỉ chỉ có hiệu lực 365 ngày; hết hạn thì tạo lại bằng `gen-certs.ps1` và phát lại `server.crt`.
- **Không commit** `server.p12`, `*.key`, `*.jks`, `*.pfx`, thư mục `certificates*/` hay mật khẩu keystore (đã có trong `.gitignore`). Keystore chứa private key: ai có file và mật khẩu đều giả mạo được server.
- Dự án không kiểm tra thu hồi chứng chỉ (OCSP/CRL) và không dùng client certificate (mTLS).
- Chỉ giới hạn đăng nhập sai theo cặp (IP, username): tối đa 5 lần sai trong 5 phút, sau đó khóa 60 giây.
