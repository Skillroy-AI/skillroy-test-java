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

import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JPanel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.arr;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hidden-window predicate without a display (phase7-contracts.md section 3.4, the point bullet; Phase 7 security
 * fix, S104), through the window-list seam {@code Windows.Source} and the state seam {@code Windows.State}, whose
 * stand-ins say which window is an iconified frame and who owns whom (a headless JVM cannot create a {@code Frame}):
 * an iconified frame and every window it owns, directly or transitively, are left out of the stacking, and so of the
 * {@code SCREEN} composite and of the glass capture's window rectangles; a window owned by a frame that is not
 * iconified stays. The Robot refusal {@code NOT_FOUND iconified} on real windows is {@code RobotPathDisplayTest}'s.
 */
class WindowsTest {

    private FakeContext ctx;
    private Windows windows;
    private JPanel iconified;
    private JPanel ownedDialog;
    private JPanel ownedByTheDialog;
    private JPanel main;
    private JPanel mainsDialog;

    @BeforeEach
    void setUp() {
        ctx = new FakeContext();
        windows = new Windows();
        iconified = CaptureTest.panel(CaptureTest.RED, 0, 0, 200, 100);
        JButton button = new JButton("In the minimised frame");
        button.setBounds(10, 10, 150, 30);
        iconified.add(button);
        ownedDialog = CaptureTest.panel(CaptureTest.RED, 0, 0, 80, 60);
        ownedByTheDialog = CaptureTest.panel(CaptureTest.RED, 0, 0, 40, 30);
        main = CaptureTest.panel(CaptureTest.GREEN, 0, 0, 200, 100);
        mainsDialog = CaptureTest.panel(CaptureTest.BACK, 0, 0, 60, 40);
        final Map<Component, Component> owners = new IdentityHashMap<Component, Component>();
        owners.put(ownedDialog, iconified);
        owners.put(ownedByTheDialog, ownedDialog);
        owners.put(mainsDialog, main);
        Windows.state = new Windows.State() {
            @Override
            public boolean iconified(Component window) {
                return window == iconified;
            }

            @Override
            public Component owner(Component window) {
                return owners.get(window);
            }
        };
        // the stacking's own order: the minimised frame's last position overlaps the main frame, and its owned
        // dialog would be placed in the owned tier above the active frame
        windows.source(() -> Arrays.asList(
                new Windows.Layer(iconified, iconified, new Rectangle(500, 100, 200, 100), new Point(0, 0), false),
                new Windows.Layer(main, main, new Rectangle(100, 100, 200, 100), new Point(0, 0), true),
                new Windows.Layer(ownedDialog, ownedDialog, new Rectangle(150, 120, 80, 60), new Point(0, 0), true),
                new Windows.Layer(ownedByTheDialog, ownedByTheDialog, new Rectangle(160, 130, 40, 30),
                        new Point(0, 0), true),
                new Windows.Layer(mainsDialog, mainsDialog, new Rectangle(220, 150, 60, 40), new Point(0, 0), true)));
    }

    @AfterEach
    void tearDown() {
        Windows.state = Windows.TOOLKIT_STATE;
        ctx.shutdown();
    }

    @Test
    void anIconifiedFrameAndTheWindowsItOwnsTransitivelyAreHidden() {
        assertTrue(Windows.hidden(iconified), "the iconified frame");
        assertTrue(Windows.hidden(ownedDialog), "a window it owns");
        assertTrue(Windows.hidden(ownedByTheDialog), "a window it owns through another");
        assertFalse(Windows.hidden(main), "a frame that is not iconified");
        assertFalse(Windows.hidden(mainsDialog), "a window a shown frame owns");
        assertFalse(Windows.hidden(null));
    }

    @Test
    void theStackingLeavesThemOut() throws Exception {
        List<Windows.Layer> layers = onEdt(() -> windows.stacking());
        assertEquals(2, layers.size(), "the main frame and its own dialog only");
        assertSame(main, layers.get(0).window);
        assertSame(mainsDialog, layers.get(1).window);
    }

    @Test
    void theScreenCompositeNeitherPaintsNorListsThem() throws Exception {
        Capture.Request r = new Capture.Request();
        r.scope = Capture.Scope.SCREEN;
        r.annotate = true;
        Capture.Shot shot = onEdt(() -> Capture.take(new ComponentView(ctx), windows, new SwingSpis(ctx), null, r,
                7L));
        List<Object> ws = arr(shot.fields.get("windows"));
        assertEquals(2, ws.size(), ws.toString());
        assertEquals(ctx.handles.handleFor(main, ComponentView.KIND), obj(ws.get(0)).get("ref"));
        assertEquals(map("x", 100L, "y", 100L), shot.fields.get("origin"), "the union of the shown windows only");
        assertEquals(200, shot.image.getWidth(), "100..300: the minimised frame at 500..700 is not in the union");
        assertEquals(CaptureTest.GREEN.getRGB(), shot.image.getRGB(70, 30),
                "the owned dialog of the minimised frame is not painted over the main frame");
        for (Object m : arr(shot.fields.get("marks"))) {
            assertFalse(String.valueOf(obj(m).get("label")).contains("minimised"), "no mark: " + m);
        }
    }

    @Test
    void theToolkitStateAnswersNothingForAStandIn() {
        assertFalse(Windows.TOOLKIT_STATE.iconified(main));
        assertNull(Windows.TOOLKIT_STATE.owner(main));
    }
}
