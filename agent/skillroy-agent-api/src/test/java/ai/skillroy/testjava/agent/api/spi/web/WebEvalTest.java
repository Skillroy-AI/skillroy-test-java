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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WebEval}: the builder's defaults and validation, value semantics (Phase 4 contract §3, §12.1 item 1). */
class WebEvalTest {

    @Test
    void theDefaultsAreTheMainFrameNoPromiseAndTenSeconds() {
        WebEval eval = WebEval.of("document.title").build();
        assertEquals("document.title", eval.expression());
        assertNull(eval.frameKey());
        assertFalse(eval.awaitPromise());
        assertEquals(10000L, eval.timeoutMs());
    }

    @Test
    void theBuilderSetsEveryField() {
        WebEval eval = WebEval.of("loanStatus()").frameKey("F-7").awaitPromise(true).timeoutMs(250L).build();
        assertEquals("loanStatus()", eval.expression());
        assertEquals("F-7", eval.frameKey());
        assertTrue(eval.awaitPromise());
        assertEquals(250L, eval.timeoutMs());
        assertNull(WebEval.of("1").frameKey("F-7").frameKey(null).build().frameKey(), "null is the main frame again");
        assertFalse(WebEval.of("1").awaitPromise(true).awaitPromise(false).build().awaitPromise());
    }

    @Test
    void theExpressionIsKeptAsGiven() {
        assertEquals("  1 + 1\n", WebEval.of("  1 + 1\n").build().expression());
    }

    @Test
    void aNullExpressionIsRejected() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> WebEval.of(null));
        assertEquals("expression must not be null", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n", " \r\n\t "})
    void aBlankExpressionIsRejected(String expression) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WebEval.of(expression));
        assertEquals("expression must not be blank", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void aTimeoutThatIsNotPositiveIsRejected(long timeoutMs) {
        WebEval.Builder builder = WebEval.of("1");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> builder.timeoutMs(timeoutMs));
        assertEquals("timeoutMs must be positive: " + timeoutMs, e.getMessage());
        assertEquals(10000L, builder.build().timeoutMs(), "a rejected value leaves the builder as it was");
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 600000L, Long.MAX_VALUE})
    void anyPositiveTimeoutIsAccepted(long timeoutMs) {
        assertEquals(timeoutMs, WebEval.of("1").timeoutMs(timeoutMs).build().timeoutMs());
    }

    @Test
    void requestsAreImmutableValues() {
        WebEval.Builder builder = WebEval.of("document.title").frameKey("F-1");
        WebEval first = builder.build();
        builder.awaitPromise(true).timeoutMs(5L).frameKey("F-2");
        WebEval second = builder.build();
        assertEquals("F-1", first.frameKey());
        assertFalse(first.awaitPromise());
        assertEquals(10000L, first.timeoutMs());
        assertNotEquals(first, second);
        assertEquals(second, builder.build());
        assertEquals(second.hashCode(), builder.build().hashCode());

        WebEval base = WebEval.of("a").build();
        assertEquals(base, WebEval.of("a").frameKey(null).awaitPromise(false).timeoutMs(10000L).build());
        assertNotEquals(base, WebEval.of("b").build());
        assertNotEquals(base, WebEval.of("a").frameKey("F-1").build());
        assertNotEquals(base, WebEval.of("a").awaitPromise(true).build());
        assertNotEquals(base, WebEval.of("a").timeoutMs(9999L).build());
        assertFalse(base.equals(null));
        assertFalse(base.equals("a"));
    }

    @Test
    void toStringShowsTheLengthOfTheExpressionNeverItsText() {
        String expression = "fill('#pin', 'secret-pin-4711')";
        String text = WebEval.of(expression).frameKey("F-3").build().toString();
        assertFalse(text.contains("4711"), text);
        assertEquals("WebEval{expression=<" + expression.length() + " chars>, frameKey=F-3, awaitPromise=false,"
                + " timeoutMs=10000}", text);
    }
}
