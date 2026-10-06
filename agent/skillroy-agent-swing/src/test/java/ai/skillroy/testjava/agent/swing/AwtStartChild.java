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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Body of the child JVMs of {@link AwtStartTest}: a JVM whose application has not used AWT yet. It installs the module
 * the way the agent does at start ({@code install}, then {@code onTargetReady}), lets the watcher look a few times and
 * reports what it sees; then, as asked, sets {@code java.awt.headless=true} and asks AWT whether it is headless (true
 * only if nothing fixed the decision earlier), and starts AWT the way an application would ({@code invokeLater}) and
 * reports again. Prints {@code key=value} lines and {@code done=true} last; exits 0. Arguments: any of
 * {@code flip-headless}, {@code start-awt}.
 */
final class AwtStartChild {

    private static final long WAIT_MS = 120000;

    private AwtStartChild() {
    }

    public static void main(String[] args) throws Exception {
        List<String> options = Arrays.asList(args);
        out("awtThreadsAtStart", awtThreads());
        FakeContext ctx = new FakeContext();
        SwingModule module = new SwingModule();
        module.install(ctx);
        module.onTargetReady(ctx);
        int checks = module.awtChecks();
        long deadline = System.currentTimeMillis() + WAIT_MS;
        synchronized (AwtStartChild.class) {
            while (module.awtChecks() < checks + 4 && System.currentTimeMillis() < deadline) {
                AwtStartChild.class.wait(50); // the watcher looks every 250 ms; four more looks are wanted
            }
        }
        out("watcherLooks", String.valueOf(module.awtChecks() - checks));
        out("awtThreadsBefore", awtThreads());
        out("watcherBefore", watcher());
        caps(ctx, "before");
        out("edtStateBefore", Json.write(ctx.call("swing.edtState", new LinkedHashMap<String, Object>())));
        try {
            ctx.call("swing.snapshot", new LinkedHashMap<String, Object>());
            out("snapshotBefore", "answered");
        } catch (RpcException e) {
            out("snapshotBefore", e.error().code() + "|" + e.error().data().get("reason"));
        }
        out("awtThreadsAfterCalls", awtThreads());
        boolean mayStart = true;
        if (options.contains("flip-headless")) {
            System.setProperty("java.awt.headless", "true");
            boolean headless = GraphicsEnvironment.isHeadless();
            out("headlessAfterFlip", String.valueOf(headless));
            // a module that fixed the decision early would make the next step start a real toolkit on this host
            mayStart = headless;
        }
        if (options.contains("start-awt") && mayStart) {
            final CountDownLatch ran = new CountDownLatch(1);
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    ran.countDown();
                }
            });
            out("edtRan", String.valueOf(ran.await(WAIT_MS, TimeUnit.MILLISECONDS)));
            deadline = System.currentTimeMillis() + WAIT_MS;
            synchronized (AwtStartChild.class) {
                while (!"none".equals(watcher()) && System.currentTimeMillis() < deadline) {
                    AwtStartChild.class.wait(50); // the watcher ends once it has seen the dispatch thread
                }
            }
            out("watcherAfter", watcher());
            caps(ctx, "after");
            out("tapInstalled", String.valueOf(module.tapInstalled()));
            out("edtStateAfter", Json.write(ctx.call("swing.edtState", new LinkedHashMap<String, Object>())));
        }
        module.uninstall();
        ctx.shutdown();
        out("done", "true");
        System.exit(0); // a started dispatch thread would keep the JVM alive
    }

    private static void caps(FakeContext ctx, String when) {
        for (Map.Entry<String, Capability> e : ctx.capabilities.snapshot().entrySet()) {
            out("cap." + when + "." + e.getKey(), e.getValue().state() + "|" + e.getValue().reason());
        }
    }

    /** Names of the live threads AWT starts, comma-separated; empty if there are none. */
    private static String awtThreads() {
        List<String> names = new ArrayList<String>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(AwtThreads.ANY)) {
                names.add(t.getName());
            }
        }
        return String.join(",", names);
    }

    /** {@code daemon}, {@code non-daemon} or {@code none}: the module's watcher thread. */
    private static String watcher() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && SwingModule.TAP_THREAD.equals(t.getName())) {
                return t.isDaemon() ? "daemon" : "non-daemon";
            }
        }
        return "none";
    }

    private static void out(String key, String value) {
        System.out.println(key + "=" + value);
        System.out.flush();
    }
}
