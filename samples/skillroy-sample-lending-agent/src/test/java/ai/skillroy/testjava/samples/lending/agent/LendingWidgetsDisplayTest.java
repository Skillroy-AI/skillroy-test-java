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

import ai.skillroy.testjava.agent.api.spi.DialogInfo;
import ai.skillroy.testjava.samples.lending.IconKey;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingFrame;
import ai.skillroy.testjava.samples.lending.LendingInput;
import ai.skillroy.testjava.samples.lending.LendingOptions;
import ai.skillroy.testjava.samples.lending.PromptButton;
import ai.skillroy.testjava.samples.lending.PromptDialog;
import ai.skillroy.testjava.samples.lending.Seed;
import org.junit.jupiter.api.Test;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The widget SPIs against the real desk window and its custom-painted prompt dialog. Needs a display: runs under
 * {@code ci/run-in-linux.sh} with {@code -Dskillroy.headless=false} and is skipped elsewhere.
 */
class LendingWidgetsDisplayTest {

    @Test
    void theDesksKeysAndPromptDialogAreReadNamedAndPressed() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        final LendingWidgets widgets = new LendingWidgets();
        final CountDownLatch dialogOpened = new CountDownLatch(1);
        final AtomicReference<PromptDialog> dialog = new AtomicReference<PromptDialog>();
        AWTEventListener tap = new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (event.getID() == WindowEvent.WINDOW_OPENED && event.getSource() instanceof PromptDialog) {
                    dialog.set((PromptDialog) event.getSource());
                    dialogOpened.countDown();
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(tap, AWTEvent.WINDOW_EVENT_MASK);
        final LendingApp app = LendingApp.launch(LendingOptions.defaults().seed(Seed.A)
                .dbName("lending_widgets_display"));
        try {
            final LendingFrame frame = app.frame();
            assertNotNull(frame);
            EventQueue.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    for (IconKey key : frame.keys()) {
                        assertEquals(key.actionId(), widgets.nameFor(key));
                        assertEquals(Collections.singletonList(key), widgets.resolve("action", key.actionId(),
                                frame));
                    }
                }
            });
            // Any unknown code at SIGNED_OFF opens the "Unknown staff code" prompt.
            assertTrue(app.inbox().offer(LendingInput.entry("999")));
            assertTrue(dialogOpened.await(20, TimeUnit.SECONDS), "prompt dialog shown");
            EventQueue.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    DialogInfo info = widgets.extract(dialog.get());
                    assertNotNull(info);
                    assertEquals("lending", info.extractorId());
                    assertNull(info.title());
                    assertEquals("Unknown staff code", info.text());
                    assertEquals(Collections.singletonList("OK"), info.buttons());
                    assertNull(widgets.extract(frame), "not a prompt dialog");
                    PromptButton ok = dialog.get().buttons().get(0);
                    assertEquals("OK", widgets.nameFor(ok));
                    assertTrue(widgets.canFire(ok));
                    widgets.fire(ok);
                }
            });
            assertNull(app.prompts().current(), "pressing the painted button answered the prompt");
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(tap);
            app.shutdown();
        }
    }
}
