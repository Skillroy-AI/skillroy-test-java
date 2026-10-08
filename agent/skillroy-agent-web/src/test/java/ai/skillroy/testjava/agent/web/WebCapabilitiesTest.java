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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.web.CdpEndpoint;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebCapability;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.api.spi.web.WebInitScript;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capabilities of phase4-contracts.md section 4.4 and the probe cycle of section 4.1 over scripted adapters:
 * readiness probes and declares, an UNAVAILABLE adapter stays out of the index until a reprobe, a late adapter and a
 * {@code window.opened} event are picked up (only the adapters whose technology may have arrived are probed again,
 * cheaply, and a full cycle runs only on a change), the per-browser flags decide the generic capabilities, a rebuild
 * outside a cycle improves them, and uninstall withdraws everything, also against a re-probe in flight.
 */
class WebCapabilitiesTest {

    @Test
    void readinessProbesTheAdaptersAndEveryCapabilityTakesItsStateAndStrategy() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        a.add(new ScriptedBrowser("scripted"));
        a.endpoints.add(new CdpEndpoint("127.0.0.1", 9222, "scripted"));
        try (Harness h = new Harness(a).install()) {
            assertEquals(0, a.probes.get(), "install probes nothing");
            h.ready();
            assertTrue(a.probes.get() >= 1);
            assertCap(h, "web.adapter.scripted", CapabilityState.OK, null, "scripted 1");
            assertCap(h, "web.browsers", CapabilityState.OK, null, null);
            assertCap(h, "web.evaluate", CapabilityState.OK, null, "scripted");
            assertCap(h, "web.events", CapabilityState.OK, null, "scripted");
            assertCap(h, "web.initScript", CapabilityState.OK, null, "DOCUMENT_START");
            assertCap(h, "web.resize", CapabilityState.OK, null, "scripted");
            assertCap(h, "web.bitmap", CapabilityState.OK, null, "scripted");
            assertCap(h, "web.cdp", CapabilityState.OK, null, "127.0.0.1:9222");
            assertCap(h, "web.hittest", CapabilityState.UNAVAILABLE, "reserved for the vision bolt-on", null);
            assertCap(h, "web.pointer", CapabilityState.UNAVAILABLE, "reserved for the vision bolt-on", null);
            assertTrue(h.ctx.capabilities.declared("web.evaluate"), "declared with a probe, for reprobe");
            assertFalse(h.ctx.capabilities.declared("web.hittest"), "reserved capabilities are only set");
        }
    }

    @Test
    void theFlagsOfTheIndexedBrowsersDecideTheGenericCapabilities() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        ScriptedBrowser b = a.add(new ScriptedBrowser("scripted"));
        b.capabilities = EnumSet.of(WebCapability.EVALUATE, WebCapability.EVENTS_LOAD);
        b.timing = WebInitScript.Timing.DOCUMENT_LOADED;
        a.enumeration = ProbeResult.degraded("seeds only: the walk is missing");
        try (Harness h = new Harness(a).install().ready()) {
            assertCap(h, "web.browsers", CapabilityState.DEGRADED, "seeds only: the walk is missing", null);
            assertCap(h, "web.evaluate", CapabilityState.OK, null, "scripted");
            assertCap(h, "web.initScript", CapabilityState.DEGRADED, "after document load", null);
            assertCap(h, "web.resize", CapabilityState.UNAVAILABLE, "no indexed browser has RESIZE", null);
            assertCap(h, "web.bitmap", CapabilityState.UNAVAILABLE, "no indexed browser has BITMAP", null);
            assertCap(h, "web.cdp", CapabilityState.UNAVAILABLE, "no remote-debugging endpoint configured", null);
        }
    }

    @Test
    void anAdapterWithoutBrowsersAndAnAbsentTechnologyGiveTheirReasons() throws Exception {
        ScriptedAdapter present = new ScriptedAdapter("present");
        try (Harness h = new Harness(present).install().ready()) {
            assertCap(h, "web.browsers", CapabilityState.OK, null, null);
            assertCap(h, "web.evaluate", CapabilityState.UNAVAILABLE, "no browser found", null);
        }
        ScriptedAdapter old = new ScriptedAdapter("old");
        old.probe = ProbeResult.unavailable("JCEF older than 2024-01: no in-process result channel");
        ScriptedAdapter absent = new ScriptedAdapter("absent");
        absent.probe = ProbeResult.unavailable("not present");
        try (Harness h = new Harness(absent, old).install().ready()) {
            assertCap(h, "web.adapter.old", CapabilityState.UNAVAILABLE,
                    "JCEF older than 2024-01: no in-process result channel", null);
            assertCap(h, "web.browsers", CapabilityState.UNAVAILABLE, "no embedded browser technology found", null);
            assertCap(h, "web.evaluate", CapabilityState.UNAVAILABLE,
                    "JCEF older than 2024-01: no in-process result channel", null);
            assertEquals(0, old.seedsSeen.size(), "an UNAVAILABLE adapter is kept out of the index");
            RpcException e = h.ctx.error("web.browsers", Harness.args());
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, e.error().code());
        }
    }

    @Test
    void anUnavailableAdapterStaysOutOfTheIndexUntilTheReprobe() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("late");
        a.add(new ScriptedBrowser("late"));
        a.probe = ProbeResult.unavailable("present but not loaded by the application yet");
        try (Harness h = new Harness(a).install().ready()) {
            assertCap(h, "web.adapter.late", CapabilityState.UNAVAILABLE,
                    "present but not loaded by the application yet", null);
            assertEquals(0, a.seedsSeen.size());
            a.probe = ProbeResult.ok("late 2");
            h.reprobe();
            assertCap(h, "web.adapter.late", CapabilityState.OK, null, "late 2");
            assertCap(h, "web.evaluate", CapabilityState.OK, null, "late");
            assertTrue(a.seedsSeen.size() >= 1, "the reprobe rebuilt the index with the adapter");
        }
    }

    @Test
    void anAdapterRegisteredLaterIsPickedUpByTheReprobe() throws Exception {
        ScriptedAdapter builtIn = new ScriptedAdapter("builtin");
        try (Harness h = new Harness(builtIn).install().ready()) {
            assertNull(h.cap("web.adapter.pack"));
            ScriptedAdapter pack = new ScriptedAdapter("pack");
            pack.add(new ScriptedBrowser("pack"));
            h.ctx.spi.register(WebEngineAdapter.class, pack);
            ScriptedAdapter clash = new ScriptedAdapter("builtin");
            h.ctx.spi.register(WebEngineAdapter.class, clash);
            ScriptedAdapter invalid = new ScriptedAdapter("Not-Valid");
            h.ctx.spi.register(WebEngineAdapter.class, invalid);
            h.reprobe();
            awaitCap(h, "web.adapter.pack", CapabilityState.OK);
            assertCap(h, "web.adapter.pack", CapabilityState.OK, null, "pack 1");
            awaitCap(h, "web.evaluate", CapabilityState.OK);
            assertEquals(0, clash.probes.get(), "a second adapter with an existing id is skipped");
            assertEquals(0, invalid.probes.get(), "an invalid id is skipped");
            assertNull(h.cap("web.adapter.Not-Valid"));
        }
    }

    @Test
    void aWindowOpenedEventReprobesAnUnavailableAdapter() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("late");
        a.add(new ScriptedBrowser("late"));
        a.probe = ProbeResult.unavailable("present but not loaded by the application yet");
        try (Harness h = new Harness(a).install().ready()) {
            h.ctx.threads.inlinePosts = true;
            int before = a.probes.get();
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "h1"));
            assertTrue(a.probes.get() > before, "the event made the module probe again");
            assertCap(h, "web.adapter.late", CapabilityState.UNAVAILABLE,
                    "present but not loaded by the application yet", null);
            a.probe = ProbeResult.ok("late 3");
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "h2"));
            assertCap(h, "web.adapter.late", CapabilityState.OK, null, "late 3");
            assertCap(h, "web.evaluate", CapabilityState.OK, null, "late");
            int after = a.probes.get();
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "h3"));
            assertEquals(after, a.probes.get(), "no re-probe once every adapter is present");
            assertTrue(h.ctx.capabilities.declared("web.evaluate"), "the declared probe stays for reprobe");
        }
    }

    @Test
    void windowOpenedProbesNoAdapterWhoseTechnologyIsNotPresentAndReadsNoSwingTree() throws Exception {
        ScriptedAdapter present = new ScriptedAdapter("present");
        ScriptedBrowser b = present.add(new ScriptedBrowser("present"));
        JPanel view = new JPanel();
        b.component = view;
        ScriptedAdapter absent = new ScriptedAdapter("absent");
        absent.probe = ProbeResult.unavailable("not present");
        try (Harness h = new Harness(present, absent).install()) {
            h.walk.components.add(view);
            h.walk.showing.add(view);
            h.ready();
            h.ctx.threads.inlinePosts = true;
            int presentProbes = present.probes.get();
            int absentProbes = absent.probes.get();
            int walks = h.walk.componentReads.get();
            int showing = h.walk.showingReads.get();
            int enumerations = present.seedsSeen.size();
            for (int i = 0; i < 10; i++) {
                h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w" + i));
            }
            assertEquals(presentProbes, present.probes.get(), "a present adapter is not probed again");
            assertEquals(absentProbes, absent.probes.get(), "an absent technology is probed again only on reprobe");
            assertEquals(walks, h.walk.componentReads.get(), "no Swing tree walk (no EDT read)");
            assertEquals(showing, h.walk.showingReads.get());
            assertEquals(enumerations, present.seedsSeen.size(), "no index rebuild");
        }
        ScriptedAdapter none1 = new ScriptedAdapter("none1");
        none1.probe = ProbeResult.unavailable("not present");
        ScriptedAdapter none2 = new ScriptedAdapter("none2");
        none2.probe = ProbeResult.unavailable("JCEF older than 2024-01: no in-process result channel");
        try (Harness h = new Harness(none1, none2).install().ready()) {
            h.ctx.threads.inlinePosts = true;
            int probes = none1.probes.get() + none2.probes.get();
            for (int i = 0; i < 10; i++) {
                h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w" + i));
            }
            assertEquals(probes, none1.probes.get() + none2.probes.get(),
                    "a target without the technology: no probe at all");
            assertEquals(0, h.walk.componentReads.get());
        }
    }

    @Test
    void aChangedReasonOfANotLoadedAdapterRunsAFullCycle() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("late");
        a.probe = ProbeResult.unavailable("present but not loaded by the application yet");
        ScriptedAdapter other = new ScriptedAdapter("other");
        other.add(new ScriptedBrowser("other"));
        try (Harness h = new Harness(a, other).install().ready()) {
            h.ctx.threads.inlinePosts = true;
            int otherProbes = other.probes.get();
            a.probe = ProbeResult.unavailable("JCEF older than 2024-01: no in-process result channel");
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w1"));
            assertCap(h, "web.adapter.late", CapabilityState.UNAVAILABLE,
                    "JCEF older than 2024-01: no in-process result channel", null);
            assertEquals(otherProbes + 1, other.probes.get(), "the change ran a full cycle");
            int lateProbes = a.probes.get();
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w2"));
            assertEquals(lateProbes, a.probes.get(), "a technology found too old is probed again only on reprobe");
        }
    }

    @Test
    void aRegisteredAdapterThatWasUnavailableAndOneRegisteredLaterAreProbedAgainOnWindowOpened() throws Exception {
        ScriptedAdapter builtIn = new ScriptedAdapter("builtin");
        try (Harness h = new Harness(builtIn).install()) {
            ScriptedAdapter pack = new ScriptedAdapter("pack");
            pack.probe = ProbeResult.unavailable("the pack's engine is not started");
            pack.add(new ScriptedBrowser("pack"));
            h.ctx.spi.register(WebEngineAdapter.class, pack);
            h.ready();
            h.ctx.threads.inlinePosts = true;
            assertCap(h, "web.adapter.pack", CapabilityState.UNAVAILABLE, "the pack's engine is not started", null);
            int before = pack.probes.get();
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w1"));
            assertEquals(before + 1, pack.probes.get(), "a registered UNAVAILABLE adapter is probed again");
            assertEquals(0, pack.seedsSeen.size(), "unchanged: no cycle");
            pack.probe = ProbeResult.ok("pack 2");
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w2"));
            assertCap(h, "web.adapter.pack", CapabilityState.OK, null, "pack 2");
            assertCap(h, "web.evaluate", CapabilityState.OK, null, "pack");
            ScriptedAdapter later = new ScriptedAdapter("later");
            later.add(new ScriptedBrowser("later"));
            h.ctx.spi.register(WebEngineAdapter.class, later);
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w3"));
            assertCap(h, "web.adapter.later", CapabilityState.OK, null, "later 1");
        }
    }

    @Test
    void aReprobeTheCoreDroppedHoldsTheNextOneBackOnlyForAWhile() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("late");
        a.probe = ProbeResult.unavailable("present but not loaded by the application yet");
        try (Harness h = new Harness(a).install().ready()) {
            h.ctx.threads.inlinePosts = true;
            int before = a.probes.get();
            h.ctx.threads.dropPosts.set(1);
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w1"));
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w2"));
            assertEquals(before, a.probes.get(), "dropped, and the next event is coalesced while it counts as queued");
            h.module.reprobeHoldMs(0);
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w3"));
            assertEquals(before + 1, a.probes.get(), "a dropped re-probe does not hold every later one back");
        }
    }

    @Test
    void aCycleInFlightDuringUninstallLeavesNoSubscriptionBehind() throws Exception {
        final Gated b = new Gated(new ScriptedAdapter("b"));
        b.inner.probe = ProbeResult.unavailable("present but not loaded by the application yet");
        ScriptedBrowser nb = b.inner.add(new ScriptedBrowser("b"));
        final Harness h = new Harness(b).install().ready();
        try {
            // The application loads the technology; a window opens; the module's full cycle is enumerating b's
            // browsers when the uninstall starts.
            b.inner.probe = ProbeResult.ok("b 2");
            CountDownLatch release = new CountDownLatch(1);
            b.browsersWaitFor = release;
            h.ctx.events.publish("window.opened", "swing", Harness.args("window", "w1"));
            assertTrue(b.browsersEntered.await(10, TimeUnit.SECONDS), "the cycle is in flight");
            Thread uninstall = new Thread(new Runnable() {
                @Override
                public void run() {
                    h.module.uninstall();
                }
            }, "uninstall");
            uninstall.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (uninstall.getState() != Thread.State.BLOCKED && uninstall.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }
            release.countDown();
            uninstall.join(20000);
            assertFalse(uninstall.isAlive(), "uninstall ended");
            assertEquals(1, b.inner.detaches.get());
            assertEquals(0, nb.listeners.size(), "no subscription of the detached adapter survives the uninstall");
            assertEquals(0, h.module.indexForTest().snapshot().entries.size());
        } finally {
            h.ctx.shutdown();
        }
    }

    @Test
    void aRebuildOutsideACycleImprovesTheCapabilitiesAndFollowsTheEnumeration() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        try (Harness h = new Harness(a).install().ready()) {
            assertCap(h, "web.evaluate", CapabilityState.UNAVAILABLE, "no browser found", null);
            a.add(new ScriptedBrowser("scripted"));
            a.enumeration = ProbeResult.degraded("seeds only: x");
            h.ctx.call("web.browsers", Harness.args());
            assertCap(h, "web.evaluate", CapabilityState.OK, null, "scripted");
            assertCap(h, "web.browsers", CapabilityState.DEGRADED, "seeds only: x", null);
            a.enumeration = ProbeResult.ok();
            h.ctx.call("web.browsers", Harness.args());
            assertCap(h, "web.browsers", CapabilityState.OK, null, null);
        }
    }

    @Test
    void anUnavailableEnumerationOfAPresentAdapterKeepsWebBrowsersCallable() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        a.enumeration = ProbeResult.unavailable("CefApp is TERMINATED");
        try (Harness h = new Harness(a).install().ready()) {
            assertCap(h, "web.browsers", CapabilityState.DEGRADED, "CefApp is TERMINATED", null);
            h.ctx.call("web.browsers", Harness.args());
        }
    }

    @Test
    void uninstallDetachesTheAdaptersWithdrawsTheCapabilitiesAndUnregistersThePrimitives() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        ScriptedBrowser b = a.add(new ScriptedBrowser("scripted"));
        Harness h = new Harness(a).install().ready();
        h.ctx.call("web.browsers", Harness.args());
        assertEquals(1, b.listeners.size(), "subscribed when the browser entered the index");
        h.module.uninstall();
        assertEquals(1, a.detaches.get());
        assertEquals(0, b.listeners.size(), "the subscription is cancelled");
        assertTrue(h.ctx.primitives.list().isEmpty());
        assertFalse(h.ctx.capabilities.declared("web.evaluate"), "declared capabilities withdrawn");
        h.ctx.shutdown();
    }

    private static void assertCap(Harness h, String id, CapabilityState state, String reason, String strategy) {
        Capability c = h.cap(id);
        assertEquals(state, c == null ? null : c.state(), id + ": " + c);
        if (reason != null) {
            assertEquals(reason, c.reason(), id);
        }
        if (strategy != null) {
            assertEquals(strategy, c.strategy(), id);
        }
    }

    /** Delegates to a scripted adapter; its enumeration can be held by a latch (and tells when it was entered). */
    static final class Gated implements WebEngineAdapter {
        final ScriptedAdapter inner;
        final CountDownLatch browsersEntered = new CountDownLatch(1);
        volatile CountDownLatch browsersWaitFor;

        Gated(ScriptedAdapter inner) {
            this.inner = inner;
        }

        @Override
        public String id() {
            return inner.id();
        }

        @Override
        public ProbeResult probe(ClassLoader hostLoader) {
            return inner.probe(hostLoader);
        }

        @Override
        public List<WebBrowser> browsers(Collection<?> seeds) {
            CountDownLatch l = browsersWaitFor;
            if (l != null) {
                browsersEntered.countDown();
                try {
                    l.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return inner.browsers(seeds);
        }

        @Override
        public List<CdpEndpoint> cdpEndpoints(Collection<?> seeds) {
            return inner.cdpEndpoints(seeds);
        }

        @Override
        public ProbeResult enumerationState() {
            return inner.enumerationState();
        }

        @Override
        public void detach() {
            inner.detach();
        }
    }

    /** A declaration made after the first probe round is probed on a worker: wait for it (bounded). */
    private static void awaitCap(Harness h, String id, CapabilityState state) throws InterruptedException {
        long deadline = System.nanoTime() + 10000000000L;
        while (System.nanoTime() < deadline) {
            Capability c = h.cap(id);
            if (c != null && c.state() == state && !h.ctx.capabilities.isPending(id)) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError(id + " never became " + state + ": " + h.cap(id));
    }
}
