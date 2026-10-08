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

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The neutral fake of the vendor's engine, with the member shapes of the F21 table that the {@code jxbrowser}
 * adapter uses ({@code browsers()}, {@code profiles()}, {@code options()}), under this project's own package so that
 * no class sits in a vendor's namespace (browser-spi F28). It renders nothing: a page is a {@link FakeDocument}, an
 * evaluation goes through {@link FakeScript}. Its work runs on one renderer thread, and what it hands back (results,
 * events) arrives on the EDT, so that the kit's watchdog catches an adapter that runs anything there. It records
 * violations of the adapter rules: a blocking call on the EDT, a licence key read, {@code dispatch(...)} used,
 * {@code set(...)} called.
 *
 * <p>Thread-safe.
 */
public final class FakeEngine {

    static {
        ClassInitialisations.record(FakeEngine.class);
    }

    private static final AtomicInteger ENGINES = new AtomicInteger();

    final Violations violations = new Violations();
    private final ScheduledExecutorService renderer;
    private final FakeOptions options;
    private final FakeProfiles profiles = new FakeProfiles();
    private final FakeProfile defaultProfile = new FakeProfile("default");
    private volatile Thread rendererThread;

    private FakeEngine(Integer port, Path userDataDir, FakeRenderingMode mode) {
        this.options = new FakeOptions(port, userDataDir, mode, violations);
        profiles.profiles.add(defaultProfile);
        this.renderer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fake-jx-renderer-" + ENGINES.incrementAndGet());
                t.setDaemon(true);
                rendererThread = t;
                return t;
            }
        });
    }

    /** The application's way to start an engine. */
    public static FakeEngine newInstance(Integer remoteDebuggingPort, Path userDataDir, FakeRenderingMode mode) {
        return new FakeEngine(remoteDebuggingPort, userDataDir, mode);
    }

    public List<FakeBrowser> browsers() {
        return defaultProfile.browsers();
    }

    public FakeProfiles profiles() {
        return profiles;
    }

    public FakeOptions options() {
        return options;
    }

    /** A new browser in the default profile. */
    public FakeBrowser newBrowser() {
        return newBrowser(defaultProfile);
    }

    /** A new browser in a profile. */
    public FakeBrowser newBrowser(FakeProfile profile) {
        FakeBrowser b = new FakeBrowser(this, profile);
        profile.browsers.add(b);
        return b;
    }

    /** A new profile. */
    public FakeProfile newProfile(String name) {
        FakeProfile p = new FakeProfile(name);
        profiles.profiles.add(p);
        return p;
    }

    /** The rule violations seen so far (for the tests' checks). */
    public List<String> violations() {
        return violations.list();
    }

    /** Keeps the renderer busy until the latch is released (at most 30 s): what is asked meanwhile waits behind. */
    public void holdRenderer(final java.util.concurrent.CountDownLatch release) {
        renderer.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    /** Waits (at most 10 s) until the renderer has run everything asked of it before. */
    public void awaitRenderer() {
        onRenderer(new Callable<Object>() {
            @Override
            public Object call() {
                return null;
            }
        });
    }

    /** Ends the renderer thread. */
    public void shutdown() {
        renderer.shutdownNow();
    }

    ScheduledExecutorService renderer() {
        return renderer;
    }

    boolean onRendererThread() {
        return Thread.currentThread() == rendererThread;
    }

    /** Runs on the renderer and waits (bounded), or at once when already there. */
    <T> T onRenderer(Callable<T> task) {
        try {
            if (onRendererThread()) {
                return task.call();
            }
            Future<T> f = renderer.submit(task);
            return f.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The violations of the adapter rules a fake can see. */
    static final class Violations {
        private final List<String> list = Collections.synchronizedList(new ArrayList<String>());

        void add(String what) {
            list.add(what);
        }

        /** Records a blocking vendor call made on the EDT. */
        void blockingCall(String what) {
            if (java.awt.EventQueue.isDispatchThread()) {
                list.add("blocking call on the EDT: " + what);
            }
        }

        List<String> list() {
            synchronized (list) {
                return new ArrayList<String>(list);
            }
        }
    }
}
