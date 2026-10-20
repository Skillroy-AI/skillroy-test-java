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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.spi.hook.HookEngine;
import ai.skillroy.testjava.fixture.hooks.Capturing;
import ai.skillroy.testjava.fixture.hooks.ChildFirstLoader;
import ai.skillroy.testjava.fixture.hooks.DepositRule;
import ai.skillroy.testjava.fixture.hooks.Returns;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.protocol.hook.HookStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hook.install} (phase6-contracts.md sections 3.2 and 4.2): a class defined by two loaders, idempotency by
 * catalogue hash, {@code HOOK_CONFLICT} and {@code replace}, the ledger's bound, a method hooked twice, the
 * {@code hooks.installed} event, and the SPI through {@code AgentContext.hooks()}. Phase 8 (phase8-contracts.md
 * sections 6.4 and 6.6, section 13.1 row 24): {@code touched[].codeSource}, {@code hook.health.classes}, the
 * catalogue's deny validated, merged before the conflict check, held add-only and echoed, and a hook installed before
 * its class was denied flagged, not removed.
 */
class InstallTest {

    private Harness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutALoaderFilterEveryLoaderThatDefinesTheClassIsHookedAndRestored() throws Exception {
        // A class no other test of this JVM loads twice: a copy defined by a child-first loader stays loaded.
        Capturing own = new Capturing();
        ChildFirstLoader loader = new ChildFirstLoader(new URL[] {Harness.testClasses()},
                InstallTest.class.getClassLoader(), "ai.skillroy.testjava.fixture.hooks.");
        Class<?> copy = loader.loadClass(Capturing.class.getName());
        Object other = copy.newInstance();
        Method label = copy.getMethod("label");
        harness = new Harness();
        Map<String, Object> result = harness.install("toys", Harness.spec("label.read", HookMode.OBSERVE,
                Capturing.class.getName(), "label", "()Ljava/lang/String;", null, null, null, false));
        List<Object> touched = (List<Object>) result.get("touched");
        String copyId = Integer.toHexString(System.identityHashCode(loader));
        List<Object> loaders = new java.util.ArrayList<Object>();
        for (Object line : touched) {
            loaders.add(((Map<String, Object>) line).get("loader"));
        }
        assertTrue(loaders.contains("system") && loaders.contains(copyId),
                "one ledger entry per loader that defines the name: " + touched);
        int before = Harness.events(harness.ctx, "hook.label.read").size();
        own.label();
        label.invoke(other);
        assertEquals(before + 2, Harness.events(harness.ctx, "hook.label.read").size(), "both copies fire");
        Map<String, Object> removed = harness.ctx.map("hook.remove", map("ids", Arrays.asList("label.read")));
        List<Object> restored = (List<Object>) removed.get("restored");
        assertEquals(touched.size(), restored.size());
        for (Object line : restored) {
            assertEquals("OK", ((Map<String, Object>) line).get("verified"), String.valueOf(line));
        }
        loader.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSameCatalogueIsAlreadyInstalledAnotherConflictsAndReplaceRemovesFirst() throws Exception {
        new DepositRule();
        harness = new Harness();
        HookSpec a = spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor");
        HookSpec b = spec("deposit.waived", HookMode.OBSERVE, DepositRule.class, "waives");
        Map<String, Object> catalogue = map("id", "desk", "version", 1, "hash", "sha256:aa", "application", "toy");
        Map<String, Object> first = harness.ctx.map("hook.install", map("specs", Harness.json(a, b),
                "catalogue", catalogue));
        assertEquals(Boolean.FALSE, ((Map<String, Object>) first.get("catalogue")).get("alreadyInstalled"));
        Event installed = harness.ctx.events.last(Topics.HOOKS_INSTALLED);
        assertEquals(Arrays.asList("deposit.computed", "deposit.waived"), installed.payload().get("hooks"));
        assertEquals(1L, ((Number) installed.payload().get("touched")).longValue(), "one class for two hooks");
        long retransformsBefore = harness.engine().ledger.entries().get(0).own.get();

        Map<String, Object> again = harness.ctx.map("hook.install", map("specs", Harness.json(b, a),
                "catalogue", catalogue));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) again.get("catalogue")).get("alreadyInstalled"));
        assertTrue(((List<Object>) again.get("touched")).isEmpty());
        assertEquals(2, ((List<Object>) again.get("hooks")).size());
        assertEquals(retransformsBefore, harness.engine().ledger.entries().get(0).own.get(), "nothing retransformed");

        RpcException other = harness.ctx.error("hook.install", map("specs", Harness.json(a), "catalogue",
                map("id", "other", "hash", "sha256:bb")));
        assertEquals(ErrorCodes.HOOK_CONFLICT, other.error().code());
        RpcException sameHash = harness.ctx.error("hook.install", map("specs", Harness.json(a), "catalogue",
                catalogue));
        assertEquals(ErrorCodes.HOOK_CONFLICT, sameHash.error().code());
        assertEquals("spec set differs under the same hash", sameHash.error().data().get("reason"));

        Map<String, Object> replaced = harness.ctx.map("hook.install", map("specs", Harness.json(a), "catalogue",
                map("id", "other", "hash", "sha256:bb"), "replace", true));
        assertEquals("other", ((Map<String, Object>) replaced.get("catalogue")).get("id"));
        assertEquals(Collections.singleton("deposit.computed"), harness.engine().hooks.keySet());
        assertTrue(harness.ctx.capabilities.get("hook.deposit.waived") == null, "the replaced hook is withdrawn");
        Map<String, Object> health = harness.ctx.map("hook.health", map());
        assertEquals("other", ((Map<String, Object>) health.get("catalogue")).get("id"));
        harness.ctx.call("hook.remove", map());
        assertTrue(harness.ctx.map("hook.health", map()).get("catalogue") == null, "no catalogue once empty");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLedgerBoundAndAMethodHookedTwiceAreRefusedPerSpec() throws Exception {
        new DepositRule();
        new Returns();
        FakeHookContext ctx = new FakeHookContext();
        ctx.options.put(HookOptions.MAX_CLASSES, "1");
        harness = new Harness(ctx);
        Map<String, Object> result = harness.install("toys",
                spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                spec("deposit.again", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                spec("returns.count", HookMode.OBSERVE, Returns.class, "count"));
        List<Object> hooks = (List<Object>) result.get("hooks");
        assertEquals("OK", ((Map<String, Object>) hooks.get(0)).get("state"));
        Map<String, Object> twice = (Map<String, Object>) hooks.get(1);
        assertEquals("UNAVAILABLE", twice.get("state"));
        assertTrue(String.valueOf(twice.get("reason")).startsWith("method already hooked: "), String.valueOf(twice));
        assertEquals(null, twice.get("capability"), "a refused hook is not declared");
        Map<String, Object> full = (Map<String, Object>) hooks.get(2);
        assertEquals("ledger full", full.get("reason"));
        assertTrue(harness.ctx.capabilities.get("hook.returns.count") == null);
        harness.ctx.call("hook.remove", map());
    }

    /** Section 3.5: hook.remove restores the classes of one removal in the batches of section 3.2. */
    @Test
    @SuppressWarnings("unchecked")
    void aRemovalRestoresItsClassesInOneBatch() throws Exception {
        new DepositRule();
        new Returns();
        harness = new Harness();
        harness.install("toys", spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                spec("returns.count", HookMode.OBSERVE, Returns.class, "count"));
        Map<String, Object> removed = harness.ctx.map("hook.remove", map());
        assertEquals(2, ((List<Object>) removed.get("restored")).size());
        int batches = 0;
        for (String line : harness.ctx.log.all()) {
            if (line.contains("hooks: restore retransformed [")) {
                batches++;
                assertTrue(line.contains(DepositRule.class.getName()) && line.contains(Returns.class.getName()),
                        "both classes in one retransformation: " + line);
            }
        }
        assertEquals(1, batches, "one safepoint for the removal");
    }

    @Test
    void aCatalogueThatFitsNothingInstallsNothing() throws Exception {
        harness = new Harness();
        String name = "ai.skillroy.testjava.fixture.hooks.Refusals$NeverLoaded";
        Map<String, Object> result = harness.ctx.map("hook.install", map("specs", Harness.json(Harness.spec("never",
                HookMode.OBSERVE, name, "x", "()I", null, null, null, false)), "catalogue",
                map("id", "none", "hash", "sha256:00")));
        assertEquals(Boolean.FALSE, Harness.slotHeld());
        assertTrue(harness.engine().ledger.isEmpty());
        assertEquals(null, ((Map<?, ?>) result.get("catalogue")).get("installedAt"), "no catalogue recorded");
        assertTrue(harness.ctx.events.topic(Topics.HOOKS_INSTALLED).size() <= 1);
    }

    /**
     * phase6-contracts.md 4.6, as built in Phase 7 (FX, D449): {@code hook.install} answers after every declared
     * {@code hook.<id>} holds its health state, also while the core has not yet run the probe it posted to a worker
     * for the late declaration; the read that follows the answer (a run's step 0b) never sees "not probed yet". The
     * worker's probe, when it runs, answers the same. Fails without the first answer set at declaration.
     */
    @Test
    void theAnswerComesAfterEveryDeclaredHookHoldsItsHealthState() throws Exception {
        harness = new Harness();
        harness.ctx.capabilities.deferLateProbes = true;
        Map<String, Object> result = harness.install("toys", spec("deposit.computed", HookMode.OBSERVE,
                DepositRule.class, "depositFor"), spec("deposit.waived", HookMode.OBSERVE, DepositRule.class,
                "waives"));
        for (String id : Arrays.asList("hook.deposit.computed", "hook.deposit.waived")) {
            Capability declared = harness.ctx.capabilities.get(id);
            assertEquals(new Capability(id, CapabilityState.OK, null, "advice"), declared, result + " / " + declared);
            assertFalse(harness.ctx.capabilities.isPending(id), id);
        }
        harness.ctx.capabilities.runDeferred(harness.ctx);
        for (String id : Arrays.asList("hook.deposit.computed", "hook.deposit.waived")) {
            assertEquals(new Capability(id, CapabilityState.OK, null, "advice"), harness.ctx.capabilities.get(id),
                    "the worker's probe answers the same");
        }
    }

    @Test
    void theSpiIsFoundThroughTheContextAndInstallsAsThePrimitiveDoes() throws Exception {
        DepositRule rule = new DepositRule();
        harness = new Harness();
        HookEngine engine = harness.ctx.hooks();
        assertSame(harness.engine(), engine, "registered as the HookEngine SPI");
        List<HookStatus> statuses = engine.install(Collections.singletonList(spec("deposit.computed",
                HookMode.OBSERVE, DepositRule.class, "depositFor")), "spi", "sha256:cc", false);
        assertEquals(CapabilityState.OK, statuses.get(0).state());
        assertEquals("hook.deposit.computed", statuses.get(0).capability());
        assertEquals("spi", engine.catalogue().get("id"));
        assertNotNull(engine.catalogue().get("installedAt"));
        assertEquals(new BigDecimal("20.00"), rule.depositFor("T1"));
        assertEquals("FIRING", engine.health(Collections.<String>emptyList()).get("deposit.computed").state().name());
        assertEquals(CapabilityState.OK, engine.probe(spec("deposit.waived", HookMode.OBSERVE, DepositRule.class,
                "waives")).state());
        List<HookStatus> removed = engine.remove(Collections.<String>emptyList());
        assertEquals(CapabilityState.OK, removed.get(0).state());
        assertFalse(Harness.slotHeld());
        assertTrue(engine.catalogue().isEmpty());
    }

    // ---- Phase 8 (phase8-contracts.md sections 6.4 and 6.6) ----

    private static final String DEPOSIT_RULE = "ai.skillroy.testjava.fixture.hooks.DepositRule";

    private Map<String, Object> installWithDeny(String id, String hash, List<String> deny, boolean replace,
                                                HookSpec... specs) throws Exception {
        Map<String, Object> catalogue = map("id", id, "hash", hash);
        if (deny != null) {
            catalogue.put("deny", deny);
        }
        return harness.ctx.map("hook.install", map("specs", Harness.json(specs), "catalogue", catalogue,
                "replace", replace));
    }

    private RpcException refusedWithDeny(String id, List<?> deny) {
        return harness.ctx.error("hook.install", map("specs", Collections.emptyList(), "catalogue",
                map("id", id, "hash", "sha256:" + id, "deny", deny)));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> echo(Map<String, Object> answer) {
        return (List<Object>) ((Map<String, Object>) answer.get("catalogue")).get("deny");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTouchedLinesAndTheHealthsClassesCarryTheCodeSource() throws Exception {
        new DepositRule();
        harness = new Harness();
        Map<String, Object> result = harness.install("toys", spec("deposit.computed", HookMode.OBSERVE,
                DepositRule.class, "depositFor"), spec("deposit.waived", HookMode.OBSERVE, DepositRule.class,
                "waives"));
        Map<String, Object> line = (Map<String, Object>) ((List<Object>) result.get("touched")).get(0);
        assertEquals(Arrays.asList("class", "loader", "codeSource", "bytes", "methods", "ms"),
                new ArrayList<String>(line.keySet()));
        assertEquals("test-classes", line.get("codeSource"));
        assertEquals(Collections.emptyList(), echo(result), "a new agent holding nothing echoes an empty list");
        Map<String, Object> health = harness.ctx.map("hook.health", map());
        List<Object> classes = (List<Object>) health.get("classes");
        assertEquals(1, classes.size(), String.valueOf(classes));
        Map<String, Object> entry = (Map<String, Object>) classes.get(0);
        assertEquals(DEPOSIT_RULE, entry.get("class"));
        assertEquals("system", entry.get("loader"));
        assertEquals("test-classes", entry.get("codeSource"));
        assertEquals(Arrays.asList("deposit.computed", "deposit.waived"), entry.get("hooks"));
        assertTrue(((Number) entry.get("bytes")).longValue() > 0);
        assertFalse(entry.containsKey("deniedNow"));
        Map<String, Object> deny = (Map<String, Object>) health.get("deny");
        assertEquals(Collections.emptyList(), deny.get("catalogue"));
        assertEquals(Collections.emptyList(), deny.get("from"));
        assertEquals(Collections.emptyList(), deny.get("option"));
        assertEquals(0L, ((Number) deny.get("modules")).longValue(), "the engine's own registration is not counted");
        harness.ctx.call("hook.remove", map());
        assertEquals(Collections.emptyList(), harness.ctx.map("hook.health", map()).get("classes"));
    }

    @Test
    void theCataloguesDenyIsValidated() throws Exception {
        harness = new Harness();
        RpcException bad = refusedWithDeny("desk", Arrays.asList("a.b.C", "a..b"));
        assertEquals(ErrorCodes.INVALID_PARAMS, bad.error().code());
        assertEquals("catalogue.deny[1]", bad.error().data().get("param"));
        assertEquals("deny pattern 1: an empty segment", bad.getMessage());
        RpcException notString = refusedWithDeny("desk", Arrays.asList("a.b.C", 7));
        assertEquals("catalogue.deny[1]", notString.error().data().get("param"));
        RpcException notList = harness.ctx.error("hook.install", map("specs", Collections.emptyList(), "catalogue",
                map("id", "desk", "hash", "sha256:aa", "deny", "a.b.C")));
        assertEquals("catalogue.deny", notList.error().data().get("param"));
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 65; i++) {
            many.add("a.b.C" + i);
        }
        RpcException tooMany = refusedWithDeny("desk", many);
        assertEquals(ErrorCodes.INVALID_PARAMS, tooMany.error().code());
        assertEquals("catalogue.deny", tooMany.error().data().get("param"));
        assertTrue(harness.engine().deny().isEmpty(), "nothing of a refused call is held");
        for (int c = 0; c < 4; c++) {
            List<String> sixtyFour = new ArrayList<String>();
            for (int i = 0; i < 64; i++) {
                sixtyFour.add("p" + c + ".C" + i);
            }
            assertEquals(64 * (c + 1), echo(installWithDeny("cat" + c, "sha256:c" + c, sixtyFour, false)).size());
        }
        RpcException full = refusedWithDeny("cat4", Arrays.asList("p0.C0", "p9.Z"));
        assertEquals(ErrorCodes.INVALID_PARAMS, full.error().code());
        assertEquals("catalogue.deny", full.error().data().get("param"));
        assertEquals("the engine holds 256 deny patterns; restart the target", full.getMessage());
        assertEquals(256, harness.engine().deny().size());
        assertEquals(256, echo(installWithDeny("cat4", "sha256:c4", Arrays.asList("p0.C0"), false)).size(),
                "a pattern already held adds nothing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDenyIsMergedBeforeTheConflictCheckHeldAddOnlyAndEchoed() throws Exception {
        DepositRule rule = new DepositRule();
        new Returns();
        harness = new Harness();
        HookSpec deposit = spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor");
        HookSpec returns = spec("returns.count", HookMode.OBSERVE, Returns.class, "count");
        Map<String, Object> first = installWithDeny("desk", "sha256:aa", Arrays.asList("x.Late", "a.b.*"), false,
                deposit);
        assertEquals(Arrays.asList("a.b.*", "x.Late"), echo(first), "the held patterns, sorted");
        Map<String, Object> again = installWithDeny("desk", "sha256:aa", Arrays.asList("x.Late", "a.b.*"), false,
                deposit);
        assertEquals(Boolean.TRUE, ((Map<String, Object>) again.get("catalogue")).get("alreadyInstalled"));
        assertEquals(Arrays.asList("a.b.*", "x.Late"), echo(again), "echoed on the alreadyInstalled path too");

        // another catalogue denying the hooked class: HOOK_CONFLICT, but the engine is tightened
        RpcException conflict = harness.ctx.error("hook.install", map("specs", Collections.emptyList(),
                "catalogue", map("id", "branch", "hash", "sha256:bb", "deny", Arrays.asList(DEPOSIT_RULE))));
        assertEquals(ErrorCodes.HOOK_CONFLICT, conflict.error().code());
        assertTrue(harness.engine().deny().contains(DEPOSIT_RULE), "a refused catalogue still tightened the engine");
        assertEquals(Arrays.asList("a.b.*", DEPOSIT_RULE, "x.Late"), harness.engine().deny());

        // the hook installed before the deny keeps firing and is flagged, not removed (D568)
        long before = harness.engine().hooks.get("deposit.computed").count.get();
        rule.depositFor("T1");
        assertEquals(before + 1, harness.engine().hooks.get("deposit.computed").count.get(), "still firing");
        Map<String, Object> health = harness.ctx.map("hook.health", map());
        Map<String, Object> hook = (Map<String, Object>) ((Map<String, Object>) health.get("hooks"))
                .get("deposit.computed");
        assertEquals(Boolean.TRUE, hook.get("deniedNow"), String.valueOf(hook));
        Map<String, Object> line = (Map<String, Object>) ((List<Object>) health.get("classes")).get(0);
        assertEquals("denied by the catalogue branch: " + DEPOSIT_RULE, line.get("deniedNow"));
        Map<String, Object> deny = (Map<String, Object>) health.get("deny");
        assertEquals(Arrays.asList("a.b.*", DEPOSIT_RULE, "x.Late"), deny.get("catalogue"));
        assertEquals(Arrays.asList("desk", "branch"), deny.get("from"));

        // the call's own specs are checked against the merged list
        Map<String, Object> replaced = installWithDeny("desk", "sha256:cc", Arrays.asList(
                Returns.class.getName()), true, deposit, returns);
        List<Object> statuses = (List<Object>) replaced.get("hooks");
        assertEquals("denied by the catalogue branch: " + DEPOSIT_RULE,
                ((Map<String, Object>) statuses.get(0)).get("reason"));
        assertEquals("denied by the catalogue desk: " + Returns.class.getName(),
                ((Map<String, Object>) statuses.get(1)).get("reason"));
        assertEquals(Arrays.asList("a.b.*", DEPOSIT_RULE, Returns.class.getName(), "x.Late"), echo(replaced),
                "replace: true unions, never replaces");
        assertTrue(harness.engine().hooks.isEmpty(), "nothing installs");
        assertNull(harness.ctx.map("hook.health", map()).get("catalogue"), "no catalogue recorded");

        harness.ctx.call("hook.remove", map());
        assertEquals(4, harness.engine().deny().size(), "held after hook.remove {}");
        Map<String, Object> zero = installWithDeny("only", "sha256:dd", Arrays.asList("z.Z"), false);
        assertEquals(Collections.emptyList(), zero.get("hooks"), "a zero-spec install delivers a deny only");
        assertEquals(5, echo(zero).size());
        assertEquals(harness.engine().deny(), harness.ctx.hooks().deny(), "HookEngine.deny() through the SPI");
    }
}
