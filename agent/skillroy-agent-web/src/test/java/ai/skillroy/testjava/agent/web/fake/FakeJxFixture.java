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

package ai.skillroy.testjava.agent.web.fake;

import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.api.spi.web.WebRendering;
import ai.skillroy.testjava.agent.web.jxbrowser.JxBrowserAdapter;
import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;
import ai.skillroy.testjava.agent.web.kit.RecordingClassLoader;
import ai.skillroy.testjava.agent.web.kit.WebAdapterFixture;
import ai.skillroy.testjava.protocol.CapabilityState;

import java.awt.EventQueue;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The contract kit's fixture over the neutral fake of JxBrowser: an engine with a visible browser (a view), a hidden
 * one (a view, not showing) and one without a view (reached through the engine as the seed), driven by the
 * {@code jxbrowser} adapter with the descriptor {@value #DESCRIPTOR}. Proves the adapter's mechanics, not
 * compatibility with the product.
 */
public final class FakeJxFixture implements WebAdapterFixture {

    /** The fake's descriptor: {@code jxbrowser.json} with the fake's class names. */
    public static final String DESCRIPTOR = "ai/skillroy/testjava/agent/web/fake/fake-jxbrowser.json";
    static final String PACKAGE = "ai.skillroy.testjava.agent.web.fake";
    static final String BLANK = "<!doctype html><html><head><title>blank</title></head><body></body></html>";

    final FakeEngine engine;
    final FakeBrowser visible;
    final FakeBrowser hidden;
    final FakeBrowser viewless;
    final FakeView visibleView;
    final FakeView hiddenView;
    private final boolean chain;
    private RecordingClassLoader host;

    public FakeJxFixture(boolean chain) throws Exception {
        this.chain = chain;
        engine = FakeEngine.newInstance(null, Paths.get("fake-user-data"), FakeRenderingMode.OFF_SCREEN);
        visible = engine.newBrowser();
        hidden = engine.newBrowser();
        viewless = engine.newBrowser();
        visibleView = new FakeView(visible);
        hiddenView = new FakeView(hidden);
        visible.loadAndWait(BLANK);
        hidden.loadAndWait(BLANK);
        viewless.loadAndWait(BLANK);
    }

    @Override
    public WebEngineAdapter newAdapter() {
        return new JxBrowserAdapter(DESCRIPTOR, null, new JxBrowserAdapter.Options() {
            @Override
            public boolean chainInjectJs() {
                return chain;
            }
        });
    }

    @Override
    public ClassLoader hostLoader() {
        return FakeJxFixture.class.getClassLoader();
    }

    @Override
    public Collection<?> seeds(Which which) {
        switch (which) {
            case VISIBLE:
                return Collections.singletonList(visibleView);
            case HIDDEN:
                return Collections.singletonList(hiddenView);
            default:
                return Collections.singletonList(engine);
        }
    }

    @Override
    public Object browserObject(Which which) {
        return which == Which.VISIBLE ? visible : which == Which.HIDDEN ? hidden : viewless;
    }

    @Override
    public void load(WebBrowser browser, String html) throws Exception {
        ((FakeBrowser) browser.vendorObject()).loadAndWait(html);
    }

    @Override
    public Object newDisposableBrowser() throws Exception {
        FakeBrowser b = engine.newBrowser();
        b.loadAndWait(BLANK);
        return b;
    }

    @Override
    public void close(WebBrowser browser) {
        ((FakeBrowser) browser.vendorObject()).close();
    }

    @Override
    public AppListener installAppListener(WebBrowser browser) {
        final AtomicInteger received = new AtomicInteger();
        ((FakeBrowser) browser.vendorObject()).on(FakeConsoleMessageReceived.class,
                new FakeObserver<FakeConsoleMessageReceived>() {
                    @Override
                    public void on(FakeConsoleMessageReceived event) {
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
        return which == Which.VIEWLESS ? WebRendering.UNKNOWN : WebRendering.OFFSCREEN_LIGHTWEIGHT;
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
        return false;
    }

    @Override
    public RecordingClassLoader pristineLoader() {
        ClassLoader test = FakeJxFixture.class.getClassLoader();
        return new RecordingClassLoader(test, test, PACKAGE + ".");
    }

    /** A host that loaded and initialised the fake's gate class ({@code FakeEngine}) and nothing else of it. */
    @Override
    public synchronized RecordingClassLoader recordingHostLoader() {
        if (host == null) {
            ClassLoader test = FakeJxFixture.class.getClassLoader();
            RecordingClassLoader rec = new RecordingClassLoader(test, test, PACKAGE + ".");
            try {
                Class.forName(PACKAGE + ".FakeEngine", true, rec);
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

    /**
     * The fake's own package only: its recording host defines every class under the package's prefix, the CEF fake's
     * subpackage included, which item 1 then counts as outside the technology.
     */
    @Override
    public Set<String> technologyPackages() {
        return java.util.Collections.singleton(PACKAGE);
    }

    /** The observers registered on the fake browser. */
    @Override
    public int vendorListenerCount(WebBrowser browser) {
        return ((FakeBrowser) browser.vendorObject()).observerCount();
    }
}
