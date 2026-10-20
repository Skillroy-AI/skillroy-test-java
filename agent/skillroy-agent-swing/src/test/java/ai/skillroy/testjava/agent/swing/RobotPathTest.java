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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Robot path behind the {@link RobotFactory} seam without a display (phase7-contracts.md section 13.1 item 1): the
 * native sequence's order (a press always paired with its release in one task, every pressed button released in a
 * {@code finally} before the pointer is restored, also when the task fails between press and release), {@code PRESS}
 * and {@code RELEASE} refused before anything moves, the capability probe in a headless JVM, and the glass blanked
 * outside the JVM's windows. The delivery probe, {@code requireFocus}, the covering rule and the observed press need
 * real windows and run in {@code RobotPathDisplayTest}.
 */
class RobotPathTest {

    private final FakeContext ctx = new FakeContext();

    @AfterEach
    void tearDown() {
        ctx.shutdown();
    }

    /** Records what the path asks of the OS; may fail on the n-th move. */
    static class FakePort implements RobotPort {
        final List<String> calls = new ArrayList<String>();
        int failOnMove = -1;
        private int moves;

        @Override
        public void mouseMove(int x, int y) {
            moves++;
            if (moves == failOnMove) {
                throw new IllegalStateException("interrupted mid-drag");
            }
            calls.add("move " + x + "," + y);
        }

        @Override
        public void mousePress(int buttons) {
            calls.add("press " + buttons);
        }

        @Override
        public void mouseRelease(int buttons) {
            calls.add("release " + buttons);
        }

        @Override
        public void mouseWheel(int notches) {
            calls.add("wheel " + notches);
        }

        @Override
        public BufferedImage createScreenCapture(Rectangle screen) {
            calls.add("grab");
            return new BufferedImage(screen.width, screen.height, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public Point pointer() {
            return new Point(500, 400);
        }
    }

    private static Pointer.Request request(Object... kv) {
        Map<String, Object> m = map(kv);
        m.put("by", map("id", "x"));
        m.put("via", "ROBOT");
        return Pointer.parse(JsonView.of(m));
    }

    private static final int B1 = InputEvent.BUTTON1_DOWN_MASK;

    @Test
    void aClickIsAPairedPressAndReleaseThenThePointerIsRestored() {
        FakePort port = new FakePort();
        RobotPath.act(port, request("action", "CLICK"), new Point(10, 20), null);
        assertEquals(Arrays.asList("move 10,20", "press " + B1, "release " + B1, "move 500,400"), port.calls);
    }

    @Test
    void aDoubleClickIsTwoPairs() {
        FakePort port = new FakePort();
        RobotPath.act(port, request("action", "DOUBLE_CLICK"), new Point(10, 20), null);
        assertEquals(Arrays.asList("move 10,20", "press " + B1, "release " + B1, "press " + B1, "release " + B1,
                "move 500,400"), port.calls);
    }

    @Test
    void aFailureBetweenPressAndReleaseStillReleasesBeforeThePointerIsRestored() {
        FakePort port = new FakePort();
        port.failOnMove = 3; // the move to the point, the first drag sample, then a failure
        assertThrows(IllegalStateException.class, () -> RobotPath.act(port,
                request("action", "DRAG", "to", map("fx", 1.0, "fy", 1.0), "steps", 4L), new Point(10, 20),
                new Point(50, 20)));
        assertEquals(Arrays.asList("move 10,20", "press " + B1, "move 20,20", "release " + B1, "move 500,400"),
                port.calls, "released in the finally, and only then restored");
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-F5): a press that throws after the native press took effect is still released in
     * the {@code finally} before the pointer is restored (a spurious release of a button never pressed is harmless).
     */
    @Test
    void aPressThatThrowsIsStillReleasedBeforeThePointerIsRestored() {
        FakePort port = new FakePort() {
            @Override
            public void mousePress(int buttons) {
                super.mousePress(buttons);
                throw new IllegalStateException("the toolkit failed after the press");
            }
        };
        assertThrows(IllegalStateException.class, () -> RobotPath.act(port, request("action", "CLICK"),
                new Point(10, 20), null));
        assertEquals(Arrays.asList("move 10,20", "press " + B1, "release " + B1, "move 500,400"), port.calls);
        FakePort dragging = new FakePort() {
            @Override
            public void mousePress(int buttons) {
                super.mousePress(buttons);
                throw new IllegalStateException("the toolkit failed after the press");
            }
        };
        assertThrows(IllegalStateException.class, () -> RobotPath.act(dragging,
                request("action", "DRAG", "to", map("fx", 1.0, "fy", 1.0)), new Point(10, 20), new Point(50, 20)));
        assertEquals(Arrays.asList("move 10,20", "press " + B1, "release " + B1, "move 500,400"), dragging.calls);
    }

    /**
     * Phase 8 security fix (S155, FA): a drag's release is asked again after the moves; allowed, it lands on the end
     * point; refused, or failing, the pointer moves back to the press point and the release lands there, before the
     * pointer is restored.
     */
    @Test
    void aDragsReleaseIsAskedAgainAndGoesToThePressPointUnlessAllowed() {
        Pointer.Request drag = request("action", "DRAG", "to", map("fx", 1.0, "fy", 1.0), "steps", 2L);
        FakePort allowed = new FakePort();
        RobotPath.act(allowed, drag, new Point(10, 20), new Point(50, 20), () -> true);
        assertEquals(Arrays.asList("move 10,20", "press " + B1, "move 30,20", "move 50,20", "release " + B1,
                "move 500,400"), allowed.calls);
        FakePort refused = new FakePort();
        RobotPath.act(refused, drag, new Point(10, 20), new Point(50, 20), () -> false);
        assertEquals(Arrays.asList("move 10,20", "press " + B1, "move 30,20", "move 50,20", "move 10,20",
                "release " + B1, "move 500,400"), refused.calls, "moved back to the press point, released there");
        FakePort failing = new FakePort();
        assertThrows(IllegalStateException.class, () -> RobotPath.act(failing, drag, new Point(10, 20),
                new Point(50, 20), () -> {
                    throw new IllegalStateException("the read failed");
                }));
        assertEquals(Arrays.asList("move 10,20", "press " + B1, "move 30,20", "move 50,20", "move 10,20",
                "release " + B1, "move 500,400"), failing.calls, "a failed question never releases on the end");
        FakePort click = new FakePort();
        RobotPath.act(click, request("action", "CLICK"), new Point(10, 20), null, () -> false);
        assertEquals(Arrays.asList("move 10,20", "press " + B1, "release " + B1, "move 500,400"), click.calls,
                "only a drag asks");
    }

    @Test
    void aWheelTurnsOneNotchAtATime() {
        FakePort port = new FakePort();
        RobotPath.act(port, request("action", "WHEEL", "wheel", map("notches", 2L)), new Point(1, 2), null);
        assertEquals(Arrays.asList("move 1,2", "wheel 1", "wheel 1", "move 500,400"), port.calls);
    }

    @Test
    void pressAndReleaseAreRefusedBeforeAnythingMoves() throws Exception {
        final FakePort port = new FakePort();
        RobotPath robot = new RobotPath(ctx, new Windows(), new Pointer(ctx, new Windows(), new RecentCaptures()),
                new RecentCaptures(), device -> port);
        for (String action : new String[] {"PRESS", "RELEASE"}) {
            RpcException e = expectError(ErrorCodes.INVALID_PARAMS,
                    () -> robot.pointer(invocation(ctx), request("action", action)));
            assertTrue(e.getMessage().contains("never left held"), e.getMessage());
        }
        assertTrue(port.calls.isEmpty());
    }

    @Test
    void theCapabilityProbeInAHeadlessJvmSaysHeadless() {
        RobotPath robot = new RobotPath(ctx, new Windows(), new Pointer(ctx, new Windows(), new RecentCaptures()),
                new RecentCaptures(), device -> new FakePort());
        robot.declare(true);
        assertEquals(CapabilityState.UNAVAILABLE, ctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state());
        assertEquals("headless", ctx.capabilities.get(SwingModule.CAP_CAPTURE_ROBOT).reason());
        RobotPath failing = new RobotPath(ctx, new Windows(), new Pointer(ctx, new Windows(), new RecentCaptures()),
                new RecentCaptures(), device -> {
                    throw new java.awt.AWTException("no XTEST");
                });
        failing.declare(false);
        assertEquals(CapabilityState.UNAVAILABLE, ctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state());
        assertTrue(ctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).reason().startsWith("robot: "),
                ctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).reason());
    }

    @Test
    void theGlassIsBlankedOutsideTheWindowsOfTheJvm() {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                image.setRGB(x, y, 0xFF102030);
            }
        }
        RobotPath.blankOutside(image, new Rectangle(100, 100, 10, 10), Arrays.asList(new Rectangle(102, 102, 5, 5)));
        assertEquals(0xFF808080, image.getRGB(0, 0));
        assertEquals(0xFF808080, image.getRGB(9, 9));
        assertEquals(0xFF102030, image.getRGB(3, 3));
        assertEquals(0xFF102030, image.getRGB(6, 6));
        assertEquals(0xFF808080, image.getRGB(7, 7));
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-S3): the outside of the windows is blanked with filled complement rectangles,
     * pixel for pixel what the per-pixel rule says (overlapping windows, a window partly outside the grab, none).
     */
    @Test
    void theOutsideIsBlankedPixelForPixelAsThePerPixelRuleSays() {
        Rectangle screen = new Rectangle(100, 50, 64, 48);
        List<List<Rectangle>> cases = Arrays.asList(
                Arrays.<Rectangle>asList(),
                Arrays.asList(new Rectangle(100, 50, 64, 48)),
                Arrays.asList(new Rectangle(90, 40, 30, 30), new Rectangle(110, 60, 30, 20)),
                Arrays.asList(new Rectangle(120, 55, 10, 40), new Rectangle(105, 70, 50, 5),
                        new Rectangle(150, 30, 40, 100)),
                Arrays.asList(new Rectangle(0, 0, 10, 10), new Rectangle(163, 97, 5, 5)));
        for (List<Rectangle> windowRects : cases) {
            BufferedImage image = new BufferedImage(screen.width, screen.height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < screen.height; y++) {
                for (int x = 0; x < screen.width; x++) {
                    image.setRGB(x, y, 0xFF102030);
                }
            }
            RobotPath.blankOutside(image, screen, windowRects);
            for (int y = 0; y < screen.height; y++) {
                for (int x = 0; x < screen.width; x++) {
                    boolean inside = false;
                    for (Rectangle r : windowRects) {
                        inside |= r.contains(screen.x + x, screen.y + y);
                    }
                    assertEquals(inside ? 0xFF102030 : 0xFF808080, image.getRGB(x, y),
                            "pixel " + x + "," + y + " of " + windowRects);
                }
            }
        }
    }

    // ------------------------------------------------------------------- Phase 7 security fix (SG, 2026-10-11)

    private static int robotPathListeners() {
        int n = 0;
        for (java.awt.event.AWTEventListener l : java.awt.Toolkit.getDefaultToolkit().getAWTEventListeners()) {
            java.awt.event.AWTEventListener inner = l instanceof java.awt.event.AWTEventListenerProxy
                    ? ((java.awt.event.AWTEventListenerProxy) l).getListener() : l;
            if (inner.getClass().getName().startsWith(RobotPath.class.getName())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Security fix S121 (L2-F3; the L2 reviewer's probe 1 inverted): a delivery probe whose Robot port cannot be had
     * (the factory fails for the device, or the JVM has no screen) leaves no listener on the toolkit: the port is
     * obtained before the listener is added. Reached by reflection, so that the same test runs against the code
     * before the fix, where it fails (the listener count grows by one per failed probe).
     */
    @Test
    void aProbeWhosePortCannotBeHadLeavesNoListener() throws Exception {
        RobotPath robot = new RobotPath(ctx, new Windows(), new Pointer(ctx, new Windows(), new RecentCaptures()),
                new RecentCaptures(), device -> {
                    throw new java.awt.AWTException("the Robot cannot be constructed for this device");
                });
        java.lang.reflect.Method probe = RobotPath.class.getDeclaredMethod("probe", Invocation.class,
                java.awt.Window.class, Point.class);
        probe.setAccessible(true);
        int before = robotPathListeners();
        for (int i = 0; i < 2; i++) {
            try {
                probe.invoke(robot, invocation(ctx), null, new Point(10, 10));
                throw new AssertionError("the probe should fail without a port");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                // the factory's AWTException, or a headless JVM's HeadlessException
            }
        }
        assertEquals(before, robotPathListeners(), "no listener leaked by a probe without a port");
    }

    /**
     * Security fix S124 (L2-S3): {@code modifiers} with {@code via: ROBOT} are refused ({@code INVALID_PARAMS},
     * {@code data.param: "modifiers"}) before anything moves: the Robot path holds no key, so a modified click would be
     * pressed plain and reported as the modified one.
     */
    @Test
    void modifiersAreRefusedWithTheRobotBeforeAnythingMoves() throws Exception {
        final FakePort port = new FakePort();
        RobotPath robot = new RobotPath(ctx, new Windows(), new Pointer(ctx, new Windows(), new RecentCaptures()),
                new RecentCaptures(), device -> port);
        for (String mod : new String[] {"SHIFT", "CTRL", "ALT", "META"}) {
            RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> robot.pointer(invocation(ctx),
                    request("action", "CLICK", "modifiers", Arrays.<Object>asList(mod))));
            assertEquals("modifiers", e.error().data().get("param"), mod);
        }
        assertTrue(port.calls.isEmpty(), "nothing moved: " + port.calls);
        RobotPath.refuseModifiers(request("action", "CLICK", "modifiers", Arrays.<Object>asList()));
    }

    /**
     * Security fix S126 (L2-S5): what a glass capture's grab allocates at the screen's UI scale, the device-space
     * pixels beside the user-space image ({@code (s² + 1) 4 w h}), and the user-space raster alone at scale 1.
     */
    @Test
    void theGrabsBytesFollowTheScreensScale() {
        Rectangle screen = new Rectangle(0, 0, 400, 300);
        assertEquals(4L * 400 * 300, RobotPath.grabBytes(screen, 1.0, 1.0));
        assertEquals(4L * 400 * 300 * 5, RobotPath.grabBytes(screen, 2.0, 2.0));
        assertEquals(4L * 400 * 300 * 10, RobotPath.grabBytes(screen, 3.0, 3.0));
        assertEquals(4L * 400 * 300 + 4L * 600 * 450, RobotPath.grabBytes(screen, 1.5, 1.5));
    }

    /**
     * Security fix S126 (L2-S5): a person's glass capture on a screen at UI scale 3 is refused by section 2.4's heap
     * bound when the device-space grab would exceed an eighth of the headroom, although the user-space raster would
     * not (the window list and the screens through their seams; refused before any port is looked up).
     */
    @Test
    void aGlassCaptureAtAUiScaleCountsTheDevicePixels() throws Exception {
        javax.swing.JPanel frame = CaptureTest.panel(CaptureTest.GREEN, 0, 0, 400, 300);
        Windows windows = new Windows();
        windows.source(() -> java.util.Collections.singletonList(new Windows.Layer(frame, frame,
                new Rectangle(100, 100, 400, 300), new Point(0, 0), true)));
        final FakePort port = new FakePort();
        RobotPath robot = new RobotPath(ctx, windows, new Pointer(ctx, windows, new RecentCaptures()),
                new RecentCaptures(), device -> port);
        Capture.Request req = new Capture.Request();
        req.scope = Capture.Scope.SCREEN;
        try {
            RobotPath.screens = new RobotPath.Screens() {
                @Override
                public List<Rectangle> bounds() {
                    return Arrays.asList(new Rectangle(0, 0, 4000, 3000));
                }

                @Override
                public double[] scaleAt(double x, double y) {
                    return new double[] {3.0, 3.0};
                }
            };
            Capture.heap = () -> 4L << 20; // an eighth is 512 KB: the 469 KB raster fits, the 4.6 MB grab does not
            RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> robot.capture(invocation(ctx), null, req));
            assertEquals(5L, e.error().data().get("neededMb"), String.valueOf(e.error().data()));
            assertTrue(port.calls.isEmpty(), "nothing grabbed: " + port.calls);
        } finally {
            RobotPath.screens = RobotPath.TOOLKIT_SCREENS;
            Capture.heap = Capture.RUNTIME_HEAP;
        }
    }

    static Invocation invocation(final AgentContext ctx) {
        return new Invocation() {
            @Override
            public JsonView args() {
                return JsonView.of(null);
            }

            @Override
            public String correlationId() {
                return null;
            }

            @Override
            public String traceparent() {
                return null;
            }

            @Override
            public AgentContext context() {
                return ctx;
            }

            @Override
            public long remainingMs() {
                return 10000;
            }
        };
    }
}
