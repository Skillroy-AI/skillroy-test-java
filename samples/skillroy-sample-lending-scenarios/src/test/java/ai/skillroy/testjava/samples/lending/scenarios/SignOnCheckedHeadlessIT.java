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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentHome;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.LaunchedTarget;
import ai.skillroy.testjava.controller.TargetLauncher;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase6-contracts.md 13.1 item 6 without a display (fix wave of the Phase 6 review, FC, 2026-10-09; finding L3-F3):
 * "{@code sign.on.checked} enabled in a test: the password index is {@code <redacted>} in the event". The real agent
 * with the hooks module ({@code optionalModules=skillroy-agent-hooks}, {@code JAVAAGENT}) in a headless child JVM
 * ({@link SignOnHeadlessMain}: the desk's database and its real {@code AccessControl}, no window, checking staff
 * 100's password every 100 ms); the pack's own
 * catalogue with {@code sign.on.checked} enabled and capturing both arguments
 * ({@link HookChecks#signOnCheckedCapturingThePasswordIndex}: the committed entry captures index 0 only, which would
 * never exercise {@code secretArgs: [1]}). The first event after the install carries the code at index 0,
 * {@code <redacted>} at index 1 and the result, and the password is in no event, no health answer and no line of the
 * child's output.
 *
 * <p>A failsafe test (the agent home is unpacked at {@code pre-integration-test}) that needs no display, so it runs in
 * the normal {@code mvn verify}. The child runs on this JVM's own Java (the fixture main is compiled for it); the hooks
 * leg's order 67 ({@link HookChecks}) proves the same on the desk itself on both target Javas. The agent home is a copy
 * of the module's without the lending module, whose readiness waits for a desk this host does not start.
 */
class SignOnCheckedHeadlessIT {

    private static final String HOOK = "sign.on.checked";
    private static final CallOptions CALL = CallOptions.defaults().withTimeoutMs(60_000L);

    @Test
    void signOnCheckedEnabledRedactsThePasswordIndexInTheEvent() throws Exception {
        AgentHome home = homeWithoutTheLendingModule();
        Path dir = ItEnv.newDiscoveryDir("sign-on-headless");
        Path log = ItEnv.log("sign-on-headless.log");
        Path credentials = ItEnv.buildDir().resolve("it-sign-on").resolve("staff-100.txt");
        Files.createDirectories(credentials.getParent());
        Files.writeString(credentials, "100 " + LoadModeChecks.PASSWORD + "\n", StandardCharsets.UTF_8);
        List<Path> classPath = new ArrayList<>(ItEnv.applicationClassPath());
        classPath.add(Path.of(SignOnHeadlessMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        LaunchedTarget launched = TargetLauncher.java(Path.of(System.getProperty("java.home"), "bin", "java"))
                .mainClass(SignOnHeadlessMain.class.getName())
                .arg(credentials.toString())
                .jvmArg("-Djava.awt.headless=true")
                .classPath(classPath)
                .discoveryDir(dir)
                .inheritOutputTo(log)
                .javaAgent(home, Map.of("discoveryDir", dir.toString(), LendingTarget.OPTIONAL_MODULES,
                        "skillroy-agent-hooks"))
                .start();
        AgentClient client = null;
        try {
            client = AgentClient.connect(launched.awaitAgent(LendingTarget.AGENT_TIMEOUT));
            LendingTarget.awaitReady(client);
            // The agent is ready before the host has built its database and loaded AccessControl, and the install
            // retransforms only loaded classes ("class not loaded" otherwise).
            awaitLine(log, "ready", Duration.ofSeconds(120));
            assertEquals(CapabilityState.OK, client.capabilities().get("hook.engine").state(),
                    String.valueOf(client.capabilities().get("hook.engine")));

            HookCatalogue enabled = HookChecks.signOnCheckedCapturingThePasswordIndex(Runs.PACK.hooks());
            Map<?, ?> installed = (Map<?, ?>) client.call("hook.install", HookChecks.installParams(enabled, false),
                    CALL.withTimeoutMs(70_000L)).value();
            assertTrue(((List<?>) installed.get("hooks")).stream().anyMatch(h -> h instanceof Map<?, ?> status
                    && HOOK.equals(status.get("id")) && "OK".equals(status.get("state"))), String.valueOf(installed));

            long since = client.call("trace.mark", Map.of("label", "sign-on-headless"), CALL).seqAfter();
            Event fired = client.await("hook." + HOOK, since, null, Duration.ofSeconds(60));
            assertNotNull(fired, "the password check fired " + HOOK);

            Map<?, ?> answer = (Map<?, ?>) client.call("hook.events", Map.of("since", since, "id", HOOK, "limit", 10L),
                    CALL).value();
            List<?> events = (List<?>) answer.get("events");
            assertFalse(events.isEmpty(), String.valueOf(answer));
            Map<?, ?> payload = (Map<?, ?>) ((Map<?, ?>) events.get(0)).get("payload");
            Map<?, ?> args = (Map<?, ?>) payload.get("args");
            assertEquals("100", args.get("0"), String.valueOf(args));
            assertEquals(SecretKeys.REDACTED, args.get("1"), "the password's index is redacted: " + args);
            assertEquals(Boolean.TRUE, payload.get("returned"), String.valueOf(payload));
            assertEquals(List.of(), LoadModeChecks.passwordOccurrences(Json.write(events)), "no event holds it");
            Object health = client.call("hook.health", Map.of("ids", List.of(HOOK)), CALL).value();
            assertEquals(List.of(), LoadModeChecks.passwordOccurrences(Json.write(health)), "nor the health answer");

            Map<?, ?> removed = (Map<?, ?>) client.call("hook.remove", Map.of(), CALL).value();
            for (Object restored : (List<?>) removed.get("restored")) {
                assertEquals("OK", ((Map<?, ?>) restored).get("verified"), String.valueOf(restored));
            }
        } finally {
            if (client != null) {
                client.close();
            }
            launched.close();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(output.contains("checked=true"), "the host checked the password: " + output);
        assertFalse(LoadModeChecks.passwordOccurrences(output).size() > 0, "nor the child's output: " + output);
    }

    /**
     * Waits until the child's output (its log file) holds {@code line} as a line of its own: a bounded poll, the only
     * way to wait on a child's output file (as the agent home's ITs do).
     */
    private static void awaitLine(Path log, String line, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (Files.isRegularFile(log) && List.of(new String(Files.readAllBytes(log), StandardCharsets.UTF_8)
                    .split("\\R")).contains(line)) {
                return;
            }
            assertTrue(System.nanoTime() < end, "the host did not print \"" + line + "\" within " + timeout);
            Thread.sleep(50L);
        }
    }

    /**
     * {@code target/it-hooks-home}: a fresh copy of the module's agent home ({@link ItEnv#agentHome()}) without
     * {@code modules/skillroy-sample-lending-agent.jar}.
     */
    private static AgentHome homeWithoutTheLendingModule() throws IOException {
        Path source = ItEnv.agentHome().dir();
        Path copy = ItEnv.buildDir().resolve("it-hooks-home");
        if (Files.exists(copy)) {
            try (Stream<Path> walk = Files.walk(copy)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Path skipped = source.resolve("modules").resolve("skillroy-sample-lending-agent.jar");
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(copy.resolve(source.relativize(d).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.equals(skipped)) {
                    Files.copy(file, copy.resolve(source.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue(Files.isRegularFile(skipped), "the module's agent home holds the lending module: " + skipped);
        return AgentHome.of(copy);
    }
}
