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

import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.web.kit.RecordingClassLoader;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module's install (phase4-contracts.md section 4.1; section 12.1 item 3): headless, every capability
 * UNAVAILABLE with the right reasons, the thirteen primitives of the table with the contract's thread, timeout,
 * capability and mutating flag (D51), no AWT and no vendor class requested at install (a recording class loader), no
 * thread started, and nothing done at readiness in a target without an embedded browser.
 */
class WebModuleInstallTest {

    private static final List<String> NAMES = Arrays.asList("web.ariaSnapshot", "web.bitmap", "web.browsers",
            "web.cdp.status", "web.dispatchEvent", "web.dom.click", "web.dom.fill", "web.dom.text", "web.evaluate",
            "web.reload", "web.resize", "web.tap", "web.waitFlag");
    private static final Set<String> MUTATING = new HashSet<String>(Arrays.asList("web.evaluate", "web.dom.click",
            "web.dom.fill", "web.dispatchEvent", "web.tap", "web.resize", "web.reload"));

    @Test
    void theModuleIsFoundByServiceLoaderWithItsIdAndVersion() {
        List<String> ids = new ArrayList<String>();
        for (AgentModule m : ServiceLoader.load(AgentModule.class, WebModule.class.getClassLoader())) {
            ids.add(m.id());
            if (m instanceof WebModule) {
                assertNotEquals("unknown", m.version(), "the filtered version file is read");
                assertFalse(m.version().contains("${"), m.version());
            }
        }
        assertTrue(ids.contains("web"), String.valueOf(ids));
    }

    @Test
    void installsHeadlessWithEveryCapabilityUnavailableAndTheRightReasons() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext();
        WebModule module = new WebModule();
        try {
            module.install(ctx);
            Map<String, String> expected = new LinkedHashMap<String, String>();
            for (String id : new String[] {"web.adapter.jcef", "web.adapter.jxbrowser", "web.browsers",
                    "web.evaluate", "web.events", "web.initScript", "web.resize", "web.bitmap", "web.cdp"}) {
                expected.put(id, "no embedded browser technology found");
            }
            expected.put("web.hittest", "reserved for the vision bolt-on");
            expected.put("web.pointer", "reserved for the vision bolt-on");
            for (Map.Entry<String, String> e : expected.entrySet()) {
                Capability c = ctx.capabilities.get(e.getKey());
                assertEquals(CapabilityState.UNAVAILABLE, c.state(), e.getKey());
                assertEquals(e.getValue(), c.reason(), e.getKey());
            }
            assertEquals(new TreeSet<String>(expected.keySet()), webCapabilities(ctx));
        } finally {
            module.uninstall();
            ctx.shutdown();
        }
    }

    @Test
    void theThirteenPrimitivesHaveTheContractsThreadTimeoutCapabilityAndFlags() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext();
        WebModule module = new WebModule();
        try {
            module.install(ctx);
            List<String> names = new ArrayList<String>();
            for (PrimitiveSpec spec : ctx.primitives.list()) {
                names.add(spec.name());
                assertEquals(ThreadPolicy.WORKER, spec.thread(), spec.name());
                assertEquals(MUTATING.contains(spec.name()), spec.mutating(), spec.name() + " mutating (D51)");
                assertFalse(spec.destructive(), spec.name());
                long timeout = "web.bitmap".equals(spec.name()) ? 20000 : "web.cdp.status".equals(spec.name())
                        ? 5000 : 10000;
                assertEquals(timeout, spec.timeoutMs(), spec.name());
            }
            assertEquals(NAMES, names, "the thirteen primitives of the contract's table, in name order");
            Map<String, String> caps = new LinkedHashMap<String, String>();
            for (PrimitiveSpec spec : ctx.primitives.list()) {
                caps.put(spec.name(), spec.capabilityId());
            }
            assertEquals("web.browsers", caps.get("web.browsers"));
            assertEquals("web.browsers", caps.get("web.cdp.status"));
            assertEquals("web.browsers", caps.get("web.reload"));
            assertEquals("web.events", caps.get("web.tap"));
            assertEquals("web.resize", caps.get("web.resize"));
            assertEquals("web.bitmap", caps.get("web.bitmap"));
            for (String n : new String[] {"web.evaluate", "web.ariaSnapshot", "web.dom.text", "web.dom.click",
                    "web.dom.fill", "web.dispatchEvent", "web.waitFlag"}) {
                assertEquals("web.evaluate", caps.get(n), n);
            }
        } finally {
            module.uninstall();
            ctx.shutdown();
        }
    }

    @Test
    void installRequestsNoAwtAndNoVendorClassAndStartsNoThread() throws Exception {
        URL main = WebModule.class.getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader mainOnly = new URLClassLoader(new URL[] {main}, null);
        RecordingClassLoader rec = new RecordingClassLoader(mainOnly, WebModuleInstallTest.class.getClassLoader(),
                "ai.skillroy.testjava.agent.web.");
        Set<Thread> threadsBefore = webThreads();
        FakeAgentContext ctx = new FakeAgentContext();
        AgentModule module = (AgentModule) rec.loadClass(WebModule.class.getName()).getConstructor().newInstance();
        try {
            module.install(ctx);
            assertEquals(13, ctx.primitives.list().size());
            List<String> forbidden = new ArrayList<String>();
            for (String name : rec.requested()) {
                if (name.startsWith("java.awt.") || name.startsWith("javax.swing.") || name.startsWith("sun.awt.")
                        || name.startsWith("sun.java2d.") || name.startsWith("javax.imageio.")
                        || name.startsWith("org.cef.") || name.startsWith("com.teamdev.")) {
                    forbidden.add(name);
                }
            }
            assertEquals(new ArrayList<String>(), forbidden, "install requested AWT or vendor classes");
            assertFalse(rec.defined().isEmpty(), "the module's classes came from the recording loader");
            assertEquals(new ArrayList<String>(), started(threadsBefore), "install starts no thread");
        } finally {
            module.uninstall();
            ctx.shutdown();
            mainOnly.close();
        }
    }

    @Test
    void readinessWithoutAnEmbeddedBrowserTouchesNeitherTheEdtNorAThread() throws Exception {
        Set<Thread> threadsBefore = webThreads();
        FakeAgentContext ctx = new FakeAgentContext();
        WebModule module = new WebModule();
        try {
            module.install(ctx);
            module.onTargetReady(ctx);
            ctx.probeAll();
            assertEquals(CapabilityState.UNAVAILABLE, ctx.capabilities.get("web.adapter.jcef").state());
            assertEquals("not present", ctx.capabilities.get("web.adapter.jcef").reason());
            assertEquals("not present", ctx.capabilities.get("web.adapter.jxbrowser").reason());
            for (String id : new String[] {"web.browsers", "web.evaluate", "web.events", "web.initScript",
                    "web.resize", "web.bitmap"}) {
                assertEquals("no embedded browser technology found", ctx.capabilities.get(id).reason(), id);
            }
            assertEquals("no remote-debugging endpoint configured", ctx.capabilities.get("web.cdp").reason());
            assertEquals("reserved for the vision bolt-on", ctx.capabilities.get("web.hittest").reason());
            assertEquals(0, ctx.threads.edtCalls.get(), "no EDT call in a target without a browser");
            assertEquals(new ArrayList<String>(), started(threadsBefore), "no thread of the module");
        } finally {
            module.uninstall();
            ctx.shutdown();
        }
    }

    private static Set<String> webCapabilities(FakeAgentContext ctx) {
        Set<String> out = new TreeSet<String>();
        for (String id : ctx.capabilities.snapshot().keySet()) {
            if (id.startsWith("web.")) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * The live threads whose names say they are the module's, by identity (names repeat: every adapter numbers its
     * threads from 1, and the tap poller has one name), so that a thread of an earlier test class that ends meanwhile
     * is not taken for a change and a new thread with an old name is not missed.
     */
    static Set<Thread> webThreads() {
        Set<Thread> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Thread, Boolean>());
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("skillroy-web")) {
                out.add(t);
            }
        }
        return out;
    }

    /** The names of the module's live threads that were not alive in {@code before}. */
    static List<String> started(Set<Thread> before) {
        List<String> out = new ArrayList<String>();
        for (Thread t : webThreads()) {
            if (!before.contains(t)) {
                out.add(t.getName());
            }
        }
        return out;
    }
}
