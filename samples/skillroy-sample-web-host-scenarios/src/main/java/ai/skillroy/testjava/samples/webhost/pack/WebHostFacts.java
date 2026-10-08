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

package ai.skillroy.testjava.samples.webhost.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.scenario.pack.FactProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fact root {@value #ROOT}: the web host's three browsers by name, {@code front}, {@code back} and
 * {@code detached} (the last only with off-screen rendering), each
 * {@code {ref, tag?, page, title?, visibleInSwing, rendering, status?}}:
 *
 * <ul>
 *   <li>from {@code web.browsers}: the browser whose {@code tag} is the name (the sample's agent module tags its
 *       seeds so), else the single one whose URL holds the page's marker {@code webhost-<name>} (the body id of each
 *       page, which its {@code data:} URL carries verbatim);</li>
 *   <li>{@code page}: the name the browser's URL marker gives (or null); the URL itself, about 8 KB of encoded page,
 *       is left out;</li>
 *   <li>{@code status}: the text of the page's status line ({@code #status}, through {@code web.dom.text}), for a
 *       browser that can evaluate; left out when that read fails.</li>
 * </ul>
 * For example {@code webhost.front.status: "borrowed hammer x 2"} or {@code webhost.back.visibleInSwing: false}.
 *
 * <p>One read is bounded by {@value #BUDGET_MS} ms in all, as the base root {@code web}: a failing
 * {@code web.browsers} fails the read (the executor retries it until the step timeout), a status read that fails or
 * that the budget no longer allows is left out. Reads go through the connection the executor hands in, which in a run
 * sends {@code web.*} calls to the run's web path. Stateless; safe from any thread.
 */
public final class WebHostFacts implements FactProvider {

    /** The root name. */
    public static final String ROOT = "webhost";
    /** The host's page names, in the order the root lists them. */
    public static final List<String> PAGES = List.of("front", "back", "detached");
    /** The budget of one read of the root. */
    static final long BUDGET_MS = 10_000L;
    /** At most this many characters of a status line are read. */
    static final long STATUS_CHARS = 200L;

    @Override
    public String root() {
        return ROOT;
    }

    @Override
    public Object fetch(AgentConnection agent) throws Exception {
        long deadline = System.nanoTime() + BUDGET_MS * 1_000_000L;
        Object value = agent.call("web.browsers", Map.of(), CallOptions.defaults().withTimeoutMs(BUDGET_MS)).value();
        List<Map<?, ?>> browsers = new ArrayList<>();
        if (value instanceof Map<?, ?> map && map.get("browsers") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> browser && !Boolean.TRUE.equals(browser.get("closed"))) {
                    browsers.add(browser);
                }
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (String page : PAGES) {
            Map<?, ?> browser = find(browsers, page);
            if (browser != null) {
                root.put(page, entry(agent, browser, deadline));
            }
        }
        return root;
    }

    /** The browser tagged {@code page}, else the single one whose URL holds the page's marker; null if none. */
    static Map<?, ?> find(List<Map<?, ?>> browsers, String page) {
        for (Map<?, ?> browser : browsers) {
            if (page.equals(browser.get("tag"))) {
                return browser;
            }
        }
        Map<?, ?> found = null;
        for (Map<?, ?> browser : browsers) {
            if (page.equals(pageOf(browser.get("url")))) {
                if (found != null) {
                    return null;
                }
                found = browser;
            }
        }
        return found;
    }

    /** The page name the URL's marker {@code webhost-<name>} gives; null when none or more than one marker. */
    public static String pageOf(Object url) {
        if (!(url instanceof String text)) {
            return null;
        }
        String found = null;
        for (String page : PAGES) {
            if (text.contains("webhost-" + page)) {
                if (found != null) {
                    return null;
                }
                found = page;
            }
        }
        return found;
    }

    private static Map<String, Object> entry(AgentConnection agent, Map<?, ?> browser, long deadline)
            throws AgentLostException, InterruptedException {
        Map<String, Object> entry = new LinkedHashMap<>();
        Object ref = browser.get("ref");
        entry.put("ref", ref);
        if (browser.get("tag") != null) {
            entry.put("tag", browser.get("tag"));
        }
        entry.put("page", pageOf(browser.get("url")));
        if (browser.get("title") != null) {
            entry.put("title", browser.get("title"));
        }
        entry.put("visibleInSwing", browser.get("visibleInSwing"));
        entry.put("rendering", browser.get("rendering"));
        long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
        if (ref != null && remainingMs > 0 && browser.get("capabilities") instanceof List<?> capabilities
                && capabilities.contains("EVALUATE")) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("browser", ref);
            params.put("selector", "#status");
            params.put("maxChars", STATUS_CHARS);
            try {
                Object text = agent.call("web.dom.text", params, CallOptions.defaults().withTimeoutMs(remainingMs))
                        .value();
                if (text instanceof Map<?, ?> map && map.get("text") instanceof String status) {
                    entry.put("status", status.strip());
                }
            } catch (AgentRpcException e) {
                // The status is left out; the root still resolves.
            }
        }
        return entry;
    }
}
