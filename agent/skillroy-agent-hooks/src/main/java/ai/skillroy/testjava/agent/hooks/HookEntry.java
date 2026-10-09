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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.protocol.hook.HookCapture;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One installed hook in the handler's registry: its spec, its sites, and what it has done since the install (the
 * figures {@code hook.health} reports). The mode lives here, not in the bytecode (section 3.4).
 *
 * <p>The figures are written by application threads (the handler) and read by agent workers; safe to share between
 * threads.
 */
final class HookEntry {

    final String id;
    final HookSpec spec;
    final HookMode mode;
    final String topic;
    final Set<Integer> secretArgs;
    final boolean secretResult;
    /** What a CAPTURE hook copies; never null for CAPTURE (possibly empty), null otherwise. */
    final HookCapture capture;
    /** A DEGRADED probe's reason (the hook is installed with it), or null. */
    final String degraded;
    /** The sites, set once at install before the hook is reachable from the handler. */
    volatile List<Site> sites = Collections.emptyList();
    /** The ledger entries of the classes the hook's sites are in (for STALE and the restore's verdict). */
    volatile List<Ledger.Entry> classes = Collections.emptyList();

    final AtomicLong count = new AtomicLong();
    volatile long lastFiredMs;
    volatile Map<String, Object> last;
    /** Why the hook is in ERROR (the handler or a mask policy threw, a retransform or restore failed), or null. */
    volatile String error;

    HookEntry(HookSpec spec, String degraded) {
        this.id = spec.id();
        this.spec = spec;
        this.mode = spec.mode();
        this.topic = spec.topic();
        this.secretArgs = Collections.unmodifiableSet(new HashSet<Integer>(spec.secretArgs()));
        this.secretResult = spec.secretResult();
        this.capture = spec.mode() == HookMode.CAPTURE
                ? (spec.capture() == null ? new HookCapture(null, false, false) : spec.capture()) : null;
        this.degraded = degraded;
    }

    void sites(List<Site> list) {
        this.sites = Collections.unmodifiableList(new ArrayList<Site>(list));
    }

    /** True when a STUB hook holds a value on any of its sites. */
    boolean armed() {
        for (Site s : sites) {
            if (s.armed != null) {
                return true;
            }
        }
        return false;
    }

    void fired(Map<String, Object> payload) {
        count.incrementAndGet();
        lastFiredMs = System.currentTimeMillis();
        last = payload;
    }
}
