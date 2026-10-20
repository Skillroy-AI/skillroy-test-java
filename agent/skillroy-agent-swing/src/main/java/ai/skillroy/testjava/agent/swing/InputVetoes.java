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

/**
 * The registered {@link InputVeto}s as the module asks them (Phase 8, phase8-contracts.md 3.2, D501-D506): for a
 * component and each of its ancestors up to and including its window, nearest first; the first refusal wins and names
 * the component that refused. Memoised per instance and route, so a walk asks each veto once per component; one
 * instance serves one call's read (a posted task and a pointer event take a fresh one).
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
        for (Component p = c; p != null; p = p.getParent()) {
            depth++;
            if (depth > MAX_DEPTH) {
                return new Refusal(c, "the component lies more than " + MAX_DEPTH + " levels below its window; the "
                        + "input veto cannot be asked for every ancestor", route);
            }
            if (p instanceof Window) {
                break;
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
            Component parent = c.getParent();
            r = c instanceof Window || parent == null ? ALLOWED : chain(parent, route, known);
        }
        known.put(c, r);
        return r;
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
                ctx.log().warn("swing: InputVeto " + veto.getClass().getName() + " failed; the input is refused: "
                        + t, t);
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
