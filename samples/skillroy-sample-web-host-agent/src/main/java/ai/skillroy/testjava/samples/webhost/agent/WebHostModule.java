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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.spi.CaptureMask;
import ai.skillroy.testjava.agent.api.spi.ReadinessSource;
import ai.skillroy.testjava.agent.api.spi.web.BrowserRegistryAdapter;
import ai.skillroy.testjava.agent.api.spi.web.BrowserSeed;
import ai.skillroy.testjava.samples.webhost.WebHostBrowsers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Agent module {@value #ID}: what the agent needs to know about the sample web host that the generic web module
 * cannot find out (Phase 4 contract, section 9.2). It registers, through the {@code SpiRegistry},
 *
 * <ul>
 *   <li>a {@link BrowserRegistryAdapter} that hands the agent the host's browsers as seeds, each tagged with its
 *       name: {@value #TAG_FRONT}, {@value #TAG_BACK} and {@value #TAG_DETACHED}, only the ones the host has (the
 *       detached browser exists with off-screen rendering only). The back browser is also found by the Swing-tree
 *       scan (displayable, not showing), the detached one only through its seed, and the front one through both; the
 *       seeds give all three the tag a scenario selects them by ({@code by: {tag: front}});</li>
 *   <li>a {@link ReadinessSource}: ready once the host has registered its browsers (the registry holds the front
 *       browser), which the host does only when its pages have loaded;</li>
 *   <li>only with the agent option {@value #OPTION_MASK}{@code =}{@value #MASK_FRONT} (read once at install, so the
 *       system property {@code skillroy.agent.webhost.mask} sets it too): a {@link CaptureMask} that names the front
 *       card ({@link FrontCardMask}), for the web host's end-to-end check of a masked browser on real JCEF
 *       (phase7-contracts.md 13.2 item 10, gap 95). Without it the module registers no mask, and the web module makes
 *       no masked read.</li>
 * </ul>
 * It registers no primitive and declares no capability.
 *
 * <p>How the host is found: through its static registry {@link WebHostBrowsers}, filled by the host's {@code main}.
 * {@link #install} touches nothing of the application, of JCEF or of AWT; the readiness source and the seeds read the
 * registry, which loads only that class of the host (it holds no reference to a JCEF or AWT class until the host
 * registers). The module's code uses no JCEF type: the browsers cross as {@code Object} seeds.
 *
 * <p>The core calls the lifecycle methods one at a time. {@link #uninstall} closes every registration and never
 * throws.
 */
public final class WebHostModule implements AgentModule {

    /** Module id. */
    public static final String ID = "webhost";
    /** Module version: {@code 0.2.0} (Phase 7 follow-up, gap 95) adds the optional {@link FrontCardMask}. */
    public static final String VERSION = "0.2.0";
    /** Tag of the seed for the browser in the front card. */
    public static final String TAG_FRONT = "front";
    /** Tag of the seed for the browser in the hidden back card. */
    public static final String TAG_BACK = "back";
    /** Tag of the seed for the browser without a view. */
    public static final String TAG_DETACHED = "detached";
    /** Id of the readiness source. */
    static final String READINESS_ID = "webhost.browsers";
    /** Agent option: {@value #MASK_FRONT} registers {@link FrontCardMask}; anything else, or none, no mask. */
    public static final String OPTION_MASK = "webhost.mask";
    /** The one value of {@link #OPTION_MASK} the module acts on. */
    public static final String MASK_FRONT = "front";

    private final List<AutoCloseable> registrations = new ArrayList<AutoCloseable>();
    private volatile AgentContext ctx;

    /** Public no-argument constructor for {@code ServiceLoader}. */
    public WebHostModule() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public void install(AgentContext context) {
        ctx = context;
        register(BrowserRegistryAdapter.class, new Seeds());
        register(ReadinessSource.class, new Readiness());
        if (MASK_FRONT.equals(context.option(OPTION_MASK))) {
            register(CaptureMask.class, new FrontCardMask());
            context.log().info("webhost: " + OPTION_MASK + "=" + MASK_FRONT + ": the front card is masked");
        }
    }

    @Override
    public void onTargetReady(AgentContext context) {
        List<String> tags = new ArrayList<String>();
        for (BrowserSeed seed : seeds()) {
            tags.add(seed.tag());
        }
        context.log().info("webhost: target ready; browser seeds: " + tags);
    }

    @Override
    public void uninstall() {
        AgentContext context = ctx;
        List<AutoCloseable> handles;
        synchronized (registrations) {
            handles = new ArrayList<AutoCloseable>(registrations);
            registrations.clear();
        }
        Collections.reverse(handles);
        for (AutoCloseable handle : handles) {
            try {
                handle.close();
            } catch (Throwable t) {
                if (context != null) {
                    try {
                        context.log().warn("webhost: unregistering an SPI failed: " + t);
                    } catch (Throwable ignored) {
                        // uninstall never throws.
                    }
                }
            }
        }
    }

    /**
     * The seeds for the host's browsers that exist: {@value #TAG_FRONT}, {@value #TAG_BACK},
     * {@value #TAG_DETACHED}, in that order, each tagged with its name; empty before the host registered them.
     * Cheap and non-blocking: reads the registry only.
     */
    static List<BrowserSeed> seeds() {
        List<BrowserSeed> seeds = new ArrayList<BrowserSeed>(3);
        add(seeds, WebHostBrowsers.front(), TAG_FRONT);
        add(seeds, WebHostBrowsers.back(), TAG_BACK);
        add(seeds, WebHostBrowsers.detached(), TAG_DETACHED);
        return seeds;
    }

    private static void add(List<BrowserSeed> seeds, Object browser, String tag) {
        if (browser != null) {
            seeds.add(BrowserSeed.of(browser, tag));
        }
    }

    private <T> void register(Class<T> type, T implementation) {
        AutoCloseable handle = ctx.spi().register(type, implementation);
        if (handle != null) {
            synchronized (registrations) {
                registrations.add(handle);
            }
        }
    }

    /** The host's browsers as seeds. Called on agent workers whenever the agent rebuilds its browser index. */
    private static final class Seeds implements BrowserRegistryAdapter {
        @Override
        public Collection<BrowserSeed> seeds() {
            return WebHostModule.seeds();
        }
    }

    /** Ready once the host has registered its browsers. Polled on an agent worker; reads the registry only. */
    private static final class Readiness implements ReadinessSource {
        @Override
        public String id() {
            return READINESS_ID;
        }

        @Override
        public boolean isReady() {
            return WebHostBrowsers.front() != null;
        }

        @Override
        public String describe() {
            return isReady() ? "the web host has registered its browsers"
                    : "waiting for the web host to register its browsers (WebHostBrowsers)";
        }
    }
}
