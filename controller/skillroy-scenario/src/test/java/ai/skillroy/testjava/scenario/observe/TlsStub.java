/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.skillroy.testjava.scenario.observe;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A loopback HTTPS stub for the telemetry transport tests (Phase 6 security fix, pass 2, P2-L8-F2: a telemetry header
 * needs an {@code https://} endpoint, so the tests that see a header arrive need TLS). The certificate is self-signed
 * for {@code 127.0.0.1} and {@code localhost}, made by the JDK's own {@code keytool} into the test's directory (nothing
 * is downloaded or committed); a client trusts it through {@link #certificatePem()} (the exporter's test seam) or, in a
 * child JVM, through {@link #trustStoreOptions()}.
 */
public final class TlsStub implements AutoCloseable {

    private static final String PASSWORD = "skillroy-test-stub";

    private final HttpsServer server;
    private final byte[] certificatePem;
    private final Path trustStore;

    private TlsStub(HttpsServer server, byte[] certificatePem, Path trustStore) {
        this.server = server;
        this.certificatePem = certificatePem;
        this.trustStore = trustStore;
    }

    /** Starts the stub on a free loopback port; every request goes to {@code handler}. */
    public static TlsStub start(Path dir, HttpHandler handler) throws Exception {
        Files.createDirectories(dir);
        Path keyStore = dir.resolve("tls-stub-key.p12");
        Files.deleteIfExists(keyStore);
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "stub", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "san=ip:127.0.0.1,dns:localhost",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", PASSWORD,
                "-keypass", PASSWORD).redirectErrorStream(true).redirectOutput(dir.resolve("keytool.log").toFile())
                .start();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("keytool failed: " + Files.readString(dir.resolve("keytool.log")));
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) {
            keys.load(in, PASSWORD.toCharArray());
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", handler);
        server.start();

        Certificate certificate = keys.getCertificate("stub");
        String pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets
                .US_ASCII)).encodeToString(certificate.getEncoded()) + "\n-----END CERTIFICATE-----\n";
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("stub", certificate);
        Path trustStore = dir.resolve("tls-stub-trust.p12");
        try (OutputStream out = Files.newOutputStream(trustStore)) {
            trusted.store(out, PASSWORD.toCharArray());
        }
        return new TlsStub(server, pem.getBytes(StandardCharsets.US_ASCII), trustStore);
    }

    /** The stub's port. */
    public int port() {
        return server.getAddress().getPort();
    }

    /** {@code https://127.0.0.1:<port>}. */
    public String endpoint() {
        return "https://127.0.0.1:" + port();
    }

    /** The stub's certificate as PEM, for the exporter's test seam. */
    public byte[] certificatePem() {
        return certificatePem.clone();
    }

    /** The options that make a child JVM trust the stub (and nothing else). */
    public List<String> trustStoreOptions() {
        return List.of("-Djavax.net.ssl.trustStore=" + trustStore, "-Djavax.net.ssl.trustStorePassword=" + PASSWORD,
                "-Djavax.net.ssl.trustStoreType=PKCS12");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
