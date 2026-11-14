# Secure TCP Chat (Java) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Xây dựng ứng dụng chat TCP nhiều client bằng Java: 1 server, nhiều client console. Mọi dữ liệu đi qua TLS 1.2/1.3, phải đăng nhập trước khi chat, có broadcast, xử lý ngắt kết nối và ghi log an toàn.

**Architecture:** Một Maven project chia thành các package `model` (dùng chung), `common` (framing, TLS), `server`, `client`.
- Server dùng `SSLServerSocket` ở cổng 5000. Mỗi client có một thread đọc (`ClientHandler`) và một thread ghi riêng, thread ghi lấy dữ liệu từ hàng đợi có giới hạn.
- Danh sách client lưu trong `ConcurrentHashMap`.
- Giao thức là JSON UTF-8, mỗi dòng một message, tối đa 4096 byte/dòng theo **cả hai chiều**.

**Tech Stack:** Java 17+ (`maven.compiler.release=17`, máy hiện có JDK 24), Maven 3.9, Jackson databind 2.17.2, JUnit Jupiter 5.10.3, `java.util.logging`, PBKDF2 có sẵn trong JDK, `keytool`.

**Spec:** 35 slide trong `pics/Chuong16.1 - Java-1,Chuong16.1 - Java-2,Chuong16.1 - Java-3/` (tên file "Chuong16.1 - Java-N.png").
- Từ slide 3 trở đi, phần lớn slide lẻ là bản C#, slide chẵn tương ứng là bản Java.
- Ngoại lệ: slide 34 có logo Java nhưng code vẫn là C#.
- Plan làm theo bản Java; yêu cầu bảo mật lấy từ cả hai bản.

### Bảng ánh xạ slide → task

- Slide 1–2 (mục tiêu: nhiều client, TLS, login, broadcast, log) → toàn bộ plan
- Slide 3–4 (kiến trúc; luồng `DISCONNECTED→TCP_CONNECTED→TLS_READY→AUTHENTICATED→CHATTING→CLOSED`) → Task 6, 7
- Slide 5–6 (môi trường; cấu trúc server/client/model/certificates) → Task 1
- Slide 7–8 (tạo chứng chỉ, SAN, không commit key) → Task 4
- Slide 9–10 (giao thức JSON, MessageType, giới hạn 4 KB, không log password) → Task 1, 2, 7
- Slide 11–12 (khởi tạo server, keystore, env var, `0.0.0.0:5000`) → Task 4, 6, 8
- Slide 13–14 (accept loop, client ID, thread riêng) → Task 6
- Slide 15–18 (bọc TLS, handshake, chỉ TLS 1.2/1.3, không yêu cầu client cert) → Task 4, 6
- Slide 19–20 (registry thread-safe, gửi trọn vẹn từng message, snapshot) → Task 5
- Slide 21–22 (login bằng password hash, thông báo lỗi chung, giới hạn số lần login sai) → Task 3, 6
- Slide 23–24 (đọc stream, ghép message, định tuyến theo type) → Task 2, 7
- Slide 25–26 (broadcast an toàn đa luồng; client chậm cần timeout hoặc hàng đợi) → Task 5, 7
- Slide 27–28 (ngắt kết nối idempotent, `user_left`, log) → Task 7
- Slide 29–30 (client kết nối, timeout, lỗi thường gặp) → Task 9
- Slide 31–32 (client kiểm tra chứng chỉ: hostname, thời hạn, chuỗi tin cậy) → Task 4, 9
- Slide 33–34 (gửi và nhận đồng thời, không treo UI) → Task 9
- Slide 35 (kiểm thử TC01–TC06, log mẫu) → Task 6, 7, 9, 10

### Những chỗ plan cố ý khác slide

- **Đăng nhập (slide 21–22):** hai slide này minh họa bằng HTTP (`POST /login`, 200/401; slide 22 dùng Spring). Plan đăng nhập bằng message `login` và trả kết quả bằng `login_result` qua TLS socket, đúng luồng của slide 4 và 9–10.
- **I/O (slide 26):** slide dùng `SocketChannel` + `CompletableFuture`. NIO không dùng chung được với `SSLSocket` (phải chuyển sang `SSLEngine`), nên plan giữ blocking `SSLSocket`.
- **Khóa ghi (slide 19–20, 25–26):** slide dùng `SendLock` (khóa ghi). Plan thay bằng **một writer thread duy nhất cho mỗi client** kèm hàng đợi có giới hạn. Cách này vẫn bảo đảm mỗi message được gửi trọn vẹn và đáp ứng thêm yêu cầu "client chậm cần hàng đợi" của slide 25.
- **Tách message (slide 24):** slide đếm cặp `{}` (`isCompleteJson`). Plan tách message theo dòng, có giới hạn byte, như slide 9–10.
- **Mật khẩu keystore (slide 12):** slide hardcode mật khẩu. Plan đọc từ biến môi trường `CHAT_CERT_PASSWORD` theo slide 11.
- **Tên type:** slide 9 dùng `message`, slide 23–24 dùng `chat`. Plan thống nhất dùng `message`.
- **Kiểm tra thời hạn chứng chỉ (slide 31–32):** client chỉ tin đúng `server.crt`. PKIX của OpenJDK không kiểm tra thời hạn khi chứng chỉ đầu chuỗi nằm sẵn trong trust store, nên plan bổ sung `checkValidity()` ngay trong TrustManager.
- **Giao diện (slide 33–34):** client là console, đây là bản giản lược so với giao diện đồ họa trong slide. Vẫn giữ một thread nhận chạy nền và thread chính nhập/gửi, nên không bị treo.

## Global Constraints

- Java: `maven.compiler.release` = `17`; không dùng API mới hơn Java 17.
- Cổng mặc định `5000`; server bind `0.0.0.0`.
- TLS: chỉ bật `{"TLSv1.3", "TLSv1.2"}`; `setNeedClientAuth(false)`.
- Keystore PKCS12 `server.p12`, alias `server`, RSA 2048, validity 365, `CN=localhost, OU=Lab, O=SecureChat, L=Hanoi, C=VN`, SAN `dns:localhost,ip:127.0.0.1`.
- Mật khẩu keystore lấy từ env `CHAT_CERT_PASSWORD`. Thiếu biến này thì in `Thiếu CHAT_CERT_PASSWORD` và exit 1.
- Client chỉ có `server.crt`. Không có "trust all"; bắt buộc kiểm tra hostname (`setEndpointIdentificationAlgorithm("HTTPS")`), chuỗi tin cậy và thời hạn chứng chỉ.
- Không commit `*.p12`, `*.key`, `*.pfx`, `*.jks`, `certificates/` hay mật khẩu.
- Framing: JSON UTF-8, mỗi dòng 1 message, **mọi dòng gửi đi theo cả hai chiều** tối đa `4096` byte (không tính `\n`).
- Chỉ session đã `authenticated` mới được gửi và nhận chat/system/user_left.
- Không bao giờ ghi password ra log hay ra `toString()`.
- Password lưu dạng `pbkdf2$<iterations>$<saltB64>$<hashB64>` (PBKDF2WithHmacSHA256, salt 16 byte, hash 32 byte, mặc định 600000 vòng).
- Rate limit login: 5 lần sai trong 5 phút cho cùng cặp (IP, username) → khóa 60 giây.
- Hàng đợi gửi mỗi client tối đa `256` message; đầy thì ngắt client đó với lý do `send queue full`.
- Comment trong code và commit message viết bằng tiếng Anh.

## Giao thức (dùng cho mọi task)

| Hướng | JSON |
|---|---|
| C→S | `{"type":"login","username":"an","password":"..."}` |
| S→C | `{"type":"login_result","success":true,"clientId":1}` / `{"type":"login_result","success":false,"content":"<lý do>"}` |
| C→S | `{"type":"message","content":"Xin chào!"}` |
| S→C | `{"type":"message","clientId":1,"from":"an","content":"Xin chào!"}` |
| S→C | `{"type":"system","content":"an đã tham gia"}` |
| S→C | `{"type":"user_left","clientId":3,"from":"chi","content":"chi đã rời phòng"}` |
| S→C | `{"type":"error","content":"<CODE>"}`, CODE ∈ `NOT_AUTHENTICATED`, `ALREADY_AUTHENTICATED`, `INVALID_JSON`, `UNKNOWN_TYPE`, `EMPTY_MESSAGE`, `MESSAGE_TOO_LARGE` |
| C→S | `{"type":"disconnect"}` |

Lý do khi `login_result` thất bại (dùng đúng chuỗi):
- `Username hoặc Password không đúng`
- `Tài khoản đang online`
- `Đăng nhập sai quá nhiều lần, thử lại sau`

Quy tắc chung:
- Field null không được serialize.
- Sender cũng nhận lại message của chính mình (slide 3).

Log server (test sẽ assert trên các chuỗi này):
- `Server listening on port <port>`
- `[PASS] TLS authenticated: client <id> (<ip>) <protocol>`
- `[BLOCK] TLS handshake failed: client <id> (<ip>)`
- `[BLOCK] Login failed: client <id>`
- `[BLOCK] Login throttled: client <id>`
- `[INFO] Client <id> disconnected (<ip>) - <reason>`

## Review Focus

1. **Ranh giới message không khớp với ranh giới lần đọc TCP:** một message bị tách qua nhiều lần đọc, nhiều message dồn chung một lần đọc, hoặc chỗ tách rơi giữa các byte của một ký tự UTF-8. Phải ghép lại đúng → test trong Task 2.
2. **Client bắt tay TLS xong nhưng không gửi login:** server phải đóng kết nối sau `loginTimeout` → test `idleUnauthenticatedClientIsDisconnected` (Task 6).
3. **Cùng username đăng nhập hai lần:** lần thứ hai bị từ chối, session đầu không bị ảnh hưởng → `duplicateLoginIsRejected` (Task 6).
4. **Message tiếng Việt sát 4096 byte:** server thêm `clientId`/`from` có thể làm dòng vượt giới hạn. Server phải trả `MESSAGE_TOO_LARGE` thay vì gửi dòng quá dài → `enrichedMessageOverLimitIsRejected` (Task 7).
5. **Server dừng khi client đang chat:** client phải nhận callback `onClosed`, không bị treo → `clientNotifiedWhenServerStops` (Task 9).

---

## Cấu trúc file

```
pom.xml  .gitignore  README.md
config/users.properties                      # username=pbkdf2$... (chỉ hash, được commit)
scripts/gen-certs.ps1                        # keytool: server.p12 + server.crt
certificates/server/server.p12               # sinh ra, gitignore
certificates/client/server.crt               # sinh ra, gitignore
src/main/java/com/example/securechat/
  model/MessageType.java  model/ChatMessage.java  model/JsonCodec.java  model/InvalidMessageException.java
  common/Protocol.java  common/LineReader.java  common/MessageTooLargeException.java
  common/TlsContexts.java  common/ValidityCheckingTrustManager.java
  server/PasswordHasher.java  server/UserStore.java  server/HashPasswordTool.java  server/LoginThrottle.java
  server/ClientSession.java  server/ClientRegistry.java  server/ServerOptions.java
  server/ChatServer.java  server/ClientHandler.java  server/ServerMain.java
  client/ChatClient.java  client/ClientMain.java
src/test/java/com/example/securechat/
  testsupport/TestCerts.java  testsupport/RawTlsClient.java  testsupport/LogCapture.java  testsupport/TestUsers.java
  model/JsonCodecTest.java  common/LineReaderTest.java  common/TlsContextsTest.java
  server/PasswordHasherTest.java  server/UserStoreTest.java  server/LoginThrottleTest.java  server/ClientRegistryTest.java
  server/ChatServerLoginTest.java  server/ChatServerChatTest.java
  client/ChatClientTest.java
```

---

### Task 1: Scaffold Maven + model dùng chung

**Files:**
- Create: `pom.xml`, `.gitignore`, `src/main/java/com/example/securechat/model/{MessageType,ChatMessage,JsonCodec,InvalidMessageException}.java`
- Test: `src/test/java/com/example/securechat/model/JsonCodecTest.java`

**Interfaces:**
- Produces:
  - `enum MessageType { LOGIN("login"), LOGIN_RESULT("login_result"), MESSAGE("message"), SYSTEM("system"), USER_LEFT("user_left"), ERROR("error"), DISCONNECT("disconnect"); String value(); static Optional<MessageType> fromValue(String v) }`
  - `final class ChatMessage`: các field `String type, username, password, content, from; Boolean success; Integer clientId`, có getter/setter và constructor rỗng (cho Jackson). Factory: `login(String username, char[] password)`, `loginResult(boolean success, Integer clientId, String content)`, `message(String content)`, `chat(int clientId, String from, String content)`, `system(String content)`, `userLeft(int clientId, String from)` (content `"<from> đã rời phòng"`), `error(String code)`, `disconnect()`. Getter `Optional<MessageType> messageType()`. `toString()` in `password=***` khi password khác null.
  - `final class JsonCodec { static String encode(ChatMessage m); static ChatMessage decode(String line) throws InvalidMessageException }`: dùng một `ObjectMapper` static với `FAIL_ON_UNKNOWN_PROPERTIES=false` và `Include.NON_NULL`. `decode` ném lỗi khi JSON hỏng, kết quả null, hoặc `type` null/rỗng.
  - `class InvalidMessageException extends Exception`.

- [ ] **Step 1: Chạy `git init`, tạo `pom.xml` và `.gitignore`**
  - `pom.xml`: `com.example:secure-tcp-chat:1.0.0`, `maven.compiler.release=17`, UTF-8.
  - Dependency: `jackson-databind:2.17.2`; `junit-jupiter:5.10.3` (scope test).
  - Plugin: `maven-surefire-plugin:3.2.5`; `maven-shade-plugin:3.6.0` chạy ở phase `package`, `finalName` `secure-tcp-chat`, không khai báo Main-Class.
  - `.gitignore`: `target/`, `certificates/`, `*.p12`, `*.pfx`, `*.jks`, `*.key`, `*.crt`, `.idea/`, `*.iml`.
- [ ] **Step 2: Viết test bị fail**

```java
@Test void encodeOmitsNullFields() {
    assertEquals("{\"type\":\"message\",\"content\":\"Xin chào!\"}", JsonCodec.encode(ChatMessage.message("Xin chào!")));
}
@Test void roundTripLoginResult() throws Exception {
    ChatMessage m = JsonCodec.decode(JsonCodec.encode(ChatMessage.loginResult(true, 1, null)));
    assertEquals("login_result", m.getType()); assertTrue(m.getSuccess()); assertEquals(1, m.getClientId());
}
@Test void userLeftCarriesClientIdAndName() throws Exception {
    ChatMessage m = JsonCodec.decode(JsonCodec.encode(ChatMessage.userLeft(3, "chi")));
    assertEquals("user_left", m.getType()); assertEquals(3, m.getClientId());
    assertEquals("chi", m.getFrom()); assertEquals("chi đã rời phòng", m.getContent());
}
@Test void decodeRejectsBrokenJson() { assertThrows(InvalidMessageException.class, () -> JsonCodec.decode("{\"type\":")); }
@Test void decodeRejectsMissingType() { assertThrows(InvalidMessageException.class, () -> JsonCodec.decode("{\"content\":\"x\"}")); }
@Test void decodeKeepsUnknownTypeString() throws Exception {
    ChatMessage m = JsonCodec.decode("{\"type\":\"ping\"}");
    assertEquals("ping", m.getType()); assertTrue(m.messageType().isEmpty());
}
@Test void toStringMasksPassword() {
    String s = ChatMessage.login("an", "Secret#1".toCharArray()).toString();
    assertFalse(s.contains("Secret#1")); assertTrue(s.contains("***"));
}
```

- [ ] **Step 3: Chạy `mvn -q test -Dtest=JsonCodecTest`**. Kỳ vọng: compile FAIL.
- [ ] **Step 4: Implement 4 class theo Interfaces**
- [ ] **Step 5: Chạy lại.** Kỳ vọng: 7 test PASS.
- [ ] **Step 6: Commit** `git add -A && git commit -m "feat: scaffold maven project and shared chat message model"`

---

### Task 2: Đọc dòng có giới hạn (framing)

**Files:**
- Create: `src/main/java/com/example/securechat/common/{Protocol,LineReader,MessageTooLargeException}.java`
- Test: `src/test/java/com/example/securechat/common/LineReaderTest.java`

**Interfaces:**
- Produces:
  - `final class Protocol`:
    - Hằng số: `DEFAULT_PORT = 5000`, `MAX_LINE_BYTES = 4096`, `TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"}`, `HANDSHAKE_TIMEOUT_MS = 10_000`, `CONNECT_TIMEOUT_MS = 5_000`, `DEFAULT_LOGIN_TIMEOUT = Duration.ofSeconds(30)`, `DEFAULT_SEND_QUEUE_CAPACITY = 256`.
    - `static byte[] frame(String json)`: trả về `(json + "\n")` mã hóa UTF-8.
    - `static boolean fitsLine(String json)`: `true` khi số byte UTF-8 của `json` ≤ `MAX_LINE_BYTES`.
  - `final class LineReader { LineReader(InputStream in, int maxBytes); String readLine() throws IOException }`:
    - Đọc từng byte qua `BufferedInputStream`.
    - Gặp `\n` thì decode các byte đã gom bằng UTF-8, bỏ `\r` cuối nếu có.
    - EOF khi buffer rỗng → trả `null`; EOF giữa dòng → trả phần đã đọc.
    - Vượt `maxBytes` trước khi gặp `\n` → ném `MessageTooLargeException`.
  - `class MessageTooLargeException extends IOException`.

- [ ] **Step 1: Viết test bị fail**

```java
@Test void twoMessagesInOneChunk() throws IOException {
    LineReader r = reader("{\"a\":1}\n{\"b\":2}\n", 4096);
    assertEquals("{\"a\":1}", r.readLine()); assertEquals("{\"b\":2}", r.readLine()); assertNull(r.readLine());
}
@Test void splitInsideUtf8Character() throws Exception {
    byte[] all = "{\"content\":\"Xin chào\"}\n".getBytes(UTF_8);
    int cut = indexOf(all, (byte) 0xC3) + 1;  // after the first byte of 'à' (0xC3 0xA0)
    // PipedOutputStream: write all[0..cut), sleep 100 ms, write all[cut..]
    assertEquals("{\"content\":\"Xin chào\"}", r.readLine());
}
@Test void crlfIsStripped() throws IOException { assertEquals("x", reader("x\r\n", 10).readLine()); }
@Test void lineAtLimitIsAccepted() throws IOException { assertEquals(4096, reader("a".repeat(4096) + "\n", 4096).readLine().length()); }
@Test void lineOverLimitThrows() { assertThrows(MessageTooLargeException.class, () -> reader("a".repeat(4097) + "\n", 4096).readLine()); }
@Test void frameAppendsNewlineUtf8() { assertArrayEquals("chào\n".getBytes(UTF_8), Protocol.frame("chào")); }
@Test void fitsLineCountsBytesNotChars() { assertTrue(Protocol.fitsLine("ệ".repeat(1365))); assertFalse(Protocol.fitsLine("ệ".repeat(1366))); } // 3 bytes each
```

- [ ] **Step 2: Chạy `mvn -q test -Dtest=LineReaderTest`**. Kỳ vọng: FAIL.
- [ ] **Step 3: Implement theo Interfaces**
- [ ] **Step 4: Chạy lại.** Kỳ vọng: 7 test PASS.
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: add bounded newline-delimited line reader"`

---

### Task 3: Password hash, UserStore, LoginThrottle

**Files:**
- Create: `src/main/java/com/example/securechat/server/{PasswordHasher,UserStore,HashPasswordTool,LoginThrottle}.java`, `config/users.properties`
- Test: `src/test/java/com/example/securechat/server/{PasswordHasherTest,UserStoreTest,LoginThrottleTest}.java`, `src/test/java/com/example/securechat/testsupport/TestUsers.java`

**Interfaces:**
- Produces:
  - `final class PasswordHasher { static final int DEFAULT_ITERATIONS = 600_000; static String hash(char[] password, int iterations); static boolean verify(char[] password, String encoded) }`:
    - Dùng `PBKDF2WithHmacSHA256`, salt 16 byte từ `SecureRandom`, key 256 bit.
    - So sánh hash bằng `MessageDigest.isEqual`.
    - Chuỗi `encoded` sai định dạng → `false`, không ném exception.
  - `final class UserStore { static UserStore load(Path propertiesFile) throws IOException; static UserStore of(Map<String,String> usernameToEncoded); boolean verify(String username, char[] password); static String normalize(String username) }`:
    - `normalize` trả `trim().toLowerCase(Locale.ROOT)`, input null thì trả `""`.
    - Username rỗng hoặc không tồn tại vẫn chạy verify trên một hash giả (tạo một lần lúc khởi tạo, cùng số vòng) rồi trả `false`, để thời gian phản hồi không lộ username có tồn tại hay không.
  - `final class LoginThrottle { LoginThrottle(Clock clock, int maxFailures, Duration window, Duration lockout); static LoginThrottle defaults(); boolean isLocked(String ip, String username); void recordFailure(String ip, String username); void recordSuccess(String ip, String username) }`:
    - `defaults()` = `(Clock.systemUTC(), 5, 5 phút, 60 giây)`.
    - Key là `ip + "|" + UserStore.normalize(username)`, lưu trong `ConcurrentHashMap`.
    - Chỉ đếm các lần sai trong `window`. Đủ `maxFailures` thì khóa `lockout` tính từ lần sai cuối.
    - `recordSuccess` xóa bộ đếm của key đó.
  - `HashPasswordTool.main`: đọc password bằng `System.console().readPassword()` (không có console thì đọc dòng từ stdin), in ra chuỗi `pbkdf2$...`.
  - Test support:
    - `TestUsers.store()`: user `an/An@12345`, `binh/Binh@12345`, `chi/Chi@12345`, hash 1000 vòng.
    - `TestUsers.storeWithUsers(int n)`: user `u1..un` với password `Pass@<i>`.

- [ ] **Step 1: Viết test bị fail**

```java
// PasswordHasherTest
@Test void hashThenVerify() { String h = PasswordHasher.hash("An@12345".toCharArray(), 1000);
    assertTrue(h.startsWith("pbkdf2$1000$")); assertTrue(PasswordHasher.verify("An@12345".toCharArray(), h));
    assertFalse(PasswordHasher.verify("wrong".toCharArray(), h)); }
@Test void sameInputDifferentSalt() { assertNotEquals(PasswordHasher.hash("x".toCharArray(), 1000), PasswordHasher.hash("x".toCharArray(), 1000)); }
@Test void malformedEncodedReturnsFalse() { assertFalse(PasswordHasher.verify("x".toCharArray(), "garbage")); }
// UserStoreTest
@Test void verifyIsCaseInsensitiveOnUsername() { assertTrue(TestUsers.store().verify(" AN ", "An@12345".toCharArray())); }
@Test void unknownUserFails() { assertFalse(TestUsers.store().verify("ghost", "An@12345".toCharArray())); }
@Test void nullUsernameFails() { assertFalse(TestUsers.store().verify(null, "x".toCharArray())); }
@Test void loadFromProperties(@TempDir Path dir) throws IOException { /* write "an=" + hash(...); load; verify true */ }
// LoginThrottleTest (MutableClock: a Clock subclass with advance(Duration))
@Test void locksAfterFiveFailures()     { 5x recordFailure("1.2.3.4","an") -> isLocked true; 4x -> false }
@Test void lockExpiresAfterLockout()    { lock, clock.advance(61s) -> isLocked false }
@Test void failuresOutsideWindowIgnored(){ 4 failures, advance(6 min), 1 failure -> isLocked false }
@Test void keyedByIpAndUsername()       { lock ("1.2.3.4","an") -> ("1.2.3.4","binh") and ("5.6.7.8","an") not locked; ("1.2.3.4"," AN ") locked }
@Test void successResetsCounter()       { 4 failures, recordSuccess, 1 failure -> not locked }
```

- [ ] **Step 2: Chạy `mvn -q test -Dtest=PasswordHasherTest,UserStoreTest,LoginThrottleTest`**. Kỳ vọng: FAIL.
- [ ] **Step 3: Implement theo Interfaces**
- [ ] **Step 4: Chạy lại.** Kỳ vọng: 12 test PASS.
- [ ] **Step 5: Sinh `config/users.properties`**
  - Chạy `mvn -q package -DskipTests`, rồi `java -cp target/secure-tcp-chat.jar com.example.securechat.server.HashPasswordTool` cho từng tài khoản.
  - File có 3 dòng `an=`, `binh=`, `chi=`, ứng với password `An@12345`, `Binh@12345`, `Chi@12345`.
  - File chỉ chứa hash. Password demo chỉ ghi trong README.
- [ ] **Step 6: Commit** `git add -A && git commit -m "feat: add password hashing, user store and login throttle"`

---

### Task 4: Chứng chỉ + SSLContext (kiểm tra cả thời hạn)

**Files:**
- Create: `scripts/gen-certs.ps1`, `src/main/java/com/example/securechat/common/{TlsContexts,ValidityCheckingTrustManager}.java`, `src/test/java/com/example/securechat/testsupport/TestCerts.java`
- Test: `src/test/java/com/example/securechat/common/TlsContextsTest.java`

**Interfaces:**
- Produces:
  - `final class ValidityCheckingTrustManager extends X509ExtendedTrustManager`:
    - Constructor `ValidityCheckingTrustManager(X509ExtendedTrustManager delegate)`.
    - Ba overload `checkServerTrusted` (không tham số phụ / `Socket` / `SSLEngine`): gọi **đúng overload đó** trên `delegate` (để giữ kiểm tra hostname), sau đó gọi `chain[0].checkValidity()`. `CertificateExpiredException` và `CertificateNotYetValidException` để ném tiếp ra ngoài.
    - `checkClientTrusted*`: ném `CertificateException("client role only")`.
    - `getAcceptedIssuers()`: delegate.
    - Lý do cần class này: `PKIXValidator` của OpenJDK trả về ngay khi `chain[0]` có sẵn trong trust store, bỏ qua kiểm tra thời hạn.
  - `final class TlsContexts`:
    - `static SSLContext server(Path pkcs12, char[] password) throws GeneralSecurityException, IOException`: KeyStore PKCS12 → `KeyManagerFactory` → `SSLContext.getInstance("TLS")`.
    - `static SSLContext client(Path trustedCertPem) throws GeneralSecurityException, IOException`: cert X.509 → KeyStore PKCS12 rỗng (`setCertificateEntry("server", cert)`) → `TrustManagerFactory` PKIX → lấy `X509ExtendedTrustManager` → bọc bằng `ValidityCheckingTrustManager`.
    - `static void hardenServer(SSLServerSocket s)`: set `TLS_PROTOCOLS`, `setNeedClientAuth(false)`.
    - `static void hardenClient(SSLSocket s)`: set `TLS_PROTOCOLS`, `setEndpointIdentificationAlgorithm("HTTPS")`.
  - Test support `TestCerts.create(Path dir) -> TestCerts`, password `"test-pass"`. Sinh cert bằng `ProcessBuilder` gọi `Path.of(System.getProperty("java.home"),"bin","keytool")`. Mỗi keystore đi kèm file `.crt` export bằng `-exportcert -rfc`:
    - `serverP12` / `serverCrt`: theo Global Constraints.
    - `wrongHostP12` / `wrongHostCrt`: SAN và CN là `wronghost.invalid`.
    - `expiredP12` / `expiredCrt`: SAN đúng, `-startdate -10d -validity 1`.
    - `notYetValidP12` / `notYetValidCrt`: SAN đúng, `-startdate +10d -validity 30`.
    - `untrustedP12`: SAN đúng nhưng khác cặp khóa; client chỉ tin `serverCrt`.

- [ ] **Step 1: Viết test bị fail** (server TLS loopback chạy trên thread phụ; client gọi `connect` rồi `startHandshake`)

```java
@Test void handshakeSucceedsWithTrustedCert()   // client(serverCrt) -> server(serverP12) at "localhost": protocol in {"TLSv1.3","TLSv1.2"}
@Test void hostnameMismatchFails()              // client(wrongHostCrt) -> server(wrongHostP12) at "localhost": SSLHandshakeException
@Test void expiredCertFailsEvenWhenDirectlyTrusted() // client(expiredCrt) -> server(expiredP12): SSLHandshakeException whose cause chain contains CertificateExpiredException
@Test void notYetValidCertFails()               // client(notYetValidCrt) -> server(notYetValidP12): cause chain contains CertificateNotYetValidException
@Test void untrustedCertFails()                 // client(serverCrt) -> server(untrustedP12): SSLHandshakeException
@Test void legacyProtocolRejected()             // client setEnabledProtocols({"TLSv1.1"}): either IllegalArgumentException there, or SSLException on handshake; no session
@Test void wrongKeystorePasswordFails()         // TlsContexts.server(serverP12, "nope") throws IOException
```

- [ ] **Step 2: Chạy `mvn -q test -Dtest=TlsContextsTest`**. Kỳ vọng: FAIL.
- [ ] **Step 3: Implement `ValidityCheckingTrustManager`, `TlsContexts`, `TestCerts`**
- [ ] **Step 4: Chạy lại.** Kỳ vọng: 7 test PASS.
- [ ] **Step 5: Viết `scripts/gen-certs.ps1`**
  - Tham số: `-OutDir certificates` (mặc định), `-AddIp <ip>` (tùy chọn, thêm IP LAN vào SAN), `-StartDate <keytool startdate>` (tùy chọn, dùng cho kiểm thử TC04 thủ công).
  - Đọc `$env:CHAT_CERT_PASSWORD`; thiếu thì `throw "Thiếu CHAT_CERT_PASSWORD"`. Xóa file cũ trước khi tạo.
  - Lệnh sinh keystore: `keytool -genkeypair -alias server -keyalg RSA -keysize 2048 -validity 365 -keystore $OutDir/server/server.p12 -storetype PKCS12 -storepass $pw -keypass $pw -dname "CN=localhost, OU=Lab, O=SecureChat, L=Hanoi, C=VN" -ext "SAN=dns:localhost,ip:127.0.0.1[,ip:$AddIp]"`.
  - Lệnh export cert: `keytool -exportcert -rfc ... -file $OutDir/client/server.crt`.
  - Cuối cùng chạy `keytool -printcert -file ...` để kiểm tra (slide 8, bước 3).
- [ ] **Step 6: Kiểm tra script.** Chạy `$env:CHAT_CERT_PASSWORD="LabOnly_ChangeMe"; ./scripts/gen-certs.ps1`. Kỳ vọng: output có `DNSName: localhost` và `IPAddress: 127.0.0.1`; `git status` không liệt kê file nào trong `certificates/`.
- [ ] **Step 7: Commit** `git add -A && git commit -m "feat: add TLS contexts with validity-checking trust manager and cert script"`

---

### Task 5: ClientSession (hàng đợi gửi) + ClientRegistry

**Files:**
- Create: `src/main/java/com/example/securechat/server/{ClientSession,ClientRegistry}.java`
- Test: `src/test/java/com/example/securechat/server/ClientRegistryTest.java`

**Interfaces:**
- Consumes: `Protocol.frame`, `JsonCodec.encode`
- Produces:
  - `final class ClientSession`:
    - Constructor: `ClientSession(int id, String remoteAddress, OutputStream out, Closeable connection, int queueCapacity)`.
    - Getter: `int id()`, `String remoteAddress()`, `String username()`, `boolean isAuthenticated()`.
    - `void markAuthenticated(String username)`.
    - `void start(Consumer<IOException> onWriteFailure)`: khởi động writer thread daemon `writer-<id>`. Thread này lấy từng dòng từ `ArrayBlockingQueue<String>(queueCapacity)` rồi ghi `Protocol.frame(line)` + `flush`. Đây là thread duy nhất ghi vào `out`, nên các message không bao giờ chen byte vào nhau. Khi gặp `IOException` thì gọi `onWriteFailure` một lần rồi dừng.
    - `boolean enqueue(String jsonLine)`: dùng `offer`; trả `false` nếu hàng đợi đầy hoặc session đã đóng. Không bao giờ block.
    - `boolean send(ChatMessage m)`: gọi `enqueue(JsonCodec.encode(m))`.
    - `void closeGracefully(Duration maxWait)`: ngừng nhận message mới, đưa poison pill vào hàng đợi, rồi `join` writer tối đa `maxWait`. Nếu đang chạy trên chính writer thread thì không join. Sau đó gọi `abort()`.
    - `void abort()`: đóng `connection` ngay; idempotent (`AtomicBoolean`); nuốt `IOException`.
    - Trường `authenticated` khai báo `volatile`.
  - `final class ClientRegistry`:
    - API: `void add(ClientSession s)`, `ClientSession remove(int id)`, `List<ClientSession> authenticatedSnapshot()`, `boolean tryClaimUsername(String username, int id)`, `void releaseUsername(String username, int id)`, `int size()`.
    - Lưu trong `ConcurrentHashMap<Integer, ClientSession>` và `ConcurrentHashMap<String, Integer>` (claim username bằng `putIfAbsent`).
    - `remove` trả `null` từ lần gọi thứ hai.
    - `releaseUsername` dùng `remove(username, id)`.

- [ ] **Step 1: Viết test bị fail**

```java
@Test void removeIsIdempotent()           { reg.add(s1); assertSame(s1, reg.remove(1)); assertNull(reg.remove(1)); }
@Test void snapshotOnlyAuthenticated()    { reg.add(s1); reg.add(s2); s1.markAuthenticated("an"); assertEquals(List.of(s1), reg.authenticatedSnapshot()); }
@Test void usernameClaimIsExclusive()     { assertTrue(reg.tryClaimUsername("an", 1)); assertFalse(reg.tryClaimUsername("an", 2));
                                            reg.releaseUsername("an", 2); assertFalse(reg.tryClaimUsername("an", 3));
                                            reg.releaseUsername("an", 1); assertTrue(reg.tryClaimUsername("an", 3)); }
@Test void concurrentEnqueuesNeverInterleave() throws Exception {
    // session capacity 2000 over ByteArrayOutputStream; 8 threads x 200 send(message("T<i>-" + "x".repeat(500)))
    // closeGracefully(5s); split output by '\n': exactly 1600 lines, each decodes with JsonCodec
}
@Test void fullQueueRejectsWithoutBlocking() {
    // OutputStream whose write() blocks forever; capacity 4; start(); 
    // enqueue returns true for the first few then false; the 10th call returns within 100 ms
}
@Test void closeGracefullyFlushesPending()  { /* enqueue 3 messages, closeGracefully(2s) -> output has 3 lines, connection closed */ }
@Test void closeGracefullyGivesUpOnStuckWriter() { /* blocking OutputStream; closeGracefully(500ms) returns within 1s and connection Closeable was invoked */ }
@Test void abortIsIdempotent()              { s1.abort(); s1.abort(); /* Closeable invoked exactly once */ }
```

- [ ] **Step 2: Chạy `mvn -q test -Dtest=ClientRegistryTest`**. Kỳ vọng: FAIL.
- [ ] **Step 3: Implement theo Interfaces**
- [ ] **Step 4: Chạy lại.** Kỳ vọng: 8 test PASS.
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: add client sessions with bounded send queue and registry"`

---

### Task 6: ChatServer: accept loop, TLS handshake, login

**Files:**
- Create: `src/main/java/com/example/securechat/server/{ServerOptions,ChatServer,ClientHandler}.java`, `src/test/java/com/example/securechat/testsupport/{RawTlsClient,LogCapture}.java`
- Test: `src/test/java/com/example/securechat/server/ChatServerLoginTest.java`

**Interfaces:**
- Consumes: `TlsContexts.server/hardenServer`, `UserStore`, `LoginThrottle`, `ClientRegistry`, `ClientSession`, `LineReader`, `JsonCodec`, `Protocol`
- Produces:
  - `record ServerOptions(Duration loginTimeout, int sendQueueCapacity, LoginThrottle throttle)`, kèm `static ServerOptions defaults()` = `(Protocol.DEFAULT_LOGIN_TIMEOUT, Protocol.DEFAULT_SEND_QUEUE_CAPACITY, LoginThrottle.defaults())`.
  - `final class ChatServer`:
    - Khai báo: `static final Logger LOG = Logger.getLogger("securechat.server")`; constructor `ChatServer(SSLContext ctx, UserStore users, int port, ServerOptions options)`; các method `int start() throws IOException`, `void stop()`, `ClientRegistry registry()`.
    - `start()`: tạo `SSLServerSocket` bind `0.0.0.0:port` (test dùng port 0), gọi `hardenServer`, log `Server listening on port <port>`, chạy accept loop trên thread riêng, trả về port thực.
    - Accept loop: mỗi kết nối được gán `id = nextId.getAndIncrement()` (bắt đầu từ 1) rồi submit `ClientHandler` vào `Executors.newCachedThreadPool()`. Accept loop không bao giờ chờ handler.
    - `stop()`: đóng server socket, `abort()` mọi session, `shutdownNow` executor. Gọi nhiều lần vẫn an toàn.
  - `final class ClientHandler implements Runnable`, luồng xử lý:
    1. Set `soTimeout(HANDSHAKE_TIMEOUT_MS)`, gọi `startHandshake()`. Nếu lỗi: log `[BLOCK] TLS handshake failed: client <id> (<ip>)`, đóng socket, return.
    2. Tạo `ClientSession` (với `options.sendQueueCapacity()`) và gọi `start(e -> server.removeClient(id, "send failed"))`, rồi `registry.add`. Log `[PASS] TLS authenticated: client <id> (<ip>) <protocol>`.
    3. Set `soTimeout(loginTimeout)` và bắt đầu vòng đọc bằng `LineReader(MAX_LINE_BYTES)`.
    4. Xử lý message khi chưa authenticated:
       - Type khác `login`: gửi `error("NOT_AUTHENTICATED")`, giữ kết nối.
       - `login` khi `throttle.isLocked(ip, username)`: gửi `loginResult(false, null, "Đăng nhập sai quá nhiều lần, thử lại sau")`, log `[BLOCK] Login throttled: client <id>`, rồi đóng. Không verify password.
       - `login` với password sai: `throttle.recordFailure`, gửi `loginResult(false, null, "Username hoặc Password không đúng")`, log `[BLOCK] Login failed: client <id>`, rồi đóng.
       - `login` đúng nhưng `!tryClaimUsername`: gửi `loginResult(false, null, "Tài khoản đang online")` rồi đóng.
       - `login` đúng: `throttle.recordSuccess`, `markAuthenticated(normalized)`, gửi `loginResult(true, id, null)`, set `soTimeout(0)`, broadcast `system("<username> đã tham gia")`.
    5. Xóa mảng `char[]` password bằng `Arrays.fill` ngay sau khi verify.
    6. "Đóng" ở trên nghĩa là thoát vòng đọc; `finally` sẽ gọi `server.removeClient(id, reason)`. `removeClient` dùng `closeGracefully(2s)` để `login_result` kịp được gửi đi. Task này cài phiên bản tối thiểu của `removeClient`; Task 7 hoàn thiện.
    7. `SocketTimeoutException` khi chưa authenticated: reason `login timeout`.
  - Test support:
    - `RawTlsClient.connect(SSLContext ctx, int port)` với các method `send(ChatMessage)`, `sendRaw(String)`, `ChatMessage read(Duration)` (trả `null` khi EOF), `boolean isClosedByPeer(Duration)`, `close()`.
    - `RawTlsClient.connectSlowReader(SSLContext ctx, int port)`: set `setReceiveBufferSize(4096)` trước khi connect và không bao giờ tự đọc.
    - `LogCapture.attach(Logger)` với `List<String> messages()` và `detach()`.

- [ ] **Step 1: Viết test bị fail**

`@BeforeAll` sinh `TestCerts`. `@BeforeEach` start server với port 0, `loginTimeout` 1 s, `TestUsers.store()` và `LoginThrottle` dùng `MutableClock`. `@AfterEach` stop server.

```java
@Test void tc01_validLoginGetsClientId()           // an/An@12345 -> success=true, clientId>=1
@Test void tc02_wrongPasswordRejectedAndClosed()   // an/wrong -> success=false "Username hoặc Password không đúng"; isClosedByPeer(2s); no authenticated session; log has "[BLOCK] Login failed"
@Test void unknownUserSameMessageAsWrongPassword() // ghost/x -> same content as tc02
@Test void bruteForceIsThrottled()                 // 5 new connections each with an/wrong -> 6th connection with an/An@12345 gets "Đăng nhập sai quá nhiều lần, thử lại sau"; log has "[BLOCK] Login throttled"; after clock.advance(61s) correct login succeeds
@Test void messageBeforeLoginIsRejected()          // message("hi") first -> error NOT_AUTHENTICATED; then valid login on same connection succeeds
@Test void tc05_plainTcpIsRejected()               // plain java.net.Socket writes a login JSON line; read returns -1 within 5s; log has "[BLOCK] TLS handshake failed"; afterwards RawTlsClient login succeeds
@Test void duplicateLoginIsRejected()              // A logs in as an; B logs in as an -> "Tài khoản đang online"; A still logged in (registry has A authenticated)
@Test void idleUnauthenticatedClientIsDisconnected() // TLS connect, send nothing -> isClosedByPeer(3s)
@Test void passwordNeverLogged()                   // after tc01 + tc02 flows, no captured log line contains "An@12345" or "wrong"
@Test void manyClientsAcceptedConcurrently()       // separate server with TestUsers.storeWithUsers(10); u1..u10 log in in parallel -> all success, 10 distinct clientIds
```

- [ ] **Step 2: Chạy `mvn -q test -Dtest=ChatServerLoginTest`**. Kỳ vọng: FAIL.
- [ ] **Step 3: Implement `ServerOptions`, `ChatServer`, `ClientHandler` (phần handshake + login), `RawTlsClient`, `LogCapture`**
- [ ] **Step 4: Chạy lại.** Kỳ vọng: 10 test PASS.
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: add TLS chat server with accept loop, login and throttling"`

---

### Task 7: ChatServer: định tuyến message, broadcast, ngắt kết nối

**Files:**
- Modify: `src/main/java/com/example/securechat/server/{ChatServer,ClientHandler}.java`
- Test: `src/test/java/com/example/securechat/server/ChatServerChatTest.java`

**Interfaces:**
- Consumes: mọi thứ của Task 5, 6
- Produces:
  - `ChatServer.broadcast(ChatMessage m)`:
    - Encode **một lần** thành `line`.
    - Duyệt `registry.authenticatedSnapshot()` và gọi `session.enqueue(line)`.
    - `enqueue` trả `false` → `removeClient(id, "send queue full")`, rồi tiếp tục với session sau.
    - Không bao giờ block trên socket của client.
  - `ChatServer.removeClient(int id, String reason)`:
    - `registry.remove(id)`; trả `null` thì return ngay (idempotent).
    - Còn lại: `releaseUsername`.
    - Reason `send queue full` hoặc `send failed` → gọi `abort()`; reason khác → gọi `closeGracefully(2s)`.
    - Log `[INFO] Client <id> disconnected (<ip>) - <reason>`.
    - Nếu session đã authenticated: broadcast `userLeft(id, username)`.
  - Định tuyến khi đã authenticated:
    - `message` với content null/blank → `error("EMPTY_MESSAGE")`.
    - `message` hợp lệ → dựng `chat(id, username, content)` và encode. Nếu `!Protocol.fitsLine(json)` thì gửi `error("MESSAGE_TOO_LARGE")` cho sender, **giữ kết nối**, không broadcast. Ngược lại broadcast (sender cũng nhận).
    - `login` → `error("ALREADY_AUTHENTICATED")`.
    - `disconnect` → thoát vòng đọc với reason `client requested`.
    - Type nằm ngoài enum → `error("UNKNOWN_TYPE")`.
    - `InvalidMessageException` → `error("INVALID_JSON")`, giữ kết nối.
    - `MessageTooLargeException` khi đọc → `error("MESSAGE_TOO_LARGE")` rồi đóng (không thể đồng bộ lại stream).
    - EOF → reason `connection closed`; `IOException` → reason `io error: <SimpleName>`.
    - Mọi đường thoát đều đi qua `finally { server.removeClient(id, reason) }`.

- [ ] **Step 1: Viết test bị fail** (3 client an/binh/chi đã login sẵn; một số test dùng `ServerOptions` với `sendQueueCapacity` = 8)

```java
@Test void tc01_broadcastReachesAllThree()        // an sends "Xin chào mọi người!" -> an, binh, chi read type=message, from="an", exact content
@Test void joinNoticeSentToOthers()               // chi logs in last -> an reads system "chi đã tham gia"
@Test void tc06_abruptDisconnect()                // binh socket closed without disconnect -> an and chi read type=user_left, clientId=binhId, from="binh"; registry size 2; an->chi chat works; log has "[INFO] Client <binhId> disconnected"
@Test void gracefulDisconnectNotifiesOnce()       // chi sends disconnect -> an gets exactly one user_left for chi within 1s
@Test void invalidJsonKeepsConnection()           // an sendRaw("{oops") -> error INVALID_JSON; next message still broadcast
@Test void unknownTypeRejected()                  // sendRaw("{\"type\":\"ping\"}") -> error UNKNOWN_TYPE
@Test void emptyMessageRejected()                 // message("   ") -> error EMPTY_MESSAGE; binh receives nothing within 500 ms
@Test void enrichedMessageAtLimitIsDelivered()    // k = max count of "ệ" such that fitsLine(encode(chat(anId,"an","ệ"*k))); an sends "ệ"*k -> binh receives it intact
@Test void enrichedMessageOverLimitIsRejected()   // an sends "ệ"*(k+1) (incoming line still <= 4096) -> an gets error MESSAGE_TOO_LARGE, still connected; binh receives nothing within 500 ms
@Test void oversizedIncomingLineClosesOnlyThatClient() // an sendRaw("x".repeat(5000)) -> error MESSAGE_TOO_LARGE then closed; binh<->chi chat works
@Test void secondLoginRejected()                  // an sends login again -> error ALREADY_AUTHENTICATED
@Test void usernameFreedAfterDisconnect()         // an disconnects; new connection logs in as an -> success
@Test void slowClientDoesNotBlockOthers()         // queue capacity 8; slow = connectSlowReader logged in as chi; an sends 2000 messages of "x".repeat(3500) -> binh receives all 2000 chat messages in order within 20s; log has "[INFO] Client <chiId> disconnected" with reason "send queue full"; registry no longer contains chi
```

- [ ] **Step 2: Chạy `mvn -q test -Dtest=ChatServerChatTest`**. Kỳ vọng: FAIL.
- [ ] **Step 3: Implement theo Interfaces**
- [ ] **Step 4: Chạy `mvn -q test`** (toàn bộ). Kỳ vọng: tất cả PASS.
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: add message routing, size-checked broadcast and disconnect cleanup"`

---

### Task 8: ServerMain

**Files:**
- Create: `src/main/java/com/example/securechat/server/ServerMain.java`

**Interfaces:**
- Consumes: `TlsContexts.server`, `UserStore.load`, `ServerOptions.defaults`, `ChatServer`
- Produces: `ServerMain.main(String[] args)` với tham số tùy chọn `--port 5000 --keystore certificates/server/server.p12 --users config/users.properties`.
  - Env `CHAT_CERT_PASSWORD` null/blank → in `Thiếu CHAT_CERT_PASSWORD` ra stderr, exit 1.
  - Không tìm thấy file → in `Không tìm thấy <path>`, exit 1.
  - Đăng ký shutdown hook gọi `stop()`.

Task này không có unit test (chỉ là phần nối các thành phần lại); kiểm chứng bằng bước thủ công bên dưới.

- [ ] **Step 1: Implement**
- [ ] **Step 2: Kiểm tra thủ công**
  - Chạy `mvn -q package -DskipTests`, rồi chạy server khi chưa set env. Kỳ vọng: in `Thiếu CHAT_CERT_PASSWORD`, `$LASTEXITCODE` = 1.
  - Set `$env:CHAT_CERT_PASSWORD="LabOnly_ChangeMe"` rồi chạy lại. Kỳ vọng: log `Server listening on port 5000`; Ctrl+C dừng sạch.
- [ ] **Step 3: Commit** `git add -A && git commit -m "feat: add server entry point"`

---

### Task 9: ChatClient + ClientMain (console)

**Files:**
- Create: `src/main/java/com/example/securechat/client/{ChatClient,ClientMain}.java`
- Test: `src/test/java/com/example/securechat/client/ChatClientTest.java`

**Interfaces:**
- Consumes: `TlsContexts.client/hardenClient`, `LineReader`, `JsonCodec`, `Protocol`, `ChatServer` (trong test)
- Produces:
  - `final class ChatClient implements AutoCloseable`:
    - Khai báo: constructor `ChatClient(SSLContext ctx)`; các method `void connect(String host, int port, int timeoutMs) throws IOException`, `String negotiatedProtocol()`, `ChatMessage login(String username, char[] password) throws IOException`, `void startReceiving(Consumer<ChatMessage> onMessage, Consumer<Exception> onClosed)`, `void send(String content) throws IOException`, `void close()`.
    - `connect`: tạo `new Socket()`, `connect(new InetSocketAddress(host, port), timeoutMs)`, rồi `createSocket(raw, host, port, true)`, `hardenClient`, `startHandshake()`.
    - `login`: đọc đồng bộ tới khi gặp `login_result`, bỏ qua message khác, timeout 10 s.
    - `startReceiving`: daemon thread `chat-receive` là thread duy nhất đọc stream; gọi `onClosed` đúng một lần.
    - `send`: nếu `!Protocol.fitsLine(encoded)` thì ném `IllegalArgumentException("Tin nhắn quá dài")`. Ngược lại ghi dưới `ReentrantLock`, dùng chung lock với `close`.
    - `close`: gửi `disconnect` (best-effort), đóng socket; idempotent.
  - `ClientMain.main(args)` với tham số `[host=localhost] [port=5000] [--cert certificates/client/server.crt]`:
    - Nhập username, password (dùng `System.console().readPassword`, không có console thì đọc từ stdin).
    - In `Đã kết nối <host>:<port> (<protocol>)`.
    - Format in: message → `[from] content`; system và user_left → `* content`; error → `! content`. Gõ `/quit` để thoát.
    - Thông báo lỗi kết nối (slide 30):
      - `ConnectException` → `Không kết nối được: server chưa chạy hoặc sai cổng`
      - `SocketTimeoutException` → `Hết thời gian kết nối`
      - `UnknownHostException` → `Sai địa chỉ server`
      - `SSLHandshakeException` → `Chứng chỉ server không hợp lệ: <message>`

- [ ] **Step 1: Viết test bị fail** (server thật, port 0, `TestCerts`)

```java
@Test void connectLoginAndReceiveOwnBroadcast() // "localhost", login an; startReceiving; send "Xin chào!" -> onMessage from=an within 2s; protocol in {"TLSv1.3","TLSv1.2"}
@Test void connectByIpMatchesSan()              // "127.0.0.1" with serverCrt -> handshake OK
@Test void tc03_wrongHostCertRejected()          // server wrongHostP12, client trusts wrongHostCrt, host "localhost" -> SSLHandshakeException, no login sent (server log has no "Login")
@Test void tc04_expiredCertRejected()            // server expiredP12, client trusts expiredCrt -> SSLHandshakeException
@Test void tc04_untrustedCertRejected()          // server untrustedP12, client trusts serverCrt -> SSLHandshakeException
@Test void tooLongMessageRejectedLocally()       // send("x".repeat(5000)) -> IllegalArgumentException; connection still usable
@Test void connectionRefusedIsConnectException() // port with nothing listening -> ConnectException
@Test void clientNotifiedWhenServerStops()       // logged in + receiving; server.stop() -> onClosed within 3s
@Test void closeIsIdempotent()                   // close(); close(); no exception
```

- [ ] **Step 2: Chạy `mvn -q test -Dtest=ChatClientTest`**. Kỳ vọng: FAIL.
- [ ] **Step 3: Implement `ChatClient` rồi `ClientMain`**
- [ ] **Step 4: Chạy `mvn -q test`**. Kỳ vọng: tất cả PASS.
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: add TLS chat client and console entry point"`

---

### Task 10: README + nghiệm thu thủ công TC01–TC06 (slide 35)

**Files:**
- Create: `README.md`, `docs/acceptance-log.md`

Task này là tài liệu và nghiệm thu, không theo TDD.

- [ ] **Step 1: Viết `README.md`**, gồm:
  - Yêu cầu môi trường (JDK 17+, Maven)
  - Tạo chứng chỉ bằng `scripts/gen-certs.ps1`
  - `mvn package`
  - Chạy server với `CHAT_CERT_PASSWORD`
  - Chạy 3 client: `java -cp target/secure-tcp-chat.jar com.example.securechat.client.ClientMain localhost 5000`
  - Tài khoản demo an/binh/chi, ghi rõ "chỉ dùng cho bài thực hành"
  - Thêm user bằng `HashPasswordTool`
  - Chạy qua LAN: `-AddIp`, mở firewall TCP 5000, chỉ copy `server.crt`
  - Bảng TC01–TC06 ↔ test tự động
  - Cảnh báo về chứng chỉ tự ký
- [ ] **Step 2: Nghiệm thu thủ công.** Với mỗi TC, ghi lệnh đã chạy, output client và các dòng log server vào `docs/acceptance-log.md`:
  - **TC01:** server + 3 client an/binh/chi; an gửi `Xin chào mọi người!`, cả ba thấy `[an] Xin chào mọi người!`.
  - **TC02:** client thứ tư nhập `an` với password sai, thấy `Username hoặc Password không đúng`; log có `[BLOCK] Login failed`.
  - **TC03:** `ClientMain 127.0.0.2 5000` (cert chỉ có SAN `localhost`, `127.0.0.1`), kỳ vọng `Chứng chỉ server không hợp lệ: ...` (lỗi `No subject alternative names matching IP address 127.0.0.2`). Nếu máy không route được `127.0.0.2`, dùng IP LAN của máy (không có trong SAN).
  - **TC04 hết hạn:** `./scripts/gen-certs.ps1 -OutDir certificates-expired -StartDate -400d` (`-10d` vẫn cho chứng chỉ còn hạn vì `-validity` là 365 ngày; cần lùi hơn 365 ngày), chạy server với `--keystore certificates-expired/server/server.p12`, client dùng `--cert certificates-expired/client/server.crt`. Kỳ vọng `Chứng chỉ server không hợp lệ` có nhắc tới hết hạn.
  - **TC04 không tin cậy:** server dùng keystore chuẩn, client dùng `--cert certificates-expired/client/server.crt` (khác cặp khóa). Kỳ vọng bị từ chối.
  - **TC05:** từ PowerShell: `$c=[Net.Sockets.TcpClient]::new('localhost',5000); $s=$c.GetStream(); $b=[Text.Encoding]::UTF8.GetBytes('{"type":"login","username":"an","password":"x"}' + "`n"); $s.Write($b,0,$b.Length); Start-Sleep 2; $s.Read((New-Object byte[] 16),0,16)`. Server phải không đọc được login nào, đóng kết nối (hoặc chỉ gửi bản ghi TLS alert, không có JSON); log có `[BLOCK] TLS handshake failed`.
  - **TC06:** đóng đột ngột cửa sổ binh; an và chi thấy `* binh đã rời phòng`; log có `[INFO] Client <id> disconnected`; an và chi tiếp tục chat được.
  - Cuối cùng chạy `Select-String -Path <file log server> -Pattern "An@12345"`, kỳ vọng không có kết quả.
- [ ] **Step 3: Chạy `mvn -q test`** lần cuối. Kỳ vọng: tất cả PASS.
- [ ] **Step 4: Commit** `git add -A && git commit -m "docs: add README and manual acceptance log"`

---

## Ngoài phạm vi (đã cân nhắc, không làm)

- **Heartbeat/ping cho client đã login nhưng im lặng:** chỉ có timeout cho client chưa login. Client không đọc thì đã bị phát hiện qua hàng đợi gửi đầy.
- **Rate limit theo IP cho mọi username:** chỉ giới hạn theo cặp (IP, username). Một nguồn có thể thử nhiều username khác nhau, mỗi username tối đa 5 lần mỗi 5 phút. Khi chạy trên localhost, mọi client đều có cùng IP, nên khóa riêng theo IP sẽ chặn cả client hợp lệ.
- **Writer bị kẹt khi mạng treo hẳn:** thread ghi có thể kẹt trong `write`. Hàng đợi đầy sẽ kích hoạt `abort()` đóng socket để giải phóng thread, nhưng không có timeout cho từng lần ghi.
- **Các phần không làm:** kiểm tra thu hồi chứng chỉ (OCSP/CRL), client certificate (mTLS), giao diện đồ họa (Swing/JavaFX).
