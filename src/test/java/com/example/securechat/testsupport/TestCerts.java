package com.example.securechat.testsupport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generates throw-away keystores and certificates at test time by invoking keytool.
 * Nothing is ever checked in: every file lives in the caller-supplied (temporary) directory.
 */
public final class TestCerts {

    public static final String PASSWORD = "test-pass";
    private static final String GOOD_SAN = "SAN=dns:localhost,ip:127.0.0.1";
    private static final String DN_LOCALHOST = "CN=localhost, OU=Lab, O=SecureChat, L=Hanoi, C=VN";

    public final Path serverP12;
    public final Path serverCrt;
    public final Path wrongHostP12;
    public final Path wrongHostCrt;
    public final Path expiredP12;
    public final Path expiredCrt;
    public final Path notYetValidP12;
    public final Path notYetValidCrt;
    public final Path untrustedP12;

    private TestCerts(Path dir) throws IOException, InterruptedException {
        serverP12 = dir.resolve("server.p12");
        serverCrt = dir.resolve("server.crt");
        generate(serverP12, serverCrt, DN_LOCALHOST, GOOD_SAN, null, "365");

        wrongHostP12 = dir.resolve("wronghost.p12");
        wrongHostCrt = dir.resolve("wronghost.crt");
        generate(wrongHostP12, wrongHostCrt, "CN=wronghost.invalid, OU=Lab, O=SecureChat, L=Hanoi, C=VN",
                "SAN=dns:wronghost.invalid", null, "365");

        expiredP12 = dir.resolve("expired.p12");
        expiredCrt = dir.resolve("expired.crt");
        generate(expiredP12, expiredCrt, DN_LOCALHOST, GOOD_SAN, "-10d", "1");

        notYetValidP12 = dir.resolve("notyetvalid.p12");
        notYetValidCrt = dir.resolve("notyetvalid.crt");
        generate(notYetValidP12, notYetValidCrt, DN_LOCALHOST, GOOD_SAN, "+10d", "30");

        // Correct SAN but a different key pair than serverCrt; clients only trust serverCrt.
        untrustedP12 = dir.resolve("untrusted.p12");
        generate(untrustedP12, null, DN_LOCALHOST, GOOD_SAN, null, "365");
    }

    public static TestCerts create(Path dir) {
        try {
            return new TestCerts(dir);
        } catch (IOException e) {
            throw new IllegalStateException("keytool failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running keytool", e);
        }
    }

    private static void generate(Path p12, Path crt, String dn, String san, String startDate, String validity)
            throws IOException, InterruptedException {
        List<String> gen = new ArrayList<>(List.of(
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", validity, "-keystore", p12.toString(), "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-dname", dn, "-ext", san));
        if (startDate != null) {
            gen.add("-startdate");
            gen.add(startDate);
        }
        keytool(gen);
        if (crt != null) {
            keytool(List.of("-exportcert", "-rfc", "-alias", "server", "-keystore", p12.toString(),
                    "-storetype", "PKCS12", "-storepass", PASSWORD, "-file", crt.toString()));
        }
    }

    private static void keytool(List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("keytool timed out");
        }
        if (p.exitValue() != 0) {
            throw new IOException("keytool exited with " + p.exitValue() + ": " + out);
        }
    }
}
