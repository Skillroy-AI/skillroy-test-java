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

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

/** The three input paths of the spike, and the point type they share. */
final class Drivers {

    /** Whether the platform's popup trigger is the press (X11, macOS) or the release (Windows). */
    static final boolean POPUP_ON_PRESS = !System.getProperty("os.name", "").toLowerCase().contains("windows");

    private Drivers() {
    }

    /** A point in a window's coordinates (the window's own origin, decorations included) and on the screen. */
    static final class WPoint {
        final Window window;
        final int x;
        final int y;
        final int sx;
        final int sy;

        WPoint(Window window, int x, int y, int sx, int sy) {
            this.window = window;
            this.x = x;
            this.y = y;
            this.sx = sx;
            this.sy = sy;
        }

        /** The point of {@code c} at {@code (lx, ly)} in its own coordinates. EDT only. */
        static WPoint of(Component c, int lx, int ly) {
            Window w = c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c);
            Point p = SwingUtilities.convertPoint(c, lx, ly, w);
            Point o = w.getLocationOnScreen();
            return new WPoint(w, p.x, p.y, o.x + p.x, o.y + p.y);
        }

        WPoint toward(WPoint to, int step, int steps) {
            int nx = x + (to.x - x) * step / steps;
            int ny = y + (to.y - y) * step / steps;
            return new WPoint(window, nx, ny, sx + (nx - x), sy + (ny - y));
        }

        @Override
        public String toString() {
            return EventLog.name(window) + "(" + x + "," + y + ")screen(" + sx + "," + sy + ")";
        }
    }

    /** A pointer path addressed by window points: Robot or routed. */
    interface PointerDriver {
        String name();

        void move(WPoint p) throws Exception;

        void click(WPoint p, int button, int clickCount) throws Exception;

        void drag(WPoint from, WPoint to, int steps) throws Exception;

        void wheel(WPoint p, int notches) throws Exception;
    }

    /**
     * Guards every Robot action: the screen point must lie inside one of this JVM's showing windows and, in strict mode
     * (a person's desktop), this JVM must hold the focused window, so that its windows are in front.
     */
    static final class Safety {
        final boolean strict;
        volatile String refused;

        Safety(boolean strict) {
            this.strict = strict;
        }

        void check(final int sx, final int sy) throws Exception {
            String why = Edt.call(() -> {
                if (strict && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == null) {
                    return "no window of this JVM is focused";
                }
                for (Window w : Window.getWindows()) {
                    if (w.isShowing()) {
                        Rectangle r = new Rectangle(w.getLocationOnScreen(), w.getSize());
                        if (r.contains(sx, sy)) {
                            return null;
                        }
                    }
                }
                return "screen point " + sx + "," + sy + " is outside every window of this JVM";
            });
            if (why != null) {
                refused = why;
                throw new SafetyException(why);
            }
        }
    }

    static final class SafetyException extends Exception {
        private static final long serialVersionUID = 1L;

        SafetyException(String m) {
            super(m);
        }
    }

    /** {@code java.awt.Robot} on the harness thread (never the EDT). */
    static final class RobotDriver implements PointerDriver {
        final Robot robot;
        final Safety safety;

        RobotDriver(Robot robot, Safety safety) {
            this.robot = robot;
            this.safety = safety;
            robot.setAutoDelay(0);
        }

        @Override
        public String name() {
            return "ROBOT";
        }

        private int lastX = Integer.MIN_VALUE;
        private int lastY = Integer.MIN_VALUE;

        @Override
        public void move(WPoint p) throws Exception {
            safety.check(p.sx, p.sy);
            robot.mouseMove(p.sx, p.sy);
            lastX = p.sx;
            lastY = p.sy;
            Edt.sleep(30);
        }

        /** The pointer of a person clicking twice does not move between the clicks: no move when already there. */
        private void moveIfElsewhere(WPoint p) throws Exception {
            if (p.sx != lastX || p.sy != lastY) {
                move(p);
            }
        }

        @Override
        public void click(WPoint p, int button, int clickCount) throws Exception {
            moveIfElsewhere(p);
            int mask = InputEvent.getMaskForButton(button);
            safety.check(p.sx, p.sy);
            robot.mousePress(mask);
            Edt.sleep(25);
            robot.mouseRelease(mask);
            Edt.sleep(30);
        }

        @Override
        public void drag(WPoint from, WPoint to, int steps) throws Exception {
            move(from);
            int mask = InputEvent.BUTTON1_DOWN_MASK;
            safety.check(from.sx, from.sy);
            robot.mousePress(mask);
            try {
                Edt.sleep(30);
                for (int i = 1; i <= steps; i++) {
                    WPoint p = from.toward(to, i, steps);
                    safety.check(p.sx, p.sy);
                    robot.mouseMove(p.sx, p.sy);
                    lastX = p.sx;
                    lastY = p.sy;
                    Edt.sleep(20);
                }
            } finally {
                robot.mouseRelease(mask);
            }
            Edt.sleep(30);
        }

        @Override
        public void wheel(WPoint p, int notches) throws Exception {
            moveIfElsewhere(p);
            safety.check(p.sx, p.sy);
            robot.mouseWheel(notches);
            Edt.sleep(30);
        }
    }

    /**
     * ROUTED: mouse events whose source is the heavyweight window, in window coordinates, each posted with
     * {@code invokeLater} and dispatched there with {@code window.dispatchEvent} (non-posted), so that the window's
     * {@code LightweightDispatcher} picks the target, tracks enter and exit and keeps the press target for the release.
     * The sequence imitates the native one: a move to the point first (when the pointer is elsewhere), press with the
     * button's down mask, release, click with the same click count; the popup trigger on the press (X11, macOS) or the
     * release (Windows); drags with the button-1 down mask, a release with click count 0 and no click after them; one
     * wheel event per notch.
     */
    static final class RoutedDriver implements PointerDriver {
        private WPoint last;

        @Override
        public String name() {
            return "ROUTED";
        }

        @Override
        public void move(WPoint p) throws Exception {
            post(new MouseEvent(p.window, MouseEvent.MOUSE_MOVED, now(), 0, p.x, p.y, p.sx, p.sy, 0, false,
                    MouseEvent.NOBUTTON));
            last = p;
        }

        @Override
        public void click(WPoint p, int button, int clickCount) throws Exception {
            if (last == null || last.window != p.window || last.x != p.x || last.y != p.y) {
                move(p);
            }
            int mask = InputEvent.getMaskForButton(button);
            boolean popup = button == MouseEvent.BUTTON3;
            post(new MouseEvent(p.window, MouseEvent.MOUSE_PRESSED, now(), mask, p.x, p.y, p.sx, p.sy, clickCount,
                    popup && POPUP_ON_PRESS, button));
            post(new MouseEvent(p.window, MouseEvent.MOUSE_RELEASED, now(), 0, p.x, p.y, p.sx, p.sy, clickCount,
                    popup && !POPUP_ON_PRESS, button));
            post(new MouseEvent(p.window, MouseEvent.MOUSE_CLICKED, now(), 0, p.x, p.y, p.sx, p.sy, clickCount,
                    false, button));
        }

        @Override
        public void drag(WPoint from, WPoint to, int steps) throws Exception {
            move(from);
            post(new MouseEvent(from.window, MouseEvent.MOUSE_PRESSED, now(), InputEvent.BUTTON1_DOWN_MASK, from.x,
                    from.y, from.sx, from.sy, 1, false, MouseEvent.BUTTON1));
            WPoint p = from;
            for (int i = 1; i <= steps; i++) {
                p = from.toward(to, i, steps);
                post(new MouseEvent(p.window, MouseEvent.MOUSE_DRAGGED, now(), InputEvent.BUTTON1_DOWN_MASK, p.x, p.y,
                        p.sx, p.sy, 0, false, MouseEvent.NOBUTTON));
            }
            // X11 reports the release after a drag with click count 0 (the drag ends the click sequence)
            post(new MouseEvent(p.window, MouseEvent.MOUSE_RELEASED, now(), 0, p.x, p.y, p.sx, p.sy, 0, false,
                    MouseEvent.BUTTON1));
            last = p;
        }

        @Override
        public void wheel(WPoint p, int notches) throws Exception {
            if (last == null || last.window != p.window || last.x != p.x || last.y != p.y) {
                move(p);
            }
            int n = Math.abs(notches);
            int rot = notches < 0 ? -1 : 1;
            for (int i = 0; i < n; i++) {
                post(new MouseWheelEvent(p.window, MouseEvent.MOUSE_WHEEL, now(), 0, p.x, p.y, p.sx, p.sy, 0, false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rot));
            }
        }

        /** Posts one event to be dispatched non-posted on the EDT, then lets the EDT run what it queued. */
        private static void post(final MouseEvent e) throws Exception {
            EventQueue.invokeLater(() -> e.getComponent().dispatchEvent(e));
            Edt.drain();
            Edt.sleep(15);
        }
    }

    /**
     * DISPATCH: component-sourced events. {@link #builtClick} is exactly the built rung ({@code Clicks.mouseClick}:
     * press, release and click at the component's centre, left button, one task on the EDT); the other methods are
     * the same delivery extended to what the built rung cannot express (another button, a click count, a drag, the
     * wheel, a hover), each event at a point in the component's own coordinates.
     */
    static final class Dispatcher {

        static void builtClick(final Component c) throws Exception {
            EventQueue.invokeLater(() -> {
                int x = Math.max(0, c.getWidth() / 2);
                int y = Math.max(0, c.getHeight() / 2);
                long when = System.currentTimeMillis();
                c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON1_DOWN_MASK, x, y,
                        1, false, MouseEvent.BUTTON1));
                c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, when, 0, x, y, 1, false,
                        MouseEvent.BUTTON1));
                c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, when, 0, x, y, 1, false,
                        MouseEvent.BUTTON1));
            });
            Edt.drain();
        }

        static void click(Component c, Point p, int button, int clickCount) throws Exception {
            int mask = InputEvent.getMaskForButton(button);
            boolean popup = button == MouseEvent.BUTTON3;
            List<MouseEvent> es = new ArrayList<MouseEvent>();
            es.add(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now(), mask, p.x, p.y, clickCount,
                    popup && POPUP_ON_PRESS, button));
            es.add(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now(), 0, p.x, p.y, clickCount,
                    popup && !POPUP_ON_PRESS, button));
            es.add(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now(), 0, p.x, p.y, clickCount, false, button));
            send(es);
        }

        static void drag(Component c, Point from, Point to, int steps) throws Exception {
            List<MouseEvent> es = new ArrayList<MouseEvent>();
            es.add(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now(), InputEvent.BUTTON1_DOWN_MASK, from.x, from.y,
                    1, false, MouseEvent.BUTTON1));
            Point p = from;
            for (int i = 1; i <= steps; i++) {
                p = new Point(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps);
                es.add(new MouseEvent(c, MouseEvent.MOUSE_DRAGGED, now(), InputEvent.BUTTON1_DOWN_MASK, p.x, p.y, 0,
                        false, MouseEvent.NOBUTTON));
            }
            es.add(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now(), 0, p.x, p.y, 1, false, MouseEvent.BUTTON1));
            send(es);
        }

        static void wheel(Component c, Point p, int notches) throws Exception {
            List<MouseEvent> es = new ArrayList<MouseEvent>();
            for (int i = 0; i < Math.abs(notches); i++) {
                es.add(new MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, now(), 0, p.x, p.y, 0, false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, notches < 0 ? -1 : 1));
            }
            send(es);
        }

        static void hover(Component c, Point p) throws Exception {
            List<MouseEvent> es = new ArrayList<MouseEvent>();
            es.add(new MouseEvent(c, MouseEvent.MOUSE_ENTERED, now(), 0, p.x, p.y, 0, false, MouseEvent.NOBUTTON));
            es.add(new MouseEvent(c, MouseEvent.MOUSE_MOVED, now(), 0, p.x, p.y, 0, false, MouseEvent.NOBUTTON));
            send(es);
        }

        private static void send(List<MouseEvent> es) throws Exception {
            for (final MouseEvent e : es) {
                EventQueue.invokeLater(() -> e.getComponent().dispatchEvent(e));
                Edt.drain();
                Edt.sleep(15);
            }
        }
    }

    static long now() {
        return System.currentTimeMillis();
    }
}
