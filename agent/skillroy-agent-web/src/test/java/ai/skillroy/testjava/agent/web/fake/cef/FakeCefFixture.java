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

package ai.skillroy.testjava.agent.web.fake.cef;

import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.api.spi.web.WebRendering;
import ai.skillroy.testjava.agent.web.jcef.JcefAdapter;
import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;
import ai.skillroy.testjava.agent.web.kit.RecordingClassLoader;
import ai.skillroy.testjava.agent.web.kit.WebAdapterFixture;
import ai.skillroy.testjava.protocol.CapabilityState;

import java.awt.EventQueue;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The contract kit's fixture over the fake JCEF shapes (BS8's behaviour: answers on the EDT, a hidden browser created
 * with {@code createImmediately()} whose first DevTools client is closed and whose viewport is 1 x 1, a detached
 * browser without a view), driven by the {@code jcef} adapter with the descriptor {@value #DESCRIPTOR}. The real
 * JCEF run of the kit happens in the web host sample under the profile {@code jcef}.
 */
public final class FakeCefFixture implements WebAdapterFixture {

    /** The fake's descriptor: {@code jcef.json} with the fake's class names, without the raw channel's roles. */
    public static final String DESCRIPTOR = "ai/skillroy/testjava/agent/web/fake/fake-jcef.json";
    /** The same with the raw channel's roles ({@code jcef.json} as it is). */
    public static final String RAW_DESCRIPTOR = "ai/skillroy/testjava/agent/web/fake/fake-jcef-raw.json";
    static final String PACKAGE = "ai.skillroy.testjava.agent.web.fake.cef";
    static final String BLANK = "<!doctype html><html><head><title>blank</title></head><body></body></html>";

    final FakeCefApp app;
    final FakeCefClient client;
    final FakeCefBrowserBase visible;
    final FakeCefBrowserBase hidden;
    final FakeCefBrowserBase detached;
    private final String descriptor;
    private RecordingClassLoader host;

    public FakeCefFixture() throws Exception {
        this(DESCRIPTOR);
    }

    /** A fixture whose adapters read the given descriptor ({@link #DESCRIPTOR} or {@link #RAW_DESCRIPTOR}). */
    public FakeCefFixture(String descriptor) throws Exception {
        this.descriptor = descriptor;
        FakeCefSettings settings = new FakeCefSettings();
        settings.windowless_rendering_enabled = true;
        app = FakeCefApp.startup(settings);
        client = app.createClient();
        visible = (FakeCefBrowserBase) client.createBrowser("about:blank", true);
        visible.show(800, 600);
        hidden = (FakeCefBrowserBase) client.createBrowser("about:blank", true);
        hidden.createImmediately();
        detached = (FakeCefBrowserBase) client.createBrowser("about:blank", true);
        detached.createImmediately();
        for (FakeCefBrowserBase b : new FakeCefBrowserBase[] {visible, hidden, detached}) {
            b.loadHtml(BLANK).get(10, TimeUnit.SECONDS);
        }
    }

    @Override
    public WebEngineAdapter newAdapter() {
        return new JcefAdapter(descriptor, null);
    }

    @Override
    public ClassLoader hostLoader() {
        return FakeCefFixture.class.getClassLoader();
    }

    @Override
    public Collection<?> seeds(Which which) {
        return Collections.singletonList(browserObject(which));
    }

    @Override
    public Object browserObject(Which which) {
        return which == Which.VISIBLE ? visible : which == Which.HIDDEN ? hidden : detached;
    }

    @Override
    public void load(WebBrowser browser, String html) throws Exception {
        ((FakeCefBrowserBase) browser.vendorObject()).loadHtml(html).get(10, TimeUnit.SECONDS);
    }

    @Override
    public Object newDisposableBrowser() throws Exception {
        FakeCefBrowserBase b = (FakeCefBrowserBase) client.createBrowser("about:blank", true);
        b.show(400, 300);
        b.loadHtml(BLANK).get(10, TimeUnit.SECONDS);
        return b;
    }

    @Override
    public void close(WebBrowser browser) {
        ((FakeCefBrowser) browser.vendorObject()).close(true);
    }

    @Override
    public AppListener installAppListener(WebBrowser browser) {
        final AtomicInteger received = new AtomicInteger();
        FakeDevToolsClient c = ((FakeCefBrowser) browser.vendorObject()).getDevToolsClient();
        c.addEventListener(new FakeDevToolsClient.EventListener() {
            @Override
            public void onEvent(String eventName, String messageAsJson) {
                received.incrementAndGet();
            }
        });
        return new AppListener() {
            @Override
            public int received() {
                return received.get();
            }
        };
    }

    @Override
    public void postToGuardedThread(Runnable task) {
        EventQueue.invokeLater(task);
    }

    @Override
    public WebRendering expectedRendering(Which which) {
        return WebRendering.OFFSCREEN_HEAVYWEIGHT;
    }

    @Override
    public CapabilityState expectedEnumerationState() {
        return CapabilityState.OK;
    }

    @Override
    public Integer debugPort() {
        return null;
    }

    @Override
    public boolean checksBitmapColours() {
        return true;
    }

    /** The built-in adapter budgets the console (P2-L4-S1, kit item 14). */
    @Override
    public boolean budgetsConsole() {
        return true;
    }

    @Override
    public RecordingClassLoader pristineLoader() {
        ClassLoader test = FakeCefFixture.class.getClassLoader();
        return new RecordingClassLoader(test, test, PACKAGE + ".");
    }

    /** A host that loaded and initialised the fake's gate class ({@code FakeCefApp}) and nothing else of it. */
    @Override
    public synchronized RecordingClassLoader recordingHostLoader() {
        if (host == null) {
            ClassLoader test = FakeCefFixture.class.getClassLoader();
            RecordingClassLoader rec = new RecordingClassLoader(test, test, PACKAGE + ".");
            try {
                Class.forName(PACKAGE + ".FakeCefApp", true, rec);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            host = rec;
        }
        return host;
    }

    @Override
    public Set<String> initialisedClasses(ClassLoader loader) {
        return ClassInitialisations.of(loader);
    }

    /** The fake's own package (its recording host defines only that package's classes). */
    @Override
    public Set<String> technologyPackages() {
        return java.util.Collections.singleton(PACKAGE);
    }

    /** The listeners of the browser's current DevTools client and the observers of its raw channel. */
    @Override
    public int vendorListenerCount(WebBrowser browser) {
        FakeCefBrowserBase b = (FakeCefBrowserBase) browser.vendorObject();
        FakeDevToolsClient c = b.getDevToolsClient();
        return (c == null ? 0 : c.listenerCount()) + b.observerCount();
    }
}
