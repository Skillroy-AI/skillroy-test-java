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

import ai.skillroy.testjava.agent.api.spi.LocatorResolver;
import ai.skillroy.testjava.agent.api.spi.NameEnricher;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.arr;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locator parsing and matching. Runs headless: the tree is built from lightweight components that are never shown,
 * so every search uses includeHidden.
 */
class LocatorTest {

    private FakeContext ctx;
    private JPanel root;
    private JButton ok;
    private JButton cancel;
    private JButton icon;
    private JTextField entry;
    private JPasswordField pin;
    private JLabel label;
    private JPanel inner;
    private JButton innerOk;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new FakeContext();
        onEdt(() -> {
            root = new JPanel();
            ok = new JButton("OK");
            ok.setName("okButton");
            cancel = new JButton("Cancel");
            icon = new JButton();
            entry = new JTextField("hello world");
            entry.setName("entry");
            pin = new JPasswordField("1234");
            label = new JLabel("<html>Total <b>due</b></html>");
            inner = new JPanel();
            innerOk = new JButton("OK");
            inner.add(innerOk);
            root.add(ok);
            root.add(cancel);
            root.add(icon);
            root.add(entry);
            root.add(pin);
            root.add(label);
            root.add(inner);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        ctx.shutdown();
    }

    private List<Component> find(Map<String, Object> by) throws Exception {
        final Locator loc = Locator.parse(by, "by");
        return onEdt(() -> new LocatorEngine(new ComponentView(ctx)).find(loc, root, true));
    }

    private Component one(Map<String, Object> by) throws Exception {
        final Locator loc = Locator.parse(by, "by");
        return onEdt(() -> new LocatorEngine(new ComponentView(ctx)).one(loc, root, true));
    }

    @Test
    void nameMatchesExactlyByDefault() throws Exception {
        assertSame(entry, one(map("name", "entry")));
        assertTrue(find(map("name", "entr")).isEmpty());
        // a button's accessible name is its text, so "Cancel" is a name too
        assertSame(cancel, one(map("name", "Cancel")));
    }

    @Test
    void accessibleNameComesBeforeComponentName() throws Exception {
        // contract name rule: enrichers, then the accessible name (a button's text), then getName()
        assertTrue(find(map("name", "okButton")).isEmpty());
        assertEquals("OK", onEdt(() -> new ComponentView(ctx).name(ok)));
        assertEquals("entry", onEdt(() -> new ComponentView(ctx).name(entry)));
    }

    @Test
    void idMatchesTheExplicitComponentNameExactly() throws Exception {
        // A7: the accessible name hides getName() from {name}; {id} reaches it
        assertSame(ok, one(map("id", "okButton")));
        assertSame(entry, one(map("id", "entry")));
        assertTrue(find(map("id", "OK")).isEmpty(), "the button's text is its name, not its id");
        assertTrue(find(map("id", "okBut")).isEmpty(), "exact match only");
        assertSame(ok, one(map("id", "okButton", "role", "button")));
        assertTrue(find(map("id", "okButton", "role", "textbox")).isEmpty());
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("id", map("contains", "ok")), "by"));
        assertTrue(Locator.parse(map("id", "x"), "by").builtIn());
    }

    @Test
    void nodeCarriesTheIdOnlyWhenOneIsSet() throws Exception {
        Map<String, Object> okNode = onEdt(() -> new ComponentView(ctx).node(ok));
        assertEquals("okButton", okNode.get("id"));
        assertEquals("OK", okNode.get("name"), "the name rule is unchanged");
        Map<String, Object> cancelNode = onEdt(() -> new ComponentView(ctx).node(cancel));
        assertFalse(cancelNode.containsKey("id"), "no id key without an explicit name: " + cancelNode);
        // a heavyweight component's made-up name (panel0, frame3, ...) is not an id
        java.awt.Panel heavy = new java.awt.Panel();
        assertNull(onEdt(() -> new ComponentView(ctx).id(heavy)));
        heavy.setName("toolbar");
        assertEquals("toolbar", onEdt(() -> new ComponentView(ctx).id(heavy)));
    }

    @Test
    void containsAndRegex() throws Exception {
        assertSame(entry, one(map("text", map("contains", "lo wo"))));
        assertSame(cancel, one(map("text", map("regex", "^Can"))));
        assertSame(label, one(map("text", "Total due")), "HTML label text is matched as plain text");
        assertEquals(2, find(map("text", map("regex", "^OK$"))).size());
    }

    @Test
    void classMatchesSimpleOrQualifiedName() throws Exception {
        assertSame(pin, one(map("class", "JPasswordField")));
        assertSame(pin, one(map("class", "javax.swing.JPasswordField")));
        assertEquals(4, find(map("class", "JButton")).size());
    }

    @Test
    void pathWalksChildIndexes() throws Exception {
        assertSame(root, one(map("path", "")));
        assertSame(entry, one(map("path", "3")));
        assertSame(innerOk, one(map("path", "6/0")));
        assertTrue(find(map("path", "6/5")).isEmpty());
        assertTrue(find(map("path", "0/0/0")).isEmpty());
    }

    @Test
    void refResolvesThroughTheHandleRegistry() throws Exception {
        String ref = ctx.handles.handleFor(entry, ComponentView.KIND);
        assertSame(entry, one(map("ref", ref)));
        expectError(ErrorCodes.STALE_HANDLE, () -> one(map("ref", "h424242")));
    }

    @Test
    void roleAndIndexNarrowTheMatches() throws Exception {
        assertSame(label, one(map("text", map("contains", "Total"), "role", "label")));
        assertSame(innerOk, one(map("text", "OK", "index", 1)));
        assertSame(ok, one(map("text", "OK", "index", 0)));
        RpcException e = expectError(ErrorCodes.NOT_FOUND, () -> one(map("text", "OK", "index", 2)));
        assertEquals(2L, e.error().data().get("count"));
        assertTrue(find(map("text", "OK", "role", "textbox")).isEmpty());
    }

    @Test
    void ambiguousListsCandidates() throws Exception {
        RpcException e = expectError(ErrorCodes.AMBIGUOUS, () -> one(map("text", "OK")));
        assertEquals(2L, e.error().data().get("count"));
        List<Object> candidates = arr(e.error().data().get("candidates"));
        assertEquals(2, candidates.size());
        assertEquals("OK", JsonCheck.obj(candidates.get(0)).get("text"));
        assertEquals(map("text", "OK"), e.error().data().get("locator"));
    }

    @Test
    void ambiguousCandidatesAreCappedAtTwenty() throws Exception {
        onEdt(() -> {
            for (int i = 0; i < 30; i++) {
                root.add(new JButton("Same"));
            }
            return null;
        });
        RpcException e = expectError(ErrorCodes.AMBIGUOUS, () -> one(map("text", "Same")));
        assertEquals(30L, e.error().data().get("count"));
        assertEquals(20, arr(e.error().data().get("candidates")).size());
    }

    @Test
    void notFoundCarriesTheLocator() throws Exception {
        RpcException e = expectError(ErrorCodes.NOT_FOUND, () -> one(map("name", "nope")));
        assertEquals(map("name", "nope"), e.error().data().get("locator"));
    }

    @Test
    void nameEnrichersComeFirst() throws Exception {
        ctx.spi.register(NameEnricher.class, c -> c == icon ? "DUE" : null);
        assertSame(icon, one(map("name", "DUE")));
        assertEquals("DUE", onEdt(() -> new ComponentView(ctx).name(icon)));
        assertNull(onEdt(() -> new ComponentView(ctx).name(inner)));
    }

    @Test
    void customStrategiesUseRegisteredResolvers() throws Exception {
        final AtomicReference<String> asked = new AtomicReference<String>();
        ctx.spi.register(LocatorResolver.class, new LocatorResolver() {
            @Override
            public Set<String> strategies() {
                return Collections.singleton("action");
            }

            @Override
            public List<Component> resolve(String strategy, String value, Component r) {
                asked.set(strategy + "=" + value);
                List<Component> out = new ArrayList<Component>();
                if ("DUE".equals(value)) {
                    out.add(icon);
                    out.add(icon); // duplicates are dropped
                    out.add(new JButton("outside the root")); // not under the root: dropped
                }
                return out;
            }
        });
        assertSame(icon, one(map("action", "DUE")));
        assertEquals("action=DUE", asked.get());
        assertTrue(find(map("action", "DEPOSIT")).isEmpty());
        assertSame(icon, one(map("action", "DUE", "role", "button")));
    }

    @Test
    void unknownStrategyIsInvalidParams() {
        RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> find(map("bogus", "x")));
        assertEquals(list(), e.error().data().get("registered"));
        assertTrue(e.getMessage().contains("bogus"));
    }

    @Test
    void malformedLocatorsAreInvalidParams() {
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse("okButton", "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map(), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("name", "a", "text", "b"), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("name", null), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("name", map("regex", "(")), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("name", map("startsWith", "a")), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("name", map("contains", "a",
                "regex", "b")), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("class", map("contains", "J")), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("path", "0/a"), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("path", "/0"), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("name", "a", "role", "widget"), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("name", "a", "index", -1L), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("name", "a", "index", 1.5), "by"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Locator.parse(map("action", list("x")), "by"));
    }

    @Test
    void parsedLocatorKeepsItsParts() {
        Locator loc = Locator.parse(map("name", map("contains", "ok"), "role", "button", "index", 2L), "by");
        assertEquals("name", loc.strategy());
        assertEquals("button", loc.role());
        assertEquals(Integer.valueOf(2), loc.index());
        assertTrue(loc.match().test("my ok button"));
        assertFalse(loc.match().test(null));
        assertTrue(loc.builtIn());
        Locator custom = Locator.parse(map("action", 7L), "by");
        assertEquals("7", custom.value());
        assertFalse(custom.builtIn());
    }

    @Test
    void hiddenComponentsMatchOnlyWithIncludeHidden() throws Exception {
        final Locator loc = Locator.parse(map("name", "entry"), "by");
        List<Component> showingOnly = onEdt(() -> new LocatorEngine(new ComponentView(ctx)).find(loc, root, false));
        assertTrue(showingOnly.isEmpty(), "nothing is showing in a never-shown tree");
        assertFalse(find(map("name", "entry")).isEmpty());
    }

    @Test
    void rootRestrictsTheSearch() throws Exception {
        final Locator loc = Locator.parse(map("text", "OK"), "by");
        List<Component> under = onEdt(() -> new LocatorEngine(new ComponentView(ctx)).find(loc, inner, true));
        assertEquals(Collections.<Component>singletonList(innerOk), under);
        assertTrue(inner instanceof Container);
    }

    static <T> T onEdt(final Callable<T> body) throws Exception {
        final AtomicReference<T> value = new AtomicReference<T>();
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(body.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return value.get();
    }
}
