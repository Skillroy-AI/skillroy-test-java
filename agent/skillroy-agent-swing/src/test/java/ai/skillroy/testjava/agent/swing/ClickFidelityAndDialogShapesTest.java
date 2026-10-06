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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.agent.api.spi.DialogInfo;
import ai.skillroy.testjava.agent.api.spi.DialogLocator;
import ai.skillroy.testjava.agent.api.spi.DialogTextExtractor;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Component;
import java.awt.Window;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JInternalFrame;
import javax.swing.JPanel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.fired;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendments A27 and A28 without a display: the click result shapes and the {@code fallback} parameter, the
 * {@code MODAL_OPEN} data of an in-window dialog, the default {@code extract(Component)} of
 * {@code DialogTextExtractor}, the generic title of an in-window dialog, and what {@link InWindowDialogs} does with
 * nothing showing. Headless; the behaviour on a display is in {@link ComponentFallbackDisplayTest} and
 * {@link InWindowDialogDisplayTest}.
 */
class ClickFidelityAndDialogShapesTest {

    private final FakeContext ctx = new FakeContext();

    @AfterEach
    void shutdown() {
        ctx.shutdown();
    }

    @Test
    void resultShapes() {
        assertEquals(fired("ACTION", "AUTO"), Clicks.fired("ACTION", "AUTO"));
        assertEquals(map("rung", "ACTION", "requestedRung", "COMPONENT", "fidelity", "DEGRADED",
                "reason", "FOCUS_FAILED: focus did not arrive within 500 ms", "fired", true),
                Clicks.degraded("ACTION", "COMPONENT", "FOCUS_FAILED: focus did not arrive within 500 ms"));
        assertEquals(map("rung", "FOCUS_FAILED", "fired", false, "reason", "why"), Clicks.notFired("why"),
                "a click that did not fire is reported as before: no fidelity, no requestedRung");
        JsonCheck.requirePlainJson(Clicks.degraded("ACTION", "COMPONENT", "x"), "degraded");
    }

    @Test
    void fallbackIsActionWithComponentOnly() {
        assertFalse(SwingPrimitives.fallback(JsonView.of(map()), "COMPONENT"));
        assertFalse(SwingPrimitives.fallback(JsonView.of(map("fallback", null)), "ACTION"), "null is absent");
        assertTrue(SwingPrimitives.fallback(JsonView.of(map("fallback", "ACTION")), "COMPONENT"));
        assertTrue(SwingPrimitives.fallback(JsonView.of(map("fallback", " action ")), "COMPONENT"),
                "case-insensitive like the rung");
        for (String rung : new String[] {"AUTO", "ACTION", "DISPATCH", "ROBOT"}) {
            RpcException e = expectError(ErrorCodes.INVALID_PARAMS,
                    () -> SwingPrimitives.fallback(JsonView.of(map("fallback", "ACTION")), rung));
            assertEquals("fallback", e.error().data().get("param"), rung);
        }
        for (Object bad : new Object[] {"DISPATCH", "COMPONENT", "", 5L}) {
            RpcException e = expectError(ErrorCodes.INVALID_PARAMS,
                    () -> SwingPrimitives.fallback(JsonView.of(map("fallback", bad)), "COMPONENT"));
            assertEquals("fallback", e.error().data().get("param"), String.valueOf(bad));
        }
        expectError(ErrorCodes.INVALID_PARAMS,
                () -> SwingPrimitives.fallback(JsonView.of(map("fallback", map())), "COMPONENT"));
    }

    @Test
    void modalOpenOfAnInWindowDialogCarriesInWindow() {
        RpcException e = Errors.modalOpenInWindow("blocked", "h1", "Confirm return", "h2");
        assertEquals(ErrorCodes.MODAL_OPEN, e.error().code());
        assertEquals(map("dialog", "h1", "title", "Confirm return", "ref", "h2", "inWindow", true),
                e.error().data());
        assertEquals(map("dialog", "h1", "title", "Loan", "ref", "h2"),
                Errors.modalOpen("blocked", "h1", "Loan", "h2").error().data(), "dialog windows: unchanged");
    }

    @Test
    void anExtractorWrittenForWindowsDeclinesInWindowRoots() throws Exception {
        DialogTextExtractor windowsOnly = new DialogTextExtractor() {
            @Override
            public DialogInfo extract(Window window) {
                return new DialogInfo("windows-only", "t", "x", null);
            }
        };
        assertNull(windowsOnly.extract((Component) new JPanel()), "the default extract(Component) is null");
        DialogLocator blocking = new DialogLocator() {
            @Override
            public List<Component> dialogRoots(Window window) {
                return Collections.emptyList();
            }
        };
        assertTrue(blocking.blocksInput(new JPanel()), "an in-window dialog blocks input by default");
    }

    @Test
    void genericTitleOfAnInWindowRoot() throws Exception {
        onEdt(() -> {
            assertEquals("Lend", DialogReader.genericTitle(new JInternalFrame("Lend")));
            JPanel titled = new JPanel();
            titled.setBorder(BorderFactory.createTitledBorder("<html>Confirm <b>return</b></html>"));
            assertEquals("Confirm return", DialogReader.genericTitle(titled), "HTML reduced like other texts");
            JPanel compound = new JPanel();
            compound.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4),
                    BorderFactory.createTitledBorder("Inner title")));
            assertEquals("Inner title", DialogReader.genericTitle(compound));
            assertNull(DialogReader.genericTitle(new JPanel()));
            return null;
        });
    }

    @Test
    void withNothingShowingThereIsNoInWindowDialog() throws Exception {
        final JPanel root = new JPanel();
        final int[] asked = new int[1];
        ctx.spi.register(DialogLocator.class, new DialogLocator() {
            @Override
            public List<Component> dialogRoots(Window window) {
                asked[0]++;
                return Collections.<Component>singletonList(root);
            }
        });
        onEdt(() -> {
            InWindowDialogs dialogs = new InWindowDialogs(ctx);
            assertTrue(dialogs.all().isEmpty());
            assertTrue(dialogs.in(null).isEmpty());
            assertNull(dialogs.find(root), "a root that is not showing is no dialog");
            assertNull(dialogs.find(null));
            assertNull(dialogs.blocking(root));
            assertNull(dialogs.blocking(null));
            return null;
        });
        assertEquals(0, asked[0], "a locator is asked about showing windows only");
    }
}
