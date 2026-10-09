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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What every part of the probe shares. */
final class Ctx {

    final Instrumentation inst;
    final Map<String, String> opts;
    final Report r;
    final HostView host;
    final EventRing ring;
    final HookRegistry registry;
    final HookEngine engine;
    final Ticker ticker;
    private final List<String> failed = new ArrayList<String>();
    private int checks;

    Ctx(Instrumentation inst, Map<String, String> opts, Report r, HostView host, EventRing ring,
        HookRegistry registry, HookEngine engine, Ticker ticker) {
        this.inst = inst;
        this.opts = opts;
        this.r = r;
        this.host = host;
        this.ring = ring;
        this.registry = registry;
        this.engine = engine;
        this.ticker = ticker;
    }

    /** Records a pass/fail check under {@code check.<name>}. */
    boolean check(String name, boolean ok, Object detail) {
        checks++;
        if (!ok) {
            failed.add(name);
        }
        r.put("check." + name, (ok ? "PASS" : "FAIL") + (detail == null ? "" : " " + detail));
        return ok;
    }

    List<String> failed() {
        return failed;
    }

    int checks() {
        return checks;
    }

    File spyJar() {
        return new File(opts.get("spyJar"));
    }

    boolean flag(String key, boolean dflt) {
        String v = opts.get(key);
        return v == null ? dflt : Boolean.parseBoolean(v);
    }

    void events(String label, long since) {
        for (EventRing.Event e : ring.since(since)) {
            r.line("  event " + label + ": " + e);
        }
    }

    int countEvents(long since, String hookId, String phase) {
        int n = 0;
        for (EventRing.Event e : ring.since(since)) {
            if (e.hookId.equals(hookId) && (phase == null || e.phase.equals(phase))) {
                n++;
            }
        }
        return n;
    }

    static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                b.append('.');
            }
            b.append(parts[i]);
        }
        return b.toString();
    }
}
