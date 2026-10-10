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

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * BS9's sample probe, loaded with {@code -javaagent:bs9-probe.jar=<options>} into the sample lending desk launched
 * from its built jar. Options: {@code out}, {@code env}, {@code strict}, {@code images=false} (no PNGs). It waits for
 * the desk's frame, then: a ROBOT capture against a PAINT capture of the frame (three attempts; pixel differences
 * counted and attributed to the component under them, or to the decorations), Robot keys into the focused entry field,
 * Robot clicks on three keypad keys (each counted by an action listener the probe adds), the same three keys clicked
 * by routed window-sourced events and by the built DISPATCH rung's delivery, and the capture comparison again with
 * text in the field. The keys clicked are DUE, CANCEL and RETURN, which do nothing in the signed-off state.
 */
public final class SampleProbe {

    static final String[] KEYS = {"DUE", "CANCEL", "RETURN"};

    private final Probe p;
    private final PrintWriter tsv;
    private Frame frame;
    private JTextField entry;
    private final Map<String, Component> keys = new LinkedHashMap<String, Component>();
    private final Map<String, AtomicInteger> fired = new LinkedHashMap<String, AtomicInteger>();

    private SampleProbe(Probe p) throws Exception {
        this.p = p;
        this.tsv = p.writer("sample.tsv");
        tsv.println("env\tjava\tstep\tdriver\toutcome\tnote");
    }

    public static void premain(String args, Instrumentation inst) {
        final Map<String, String> o = Probe.parse(args);
        Thread th = new Thread(() -> {
            int code = 0;
            Probe p = null;
            try {
                p = new Probe(o, "sample.log");
                Probe.watchdog(Long.parseLong(p.opt("deadline", p.strict ? "50" : "120")), p);
                new SampleProbe(p).run();
            } catch (Throwable e) {
                code = 2;
                System.out.println("BS9 FAILED " + e);
                e.printStackTrace(System.out);
            } finally {
                if (p != null) {
                    p.restorePointer();
                    p.say("EXIT " + code);
                }
                System.out.flush();
                Runtime.getRuntime().halt(code);
            }
        }, "bs9-sample-probe");
        th.setDaemon(true);
        th.start();
    }

    private void row(String step, String driver, String outcome, String note) {
        tsv.println(p.env + "\t" + System.getProperty("java.version") + "\t" + step + "\t" + driver + "\t" + outcome
                + "\t" + note);
        p.say("step=" + step + " driver=" + driver + " outcome=" + outcome + (note.isEmpty() ? "" : " note=" + note));
    }

    private void run() throws Exception {
        long t0 = System.nanoTime();
        while (frame == null) {
            frame = Edt.call(() -> {
                for (Frame f : Frame.getFrames()) {
                    if (f.isShowing() && f.getClass().getName().endsWith("LendingFrame")) {
                        return f;
                    }
                }
                return null;
            });
            if (frame == null) {
                if (System.nanoTime() - t0 > 30_000_000_000L) {
                    throw new IllegalStateException("the desk's frame did not show within 30 s");
                }
                Thread.sleep(50);
            }
        }
        long shownMs = (System.nanoTime() - t0) / 1_000_000;
        p.watchFocus(frame);
        boolean focused = Edt.waitFor(() -> frame.isFocused(), 5000);
        String info = Edt.call(() -> {
            Insets in = frame.getInsets();
            Point o = frame.getLocationOnScreen();
            return "java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name") + "/"
                    + System.getProperty("os.arch") + " frame=" + frame.getWidth() + "x" + frame.getHeight() + " at "
                    + o.x + "," + o.y + " insets=" + in.top + "," + in.left + "," + in.bottom + "," + in.right;
        });
        p.say("env=" + p.env + " " + info + " shown after " + shownMs + " ms; focused=" + focused);
        if (!focused) {
            boolean act = false;
            for (int i = 0; i < 3 && !act; i++) {
                Edt.run(() -> {
                    frame.toFront();
                    frame.requestFocus();
                });
                act = Edt.waitFor(() -> frame.isFocused(), 1000);
            }
            p.say("self-activation by toFront()+requestFocus(): " + act);
        }
        Edt.run(() -> {
            collect(frame);
            for (final Map.Entry<String, Component> e : keys.entrySet()) {
                final AtomicInteger n = new AtomicInteger();
                fired.put(e.getKey(), n);
                try {
                    e.getValue().getClass().getMethod("addActionListener", ActionListener.class)
                            .invoke(e.getValue(), (ActionListener) ev -> n.incrementAndGet());
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        });
        p.say("found keys " + keys.keySet() + " entry=" + (entry != null));
        p.startRobot();
        Edt.sleep(800);
        boolean images = !"false".equals(p.opt("images", "true"));

        capture("initial", images);

        // Robot keys into whatever has the focus (the entry field when the desk has just started).
        String owner = focusOwner();
        String why = p.robot == null ? "no Robot" : p.typeLetters("abc");
        String text = settleEntry("abc");
        row("keys", "ROBOT", "entry='" + text + "' focusOwner=" + focusOwner(), "focus owner before: " + owner
                + (why == null ? "" : "; not all sent: " + why));

        for (String driver : new String[] {"ROBOT", "ROUTED", "DISPATCH"}) {
            for (String id : KEYS) {
                clickKey(driver, id);
            }
        }

        capture("after-input", images);
        tsv.close();
        p.say("DONE in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }

    private void collect(Container c) {
        for (Component k : c.getComponents()) {
            String cn = k.getClass().getName();
            if (cn.endsWith("IconKey")) {
                try {
                    String id = (String) k.getClass().getMethod("actionId").invoke(k);
                    for (String want : KEYS) {
                        if (want.equals(id)) {
                            keys.put(id, k);
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            if (k instanceof JTextField && "entry".equals(k.getName())) {
                entry = (JTextField) k;
            }
            if (k instanceof Container) {
                collect((Container) k);
            }
        }
    }

    private String focusOwner() throws Exception {
        return Edt.call(() -> {
            Component o = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (o == null) {
                return "none";
            }
            return o.getName() != null ? o.getName() : o.getClass().getSimpleName();
        });
    }

    private String settleEntry(String want) throws Exception {
        if (entry == null) {
            return "(no entry field)";
        }
        Edt.waitFor(() -> want.equals(entry.getText()), 1500);
        Edt.sleep(150);
        return Edt.call(() -> entry.getText());
    }

    private void clickKey(String driver, String id) throws Exception {
        final Component k = keys.get(id);
        if (k == null) {
            row("click " + id, driver, "key not found", "");
            return;
        }
        final AtomicInteger n = fired.get(id);
        int before = n.get();
        String note = "";
        if ("ROBOT".equals(driver)) {
            Point c = Edt.call(() -> Probe.screenCentre(k));
            if (p.robot == null || !p.click(c.x, c.y)) {
                row("click " + id, driver, "not sent", "no Robot or refused by the safety gate");
                return;
            }
        } else if ("ROUTED".equals(driver)) {
            final Point w = Edt.call(() -> SwingUtilities.convertPoint(k, k.getWidth() / 2, k.getHeight() / 2,
                    frame));
            final Point s = Edt.call(() -> Probe.screenCentre(k));
            routed(MouseEvent.MOUSE_MOVED, 0, w, s, 0, MouseEvent.NOBUTTON);
            routed(MouseEvent.MOUSE_PRESSED, InputEvent.BUTTON1_DOWN_MASK, w, s, 1, MouseEvent.BUTTON1);
            routed(MouseEvent.MOUSE_RELEASED, 0, w, s, 1, MouseEvent.BUTTON1);
            routed(MouseEvent.MOUSE_CLICKED, 0, w, s, 1, MouseEvent.BUTTON1);
        } else {
            EventQueue.invokeLater(() -> {
                int x = k.getWidth() / 2;
                int y = k.getHeight() / 2;
                long when = System.currentTimeMillis();
                k.dispatchEvent(new MouseEvent(k, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON1_DOWN_MASK, x, y,
                        1, false, MouseEvent.BUTTON1));
                k.dispatchEvent(new MouseEvent(k, MouseEvent.MOUSE_RELEASED, when, 0, x, y, 1, false,
                        MouseEvent.BUTTON1));
                k.dispatchEvent(new MouseEvent(k, MouseEvent.MOUSE_CLICKED, when, 0, x, y, 1, false,
                        MouseEvent.BUTTON1));
            });
            Edt.drain();
        }
        long t = System.nanoTime();
        boolean ok = waitCount(n, before + 1, 1500);
        long ms = (System.nanoTime() - t) / 1_000_000;
        row("click " + id, driver, ok ? "fired (" + ms + " ms)" : "not fired", "focus owner after: "
                + focusOwner() + note);
    }

    private void routed(int id, int mods, Point w, Point s, int count, int button) throws Exception {
        final MouseEvent e = new MouseEvent(frame, id, System.currentTimeMillis(), mods, w.x, w.y, s.x, s.y, count,
                false, button);
        EventQueue.invokeLater(() -> frame.dispatchEvent(e));
        Edt.drain();
        Edt.sleep(15);
    }

    private static boolean waitCount(AtomicInteger n, int want, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (n.get() >= want) {
                return true;
            }
            Edt.sleep(20);
        }
        return n.get() >= want;
    }

    // ------------------------------------------------------------------ ROBOT against PAINT

    /**
     * ROBOT against two PAINT captures of the same frame: {@code frame.printAll} as the built {@code swing.capture}
     * does (on XToolkit the frame peer's {@code print} then draws imitation decorations over the inset band, and over
     * the content when the insets are 0), and the root pane alone printed at its place in the frame (the inset band
     * left as background), compared inside the root pane's bounds only.
     */
    private void capture(String label, boolean images) throws Exception {
        if (p.robot == null) {
            row("capture " + label, "ROBOT vs PAINT", "no Robot", "");
            return;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (p.strict) {
                String why = Edt.call(() -> frame.isFocused() ? null : "the desk's frame is not focused");
                if (why != null) {
                    row("capture " + label, "ROBOT vs PAINT", "not taken", why);
                    return;
                }
            }
            final BufferedImage[] paints = Edt.call(() -> new BufferedImage[] {paintFrame(), paintRoot()});
            final Rectangle root = Edt.call(() -> ((javax.swing.RootPaneContainer) frame).getRootPane().getBounds());
            Rectangle onScreen = Edt.call(() -> new Rectangle(frame.getLocationOnScreen(), frame.getSize()));
            long t = System.nanoTime();
            BufferedImage robotImg = p.robot.createScreenCapture(onScreen);
            long robotMs = (System.nanoTime() - t) / 1_000_000;
            Diff whole = diff(paints[0], robotImg, null);
            Diff inner = diff(paints[1], robotImg, root);
            String note = "robot capture " + robotMs + " ms; robot image " + robotImg.getWidth() + "x"
                    + robotImg.getHeight() + " type " + robotImg.getType();
            row("capture " + label + " #" + attempt, "ROBOT vs PAINT(frame.printAll)", whole.summary(), note);
            row("capture " + label + " #" + attempt, "ROBOT vs PAINT(rootPane, inside it)", inner.summary(),
                    "root pane " + root.x + "," + root.y + "," + root.width + "x" + root.height);
            if (attempt == 1 && images) {
                ImageIO.write(paints[0], "png", new File(p.out, "paint-frame-" + label + ".png"));
                ImageIO.write(paints[1], "png", new File(p.out, "paint-root-" + label + ".png"));
                ImageIO.write(robotImg, "png", new File(p.out, "robot-" + label + ".png"));
                ImageIO.write(whole.mask, "png", new File(p.out, "diff-frame-" + label + ".png"));
                ImageIO.write(inner.mask, "png", new File(p.out, "diff-root-" + label + ".png"));
            }
            Edt.sleep(300);
        }
    }

    /** The built capture: {@code printAll} of the frame into an image of its size. EDT only. */
    private BufferedImage paintFrame() {
        BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            Color bg = frame.getBackground();
            g.setColor(bg != null ? bg : Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            frame.printAll(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** The root pane alone, printed at its place in an image of the frame's size. EDT only. */
    private BufferedImage paintRoot() {
        javax.swing.JRootPane rp = ((javax.swing.RootPaneContainer) frame).getRootPane();
        BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            Color bg = frame.getBackground();
            g.setColor(bg != null ? bg : Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.translate(rp.getX(), rp.getY());
            rp.printAll(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Pixel differences between the two captures, and where they lie. */
    final class Diff {
        int total;
        int strong;
        Rectangle box;
        Map<String, Integer> where = new TreeMap<String, Integer>();
        BufferedImage mask;
        String sizeNote = "";

        String summary() {
            List<Map.Entry<String, Integer>> l = new ArrayList<Map.Entry<String, Integer>>(where.entrySet());
            l.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, l.size()); i++) {
                sb.append(i == 0 ? "" : ", ").append(l.get(i).getKey()).append('=').append(l.get(i).getValue());
            }
            return "differing=" + total + " strong(>32)=" + strong + (box == null ? "" : " box=" + box.x + ","
                    + box.y + "," + box.width + "x" + box.height) + sizeNote + " where=[" + sb + "]";
        }
    }

    /** Compares the two images; with {@code only}, pixels outside that rectangle are neither compared nor counted. */
    private Diff diff(final BufferedImage a, final BufferedImage b, Rectangle only) throws Exception {
        final Diff d = new Diff();
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            d.sizeNote = " sizes " + a.getWidth() + "x" + a.getHeight() + " vs " + b.getWidth() + "x" + b.getHeight();
        }
        d.mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final List<int[]> points = new ArrayList<int[]>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y) & 0xffffff;
                int pb = b.getRGB(x, y) & 0xffffff;
                if (only != null && !only.contains(x, y)) {
                    d.mask.setRGB(x, y, 0x404040);
                    continue;
                }
                if (pa == pb) {
                    int g = ((pa >> 16 & 0xff) + (pa >> 8 & 0xff) + (pa & 0xff)) / 3;
                    g = 160 + g * 95 / 255;
                    d.mask.setRGB(x, y, g << 16 | g << 8 | g);
                    continue;
                }
                d.total++;
                int dr = Math.abs((pa >> 16 & 0xff) - (pb >> 16 & 0xff));
                int dg = Math.abs((pa >> 8 & 0xff) - (pb >> 8 & 0xff));
                int db = Math.abs((pa & 0xff) - (pb & 0xff));
                boolean strong = Math.max(dr, Math.max(dg, db)) > 32;
                if (strong) {
                    d.strong++;
                }
                d.mask.setRGB(x, y, strong ? 0xff0000 : 0xff9900);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                if (points.size() < 200_000) {
                    points.add(new int[] {x, y});
                }
            }
        }
        if (maxX >= 0) {
            d.box = new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
        }
        Edt.call(() -> {
            Insets in = frame.getInsets();
            int fw = frame.getWidth();
            int fh = frame.getHeight();
            for (int[] pt : points) {
                String k;
                if (pt[0] < in.left || pt[1] < in.top || pt[0] >= fw - in.right || pt[1] >= fh - in.bottom) {
                    k = "decorations";
                } else {
                    Component c = SwingUtilities.getDeepestComponentAt(frame, pt[0], pt[1]);
                    k = c == null ? "none" : (c.getName() != null ? c.getName() : c.getClass().getSimpleName());
                    if ("IconKey".equals(k)) {
                        try {
                            k = "IconKey:" + c.getClass().getMethod("actionId").invoke(c);
                        } catch (ReflectiveOperationException e) {
                            k = "IconKey";
                        }
                    }
                }
                Integer n = d.where.get(k);
                d.where.put(k, n == null ? 1 : n + 1);
            }
            return null;
        }, 20_000);
        return d;
    }
}
