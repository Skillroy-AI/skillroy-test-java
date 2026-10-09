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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hook.install} (phase6-contracts.md sections 3.2 and 4.2): a class defined by two loaders, idempotency by
 * catalogue hash, {@code HOOK_CONFLICT} and {@code replace}, the ledger's bound, a method hooked twice, the
 * {@code hooks.installed} event, and the SPI through {@code AgentContext.hooks()}.
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
        long retransformsBefore = harness.engine().ledger.entries().get(0).own;

        Map<String, Object> again = harness.ctx.map("hook.install", map("specs", Harness.json(b, a),
                "catalogue", catalogue));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) again.get("catalogue")).get("alreadyInstalled"));
        assertTrue(((List<Object>) again.get("touched")).isEmpty());
        assertEquals(2, ((List<Object>) again.get("hooks")).size());
        assertEquals(retransformsBefore, harness.engine().ledger.entries().get(0).own, "nothing retransformed");

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
}
