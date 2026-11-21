package com.example.securechat.server;

import com.example.securechat.common.Protocol;
import com.example.securechat.common.TlsContexts;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.concurrent.CountDownLatch;
import javax.net.ssl.SSLContext;

/** Command-line entry point: loads the keystore and users file, starts {@link ChatServer} and waits for shutdown. */
public final class ServerMain {

    static final String PASSWORD_ENV = "CHAT_CERT_PASSWORD";
    static final String DEFAULT_KEYSTORE = "certificates/server/server.p12";
    static final String DEFAULT_USERS = "config/users.properties";

    private ServerMain() {}

    public static void main(String[] args) {
        int port = Protocol.DEFAULT_PORT;
        Path keystore = Path.of(DEFAULT_KEYSTORE);
        Path users = Path.of(DEFAULT_USERS);
        Path data = Path.of("data");

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (i + 1 >= args.length) {
                fail("Thiếu giá trị cho " + arg);
            }
            String value = args[++i];
            switch (arg) {
                case "--port" -> port = parsePort(value);
                case "--keystore" -> keystore = Path.of(value);
                case "--users" -> users = Path.of(value);
                case "--data" -> data = Path.of(value);
                default -> fail("Tham số không hợp lệ: " + arg);
            }
        }

        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.isBlank()) {
            fail("Thiếu " + PASSWORD_ENV);
        }
        if (!Files.isRegularFile(keystore)) {
            fail("Không tìm thấy " + keystore);
        }
        if (!Files.isRegularFile(users)) {
            fail("Không tìm thấy " + users);
        }

        SSLContext tls;
        UserStore store;
        try {
            tls = TlsContexts.server(keystore, password.toCharArray());
            store = UserStore.load(users);
        } catch (GeneralSecurityException | IOException e) {
            // Never print the password; the exception message does not contain it.
            fail("Không thể đọc cấu hình TLS hoặc người dùng: " + e.getMessage());
            return;
        }

        ServerOptions defaults = ServerOptions.defaults();
        ChatServer server;
        try {
            server = new ChatServer(tls, store, port,
                    new ServerOptions(defaults.loginTimeout(), defaults.sendQueueCapacity(), defaults.throttle(), data));
        } catch (UncheckedIOException e) {
            fail("Không thể mở thư mục dữ liệu: " + e.getCause().getMessage());
            return;
        }
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            shutdown.countDown();
        }, "chat-shutdown"));

        try {
            server.start();
        } catch (IOException | IllegalStateException e) {
            fail("Không thể mở cổng " + port + ": " + e.getMessage());
        }

        try {
            // Keep the main thread alive until Ctrl+C runs the shutdown hook.
            shutdown.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    /** Prints the message to stderr as UTF-8 (the default Windows stderr charset cannot encode Vietnamese) and exits with status 1. */
    private static void fail(String message) {
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        err.println(message);
        System.exit(1);
    }
}
