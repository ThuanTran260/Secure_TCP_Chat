# Nhật ký nghiệm thu thủ công TC01–TC06 (slide 35)

Ngày chạy: 2026-11-14. Máy: Windows 11, JDK 24, Maven 3.9.12, PowerShell. Code ở commit `6762471` (chưa sửa code production trong task này).

## Kết quả tổng hợp

| TC | Kết quả | Bằng chứng ngắn |
| --- | --- | --- |
| TC01 | PASS | Cả ba client in `[an] Xin chào mọi người!` |
| TC02 | PASS | `Đăng nhập thất bại: Username hoặc Password không đúng` + log `[BLOCK] Login failed` |
| TC03 | PASS | `Chứng chỉ server không hợp lệ: ... No subject alternative names matching IP address 127.0.0.2 found` |
| TC04 hết hạn | PASS | `Chứng chỉ server không hợp lệ: (certificate_unknown) NotAfter: Sat Oct 10 14:34:59 GMT+07:00 2026` |
| TC04 không tin cậy | PASS | `... PKIX path validation failed: ... signature check failed` |
| TC05 | PASS | Log `[BLOCK] TLS handshake failed`; server chỉ trả 7 byte TLS alert (`15 03 03 00 02 02 0A`), không có JSON |
| TC06 | PASS | an và chi in `* binh đã rời phòng`, rồi vẫn trao đổi `[an] ...` / `[chi] ...` |
| Graceful shutdown | Xác minh một phần (xem mục cuối) | Ctrl+C thật → server thoát, cổng trống, client in `! Mất kết nối tới server`; chưa quan sát được riêng việc hook chạy `server.stop()` |

Ghi chú về cách chạy:

- Server chạy bằng `Start-Process` (ẩn cửa sổ), stderr chuyển hướng ra file log. Log server do `java.util.logging` ghi ra stderr nên đây chính là "file log server".
- Mỗi client chạy bằng một script PowerShell phụ (`Process` .NET, stdin/stdout UTF-8) để cấp từng dòng đầu vào kèm `Start-Sleep`, nhờ vậy ba client online cùng lúc. Output client được ghi bất đồng bộ nên thứ tự dòng `[exit code: N]` trong file có thể lệch so với thời điểm thật; không ảnh hưởng nội dung.
- `java` trên máy này là stub `C:\Program Files\Common Files\Oracle\Java\javapath\java.exe`, nó sinh một process `java.exe` thật (JDK 24) làm con. Khi cần kill "client" phải kill process con (xem TC06).
- Số `client N` trong log tăng dần theo thứ tự kết nối của server từ lúc khởi động: client 1–3 là TC01, 4–9 là TC02/03/05 (chạy hai lượt, xem bên dưới), 10–12 là lượt TC06 thứ nhất (kill nhầm), 13–15 là TC06 chính thức, 16–17 là TC04 không tin cậy, 18–19 là graceful shutdown.
- Chứng chỉ demo: `./scripts/gen-certs.ps1 -OutDir certificates-demo` với `CHAT_CERT_PASSWORD` là chuỗi demo (không ghi ở đây). Thư mục `certificates-demo/`, `certificates-expired/`, `certificates-other/` đều bị `.gitignore` (`certificates*/`) nên không commit. Thư mục `certificates/` có sẵn của người dùng không bị đụng tới.

## Chuẩn bị

```powershell
mvn -q package -DskipTests            # exit 0
$env:CHAT_CERT_PASSWORD = "<demo>"
./scripts/gen-certs.ps1 -OutDir certificates-demo
```

Chứng chỉ sinh ra (trích `keytool -printcert`):

```text
Owner: CN=localhost, OU=Lab, O=SecureChat, L=Hanoi, C=VN
Valid from: Sat Nov 14 14:31:42 GMT+07:00 2026 until: Sun Nov 14 14:31:42 GMT+07:00 2027
SubjectAlternativeName [ DNSName: localhost, IPAddress: 127.0.0.1 ]
```

Trước khi chạy: `netstat -ano | findstr :5000` không có dòng nào, không có process `java`.

Khởi động server:

```powershell
Start-Process java -ArgumentList '-cp','target/secure-tcp-chat.jar','com.example.securechat.server.ServerMain','--keystore','certificates-demo/server/server.p12' `
  -RedirectStandardError server.log -RedirectStandardOutput server.out -PassThru -WindowStyle Hidden
```

```text
INFO: Server listening on port 5000
TCP    0.0.0.0:5000    0.0.0.0:0    LISTENING    29932
```

---

## TC01: ba client cùng thấy tin broadcast — PASS

Lệnh mỗi client (qua script phụ; stdin là `<user>`, `<password>`, rồi các dòng chat, `/quit`):

```text
java -cp target/secure-tcp-chat.jar com.example.securechat.client.ClientMain localhost 5000 --cert certificates-demo/client/server.crt
```

Lịch nhập: binh và chi đăng nhập trước, `an` đăng nhập sau rồi 7 giây sau gửi `Xin chào mọi người!`; sau đó cả ba `/quit`. Cả ba process cùng chạy (3 kết nối `ESTABLISHED` cùng lúc).

Output client `an`:

```text
Username: Password: Đã kết nối localhost:5000 (TLSv1.3)
Đăng nhập thành công. Gõ /quit để thoát.
* an đã tham gia
[an] Xin chào mọi người!
* binh đã rời phòng
```

Output client `binh`:

```text
Username: Password: Đã kết nối localhost:5000 (TLSv1.3)
Đăng nhập thành công. Gõ /quit để thoát.
* binh đã tham gia
* chi đã tham gia
* an đã tham gia
[an] Xin chào mọi người!
```

Output client `chi`:

```text
Username: Password: Đã kết nối localhost:5000 (TLSv1.3)
Đăng nhập thành công. Gõ /quit để thoát.
* chi đã tham gia
* an đã tham gia
[an] Xin chào mọi người!
* binh đã rời phòng
* an đã rời phòng
```

Log server:

```text
INFO: [PASS] TLS authenticated: client 1 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 2 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 3 (127.0.0.1) TLSv1.3
INFO: [INFO] Client 1 disconnected (127.0.0.1) - client requested
INFO: [INFO] Client 3 disconnected (127.0.0.1) - client requested
INFO: [INFO] Client 2 disconnected (127.0.0.1) - client requested
```

(Output của `binh`/`chi` không hiển thị các dòng `* ... đã tham gia` của người vào trước mình, đúng với thiết kế: thông báo chỉ gửi cho người đang online.)

---

## TC02: sai password — PASS

```powershell
cmd /c "(echo an& echo Sai@Pass1& echo /quit) | java -cp target/secure-tcp-chat.jar com.example.securechat.client.ClientMain localhost 5000 --cert certificates-demo/client/server.crt 2>&1"
```

Output (exit code 1):

```text
Username: Password: Đã kết nối localhost:5000 (TLSv1.3)
Đăng nhập thất bại: Username hoặc Password không đúng
```

Log server:

```text
INFO: [PASS] TLS authenticated: client 7 (127.0.0.1) TLSv1.3
WARNING: [BLOCK] Login failed: client 7
INFO: [INFO] Client 7 disconnected (127.0.0.1) - login failed
```

Thông báo lỗi chung, không tiết lộ username có tồn tại hay không. Lệnh này chạy hai lượt (lượt đầu cho client 4, lượt sau là client 7 ở trên); cả hai đều ra cùng kết quả.

---

## TC03: hostname không có trong SAN — PASS

`127.0.0.2` route được trên máy này (`ping 127.0.0.2` có reply, server bind `0.0.0.0` nên nhận được), nên dùng đúng `127.0.0.2` như plan, không cần IP LAN.

```powershell
cmd /c "(echo an& echo An@12345& echo /quit) | java -cp target/secure-tcp-chat.jar com.example.securechat.client.ClientMain 127.0.0.2 5000 --cert certificates-demo/client/server.crt 2>&1"
```

Output (exit code 1):

```text
Username: Password: Chứng chỉ server không hợp lệ: (certificate_unknown) No subject alternative names matching IP address 127.0.0.2 found
```

Log server:

```text
WARNING: [BLOCK] TLS handshake failed: client 8 (127.0.0.1)
```

Client không gửi login (handshake đã thất bại trước đó), server không có dòng `Login` nào cho kết nối này.

---

## TC04: chứng chỉ hết hạn và không tin cậy — PASS (cả hai)

### Lưu ý về `-StartDate -10d` trong plan

Plan viết `-StartDate -10d`, nhưng script dùng `-validity 365`, nên chứng chỉ vẫn còn hạn 355 ngày. Đã kiểm tra bằng `keytool`:

```text
-StartDate -10d  -> Valid from: Wed Nov 04 14:34:58 2026 until: Thu Nov 04 14:34:58 2027   (CÒN HẠN)
-StartDate -400d -> Valid from: Fri Oct 10 14:34:59 2025 until: Sat Oct 10 14:34:59 2026   (HẾT HẠN)
```

Vì vậy nghiệm thu dùng `-StartDate -400d`. Dòng tương ứng trong plan đã được sửa.

### Hết hạn

```powershell
./scripts/gen-certs.ps1 -OutDir certificates-expired -StartDate -400d
Start-Process java -ArgumentList '-cp','target/secure-tcp-chat.jar','com.example.securechat.server.ServerMain','--port','5001','--keystore','certificates-expired/server/server.p12' `
  -RedirectStandardError server-expired.log -WindowStyle Hidden
cmd /c "(echo an& echo An@12345& echo /quit) | java -cp target/secure-tcp-chat.jar com.example.securechat.client.ClientMain localhost 5001 --cert certificates-expired/client/server.crt 2>&1"
```

(Server hết hạn chạy ở cổng 5001 để không phải dừng server chuẩn ở cổng 5000.)

Output client (exit code 1):

```text
Username: Password: Chứng chỉ server không hợp lệ: (certificate_unknown) NotAfter: Sat Oct 10 14:34:59 GMT+07:00 2026
```

Thông báo là chi tiết của `CertificateExpiredException` (`NotAfter: <ngày hết hạn>`), không có chữ "expired"; kết luận hết hạn dựa trên giá trị `NotAfter` đã nằm trong quá khứ so với ngày chạy 2026-11-14.

Log server (cổng 5001):

```text
INFO: Server listening on port 5001
WARNING: [BLOCK] TLS handshake failed: client 1 (127.0.0.1)
```

### Không tin cậy (khác cặp khóa)

Server chuẩn (cổng 5000, keystore `certificates-demo`), client tin chứng chỉ khác.

Theo plan, dùng `certificates-expired/client/server.crt`:

```powershell
cmd /c "(echo an& echo An@12345& echo /quit) | java -cp target/secure-tcp-chat.jar com.example.securechat.client.ClientMain localhost 5000 --cert certificates-expired/client/server.crt 2>&1"
```

```text
Username: Password: Chứng chỉ server không hợp lệ: (certificate_unknown) PKIX path validation failed: java.security.cert.CertPathValidatorException: signature check failed
```

Chứng chỉ này vừa hết hạn vừa khác khóa nên kết quả không tách được nguyên nhân. Chạy thêm với một chứng chỉ **còn hạn nhưng khác khóa** (`./scripts/gen-certs.ps1 -OutDir certificates-other`, hiệu lực đến 2027-11-14):

```powershell
cmd /c "(echo an& echo An@12345& echo /quit) | java -cp target/secure-tcp-chat.jar com.example.securechat.client.ClientMain localhost 5000 --cert certificates-other/client/server.crt 2>&1"
```

```text
Username: Password: Chứng chỉ server không hợp lệ: (certificate_unknown) PKIX path validation failed: java.security.cert.CertPathValidatorException: signature check failed
```

Log server:

```text
WARNING: [BLOCK] TLS handshake failed: client 16 (127.0.0.1)
WARNING: [BLOCK] TLS handshake failed: client 17 (127.0.0.1)
```

---

## TC05: TCP thường, không TLS — PASS

Snippet trong plan (chạy từ PowerShell), có thêm in byte nhận được để thấy rõ nội dung trả về:

```powershell
$c=[Net.Sockets.TcpClient]::new('localhost',5000); $ns=$c.GetStream()
$b=[Text.Encoding]::UTF8.GetBytes('{"type":"login","username":"an","password":"x"}' + "`n")
$ns.Write($b,0,$b.Length); Start-Sleep 2
$buf = New-Object byte[] 16
$r = $ns.Read($buf,0,16); "Read returned $r bytes: " + (($buf[0..($r-1)] | % { $_.ToString('X2') }) -join ' ')
$r2 = $ns.Read($buf,0,16); "2nd Read returned $r2 bytes"
$c.Close()
```

Output:

```text
Read returned 7 bytes: 15 03 03 00 02 02 0A
2nd Read returned 0 bytes
```

`15 03 03 00 02 02 0A` là một bản ghi TLS alert (type `0x15`, version TLS 1.2, độ dài 2, mức fatal `02`, mô tả `0A` = unexpected_message), không phải JSON. Lần đọc thứ hai trả `0` nghĩa là server đã đóng kết nối. (Lần chạy đầu tiên cho kết quả tương tự: `Read returned 7 bytes`.)

Log server:

```text
WARNING: [BLOCK] TLS handshake failed: client 9 (0:0:0:0:0:0:0:1)
```

(`localhost` được phân giải sang `::1`, vì vậy địa chỉ trong log là IPv6.) Log không có dòng `Login` nào cho kết nối này, tức là server không đọc được message login.

---

## TC06: client thoát đột ngột — PASS

Ba client binh, chi, an online cùng lúc. Sau 5 giây kill process `java` **thật** của binh bằng `Stop-Process -Force` (không phải stub `javapath\java.exe`):

```powershell
$stub = [int](Get-Content tc06\binh.pid)
$real = Get-CimInstance Win32_Process -Filter "ParentProcessId=$stub" | Where-Object Name -eq 'java.exe'
"binh stub PID $stub, real java PID $($real.ProcessId)"
Stop-Process -Id $real.ProcessId -Force
```

```text
binh stub PID 30960, real java PID 29184
Killing binh real java at 14:34:27.910
```

Sau đó `an` gửi `chi ơi, binh vừa thoát đột ngột`, `chi` đáp `an ơi, mình vẫn chat được`.

Output client `an`:

```text
Username: Password: Đã kết nối localhost:5000 (TLSv1.3)
Đăng nhập thành công. Gõ /quit để thoát.
* an đã tham gia
* binh đã rời phòng
[an] chi ơi, binh vừa thoát đột ngột
[chi] an ơi, mình vẫn chat được
```

Output client `chi`:

```text
Username: Password: Đã kết nối localhost:5000 (TLSv1.3)
Đăng nhập thành công. Gõ /quit để thoát.
* chi đã tham gia
* an đã tham gia
* binh đã rời phòng
[an] chi ơi, binh vừa thoát đột ngột
[chi] an ơi, mình vẫn chat được
* an đã rời phòng
```

Log server (client 13 = binh):

```text
INFO: [PASS] TLS authenticated: client 13 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 14 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 15 (127.0.0.1) TLSv1.3
INFO: [INFO] Client 13 disconnected (127.0.0.1) - io error: SocketException
INFO: [INFO] Client 15 disconnected (127.0.0.1) - client requested
INFO: [INFO] Client 14 disconnected (127.0.0.1) - client requested
```

Lượt chạy TC06 đầu tiên (clients 10–12) không dùng làm bằng chứng: lúc đó tôi kill nhầm process stub, process `java` thật của binh vẫn sống và tự thoát bằng `/quit` sau 40 giây (log `Client 10 disconnected - client requested`). Đã chạy lại và kill đúng process như trên.

---

## Graceful shutdown (Ctrl+C) — xác minh một phần

Cách làm: server chạy trong một console ẩn; hai client an, chi online (xác nhận bằng `netstat`: 2 kết nối `ESTABLISHED` tới cổng 5000). Gửi một sự kiện **Ctrl+C thật** tới console của server bằng `AttachConsole` + `GenerateConsoleCtrlEvent(CTRL_C_EVENT)` (script phụ `send-ctrlc.ps1`, đích là PID của process `java` thật).

```text
Online before Ctrl+C:
  TCP  127.0.0.1:5000  127.0.0.1:65059  ESTABLISHED  29932
  TCP  127.0.0.1:5000  127.0.0.1:65060  ESTABLISHED  29932
Sending Ctrl+C to server real PID 29932 at 14:35:48.871
Server java processes after Ctrl+C:   (không còn process nào)
netstat :5000:  chỉ còn TIME_WAIT, không còn LISTENING
```

Output hai client ngay sau đó:

```text
=== an ===
* an đã tham gia
* chi đã tham gia
! Mất kết nối tới server     (exit code 1)
=== chi ===
* chi đã tham gia
! Mất kết nối tới server     (exit code 1)
```

Đã xác minh:

- Ctrl+C làm server thoát hẳn trong vài giây, cổng 5000 được giải phóng.
- Hai client đang online được báo mất kết nối ngay (`! Mất kết nối tới server`).

**Chưa kiểm chứng:** việc shutdown hook (`chat-shutdown`) thực sự chạy `server.stop()` thay vì process bị dừng theo cách khác. Lý do:

- `ChatServer.stop()` không ghi log nào (chỉ `LOG.warning` khi handler quá 5 giây), và log server không có dòng `Client N disconnected` nào sau Ctrl+C (khả năng cao do `java.util.logging` đã bị reset trong lúc JVM tắt; chưa xác nhận).
- Mã thoát của lần thử thứ hai (server cổng 5001, không có client) là `-1073741510` (`0xC000013A`, STATUS_CONTROL_C_EXIT), nhưng đó là mã của stub `javapath\java.exe` cũng nhận Ctrl+C, không phản ánh JVM thật.
- JVM luôn chạy shutdown hook khi nhận Ctrl+C, nên nhiều khả năng hook đã chạy, nhưng đây là suy luận, không phải quan sát.

---

## Kiểm tra log không chứa mật khẩu

```powershell
$logs = server.log, server.out, server-expired.log, server-expired.out
foreach($p in 'An@12345','Binh@12345','Chi@12345','Sai@Pass1','<mật khẩu keystore demo>'){ Select-String -Path $logs -Pattern $p -SimpleMatch }
```

```text
Pattern 'An@12345'      -> no matches
Pattern 'Binh@12345'    -> no matches
Pattern 'Chi@12345'     -> no matches
Pattern 'Sai@Pass1'     -> no matches
Pattern '<mật khẩu keystore demo>' -> no matches
```

`Select-String -Pattern 'password' -SimpleMatch` trên các file này cũng không có dòng nào.

## Log server cổng 5000, đầy đủ (không gồm timestamp)

```text
INFO: Server listening on port 5000
INFO: [PASS] TLS authenticated: client 1 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 2 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 3 (127.0.0.1) TLSv1.3
INFO: [INFO] Client 1 disconnected (127.0.0.1) - client requested
INFO: [INFO] Client 3 disconnected (127.0.0.1) - client requested
INFO: [INFO] Client 2 disconnected (127.0.0.1) - client requested
INFO: [PASS] TLS authenticated: client 4 (127.0.0.1) TLSv1.3
WARNING: [BLOCK] Login failed: client 4
INFO: [INFO] Client 4 disconnected (127.0.0.1) - login failed
WARNING: [BLOCK] TLS handshake failed: client 5 (127.0.0.1)
WARNING: [BLOCK] TLS handshake failed: client 6 (0:0:0:0:0:0:0:1)
INFO: [PASS] TLS authenticated: client 7 (127.0.0.1) TLSv1.3
WARNING: [BLOCK] Login failed: client 7
INFO: [INFO] Client 7 disconnected (127.0.0.1) - login failed
WARNING: [BLOCK] TLS handshake failed: client 8 (127.0.0.1)
WARNING: [BLOCK] TLS handshake failed: client 9 (0:0:0:0:0:0:0:1)
INFO: [PASS] TLS authenticated: client 10 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 11 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 12 (127.0.0.1) TLSv1.3
INFO: [INFO] Client 12 disconnected (127.0.0.1) - client requested
INFO: [INFO] Client 11 disconnected (127.0.0.1) - client requested
INFO: [INFO] Client 10 disconnected (127.0.0.1) - client requested
INFO: [PASS] TLS authenticated: client 13 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 14 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 15 (127.0.0.1) TLSv1.3
INFO: [INFO] Client 13 disconnected (127.0.0.1) - io error: SocketException
INFO: [INFO] Client 15 disconnected (127.0.0.1) - client requested
INFO: [INFO] Client 14 disconnected (127.0.0.1) - client requested
WARNING: [BLOCK] TLS handshake failed: client 16 (127.0.0.1)
WARNING: [BLOCK] TLS handshake failed: client 17 (127.0.0.1)
INFO: [PASS] TLS authenticated: client 18 (127.0.0.1) TLSv1.3
INFO: [PASS] TLS authenticated: client 19 (127.0.0.1) TLSv1.3
```

Client 5 và 8 là TC03 (lượt 1 và 2), client 6 và 9 là TC05 (lượt 1 và 2). Lượt 1 của TC02/03/05 chạy trực tiếp trong PowerShell, lượt 2 qua `cmd /c` cho output sạch hơn; kết quả hai lượt giống nhau.

## Dọn dẹp

Sau khi nghiệm thu: `Get-Process java` không trả về process nào; `netstat -ano | findstr :5000` và `netstat -ano | findstr :5001` không có dòng nào (cổng trống). Cuối cùng chạy `mvn -q test`: exit code 0, 75 test, 0 failure, 0 error.
