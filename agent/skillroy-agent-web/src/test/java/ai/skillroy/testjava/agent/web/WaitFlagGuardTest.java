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

import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebScriptException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code web.waitFlag} on the agent path with the shared guard of amendment A35 (phase4-contracts.md sections 4.2 and
 * 16.1), against a scripted browser whose fake page evaluates the guard ({@code WebLib.flagExpression}): a poll whose
 * expression throws counts as not ready and the wait goes on until the flag turns true; a flag that throws until the
 * end answers {@code ready: false} with the last poll's error in {@code lastError} (and none when the last poll did
 * not throw); an expression that does not compile is {@code SCRIPT_ERROR} at once, after one poll. The fake page reads
 * {@code window.desk.ready === true} as a page does: it throws a {@code TypeError} while {@code desk} is undefined.
 */
class WaitFlagGuardTest {

    private static final String FLAG = "window.desk.ready === true";
    private static final String NOT_YET = "TypeError: Cannot read properties of undefined (reading 'ready')";
    private static final long BOUND_MS = 10000;

    private ScriptedAdapter adapter;
    private ScriptedBrowser browser;

    private Harness harness() throws Exception {
        adapter = new ScriptedAdapter("scripted");
        browser = adapter.add(new ScriptedBrowser("scripted"));
        browser.url = "app://desk/";
        browser.load("<html><head><title>Desk</title></head><body><p id=\"status\">idle</p></body></html>");
        JPanel view = new JPanel();
        browser.component = view;
        Harness h = new Harness(adapter).install();
        h.walk.components.add(view);
        h.walk.showing.add(view);
        return h.ready();
    }

    /** Answers every evaluation as the fake page does and keeps the JSON of each answer to a poll of the guard. */
    private List<String> recordAnswers() {
        final List<String> answers = new CopyOnWriteArrayList<String>();
        browser.responder = new ScriptedBrowser.Responder() {
            @Override
            public CompletableFuture<String> answer(WebEval eval) {
                CompletableFuture<String> out = browser.byDocument(eval);
                if (eval.expression().equals(WebLib.flagExpression(FLAG))) {
                    answers.add(out.getNow("(pending)"));
                }
                return out;
            }
        };
        return answers;
    }

    private int polls(String expression) {
        int n = 0;
        for (WebEval e : browser.evals) {
            if (e.expression().equals(WebLib.flagExpression(expression))) {
                n++;
            }
        }
        return n;
    }

    private void awaitPolls(String expression, int n) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
        while (polls(expression) < n && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(polls(expression) >= n, "at least " + n + " polls of the guard");
    }

    @Test
    void aFlagThatThrowsForTheFirstPollsThenTurnsTrueIsReady() throws Exception {
        try (final Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            List<String> answers = recordAnswers();
            long seq = h.ctx.events.lastSeq();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<Object> wait = caller.submit(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return h.ctx.call("web.waitFlag", Harness.args("expression", FLAG, "pollMs", Long.valueOf(5),
                                "timeoutMs", Long.valueOf(8000)));
                    }
                });
                awaitPolls(FLAG, 3);
                browser.document.pageGlobal("desk", Collections.singletonMap("ready", Boolean.TRUE));
                Map<?, ?> out = (Map<?, ?>) wait.get(BOUND_MS, TimeUnit.MILLISECONDS);
                assertEquals(Boolean.TRUE, out.get("ready"), String.valueOf(out));
                assertTrue(((Number) out.get("polls")).longValue() >= 4, String.valueOf(out));
                assertFalse(out.containsKey("lastError"), "no lastError when ready: " + out);
            } finally {
                caller.shutdownNow();
            }
            Map<String, Object> threw = Json.parseObject(answers.get(0));
            assertEquals(Boolean.FALSE, threw.get("ready"));
            assertEquals(NOT_YET, threw.get("error"), "the first polls threw and counted as not ready");
            assertEquals(Collections.singletonMap("ready", Boolean.TRUE), Json.parseObject(
                    answers.get(answers.size() - 1)));
            Event flag = h.ctx.events.awaitTopic("web.ready", seq, Harness.args("signal", "FLAG"), BOUND_MS);
            assertEquals(Harness.args("browser", ref, "url", "app://desk/", "signal", "FLAG"), flag.payload());
            for (WebEval e : browser.evals) {
                if (e.expression().contains("desk.ready")) {
                    assertEquals(WebLib.flagExpression(FLAG), e.expression(), "every poll is the shared guard");
                }
            }
        }
    }

    @Test
    void aFlagThatThrowsUntilTheEndAnswersReadyFalseWithTheLastPollsError() throws Exception {
        try (Harness h = harness()) {
            long started = System.nanoTime();
            Map<String, Object> out = h.ctx.map("web.waitFlag", Harness.args("expression", FLAG + " ;\n",
                    "timeoutMs", Long.valueOf(300), "pollMs", Long.valueOf(20)));
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(Boolean.FALSE, out.get("ready"));
            assertEquals(NOT_YET, out.get("lastError"));
            assertTrue(((Number) out.get("polls")).longValue() >= 2, String.valueOf(out));
            assertTrue(ms >= 250 && ms < 3000, "polled on until timeoutMs, not ended at the first throw: " + ms);
            assertEquals(polls(FLAG), ((Number) out.get("polls")).intValue(), "trailing semicolons are dropped");
        }
    }

    @Test
    void lastErrorIsTheLastPollsOnly() throws Exception {
        try (final Harness h = harness()) {
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<Object> wait = caller.submit(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return h.ctx.call("web.waitFlag", Harness.args("expression", FLAG, "pollMs", Long.valueOf(5),
                                "timeoutMs", Long.valueOf(600)));
                    }
                });
                awaitPolls(FLAG, 2);
                browser.document.pageGlobal("desk", Collections.singletonMap("ready", Boolean.FALSE));
                Map<?, ?> out = (Map<?, ?>) wait.get(BOUND_MS, TimeUnit.MILLISECONDS);
                assertEquals(Boolean.FALSE, out.get("ready"));
                assertFalse(out.containsKey("lastError"), "the last poll did not throw: " + out);
            } finally {
                caller.shutdownNow();
            }
        }
    }

    @Test
    void aChannelThatReportsAScriptErrorForTheGuardCountsThePollAsNotReady() throws Exception {
        try (Harness h = harness()) {
            StringBuilder longLine = new StringBuilder("Error: ");
            for (int i = 0; i < 3000; i++) {
                longLine.append('x');
            }
            final String message = longLine + "\n    at <anonymous>:1:1";
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    if (!eval.expression().equals(WebLib.flagExpression(FLAG))) {
                        return browser.byDocument(eval);
                    }
                    CompletableFuture<String> failed = new CompletableFuture<String>();
                    failed.completeExceptionally(new WebScriptException(message, "at <anonymous>:1:1"));
                    return failed;
                }
            };
            Map<String, Object> out = h.ctx.map("web.waitFlag", Harness.args("expression", FLAG,
                    "timeoutMs", Long.valueOf(200)));
            assertEquals(Boolean.FALSE, out.get("ready"));
            assertEquals(WebLib.errorLine(message), out.get("lastError"), "the first line, cut at 2000");
            assertEquals(WebLib.MAX_ERROR_CHARS, ((String) out.get("lastError")).length());
        }
    }

    @Test
    void anExpressionThatDoesNotCompileIsScriptErrorAtOnce() throws Exception {
        try (Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            for (String expression : new String[] {"nonsense(", "document.title ==="}) {
                int before = polls(expression);
                long started = System.nanoTime();
                RpcException e = h.ctx.error("web.waitFlag", Harness.args("expression", expression,
                        "timeoutMs", Long.valueOf(5000), "pollMs", Long.valueOf(20)));
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertEquals(ErrorCodes.SCRIPT_ERROR, e.error().code(), expression);
                assertTrue(ms < 2000, "at once, not after the wait's 5000 ms: " + ms);
                assertEquals(1, polls(expression) - before, "one poll: " + expression);
                Map<String, Object> data = e.error().data();
                assertEquals(ref, data.get("browser"));
                assertTrue(String.valueOf(data.get("message")).startsWith("SyntaxError: "), String.valueOf(data));
                assertEquals("", data.get("stack"));
                assertEquals("script error in browser " + ref + ": " + data.get("message"), e.error().message());
            }
        }
    }
}
