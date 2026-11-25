# Slide Gap Fixes Implementation Plan (draft)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Status:** temporary plan, written 2026-11-26 from the 35-slide comparison below. Not yet reviewed or executed.

**Goal:** Close the gaps between the project and the 35 course slides: heartbeat (slide 28), `ping` and error code (slides 23–24), send-rate limit (slide 25), logging (slides 26, 35), LAN-ready setup and fresh acceptance evidence (slide 35).

**Architecture:** Client sends `ping` periodically after login; server answers `pong` and enforces an idle read timeout on authenticated sockets instead of `setSoTimeout(0)`. A per-session sliding-window limiter rejects bursts. Logging and `setup.bat` get small targeted changes.

**Tech Stack:** Java 17+, Maven, JUnit 5, JavaFX, Windows batch/PowerShell.

**Spec:** the slide comparison (summary below); no separate spec file.

## Comparison summary (source of this plan)

Already met: certificates and TLS server (slides 7–18), JSON-line protocol and login (9–10, 21–24), client registry/broadcast/disconnect (19–20, 25–28), client-side validation and threading (29–34), TC01–TC06 automated (35; 142 tests passed on 2026-11-22, not re-run since).

Gaps, in priority order:
1. No heartbeat/KeepAlive (slide 28). `ClientHandler.java:188` sets `socket.setSoTimeout(0)` after login. Inferred from code (not reproduced): if a client loses Wi-Fi abruptly, the server may keep the account online and re-login fails with "already online".
2. No `ping` message (slides 23–24). Unknown types reply `UNKNOWN_TYPE` (`ClientHandler.java:206,229`); Java slide uses `UNKNOWN_MESSAGE_TYPE` (C# slide uses `UNKNOWN_TYPE`).
3. No send-rate limit (slide 25, C# version). Only size and queue limits exist.
4. Logging: no log on successful broadcast (slide 26); no `[BLOCK] Certificate validation failed` line as in slide 35 (server logs `[BLOCK] TLS handshake failed`, `ClientHandler.java:69`).
5. No fresh run evidence: `docs/acceptance-log.md` was produced at commit 6762471, before the GUI upgrade, not over a LAN IP, no screenshots.

Intentional differences (no work): single Maven project vs two projects (slides 5–6); writer thread + queue instead of `ReentrantLock` (slide 20, allowed by slide 26); `clientId` instead of REST token (slide 22); `message/content` per slide 10; no revocation check (slides 31–32, "if needed", documented in README); Zalo-style extras are out of slide scope.

## Global Constraints

- Keep the wire format: one JSON object per line, max `Protocol.MAX_LINE_BYTES = 4096`.
- New constants live in `Protocol`; tunables in `ServerOptions` so tests can shrink them.
- Code comments, commits in English; existing Vietnamese user-facing strings stay Vietnamese.
- `mvn -q test` must stay green after every task.

## Review Focus

- Client that is idle but healthy (no chatting for minutes) must NOT be dropped — pings keep it alive.
- Large file transfer in progress must not trip the idle timeout or the rate limiter (file chunks are exempt from the chat rate limit).
- Reconnect after heartbeat drop: the username must be free again (`tryClaimUsername` succeeds).
- `pong` arriving at the GUI must not show up as a chat bubble or system line.
- `setup.bat` on a machine with several adapters (VPN, Hyper-V) must still let the user pick/confirm the IP.

---

### Task 1: `ping`/`pong` messages and error code

**Files:**
- Modify: `src/main/java/com/example/securechat/model/MessageType.java`, `model/ChatMessage.java`, `server/ClientHandler.java:206,229`
- Test: `src/test/java/com/example/securechat/server/ChatServerChatTest.java`

**Interfaces:**
- Produces: `MessageType.PING("ping")`, `MessageType.PONG("pong")`, `ChatMessage.ping()`, `ChatMessage.pong()`; error code constant `"UNKNOWN_MESSAGE_TYPE"`.

- [ ] Step 1: Tests `pingIsAnsweredWithPong` (authenticated client sends `{"type":"ping"}` → receives `type == "pong"`) and `unknownTypeRepliesUnknownMessageType` (sends `{"type":"foo"}` → error code `UNKNOWN_MESSAGE_TYPE`). Update existing assertions that expect `UNKNOWN_TYPE`.
- [ ] Step 2: Run `mvn -q test -Dtest=ChatServerChatTest` → FAIL.
- [ ] Step 3: Add enum values/factories; handle `PING` in `handleAuthenticated` by `session.send(ChatMessage.pong())`; rename error code.
- [ ] Step 4: Run tests → PASS.
- [ ] Step 5: Commit `feat: add ping/pong and UNKNOWN_MESSAGE_TYPE error`.

### Task 2: Server idle timeout (heartbeat enforcement)

**Files:**
- Modify: `common/Protocol.java`, `server/ServerOptions.java`, `server/ClientHandler.java:107,188`
- Test: `src/test/java/com/example/securechat/server/ChatServerLoginTest.java`

**Interfaces:**
- Consumes: `ChatMessage.ping()` (Task 1).
- Produces: `Protocol.HEARTBEAT_INTERVAL = Duration.ofSeconds(15)`, `Protocol.DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(45)`; `ServerOptions.idleTimeout()` (new record component; keep existing constructors delegating with the default); disconnect reason `"heartbeat timeout"`.

- [ ] Step 1: Tests with `idleTimeout = 1s`: `silentClientIsDroppedAfterIdleTimeout` (login, send nothing → peers get `user_left`/presence offline within `WAIT`; server log reason `heartbeat timeout`), `pingingClientStaysOnline` (ping every 300 ms for 3 s → still online), `usernameFreeAfterHeartbeatDrop` (after drop, new login with same user succeeds).
- [ ] Step 2: Run → FAIL.
- [ ] Step 3: Replace `socket.setSoTimeout(0)` with `setSoTimeout((int) idleTimeout.toMillis())`; in `readLoop` map `SocketTimeoutException` on an authenticated session to reason `"heartbeat timeout"`. Any received line (incl. file chunks) resets the timer implicitly.
- [ ] Step 4: Run full `mvn -q test` → PASS.
- [ ] Step 5: Commit `feat: drop silent clients after idle timeout`.

### Task 3: Client heartbeat sender

**Files:**
- Modify: `client/ChatClient.java`, `gui/ChatState.java` (ignore `pong`)
- Test: `src/test/java/com/example/securechat/client/ChatClientTest.java`

**Interfaces:**
- Consumes: `Protocol.HEARTBEAT_INTERVAL`, `ChatMessage.ping()`.
- Produces: `ChatClient` starts a daemon `ScheduledExecutorService` after successful login sending `ping` every interval; stopped in `close()`. Package-private constructor/overload accepting a `Duration` interval for tests.

- [ ] Step 1: Tests `clientSendsPingsAfterLogin` (interval 200 ms, server idle 1 s, wait 3 s → still connected), `pongNotDeliveredAsChat` (listener never receives `pong`), `heartbeatStopsOnClose`.
- [ ] Step 2: Run → FAIL.
- [ ] Step 3: Implement scheduler; swallow `pong` before dispatching to listeners.
- [ ] Step 4: Run full tests → PASS.
- [ ] Step 5: Commit `feat: send client heartbeat pings`.

### Task 4: Chat send-rate limit

**Files:**
- Create: `server/RateLimiter.java`
- Modify: `common/Protocol.java`, `server/ClientSession.java`, `server/ClientHandler.java` (`handleChat`)
- Test: `src/test/java/com/example/securechat/server/RateLimiterTest.java`, `ChatServerChatTest.java`

**Interfaces:**
- Produces: `final class RateLimiter { RateLimiter(int maxEvents, Duration window, LongSupplier nanoClock); boolean tryAcquire(); }`; `Protocol.CHAT_RATE_MAX = 20`, `Protocol.CHAT_RATE_WINDOW = Duration.ofSeconds(10)`; error code `"RATE_LIMITED"`. Applies only to `MESSAGE`, not file/history/ping.

- [ ] Step 1: Unit tests (fake clock): 20 acquires true, 21st false, true again after window. Integration: `burstBeyondLimitGetsRateLimited` (21 messages fast → one `error RATE_LIMITED`, connection stays open, peers receive 20).
- [ ] Step 2: Run → FAIL.
- [ ] Step 3: Sliding window with `ArrayDeque<Long>`; one limiter per `ClientSession`; log `[BLOCK] Rate limited: client <id>`.
- [ ] Step 4: Run full tests → PASS.
- [ ] Step 5: Commit `feat: limit chat send rate per client`.

### Task 5: Logging per slides 26 and 35

**Files:**
- Modify: `server/ChatServer.java:217` (`broadcastLine`), `server/ClientHandler.java:69`
- Test: existing log-capturing tests in `CriteriaEndToEndTest.java` (extend)

- [ ] Step 1: Assert log contains `[BROADCAST]` line with sender id and recipient count after a chat, and `[BLOCK] Certificate validation failed` when a client rejects the server cert / handshake fails.
- [ ] Step 2: Run → FAIL.
- [ ] Step 3: Log `[BROADCAST] from client <id> to <n> clients` (INFO) for chat broadcasts only (not presence/system spam); change handshake-failure message to `[BLOCK] Certificate validation failed / TLS handshake failed: client <id> (<ip>)`.
- [ ] Step 4: Run full tests → PASS.
- [ ] Step 5: Commit `feat: log broadcasts and certificate validation failures`.

### Task 6: `setup.bat` adds LAN IP to certificate SAN

**Files:**
- Modify: `setup.bat`, `README.md` (LAN section)

- [ ] Step 1: Detect IPv4 of the default-route adapter via PowerShell (`Get-NetRoute 0.0.0.0/0` → `Get-NetIPAddress`), show it, let user confirm or type another; pass `-AddIp` to `scripts/gen-certs.ps1`.
- [ ] Step 2: Verify manually: run `setup.bat`, then `keytool -list -v -keystore certificates\server\server.p12` shows `IPAddress: <LAN IP>` in SAN.
- [ ] Step 3: Commit `feat: include LAN IP in certificate SAN during setup`.

### Task 7: Fresh acceptance run over LAN

**Files:**
- Modify: `docs/acceptance-log.md`; add screenshots under `docs/acceptance/` (curated from `pics/`)

- [ ] Step 1: `mvn -q test` → record test count and result.
- [ ] Step 2: Run TC01–TC06 between two machines using the LAN IP; capture server log and screenshots for each.
- [ ] Step 3: Wi-Fi drop test: log in from machine B, disable its Wi-Fi, confirm server logs `heartbeat timeout` within ~45 s and re-login succeeds.
- [ ] Step 4: Update `docs/acceptance-log.md` with commit hash, date, results; commit `docs: refresh acceptance log over LAN`.
