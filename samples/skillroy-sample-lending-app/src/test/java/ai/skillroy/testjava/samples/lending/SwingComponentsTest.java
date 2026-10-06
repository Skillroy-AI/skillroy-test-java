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

package ai.skillroy.testjava.samples.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.EventQueue;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.accessibility.AccessibleRole;

import org.junit.jupiter.api.Test;

/**
 * Swing pieces that need no display: lightweight components, timers and models work headless. The window-level
 * behaviour is in {@link LendingUiDisplayTest}.
 */
class SwingComponentsTest {

    private static <T> T onEdt(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<T>();
        AtomicReference<Exception> failure = new AtomicReference<Exception>();
        EventQueue.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static void awaitCount(AtomicInteger count, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Recorder.TIMEOUT_MS;
        while (count.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, count.get());
    }

    @Test
    void iconKeyIsIconOnlyAndFocusGated() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        IconKey key = onEdt(() -> {
            IconKey k = new IconKey("DUE");
            k.addActionListener(e -> {
                assertEquals("DUE", e.getActionCommand());
                delivered.incrementAndGet();
            });
            return k;
        });
        assertEquals("DUE", key.actionId());
        assertNull(key.getName());
        assertNull(onEdt(() -> key.getAccessibleContext().getAccessibleName()));
        assertEquals(AccessibleRole.PUSH_BUTTON, onEdt(() -> key.getAccessibleContext().getAccessibleRole()));
        assertNull(key.getToolTipText());
        assertTrue(key.isFocusGated());

        onEdt(() -> {
            key.doClick();
            return null;
        });
        long until = System.currentTimeMillis() + 10L * IconKey.DEBOUNCE_MS;
        while (System.currentTimeMillis() < until) {
            assertEquals(0, delivered.get(), "a press without focus is not delivered");
            Thread.sleep(20);
        }
        onEdt(() -> {
            key.perform();
            return null;
        });
        assertEquals(1, delivered.get(), "perform() delivers at once");

        onEdt(() -> {
            key.setFocusGated(false);
            key.doClick();
            key.doClick();
            key.doClick();
            return null;
        });
        awaitCount(delivered, 2);
        Thread.sleep(10L * IconKey.DEBOUNCE_MS);
        assertEquals(2, delivered.get(), "presses within the debounce time are delivered once");
    }

    @Test
    void entryFieldMasksWhatIsTyped() throws Exception {
        onEdt(() -> {
            EntryField f = new EntryField(10);
            assertEquals(EntryField.NAME, f.getName());
            f.setText("100");
            assertEquals("100", f.getText());
            f.setMasked(true);
            assertEquals("", f.getText(), "switching the mask clears the field");
            f.setText("1111");
            assertEquals("****", f.getText());
            assertEquals("1111", f.value());
            f.getDocument().remove(1, 2);
            f.getDocument().insertString(1, "9", null);
            assertEquals("***", f.getText());
            assertEquals("191", f.value());
            assertEquals("191", f.takeValue());
            assertEquals("", f.value());
            f.setMasked(false);
            f.setText("abc");
            f.replaceSelection("x");
            assertEquals(f.getText(), f.value());
            return null;
        });
    }

    @Test
    void loanTableModelShowsLines() throws Exception {
        onEdt(() -> {
            LoanTableModel model = new LoanTableModel();
            assertEquals(0, model.getRowCount());
            assertEquals(6, model.getColumnCount());
            Item spade = new Item("YRD-001", "1", "Garden spade", new BigDecimal("3.50"), true, false, false);
            model.setLoan(Loan.open("100", false).withLines(Arrays.asList(
                LoanLine.of(1, spade, new BigDecimal("2"), false))));
            assertEquals(1, model.getRowCount());
            assertEquals("YRD-001", model.getValueAt(0, 1));
            assertEquals("2", model.getValueAt(0, 3));
            assertEquals("7.00", model.getValueAt(0, 5));
            assertFalse(model.loan().posted());
            return null;
        });
    }
}
