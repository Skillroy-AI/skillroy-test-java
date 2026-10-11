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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.arr;
import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 7 Swing primitives on a real display (phase7-contracts.md sections 3.1-3.5, 3.8): the capture painted
 * from the root pane with its geometry, redaction, scopes, marks and hash; the hit-test in window and capture space
 * with its locators, row, fires, covering windows and blocker; the routed pointer (click, point form,
 * {@code expectTarget}, drag, wheel); {@code swing.type} without a target; {@code swing.rendering}.
 */
class VisionDisplayTest extends DisplayTestBase {

    @AfterEach
    void closeExtraWindows() throws Exception {
        onEdt(() -> {
            for (Window w : Window.getWindows()) {
                if (w != ui.frame && w.isDisplayable()) {
                    w.dispose();
                }
            }
            return null;
        });
        await("extra windows gone", WAIT_MS, () -> Windows.showingModalDialogs().isEmpty());
        settle();
    }

    private static BufferedImage decode(Map<String, Object> shot) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String) shot.get("base64"))));
    }

    /** Moves the focus off the text fields, so that no caret blinks between two captures (BS11). */
    private static void focusOk() throws Exception {
        activate("the test frame is the focused window", ui.frame);
        onEdt(() -> ui.ok.requestFocusInWindow());
        await("OK owns the focus", WAIT_MS, () -> ui.ok.hasFocus());
        settle();
    }

    // -------------------------------------------------------------------------------------------------- capture

    @Test
    void aWindowIsPaintedFromItsRootPaneAndSaysWhereItsPixelsLie() throws Exception {
        focusOk();
        Map<String, Object> shot = call("swing.capture");
        Point root = onEdt(() -> SwingUtilities.convertPoint(ui.frame.getRootPane(), 0, 0, ui.frame));
        assertEquals("WINDOW", shot.get("space"));
        assertEquals("WINDOW", shot.get("scope"));
        assertEquals("PAINT", shot.get("mode"));
        assertEquals(ref(ui.frame), shot.get("window"));
        assertEquals(map("x", (long) root.x, "y", (long) root.y), shot.get("origin"));
        assertEquals(1.0, shot.get("scale"));
        assertTrue(((String) shot.get("captureId")).startsWith("cap-"));
        assertTrue(((String) shot.get("hash")).startsWith("sha256:"));
        assertNotNull(shot.get("seq"));
        assertEquals(1, arr(shot.get("windows")).size());
        // the same raster as a capture of the root pane itself (no imitation decorations; gap 76)
        Map<String, Object> rootShot = call("swing.capture", "target", ref(onEdt(() -> ui.frame.getRootPane())));
        assertEquals(shot.get("hash"), rootShot.get("hash"));
        assertEquals(shot.get("hash"), call("swing.capture").get("hash"), "stable on the unfocused caret");
        // the password field is blanked grey and reported (A61, D295)
        List<Object> redacted = arr(shot.get("redacted"));
        assertEquals(1, redacted.size(), redacted.toString());
        assertEquals(ref(ui.pin), obj(redacted.get(0)).get("ref"));
        assertEquals("password-field", obj(redacted.get(0)).get("rule"));
        Map<String, Object> rect = obj(obj(redacted.get(0)).get("rect"));
        BufferedImage img = decode(shot);
        int cx = (int) ((Long) rect.get("x") + (Long) rect.get("w") / 2);
        int cy = (int) ((Long) rect.get("y") + (Long) rect.get("h") / 2);
        assertEquals(0xFF808080, img.getRGB(cx, cy));
        BufferedImage open = decode(call("swing.capture", "redact", false));
        assertNotEquals(0xFF808080, open.getRGB(cx, cy), "a person's redact: false leaves the field in");
    }

    @Test
    void aComponentScopeLiesAtItsSnapshotBounds() throws Exception {
        Map<String, Object> node = obj(arr(call("swing.find", "by", map("name", "grid")).get("matches")).get(0));
        Map<String, Object> shot = call("swing.capture", "target", ref(ui.grid));
        assertEquals("COMPONENT", shot.get("scope"));
        Map<String, Object> bounds = obj(node.get("bounds"));
        assertEquals(map("x", bounds.get("x"), "y", bounds.get("y")), shot.get("origin"));
        assertEquals(bounds.get("w"), shot.get("width"));
        Map<String, Object> region = call("swing.capture", "target", ref(ui.frame), "scope", "REGION", "region",
                map("x", 10L, "y", 10L, "w", 50L, "h", 40L));
        assertEquals(50L, region.get("width"));
        assertEquals(map("x", 10L, "y", 10L), region.get("origin"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.capture", "region", map("x", 0L, "y", 0L,
                "w", 5L, "h", 5L)));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.capture", "scale", 5.0));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.capture", "scope", "GALAXY"));
    }

    @Test
    void theScreenScopeCompositesTheDialogOnTopOfTheFrame() throws Exception {
        final JDialog[] d = new JDialog[1];
        onEdt(() -> {
            d[0] = new JDialog(ui.frame, "On top", false);
            d[0].getContentPane().setBackground(java.awt.Color.MAGENTA);
            d[0].setBounds(100, 100, 200, 120);
            d[0].setVisible(true);
            return null;
        });
        await("dialog showing", WAIT_MS, d[0]::isShowing);
        settle();
        Map<String, Object> shot = call("swing.capture", "scope", "SCREEN");
        assertEquals("SCREEN", shot.get("space"));
        assertEquals(Boolean.TRUE, shot.get("stackingAssumed"));
        List<Object> ws = arr(shot.get("windows"));
        assertEquals(2, ws.size());
        assertEquals(ref(d[0]), obj(ws.get(1)).get("ref"), "the owned dialog is above its owner");
        Map<String, Object> dr = obj(obj(ws.get(1)).get("rect"));
        BufferedImage img = decode(shot);
        int x = (int) ((Long) dr.get("x") + 100);
        int y = (int) ((Long) dr.get("y") + 60);
        assertEquals(java.awt.Color.MAGENTA.getRGB(), img.getRGB(x, y));
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-F1 case b): a {@code CaptureMask} that names a dialog (whose root pane, not the
     * dialog, is the paint root) blanks the dialog's whole image in {@code WINDOW} and its layer in {@code SCREEN},
     * reported under the dialog's ref; the frame beside it is painted.
     */
    @Test
    void aMaskThatNamesADialogBlanksItInWindowAndScreenCaptures() throws Exception {
        final JDialog[] d = new JDialog[1];
        onEdt(() -> {
            d[0] = new JDialog(ui.frame, "Shows personal data", false);
            d[0].getContentPane().setBackground(java.awt.Color.MAGENTA);
            d[0].setBounds(100, 100, 200, 120);
            d[0].setVisible(true);
            return null;
        });
        await("dialog showing", WAIT_MS, d[0]::isShowing);
        settle();
        AutoCloseable mask = ctx.spi.register(ai.skillroy.testjava.agent.api.spi.CaptureMask.class,
                c -> c == d[0]);
        try {
            Map<String, Object> window = call("swing.capture", "target", ref(d[0]), "annotateRefs", true);
            BufferedImage wi = decode(window);
            assertEquals(0xFF808080, wi.getRGB(wi.getWidth() / 2, wi.getHeight() / 2), "the dialog is grey");
            assertEquals(0xFF808080, wi.getRGB(1, 1));
            List<Object> redacted = arr(window.get("redacted"));
            assertEquals(1, redacted.size(), redacted.toString());
            assertEquals(ref(d[0]), obj(redacted.get(0)).get("ref"));
            assertEquals(0, arr(window.get("marks")).size(), "nothing in a masked dialog is marked");

            Map<String, Object> screen = call("swing.capture", "scope", "SCREEN");
            BufferedImage si = decode(screen);
            Map<String, Object> dr = null;
            for (Object o : arr(screen.get("windows"))) {
                if (ref(d[0]).equals(obj(o).get("ref"))) {
                    dr = obj(obj(o).get("rect"));
                }
            }
            assertNotNull(dr, "the dialog is in the composite");
            assertEquals(0xFF808080, si.getRGB((int) ((Long) dr.get("x") + 100), (int) ((Long) dr.get("y") + 60)),
                    "the dialog's layer is grey");
            boolean listed = false;
            for (Object o : arr(screen.get("redacted"))) {
                listed |= ref(d[0]).equals(obj(o).get("ref"));
            }
            assertTrue(listed, "reported under the dialog's ref: " + screen.get("redacted"));
        } finally {
            mask.close();
        }
    }

    @Test
    void marksStandOnTheControlsAndLeaveThePixelsAlone() throws Exception {
        focusOk();
        Map<String, Object> plain = call("swing.capture");
        Map<String, Object> marked = call("swing.capture", "annotateRefs", true);
        assertEquals(plain.get("hash"), marked.get("hash"), "the agent draws nothing (D346)");
        List<Object> marks = arr(marked.get("marks"));
        boolean ok = false;
        boolean due = false;
        int rows = 0;
        for (Object o : marks) {
            Map<String, Object> m = obj(o);
            assertNotEquals(ref(ui.pin), m.get("ref"), "no mark on a masked component");
            ok |= ref(ui.ok).equals(m.get("ref"));
            due |= ref(ui.due).equals(m.get("ref")) && String.valueOf(m.get("label")).contains("DUE");
            if (ref(ui.grid).equals(m.get("ref"))) {
                rows++;
                assertNotNull(m.get("row"));
            }
        }
        assertTrue(ok && due, marks.toString());
        assertEquals(2, rows, "one mark per visible table row");
    }

    // ------------------------------------------------------------------------------------------------- hit-test

    @Test
    void theHitTestNamesTheKeyItsDomainLocatorFirstAndItsRow() throws Exception {
        Rectangle due = onEdt(() -> SwingUtilities.convertRectangle(ui.due.getParent(), ui.due.getBounds(),
                ui.frame));
        Map<String, Object> hit = call("swing.hitTest", "point", map("x", (long) due.getCenterX(), "y",
                (long) due.getCenterY()), "space", map("window", ref(ui.frame)));
        assertEquals(ref(ui.due), hit.get("target"));
        assertEquals("MOUSE_TARGET", hit.get("targetKind"));
        assertEquals(map("action", "DUE"), arr(hit.get("locators")).get(0));
        assertEquals(Boolean.TRUE, hit.get("fires"));
        assertEquals("SWING", hit.get("surface"));
        assertEquals(Boolean.FALSE, hit.get("masked"));
        Map<String, Object> rel = obj(hit.get("relative"));
        assertEquals(0.5, (Double) rel.get("fx"), 0.02);
        // the same point through a capture's image coordinates
        Map<String, Object> shot = call("swing.capture");
        Map<String, Object> origin = obj(shot.get("origin"));
        Map<String, Object> viaCapture = call("swing.hitTest", "point", map("x", (long) due.getCenterX()
                - (Long) origin.get("x"), "y", (long) due.getCenterY() - (Long) origin.get("y")), "space",
                map("captureId", shot.get("captureId")));
        assertEquals(ref(ui.due), viaCapture.get("target"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.hitTest", "point", map("x", 1L, "y", 1L),
                "space", map("captureId", "cap-999999")));
        RpcException outside = expectError(ErrorCodes.NOT_FOUND, () -> call("swing.hitTest", "point",
                map("x", 5000L, "y", 5000L), "space", map("window", ref(ui.frame))));
        assertEquals("outside", outside.error().data().get("reason"));

        Map<String, Object> pin = call("swing.hitTest", "target", ref(ui.pin));
        assertEquals(Boolean.TRUE, pin.get("masked"));
        assertEquals("TARGET", pin.get("targetKind"));
        Map<String, Object> entry = call("swing.hitTest", "target", ref(ui.entry));
        assertEquals(map("id", "entry"), arr(entry.get("locators")).get(0));

        Rectangle row1 = onEdt(() -> SwingUtilities.convertRectangle(ui.grid, ui.grid.getCellRect(1, 0, true),
                ui.frame));
        Map<String, Object> cell = call("swing.hitTest", "point", map("x", (long) row1.getCenterX(), "y",
                (long) row1.getCenterY()), "space", map("window", ref(ui.frame)));
        assertEquals(ref(ui.grid), cell.get("target"));
        assertEquals(1L, cell.get("row"));
    }

    @Test
    void aModalDialogOverThePointIsTheBlockerAndARankedCoveringWindow() throws Exception {
        final JDialog[] d = new JDialog[1];
        onEdt(() -> {
            d[0] = new JDialog(ui.frame, "Covers the frame", true);
            d[0].setBounds(20, 20, 300, 200);
            return null;
        });
        SwingUtilities.invokeLater(() -> d[0].setVisible(true));
        await("modal showing", WAIT_MS, d[0]::isShowing);
        settle();
        Map<String, Object> hit = call("swing.hitTest", "point", map("x", 100L, "y", 100L), "space",
                map("window", ref(ui.frame)));
        assertEquals(ref(d[0]), obj(hit.get("blockedBy")).get("ref"));
        List<Object> covering = arr(hit.get("covering"));
        assertEquals(1, covering.size());
        assertEquals(ref(d[0]), obj(covering.get(0)).get("ref"));
        assertEquals(Boolean.TRUE, obj(covering.get(0)).get("ranked"));
        Map<String, Object> screen = call("swing.hitTest", "point", map("x", 100L, "y", 100L), "space", "SCREEN");
        assertEquals(ref(d[0]), screen.get("window"), "the topmost window at a screen point");
        assertEquals(Boolean.TRUE, screen.get("stackingAssumed"));
    }

    // -------------------------------------------------------------------------------------------------- pointer

    @Test
    void aRoutedClickByLocatorFiresTheButtonAndReportsWhatReceivedIt() throws Exception {
        final int ok = ui.okClicks.get();
        Map<String, Object> r = call("swing.pointer", "by", map("id", "okButton"), "action", "CLICK");
        assertEquals(Boolean.TRUE, r.get("fired"), r.toString());
        assertEquals("ROUTED", r.get("via"));
        assertEquals(ref(ui.ok), r.get("received"));
        Map<String, Object> d = obj(r.get("dispatched"));
        assertEquals(d.get("of"), d.get("sent"));
        assertEquals(Boolean.FALSE, r.get("balanced"));
        assertEquals(ref(ui.ok), obj(r.get("hit")).get("target"));
        await("the routed click acted", WAIT_MS, () -> ui.okClicks.get() == ok + 1);

        Rectangle cancel = onEdt(() -> SwingUtilities.convertRectangle(ui.cancel.getParent(), ui.cancel.getBounds(),
                ui.frame));
        final int cancels = ui.cancelClicks.get();
        Map<String, Object> byPoint = call("swing.pointer", "point", map("x", (long) cancel.getCenterX(), "y",
                (long) cancel.getCenterY()), "space", map("window", ref(ui.frame)), "action", "CLICK");
        assertEquals(ref(ui.cancel), byPoint.get("received"));
        await("the point-addressed click acted", WAIT_MS, () -> ui.cancelClicks.get() == cancels + 1);

        RpcException changed = expectError(ErrorCodes.INPUT_REJECTED, () -> call("swing.pointer", "by",
                map("id", "okButton"), "action", "CLICK", "expectTarget", ref(ui.cancel)));
        assertEquals("target changed", changed.error().data().get("reason"));
        settle();
        assertEquals(ok + 1, ui.okClicks.get(), "a refused pointer did nothing");
    }

    @Test
    void aDragSelectsTableRowsAndAWheelScrolls() throws Exception {
        onEdt(() -> {
            ui.grid.clearSelection();
            return null;
        });
        Map<String, Object> drag = call("swing.pointer", "target", ref(ui.grid), "at", map("fx", 0.5, "fy", 0.25),
                "action", "DRAG", "to", map("fx", 0.5, "fy", 0.75));
        assertEquals(Boolean.TRUE, drag.get("fired"), drag.toString());
        settle();
        assertEquals(2, (int) onEdt(() -> ui.grid.getSelectedRowCount()), "the drag selected both rows");

        final JList<String>[] list = new JList[1];
        final JScrollPane[] pane = new JScrollPane[1];
        onEdt(() -> {
            String[] items = new String[100];
            for (int i = 0; i < items.length; i++) {
                items[i] = "line " + i;
            }
            JFrame f = new JFrame("Long list");
            list[0] = new JList<String>(items);
            pane[0] = new JScrollPane(list[0]);
            f.add(pane[0]);
            f.setBounds(660, 0, 200, 200);
            f.setVisible(true);
            return null;
        });
        await("long list showing", WAIT_MS, () -> list[0].isShowing());
        settle();
        Map<String, Object> wheel = call("swing.pointer", "target", ref(list[0]), "action", "WHEEL", "wheel",
                map("notches", 3L), "at", map("fx", 0.5, "fy", 0.1));
        assertEquals(Boolean.TRUE, wheel.get("fired"), wheel.toString());
        settle();
        assertTrue(onEdt(() -> pane[0].getViewport().getViewPosition().y) > 0, "the wheel scrolled the list");
    }

    // ------------------------------------------------------------------------------------- type and rendering

    @Test
    void typeWithoutATargetGoesToTheFocusOwner() throws Exception {
        activate("the test frame is the focused window", ui.frame);
        onEdt(() -> ui.entry.requestFocusInWindow());
        await("the entry field owns the focus", WAIT_MS, () -> ui.entry.hasFocus());
        call("swing.type", "text", "untargeted");
        settle();
        assertEquals("untargeted", onEdt(() -> ui.entry.getText()));
    }

    @Test
    void typeWithoutATargetInDispatchModeWithEnterSubmitsTheLastFocusedFieldGap60() throws Exception {
        activate("the test frame is the focused window", ui.frame);
        onEdt(() -> ui.entry.requestFocusInWindow());
        await("the entry field owns the focus", WAIT_MS, () -> ui.entry.hasFocus());
        final int actions = ui.entryActions.get();
        call("swing.type", "text", "gap", "mode", "DISPATCH", "enter", true);
        await("the field that has the focus was submitted", WAIT_MS, () -> ui.entryActions.get() == actions + 1);
        assertEquals("gap", ui.lastEntry.get());
    }

    @Test
    void renderingAnswersEveryFieldAndAStableKey() throws Exception {
        Map<String, Object> r = call("swing.rendering");
        for (String key : new String[] {"jdk", "os", "lookAndFeel", "antialiasing", "screenScale", "probeHash",
            "keyed", "renderKey"}) {
            assertTrue(r.containsKey(key), key + " in " + r.keySet());
        }
        assertEquals(list("jdk.specification", "lookAndFeel", "antialiasing", "screenScale", "probeHash"),
                r.get("keyed"));
        assertEquals(r.get("renderKey"), call("swing.rendering").get("renderKey"));
        assertEquals(16, ((String) r.get("renderKey")).length());
        assertNull(obj(r.get("antialiasing")).get("unexpected"));
        assertFalse(((String) r.get("probeHash")).isEmpty());
    }
}
