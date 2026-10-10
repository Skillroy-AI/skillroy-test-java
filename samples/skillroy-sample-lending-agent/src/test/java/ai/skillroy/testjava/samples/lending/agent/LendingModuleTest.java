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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.ActionFirer;
import ai.skillroy.testjava.agent.api.spi.AppStateProvider;
import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.DeviceAdapter;
import ai.skillroy.testjava.agent.api.spi.DialogTextExtractor;
import ai.skillroy.testjava.agent.api.spi.LocatorResolver;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.agent.api.spi.NameEnricher;
import ai.skillroy.testjava.agent.api.spi.ReadinessSource;
import ai.skillroy.testjava.agent.api.spi.RedactionKeys;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.samples.lending.Edition;
import ai.skillroy.testjava.samples.lending.Engine;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingInput;
import ai.skillroy.testjava.samples.lending.LendingOptions;
import ai.skillroy.testjava.samples.lending.LendingState;
import ai.skillroy.testjava.samples.lending.Seed;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static ai.skillroy.testjava.samples.lending.agent.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The module's lifecycle: install, readiness and the engine listener, capabilities, thread policy, uninstall. */
class LendingModuleTest {

    private static LendingApp launch(String name) throws Exception {
        return LendingApp.launch(LendingOptions.defaults().seed(Seed.A).ui(false)
                .dbName("lending_module_test_" + name));
    }

    @Test
    void installRegistersEverythingAndTouchesNoApplicationObject() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext(null, LoadMode.JAVAAGENT);
        LendingModule module = new LendingModule();
        assertEquals("lending", module.id());
        assertEquals("0.2.0", module.version());
        assertEquals(Collections.emptySet(), module.requires());
        module.install(ctx);
        try {
            for (Class<?> type : Arrays.<Class<?>>asList(ReadinessSource.class, ThreadPolicyProvider.class,
                    AppStateProvider.class, NameEnricher.class, LocatorResolver.class, ActionFirer.class,
                    DialogTextExtractor.class, DataSourceProvider.class, MaskPolicy.class, DeviceAdapter.class,
                    RedactionKeys.class, ai.skillroy.testjava.agent.api.spi.RegionResolver.class,
                    ai.skillroy.testjava.agent.api.spi.CaptureMask.class)) {
                assertEquals(1, ctx.spi.all(type).size(), type.getSimpleName());
            }
            assertEquals(13, ctx.spi.count(), "the eleven SPIs of Phase 6 and the two of Phase 7");
            assertTrue(ctx.spi.first(ai.skillroy.testjava.agent.api.spi.RegionResolver.class)
                    instanceof LendingRegions);
            assertTrue(ctx.spi.first(ai.skillroy.testjava.agent.api.spi.CaptureMask.class)
                    instanceof LendingCaptureMask);
            assertEquals(Arrays.asList("lending.access.check", "lending.deposit", "lending.input", "lending.limits",
                    "lending.loan", "lending.prompt", "lending.prompt.answer", "lending.slip", "lending.state",
                    "lending.waiver.evaluate"), names(ctx));
            assertEquals(Arrays.asList("lending.access.check", "lending.deposit", "lending.input", "lending.limits",
                    "lending.loan", "lending.loan.authorisations", "lending.prompt", "lending.prompt.answer",
                    "lending.slip", "lending.sql", "lending.state", "lending.waiver.evaluate"),
                    new java.util.ArrayList<String>(ctx.capabilities.probes().keySet()));
            assertTrue(ctx.spi.first(ThreadPolicyProvider.class) instanceof LendingWorker);
            assertTrue(ctx.primitives.find("lending.input").spec().mutating());
            assertTrue(ctx.primitives.find("lending.prompt.answer").spec().mutating());
            assertFalse(ctx.primitives.find("lending.state").spec().mutating());
            assertEquals("APP", ctx.primitives.find("lending.loan").spec().describe().thread());
            for (String readOnly : Arrays.asList("lending.access.check", "lending.deposit", "lending.limits",
                    "lending.waiver.evaluate")) {
                ai.skillroy.testjava.agent.api.PrimitiveSpec spec = ctx.primitives.find(readOnly).spec();
                assertFalse(spec.mutating(), readOnly + " only reads, so a binder may call it");
                assertFalse(spec.destructive(), readOnly);
                assertEquals("APP", spec.describe().thread(), readOnly);
                assertEquals(5000L, spec.timeoutMs(), readOnly);
                assertEquals(readOnly, spec.capabilityId(), readOnly + " depends on its own capability");
            }

            ReadinessSource readiness = ctx.spi.first(ReadinessSource.class);
            assertFalse(readiness.isReady());
            assertEquals("waiting for the lending application to start its engine", readiness.describe());
            assertNull(module.access().app(), "nothing of the application is looked at before its engine runs");

            ctx.probeAll();
            Capability input = ctx.capabilities.get("lending.input");
            assertEquals(CapabilityState.UNAVAILABLE, input.state());
            assertEquals("the lending application is not running", input.reason());
            RpcException notRunning = ctx.fails("lending.state", args());
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, notRunning.error().code());
            assertEquals(Collections.singletonMap("running", Boolean.FALSE),
                    ctx.spi.first(AppStateProvider.class).appState());
        } finally {
            module.uninstall();
        }
        assertEquals(0, ctx.spi.count(), "every SPI unregistered");
        assertTrue(ctx.primitives.list().isEmpty(), "every primitive unregistered");
        assertEquals(Collections.emptyMap(), ctx.capabilities.snapshot(), "every capability withdrawn (A18)");
        assertTrue(ctx.capabilities.probes().isEmpty(), "and no probe left to run");
    }

    /**
     * Amendment A21: the module adds the desk's PIN keys to the keys the core redacts. The shared rule does not know
     * them, so without the module a {@code lending.pin} would be shown; the core's own use of the pattern
     * ({@code jvm.properties}, {@code jvm.info}) is proven end to end in every load mode.
     */
    @Test
    void theDesksPinKeysAreSecretAndNothingElseOfItsIs() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext(null, LoadMode.JAVAAGENT);
        LendingModule module = new LendingModule();
        module.install(ctx);
        java.util.regex.Pattern keys;
        try {
            RedactionKeys redaction = ctx.spi.first(RedactionKeys.class);
            assertTrue(redaction instanceof LendingSecrets);
            keys = redaction.secretKeys();
            assertNotNull(keys);
            assertSame(keys, redaction.secretKeys(), "one compiled pattern, cheap to ask for at every call");
        } finally {
            module.uninstall();
        }
        for (String secret : Arrays.asList("lending.pin", "LENDING.PIN", "lending.Pin", "lending.supervisor.pin",
                "lending.desk-2.supervisor.pin", "lending.desk_2.pin")) {
            assertFalse(ai.skillroy.testjava.protocol.SecretKeys.looksSecret(secret), secret
                    + ": the shared rule does not know it, so the module's rule is what hides it");
            assertTrue(keys.matcher(secret).find(), secret);
        }
        for (String plain : Arrays.asList("lending.seed", "lending.plugins", "lending.title", "lending.pinned",
                "lending.spin", "lending.pin.length", "lending..pin", "other.pin", "pin", "xlending.pin",
                "lending.idleLockSeconds")) {
            assertFalse(keys.matcher(plain).find(), plain);
        }
        assertTrue(ctx.spi.all(RedactionKeys.class).isEmpty(), "unregistered on uninstall: the keys stop counting");
    }

    @Test
    void outsideEmbeddedModeTheDeskIsFoundThroughItsStaticAccessorOnceItsEngineRuns() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext(null, LoadMode.ATTACH);
        LendingModule module = new LendingModule();
        module.install(ctx);
        LendingApp app = launch("static");
        try {
            LendingFixture.awaitReady(ctx, 10000);
            assertSame(app, module.access().app());
            assertEquals(1, app.engine().listenerCount());
        } finally {
            module.uninstall();
            app.shutdown();
        }
        assertEquals(0, app.engine().listenerCount());
    }

    @Test
    void theListenerIsAttachedWhileTheEngineWaitsAndRemovedOnUninstall() throws Exception {
        LendingFixture lending = LendingFixture.start();
        try {
            Engine engine = lending.app.engine();
            assertEquals(1, engine.listenerCount(), "headless desk: the module's listener only");
            EngineEvents events = lending.module.slot().events();
            assertNotNull(events);
            assertTrue(events.isActive());
            for (String id : LendingPrimitives.NAMES) {
                assertEquals(CapabilityState.OK, lending.ctx.capabilities.get(id).state(), id);
            }
            assertEquals(CapabilityState.OK, lending.ctx.capabilities.get("lending.sql").state());
            assertEquals("read-only JDBC", lending.ctx.capabilities.get("lending.sql").strategy());
            assertEquals(CapabilityState.OK, lending.ctx.capabilities.get("lending.loan.authorisations").state());

            lending.uninstall();
            assertEquals(0, engine.listenerCount(), "removed at once: the engine waits for input");
            assertFalse(events.isActive());
            long before = lending.ctx.events.lastSeq();
            events.stateChanged(LendingState.IDLE, LendingState.LOAN);
            events.inputRequested(LendingState.LOAN, "x");
            events.promptShown("p", "t", Collections.singletonList("OK"));
            events.loanPosted(1L);
            assertEquals(before, lending.ctx.events.lastSeq(), "an inert listener publishes nothing");
            assertNull(lending.module.slot().events());
            assertFalse(lending.module.slot().tryAttach(lending.app), "a closed slot never attaches again");
        } finally {
            lending.close();
        }
    }

    /**
     * An agent can arrive while the desk is asking a question (a prompt left open by whoever used it last). The
     * engine does not wait for input then, so no listener may be added; the module must be ready all the same, or
     * nothing could ever answer the question (answering is a mutating call, refused until the target is ready).
     */
    @Test
    void aDeskFoundAskingAQuestionIsReadyWithoutTheListenerWhichFollowsTheAnswer() throws Exception {
        LendingApp app = launch("prompt_open");
        LendingModule module = new LendingModule();
        try {
            final Engine engine = app.engine();
            offerAndAwait(app, LendingInput.entry("100"), "PASSWORD");
            offerAndAwait(app, LendingInput.entry("1111"), "IDLE");
            assertTrue(app.inbox().offer(new LendingInput("Barcode", "0000000000000")));
            await("the unknown-item prompt", () -> app.prompts().current() != null);
            assertFalse(engine.isAwaitingInput());

            FakeAgentContext ctx = new FakeAgentContext(app, LoadMode.ATTACH);
            module.install(ctx);
            ReadinessSource readiness = ctx.spi.first(ReadinessSource.class);
            assertTrue(readiness.isReady(), "ready although no listener can be attached: " + readiness.describe());
            assertTrue(readiness.isReady(), "and stays ready");
            module.onTargetReady(ctx);
            assertNull(module.slot().events());
            assertEquals(0, engine.listenerCount(), "nothing was added while the engine does not wait for input");
            ctx.probeAll();
            Capability input = ctx.capabilities.get("lending.input");
            assertEquals(CapabilityState.UNAVAILABLE, input.state());
            assertTrue(input.reason().contains("prompt open (unknown-item)"), input.reason());
            for (String id : Arrays.asList("lending.prompt", "lending.prompt.answer", "lending.state",
                    "lending.loan")) {
                assertEquals(CapabilityState.OK, ctx.capabilities.get(id).state(), id);
            }
            assertEquals("unknown-item", ((Map<?, ?>) ctx.call("lending.prompt", args())).get("id"));
            assertEquals(Boolean.FALSE, ((Map<?, ?>) ctx.call("lending.state", args())).get("awaitingInput"));
            assertEquals(0, engine.listenerCount(), "looking at the desk adds nothing while the prompt is open");
            long started = System.nanoTime();
            assertFalse(module.slot().attachWhenWaiting(app, 60000), "a prompt is open: nothing to wait for");
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30), "answered at once");

            long before = ctx.events.lastSeq();
            assertEquals(args("answered", Boolean.TRUE, "id", "unknown-item", "choice", "OK", "listening",
                    Boolean.TRUE), ctx.call("lending.prompt.answer", args("choice", "OK")));
            assertNotNull(module.slot().events(), "attached as soon as the engine waited for input again");
            assertEquals(1, engine.listenerCount());
            assertEquals(CapabilityState.OK, ctx.capabilities.get("lending.input").state(),
                    "lending.input is available from the moment the listener is attached");
            ai.skillroy.testjava.protocol.Event waiting = ctx.events.await(before,
                    ai.skillroy.testjava.protocol.TopicFilter.parse("input.requested"), null, 10000);
            assertNotNull(waiting, "the late listener announces that the desk waits for input: "
                    + ctx.events.topics());
            assertEquals("IDLE", waiting.payload().get("state"));
            assertTrue(((java.util.List<?>) waiting.payload().get("validInputs")).contains("Key.SIGN_OFF"));
            assertTrue(ctx.events.await(waiting.seq(), ai.skillroy.testjava.protocol.TopicFilter.parse(
                    "input.requested"), null, 200) == null, "announced once");

            @SuppressWarnings("unchecked")
            Map<String, Object> signOff = (Map<String, Object>) ctx.call("lending.input", args("source",
                    "Key.SIGN_OFF"));
            assertEquals(Boolean.TRUE, signOff.get("acked"), "inputs are acknowledged again: " + signOff);
            ctx.probeAll();
            assertEquals(CapabilityState.OK, ctx.capabilities.get("lending.input").state(), "and a re-probe agrees");
        } finally {
            module.uninstall();
            app.shutdown();
        }
        assertEquals(0, app.engine().listenerCount());
    }

    /**
     * The question may be answered by a person at the desk instead of through the agent: the listener must follow all
     * the same, without anybody calling a lending primitive, or {@code lending.input} would stay unavailable.
     */
    @Test
    void aPromptAnsweredAtTheDeskItselfIsFollowedByTheListenerToo() throws Exception {
        LendingApp app = launch("prompt_open_person");
        LendingModule module = new LendingModule();
        try {
            assertTrue(app.inbox().offer(LendingInput.entry("999999")));
            await("the unknown-staff prompt", () -> app.prompts().current() != null);
            FakeAgentContext ctx = new FakeAgentContext(app, LoadMode.ATTACH);
            module.install(ctx);
            assertTrue(ctx.spi.first(ReadinessSource.class).isReady());
            ctx.probeAll();
            assertEquals(CapabilityState.UNAVAILABLE, ctx.capabilities.get("lending.input").state());
            Thread watcher = thread(ListenerSlot.WATCHER_NAME);
            assertNotNull(watcher, "a thread waits for the moment the listener can be attached");
            assertTrue(watcher.isDaemon());
            long before = ctx.events.lastSeq();

            assertTrue(app.prompts().answer("OK"), "answered by the application's own user");
            await("the listener to follow the answer", () -> module.slot().events() != null);
            assertEquals(1, app.engine().listenerCount());
            await("lending.input to become available", () -> ctx.capabilities.get("lending.input").state()
                    == CapabilityState.OK);
            ai.skillroy.testjava.protocol.Event waiting = ctx.events.await(before,
                    ai.skillroy.testjava.protocol.TopicFilter.parse("input.requested"), null, 10000);
            assertNotNull(waiting, "announced: " + ctx.events.topics());
            assertEquals("SIGNED_OFF", waiting.payload().get("state"));
            watcher.join(10000);
            assertFalse(watcher.isAlive(), "the watcher ends once the listener is attached");
        } finally {
            module.uninstall();
            app.shutdown();
        }
        assertEquals(0, app.engine().listenerCount());
    }

    /** A module that is ready without its listener and is uninstalled before the prompt is answered adds nothing. */
    @Test
    void uninstallingBeforeThePromptIsAnsweredLeavesTheDeskUntouched() throws Exception {
        LendingApp app = launch("prompt_open_uninstall");
        try {
            assertTrue(app.inbox().offer(LendingInput.entry("999999")));
            await("the unknown-staff prompt", () -> app.prompts().current() != null);
            FakeAgentContext ctx = new FakeAgentContext(app, LoadMode.ATTACH);
            LendingModule module = new LendingModule();
            module.install(ctx);
            assertTrue(ctx.spi.first(ReadinessSource.class).isReady());
            ListenerSlot slot = module.slot();
            Thread watcher = thread(ListenerSlot.WATCHER_NAME);
            assertNotNull(watcher);
            module.uninstall();
            watcher.join(10000);
            assertFalse(watcher.isAlive(), "uninstall ends the watcher");
            assertTrue(app.prompts().answer("OK"));
            await("the engine to wait for input", () -> app.engine().isAwaitingInput());
            assertFalse(slot.tryAttach(app), "a closed slot never attaches");
            assertFalse(slot.attachWhenWaiting(app, 60000), "nor waits to");
            assertEquals(0, app.engine().listenerCount());
        } finally {
            app.shutdown();
        }
    }

    private static void offerAndAwait(final LendingApp app, LendingInput input, final String state) throws Exception {
        assertTrue(app.inbox().offer(input));
        await("state " + state, () -> app.engine().isAwaitingInput()
                && state.equals(app.engine().status().state().name()));
    }

    /** Waits (bounded) for a condition of a desk that has no listener to report it. */
    private static void await(String what, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, "timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    @Test
    void removalWaitsForTheEngineToWaitForInputAgain() throws Exception {
        LendingFixture lending = LendingFixture.start();
        try {
            lending.signOn();
            lending.send("Barcode", "0000000000000");
            Engine engine = lending.app.engine();
            assertFalse(engine.isAwaitingInput(), "the prompt blocks the engine");
            final ListenerSlot slot = lending.module.slot();
            final EngineEvents events = slot.events();
            final CountDownLatch detached = new CountDownLatch(1);
            Thread detacher = new Thread(new Runnable() {
                @Override
                public void run() {
                    slot.detach(20000);
                    detached.countDown();
                }
            }, "test-detacher");
            detacher.setDaemon(true);
            detacher.start();
            assertFalse(detached.await(200, TimeUnit.MILLISECONDS), "not removed while the prompt is open");
            assertEquals(1, engine.listenerCount());
            assertTrue(lending.app.prompts().answer("OK"));
            assertTrue(detached.await(10, TimeUnit.SECONDS), "removed at the engine's next inputRequested");
            assertEquals(0, engine.listenerCount());
            assertFalse(events.isActive());
        } finally {
            lending.close();
        }
    }

    @Test
    void aRemovalThatCannotHappenInTimeIsFinishedByADaemonThread() throws Exception {
        LendingFixture lending = LendingFixture.start();
        try {
            lending.signOn();
            lending.send("Barcode", "0000000000000");
            Engine engine = lending.app.engine();
            lending.module.slot().detach(0);
            assertEquals(1, engine.listenerCount(), "still registered, but inert");
            Thread deferred = thread("skillroy-lending-detach");
            assertNotNull(deferred, "deferred removal thread");
            assertTrue(deferred.isDaemon());
            assertTrue(lending.app.prompts().answer("OK"));
            deferred.join(10000);
            assertFalse(deferred.isAlive());
            assertEquals(0, engine.listenerCount());
        } finally {
            lending.close();
        }
    }

    @Test
    void anAcknowledgementWaitEndsWhenTheModuleIsUninstalled() throws Exception {
        LendingFixture lending = LendingFixture.start();
        try {
            EngineEvents events = lending.module.slot().events();
            EngineEvents.AckWait wait = events.expectAck(LendingInput.entry("100"));
            events.deactivate();
            assertFalse(wait.await(10000), "released at once, as not acknowledged");
            assertFalse(events.expectAck(LendingInput.entry("100")).await(10000));
        } finally {
            lending.close();
        }
    }

    @Test
    void appCallsRunOnTheLendingWorkerAndNeverOnTheEngineThread() throws Exception {
        LendingFixture lending = LendingFixture.start();
        try {
            final LendingWorker worker = (LendingWorker) lending.ctx.spi.first(ThreadPolicyProvider.class);
            assertEquals("lending", worker.id());
            Thread engineThread = lending.app.engine().thread();
            assertTrue(worker.isForbidden(engineThread));
            assertFalse(worker.isForbidden(Thread.currentThread()));
            assertFalse(worker.isForbidden(null));

            final AtomicReference<Thread> ran = new AtomicReference<Thread>();
            assertEquals("done", worker.callOnAppThread(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    ran.set(Thread.currentThread());
                    // A nested APP call from the worker runs inline instead of waiting for itself.
                    return worker.callOnAppThread(new Callable<String>() {
                        @Override
                        public String call() {
                            return Thread.currentThread() == ran.get() ? "done" : "other thread";
                        }
                    }, 1000);
                }
            }, 5000));
            assertEquals(LendingWorker.THREAD_NAME, ran.get().getName());
            assertTrue(ran.get().isDaemon());

            final CountDownLatch release = new CountDownLatch(1);
            RpcException timeout = assertThrows(RpcException.class, () -> worker.callOnAppThread(
                    new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                            release.await(10, TimeUnit.SECONDS);
                            return null;
                        }
                    }, 100));
            assertEquals(ErrorCodes.TIMEOUT, timeout.error().code());
            release.countDown();

            IllegalStateException boom = assertThrows(IllegalStateException.class, () -> worker.callOnAppThread(
                    new Callable<Object>() {
                        @Override
                        public Object call() {
                            throw new IllegalStateException("boom");
                        }
                    }, 5000));
            assertEquals("boom", boom.getMessage());

            lending.uninstall();
            RpcException stopped = assertThrows(RpcException.class, () -> worker.callOnAppThread(
                    new Callable<Object>() {
                        @Override
                        public Object call() {
                            return null;
                        }
                    }, 1000));
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, stopped.error().code());
        } finally {
            lending.close();
        }
    }

    @Test
    void capabilitiesBecomeUnavailableWhenTheDeskStops() throws Exception {
        LendingFixture lending = LendingFixture.start();
        try {
            lending.app.engine().stop();
            lending.ctx.probeAll();
            Capability state = lending.ctx.capabilities.get("lending.state");
            assertEquals(CapabilityState.UNAVAILABLE, state.state());
            assertEquals("the desk engine is not running", state.reason());
        } finally {
            lending.close();
        }
    }

    @Test
    void seedBIsReportedAndItsDataIsUsed() throws Exception {
        LendingFixture lending = LendingFixture.start(Seed.B);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> decision = (Map<String, Object>) lending.ctx.call("lending.access.check",
                    args("staff", "4711", "permission", "RETURN", "amount", "16.00"));
            assertEquals(Boolean.FALSE, decision.get("allowed"));
            assertEquals("15.00", decision.get("limit"));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) lending.ctx.call("lending.access.check",
                    args("staff", "4714", "permission", "SIGN_ON"))).get("autoSignOn"));
            assertEquals(args("maxReturn", "350.00"), lending.ctx.call("lending.limits", args()));
            assertEquals("27.50", ((Map<?, ?>) lending.ctx.call("lending.deposit", args("barcode", "4006000000080")))
                    .get("deposit"));
            assertEquals("B", lending.ctx.spi.first(AppStateProvider.class).appState().get("seed"));
        } finally {
            lending.close();
        }
    }

    /**
     * phase6-contracts.md 11.3: {@code app.state} reports the desk's edition ({@code standard} by default,
     * {@code branch} for a desk launched with {@code lending.edition=branch}), and the branch desk answers the access
     * check and the limits from its own column and setting names with the same values.
     */
    @Test
    void appStateReportsTheEdition() throws Exception {
        LendingFixture standard = LendingFixture.start();
        try {
            assertEquals("standard", standard.ctx.spi.first(AppStateProvider.class).appState().get("edition"));
        } finally {
            standard.close();
        }
        LendingFixture branch = LendingFixture.start(Edition.BRANCH);
        try {
            assertEquals("branch", branch.ctx.spi.first(AppStateProvider.class).appState().get("edition"));
            assertEquals("A", branch.ctx.spi.first(AppStateProvider.class).appState().get("seed"));
            assertEquals(args("maxReturn", "500.00"), branch.ctx.call("lending.limits", args()));
            @SuppressWarnings("unchecked")
            Map<String, Object> decision = (Map<String, Object>) branch.ctx.call("lending.access.check",
                    args("staff", "100", "permission", "RETURN", "amount", "20.01"));
            assertEquals(Boolean.FALSE, decision.get("allowed"));
            assertEquals("20.00", decision.get("limit"));
        } finally {
            branch.close();
        }
    }

    /**
     * The {@code lending} section of {@code app.state} reports the port the desk's database listener is bound to
     * ({@code dbPort}), which is how a test that let the desk pick a free port ({@code lending.dbPort=0}) learns it,
     * and {@code null} for a desk without a listener. The field is reported, not a fingerprint component.
     */
    @Test
    void appStateReportsTheDatabaseListenersPort() throws Exception {
        LendingFixture serving = LendingFixture.startServing("Module-Reader-5150");
        try {
            Map<String, Object> state = serving.ctx.spi.first(AppStateProvider.class).appState();
            int port = serving.app.dbServer().port();
            assertTrue(port > 0, "the desk picked a port: " + port);
            assertEquals(Long.valueOf(port), state.get("dbPort"), String.valueOf(state));
            assertFalse(String.valueOf(state).contains("Module-Reader-5150"), "never the password");
        } finally {
            serving.close();
        }
        LendingFixture plain = LendingFixture.start();
        try {
            Map<String, Object> state = plain.ctx.spi.first(AppStateProvider.class).appState();
            assertTrue(state.containsKey("dbPort"), String.valueOf(state));
            assertNull(state.get("dbPort"), "no listener");
        } finally {
            plain.close();
        }
    }

    /**
     * The fields of the {@code lending} section of {@code app.state}, in order: {@code running}, the
     * {@code lending.state} fields, the desk's own, Phase 2's {@code dbPort} and Phase 3's {@code today}.
     */
    @Test
    void appStateHasExactlyTheContractsFields() throws Exception {
        LendingFixture lending = LendingFixture.start();
        try {
            Map<String, Object> state = lending.ctx.spi.first(AppStateProvider.class).appState();
            assertEquals(Arrays.asList("running", "name", "prompt", "validInputs", "staff", "returnMode", "locked",
                    "awaitingInput", "modalPrompt", "loanOpen", "lastLoanId", "slips", "engineListeners", "seed",
                    "edition", "dbPort", "today"), new java.util.ArrayList<String>(state.keySet()));
        } finally {
            lending.close();
        }
    }

    /**
     * {@code today} is the desk's business date, from the engine's clock and in the clock's zone (Phase 3 contract
     * 10.2): the date the desk evaluates its waiver rules on. On a fixed clock it is that clock's date; on a clock that
     * moves on it moves with it. It is reported, not a fingerprint component.
     */
    @Test
    void appStateReportsTheDesksBusinessDate() throws Exception {
        LendingFixture system = LendingFixture.start();
        try {
            Clock clock = system.app.engine().clock();
            LocalDate before = LocalDate.now(clock);
            Object today = system.ctx.spi.first(AppStateProvider.class).appState().get("today");
            LocalDate after = LocalDate.now(clock);
            assertTrue(before.toString().equals(today) || after.toString().equals(today), today + " is the clock's "
                    + "date (" + before + ")");
        } finally {
            system.close();
        }
        Instant evening = Instant.parse("2031-02-28T20:00:00Z");
        LendingFixture fixed = LendingFixture.start(Seed.B, Clock.fixed(evening, ZoneOffset.ofHours(10)));
        try {
            assertEquals("2031-03-01", fixed.ctx.spi.first(AppStateProvider.class).appState().get("today"),
                    "the date in the clock's zone, ten hours ahead of UTC");
        } finally {
            fixed.close();
        }
        TestClock moving = new TestClock(evening, ZoneOffset.UTC);
        LendingFixture lending = LendingFixture.start(Seed.A, moving);
        try {
            AppStateProvider provider = lending.ctx.spi.first(AppStateProvider.class);
            assertEquals("2031-02-28", provider.appState().get("today"));
            moving.advanceDays(1);
            assertEquals("2031-03-01", provider.appState().get("today"), "the next day, without a restart");
            moving.advanceDays(366);
            assertEquals("2032-03-01", provider.appState().get("today"));
        } finally {
            lending.close();
        }
    }

    private static java.util.List<String> names(FakeAgentContext ctx) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (ai.skillroy.testjava.agent.api.PrimitiveSpec spec : ctx.primitives.list()) {
            names.add(spec.name());
        }
        return names;
    }

    private static Thread thread(String name) {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Thread[] threads = new Thread[root.activeCount() + 16];
        int n = root.enumerate(threads, true);
        for (int i = 0; i < n; i++) {
            if (name.equals(threads[i].getName())) {
                return threads[i];
            }
        }
        return null;
    }
}
