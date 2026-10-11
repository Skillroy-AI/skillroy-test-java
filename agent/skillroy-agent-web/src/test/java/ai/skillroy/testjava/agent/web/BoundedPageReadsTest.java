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
import ai.skillroy.testjava.agent.web.kit.KitPages;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 security fix S131 (phase4-contracts.md 4.6; phase7-contracts.md 4.8 as built): the two page reads the module
 * made raw are bounded at their source. The component-point conversion reads the inner size with an expression whose
 * answer is built from {@code typeof} and two numbers, so a page that redefines {@code innerWidth} as a long text
 * answers {@code null} (no conversion) and never a text of its choosing; {@code web.browsers}' title read cuts the title
 * in the page and passes the answer through the channel check, so an oversized answer is refused, not parsed.
 */
class BoundedPageReadsTest {

    /** The old, raw inner-size read. */
    private static final String RAW_INNER_SIZE = "[window.innerWidth, window.innerHeight]";

    private static String text(int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, 'w');
        return new String(chars);
    }

    @Test
    void theInnerSizeReadIsGuardedAndAPageChoosesNoLongAnswer() throws Exception {
        ScriptedAdapter adapter = new ScriptedAdapter("scripted");
        final ScriptedBrowser browser = adapter.add(new ScriptedBrowser("scripted"));
        browser.load(KitPages.main());
        JPanel view = new JPanel();
        browser.component = view;
        try (Harness h = new Harness(adapter).install()) {
            h.walk.components.add(view);
            h.walk.showing.add(view);
            h.ready();
            h.walk.state(view, 400, 300, 1.0, null, null);
            final String huge = Json.write(text(3000008));
            final AtomicInteger longest = new AtomicInteger();
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    String e = eval.expression();
                    String answer;
                    if (RAW_INNER_SIZE.equals(e)) {
                        answer = huge; // a page whose innerWidth is a 3 MB text, read raw
                    } else if (e.equals(Pointing.INNER_SIZE)) {
                        answer = "null"; // the guard: typeof "string" is not a number
                    } else {
                        return browser.byDocument(eval);
                    }
                    longest.set(Math.max(longest.get(), answer.length()));
                    return CompletableFuture.completedFuture(answer);
                }
            };
            Map<String, Object> hit = h.ctx.map("web.hitTest", Harness.args("point", Harness.args("x", 84L, "y",
                    110L), "space", "COMPONENT"));
            assertEquals(Harness.args("x", 84L, "y", 110L), hit.get("viewportPoint"), "no conversion without a width");
            for (WebEval eval : browser.evals) {
                assertFalse(RAW_INNER_SIZE.equals(eval.expression()), "the raw read is never sent");
            }
            assertTrue(longest.get() <= WebLib.MAX_JSON_CHARS, "no page answer over the library's bound crossed: "
                    + longest.get());
        }
    }

    @Test
    void anOversizedTitleAnswerIsRefusedNotParsed() throws Exception {
        ScriptedAdapter adapter = new ScriptedAdapter("scripted");
        final ScriptedBrowser browser = adapter.add(new ScriptedBrowser("scripted"));
        browser.load(KitPages.main());
        try (Harness h = new Harness(adapter).install()) {
            h.ready();
            final String oversized = Json.write(text(WebLib.MAX_CHANNEL_CHARS + 16));
            browser.responder = new ScriptedBrowser.Responder() {
                @Override
                public CompletableFuture<String> answer(WebEval eval) {
                    String e = eval.expression();
                    if ("document.title".equals(e) || e.equals(Selection.TITLE)) {
                        return CompletableFuture.completedFuture(oversized);
                    }
                    return browser.byDocument(eval);
                }
            };
            List<?> listed = (List<?>) h.ctx.map("web.browsers", Harness.args()).get("browsers");
            Map<?, ?> entry = (Map<?, ?>) listed.get(0);
            assertFalse(entry.containsKey("title"), "an answer over the channel bound is refused: " + entry.keySet());
        }
    }
}
