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
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.controller.LaunchedTarget;
import ai.skillroy.testjava.controller.TargetLauncher;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Byteman on the sample lending desk (phase6-contracts.md 13.2 item 7): the desk with the agent through
 * {@code -javaagent} and {@code optionalModules=skillroy-agent-byteman;byteman.jar=<path>} (the Byteman of this
 * module's test class path, test scope). {@code rule.check} of a rule on {@code AccessControl.returnLimit}
 * ({@code AT EXIT}, {@code DO fired($0, $!)}) is {@code ok} with {@code typeChecked: false} before any submit;
 * {@code rule.submit} with {@code confirm} loads Byteman and installs it; the return line of committed scenario A
 * publishes {@code rule.fired} with two bindings (a handle and the limit); {@code rule.list} shows it;
 * {@code rule.unload} restores with {@code verified: OK}; a rule naming {@code killJVM} is {@code RULE_REJECTED}; the
 * desk's listening sockets before the first submit and after the unload are equal and hold exactly the agent's port.
 *
 * <p><b>Integration note (BW):</b> the contract places this as one block of {@code HookChecks} after the
 * {@code hook.remove} demonstration, in the hooks leg ({@code LendingTarget.Mode.JAVAAGENT_HOOKS} with the two Byteman
 * options). Neither existed on a branch BW could merge when this was written, so the block is a class of its own that
 * launches its own desk; at integration its body moves into {@code HookChecks} and its two options onto the hooks
 * mode.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BytemanDeskIT {

    static final CallOptions CALL = CallOptions.defaults().withTimeoutMs(60_000L);
    static final CallOptions CONFIRM = CALL.withConfirm(true);
    static final String ACCESS_CONTROL = "ai.skillroy.testjava.samples.lending.AccessControl";
    static final String LIMIT_RULE = "RULE limit read\n"
            + "CLASS " + ACCESS_CONTROL + "\n"
            + "METHOD returnLimit\n"
            + "AT EXIT\n"
            + "IF true\n"
            + "DO fired($0, $!)\n"
            + "ENDRULE\n";

    private LaunchedTarget launched;
    private AgentClient client;

    /** The Byteman jar of this module's test class path: the code source of its Main, loaded uninitialised. */
    static String bytemanJar() throws Exception {
        Class<?> main = Class.forName("org.jboss.byteman.agent.Main", false, BytemanDeskIT.class.getClassLoader());
        return new File(main.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
    }

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        AgentHome home = ItEnv.agentHome();
        Path dir = ItEnv.newDiscoveryDir("javaagent-byteman");
        launched = TargetLauncher.java(ItEnv.targetJava())
                .mainClass(LendingTarget.MAIN_CLASS)
                .jvmArg("-Dlending.seed=A")
                .jvmArg("-Dlending.pin=" + LendingTarget.PIN)
                .discoveryDir(dir)
                .inheritOutputTo(ItEnv.log("javaagent-byteman.log"))
                .javaAgent(home, Map.of("discoveryDir", dir.toString(),
                        "optionalModules", "skillroy-agent-byteman", "byteman.jar", bytemanJar()))
                .classPath(ItEnv.applicationClassPath())
                .start();
        Endpoint endpoint = launched.awaitAgent(LendingTarget.AGENT_TIMEOUT);
        client = AgentClient.connect(endpoint);
        LendingTarget.awaitReady(client);
    }

    @AfterAll
    void close() {
        if (client != null) {
            client.close();
        }
        if (launched != null) {
            launched.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(Object v) {
        return (Map<String, Object>) v;
    }

    /**
     * The process's listening TCP ports: {@code /proc/<pid>/net/tcp} and {@code tcp6} entries in state {@code 0A}
     * whose inode is the target of one of {@code /proc/<pid>/fd/*}; null on a host without {@code /proc}.
     */
    static Set<Integer> listening(long pid) throws IOException {
        Path proc = Paths.get("/proc", Long.toString(pid));
        if (!Files.isDirectory(proc.resolve("fd")) || !Files.isReadable(proc.resolve("net/tcp"))) {
            return null;
        }
        Set<String> inodes = new HashSet<>();
        File[] fds = proc.resolve("fd").toFile().listFiles();
        if (fds != null) {
            for (File fd : fds) {
                try {
                    String target = Files.readSymbolicLink(fd.toPath()).toString();
                    if (target.startsWith("socket:[")) {
                        inodes.add(target.substring(8, target.length() - 1));
                    }
                } catch (IOException gone) {
                    // closed while listing
                }
            }
        }
        Set<Integer> ports = new TreeSet<>();
        for (String table : List.of("net/tcp", "net/tcp6")) {
            Path p = proc.resolve(table);
            if (!Files.isReadable(p)) {
                continue;
            }
            List<String> lines = Files.readAllLines(p, StandardCharsets.US_ASCII);
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).trim().split("\\s+");
                if (f.length > 9 && "0A".equals(f[3]) && inodes.contains(f[9])) {
                    ports.add(Integer.parseInt(f[1].substring(f[1].indexOf(':') + 1), 16));
                }
            }
        }
        return ports;
    }

    @Test
    void aRuleOnTheReturnLimitIsCheckedSubmittedFiredListedAndUnloadedWithNoListener() throws Exception {
        Capability engine = client.capabilities().get("rule.engine");
        assertEquals(CapabilityState.OK, engine.state(), String.valueOf(engine));
        Set<Integer> before = listening(launched.pid());
        if (before == null) {
            System.out.println("BytemanDeskIT: no /proc on this host; the listening-socket checks are skipped");
        } else {
            assertEquals(Collections.singleton(client.endpoint().port()), before,
                    "only the agent listens before the first submit");
        }

        Map<String, Object> check = value(client.call("rule.check", Map.of("rule", LIMIT_RULE), CALL).value());
        assertEquals(Boolean.TRUE, check.get("ok"), String.valueOf(check));
        assertEquals(Boolean.FALSE, check.get("typeChecked"), "Byteman is not loaded before the first submit");

        try {
            client.call("rule.submit", Map.of("rule", LIMIT_RULE.replace("fired($0, $!)", "killJVM()")
                    .replace("RULE limit read", "RULE stop")), CONFIRM);
            fail("a rule naming killJVM must be refused");
        } catch (AgentRpcException e) {
            assertEquals(ErrorCodes.RULE_REJECTED, e.error().code(), String.valueOf(e.error()));
        }

        Map<String, Object> submitted = value(client.call("rule.submit", Map.of("rule", LIMIT_RULE), CONFIRM).value());
        assertEquals(Boolean.TRUE, submitted.get("installed"), String.valueOf(submitted));
        long since = client.call("agent.info", Map.of(), CALL).seqAfter();

        RunResult a = Runs.run(client, Runs.committed(ScenarioA.ID), ItEnv.runDir("javaagent-byteman",
                ScenarioA.ID)).result();
        assertEquals(Status.PASS, a.status(), a.message());
        Event fired = client.await(Topics.RULE_FIRED, since, Map.of("rule", "limit read"), Duration.ofSeconds(30));
        assertEquals(ACCESS_CONTROL, fired.payload().get("class"));
        assertEquals("returnLimit", fired.payload().get("method"));
        Map<String, Object> bindings = value(fired.payload().get("bindings"));
        assertEquals(2, bindings.size(), String.valueOf(bindings));
        assertEquals(ACCESS_CONTROL, value(bindings.get("0")).get("class"), "$0 is a handle");
        assertTrue(String.valueOf(bindings.get("1")).matches("\\d+\\.\\d{2}"), "$! is the limit: " + bindings);

        Map<String, Object> listed = value(client.call("rule.list", Map.of(), CALL).value());
        List<?> rules = (List<?>) listed.get("rules");
        assertEquals(1, rules.size(), String.valueOf(listed));
        assertEquals("limit read", value(rules.get(0)).get("name"));

        Map<String, Object> unloaded = value(client.call("rule.unload", Map.of("name", "limit read"), CALL).value());
        assertEquals(Boolean.TRUE, unloaded.get("removed"));
        List<Map<String, Object>> restored = new ArrayList<>();
        for (Object r : (List<?>) unloaded.get("restored")) {
            restored.add(value(r));
        }
        assertEquals(1, restored.size(), String.valueOf(unloaded));
        assertEquals(ACCESS_CONTROL, restored.get(0).get("class"));
        assertEquals("OK", restored.get(0).get("verified"), String.valueOf(unloaded));
        assertEquals(0L, ((Number) restored.get(0).get("foreignEvents")).longValue());
        if (before != null) {
            assertEquals(before, listening(launched.pid()), "the listening sockets after the unload equal those before");
        }
        assertFalse(Files.readString(ItEnv.log("javaagent-byteman.log")).contains("org.jboss.byteman"),
                "nothing of Byteman on the desk's console");
    }
}
