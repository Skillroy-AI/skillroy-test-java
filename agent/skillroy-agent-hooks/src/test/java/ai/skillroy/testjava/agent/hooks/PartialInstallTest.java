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

import ai.skillroy.testjava.agent.api.instrument.RetransformBatches;
import ai.skillroy.testjava.fixture.hooks.ChildFirstLoader;
import ai.skillroy.testjava.fixture.hooks.LateFeeRule;
import ai.skillroy.testjava.fixture.hooks.SlipFormat;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hook whose classes retransform only partly (phase6-contracts.md section 3.2 step 6 and section 3.3 as built, D178;
 * section 3.5, D117; the Phase 6 review's L2-S3 and L2-F7): a hook over two loaders whose second copy fails its
 * retransformation is refused, and the class that did take its advice is restored and verified before the call
 * answers; the slot is released only once no class carries an advice; a refused hook's id fires in no class it had
 * reached; and a failure's reason reaches the wire redacted (section 3.6, L1-F2).
 */
class PartialInstallTest {

    private static final String BAND = "(I)Ljava/lang/String;";

    private Harness harness;
    private ChildFirstLoader loader;
    private ClassFileTransformer foreign;

    @AfterEach
    void close() throws Exception {
        if (foreign != null) {
            Harness.inst().removeTransformer(foreign);
        }
        if (harness != null) {
            harness.close();
        }
        if (loader != null) {
            loader.close();
        }
    }

    /**
     * Another agent's transformer, registered ahead of the engine's: it hands the JVM a malformed class file for the
     * copy's class (every time) and, when {@code systemFrom} is positive, for the system class from that transform on.
     */
    private static ClassFileTransformer breaking(final ClassLoader copyLoader, final int systemFrom) {
        final AtomicInteger systemSeen = new AtomicInteger();
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader l, String name, Class<?> redefined, ProtectionDomain pd, byte[] b) {
                if (redefined == null || !"ai/skillroy/testjava/fixture/hooks/LateFeeRule".equals(name)) {
                    return null;
                }
                if (l == copyLoader) {
                    return new byte[] {1, 2, 3};
                }
                if (systemFrom > 0 && systemSeen.incrementAndGet() >= systemFrom) {
                    return new byte[] {1, 2, 3};
                }
                return null;
            }
        };
    }

    /** Two copies of the rule: the system loader's and a child-first copy; one batch each (section 3.2). */
    private Object copyAndHarness() throws Exception {
        new LateFeeRule().band(3);
        loader = new ChildFirstLoader(new URL[] {Harness.testClasses()}, PartialInstallTest.class.getClassLoader(),
                LateFeeRule.class.getName());
        Class<?> copy = loader.loadClass(LateFeeRule.class.getName());
        long size = RetransformBatches.CLASS_FILE_RESOURCE.bytes(LateFeeRule.class);
        long max = Math.max(1024, size + 256);
        assertTrue(2 * size > max, "the two copies need a batch each: " + size + " bytes, bound " + max);
        FakeHookContext ctx = new FakeHookContext();
        ctx.options.put(HookOptions.MAX_CLASS_BYTES, String.valueOf(max));
        harness = new Harness(ctx);
        return copy.newInstance();
    }

    private static HookSpec band(String id, HookMode mode, String loader) {
        return Harness.spec(id, mode, LateFeeRule.class.getName(), "band", BAND, loader, null, null, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> status(Map<String, Object> result, int i) {
        return (Map<String, Object>) ((List<Object>) result.get("hooks")).get(i);
    }

    @Test
    void theClassThatTookARefusedHooksAdviceIsRestoredBeforeTheSlotIsReleased() throws Exception {
        LateFeeRule own = new LateFeeRule();
        Object other = copyAndHarness();
        Method copyBand = other.getClass().getMethod("band", int.class);
        foreign = breaking(loader, 0);
        Harness.inst().addTransformer(foreign, true);

        Map<String, Object> refused = status(harness.install("fees", band("fee.band", HookMode.OBSERVE, null)), 0);
        assertEquals("UNAVAILABLE", refused.get("state"));
        assertTrue(String.valueOf(refused.get("reason")).startsWith("retransform failed: "), String.valueOf(refused));
        Harness.assertLogContains(harness.ctx, "hooks: restored " + LateFeeRule.class.getName() + " (system): OK");
        assertTrue(harness.engine().ledger.isEmpty(), "every class of the refused hook left the ledger");
        assertFalse(harness.engine().handlerInstalled(), "an install that installed nothing releases the handler");
        assertFalse(harness.engine().registered(), "and removes the transformers");
        assertFalse(Harness.slotHeld(), "the slot is free: no class carries an advice");
        long[] before = Harness.counters();
        assertEquals("grace", own.band(3));
        assertEquals(before[0], Harness.counters()[0], "the system class calls Spy no more");

        // The same id on the copy only: a STUB, armed. The system class that the refused hook had reached keeps its
        // own behaviour (no site key of the refused hook is left in it).
        Harness.inst().removeTransformer(foreign);
        foreign = null;
        String copyId = Integer.toHexString(System.identityHashCode(loader));
        Map<String, Object> second = harness.ctx.map("hook.install", map("specs", Harness.json(band("fee.band",
                HookMode.STUB, copyId)), "catalogue", map("id", "fees2", "hash", "sha256:f2")));
        assertEquals("OK", status(second, 0).get("state"), String.valueOf(second));
        harness.ctx.call("hook.stub", map("id", "fee.band", "value", "waived"), map("confirm", true));
        assertEquals("waived", copyBand.invoke(other, 9), "the copy is stubbed");
        assertEquals("late", own.band(9), "the class the operator did not target is not");
        harness.ctx.call("hook.remove", map());
    }

    @Test
    void aRefusedHookIsTakenOutOfAClassThatKeepsAnotherHook() throws Exception {
        LateFeeRule own = new LateFeeRule();
        copyAndHarness();
        foreign = breaking(loader, 0);
        Harness.inst().addTransformer(foreign, true);

        Map<String, Object> result = harness.install("fees", band("fee.band", HookMode.OBSERVE, null),
                Harness.spec("fee.computed", HookMode.OBSERVE, LateFeeRule.class, "feeFor"));
        assertEquals("UNAVAILABLE", status(result, 0).get("state"), String.valueOf(result));
        assertEquals("OK", status(result, 1).get("state"), String.valueOf(result));
        assertEquals(1, harness.engine().ledger.size(), "the system class keeps the other hook");
        long[] before = Harness.counters();
        int events = Harness.events(harness.ctx, "hook.fee.computed").size();
        assertEquals("late", own.band(9));
        assertEquals(before[0], Harness.counters()[0], "the refused hook's method calls Spy no more");
        assertEquals(18, own.feeFor(9));
        assertEquals(events + 1, Harness.events(harness.ctx, "hook.fee.computed").size(), "the other hook fires");
        Map<String, Object> removed = harness.ctx.map("hook.remove", map());
        assertEquals("OK", ((Map<?, ?>) ((List<?>) removed.get("restored")).get(0)).get("verified"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRestoreThatFailsKeepsTheEntryAndTheSlotAndTheIdIsRefusedUntilARemoveRestoresIt() throws Exception {
        LateFeeRule own = new LateFeeRule();
        copyAndHarness();
        foreign = breaking(loader, 2);
        Harness.inst().addTransformer(foreign, true);

        Map<String, Object> refused = status(harness.install("fees", band("fee.band", HookMode.OBSERVE, null)), 0);
        assertEquals("UNAVAILABLE", refused.get("state"));
        assertTrue(String.valueOf(refused.get("reason")).contains("hook.remove {} retries it"),
                String.valueOf(refused));
        assertEquals(1, harness.engine().ledger.size(), "a class whose restore failed keeps its entry");
        assertTrue(Harness.slotHeld(), "and so the slot (D117)");
        Map<String, Object> health = (Map<String, Object>) ((Map<String, Object>) harness.ctx.map("hook.health",
                map()).get("hooks")).get("fee.band");
        assertEquals("ERROR", health.get("state"), String.valueOf(health));
        assertEquals(HookEngineImpl.RESIDUE_AFTER_REFUSAL, health.get("reason"));

        String copyId = Integer.toHexString(System.identityHashCode(loader));
        Map<String, Object> again = harness.ctx.map("hook.install", map("specs", Harness.json(band("fee.band",
                HookMode.STUB, copyId)), "catalogue", map("id", "fees2", "hash", "sha256:f2")));
        assertEquals("UNAVAILABLE", status(again, 0).get("state"));
        assertTrue(String.valueOf(status(again, 0).get("reason")).startsWith("a residue of hook fee.band is left"),
                String.valueOf(again));

        Harness.inst().removeTransformer(foreign);
        foreign = null;
        Map<String, Object> removed = harness.ctx.map("hook.remove", map());
        assertEquals("OK", ((Map<?, ?>) ((List<?>) removed.get("restored")).get(0)).get("verified"));
        assertTrue(harness.engine().ledger.isEmpty());
        assertFalse(Harness.slotHeld());
        assertTrue(((Map<String, Object>) harness.ctx.map("hook.health", map()).get("hooks")).isEmpty(),
                "the residue is gone once its class is restored");
        long[] before = Harness.counters();
        assertEquals("late", own.band(9));
        assertEquals(before[0], Harness.counters()[0]);
    }

    /**
     * Phase 6 security fix, pass 2, P2-L2-S1 (and P2-L2-F4's first half): {@code hook.remove} of one of two hooks on
     * the system class, whose retransformation without it throws (another agent breaks the class from then on), keeps
     * the class's bytes and so the removed hook's advice. The removed hook is then a residue: answered
     * {@code UNAVAILABLE} with the reason, {@code ERROR} in health, and its id refused by a later install, also one
     * pinned to another loader's copy, so that a confirmed {@code STUB} of the id can never reach the class the
     * operator did not target. Before the fix the hook was answered {@code OK "removed"}, the install pinned to the copy
     * passed, and the stub answered {@code waived} from the system class too (the sceptic's probe).
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRemoveWhosePartialRetransformationThrowsLeavesAResidueWhoseIdIsRefused() throws Exception {
        LateFeeRule own = new LateFeeRule();
        Object other = copyAndHarness();
        Method copyBand = other.getClass().getMethod("band", int.class);
        harness.ctx.map("hook.install", map("specs", Harness.json(band("fee.band", HookMode.STUB, "system"),
                Harness.spec("fee.computed", HookMode.OBSERVE, LateFeeRule.class.getName(), "feeFor", "(I)I",
                        "system", null, null, false)), "catalogue", map("id", "fees", "hash", "sha256:h1")));
        foreign = breaking(null, 1);
        Harness.inst().addTransformer(foreign, true);

        Map<String, Object> removed = harness.ctx.map("hook.remove", map("ids", java.util.Arrays.asList("fee.band")));
        Map<String, Object> band = status(removed, 0);
        assertEquals("UNAVAILABLE", band.get("state"), String.valueOf(removed));
        String reason = String.valueOf(band.get("reason"));
        assertTrue(reason.startsWith(HookEngineImpl.RESIDUE_AFTER_REMOVE + ": the retransformation without it failed: "
                + LateFeeRule.class.getName() + " (system): java.lang.ClassFormatError"), reason);
        assertTrue(reason.endsWith("(hook.remove {} retries it)"), reason);
        assertTrue(((List<Object>) removed.get("restored")).isEmpty(), "the class keeps another hook");
        long[] before = Harness.counters();
        assertEquals("late", own.band(9));
        assertTrue(Harness.counters()[0] > before[0], "the class kept its bytes: the removed hook's advice is there,"
                + " inert");
        Map<String, Object> health = (Map<String, Object>) ((Map<String, Object>) harness.ctx.map("hook.health",
                map()).get("hooks")).get("fee.band");
        assertEquals("ERROR", health.get("state"), String.valueOf(health));
        assertTrue(String.valueOf(health.get("reason")).startsWith(HookEngineImpl.RESIDUE_AFTER_REMOVE + ": "),
                String.valueOf(health));

        String copyId = Integer.toHexString(System.identityHashCode(loader));
        Map<String, Object> again = harness.ctx.map("hook.install", map("specs", Harness.json(band("fee.band",
                HookMode.STUB, copyId)), "catalogue", map("id", "fees", "hash", "sha256:h2"), "replace", true));
        assertEquals("UNAVAILABLE", status(again, 0).get("state"), String.valueOf(again));
        assertTrue(String.valueOf(status(again, 0).get("reason")).startsWith("a residue of hook fee.band is left"),
                String.valueOf(again));
        assertEquals("FAILED", ((Map<String, Object>) again.get("replaced")).get("verified"),
                "the replace's remove of fee.computed could not restore the class (P2-L2-F3): " + again);
        assertEquals(ai.skillroy.testjava.protocol.ErrorCodes.INVALID_PARAMS, harness.ctx.error("hook.stub",
                map("id", "fee.band", "value", "waived"), map("confirm", true)).error().code(),
                "no hook of the id is installed, so nothing can be armed");
        assertEquals("late", own.band(9), "the class the operator did not target keeps its own answer");
        assertEquals("grace", copyBand.invoke(other, 3));

        Harness.inst().removeTransformer(foreign);
        foreign = null;
        harness.ctx.map("hook.remove", map());
        assertTrue(harness.engine().ledger.isEmpty());
        assertTrue(((Map<String, Object>) harness.ctx.map("hook.health", map()).get("hooks")).isEmpty(),
                "the residues are gone once their class is restored");
        assertFalse(Harness.slotHeld());
        before = Harness.counters();
        assertEquals("late", own.band(9));
        assertEquals(before[0], Harness.counters()[0], "restored: the system class calls Spy no more");
    }

    @Test
    void aFailedRetransformsReasonGoesThroughTheRedactionAndAnInstallOfNothingReleasesTheSlot() throws Exception {
        new SlipFormat().header("drill");
        harness = new Harness();
        foreign = malformed(SlipFormat.class);
        Harness.inst().addTransformer(foreign, true);
        Map<String, Object> refused = status(harness.install("slips", Harness.spec("slip.header", HookMode.OBSERVE,
                SlipFormat.class, "header")), 0);
        String reason = String.valueOf(refused.get("reason"));
        assertEquals("UNAVAILABLE", refused.get("state"));
        assertTrue(reason.startsWith("retransform failed: java.lang.ClassFormatError"), reason);
        assertTrue(harness.ctx.redacting.contains(reason), "the reason went through the core's redactText: "
                + harness.ctx.redacting);
        assertTrue(harness.engine().ledger.isEmpty());
        assertFalse(Harness.slotHeld(), "nothing installed, nothing advised: the slot is released at once");
        assertFalse(harness.engine().handlerInstalled());
        assertFalse(harness.engine().registered());
        assertEquals("Slip: drill", new SlipFormat().header("drill"));
    }

    /** Another agent's transformer that hands the JVM a malformed class file for one class. */
    private static ClassFileTransformer malformed(final Class<?> type) {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader l, String name, Class<?> redefined, ProtectionDomain pd, byte[] b) {
                return redefined == type ? new byte[] {1, 2, 3} : null;
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void anErrorReasonInHealthIsRedacted() throws Exception {
        new SlipFormat();
        harness = new Harness();
        harness.install("slips", Harness.spec("slip.header", HookMode.OBSERVE, SlipFormat.class, "header"));
        harness.engine().ledger.entries().get(0).error = "transform failed: x.Y: password=hunter2 rejected";
        Map<String, Object> health = (Map<String, Object>) ((Map<String, Object>) harness.ctx.map("hook.health",
                map()).get("hooks")).get("slip.header");
        assertEquals("ERROR", health.get("state"));
        assertEquals("transform failed: x.Y: password=<redacted> rejected", health.get("reason"));
        harness.ctx.capabilities.reprobe(harness.ctx);
        assertEquals("transform failed: x.Y: password=<redacted> rejected",
                harness.ctx.capabilities.get("hook.slip.header").reason());
        harness.engine().ledger.entries().get(0).error = null;
        harness.ctx.call("hook.remove", map());
    }
}
