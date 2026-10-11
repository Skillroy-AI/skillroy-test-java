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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.SurfaceSource;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.web.jcef.JcefAdapter;
import ai.skillroy.testjava.agent.web.jxbrowser.JxBrowserAdapter;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.Topics;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The agent module {@code web} (phase4-contracts.md section 4): the {@code web.*} primitives over embedded browsers,
 * written once against the embedded-browser SPI ({@code ai.skillroy.testjava.agent.api.spi.web}), with the two
 * built-in reflective adapters {@code jcef} and {@code jxbrowser}, the browser index, the {@code web.*} events and
 * the page-event tap. Discovered through {@code META-INF/services}; needs nothing from other modules.
 *
 * <p><b>Inert until needed.</b> {@link #install} registers the primitives and sets every capability
 * UNAVAILABLE("no embedded browser technology found"); it constructs the two built-in adapters without probing them,
 * registers the module's {@code SurfaceSource} (phase7-contracts.md 4.8: the browsers of the index as {@code WEB}
 * surfaces of the Swing capture, answered from the index), touches no AWT and no vendor class, registers nothing with
 * the host and starts no thread. A target without an
 * embedded browser therefore sees little of the module: {@link #onTargetReady} probes the adapters (answering "not
 * present" without loading a class), finds no adapter present and reads nothing else (no EDT read, no thread); later
 * {@code window.opened} events cost it one short task on an agent worker each (coalesced) that finds no adapter to
 * probe again.
 *
 * <p><b>Readiness.</b> {@link #onTargetReady} probes each built-in adapter and each {@code WebEngineAdapter}
 * registered through {@code SpiRegistry} with the host's class loader, rebuilds the browser index over the present
 * ones (bounded), and declares every capability with a probe ({@link WebCapabilities}); an adapter whose probe is
 * UNAVAILABLE stays out of the index until the next probe round ({@code agent.capabilities {reprobe: true}}), or
 * until the module's own re-probe after a {@code window.opened} event finds it: an {@code EventBus} subscription
 * marks a re-probe pending and posts one re-probe worker to an agent worker unless one is queued or running (one
 * queued longer than {@value #CYCLE_BOUND_MS} ms, a post the core dropped, no longer holds the next one back). The
 * worker probes again only the adapters whose technology may have arrived (present but not loaded by the application
 * yet, a registered adapter that was UNAVAILABLE, one registered since) and runs a full cycle only when one of their
 * results changed ({@link WebCapabilities#reprobeChanged()}); a technology that is not present at all is probed again
 * only on reprobe. (A re-probe inside a {@code web.*} call cannot work: while no adapter is present the dispatcher
 * refuses every {@code web.*} call before the module sees it.)
 *
 * <p><b>The pace</b> (amendment A38): the re-probe runs at most once every {@value #REPROBE_PACE_MS} ms, and no event
 * is dropped. The worker loops while a re-probe is pending: when some adapter is a candidate
 * ({@link WebCapabilities#reprobeHasCandidates()}) and the previous re-probe cycle started less than
 * {@value #REPROBE_PACE_MS} ms ago, it first waits out the rest of that time on its own agent worker (a bounded wait on
 * a monitor that uninstall wakes; never on the publishing thread, never a thread, timer or scheduler of its own), then
 * clears the mark and runs the cycle. An event that arrives during the wait is covered by that cycle; one that arrives
 * while the cycle runs marks the re-probe pending again, and the worker runs one further cycle. Without a candidate it
 * neither waits nor probes. The readiness cycle and {@code reprobe} are not paced.
 *
 * <p><b>Uninstall</b> first stops what could start new work against the adapters (the {@code window.opened}
 * subscription, the event bridge with every subscription it made, the capability cycle, waiting for one in
 * progress), then removes the taps (bounded), detaches every adapter (listeners, init scripts and viewport overrides
 * removed; nothing of the application closed; the adapter's threads ended for good), clears the index and
 * unregisters the primitives. It never throws.
 *
 * <p>The core calls the lifecycle methods one at a time; mutable state is guarded by this object's lock.
 */
public final class WebModule implements AgentModule {

    /** Module id. */
    public static final String ID = "web";
    /** The agent option that opts in to chaining JxBrowser's {@code InjectJsCallback} (phase4-contracts.md 4.5). */
    public static final String OPTION_CHAIN_INJECT_JS = "web.jxbrowser.chainInjectJs";
    /** Bound of the probe cycle run by {@code onTargetReady} and by the module's own re-probe. */
    static final long CYCLE_BOUND_MS = 30000;
    /** Bound of the taps' removal at uninstall. */
    static final long UNINSTALL_TAPS_MS = 3000;
    /** The re-probe after {@code window.opened} starts a cycle at most once per this many ms (A38). */
    static final long REPROBE_PACE_MS = 1000;

    private final SeedWalkFactory walks;
    private final AdapterFactory adapterFactory;
    private AgentContext ctx;
    private List<WebEngineAdapter> builtIns;
    private BrowserIndex index;
    private EventBridge bridge;
    private Taps taps;
    private WebCapabilities capabilities;
    private AutoCloseable windowSubscription;
    /** The registration of the module's {@link SurfaceSource} (phase7-contracts.md 4.8), withdrawn at uninstall. */
    private AutoCloseable surfaceRegistration;
    /**
     * When the re-probe worker that is queued or running was queued, or began its current round ({@link Pace#nanoTime},
     * never 0), or 0 when there is none.
     */
    private final AtomicLong reprobeQueuedAt = new AtomicLong();
    /** How long a queued re-probe holds the next one back ({@value #CYCLE_BOUND_MS} ms; tests shorten it). */
    private volatile long reprobeHoldMs = CYCLE_BOUND_MS;
    /** Set by every {@code window.opened}, cleared by the re-probe cycle that starts after it (A38). */
    private final AtomicBoolean reprobePending = new AtomicBoolean();
    /** When the last re-probe cycle started ({@link Pace#nanoTime}); meaningful once {@link #reprobedOnce}. */
    private volatile long lastReprobeStart;
    private volatile boolean reprobedOnce;
    /** What a re-probe waits on for its pace; uninstall wakes it. */
    private final Object paceMonitor = new Object();
    /** The pace's clock and wait ({@link Pace#SYSTEM}; tests replace it). */
    private volatile Pace pace = Pace.SYSTEM;
    private final List<String> registered = new ArrayList<String>();
    private volatile boolean uninstalled;

    /** For {@code ServiceLoader}. */
    public WebModule() {
        this(null, null);
    }

    /** For tests: a fake Swing walk and other adapters than the built-in ones (null: the production ones). */
    WebModule(SeedWalkFactory walks, AdapterFactory adapters) {
        this.walks = walks;
        this.adapterFactory = adapters;
    }

    /** {@value #ID}. */
    @Override
    public String id() {
        return ID;
    }

    /** The module's Maven version, from the filtered {@code web-module.properties}. */
    @Override
    public String version() {
        return Version.VALUE;
    }

    /**
     * Registers the primitives and sets the install-time capabilities (see the class comment). Called once on the
     * agent's own thread; touches no AWT and no vendor class, starts no thread, never waits.
     */
    @Override
    public synchronized void install(AgentContext context) throws Exception {
        this.ctx = context;
        this.uninstalled = false;
        this.reprobePending.set(false);
        this.reprobedOnce = false;
        this.builtIns = adapterFactory != null ? adapterFactory.create(context) : Arrays.<WebEngineAdapter>asList(
                new JcefAdapter(context.instrumentation()),
                new JxBrowserAdapter(context.instrumentation(), new JxBrowserAdapter.Options() {
                    @Override
                    public boolean chainInjectJs() {
                        return chainOption(ctx);
                    }
                }));
        SeedWalk walk = walks != null ? walks.create(context) : new SwingSeeds(context);
        this.index = new BrowserIndex(context, walk);
        Secrets secrets = new Secrets(context.spi(), context.log(), context.option("redact"));
        this.bridge = new EventBridge(context, secrets);
        this.taps = new Taps(context, bridge);
        bridge.taps(taps);
        index.listener(new BrowserIndex.Listener() {
            @Override
            public void entered(Entry entry) {
                bridge.entered(entry);
            }

            @Override
            public void left(Entry entry) {
                bridge.left(entry);
            }
        });
        this.capabilities = new WebCapabilities(context, index, builtIns);
        final WebCapabilities caps = capabilities;
        index.hook(new BrowserIndex.Hook() {
            @Override
            public void rebuilt(BrowserIndex.Snapshot snapshot) {
                caps.afterRebuild(snapshot);
            }
        });
        capabilities.installDefaults();
        Selection selection = new Selection(context, index, walk);
        WebPrimitives primitives = new WebPrimitives(context, index, selection, bridge, taps, secrets, capabilities,
                walk);
        for (Primitive p : primitives.all()) {
            context.primitives().register(p);
            registered.add(p.spec().name());
        }
        try {
            surfaceRegistration = context.spi().register(SurfaceSource.class, new WebSurfaces(index));
        } catch (RuntimeException e) {
            context.log().warn("web: the browser surfaces could not be registered for the Swing capture: " + e);
        }
        context.log().info("web: installed " + registered.size() + " primitives; adapters "
                + adapterIds(builtIns) + " constructed, not probed");
    }

    /**
     * Probes the adapters, builds the index and declares the capabilities (see the class comment). Runs on an agent
     * thread of its own; the cycle itself runs on an agent worker, bounded. Never throws.
     */
    @Override
    public synchronized void onTargetReady(AgentContext context) {
        if (uninstalled || capabilities == null) {
            return;
        }
        try {
            WebCapabilities.Cycle cycle = ctx.threads().call(ThreadPolicy.WORKER,
                    new Callable<WebCapabilities.Cycle>() {
                        @Override
                        public WebCapabilities.Cycle call() {
                            return capabilities.compute();
                        }
                    }, CYCLE_BOUND_MS);
            capabilities.declareAll(cycle);
            ctx.log().info("web: adapters probed: " + cycle.adapterProbes);
        } catch (Throwable t) {
            ctx.log().warn("web: the probe cycle at readiness failed: " + t, t);
        }
        try {
            windowSubscription = ctx.events().subscribe(TopicFilter.parse(Topics.WINDOW_OPENED),
                    new ai.skillroy.testjava.agent.api.EventSink() {
                        @Override
                        public void onEvent(Event event) {
                            windowOpened();
                        }
                    });
        } catch (Throwable t) {
            ctx.log().warn("web: could not subscribe to window.opened: " + t);
        }
    }

    /**
     * Called on the publishing thread (often the EDT): marks a re-probe pending and posts a re-probe worker unless one
     * is queued or running (that one sees the mark). Never waits.
     */
    private void windowOpened() {
        if (uninstalled) {
            return;
        }
        reprobePending.set(true);
        startReprobe();
    }

    /**
     * Posts a re-probe worker to an agent worker unless one is queued or running; one queued longer than
     * {@link #reprobeHoldMs} ago no longer counts (the core's broker drops a post it cannot run without telling the
     * poster, so a lost one must not hold every later one back). Never waits.
     */
    private void startReprobe() {
        long now = pace.nanoTime();
        long queued = reprobeQueuedAt.get();
        if (queued != 0 && now - queued < TimeUnit.MILLISECONDS.toNanos(reprobeHoldMs)) {
            return;
        }
        final long mine = now == 0 ? 1 : now;
        if (!reprobeQueuedAt.compareAndSet(queued, mine)) {
            return;
        }
        try {
            ctx.threads().post(ThreadPolicy.WORKER, new Runnable() {
                @Override
                public void run() {
                    reprobeWorker(mine);
                }
            });
        } catch (Throwable t) {
            reprobeQueuedAt.compareAndSet(mine, 0);
        }
    }

    /**
     * The re-probe worker (A38, see the class comment): runs rounds while a re-probe is pending. A round with a
     * candidate first waits out the pace on this agent worker, then clears the mark and runs the cycle; a round
     * without one clears the mark and finds nothing to probe. Each round renews the worker's queued time, so that a
     * long burst is not taken for a lost post. An event that arrived after the last round found this worker running
     * and left the re-probe to it, so the worker posts a new one when the mark is set again as it ends.
     */
    private void reprobeWorker(long queuedAt) {
        long held = queuedAt;
        try {
            while (!uninstalled && reprobePending.get()) {
                long now = pace.nanoTime();
                long round = now == 0 ? 1 : now;
                if (!reprobeQueuedAt.compareAndSet(held, round)) {
                    return; // taken for lost and replaced: the new worker sees the mark
                }
                held = round;
                WebCapabilities caps = capabilities;
                boolean candidates = caps != null && caps.reprobeHasCandidates();
                if (candidates && !awaitPace()) {
                    return; // uninstalled or interrupted while waiting: no cycle
                }
                reprobePending.set(false);
                if (candidates) {
                    lastReprobeStart = pace.nanoTime();
                    reprobedOnce = true;
                }
                reprobeAfterWindowOpened();
            }
        } finally {
            reprobeQueuedAt.compareAndSet(held, 0);
        }
        if (!uninstalled && reprobePending.get()) {
            startReprobe();
        }
    }

    /**
     * Waits on this agent worker until {@value #REPROBE_PACE_MS} ms have passed since the last re-probe cycle started:
     * at most that long, on {@link #paceMonitor}, which uninstall wakes. False when uninstall or an interrupt ended
     * the wait.
     */
    private boolean awaitPace() {
        if (!reprobedOnce) {
            return true;
        }
        long until = lastReprobeStart + TimeUnit.MILLISECONDS.toNanos(REPROBE_PACE_MS);
        synchronized (paceMonitor) {
            while (!uninstalled) {
                long left = until - pace.nanoTime();
                if (left <= 0) {
                    return true;
                }
                try {
                    pace.await(paceMonitor, Math.max(1, (left + 999999) / 1000000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }
    }

    /**
     * The module's own re-probe after a {@code window.opened} event: the cheap probes of
     * {@link WebCapabilities#reprobeChanged()}, then a new cycle, applied at once, only when one of them changed.
     */
    void reprobeAfterWindowOpened() {
        WebCapabilities caps = capabilities;
        if (uninstalled || caps == null) {
            return;
        }
        try {
            if (caps.reprobeChanged() && !uninstalled) {
                caps.apply(caps.compute());
            }
        } catch (Throwable t) {
            ctx.log().warn("web: the re-probe after a window opened failed: " + t);
        }
    }

    /** Shortens how long a queued re-probe holds the next one back (for tests of a dropped post). */
    void reprobeHoldMs(long ms) {
        this.reprobeHoldMs = ms;
    }

    /** Replaces the pace's clock and wait (for tests: a clock that moves only when told). */
    void reprobePace(Pace p) {
        this.pace = p;
    }

    /** True when no re-probe is pending and no re-probe worker is queued or running (for tests). */
    boolean reprobeIdle() {
        return reprobeQueuedAt.get() == 0 && !reprobePending.get();
    }

    /** Removes everything the module added (see the class comment). Never throws, never blocks for long. */
    @Override
    public synchronized void uninstall() {
        uninstalled = true;
        reprobePending.set(false);
        synchronized (paceMonitor) {
            paceMonitor.notifyAll();
        }
        closeQuietly(windowSubscription);
        windowSubscription = null;
        closeQuietly(surfaceRegistration);
        surfaceRegistration = null;
        // First what could start new work against the adapters: a re-probe or a rebuild in flight would otherwise
        // subscribe a browser of an adapter detached below (and so revive it).
        try {
            if (bridge != null) {
                bridge.close();
            }
        } catch (Throwable t) {
            log("web: closing the event bridge failed: " + t);
        }
        try {
            if (capabilities != null) {
                capabilities.close();
            }
        } catch (Throwable t) {
            log("web: closing the capability cycle failed: " + t);
        }
        try {
            if (taps != null) {
                taps.shutdown(UNINSTALL_TAPS_MS);
            }
        } catch (Throwable t) {
            log("web: removing the taps failed: " + t);
        }
        if (builtIns != null && capabilities != null) {
            for (WebEngineAdapter a : capabilities.adapters()) {
                try {
                    a.detach();
                } catch (Throwable t) {
                    log("web: adapter " + safeId(a) + " failed to detach: " + t);
                }
            }
        }
        try {
            if (index != null) {
                index.listener(null);
                index.hook(null);
                index.clear();
            }
        } catch (Throwable t) {
            log("web: clearing the browser index failed: " + t);
        }
        if (ctx != null) {
            for (String name : registered) {
                try {
                    ctx.primitives().unregister(name);
                } catch (Throwable t) {
                    log("web: could not unregister " + name + ": " + t);
                }
            }
        }
        registered.clear();
    }

    /** The current capability cycle (for tests). */
    WebCapabilities capabilitiesForTest() {
        return capabilities;
    }

    /** The browser index (for tests). */
    BrowserIndex indexForTest() {
        return index;
    }

    /** The taps (for tests). */
    Taps tapsForTest() {
        return taps;
    }

    /**
     * True when the agent option {@value #OPTION_CHAIN_INJECT_JS} reads {@code true} (case-insensitive, surrounding
     * white space ignored); any other value, or none, is false. Read live (amendment A20), so a pack may set it as a
     * system property after the agent started.
     */
    static boolean chainOption(AgentContext context) {
        if (context == null) {
            return false;
        }
        String value = context.option(OPTION_CHAIN_INJECT_JS);
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    private void log(String msg) {
        try {
            if (ctx != null) {
                ctx.log().warn(msg);
            }
        } catch (Throwable ignored) {
            // uninstall must not throw
        }
    }

    private static String safeId(WebEngineAdapter a) {
        try {
            return a.id();
        } catch (Throwable t) {
            return a.getClass().getName();
        }
    }

    private static List<String> adapterIds(List<WebEngineAdapter> adapters) {
        List<String> out = new ArrayList<String>();
        for (WebEngineAdapter a : adapters) {
            out.add(safeId(a));
        }
        return out;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // a subscription handle never throws
        }
    }

    /** Makes the Swing walk (tests replace it). */
    interface SeedWalkFactory {
        SeedWalk create(AgentContext context);
    }

    /** Makes the built-in adapters (tests replace them). */
    interface AdapterFactory {
        List<WebEngineAdapter> create(AgentContext context);
    }

    /** The clock and the wait of the re-probe's pace (A38); tests replace them with a clock that moves when told. */
    interface Pace {

        /** {@code System.nanoTime()} and {@code Object.wait(ms)}. */
        Pace SYSTEM = new Pace() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void await(Object monitor, long ms) throws InterruptedException {
                monitor.wait(ms);
            }
        };

        /** A monotonic time in nanoseconds. */
        long nanoTime();

        /**
         * Waits on {@code monitor}, whose lock the caller holds, at most {@code ms} or until it is notified; may
         * return early (the caller reads the time again).
         */
        void await(Object monitor, long ms) throws InterruptedException;
    }

    /** The version from the filtered {@code web-module.properties}. */
    private static final class Version {
        static final String VALUE = load();

        private static String load() {
            InputStream in = WebModule.class.getResourceAsStream("web-module.properties");
            if (in == null) {
                return "unknown";
            }
            try {
                Properties p = new Properties();
                p.load(in);
                return p.getProperty("version", "unknown");
            } catch (IOException e) {
                return "unknown";
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // read-only resource
                }
            }
        }
    }
}
