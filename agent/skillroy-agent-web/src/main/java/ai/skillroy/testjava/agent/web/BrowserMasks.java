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
import ai.skillroy.testjava.agent.api.spi.web.BrowserMask;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The view-less rule and the {@link BrowserMask} SPI (phase8-contracts.md section 5.2, D545 to D547, gap 110): which
 * browsers of the index are masked by what a pack knows of them. Steps 1 to 4 of the contract's computation, without an
 * EDT read; step 5 (a component's {@code CaptureMask} read) is the caller's:
 * <ol>
 *   <li>no {@code CaptureMask} and no {@code BrowserMask} registered: nothing is masked and no mask is called;</li>
 *   <li>the union of every registered {@code BrowserMask}: TRUE when one answers TRUE or throws (logged once per mask
 *       class), FALSE when one answers FALSE and none TRUE, null otherwise;</li>
 *   <li>TRUE masks the browser whatever its view;</li>
 *   <li>a browser without a component is masked unless the union is FALSE (the view-less rule);</li>
 *   <li>a browser with a component: its {@code CaptureMask} read decides (a FALSE never releases it).</li>
 * </ol>
 * The masks are looked up per evaluation (a module's mask applies from its {@code install} and stops with its
 * uninstall) and called on the agent worker that rebuilds the index or serves the call, never on the EDT and never on an
 * adapter's thread.
 *
 * <p>Thread-safe.
 */
final class BrowserMasks {

    private final AgentContext ctx;
    /** The mask classes already logged as failing; guarded by itself. */
    private final Set<String> logged = new HashSet<String>();

    BrowserMasks(AgentContext ctx) {
        this.ctx = ctx;
    }

    /** True when the context's registry holds a {@code BrowserMask}; a context without a registry holds none. */
    static boolean anyRegistered(AgentContext ctx) {
        if (ctx == null || ctx.spi() == null) {
            return false;
        }
        List<BrowserMask> all = ctx.spi().all(BrowserMask.class);
        return all != null && !all.isEmpty();
    }

    /**
     * Steps 1 to 4 for one entry: {@code FALSE} when nothing is registered, {@code TRUE} when the union is TRUE or the
     * browser has no component and is not released, null when it has a component and its {@code CaptureMask} read
     * decides.
     *
     * @param registered what {@link SeedWalk#masksRegistered()} answered for this evaluation
     * @param component the browser's component as the caller knows it, or null (no view)
     */
    Boolean decide(boolean registered, Entry e, Object component) {
        if (!registered) {
            return Boolean.FALSE;
        }
        Boolean named = named(e, component != null);
        if (Boolean.TRUE.equals(named)) {
            return Boolean.TRUE;
        }
        if (component == null) {
            return Boolean.valueOf(!Boolean.FALSE.equals(named));
        }
        return null;
    }

    /**
     * Step 2, the union of every registered {@code BrowserMask} for the entry: TRUE, FALSE or null (no opinion). A mask
     * that throws, a registry that throws and a vendor object that cannot be read count TRUE (the safe side).
     */
    Boolean named(Entry e, boolean hasView) {
        List<BrowserMask> masks;
        try {
            masks = ctx == null || ctx.spi() == null ? null : ctx.spi().all(BrowserMask.class);
        } catch (RuntimeException ex) {
            logOnce("the SPI registry", ex);
            return Boolean.TRUE;
        }
        if (masks == null || masks.isEmpty()) {
            return null;
        }
        Object vendor;
        try {
            vendor = e.vendor();
        } catch (RuntimeException ex) {
            return Boolean.TRUE;
        }
        if (vendor == null) {
            return Boolean.TRUE;
        }
        boolean released = false;
        for (BrowserMask mask : masks) {
            Boolean answer;
            try {
                answer = mask.masks(e.tag, vendor, hasView);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) {
                    throw (VirtualMachineError) t;
                }
                logOnce(mask.getClass().getName(), t);
                return Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(answer)) {
                return Boolean.TRUE;
            }
            if (Boolean.FALSE.equals(answer)) {
                released = true;
            }
        }
        return released ? Boolean.FALSE : null;
    }

    /** Logs a failing mask once per class, by the exception's class name only (its message may quote page data). */
    private void logOnce(String name, Throwable t) {
        boolean first;
        synchronized (logged) {
            first = logged.add(name);
        }
        if (first && ctx != null) {
            ctx.log().warn("web: BrowserMask " + name + " failed (" + t.getClass().getName() + "); the browser is "
                    + "masked (the safe side); logged once");
        }
    }

    /** For tests: the classes logged as failing. */
    Set<String> logged() {
        synchronized (logged) {
            return Collections.unmodifiableSet(new HashSet<String>(logged));
        }
    }
}
