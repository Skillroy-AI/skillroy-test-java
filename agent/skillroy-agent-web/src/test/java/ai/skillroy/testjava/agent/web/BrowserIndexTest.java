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

import ai.skillroy.testjava.agent.api.spi.CaptureMask;
import ai.skillroy.testjava.agent.api.spi.web.BrowserMask;
import ai.skillroy.testjava.agent.api.spi.web.BrowserRegistryAdapter;
import ai.skillroy.testjava.agent.api.spi.web.BrowserSeed;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The browser index of phase4-contracts.md section 4.1 over a scripted adapter: seeds from registered
 * {@code BrowserRegistryAdapter}s (unwrapped, tagged; one that throws is logged and skipped; null collections and
 * elements tolerated) and from the Swing tree through a fake walk, refs of kind {@code browser} stable across
 * rebuilds, component refs of kind {@code component}, {@code STALE_HANDLE} for a browser that left the index,
 * {@code web.browsers.changed} on a difference, {@code web.closed} once for a closed browser.
 */
class BrowserIndexTest {

    @Test
    void registrySeedsAreUnwrappedTaggedAndAFailingRegistryIsSkipped() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        ScriptedBrowser back = a.add(new ScriptedBrowser("scripted"));
        back.seedOnly = true;
        try (Harness h = new Harness(a).install().ready()) {
            final Object token = back.seedToken;
            h.ctx.spi.register(BrowserRegistryAdapter.class, new BrowserRegistryAdapter() {
                @Override
                public Collection<BrowserSeed> seeds() {
                    throw new IllegalStateException("registry boom");
                }
            });
            h.ctx.spi.register(BrowserRegistryAdapter.class, new BrowserRegistryAdapter() {
                @Override
                public Collection<BrowserSeed> seeds() {
                    return null;
                }
            });
            h.ctx.spi.register(BrowserRegistryAdapter.class, new BrowserRegistryAdapter() {
                @Override
                public Collection<BrowserSeed> seeds() {
                    return Arrays.asList(null, BrowserSeed.of(back.vendorObject(), "back"), BrowserSeed.of(token));
                }
            });
            Map<String, Object> listed = h.ctx.map("web.browsers", Harness.args());
            List<?> browsers = (List<?>) listed.get("browsers");
            assertEquals(1, browsers.size(), String.valueOf(listed));
            assertEquals("back", ((Map<?, ?>) browsers.get(0)).get("tag"));
            List<Object> seen = a.seedsSeen.get(a.seedsSeen.size() - 1);
            assertTrue(containsSame(seen, back.vendorObject()) && containsSame(seen, token), "seeds unwrapped");
            for (Object s : seen) {
                assertFalse(s instanceof BrowserSeed, "the adapter gets the objects, not the wrappers");
            }
            h.ctx.call("web.browsers", Harness.args());
            int failures = 0;
            for (String line : h.ctx.log.all()) {
                if (line.contains("registry boom")) {
                    failures++;
                }
            }
            assertEquals(1, failures, "a failing registry is logged once and skipped: " + h.ctx.log.all());
        }
    }

    @Test
    void swingSeedsComeFromTheWalkAndComponentsGetComponentRefsAndVisibility() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        ScriptedBrowser shown = a.add(new ScriptedBrowser("scripted"));
        ScriptedBrowser hidden = a.add(new ScriptedBrowser("scripted"));
        JPanel shownView = new JPanel();
        JPanel hiddenView = new JPanel();
        shown.component = shownView;
        hidden.component = hiddenView;
        try (Harness h = new Harness(a).install()) {
            h.walk.components.addAll(Arrays.<Object>asList(shownView, hiddenView));
            h.walk.showing.add(shownView);
            h.ready();
            List<?> browsers = (List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers");
            Map<?, ?> first = (Map<?, ?>) browsers.get(0);
            Map<?, ?> second = (Map<?, ?>) browsers.get(1);
            assertEquals(Boolean.TRUE, first.get("visibleInSwing"));
            assertEquals(Boolean.FALSE, second.get("visibleInSwing"));
            assertEquals(h.ctx.handles.handleFor(shownView, "component"), first.get("component"),
                    "the component's ref equals a swing.snapshot ref");
            assertEquals("component", h.ctx.handles.kindOf((String) first.get("component")));
            assertEquals("browser", h.ctx.handles.kindOf((String) first.get("ref")));
            List<Object> seen = a.seedsSeen.get(a.seedsSeen.size() - 1);
            assertTrue(containsSame(seen, shownView) && containsSame(seen, hiddenView), "Swing seeds handed over");
        }
    }

    @Test
    void refsAreStableAndABrowserThatLeftAnswersStaleHandleAfterTheChangeEvents() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        ScriptedBrowser one = a.add(new ScriptedBrowser("scripted"));
        ScriptedBrowser two = a.add(new ScriptedBrowser("scripted"));
        try (Harness h = new Harness(a).install().ready()) {
            List<Event> changed = h.ctx.events.topic("web.browsers.changed");
            assertEquals(1, changed.size(), "the readiness rebuild found two browsers");
            String r1 = ref(h, one);
            String r2 = ref(h, two);
            assertEquals(Arrays.asList(r1, r2), changed.get(0).payload().get("added"));
            assertEquals(Collections.emptyList(), changed.get(0).payload().get("removed"));
            h.ctx.call("web.browsers", Harness.args());
            h.ctx.call("web.browsers", Harness.args());
            assertEquals(1, h.ctx.events.topic("web.browsers.changed").size(), "no change, no event");
            assertEquals(r1, ref(h, one), "the ref is stable for the life of the vendor object");
            two.removed = true;
            h.ctx.call("web.browsers", Harness.args());
            List<Event> after = h.ctx.events.topic("web.browsers.changed");
            assertEquals(2, after.size());
            assertEquals(Collections.singletonList(r2), after.get(1).payload().get("removed"));
            RpcException e = h.ctx.error("web.dom.text", Harness.args("browser", r2));
            assertEquals(ErrorCodes.STALE_HANDLE, e.error().code(), e.getMessage());
            assertEquals(r2, e.error().data().get("handle"));
            two.removed = false;
            h.ctx.call("web.browsers", Harness.args());
            assertNotEquals(r2, ref(h, two), "a browser that comes back gets a new ref");
        }
    }

    @Test
    void aClosedBrowserIsReportedOnceAndListedOnlyWithIncludeClosed() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        ScriptedBrowser b = a.add(new ScriptedBrowser("scripted"));
        try (Harness h = new Harness(a).install().ready()) {
            String r = ref(h, b);
            b.fire(WebEvent.closed());
            b.closed = true;
            b.fire(WebEvent.closed());
            List<Event> closed = h.ctx.events.topic("web.closed");
            assertEquals(1, closed.size(), "web.closed once");
            assertEquals(r, closed.get(0).payload().get("browser"));
            assertEquals(Collections.emptyList(), h.ctx.map("web.browsers", Harness.args()).get("browsers"));
            List<?> all = (List<?>) h.ctx.map("web.browsers", Harness.args("includeClosed", Boolean.TRUE))
                    .get("browsers");
            assertEquals(1, all.size());
            assertEquals(Boolean.TRUE, ((Map<?, ?>) all.get(0)).get("closed"));
            RpcException e = h.ctx.error("web.dom.text", Harness.args("browser", r));
            assertEquals(ErrorCodes.STALE_HANDLE, e.error().code());
            assertEquals(1, h.ctx.events.topic("web.closed").size());
        }
    }

    @Test
    void anAdapterThatFailsToEnumerateKeepsItsBrowsersAndTheWalkFailureIsLogged() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        ScriptedBrowser b = a.add(new ScriptedBrowser("scripted"));
        try (Harness h = new Harness(a).install().ready()) {
            String r = ref(h, b);
            a.failEnumeration = new IllegalStateException("enumeration boom");
            h.walk.failure = new IllegalStateException("walk boom");
            List<?> browsers = (List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers");
            assertEquals(r, ((Map<?, ?>) browsers.get(0)).get("ref"), "kept from the previous rebuild");
            assertTrue(h.ctx.log.all().toString().contains("enumeration boom"));
            assertTrue(h.ctx.log.all().toString().contains("walk boom"));
        }
    }

    /** The ref the index gave the browser. */
    /**
     * Phase 8 (phase8-contracts.md 5.2, D547): every rebuild sets each entry's {@code masked} flag; the components are
     * read once per rebuild, all together, and only while a mask is registered; a view-less browser costs no read; a
     * read that fails masks every component-bearing entry.
     */
    @Test
    void everyRebuildSetsTheMaskedFlagWithOneReadForAllComponentsOnlyWhileMasksAreRegistered() throws Exception {
        ScriptedAdapter a = new ScriptedAdapter("scripted");
        ScriptedBrowser named = a.add(new ScriptedBrowser("scripted"));
        ScriptedBrowser plain = a.add(new ScriptedBrowser("scripted"));
        ScriptedBrowser viewless = a.add(new ScriptedBrowser("scripted"));
        final JPanel namedView = new JPanel();
        JPanel plainView = new JPanel();
        named.component = namedView;
        plain.component = plainView;
        try (Harness h = new Harness(a).install()) {
            h.walk.components.addAll(Arrays.<Object>asList(namedView, plainView));
            h.ready();
            BrowserIndex index = h.module.indexForTest();
            index.rebuild(BrowserIndex.BUDGET_MS);
            assertEquals(0, h.walk.maskedReads.get(), "no read while nothing is registered");
            for (Entry e : index.snapshot().entries) {
                assertFalse(e.masked, e.toString());
            }

            AutoCloseable mask = h.ctx.spi.register(CaptureMask.class, new CaptureMask() {
                @Override
                public boolean masks(java.awt.Component c) {
                    return c == namedView;
                }
            });
            index.rebuild(BrowserIndex.BUDGET_MS);
            assertEquals(1, h.walk.maskedReads.get(), "one read for the two components");
            assertTrue(index.entryFor(named.vendorObject()).masked, "named by the CaptureMask");
            assertFalse(index.entryFor(plain.vendorObject()).masked, "a component no mask names");
            assertTrue(index.entryFor(viewless.vendorObject()).masked, "no view while a mask is registered");
            index.rebuild(BrowserIndex.BUDGET_MS);
            assertEquals(2, h.walk.maskedReads.get(), "one read per rebuild");

            h.walk.maskedFailure = new IllegalStateException("EDT busy");
            index.rebuild(BrowserIndex.BUDGET_MS);
            assertTrue(index.entryFor(plain.vendorObject()).masked, "a failed read masks (the safe side)");
            h.walk.maskedFailure = null;

            mask.close();
            h.ctx.spi.register(BrowserMask.class, new BrowserMask() {
                @Override
                public Boolean masks(String tag, Object vendorObject, boolean hasView) {
                    return hasView ? null : Boolean.FALSE;
                }
            });
            int reads = h.walk.maskedReads.get();
            index.rebuild(BrowserIndex.BUDGET_MS);
            assertFalse(index.entryFor(viewless.vendorObject()).masked, "released by a BrowserMask");
            assertFalse(index.entryFor(named.vendorObject()).masked, "no CaptureMask names it any more");
            assertEquals(reads + 1, h.walk.maskedReads.get(), "a BrowserMask alone also asks the components");
        }
    }

    static String ref(Harness h, ScriptedBrowser b) throws Exception {
        for (Object o : (List<?>) h.ctx.map("web.browsers", Harness.args("includeClosed", Boolean.TRUE))
                .get("browsers")) {
            Map<?, ?> m = (Map<?, ?>) o;
            Object vendor = h.ctx.handles.resolve((String) m.get("ref"));
            if (vendor == b.vendorObject()) {
                return (String) m.get("ref");
            }
        }
        throw new AssertionError("browser not indexed");
    }

    private static boolean containsSame(List<Object> list, Object o) {
        for (Object x : list) {
            if (x == o) {
                return true;
            }
        }
        return false;
    }

    static List<String> refs(List<?> browsers) {
        List<String> out = new ArrayList<String>();
        for (Object o : browsers) {
            out.add((String) ((Map<?, ?>) o).get("ref"));
        }
        return out;
    }
}
