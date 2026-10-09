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

package ai.skillroy.testjava.spikes.bs14.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The probe agent (-javaagent). Its options are separated by {@code ;} because Byteman's own option string contains
 * commas: {@code out=<dir>;probe=<jar>;sys=<jar>;byteman=<jar>;bmopts=<Byteman options>}. It starts one daemon worker
 * that waits for the toy host to be ready, then runs the probe in a child-first loader of its own (the shape of a
 * module loader) and finally tells the host to stop.
 */
public final class Bs14Agent {

    private Bs14Agent() {
    }

    public static void premain(String args, final Instrumentation inst) {
        final Map<String, String> opts = new LinkedHashMap<String, String>();
        for (String part : (args == null ? "" : args).split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                opts.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                work(inst, opts);
            }
        }, "bs14-worker");
        worker.setDaemon(true);
        worker.start();
    }

    static void work(Instrumentation inst, Map<String, String> opts) {
        File out = new File(opts.get("out"));
        try {
            for (int i = 0; i < 600 && !"true".equals(System.getProperty("bs14.host.ready")); i++) {
                Thread.sleep(100);
            }
            ProbeLoader loader = new ProbeLoader(new URL[] {new File(opts.get("probe")).toURI().toURL()},
                    ClassLoader.getSystemClassLoader());
            Class<?> probe = Class.forName("ai.skillroy.testjava.spikes.bs14.probe.Probe", true, loader);
            Method run = probe.getMethod("run", Instrumentation.class, Map.class);
            run.invoke(null, inst, opts);
        } catch (Throwable t) {
            try {
                PrintStream ps = new PrintStream(new FileOutputStream(new File(out, "agent-failure.txt")), true);
                t.printStackTrace(ps);
                ps.close();
            } catch (Exception ignored) {
                // nothing more to do
            }
        } finally {
            stopHost();
        }
    }

    @SuppressWarnings("unchecked")
    static void stopHost() {
        try {
            Class<?> host = Class.forName("ai.skillroy.testjava.spikes.bs14.toy.ToyHost", false,
                    ClassLoader.getSystemClassLoader());
            ((java.util.concurrent.BlockingQueue<String>) host.getField("CODES").get(null)).put("STOP");
        } catch (Throwable ignored) {
            // the host's own wait expires
        }
    }
}
