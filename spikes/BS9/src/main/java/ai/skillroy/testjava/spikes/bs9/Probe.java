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

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * What both BS9 probes share: options, output files, the X diagnostics (xdotool and xprop, when installed), and Robot
 * input behind the safety gate of a person's desktop (strict mode): a pointer action only at a point inside one of
 * this JVM's showing windows and only while this JVM holds the focused window; a key press only while the probe's
 * own frame is the focused window (a {@link WindowFocusListener} flag checked before every press, plus a read on the
 * EDT), only letter keys; the pointer put back where it was at the end.
 */
final class Probe {

    final Map<String, String> opts;
    final String env;
    final boolean strict;
    final File out;
    final PrintWriter log;
    Robot robot;
    Point pointerAtStart;
    volatile boolean frameFocused;
    Window frame;

    Probe(Map<String, String> opts, String logName) throws IOException {
        this.opts = opts;
        this.env = opt("env", "unknown");
        this.strict = Boolean.parseBoolean(opt("strict", "false"));
        this.out = new File(opt("out", "bs9-out"));
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IOException("cannot create " + out);
        }
        this.log = writer(logName);
    }

    String opt(String k, String d) {
        String v = opts.get(k);
        return v == null ? d : v;
    }

    PrintWriter writer(String name) throws IOException {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(out, name)),
                StandardCharsets.UTF_8), true);
    }

    static Map<String, String> parse(String args) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (args == null) {
            return m;
        }
        for (String part : args.split(",")) {
            int i = part.indexOf('=');
            if (i > 0) {
                m.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
            }
        }
        return m;
    }

    void say(String s) {
        System.out.println("BS9 " + s);
        System.out.flush();
        log.println(s);
    }

    static void watchdog(final long seconds, final Probe p) {
        Thread w = new Thread(() -> {
            Edt.sleep(seconds * 1000);
            System.out.println("BS9 WATCHDOG: deadline of " + seconds + " s passed; halting");
            if (p != null) {
                p.restorePointer();
            }
            System.out.flush();
            Runtime.getRuntime().halt(3);
        }, "bs9-watchdog");
        w.setDaemon(true);
        w.start();
    }

    /** Creates the Robot and remembers where the pointer is. */
    void startRobot() {
        try {
            robot = new Robot();
            robot.setAutoDelay(0);
        } catch (Exception e) {
            say("Robot unavailable: " + e);
        }
        PointerInfo pi = MouseInfo.getPointerInfo();
        pointerAtStart = pi == null ? null : pi.getLocation();
    }

    void restorePointer() {
        if (robot != null && pointerAtStart != null) {
            robot.mouseMove(pointerAtStart.x, pointerAtStart.y);
            say("pointer restored to " + pointerAtStart.x + "," + pointerAtStart.y);
        }
    }

    /** Tracks whether {@code w} is the focused window (the flag the key gate checks before every press). */
    void watchFocus(final Window w) throws Exception {
        frame = w;
        Edt.run(() -> {
            w.addWindowFocusListener(new WindowFocusListener() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                    frameFocused = true;
                }

                @Override
                public void windowLostFocus(WindowEvent e) {
                    frameFocused = false;
                }
            });
            frameFocused = w.isFocused();
        });
    }

    /** Why a pointer action at the screen point would be unsafe, or null. */
    String pointerRefusal(final int sx, final int sy) throws Exception {
        return Edt.call(() -> {
            if (strict && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == null) {
                return "no window of this JVM is focused";
            }
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && new Rectangle(w.getLocationOnScreen(), w.getSize()).contains(sx, sy)) {
                    return null;
                }
            }
            return "screen point " + sx + "," + sy + " is outside every window of this JVM";
        });
    }

    /** Why a key press would be unsafe now, or null (strict mode only: the probe's own frame must be focused). */
    String keyRefusal() throws Exception {
        if (!strict) {
            return null;
        }
        if (!frameFocused) {
            return "the probe's frame is not the focused window (focus listener)";
        }
        boolean ok = Edt.call(() -> frame != null && frame.isFocused()
                && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == frame);
        return ok ? null : "the probe's frame is not the focused window (EDT check)";
    }

    /** Moves the pointer to a screen point; false (and nothing done) when the gate refuses. */
    boolean move(int sx, int sy) throws Exception {
        String why = pointerRefusal(sx, sy);
        if (why != null) {
            say("pointer move refused: " + why);
            return false;
        }
        robot.mouseMove(sx, sy);
        Edt.sleep(40);
        return true;
    }

    boolean click(int sx, int sy) throws Exception {
        if (!move(sx, sy)) {
            return false;
        }
        String why = pointerRefusal(sx, sy);
        if (why != null) {
            say("click refused: " + why);
            return false;
        }
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Edt.sleep(25);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Edt.sleep(40);
        return true;
    }

    /**
     * Types lower-case letters with Robot; stops at the first refused press.
     *
     * @return null when all were sent, else why the rest was not sent
     */
    String typeLetters(String letters) throws Exception {
        for (char ch : letters.toCharArray()) {
            if (ch < 'a' || ch > 'z') {
                throw new IllegalArgumentException("letters only: " + letters);
            }
            String why = keyRefusal();
            if (why != null) {
                say("key part aborted before '" + ch + "': " + why);
                return why;
            }
            int vk = KeyEvent.VK_A + (ch - 'a');
            robot.keyPress(vk);
            Edt.sleep(15);
            robot.keyRelease(vk);
            Edt.sleep(25);
        }
        return null;
    }

    /** The point at the centre of a component, on the screen. EDT only. */
    static Point screenCentre(Component c) {
        Point o = c.getLocationOnScreen();
        return new Point(o.x + c.getWidth() / 2, o.y + c.getHeight() / 2);
    }

    // ------------------------------------------------------------------ external tools (Linux images only)

    static boolean has(String tool) {
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            return false; // the X tools mean nothing on the macOS desktop, even when installed
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String d : path.split(File.pathSeparator)) {
            if (new File(d, tool).canExecute()) {
                return true;
            }
        }
        return false;
    }

    /** Runs a command with a 5 s deadline; its stdout and stderr in one line, or why it did not run. */
    static String exec(String... cmd) {
        if (!has(cmd[0])) {
            return "(" + cmd[0] + " absent)";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            byte[] b = new byte[4096];
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(b);
                    if (n < 0) {
                        break;
                    }
                    buf.write(b, 0, n);
                }
                if (p.waitFor(20, TimeUnit.MILLISECONDS)) {
                    int n;
                    while ((n = in.read(b)) > 0) {
                        buf.write(b, 0, n);
                    }
                    break;
                }
                if (System.nanoTime() > end) {
                    p.destroyForcibly();
                    return "(timed out) " + oneLine(buf);
                }
            }
            return oneLine(buf) + (p.exitValue() == 0 ? "" : " [exit " + p.exitValue() + "]");
        } catch (Exception e) {
            return "(" + e + ")";
        }
    }

    private static String oneLine(ByteArrayOutputStream buf) {
        return new String(buf.toByteArray(), StandardCharsets.UTF_8).trim().replace('\n', ' ');
    }

    /** The X server's view of the focus and the active window, when the tools are installed. */
    static String xFocus(String title) {
        if (!has("xdotool")) {
            return "x=(no xdotool)";
        }
        List<String> parts = new ArrayList<String>();
        String raw = exec("xdotool", "getwindowfocus", "-f");
        if (raw.contains("focused window of 1")) {
            parts.add("xFocus=PointerRoot");
        } else if (raw.contains("focused window of 0")) {
            parts.add("xFocus=None");
        } else {
            parts.add("xFocus=" + raw + " (parent " + parent(raw) + ")");
        }
        parts.add("frameIds=" + exec("xdotool", "search", "--name", "^" + title + "$").replace(' ', '+'));
        String active = exec("xprop", "-root", "_NET_ACTIVE_WINDOW");
        parts.add("netActive=" + (active.contains("no such atom") ? "unset" : active.replaceAll(".*# ", "")));
        return String.join(" ", parts);
    }

    /** The parent of an X window (decimal id), from xwininfo, in decimal; or what went wrong. */
    static String parent(String id) {
        if (!id.matches("[0-9]+") || !has("xwininfo")) {
            return "?";
        }
        String t = exec("xwininfo", "-tree", "-id", id);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Parent window id: 0x([0-9a-fA-F]+)").matcher(t);
        return m.find() ? String.valueOf(Long.parseLong(m.group(1), 16)) : "?";
    }

    static List<String> list(String... s) {
        return new ArrayList<String>(Arrays.asList(s));
    }
}
