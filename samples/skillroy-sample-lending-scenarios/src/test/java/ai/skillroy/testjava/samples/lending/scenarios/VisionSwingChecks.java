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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.image.ImageCompare;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 7 Swing checks on the real desk (phase7-contracts.md section 13.2 items 1 to 5), woven by {@code @Order}
 * into {@link LoadModeChecks} in every load mode, after check 3 (50) and before check 4 (60), each leaving the desk
 * signed off: the capture painted from the root pane with its geometry, marks, redaction in the {@code PASSWORD} state
 * and the caret; the hit-test of the keypad keys, a table row, the number pad's {@code key:7}, the prompt dialog as the
 * blocker and a ranked covering window; the routed pointer on {@code DUE} and the number pad, {@code MODAL_OPEN} and
 * {@code force}, {@code expectTarget}, a drag over the loan table and a wheel on the slip panel; {@code swing.type}
 * without a target (gap 60 named); {@code swing.rendering}. Item 6 (Robot) is {@link RobotChecks}' own leg.
 */
abstract class VisionSwingChecks extends LoadModeChecks {

    static final int GREY = 0xFF808080;
    static final List<String> KEYS = List.of("QTY", "CANCEL", "RETURN", "DUE", "DEPOSIT", "SIGN_OFF");

    Map<String, Object> call(String method, Object... kv) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            params.put((String) kv[i], kv[i + 1]);
        }
        return value(client().call(method, params, CALL));
    }

    AgentRpcException fails(int code, String method, Object... kv) {
        AgentRpcException e = assertThrows(AgentRpcException.class, () -> call(method, kv));
        assertEquals(code, e.error().code(), String.valueOf(e.error()));
        return e;
    }

    static BufferedImage decode(Map<String, Object> shot) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String) shot.get("base64"))));
    }

    /** The raster with the capture's caret rectangles blanked, as a comparison blanks them (BS11 condition 1). */
    static BufferedImage withoutCarets(Map<String, Object> shot) throws Exception {
        BufferedImage img = decode(shot);
        for (Object c : (List<?>) shot.get("carets")) {
            Map<?, ?> rect = (Map<?, ?>) ((Map<?, ?>) c).get("rect");
            int x0 = ((Number) rect.get("x")).intValue();
            int y0 = ((Number) rect.get("y")).intValue();
            for (int y = y0; y < y0 + ((Number) rect.get("h")).intValue() && y < img.getHeight(); y++) {
                for (int x = x0; x < x0 + ((Number) rect.get("w")).intValue() && x < img.getWidth(); x++) {
                    img.setRGB(x, y, GREY);
                }
            }
        }
        return img;
    }

    static boolean sameRaster(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    Map<String, Object> node(Map<String, Object> by) throws Exception {
        return first(call("swing.find", "by", by).get("matches"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> first(Object list) {
        return (Map<String, Object>) ((List<?>) list).get(0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    static long n(Object o) {
        return ((Number) o).longValue();
    }

    /** The centre of a node's bounds in its window's space. */
    static Map<String, Object> centre(Map<String, Object> node) {
        Map<String, Object> b = map(node.get("bounds"));
        return Map.of("x", n(b.get("x")) + n(b.get("w")) / 2, "y", n(b.get("y")) + n(b.get("h")) / 2);
    }

    /** The node whose child has the ref, searched depth first, or null. */
    static Map<String, Object> parentOf(Map<String, Object> node, String ref) {
        Object children = node.get("children");
        if (!(children instanceof List)) {
            return null;
        }
        for (Object o : (List<?>) children) {
            Map<String, Object> child = map(o);
            if (ref.equals(child.get("ref"))) {
                return node;
            }
            Map<String, Object> found = parentOf(child, ref);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    String frameRef() throws Exception {
        for (Object w : (List<?>) call("swing.windows").get("windows")) {
            Map<String, Object> window = map(w);
            if ("frame".equals(window.get("type"))) {
                return (String) window.get("ref");
            }
        }
        throw new AssertionError("no frame");
    }

    /** Makes the desk's frame the focused window (a WM-less display hands the focus back to nobody). */
    void activate() throws Exception {
        Map<String, Object> click = call("swing.click", "by", Map.of("id", "entry"), "rung", "COMPONENT",
                "activateWindow", true, "focusTimeoutMs", 20_000L);
        assertEquals(Boolean.TRUE, click.get("fired"), String.valueOf(click));
    }

    /**
     * Whatever a check left behind (a failed assertion in the middle of it), the next check starts on a signed-off
     * desk: a prompt answered, the loan abandoned, the staff member signed off.
     */
    void recoverQuietly() {
        try {
            Map<String, Object> state = value(client().call("lending.state", Map.of(), CALL));
            if ("PASSWORD".equals(state.get("name")) && Boolean.TRUE.equals(state.get("awaitingInput"))) {
                send("Entry", PASSWORD); // the staff code was taken: finish the sign-on, then sign off
            }
            Runs.PACK.recover(client());
        } catch (Exception | AssertionError e) {
            ItEnv.timing("java=" + java + " mode=" + mode().slug + " visionRecoverFailed=" + e);
        }
    }

    void signOn() throws Exception {
        send("Entry", "100");
        assertEquals("IDLE", send("Entry", PASSWORD).payload().get("state"));
    }

    void signOff() throws Exception {
        Runs.PACK.recover(client());
        assertSignedOff();
    }

    // ------------------------------------------------------------------------------------------- items 1, 2, 4

    /** Items 1 and 2 on the signed-off desk, item 4, then item 1's {@code PASSWORD} state. */
    @Test
    @Order(52)
    void theCaptureHitTestAndTypeOnTheSignedOffDeskAndThePasswordState() throws Exception {
        try {
            assertSignedOff();
            String frame = frameRef();
            Map<String, Object> rootPane = first(map(first(call("swing.snapshot", "root", frame, "maxDepth", 1L)
                    .get("windows"))).get("children"));
            Map<String, Object> rootBounds = map(rootPane.get("bounds"));

            // item 1: the window from its root pane, its geometry, no imitation decoration
            Map<String, Object> shot = call("swing.capture", "target", frame);
            assertEquals(rootBounds.get("w"), shot.get("width"));
            assertEquals(rootBounds.get("h"), shot.get("height"));
            assertEquals("WINDOW", shot.get("space"));
            assertEquals(frame, shot.get("window"));
            assertEquals(Map.of("x", rootBounds.get("x"), "y", rootBounds.get("y")), shot.get("origin"));
            assertNotNull(shot.get("seq"));
            Map<String, Object> rootShot = call("swing.capture", "target", rootPane.get("ref"));
            assertTrue(sameRaster(withoutCarets(shot), withoutCarets(rootShot)),
                    "the frame's capture is its root pane's raster: the top rows hold the prompt line, not a title bar");
            Map<String, Object> again = call("swing.capture", "target", frame);
            assertTrue(sameRaster(withoutCarets(shot), withoutCarets(again)), "stable but for the caret (BS11)");
            // the agent's hash and the controller's ImageCompare.rasterHash agree on the desk's raster (fix wave of the
            // Phase 7 review, FA, L3-F1: the export checks an anchor's capture file by exactly this equality)
            assertEquals(shot.get("hash"), ImageCompare.rasterHash(decode(shot)), "Capture.hash == rasterHash");

            Map<String, Object> table = node(Map.of("name", "loanTable"));
            Map<String, Object> component = call("swing.capture", "target", table.get("ref"));
            Map<String, Object> tb = map(table.get("bounds"));
            assertEquals(Map.of("x", tb.get("x"), "y", tb.get("y")), component.get("origin"));

            Map<String, Object> marked = call("swing.capture", "target", frame, "annotateRefs", true);
            // the agent draws nothing: the annotated raster is the plain one, carets blanked as above since the desk is
            // focused here and the caret blinks (fix wave, FA, L3-F4; the raw hash would be flaky)
            assertTrue(sameRaster(withoutCarets(shot), withoutCarets(marked)), "marks leave the pixels untouched");
            List<String> keyMarks = new ArrayList<>();
            List<String> regions = new ArrayList<>();
            boolean header = false;
            for (Object o : (List<?>) marked.get("marks")) {
                Map<String, Object> m = map(o);
                for (String key : KEYS) {
                    if (String.valueOf(m.get("label")).contains("'" + key + "'")) {
                        keyMarks.add(key);
                    }
                }
                if (m.get("region") != null) {
                    regions.add((String) m.get("region"));
                }
                header |= String.valueOf(m.get("label")).startsWith("header");
            }
            assertEquals(KEYS, keyMarks, "one mark per keypad key with its NameEnricher name, in reading order");
            assertEquals(12, regions.size(), "one mark per number-pad region: " + regions);
            assertTrue(regions.contains("key:7") && regions.contains("key:clear"), regions.toString());
            assertTrue(header, "the loan table's header");

            // item 2: every keypad key's centre in window space and in the capture's space
            Map<String, Object> origin = map(shot.get("origin"));
            for (String key : KEYS) {
                Map<String, Object> keyNode = node(Map.of("action", key));
                Map<String, Object> c = centre(keyNode);
                Map<String, Object> hit = call("swing.hitTest", "point", c, "space", Map.of("window", frame));
                assertEquals(keyNode.get("ref"), hit.get("target"), key);
                assertEquals(Map.of("action", key), first(hit.get("locators")), key + ": the domain locator first");
                assertEquals(Boolean.TRUE, hit.get("fires"), key);
                assertEquals(0.5, ((Number) map(hit.get("relative")).get("fx")).doubleValue(), 0.02, key);
                Map<String, Object> viaCapture = call("swing.hitTest", "point", Map.of("x", n(c.get("x"))
                        - n(origin.get("x")), "y", n(c.get("y")) - n(origin.get("y"))), "space",
                        Map.of("captureId", shot.get("captureId")));
                assertEquals(keyNode.get("ref"), viaCapture.get("target"), key + " through the capture");
            }
            Map<String, Object> pad = node(Map.of("id", "numberPad"));
            Map<String, Object> pb = map(pad.get("bounds"));
            Map<String, Object> seven = call("swing.hitTest", "point", Map.of("x", n(pb.get("x")) + 24, "y",
                    n(pb.get("y")) + 20), "space", Map.of("window", frame));
            assertEquals("key:7", map(seven.get("region")).get("id"));
            Map<String, Object> entryHit = call("swing.hitTest", "target", Map.of("id", "entry"));
            assertEquals(Map.of("id", "entry"), first(entryHit.get("locators")));
            assertEquals(Boolean.FALSE, entryHit.get("masked"));

            // item 3's number pad: key:1, key:0, key:0 type 100 into the entry field; CLEAR empties it
            for (String key : List.of("key:1", "key:0", "key:0")) {
                Map<String, Object> r = call("swing.pointer", "by", Map.of("id", "numberPad"), "region", key, "action",
                        "CLICK");
                assertEquals(Boolean.TRUE, r.get("fired"), String.valueOf(r));
            }
            call("swing.settle", "timeoutMs", 10_000L);
            assertEquals("100", node(Map.of("id", "entry")).get("text"));
            call("swing.pointer", "by", Map.of("id", "numberPad"), "region", "key:clear", "action", "CLICK");
            call("swing.settle", "timeoutMs", 10_000L);
            assertEquals("", node(Map.of("id", "entry")).get("text"));

            // item 4: swing.type without a target types into the focus owner (SET)
            activate();
            call("swing.type", "text", "abc");
            call("swing.settle", "timeoutMs", 10_000L);
            assertEquals("abc", node(Map.of("id", "entry")).get("text"));
            call("swing.type", "text", "");

            // item 4, gap 60: DISPATCH with enter submits the field that last had the focus (here the entry field), so
            // the staff code is submitted and the desk asks for the password
            RpcResult typed = client().call("swing.type", Map.of("text", "100", "mode", "DISPATCH", "enter", true), CALL);
            Event asked = client().await("input.requested", typed.seqBefore(), Map.of("state", "PASSWORD"), WAIT);
            assertNotNull(asked, "the dispatched ENTER submitted the entry field (gap 60)");

            // item 1, the PASSWORD state: the entry field and the number pad are masked by the pack's CaptureMask
            Map<String, Object> secret = call("swing.capture", "target", frame, "annotateRefs", true);
            List<String> redacted = new ArrayList<>();
            for (Object o : (List<?>) secret.get("redacted")) {
                Map<String, Object> r = map(o);
                assertEquals("capture-mask", r.get("rule"));
                redacted.add((String) r.get("ref"));
            }
            String entryRef = (String) node(Map.of("id", "entry")).get("ref");
            assertTrue(redacted.contains(entryRef) && redacted.contains(pad.get("ref")), redacted.toString());
            BufferedImage grey = decode(secret);
            Map<String, Object> eb = map(node(Map.of("id", "entry")).get("bounds"));
            int ex = (int) (n(eb.get("x")) - n(origin.get("x")) + n(eb.get("w")) / 2);
            int ey = (int) (n(eb.get("y")) - n(origin.get("y")) + n(eb.get("h")) / 2);
            assertEquals(GREY, grey.getRGB(ex, ey), "the entry field is grey");
            assertEquals(GREY, grey.getRGB((int) (n(pb.get("x")) - n(origin.get("x")) + 24),
                    (int) (n(pb.get("y")) - n(origin.get("y")) + 20)), "the number pad is grey");
            for (Object o : (List<?>) secret.get("marks")) {
                Map<String, Object> m = map(o);
                assertNotEquals(entryRef, m.get("ref"), "no mark on the masked entry field");
                assertNotEquals(pad.get("ref"), m.get("ref"), "no mark on the masked number pad");
            }
            Map<String, Object> entryNode = node(Map.of("id", "entry"));
            assertNull(entryNode.get("text"), "the masked field's text is absent from the snapshot");
            assertEquals(Boolean.TRUE, entryNode.get("masked"));
            Map<String, Object> maskedHit = call("swing.hitTest", "target", entryRef);
            assertEquals(Boolean.TRUE, maskedHit.get("masked"));
            assertNull(map(maskedHit.get("deepest")).get("text"));
            BufferedImage open = decode(call("swing.capture", "target", frame, "redact", false));
            assertNotEquals(GREY, open.getRGB(ex, ey), "a person's redact: false shows the field (its asterisks)");
            boolean caret = false;
            for (Object o : (List<?>) secret.get("carets")) {
                caret |= entryRef.equals(map(o).get("ref"));
            }
            assertTrue(caret, "the focused entry field's caret: " + secret.get("carets"));
            assertEquals(Map.of("id", "entry"), map(secret.get("focus")).get("ownerLocator"));

            assertEquals("IDLE", send("Entry", PASSWORD).payload().get("state"));
            signOff();
        } finally {
            recoverQuietly();
        }
    }

    // ---------------------------------------------------------------------------------------- items 1, 2, 3

    /** Items 1 to 3 on the signed-on desk with two loan lines: rows, a drag, the routed DUE. */
    @Test
    @Order(54)
    void rowsADragAndTheRoutedDueOnTheSignedOnDesk() throws Exception {
        try {
            signOn();
            assertEquals("LOAN", send("Barcode", "5000000000017").payload().get("state"));
            assertEquals("LOAN", send("Barcode", "5000000000024").payload().get("state"));
            String frame = frameRef();
            Map<String, Object> marked = call("swing.capture", "target", frame, "annotateRefs", true);
            List<Long> rows = new ArrayList<>();
            for (Object o : (List<?>) marked.get("marks")) {
                if (map(o).get("row") != null) {
                    rows.add(n(map(o).get("row")));
                }
            }
            assertEquals(List.of(0L, 1L), rows, "one mark per table row");
            Map<String, Object> table = node(Map.of("name", "loanTable"));
            Map<String, Object> tb = map(table.get("bounds"));
            Map<String, Object> row1 = call("swing.hitTest", "point", Map.of("x", n(tb.get("x")) + 20, "y",
                    n(tb.get("y")) + 24), "space", Map.of("window", frame));
            assertEquals(table.get("ref"), row1.get("target"));
            assertEquals(1L, n(row1.get("row")));

            Map<String, Object> rowHit0 = call("swing.hitTest", "point", Map.of("x", n(tb.get("x")) + 20, "y",
                    n(tb.get("y")) + 4), "space", Map.of("window", frame));
            assertEquals(0L, n(rowHit0.get("row")));
            Map<String, Object> drag = call("swing.pointer", "point", Map.of("x", n(tb.get("x")) + 20, "y",
                    n(tb.get("y")) + 4), "space", Map.of("window", frame), "to", Map.of("x", n(tb.get("x")) + 20, "y",
                    n(tb.get("y")) + 24), "action", "DRAG");
            assertEquals(Boolean.TRUE, drag.get("fired"), String.valueOf(drag));
            call("swing.settle", "timeoutMs", 10_000L);
            Map<String, Object> selected = map(node(Map.of("name", "loanTable")).get("value"));
            assertEquals(List.of(0L, 1L), selected.get("selectedRows"), "the drag selected both rows");

            // item 3: the routed click on DUE fires the key; the desk's event, not 'fired', is the evidence
            activate();
            RpcResult due = client().call("swing.pointer", Map.of("by", Map.of("action", "DUE"), "action", "CLICK"), CALL);
            Map<String, Object> r = value(due);
            assertEquals(Boolean.TRUE, r.get("fired"), String.valueOf(r));
            assertEquals(node(Map.of("action", "DUE")).get("ref"), r.get("received"));
            Map<String, Object> d = map(r.get("dispatched"));
            assertEquals(d.get("of"), d.get("sent"));
            Event deposit = client().await("input.requested", due.seqBefore(), Map.of("state", "DEPOSIT"), WAIT);
            assertNotNull(deposit, "the routed DUE reached the desk");
            signOff();
        } finally {
            recoverQuietly();
        }
    }

    // ------------------------------------------------------------------------------------ items 1, 2, 3: modal

    /** Items 1 to 3 with the prompt dialog open: SCREEN, the blocker, a ranked covering window, MODAL_OPEN. */
    @Test
    @Order(56)
    void thePromptDialogOnTopTheBlockerAndModalOpen() throws Exception {
        try {
            signOn();
            RpcResult scan = client().call("lending.input", Map.of("source", "Barcode", "value", "0000000000000"), CALL);
            assertNotNull(client().await("window.opened", scan.seqBefore(), Map.of("modal", true), WAIT));
            String frame = frameRef();
            String dialog = (String) first(call("swing.dialogs").get("dialogs")).get("ref");

            Map<String, Object> screen = call("swing.capture", "scope", "SCREEN");
            List<?> windows = (List<?>) screen.get("windows");
            assertEquals(2, windows.size());
            assertEquals(dialog, map(windows.get(1)).get("ref"), "the dialog's root pane on top");
            assertEquals(Boolean.TRUE, screen.get("stackingAssumed"));

            Map<String, Object> due = node(Map.of("action", "DUE"));
            Map<String, Object> blocked = call("swing.hitTest", "point", centre(due), "space", Map.of("window", frame));
            assertEquals(dialog, map(blocked.get("blockedBy")).get("ref"));
            Map<String, Object> frameNode = first(call("swing.snapshot", "root", frame, "maxDepth", 0L)
                    .get("windows"));
            Map<String, Object> fb = map(frameNode.get("bounds"));
            Map<String, Object> middle = call("swing.hitTest", "point", Map.of("x", n(fb.get("w")) / 2, "y",
                    n(fb.get("h")) / 2), "space", Map.of("window", frame));
            boolean ranked = false;
            for (Object o : (List<?>) middle.get("covering")) {
                ranked |= dialog.equals(map(o).get("ref")) && Boolean.TRUE.equals(map(o).get("ranked"));
            }
            assertTrue(ranked, "the prompt covers the frame's middle, ranked: " + middle.get("covering"));

            fails(ErrorCodes.MODAL_OPEN, "swing.pointer", "by", Map.of("id", "entry"), "action", "CLICK");
            Map<String, Object> forced = call("swing.pointer", "by", Map.of("id", "entry"), "action", "CLICK",
                    "force", true);
            assertEquals(Boolean.TRUE, forced.get("fired"), "a person's force delivers (BS10 case modal): " + forced);
            AgentRpcException changed = fails(ErrorCodes.INPUT_REJECTED, "swing.pointer", "by", Map.of("name", "OK"),
                    "action", "MOVE", "expectTarget", frame);
            assertEquals("target changed", changed.error().data().get("reason"));

            RpcResult answer = client().call("lending.prompt.answer", Map.of("choice", "OK"), CALL);
            assertNotNull(client().await(READY_TOPICS, answer.seqBefore(), null, WAIT), "the desk waits again");
            signOff();
        } finally {
            recoverQuietly();
        }
    }

    // ------------------------------------------------------------------------------------------- items 3, 5

    /** Item 3's wheel on the slip panel (after six loans have printed their slips) and item 5. */
    @Test
    @Order(58)
    void aWheelOnTheSlipPanelAndTheRenderingInputs() throws Exception {
        try {
            // six loans, so that the slip panel's text (about ten lines a slip) is taller than its 30-row view
            signOn();
            for (int i = 0; i < 6; i++) {
                assertEquals("LOAN", send("Barcode", "5000000000017").payload().get("state"));
                RpcResult due = client().call("lending.input", Map.of("source", "Key.DUE"), CALL);
                assertNotNull(client().await("input.requested", due.seqBefore(), Map.of("state", "DEPOSIT"), WAIT));
                assertEquals("IDLE", send("Key.DEPOSIT", "10.00").payload().get("state"));
            }
            signOff();
            call("swing.settle", "timeoutMs", 10_000L);
            Map<String, Object> before = node(Map.of("name", "slip"));
            String frame = frameRef();
            Map<String, Object> viewport = parentOf(first(call("swing.snapshot", "root", frame).get("windows")),
                    (String) before.get("ref"));
            assertNotNull(viewport, "the slip panel's viewport");
            long beforeY = n(map(before.get("bounds")).get("y"));
            long areaH = n(map(before.get("bounds")).get("h"));
            long viewH = n(map(viewport.get("bounds")).get("h"));
            // the six loans are there so that the slip is taller than its view; the wheel must then move it (fix wave,
            // FA, L3-F5: the check no longer accepts "nothing moved" for a slip that happens to fit)
            assertTrue(areaH > viewH, "the slip's text (" + areaH + " px) is taller than its view (" + viewH + " px)");
            // at the top of its view the slip scrolls down, else up
            long direction = beforeY >= n(map(viewport.get("bounds")).get("y")) ? 5L : -5L;
            Map<String, Object> wheel = call("swing.pointer", "point", centre(viewport), "space", Map.of("window",
                    frame), "action", "WHEEL", "wheel", Map.of("notches", direction));
            assertEquals(Boolean.TRUE, wheel.get("fired"), String.valueOf(wheel));
            assertNotNull(wheel.get("received"), "the wheel reached the slip panel's scroll pane");
            call("swing.settle", "timeoutMs", 10_000L);
            Map<String, Object> after = node(Map.of("name", "slip"));
            long afterY = n(map(after.get("bounds")).get("y"));
            String slipText = String.valueOf(before.get("text"));
            ItEnv.timing("java=" + java + " mode=" + mode().slug + " slipWheel=" + beforeY + "->" + afterY
                    + " area=" + areaH + " view=" + viewH + " lines=" + slipText.split("\n", -1).length
);
            assertNotEquals(beforeY, afterY, "the slip panel scrolled (its text is taller than its view)");

            Map<String, Object> rendering = call("swing.rendering");
            for (String key : List.of("jdk", "os", "lookAndFeel", "antialiasing", "screenScale", "probeHash", "keyed",
                    "renderKey")) {
                assertTrue(rendering.containsKey(key), key);
            }
            assertEquals(rendering.get("renderKey"), call("swing.rendering").get("renderKey"), "stable");
            // the cross-leg half of item 5 (Java 8 against Java 25, arm64 against amd64) compares this line
            ItEnv.timing("java=" + java + " mode=" + mode().slug + " renderKey=" + rendering.get("renderKey")
                    + " spec=" + map(rendering.get("jdk")).get("specification"));
            assertFalse(String.valueOf(rendering.get("probeHash")).isEmpty());
            assertSignedOff();
        } finally {
            recoverQuietly();
        }
    }
}
