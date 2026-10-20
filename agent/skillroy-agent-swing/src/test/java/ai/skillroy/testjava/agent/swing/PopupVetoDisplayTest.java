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

import ai.skillroy.testjava.agent.api.spi.InputVeto;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Component;
import java.awt.Point;
import java.awt.Window;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.ComboPopup;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input veto and popups on a real display (fix wave of the Phase 8 review, FA; Phase 8 security fix S155): a
 * popup is asked with its invoker's chain (L2-F2: a frame veto covers the items of a heavyweight menu popup the
 * application opened; a vetoed combo covers its open list); a routed drag pressed on an allowed menu, or with the
 * right button on a panel with a context menu, and released on a vetoed item is refused at the release ({@code VETOED},
 * the press balanced, nothing fired), with the menu's popup drawn in the layered pane and as a window of its own; ENTER
 * into a field of a {@code JInternalFrame} without a default button asks the enclosing window's default button
 * (L2-S1), as a {@code '\n'} in DISPATCH text does (L2-F1). Two frames of its own, beside the test frame, so that no
 * window of the JVM covers their points.
 */
class PopupVetoDisplayTest extends DisplayTestBase {

    static final String TEXT = "closes the desk (the application would end)";

    static JFrame menuFrame;
    static JMenu deskMenu;
    static JMenuItem closeItem;
    static JMenu heavyMenu;
    static JMenuItem heavyClose;
    static JPanel contextArea;
    static JPopupMenu contextMenu;
    static JMenuItem contextClose;
    static JComboBox<String> combo;
    static JTextField innerField;
    static JButton frameDefault;
    static JFrame vetoedFrame;
    static JMenu toolsMenu;
    static JMenuItem slipItem;
    static final AtomicInteger fired = new AtomicInteger();
    static AutoCloseable vetoRegistration;

    /** Refuses the named components on every route. */
    static final class ByName implements InputVeto {
        final Set<String> names = Collections.synchronizedSet(new HashSet<String>());

        @Override
        public String refuse(Component c, String route) {
            return c.getName() != null && names.contains(c.getName()) ? TEXT : null;
        }
    }

    private static JMenuItem item(String text, String name) {
        JMenuItem i = new JMenuItem(text);
        i.setName(name);
        i.addActionListener(e -> fired.incrementAndGet());
        return i;
    }

    @BeforeAll
    static void showFixture() throws Exception {
        ByName veto = new ByName();
        Collections.addAll(veto.names, "closeItem", "heavyClose", "contextClose", "vetoedCombo", "frameDefault",
                "vetoedFrame");
        vetoRegistration = ctx.spi.register(InputVeto.class, veto);
        onEdt(() -> {
            JFrame f = new JFrame("Popup veto fixture");
            f.setName("popupFixture");
            f.setAutoRequestFocus(false);
            JMenuBar bar = new JMenuBar();
            JMenu desk = new JMenu("Desk");
            desk.setName("deskMenu");
            desk.add(item("Print a slip", "printItem"));
            JMenuItem close = item("Close the desk", "closeItem");
            desk.add(close);
            JMenu heavy = new JMenu("Heavy");
            heavy.setName("heavyMenu");
            heavy.getPopupMenu().setLightWeightPopupEnabled(false);
            heavy.add(item("Print heavy", "heavyPrint"));
            JMenuItem heavyCloseItem = item("Close heavy", "heavyClose");
            heavy.add(heavyCloseItem);
            bar.add(desk);
            bar.add(heavy);
            f.setJMenuBar(bar);
            JPanel content = new JPanel(null);
            JPanel plain = new JPanel();
            plain.setName("plainArea");
            plain.setBounds(0, 0, 360, 150);
            content.add(plain);
            JComboBox<String> box = new JComboBox<String>(new String[] {"hammer", "saw", "drill", "ladder", "clamp"});
            box.setName("vetoedCombo");
            box.setBounds(10, 160, 150, 25);
            content.add(box);
            JButton def = new JButton("Close the desk");
            def.setName("frameDefault");
            def.setBounds(180, 160, 170, 28);
            def.addActionListener(e -> fired.incrementAndGet());
            content.add(def);
            JPanel context = new JPanel();
            context.setName("contextArea");
            context.setBounds(10, 200, 340, 130);
            JPopupMenu popup = new JPopupMenu();
            popup.add(item("Print context", "contextPrint"));
            JMenuItem contextCloseItem = item("Close context", "contextClose");
            popup.add(contextCloseItem);
            context.setComponentPopupMenu(popup);
            content.add(context);
            JDesktopPane desktop = new JDesktopPane();
            desktop.setBounds(10, 340, 340, 160);
            JInternalFrame inner = new JInternalFrame("A dialog drawn in the window");
            JTextField field = new JTextField(10);
            field.setName("innerField");
            inner.getContentPane().add(field);
            inner.setBounds(10, 10, 260, 90);
            inner.setVisible(true);
            desktop.add(inner);
            content.add(desktop);
            f.setContentPane(content);
            f.getRootPane().setDefaultButton(def);
            f.setBounds(650, 20, 370, 590);
            f.setVisible(true);
            menuFrame = f;
            deskMenu = desk;
            closeItem = close;
            heavyMenu = heavy;
            heavyClose = heavyCloseItem;
            contextArea = context;
            contextMenu = popup;
            contextClose = contextCloseItem;
            combo = box;
            innerField = field;
            frameDefault = def;

            JFrame v = new JFrame("A vetoed window");
            v.setName("vetoedFrame");
            v.setAutoRequestFocus(false);
            JMenuBar vbar = new JMenuBar();
            JMenu tools = new JMenu("Tools");
            tools.setName("toolsMenu");
            tools.getPopupMenu().setLightWeightPopupEnabled(false);
            JMenuItem slip = item("Print a test slip", "slipItem");
            tools.add(slip);
            vbar.add(tools);
            v.setJMenuBar(vbar);
            v.setBounds(650, 630, 300, 120);
            v.setVisible(true);
            vetoedFrame = v;
            toolsMenu = tools;
            slipItem = slip;
            return null;
        });
        await("popup fixture showing", WAIT_MS, () -> innerField.isShowing() && slipItem != null
                && toolsMenu.isShowing());
        settle();
    }

    @AfterAll
    static void closeFixture() throws Exception {
        if (menuFrame == null) {
            return;
        }
        onEdt(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            menuFrame.dispose();
            vetoedFrame.dispose();
            return null;
        });
        vetoRegistration.close();
    }

    @AfterEach
    void closePopups() throws Exception {
        onEdt(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            combo.hidePopup();
            contextMenu.setVisible(false);
            return null;
        });
        settle();
    }

    private static Map<String, Object> windowPoint(Component c) throws Exception {
        Point p = onEdt(() -> HitTest.centre(c));
        return map("x", (long) p.x, "y", (long) p.y);
    }

    /** Where a component of an open popup lies in a frame's space (the popup may be a window of its own). */
    private static Map<String, Object> pointIn(Component c, Window w) throws Exception {
        Point p = onEdt(() -> {
            Point s = new Point(c.getWidth() / 2, c.getHeight() / 2);
            SwingUtilities.convertPointToScreen(s, c);
            Point o = w.getLocationOnScreen();
            return new Point(s.x - o.x, s.y - o.y);
        });
        return map("x", (long) p.x, "y", (long) p.y);
    }

    /** Opens a menu as the application would, waits for the item, and answers where it lies in the frame. */
    private static Map<String, Object> openAndLocate(JMenu menu, JMenuItem item) throws Exception {
        onEdt(() -> {
            menu.doClick(0);
            return null;
        });
        await("the menu's popup shows", WAIT_MS, item::isShowing);
        settle();
        return pointIn(item, menuFrame);
    }

    private static void closeMenus() throws Exception {
        onEdt(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            return null;
        });
        await("the menus closed", WAIT_MS, () -> !closeItem.isShowing() && !heavyClose.isShowing());
        settle();
    }

    private static void assertVetoedRelease(Map<String, Object> r, Component item) {
        assertEquals(Boolean.FALSE, r.get("fired"), String.valueOf(r));
        assertEquals("VETOED", r.get("reason"), String.valueOf(r));
        assertEquals(map("ref", ref(item), "veto", TEXT, "route", InputVeto.POINTER_ROUTED), r.get("vetoed"));
        Map<String, Object> dispatched = obj(r.get("dispatched"));
        assertEquals(((Number) dispatched.get("of")).longValue() - 1, ((Number) dispatched.get("sent")).longValue(),
                "everything but the release was sent: " + r);
        assertEquals(Boolean.TRUE, r.get("balanced"), "the press is balanced");
    }

    /**
     * Security fix S155 (routed): a drag pressed on an allowed menu and released on its vetoed item is refused at the
     * release; the menu was opened and closed once first, so that its lightweight popup's items have their bounds
     * (on the first opening Swing lays them out after the call's queued events, and nothing would fire either way).
     */
    @Test
    void aRoutedDragFromAMenuReleasedOnAVetoedItemIsRefusedAtTheRelease() throws Exception {
        Map<String, Object> to = openAndLocate(deskMenu, closeItem);
        closeMenus();
        int before = fired.get();
        Map<String, Object> r = call("swing.pointer", "point", windowPoint(deskMenu), "space",
                map("window", ref(menuFrame)), "to", to, "action", "DRAG");
        assertVetoedRelease(r, closeItem);
        settle();
        assertEquals(before, fired.get(), "the vetoed item never fired");
    }

    /** S155 (routed) with the menu's popup drawn as a window of its own (a heavyweight popup over the frame). */
    @Test
    void aRoutedDragReleasedOnAVetoedItemOfAHeavyweightPopupIsRefused() throws Exception {
        Map<String, Object> to = openAndLocate(heavyMenu, heavyClose);
        closeMenus();
        int before = fired.get();
        Map<String, Object> r = call("swing.pointer", "point", windowPoint(heavyMenu), "space",
                map("window", ref(menuFrame)), "to", to, "action", "DRAG");
        assertVetoedRelease(r, heavyClose);
        settle();
        assertEquals(before, fired.get(), "the vetoed item never fired");
    }

    /**
     * S155 (routed), the context-menu shape: a right-button drag on a panel with a context menu opens it on the press
     * (the popup trigger on X11) and the popup's mouse grabber hands the release to the vetoed item under it.
     */
    @Test
    void aRightDragReleasedOnAVetoedContextMenuItemIsRefused() throws Exception {
        final Point press = new Point(40, 20);
        Map<String, Object> to = pointAfter(() -> contextMenu.show(contextArea, press.x, press.y), contextClose);
        onEdt(() -> {
            contextMenu.setVisible(false);
            MenuSelectionManager.defaultManager().clearSelectedPath();
            return null;
        });
        await("the context menu closed", WAIT_MS, () -> !contextClose.isShowing());
        settle();
        Point pressInFrame = onEdt(() -> SwingUtilities.convertPoint(contextArea, press, menuFrame));
        int before = fired.get();
        Map<String, Object> r = call("swing.pointer", "point", map("x", (long) pressInFrame.x, "y",
                (long) pressInFrame.y), "space", map("window", ref(menuFrame)), "to", to, "action", "DRAG", "button",
                "RIGHT");
        assertVetoedRelease(r, contextClose);
        settle();
        assertEquals(before, fired.get(), "the vetoed item never fired");
    }

    private static Map<String, Object> pointAfter(Runnable open, Component item) throws Exception {
        onEdt(() -> {
            open.run();
            return null;
        });
        await("the popup shows", WAIT_MS, item::isShowing);
        settle();
        return pointIn(item, menuFrame);
    }

    /**
     * Fix wave L2-F2: a frame veto covers the items of its menu's heavyweight popup that the application opened (the
     * popup is a window of its own, owned by the frame); refused on a click and flagged for the readers.
     */
    @Test
    void aWindowVetoCoversTheItemsOfItsHeavyweightMenuPopup() throws Exception {
        onEdt(() -> {
            toolsMenu.doClick(0);
            return null;
        });
        await("the vetoed frame's menu shows", WAIT_MS, slipItem::isShowing);
        settle();
        assertTrue(onEdt(() -> !(SwingUtilities.getWindowAncestor(slipItem) == vetoedFrame)),
                "the popup is a window of its own");
        int before = fired.get();
        RpcException e = expectError(ErrorCodes.INPUT_REJECTED, () -> call("swing.click", "target", ref(slipItem)));
        assertEquals("vetoed", e.error().data().get("reason"));
        assertEquals(ref(vetoedFrame), e.error().data().get("vetoed"), "the frame's veto refused");
        assertEquals(Boolean.TRUE, call("swing.hitTest", "target", ref(slipItem)).get("vetoed"));
        settle();
        assertEquals(before, fired.get());
    }

    /** Fix wave L2-F2: a vetoed combo covers its open popup list (a click there would set the combo's selection). */
    @Test
    void aVetoedComboCoversItsOpenList() throws Exception {
        onEdt(() -> {
            combo.setSelectedIndex(0);
            combo.showPopup();
            return null;
        });
        final JList<?> list = onEdt(() -> ((ComboPopup) combo.getUI().getAccessibleChild(combo, 0)).getList());
        await("the combo's list shows", WAIT_MS, list::isShowing);
        settle();
        RpcException e = expectError(ErrorCodes.INPUT_REJECTED, () -> call("swing.click", "target", ref(list)));
        assertEquals(ref(combo), e.error().data().get("vetoed"), String.valueOf(e.error().data()));
        RpcException pointer = expectError(ErrorCodes.INPUT_REJECTED, () -> call("swing.pointer", "target", ref(list),
                "action", "CLICK"));
        assertEquals("vetoed", pointer.error().data().get("reason"));
        settle();
        assertEquals(Integer.valueOf(0), onEdt(() -> combo.getSelectedIndex()), "the combo's selection unchanged");
    }

    /**
     * Fix wave L2-S1 (and L2-F1): ENTER into a field of an internal frame without a default button presses the
     * enclosing window's default button, so that button is asked: refused, {@code defaultButton} naming it; a
     * {@code '\n'} in DISPATCH text likewise.
     */
    @Test
    void enterInAnInternalFrameAsksTheWindowsDefaultButton() throws Exception {
        int before = fired.get();
        RpcException key = expectError(ErrorCodes.INPUT_REJECTED, () -> call("swing.key", "target", ref(innerField),
                "key", "ENTER"));
        assertEquals("vetoed", key.error().data().get("reason"));
        assertEquals(ref(innerField), key.error().data().get("ref"));
        assertEquals(ref(frameDefault), key.error().data().get("defaultButton"));
        RpcException typed = expectError(ErrorCodes.INPUT_REJECTED, () -> call("swing.type", "target",
                ref(innerField), "text", "x\n", "mode", "DISPATCH"));
        assertEquals(ref(frameDefault), typed.error().data().get("defaultButton"));
        call("swing.type", "target", ref(innerField), "text", "typed", "mode", "DISPATCH");
        settle();
        assertEquals("typed", onEdt(() -> innerField.getText()), "typing without ENTER goes in");
        assertEquals(before, fired.get(), "the vetoed default button never fired");
    }
}
