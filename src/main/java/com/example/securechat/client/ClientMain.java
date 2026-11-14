package com.example.securechat.client;

import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import com.example.securechat.model.ChatMessage;
import com.example.securechat.model.MessageType;
import java.io.BufferedReader;
import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

/**
 * Console chat client: {@code ClientMain [host=localhost] [port=5000] [--cert certificates/client/server.crt]}.
 * Works both interactively and with piped stdin (lines are sent until EOF or {@code /quit}).
 */
public final class ClientMain {

    static final String DEFAULT_HOST = "localhost";
    static final String DEFAULT_CERT = "certificates/client/server.crt";
    private static final long ECHO_WAIT_MS = 2_000;

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private ClientMain() {}

    public static void main(String[] args) throws IOException {
        String host = DEFAULT_HOST;
        int port = Protocol.DEFAULT_PORT;
        Path cert = Path.of(DEFAULT_CERT);

        int positional = 0;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--cert")) {
                if (i + 1 >= args.length) {
                    fail("Thiếu giá trị cho --cert");
                }
                cert = Path.of(args[++i]);
            } else if (arg.startsWith("--")) {
                fail("Tham số không hợp lệ: " + arg);
            } else if (positional == 0) {
                host = arg;
                positional++;
            } else if (positional == 1) {
                port = parsePort(arg);
                positional++;
            } else {
                fail("Tham số không hợp lệ: " + arg);
            }
        }

        if (!Files.isRegularFile(cert)) {
            fail("Không tìm thấy " + cert);
        }
        SSLContext ctx = null;
        try {
            ctx = TlsContexts.client(cert);
        } catch (GeneralSecurityException | IOException e) {
            fail("Không đọc được chứng chỉ " + cert + ": " + e.getMessage());
        }

        // One reader for all of stdin: username, a piped password and the chat lines come from it in order.
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        OUT.print("Username: ");
        String username = stdin.readLine();
        if (username == null || username.isBlank()) {
            fail("Thiếu username");
        }
        username = username.trim();
        char[] password = readPassword(stdin);
        if (password == null) {
            fail("Thiếu password");
        }

        try (ChatClient client = new ChatClient(ctx)) {
            try {
                client.connect(host, port, Protocol.CONNECT_TIMEOUT_MS);
            } catch (ConnectException e) {
                fail("Không kết nối được: server chưa chạy hoặc sai cổng");
            } catch (SocketTimeoutException e) {
                fail("Hết thời gian kết nối");
            } catch (UnknownHostException e) {
                fail("Sai địa chỉ server");
            } catch (SSLHandshakeException e) {
                fail("Chứng chỉ server không hợp lệ: " + e.getMessage());
            } catch (IOException e) {
                fail("Lỗi kết nối: " + e.getMessage());
            }
            OUT.println("Đã kết nối " + host + ":" + port + " (" + client.negotiatedProtocol() + ")");

            ChatMessage result = null;
            try {
                result = client.login(username, password);
            } catch (IOException e) {
                fail("Đăng nhập thất bại: " + e.getMessage());
            } finally {
                Arrays.fill(password, '\0');
            }
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                fail("Đăng nhập thất bại: " + result.getContent());
            }
            OUT.println("Đăng nhập thành công. Gõ /quit để thoát.");

            chat(client, username, stdin);
        }
    }

    private static void chat(ChatClient client, String username, BufferedReader stdin) throws IOException {
        AtomicBoolean quitting = new AtomicBoolean();
        // Messages sent whose server reply (own broadcast or an error) has not been printed yet.
        AtomicInteger awaitingReply = new AtomicInteger();

        client.startReceiving(m -> {
            print(m);
            if (m.messageType().orElse(null) == MessageType.ERROR
                    || (m.messageType().orElse(null) == MessageType.MESSAGE && username.equals(m.getFrom()))) {
                awaitingReply.updateAndGet(n -> n > 0 ? n - 1 : 0);
            }
        }, reason -> {
            if (!quitting.get()) {
                OUT.println("! Mất kết nối tới server");
                System.exit(1);
            }
        });

        String line;
        while ((line = stdin.readLine()) != null) {
            if (line.trim().equals("/quit")) {
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            try {
                awaitingReply.incrementAndGet();
                client.send(line);
            } catch (IllegalArgumentException e) {
                awaitingReply.decrementAndGet();
                OUT.println("! " + e.getMessage());
            } catch (IOException e) {
                OUT.println("! Mất kết nối tới server");
                quitting.set(true);
                return;
            }
        }

        // With piped input we reach EOF right after the last send; give the echo a moment to arrive.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ECHO_WAIT_MS);
        while (awaitingReply.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        quitting.set(true);
    }

    private static void print(ChatMessage m) {
        MessageType type = m.messageType().orElse(null);
        if (type == null) {
            return;
        }
        switch (type) {
            case MESSAGE -> OUT.println("[" + m.getFrom() + "] " + m.getContent());
            case SYSTEM, USER_LEFT -> OUT.println("* " + m.getContent());
            case ERROR -> OUT.println("! " + m.getContent());
            default -> { }
        }
    }

    /**
     * Reads the password without echo from a real terminal; otherwise (piped stdin, or a console
     * that returns null) falls back to the next line of {@code stdin}, which is never echoed.
     */
    private static char[] readPassword(BufferedReader stdin) throws IOException {
        Console console = System.console();
        if (console != null && isInteractive(console)) {
            char[] pw = console.readPassword("Password: ");
            if (pw != null) {
                return pw;
            }
        } else {
            OUT.print("Password: ");
        }
        String line = stdin.readLine();
        return line == null ? null : line.toCharArray();
    }

    /**
     * JDK 22+ returns a non-null Console even when stdin is piped; Console.isTerminal() tells them apart,
     * but it does not exist in Java 17, so call it reflectively. On older JDKs a non-null Console is a terminal.
     */
    private static boolean isInteractive(Console console) {
        try {
            Method m = Console.class.getMethod("isTerminal");
            return (Boolean) m.invoke(console);
        } catch (NoSuchMethodException e) {
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                fail("Cổng không hợp lệ: " + value);
            }
            return port;
        } catch (NumberFormatException e) {
            fail("Cổng không hợp lệ: " + value);
            return 0;
        }
    }

    /** Prints to stderr as UTF-8 and exits with status 1. */
    private static void fail(String message) {
        ERR.println(message);
        System.exit(1);
    }
}
