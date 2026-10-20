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
import ai.skillroy.testjava.agent.api.spi.InputVeto;

import java.awt.Component;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JPopupMenu;

/**
 * The registered {@link InputVeto}s as the module asks them (Phase 8, phase8-contracts.md 3.2, D501-D506): for a
 * component and each of its ancestors up to and including its window, nearest first; the first refusal wins and names
 * the component that refused. Memoised per instance and route, so a walk asks each veto once per component; one
 * instance serves one call's read (a posted task and a pointer event take a fresh one).
 *
 * <p>A popup is asked with its invoker's chain (fix wave of the Phase 8 review, FA, L2-F2): the walk continues from a
 * {@code JPopupMenu} with an invoker to the invoker, and from a {@code Window.Type.POPUP} window with an owner to the
 * owner, so a window, combo or menu veto covers the items of the popups drawn as windows of their own or in the
 * layered pane; {@link #next} is the one successor every walk of the module uses (the depth bound, the chain, the
 * default buttons of {@code Typing}).
 *
 * <p>Fail closed: a veto that throws refuses with the text {@code "veto <SimpleClassName> failed"}, logged once per
 * instance ({@code VirtualMachineError}s other than {@code StackOverflowError} are rethrown); a component that lies
 * more than {@value #MAX_DEPTH} levels below its window is refused, because not every ancestor could be asked. A
 * veto's text is sanitised: control characters become spaces, the text is trimmed to one line and cut at
 * {@value #MAX_TEXT} characters, and an empty text becomes {@code "vetoed by <SimpleClassName>"}.
 *
 * <p>EDT only (the vetoes are asked inside the call's bounded read or posted task); not thread-safe.
 */
final class InputVetoes {

    /** Most components asked per check: the component and its ancestors (Windows.hidden's bound). */
    static final int MAX_DEPTH = 64;
    /** Longest veto text kept. */
    static final int MAX_TEXT = 200;
    /**
     * The agent's own refusal when the covering list at a pointer's point is full (Phase 8 security fixes, S155,
     * S156, FA): a further window, unasked, could be the one on top.
     */
    static final String TOO_MANY_WINDOWS = "at least " + HitTest.MAX_COVERING + " other windows of the JVM lie at "
            + "the point; the input veto cannot be asked for every one";

    /** Vetoes that have thrown once already (logged once per instance, never again). */
    private static final Map<InputVeto, Boolean> LOGGED =
            Collections.synchronizedMap(new WeakHashMap<InputVeto, Boolean>());
    /** The memo's "allowed" entry. */
    private static final Refusal ALLOWED = new Refusal(null, null, null);

    private final AgentContext ctx;
    private final List<InputVeto> vetoes;
    private final Map<String, Map<Component, Refusal>> memo = new HashMap<String, Map<Component, Refusal>>();

    InputVetoes(AgentContext ctx) {
        this.ctx = ctx;
        List<InputVeto> registered = ctx == null || ctx.spi() == null ? null : ctx.spi().all(InputVeto.class);
        this.vetoes = registered == null ? Collections.<InputVeto>emptyList() : registered;
    }

    /** True when at least one veto is registered (nothing to ask otherwise). */
    boolean any() {
        return !vetoes.isEmpty();
    }

    /** One refusal: the component whose veto refused (the one asked about or an ancestor), the text and the route. */
    static final class Refusal {
        final Component component;
        final String text;
        final String route;

        Refusal(Component component, String text, String route) {
            this.component = component;
            this.text = text;
            this.route = route;
        }
    }

    /**
     * The per-event check of the routed pointer (phase8-contracts.md 3.2 rule 6, D505), a seam beside
     * {@code Pointer.Plan.blocked}: the refusal for the component AWT will deliver the event to, or null.
     */
    interface EventVeto {
        Refusal check(MouseEvent e);
    }

    /**
     * The refusal for input on the route to the component: the nearest refusal on its ancestor chain up to and
     * including its window, or null when every veto allows every one of them. EDT only.
     */
    Refusal check(Component c, String route) {
        if (c == null || vetoes.isEmpty()) {
            return null;
        }
        Map<Component, Refusal> known = memo.get(route);
        if (known == null) {
            known = new IdentityHashMap<Component, Refusal>();
            memo.put(route, known);
        }
        int depth = 0;
        for (Component p = c; p != null; p = next(p)) {
            depth++;
            if (depth > MAX_DEPTH) {
                return new Refusal(c, "the component lies more than " + MAX_DEPTH + " levels below its window; the "
                        + "input veto cannot be asked for every ancestor", route);
            }
        }
        Refusal r = chain(c, route, known);
        return r == ALLOWED ? null : r;
    }

    /** True when input on any route would be refused (the readers' flag, route {@link InputVeto#ANY}). EDT only. */
    boolean vetoed(Component c) {
        return check(c, InputVeto.ANY) != null;
    }

    private Refusal chain(Component c, String route, Map<Component, Refusal> known) {
        Refusal r = known.get(c);
        if (r != null) {
            return r;
        }
        r = own(c, route);
        if (r == null) {
            Component up = next(c);
            r = up == null ? ALLOWED : chain(up, route, known);
        }
        known.put(c, r);
        return r;
    }

    /**
     * The next component the veto is asked about after {@code c} (phase8-contracts.md 3.2 rule 1; fix wave of the
     * Phase 8 review, FA, L2-F2), or null where the walk ends: a {@code JPopupMenu} with an invoker continues with the
     * invoker (a popup menu, a combo's list, a context menu: its items are asked with the chain of the component that
     * opened it, whether the popup is drawn in the layered pane or as a window of its own); a window of type
     * {@code POPUP} with an owner (Swing's heavyweight popup window, which also passes the owner its window-wide key
     * bindings) continues with the owner; any other window ends the walk; anything else continues with its parent.
     * Both walks of {@link #check} use it, so the depth bound and the memoised chain agree; a cycle (an invoker inside
     * its own popup) ends at the depth bound, refused. EDT only.
     */
    static Component next(Component c) {
        if (c instanceof JPopupMenu) {
            Component invoker = ((JPopupMenu) c).getInvoker();
            if (invoker != null) {
                return invoker;
            }
        }
        if (c instanceof Window) {
            Window w = (Window) c;
            return w.getType() == Window.Type.POPUP ? w.getOwner() : null;
        }
        return c.getParent();
    }

    private Refusal own(Component c, String route) {
        for (InputVeto veto : vetoes) {
            String text;
            try {
                text = veto.refuse(c, route);
            } catch (Throwable t) {
                failed(veto, t);
                return new Refusal(c, "veto " + simpleName(veto) + " failed", route);
            }
            if (text != null) {
                return new Refusal(c, sanitise(text, veto), route);
            }
        }
        return null;
    }

    /** One line of at most {@value #MAX_TEXT} characters, control characters replaced; never empty. */
    static String sanitise(String text, Object veto) {
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_TEXT + 16));
        for (int i = 0; i < text.length() && sb.length() < MAX_TEXT * 4; i++) {
            char ch = text.charAt(i);
            sb.append(Character.isISOControl(ch) || ch == ' ' || ch == ' ' ? ' ' : ch);
        }
        String s = sb.toString().trim();
        if (s.length() > MAX_TEXT) {
            s = s.substring(0, MAX_TEXT);
            if (Character.isHighSurrogate(s.charAt(s.length() - 1))) {
                s = s.substring(0, s.length() - 1);
            }
            s = s.trim();
        }
        return s.isEmpty() ? "vetoed by " + simpleName(veto) : s;
    }

    /** The simple class name; for anonymous and local classes the last segment of the binary name. */
    static String simpleName(Object o) {
        Class<?> k = o.getClass();
        String simple = k.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        String full = k.getName();
        return full.substring(full.lastIndexOf('.') + 1);
    }

    private void failed(InputVeto veto, Throwable t) {
        if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) {
            throw (VirtualMachineError) t;
        }
        if (LOGGED.put(veto, Boolean.TRUE) == null && ctx != null) {
            try {
                // the exception's class only: its message may quote the application's text, and the agent log is
                // served by agent.log (fix wave of the Phase 8 review, FA; security review L8-F6)
                ctx.log().warn("swing: InputVeto " + veto.getClass().getName() + " failed ("
                        + t.getClass().getName() + "); the input is refused");
            } catch (Throwable ignored) {
                // the log must never be the reason a read fails
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- in-task

    /**
     * The in-task re-ask of one call (phase8-contracts.md 3.2 rule 4, D503): every posted input task asks again, with
     * a fresh evaluator, immediately before it acts; a refusal acts on nothing, is logged once per call at warn and
     * counted once per call in the module's {@code vetoWithheld}. The call has already answered "posted", so the
     * refusal is not reported in its result.
     */
    static final class Withheld {
        private final AgentContext ctx;
        private final AtomicLong counter;
        private final AtomicBoolean refused = new AtomicBoolean();

        Withheld(AgentContext ctx, AtomicLong counter) {
            this.ctx = ctx;
            this.counter = counter;
        }

        /**
         * Asks for each non-null component on the route; true when the task may act. EDT only.
         */
        boolean allows(String route, Component... targets) {
            InputVetoes vetoes = new InputVetoes(ctx);
            if (!vetoes.any()) {
                return true;
            }
            for (Component c : targets) {
                Refusal r = vetoes.check(c, route);
                if (r != null) {
                    if (refused.compareAndSet(false, true)) {
                        counter.incrementAndGet();
                        try {
                            ctx.log().warn("swing: " + route + " withheld inside the task: " + r.text);
                        } catch (Throwable ignored) {
                            // the log must never be the reason a task fails
                        }
                    }
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Wraps a posted input action in the in-task re-ask: the action runs only when no veto refuses the components on
     * the route at that moment.
     */
    static Edt.Action guarded(final Withheld withheld, final String route, final Edt.Action action,
                              final Component... targets) {
        return new Edt.Action() {
            @Override
            public void run() throws Exception {
                if (withheld.allows(route, targets)) {
                    action.run();
                }
            }
        };
    }
}
