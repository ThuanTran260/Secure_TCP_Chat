# Workflow thực thi plan Zalo-style chat

Plan áp dụng: [`superpowers/plans/2026-11-21-zalo-style-chat.md`](superpowers/plans/2026-11-21-zalo-style-chat.md), dựa trên spec cùng ngày và các ràng buộc TLS của plan gốc.

## Phân công và quyền sửa file

| Agent | Trách nhiệm | Phạm vi |
| --- | --- | --- |
| Điều phối | ChatClient, client console, tích hợp, kiểm thử đầu-cuối, tài liệu, nghiệm thu | `client/`, test client/criteria, test helper cũ, `README.md`, `docs/` |
| `protocol_model` | Task 1; sau đó test server Task 3–4 và review client độc lập | Model/protocol, test model, test conversation/file/session |
| `server_features` | Task 2–4, review đồng thời và phân quyền | `server/`, test store, `.gitignore` |
| `gui_media` | Task 6–7 | `gui/`, CSS/resource, test GUI model/cache, `pom.xml` |

## Quy ước phối hợp

1. Interface công khai trong plan là hợp đồng dùng chung; giữ chữ ký cũ. Agent báo ngay khi interface sẵn sàng hoặc cần thay đổi.
2. Mỗi file có một chủ sở hữu. Agent review chéo gửi finding cho chủ file, không ghi đè thay đổi đang thực hiện.
3. Làm việc trên cùng workspace; không tự commit, stage hoặc chạy Maven song song. Agent điều phối chạy Maven sau khi các phần phụ thuộc đã được lưu, vì `target/` là thư mục dùng chung.
4. Viết test cho hành vi trước khi triển khai. Lần compile fail ban đầu ghi nhận các interface/chức năng chưa có; sau tích hợp phải chạy lại toàn bộ test, gồm test cũ.
5. Test thật qua TLS kiểm tra định tuyến, ACL, upload/download, presence và lịch sử. Test model không cần JavaFX. Nghiệm thu GUI tách khỏi kiểm thử giao thức; phần chưa quan sát được ghi rõ trong checklist.
6. Chỉ stage đường dẫn cụ thể. Không đưa cert, private key, mật khẩu, file lịch sử/upload, `pics/` hay file sinh ra vào Git.

## Các quyết định tích hợp

- Lưu đủ khoảng trống cho `history:true` khi kiểm tra kích thước message; mọi dòng gửi lại lịch sử vẫn ≤4096 byte. Helper tính biên của test cũ dùng cùng định dạng message mới, giữ nguyên assertion hành vi.
- Đường dẫn lịch sử đọc được cho ID thông thường. ID có ký tự đặc biệt được mã hóa an toàn và không nhập nhằng để tránh `dm:a_b:c` trùng đường dẫn với `dm:a:b_c`; khi đọc tiếp tục lọc `to` đúng hội thoại.
- Download dùng phần dung lượng hàng đợi riêng có điều tiết; chunk không chiếm quá nửa hàng đợi, chừa chỗ cho chat. Một writer duy nhất tiếp tục bảo đảm message không trộn byte.
- ChatClient không ghi đè file đích có sẵn; xóa dữ liệu tải dở khi lỗi, hủy hoặc đóng kết nối. MediaCache gộp yêu cầu cùng file và dùng file tạm riêng.
- GUI chỉ hiển thị message đã được server echo; I/O mạng, upload/download, copy file và ghi âm chạy ngoài FX thread.

Kết quả kiểm thử và các bước nghiệm thu: [`acceptance-zalo.md`](acceptance-zalo.md).

## Tích hợp và commit

Nhánh: `codex/zalo-style-chat`. Các phần có file phụ thuộc trực tiếp được commit chung: Task 3–4 và Task 6–7. Toàn bộ mã được kiểm tra cùng nhau sau tích hợp; quy trình song song không chạy lại 75 test sau từng bước sửa file riêng lẻ.

| Task | Commit |
| --- | --- |
| 1 — Model/protocol | `4cae150` |
| 2 — Stores/options | `185975c` |
| 3–4 — Routing/history/presence/files | `099b3be` |
| 5 — ChatClient | `52ed00f` |
| 6–7 — GUI/media/voice | `6779bb5` |
| 8 — Criteria/docs/checklist | Commit tài liệu kết thúc nhánh |

Repository chưa cấu hình tác giả Git trên máy này. Các commit dùng danh tính agent `Codex <codex@local.invalid>` bằng cấu hình từng lệnh, không sửa cấu hình Git toàn cục hay mượn danh tính tác giả cũ.
