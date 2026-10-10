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

package ai.skillroy.testjava.spikes.bs9;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

/**
 * BS9's key probe: a small frame of its own ("BS9 keys": a button first in the focus order, then a text field) and key
 * input under different focus and activation states, with Robot and, where installed, {@code xdotool} without
 * {@code --window} (XTEST). Arguments: one string of comma-separated {@code key=value} options: {@code out},
 * {@code env}, {@code strict}, {@code scenario} = {@code activated} | {@code unactivated-hover} |
 * {@code unactivated-click} | {@code unactivated-xclick} | {@code after-dialog}. Before every case it records the
 * AWT focus (the focused window, the focus owner) and the X server's (the focus window and its parent,
 * {@code _NET_ACTIVE_WINDOW}); after it, which component received the typed characters.
 */
public final class KeysProbe {

    static final String TITLE = "BS9 keys";

    private final Probe p;
    private final String scenario;
    private final PrintWriter tsv;
    private JFrame frame;
    private JButton other;
    private JTextField field;
    private final List<String> typed = new ArrayList<String>();
    private final List<String> focusLog = new ArrayList<String>();

    private KeysProbe(Probe p) throws Exception {
        this.p = p;
        this.scenario = p.opt("scenario", "activated");
        this.tsv = p.writer("keys-" + scenario + ".tsv");
        tsv.println("env\tjava\tscenario\tcase\tinput\tsent\tfieldText\ttypedTo\tfocusBefore\tfocusAfter"
                + "\tframeFocusedBefore\tframeFocusedAfter\txBefore\tnote");
    }

    public static void main(String[] args) throws Exception {
        Probe p = new Probe(Probe.parse(String.join(",", args)), "keys-" + Probe.parse(String.join(",", args))
                .getOrDefault("scenario", "activated") + ".log");
        Probe.watchdog(Long.parseLong(p.opt("deadline", p.strict ? "50" : "90")), p);
        int code = 0;
        try {
            new KeysProbe(p).run();
        } catch (Throwable e) {
            code = 2;
            p.say("FAILED " + e);
            e.printStackTrace(System.out);
        } finally {
            p.restorePointer();
            p.say("EXIT " + code);
            System.out.flush();
            Runtime.getRuntime().halt(code);
        }
    }

    private void run() throws Exception {
        final boolean auto = !scenario.startsWith("unactivated");
        final int x = Integer.parseInt(p.opt("x", "60"));
        final int y = Integer.parseInt(p.opt("y", "60"));
        EventQueue.invokeAndWait(() -> {
            frame = new JFrame(TITLE);
            frame.setName("keysFrame");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setAutoRequestFocus(auto);
            JPanel c = new JPanel(null);
            c.setPreferredSize(new Dimension(420, 140));
            other = new JButton("Other");
            other.setName("other");
            other.setBounds(10, 10, 120, 30);
            field = new JTextField();
            field.setName("field");
            field.setBounds(10, 60, 380, 28);
            JLabel l = new JLabel("letters typed by the probe land here");
            l.setBounds(10, 95, 380, 20);
            c.add(other);
            c.add(field);
            c.add(l);
            frame.setContentPane(c);
            frame.pack();
            frame.setLocation(x, y);
        });
        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent e) {
                synchronized (typed) {
                    if (e.getID() == KeyEvent.KEY_TYPED) {
                        KeyEvent k = (KeyEvent) e;
                        typed.add(name(k.getComponent()) + ":" + k.getKeyChar());
                    } else if (e.getID() == FocusEvent.FOCUS_GAINED) {
                        focusLog.add("focus>" + name(((FocusEvent) e).getComponent()));
                    } else if (e.getID() == WindowEvent.WINDOW_GAINED_FOCUS) {
                        focusLog.add("window>" + name(((WindowEvent) e).getWindow()));
                    } else if (e.getID() == WindowEvent.WINDOW_LOST_FOCUS) {
                        focusLog.add("windowLost>" + name(((WindowEvent) e).getWindow()));
                    }
                }
            }
        }, AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK);
        p.say("env=" + p.env + " scenario=" + scenario + " java=" + System.getProperty("java.version") + " os="
                + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + " toolkit="
                + Toolkit.getDefaultToolkit().getClass().getName() + " headless=" + GraphicsEnvironment.isHeadless()
                + " xdotool=" + Probe.has("xdotool"));
        p.say("X before the frame is shown: " + Probe.xFocus(TITLE));
        long t0 = System.nanoTime();
        EventQueue.invokeLater(() -> frame.setVisible(true));
        p.watchFocus(frame);
        boolean focused = Edt.waitFor(() -> frame.isFocused(), auto ? 3000 : 1500);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Edt.sleep(200);
        p.say("shown: autoRequestFocus=" + auto + " focused=" + focused + (focused ? " after " + ms + " ms" : "")
                + " " + awtFocus() + " " + Probe.xFocus(TITLE) + " focusLog=" + focusLog());
        p.startRobot();
        if (p.robot == null) {
            return;
        }
        if ("activated".equals(scenario)) {
            activated();
        } else if ("unactivated-hover".equals(scenario)) {
            unactivatedHover();
        } else if ("unactivated-click".equals(scenario)) {
            unactivatedClick();
        } else if ("unactivated-xclick".equals(scenario)) {
            unactivatedXclick();
        } else if ("after-dialog".equals(scenario)) {
            afterDialog();
        } else {
            throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        tsv.close();
        p.say("DONE");
    }

    // ------------------------------------------------------------------ scenarios

    private void activated() throws Exception {
        // The application has asked for nothing: the focus owner is whatever the frame chose (the first component).
        Point fieldAt = Edt.call(() -> Probe.screenCentre(field));
        p.move(fieldAt.x, fieldAt.y);
        keyCase("K-default-focus", "robot", "abc", null, "no focus request by the application; pointer over the field");
        // A Robot click into the field, the focus elsewhere first.
        Edt.run(() -> other.requestFocusInWindow());
        Edt.waitFor(() -> other.isFocusOwner(), 1000);
        keyCase("K-robot-click", "robot", "def", () -> {
            Point c = Edt.call(() -> Probe.screenCentre(field));
            p.click(c.x, c.y);
            Edt.waitFor(() -> field.isFocusOwner(), 1500);
        }, "Robot click into the field, then keys");
        Edt.run(() -> other.requestFocusInWindow());
        Edt.waitFor(() -> other.isFocusOwner(), 1000);
        keyCase("K-app-focus", "robot", "ghi", () -> {
            Edt.run(() -> field.requestFocusInWindow());
            Edt.waitFor(() -> field.isFocusOwner(), 1500);
        }, "the application requested the focus for the field");
        if (!Probe.has("xdotool")) {
            return;
        }
        Edt.run(() -> other.requestFocusInWindow());
        Edt.waitFor(() -> other.isFocusOwner(), 1000);
        keyCase("X-default-focus", "xdotool", "jkl", null, "xdotool key (XTEST), focus owner the button");
        keyCase("X-click", "xdotool", "mno", () -> {
            Point c = Edt.call(() -> Probe.screenCentre(field));
            p.say("xdotool click: " + Probe.exec("xdotool", "mousemove", "" + c.x, "" + c.y, "sleep", "0.1", "click",
                    "1"));
            Edt.waitFor(() -> field.isFocusOwner(), 1500);
        }, "xdotool click into the field, then xdotool keys");
        Edt.run(() -> other.requestFocusInWindow());
        Edt.waitFor(() -> other.isFocusOwner(), 1000);
        keyCase("X-app-focus", "xdotool", "pqr", () -> {
            Edt.run(() -> field.requestFocusInWindow());
            Edt.waitFor(() -> field.isFocusOwner(), 1500);
        }, "the application requested the focus for the field, then xdotool keys");
    }

    private void unactivatedHover() throws Exception {
        Point fieldAt = Edt.call(() -> Probe.screenCentre(field));
        if (!p.strict) {
            p.move(fieldAt.x, fieldAt.y);
        }
        keyCase("U-type-hover", "robot", "abc", null, "window never activated; pointer over the field; no click");
        if (Probe.has("xdotool")) {
            keyCase("U-xdotool-hover", "xdotool", "def", null, "window never activated; xdotool key (XTEST)");
        }
        keyCase("U-app-activate", "robot", "ghi", () -> {
            long t0 = System.nanoTime();
            boolean ok = false;
            for (int i = 0; i < 3 && !ok; i++) {
                Edt.run(() -> {
                    frame.toFront();
                    frame.requestFocus();
                });
                ok = Edt.waitFor(() -> frame.isFocused(), 1000);
            }
            p.say("self-activation by toFront()+requestFocus(): " + ok + " after "
                    + (System.nanoTime() - t0) / 1_000_000 + " ms");
            Edt.run(() -> field.requestFocusInWindow());
            Edt.waitFor(() -> field.isFocusOwner(), 1000);
        }, "the application activates its own window (toFront + requestFocus, re-issued once a second), then"
                + " asks for the field");
    }

    private void unactivatedClick() throws Exception {
        if (p.strict) {
            p.say("U-robot-click not run in strict mode: Java cannot tell whether another application's window"
                    + " covers a window of an inactive application, so the click could land outside this JVM");
            return;
        }
        keyCase("U-robot-click", "robot", "abc", () -> {
            Point c = Edt.call(() -> Probe.screenCentre(field));
            p.click(c.x, c.y);
            boolean f = Edt.waitFor(() -> frame.isFocused(), 1500);
            p.say("after the Robot click: frame focused=" + f + " " + awtFocus());
            Edt.waitFor(() -> field.isFocusOwner(), 500);
        }, "window never activated; Robot click into the field, then keys");
    }

    private void unactivatedXclick() throws Exception {
        if (!Probe.has("xdotool")) {
            p.say("no xdotool: U-xdotool-click not run");
            return;
        }
        keyCase("U-xdotool-click", "xdotool", "abc", () -> {
            Point c = Edt.call(() -> Probe.screenCentre(field));
            p.say("xdotool click: " + Probe.exec("xdotool", "mousemove", "" + c.x, "" + c.y, "sleep", "0.1", "click",
                    "1"));
            boolean f = Edt.waitFor(() -> frame.isFocused(), 1500);
            p.say("after the xdotool click: frame focused=" + f + " " + awtFocus());
            Edt.waitFor(() -> field.isFocusOwner(), 500);
        }, "window never activated; xdotool click into the field, then xdotool keys");
    }

    /**
     * The focus after a modal dialog closes (BS4: under the WM-less Xvfb nothing gives it back to the frame): the field
     * owns the focus, a modal dialog of the frame is shown and disposed, then keys without any focus action.
     */
    private void afterDialog() throws Exception {
        Edt.run(() -> field.requestFocusInWindow());
        Edt.waitFor(() -> field.isFocusOwner(), 1500);
        final javax.swing.JDialog[] d = new javax.swing.JDialog[1];
        Edt.run(() -> {
            d[0] = new javax.swing.JDialog(frame, "BS9 dialog", true);
            d[0].setName("dialog");
            JButton ok = new JButton("OK");
            ok.setName("dialogOk");
            d[0].getContentPane().add(ok);
            d[0].setSize(200, 80);
            Point o = frame.getLocationOnScreen();
            d[0].setLocation(o.x + 220, o.y + frame.getHeight() + 20);
        });
        EventQueue.invokeLater(() -> d[0].setVisible(true));
        boolean shown = Edt.waitFor(() -> d[0].isShowing() && d[0].isFocused(), 3000);
        p.say("dialog shown and focused: " + shown + " " + awtFocus() + " " + Probe.xFocus(TITLE));
        EventQueue.invokeLater(() -> d[0].dispose());
        Edt.waitFor(() -> !d[0].isShowing(), 3000);
        long t0 = System.nanoTime();
        boolean back = Edt.waitFor(() -> frame.isFocused(), 2000);
        p.say("after dispose: frame focused again=" + back + (back ? " after " + (System.nanoTime() - t0) / 1_000_000
                + " ms" : " (waited 2000 ms)") + " " + awtFocus() + " " + Probe.xFocus(TITLE));
        Point fieldAt = Edt.call(() -> Probe.screenCentre(field));
        if (!p.strict) {
            p.move(fieldAt.x, fieldAt.y);
        }
        keyCase("D-after-dialog", "robot", "abc", null, "a modal dialog of the frame was shown and disposed;"
                + " no focus action since; pointer over the field");
        if (Probe.has("xdotool")) {
            keyCase("D-after-dialog-xdotool", "xdotool", "def", null, "the same, xdotool key (XTEST)");
        }
        if (!p.strict || back) {
            keyCase("D-click-back", "robot", "ghi", () -> {
                Point c = Edt.call(() -> Probe.screenCentre(field));
                p.click(c.x, c.y);
                Edt.waitFor(() -> field.isFocusOwner(), 1500);
            }, "a Robot click into the field after the dialog closed, then keys");
        }
    }

    // ------------------------------------------------------------------ one case

    interface Step {
        void run() throws Exception;
    }

    private void keyCase(String id, String input, String letters, Step before, String note) throws Exception {
        Edt.run(() -> field.setText(""));
        synchronized (typed) {
            typed.clear();
            focusLog.clear();
        }
        if (before != null) {
            before.run();
        }
        String focusBefore = awtFocus();
        boolean frameBefore = Edt.call(() -> frame.isFocused());
        String xBefore = Probe.xFocus(TITLE);
        String sent;
        if ("robot".equals(input)) {
            String why = p.typeLetters(letters);
            sent = why == null ? "yes" : "no (" + why + ")";
        } else {
            String[] cmd = new String[4 + letters.length()];
            cmd[0] = "xdotool";
            cmd[1] = "key";
            cmd[2] = "--delay";
            cmd[3] = "30";
            for (int i = 0; i < letters.length(); i++) {
                cmd[4 + i] = String.valueOf(letters.charAt(i));
            }
            String r = Probe.exec(cmd);
            sent = r.isEmpty() ? "yes" : "yes (" + r + ")";
        }
        String text = settleText();
        String focusAfter = awtFocus();
        boolean frameAfter = Edt.call(() -> frame.isFocused());
        String typedTo;
        synchronized (typed) {
            typedTo = typed.isEmpty() ? "nothing" : String.join(" ", typed);
        }
        p.say("case=" + id + " input=" + input + " sent=" + sent + " field='" + text + "' typedTo=" + typedTo
                + " | before: " + focusBefore + " frameFocused=" + frameBefore + " " + xBefore + " | after: "
                + focusAfter + " frameFocused=" + frameAfter + " focusLog=" + focusLog());
        tsv.println(p.env + "\t" + System.getProperty("java.version") + "\t" + scenario + "\t" + id + "\t" + input
                + "\t" + sent + "\t" + text + "\t" + typedTo + "\t" + focusBefore + "\t" + focusAfter + "\t"
                + frameBefore + "\t" + frameAfter + "\t" + xBefore + "\t" + note);
    }

    /** The field's text once it has not changed for 300 ms (at most 1.5 s). */
    private String settleText() throws Exception {
        long end = System.currentTimeMillis() + 1500;
        String last = Edt.call(() -> field.getText());
        long since = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            Edt.sleep(30);
            String now = Edt.call(() -> field.getText());
            if (!now.equals(last)) {
                last = now;
                since = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - since >= 300 && !now.isEmpty()) {
                break;
            }
        }
        return last;
    }

    private String awtFocus() throws Exception {
        return Edt.call(() -> {
            KeyboardFocusManager k = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            Window w = k.getFocusedWindow();
            Component o = k.getFocusOwner();
            return "awtFocusedWindow=" + name(w) + " awtFocusOwner=" + name(o);
        });
    }

    private String focusLog() {
        synchronized (typed) {
            return focusLog.isEmpty() ? "[]" : focusLog.toString().replace(" ", "");
        }
    }

    static String name(Component c) {
        if (c == null) {
            return "none";
        }
        return c.getName() != null ? c.getName() : c.getClass().getSimpleName();
    }
}
