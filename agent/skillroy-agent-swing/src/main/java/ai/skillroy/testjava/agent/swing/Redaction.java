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
import ai.skillroy.testjava.agent.api.spi.CaptureMask;

import java.awt.Color;
import java.awt.Component;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JPasswordField;

/**
 * The one rule for pixels and text (phase7-contracts.md section 3.6, A61, A64, D349): a component the built-in rule
 * (every {@code JPasswordField}) or a registered {@link CaptureMask} names is blanked grey in a capture and has no
 * {@code text} and no {@code value} in any output of the module; its node carries {@code masked: true}, the hit-test
 * proposes no {@code {text, role}} locator for it and the marks skip it.
 *
 * <p>As built (Phase 7, VS): a component that lies inside a masked component is treated as masked too (its pixels are
 * inside the blanked rectangle anyway, and a pack that masks a container expects its content hidden); the safe side.
 * Phase 7 security fix: masks combine by union ({@link #blanked}, S123), and a rectangle a mask's {@code regions}
 * blank withholds the text under it ({@code ComponentView.masked}, S122).
 *
 * <p>A mask that throws is logged once per mask instance (one instance lives for one agent start) and the component is
 * treated as masked, the safe side. One instance serves one primitive call: it takes the registered masks at
 * construction. EDT only.
 */
final class Redaction {

    /** The fill of a blanked rectangle: solid grey (D295). */
    static final Color FILL = new Color(128, 128, 128);
    /** {@code redacted[].rule} of the built-in rule. */
    static final String PASSWORD_FIELD = "password-field";
    /** {@code redacted[].rule} of a registered mask. */
    static final String CAPTURE_MASK = "capture-mask";

    /** Masks that have thrown once already (logged once per instance, never again). */
    private static final Map<CaptureMask, Boolean> LOGGED =
            Collections.synchronizedMap(new WeakHashMap<CaptureMask, Boolean>());

    private final AgentContext ctx;
    private final List<CaptureMask> masks;

    Redaction(AgentContext ctx) {
        this.ctx = ctx;
        List<CaptureMask> registered = ctx == null || ctx.spi() == null ? null : ctx.spi().all(CaptureMask.class);
        this.masks = registered == null ? Collections.<CaptureMask>emptyList() : registered;
    }

    /**
     * The rule that names the component itself: {@link #PASSWORD_FIELD}, {@link #CAPTURE_MASK}, or null when neither
     * does. A mask that throws names it ({@link #CAPTURE_MASK}).
     */
    String ownRule(Component c) {
        if (c == null) {
            return null;
        }
        if (c instanceof JPasswordField) {
            return PASSWORD_FIELD;
        }
        for (CaptureMask mask : masks) {
            try {
                if (mask.masks(c)) {
                    return CAPTURE_MASK;
                }
            } catch (Throwable t) {
                failed(mask, t);
                return CAPTURE_MASK;
            }
        }
        return null;
    }

    /** True when a {@link CaptureMask} is registered (the built-in rule alone gives no {@code regions}). */
    boolean hasMasks() {
        return !masks.isEmpty();
    }

    /**
     * The rectangles to blank inside a component, in its own coordinates. Masks combine by union (Phase 7 security
     * fix, S123): each mask contributes its {@code regions} when it gives some, else the whole component when it
     * names the component ({@code masks} true), else nothing; one whole rectangle stands for the union when any mask
     * contributes it. A password field (the built-in rule) and a mask that throws blank the whole component. Before,
     * one mask's {@code regions} replaced another mask's whole-component blanking.
     *
     * @param rule the component's {@link #ownRule}: null when no mask names it
     */
    List<Rectangle> blanked(Component c, String rule) {
        List<Rectangle> out = new ArrayList<Rectangle>();
        Rectangle whole = new Rectangle(0, 0, c.getWidth(), c.getHeight());
        if (c instanceof JPasswordField) {
            out.add(whole);
            return out;
        }
        for (CaptureMask mask : masks) {
            boolean gave = false;
            try {
                List<Rectangle> rs = mask.regions(c);
                if (rs != null) {
                    for (Rectangle r : rs) {
                        if (r != null && !r.isEmpty()) {
                            out.add(new Rectangle(r));
                            gave = true;
                        }
                    }
                }
            } catch (Throwable t) {
                failed(mask, t);
                return whole(whole);
            }
            if (!gave && rule != null) {
                boolean names;
                try {
                    names = mask.masks(c);
                } catch (Throwable t) {
                    failed(mask, t);
                    names = true;
                }
                if (names) {
                    return whole(whole);
                }
            }
        }
        if (out.isEmpty() && rule != null) {
            out.add(whole);
        }
        return out;
    }

    private static List<Rectangle> whole(Rectangle whole) {
        List<Rectangle> out = new ArrayList<Rectangle>(1);
        out.add(whole);
        return out;
    }

    private void failed(CaptureMask mask, Throwable t) {
        if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) {
            throw (VirtualMachineError) t;
        }
        if (LOGGED.put(mask, Boolean.TRUE) == null && ctx != null) {
            try {
                ctx.log().warn("swing: CaptureMask " + mask.getClass().getName() + " failed; the component is "
                        + "blanked and its text withheld: " + t, t);
            } catch (Throwable ignored) {
                // the log must never be the reason a read fails
            }
        }
    }
}
