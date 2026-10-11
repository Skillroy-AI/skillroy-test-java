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

package ai.skillroy.testjava.agent.web.fake.cef;

import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebPointerEvent;
import ai.skillroy.testjava.agent.web.jcef.JcefAdapter;
import ai.skillroy.testjava.agent.web.kit.KitScripts;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code jcef} adapter's raw channel (its own observer registration, answers paired with calls by message id)
 * against the fake, which gives every second answer before its id as JCEF 152 did in the Linux image: every call is
 * answered; the registration is the handle's own and is disposed of on detach (kept, after the detach, only until
 * the page answers a script addition it had not answered, whose removal then follows); a call pending when the
 * browser closes or the adapter detaches fails at once; a browser without a native browser has no registration.
 */
class JcefRawChannelFakeTest {

    private FakeCefFixture fx;
    private JcefAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        fx = new FakeCefFixture(FakeCefFixture.RAW_DESCRIPTOR);
        adapter = (JcefAdapter) fx.newAdapter();
        assertEquals(CapabilityState.OK, adapter.probe(fx.hostLoader()).state());
    }

    @AfterEach
    void stop() {
        adapter.detach();
        FakeCefApp.dispose();
    }

    private WebBrowser browser(FakeCefBrowserBase b) {
        List<WebBrowser> found = adapter.browsers(Collections.singletonList(b));
        for (WebBrowser w : found) {
            if (w.vendorObject() == b) {
                return w;
            }
        }
        throw new AssertionError("not found");
    }

    @Test
    void everyAnswerReachesItsCallAlsoWhenItArrivesBeforeTheId() throws Exception {
        WebBrowser b = browser(fx.visible);
        for (int i = 0; i < 200; i++) {
            String v = b.evaluate(WebEval.of(i + " * 2").timeoutMs(5000).build()).get(5, TimeUnit.SECONDS);
            assertEquals(String.valueOf(2 * i), v);
        }
        assertEquals(1, fx.visible.observerCount(), "one registration of the handle's own");
    }

    @Test
    void detachDisposesOfTheRegistrationAndTheClientIsNeverUsed() throws Exception {
        int clientCalls = FakeDevToolsClient.executed();
        WebBrowser b = browser(fx.visible);
        b.evaluate(WebEval.of("6 * 7").timeoutMs(5000).build()).get(5, TimeUnit.SECONDS);
        assertEquals(1, fx.visible.observerCount());
        adapter.detach();
        assertEquals(0, fx.visible.observerCount(), "the registration is disposed of");
        assertEquals(clientCalls, FakeDevToolsClient.executed(), "nothing went through the shared client");
        assertEquals(Collections.emptyList(), FakeCefBrowserBase.violations());
    }

    @Test
    void aCallPendingWhenTheBrowserClosesFailsAtOnce() throws Exception {
        WebBrowser b = browser(fx.visible);
        b.evaluate(WebEval.of("6 * 7").timeoutMs(5000).build()).get(5, TimeUnit.SECONDS);
        fx.visible.freeze(true);
        CompletableFuture<String> pending = b.evaluate(WebEval.of("6 * 7").timeoutMs(30000).build());
        fx.visible.close(true);
        assertTrue(b.isClosed());
        ExecutionException e = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
        assertTrue(e.getCause().getMessage().contains("closed"), e.getCause().getMessage());
    }

    @Test
    void anInitScriptThePageAddsOnlyAfterDetachIsRemovedThenAndTheRegistrationDisposedOf() throws Exception {
        WebBrowser b = browser(fx.visible);
        assertEquals("42", b.evaluate(WebEval.of("6 * 7").timeoutMs(5000).build()).get(5, TimeUnit.SECONDS));
        fx.visible.freeze(true);
        b.addInitScript(KitScripts.INIT_SOURCE);
        adapter.detach(); // gives the frozen page its bound, then keeps the registration for the addition's answer
        assertEquals(1, fx.visible.observerCount(), "the registration stays for the addition's answer");
        fx.visible.freeze(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((fx.visible.scriptRemovals() < 1 || fx.visible.observerCount() > 0) && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1, fx.visible.scriptAdditions(), "the page added the script after the detach");
        assertEquals(1, fx.visible.scriptRemovals(), "and its removal followed");
        assertEquals(0, fx.visible.observerCount(), "then the registration was disposed of");
        JcefAdapter fresh = (JcefAdapter) fx.newAdapter();
        try {
            assertEquals(CapabilityState.OK, fresh.probe(fx.hostLoader()).state());
            fx.visible.loadHtml("<html><head><title>next</title></head><body></body></html>").get(10, TimeUnit.SECONDS);
            WebBrowser again = fresh.browsers(Collections.singletonList(fx.visible)).get(0);
            assertEquals("\"undefined\"", again.evaluate(WebEval.of(KitScripts.INIT_NOW).timeoutMs(5000).build())
                    .get(5, TimeUnit.SECONDS), "the script does not run in the next document");
        } finally {
            fresh.detach();
        }
    }

    @Test
    void aCallInProgressAtDetachFailsAtOnce() throws Exception {
        WebBrowser b = browser(fx.visible);
        b.evaluate(WebEval.of("6 * 7").timeoutMs(5000).build()).get(5, TimeUnit.SECONDS);
        fx.visible.freeze(true);
        CompletableFuture<String> pending = b.evaluate(WebEval.of("6 * 7").timeoutMs(30000).build());
        adapter.detach();
        ExecutionException e = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
        assertTrue(String.valueOf(e.getCause().getMessage()).contains("detached"), String.valueOf(e.getCause()));
        fx.visible.freeze(false);
    }

    /** Phase 7 (4.5): a pointer event goes through the raw channel, never through the shared client. */
    @Test
    void aPointerEventGoesThroughTheRawChannel() throws Exception {
        int clientCalls = FakeDevToolsClient.executed();
        WebBrowser b = browser(fx.visible);
        b.dispatchPointer(WebPointerEvent.of(WebPointerEvent.Action.PRESS, 5, 6).button(WebPointerEvent.Button.RIGHT)
                .clickCount(2).build()).get(5, TimeUnit.SECONDS);
        assertEquals(1, fx.visible.inputs());
        assertEquals("{\"type\":\"mousePressed\",\"x\":5,\"y\":6,\"button\":\"right\",\"clickCount\":2,"
                + "\"modifiers\":0}", Json.write(fx.visible.document().pointerEvents().get(0)));
        assertEquals(clientCalls, FakeDevToolsClient.executed(), "nothing went through the shared client");
    }

    @Test
    void aBrowserWithoutANativeBrowserHasNoRegistrationUntilItIsCreated() throws Exception {
        FakeCefBrowserBase later = (FakeCefBrowserBase) fx.client.createBrowser("about:blank", true);
        WebBrowser b = browser(later);
        assertTrue(b.capabilities().isEmpty(), "no native browser, no capabilities");
        Throwable refused = failure(b.evaluate(WebEval.of("6 * 7").timeoutMs(5000).build()));
        assertTrue(refused instanceof UnsupportedOperationException, String.valueOf(refused));
        Throwable noInput = failure(b.dispatchPointer(WebPointerEvent.of(WebPointerEvent.Action.MOVE, 1, 1).build()));
        assertTrue(noInput instanceof UnsupportedOperationException, "no native browser, no INPUT: " + noInput);
        assertEquals(0, later.observerCount());
        later.show(320, 240);
        b = browser(later);
        assertEquals("42", b.evaluate(WebEval.of("6 * 7").timeoutMs(5000).build()).get(5, TimeUnit.SECONDS));
        assertEquals(1, later.observerCount());
        assertNotNull(b.cdpTargetId().get(5, TimeUnit.SECONDS));
    }

    private static Throwable failure(CompletableFuture<?> f) throws Exception {
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        }
        throw new AssertionError("expected a failure");
    }
}
