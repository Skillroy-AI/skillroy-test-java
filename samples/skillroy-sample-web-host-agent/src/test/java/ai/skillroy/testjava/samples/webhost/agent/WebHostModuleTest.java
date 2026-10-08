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


package ai.skillroy.testjava.samples.webhost.agent;

import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.spi.ReadinessSource;
import ai.skillroy.testjava.agent.api.spi.web.BrowserRegistryAdapter;
import ai.skillroy.testjava.agent.api.spi.web.BrowserSeed;
import ai.skillroy.testjava.samples.webhost.WebHostBrowsers;
import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The webhost module against the host's registry filled with placeholder browsers (proxies of JCEF's browser
 * interface; no CEF runs): what it registers, its seeds, its readiness and its uninstall.
 */
class WebHostModuleTest {

    private final FakeAgentContext ctx = new FakeAgentContext();
    private final WebHostModule module = new WebHostModule();

    @AfterEach
    void cleanUp() {
        module.uninstall();
        WebHostBrowsers.clear();
        assertEquals(Collections.<String>emptyList(), ctx.unexpected, "AgentContext calls beyond spi() and log()");
    }

    @Test
    void installRegistersOneRegistryAdapterAndOneReadinessSourceAndNothingElse() {
        assertEquals("webhost", module.id());
        assertEquals("0.1.0", module.version());
        assertEquals(Collections.emptySet(), module.requires());
        module.install(ctx.context);
        assertEquals(2, ctx.spi.count());
        assertEquals(1, ctx.spi.all(BrowserRegistryAdapter.class).size());
        assertEquals(1, ctx.spi.all(ReadinessSource.class).size());
        assertEquals(Collections.<String>emptyList(), ctx.log.lines, "install logs nothing");
    }

    @Test
    void theModuleIsFoundByServiceLoader() {
        List<String> ids = new ArrayList<String>();
        for (AgentModule m : ServiceLoader.load(AgentModule.class, WebHostModule.class.getClassLoader())) {
            ids.add(m.id());
        }
        assertEquals(Collections.singletonList("webhost"), ids);
    }

    @Test
    void notReadyAndNoSeedsUntilTheHostRegistersItsBrowsers() {
        module.install(ctx.context);
        ReadinessSource readiness = ctx.spi.first(ReadinessSource.class);
        BrowserRegistryAdapter registry = ctx.spi.first(BrowserRegistryAdapter.class);
        assertEquals("webhost.browsers", readiness.id());
        assertFalse(readiness.isReady());
        assertEquals("waiting for the web host to register its browsers (WebHostBrowsers)", readiness.describe());
        assertEquals(Collections.<BrowserSeed>emptyList(), new ArrayList<BrowserSeed>(registry.seeds()));

        CefBrowser front = placeholder("front");
        CefBrowser back = placeholder("back");
        CefBrowser detached = placeholder("detached");
        WebHostBrowsers.register(null, null, front, back, detached);
        assertTrue(readiness.isReady());
        assertEquals("the web host has registered its browsers", readiness.describe());
        assertEquals(Arrays.asList(BrowserSeed.of(front, "front"), BrowserSeed.of(back, "back"),
                BrowserSeed.of(detached, "detached")), new ArrayList<BrowserSeed>(registry.seeds()));
        for (BrowserSeed seed : registry.seeds()) {
            assertSame(WebHostBrowsersTag.browser(seed.tag()), seed.object(), seed.tag());
        }
    }

    @Test
    void windowedHostsHaveNoDetachedSeed() {
        module.install(ctx.context);
        CefBrowser front = placeholder("front");
        CefBrowser back = placeholder("back");
        WebHostBrowsers.register(null, null, front, back, null);
        Collection<BrowserSeed> seeds = ctx.spi.first(BrowserRegistryAdapter.class).seeds();
        assertEquals(Arrays.asList(BrowserSeed.of(front, "front"), BrowserSeed.of(back, "back")),
                new ArrayList<BrowserSeed>(seeds));
    }

    @Test
    void onTargetReadyLogsTheSeedsItHandsOut() {
        module.install(ctx.context);
        WebHostBrowsers.register(null, null, placeholder("front"), placeholder("back"), null);
        module.onTargetReady(ctx.context);
        assertEquals(Collections.singletonList("INFO webhost: target ready; browser seeds: [front, back]"),
                ctx.log.lines);
    }

    @Test
    void uninstallUnregistersEverythingAndNeverThrows() {
        module.install(ctx.context);
        assertEquals(2, ctx.spi.count());
        module.uninstall();
        assertEquals(0, ctx.spi.count());
        module.uninstall();
        new WebHostModule().uninstall();
        assertEquals(0, ctx.spi.count());
    }

    /** Which registry accessor a tag stands for. */
    private static final class WebHostBrowsersTag {
        static Object browser(String tag) {
            if ("front".equals(tag)) {
                return WebHostBrowsers.front();
            }
            if ("back".equals(tag)) {
                return WebHostBrowsers.back();
            }
            return WebHostBrowsers.detached();
        }
    }

    /** A placeholder browser: answers toString, equals and hashCode, fails for anything else. */
    private static CefBrowser placeholder(final String name) {
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(),
                new Class<?>[] {CefBrowser.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("toString".equals(method.getName())) {
                            return "placeholder browser " + name;
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        throw new AssertionError("the module must not call " + method + " on a browser");
                    }
                });
    }
}
