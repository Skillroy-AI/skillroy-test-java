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

package ai.skillroy.testjava.spikes.bs10;

import ai.skillroy.testjava.spikes.bs10.Drivers.Dispatcher;
import ai.skillroy.testjava.spikes.bs10.Drivers.PointerDriver;
import ai.skillroy.testjava.spikes.bs10.Drivers.RobotDriver;
import ai.skillroy.testjava.spikes.bs10.Drivers.RoutedDriver;
import ai.skillroy.testjava.spikes.bs10.Drivers.Safety;
import ai.skillroy.testjava.spikes.bs10.Drivers.WPoint;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.plaf.basic.ComboPopup;

/**
 * The BS10 harness, loaded into the toy's JVM with {@code -javaagent:bs10-harness.jar=<options>}. Options
 * (comma-separated {@code key=value}): {@code out} (results directory), {@code env} (a label), {@code strict=true}
 * (a person's desktop: Robot acts only while this JVM holds the focused window, the pointer is restored), {@code
 * cases} and {@code drivers} (comma lists joined with {@code +}), {@code deadline} (seconds; the JVM exits after
 * it). For every case and driver it resets the toy, runs the case's script, waits for the outcome to settle, and
 * records the outcome, the focus owner afterwards, the component that received the first press, the hit-test of the
 * first point and the mouse events AWT dispatched.
 */
public final class Harness {

    static final String ROBOT = "ROBOT";
    static final String ROUTED = "ROUTED";
    static final String DISPATCH = "DISPATCH";
    /** The running harness, for the watchdog. */
    static volatile Harness current;

    private final Map<String, String> opts;
    private final File out;
    private final String env;
    private final Safety safety;
    private final EventLog log = new EventLog();
    private final PrintWriter tsv;
    private final PrintWriter events;
    private final StringBuilder summary = new StringBuilder();
    private Toy t;
    private RobotDriver robot;
    private final RoutedDriver routed = new RoutedDriver();
    private WPoint neutral;
    private Point pointerAtStart;
    private WPoint firstPoint;
    private String hitDeepest;
    private String hitTarget;
    private int barrierMisses;
    /** The window could not be activated at the start (a desktop that refuses self-activation): no retry per run. */
    private boolean activationRefused;
    /** A note a script leaves while it runs (for example the weight of a popup while it is showing). */
    volatile String scriptNote;

    private Harness(Map<String, String> opts) throws IOException {
        this.opts = opts;
        this.env = opt("env", "unknown");
        this.out = new File(opt("out", "bs10-out"));
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IOException("cannot create " + out);
        }
        this.safety = new Safety(Boolean.parseBoolean(opt("strict", "false")));
        this.tsv = writer("results.tsv");
        this.events = writer("events.txt");
    }

    private PrintWriter writer(String name) throws IOException {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(out, name)),
                StandardCharsets.UTF_8), true);
    }

    private String opt(String k, String d) {
        String v = opts.get(k);
        return v == null ? d : v;
    }

    public static void premain(String args, Instrumentation inst) {
        final Map<String, String> o = parse(args);
        Thread th = new Thread(() -> {
            int code = 0;
            Harness h = null;
            try {
                h = new Harness(o);
                current = h;
                h.run();
            } catch (Throwable e) {
                code = 2;
                System.out.println("BS10 FAILED " + e);
                e.printStackTrace(System.out);
            } finally {
                if (h != null) {
                    h.restorePointer();
                }
                System.out.println("BS10 EXIT " + code);
                System.out.flush();
                Runtime.getRuntime().halt(code);
            }
        }, "bs10-harness");
        th.setDaemon(true);
        th.start();
        final long deadline = Long.parseLong(o.containsKey("deadline") ? o.get("deadline") : "240");
        Thread watchdog = new Thread(() -> {
            Edt.sleep(deadline * 1000);
            System.out.println("BS10 WATCHDOG: deadline of " + deadline + " s passed; halting");
            Harness h = current;
            if (h != null && h.robot != null) {
                // never leave a button down on a person's desktop, and put the pointer back
                h.robot.robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                h.robot.robot.mouseRelease(java.awt.event.InputEvent.BUTTON3_DOWN_MASK);
                h.restorePointer();
            }
            System.out.flush();
            Runtime.getRuntime().halt(3);
        }, "bs10-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
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

    private void say(String s) {
        System.out.println("BS10 " + s);
        System.out.flush();
    }

    private void run() throws Exception {
        long t0 = System.nanoTime();
        while (Toy.instance == null) {
            if (System.nanoTime() - t0 > 20_000_000_000L) {
                throw new IllegalStateException("the toy did not start within 20 s");
            }
            Thread.sleep(20);
        }
        t = Toy.instance;
        long shownAt = System.nanoTime();
        boolean focused = Edt.waitFor(() -> t.frame.isFocused(), 5000);
        long focusMs = (System.nanoTime() - shownAt) / 1_000_000;
        Edt.run(() -> {
            ToolTipManager.sharedInstance().setInitialDelay(150);
            ToolTipManager.sharedInstance().setDismissDelay(10000);
        });
        log.install();
        PointerInfo pi = MouseInfo.getPointerInfo();
        pointerAtStart = pi == null ? null : pi.getLocation();

        String info = Edt.call(() -> {
            Insets in = t.frame.getInsets();
            return "java=" + System.getProperty("java.version") + " vendor=" + System.getProperty("java.vendor")
                    + " os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                    + " toolkit=" + Toolkit.getDefaultToolkit().getClass().getName()
                    + " headless=" + GraphicsEnvironment.isHeadless()
                    + " screen=" + Toolkit.getDefaultToolkit().getScreenSize().width + "x"
                    + Toolkit.getDefaultToolkit().getScreenSize().height
                    + " frameBounds=" + rect(t.frame.getBounds())
                    + " frameOnScreen=" + t.frame.getLocationOnScreen().x + "," + t.frame.getLocationOnScreen().y
                    + " insets=" + in.top + "," + in.left + "," + in.bottom + "," + in.right
                    + " popupOnPress=" + Drivers.POPUP_ON_PRESS;
        });
        say("env=" + env + " " + info);
        say("frame focused after show: " + focused + " (" + focusMs + " ms)");
        if (!focused) {
            boolean act = activate(3000);
            say("activation by toFront()+requestFocus(): " + act);
            activationRefused = !act;
        }
        try {
            robot = new RobotDriver(new Robot(), safety);
        } catch (Exception e) {
            say("Robot unavailable: " + e);
        }
        neutral = Edt.call(() -> WPoint.of(t.content, Toy.WIDTH - 20, Toy.HEIGHT - 20));
        tsv.println("env\tjava\tcase\tdriver\toutcome\tsameAsRobot\tfocusAfter\tpressTarget\thitDeepest\thitMouseTarget"
                + "\teventsSameAsRobot\tnote");
        summary.append("# BS10 ").append(env).append(" java ").append(System.getProperty("java.version"))
                .append("\n\n").append(info).append("\n\nframe focused after show: ").append(focused).append(" (")
                .append(focusMs).append(" ms)\n\n");
        summary.append("| case | ROBOT | ROUTED | DISPATCH | routed = robot | dispatch = robot | focus after (R / Ro / D)"
                + " | press target (R / Ro / D) | hit-test deepest / mouse target | routed events = robot |\n");
        summary.append("|---|---|---|---|---|---|---|---|---|---|\n");

        List<String> wantCases = list(opt("cases", "all"));
        List<String> wantDrivers = list(opt("drivers", "ROBOT+ROUTED+DISPATCH"));
        for (Case c : cases()) {
            if (!wantCases.contains("all") && !wantCases.contains(c.id)) {
                continue;
            }
            Map<String, Run> runs = new LinkedHashMap<String, Run>();
            for (String d : Arrays.asList(ROBOT, ROUTED, DISPATCH)) {
                if (!wantDrivers.contains(d)) {
                    continue;
                }
                runs.put(d, runOne(c, d));
            }
            report(c, runs);
        }
        tsv.close();
        events.close();
        try (PrintWriter w = writer("summary.md")) {
            w.print(summary);
        }
        say("DONE in " + (System.nanoTime() - t0) / 1_000_000 + " ms; motion barrier misses: " + barrierMisses);
    }

    private static List<String> list(String s) {
        return Arrays.asList(s.split("\\+"));
    }

    private boolean activate(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            Edt.run(() -> {
                t.frame.toFront();
                t.frame.requestFocus();
            });
            if (Edt.waitFor(() -> t.frame.isFocused(), 1000)) {
                return true;
            }
        }
        return false;
    }

    /** The outcome of one case on one driver. */
    static final class Run {
        String outcome;
        String focus;
        String press;
        String deepest;
        String target;
        List<String> events = new ArrayList<String>();
        String note = "";
    }

    private Run runOne(Case c, String driver) throws Exception {
        Run r = new Run();
        try {
            reset();
            c.setUp(this);
            String before = Edt.call(() -> c.observe(t));
            firstPoint = null;
            hitDeepest = null;
            hitTarget = null;
            scriptNote = null;
            log.start();
            try {
                if (DISPATCH.equals(driver)) {
                    c.dispatch(this);
                } else {
                    PointerDriver d = ROBOT.equals(driver) ? robot : routed;
                    if (d == null) {
                        throw new IllegalStateException("no Robot");
                    }
                    c.pointer(this, new Recording(d));
                }
                r.outcome = settle(c, before);
            } finally {
                r.events = log.stop();
            }
            r.press = log.firstPressTarget();
            r.focus = Edt.call(() -> {
                Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                return f == null ? "none" : EventLog.name(f);
            });
            r.deepest = hitDeepest;
            r.target = hitTarget;
            r.note = c.note(this) + (scriptNote == null ? "" : (c.note(this).isEmpty() ? "" : "; ") + scriptNote);
        } catch (Drivers.SafetyException e) {
            r.outcome = "SKIPPED(safety: " + e.getMessage() + ")";
        } catch (Exception e) {
            r.outcome = "ERROR(" + e + ")";
        } finally {
            try {
                c.tearDown(this);
            } catch (Exception e) {
                r.note += " tearDown failed: " + e;
            }
        }
        say("case=" + c.id + " driver=" + driver + " outcome=" + r.outcome + " focus=" + r.focus + " press="
                + r.press + " hit=" + r.deepest + "/" + r.target + (r.note.isEmpty() ? "" : " note=" + r.note));
        events.println("== " + c.id + " " + driver + " -> " + r.outcome);
        for (String e : r.events) {
            events.println("   " + e);
        }
        return r;
    }

    /** Polls the outcome until it changed and stayed the same for 250 ms, or the case's deadline passed. */
    private String settle(Case c, String before) throws Exception {
        long end = System.currentTimeMillis() + c.deadlineMs();
        String last = before;
        long lastChange = -1;
        while (true) {
            String now = Edt.call(() -> c.observe(t));
            long ms = System.currentTimeMillis();
            if (!now.equals(last)) {
                last = now;
                lastChange = ms;
            }
            if (lastChange > 0 && ms - lastChange >= 250) {
                return now;
            }
            if (ms >= end) {
                return now;
            }
            Thread.sleep(25);
        }
    }

    private void reset() throws Exception {
        Edt.run(() -> {
            t.resetState();
            t.home.requestFocusInWindow();
        });
        if (!Edt.waitFor(() -> t.frame.isFocused(), 300) && !activationRefused) {
            activate(2000);
            Edt.run(() -> t.home.requestFocusInWindow());
        }
        Edt.waitFor(() -> t.home.isFocusOwner(), 1000);
        if (robot != null) {
            // A motion barrier: wait until the native move to the neutral point has been dispatched, so that no late
            // native event of the reset lands in the next recording (Robot to the same point still yields a move).
            final CountDownLatch moved = new CountDownLatch(1);
            AWTEventListener barrier = ev -> {
                MouseEvent me = (MouseEvent) ev;
                if (me.getID() == MouseEvent.MOUSE_MOVED && me.getXOnScreen() == neutral.sx
                        && me.getYOnScreen() == neutral.sy) {
                    moved.countDown();
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(barrier, AWTEvent.MOUSE_MOTION_EVENT_MASK);
            try {
                robot.move(neutral);
                // after two misses (Robot's events are not arriving at all, e.g. a desktop that drops them) stop waiting
                if (barrierMisses < 2 && !moved.await(1000, TimeUnit.MILLISECONDS)) {
                    barrierMisses++;
                }
            } catch (Drivers.SafetyException e) {
                // strict mode without the focus: the routed move below still resets the dispatcher's state
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(barrier);
            }
        }
        routed.move(neutral);
        Edt.sleep(30);
        Edt.drain();
    }

    private void report(Case c, Map<String, Run> runs) {
        Run robotRun = runs.get(ROBOT);
        StringBuilder row = new StringBuilder("| ").append(c.id).append(" | ");
        for (String d : Arrays.asList(ROBOT, ROUTED, DISPATCH)) {
            Run r = runs.get(d);
            row.append(r == null ? "-" : r.outcome.replace("|", "/")).append(" | ");
        }
        row.append(same(robotRun, runs.get(ROUTED))).append(" | ").append(same(robotRun, runs.get(DISPATCH)))
                .append(" | ");
        row.append(field(runs, "focus")).append(" | ").append(field(runs, "press")).append(" | ");
        Run any = robotRun != null ? robotRun : runs.get(ROUTED);
        row.append(any == null ? "-" : any.deepest + " / " + any.target).append(" | ");
        Run ro = runs.get(ROUTED);
        row.append(robotRun == null || ro == null ? "-" : eventsCompare(robotRun.events, ro.events)).append(" |\n");
        summary.append(row);
        for (Map.Entry<String, Run> e : runs.entrySet()) {
            Run r = e.getValue();
            String sameEv = robotRun == null || e.getValue() == robotRun ? "-" : eventsCompare(robotRun.events,
                    r.events);
            tsv.println(env + "\t" + System.getProperty("java.version") + "\t" + c.id + "\t" + e.getKey() + "\t"
                    + r.outcome + "\t" + same(robotRun, r) + "\t" + r.focus + "\t" + r.press + "\t" + r.deepest + "\t"
                    + r.target + "\t" + sameEv + "\t" + r.note);
        }
    }

    private static String same(Run robotRun, Run r) {
        if (robotRun == null || r == null) {
            return "-";
        }
        if (robotRun.outcome.startsWith("SKIPPED") || robotRun.outcome.startsWith("ERROR")) {
            return "n/a";
        }
        return robotRun.outcome.equals(r.outcome) ? "yes" : "NO";
    }

    private static String field(Map<String, Run> runs, String f) {
        StringBuilder sb = new StringBuilder();
        for (String d : Arrays.asList(ROBOT, ROUTED, DISPATCH)) {
            Run r = runs.get(d);
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(r == null ? "-" : ("focus".equals(f) ? r.focus : r.press));
        }
        return sb.toString();
    }

    /** "yes", or "no" with the first differing entry; window-level entries (the frame's own) compared apart. */
    static String eventsCompare(List<String> a, List<String> b) {
        if (a.equals(b)) {
            return "yes";
        }
        if (withoutCounts(a).equals(withoutCounts(b))) {
            return "yes (move or drag counts differ: the queue coalesces them)";
        }
        List<String> fa = withoutCounts(withoutWindowLevel(a));
        List<String> fb = withoutCounts(withoutWindowLevel(b));
        String tag = fa.equals(fb) ? "yes except window-level entries" : "no";
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n && a.get(i).equals(b.get(i))) {
            i++;
        }
        String ra = i < a.size() ? a.get(i) : "(end)";
        String rb = i < b.size() ? b.get(i) : "(end)";
        return tag + " (first difference at #" + i + ": robot `" + ra + "` vs `" + rb + "`)";
    }

    private static List<String> withoutCounts(List<String> l) {
        List<String> r = new ArrayList<String>();
        for (String s : l) {
            r.add(s.replaceAll(" x[0-9]+$", ""));
        }
        return r;
    }

    private static List<String> withoutWindowLevel(List<String> l) {
        List<String> r = new ArrayList<String>();
        for (String s : l) {
            String[] p = s.split(" ");
            if (p.length > 1 && (p[1].equals("toyFrame") || p[1].equals("modal") || p[1].equals("cover"))) {
                continue;
            }
            r.add(s);
        }
        return r;
    }

    private void restorePointer() {
        if (robot != null && pointerAtStart != null) {
            robot.robot.mouseMove(pointerAtStart.x, pointerAtStart.y);
            say("pointer restored to " + pointerAtStart.x + "," + pointerAtStart.y);
        }
    }

    static String rect(Rectangle r) {
        return r.x + "," + r.y + "," + r.width + "x" + r.height;
    }

    // ------------------------------------------------------------------ points and hit-tests

    WPoint at(final Component c, final double fx, final double fy) throws Exception {
        return Edt.call(() -> WPoint.of(c, (int) (c.getWidth() * fx), (int) (c.getHeight() * fy)));
    }

    /** A point inside the left third of character {@code index} of an unscrolled, left-aligned text field. */
    WPoint textPoint(final JTextField f, final int index) throws Exception {
        return Edt.call(() -> {
            Point p = textLocal(f, index);
            return WPoint.of(f, p.x, p.y);
        });
    }

    static Point textLocal(JTextField f, int index) {
        FontMetrics fm = f.getFontMetrics(f.getFont());
        String s = f.getText();
        int x = f.getInsets().left + fm.stringWidth(s.substring(0, index));
        int w = fm.charWidth(s.charAt(index));
        return new Point(x + Math.max(1, w / 3), f.getHeight() / 2);
    }

    WPoint listCell(final JList<?> l, final int index) throws Exception {
        return Edt.call(() -> {
            Rectangle r = l.getCellBounds(index, index);
            return WPoint.of(l, r.x + r.width / 2, r.y + r.height / 2);
        });
    }

    /** Records the first point a script acts at and its hit-test, taken before the action. */
    private void recordFirst(final WPoint p) throws Exception {
        if (firstPoint != null) {
            return;
        }
        firstPoint = p;
        String[] hit = Edt.call(() -> new String[] {
            EventLog.name(HitTest.deepest(p.window, p.x, p.y)),
            EventLog.name(HitTest.mouseTarget(p.window, p.x, p.y))});
        hitDeepest = hit[0];
        hitTarget = hit[1];
    }

    /** Records the hit-test for a DISPATCH script, which has no driver: the case's natural point. */
    void recordFirstDispatch(WPoint p) throws Exception {
        recordFirst(p);
    }

    /** A pointer driver that records the first point and its hit-test before passing each call on. */
    private final class Recording implements PointerDriver {
        private final PointerDriver d;

        Recording(PointerDriver d) {
            this.d = d;
        }

        @Override
        public String name() {
            return d.name();
        }

        @Override
        public void move(WPoint p) throws Exception {
            d.move(p);
        }

        @Override
        public void click(WPoint p, int button, int clickCount) throws Exception {
            recordFirst(p);
            d.click(p, button, clickCount);
        }

        @Override
        public void drag(WPoint from, WPoint to, int steps) throws Exception {
            recordFirst(from);
            d.drag(from, to, steps);
        }

        @Override
        public void wheel(WPoint p, int notches) throws Exception {
            recordFirst(p);
            d.wheel(p, notches);
        }
    }

    // ------------------------------------------------------------------ the cases

    /** One case: a pointer script (Robot and ROUTED), a DISPATCH script, and the outcome to observe. */
    abstract static class Case {
        final String id;

        Case(String id) {
            this.id = id;
        }

        void setUp(Harness h) throws Exception {
        }

        void tearDown(Harness h) throws Exception {
        }

        abstract void pointer(Harness h, PointerDriver d) throws Exception;

        abstract void dispatch(Harness h) throws Exception;

        /** EDT only. */
        abstract String observe(Toy t);

        String note(Harness h) throws Exception {
            return "";
        }

        long deadlineMs() {
            return 1000;
        }
    }

    private static boolean tooltipShowing() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && find(w, JToolTip.class) != null) {
                return true;
            }
        }
        return false;
    }

    private static Component find(Container c, Class<?> type) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k) && k.isShowing()) {
                return k;
            }
            if (k instanceof Container) {
                Component f = find((Container) k, type);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private static JList<?> comboList(Toy t) {
        Object child = t.combo.getUI().getAccessibleChild(t.combo, 0);
        return child instanceof ComboPopup ? ((ComboPopup) child).getList() : null;
    }

    private static String popupWeight(Component item, Window frame) {
        Window w = SwingUtilities.getWindowAncestor(item);
        return w == frame ? "lightweight" : "heavyweight(" + (w == null ? "none" : w.getClass().getSimpleName()) + ")";
    }

    List<Case> cases() {
        List<Case> l = new ArrayList<Case>();
        l.add(new Case("button") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                d.click(h.at(t.count, .5, .5), 1, 1);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(t.count, .5, .5));
                Dispatcher.builtClick(t.count);
            }

            String observe(Toy t) {
                return "count=" + t.countClicks + " entered=" + t.countEntered + " rollover="
                        + t.count.getModel().isRollover();
            }
        });
        l.add(new Case("tooltip") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                WPoint p = h.at(t.count, .5, .5);
                h.recordFirst(p);
                d.move(p);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(t.count, .5, .5));
                Dispatcher.hover(t.count, new Point(t.count.getWidth() / 2, t.count.getHeight() / 2));
            }

            String observe(Toy t) {
                return "tooltip=" + (tooltipShowing() ? "shown" : "none");
            }

            long deadlineMs() {
                return 1500;
            }
        });
        l.add(new Case("caret") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                d.click(h.textPoint(t.caretField, 12), 1, 1);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(t.caretField, .5, .5));
                Dispatcher.builtClick(t.caretField);
            }

            String observe(Toy t) {
                int dot = t.caretField.getCaret().getDot();
                int mark = t.caretField.getCaret().getMark();
                return "caret=" + dot + (mark != dot ? " mark=" + mark : "");
            }
        });
        l.add(new Case("select-drag") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                d.drag(h.textPoint(t.selField, 4), h.textPoint(t.selField, 15), 6);
            }

            void dispatch(Harness h) throws Exception {
                Point[] p = Edt.call(() -> new Point[] {textLocal(t.selField, 4), textLocal(t.selField, 15)});
                h.recordFirstDispatch(h.textPoint(t.selField, 4));
                Dispatcher.drag(t.selField, p[0], p[1], 6);
            }

            String observe(Toy t) {
                return "sel=" + t.selField.getSelectionStart() + "-" + t.selField.getSelectionEnd();
            }

            String note(Harness h) {
                return "DISPATCH extended (component-sourced at the same points; the built rung has no drag)";
            }
        });
        l.add(new Case("double-click") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                WPoint p = h.listCell(t.list, 2);
                d.click(p, 1, 1);
                Edt.sleep(60);
                d.click(p, 1, 2);
            }

            void dispatch(Harness h) throws Exception {
                Rectangle vis = Edt.call(() -> t.list.getVisibleRect());
                Point c = new Point(vis.x + vis.width / 2, vis.y + vis.height / 2);
                h.recordFirstDispatch(Edt.call(() -> WPoint.of(t.list, c.x, c.y)));
                Dispatcher.click(t.list, c, 1, 1);
                Edt.sleep(60);
                Dispatcher.click(t.list, c, 1, 2);
            }

            String observe(Toy t) {
                return "doubles=" + t.doubleClicks + "@" + t.lastDoubleIndex + " sel=" + t.list.getSelectedIndex();
            }

            String note(Harness h) {
                return "DISPATCH extended (two clicks, counts 1 and 2, at the visible centre)";
            }
        });
        l.add(popupCase("popup-listener", true));
        l.add(popupCase("popup-component", false));
        l.add(new Case("combo") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                d.click(h.at(t.combo, .5, .5), 1, 1);
                if (!Edt.waitFor(() -> comboList(t) != null && comboList(t).isShowing(), 1500)) {
                    return;
                }
                JList<?> pl = Edt.call(() -> comboList(t));
                h.scriptNote = Edt.call(() -> "combo popup " + popupWeight(pl, t.frame));
                d.click(h.listCell(pl, 2), 1, 1);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(t.combo, .5, .5));
                Dispatcher.builtClick(t.combo);
                if (!Edt.waitFor(() -> comboList(t) != null && comboList(t).isShowing(), 1500)) {
                    return;
                }
                Dispatcher.builtClick(Edt.call(() -> comboList(t)));
            }

            String observe(Toy t) {
                return "selected=" + t.combo.getSelectedItem() + " open=" + t.combo.isPopupVisible();
            }

        });
        l.add(new Case("table") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                WPoint p = Edt.call(() -> {
                    Rectangle r = t.table.getCellRect(5, 1, false);
                    return WPoint.of(t.table, r.x + r.width / 2, r.y + r.height / 2);
                });
                d.click(p, 1, 1);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(t.table, .5, .5));
                Dispatcher.builtClick(t.table);
            }

            String observe(Toy t) {
                return "row=" + t.table.getSelectedRow() + " col=" + t.table.getSelectedColumn();
            }
        });
        l.add(new Case("wheel") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                d.wheel(h.at(t.listScroll.getViewport(), .5, .5), 3);
            }

            void dispatch(Harness h) throws Exception {
                Rectangle vis = Edt.call(() -> t.list.getVisibleRect());
                Point c = new Point(vis.x + vis.width / 2, vis.y + vis.height / 2);
                h.recordFirstDispatch(Edt.call(() -> WPoint.of(t.list, c.x, c.y)));
                Dispatcher.wheel(t.list, c, 3);
            }

            String observe(Toy t) {
                return "first=" + t.list.getFirstVisibleIndex() + " viewY="
                        + t.listScroll.getViewport().getViewPosition().y;
            }

            String note(Harness h) {
                return "DISPATCH extended (three wheel events to the list)";
            }
        });
        l.add(new Case("slider-drag") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                WPoint[] p = Edt.call(() -> {
                    Rectangle th = t.sliderUi.thumb();
                    Rectangle tr = t.sliderUi.track();
                    int y = th.y + th.height / 2;
                    return new WPoint[] {WPoint.of(t.slider, th.x + th.width / 2, y),
                        WPoint.of(t.slider, tr.x + tr.width * 3 / 4 + th.width / 2, y)};
                });
                d.drag(p[0], p[1], 8);
            }

            void dispatch(Harness h) throws Exception {
                Point[] p = Edt.call(() -> {
                    Rectangle th = t.sliderUi.thumb();
                    Rectangle tr = t.sliderUi.track();
                    int y = th.y + th.height / 2;
                    return new Point[] {new Point(th.x + th.width / 2, y),
                        new Point(tr.x + tr.width * 3 / 4 + th.width / 2, y)};
                });
                h.recordFirstDispatch(Edt.call(() -> WPoint.of(t.slider, p[0].x, p[0].y)));
                Dispatcher.drag(t.slider, p[0], p[1], 8);
            }

            String observe(Toy t) {
                return "value=" + t.slider.getValue();
            }

            String note(Harness h) {
                return "DISPATCH extended (component-sourced at the same points)";
            }
        });
        l.add(glassCase("glass-listening", true));
        l.add(glassCase("glass-transparent", false));
        l.add(new Case("disabled") {
            void pointer(Harness h, PointerDriver d) throws Exception {
                d.click(h.at(t.disabled, .5, .5), 1, 1);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(t.disabled, .5, .5));
                Dispatcher.builtClick(t.disabled);
            }

            String observe(Toy t) {
                return "actions=" + t.disabledActions + " presses=" + t.disabledPresses;
            }

            String note(Harness h) {
                return "the built DISPATCH rung refuses a disabled component before dispatching (requireClickable);"
                        + " the raw dispatch is shown";
            }
        });
        l.add(new Case("covered") {
            void setUp(Harness h) throws Exception {
                Edt.run(() -> {
                    Rectangle r = new Rectangle(t.covered.getLocationOnScreen(), t.covered.getSize());
                    t.cover.setBounds(r.x - 4, r.y - 4, r.width + 8, r.height + 8);
                    t.cover.setVisible(true);
                });
                Edt.waitFor(() -> t.cover.isShowing(), 2000);
                Edt.sleep(150);
            }

            void tearDown(Harness h) throws Exception {
                Edt.run(() -> t.cover.setVisible(false));
            }

            void pointer(Harness h, PointerDriver d) throws Exception {
                d.click(h.at(t.covered, .5, .5), 1, 1);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(t.covered, .5, .5));
                Dispatcher.builtClick(t.covered);
            }

            String observe(Toy t) {
                return "covered=" + t.coveredClicks + " cover=" + t.coverPresses;
            }
        });
        l.add(new Case("modal") {
            void setUp(Harness h) throws Exception {
                Edt.run(() -> {
                    Point o = t.frame.getLocationOnScreen();
                    t.modal.setLocation(o.x + 380, o.y + 450);
                });
                javax.swing.SwingUtilities.invokeLater(() -> t.modal.setVisible(true));
                Edt.waitFor(() -> t.modal.isShowing(), 3000);
                Edt.sleep(200);
            }

            void tearDown(Harness h) throws Exception {
                javax.swing.SwingUtilities.invokeLater(() -> t.modal.setVisible(false));
                Edt.waitFor(() -> !t.modal.isShowing(), 3000);
            }

            void pointer(Harness h, PointerDriver d) throws Exception {
                d.click(h.at(t.count, .5, .5), 1, 1);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(t.count, .5, .5));
                Dispatcher.builtClick(t.count);
            }

            String observe(Toy t) {
                return "count=" + t.countClicks;
            }

            String note(Harness h) throws Exception {
                return Edt.call(() -> "modal showing=" + t.modal.isShowing() + " focusedWindow=" + EventLog.name(
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow())
                        + "; the built rung refuses MODAL_OPEN unless force");
            }
        });
        return l;
    }

    private Case popupCase(final String id, final boolean listener) {
        return new Case(id) {
            private javax.swing.JPopupMenu menu() {
                return listener ? t.popup : t.popup2;
            }

            private JComponent area() {
                return listener ? t.popupArea : t.popupArea2;
            }

            private JMenuItem item() {
                return (JMenuItem) menu().getComponent(1);
            }

            void pointer(Harness h, PointerDriver d) throws Exception {
                d.click(h.at(area(), .5, .5), 3, 1);
                if (!Edt.waitFor(() -> item().isShowing(), 1500)) {
                    return;
                }
                Edt.sleep(50);
                h.scriptNote = Edt.call(() -> "popup " + popupWeight(item(), t.frame));
                d.click(h.at(item(), .5, .5), 1, 1);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(area(), .5, .5));
                Dispatcher.click(area(), new Point(area().getWidth() / 2, area().getHeight() / 2), 3, 1);
                if (!Edt.waitFor(() -> item().isShowing(), 1500)) {
                    return;
                }
                Edt.sleep(50);
                Dispatcher.builtClick(item());
            }

            String observe(Toy t) {
                return "chosen=" + (listener ? t.popupChosen : t.popup2Chosen) + " open=" + menu().isVisible();
            }

            String note(Harness h) throws Exception {
                return "DISPATCH extended for the right-click (button 3, popup trigger on the "
                        + (Drivers.POPUP_ON_PRESS ? "press" : "release") + "); the item by the built rung";
            }
        };
    }

    private Case glassCase(final String id, final boolean listening) {
        return new Case(id) {
            void setUp(Harness h) throws Exception {
                Edt.run(() -> {
                    JComponent g = listening ? t.glassListening : t.glassTransparent;
                    t.frame.setGlassPane(g);
                    g.setVisible(true);
                });
            }

            void tearDown(Harness h) throws Exception {
                Edt.run(() -> {
                    t.glassListening.setVisible(false);
                    t.glassTransparent.setVisible(false);
                    t.frame.setGlassPane(t.glassListening);
                });
            }

            void pointer(Harness h, PointerDriver d) throws Exception {
                d.click(h.at(t.underGlass, .5, .5), 1, 1);
            }

            void dispatch(Harness h) throws Exception {
                h.recordFirstDispatch(h.at(t.underGlass, .5, .5));
                Dispatcher.builtClick(t.underGlass);
            }

            String observe(Toy t) {
                return "glass=" + t.glassPresses + " underGlass=" + t.underGlassClicks;
            }
        };
    }

}
