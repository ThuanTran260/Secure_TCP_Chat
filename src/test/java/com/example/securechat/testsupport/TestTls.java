package com.example.securechat.testsupport;

import com.example.securechat.common.TlsContexts;
import javax.net.ssl.SSLContext;
import java.nio.file.Files;

/** Shared throw-away TLS contexts for client API integration fixtures. */
public final class TestTls {
    private static final SSLContext SERVER;
    private static final SSLContext CLIENT;

    static {
        try {
            TestCerts certs = TestCerts.create(Files.createTempDirectory("chat-peer-certificates-"));
            SERVER = TlsContexts.server(certs.serverP12, TestCerts.PASSWORD.toCharArray());
            CLIENT = TlsContexts.client(certs.serverCrt);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestTls() { }
    public static SSLContext server() { return SERVER; }
    public static SSLContext client() { return CLIENT; }
}
