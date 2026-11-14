package com.example.securechat.common;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Client-side trust manager that adds an explicit validity-period check on top of a PKIX delegate.
 *
 * <p>OpenJDK's PKIXValidator returns early when the leaf certificate is itself present in the trust
 * store, which skips the validity check. Every {@code checkServerTrusted} overload therefore calls the
 * <em>same</em> overload on the delegate (so hostname verification driven by the socket/engine still
 * runs) and then calls {@code chain[0].checkValidity()}.
 */
final class ValidityCheckingTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager delegate;

    ValidityCheckingTrustManager(X509ExtendedTrustManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        checkLeafValidity(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
        checkLeafValidity(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
        checkLeafValidity(chain);
    }

    private static void checkLeafValidity(X509Certificate[] chain) throws CertificateException {
        // CertificateExpiredException / CertificateNotYetValidException propagate to the caller.
        chain[0].checkValidity();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("client role only");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new CertificateException("client role only");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new CertificateException("client role only");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
