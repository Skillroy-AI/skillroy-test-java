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

/**
 * Which embedded browsers show a secret, by what the pack knows of them: the seed's tag and the vendor's browser
 * object (Phase 8, gap 110; phase8-contracts.md section 5.2, amendment A79). Registered through
 * {@code AgentContext.spi()} ({@code SpiRegistry.register(BrowserMask.class, ...)}) during {@code AgentModule.install},
 * looked up at every evaluation and withdrawn with the module.
 *
 * <p>While at least one {@code CaptureMask} or one {@code BrowserMask} is registered, a browser the adapter reports no
 * Swing component for is masked unless a {@code BrowserMask} releases it (the view-less rule); a masked browser is
 * masked on every route of the web module: its four reads are refused, {@code web.browsers} withholds its URL, title
 * and frames, its events withhold page data, {@code by: {url|title}} never selects it and the actions that stay allowed
 * on it answer without page data.
 *
 * <p>Called on an agent worker at each index rebuild and before each masked-read check, never on the EDT; must be
 * fast, must not block and must call no method of the vendor object (compare it by identity: it may belong to a vendor
 * and block or throw). A mask that throws masks (the safe side), and is logged once per mask class.
 */
public interface BrowserMask {

    /**
     * Whether this browser is masked. Masks combine by union: one {@code TRUE} masks.
     *
     * @param tag the browser's own seed tag, or null (a browser found through an engine, client, app or profile seed,
     *     or by the Swing-tree scan alone)
     * @param vendorObject {@code WebBrowser.vendorObject()}: the vendor's browser object, never null
     * @param hasView whether the adapter reports a Swing component for it at this evaluation
     * @return {@code TRUE}: mask this browser on every route, whatever its view; {@code FALSE}: it shows nothing secret
     *     while it has no view (releases it from the view-less rule; a {@code FALSE} never overrides a
     *     {@code CaptureMask} that names its component); null: no opinion
     */
    Boolean masks(String tag, Object vendorObject, boolean hasView);
}
