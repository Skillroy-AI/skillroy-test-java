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

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spike BS4 (focus part): what focus does a freshly shown frame get on this display, does requestFocusInWindow() on a
 * button obtain focus (before and after toFront()/requestFocus() on the window), what happens with two frames, and
 * what the COMPONENT click rung therefore reports. Observations go to {@code target/bs4-focus-java<version>.txt}; the
 * only assertions are that the COMPONENT rung is honest (it clicked if and only if it said it did).
 */
class FocusSpikeTest extends DisplayTestBase {

    private static final long OBSERVE_MS = 3000;

    private final StringBuilder report = new StringBuilder();

    @Test
    void focusOnThisDisplay() throws Exception {
        TestUi.disposeAll(); // start from a display with no windows
        line("java.version=" + System.getProperty("java.version") + " vm=" + System.getProperty("java.vm.name")
                + " toolkit=" + Toolkit.getDefaultToolkit().getClass().getName() + " os="
                + System.getProperty("os.name"));
        final AtomicInteger a1Clicks = new AtomicInteger();
        final AtomicInteger b1Clicks = new AtomicInteger();
        final JFrame[] frames = new JFrame[2];
        final JButton[] buttons = new JButton[3];
        final JTextField[] fields = new JTextField[2];
        try {
            onEdt(() -> {
                frames[0] = frame("BS4 A", 0, buttons, 0, fields, 0);
                buttons[0].addActionListener(e -> a1Clicks.incrementAndGet());
                frames[0].setVisible(true);
                return null;
            });
            await("frame A showing", WAIT_MS, frames[0]::isShowing);
            JFrame a = frames[0];
            line("1. frame A shown: focusedWithin " + OBSERVE_MS + "ms=" + poll(a::isFocused) + " state=" + state(a));

            boolean accepted = onEdt(() -> buttons[1].requestFocusInWindow());
            line("2. a2.requestFocusInWindow() returned " + accepted + "; a2.hasFocus within " + OBSERVE_MS + "ms="
                    + poll(buttons[1]::hasFocus) + " state=" + state(a));

            onEdt(() -> {
                a.toFront();
                a.requestFocus();
                return null;
            });
            line("3. A.toFront()+A.requestFocus(): A focused=" + poll(a::isFocused) + " state=" + state(a));
            accepted = onEdt(() -> buttons[1].requestFocusInWindow());
            line("4. then a2.requestFocusInWindow() returned " + accepted + "; a2.hasFocus="
                    + poll(buttons[1]::hasFocus)
                    + " state=" + state(a));

            line("5. swing.click COMPONENT a1 -> " + componentClick(buttons[0], a1Clicks));

            onEdt(() -> {
                frames[1] = frame("BS4 B", 360, buttons, 2, fields, 1);
                buttons[2].addActionListener(e -> b1Clicks.incrementAndGet());
                frames[1].setVisible(true);
                return null;
            });
            JFrame b = frames[1];
            await("frame B showing", WAIT_MS, b::isShowing);
            line("6. frame B shown: B focused=" + poll(b::isFocused) + " A focused=" + onEdt(a::isFocused)
                    + " state=" + state(b));
            accepted = onEdt(() -> buttons[2].requestFocusInWindow());
            line("7. b1.requestFocusInWindow() returned " + accepted + "; b1.hasFocus=" + poll(buttons[2]::hasFocus)
                    + " state=" + state(b));
            accepted = onEdt(() -> buttons[0].requestFocusInWindow());
            line("8. back to A: a1.requestFocusInWindow() returned " + accepted + "; a1.hasFocus="
                    + poll(buttons[0]::hasFocus) + " state=" + state(a));
            onEdt(() -> {
                a.toFront();
                a.requestFocus();
                return null;
            });
            line("9. A.toFront()+A.requestFocus(): A focused=" + poll(a::isFocused) + " state=" + state(a));
            accepted = onEdt(() -> buttons[0].requestFocusInWindow());
            line("10. then a1.requestFocusInWindow() returned " + accepted + "; a1.hasFocus="
                    + poll(buttons[0]::hasFocus) + " state=" + state(a));
            line("11. swing.click COMPONENT b1 -> " + componentClick(buttons[2], b1Clicks));
            line("12. swing.click COMPONENT a1 -> " + componentClick(buttons[0], a1Clicks));

            // posted versus dispatched key events to a field in a window that may not be focused
            final JTextField bf = fields[1];
            onEdt(() -> {
                bf.setText("");
                KeyEvent typed = new KeyEvent(bf, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
                        KeyEvent.VK_UNDEFINED, 'p');
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(typed);
                return null;
            });
            settle();
            Component owner = onEdt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
            line("13. KEY_TYPED 'p' posted to B's field: field text='" + onEdt(bf::getText) + "' focusOwner="
                    + describe(owner) + " A field text='" + onEdt(fields[0]::getText) + "'");
            call("swing.type", "target", ref(bf), "text", "d", "mode", "DISPATCH", "clear", false);
            settle();
            line("14. swing.type DISPATCH 'd' to B's field: field text='" + onEdt(bf::getText) + "'");
        } finally {
            write();
            onEdt(() -> {
                for (JFrame f : frames) {
                    if (f != null) {
                        f.dispose();
                    }
                }
                return null;
            });
        }
    }

    /** Runs the COMPONENT rung and checks it is honest: clicked if and only if it reported fired. */
    private String componentClick(JButton button, AtomicInteger clicks) throws Exception {
        int before = clicks.get();
        Map<String, Object> r = call("swing.click", "target", ref(button), "rung", "COMPONENT", "focusTimeoutMs",
                OBSERVE_MS);
        if (Boolean.TRUE.equals(r.get("fired"))) {
            await("COMPONENT click delivered", WAIT_MS, () -> clicks.get() == before + 1);
        } else {
            settle();
            assertEquals(before, clicks.get(), "FOCUS_FAILED must not click");
        }
        return r + " clicks " + before + "->" + clicks.get();
    }

    private static JFrame frame(String title, int x, JButton[] buttons, int at, JTextField[] fields, int fieldAt) {
        JFrame f = new JFrame(title);
        JPanel p = new JPanel();
        int n = at == 0 ? 2 : 1;
        for (int i = 0; i < n; i++) {
            JButton b = new JButton(title.substring(title.length() - 1).toLowerCase() + (i + 1));
            buttons[at + i] = b;
            p.add(b);
        }
        JTextField field = new JTextField(8);
        fields[fieldAt] = field;
        p.add(field);
        f.setContentPane(p);
        f.setBounds(x, 0, 340, 160);
        return f;
    }

    /** Milliseconds until the condition held (checked on the EDT), or "no" after OBSERVE_MS. */
    private static String poll(final Callable<Boolean> condition) throws Exception {
        long start = System.nanoTime();
        long deadline = start + OBSERVE_MS * 1000000L;
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(onEdt(condition))) {
                return "yes(" + (System.nanoTime() - start) / 1000000L + "ms)";
            }
            Thread.sleep(25);
        }
        return "no";
    }

    private static String state(final Window w) throws Exception {
        return onEdt(() -> {
            KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            return "{" + title(w) + " focused=" + w.isFocused() + " active=" + w.isActive() + ", kfm.focusedWindow="
                    + title(kfm.getFocusedWindow()) + ", kfm.activeWindow=" + title(kfm.getActiveWindow())
                    + ", focusOwner=" + describe(kfm.getFocusOwner()) + "}";
        });
    }

    private static String title(Window w) {
        return w == null ? "null" : w instanceof JFrame ? ((JFrame) w).getTitle() : w.getClass().getSimpleName();
    }

    private static String describe(Component c) {
        if (c == null) {
            return "null";
        }
        return c instanceof JButton ? "button " + ((JButton) c).getText() : c.getClass().getSimpleName();
    }

    private void line(String s) {
        report.append(s).append('\n');
    }

    private void write() throws Exception {
        String version = System.getProperty("java.specification.version");
        File dir = new File(System.getProperty("basedir", "."), "target");
        dir.mkdirs();
        File out = new File(dir, "bs4-focus-java" + version + ".txt");
        Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
        try {
            w.write(report.toString());
        } finally {
            w.close();
        }
        System.out.println("[BS4] report written to " + out + "\n" + report);
    }
}
