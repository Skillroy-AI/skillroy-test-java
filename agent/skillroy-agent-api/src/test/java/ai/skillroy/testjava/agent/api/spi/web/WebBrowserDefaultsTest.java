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

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The default methods of {@link WebBrowser} (amendment A37, Phase 4 contract §3 and §16.3), on a browser that
 * implements only the abstract methods, as an adapter built against the Phase 4 SPI does: {@code reload} evaluates
 * {@code location.reload()} in the main frame ({@code location.reload(true)} with {@code ignoreCache}), completes when
 * that evaluation answers, fails with its failure (also when {@code evaluate} throws), and cancelling it cancels the
 * evaluation; {@code bitmapSource()} is {@code VENDOR}; {@code dispatchPointer} (A66) refuses without INPUT.
 */
class WebBrowserDefaultsTest {

    @Test
    void reloadEvaluatesLocationReloadInTheMainFrame() throws Exception {
        PlainBrowser b = new PlainBrowser();
        b.reload(false);
        b.reload(true);
        assertEquals(2, b.evaluations.size());
        assertEquals("location.reload()", b.evaluations.get(0).expression());
        assertEquals("location.reload(true)", b.evaluations.get(1).expression(),
                "ignoreCache passes the non-standard argument on; only an override honours it");
        for (WebEval e : b.evaluations) {
            assertNull(e.frameKey(), "the main frame");
            assertFalse(e.awaitPromise());
            assertEquals(WebEval.of("x").build().timeoutMs(), e.timeoutMs(), "WebEval's default timeout");
        }
    }

    @Test
    void reloadCompletesWhenTheEvaluationAnswersAndNotBefore() throws Exception {
        PlainBrowser b = new PlainBrowser();
        CompletableFuture<Void> reload = b.reload(false);
        assertFalse(reload.isDone(), "pending while the evaluation is");
        b.answers.get(0).complete("null");
        assertNull(reload.get(1, TimeUnit.SECONDS));
        assertFalse(reload.isCompletedExceptionally());
    }

    @Test
    void reloadFailsWithTheEvaluationsOwnFailure() throws Exception {
        List<Throwable> failures = Arrays.<Throwable>asList(new WebScriptException("Error: blocked", "at <anonymous>"),
                new UnsupportedOperationException("no EVALUATE"), new IllegalStateException("the browser has closed"));
        for (Throwable failure : failures) {
            PlainBrowser b = new PlainBrowser();
            CompletableFuture<Void> reload = b.reload(false);
            b.answers.get(0).completeExceptionally(failure);
            assertSame(failure, cause(reload), "the reload fails as its evaluation did");
        }
        PlainBrowser chained = new PlainBrowser();
        chained.dependent = true;
        CompletableFuture<Void> reload = chained.reload(true);
        IllegalStateException closed = new IllegalStateException("the browser has closed");
        chained.answers.get(0).completeExceptionally(closed);
        assertSame(closed, cause(reload), "a failure that reached the adapter's future through a stage, unwrapped");
    }

    @Test
    void anEvaluateThatThrowsOrAnswersNothingFailsTheFutureInsteadOfThrowing() throws Exception {
        PlainBrowser throwing = new PlainBrowser();
        final UnsupportedOperationException refused = new UnsupportedOperationException("no EVALUATE");
        throwing.throwOnEvaluate = refused;
        CompletableFuture<Void> reload = throwing.reload(false);
        assertSame(refused, cause(reload));
        PlainBrowser silent = new PlainBrowser();
        silent.answerNothing = true;
        assertTrue(cause(silent.reload(false)) instanceof IllegalStateException, "a null future is a failure");
    }

    @Test
    void cancellingTheReloadCancelsTheEvaluation() throws Exception {
        PlainBrowser b = new PlainBrowser();
        CompletableFuture<Void> reload = b.reload(false);
        assertTrue(reload.cancel(false));
        assertTrue(reload.isCancelled());
        assertTrue(b.answers.get(0).isCancelled(), "the caller's abandoned wait abandons the evaluation");
        assertFalse(b.answers.get(0).complete("null"), "nothing completes it any more");
    }

    @Test
    void theBitmapSourceIsVendorByDefault() {
        assertSame(BitmapSource.VENDOR, new PlainBrowser().bitmapSource());
    }

    /**
     * A66 (phase7-contracts.md section 4.1): the default {@code dispatchPointer} has no INPUT; it fails with
     * {@code UnsupportedOperationException} at once and touches nothing (no evaluation, also for a null event).
     */
    @Test
    void theDefaultDispatchPointerRefusesWithoutTouchingTheBrowser() throws Exception {
        PlainBrowser b = new PlainBrowser();
        CompletableFuture<Void> f = b.dispatchPointer(WebPointerEvent.of(WebPointerEvent.Action.MOVE, 1, 2).build());
        assertTrue(f.isDone(), "at once");
        Throwable cause = cause(f);
        assertTrue(cause instanceof UnsupportedOperationException, String.valueOf(cause));
        assertTrue(cause.getMessage().contains("INPUT"), cause.getMessage());
        assertTrue(cause(b.dispatchPointer(null)) instanceof UnsupportedOperationException);
        assertTrue(b.evaluations.isEmpty(), "nothing was sent to the page");
    }

    @Test
    void theDefaultsNeedNothingButTheAbstractMethods() {
        // PlainBrowser declares none of them: an adapter built before A37 and A66 gets every default.
        assertThrows(NoSuchMethodException.class, () -> PlainBrowser.class.getDeclaredMethod("reload", boolean.class));
        assertThrows(NoSuchMethodException.class, () -> PlainBrowser.class.getDeclaredMethod("bitmapSource"));
        assertThrows(NoSuchMethodException.class, () -> PlainBrowser.class.getDeclaredMethod("dispatchPointer",
                WebPointerEvent.class));
    }

    private static Throwable cause(CompletableFuture<?> f) throws Exception {
        try {
            f.get(1, TimeUnit.SECONDS);
            fail("expected a failure");
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    /** A browser that implements the abstract methods only; its evaluations are answered by the test. */
    private static final class PlainBrowser implements WebBrowser {
        final List<WebEval> evaluations = new ArrayList<WebEval>();
        final List<CompletableFuture<String>> answers = new ArrayList<CompletableFuture<String>>();
        RuntimeException throwOnEvaluate;
        boolean answerNothing;
        /** Hand out a dependent stage of the answer, as an adapter that maps its technology's future does. */
        boolean dependent;

        @Override
        public String adapterId() {
            return "plain";
        }

        @Override
        public Object vendorObject() {
            return this;
        }

        @Override
        public String nativeId() {
            return null;
        }

        @Override
        public Set<WebCapability> capabilities() {
            return Collections.singleton(WebCapability.EVALUATE);
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public String url() {
            return "";
        }

        @Override
        public Component component() {
            return null;
        }

        @Override
        public WebRendering rendering() {
            return WebRendering.UNKNOWN;
        }

        @Override
        public Dimension viewport() {
            return null;
        }

        @Override
        public List<WebFrame> frames() {
            return Collections.emptyList();
        }

        @Override
        public CompletableFuture<String> evaluate(WebEval request) {
            evaluations.add(request);
            if (throwOnEvaluate != null) {
                throw throwOnEvaluate;
            }
            if (answerNothing) {
                return null;
            }
            CompletableFuture<String> answer = new CompletableFuture<String>();
            answers.add(answer);
            return dependent ? answer.thenApply(v -> v) : answer;
        }

        @Override
        public WebSubscription subscribe(WebEventListener listener) {
            return () -> { };
        }

        @Override
        public WebInitScript addInitScript(String source) {
            throw new UnsupportedOperationException("no init scripts");
        }

        @Override
        public CompletableFuture<Void> resize(int width, int height) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> clearResize() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<BufferedImage> bitmap() {
            CompletableFuture<BufferedImage> f = new CompletableFuture<BufferedImage>();
            f.completeExceptionally(new UnsupportedOperationException("no BITMAP"));
            return f;
        }

        @Override
        public CompletableFuture<String> cdpTargetId() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
