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

package ai.skillroy.testjava.spikes.bs3.engine;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Puts {@code Spy} on the bootstrap search path, by name and reflection only: nothing that references the class may
 * be linked before the append (a failed resolution sticks, see {@link EarlySpyRef}). The name below is a string
 * constant, which the shade plugin relocates together with the class references when the java.* placement is built.
 */
final class SpyBoot {

    static final String SPY = "ai.skillroy.testjava.spikes.bs3.spy.Spy";

    /** What {@link #ensure} did. */
    static final class Result {
        boolean alreadyPresent;
        boolean appended;
        boolean appendedAgain;
        String appendError;
        long appendNanos;
        long loadNanos;
        boolean bootstrapLoaded;
        String spyName = SPY;
    }

    private SpyBoot() {
    }

    /** @return the bootstrap loader's Spy, or null if it is not (yet) on the bootstrap path */
    static Class<?> bootSpy() {
        try {
            return Class.forName(SPY, false, null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    /**
     * Appends the jar unless the bootstrap loader already has Spy; with {@code appendAgain} it appends the jar a
     * second time even then (to show what a repeated append does).
     */
    static Result ensure(Instrumentation inst, File jar, boolean appendAgain) {
        Result r = new Result();
        r.alreadyPresent = bootSpy() != null;
        if (!r.alreadyPresent || appendAgain) {
            // Markers on stderr, so that anything the JVM prints there because of the append can be placed.
            System.err.println("[bs3] stderr marker: appending " + jar.getName() + " to the bootstrap search path");
            long t0 = System.nanoTime();
            try {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
                if (r.alreadyPresent) {
                    r.appendedAgain = true;
                } else {
                    r.appended = true;
                }
            } catch (Throwable t) {
                r.appendError = t.toString();
            }
            r.appendNanos = System.nanoTime() - t0;
            System.err.println("[bs3] stderr marker: appended");
        }
        long t1 = System.nanoTime();
        try {
            Class<?> c = Class.forName(SPY, true, null);
            r.bootstrapLoaded = c.getClassLoader() == null;
        } catch (Throwable t) {
            r.appendError = (r.appendError == null ? "" : r.appendError + "; ") + "load: " + t;
        }
        r.loadNanos = System.nanoTime() - t1;
        return r;
    }

    /** The install-time visibility probe: does this loader resolve the name to the bootstrap Spy? */
    static String visibility(ClassLoader loader) {
        Class<?> boot = bootSpy();
        if (boot == null) {
            return "NO_BOOT_SPY";
        }
        try {
            Class<?> seen = Class.forName(SPY, false, loader);
            return seen == boot ? "VISIBLE" : "OTHER_COPY:" + seen.getClassLoader();
        } catch (ClassNotFoundException e) {
            return "NOT_VISIBLE";
        } catch (Throwable t) {
            return "ERROR:" + t;
        }
    }
}
