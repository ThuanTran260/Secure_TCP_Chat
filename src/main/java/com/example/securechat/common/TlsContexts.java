package com.example.securechat.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/** Factory for the server and client {@link SSLContext}s plus socket hardening helpers. */
public final class TlsContexts {

    private TlsContexts() {}

    /** Server context backed by a PKCS12 keystore; a wrong password surfaces as an IOException. */
    public static SSLContext server(Path pkcs12, char[] password) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(pkcs12)) {
            ks.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** Client context that trusts exactly the given certificate and also enforces its validity period. */
    public static SSLContext client(Path trustedCertPem) throws GeneralSecurityException, IOException {
        X509Certificate cert;
        try (InputStream in = Files.newInputStream(trustedCertPem)) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("server", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
        tmf.init(ks);
        X509ExtendedTrustManager pkix = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager) {
                pkix = (X509ExtendedTrustManager) tm;
                break;
            }
        }
        if (pkix == null) {
            throw new GeneralSecurityException("No X509ExtendedTrustManager available");
        }

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {new ValidityCheckingTrustManager(pkix)}, null);
        return ctx;
    }

    public static void hardenServer(SSLServerSocket s) {
        s.setEnabledProtocols(Protocol.TLS_PROTOCOLS);
        s.setNeedClientAuth(false);
    }

    public static void hardenClient(SSLSocket s) {
        s.setEnabledProtocols(Protocol.TLS_PROTOCOLS);
        SSLParameters p = s.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        s.setSSLParameters(p);
    }
}
