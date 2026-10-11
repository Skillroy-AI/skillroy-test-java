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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.samples.lending.IconKey;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The widget SPIs on the desk's keypad keys, which are lightweight components and work headless. The prompt
 * dialog's extractor and button need a window and are covered by {@link LendingWidgetsDisplayTest}.
 */
class LendingWidgetsTest {

    private final LendingWidgets widgets = new LendingWidgets();

    @Test
    void keypadKeysAreNamedLocatedAndFiredByTheirActionId() throws Exception {
        final List<String> fired = new ArrayList<String>();
        final AtomicReference<JPanel> panel = new AtomicReference<JPanel>();
        final AtomicReference<IconKey> due = new AtomicReference<IconKey>();
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                JPanel keypad = new JPanel();
                JPanel nested = new JPanel();
                for (String id : new String[] {"QTY", "DUE", "DEPOSIT"}) {
                    IconKey key = new IconKey(id);
                    key.addActionListener(new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            fired.add(e.getActionCommand());
                        }
                    });
                    (id.equals("DEPOSIT") ? nested : keypad).add(key);
                    if (id.equals("DUE")) {
                        due.set(key);
                    }
                }
                keypad.add(nested);
                keypad.add(new JButton("OK"));
                panel.set(keypad);
            }
        });
        EventQueue.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                assertEquals("DUE", widgets.nameFor(due.get()));
                assertNull(widgets.nameFor(new JButton("OK")), "no opinion on other components");
                assertEquals(Collections.singleton("action"), widgets.strategies());
                List<Component> found = widgets.resolve("action", "DUE", panel.get());
                assertEquals(1, found.size());
                assertSame(due.get(), found.get(0));
                assertEquals(1, widgets.resolve("action", "DEPOSIT", panel.get()).size(), "nested keys are found");
                assertTrue(widgets.resolve("action", "CANCEL", panel.get()).isEmpty());
                assertTrue(widgets.resolve("name", "DUE", panel.get()).isEmpty());
                assertTrue(widgets.resolve("action", null, panel.get()).isEmpty());
                assertTrue(widgets.canFire(due.get()));
                assertFalse(widgets.canFire(new JButton("OK")));
                // perform(): delivered at once, with no focus and no debounce, unlike doClick() on an unfocused key.
                widgets.fire(due.get());
                assertEquals(Collections.singletonList("DUE"), fired);
                assertNull(widgets.extract(null));
                // Phase 7: describe proposes the domain locator first (phase7-contracts.md section 12.2)
                assertEquals(Collections.singletonMap("action", "DUE"), widgets.describe(due.get()));
                assertTrue(widgets.describe(new JButton("OK")).isEmpty());
            }
        });
    }
}
