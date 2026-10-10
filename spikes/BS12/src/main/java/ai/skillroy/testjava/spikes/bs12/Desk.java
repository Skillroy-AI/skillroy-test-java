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

package ai.skillroy.testjava.spikes.bs12;

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentHome;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.controller.LaunchedTarget;
import ai.skillroy.testjava.controller.SecureFiles;
import ai.skillroy.testjava.controller.TargetLauncher;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One sample lending desk (seed A) with the agent on the command line, launched as the scenario tests launch it
 * (the real {@link TargetLauncher} and {@link AgentClient}), from the copies {@code run.sh prepare} puts under
 * {@code target/}: the agent home with the lending module ({@code target/agent-home}) and the desk's class path
 * ({@code target/desk}). Every wait is bounded.
 */
final class Desk implements AutoCloseable {

    static final String MAIN_CLASS = "ai.skillroy.testjava.samples.lending.LendingApp";
    static final String FRAME_TITLE = "Tool Library Lending Desk";
    /** The sample's seed-A desk assistant (phase1-contracts.md 9.1): sample data of the lending desk, not a secret. */
    static final String STAFF = "100";
    static final String STAFF_PASSWORD = "1111";
    static final String READY_TOPICS = "input.requested,prompt.waiting";
    private static final AtomicInteger LAUNCHES = new AtomicInteger();

    final LaunchedTarget launched;
    final AgentClient client;
    final String targetJava;
    final long launchMs;

    private Desk(LaunchedTarget launched, AgentClient client, String targetJava, long launchMs) {
        this.launched = launched;
        this.client = client;
        this.targetJava = targetJava;
        this.launchMs = launchMs;
    }

    static Desk launch(String label) throws Exception {
        Path t = Bs12.target();
        AgentHome home = AgentHome.of(t.resolve("agent-home"));
        Path parent = t.resolve("discovery");
        Files.createDirectories(parent);
        Path dir = SecureFiles.ensureOwnerOnlyDirectory(parent.resolve(label + "-" + ProcessHandle.current().pid()
                + "-" + LAUNCHES.incrementAndGet()), "the discovery directory");
        Path java = Path.of(System.getProperty("bs12.targetJava",
                Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        Path logs = t.resolve("logs");
        Files.createDirectories(logs);
        TargetLauncher.Builder b = TargetLauncher.java(java)
                .mainClass(MAIN_CLASS)
                .jvmArg("-Dlending.seed=A")
                .discoveryDir(dir)
                .inheritOutputTo(logs.resolve(label + ".log"))
                .classPath(List.of(t.resolve("desk").resolve("skillroy-sample-lending-app.jar"),
                        t.resolve("desk").resolve("hsqldb.jar")))
                .javaAgent(home, Map.of("discoveryDir", dir.toString()));
        long t0 = System.nanoTime();
        LaunchedTarget launched = b.start();
        AgentClient client = null;
        try {
            Endpoint endpoint = launched.awaitAgent(Duration.ofSeconds(180));
            client = AgentClient.connect(endpoint);
            Event ready = client.await("target.ready", 0, Map.of("ready", true), Duration.ofSeconds(180));
            if (ready == null) {
                throw new IllegalStateException("the agent did not report the desk ready within 180 s");
            }
            Desk desk = new Desk(launched, client, java.toString(), (System.nanoTime() - t0) / 1_000_000L);
            desk.settle();
            return desk;
        } catch (Exception | Error e) {
            if (client != null) {
                client.close();
            }
            launched.close();
            throw e;
        }
    }

    RpcResult call(String method, Map<String, Object> params) throws Exception {
        return client.call(method, params);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> value(String method, Map<String, Object> params) throws Exception {
        Object v = client.call(method, params).value();
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("value", String.valueOf(v));
    }

    /** {@code swing.settle} (bounded at 3 s, 100 ms quiet). */
    Map<String, Object> settle() throws Exception {
        return value("swing.settle", Map.of("timeoutMs", 3000, "quietMs", 100));
    }

    /** The first event of {@code topics} after {@code since}, or null after {@code ms}. */
    Event await(String topics, long since, long ms) throws Exception {
        return client.await(topics, since, null, Duration.ofMillis(ms));
    }

    /**
     * One engine input through {@code lending.input}, then the desk's own "waiting again" event (or a prompt) and a
     * settle: how the pack's verbs drive the desk between screens.
     */
    Event input(String source, String value) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("source", source);
        if (value != null) {
            p.put("value", value);
        }
        RpcResult r = call("lending.input", p);
        Event e = await(READY_TOPICS, r.seqBefore(), 10_000);
        settle();
        return e;
    }

    /** Answers the open prompt through {@code lending.prompt.answer}, then waits as {@link #input}. */
    Event answer(String choice) throws Exception {
        RpcResult r = call("lending.prompt.answer", Map.of("choice", choice));
        Event e = await(READY_TOPICS, r.seqBefore(), 10_000);
        settle();
        return e;
    }

    /** Signs the seed-A assistant on (the person's verb in Phase 5's session; the model never sees the password). */
    void signOn() throws Exception {
        input("Entry", STAFF);
        input("Entry", STAFF_PASSWORD);
    }

    Map<String, Object> state() throws Exception {
        return value("lending.state", Map.of());
    }

    Map<String, Object> loan() throws Exception {
        return value("lending.loan", Map.of());
    }

    /** Answers any open prompt (first button), cancels any open loan; bounded, best effort. */
    void recover() {
        try {
            for (int i = 0; i < 5; i++) {
                Object prompt = client.call("lending.prompt", Map.of()).value();
                if (!(prompt instanceof Map<?, ?> p) || !(p.get("buttons") instanceof List<?> buttons)
                        || buttons.isEmpty()) {
                    break;
                }
                answer(String.valueOf(buttons.get(buttons.size() - 1)));
            }
            try {
                input("Key.CANCEL", null);
            } catch (AgentRpcException ignored) {
                // not valid in this state
            }
        } catch (Exception e) {
            Bs12.log("recover: %s", e);
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            launched.close();
        }
    }
}
