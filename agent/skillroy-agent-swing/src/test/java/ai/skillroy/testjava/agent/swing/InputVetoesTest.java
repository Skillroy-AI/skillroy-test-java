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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JButton;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JTextField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input veto's evaluator without a display (phase8-contracts.md 3.2, 13.1 row 2): the ancestor rule (the nearest
 * refusal wins and names the component that refused, at most 64 levels, deeper refused), memoisation (one ask per
 * component and route), the text sanitising (control characters, the 200 cut, an empty text), a throwing veto
 * refusing with the fixed text and logged once, a {@code VirtualMachineError} rethrown, the in-task re-ask counted
 * and logged once per call, and the error's shape. The fix wave of the Phase 8 review (FA) adds: a popup's items asked
 * with its invoker's chain (L2-F2), a cycle through an invoker refused at the depth bound, ENTER asking the default
 * button of every root pane up to the window (L2-S1), and a throwing veto's log line naming the exception's class only
 * (security review L8-F6).
 */
class InputVetoesTest {

    private final FakeContext ctx = new FakeContext();

    @AfterEach
    void tearDown() {
        ctx.shutdown();
    }

    /** Refuses the named components on every route and counts every ask per component and route. */
    static final class ByName implements InputVeto {
        final Map<String, String> refusals = new HashMap<String, String>();
        final Map<String, Integer> asks = new HashMap<String, Integer>();

        ByName refuse(String name, String text) {
            refusals.put(name, text);
            return this;
        }

        @Override
        public synchronized String refuse(Component component, String route) {
            String key = component.getName() + "@" + route;
            Integer n = asks.get(key);
            asks.put(key, n == null ? 1 : n + 1);
            return refusals.get(component.getName());
        }

        int asked(String name, String route) {
            Integer n = asks.get(name + "@" + route);
            return n == null ? 0 : n;
        }

        int total() {
            int t = 0;
            for (Integer n : asks.values()) {
                t += n;
            }
            return t;
        }
    }

    private static JPanel named(String name) {
        JPanel p = new JPanel(null);
        p.setName(name);
        return p;
    }

    /** root > menu > key, and root > other. */
    private static Component[] tree() {
        JPanel root = named("root");
        JPanel menu = named("menu");
        JButton key = new JButton("Close");
        key.setName("key");
        JButton other = new JButton("Print");
        other.setName("other");
        menu.add(key);
        root.add(menu);
        root.add(other);
        return new Component[] {root, menu, key, other};
    }

    @Test
    void theNearestRefusalOnTheChainWinsAndNamesTheComponentThatRefused() throws Exception {
        final Component[] t = tree();
        ByName veto = new ByName().refuse("menu", "closes the desk");
        ctx.spi.register(InputVeto.class, veto);
        InputVetoes.Refusal r = onEdt(() -> new InputVetoes(ctx).check(t[2], InputVeto.CLICK_ACTION));
        assertNotNull(r, "an ancestor's refusal covers its descendants");
        assertSame(t[1], r.component, "vetoed names the ancestor that refused");
        assertEquals("closes the desk", r.text);
        assertEquals(InputVeto.CLICK_ACTION, r.route);
        assertNull(onEdt(() -> new InputVetoes(ctx).check(t[3], InputVeto.CLICK_ACTION)), "a sibling is allowed");
        veto.refuse("key", "the key itself");
        InputVetoes.Refusal nearer = onEdt(() -> new InputVetoes(ctx).check(t[2], InputVeto.KEY));
        assertSame(t[2], nearer.component, "the refusal nearest the component wins");
        assertEquals("the key itself", nearer.text);
    }

    @Test
    void noVetoMeansNothingIsAskedAndEverythingIsAllowed() throws Exception {
        final Component[] t = tree();
        InputVetoes vetoes = onEdt(() -> new InputVetoes(ctx));
        assertFalse(vetoes.any());
        assertNull(onEdt(() -> vetoes.check(t[2], InputVeto.ANY)));
        assertFalse(onEdt(() -> new ComponentView(ctx).vetoed(t[2])));
    }

    @Test
    void eachComponentIsAskedOncePerRouteAndCall() throws Exception {
        final Component[] t = tree();
        final ByName veto = new ByName();
        ctx.spi.register(InputVeto.class, veto);
        onEdt(() -> {
            InputVetoes v = new InputVetoes(ctx);
            v.check(t[2], InputVeto.ANY);
            v.check(t[2], InputVeto.ANY);
            v.check(t[3], InputVeto.ANY); // shares root with key: root is not asked again
            v.check(t[2], InputVeto.KEY);
            return null;
        });
        assertEquals(1, veto.asked("key", InputVeto.ANY));
        assertEquals(1, veto.asked("menu", InputVeto.ANY));
        assertEquals(1, veto.asked("root", InputVeto.ANY));
        assertEquals(1, veto.asked("other", InputVeto.ANY));
        assertEquals(1, veto.asked("key", InputVeto.KEY), "another route is asked anew");
        assertEquals(1, veto.asked("root", InputVeto.KEY));
        onEdt(() -> new InputVetoes(ctx).check(t[2], InputVeto.ANY));
        assertEquals(2, veto.asked("key", InputVeto.ANY), "a fresh evaluator asks again (the in-task re-ask)");
    }

    @Test
    void atMostSixtyFourLevelsAreAskedAndADeeperComponentIsRefused() throws Exception {
        final List<JPanel> chain = new ArrayList<JPanel>();
        onEdt(() -> {
            JPanel p = named("level0");
            chain.add(p);
            for (int i = 1; i < 65; i++) {
                JPanel child = named("level" + i);
                p.add(child);
                chain.add(child);
                p = child;
            }
            return null;
        });
        final ByName veto = new ByName();
        ctx.spi.register(InputVeto.class, veto);
        assertNull(onEdt(() -> new InputVetoes(ctx).check(chain.get(63), InputVeto.ANY)),
                "a component with 63 ancestors: 64 levels, all asked");
        assertEquals(64, veto.total(), "one ask per level");
        InputVetoes.Refusal deep = onEdt(() -> new InputVetoes(ctx).check(chain.get(64), InputVeto.ANY));
        assertNotNull(deep, "65 levels: refused, since not every ancestor could be asked (fail closed)");
        assertTrue(deep.text.contains("more than 64 levels"), deep.text);
        assertSame(chain.get(64), deep.component);
        assertEquals(64, veto.total(), "nothing is asked for a component that lies too deep");
    }

    @Test
    void theTextIsOneLineOfAtMost200CharactersAndNeverEmpty() throws Exception {
        final Component[] t = tree();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longText.append('x');
        }
        final ByName veto = new ByName().refuse("key", "ends\nthe\tJVM\u0007 ").refuse("other", longText.toString())
                .refuse("menu", "  \n ");
        ctx.spi.register(InputVeto.class, veto);
        assertEquals("ends the JVM", onEdt(() -> new InputVetoes(ctx).check(t[2], InputVeto.ANY)).text,
                "control characters become spaces, then trimmed");
        assertEquals(200, onEdt(() -> new InputVetoes(ctx).check(t[3], InputVeto.ANY)).text.length());
        veto.refusals.remove("key");
        assertEquals("vetoed by ByName", onEdt(() -> new InputVetoes(ctx).check(t[2], InputVeto.ANY)).text,
                "an empty text names the veto's class");
        String anonymous = InputVetoes.sanitise("", new Object() { });
        assertTrue(anonymous.startsWith("vetoed by InputVetoesTest$"), "an anonymous class: the binary name's last "
                + "part: " + anonymous);
    }

    @Test
    void aThrowingVetoRefusesWithTheFixedTextAndIsLoggedOnce() throws Exception {
        final Component[] t = tree();
        InputVeto broken = new InputVeto() {
            @Override
            public String refuse(Component component, String route) {
                throw new IllegalStateException("broken veto");
            }
        };
        ctx.spi.register(InputVeto.class, broken);
        InputVetoes.Refusal r = onEdt(() -> new InputVetoes(ctx).check(t[3], InputVeto.CLICK_ACTION));
        assertNotNull(r, "a veto that throws refuses (the safe side)");
        assertSame(t[3], r.component);
        assertTrue(r.text.startsWith("veto ") && r.text.endsWith(" failed"), r.text);
        onEdt(() -> new InputVetoes(ctx).check(t[2], InputVeto.KEY));
        int logged = 0;
        for (String w : ctx.log.warnings()) {
            if (w.contains("InputVeto") && w.contains("failed")) {
                logged++;
            }
        }
        assertEquals(1, logged, "logged once per veto instance: " + ctx.log.warnings());
    }

    @Test
    void aVirtualMachineErrorIsRethrownButAStackOverflowRefuses() throws Exception {
        final Component[] t = tree();
        final AtomicBoolean oom = new AtomicBoolean(true);
        ctx.spi.register(InputVeto.class, new InputVeto() {
            @Override
            public String refuse(Component component, String route) {
                if (oom.get()) {
                    throw new OutOfMemoryError("test");
                }
                throw new StackOverflowError();
            }
        });
        Throwable thrown = null;
        try {
            onEdt(() -> {
                try {
                    return new InputVetoes(ctx).check(t[3], InputVeto.ANY);
                } catch (OutOfMemoryError e) {
                    throw new IllegalStateException("rethrown", e);
                }
            });
        } catch (IllegalStateException e) {
            thrown = e.getCause();
        }
        assertTrue(thrown instanceof OutOfMemoryError, "rethrown: " + thrown);
        oom.set(false);
        assertNotNull(onEdt(() -> new InputVetoes(ctx).check(t[3], InputVeto.ANY)), "a stack overflow refuses");
    }

    @Test
    void theErrorIsInputRejectedVetoedWithTheTextTheRefsAndTheRoute() throws Exception {
        final Component[] t = tree();
        ctx.spi.register(InputVeto.class, new ByName().refuse("menu", "closes the desk"));
        RpcException e = assertThrows(RpcException.class, () -> onEdt(() -> {
            Clicks.requireNotVetoed(new ComponentView(ctx), t[2], InputVeto.CLICK_DISPATCH);
            return null;
        }));
        assertEquals(ErrorCodes.INPUT_REJECTED, e.error().code());
        Map<String, Object> data = e.error().data();
        assertEquals("vetoed", data.get("reason"));
        assertEquals("closes the desk", data.get("veto"));
        assertEquals(ctx.handles.handleFor(t[2], ComponentView.KIND), data.get("ref"));
        assertEquals(ctx.handles.handleFor(t[1], ComponentView.KIND), data.get("vetoed"));
        assertEquals(InputVeto.CLICK_DISPATCH, data.get("route"));
        assertFalse(data.containsKey("hit"));
        assertTrue(e.getMessage().contains("takes no input from the agent (swing.click.dispatch): closes the desk"),
                e.getMessage());
    }

    @Test
    void theInTaskReAskWithholdsCountsAndLogsOncePerCall() throws Exception {
        final Component[] t = tree();
        final ByName veto = new ByName();
        ctx.spi.register(InputVeto.class, veto);
        final AtomicLong counter = new AtomicLong();
        final InputVetoes.Withheld call = new InputVetoes.Withheld(ctx, counter);
        final List<String> ran = Collections.synchronizedList(new ArrayList<String>());
        Edt.Action act = InputVetoes.guarded(call, InputVeto.TYPE_DISPATCH, () -> ran.add("acted"), t[2]);
        onEdt(() -> {
            act.run();
            return null;
        });
        assertEquals(Collections.singletonList("acted"), ran, "allowed at the time of the task");
        veto.refuse("menu", "switched on between the read and the task");
        onEdt(() -> {
            act.run();
            act.run();
            return null;
        });
        assertEquals(1, ran.size(), "a refusal inside the task acts on nothing");
        assertEquals(1L, counter.get(), "counted once per call");
        int logged = 0;
        for (String w : ctx.log.warnings()) {
            if (w.contains("withheld inside the task")) {
                logged++;
                assertTrue(w.contains("swing.type.dispatch") && w.contains("switched on"), w);
            }
        }
        assertEquals(1, logged, "logged once per call");
        new InputVetoes.Withheld(ctx, counter).allows(InputVeto.TYPE_DISPATCH, t[2]);
        assertEquals(2L, counter.get(), "another call counts again");
    }

    /** A frame-less stand-in for a menu: root > menu > opener, and a popup whose invoker is the opener. */
    private static Object[] popupTree() {
        JPanel root = named("root");
        JPanel menu = named("menu");
        JButton opener = new JButton("Desk");
        opener.setName("opener");
        menu.add(opener);
        root.add(menu);
        JPopupMenu popup = new JPopupMenu();
        popup.setName("popup");
        JMenuItem item = new JMenuItem("Close the desk");
        item.setName("item");
        popup.add(item);
        popup.setInvoker(opener);
        return new Object[] {root, menu, opener, popup, item};
    }

    /**
     * Fix wave of the Phase 8 review (FA, L2-F2): a popup's item is asked with its invoker's chain, so a veto on the
     * invoker's panel covers it (refusing on the route and flagged for the readers); without an invoker the popup's
     * own parent chain is asked, as before.
     */
    @Test
    void aPopupsItemsAreAskedWithTheChainOfTheComponentThatOpenedIt() throws Exception {
        final Object[] t = onEdt(InputVetoesTest::popupTree);
        final Component menu = (Component) t[1];
        final JPopupMenu popup = (JPopupMenu) t[3];
        final Component item = (Component) t[4];
        ctx.spi.register(InputVeto.class, new ByName().refuse("menu", "closes the desk"));
        InputVetoes.Refusal r = onEdt(() -> new InputVetoes(ctx).check(item, InputVeto.CLICK_ACTION));
        assertNotNull(r, "the item of a popup the vetoed panel's button opened is refused");
        assertSame(menu, r.component, "vetoed names the invoker's ancestor that refused");
        assertTrue(onEdt(() -> new ComponentView(ctx).vetoed(item)), "the readers flag it");
        onEdt(() -> {
            popup.setInvoker(null);
            return null;
        });
        assertNull(onEdt(() -> new InputVetoes(ctx).check(item, InputVeto.CLICK_ACTION)),
                "a popup without an invoker is asked with its own parents");
    }

    /** Fix wave (FA, L2-F2): an invoker inside its own popup is a cycle, refused at the depth bound (fail closed). */
    @Test
    void anInvokerInsideItsOwnPopupIsRefusedAtTheDepthBound() throws Exception {
        final Object[] t = onEdt(InputVetoesTest::popupTree);
        final JPopupMenu popup = (JPopupMenu) t[3];
        final Component item = (Component) t[4];
        final ByName veto = new ByName();
        ctx.spi.register(InputVeto.class, veto);
        onEdt(() -> {
            popup.setInvoker(item);
            return null;
        });
        InputVetoes.Refusal r = onEdt(() -> new InputVetoes(ctx).check(item, InputVeto.ANY));
        assertNotNull(r, "a cycle cannot be asked to its end: refused");
        assertTrue(r.text.contains("more than 64 levels"), r.text);
        assertEquals(0, veto.total(), "nothing is asked for it");
    }

    /**
     * Fix wave of the Phase 8 review (FA, L2-S1): ENTER into a field of a {@code JInternalFrame} without a default
     * button presses the enclosing window's default button, so that button is asked too; a vetoed one refuses with
     * {@code defaultButton} naming it; without ENTER nothing but the target is asked.
     */
    @Test
    void enterAsksTheDefaultButtonOfEveryRootPaneUpToTheWindow() throws Exception {
        final Component[] t = onEdt(() -> {
            JRootPane outer = new JRootPane();
            JPanel content = named("content");
            content.setLayout(null);
            outer.setContentPane(content);
            JPanel close = named("closeMenu");
            JButton closeDesk = new JButton("Close the desk");
            closeDesk.setName("closeDesk");
            close.add(closeDesk);
            content.add(close);
            outer.setDefaultButton(closeDesk);
            JDesktopPane desk = new JDesktopPane();
            content.add(desk);
            JInternalFrame inner = new JInternalFrame("A dialog drawn in the window");
            JTextField field = new JTextField(8);
            field.setName("innerField");
            inner.getContentPane().add(field);
            desk.add(inner);
            return new Component[] {outer, close, closeDesk, inner, field};
        });
        ctx.spi.register(InputVeto.class, new ByName().refuse("closeMenu", "closes the desk"));
        RpcException e = assertThrows(RpcException.class, () -> onEdt(() -> {
            Typing.requireKeyTargetsNotVetoed(new ComponentView(ctx), t[4], InputVeto.KEY, true);
            return null;
        }));
        assertEquals(ErrorCodes.INPUT_REJECTED, e.error().code());
        Map<String, Object> data = e.error().data();
        assertEquals("vetoed", data.get("reason"));
        assertEquals(ctx.handles.handleFor(t[4], ComponentView.KIND), data.get("ref"), "ref is the key's target");
        assertEquals(ctx.handles.handleFor(t[2], ComponentView.KIND), data.get("defaultButton"),
                "the enclosing root pane's default button, past the internal frame's root pane without one");
        assertEquals(ctx.handles.handleFor(t[1], ComponentView.KIND), data.get("vetoed"));
        onEdt(() -> {
            Typing.requireKeyTargetsNotVetoed(new ComponentView(ctx), t[4], InputVeto.KEY, false);
            return null;
        });
    }

    /**
     * Security review L8-F6, fixed by the fix wave (FA): a throwing veto's log line names the veto's and the
     * exception's classes only, never the exception's message (it may quote the application's text, and the agent
     * log is served by {@code agent.log}).
     */
    @Test
    void aThrowingVetosLogLineNamesTheExceptionsClassOnly() throws Exception {
        final Component[] t = tree();
        ctx.spi.register(InputVeto.class, new InputVeto() {
            @Override
            public String refuse(Component component, String route) {
                throw new NumberFormatException("For input string: \"planted field text 4711\"");
            }
        });
        assertNotNull(onEdt(() -> new InputVetoes(ctx).check(t[3], InputVeto.CLICK_ACTION)));
        String line = null;
        for (String w : ctx.log.warnings()) {
            if (w.contains("InputVeto")) {
                line = w;
            }
        }
        assertNotNull(line, String.valueOf(ctx.log.warnings()));
        assertTrue(line.contains("java.lang.NumberFormatException"), line);
        assertFalse(line.contains("planted field text"), "the exception's message stays out of the log: " + line);
    }
}
