# Zalo-style Secure Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nâng Secure TCP Chat thành ứng dụng chat kiểu Zalo với GUI JavaFX, đạt đủ 11 tiêu chí: P2P, Group, File, Emoji, History, Image, Video, Sound, State, Time, Sender.

**Architecture:** Giữ nguyên server TLS và giao thức JSON 1 dòng ≤ 4096 byte, mở rộng thêm hội thoại (`lobby`/`dm:`/`group:`), timestamp do server đóng dấu, lịch sử JSONL, presence, và truyền file theo chunk base64 trên cùng kết nối TLS. Client: mở rộng `ChatClient`, thêm package `gui` (JavaFX 21) với model thuần Java `ChatState` để test được.

**Tech Stack:** Java 17 (`release=17`, máy có JDK 24), Maven 3.9, Jackson 2.17.2, JUnit 5.10.3, OpenJFX 21.0.5 (`javafx-controls`, `javafx-media`), `javafx-maven-plugin` 0.0.8, `javax.sound.sampled`.

**Spec:** `docs/superpowers/specs/2026-11-21-zalo-style-chat-design.md` (và plan gốc `docs/superpowers/plans/2026-11-14-secure-tcp-chat.md` cho phần đã có).

## Global Constraints

- Mọi mục trong "Global Constraints" của plan 2026-11-14 vẫn áp dụng (TLS 1.2/1.3 only, không trust-all, không commit key/mật khẩu, không log mật khẩu, mỗi dòng ≤ 4096 byte cả hai chiều).
- 75 test cũ phải pass sau mỗi task; không đổi chữ ký public đang được test cũ dùng (chỉ thêm).
- Client console (`ClientMain`) vẫn chạy được ở Phòng chung; message không có `to` = `lobby`.
- Conversation id: `lobby`; `dm:<a>:<b>` (username normalize bằng `UserStore.normalize`, `a < b` theo `String.compareTo`); `group:<uuid>`.
- `Protocol.MAX_FILE_BYTES = 25 * 1024 * 1024`; `Protocol.FILE_CHUNK_BYTES = 2048` (raw, trước base64); `Protocol.HISTORY_LIMIT = 50`; ghi âm tối đa 60 s, WAV PCM 16000 Hz 16-bit mono.
- `fileId` là UUID dạng chuẩn (`UUID.fromString` + so lại `toString()`), mọi giá trị khác bị từ chối → không thể path traversal.
- Thư mục dữ liệu server mặc định `data/`, thêm `data/` vào `.gitignore`. Không commit file lịch sử, file upload.
- Chuỗi hiển thị tiếng Việt, code/comment/commit tiếng Anh. Stage file theo đường dẫn, không `git add -A`; không stage `pics/`.
- Màu chủ đạo `#0068FF`; tin của mình nền `#E5EFFF` bên phải, tin người khác nền trắng bên trái; nền khung chat `#EEF0F1`.

## Giao thức mở rộng

Trường mới trong `ChatMessage` (đều nullable, null thì không serialize): `to` String, `timestamp` Long, `kind` String (`text|image|video|audio|file`, null = text), `fileId` String, `fileName` String, `mime` String, `size` Long, `index` Integer, `data` String (base64), `online` Boolean, `members` List<String>, `onlineUsers` List<String>, `groupName` String, `history` Boolean.

Type mới (`MessageType`):
- `presence` (S→C): `from`, `online`.
- `user_list` (S→C, ngay sau login_result thành công): `members` = mọi username trong UserStore (sắp xếp), `onlineUsers`.
- `group_create` (C→S): `groupName` (1–50 ký tự, không blank), `members` (người tạo tự được thêm; mọi member phải tồn tại).
- `group_info` (S→C): `to`=`group:<uuid>`, `groupName`, `members`. Gửi cho các member online khi tạo, và cho user mỗi nhóm của họ khi login.
- `history_request` (C→S): `to`. Server gửi tối đa 50 tin mới nhất dạng `message` có `history:true` (cũ → mới), rồi `history_end` với `to`.
- `history_end` (S→C).
- `file_start` (C→S): `fileId`, `to`, `fileName`, `mime`, `size`, `kind` (≠ text).
- `file_chunk` (hai chiều): `fileId`, `index` (0-based, liên tiếp), `data`.
- `file_end` (hai chiều): `fileId`. C→S: server kiểm tra tổng byte == `size`, lưu, rồi phát `message` (kind, fileId, fileName, mime, size, timestamp, from, to) tới hội thoại và ghi lịch sử. S→C: kết thúc download.
- `file_get` (C→S): `fileId`; server kiểm tra người xin là thành viên hội thoại của file rồi gửi chunk + `file_end`.

`message` S→C luôn có `to`, `timestamp`, `from`, `clientId`. Tin gửi tới `dm:`/`group:` chỉ đi tới session của thành viên (gồm cả người gửi — client chỉ hiển thị tin từ server echo).

Error code mới (`error.content`, kèm `fileId`/`to` nếu liên quan): `NOT_MEMBER`, `UNKNOWN_CONVERSATION`, `INVALID_GROUP`, `FILE_TOO_LARGE`, `FILE_INVALID`, `FILE_NOT_FOUND`.

## Review Focus

1. Người không thuộc `dm:`/`group:` gửi tin, xin lịch sử hoặc `file_get` → phải nhận `NOT_MEMBER`, không lộ nội dung (test ở Task 3, 4).
2. Tải file lớn trong lúc nhóm đang chat → người tải không bị server ngắt vì "send queue full", tin thường vẫn tới (test `downloadDoesNotStarveChat` ở Task 4).
3. Upload bị ngắt giữa chừng / chunk sai thứ tự / vượt `size` khai báo → server xóa file `.part`, trả `FILE_INVALID`, kết nối vẫn dùng được (Task 4).
4. Tin P2P gửi khi người nhận offline → người nhận thấy trong lịch sử khi đăng nhập lại (Task 3 `offlineDmAppearsInHistory`).
5. Server khởi động lại → nhóm và lịch sử vẫn còn (Task 2 store tests reload từ đĩa).

---

### Task 1: Mở rộng model và protocol

**Files:**
- Modify: `src/main/java/com/example/securechat/model/ChatMessage.java`, `model/MessageType.java`, `common/Protocol.java`
- Create: `src/main/java/com/example/securechat/model/ConversationIds.java`
- Test: `src/test/java/com/example/securechat/model/ChatMessageExtTest.java`, `model/ConversationIdsTest.java`

**Interfaces:**
- Produces: các field ở mục "Giao thức mở rộng" với getter/setter Jackson; `MessageType` thêm `PRESENCE("presence")`, `USER_LIST("user_list")`, `GROUP_CREATE("group_create")`, `GROUP_INFO("group_info")`, `HISTORY_REQUEST("history_request")`, `HISTORY_END("history_end")`, `FILE_START("file_start")`, `FILE_CHUNK("file_chunk")`, `FILE_END("file_end")`, `FILE_GET("file_get")`.
- Factory mới (static trên `ChatMessage`): `messageTo(String to, String content)`, `chatIn(String to, int clientId, String from, String content, long timestamp)`, `presence(String username, boolean online)`, `userList(List<String> members, List<String> onlineUsers)`, `groupCreate(String name, List<String> members)`, `groupInfo(String groupId, String name, List<String> members)`, `historyRequest(String to)`, `historyEnd(String to)`, `fileStart(String fileId, String to, String fileName, String mime, long size, String kind)`, `fileChunk(String fileId, int index, String base64)`, `fileEnd(String fileId)`, `fileGet(String fileId)`, `error(String code, String fileId, String to)`; instance `ChatMessage copy()` (shallow copy mọi field).
- `ConversationIds`: `LOBBY = "lobby"`; `static String dm(String u1, String u2)`; `static boolean isDm/isGroup/isLobby(String id)`; `static List<String> dmMembers(String id)`; `static String newGroupId()`; `static boolean isValidFileId(String s)`.
- `Protocol`: `MAX_FILE_BYTES`, `FILE_CHUNK_BYTES`, `HISTORY_LIMIT` theo Global Constraints.

- [ ] **Step 1: Viết test fail**

```java
@Test void dmIdIsSortedAndNormalized() {
    assertEquals("dm:an:binh", ConversationIds.dm("Binh ", "an"));
    assertEquals(List.of("an", "binh"), ConversationIds.dmMembers("dm:an:binh"));
}
@Test void rejectsSelfDmAndBadIds() {
    assertThrows(IllegalArgumentException.class, () -> ConversationIds.dm("an", "AN"));
    assertFalse(ConversationIds.isValidFileId("../../etc/passwd"));
    assertTrue(ConversationIds.isValidFileId(UUID.randomUUID().toString()));
}
@Test void fullFileChunkFitsOneLine() {
    String b64 = Base64.getEncoder().encodeToString(new byte[Protocol.FILE_CHUNK_BYTES]);
    String line = JsonCodec.encode(ChatMessage.fileChunk(UUID.randomUUID().toString(), Integer.MAX_VALUE, b64));
    assertTrue(Protocol.fitsLine(line));
}
@Test void newFieldsRoundTripAndNullsOmitted() throws Exception {
    ChatMessage m = ChatMessage.chatIn("dm:an:binh", 3, "an", "hi", 1700000000000L);
    ChatMessage back = JsonCodec.decode(JsonCodec.encode(m));
    assertEquals("dm:an:binh", back.getTo()); assertEquals(1700000000000L, back.getTimestamp());
    assertFalse(JsonCodec.encode(ChatMessage.message("x")).contains("timestamp"));
    assertEquals(MessageType.FILE_GET, MessageType.fromValue("file_get").orElseThrow());
}
```

- [ ] **Step 2:** `mvn -q test -Dtest=ChatMessageExtTest,ConversationIdsTest` → FAIL (không compile).
- [ ] **Step 3:** Implement theo Interfaces. `toString()` vẫn che password và **không in `data`** (chỉ in `data=<n chars>`).
- [ ] **Step 4:** `mvn -q test` → exit 0, 75 test cũ + test mới pass.
- [ ] **Step 5:** Commit `feat: extend protocol model for conversations, presence and files`.

### Task 2: Lưu trữ phía server

**Files:**
- Create: `src/main/java/com/example/securechat/server/HistoryStore.java`, `GroupStore.java`, `FileStore.java`
- Modify: `server/ServerOptions.java`, `server/UserStore.java`, `server/ServerMain.java`, `.gitignore`
- Test: `src/test/java/com/example/securechat/server/HistoryStoreTest.java`, `GroupStoreTest.java`, `FileStoreTest.java`

**Interfaces:**
- `ServerOptions` thêm component cuối `Path dataDir`; giữ constructor 3 tham số cũ (dùng `Files.createTempDirectory("securechat-data")`) để test cũ không đổi; `defaults()` dùng `Path.of("data")`. `ServerMain` thêm tham số `--data <dir>`.
- `UserStore.usernames()` → `List<String>` đã sắp xếp.
- `HistoryStore(Path dir)`: `synchronized void append(String conversationId, ChatMessage m)` (ghi `JsonCodec.encode(m)` + `\n` vào `<dir>/<id với ':' thay bằng '_'>.jsonl`, UTF-8, append); `List<ChatMessage> recent(String conversationId, int limit)` (cũ → mới, bỏ qua dòng hỏng).
- `GroupStore(Path file)`: `record Group(String id, String name, List<String> members)`; `Group create(String name, List<String> members)`; `Optional<Group> find(String id)`; `List<Group> groupsOf(String username)`. Ghi `groups.json` qua file tạm + `Files.move(ATOMIC_MOVE)`, load lúc khởi tạo.
- `FileStore(Path dir)`: `record FileMeta(String fileId, String conversationId, String uploader, String fileName, String mime, long size, String kind)`; `Upload begin(FileMeta meta)` (ném `IllegalArgumentException` nếu fileId không hợp lệ/đã tồn tại hoặc `size` ∉ (0, MAX_FILE_BYTES]); `Upload` có `void write(int index, byte[] bytes)` (index phải liên tiếp, tổng không vượt `size`, sai → `IllegalStateException`), `FileMeta complete()` (tổng ≠ size → `IllegalStateException`; đổi `.part` → `.bin`, ghi `.json`), `void abort()` (xóa `.part`); `Optional<FileMeta> meta(String fileId)`; `InputStream open(String fileId)`. `fileName` được làm sạch: chỉ giữ phần sau dấu `/` hoặc `\` cuối, tối đa 120 ký tự.

- [ ] **Step 1: Test fail** — tối thiểu:
  - `historyKeepsOrderAndLimit`: append 60 tin vào `dm:an:binh`, `recent(…, 50)` trả 50 tin, phần tử đầu là tin thứ 11.
  - `historySurvivesReload`: store mới cùng thư mục đọc lại đúng tin; dòng rác trong file bị bỏ qua.
  - `groupPersistsAndListsByMember`: create("Nhóm A", [an, binh]) → GroupStore mới cùng file `find` thấy; `groupsOf("chi")` rỗng.
  - `uploadCompletesOnlyWithExactSize`, `outOfOrderChunkRejected`, `overSizeRejected`, `abortDeletesPart`, `invalidFileIdRejected`, `fileNameSanitized` (`"..\\..\\evil.txt"` → `"evil.txt"`).
- [ ] **Step 2:** chạy 3 test class → FAIL.
- [ ] **Step 3:** Implement. Thêm `data/` vào `.gitignore`.
- [ ] **Step 4:** `mvn -q test` → exit 0.
- [ ] **Step 5:** Commit `feat: add history, group and file stores`.

### Task 3: Định tuyến hội thoại, lịch sử, presence, nhóm

**Files:**
- Modify: `server/ChatServer.java`, `server/ClientHandler.java`, `server/ClientRegistry.java`, `client/ClientMain.java`
- Test: `src/test/java/com/example/securechat/server/ChatServerConversationTest.java`

**Interfaces:**
- Consumes: Task 1, Task 2.
- `ClientRegistry.sessionOf(String username)` → `Optional<ClientSession>` (chỉ session authenticated).
- `ChatServer` tạo `HistoryStore(dataDir/history)`, `GroupStore(dataDir/groups.json)`, `FileStore(dataDir/files)` trong constructor; thêm package-private `boolean canAccess(String username, String conversationId)` (lobby: true; dm: username thuộc 2 thành viên và người kia có trong UserStore; group: là member; còn lại false) và `void deliver(String conversationId, String line)` (lobby → `broadcastLine`; dm/group → enqueue cho `sessionOf` từng member, queue đầy → `removeClient(..., REASON_SEND_QUEUE_FULL)`).
- `ChatServer` dùng `java.time.Clock` (thêm constructor package-private nhận Clock; constructor public dùng `Clock.systemUTC()`) để đóng dấu `timestamp`.
- Luồng chat: `to` null → `lobby`; id không nhận dạng được → `UNKNOWN_CONVERSATION`; không có quyền → `NOT_MEMBER`; hợp lệ → tạo `chatIn`, kiểm `fitsLine`, `history.append`, `deliver`.
- Sau login thành công (sau `markAuthenticated`, trước system "đã tham gia"): gửi `user_list` cho session mới, gửi `group_info` cho từng nhóm của user, rồi `broadcast(presence(username, true))`. Trong `removeClient` cho session authenticated: thêm `broadcast(presence(username, false))` cạnh `user_left`.
- `group_create`: validate (`INVALID_GROUP` nếu tên blank/>50 hoặc member không tồn tại), tạo, gửi `group_info` tới member online.
- `history_request`: kiểm quyền, gửi `recent(to, HISTORY_LIMIT)` với `history=true`, rồi `history_end`.
- `ClientMain`: chỉ in `message` có `to` null hoặc `lobby`; bỏ qua mọi type mới.

- [ ] **Step 1: Test fail** (dùng `RawTlsClient`, `TestUsers`, server với dataDir là `@TempDir`):
  - `dmReachesOnlyTheTwoMembers`: an gửi `messageTo("dm:an:binh","hi")` → an và binh nhận `message` có `to="dm:an:binh"`, `timestamp` = giờ của Clock giả, `from="an"`; chi không nhận gì trong 500 ms.
  - `outsiderGetsNotMember`: chi gửi tới `dm:an:binh` → `error` `NOT_MEMBER`; chi gửi `history_request` `dm:an:binh` → `NOT_MEMBER`.
  - `groupCreateAndChat`: an tạo nhóm với [binh] → an, binh nhận `group_info` (members [an, binh]); tin trong nhóm tới an, binh, không tới chi.
  - `offlineDmAppearsInHistory`: binh offline, an gửi dm; binh login → `history_request` → nhận tin đó có `history=true` rồi `history_end`.
  - `loginSendsUserListAndPresence`: an online, binh login → binh nhận `user_list` (members [an, binh, chi], onlineUsers chứa an, binh); an nhận `presence` binh online=true; binh ngắt → an nhận `presence` online=false.
  - `lobbyStillWorksWithoutTo`: `ChatMessage.message("x")` vẫn tới mọi người (giữ hành vi cũ), có `to="lobby"`.
- [ ] **Step 2:** chạy → FAIL.
- [ ] **Step 3:** Implement. Các test cũ đọc message theo thứ tự có thể nhận thêm `user_list`/`presence`/`group_info`: sửa **helper đọc trong test cũ** để bỏ qua 3 type này, không đổi assertion.
- [ ] **Step 4:** `mvn -q test` → exit 0, chạy `ChatServerConversationTest` 3 lần liên tiếp đều pass.
- [ ] **Step 5:** Commit `feat: route direct and group conversations with history and presence`.

### Task 4: Truyền file qua TLS

**Files:**
- Modify: `server/ClientHandler.java`, `server/ChatServer.java`, `server/ClientSession.java`
- Create: `server/FileTransfers.java`
- Test: `src/test/java/com/example/securechat/server/ChatServerFileTest.java`, thêm case vào `ClientRegistryTest` (hoặc `ClientSessionTest` nếu đã có) cho `enqueueBulk`

**Interfaces:**
- `ClientSession.enqueueBulk(String line, Duration maxWait)` → `boolean`: chỉ enqueue khi còn trống **hơn một nửa** capacity, nếu không thì chờ (tối đa `maxWait`); trả false khi hết giờ hoặc session đang đóng. Tin thường vẫn dùng `enqueue`.
- `FileTransfers` (giữ upload đang dở theo session; executor download `Executors.newFixedThreadPool(4)` daemon "file-sender-"): `void onStart(ClientSession, ChatMessage)`, `void onChunk(ClientSession, ChatMessage)`, `void onEnd(ClientSession, ChatMessage)`, `void onGet(ClientSession, ChatMessage)`, `void sessionClosed(int sessionId)` (abort upload dở), `void shutdown()` (gọi trong `stop()`).
- Mỗi session tối đa 1 upload đang dở; `file_start` mới khi đang dở → `FILE_INVALID`. Kiểm quyền hội thoại (`NOT_MEMBER`), `size > MAX_FILE_BYTES` → `FILE_TOO_LARGE`, base64 hỏng/chunk sai → abort + `FILE_INVALID`. Lỗi upload không ngắt kết nối.
- Hoàn tất upload → `chatIn` kèm kind/fileId/fileName/mime/size (content = fileName), append lịch sử, `deliver`.
- `file_get`: không tồn tại → `FILE_NOT_FOUND`; không có quyền với `conversationId` của file → `NOT_MEMBER`; hợp lệ → task trên executor đọc theo đoạn `FILE_CHUNK_BYTES`, `enqueueBulk(…, 30s)` từng chunk, cuối cùng `file_end`. `enqueueBulk` false → dừng, không ngắt client.
- Log: `[INFO] File <fileId> uploaded by client <id> (<size> bytes)`; không bao giờ log nội dung/base64.

- [ ] **Step 1: Test fail**:
  - `uploadThenDownloadRoundTrip`: an upload 100 000 byte ngẫu nhiên vào `dm:an:binh` → an, binh nhận `message` kind=`file`; binh `file_get` → ghép chunk khớp byte gốc; chi `file_get` → `NOT_MEMBER`.
  - `oversizeDeclaredRejected`: `size = MAX_FILE_BYTES + 1` → `FILE_TOO_LARGE`, sau đó vẫn chat được.
  - `wrongTotalRejectedAndCleaned`: khai `size` 5000, gửi 3000 byte rồi `file_end` → `FILE_INVALID`; thư mục files không còn `.part`.
  - `disconnectMidUploadCleansUp`: ngắt giữa chừng → không còn `.part` (chờ tối đa 2 s).
  - `downloadDoesNotStarveChat`: server `sendQueueCapacity` 16; binh tải file 2 MB bằng client đọc chậm, đồng thời an gửi 20 tin lobby → binh không bị ngắt và nhận đủ 20 tin.
  - `enqueueBulkKeepsHalfFree` (unit): queue capacity 8, sau 4 `enqueueBulk` thành công lần thứ 5 trả false với maxWait 50 ms; `enqueue` thường vẫn thành công.
- [ ] **Step 2:** chạy → FAIL.
- [ ] **Step 3:** Implement; `removeClient` gọi `fileTransfers.sessionClosed(id)`.
- [ ] **Step 4:** `mvn -q test` → exit 0; `ChatServerFileTest` 3 lần liên tiếp pass.
- [ ] **Step 5:** Commit `feat: add chunked file upload and download over TLS`.

### Task 5: Mở rộng ChatClient

**Files:**
- Modify: `client/ChatClient.java`
- Test: `src/test/java/com/example/securechat/client/ChatClientExtTest.java`

**Interfaces:**
- `void sendTo(String conversationId, String content) throws IOException` (kiểm `fitsLine`, lỗi → `IllegalArgumentException("Tin nhắn quá dài")`).
- `void requestHistory(String conversationId) throws IOException`; `void createGroup(String name, List<String> members) throws IOException`.
- `String uploadFile(String conversationId, Path file, String kind) throws IOException`: chặn khi gọi (chạy trên thread của caller), kiểm `size` ≤ `MAX_FILE_BYTES` trước khi gửi (vượt → `IllegalArgumentException("File vượt quá 25 MB")`), mime = `Files.probeContentType` hoặc `application/octet-stream`, trả fileId.
- `CompletableFuture<Path> download(String fileId, Path target)`: thread nhận tự xử lý `file_chunk`/`file_end`/`error` có `fileId` đang chờ (ghi vào `target`, complete/completeExceptionally) và **không** chuyển các message đó cho `onMessage`. Kết nối đóng → mọi download đang chờ completeExceptionally.

- [ ] **Step 1: Test fail** (server thật + `TestCerts`): `sendToDmDelivered`, `uploadAndDownloadMatchesBytes` (300 KB), `downloadUnknownFileFails` (future lỗi với message chứa `FILE_NOT_FOUND`), `uploadTooLargeRejectedLocally` (file thưa 25 MB + 1 qua `RandomAccessFile.setLength`), `pendingDownloadFailsOnClose`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** `mvn -q test` exit 0.
- [ ] **Step 5:** Commit `feat: add conversation, history, group and file APIs to ChatClient`.

### Task 6: JavaFX app — đăng nhập, danh sách hội thoại, chat text

**Files:**
- Modify: `pom.xml`
- Create (package `com.example.securechat.gui`): `GuiLauncher.java`, `ChatApp.java`, `ChatState.java`, `LoginView.java`, `MainView.java`, `ConversationCell.java`, `MessageBubbles.java`, `EmojiPicker.java`, `CreateGroupDialog.java`, `Avatars.java`; resource `src/main/resources/com/example/securechat/gui/zalo.css`
- Test: `src/test/java/com/example/securechat/gui/ChatStateTest.java`

**Interfaces:**
- pom: `org.openjfx:javafx-controls:21.0.5`, `org.openjfx:javafx-media:21.0.5`, plugin `org.openjfx:javafx-maven-plugin:0.0.8` với `mainClass` `com.example.securechat.gui.ChatApp`. Shade jar: `GuiLauncher.main` gọi `Application.launch(ChatApp.class, args)` (class không extends Application, để chạy được từ fat jar).
- `ChatState` (thuần Java, không import `javafx.*`; thread-safety: chỉ gọi trên FX thread):
  - `record Conversation(String id, String title, boolean group, List<String> members)`; `record Entry(ChatMessage message, boolean mine)`.
  - `ChatState(String me)`; `void apply(ChatMessage m)` xử lý `user_list`, `presence`, `group_info`, `message` (kể cả `history=true`, chống trùng theo cặp `(timestamp, from, content, fileId)`), `user_left`.
  - `List<Conversation> conversations()`: Phòng chung đầu tiên, rồi nhóm, rồi một `dm:` cho mỗi user khác (title = username); sắp theo thời gian tin cuối giảm dần (Phòng chung luôn đầu).
  - `List<Entry> messages(String conversationId)` theo `timestamp` tăng dần; `boolean isOnline(String username)`; `int unread(String id)`; `void open(String id)` (đặt unread = 0, đánh dấu đang mở); `Optional<Entry> last(String id)`; `void addListener(Runnable r)`.
- Màn hình (chuỗi chính xác):
  - Đăng nhập: tiêu đề "Secure Chat", các ô "Máy chủ" (mặc định `localhost`), "Cổng" (`5000`), "Chứng chỉ server" (mặc định `certificates/client/server.crt`, nút "Chọn…"), "Tên đăng nhập", "Mật khẩu" (`PasswordField`), nút "Đăng nhập". Kết nối trên background `Task`; lỗi hiện label đỏ: lỗi chứng chỉ → "Chứng chỉ server không hợp lệ: <message>", login fail → `content` của `login_result`. Mật khẩu `char[]` được xóa sau khi gửi.
  - Chính: cột trái 320 px (header avatar + tên mình, nút "+" "Tạo nhóm", list hội thoại: avatar chữ cái, chấm xanh `#2DC26B` online / xám offline cho dm, tên, dòng xem trước tin cuối, giờ, badge đỏ số chưa đọc). Cột phải: header (tên hội thoại; dm: "Đang hoạt động"/"Không hoạt động"; nhóm: "<n> thành viên"), vùng tin nhắn cuộn, thanh nhập (nút 😊, nút đính kèm, nút micro, `TextField` gợi ý "Nhập tin nhắn…", nút "Gửi"; Enter = gửi).
  - Bong bóng: tin người khác trong nhóm/Phòng chung có avatar + tên người gửi phía trên; giờ `HH:mm` dưới bong bóng; vạch ngăn ngày "Hôm nay" / "Hôm qua" / `dd/MM/yyyy`.
  - Mở hội thoại lần đầu → `requestHistory`. Callback từ thread nhận chuyển về FX thread bằng `Platform.runLater`. Mất kết nối → banner "Mất kết nối tới server".
- `EmojiPicker`: `Popup` lưới 8 cột, khoảng 40 emoji, font "Segoe UI Emoji", bấm → chèn tại caret.
- `CreateGroupDialog`: tên nhóm + danh sách checkbox user khác → `createGroup`.
- `Avatars.circle(String name, double size)`: hình tròn màu theo `name.hashCode()` (bảng 6 màu), chữ cái đầu viết hoa.

- [ ] **Step 1: Test fail `ChatStateTest`**: `userListBuildsLobbyGroupsAndDms` (me=an, members [an,binh,chi] → ids `lobby`, `dm:an:binh`, `dm:an:chi`), `presenceUpdatesOnline`, `messageIncrementsUnreadUnlessOpen`, `historyDeduplicated` (cùng tin vừa live vừa history → 1 entry), `mineFlagByFrom`, `messagesSortedByTimestamp`, `groupInfoAddsGroup`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement `ChatState`, rồi các view.
- [ ] **Step 4:** `mvn -q test` exit 0; `mvn -q -DskipTests package` exit 0; khởi động server (env `CHAT_CERT_PASSWORD`, certs trong `certificates/`) rồi `mvn javafx:run` → cửa sổ đăng nhập hiện ra, đăng nhập `an` thành công thấy Phòng chung + binh + chi (ghi lại output/ảnh chụp bằng `[System.Windows.Forms.SendKeys]`/screenshot nếu làm được; nếu không tự đóng cửa sổ được thì ghi "kiểm tra thủ công").
- [ ] **Step 5:** Commit `feat: add JavaFX client with conversations, presence, emoji and history`.

### Task 7: Ảnh, video, file, ghi âm trong GUI

**Files:**
- Create (package `gui`): `MediaBubbles.java`, `VoiceRecorder.java`, `MediaCache.java`, `AttachmentKinds.java`
- Modify: `gui/MainView.java`, `gui/MessageBubbles.java`, `zalo.css`
- Test: `src/test/java/com/example/securechat/gui/AttachmentKindsTest.java`, `MediaCacheTest.java`

**Interfaces:**
- `AttachmentKinds.kindFor(String fileName)` → `"image"` (png, jpg, jpeg, gif, bmp), `"video"` (mp4, m4v), `"audio"` (wav, mp3, m4a), còn lại `"file"`; không phân biệt hoa thường.
- `MediaCache(Path root)` (mặc định `%TEMP%/securechat-cache/<username>`): `Path pathFor(String fileId, String fileName)` = `root/<fileId>_<fileName đã làm sạch>`; `CompletableFuture<Path> get(ChatClient client, String fileId, String fileName)` — có sẵn thì trả luôn, chưa có thì `client.download` vào `.part` rồi đổi tên; cùng fileId đang tải thì dùng chung future.
- Nút đính kèm mở `ContextMenu`: "Ảnh", "Video", "File" → `FileChooser` lọc theo đuôi (Video: `*.mp4`, `*.m4v`); upload trên background thread; trong lúc gửi hiện bong bóng tạm "Đang gửi <tên>…"; lỗi → Alert với message.
- Bong bóng theo `kind`:
  - `image`: `ImageView` tối đa 240×240 giữ tỉ lệ; bấm mở `Stage` xem cỡ lớn.
  - `video`: `MediaView` 320×180 + nút ▶/⏸ + thanh thời gian; lỗi codec → hiện "Không phát được video này" + nút "Lưu về máy".
  - `audio`: nút ▶ + "Tin nhắn thoại" + thời lượng `m:ss`.
  - `file`: biểu tượng 📄 + tên + kích thước (`KB`/`MB`, 1 chữ số thập phân) + nút "Tải về" → `FileChooser` lưu (copy từ cache).
- `VoiceRecorder`: `void start() throws LineUnavailableException` (AudioFormat 16000 Hz, 16-bit, mono, signed, little-endian); `Path stop() throws IOException` (ghi WAV bằng `AudioSystem.write` vào file tạm `voice-<timestamp>.wav`); tự dừng sau 60 s; `boolean isRecording()`. Nút micro: bấm lần 1 bắt đầu (nút đỏ + đồng hồ `0:12`), lần 2 dừng và gửi với kind `audio`; không có micro → Alert "Không tìm thấy micro".

- [ ] **Step 1: Test fail**: `AttachmentKindsTest` (`"A.MP4"`→video, `"x.jpeg"`→image, `"r.wav"`→audio, `"doc.pdf"`→file); `MediaCacheTest`: `pathForSanitizesName` (`"../a.png"` không thoát khỏi root), `concurrentGetSharesOneDownload` (dùng server thật + `ChatClient` từ Task 5: 2 lần `get` cùng fileId → chỉ 1 lần tải, cả hai future trả cùng Path).
- [ ] **Step 2:** FAIL. **Step 3:** Implement.
- [ ] **Step 4:** `mvn -q test` exit 0; chạy GUI thủ công: gửi ảnh PNG, video MP4 (H.264), file PDF, ghi âm 3 s giữa `an` và `binh` → hiển thị và phát được ở cả hai bên. Ghi lại kết quả thật; mục nào không tự kiểm được thì ghi "chờ kiểm tra thủ công".
- [ ] **Step 5:** Commit `feat: add image, video, file and voice message bubbles`.

### Task 8: Test đầu-cuối theo tiêu chí, README, nghiệm thu

**Files:**
- Create: `src/test/java/com/example/securechat/CriteriaEndToEndTest.java`, `docs/acceptance-zalo.md`
- Modify: `README.md`

**Interfaces:** Consumes Task 1–7.

- [ ] **Step 1:** `CriteriaEndToEndTest` dùng 3 `ChatClient` thật (an, binh, chi), mỗi tiêu chí không cần GUI là một `@Test` tên tiếng Anh có `@DisplayName` đúng tên tiêu chí: `p2p`, `group`, `file`, `emoji` (gửi `"Chào 😀👍❤️"` → nhận lại đúng chuỗi, đúng code point), `history`, `image` (PNG nhỏ tạo bằng code, download khớp byte), `video` (byte mẫu mime `video/mp4`, khớp byte), `sound` (WAV 1 s tạo bằng `AudioSystem.write`, khớp byte), `state` (presence online/offline), `time` (`timestamp` nằm giữa thời điểm trước gửi và sau nhận), `sender` (`from` đúng người gửi).
- [ ] **Step 2:** `mvn -q test` → exit 0; ghi tổng số test.
- [ ] **Step 3:** README thêm mục "Giao diện kiểu Zalo": cách chạy GUI (`mvn javafx:run` và `java -cp target/secure-tcp-chat.jar com.example.securechat.gui.GuiLauncher`), bảng 11 tiêu chí → tính năng → test tự động, giới hạn (25 MB, MP4 H.264, ghi âm 60 s, emoji có thể đơn sắc), thư mục `data/`.
- [ ] **Step 4:** `docs/acceptance-zalo.md`: checklist thủ công 11 tiêu chí (bước bấm cụ thể trên GUI, kết quả mong đợi, cột PASS/FAIL/chưa kiểm) — điền kết quả thật của các phần đã kiểm được ở Task 6–7, phần còn lại để "chờ kiểm tra thủ công".
- [ ] **Step 5:** Đảm bảo không còn process java nền, cổng 5000 trống. Commit `docs: add Zalo-style criteria tests, README and acceptance checklist`.
