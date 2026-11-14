package com.example.securechat.common;

import static org.junit.jupiter.api.Assertions.*;

import com.example.securechat.testsupport.TestCerts;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsContextsTest {

    private static TestCerts certs;
    private SSLServerSocket server;

    @BeforeAll
    static void generateCerts(@TempDir Path dir) {
        certs = TestCerts.create(dir);
    }

    @AfterEach
    void closeServer() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** Starts a loopback TLS server that handshakes once, then waits for the peer to close. */
    private int startServer(Path p12) throws Exception {
        SSLContext ctx = TlsContexts.server(p12, TestCerts.PASSWORD.toCharArray());
        server = (SSLServerSocket) ctx.getServerSocketFactory()
                .createServerSocket(0, 5, InetAddress.getLoopbackAddress());
        TlsContexts.hardenServer(server);
        Thread t = new Thread(() -> {
            try (SSLSocket s = (SSLSocket) server.accept()) {
                s.setSoTimeout(5000);
                s.startHandshake();
                s.getInputStream().read();
            } catch (Exception ignored) {
                // handshake failures are asserted on the client side
            }
        }, "tls-test-server");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    private SSLSocket connect(Path trustedCrt, int port) throws Exception {
        SSLContext ctx = TlsContexts.client(trustedCrt);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress("localhost", port), 5000);
        raw.setSoTimeout(5000);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, "localhost", port, true);
        TlsContexts.hardenClient(s);
        return s;
    }

    private static boolean causeChainContains(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void handshakeSucceedsWithTrustedCert() throws Exception {
        int port = startServer(certs.serverP12);
        try (SSLSocket s = connect(certs.serverCrt, port)) {
            s.startHandshake();
            assertTrue(Set.of("TLSv1.3", "TLSv1.2").contains(s.getSession().getProtocol()));
        }
    }

    @Test
    void hostnameMismatchFails() throws Exception {
        int port = startServer(certs.wrongHostP12);
        try (SSLSocket s = connect(certs.wrongHostCrt, port)) {
            assertThrows(SSLHandshakeException.class, s::startHandshake);
        }
    }

    @Test
    void expiredCertFailsEvenWhenDirectlyTrusted() throws Exception {
        int port = startServer(certs.expiredP12);
        try (SSLSocket s = connect(certs.expiredCrt, port)) {
            SSLHandshakeException e = assertThrows(SSLHandshakeException.class, s::startHandshake);
            assertTrue(causeChainContains(e, CertificateExpiredException.class), "cause chain: " + e);
        }
    }

    @Test
    void notYetValidCertFails() throws Exception {
        int port = startServer(certs.notYetValidP12);
        try (SSLSocket s = connect(certs.notYetValidCrt, port)) {
            SSLHandshakeException e = assertThrows(SSLHandshakeException.class, s::startHandshake);
            assertTrue(causeChainContains(e, CertificateNotYetValidException.class), "cause chain: " + e);
        }
    }

    @Test
    void untrustedCertFails() throws Exception {
        int port = startServer(certs.untrustedP12);
        try (SSLSocket s = connect(certs.serverCrt, port)) {
            assertThrows(SSLHandshakeException.class, s::startHandshake);
        }
    }

    @Test
    void legacyProtocolRejected() throws Exception {
        int port = startServer(certs.serverP12);
        try (SSLSocket s = connect(certs.serverCrt, port)) {
            try {
                s.setEnabledProtocols(new String[] {"TLSv1.1"});
                s.startHandshake();
                fail("TLSv1.1 handshake must not succeed");
            } catch (IllegalArgumentException | SSLException expected) {
                // either the protocol is refused up front or the handshake fails
            }
        }
    }

    @Test
    void wrongKeystorePasswordFails() {
        assertThrows(IOException.class, () -> TlsContexts.server(certs.serverP12, "nope".toCharArray()));
    }
}
