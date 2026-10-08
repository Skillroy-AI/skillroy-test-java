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

package ai.skillroy.testjava.samples.webhost.scenarios;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.samples.webhost.pack.WebHostFacts;
import ai.skillroy.testjava.samples.webhost.pack.WebHostPack;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The webhost pack without a target: its id, sync policy, facts and recovery against a scripted connection. */
class WebHostPackTest {

    @Test
    void thePackIsFoundAndHasNoVerbsOfItsOwn() {
        List<String> ids = new ArrayList<>();
        ServiceLoader.load(ai.skillroy.testjava.scenario.pack.DomainPack.class).forEach(p -> ids.add(p.id()));
        assertEquals(List.of(WebHostPack.ID), ids);
        WebHostPack pack = new WebHostPack();
        assertEquals("webhost", pack.id());
        assertEquals("0.1.0", pack.version());
        assertEquals(List.of(), List.copyOf(pack.steps().verbs()));
        assertThrows(ScenarioException.class, () -> pack.steps().compile("borrow", Map.of(), null));
        assertEquals(List.of(), pack.preflight());
    }

    /** Section 9.3: ack null, ready web.ready, and no prompt topics (a null list would mean prompt.waiting). */
    @Test
    void theSyncPolicyWaitsForWebReadyAndLooksForNoPrompt() {
        SyncPolicy policy = new WebHostPack().syncPolicy();
        assertEquals(null, policy.ackTopic());
        assertEquals(List.of("web.ready"), policy.readyTopics());
        assertEquals(List.of(), policy.promptTopics());
        assertEquals(null, policy.ackTimeout());
        assertEquals(null, policy.readyTimeout());
    }

    @Test
    void theRootNamesTheThreeBrowsersByTagWithTheirStatusLines() throws Exception {
        Scripted agent = new Scripted((method, params) -> switch (method) {
            case "web.browsers" -> Map.of("browsers", List.of(
                    browser("h1", "front", "data:text/html,...webhost-front...", true, true),
                    browser("h2", "back", "data:text/html,...webhost-back...", false, true),
                    browser("h3", "detached", "data:text/html,...webhost-detached...", false, false)));
            case "web.dom.text" -> Map.of("text", " borrowed hammer x 2 \n", "truncated", false);
            default -> throw new AssertionError(method);
        });
        Map<?, ?> root = (Map<?, ?>) facts().fetch(agent);
        assertEquals(List.of("front", "back", "detached"), List.copyOf(root.keySet()));
        Map<?, ?> front = (Map<?, ?>) root.get("front");
        assertEquals("h1", front.get("ref"));
        assertEquals("front", front.get("tag"));
        assertEquals("front", front.get("page"));
        assertEquals(Boolean.TRUE, front.get("visibleInSwing"));
        assertEquals("borrowed hammer x 2", front.get("status"));
        assertFalse(front.containsKey("url"), "the long data: URL is left out");
        assertFalse(((Map<?, ?>) root.get("detached")).containsKey("status"), "no EVALUATE, no status read");
        assertEquals(List.of("web.browsers", "web.dom.text", "web.dom.text"), agent.methods());
        assertEquals(Map.of("browser", "h1", "selector", "#status", "maxChars", 200L), agent.params().get(1));
    }

    @Test
    void withoutTagsTheMarkerInTheUrlFindsThePageAndAFailingStatusReadIsLeftOut() throws Exception {
        Scripted agent = new Scripted((method, params) -> {
            if (method.equals("web.dom.text")) {
                throw new AgentRpcException(method, new RpcError(-32008, "boom", null));
            }
            return Map.of("browsers", List.of(browser("h7", null, "data:,<body id=\"webhost-back\">", false, true),
                    browser("h8", null, "data:,<body id=\"webhost-front\"> background", true, true),
                    browser("h9", null, "about:blank", true, true)));
        });
        Map<?, ?> root = (Map<?, ?>) facts().fetch(agent);
        assertEquals(List.of("front", "back"), List.copyOf(root.keySet()));
        assertEquals("h8", ((Map<?, ?>) root.get("front")).get("ref"));
        assertEquals("h7", ((Map<?, ?>) root.get("back")).get("ref"));
        assertFalse(((Map<?, ?>) root.get("front")).containsKey("status"));
        assertEquals("front", ((Map<?, ?>) root.get("front")).get("page"));
        assertEquals(null, WebHostFacts.pageOf("data:,webhost-front webhost-back"), "two markers: no page");
    }

    @Test
    void aFailingBrowserListFailsTheRead() {
        Scripted agent = new Scripted((method, params) -> {
            throw new AgentRpcException(method, new RpcError(-32002, "unavailable", null));
        });
        assertThrows(AgentRpcException.class, () -> facts().fetch(agent));
    }

    /** Recovery: the reset event to the front page by its tag, then the settle; by the URL marker when untagged. */
    @Test
    void recoveryResetsTheFrontPageAndSettles() throws Exception {
        Scripted tagged = new Scripted((method, params) -> Map.of());
        new WebHostPack().recover(tagged);
        assertEquals(List.of("web.dispatchEvent", "swing.settle"), tagged.methods());
        assertEquals(Map.of("by", Map.of("tag", "front"), "name", "webhost.reset"), tagged.params().get(0));
        assertEquals(Map.of("timeoutMs", 5000L), tagged.params().get(1));

        Scripted untagged = new Scripted((method, params) -> {
            if (method.equals("web.dispatchEvent") && params.get("by") instanceof Map<?, ?> by
                    && by.containsKey("tag")) {
                throw new AgentRpcException(method, new RpcError(-32006, "no browser", null));
            }
            return Map.of();
        });
        new WebHostPack().recover(untagged);
        assertEquals(List.of("web.dispatchEvent", "web.dispatchEvent", "swing.settle"), untagged.methods());
        assertEquals(Map.of("by", Map.of("url", "webhost-front"), "name", "webhost.reset"),
                untagged.params().get(1));

        Scripted broken = new Scripted((method, params) -> {
            throw new AgentRpcException(method, new RpcError(-32002, "unavailable", null));
        });
        assertThrows(AgentRpcException.class, () -> new WebHostPack().recover(broken));
        assertEquals(List.of("web.dispatchEvent"), broken.methods(), "another error is not retried");
    }

    private static FactProvider facts() {
        List<FactProvider> facts = new WebHostPack().facts();
        assertEquals(1, facts.size());
        assertEquals("webhost", facts.get(0).root());
        return facts.get(0);
    }

    private static Map<String, Object> browser(String ref, String tag, String url, boolean visible,
                                               boolean evaluate) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ref", ref);
        b.put("adapter", "jcef");
        b.put("url", url);
        b.put("title", "Page " + ref);
        b.put("closed", false);
        b.put("visibleInSwing", visible);
        b.put("rendering", "OFFSCREEN_HEAVYWEIGHT");
        b.put("capabilities", evaluate ? List.of("EVALUATE", "FRAMES") : List.of());
        if (tag != null) {
            b.put("tag", tag);
        }
        return b;
    }

    /** Answers calls from a function and records them. */
    static final class Scripted implements AgentConnection {

        interface Answer {
            Object answer(String method, Map<String, Object> params) throws AgentRpcException;
        }

        private final Answer answer;
        private final List<String> methods = new ArrayList<>();
        private final List<Map<String, Object>> params = new ArrayList<>();

        Scripted(Answer answer) {
            this.answer = answer;
        }

        List<String> methods() {
            return methods;
        }

        List<Map<String, Object>> params() {
            return params;
        }

        @Override
        public RpcResult call(String method, Map<String, Object> p) throws AgentRpcException {
            return call(method, p, CallOptions.defaults());
        }

        @Override
        public RpcResult call(String method, Map<String, Object> p, CallOptions options) throws AgentRpcException {
            assertTrue(options.timeoutMs() != null && options.timeoutMs() > 0, "every call is bounded");
            methods.add(method);
            params.add(p == null ? Map.of() : new LinkedHashMap<>(p));
            return new RpcResult(answer.answer(method, p == null ? Map.of() : p), List.of(), 0, 0, 1, "worker");
        }

        @Override
        public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CapabilityMatrix capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PrimitiveDescriptor> schema() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> ping() throws AgentLostException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
