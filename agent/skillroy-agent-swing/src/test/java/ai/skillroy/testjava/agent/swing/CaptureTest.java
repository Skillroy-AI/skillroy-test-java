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

import ai.skillroy.testjava.agent.api.spi.CaptureMask;
import ai.skillroy.testjava.agent.api.spi.RegionResolver;
import ai.skillroy.testjava.agent.api.spi.SurfaceSource;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JViewport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.arr;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extended {@code swing.capture} without a display (phase7-contracts.md section 13.1 item 1): lightweight trees in
 * no window, the window-list seam for {@code SCREEN}. The mapping rule, {@code scale} and the 4096 cap, the heap bound
 * and an {@code OutOfMemoryError}, the marks' geometry and counts with the pixels untouched, the redaction fill and
 * {@code redacted}, a {@code CaptureMask} on a text field with the masked-text rule, the carets, and the raster hash
 * against the vector {@code ImageCompareTest} shares.
 */
class CaptureTest {

    static final Color BACK = new Color(20, 40, 160);
    static final Color GREEN = new Color(30, 170, 60);
    static final Color RED = new Color(190, 30, 30);
    static final int GREY = 0xFF808080;

    /**
     * The shared raster hash vector: a 3 x 2 opaque raster. The same six pixels and the same hash literal are the
     * controller's {@code ImageCompareTest.VECTOR} and {@code VECTOR_HASH} ({@code skillroy-scenario}), so that
     * {@link Capture#hash} and {@code ImageCompare.rasterHash} are proven to agree (fix wave of the Phase 7 review, FA,
     * L3-F1).
     */
    static final int[] VECTOR = {0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFF102030, 0xFFFF0000, 0xFF00FF00};
    static final String VECTOR_HASH = "sha256:6be118c38e93cd2b790c98897d10e69afa4ea5b1248c59203b1c2633a884c4ba";

    private FakeContext ctx;
    private Windows windows;

    @BeforeEach
    void setUp() {
        ctx = new FakeContext();
        windows = new Windows();
    }

    @AfterEach
    void tearDown() {
        Capture.heap = Capture.RUNTIME_HEAP;
        ctx.shutdown();
    }

    static JPanel panel(Color colour, int x, int y, int w, int h) {
        JPanel p = new JPanel(null);
        p.setOpaque(true);
        p.setBackground(colour);
        p.setBounds(x, y, w, h);
        return p;
    }

    private Capture.Shot take(Component target, Capture.Request req) throws Exception {
        return onEdt(() -> Capture.take(new ComponentView(ctx), windows, new SwingSpis(ctx), target, req, 7L));
    }

    private static Capture.Request req(Capture.Scope scope) {
        Capture.Request r = new Capture.Request();
        r.scope = scope;
        return r;
    }

    private static Color at(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y) & 0xffffff);
    }

    @Test
    void theRasterHashIsTheSharedVector() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < VECTOR.length; i++) {
            image.setRGB(i % 3, i / 3, VECTOR[i]);
        }
        assertEquals(VECTOR_HASH, Capture.hash(image));
    }

    @Test
    void aComponentScopeLiesAtItsWindowOffsetAndScales() throws Exception {
        JPanel root = panel(BACK, 0, 0, 400, 300);
        JPanel child = panel(GREEN, 50, 40, 100, 80);
        child.add(panel(RED, 10, 10, 20, 20));
        root.add(child);
        Capture.Shot shot = take(child, req(null));
        assertEquals(100, shot.image.getWidth());
        assertEquals(80, shot.image.getHeight());
        assertEquals("COMPONENT", shot.fields.get("scope"));
        assertEquals("WINDOW", shot.fields.get("space"));
        assertEquals(map("x", 50L, "y", 40L), shot.fields.get("origin"));
        assertEquals(1.0, shot.fields.get("scale"));
        assertEquals(7L, shot.fields.get("seq"));
        assertEquals(RED, at(shot.image, 15, 15));
        // the mapping rule: pixel (px, py) lies at origin + p / scale
        assertEquals(new Point(60, 50), shot.geometry.toSpace(10, 10));

        Capture.Request twice = req(null);
        twice.scale = 2.0;
        Capture.Shot big = take(child, twice);
        assertEquals(200, big.image.getWidth());
        assertEquals(160, big.image.getHeight());
        assertEquals(RED, at(big.image, 25, 25));
        assertEquals(new Point(60, 50), big.geometry.toSpace(20, 20));
    }

    @Test
    void theCapLowersTheScaleAndTheResultSaysSo() throws Exception {
        JPanel wide = panel(BACK, 0, 0, 2000, 10);
        Capture.Request r = req(null);
        r.scale = 4.0;
        Capture.Shot shot = take(wide, r);
        assertEquals(4096, shot.image.getWidth());
        assertEquals(4096.0 / 2000, (Double) shot.fields.get("scale"), 1e-9);
    }

    @Test
    void aRegionIsClippedToTheTargetAndAnEmptyOneIsRefused() throws Exception {
        JPanel root = panel(BACK, 0, 0, 400, 300);
        JPanel child = panel(GREEN, 50, 40, 100, 80);
        child.add(panel(RED, 10, 10, 20, 20));
        root.add(child);
        Capture.Request r = req(Capture.Scope.REGION);
        r.region = new Rectangle(10, 10, 30, 20);
        Capture.Shot shot = take(child, r);
        assertEquals(30, shot.image.getWidth());
        assertEquals(20, shot.image.getHeight());
        assertEquals(map("x", 60L, "y", 50L), shot.fields.get("origin"));
        assertEquals(RED, at(shot.image, 0, 0));
        assertEquals(GREEN, at(shot.image, 25, 15));

        r.region = new Rectangle(90, 70, 50, 50);
        Capture.Shot clipped = take(child, r);
        assertEquals(10, clipped.image.getWidth(), "clipped to the target");

        r.region = new Rectangle(500, 500, 10, 10);
        RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> take(child, r));
        assertEquals("outside", e.error().data().get("reason"));
    }

    @Test
    void aRegionIdIsResolvedAndAnUnknownOneIsNotFound() throws Exception {
        JPanel pad = panel(GREEN, 0, 0, 90, 30);
        ctx.spi.register(RegionResolver.class, new ThreeKeys(pad));
        Capture.Request r = req(Capture.Scope.REGION);
        r.regionId = "key:2";
        Capture.Shot shot = take(pad, r);
        assertEquals(30, shot.image.getWidth());
        assertEquals(map("id", "key:2", "rect", map("x", 30L, "y", 0L, "w", 30L, "h", 30L)),
                shot.fields.get("region"));
        r.regionId = "key:9";
        RpcException e = expectError(ErrorCodes.NOT_FOUND, () -> take(pad, r));
        assertEquals("key:9", e.error().data().get("region"));
    }

    @Test
    void theHeapBoundRefusesARasterBeyondAnEighthOfTheFreeHeap() throws Exception {
        JPanel big = panel(BACK, 0, 0, 1024, 1024);
        Capture.heap = () -> 16L << 20; // 16 MB free: an eighth is 2 MB, the raster needs 4 MB
        RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> take(big, req(null)));
        assertEquals(4L, e.error().data().get("neededMb"));
        assertEquals(16L, e.error().data().get("freeMb"));
        assertTrue(e.getMessage().contains("4 MB") && e.getMessage().contains("16 MB"), e.getMessage());
        Capture.heap = () -> 64L << 20;
        assertEquals(1024, take(big, req(null)).image.getWidth(), "8 MB is an eighth of 64 MB");
    }

    @Test
    void anOutOfMemoryErrorIsAnsweredAsAnInternalError() throws Exception {
        JComponent hungry = new JComponent() {
            private static final long serialVersionUID = 1L;

            @Override
            public void paint(Graphics g) {
                throw new OutOfMemoryError("test: no room");
            }
        };
        hungry.setBounds(0, 0, 50, 50);
        RpcException e = expectError(ErrorCodes.INTERNAL_ERROR, () -> take(hungry, req(null)));
        assertEquals("out of memory", e.error().data().get("reason"));
    }

    @Test
    void marksFollowTheRulesAndLeaveThePixelsUntouched() throws Exception {
        JPanel root = panel(BACK, 0, 0, 600, 400);
        JButton second = new JButton("Second");
        second.setBounds(300, 10, 100, 30);
        JButton first = new JButton("First");
        first.setBounds(10, 10, 100, 30);
        JLabel text = new JLabel("A label");
        text.setBounds(10, 60, 100, 20);
        JLabel empty = new JLabel("");
        empty.setBounds(200, 60, 100, 20);
        JTable table = new JTable(new Object[][] {{"a", "1"}, {"b", "2"}, {"c", "3"}}, new Object[] {"Item", "Due"});
        table.setRowHeight(20);
        table.setBounds(10, 100, 300, 60);
        JPanel pad = panel(GREEN, 400, 100, 90, 30);
        ctx.spi.register(RegionResolver.class, new ThreeKeys(pad));
        JTextField secret = new JTextField("hidden");
        secret.setName("secretField");
        secret.setBounds(10, 200, 100, 24);
        ctx.spi.register(CaptureMask.class, c -> c == secret);
        for (Component c : Arrays.asList(second, first, text, empty, table, pad, secret)) {
            root.add(c);
        }
        Capture.Request plain = req(null);
        Capture.Request marked = req(null);
        marked.annotate = true;
        Capture.Shot without = take(root, plain);
        Capture.Shot with = take(root, marked);
        assertEquals(Capture.hash(without.image), Capture.hash(with.image), "the agent draws nothing (D346)");
        List<Object> marks = arr(with.fields.get("marks"));
        List<String> labels = new ArrayList<String>();
        for (Object o : marks) {
            labels.add((String) obj(o).get("label"));
        }
        assertEquals(Arrays.asList("button 'First'", "button 'Second'", "label 'A label'",
                "row 0: a | 1", "region key:1", "region key:2", "region key:3", "row 1: b | 2", "row 2: c | 3"),
                labels, "reading order; no mark for an empty label or a masked field");
        Map<String, Object> firstMark = obj(marks.get(0));
        assertEquals(1L, firstMark.get("mark"));
        assertEquals(map("x", 10L, "y", 10L, "w", 100L, "h", 30L), firstMark.get("rect"));
        assertEquals("button", firstMark.get("role"));
        Map<String, Object> row = obj(marks.get(3));
        assertEquals(0L, row.get("row"));
        assertEquals(map("x", 10L, "y", 100L, "w", 300L, "h", 20L), row.get("rect"));
        Map<String, Object> region = obj(marks.get(5));
        assertEquals("key:2", region.get("region"));
        assertEquals(map("x", 430L, "y", 100L, "w", 30L, "h", 30L), region.get("rect"));
        assertNull(with.fields.get("marksTruncated"));
    }

    @Test
    void atMostTwoHundredMarks() throws Exception {
        JPanel root = panel(BACK, 0, 0, 2500, 1000);
        for (int i = 0; i < 250; i++) {
            JButton b = new JButton("b" + i);
            b.setBounds((i % 25) * 100, (i / 25) * 100, 90, 30);
            root.add(b);
        }
        Capture.Request r = req(null);
        r.annotate = true;
        Capture.Shot shot = take(root, r);
        assertEquals(200, arr(shot.fields.get("marks")).size());
        assertEquals(Boolean.TRUE, shot.fields.get("marksTruncated"));
    }

    @Test
    void passwordFieldsAndMaskedComponentsAreBlankedGreyAndReported() throws Exception {
        JPanel root = panel(BACK, 0, 0, 300, 200);
        JPasswordField password = new JPasswordField("hunter2");
        password.setBounds(10, 10, 100, 24);
        JTextField masked = new JTextField("a secret");
        masked.setBounds(10, 50, 100, 24);
        JTextField open = new JTextField("open");
        open.setBounds(10, 90, 100, 24);
        root.add(password);
        root.add(masked);
        root.add(open);
        ctx.spi.register(CaptureMask.class, c -> c == masked);
        Capture.Shot shot = take(root, req(null));
        assertEquals(GREY, shot.image.getRGB(50, 20));
        assertEquals(GREY, shot.image.getRGB(50, 60));
        assertNotEquals(GREY, shot.image.getRGB(50, 100), "an unmasked field is painted");
        List<Object> redacted = arr(shot.fields.get("redacted"));
        assertEquals(2, redacted.size());
        assertEquals("password-field", obj(redacted.get(0)).get("rule"));
        assertEquals(map("x", 10L, "y", 10L, "w", 100L, "h", 24L), obj(redacted.get(0)).get("rect"));
        assertEquals("capture-mask", obj(redacted.get(1)).get("rule"));

        Capture.Request open2 = req(null);
        open2.redact = false;
        Capture.Shot unredacted = take(root, open2);
        assertNotEquals(GREY, unredacted.image.getRGB(50, 60), "redact: false leaves everything in");
        assertEquals(0, arr(unredacted.fields.get("redacted")).size());
    }

    @Test
    void aMaskedTextFieldHasNoTextNoMarkNoTextLocatorAndIsMasked() throws Exception {
        JPanel root = panel(BACK, 0, 0, 300, 200);
        JTextField field = new JTextField("100");
        field.setName("entry");
        field.setBounds(10, 10, 100, 24);
        root.add(field);
        ctx.spi.register(CaptureMask.class, c -> c == field);
        Map<String, Object> node = onEdt(() -> new ComponentView(ctx).node(field));
        assertNull(node.get("text"), "no text for a masked component (D349)");
        assertEquals(Boolean.TRUE, node.get("masked"));
        assertEquals("textbox", node.get("role"), "the role is unchanged");
        List<Object> locators = onEdt(() -> HitTest.locators(new ComponentView(ctx), new SwingSpis(ctx), field,
                HitTest.MAX_LOCATORS, root, true));
        for (Object l : locators) {
            assertFalse(obj(l).containsKey("text"), "no {text, role} candidate: " + locators);
        }
        assertEquals(map("id", "entry"), locators.get(0));
        Capture.Request r = req(null);
        r.annotate = true;
        assertEquals(0, arr(take(root, r).fields.get("marks")).size(), "no mark on a masked component");
        assertEquals(GREY, take(root, r).image.getRGB(50, 20));
    }

    @Test
    void aThrowingMaskBlanksTheComponentAndWithholdsItsText() throws Exception {
        JPanel root = panel(BACK, 0, 0, 300, 200);
        JTextField field = new JTextField("visible?");
        field.setBounds(10, 10, 100, 24);
        root.add(field);
        ctx.spi.register(CaptureMask.class, c -> {
            throw new IllegalStateException("mask broken");
        });
        assertNull(onEdt(() -> new ComponentView(ctx).node(field)).get("text"));
        assertEquals(GREY, take(root, req(null)).image.getRGB(50, 20));
    }

    @Test
    void aMaskMayBlankRegionsOfAComponentInsteadOfTheWhole() throws Exception {
        JPanel root = panel(BACK, 0, 0, 300, 200);
        JPanel cells = panel(GREEN, 10, 10, 200, 100);
        root.add(cells);
        ctx.spi.register(CaptureMask.class, new CaptureMask() {
            @Override
            public boolean masks(Component component) {
                return false;
            }

            @Override
            public List<Rectangle> regions(Component component) {
                return component == cells ? Arrays.asList(new Rectangle(0, 0, 50, 20)) : null;
            }
        });
        Capture.Shot shot = take(root, req(null));
        assertEquals(GREY, shot.image.getRGB(20, 15));
        assertEquals(GREEN, at(shot.image, 100, 80));
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-F1): a capture whose paint root lies inside a component a mask names (a
     * {@code COMPONENT} or {@code REGION} capture of a child of a masked container) is blanked by the ancestor's rule
     * and reports it under the ancestor's ref; nothing inside it is marked.
     */
    @Test
    void aCaptureInsideAMaskedContainerIsBlankedByTheContainersMask() throws Exception {
        JPanel root = panel(BACK, 0, 0, 400, 300);
        JPanel parent = panel(GREEN, 20, 20, 200, 100);
        JPanel child = panel(RED, 20, 20, 60, 40);
        JButton inner = new JButton("Inner");
        inner.setBounds(5, 5, 40, 20);
        child.add(inner);
        parent.add(child);
        root.add(parent);
        ctx.spi.register(CaptureMask.class, c -> c == parent);
        String parentRef = ctx.handles.handleFor(parent, ComponentView.KIND);

        Capture.Request component = req(null);
        component.annotate = true;
        Capture.Shot shot = take(child, component);
        assertEquals(GREY, shot.image.getRGB(10, 10), "the child of a masked panel is grey");
        assertEquals(GREY, shot.image.getRGB(55, 35));
        List<Object> redacted = arr(shot.fields.get("redacted"));
        assertEquals(1, redacted.size(), redacted.toString());
        assertEquals(parentRef, obj(redacted.get(0)).get("ref"), "reported under the masking ancestor");
        assertEquals("capture-mask", obj(redacted.get(0)).get("rule"));
        assertEquals(map("x", 0L, "y", 0L, "w", 60L, "h", 40L), obj(redacted.get(0)).get("rect"));
        assertEquals(0, arr(shot.fields.get("marks")).size(), "nothing inside a masked component is marked");

        Capture.Request region = req(Capture.Scope.REGION);
        region.region = new Rectangle(10, 10, 30, 20);
        Capture.Shot part = take(child, region);
        assertEquals(GREY, part.image.getRGB(2, 2), "a region of the child likewise");
        assertEquals(1, arr(part.fields.get("redacted")).size());

        Capture.Request open = req(null);
        open.redact = false;
        assertEquals(RED, at(take(child, open).image, 55, 35), "a person's redact: false leaves it in");
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-F1 restated by its sceptic, probe D): an ancestor's mask {@code regions} (the
     * table-cell form, {@code masks} false) blank the part of a descendant's capture that lies inside them.
     */
    @Test
    void anAncestorsMaskRegionsBlankADescendantsCapture() throws Exception {
        JPanel root = panel(BACK, 0, 0, 400, 300);
        JPanel parent = panel(GREEN, 20, 20, 200, 100);
        JPanel child = panel(RED, 20, 20, 60, 40);
        parent.add(child);
        root.add(parent);
        ctx.spi.register(CaptureMask.class, new CaptureMask() {
            @Override
            public boolean masks(Component component) {
                return false;
            }

            @Override
            public List<Rectangle> regions(Component component) {
                return component == parent ? Arrays.asList(new Rectangle(20, 20, 40, 30)) : null;
            }
        });
        Capture.Shot shot = take(child, req(null));
        assertEquals(GREY, shot.image.getRGB(10, 10), "inside the ancestor's region");
        assertEquals(RED, at(shot.image, 50, 35), "outside it");
        List<Object> redacted = arr(shot.fields.get("redacted"));
        assertEquals(1, redacted.size(), redacted.toString());
        assertEquals(ctx.handles.handleFor(parent, ComponentView.KIND), obj(redacted.get(0)).get("ref"));
        assertEquals(map("x", 0L, "y", 0L, "w", 40L, "h", 30L), obj(redacted.get(0)).get("rect"));
    }

    /**
     * Fix wave (Phase 7 review, FA, L2-F1 case b): a mask that names the window, whose root pane is the paint root
     * (the stand-ins of the window-list seam: a panel for the window, its child for the root pane), blanks the whole
     * layer of a {@code SCREEN} capture.
     */
    @Test
    void aMaskOnTheWindowBlanksItsLayer() throws Exception {
        JPanel window = panel(GREEN, 0, 0, 200, 100);
        JPanel pane = panel(RED, 0, 0, 200, 100);
        JButton button = new JButton("In the masked window");
        button.setBounds(10, 10, 150, 30);
        pane.add(button);
        window.add(pane);
        JPanel other = panel(BACK, 0, 0, 100, 100);
        windows.source(() -> Arrays.asList(
                new Windows.Layer(other, other, new Rectangle(300, 100, 100, 100), new Point(0, 0), false),
                new Windows.Layer(window, pane, new Rectangle(100, 100, 200, 100), new Point(0, 0), true)));
        ctx.spi.register(CaptureMask.class, c -> c == window);
        Capture.Request r = req(Capture.Scope.SCREEN);
        r.annotate = true;
        Capture.Shot shot = take(null, r);
        assertEquals(GREY, shot.image.getRGB(50, 50), "the masked window's layer is grey");
        assertEquals(GREY, shot.image.getRGB(20, 20));
        assertEquals(BACK, at(shot.image, 250, 50), "the other window is painted");
        List<Object> redacted = arr(shot.fields.get("redacted"));
        assertEquals(1, redacted.size(), redacted.toString());
        assertEquals(ctx.handles.handleFor(window, ComponentView.KIND), obj(redacted.get(0)).get("ref"));
        assertEquals(map("x", 0L, "y", 0L, "w", 200L, "h", 100L), obj(redacted.get(0)).get("rect"));
        for (Object m : arr(shot.fields.get("marks"))) {
            assertFalse(String.valueOf(obj(m).get("label")).contains("masked window"), "no mark: " + m);
        }
    }

    @Test
    void theFocusedFieldsCaretIsReported() throws Exception {
        JPanel root = panel(BACK, 0, 0, 300, 200);
        JTextField focused = new JTextField("abc") {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean hasFocus() {
                return true;
            }
        };
        focused.setBounds(20, 30, 120, 24);
        root.add(focused);
        onEdt(() -> {
            focused.setCaretPosition(3);
            return null;
        });
        Capture.Shot shot = take(root, req(null));
        List<Object> carets = arr(shot.fields.get("carets"));
        assertEquals(1, carets.size());
        Map<String, Object> caret = obj(carets.get(0));
        assertEquals(ctx.handles.handleFor(focused, ComponentView.KIND), caret.get("ref"));
        Map<String, Object> rect = obj(caret.get("rect"));
        assertTrue((Long) rect.get("x") > 20 && (Long) rect.get("x") < 140, rect.toString());
        assertTrue((Long) rect.get("y") >= 30, rect.toString());
        Map<String, Object> focus = obj(shot.fields.get("focus"));
        assertTrue(focus.containsKey("ownerLocator") && focus.containsKey("ownerRect") && focus.containsKey("active"));

        onEdt(() -> {
            focused.select(0, 2);
            return null;
        });
        assertEquals(0, arr(take(root, req(null)).fields.get("carets")).size(), "a selection has no caret entry");
    }

    @Test
    void theFirstUniqueLocatorOfAFieldIsItsId() throws Exception {
        JPanel root = panel(BACK, 0, 0, 300, 200);
        JTextField field = new JTextField("x");
        field.setName("entry");
        field.setBounds(0, 0, 50, 20);
        root.add(field);
        assertEquals(map("id", "entry"), onEdt(() -> HitTest.firstUniqueLocator(new ComponentView(ctx),
                new SwingSpis(ctx), field, root, true)));
    }

    @Test
    void theScreenScopeCompositesTheWindowsInStackingOrder() throws Exception {
        JPanel bottom = panel(GREEN, 0, 0, 200, 100);
        JButton under = new JButton("Under");
        under.setBounds(90, 70, 60, 20);
        bottom.add(under);
        JButton visible = new JButton("Visible");
        visible.setBounds(5, 5, 60, 20);
        bottom.add(visible);
        JPanel top = panel(RED, 0, 0, 100, 100);
        windows.source(() -> Arrays.asList(
                new Windows.Layer(bottom, bottom, new Rectangle(100, 100, 200, 100), new Point(0, 0), false),
                new Windows.Layer(top, top, new Rectangle(150, 150, 100, 100), new Point(0, 0), true)));
        Capture.Request r = req(Capture.Scope.SCREEN);
        r.annotate = true;
        Capture.Shot shot = take(null, r);
        assertEquals(200, shot.image.getWidth());
        assertEquals(150, shot.image.getHeight());
        assertEquals("SCREEN", shot.fields.get("space"));
        assertEquals(map("x", 100L, "y", 100L), shot.fields.get("origin"));
        assertEquals(Boolean.TRUE, shot.fields.get("stackingAssumed"));
        assertNull(shot.fields.get("window"));
        List<Object> ws = arr(shot.fields.get("windows"));
        assertEquals(2, ws.size());
        assertEquals(map("x", 50L, "y", 50L, "w", 100L, "h", 100L), obj(ws.get(1)).get("rect"));
        assertEquals(RED, at(shot.image, 60, 60), "the top window is painted last");
        assertEquals(GREEN, at(shot.image, 120, 10));
        assertEquals(Capture.SCREEN_BACKGROUND, at(shot.image, 10, 140), "outside every window");
        List<Object> marks = arr(shot.fields.get("marks"));
        assertEquals(1, marks.size(), "the covered button has no mark: " + marks);
        assertEquals("button 'Visible'", obj(marks.get(0)).get("label"));
    }

    // ------------------------------------------------------------------- Phase 7 security fix (SG, 2026-10-11)

    private void source(final Component browser) {
        ctx.spi.register(SurfaceSource.class, () -> Collections.singletonList(new SurfaceSource.Surface(browser,
                "WEB", "b1")));
    }

    private static Map<String, Object> webSurface(Capture.Shot shot) {
        Map<String, Object> found = null;
        for (Object o : arr(shot.fields.get("surfaces"))) {
            if ("WEB".equals(obj(o).get("kind"))) {
                found = obj(o);
            }
        }
        return found;
    }

    /**
     * Security fix S129 (L4-F1; the L4 sceptic's probe case B): a browser in a {@code CardLayout} card that is not
     * shown keeps its own {@code isVisible()}, but is no surface: a surface is listed only for a component that is
     * showing (visible at every level up to its window).
     */
    @Test
    void aBrowserInsideAHiddenCardIsNoSurface() throws Exception {
        JPanel frame = panel(GREEN, 0, 0, 200, 100);
        JPanel deck = new JPanel(new CardLayout());
        deck.setBounds(0, 0, 200, 100);
        JPanel front = panel(BACK, 0, 0, 200, 100);
        JPanel back = new JPanel(null);
        JPanel browser = panel(Color.BLUE, 0, 30, 200, 70);
        back.add(browser);
        deck.add(front, "front");
        deck.add(back, "back");
        frame.add(deck);
        onEdt(() -> {
            deck.doLayout();
            ((CardLayout) deck.getLayout()).show(deck, "front");
            return null;
        });
        assertTrue(browser.isVisible() && !back.isVisible(), "the browser's own isVisible() is true");
        source(browser);
        windows.source(() -> Collections.singletonList(
                new Windows.Layer(frame, frame, new Rectangle(100, 100, 200, 100), new Point(0, 0), true)));
        Capture.Request r = req(Capture.Scope.SCREEN);
        assertNull(webSurface(take(null, r)), "a browser in a hidden card is no surface");
        onEdt(() -> {
            ((CardLayout) deck.getLayout()).show(deck, "back");
            return null;
        });
        Map<String, Object> shown = webSurface(take(null, r));
        assertEquals(map("x", 0L, "y", 30L, "w", 200L, "h", 70L), shown.get("rect"), "once its card is shown");
        assertEquals(list(), shown.get("covered"));
    }

    /**
     * Security fix S108 (L4-S1; the L4 sceptic's probe case A): a surface's {@code covered} lists, in image
     * coordinates, what is painted above it: a window above its own in the composite, and in its own root a component
     * Swing paints later (a popup in the layered pane's popup layer over the content).
     */
    @Test
    void aSurfaceCarriesTheRectanglesPaintedAboveIt() throws Exception {
        JPanel frame = panel(GREEN, 0, 0, 200, 150);
        JLayeredPane layers = new JLayeredPane();
        layers.setBounds(0, 0, 200, 150);
        JPanel content = panel(Color.WHITE, 0, 0, 200, 150);
        JPanel browser = panel(Color.BLUE, 10, 10, 180, 100);
        content.add(browser);
        layers.add(content, JLayeredPane.DEFAULT_LAYER);
        JPanel popup = panel(RED, 50, 40, 60, 30);
        layers.add(popup, JLayeredPane.POPUP_LAYER);
        frame.add(layers);
        JPanel dialog = panel(RED, 0, 0, 100, 100);
        JButton yes = new JButton("Yes, return the loan");
        yes.setBounds(10, 10, 80, 20);
        dialog.add(yes);
        source(browser);
        windows.source(() -> Arrays.asList(
                new Windows.Layer(frame, frame, new Rectangle(100, 100, 200, 150), new Point(0, 0), true),
                new Windows.Layer(dialog, dialog, new Rectangle(250, 200, 100, 100), new Point(0, 0), true)));
        Capture.Request r = req(Capture.Scope.SCREEN);
        r.annotate = true;
        Capture.Shot shot = take(null, r);
        Map<String, Object> surface = webSurface(shot);
        assertEquals(map("x", 10L, "y", 10L, "w", 180L, "h", 100L), surface.get("rect"));
        assertEquals(list(map("x", 150L, "y", 100L, "w", 40L, "h", 10L), map("x", 50L, "y", 40L, "w", 60L,
                "h", 30L)), surface.get("covered"), "the dialog above in the composite, then the popup above it");
        boolean marked = false;
        for (Object m : arr(shot.fields.get("marks"))) {
            marked |= String.valueOf(obj(m).get("label")).contains("return the loan");
        }
        assertTrue(marked, "the dialog's button keeps its mark inside the surface's rectangle");
    }

    /**
     * Security fix S108 (the L4 sceptic's probe case C): a surface's {@code rect} is the browser's visible part, so a
     * browser a viewport has scrolled partly out is clipped to the viewport, and one scrolled wholly out is no surface
     * at all (before, its whole bounds lay over the panel below the viewport).
     */
    @Test
    void aSurfaceIsTheVisiblePartOfTheBrowser() throws Exception {
        JPanel frame = panel(GREEN, 0, 0, 200, 150);
        JViewport viewport = new JViewport();
        viewport.setBounds(0, 0, 200, 60);
        JPanel form = panel(Color.WHITE, 0, 0, 180, 300);
        JPanel browser = panel(Color.BLUE, 0, 120, 180, 150);
        form.add(browser);
        viewport.setView(form);
        frame.add(viewport);
        JPanel bar = panel(Color.LIGHT_GRAY, 0, 60, 200, 90);
        JButton waive = new JButton("Waive deposit");
        waive.setBounds(10, 10, 120, 20);
        bar.add(waive);
        frame.add(bar);
        source(browser);
        windows.source(() -> Collections.singletonList(
                new Windows.Layer(frame, frame, new Rectangle(100, 100, 200, 150), new Point(0, 0), true)));
        onEdt(() -> {
            form.setBounds(0, 0, 180, 300);
            viewport.setViewPosition(new Point(0, 0));
            return null;
        });
        Capture.Request r = req(Capture.Scope.SCREEN);
        assertNull(webSurface(take(null, r)), "scrolled wholly out of the viewport: no surface over the bar below");
        onEdt(() -> {
            viewport.setViewPosition(new Point(0, 100));
            return null;
        });
        assertEquals(map("x", 0L, "y", 20L, "w", 180L, "h", 40L), webSurface(take(null, r)).get("rect"),
                "the part the viewport shows (rows 120..160 of the form at view position 100)");
    }

    /**
     * Security fix S122 (L2-F4; the L2 reviewer's probe 2): a mask's {@code regions} withhold the text under them,
     * with {@code masks} false: a label a region holds is masked (no mark, no text, {@code masked: true}); a table's
     * cells under a region are left out of its row marks and nulled in its value, its other cells stay.
     */
    @Test
    void aRegionsOnlyMaskWithholdsTheTextUnderItsRegions() throws Exception {
        JPanel root = panel(BACK, 0, 0, 320, 120);
        JLabel label = new JLabel("PIN 4711");
        label.setBounds(10, 10, 120, 20);
        root.add(label);
        JTable table = new JTable(new Object[][] {{"Ann", "4711"}, {"Bo", "0815"}}, new Object[] {"Name", "PIN"});
        table.setRowHeight(20);
        table.setBounds(10, 40, 220, 40);
        root.add(table);
        Rectangle pinColumn = onEdt(() -> table.getCellRect(0, 1, true).union(table.getCellRect(1, 1, true)));
        ctx.spi.register(CaptureMask.class, new CaptureMask() {
            @Override
            public boolean masks(Component component) {
                return false;
            }

            @Override
            public List<Rectangle> regions(Component component) {
                if (component == root) {
                    return Arrays.asList(new Rectangle(10, 10, 120, 20));
                }
                return component == table ? Arrays.asList(pinColumn) : null;
            }
        });
        Capture.Request r = req(null);
        r.annotate = true;
        Capture.Shot shot = take(root, r);
        assertEquals(GREY, shot.image.getRGB(15, 15), "the label's pixels are blanked as before");
        List<String> labels = new ArrayList<String>();
        for (Object m : arr(shot.fields.get("marks"))) {
            labels.add(String.valueOf(obj(m).get("label")));
        }
        assertEquals(Arrays.asList("row 0: Ann | ", "row 1: Bo | "), labels, "no mark on the label, no PIN cell");
        Map<String, Object> labelNode = onEdt(() -> new ComponentView(ctx).node(label));
        assertNull(labelNode.get("text"));
        assertNull(labelNode.get("name"), "the name a label takes from its text is withheld with it (S106)");
        assertEquals(Boolean.TRUE, labelNode.get("masked"));
        Map<String, Object> value = obj(onEdt(() -> new ComponentView(ctx).node(table)).get("value"));
        assertEquals(Arrays.asList(Arrays.asList("Ann", null), Arrays.asList("Bo", null)), value.get("cells"));
        assertFalse(String.valueOf(onEdt(() -> new ComponentView(ctx).node(table))).contains("4711"));
        assertTrue(onEdt(() -> new ComponentView(ctx).masked(label)), "the hit-test's masked follows the node's");
    }

    /**
     * Security fix S123 (L2-S2; the L2 sceptic's probe B): two masks combine by union, so one that gives regions for a
     * component does not cancel another's whole-component blanking of it.
     */
    @Test
    void twoMasksCombineByUnion() throws Exception {
        JPanel root = panel(new Color(240, 240, 240), 0, 0, 400, 200);
        JPanel secret = panel(new Color(200, 30, 30), 10, 10, 300, 100);
        JPanel child = panel(new Color(30, 160, 30), 150, 50, 100, 40);
        secret.add(child);
        root.add(secret);
        ctx.spi.register(CaptureMask.class, c -> c == secret);
        ctx.spi.register(CaptureMask.class, new CaptureMask() {
            @Override
            public boolean masks(Component component) {
                return false;
            }

            @Override
            public List<Rectangle> regions(Component component) {
                return component == secret ? Arrays.asList(new Rectangle(0, 0, 40, 20)) : null;
            }
        });
        Capture.Shot shot = take(root, req(Capture.Scope.COMPONENT));
        assertEquals(GREY, shot.image.getRGB(12, 12), "in the second mask's region");
        assertEquals(GREY, shot.image.getRGB(100, 80), "the panel outside that region");
        assertEquals(GREY, shot.image.getRGB(165, 65), "the child");
        List<Object> redacted = arr(shot.fields.get("redacted"));
        assertEquals(1, redacted.size(), redacted.toString());
        assertEquals(map("x", 10L, "y", 10L, "w", 300L, "h", 100L), obj(redacted.get(0)).get("rect"));
    }

    /**
     * Security fix S125 (L2-S4; the L2 sceptic's probe C, scaled down): a high-entropy raster's PNG is about its size,
     * and what delivering it allocates (base64, the reply's text and bytes) is counted: a PNG whose six-fold exceeds
     * half the heap headroom is refused with both figures, while the raster bound admitted the raster.
     */
    @Test
    void theEncodingBoundRefusesAPngWhoseDeliveryExceedsHalfTheFreeHeap() throws Exception {
        BufferedImage noise = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                noise.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        Capture.heap = () -> 8L << 20; // the raster's 1 MB is an eighth of 8 MB: admitted by the raster bound
        Capture.heapBound(4L * 512 * 512, "scale", "lower 'scale'");
        RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> Capture.encode(noise));
        assertEquals(8L, e.error().data().get("freeMb"));
        assertTrue((Long) e.error().data().get("neededMb") > 4L, String.valueOf(e.error().data()));
        Capture.heap = () -> 64L << 20;
        assertTrue(Capture.encode(noise).length > 700000, "admitted with more headroom; noise does not compress");
    }

    /**
     * Security fix S146 (L8-F2): the focus owner's locator ({@code focus.ownerLocator}) is never a {@code {text}}
     * candidate, so a nameless text field's content never enters a baseline's or an anchor's {@code focus.owner}; the
     * hit-test's own candidates are unchanged.
     */
    @Test
    void theFocusOwnersLocatorIsNeverItsText() throws Exception {
        JPanel root = panel(BACK, 0, 0, 300, 200);
        JTextField field = new JTextField("Jane Doe");
        field.setBounds(10, 10, 120, 24);
        root.add(field);
        Map<String, Object> owner = onEdt(() -> HitTest.firstUniqueLocator(new ComponentView(ctx), new SwingSpis(ctx),
                field, root, true, false));
        assertEquals(map("class", "JTextField", "index", 0L), owner);
        assertEquals(map("text", "Jane Doe", "role", "textbox"), onEdt(() -> HitTest.firstUniqueLocator(
                new ComponentView(ctx), new SwingSpis(ctx), field, root, true)), "the hit-test's order keeps it");
    }

    /** Three 30-pixel keys in a row: {@code key:1} to {@code key:3}. */
    static final class ThreeKeys implements RegionResolver {
        private final Component pad;

        ThreeKeys(Component pad) {
            this.pad = pad;
        }

        @Override
        public boolean supports(Component component) {
            return component == pad;
        }

        @Override
        public Region regionAt(Component component, Point point) {
            int i = point.x / 30;
            return i >= 0 && i < 3 ? new Region("key:" + (i + 1), new Rectangle(i * 30, 0, 30, 30)) : null;
        }

        @Override
        public Rectangle rectOf(Component component, String id) {
            for (int i = 0; i < 3; i++) {
                if (("key:" + (i + 1)).equals(id)) {
                    return new Rectangle(i * 30, 0, 30, 30);
                }
            }
            return null;
        }

        @Override
        public List<Region> regions(Component component) {
            List<Region> out = new ArrayList<Region>();
            for (int i = 0; i < 3; i++) {
                out.add(new Region("key:" + (i + 1), new Rectangle(i * 30, 0, 30, 30)));
            }
            return out;
        }
    }
}
