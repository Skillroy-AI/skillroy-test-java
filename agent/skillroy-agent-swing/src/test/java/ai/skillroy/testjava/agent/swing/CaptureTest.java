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
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTable;
import javax.swing.JTextField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.arr;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
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

    /** The shared raster hash vector: a 3 x 2 opaque raster (also in the controller's {@code ImageCompareTest}). */
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
