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

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.arr;
import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Robot path behind the {@link RobotFactory} seam on a real display (phase7-contracts.md sections 3.4, 13.1 item 1),
 * with a fake port that delivers like the OS (window-sourced events at the screen point) or drops: the capability probe,
 * the delivery probe before the pre-check, {@code NOT_DELIVERED}, {@code received elsewhere}, {@code PRESS} refused,
 * {@code requireFocus}, any covering window refusing the point, and a person's glass capture (masks before and after
 * the grab, {@code unstable}, the pixels outside the JVM's windows blanked). A second module instance with the agent
 * option {@code robot=true} serves the calls.
 */
class RobotPathDisplayTest extends DisplayTestBase {

    /** Simulates the OS: moves, presses and wheels become window-sourced events at the screen point. */
    static final class FakeOs implements RobotPort {
        final List<String> calls = new CopyOnWriteArrayList<String>();
        volatile boolean deliverMoves = true;
        volatile boolean deliverPresses = true;
        volatile Point pressOffset = new Point(0, 0);
        volatile Runnable duringGrab;
        private volatile Point at = new Point(900, 700);

        @Override
        public void mouseMove(int x, int y) {
            calls.add("move");
            at = new Point(x, y);
            if (deliverMoves) {
                post(MouseEvent.MOUSE_MOVED, at, 0, 0, MouseEvent.NOBUTTON);
            }
        }

        @Override
        public void mousePress(int buttons) {
            calls.add("press");
            if (deliverPresses) {
                post(MouseEvent.MOUSE_PRESSED, new Point(at.x + pressOffset.x, at.y + pressOffset.y),
                        InputEvent.BUTTON1_DOWN_MASK, 1, MouseEvent.BUTTON1);
            }
        }

        @Override
        public void mouseRelease(int buttons) {
            calls.add("release");
            if (deliverPresses) {
                Point p = new Point(at.x + pressOffset.x, at.y + pressOffset.y);
                post(MouseEvent.MOUSE_RELEASED, p, 0, 1, MouseEvent.BUTTON1);
                post(MouseEvent.MOUSE_CLICKED, p, 0, 1, MouseEvent.BUTTON1);
            }
        }

        @Override
        public void mouseWheel(int notches) {
            calls.add("wheel");
        }

        @Override
        public BufferedImage createScreenCapture(Rectangle screen) {
            calls.add("grab");
            Runnable hook = duringGrab;
            if (hook != null) {
                hook.run();
            }
            BufferedImage image = new BufferedImage(screen.width, screen.height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.ORANGE);
            g.fillRect(0, 0, screen.width, screen.height);
            g.dispose();
            return image;
        }

        @Override
        public Point pointer() {
            return new Point(at);
        }

        private static void post(final int id, final Point screen, final int mods, final int count, final int button) {
            SwingUtilities.invokeLater(() -> {
                Window target = null;
                for (Window w : Window.getWindows()) {
                    if (w.isShowing() && new Rectangle(w.getLocationOnScreen(), w.getSize()).contains(screen)) {
                        target = w;
                    }
                }
                if (target == null) {
                    return;
                }
                Point o = target.getLocationOnScreen();
                target.dispatchEvent(new MouseEvent(target, id, System.currentTimeMillis(), mods, screen.x - o.x,
                        screen.y - o.y, screen.x, screen.y, count, false, button));
            });
        }
    }

    private FakeContext rctx;
    private SwingModule robotModule;
    private FakeOs os;

    @BeforeEach
    void installTheRobotModule() throws Exception {
        rctx = new FakeContext();
        rctx.options.put("robot", "true");
        os = new FakeOs();
        robotModule = new SwingModule(() -> true, device -> os);
        robotModule.install(rctx);
        robotModule.onTargetReady(rctx);
        activate("the test frame is the focused window", ui.frame);
        settle();
    }

    @AfterEach
    void removeIt() throws Exception {
        robotModule.uninstall();
        rctx.shutdown();
        onEdt(() -> {
            for (Window w : Window.getWindows()) {
                if (w != ui.frame && w.isDisplayable()) {
                    w.dispose();
                }
            }
            return null;
        });
        settle();
    }

    private Map<String, Object> robot(Object... kv) throws Exception {
        return rctx.call("swing.pointer", map(kv), CALL_MS);
    }

    private String rref(Object c) {
        return rctx.handles.handleFor(c, ComponentView.KIND);
    }

    @Test
    void theCapabilityProbeConstructsTheRobotAndDeclaresBothIds() {
        for (String id : new String[] {SwingModule.CAP_POINTER_ROBOT, SwingModule.CAP_CAPTURE_ROBOT}) {
            Capability c = rctx.capabilities.get(id);
            assertEquals(CapabilityState.OK, c.state(), id);
            assertEquals("robot", c.strategy(), id);
        }
        assertTrue(os.calls.isEmpty(), "the probe moves nothing: " + os.calls);
    }

    @Test
    void aRobotClickFiresAfterTheDeliveryProbeAndRestoresThePointer() throws Exception {
        final int ok = ui.okClicks.get();
        Map<String, Object> r = robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT",
                "requireFocus", false);
        assertEquals(Boolean.TRUE, r.get("fired"), r.toString());
        assertEquals(rref(ui.ok), r.get("received"));
        assertEquals("ROBOT", r.get("via"));
        await("the Robot click acted", WAIT_MS, () -> ui.okClicks.get() == ok + 1);
        int press = os.calls.indexOf("press");
        assertTrue(press > 0 && os.calls.get(0).equals("move"), "the probe's move comes first: " + os.calls);
        assertEquals("release", os.calls.get(press + 1), "the release follows the press at once");
        assertEquals("move", os.calls.get(os.calls.size() - 1), "the pointer is restored last");
    }

    @Test
    void nothingDeliveredDegradesTheCapabilityAndMovesNoButton() throws Exception {
        os.deliverMoves = false;
        RpcException e = expectError(ErrorCodes.CAPABILITY_UNAVAILABLE, () -> robot("by", map("id", "okButton"),
                "action", "CLICK", "via", "ROBOT", "requireFocus", false));
        assertEquals(RobotPath.NOT_DELIVERED_REASON, e.error().data().get("reason"));
        assertEquals(CapabilityState.DEGRADED, rctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state());
        assertFalse(os.calls.contains("press"), "no press without delivery: " + os.calls);
        os.deliverMoves = true;
        Map<String, Object> again = robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT",
                "requireFocus", false);
        assertEquals(Boolean.TRUE, again.get("fired"));
        assertEquals(CapabilityState.OK, rctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state(),
                "a passing reprobe restores the capability");
    }

    @Test
    void aDroppedPressIsNotDeliveredAndAPressElsewhereIsReported() throws Exception {
        os.deliverPresses = false;
        Map<String, Object> dropped = robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT",
                "requireFocus", false);
        assertEquals(Boolean.FALSE, dropped.get("fired"));
        assertEquals("NOT_DELIVERED", dropped.get("reason"));
        os.deliverPresses = true;
        os.calls.clear();
        Rectangle okRect = onEdt(() -> SwingUtilities.convertRectangle(ui.ok.getParent(), ui.ok.getBounds(), ui.frame));
        Rectangle cancelRect = onEdt(() -> SwingUtilities.convertRectangle(ui.cancel.getParent(),
                ui.cancel.getBounds(), ui.frame));
        os.pressOffset = new Point((int) (cancelRect.getCenterX() - okRect.getCenterX()),
                (int) (cancelRect.getCenterY() - okRect.getCenterY()));
        Map<String, Object> elsewhere = robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT",
                "requireFocus", false);
        assertEquals(Boolean.FALSE, elsewhere.get("fired"));
        assertEquals("received elsewhere", elsewhere.get("reason"));
        assertEquals("move", os.calls.get(0), "a reprobe ran after the press that was not received");
    }

    @Test
    void pressAndReleaseAreRefusedThroughTheRobot() {
        RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> robot("by", map("id", "okButton"), "action",
                "PRESS", "via", "ROBOT"));
        assertTrue(e.getMessage().contains("never left held"));
        assertTrue(os.calls.isEmpty());
    }

    @Test
    void requireFocusRefusesAJvmWithoutAFocusedWindow() throws Exception {
        final JFrame[] thief = new JFrame[1];
        onEdt(() -> {
            thief[0] = new JFrame("Takes the focus away");
            thief[0].setBounds(700, 500, 150, 80);
            thief[0].setVisible(true);
            return null;
        });
        awaitFocusedWindow("the thief is focused", thief[0]);
        onEdt(() -> {
            thief[0].dispose();
            return null;
        });
        await("no window of the JVM is focused", WAIT_MS, () -> java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getFocusedWindow() == null);
        RpcException e = expectError(ErrorCodes.INPUT_REJECTED, () -> robot("by", map("id", "okButton"), "action",
                "CLICK", "via", "ROBOT"));
        assertEquals("not focused", e.error().data().get("reason"));
        assertFalse(os.calls.contains("press"));
        activate("the test frame is focused again", ui.frame);
        assertEquals(Boolean.TRUE, robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT")
                .get("fired"));
    }

    @Test
    void anyCoveringWindowRefusesTheRobotWhileTheRoutedPathReportsIt() throws Exception {
        // the delivery probe passes first (it would see the covering window take the move, as the OS's would)
        assertEquals(Boolean.TRUE, robot("by", map("id", "okButton"), "action", "MOVE", "via", "ROBOT",
                "requireFocus", false).get("fired"));
        Point okScreen = onEdt(() -> {
            Point p = new Point(ui.ok.getWidth() / 2, ui.ok.getHeight() / 2);
            SwingUtilities.convertPointToScreen(p, ui.ok);
            return p;
        });
        onEdt(() -> {
            JFrame cover = new JFrame("Unrelated frame on top");
            cover.add(new JButton("Cover"));
            cover.setBounds(okScreen.x - 20, okScreen.y - 20, 60, 60);
            cover.setFocusableWindowState(false);
            cover.setVisible(true);
            return null;
        });
        settle();
        RpcException e = expectError(ErrorCodes.INPUT_REJECTED, () -> robot("by", map("id", "okButton"), "action",
                "CLICK", "via", "ROBOT", "requireFocus", false));
        assertEquals("covered", e.error().data().get("reason"));
        Map<String, Object> routed = robot("by", map("id", "okButton"), "action", "MOVE");
        List<Object> covering = arr(obj(routed.get("hit")).get("covering"));
        assertEquals(1, covering.size());
        assertEquals(Boolean.FALSE, obj(covering.get(0)).get("ranked"), "reported, not refused, when routed");
    }

    @Test
    void aGlassCaptureIsMaskedAndBlankedOutsideTheWindows() throws Exception {
        Map<String, Object> shot = rctx.call("swing.capture", map("target", rref(ui.frame), "mode", "ROBOT"),
                CALL_MS);
        assertEquals("ROBOT", shot.get("mode"));
        assertEquals("SCREEN", shot.get("space"));
        assertEquals(Boolean.TRUE, shot.get("glass"));
        assertEquals(RobotPath.GLASS_NOTE, shot.get("glassNote"));
        assertEquals((long) onEdt(() -> ui.frame.getWidth()), shot.get("width"), "the window with its decorations");
        List<Object> redacted = arr(shot.get("redacted"));
        assertEquals(rref(ui.pin), obj(redacted.get(0)).get("ref"));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String)
                shot.get("base64"))));
        Map<String, Object> pin = obj(obj(redacted.get(0)).get("rect"));
        assertEquals(0xFF808080, img.getRGB((int) ((Long) pin.get("x") + 2), (int) ((Long) pin.get("y") + 2)));
        assertEquals(Color.ORANGE.getRGB(), img.getRGB(2, 2), "the glass inside the window");

        onEdt(() -> {
            JDialog far = new JDialog(ui.frame, "Far away", false);
            far.setBounds(900, 600, 80, 60);
            far.setVisible(true);
            return null;
        });
        settle();
        Map<String, Object> screen = rctx.call("swing.capture", map("scope", "SCREEN", "mode", "ROBOT"), CALL_MS);
        BufferedImage all = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String)
                screen.get("base64"))));
        assertEquals(0xFF808080, all.getRGB(all.getWidth() - 100, all.getHeight() - 100),
                "the desktop between the windows is grey");
        expectError(ErrorCodes.INVALID_PARAMS, () -> rctx.call("swing.capture", map("mode", "ROBOT",
                "annotateRefs", true), CALL_MS));
    }

    @Test
    void aWindowOpeningDuringBothGrabsIsUnstable() throws Exception {
        os.duringGrab = () -> {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    JWindow flash = new JWindow(ui.frame);
                    flash.setBounds(10, 10, 20, 20);
                    flash.setVisible(true);
                    flash.dispose();
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Map<String, Object> shot = rctx.call("swing.capture", map("target", rref(ui.frame), "mode", "ROBOT"),
                CALL_MS);
        assertEquals(Boolean.TRUE, shot.get("unstable"), shot.keySet().toString());
        assertEquals(2, os.calls.stream().filter("grab"::equals).count(), "taken once more, then reported");
    }
}
