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

package ai.skillroy.testjava.spikes.bs3.host;

import java.io.File;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongBiFunction;

import ai.skillroy.testjava.samples.lending.AccessControl;
import ai.skillroy.testjava.samples.lending.AccessDecision;

/**
 * What the host exposes to the probe (by name, through the system loader): the sample desk's {@link AccessControl},
 * the toy rule and its jar, factories for the two kinds of custom loader, a single "lending-desk" thread to run calls
 * on, and tight loops for the fire-cost measurements. Plain host code: it knows nothing about the agent.
 */
public final class HostTargets {

    /** The desk's access rules (seed A). */
    public static volatile AccessControl ACCESS;
    /** The toy jar; never on the class path. */
    public static volatile File TOY_JAR;
    /** The toy loader the base hook targets, and the toy rule it defined. */
    public static volatile ClassLoader TOY_LOADER;
    public static volatile Object TOY_RULE;
    /** Keeps loop results alive so the JIT cannot drop the loops. */
    public static volatile long sink;

    private static final ExecutorService DESK = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "lending-desk");
            t.setDaemon(true);
            return t;
        }
    });

    private HostTargets() {
    }

    /** Runs a task on the host's desk thread, waiting at most {@code timeoutMs}. */
    public static Object onDesk(Callable<?> task, long timeoutMs) throws Exception {
        return DESK.submit(task).get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** The desk's access check: {@code ACCESS.check(code, permission, amount)}. */
    public static AccessDecision check(String code, String permission, String amount) {
        return ACCESS.check(code, permission, new BigDecimal(amount));
    }

    /** @return nanoseconds for {@code n} calls of the desk's return-limit check (Alice, 45.00) */
    public static long loopCheck(int n) {
        AccessControl a = ACCESS;
        BigDecimal amount = new BigDecimal("45.00");
        long allowed = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            if (a.check("100", AccessControl.RETURN, amount).allowed()) {
                allowed++;
            }
        }
        long t = System.nanoTime() - t0;
        sink += allowed;
        return t;
    }

    private static final String[] ITEMS = {"HND-001", "HND-002", "LAD-001", "PWR-001"};
    private static final Integer[] DAYS = {1, 2, 3, 4, 5, 6, 7, 8};

    /**
     * @return nanoseconds for {@code n} deposit calculations through the rule's JDK interface; the inputs vary per
     *     call so that the compiler cannot fold an unhooked loop away
     */
    public static long loopDeposit(ToLongBiFunction<String, Integer> rule, int n) {
        long sum = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            sum += rule.applyAsLong(ITEMS[i & 3], DAYS[(i >>> 2) & 7]);
        }
        long t = System.nanoTime() - t0;
        sink += sum;
        return t;
    }

    /** @return a new {@link ToolShedLoader} over the toy jar (parent: the system loader) */
    public static ClassLoader newToyLoader(String name) throws MalformedURLException {
        return new ToolShedLoader(name, new URL[] {TOY_JAR.toURI().toURL()}, ClassLoader.getSystemClassLoader());
    }

    /** @return a new {@link StrictDelegationLoader} over the toy jar */
    public static ClassLoader newStrictLoader(String name) throws MalformedURLException {
        return new StrictDelegationLoader(name, new URL[] {TOY_JAR.toURI().toURL()},
                ClassLoader.getSystemClassLoader());
    }
}
