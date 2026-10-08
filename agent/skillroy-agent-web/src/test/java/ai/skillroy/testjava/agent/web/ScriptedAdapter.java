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

import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.web.CdpEndpoint;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link WebEngineAdapter} whose answers a test sets: its probe, its browsers ({@link ScriptedBrowser}s; one marked
 * seed-only is found only when its vendor object or its token is among the seeds), its enumeration state and notes,
 * its endpoints. It records what the module asked of it.
 */
final class ScriptedAdapter implements WebEngineAdapter {

    final String id;
    final List<ScriptedBrowser> browsers = new CopyOnWriteArrayList<ScriptedBrowser>();
    final List<List<Object>> seedsSeen = new CopyOnWriteArrayList<List<Object>>();
    final List<CdpEndpoint> endpoints = new CopyOnWriteArrayList<CdpEndpoint>();
    final AtomicInteger probes = new AtomicInteger();
    final AtomicInteger detaches = new AtomicInteger();
    volatile ProbeResult probe;
    volatile ProbeResult enumeration = ProbeResult.ok();
    volatile List<String> notes = Collections.emptyList();
    volatile RuntimeException failEnumeration;

    ScriptedAdapter(String id) {
        this.id = id;
        this.probe = ProbeResult.ok(id + " 1");
    }

    /** Adds a browser to the technology. */
    ScriptedBrowser add(ScriptedBrowser b) {
        browsers.add(b);
        return b;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProbeResult probe(ClassLoader hostLoader) {
        probes.incrementAndGet();
        return probe;
    }

    @Override
    public List<WebBrowser> browsers(Collection<?> seeds) {
        seedsSeen.add(new ArrayList<Object>(seeds));
        if (failEnumeration != null) {
            throw failEnumeration;
        }
        Map<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        for (Object s : seeds) {
            seen.put(s, Boolean.TRUE);
        }
        List<WebBrowser> out = new ArrayList<WebBrowser>();
        for (ScriptedBrowser b : browsers) {
            if (b.removed) {
                continue;
            }
            if (b.seedOnly && !seen.containsKey(b.vendorObject()) && !seen.containsKey(b.seedToken)) {
                continue;
            }
            out.add(b);
        }
        return out;
    }

    @Override
    public List<CdpEndpoint> cdpEndpoints(Collection<?> seeds) {
        return new ArrayList<CdpEndpoint>(endpoints);
    }

    @Override
    public ProbeResult enumerationState() {
        return enumeration;
    }

    @Override
    public List<String> enumerationNotes() {
        return notes;
    }

    @Override
    public void detach() {
        detaches.incrementAndGet();
        for (ScriptedBrowser b : browsers) {
            b.detached = true;
        }
    }
}
