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

package ai.skillroy.testjava.agent.api.spi.web;

import ai.skillroy.testjava.agent.api.ProbeResult;

import java.util.Collection;
import java.util.List;

/**
 * One per embedded-browser technology: detects the technology in the target, turns seeds into {@link WebBrowser}
 * handles and reports the remote-debugging endpoints configured at engine start. Built in: {@code "jcef"} and
 * {@code "jxbrowser"} (agent module {@code skillroy-agent-web}); a pack registers another through
 * {@code SpiRegistry.register(WebEngineAdapter.class, …)}. Thread-safe; called on agent workers.
 *
 * <p>Rules every adapter obeys, each tested by the adapter contract kit (the {@code skillroy-agent-web} test-jar):
 * <ol>
 *   <li><b>No engine is created, initialised or configured.</b> JCEF: never {@code CefApp.getInstance()}, only
 *       {@code getState()} and, in state {@code INITIALIZED}, the existing instance. JxBrowser: never
 *       {@code Engine.newInstance}.</li>
 *   <li><b>Nothing single-slot of the application is taken.</b> No {@code add*Handler} on a {@code CefClient}, no
 *       {@code Browser.set(callback)}. The one exception is a pack's explicit opt-in to chain JxBrowser's
 *       {@code InjectJsCallback}, which restores the previous occupant on {@link #detach()}.</li>
 *   <li><b>The JCEF DevTools client is shared with the application</b> and is never closed by the agent; a client
 *       found closed is re-fetched and the agent's listeners re-subscribed; protocol domains the agent enabled stay
 *       enabled on detach.</li>
 *   <li><b>No wait on the EDT, no blocking vendor call on the EDT.</b></li>
 *   <li><b>The script library leaves no enumerable globals behind</b>: one non-enumerable slot,
 *       {@code window[Symbol.for("skillroy")]}, holds the library and the tap buffer.</li>
 *   <li><b>No vendor type in a signature or on the wire; no vendor artifact in any scope; no licence key read,
 *       logged or forwarded</b> (JxBrowser's {@code EngineOptions.licenseKey()} is off limits).</li>
 *   <li><b>A missing optional member is a missing capability, not an error</b>: the handle's
 *       {@link WebBrowser#capabilities()} omits it and the primitive that needs it answers
 *       {@code CAPABILITY_UNAVAILABLE} with the reason.</li>
 * </ol>
 */
public interface WebEngineAdapter {

    /**
     * Stable id: lower-case letters and digits ({@code "jcef"}, {@code "jxbrowser"}). Also the suffix of the
     * capability {@code web.adapter.<id>}.
     */
    String id();

    /**
     * Are the technology's classes reachable from the host's loader, and which optional operations resolve? Must not
     * create, initialise or configure an engine, and must not load a class the host has not loaded
     * ({@code Class.forName(name, false, hostLoader)} is the only lookup allowed; a class that is not yet loaded by
     * the host counts as absent). The strategy of an OK result names the product and line it found
     * ({@code "jcef 152"}, {@code "jxbrowser 7"}).
     *
     * @param hostLoader the loader of the target application's classes ({@code AgentContext.hostClassLoader()})
     * @return the result for the capability {@code web.adapter.<id>}: OK with the product and line as strategy, or
     *     DEGRADED or UNAVAILABLE with the reason
     */
    ProbeResult probe(ClassLoader hostLoader);

    /**
     * Turns seeds into handles for every live browser reachable from them. A seed is a Swing component the tree scan
     * found, or an object a {@link BrowserRegistryAdapter} handed in (a browser, a view, an engine or a client, as
     * that technology knows them). Seeds of another technology are ignored. May add browsers no seed pointed at
     * (JCEF: the process-wide list). The same live browser yields a handle that is {@code equal()} to the one
     * returned before, so that the module's {@code HandleRegistry} keeps one ref per browser. Never throws for a bad
     * seed: it is skipped and reported through {@link #enumerationNotes()}.
     *
     * @param seeds the Swing components of the tree scan and the {@link BrowserSeed#object() objects} of the seeds
     *     every {@link BrowserRegistryAdapter} returned; never null, may be empty
     * @return one handle per live browser found, each browser once; never null
     */
    List<WebBrowser> browsers(Collection<?> seeds);

    /**
     * The remote-debugging endpoints configured at engine start, one per engine, empty if none or unknown. A
     * read-only fact for {@code web.cdp.status}; the adapter never sets or probes a port.
     *
     * @param seeds as for {@link #browsers(Collection)}
     * @return never null
     */
    List<CdpEndpoint> cdpEndpoints(Collection<?> seeds);

    /**
     * How complete the last {@link #browsers(Collection)} call was: OK (every route worked), DEGRADED(reason) (seeds
     * only: the technology's own list could not be read), UNAVAILABLE(reason) (the technology is present but no
     * browser can be reached). Drives the capability {@code web.browsers}.
     */
    ProbeResult enumerationState();

    /**
     * Notes of the last {@link #browsers(Collection)} call that are not a state change: seeds that were skipped and
     * why, routes that answered partially. Informational (the module logs them once per change); empty by default.
     *
     * @return never null
     */
    default List<String> enumerationNotes() {
        return java.util.Collections.emptyList();
    }

    /**
     * Called once when the module is uninstalled: remove every listener and init script the adapter added, clear
     * viewport overrides, forget cached reflection. Must not close anything that belongs to the application. Never
     * throws.
     */
    void detach();
}
