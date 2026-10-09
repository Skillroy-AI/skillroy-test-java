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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hooks leg's own checks (phase6-contracts.md 13.2 items 1 to 3 and 6's log scan), woven by {@code @Order} into
 * {@link LoadModeChecks} on a desk launched with {@code optionalModules=skillroy-agent-hooks}
 * ({@link LendingTarget.Mode#JAVAAGENT_HOOKS}): after check 1c and before check 5 (65), the pack's catalogue is probed,
 * installed and fires, the {@code STUB} demonstration, the idempotent install and the conflict, and
 * {@code sign.on.checked} enabled once to show that the password argument is never captured; after check 14 (165), the
 * restore of every class with its byte verification; in checks 16 and 17, the re-attach with the same options; before
 * check 19 (205), the target's log: one CDS line on Java 25 (none on Java 8) and nothing of Byte Buddy,
 * {@code sun.misc.Unsafe} or an agent stack trace.
 */
abstract class HookChecks extends LoadModeChecks {

    static final List<String> HOOKS = List.of("access.checked", "limit.applied", "line.amount", "waiver.evaluated");
    static final String APP = "ai.skillroy.testjava.samples.lending.";
    /** The VM's one line after a bootstrap append on Java 9+ with class data sharing on (BS3 RESULT 9 fact 8). */
    static final String CDS_LINE = "Sharing is only supported for boot loader classes because bootstrap classpath"
            + " has been appended";

    /** The pack's catalogue as {@code hook.install} takes it. */
    static Map<String, Object> installParams(HookCatalogue catalogue, boolean replace) {
        List<Object> specs = new ArrayList<>();
        for (HookSpec spec : catalogue.toSpecs()) {
            specs.add(spec.toJson());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("specs", specs);
        params.put("catalogue", catalogue.descriptor());
        if (replace) {
            params.put("replace", true);
        }
        return params;
    }

    List<Map<String, Object>> events(String id, long since) throws Exception {
        Map<String, Object> answer = value(client().call("hook.events", Map.of("since", since, "id", id, "limit", 1000L),
                CALL));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : (List<?>) answer.get("events")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) e;
            out.add(event);
        }
        return out;
    }

    static Map<?, ?> payload(Map<String, Object> event) {
        return (Map<?, ?>) event.get("payload");
    }

    // ------------------------------------------------------------------ 65: probe, install, fire, stub

    /**
     * Item 1 (phase6-contracts.md 13.2): {@code hook.probe} of the pack's catalogue answers every enabled entry
     * {@code OK} ({@code sign.on.checked} is off, so absent) with no other agent; {@code hook.install} installs the
     * four in under 60 s touching AccessControl, WaiverEngine and Deposits once each; the matrix shows the four
     * {@code hook.<id>} {@code OK(advice)}; a return line fires {@code limit.applied} on the desk's own engine thread
     * with the staff code and the limit; a {@code DUE} fires {@code waiver.evaluated} with handles only; the same
     * install again is {@code alreadyInstalled}, a second catalogue id {@code HOOK_CONFLICT}.
     */
    @Test
    @Order(65)
    void thePacksCatalogueIsProbedInstalledAndFires() throws Exception {
        CapabilityMatrix before = client().capabilities();
        assertTrue(before.isOk("jvm.instrumentation") && before.isOk("hook.engine"), String.valueOf(before.get(
                "hook.engine")));
        HookCatalogue catalogue = Runs.PACK.hooks();
        Map<String, Object> params = installParams(catalogue, false);
        Map<String, Object> probe = value(client().call("hook.probe", Map.of("specs", params.get("specs")), CALL));
        Map<String, Object> states = new TreeMap<>();
        for (Object p : (List<?>) probe.get("hooks")) {
            states.put(String.valueOf(((Map<?, ?>) p).get("id")), ((Map<?, ?>) p).get("state"));
        }
        assertEquals(Map.of("access.checked", "OK", "limit.applied", "OK", "line.amount", "OK", "waiver.evaluated",
                "OK"), states, String.valueOf(probe));
        assertEquals(List.of(), probe.get("otherAgents"));

        long t0 = System.nanoTime();
        Map<String, Object> installed = value(client().call("hook.install", params, CALL.withTimeoutMs(70_000L)));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " hookInstallMs=" + ms);
        assertTrue(ms < 60_000L, "installed in " + ms + " ms");
        Map<String, Object> installedStates = new TreeMap<>();
        for (Object h : (List<?>) installed.get("hooks")) {
            installedStates.put(String.valueOf(((Map<?, ?>) h).get("id")), ((Map<?, ?>) h).get("state"));
        }
        assertEquals(List.copyOf(installedStates.keySet()), HOOKS, String.valueOf(installed));
        assertTrue(installedStates.values().stream().allMatch("OK"::equals), String.valueOf(installed));
        List<String> touched = new ArrayList<>();
        for (Object t : (List<?>) installed.get("touched")) {
            touched.add(String.valueOf(((Map<?, ?>) t).get("class")));
        }
        assertEquals(List.of(APP + "AccessControl", APP + "Deposits", APP + "WaiverEngine"),
                touched.stream().sorted().toList(), "each class once: " + touched);
        CapabilityMatrix after = client().capabilities();
        for (String id : HOOKS) {
            Capability capability = after.get("hook." + id);
            assertTrue(after.isOk("hook." + id), id + ": " + capability);
            assertEquals("advice", capability.strategy(), id);
        }

        // A return line: limit.applied on the desk's own engine thread, with the staff code and the limit applied.
        send("Entry", "100");
        assertEquals("IDLE", send("Entry", "1111").payload().get("state"));
        RpcResult returning = client().call("lending.input", Map.of("source", "Key.RETURN"), CALL);
        assertNotNull(client().await("input.requested", returning.seqBefore(), Map.of("state", "RETURN"), WAIT));
        long sinceScan = client().call("trace.mark", Map.of("label", "hooks.scan"), CALL).seqAfter();
        send("Barcode", "5000000000055");
        List<Map<String, Object>> fired = events("limit.applied", sinceScan);
        assertFalse(fired.isEmpty(), "the return line's check fired the hook");
        Map<?, ?> last = payload(fired.get(fired.size() - 1));
        assertEquals("LendingEngine", last.get("thread"), "the application's own thread: " + last);
        assertEquals("100", ((Map<?, ?>) last.get("args")).get("0"), String.valueOf(last));
        assertEquals("20.00", last.get("returned"), String.valueOf(last));
        assertEquals("returnLimit", last.get("method"));
        Map<?, ?> health = (Map<?, ?>) value(client().call("hook.health", Map.of(), CALL)).get("hooks");
        Map<?, ?> limit = (Map<?, ?>) health.get("limit.applied");
        assertEquals("FIRING", limit.get("state"), String.valueOf(limit));
        assertNotNull(limit.get("last"), String.valueOf(limit));
        Runs.PACK.recover(client());
        assertSignedOff();

        // A DUE of three hand tools: the waivers the engine evaluated arrive as handles, no application field.
        send("Entry", "100");
        send("Entry", "1111");
        long sinceLoan = client().call("trace.mark", Map.of("label", "hooks.due"), CALL).seqAfter();
        for (String barcode : List.of("5000000000048", "5000000000055", "5000000000062")) {
            send("Barcode", barcode);
        }
        RpcResult due = client().call("lending.input", Map.of("source", "Key.DUE"), CALL);
        assertNotNull(client().await("input.requested", due.seqBefore(), Map.of("state", "DEPOSIT"), WAIT));
        List<Map<String, Object>> waivers = events("waiver.evaluated", sinceLoan);
        assertEquals(1, waivers.size(), String.valueOf(waivers));
        Object returned = payload(waivers.get(0)).get("returned");
        assertTrue(returned instanceof List<?> list && list.size() == 1, String.valueOf(returned));
        Map<?, ?> handle = (Map<?, ?>) ((List<?>) returned).get(0);
        assertEquals(List.of("class", "ref"), handle.keySet().stream().map(String::valueOf).sorted().toList(),
                "a handle and nothing of the object's state: " + handle);
        assertEquals(APP + "LoanWaiver", handle.get("class"));
        Runs.PACK.recover(client());
        assertSignedOff();

        Map<String, Object> again = value(client().call("hook.install", params, CALL.withTimeoutMs(70_000L)));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) again.get("catalogue")).get("alreadyInstalled"));
        Map<String, Object> other = installParams(HookCatalogue.builder(catalogue).id("lending-other").build(), false);
        AgentRpcException conflict = assertThrows(AgentRpcException.class, () -> client().call("hook.install", other,
                CALL.withTimeoutMs(70_000L)));
        assertEquals(ErrorCodes.HOOK_CONFLICT, conflict.error().code());
    }

    /**
     * Item 3, the {@code STUB} demonstration: {@code line.amount} on {@code Deposits.money} armed with "1.00" without
     * {@code confirm} is {@code CONFIRM_REQUIRED}; confirmed, {@code hook.health} lists the stub and the next scanned
     * line's amount is 1.00; cleared, the desk computes again and no stub is listed. Nothing committed arms a stub.
     */
    @Test
    @Order(66)
    void theStubDemonstrationArmsAndClearsLineAmount() throws Exception {
        Map<String, Object> arm = Map.of("id", "line.amount", "value", "1.00");
        AgentRpcException unconfirmed = assertThrows(AgentRpcException.class, () -> client().call("hook.stub", arm,
                CALL));
        assertEquals(ErrorCodes.CONFIRM_REQUIRED, unconfirmed.error().code());
        send("Entry", "100");
        send("Entry", "1111");
        try {
            Map<String, Object> armed = value(client().call("hook.stub", arm, CALL.withConfirm(true)));
            assertEquals(Boolean.TRUE, armed.get("armed"), String.valueOf(armed));
            assertEquals(List.of("line.amount"), value(client().call("hook.health", Map.of(), CALL)).get("stubs"));
            send("Barcode", "5000000000017");
            Map<?, ?> line = (Map<?, ?>) ((List<?>) value(client().call("lending.loan", Map.of(), CALL))
                    .get("lines")).get(0);
            assertEquals("1.00", line.get("amount"), "the stubbed rounding: " + line);
        } finally {
            Map<String, Object> cleared = value(client().call("hook.stub", Map.of("id", "line.amount", "clear", true),
                    CALL.withConfirm(true)));
            assertEquals(Boolean.FALSE, cleared.get("armed"), String.valueOf(cleared));
        }
        assertEquals(List.of(), value(client().call("hook.health", Map.of(), CALL)).get("stubs"));
        send("Barcode", "5000000000048");
        List<?> lines = (List<?>) value(client().call("lending.loan", Map.of(), CALL)).get("lines");
        assertEquals("1.10", ((Map<?, ?>) lines.get(lines.size() - 1)).get("amount"), "computed again: " + lines);
        Runs.PACK.recover(client());
        assertSignedOff();
    }

    /**
     * phase6-contracts.md 6.3, 13.1 item 6, 13.2 item 10: {@code sign.on.checked} (catalogued and off) enabled once,
     * the catalogue replaced under its own id: a sign-on fires it with the staff code and never the password, which
     * {@code secretArgs: [1]} keeps out; its events are written below this mode's run outputs, which check 18 scans for
     * the password. The pack's own catalogue is installed again afterwards.
     */
    @Test
    @Order(67)
    void signOnCheckedEnabledNeverCapturesThePassword() throws Exception {
        HookCatalogue catalogue = Runs.PACK.hooks();
        HookCatalogue enabled = HookCatalogue.builder(catalogue).enable("sign.on.checked").build();
        try {
            value(client().call("hook.install", installParams(enabled, true), CALL.withTimeoutMs(70_000L)));
            long since = client().call("trace.mark", Map.of("label", "hooks.sign-on"), CALL).seqAfter();
            send("Entry", "100");
            send("Entry", PASSWORD);
            List<Map<String, Object>> fired = events("sign.on.checked", since);
            assertFalse(fired.isEmpty(), "the sign-on checked the password");
            Map<?, ?> args = (Map<?, ?>) payload(fired.get(0)).get("args");
            assertEquals("100", args.get("0"), String.valueOf(args));
            assertTrue(!args.containsKey("1") || SecretKeys.REDACTED.equals(args.get("1")), String.valueOf(args));
            assertEquals(Boolean.TRUE, payload(fired.get(0)).get("returned"));
            assertFalse(passwordOccurrences(Json.write(fired)).size() > 0, "the password is nowhere: " + fired);
            Path dir = ItEnv.runDir(mode().slug, "hooks");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("sign-on-events.json"), Json.writePretty(fired), StandardCharsets.UTF_8);
            Runs.PACK.recover(client());
        } finally {
            value(client().call("hook.install", installParams(catalogue, true), CALL.withTimeoutMs(70_000L)));
        }
        assertSignedOff();
    }

    // ------------------------------------------------------------------ 165: the restore

    /**
     * Items 1 and 2 (the exit criterion's restore): {@code hook.remove {}} restores every class with
     * {@code verified: OK} and no foreign event, {@code AccessControl} (check and returnLimit, the methods BS3 and
     * scenario E hook) among them, on the Java 8 and the Java 25 target alike; the matrix drops the {@code hook.<id>}
     * ids and {@code hook.health} lists no hook.
     */
    @Test
    @Order(165)
    void removeRestoresEveryClassByteIdenticalAndTheMatrixDropsTheHooks() throws Exception {
        Map<String, Object> removed = value(client().call("hook.remove", Map.of(), CALL));
        List<String> classes = new ArrayList<>();
        for (Object r : (List<?>) removed.get("restored")) {
            Map<?, ?> entry = (Map<?, ?>) r;
            classes.add(String.valueOf(entry.get("class")));
            assertEquals("OK", entry.get("verified"), String.valueOf(entry));
            assertEquals(0L, ((Number) entry.get("foreignEvents")).longValue(), String.valueOf(entry));
            if (entry.containsKey("bytesBefore")) {
                assertEquals(entry.get("bytesBefore"), entry.get("bytesAfter"), String.valueOf(entry));
            }
        }
        assertEquals(List.of(APP + "AccessControl", APP + "Deposits", APP + "WaiverEngine"),
                classes.stream().sorted().toList(), String.valueOf(removed));
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " hookRemove=" + Json.write(removed.get("restored")));
        CapabilityMatrix after = client().capabilities();
        assertTrue(after.all().keySet().stream().noneMatch(id -> id.startsWith("hook.") && !id.equals("hook.engine")),
                String.valueOf(after.all().keySet()));
        assertEquals(Map.of(), value(client().call("hook.health", Map.of(), CALL)).get("hooks"));
    }

    // ------------------------------------------------------------------ item 7: Byteman (BW), orders 166 to 169
    //
    // Reserved for phase6-contracts.md 13.2 item 7, built by package BW as the standalone BytemanDeskIT and moved
    // here at integration: after the hook.remove demonstration above (165, every class restored, no hook left) and
    // before check 15 (170).

    // ------------------------------------------------------------------ checks 16, 17: the re-attach

    /**
     * Item 1, in checks 16 and 17: the re-attached agent was given the launch's options, so the hooks module is
     * loaded again, and the {@code Spy} of the first agent is still on the bootstrap path, so the next install appends
     * nothing (the log's CDS line count, at 205, proves it).
     */
    @Override
    void afterReattach() throws Exception {
        CapabilityMatrix caps = client().capabilities();
        assertTrue(caps.isOk("hook.engine"), String.valueOf(caps.get("hook.engine")));
        Map<String, Object> probe = value(client().call("hook.probe", Map.of("specs", List.of()), CALL));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) probe.get("spy")).get("onBootPath"), String.valueOf(probe));
    }

    // ------------------------------------------------------------------ 205: the target's log

    /**
     * Items 1 and 6: the target's merged log holds exactly one CDS line on Java 25 (the one bootstrap append of the
     * JVM's life, re-attaches included) and none on Java 8, and no line of Byte Buddy, {@code sun.misc.Unsafe} or a
     * stack frame of the agent. JEP 451: check 1b found no warning after the launch; the only dynamic loads since are
     * checks 16 and 17's own re-attaches (an {@code ATTACH} of the bootstrap jar, which a Java 21+ target reports),
     * so every "loaded dynamically" line names {@code skillroy-agent-bootstrap.jar} and none another jar: the engine
     * never loads an agent of its own. On Java 8 there is no such text at all.
     */
    @Test
    @Order(205)
    void theTargetsLogHoldsOneCdsLineAndNothingOfTheEngine() throws Exception {
        List<String> log = Files.readAllLines(target.launched.logFile(), StandardCharsets.UTF_8);
        long cds = log.stream().filter(l -> l.contains(CDS_LINE)).count();
        assertEquals(javaFeature(java) == 8 ? 0L : 1L, cds, "CDS lines on Java " + java);
        if (javaFeature(java) == 8) {
            assertEquals(List.of(), jep451Lines(log));
        } else {
            List<String> foreign = log.stream().filter(l -> l.contains("A Java agent has been loaded dynamically")
                    && !l.endsWith("skillroy-agent-bootstrap.jar)")).toList();
            assertEquals(List.of(), foreign, "a dynamic load other than the checks' re-attaches");
        }
        List<String> engine = log.stream().filter(l -> l.contains("bytebuddy") || l.contains("ByteBuddy")
                || l.contains("sun.misc.Unsafe") || l.matches("\\s*at ai\\.skillroy\\.testjava\\.agent\\..*"))
                .toList();
        assertEquals(List.of(), engine, "nothing of the engine on the host's output");
    }
}
