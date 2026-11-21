# Nghiệm thu Secure Chat kiểu Zalo

Ngày kiểm tra: **21/11/2026**. Môi trường: Windows x64, JDK 25, Maven 3.10.1; source vẫn biên dịch với `release=17`, JavaFX 21.0.5.

## Kiểm thử tự động

| Lệnh / phạm vi | Kết quả |
| --- | --- |
| `mvn -q test` trên mã tích hợp cuối | **142 test, 0 failure, 0 error, 0 skipped**, 23 test suite |
| Bộ test gốc | Giữ đủ 75 test và assertion hành vi; helper chờ message bỏ qua metadata mới, helper biên byte dùng message có timestamp/lịch sử |
| `CriteriaEndToEndTest` | **11/11 PASS**: p2p, group, file, emoji, history, image, video, sound, state, time, sender |
| `ChatServerConversationTest`, `ChatServerFileTest` | 8 + 7 test PASS trong hai lần chạy toàn bộ và một lượt chạy riêng; gồm ACL, restart, lịch sử offline, upload lỗi/dở và tải 2 MB đồng thời chat |
| `MediaCacheTest` | 2/2 PASS: đường dẫn an toàn, hai caller dùng chung future tải, byte khớp và không lộ `.part` |
| `ChatClientExtTest` | 9/9 PASS: DM, file 300 KB, lỗi quyền/file thiếu, bảo vệ file đích, upload đồng thời, giới hạn dung lượng/dòng và đóng nhiều download đang chờ |
| `mvn -q -DskipTests package` | PASS; tạo `target/secure-tcp-chat.jar` gồm JavaFX và `GuiLauncher` |
| `mvn -q javafx:run` | PASS; cửa sổ đăng nhập hiển thị, đóng cửa sổ thì Maven kết thúc exit 0 |
| Chạy lại `ChatClientTest` sau khi tách thư mục dữ liệu test | 9/9 PASS; test không ghi lịch sử vào `data/` của ứng dụng |

Các test media xác minh giao thức, metadata và nội dung byte. Video test dùng byte mẫu MP4; không dùng kết quả đó để xác nhận codec phát được. Test âm thanh tạo WAV một giây bằng code; không dùng micro thật.

## Những gì đã quan sát trên JavaFX

Đã mở jar bằng `GuiLauncher`, quan sát trực tiếp cửa sổ đăng nhập tiếng Việt và bố cục xanh/trắng. Dùng server kiểm thử cục bộ `localhost:5000`, chứng chỉ tạm trong `target/smoke/`, dữ liệu riêng trong `target/smoke/data/`.

- Đăng nhập tài khoản demo `an` thành công; danh sách hiện Phòng chung, `binh`, `chi`.
- Gửi `Kiểm tra GUI qua TLS 😀` trong Phòng chung; nhận server echo, bong bóng bên phải, nền xanh nhạt, giờ và vạch **Hôm nay**.
- Mở bảng 40 emoji, chọn **👍**, thấy emoji chèn vào ô nhập; gửi và thấy echo trong timeline.
- Chọn `binh`; tên hội thoại đổi đúng, hiện **Không hoạt động** và chấm trạng thái xám.
- Mở hộp **Tạo nhóm**; thấy ô tên và checkbox `binh`, `chi`. Chưa hoàn tất tạo nhóm qua thao tác GUI; việc tạo/nhận/chat nhóm đã được test qua TLS.

Không có kết luận PASS cho thao tác chưa quan sát được.

## Checklist thao tác GUI còn có thể chạy lại

Chuẩn bị server theo README, mở ba GUI `an`, `binh`, `chi`; mỗi client phải tin chứng chỉ của server đó. Cột tự động thể hiện kiểm thử giao thức; cột GUI chỉ phản ánh các bước quan sát thực tế.

| Tiêu chí | Thao tác trên GUI | Kết quả mong đợi | Tự động | GUI |
| --- | --- | --- | --- | --- |
| P2P | `an` chọn `binh`, gửi một tin; đối chiếu cả ba cửa sổ | Chỉ `an` và `binh` thấy tin, `chi` không thấy | PASS | Đã mở DM; gửi/nhận hai GUI chờ kiểm tra thủ công |
| Group | Bấm **+** cạnh tên mình, nhập tên, chọn `binh`, **Tạo nhóm**; gửi vào nhóm | Nhóm hiện ở hai thành viên, `chi` không nhận; vẫn còn sau restart | PASS | Đã mở hộp tạo nhóm; hoàn tất tạo/chat qua GUI chờ kiểm tra thủ công |
| File | Đính kèm → **File**, chọn PDF; `binh` bấm **Tải về**, chọn nơi lưu | Lưu được file gốc, byte khớp; chat vẫn tiếp tục | PASS | Chờ kiểm tra thủ công |
| Emoji | Bấm **😊**, chọn **👍**, gửi | Chèn đúng tại caret và nhận đúng emoji | PASS | **PASS** cho chọn/chèn/gửi/echo |
| History | Đóng `binh`; `an` gửi DM; mở lại `binh`, chọn `an` | Hiện tin cũ và tin gửi lúc offline, không nhân đôi live/history | PASS | Chờ kiểm tra thủ công qua hai GUI |
| Image | Đính kèm → **Ảnh**, chọn PNG; bấm thumbnail phía nhận | Thumbnail giữ tỉ lệ, mở được ảnh lớn và lưu ảnh gốc | PASS | Chờ kiểm tra thủ công |
| Video | Đính kèm → **Video**, chọn MP4 H.264/AAC; phía nhận bấm ▶, tua, tạm dừng | Phát trong khung chat; codec lỗi có thông báo và nút lưu | PASS | Chờ kiểm tra thủ công trên codec thực tế |
| Sound | Bấm micro, ghi 3 giây, bấm dừng; phía nhận bấm ▶; thử giới hạn 60 giây | Gửi WAV, nghe lại được; giới hạn thời gian đúng; thiếu micro có thông báo | PASS | Chờ kiểm tra thủ công với micro/loa |
| State | Giữ `an` mở; đăng nhập/đóng `binh` ở cửa sổ thứ hai | Chấm và nhãn đổi online/offline ngay | PASS | Đã thấy offline; chuyển trạng thái hai GUI chờ kiểm tra thủ công |
| Time | Gửi tin, quan sát giờ dưới bong bóng và vạch ngày | Hiện `HH:mm`, phân ngày; timestamp do server cấp | PASS | **PASS** cho giờ và vạch Hôm nay |
| Sender | `an` và `binh` cùng gửi vào Phòng chung/nhóm | Tin mình phải, tin người khác trái; tên và avatar đúng người | PASS | Tin mình bên phải đã quan sát; tin người khác chờ kiểm tra thủ công |

## Giới hạn và lưu ý chạy lại

- File: trên 0 byte đến 25 MB; chunk raw 2048 byte; mọi dòng JSON ≤4096 byte. Có chừa chỗ cho cờ replay lịch sử.
- Ghi âm: WAV 16 kHz, 16-bit mono, tối đa 60 giây. Video phụ thuộc codec JavaFX của máy; emoji trên máy kiểm thử hiển thị đơn sắc.
- Jar được build cho Windows x64. JavaFX có thể in cảnh báo unnamed-module/native-access/Unsafe trên JDK mới; cửa sổ và chat vẫn hoạt động ở lần kiểm tra. Có thể thêm `--enable-native-access=ALL-UNNAMED` khi chạy trên JDK 24+.
- Không lưu password kiểm thử hay chứng chỉ vào Git. Chỉ dừng các process được tạo cho lần nghiệm thu này; không dừng Java của ứng dụng khác trên máy.
- Sau nghiệm thu đã đóng cả GUI chạy từ jar và Maven, dừng server kiểm thử; cổng 5000 trống. Dữ liệu phát sinh từ test cũ đã chuyển vào `target/test-artifacts/`; các test hiện dùng thư mục tạm riêng.
- Workflow agent và lý do bổ sung bảo vệ collision lịch sử: [`agent-workflow.md`](agent-workflow.md).
