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
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounded waits of phase4-contracts.md section 4.2: a future that does not complete within the call's budget
 * less 100 ms is abandoned with {@code cancel(false)} and the call answers {@code TIMEOUT} (its own, with
 * {@code data.browser}, before the dispatcher's); a read is retried once after a {@code web.load} event (BS8), also
 * when its first attempt failed because the page navigated under it, a mutating call never; failures of the adapter
 * map to {@code STALE_HANDLE} and {@code CAPABILITY_UNAVAILABLE}.
 */
class TimeoutAndRetryTest {

    private ScriptedAdapter adapter;
    private ScriptedBrowser browser;

    private Harness harness() throws Exception {
        adapter = new ScriptedAdapter("scripted");
        browser = adapter.add(new ScriptedBrowser("scripted"));
        JPanel view = new JPanel();
        browser.component = view;
        Harness h = new Harness(adapter).install();
        h.walk.components.add(view);
        h.walk.showing.add(view);
        return h.ready();
    }

    /** The first {@code hang} evaluations never answer; later ones answer through the fake page. */
    private static ScriptedBrowser.Responder hangFirst(final ScriptedBrowser b, final int hang) {
        final AtomicInteger n = new AtomicInteger();
        return new ScriptedBrowser.Responder() {
            @Override
            public CompletableFuture<String> answer(WebEval eval) {
                return n.incrementAndGet() <= hang ? new CompletableFuture<String>() : b.byDocument(eval);
            }
        };
    }

    @Test
    void aFutureThatDoesNotCompleteIsAbandonedAndTheCallAnswersTimeoutWithItsBrowser() throws Exception {
        try (Harness h = harness()) {
            String ref = BrowserIndexTest.ref(h, browser);
            browser.evals.clear();
            browser.handedOut.clear();
            browser.responder = hangFirst(browser, Integer.MAX_VALUE);
            long started = System.nanoTime();
            RpcException e = h.ctx.error("web.evaluate", Harness.args("expression", "1"),
                    Harness.args("timeoutMs", Long.valueOf(700)));
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(ErrorCodes.TIMEOUT, e.error().code());
            assertEquals(ref, e.error().data().get("browser"), "the module's own TIMEOUT, before the dispatcher's");
            assertEquals("web.evaluate", e.error().data().get("primitive"));
            assertTrue(ms < 900, "answered within the call's budget: " + ms + " ms");
            assertTrue(browser.handedOut.get(0).isCancelled(), "the future was abandoned with cancel(false)");
            assertEquals(1, browser.evals.size(), "never retried");
        }
    }

    @Test
    void aReadIsRetriedOnceWhenALoadEventArrivesWhileItWaits() throws Exception {
        try (final Harness h = harness()) {
            browser.responder = hangFirst(browser, 1);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<Object> call = caller.submit(new java.util.concurrent.Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return h.ctx.call("web.dom.text", Harness.args());
                    }
                });
                awaitEvals(1);
                browser.fire(WebEvent.loadFinished(null, "app://reloaded"));
                Map<?, ?> text = (Map<?, ?>) call.get(10, TimeUnit.SECONDS);
                assertEquals("ready", text.get("text"));
                assertEquals(2, browser.evals.size(), "sent once more after the load");
                assertTrue(browser.handedOut.get(0).isCancelled(), "the first attempt was abandoned");
            } finally {
                caller.shutdownNow();
            }
        }
    }

    @Test
    void aReadStartedJustAfterALoadIsSentAgainAfterTheFirstAttemptsGrace() throws Exception {
        try (Harness h = harness()) {
            browser.fire(WebEvent.loadFinished(null, "app://new"));
            browser.responder = hangFirst(browser, 1);
            long started = System.nanoTime();
            Map<String, Object> text = h.ctx.map("web.dom.text", Harness.args());
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals("ready", text.get("text"));
            assertEquals(2, browser.evals.size());
            assertTrue(ms >= Await.FIRST_ATTEMPT_AFTER_LOAD_MS - 100 && ms < 8000, ms + " ms");
        }
    }

    @Test
    void aMutatingCallIsNeverRetried() throws Exception {
        try (final Harness h = harness()) {
            browser.responder = hangFirst(browser, 1);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<RpcException> call = caller.submit(new java.util.concurrent.Callable<RpcException>() {
                    @Override
                    public RpcException call() {
                        return h.ctx.error("web.dom.click", Harness.args("selector", "#status"),
                                Harness.args("timeoutMs", Long.valueOf(1500)));
                    }
                });
                awaitEvals(1);
                browser.fire(WebEvent.loadFinished(null, "app://reloaded"));
                RpcException e = call.get(10, TimeUnit.SECONDS);
                assertEquals(ErrorCodes.TIMEOUT, e.error().code());
                assertEquals(1, browser.evals.size(), "a mutating call is not sent again");
            } finally {
                caller.shutdownNow();
            }
        }
    }

    /** What a JCEF client answers a call in flight when the page navigates (Chromium's protocol error). */
    static final String NAVIGATED = "DevTools Runtime.evaluate: FakeDevToolsException: DevTools method failed: "
            + "{\"code\":-32000,\"message\":\"Inspected target navigated or closed\"}";

    /** The first evaluation fails as the page navigates; the navigation's events follow when {@code go} is released. */
    private static ScriptedBrowser.Responder navigatedFirst(final ScriptedBrowser b, final CountDownLatch go) {
        final AtomicInteger n = new AtomicInteger();
        return new ScriptedBrowser.Responder() {
            @Override
            public CompletableFuture<String> answer(WebEval eval) {
                if (n.incrementAndGet() > 1) {
                    return b.byDocument(eval);
                }
                CompletableFuture<String> f = new CompletableFuture<String>();
                f.completeExceptionally(new IllegalStateException(NAVIGATED));
                Thread events = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (go.await(10, TimeUnit.SECONDS)) {
                                b.fire(WebEvent.navigated(null, "app://next"));
                                b.fire(WebEvent.loadFinished(null, "app://next"));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }, "page-navigation");
                events.setDaemon(true);
                events.start();
                return f;
            }
        };
    }

    @Test
    void aReadWhoseFirstAttemptFailedBecauseThePageNavigatedIsSentAgainAfterTheLoad() throws Exception {
        try (final Harness h = harness()) {
            browser.evals.clear();
            CountDownLatch go = new CountDownLatch(1);
            browser.responder = navigatedFirst(browser, go);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<Object> call = caller.submit(new java.util.concurrent.Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        return h.ctx.call("web.dom.text", Harness.args());
                    }
                });
                awaitEvals(1);
                go.countDown(); // the navigation's events arrive after the failure, as Chromium sends them
                Map<?, ?> text = (Map<?, ?>) call.get(10, TimeUnit.SECONDS);
                assertEquals("ready", text.get("text"), "answered by the new document");
                assertEquals(2, browser.evals.size(), "sent once more after the load");
            } finally {
                caller.shutdownNow();
            }
        }
    }

    @Test
    void aMutatingCallWhoseAttemptFailedBecauseThePageNavigatedIsNotSentAgain() throws Exception {
        try (Harness h = harness()) {
            browser.evals.clear();
            CountDownLatch go = new CountDownLatch(1);
            go.countDown();
            browser.responder = navigatedFirst(browser, go);
            RpcException e = h.ctx.error("web.dom.click", Harness.args("selector", "#status"));
            assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
            assertTrue(e.error().message().contains("Inspected target navigated or closed"), e.error().message());
            assertEquals(1, browser.evals.size(), "a mutating call is never sent again");
        }
    }

    @Test
    void aBrowserClosedDuringTheCallIsStaleAndAMissingOperationIsUnavailable() throws Exception {
        try (Harness h = harness()) {
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    browser.closed = true;
                    CompletableFuture<String> f = new CompletableFuture<String>();
                    f.completeExceptionally(new IllegalStateException("the browser has closed"));
                    return f;
                }
            };
            RpcException stale = h.ctx.error("web.dom.text", Harness.args());
            assertEquals(ErrorCodes.STALE_HANDLE, stale.error().code());
            assertEquals("browser", stale.error().data().get("kind"));
            browser.closed = false;
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    CompletableFuture<String> f = new CompletableFuture<String>();
                    f.completeExceptionally(new UnsupportedOperationException("no such thing here"));
                    return f;
                }
            };
            RpcException missing = h.ctx.error("web.evaluate", Harness.args("expression", "1"));
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, missing.error().code());
            assertEquals("no such thing here", missing.error().data().get("reason"));
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    return CompletableFuture.completedFuture("not json at all");
                }
            };
            RpcException garbage = h.ctx.error("web.evaluate", Harness.args("expression", "1"));
            assertEquals(ErrorCodes.INTERNAL_ERROR, garbage.error().code());
            assertTrue(Json.write(garbage.error().data()).contains(BrowserIndexTest.ref(h, browser)));
        }
    }

    private void awaitEvals(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (browser.evals.size() < n && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(browser.evals.size() >= n, "the call reached the adapter");
    }
}
