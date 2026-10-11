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
import java.awt.Frame;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Robot path behind the {@link RobotFactory} seam on a real display (phase7-contracts.md sections 3.4, 13.1 item 1),
 * with a fake port that delivers like the OS (window-sourced events at the screen point) or drops: the capability probe,
 * the delivery probe before the pre-check, {@code NOT_DELIVERED}, {@code received elsewhere}, {@code PRESS} refused,
 * {@code requireFocus}, any covering window refusing the point, and a person's glass capture (masks before and after
 * the grab, {@code unstable}, the pixels outside the JVM's windows blanked). The fix wave of the Phase 7 review (FA)
 * adds: the probe's own read refusing an outside point, an unfocused JVM and a covered point before anything moves, the
 * probe skipped for a modal-blocked window, the pointer restored after a passing probe, a drag's end point bounded and
 * checked for covering, the ids' reason before the capability probe has run, the masks of a dialog over the captured
 * frame and the glass capture's heap bound. A second module instance with the agent option {@code robot=true} serves
 * the calls.
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
                if (target == null || blockedByModal(target)) {
                    return; // nothing there, or AWT drops input into a window a modal dialog blocks (BS9 "modal")
                }
                Point o = target.getLocationOnScreen();
                target.dispatchEvent(new MouseEvent(target, id, System.currentTimeMillis(), mods, screen.x - o.x,
                        screen.y - o.y, screen.x, screen.y, count, false, button));
            });
        }
    }

    /** Whether a showing modal dialog other than the window (and not its owner) blocks it: AWT's filter, roughly. */
    static boolean blockedByModal(Window w) {
        for (Window other : Window.getWindows()) {
            if (other != w && other instanceof java.awt.Dialog && ((java.awt.Dialog) other).isModal()
                    && other.isShowing()) {
                boolean owned = false;
                for (Window o = w.getOwner(); o != null; o = o.getOwner()) {
                    owned |= o == other;
                }
                if (!owned) {
                    return true;
                }
            }
        }
        return false;
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
        // fix wave (Phase 7 review, FA, L2-F6): restored to where the person left it, not to the probed point
        assertEquals(new Point(900, 700), os.pointer(), "the pointer is back where it was before the probe");
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
        loseTheFocus();
        RpcException e = expectError(ErrorCodes.INPUT_REJECTED, () -> robot("by", map("id", "okButton"), "action",
                "CLICK", "via", "ROBOT"));
        assertEquals("not focused", e.error().data().get("reason"));
        // fix wave (Phase 7 review, FA, L2-F4): refused in the delivery probe's own read, so nothing moves at all
        assertTrue(os.calls.isEmpty(), "nothing moves, not even the delivery probe: " + os.calls);
        assertEquals(CapabilityState.OK, rctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state());
        activate("the test frame is focused again", ui.frame);
        assertEquals(Boolean.TRUE, robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT")
                .get("fired"));
    }

    /**
     * Leaves the JVM without a focused window: a second frame takes the focus and is disposed, and nothing gives the
     * focus back under the WM-less Xvfb. Once (the amd64 Java 8 targets leg, under emulation, 2026-10-11) the test frame
     * was focused again within the wait, most likely a late activation of its own landing after the thief's (A39's
     * first way); the cycle runs up to three times, the last with the whole wait.
     */
    private static void loseTheFocus() throws Exception {
        for (int attempt = 1; ; attempt++) {
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
            try {
                await("no window of the JVM is focused (attempt " + attempt + ")", attempt < 3 ? WAIT_MS / 3 : WAIT_MS,
                        () -> java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == null);
                return;
            } catch (AssertionError e) {
                if (attempt == 3) {
                    throw e;
                }
            }
        }
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

    /**
     * Gap 101: a dialog over its own owner frame takes a Robot click while it is the active window (the owner lies
     * below the windows it owns, and is still reported under {@code covering}); once the frame is the active window
     * again the dialog's point is refused as covered, as any other covering window refuses it.
     */
    @Test
    void aDialogOverItsOwnOwnerTakesTheRobotWhileItIsActive() throws Exception {
        final JDialog[] dialog = new JDialog[1];
        onEdt(() -> {
            dialog[0] = new JDialog(ui.frame, "Prompt", false);
            JButton yes = new JButton("Yes");
            yes.setName("promptYes");
            dialog[0].getContentPane().add(yes);
            dialog[0].setBounds(200, 150, 200, 80);
            dialog[0].setVisible(true);
            return null;
        });
        activate("the prompt is the focused window", dialog[0]);
        settle();
        Map<String, Object> routed = robot("by", map("id", "promptYes"), "action", "MOVE");
        List<Object> covering = arr(obj(routed.get("hit")).get("covering"));
        assertEquals(1, covering.size(), "the owner lies under the point and is reported");
        assertEquals(rref(ui.frame), obj(covering.get(0)).get("ref"));
        assertEquals(Boolean.TRUE, robot("by", map("id", "promptYes"), "action", "CLICK", "via", "ROBOT")
                .get("fired"));
        activate("the frame is the focused window again", ui.frame);
        settle();
        RpcException e = expectError(ErrorCodes.INPUT_REJECTED, () -> robot("by", map("id", "promptYes"), "action",
                "CLICK", "via", "ROBOT"));
        assertEquals("covered", e.error().data().get("reason"));
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-F4): with a delivery probe due, a point outside the window is {@code NOT_FOUND
     * outside} from the probe's own read; the real pointer never moves and the capability stays {@code OK}.
     */
    @Test
    void aPointOutsideTheWindowIsRefusedBeforeTheProbeMovesAnything() throws Exception {
        RpcException e = expectError(ErrorCodes.NOT_FOUND, () -> robot("point", map("x", 700L, "y", 10L), "space",
                map("window", rref(ui.frame)), "action", "CLICK", "via", "ROBOT", "requireFocus", false));
        assertEquals("outside", e.error().data().get("reason"));
        assertTrue(os.calls.isEmpty(), "nothing moved: " + os.calls);
        assertEquals(CapabilityState.OK, rctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state());
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-F4): with a delivery probe due, a covering window of the JVM refuses the point
     * ({@code covered}) before the probe moves the pointer onto it; the capability stays {@code OK}.
     */
    @Test
    void aCoveredPointIsRefusedBeforeTheProbeMovesAnything() throws Exception {
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
        assertTrue(os.calls.isEmpty(), "nothing moved: " + os.calls);
        assertEquals(CapabilityState.OK, rctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state());
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-F4 as restated by its sceptic): a modal dialog blocks the target window, so AWT
     * would drop the probe's move as it drops the press; the probe is skipped, the action reports that nothing arrived
     * ({@code NOT_DELIVERED}) and the capability is not degraded.
     */
    @Test
    void aModalBlockedWindowSkipsTheProbeAndReportsNothingDelivered() throws Exception {
        final JDialog[] modal = new JDialog[1];
        onEdt(() -> {
            modal[0] = new JDialog(ui.frame, "Blocks the frame", true);
            modal[0].setBounds(700, 500, 200, 100);
            return null;
        });
        SwingUtilities.invokeLater(() -> modal[0].setVisible(true));
        await("the modal dialog is showing", WAIT_MS, () -> modal[0].isShowing());
        settle();
        Map<String, Object> r = robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT",
                "requireFocus", false);
        assertEquals(Boolean.FALSE, r.get("fired"), r.toString());
        assertEquals(RobotPath.NOT_DELIVERED, r.get("reason"));
        assertEquals(CapabilityState.OK, rctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state(),
                "a blocked window is not a delivery failure");
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-S2): a Robot {@code DRAG}'s end point is bounded by the window and checked for
     * covering in the same read as its start; nothing moves.
     */
    @Test
    void aRobotDragsEndPointOutsideTheWindowOrCoveredIsRefused() throws Exception {
        RpcException outside = expectError(ErrorCodes.NOT_FOUND, () -> robot("point", map("x", 20L, "y", 20L),
                "space", map("window", rref(ui.frame)), "to", map("x", 900L, "y", 20L), "action", "DRAG", "via",
                "ROBOT", "requireFocus", false));
        assertEquals("outside", outside.error().data().get("reason"));
        assertEquals("to", outside.error().data().get("param"));
        assertFalse(os.calls.contains("press"), "no press: " + os.calls);
        onEdt(() -> {
            JFrame cover = new JFrame("Covers the drop point");
            cover.setBounds(500, 380, 60, 60);
            cover.setFocusableWindowState(false);
            cover.setVisible(true);
            return null;
        });
        settle();
        os.calls.clear();
        RpcException covered = expectError(ErrorCodes.INPUT_REJECTED, () -> robot("point", map("x", 20L, "y", 20L),
                "space", map("window", rref(ui.frame)), "to", map("x", 530L, "y", 410L), "action", "DRAG", "via",
                "ROBOT", "requireFocus", false));
        assertEquals("covered", covered.error().data().get("reason"));
        assertFalse(os.calls.contains("press"), "no press: " + os.calls);
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-F8): for an agent attached to a running application, both Robot ids read
     * {@code UNAVAILABLE("robot: probe not run")} from the moment AWT is confirmed until the worker's probe declares
     * them; a refused post leaves that reason, and a Robot call is refused rather than constructing the Robot on the
     * call thread.
     */
    @Test
    void aRefusedProbePostLeavesBothIdsUnavailableWithTheReason() throws Exception {
        FakeContext c = new FakeContext();
        c.options.put("robot", "true");
        c.threads.refuseWorkerPosts = true;
        SwingModule m = new SwingModule(() -> true, device -> os);
        try {
            m.install(c);
            m.onTargetReady(c);
            for (String id : new String[] {SwingModule.CAP_POINTER_ROBOT, SwingModule.CAP_CAPTURE_ROBOT}) {
                Capability cap = c.capabilities.get(id);
                assertTrue(cap != null, id + " is set before the probe is posted");
                assertEquals(CapabilityState.UNAVAILABLE, cap.state(), id);
                assertEquals("robot: probe not run", cap.reason(), id);
            }
            expectError(ErrorCodes.CAPABILITY_UNAVAILABLE, () -> c.call("swing.pointer", map("by",
                    map("id", "okButton"), "action", "CLICK", "via", "ROBOT", "requireFocus", false), CALL_MS));
            assertTrue(os.calls.isEmpty(), "nothing constructed or moved: " + os.calls);
        } finally {
            m.uninstall();
            c.shutdown();
        }
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-S1): a glass capture of the frame blanks the masks of every JVM window whose
     * rectangle meets the captured one, here a modal sign-on dialog's password field over the frame.
     */
    @Test
    void aGlassCaptureBlanksTheMasksOfADialogOverTheFrame() throws Exception {
        final JDialog[] dialog = new JDialog[1];
        final javax.swing.JPasswordField[] secret = new javax.swing.JPasswordField[1];
        onEdt(() -> {
            dialog[0] = new JDialog(ui.frame, "Sign on", true);
            secret[0] = new javax.swing.JPasswordField("hunter2", 12);
            dialog[0].getContentPane().add(secret[0]);
            dialog[0].setBounds(300, 300, 200, 80);
            return null;
        });
        SwingUtilities.invokeLater(() -> dialog[0].setVisible(true));
        await("the sign-on dialog is showing", WAIT_MS, () -> secret[0].isShowing());
        settle();
        Map<String, Object> shot = rctx.call("swing.capture", map("target", rref(ui.frame), "mode", "ROBOT"),
                CALL_MS);
        boolean listed = false;
        for (Object o : arr(shot.get("redacted"))) {
            listed |= rref(secret[0]).equals(obj(o).get("ref"));
        }
        assertTrue(listed, "the dialog's password field is in redacted: " + shot.get("redacted"));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String)
                shot.get("base64"))));
        Map<String, Object> origin = obj(shot.get("origin"));
        Point centre = onEdt(() -> {
            Point p = new Point(secret[0].getWidth() / 2, secret[0].getHeight() / 2);
            SwingUtilities.convertPointToScreen(p, secret[0]);
            return p;
        });
        assertEquals(0xFF808080, img.getRGB((int) (centre.x - (Long) origin.get("x")),
                (int) (centre.y - (Long) origin.get("y"))), "the dialog's password field is grey in the glass");
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-S3): the glass capture keeps section 2.4's heap bound (refused before the grab,
     * naming both figures) and answers an {@code OutOfMemoryError} of the grab {@code INTERNAL_ERROR}.
     */
    @Test
    void aGlassCaptureKeepsTheHeapBoundAndCatchesOutOfMemory() throws Exception {
        try {
            Capture.heap = () -> 1L << 20; // 1 MB free: an eighth is 128 KB; the frame's grab needs about 1.2 MB
            RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> rctx.call("swing.capture", map("target",
                    rref(ui.frame), "mode", "ROBOT"), CALL_MS));
            assertTrue(e.error().data().containsKey("neededMb") && e.error().data().containsKey("freeMb"),
                    String.valueOf(e.error().data()));
            assertFalse(os.calls.contains("grab"), "refused before the grab: " + os.calls);
        } finally {
            Capture.heap = Capture.RUNTIME_HEAP;
        }
        os.duringGrab = () -> {
            throw new OutOfMemoryError("test: no room for the grab");
        };
        RpcException oom = expectError(ErrorCodes.INTERNAL_ERROR, () -> rctx.call("swing.capture", map("target",
                rref(ui.frame), "mode", "ROBOT"), CALL_MS));
        assertEquals("out of memory", oom.error().data().get("reason"));
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

    // ------------------------------------------------------------------- Phase 7 security fix (SG, 2026-10-11)

    /** A second frame (and a window it owns) away from the test frame; neither takes the focus. */
    private Window[] secondFrameAndItsDialog(Rectangle frameBounds, Rectangle dialogBounds) throws Exception {
        final Window[] made = new Window[2];
        onEdt(() -> {
            JFrame other = new JFrame("A second desk frame");
            other.getContentPane().add(new JButton("Renew"));
            other.setBounds(frameBounds);
            other.setFocusableWindowState(false);
            other.setVisible(true);
            JDialog owned = new JDialog(other, "Its dialog", false);
            owned.setBounds(dialogBounds);
            owned.setFocusableWindowState(false);
            owned.setVisible(true);
            made[0] = other;
            made[1] = owned;
            return null;
        });
        await("the second frame and its dialog are showing", WAIT_MS, () -> made[0].isShowing()
                && made[1].isShowing());
        settle();
        return made;
    }

    private void assertIconifiedRefusals(Window frame, Window owned) throws Exception {
        for (Window w : new Window[] {frame, owned}) {
            RpcException e = expectError(ErrorCodes.NOT_FOUND, () -> robot("point", map("x", 20L, "y", 20L), "space",
                    map("window", rref(w)), "action", "CLICK", "via", "ROBOT", "requireFocus", false));
            assertEquals("iconified", e.error().data().get("reason"), e.getMessage());
        }
        assertTrue(os.calls.isEmpty(), "nothing moved: " + os.calls);
        assertEquals(CapabilityState.OK, rctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state());
        Map<String, Object> shot = rctx.call("swing.capture", map("scope", "SCREEN"), CALL_MS);
        for (Object o : arr(shot.get("windows"))) {
            assertFalse(rref(frame).equals(obj(o).get("ref")) || rref(owned).equals(obj(o).get("ref")),
                    "the SCREEN composite leaves them out: " + shot.get("windows"));
        }
    }

    /**
     * Security fix S104 (L2-F1): a Robot point in a frame the toolkit reports iconified, or in a window it owns, is
     * {@code NOT_FOUND iconified} before anything moves, and the {@code SCREEN} composite leaves both out. A display
     * without a window manager cannot really iconify a frame; where the toolkit still reports the state the test runs
     * on the real frame, else it reports itself skipped and the seam variant below stands in.
     */
    @Test
    void aPointInAnIconifiedFrameOrAWindowItOwnsIsRefused() throws Exception {
        final Window[] two = secondFrameAndItsDialog(new Rectangle(660, 20, 300, 200),
                new Rectangle(680, 260, 150, 100));
        onEdt(() -> {
            ((Frame) two[0]).setExtendedState(Frame.ICONIFIED);
            return null;
        });
        long deadline = System.currentTimeMillis() + 3000;
        boolean iconified = false;
        while (!iconified && System.currentTimeMillis() < deadline) {
            iconified = onEdt(() -> (((Frame) two[0]).getExtendedState() & Frame.ICONIFIED) != 0);
            if (!iconified) {
                settle();
            }
        }
        assumeTrue(iconified, "the toolkit reports no iconified state on this display");
        assertIconifiedRefusals(two[0], two[1]);
    }

    /**
     * Security fix S104, through the state seam on real windows (any display): the frame the seam calls iconified and
     * the dialog it owns are refused {@code NOT_FOUND iconified} with nothing moved, are left out of the composite,
     * and are no covering window of a point of the test frame they lie over.
     */
    @Test
    void theHiddenPredicateRefusesRealWindowsAndIsNoCoveringWindow() throws Exception {
        Point okScreen = onEdt(() -> {
            Point p = new Point(ui.ok.getWidth() / 2, ui.ok.getHeight() / 2);
            SwingUtilities.convertPointToScreen(p, ui.ok);
            return p;
        });
        final Window[] two = secondFrameAndItsDialog(new Rectangle(okScreen.x - 30, okScreen.y - 30, 200, 120),
                new Rectangle(680, 260, 150, 100));
        try {
            Windows.state = new Windows.State() {
                @Override
                public boolean iconified(java.awt.Component window) {
                    return window == two[0];
                }

                @Override
                public java.awt.Component owner(java.awt.Component window) {
                    return Windows.TOOLKIT_STATE.owner(window);
                }
            };
            assertIconifiedRefusals(two[0], two[1]);
            Map<String, Object> hit = rctx.call("swing.hitTest", map("target", rref(ui.ok)), CALL_MS);
            for (Object o : arr(hit.get("covering"))) {
                assertFalse(rref(two[0]).equals(obj(o).get("ref")), "not a covering window: " + hit.get("covering"));
            }
        } finally {
            Windows.state = Windows.TOOLKIT_STATE;
        }
    }

    /**
     * Security fix S105 (L2-F2): a Robot point, or a drag's end point, that lies inside the target window but on no
     * screen (the WM-less display is 1024 x 768; a frame at x = 900, 300 wide, reaches x = 1199) is
     * {@code NOT_FOUND off-screen} before anything moves, instead of a press at the screen's edge (window x = 123).
     */
    @Test
    void aPointOnNoScreenIsRefusedBeforeAnythingMoves() throws Exception {
        Rectangle screens = onEdt(() -> {
            Rectangle all = null;
            for (java.awt.GraphicsDevice d : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                all = all == null ? b : all.union(b);
            }
            return all;
        });
        int x = screens.x + screens.width - 124;
        final Window[] two = secondFrameAndItsDialog(new Rectangle(x, 100, 300, 200), new Rectangle(680, 400, 100,
                60));
        assertEquals(x, (int) onEdt(() -> two[0].getLocationOnScreen().x), "the frame lies across the edge");
        RpcException e = expectError(ErrorCodes.NOT_FOUND, () -> robot("point", map("x", 250L, "y", 50L), "space",
                map("window", rref(two[0])), "action", "CLICK", "via", "ROBOT", "requireFocus", false));
        assertEquals("off-screen", e.error().data().get("reason"), e.getMessage());
        RpcException drag = expectError(ErrorCodes.NOT_FOUND, () -> robot("point", map("x", 20L, "y", 50L), "space",
                map("window", rref(two[0])), "to", map("x", 250L, "y", 50L), "action", "DRAG", "via", "ROBOT",
                "requireFocus", false));
        assertEquals("off-screen", drag.error().data().get("reason"));
        assertEquals("to", drag.error().data().get("param"));
        assertTrue(os.calls.isEmpty(), "nothing moved: " + os.calls);
        assertEquals(CapabilityState.OK, rctx.capabilities.get(SwingModule.CAP_POINTER_ROBOT).state());
    }

    /**
     * Security fix S105 (the L2 sceptic's restatement): a press received by another component than the hit-tested
     * one clears the passed delivery probe, so that the next action is probed again rather than pressed at once.
     */
    @Test
    void aPressReceivedElsewhereIsProbedAgainBeforeTheNextPress() throws Exception {
        assertEquals(Boolean.TRUE, robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT",
                "requireFocus", false).get("fired"));
        Rectangle okRect = onEdt(() -> SwingUtilities.convertRectangle(ui.ok.getParent(), ui.ok.getBounds(), ui.frame));
        Rectangle cancelRect = onEdt(() -> SwingUtilities.convertRectangle(ui.cancel.getParent(),
                ui.cancel.getBounds(), ui.frame));
        os.pressOffset = new Point((int) (cancelRect.getCenterX() - okRect.getCenterX()),
                (int) (cancelRect.getCenterY() - okRect.getCenterY()));
        assertEquals("received elsewhere", robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT",
                "requireFocus", false).get("reason"));
        os.calls.clear();
        robot("by", map("id", "okButton"), "action", "CLICK", "via", "ROBOT", "requireFocus", false);
        assertTrue(os.calls.indexOf("press") > 1, "a delivery probe's moves came before the press: " + os.calls);
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
