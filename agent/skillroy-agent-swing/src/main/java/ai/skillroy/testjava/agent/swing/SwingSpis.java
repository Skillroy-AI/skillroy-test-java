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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.ActionFirer;
import ai.skillroy.testjava.agent.api.spi.LocatorResolver;
import ai.skillroy.testjava.agent.api.spi.RegionResolver;
import ai.skillroy.testjava.agent.api.spi.SurfaceSource;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * The Phase 7 SPIs as the module calls them (phase7-contracts.md sections 3.2, 3.7): {@link RegionResolver} in
 * registration order (the first that supports the component and answers wins), the registered
 * {@link SurfaceSource}s, {@link LocatorResolver#describe} and {@link ActionFirer#canFire}. Every call is guarded: an
 * implementation that throws is logged once per instance and ignored for that call (as a {@code NameEnricher} is,
 * P1 section 4). One instance serves one primitive call; EDT only.
 */
final class SwingSpis {

    /** The region id pattern (phase7-contracts.md section 3.7). */
    static final Pattern REGION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:._-]{0,63}");

    private static final Map<Object, Boolean> LOGGED = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private final AgentContext ctx;
    private final List<RegionResolver> regions;
    private final List<SurfaceSource> sources;
    private final List<LocatorResolver> resolvers;
    private final List<ActionFirer> firers;

    SwingSpis(AgentContext ctx) {
        this.ctx = ctx;
        this.regions = ctx.spi().all(RegionResolver.class);
        this.sources = ctx.spi().all(SurfaceSource.class);
        this.resolvers = ctx.spi().all(LocatorResolver.class);
        this.firers = ctx.spi().all(ActionFirer.class);
    }

    /** True for a valid region id. */
    static boolean validRegionId(String id) {
        return id != null && REGION_ID.matcher(id).matches();
    }

    /** The first resolver that supports the component, or null. */
    RegionResolver resolverFor(Component c) {
        for (RegionResolver r : regions) {
            try {
                if (r.supports(c)) {
                    return r;
                }
            } catch (Throwable t) {
                failed(r, "supports", t);
            }
        }
        return null;
    }

    /** The region under a point of the component's own coordinates, with its resolver; null when none answers. */
    Object[] regionAt(Component c, Point local) {
        for (RegionResolver r : regions) {
            try {
                if (r.supports(c)) {
                    RegionResolver.Region region = r.regionAt(c, local);
                    if (region != null && validRegionId(region.id) && region.rect != null) {
                        return new Object[] {region, r};
                    }
                }
            } catch (Throwable t) {
                failed(r, "regionAt", t);
            }
        }
        return null;
    }

    /** The rectangle of a region id in the component's own coordinates, or null when no resolver knows it. */
    Rectangle rectOf(Component c, String id) {
        for (RegionResolver r : regions) {
            try {
                if (r.supports(c)) {
                    Rectangle rect = r.rectOf(c, id);
                    if (rect != null) {
                        return new Rectangle(rect);
                    }
                }
            } catch (Throwable t) {
                failed(r, "rectOf", t);
            }
        }
        return null;
    }

    /** Every region of the component from the first resolver that supports it; empty when none does. */
    List<RegionResolver.Region> regionsOf(Component c) {
        RegionResolver r = resolverFor(c);
        if (r == null) {
            return Collections.emptyList();
        }
        try {
            List<RegionResolver.Region> all = r.regions(c);
            if (all == null) {
                return Collections.emptyList();
            }
            List<RegionResolver.Region> out = new ArrayList<RegionResolver.Region>();
            for (RegionResolver.Region region : all) {
                if (region != null && validRegionId(region.id) && region.rect != null) {
                    out.add(region);
                }
            }
            return out;
        } catch (Throwable t) {
            failed(r, "regions", t);
            return Collections.emptyList();
        }
    }

    /** The surfaces every registered source knows now. */
    List<SurfaceSource.Surface> surfaces() {
        List<SurfaceSource.Surface> out = new ArrayList<SurfaceSource.Surface>();
        for (SurfaceSource s : sources) {
            try {
                List<SurfaceSource.Surface> list = s.surfaces();
                if (list != null) {
                    for (SurfaceSource.Surface surface : list) {
                        if (surface != null && surface.component != null) {
                            out.add(surface);
                        }
                    }
                }
            } catch (Throwable t) {
                failed(s, "surfaces", t);
            }
        }
        return out;
    }

    /** The surface that claims the component or one of its ancestors, or null. */
    SurfaceSource.Surface claiming(List<SurfaceSource.Surface> surfaces, Component c) {
        for (Component p = c; p != null; p = p.getParent()) {
            for (SurfaceSource.Surface s : surfaces) {
                if (s.component == p) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Every resolver's {@code describe} entries for the component, in registration order (strategy to value). */
    Map<String, String> describe(Component c) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (LocatorResolver r : resolvers) {
            try {
                Map<String, String> d = r.describe(c);
                if (d != null) {
                    for (Map.Entry<String, String> e : d.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null && !out.containsKey(e.getKey())) {
                            out.put(e.getKey(), e.getValue());
                        }
                    }
                }
            } catch (Throwable t) {
                failed(r, "describe", t);
            }
        }
        return out;
    }

    /** True when a registered {@link ActionFirer} accepts the component. */
    boolean fires(Component c) {
        for (ActionFirer f : firers) {
            try {
                if (f.canFire(c)) {
                    return true;
                }
            } catch (Throwable t) {
                failed(f, "canFire", t);
            }
        }
        return false;
    }

    private void failed(Object impl, String what, Throwable t) {
        if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) {
            throw (VirtualMachineError) t;
        }
        if (LOGGED.put(impl, Boolean.TRUE) == null) {
            try {
                ctx.log().warn("swing: " + impl.getClass().getName() + "." + what + " failed and is ignored: " + t,
                        t);
            } catch (Throwable ignored) {
                // never the reason a read fails
            }
        }
    }
}
