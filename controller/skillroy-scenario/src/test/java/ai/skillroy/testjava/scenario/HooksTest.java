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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookTarget;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.hooks.HookEntry;
import ai.skillroy.testjava.scenario.hooks.HookInstaller;
import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hooks on the controller side (phase6-contracts.md 5.3, 6.1, 6.3, 6.4, 13.1 item 5): {@link HookInstaller} against
 * the fake agent for every branch of 5.3, step 0b inside a run, the run-scoped base fact root {@code hooks}, the
 * archived {@code capabilities.json} and its record, the session's install at open, and {@link RunOptions}' new
 * component. Phase 8 (phase8-contracts.md 6.9, section 13.1 row 26): the deny sent, the echo required on both paths,
 * {@code denyNotDelivered} on a skip with the agent option read from {@code rule.list}, a deny-only catalogue sent as a
 * zero-spec install, {@code deniedNow} recorded, and the {@code codeSource} upgrade hint.
 */
class HooksTest {

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private final List<Map<String, Object>> installs = new ArrayList<>();
    /** What the fake engine holds: the installed catalogue {id, hash}, or null. */
    private Map<String, Object> installed;
    private List<String> stubs = List.of();
    /** The catalogue deny the fake engine holds (add-only), as its hook.health reports it. */
    private final List<String> heldDeny = new ArrayList<>();
    /** True for a 0.1.0 agent: its hook.health has no top-level deny, and its install ignores catalogue.deny. */
    private boolean oldAgent;

    static HookEntry entry(String id, String className, String fallback) {
        return new HookEntry(id, HookMode.CAPTURE, true, new HookTarget(className, "limit",
                List.of("(Ljava/lang/String;)Ljava/math/BigDecimal;"), null), null, List.of(), false, null,
                List.of(new HookEntry.Fallback.Seam(fallback)), null);
    }

    static HookCatalogue catalogue(String id, int version) {
        return HookCatalogue.builder(id, version).packages(List.of("org.example.toy"))
                .add(entry("toy.applied", "org.example.toy.Desk", "toy.limits"))
                .add(entry("toy.missing", "org.example.toy.Gone", "toy.state"))
                .build();
    }

    @BeforeEach
    void fakeEngine() {
        pack.hooks = catalogue("toy", 1);
        agent.capabilities("toy.input", "toy.state", "toy.loan", "toy.limits");
        agent.capability(HookInstaller.ENGINE, CapabilityState.OK, null);
        agent.on("hook.health", params -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            Map<String, Object> hooks = new LinkedHashMap<>();
            if (installed != null) {
                hooks.put("toy.applied", Map.of("state", "FIRING", "count", 9L, "armed", false));
            }
            answer.put("hooks", hooks);
            answer.put("stubs", stubs);
            answer.put("spy", Map.of("version", 1L));
            if (installed != null) {
                answer.put("catalogue", installed);
            }
            if (!oldAgent) {
                // phase8-contracts.md 6.6: always present on a new agent
                answer.put("deny", Map.of("catalogue", List.copyOf(heldDeny), "from", List.of(), "option", List.of(),
                        "modules", 0L));
            }
            return answer;
        });
        agent.on("hook.install", params -> {
            installs.add(params);
            Map<?, ?> catalogue = (Map<?, ?>) params.get("catalogue");
            hold(catalogue);
            installed = Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash"), "installedAt", 1L);
            agent.capability("hook.toy.applied", CapabilityState.OK, null);
            return Map.of("catalogue", Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash"),
                    "alreadyInstalled", false), "hooks", List.of(
                            Map.of("id", "toy.applied", "state", "OK", "strategy", "advice", "capability",
                                    "hook.toy.applied"),
                            Map.of("id", "toy.missing", "state", "UNAVAILABLE", "reason", "class not loaded",
                                    "capability", "hook.toy.missing")),
                    "touched", List.of(Map.of("class", "org.example.toy.Desk")), "otherAgents", List.of("p.jar"));
        });
        agent.on("hook.events", params -> {
            long since = ((Number) params.get("since")).longValue();
            String topic = "hook." + params.get("id");
            List<Object> events = new ArrayList<>();
            for (Event e : List.copyOf(agent.bus)) {
                if (e.seq() > since && e.topic().equals(topic)) {
                    events.add(e.toJson());
                }
            }
            return Map.of("events", events);
        });
        agent.describe("toy.limits", false, "toy.limits");
        agent.describe("toy.state", false, "toy.state");
    }

    private HookInstaller.Result install(HooksMode mode, boolean replay) throws Exception {
        return HookInstaller.install(pack, agent, agent.capabilities(), mode, replay, Clock.systemUTC());
    }

    // ------------------------------------------------------------------------------------------ the installer

    @Test
    void offAndNoCatalogueSkipAndSendNothingButTheHealthRead() throws Exception {
        assertEquals(Map.of("mode", "off", "skipped", "off"), install(HooksMode.OFF, true).record());
        pack.hooks = HookCatalogue.EMPTY;
        HookInstaller.Result none = install(HooksMode.AUTO, true);
        assertEquals(Map.of("mode", "auto", "skipped", "no catalogue"), none.record());
        assertTrue(none.skipped());
        pack.hooks = HookCatalogue.builder(catalogue("toy", 1)).disable("toy.applied").disable("toy.missing")
                .build();
        assertEquals("no catalogue", install(HooksMode.AUTO, true).record().get("skipped"), "nothing enabled");
        // Fix wave (Phase 6 review, FC): step 0 reads hook.health whenever hook.engine is OK, and sends nothing else.
        assertEquals(3, agent.callsTo("hook.health").size());
        assertEquals(Long.valueOf(HookInstaller.HEALTH_TIMEOUT_MS),
                agent.callsTo("hook.health").get(0).options().timeoutMs());
        assertTrue(agent.callsTo("hook.install").isEmpty());
    }

    /**
     * Fix wave (Phase 6 review, FC, 2026-10-09; finding L1-S2, D129): a stub armed on the target is ERROR /
     * ENVIRONMENT under {@code replay} whatever ends the install, {@code OFF} and a pack without an enabled catalogue
     * entry included, and is recorded in the {@code skipped} record under {@code live}; a failed health read refuses
     * {@code replay} and leaves a live skip alone; without {@code hook.engine} {@code OK} nothing is read.
     */
    @Test
    void anArmedStubIsEnvironmentInReplayEvenWhenTheInstallIsSkipped() throws Exception {
        stubs = List.of("toy.applied");
        for (HooksMode mode : new HooksMode[] {HooksMode.OFF, HooksMode.AUTO}) {
            pack.hooks = mode == HooksMode.OFF ? catalogue("toy", 1) : HookCatalogue.EMPTY;
            HookInstaller.Refused e = assertThrows(HookInstaller.Refused.class, () -> install(mode, true),
                    mode.label());
            assertEquals(FailureKind.ENVIRONMENT, e.kind());
            assertEquals("a stub is armed on the target: toy.applied; `skillroy hooks status`", e.getMessage());
            assertEquals(List.of("toy.applied"), e.detail().get("armed"));
            assertEquals(false, e.record().get("ok"));
            Map<String, Object> live = install(mode, false).record();
            assertEquals(mode == HooksMode.OFF ? "off" : "no catalogue", live.get("skipped"));
            assertEquals(List.of("toy.applied"), live.get("armed"), "recorded in the skipped record");
        }
        assertTrue(agent.callsTo("hook.install").isEmpty());

        agent.error("hook.health", ErrorCodes.INTERNAL_ERROR, "boom");
        HookInstaller.Refused failed = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.OFF, true));
        assertEquals(FailureKind.ENVIRONMENT, failed.kind());
        assertTrue(failed.getMessage().startsWith("hook.health failed: INTERNAL_ERROR"), failed.getMessage());
        assertEquals(Map.of("mode", "off", "skipped", "off"), install(HooksMode.OFF, false).record());

        int reads = agent.callsTo("hook.health").size();
        pack.hooks = catalogue("toy", 1);
        agent.capability(HookInstaller.ENGINE, CapabilityState.UNAVAILABLE, "no Instrumentation in EMBEDDED mode");
        assertEquals(Map.of("mode", "off", "skipped", "off"), install(HooksMode.OFF, true).record());
        assertEquals("hook.engine is UNAVAILABLE: no Instrumentation in EMBEDDED mode",
                install(HooksMode.AUTO, true).record().get("skipped"));
        assertEquals(reads, agent.callsTo("hook.health").size(), "nothing is read without hook.engine OK");
    }

    @Test
    void onWithoutACatalogueIsAPreconditionFailure() {
        pack.hooks = HookCatalogue.EMPTY;
        HookInstaller.Refused e = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.ON, true));
        assertEquals(FailureKind.PRECONDITION_FAILED, e.kind());
        assertEquals("hooks ON, but pack toy has no hook catalogue", e.getMessage());
        assertEquals(false, e.record().get("ok"));
    }

    @Test
    void autoWithoutAnEngineSkipsAndOnIsRefusedWithTheReason() throws Exception {
        agent.capability(HookInstaller.ENGINE, CapabilityState.UNAVAILABLE, "no Instrumentation in EMBEDDED mode");
        HookInstaller.Result auto = install(HooksMode.AUTO, true);
        assertEquals("hook.engine is UNAVAILABLE: no Instrumentation in EMBEDDED mode", auto.record().get("skipped"));
        HookInstaller.Refused on = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.ON, true));
        assertEquals(FailureKind.PRECONDITION_FAILED, on.kind());
        assertEquals("hooks ON, but hook.engine is UNAVAILABLE: no Instrumentation in EMBEDDED mode", on.getMessage());
        assertEquals("hook.engine is absent: the hooks module is not loaded",
                HookInstaller.engineProblem(new CapabilityMatrix(Map.of())));
        assertTrue(agent.callsTo("hook.install").isEmpty());
    }

    @Test
    void anInstallSendsTheEnabledSpecsAndRecordsTheFallbacks() throws Exception {
        HookInstaller.Result result = install(HooksMode.AUTO, true);
        assertFalse(result.skipped());
        assertEquals(1, installs.size());
        Map<String, Object> params = installs.get(0);
        assertEquals(pack.hooks.descriptor(), params.get("catalogue"));
        assertFalse(params.containsKey("replace"));
        assertEquals(2, ((List<?>) params.get("specs")).size());
        assertEquals(Long.valueOf(HookInstaller.INSTALL_TIMEOUT_MS),
                agent.callsTo("hook.install").get(0).options().timeoutMs());
        Map<String, Object> record = result.record();
        assertEquals(List.of("mode", "catalogue", "installed", "unavailable", "armed", "touched", "ms",
                "otherAgents"), List.copyOf(record.keySet()));
        assertEquals(Map.of("id", "toy", "version", 1L, "hash", pack.hooks.hash()), record.get("catalogue"));
        assertEquals(List.of("toy.applied"), record.get("installed"));
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("id", "toy.missing");
        missing.put("state", "UNAVAILABLE");
        missing.put("reason", "class not loaded");
        missing.put("fallback", "seam:toy.state");
        assertEquals(List.of(missing), record.get("unavailable"));
        assertEquals(1L, record.get("touched"));
        assertEquals(List.of("p.jar"), record.get("otherAgents"));
        // A seam whose capability is not OK is no fallback.
        agent.capability("toy.state", CapabilityState.UNAVAILABLE, "down");
        installed = null;
        Map<?, ?> again = (Map<?, ?>) ((List<?>) install(HooksMode.AUTO, true).record().get("unavailable")).get(0);
        assertNull(again.get("fallback"));
    }

    @Test
    void theSameCatalogueIsAlreadyInstalledAndANewerOneReplacesIt() throws Exception {
        install(HooksMode.AUTO, true);
        HookInstaller.Result again = install(HooksMode.AUTO, true);
        assertTrue(again.alreadyInstalled());
        assertEquals(Map.of("mode", "auto", "alreadyInstalled", true, "catalogue", Map.of("id", "toy", "hash",
                pack.hooks.hash()), "armed", List.of()), again.record());
        assertEquals(1, installs.size(), "nothing sent the second time");
        pack.hooks = catalogue("toy", 2);
        HookInstaller.Result newer = install(HooksMode.AUTO, true);
        assertEquals(2, installs.size());
        assertEquals(true, installs.get(1).get("replace"));
        assertEquals(true, newer.record().get("replaced"));
    }

    /**
     * Phase 6 security fix, pass 2, P2-L2-F3: a replace whose implicit remove left a reference to {@code Spy} in a
     * restored class (the answer's {@code replaced.verified} is {@code FAILED}) is ERROR / ENVIRONMENT as the remove's
     * {@code HOOK_VERIFY_FAILED} would be, with the restore lines as the detail; a replace whose remove verified
     * {@code OK} or {@code DEGRADED} records {@code replaced: true} as before. Before the fix every replace was a plain
     * success.
     */
    @Test
    void aReplaceWhoseRemoveLeftAReferenceToSpyIsEnvironment() throws Exception {
        install(HooksMode.AUTO, true);
        pack.hooks = catalogue("toy", 2);
        for (String verified : List.of("OK", "DEGRADED", "FAILED")) {
            List<Object> restored = List.of(Map.of("class", "org.example.toy.Desk", "loader", "system", "verified",
                    verified, "foreignEvents", 0L));
            agent.on("hook.install", params -> {
                installs.add(params);
                Map<?, ?> catalogue = (Map<?, ?>) params.get("catalogue");
                installed = Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash"), "installedAt", 2L);
                return Map.of("catalogue", Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash"),
                        "alreadyInstalled", false), "hooks", List.of(Map.of("id", "toy.applied", "state", "OK",
                        "strategy", "advice", "capability", "hook.toy.applied")), "touched", List.of(),
                        "otherAgents", List.of(), "replaced", Map.of("restored", restored, "verified", verified));
            });
            installed = Map.of("id", "toy", "hash", "sha256:older");
            if (!"FAILED".equals(verified)) {
                assertEquals(true, install(HooksMode.AUTO, true).record().get("replaced"), verified);
                continue;
            }
            HookInstaller.Refused e = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
            assertEquals(FailureKind.ENVIRONMENT, e.kind());
            assertTrue(e.getMessage().startsWith("hook.install failed: HOOK_VERIFY_FAILED: the remove of the installed"
                    + " version (replace: true) left a reference to Spy"), e.getMessage());
            assertEquals(ErrorCodes.HOOK_VERIFY_FAILED, ((Number) ((Map<?, ?>) e.detail().get("error")).get("code"))
                    .intValue());
            assertEquals(restored, e.detail().get("restored"));
            assertEquals(true, e.record().get("replaced"));
            assertEquals(false, e.record().get("ok"));
        }
        assertEquals(true, installs.get(installs.size() - 1).get("replace"));
    }

    @Test
    void anotherCatalogueOnTheTargetIsEnvironment() {
        installed = Map.of("id", "other", "hash", "sha256:00");
        HookInstaller.Refused e = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
        assertEquals(FailureKind.ENVIRONMENT, e.kind());
        assertEquals("catalogue other is installed on this target; `skillroy hooks remove` first", e.getMessage());
        assertTrue(installs.isEmpty());
    }

    @Test
    void anArmedStubIsEnvironmentInReplayAndRecordedInLive() throws Exception {
        stubs = List.of("toy.applied");
        HookInstaller.Refused replay = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
        assertEquals(FailureKind.ENVIRONMENT, replay.kind());
        assertEquals("a stub is armed on the target: toy.applied; `skillroy hooks status`", replay.getMessage());
        assertEquals(List.of("toy.applied"), replay.detail().get("armed"));
        assertEquals(List.of("toy.applied"), install(HooksMode.AUTO, false).record().get("armed"));
    }

    @Test
    void anAgentErrorOfTheInstallIsEnvironmentWithTheError() {
        for (int code : new int[] {ErrorCodes.HOOK_VERIFY_FAILED, ErrorCodes.HOOK_CONFLICT, ErrorCodes.TIMEOUT}) {
            agent.on("hook.install", params -> {
                throw new AgentRpcException("hook.install", new RpcError(code, "refused", Map.of("why", "x")));
            });
            HookInstaller.Refused e = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
            assertEquals(FailureKind.ENVIRONMENT, e.kind());
            assertTrue(e.getMessage().startsWith("hook.install failed: " + ErrorCodes.name(code)), e.getMessage());
            assertEquals(code, ((Number) ((Map<?, ?>) e.detail().get("error")).get("code")).intValue());
        }
    }

    // ------------------------------------------------------------------------------------------ Phase 8 (P8 6.9)

    static final String DENIED = "org.example.toy.Inbox";

    static HookCatalogue denying(HookCatalogue base, String... patterns) {
        return HookCatalogue.builder(base).deny(List.of(patterns)).build();
    }

    /** A new agent holds the deny it was sent (add-only); a 0.1.0 agent ignores it. */
    private void hold(Map<?, ?> catalogue) {
        if (!oldAgent && catalogue.get("deny") instanceof List<?> deny) {
            for (Object pattern : deny) {
                if (!heldDeny.contains(String.valueOf(pattern))) {
                    heldDeny.add(String.valueOf(pattern));
                }
            }
            heldDeny.sort(null);
        }
    }

    /**
     * The fake engine echoes the deny it was sent (a Phase 8 agent), or nothing at all (a 0.1.0 agent, whose
     * {@code hook.health} has no top-level {@code deny} either).
     */
    private void echoingEngine(boolean echo) {
        oldAgent = !echo;
        agent.on("hook.install", params -> {
            installs.add(params);
            Map<?, ?> catalogue = (Map<?, ?>) params.get("catalogue");
            hold(catalogue);
            List<?> specs = (List<?>) params.get("specs");
            if (!specs.isEmpty()) {
                installed = Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash"), "installedAt", 1L);
            }
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("id", catalogue.get("id"));
            cat.put("hash", catalogue.get("hash"));
            cat.put("alreadyInstalled", false);
            if (echo) {
                cat.put("deny", catalogue.get("deny") == null ? List.of() : catalogue.get("deny"));
            }
            List<Object> hooks = new ArrayList<>();
            for (Object spec : specs) {
                hooks.add(Map.of("id", ((Map<?, ?>) spec).get("id"), "state", "OK", "strategy", "advice",
                        "capability", "hook." + ((Map<?, ?>) spec).get("id")));
            }
            return Map.of("catalogue", cat, "hooks", hooks, "touched", List.of(), "otherAgents", List.of());
        });
    }

    @Test
    void theDenyIsSentAndItsEchoRecorded() throws Exception {
        pack.hooks = denying(catalogue("toy", 1), DENIED);
        echoingEngine(true);
        HookInstaller.Result result = install(HooksMode.AUTO, true);
        assertEquals(List.of(DENIED), ((Map<?, ?>) installs.get(0).get("catalogue")).get("deny"),
                "catalogue.deny sent");
        assertEquals(List.of(DENIED), result.record().get("deny"));
        assertEquals(List.of("mode", "catalogue", "installed", "unavailable", "armed", "touched", "ms",
                "otherAgents", "deny"), List.copyOf(result.record().keySet()));
        pack.hooks = catalogue("toy", 1);
        installed = null;
        assertFalse(install(HooksMode.AUTO, true).record().containsKey("deny"), "no deny: no key");
    }

    @Test
    void aMissingEchoIsEnvironmentOnTheInstallPathAndOnTheAlreadyInstalledPath() throws Exception {
        pack.hooks = denying(catalogue("toy", 1), DENIED, "org.example.toy.Clock");
        echoingEngine(false);
        HookInstaller.Refused old = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
        assertEquals(FailureKind.ENVIRONMENT, old.kind());
        assertEquals("the agent does not honour the catalogue's deny list (" + DENIED + ", org.example.toy.Clock);"
                + " upgrade the agent", old.getMessage());
        assertEquals(List.of(DENIED, "org.example.toy.Clock"), old.detail().get("missing"));
        assertNull(old.detail().get("answered"));
        assertEquals(false, old.record().get("ok"));
        // a partial echo is a refusal too (a new agent: its hook.health carries the deny, so the install is sent and
        // its answer checked)
        oldAgent = false;
        agent.on("hook.install", params -> {
            installs.add(params);
            return Map.of("catalogue", Map.of("id", "toy", "hash", "h", "deny", List.of(DENIED)), "hooks", List.of(),
                    "touched", List.of(), "otherAgents", List.of());
        });
        installed = null;
        HookInstaller.Refused partial = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
        assertEquals(List.of("org.example.toy.Clock"), partial.detail().get("missing"));
        assertEquals(List.of(DENIED), partial.detail().get("answered"));
        // the alreadyInstalled path: step 0's hook.health must carry deny.catalogue
        oldAgent = true;
        installed = Map.of("id", "toy", "hash", pack.hooks.hash(), "installedAt", 1L);
        HookInstaller.Refused already = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO,
                true));
        assertEquals(FailureKind.ENVIRONMENT, already.kind());
        assertTrue(already.getMessage().startsWith("the agent does not honour the catalogue's deny list"),
                already.getMessage());
        assertEquals(true, already.record().get("alreadyInstalled"));
        agent.on("hook.health", params -> Map.of("hooks", Map.of(), "stubs", List.of(), "catalogue", installed,
                "deny", Map.of("catalogue", List.of(DENIED, "org.example.toy.Clock", "x.Y"), "from", List.of("toy"),
                        "option", List.of(), "modules", 0L)));
        HookInstaller.Result ok = install(HooksMode.AUTO, true);
        assertTrue(ok.alreadyInstalled());
        assertEquals(List.of(DENIED, "org.example.toy.Clock"), ok.record().get("deny"));
    }

    /**
     * Fix wave of the Phase 8 review (L2-F4): an agent whose step-0 {@code hook.health} has no top-level {@code deny}
     * (a 0.1.0 agent, which ignores {@code catalogue.deny}) is refused before {@code hook.install}, so it never
     * retransforms the classes the pack denies; the same text, detail and record as the answer's echo check, on a
     * first install and on a replace. A catalogue without a deny still installs on such an agent. Before the fix the
     * install was sent and the refusal came after it.
     */
    @Test
    void anAgentWhoseHealthHasNoDenyIsRefusedBeforeTheInstall() throws Exception {
        oldAgent = true;
        pack.hooks = denying(catalogue("toy", 1), DENIED);
        HookInstaller.Refused first = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
        assertEquals(FailureKind.ENVIRONMENT, first.kind());
        assertEquals("the agent does not honour the catalogue's deny list (" + DENIED + "); upgrade the agent",
                first.getMessage());
        assertEquals(List.of(DENIED), first.detail().get("missing"));
        assertTrue(first.detail().containsKey("answered") && first.detail().get("answered") == null,
                String.valueOf(first.detail()));
        assertEquals(false, first.record().get("ok"));
        assertEquals(Map.of("id", "toy", "version", 1L, "hash", pack.hooks.hash()), first.record().get("catalogue"));
        assertEquals(List.of(DENIED), first.record().get("deny"));
        assertTrue(agent.callsTo("hook.install").isEmpty(), "nothing sent, nothing retransformed");
        assertTrue(installs.isEmpty());

        pack.hooks = catalogue("toy", 1);
        assertFalse(install(HooksMode.AUTO, true).skipped(), "no deny: the old agent installs as before");
        assertEquals(1, installs.size());
        pack.hooks = denying(catalogue("toy", 2), DENIED);
        HookInstaller.Refused replace = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO,
                true));
        assertEquals(List.of(DENIED), replace.detail().get("missing"));
        assertEquals(1, installs.size(), "no replace sent either");
    }

    @Test
    void aSkipOfACatalogueWithADenyRecordsItAsNotDelivered() throws Exception {
        pack.hooks = denying(catalogue("toy", 1), DENIED);
        agent.capability(HookInstaller.ENGINE, CapabilityState.UNAVAILABLE, "no Instrumentation in EMBEDDED mode");
        Map<String, Object> noRules = install(HooksMode.AUTO, true).record();
        assertEquals(Map.of("patterns", List.of(DENIED), "agentOption", "unknown"), noRules.get("denyNotDelivered"));
        assertTrue(agent.callsTo("rule.list").isEmpty(), "no rule.* capability: no rule.list call");
        agent.capability("rule.engine", CapabilityState.OK, "in-process");
        agent.on("rule.list", params -> Map.of("rules", List.of(), "loaded", false, "deny", Map.of("option",
                List.of(DENIED), "modules", 0L)));
        Map<String, Object> withRules = install(HooksMode.AUTO, true).record();
        assertEquals(Map.of("patterns", List.of(DENIED), "agentOption", List.of(DENIED)),
                withRules.get("denyNotDelivered"));
        assertEquals(1, agent.callsTo("rule.list").size());
        agent.on("rule.list", params -> Map.of("rules", List.of(), "loaded", false));
        assertEquals("unknown", ((Map<?, ?>) install(HooksMode.AUTO, true).record().get("denyNotDelivered"))
                .get("agentOption"), "a 0.1.0 agent answers no deny key");
        agent.error("rule.list", ErrorCodes.CAPABILITY_UNAVAILABLE, "no byteman.jar");
        assertEquals("unknown", ((Map<?, ?>) install(HooksMode.OFF, true).record().get("denyNotDelivered"))
                .get("agentOption"), "off is a skip too; a failed read is unknown");
        HookInstaller.Refused on = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.ON, true));
        assertEquals(FailureKind.PRECONDITION_FAILED, on.kind(), "ON without an engine as today");
        pack.hooks = catalogue("toy", 1);
        assertFalse(install(HooksMode.AUTO, true).record().containsKey("denyNotDelivered"));
        assertTrue(agent.callsTo("hook.install").isEmpty());
    }

    @Test
    void aDenyOnlyCatalogueIsSentAsAZeroSpecInstall() throws Exception {
        echoingEngine(true);
        pack.hooks = denying(HookCatalogue.builder(catalogue("toy", 1)).disable("toy.applied").disable("toy.missing")
                .build(), DENIED);
        for (HooksMode mode : new HooksMode[] {HooksMode.AUTO, HooksMode.ON}) {
            HookInstaller.Result result = install(mode, true);
            assertFalse(result.skipped(), mode.label());
            Map<String, Object> params = installs.get(installs.size() - 1);
            assertEquals(List.of(), params.get("specs"), "a zero-spec install");
            assertEquals(List.of(DENIED), ((Map<?, ?>) params.get("catalogue")).get("deny"));
            assertEquals(List.of(), result.record().get("installed"));
            assertEquals(List.of(DENIED), result.record().get("deny"));
        }
        assertEquals(2, installs.size(), "sent each time: nothing is recorded as installed");
    }

    @Test
    void theHooksStepZeroMarksDeniedNowAreRecorded() throws Exception {
        install(HooksMode.AUTO, true);
        agent.on("hook.health", params -> Map.of("hooks", Map.of("toy.applied", Map.of("state", "FIRING", "count",
                3L, "deniedNow", true), "toy.other", Map.of("state", "INSTALLED", "count", 0L)), "stubs", List.of(),
                "catalogue", installed));
        HookInstaller.Result again = install(HooksMode.AUTO, true);
        assertTrue(again.alreadyInstalled());
        assertEquals(List.of("toy.applied"), again.record().get("deniedNow"));
    }

    @Test
    void anOldAgentRefusingAQualifiedSpecGetsTheUpgradeHint() throws Exception {
        HookEntry qualified = entry("toy.applied", "org.example.toy.Desk", "toy.limits");
        pack.hooks = HookCatalogue.builder("toy", 1).add(qualified.withTarget(qualified.target()
                .withCodeSource("toy-desk\\.jar"))).build();
        agent.on("hook.install", params -> {
            throw new AgentRpcException("hook.install", new RpcError(ErrorCodes.INVALID_PARAMS,
                    "HookTarget: unknown field \"codeSource\"", Map.of("param", "specs[0]")));
        });
        HookInstaller.Refused e = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
        assertEquals(FailureKind.ENVIRONMENT, e.kind());
        assertEquals("hook.install failed: INVALID_PARAMS: HookTarget: unknown field \"codeSource\"; the catalogue"
                + " uses target.codeSource, which this agent does not know: upgrade the agent", e.getMessage());
        assertEquals(e.getMessage(), e.record().get("error"));
        pack.hooks = catalogue("toy", 1);
        HookInstaller.Refused plain = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
        assertFalse(plain.getMessage().contains("upgrade"), "no qualifier, no hint: " + plain.getMessage());
    }

    // ------------------------------------------------------------------------------------------ step 0b in a run

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private RunOptions options() {
        return RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withBind(TestCredentials.bind())
                .withPollInterval(Duration.ofMillis(100)).withStepTimeout(Duration.ofSeconds(2));
    }

    private void toyInputFiresTheHook() {
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of());
            agent.publish("hook.toy.applied", Map.of("hook", "toy.applied", "args", Map.of("0", "100"), "returned",
                    "20.00"));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of("accepted", true);
        });
    }

    static final String GUARDED = """
            steps:
              - enter: { value: "100" }
              - expect:
                  "hooks[id=toy.applied].count": { gte: 1, capability: hook.toy.applied }
                  "hooks[id=toy.applied].last.returned": { equals: "20.00", capability: hook.toy.applied }
                  "hooks[id=toy.applied].last.args.0": { equals: "100", capability: hook.toy.applied }
            """;

    @Test
    void stepZeroBInstallsBeforeRequiresAndTheGuardedExpectationsPass(@TempDir Path dir) throws Exception {
        toyInputFiresTheHook();
        // A fire before the run's first step (a verifier's call, an earlier run's) never counts.
        agent.publish("hook.toy.applied", Map.of("returned", "999.00"));
        RunResult result = new PlanExecutor(pack, agent, options().withOutputDir(dir))
                .run(plan("requires: [toy.input, hook.toy.applied]\n" + GUARDED));
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of(), result.skippedExpectations());
        List<Map<String, Object>> log = runLog(dir);
        List<String> types = log.stream().map(r -> (String) r.get("type")).toList();
        assertEquals(List.of("run-start", "hooks", "capabilities", "requires"), types.subList(0, 4));
        assertEquals(List.of("toy.applied"), log.get(1).get("installed"));
        Map<String, Object> capabilities = Json.parseObject(Files.readString(dir.resolve("capabilities.json")));
        CapabilityMatrix archived = CapabilityMatrix.fromJson(capabilities);
        assertTrue(archived.isOk("hook.toy.applied"), "the matrix after the install is archived");
        assertEquals(archived.hash(), log.get(2).get("hash"));
        assertEquals((long) archived.all().size(), log.get(2).get("ids"));
        assertEquals("capabilities.json", log.get(2).get("file"));
    }

    @Test
    void withoutTheEngineTheGuardedExpectationsAreSkippedAndTheRunIsDegraded(@TempDir Path dir) throws Exception {
        toyInputFiresTheHook();
        agent.capability(HookInstaller.ENGINE, CapabilityState.UNAVAILABLE, "no Instrumentation in EMBEDDED mode");
        agent.on("hook.health", params -> {
            throw new AgentRpcException("hook.health", new RpcError(ErrorCodes.CAPABILITY_UNAVAILABLE, "no", null));
        });
        RunResult result = new PlanExecutor(pack, agent, options().withOutputDir(dir)).run(plan(GUARDED));
        assertEquals(Status.DEGRADED, result.status(), result.message());
        assertEquals(3, result.skippedExpectations().size());
        assertTrue(result.skippedExpectations().stream().allMatch(s -> s.contains("hook.toy.applied")),
                String.valueOf(result.skippedExpectations()));
        assertEquals("hook.engine is UNAVAILABLE: no Instrumentation in EMBEDDED mode", runLog(dir).get(1)
                .get("skipped"));
        assertTrue(agent.callsTo("hook.install").isEmpty());
    }

    @Test
    void aRefusedInstallEndsTheRunBeforeTheBind(@TempDir Path dir) throws Exception {
        stubs = List.of("toy.applied");
        RunResult replay = new PlanExecutor(pack, agent, options().withOutputDir(dir)).run(plan(GUARDED));
        assertEquals(Status.ERROR, replay.status());
        assertEquals(FailureKind.ENVIRONMENT, replay.failureKind());
        assertTrue(replay.message().startsWith("a stub is armed on the target"), replay.message());
        assertEquals(false, runLog(dir).get(1).get("ok"));
        assertTrue(agent.callsTo("toy.input").isEmpty(), "no step ran");
        stubs = List.of();
        pack.hooks = HookCatalogue.EMPTY;
        RunResult on = new PlanExecutor(pack, agent, options().withHooks(HooksMode.ON)).run(plan(GUARDED));
        assertEquals(Status.ERROR, on.status());
        assertEquals(FailureKind.PRECONDITION_FAILED, on.failureKind());
    }

    @Test
    void anArmedStubIsRecordedInLive(@TempDir Path dir) throws Exception {
        toyInputFiresTheHook();
        stubs = List.of("toy.applied");
        RunResult live = new PlanExecutor(pack, agent, options().withOutputDir(dir)
                .withBind(TestCredentials.bind().withMode(BindMode.LIVE))).run(plan(GUARDED));
        assertEquals(Status.PASS, live.status(), live.message());
        assertEquals(List.of("toy.applied"), runLog(dir).get(1).get("armed"));
    }

    /** Fix wave (Phase 6 review, FC; finding L1-S2): {@code --hooks off} does not let a replay run past a stub. */
    @Test
    void offStillEndsAReplayRunOnAnArmedStubAndRecordsItInLive(@TempDir Path dir) throws Exception {
        toyInputFiresTheHook();
        stubs = List.of("toy.applied");
        RunResult replay = new PlanExecutor(pack, agent, options().withOutputDir(dir.resolve("replay"))
                .withHooks(HooksMode.OFF)).run(plan(GUARDED));
        assertEquals(Status.ERROR, replay.status());
        assertEquals(FailureKind.ENVIRONMENT, replay.failureKind());
        assertTrue(replay.message().startsWith("a stub is armed on the target"), replay.message());
        assertTrue(agent.callsTo("toy.input").isEmpty(), "no step ran");
        RunResult live = new PlanExecutor(pack, agent, options().withOutputDir(dir.resolve("live"))
                .withHooks(HooksMode.OFF).withBind(TestCredentials.bind().withMode(BindMode.LIVE))).run(plan(GUARDED));
        assertEquals(Status.DEGRADED, live.status(), live.message());
        Map<String, Object> record = PlanExecutorTest.stripTime(runLog(dir.resolve("live")).get(1));
        assertEquals(Map.of("mode", "off", "skipped", "off", "armed", List.of("toy.applied")), record);
    }

    @Test
    void offInstallsNothingAndTheFactRootIsEmptyWithoutTheModule(@TempDir Path dir) throws Exception {
        toyInputFiresTheHook();
        RunResult result = new PlanExecutor(pack, agent, options().withOutputDir(dir).withHooks(HooksMode.OFF))
                .run(plan(GUARDED));
        assertEquals(Status.DEGRADED, result.status(), result.message());
        assertEquals(3, result.skippedExpectations().size(), "hook.toy.applied is absent: nothing was installed");
        assertEquals(Map.of("mode", "off", "skipped", "off"), PlanExecutorTest.stripTime(runLog(dir).get(1)));
        assertTrue(agent.callsTo("hook.install").isEmpty());
    }

    @Test
    void theFactRootCountsOnlyTheRunsStepsAndIsEmptyWhenNotReady() throws Exception {
        installed = Map.of("id", "toy", "hash", "x");
        agent.publish("hook.toy.applied", Map.of("returned", "1.00"));
        long since = agent.lastSeq();
        agent.publish("hook.toy.applied", Map.of("returned", "2.00"));
        agent.publish("hook.toy.applied", Map.of("returned", "3.00"));
        Object root = BaseFacts.hooks(() -> since).fetch(agent);
        assertTrue(root instanceof List<?> list && list.size() == 1, String.valueOf(root));
        Map<?, ?> fact = (Map<?, ?>) ((List<?>) root).get(0);
        assertEquals("toy.applied", fact.get("id"));
        assertEquals("FIRING", fact.get("state"));
        assertEquals(2L, fact.get("count"), "the cumulative 9 of hook.health is not this run's");
        assertEquals(Map.of("returned", "3.00"), fact.get("last"));
        assertEquals(false, fact.get("armed"));
        assertTrue(fact.get("lastFiredMs") instanceof Long);
        assertEquals(1000L, ((Number) agent.callsTo("hook.events").get(0).params().get("limit")).longValue());
        for (int code : new int[] {ErrorCodes.METHOD_NOT_FOUND, ErrorCodes.CAPABILITY_UNAVAILABLE,
                ErrorCodes.NOT_READY}) {
            agent.error("hook.health", code, "none");
            assertEquals(List.of(), BaseFacts.hooks(() -> 0L).fetch(agent), ErrorCodes.name(code));
        }
        agent.error("hook.health", ErrorCodes.INTERNAL_ERROR, "boom");
        assertThrows(AgentRpcException.class, () -> BaseFacts.hooks(() -> 0L).fetch(agent));
    }

    /** {@code hook.events} as the agent answers it (phase6-contracts.md 4.2): the oldest first, at most {@code limit}. */
    private void eventsHonourTheLimit() {
        agent.on("hook.events", params -> {
            long since = ((Number) params.get("since")).longValue();
            long limit = ((Number) params.get("limit")).longValue();
            String topic = "hook." + params.get("id");
            List<Object> events = new ArrayList<>();
            for (Event e : List.copyOf(agent.bus)) {
                if (events.size() == limit) {
                    break;
                }
                if (e.seq() > since && e.topic().equals(topic)) {
                    events.add(e.toJson());
                }
            }
            return Map.of("events", events);
        });
    }

    /**
     * Fix wave (Phase 6 review, FC, 2026-10-09; finding L1-S3): more fires since the mark than one page holds are all
     * counted, and {@code last} is the newest one's payload, not the thousandth.
     */
    @Test
    void theFactRootPagesToTheNewestFireAndCountsEveryOne() throws Exception {
        eventsHonourTheLimit();
        installed = Map.of("id", "toy", "hash", "x");
        agent.publish("hook.toy.applied", Map.of("n", 0L));
        long since = agent.lastSeq();
        for (long n = 1; n <= 1500; n++) {
            agent.publish("hook.toy.applied", Map.of("n", n));
        }
        Map<?, ?> fact = (Map<?, ?>) ((List<?>) BaseFacts.hooks(() -> since).fetch(agent)).get(0);
        assertEquals(1500L, fact.get("count"));
        assertEquals(Map.of("n", 1500L), fact.get("last"), "the newest fire");
        assertFalse(fact.containsKey("truncated"));
        assertEquals(2, agent.callsTo("hook.events").size(), "a full page, then a short one");
        assertEquals(since, ((Number) agent.callsTo("hook.events").get(0).params().get("since")).longValue());
        assertEquals(since + 1000, ((Number) agent.callsTo("hook.events").get(1).params().get("since")).longValue(),
                "the second page starts after the first page's last seq");
    }

    /**
     * Fix wave (Phase 6 review, FC): past ten full pages the count is a lower bound marked {@code truncated}, and
     * {@code last} comes from the bus's last 1000 events, or is null when the hook did not fire among them.
     */
    @Test
    void pastTenPagesTheCountIsALowerBoundAndLastIsTheNewestOrNull() throws Exception {
        eventsHonourTheLimit();
        installed = Map.of("id", "toy", "hash", "x");
        long since = agent.lastSeq();
        for (long n = 1; n <= 10_500; n++) {
            agent.publish("hook.toy.applied", Map.of("n", n));
        }
        Map<?, ?> fact = (Map<?, ?>) ((List<?>) BaseFacts.hooks(() -> since).fetch(agent)).get(0);
        assertEquals(10_000L, fact.get("count"), "ten pages");
        assertEquals(true, fact.get("truncated"));
        assertEquals(Map.of("n", 10_500L), fact.get("last"), "the newest, from the tail read");
        assertTrue(fact.get("lastFiredMs") instanceof Long);
        assertEquals(11, agent.callsTo("hook.events").size(), "ten pages and the tail read");

        for (int i = 0; i < 1000; i++) {
            agent.publish("other.topic", Map.of());
        }
        Map<?, ?> stale = (Map<?, ?>) ((List<?>) BaseFacts.hooks(() -> since).fetch(agent)).get(0);
        assertEquals(10_000L, stale.get("count"));
        assertEquals(true, stale.get("truncated"));
        assertNull(stale.get("last"), "no older payload in place of the newest");
        assertNull(stale.get("lastFiredMs"));
    }

    // ------------------------------------------------------------------------------------------ the session

    @Test
    void aSessionInstallsAtOpenAndArchivesTheMatrix(@TempDir Path dir) throws Exception {
        try (ExecutorSession session = ExecutorSession.open(pack, agent, SessionOptions.defaults(dir)
                .withClock(clock).withBind(TestCredentials.bind()))) {
            assertEquals(List.of("toy.applied"), session.hooks().get("installed"));
            assertTrue(CapabilityMatrix.fromJson(Json.parseObject(Files.readString(dir.resolve("capabilities.json"))))
                    .isOk("hook.toy.applied"));
        }
        pack.hooks = HookCatalogue.EMPTY;
        SessionOpenException e = assertThrows(SessionOpenException.class, () -> ExecutorSession.open(pack, agent,
                SessionOptions.defaults(null).withHooks(HooksMode.ON).withBind(TestCredentials.bind())));
        assertEquals(FailureKind.PRECONDITION_FAILED, e.kind());
    }

    // ------------------------------------------------------------------------------------------ RunOptions

    @Test
    void runOptionsCarryTheSwitchAndTheOldConstructorsFillTheDefault() {
        assertEquals(HooksMode.AUTO, RunOptions.defaults().hooks());
        assertEquals(HooksMode.OFF, RunOptions.defaults().withHooks(HooksMode.OFF).hooks());
        assertEquals(HooksMode.AUTO, RunOptions.defaults().withHooks(null).hooks());
        RunOptions phase5 = new RunOptions(null, null, null, null, null, null, null, true, BindOptions.defaults(),
                true);
        assertEquals(HooksMode.AUTO, phase5.hooks());
        assertTrue(phase5.expectationsOnMaskedValues());
        assertEquals(HooksMode.AUTO, new RunOptions(null, null, null, null, null, null, null, true).hooks());
        assertEquals(HooksMode.ON, RunOptions.defaults().withHooks(HooksMode.ON).withStepTimeout(Duration.ofSeconds(3))
                .withBind(BindOptions.defaults()).withCaptureOnFailure(false).hooks(), "every copy keeps it");
        assertEquals(HooksMode.AUTO, SessionOptions.defaults(null).hooks());
        assertEquals(HooksMode.OFF, SessionOptions.defaults(null).withHooks(HooksMode.OFF).withReadOnly(true)
                .hooks());
    }

    static List<Map<String, Object>> runLog(Path dir) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            records.add(Json.parseObject(line));
        }
        return records;
    }
}
