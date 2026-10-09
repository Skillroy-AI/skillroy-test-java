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

package ai.skillroy.testjava.spikes.bs3.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The probe agent's entry points ({@code Premain-Class}, {@code Agent-Class}): the shape of the real bootstrap, small.
 * Idempotent, never throws into the host: it reads its options, finds the engine and spy jars next to its own jar,
 * builds an {@link EngineLoader} over the engine jar and runs the engine's probe on a daemon thread.
 *
 * <p>Options, comma-separated {@code key=value}: {@code out} (the run's output directory, required), {@code engine}
 * (the engine jar's file name, default {@code bs3-engine.jar}), {@code spy} (default {@code bs3-spy.jar}),
 * {@code mode} (a label), {@code sweep} ({@code false} skips the size sweep), {@code expectEngine}
 * ({@code relocated} turns the item 2 check on).
 */
public final class ProbeAgent {

    private static final String LOADED = "bs3.agent.loaded";

    private ProbeAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        start("premain", args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        start("agentmain", args, inst);
    }

    private static synchronized void start(String entry, String args, final Instrumentation inst) {
        final long t0 = System.nanoTime();
        try {
            if (System.getProperty(LOADED) != null) {
                System.err.println("[bs3] agent already loaded (" + System.getProperty(LOADED) + "); " + entry
                        + " ignored");
                return;
            }
            System.setProperty(LOADED, entry);
            final Map<String, String> opts = parse(args);
            opts.put("entry", entry);
            File home = new File(ProbeAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getParentFile();
            File engine = new File(home, value(opts, "engine", "bs3-engine.jar"));
            File spy = new File(home, value(opts, "spy", "bs3-spy.jar"));
            opts.put("engineJar", engine.getAbsolutePath());
            opts.put("spyJar", spy.getAbsolutePath());
            final EngineLoader loader = new EngineLoader(new URL[] {engine.toURI().toURL()},
                    ClassLoader.getSystemClassLoader());
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> probe = Class.forName("ai.skillroy.testjava.spikes.bs3.engine.Probe", true, loader);
                        opts.put("agentToProbeMs", String.format(java.util.Locale.ROOT, "%.3f",
                                (System.nanoTime() - t0) / 1e6));
                        probe.getMethod("run", Instrumentation.class, Map.class).invoke(null, inst, opts);
                    } catch (Throwable e) {
                        System.err.println("[bs3] probe failed to start: " + e);
                        e.printStackTrace();
                        System.setProperty("bs3.probe.result", "ERROR " + e);
                        System.setProperty("bs3.probe.done", "true");
                    }
                }
            }, "bs3-probe");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            System.err.println("[bs3] agent start failed: " + e);
            System.setProperty("bs3.probe.result", "ERROR " + e);
            System.setProperty("bs3.probe.done", "true");
        }
    }

    private static String value(Map<String, String> opts, String key, String dflt) {
        String v = opts.get(key);
        return v == null || v.isEmpty() ? dflt : v;
    }

    static Map<String, String> parse(String args) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (args == null) {
            return out;
        }
        for (String part : args.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
