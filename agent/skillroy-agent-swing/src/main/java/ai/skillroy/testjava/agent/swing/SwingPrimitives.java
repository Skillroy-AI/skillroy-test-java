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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.JsonView;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The {@code swing.*} primitives of contract section 6. Every primitive runs on the {@code CALLER} thread and makes
 * its own EDT hops: reads through bounded {@code EDT_SYNC} calls with the call's remaining time, actions posted with
 * {@code EDT_ASYNC}. Each except {@code swing.edtState} names a capability, so the dispatcher rejects it while that
 * capability is UNAVAILABLE (AWT not started yet, or headless); {@code swing.edtState} reports in every state without
 * starting the toolkit. {@code swing.click}, {@code swing.type} and {@code swing.key} with an explicit target fail
 * with {@code MODAL_OPEN} when a modal dialog blocks the target's window, or a blocking dialog drawn inside that window
 * does not contain the target (see {@link Windows#requireNotBlocked}), unless {@code force} is true.
 * {@code swing.dialogs} and {@code swing.dismiss} also cover the dialogs registered {@code DialogLocator}s report
 * inside windows (amendment A27).
 *
 * <p>Thread-safe: no mutable state of its own; SPIs are looked up per call.
 */
final class SwingPrimitives {

    /**
     * Most snapshot levels below a root. A node sits two JSON levels below its parent and the wire types accept 184
     * levels: a node at level 89 is at JSON level 180, which leaves room for the deepest thing a node carries (a
     * table's value: the value object, its cell rows, one row).
     */
    static final int MAX_DEPTH = 89;
    static final int DEFAULT_DEPTH = 40;
    static final int DEFAULT_NODES = 2000;
    /** Upper bound on maxNodes, so one snapshot cannot occupy the EDT for long. */
    static final int MAX_NODES = 50000;
    static final long DEFAULT_SETTLE_MS = 5000;
    static final long MAX_SETTLE_MS = 120000;
    static final long DEFAULT_QUIET_MS = 50;
    static final long DEFAULT_FOCUS_MS = 500;

    private final AgentContext ctx;
    private final Windows windows;
    private final WindowTap tap;
    private final Clicks clicks;
    private final Typing typing;
    private final EdtWatch watch;

    private SwingPrimitives(AgentContext ctx, Windows windows, WindowTap tap, AwtState awt) {
        this.ctx = ctx;
        this.windows = windows;
        this.tap = tap;
        this.clicks = new Clicks(ctx, windows);
        this.typing = new Typing(ctx, windows);
        this.watch = new EdtWatch(ctx, tap, awt);
    }

    /**
     * Builds every primitive of the module, in name order. Touches no AWT class: the primitives call into AWT only
     * when invoked, and the dispatcher invokes the AWT-dependent ones only once their capability is available.
     */
    static List<Primitive> create(AgentContext ctx, Windows windows, WindowTap tap, AwtState awt) {
        return new SwingPrimitives(ctx, windows, tap, awt).all();
    }

    private List<Primitive> all() {
        List<Primitive> list = new ArrayList<Primitive>();
        list.add(Primitives.of(PrimitiveSpec.builder("swing.capture")
                .description("PAINT capture of a window or component (printAll into an image; works when the "
                        + "window is covered). Default target: the active window, else the topmost modal dialog, "
                        + "else the first showing window. Scaled down to fit 4096 x 4096. A component whose printing "
                        + "throws (an embedded browser's GL canvas) is painted as a placeholder rectangle and listed "
                        + "in 'skipped'.")
                .param("target", "string", false, "ref of the window or component to capture")
                .param("mode", "string", false, "PAINT (default; the only mode in this version)")
                .capability(SwingModule.CAP_CAPTURE_PAINT).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return capture(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.click")
                .description("Clicks a component. Rungs: ACTION (ActionFirer, else doClick, else a dispatched "
                        + "click; needs no focus), COMPONENT (optionally activate the window, then "
                        + "requestFocusInWindow, wait for focus, then doClick; reports rung FOCUS_FAILED and clicks "
                        + "nothing if focus never arrives, unless fallback ACTION fires it through the ACTION rung "
                        + "with fidelity DEGRADED), DISPATCH (mouse press/release/click via dispatchEvent), ROBOT "
                        + "(unavailable). AUTO = ACTION. A click that fired reports rung, requestedRung and fidelity "
                        + "(FULL on the rung asked for). Fails with MODAL_OPEN when a modal dialog blocks the "
                        + "component's window or a blocking in-window dialog does not contain it, unless force. "
                        + "Returns once the click is posted; follow with swing.settle.")
                .param("target", "string|object", false, "ref of the component (or a locator object)")
                .param("by", "object", false, "locator of the component (instead of target)")
                .param("rung", "string", false, "AUTO (default), ACTION, COMPONENT, DISPATCH or ROBOT")
                .param("focusTimeoutMs", "integer", false, "COMPONENT: how long to wait for focus, window "
                        + "activation included (default 500)")
                .param("activateWindow", "boolean", false, "COMPONENT: first bring the component's window to the "
                        + "front, request the focus for it and wait for it to be the focused window, re-issuing the "
                        + "activation once a second while waiting (default false)")
                .param("force", "boolean", false, "click even if a modal dialog blocks the component's window "
                        + "(default false: MODAL_OPEN)")
                .param("fallback", "string", false, "COMPONENT only: ACTION fires the click through the ACTION rung "
                        + "when the focus does not arrive (result fidelity DEGRADED); default none (FOCUS_FAILED)")
                .capability(SwingModule.CAP_TREE).mutating().build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return click(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.dialogs")
                .description("Lists showing dialogs with title, modality, text and buttons; registered "
                        + "DialogTextExtractors first, else the JOptionPane message or the labels in reading order. "
                        + "Dialog windows first, then the dialogs registered DialogLocators report inside windows "
                        + "(inWindow: true, with the ref of their window).")
                .capability(SwingModule.CAP_TREE).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return dialogs(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.dismiss")
                .description("Answers a dialog (default: the topmost modal dialog window, else an in-window dialog "
                        + "when it is the only dialog showing): 'button' presses the button with that text (exact, "
                        + "then case-insensitive) through the ACTION rung; 'close' delivers WINDOW_CLOSING like the "
                        + "title-bar close box (dialog windows only). Never disposes or hides the dialog itself. "
                        + "Without either, a dialog with exactly one button has it pressed.")
                .param("dialog", "string", false, "ref of the dialog (a java.awt.Dialog, or the root of an in-window "
                        + "dialog a DialogLocator reports); default the topmost modal dialog")
                .param("button", "string", false, "text of the button to press")
                .param("close", "boolean", false, "close the dialog the way its close box would")
                .capability(SwingModule.CAP_TREE).mutating().build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return dismiss(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.edtState")
                .description("Health of the event dispatch thread: alive, blocked (marker not run within 500 ms), "
                        + "showing modal dialogs, window count, focus owner, and whether the application has "
                        + "started AWT at all. Never waits on the EDT for more than 500 ms; names no capability and "
                        + "never starts the toolkit or a dispatch thread, so it answers before AWT runs and in a "
                        + "headless JVM.")
                .build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return watch.edtState(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.find")
                .description("Finds components by locator. Without 'all', exactly one must match (NOT_FOUND, "
                        + "AMBIGUOUS with candidates); with 'all', every match is returned.")
                .param("by", "object", true, "locator: exactly one of {ref}, {id}, {name}, {text}, {class}, {path} "
                        + "(child indexes like \"0/2/1\") or a registered strategy, plus optional role and index; "
                        + "name and text accept a string (exact) or {contains} / {regex}")
                .param("root", "string", false, "ref of the component to search under; default every showing window")
                .param("all", "boolean", false, "return every match instead of exactly one")
                .param("includeHidden", "boolean", false, "also match components that are not showing")
                .capability(SwingModule.CAP_TREE).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return find(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.key")
                .description("Delivers a key press (pressed, typed, released) with dispatchEvent, so it reaches the "
                        + "target without focus. Default target: the focus owner, else the active window, else the "
                        + "topmost modal dialog, else the first showing window; a focus owner in a window that a "
                        + "modal dialog blocks is replaced by that dialog's focus component (a window is replaced "
                        + "by its root pane, where window-wide key bindings live). An explicit target in a window "
                        + "a modal dialog blocks, or outside a blocking in-window dialog, fails with MODAL_OPEN "
                        + "unless force.")
                .param("key", "string", true, "KeyEvent.VK_ name without the prefix, e.g. ENTER, ESCAPE, F2, A")
                .param("target", "string|object", false, "ref of the target component (or a locator object)")
                .param("by", "object", false, "locator of the target component (instead of target)")
                .param("modifiers", "array", false, "any of SHIFT, CTRL, ALT, META")
                .param("force", "boolean", false, "deliver even if a modal dialog blocks the target's window "
                        + "(default false: MODAL_OPEN)")
                .capability(SwingModule.CAP_TREE).mutating().build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return key(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.settle")
                .description("Waits until two consecutive EDT marker round trips complete with no window.opened or "
                        + "window.closed between them and none for quietMs after; bounded by timeoutMs. Each marker "
                        + "also compares the showing windows with those reported, so a window hidden or shown again "
                        + "counts as window activity.")
                .param("timeoutMs", "integer", false, "upper bound (default 5000, at most 120000)")
                .param("quietMs", "integer", false, "quiet period after the second marker (default 50)")
                .timeoutMs(MAX_SETTLE_MS + 1000)
                .capability(SwingModule.CAP_TREE).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return settle(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.snapshot")
                .description("The component tree of every showing window (or of one root) as Nodes with stable "
                        + "refs, plus the event sequence number at snapshot time.")
                .param("root", "string", false, "ref of the window or component to snapshot")
                .param("maxDepth", "integer", false, "levels below each root (default " + DEFAULT_DEPTH
                        + ", at most " + MAX_DEPTH + ")")
                .param("maxNodes", "integer", false, "most nodes in the result (default 2000)")
                .param("includeHidden", "boolean", false, "also include windows and components that are not showing")
                .capability(SwingModule.CAP_TREE).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return snapshot(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.type")
                .description("Types text into a component. SET sets the text model (then fires the field's action "
                        + "if 'enter'); DISPATCH delivers key events per character with dispatchEvent (then ENTER "
                        + "if 'enter'). Fails with MODAL_OPEN when a modal dialog blocks the component's window or a "
                        + "blocking in-window dialog does not contain it, unless force. Returns once the input is "
                        + "posted; follow with swing.settle.")
                .param("target", "string|object", false, "ref of the component (or a locator object)")
                .param("by", "object", false, "locator of the component (instead of target)")
                .param("text", "string", true, "the text to type")
                .param("mode", "string", false, "SET (default) or DISPATCH (at most " + Typing.MAX_DISPATCH_CHARS
                        + " characters)")
                .param("clear", "boolean", false, "replace the current text (default true)")
                .param("enter", "boolean", false, "press ENTER afterwards")
                .param("force", "boolean", false, "type even if a modal dialog blocks the component's window "
                        + "(default false: MODAL_OPEN)")
                .capability(SwingModule.CAP_TREE).mutating().build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return type(inv);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("swing.windows")
                .description("Displayable top-level windows with ref, type, title, name, state and screen bounds.")
                .capability(SwingModule.CAP_TREE).build(), new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) throws Exception {
                        return windowList(inv);
                    }
                }));
        return list;
    }

    // ---------------------------------------------------------------------------------------------------- reads

    private Object windowList(Invocation inv) throws Exception {
        return Edt.read(ctx, inv.remainingMs(), new Callable<Object>() {
            @Override
            public Object call() {
                tap.sync();
                ComponentView view = new ComponentView(ctx);
                List<Object> out = new ArrayList<Object>();
                for (Window w : Windows.displayable()) {
                    out.add(view.window(w));
                }
                return single("windows", out);
            }
        });
    }

    private Object snapshot(Invocation inv) throws Exception {
        JsonView a = inv.args();
        long depth = a.num("maxDepth", DEFAULT_DEPTH);
        long nodes = a.num("maxNodes", DEFAULT_NODES);
        if (depth < 0) {
            throw Errors.invalid("maxDepth must not be negative", Errors.data("param", "maxDepth"));
        }
        if (nodes < 1) {
            throw Errors.invalid("maxNodes must be at least 1", Errors.data("param", "maxNodes"));
        }
        final int maxDepth = (int) Math.min(depth, MAX_DEPTH);
        final int maxNodes = (int) Math.min(nodes, MAX_NODES);
        final boolean includeHidden = a.bool("includeHidden", false);
        String rootRef = a.str("root");
        final Component root = rootRef == null ? null : ctx.handles().resolve(rootRef, Component.class);
        return Edt.read(ctx, inv.remainingMs(), new Callable<Object>() {
            @Override
            public Object call() {
                tap.sync(); // first, so that seq covers the windows hidden or shown again since the last look
                long seq = ctx.events().lastSeq();
                ComponentView view = new ComponentView(ctx);
                SnapshotWalker walker = new SnapshotWalker(view, maxDepth, maxNodes, includeHidden);
                List<Component> roots = new ArrayList<Component>();
                if (root != null) {
                    roots.add(root);
                } else {
                    roots.addAll(Windows.roots(includeHidden));
                }
                List<Object> out = new ArrayList<Object>();
                for (Component r : roots) {
                    Map<String, Object> node = walker.walk(r);
                    if (node == null) {
                        break;
                    }
                    out.add(node);
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("seq", Long.valueOf(seq));
                m.put("truncated", Boolean.valueOf(walker.truncated()));
                m.put("windows", out);
                return m;
            }
        });
    }

    private Object find(Invocation inv) throws Exception {
        JsonView a = inv.args();
        Object by = a.get("by");
        if (by == null) {
            throw Errors.invalid("missing required parameter 'by'", Errors.data("param", "by"));
        }
        final Locator loc = Locator.parse(by, "by");
        final boolean all = a.bool("all", false);
        final boolean includeHidden = a.bool("includeHidden", false);
        String rootRef = a.str("root");
        final Component root = rootRef == null ? null : ctx.handles().resolve(rootRef, Component.class);
        return Edt.read(ctx, inv.remainingMs(), new Callable<Object>() {
            @Override
            public Object call() {
                ComponentView view = new ComponentView(ctx);
                LocatorEngine engine = new LocatorEngine(view);
                List<Component> found = engine.find(loc, root, includeHidden);
                List<Component> chosen;
                if (all && loc.index() == null) {
                    chosen = found;
                } else if (all) {
                    int i = loc.index().intValue();
                    chosen = i < found.size() ? Collections.singletonList(found.get(i))
                            : Collections.<Component>emptyList();
                } else {
                    chosen = Collections.singletonList(engine.single(loc, found));
                }
                List<Object> matches = new ArrayList<Object>();
                for (Component c : chosen) {
                    matches.add(view.node(c));
                }
                return single("matches", matches);
            }
        });
    }

    private Object dialogs(Invocation inv) throws Exception {
        return Edt.read(ctx, inv.remainingMs(), new Callable<Object>() {
            @Override
            public Object call() {
                tap.sync();
                DialogReader reader = new DialogReader(new ComponentView(ctx));
                List<Object> out = new ArrayList<Object>();
                for (Dialog d : Windows.showingDialogs()) {
                    out.add(reader.describe(d));
                }
                InWindowDialogs inWindow = new InWindowDialogs(ctx);
                for (InWindowDialogs.Found f : inWindow.all()) {
                    out.add(reader.describeInWindow(f, inWindow.blocksInput(f)));
                }
                return single("dialogs", out);
            }
        });
    }

    private Object capture(Invocation inv) throws Exception {
        JsonView a = inv.args();
        String mode = upper(a.str("mode", "PAINT"));
        if (!"PAINT".equals(mode)) {
            throw Errors.invalid("unknown capture mode '" + a.str("mode") + "': only PAINT is available",
                    Errors.data("param", "mode"));
        }
        String ref = a.str("target");
        final Component target = ref == null ? null : ctx.handles().resolve(ref, Component.class);
        final Object[] skipped = new Object[1];
        BufferedImage image = Edt.read(ctx, inv.remainingMs(), new Callable<BufferedImage>() {
            @Override
            public BufferedImage call() {
                ComponentView view = new ComponentView(ctx);
                Component c = target != null ? target : windows.defaultWindow();
                if (c == null) {
                    throw Errors.notFound("nothing to capture: no window is showing", Errors.data());
                }
                if (!c.isShowing()) {
                    throw Errors.invalid(view.describe(c) + " is not showing", Errors.data("ref", view.ref(c),
                            "reason", "not showing"));
                }
                List<Capture.Skipped> parts = new ArrayList<Capture.Skipped>();
                BufferedImage painted = Capture.paint(c, parts);
                if (!parts.isEmpty()) {
                    synchronized (skipped) {
                        skipped[0] = skippedJson(view, parts);
                    }
                }
                return painted;
            }
        });
        byte[] png = Capture.png(image);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("format", "png");
        m.put("width", Long.valueOf(image.getWidth()));
        m.put("height", Long.valueOf(image.getHeight()));
        m.put("base64", Base64.getEncoder().encodeToString(png));
        synchronized (skipped) {
            if (skipped[0] != null) {
                m.put("skipped", skipped[0]);
            }
        }
        return m;
    }

    /**
     * {@code [{ref, className, error}]} of the parts a capture painted as placeholders (P4 sections 2.5 and 14.1).
     * EDT only.
     */
    static List<Map<String, Object>> skippedJson(ComponentView view, List<Capture.Skipped> parts) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Capture.Skipped s : parts) {
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("ref", view.ref(s.component));
            one.put("className", s.component.getClass().getName());
            one.put("error", s.error);
            out.add(one);
        }
        return out;
    }

    private Object settle(Invocation inv) throws Exception {
        JsonView a = inv.args();
        long timeout = a.num("timeoutMs", DEFAULT_SETTLE_MS);
        long quiet = a.num("quietMs", DEFAULT_QUIET_MS);
        if (timeout < 0 || quiet < 0) {
            throw Errors.invalid("timeoutMs and quietMs must not be negative", Errors.data("param",
                    timeout < 0 ? "timeoutMs" : "quietMs"));
        }
        return watch.settle(inv, Math.min(timeout, MAX_SETTLE_MS), Math.min(quiet, MAX_SETTLE_MS));
    }

    // -------------------------------------------------------------------------------------------------- actions

    private Object click(Invocation inv) throws Exception {
        JsonView a = inv.args();
        String rung = upper(a.str("rung", Clicks.AUTO));
        if (!Clicks.RUNGS.contains(rung)) {
            throw Errors.invalid("unknown rung '" + a.str("rung") + "': expected one of " + Clicks.RUNGS,
                    Errors.data("param", "rung"));
        }
        boolean fallback = fallback(a, rung);
        if (Clicks.ROBOT.equals(rung)) {
            throw unavailable(SwingModule.CAP_CLICK_ROBOT, SwingModule.ROBOT_REASON);
        }
        String effective = Clicks.AUTO.equals(rung) ? Clicks.ACTION : rung;
        requireCapability(SwingModule.CAP_CLICK_PREFIX + effective.toLowerCase(Locale.ROOT), null);
        if (fallback) {
            requireCapability(SwingModule.CAP_CLICK_ACTION, null);
        }
        long focusTimeout = a.num("focusTimeoutMs", DEFAULT_FOCUS_MS);
        if (focusTimeout < 0) {
            throw Errors.invalid("focusTimeoutMs must not be negative", Errors.data("param", "focusTimeoutMs"));
        }
        boolean activate = a.bool("activateWindow", false);
        boolean force = a.bool("force", false);
        TargetSpec target = TargetSpec.parse(a, true);
        return clicks.click(inv, target, rung, effective, focusTimeout, activate, force, fallback);
    }

    /**
     * The {@code fallback} parameter of {@code swing.click}: absent (or null) is none; {@code "ACTION"}
     * (case-insensitive) is accepted with rung COMPONENT only.
     *
     * @throws ai.skillroy.testjava.protocol.RpcException INVALID_PARAMS for another value, or with another rung
     */
    static boolean fallback(JsonView a, String rung) {
        String given = a.str("fallback");
        if (given == null) {
            return false;
        }
        if (!Clicks.ACTION.equals(upper(given))) {
            throw Errors.invalid("unknown fallback '" + given + "': the only accepted value is ACTION",
                    Errors.data("param", "fallback"));
        }
        if (!Clicks.COMPONENT.equals(rung)) {
            throw Errors.invalid("fallback ACTION is accepted with rung COMPONENT only, not " + rung,
                    Errors.data("param", "fallback", "rung", rung));
        }
        return true;
    }

    private Object type(Invocation inv) throws Exception {
        JsonView a = inv.args();
        String mode = upper(a.str("mode", Typing.SET));
        if (!Typing.SET.equals(mode) && !Typing.DISPATCH.equals(mode)) {
            throw Errors.invalid("unknown mode '" + a.str("mode") + "': expected SET or DISPATCH",
                    Errors.data("param", "mode"));
        }
        String text = a.requireStr("text");
        boolean clear = a.bool("clear", true);
        boolean enter = a.bool("enter", false);
        boolean force = a.bool("force", false);
        TargetSpec target = TargetSpec.parse(a, true);
        return typing.type(inv, target, mode, text, clear, enter, force);
    }

    private Object key(Invocation inv) throws Exception {
        JsonView a = inv.args();
        int code = Keys.code(a.requireStr("key"));
        int modifiers = Keys.modifiers(a.list("modifiers"));
        boolean force = a.bool("force", false);
        TargetSpec target = TargetSpec.parse(a, false);
        return typing.key(inv, target, code, modifiers, force);
    }

    private Object dismiss(Invocation inv) throws Exception {
        JsonView a = inv.args();
        final String button = a.str("button");
        final boolean close = a.bool("close", false);
        if (button != null && close) {
            throw Errors.invalid("give either 'button' or 'close', not both", Errors.data("param", "button"));
        }
        String ref = a.str("dialog");
        final Component given = ref == null ? null : ctx.handles().resolve(ref, Component.class);
        final String[] pressed = new String[1];
        Edt.Action plan = Edt.read(ctx, inv.remainingMs(), new Callable<Edt.Action>() {
            @Override
            public Edt.Action call() {
                ComponentView view = new ComponentView(ctx);
                final Component d = dialogToDismiss(view, given);
                if (close) {
                    if (!(d instanceof Window)) {
                        throw Errors.invalid("an in-window dialog has no window to close; name a button",
                                Errors.data("param", "close", "dialog", view.ref(d), "inWindow", Boolean.TRUE));
                    }
                    // only dialogs: delivering WINDOW_CLOSING to a frame may run the application's exit handling
                    final Window w = (Window) d;
                    return new Edt.Action() {
                        @Override
                        public void run() {
                            w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
                        }
                    };
                }
                DialogReader reader = new DialogReader(view);
                Component b;
                if (button != null) {
                    b = reader.findButton(d, button);
                } else {
                    List<String> labels = reader.buttonLabels(d);
                    if (labels.size() != 1) {
                        throw Errors.invalid(view.describe(d) + " has " + labels.size() + " buttons; give 'button' "
                                + "or 'close'", Errors.data("dialog", view.ref(d), "buttons",
                                new ArrayList<Object>(labels)));
                    }
                    b = reader.findButton(d, labels.get(0));
                }
                Clicks.requireClickable(view, b);
                pressed[0] = button != null ? labelFor(view, b, button) : reader.buttonLabels(d).get(0);
                return Clicks.actionPlan(ctx, b);
            }
        });
        Edt.post(ctx, inv.remainingMs(), "swing.dismiss", plan);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dismissedBy", close ? "CLOSE" : "BUTTON_ACTION");
        if (!close) {
            m.put("button", pressed[0]);
        }
        return m;
    }

    /**
     * The dialog {@code swing.dismiss} acts on, chosen on the EDT: the given one, which must be a showing
     * {@code java.awt.Dialog} or the showing root of an in-window dialog a {@code DialogLocator} reports; else the
     * topmost modal dialog window; else the only dialog showing when that is an in-window dialog.
     *
     * @return a {@code Dialog} or an in-window dialog root
     * @throws ai.skillroy.testjava.protocol.RpcException NOT_FOUND when no dialog qualifies, AMBIGUOUS (with
     *     {@code data.dialogs}) when several dialogs show and none is modal, INVALID_PARAMS for a component that is
     *     not a dialog or not showing
     */
    private Component dialogToDismiss(ComponentView view, Component given) {
        InWindowDialogs inWindow = new InWindowDialogs(ctx);
        if (given != null) {
            if (!given.isShowing()) {
                throw Errors.invalid(view.describe(given) + " is not showing", Errors.data("ref", view.ref(given),
                        "reason", "not showing"));
            }
            if (given instanceof Dialog || inWindow.find(given) != null) {
                return given;
            }
            throw Errors.invalid(view.describe(given) + " is not a dialog: neither a java.awt.Dialog nor an in-window"
                    + " dialog a DialogLocator reports", Errors.data("param", "dialog", "ref", view.ref(given)));
        }
        Window modal = windows.topmostModal();
        if (modal != null) {
            return modal;
        }
        List<InWindowDialogs.Found> drawn = inWindow.all();
        List<Dialog> dialogWindows = Windows.showingDialogs();
        if (drawn.isEmpty()) {
            throw Errors.notFound("no modal dialog is showing", Errors.data());
        }
        if (drawn.size() == 1 && dialogWindows.isEmpty()) {
            return drawn.get(0).root;
        }
        List<Object> refs = new ArrayList<Object>();
        for (Dialog d : dialogWindows) {
            refs.add(view.ref(d));
        }
        for (InWindowDialogs.Found f : drawn) {
            refs.add(view.ref(f.root));
        }
        throw Errors.ambiguous(refs.size() + " dialogs are showing and none of them is a modal dialog window; name"
                + " the one to answer with 'dialog'", Errors.data("dialogs", refs));
    }

    /** The label the button actually carries (its text or name), falling back to what the caller asked for. */
    private static String labelFor(ComponentView view, Component b, String asked) {
        String text = ComponentText.text(b);
        if (text != null && text.equalsIgnoreCase(asked)) {
            return text;
        }
        String name = view.name(b);
        if (name != null && name.equalsIgnoreCase(asked)) {
            return name;
        }
        return asked;
    }

    // -------------------------------------------------------------------------------------------------- helpers

    private void requireCapability(String id, String fallbackReason) {
        Capability cap = ctx.capabilities().get(id);
        if (cap != null && cap.state() == CapabilityState.UNAVAILABLE) {
            throw unavailable(id, cap.reason() != null ? cap.reason() : fallbackReason);
        }
    }

    private RuntimeException unavailable(String id, String fallbackReason) {
        Capability cap = ctx.capabilities().get(id);
        String reason = cap != null && cap.reason() != null ? cap.reason() : fallbackReason;
        return Errors.unavailable(id, reason == null ? "unavailable" : reason);
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(key, value);
        return m;
    }
}
