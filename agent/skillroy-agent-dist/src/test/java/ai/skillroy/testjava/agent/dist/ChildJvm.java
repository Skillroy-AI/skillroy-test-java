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

package ai.skillroy.testjava.agent.dist;

import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A target JVM launched by a test: the java named by {@code skillroy.it.targetJava} (else the running JVM's),
 * output captured in a log file, standard input kept open so that the fixture parks until {@link #close()}.
 * Timeouts are generous because the Linux image runs x86-64 under emulation.
 */
final class ChildJvm implements AutoCloseable {

    static final Charset UTF8 = Charset.forName("UTF-8");
    static final long START_TIMEOUT_MS = 180000L;

    final Process process;
    final File log;
    private long pid = -1;

    private ChildJvm(Process process, File log) {
        this.process = process;
        this.log = log;
    }

    static String javaExecutable() {
        String target = System.getProperty("skillroy.it.targetJava");
        if (target != null && !target.trim().isEmpty()) {
            return target.trim();
        }
        return new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
    }

    /** Starts {@code java <jvmArgs> -cp <classPath> <mainClass> <args>} with extra environment variables. */
    static ChildJvm start(File workDir, List<String> jvmArgs, String classPath, String mainClass, List<String> args,
                          Map<String, String> env) throws IOException {
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(classPath);
        command.add(mainClass);
        command.addAll(args);
        File log = File.createTempFile("child-", ".log", workDir);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
                .redirectOutput(log);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("SKILLROY_AGENT_ENABLED");
        builder.environment().putAll(env);
        return new ChildJvm(builder.start(), log);
    }

    String output() throws IOException {
        return new String(Files.readAllBytes(log.toPath()), UTF8);
    }

    /** The value of the first output line {@code key=value}, waiting for it. */
    String awaitLine(String key) throws Exception {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (String line : output().split("\\r?\\n")) {
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1);
                }
            }
            if (!process.isAlive()) {
                fail("child exited with " + process.exitValue() + " before printing " + key + ":\n" + output());
            }
            Thread.sleep(50);
        }
        fail("child did not print " + key + " within " + START_TIMEOUT_MS + " ms:\n" + output());
        return null;
    }

    /**
     * Waits until the child's output contains the text and returns it. A line printed just before the awaited
     * {@code key=value} line is not necessarily in the log file when {@link #awaitLine} returns: the child
     * writes line by line, so a reader can see the earlier line and not yet the later one.
     */
    String awaitOutput(String text) throws Exception {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String out = output();
            if (out.contains(text)) {
                return out;
            }
            if (!process.isAlive()) {
                fail("child exited with " + process.exitValue() + " before printing " + text + ":\n" + out);
            }
            Thread.sleep(50);
        }
        fail("child did not print " + text + " within " + START_TIMEOUT_MS + " ms:\n" + output());
        return null;
    }

    long pid() throws Exception {
        if (pid < 0) {
            pid = pidOf(awaitLine("pid"));
        }
        return pid;
    }

    /**
     * The pid at the start of the {@code pid=} line's value. A child run with {@code -verbose:class} shares its
     * standard output with the JVM's class-loading lines, and on Java 8 {@code println} writes the text and the line
     * separator as two writes (an auto-flushing {@code PrintStream}), so a whole {@code [Loaded … from …]} line can
     * land between the digits and their newline: {@code pid=3403[Loaded …]}. The JVM writes each of its lines in one
     * call, so the digits themselves are never split.
     */
    static long pidOf(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            throw new NumberFormatException("no pid at the start of '" + value + "'");
        }
        return Long.parseLong(value.substring(0, end));
    }

    /** Waits for this child's discovery file in the directory and returns it parsed. */
    Map<String, Object> awaitDiscovery(File dir) throws Exception {
        final String prefix = "skillroy-" + pid() + "-";
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            File[] files = dir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File f) {
                    return f.getName().startsWith(prefix) && f.getName().endsWith(".json");
                }
            });
            if (files != null && files.length == 1) {
                return Json.parseObject(new String(Files.readAllBytes(files[0].toPath()), UTF8));
            }
            if (!process.isAlive()) {
                fail("child exited before publishing discovery:\n" + output());
            }
            Thread.sleep(50);
        }
        fail("no discovery file for pid " + pid() + " in " + dir + ":\n" + output());
        return null;
    }

    @Override
    public void close() {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Already gone.
        }
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A controller-side view of one agent, from its discovery file: every request carries {@code Skillroy-HMAC}
     * credentials and every reply's MAC is verified before the reply is used (hardening item H2).
     */
    static final class Agent {
        final int port;
        final String token;
        final String sessionId;
        final Map<String, Object> discovery;

        Agent(Map<String, Object> discovery) throws IOException {
            this.discovery = discovery;
            this.port = ((Number) discovery.get("port")).intValue();
            this.token = new String(Files.readAllBytes(new File((String) discovery.get("tokenFile")).toPath()), UTF8)
                    .trim();
            this.sessionId = (String) discovery.get(Protocol.KEY_SESSION_ID);
            assertTrue(Auth.isValidSessionId(sessionId), "the discovery file names the session: " + discovery);
        }

        /** One authenticated request: the connection, with the nonce its reply MAC must use. */
        final class Request {
            final HttpURLConnection connection;
            final String nonce;

            Request(String method, String pathAndQuery, byte[] body) throws IOException {
                connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + pathAndQuery).openConnection();
                connection.setRequestMethod(method);
                String authorization;
                if (pathAndQuery.startsWith("/ping?")) {
                    nonce = pathAndQuery.substring(pathAndQuery.indexOf("nonce=") + 6).split("&")[0];
                    authorization = Auth.helloAuthorization(token, nonce);
                } else {
                    nonce = Auth.newNonce();
                    authorization = Auth.requestAuthorization(token, sessionId, nonce, method, pathAndQuery,
                            body == null ? Auth.EMPTY_BODY_SHA256 : Auth.sha256hex(body));
                }
                connection.setRequestProperty(Auth.HEADER_AUTHORIZATION, authorization);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(120000);
                if (body != null) {
                    connection.setDoOutput(true);
                    // P2-L1-F1: the body hash travels in the head, so that the agent verifies it before the body.
                    connection.setRequestProperty(Auth.HEADER_BODY_SHA256, Auth.sha256hex(body));
                    OutputStream out = connection.getOutputStream();
                    out.write(body);
                    out.close();
                }
            }

            /** The reply body after checking status and reply MAC. */
            byte[] verifiedBody(int expectedStatus) throws IOException {
                int status = connection.getResponseCode();
                assertEquals(expectedStatus, status);
                byte[] body = readBytes(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
                assertEquals(sessionId, connection.getHeaderField(Auth.HEADER_SESSION));
                assertTrue(Auth.verifyResponse(token, sessionId, nonce, status, body.length == 0
                        ? Auth.EMPTY_BODY_SHA256 : Auth.sha256hex(body), connection.getHeaderField(Auth.HEADER_MAC)),
                        "the reply MAC verifies");
                return body;
            }
        }

        /** An authenticated GET whose reply body is returned once its MAC verified. */
        String get(String pathAndQuery) throws IOException {
            return new String(new Request("GET", pathAndQuery, null).verifiedBody(200), UTF8);
        }

        /** {@code /ping} with a fresh client nonce. */
        Map<String, Object> ping() throws IOException {
            return ping(Auth.newNonce());
        }

        /** {@code /ping} with the given client nonce (16 to 64 characters of {@code [A-Za-z0-9_-]}). */
        Map<String, Object> ping(String clientNonce) throws IOException {
            Map<String, Object> reply = Json.parseObject(get("/ping?nonce=" + clientNonce));
            assertEquals(clientNonce, reply.get("clientNonce"));
            assertEquals(sessionId, reply.get(Protocol.KEY_SESSION_ID));
            return reply;
        }

        /** The JSON-RPC response of one call. */
        Map<String, Object> rpc(String method, Map<String, Object> params) throws IOException {
            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("jsonrpc", "2.0");
            request.put("id", "it");
            request.put("method", method);
            if (params != null) {
                request.put("params", params);
            }
            byte[] body = Json.write(request).getBytes(UTF8);
            return Json.parseObject(new String(new Request("POST", "/rpc", body).verifiedBody(200), UTF8));
        }

        /** The value of a call that must succeed. */
        @SuppressWarnings("unchecked")
        Map<String, Object> value(String method, Map<String, Object> params) throws IOException {
            Map<String, Object> response = rpc(method, params);
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            assertNotNull(result, method + " failed: " + response);
            return (Map<String, Object>) result.get("value");
        }

        /** The status of a GET carrying the given {@code Authorization} value as is (null: none). */
        int statusWith(String pathAndQuery, String authorization) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + pathAndQuery)
                    .openConnection();
            if (authorization != null) {
                c.setRequestProperty(Auth.HEADER_AUTHORIZATION, authorization);
            }
            c.setConnectTimeout(30000);
            c.setReadTimeout(120000);
            int status = c.getResponseCode();
            readBytes(c.getErrorStream());
            c.disconnect();
            return status;
        }

        boolean portClosed() {
            try {
                new Socket("127.0.0.1", port).close();
                return false;
            } catch (IOException refused) {
                return true;
            }
        }
    }

    static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    static List<String> list(String... items) {
        return new ArrayList<String>(Arrays.asList(items));
    }

    static String read(InputStream in) throws IOException {
        return new String(readBytes(in), UTF8);
    }

    static byte[] readBytes(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }
}
