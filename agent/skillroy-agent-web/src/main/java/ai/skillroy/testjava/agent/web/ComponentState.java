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

package ai.skillroy.testjava.agent.web;

/**
 * A browser component's state as one EDT read found it ({@link SeedWalk#state}): whether it is showing, its size in
 * its own pixels, the scale of its screen ({@code GraphicsConfiguration.getDefaultTransform().getScaleX()}, 1 when
 * unknown), and the showing modal dialog that blocks its window, if any (phase7-contracts.md section 4.3: a dialog
 * whose modality is not {@code MODELESS} and that is neither the component's window nor a window that owns it, the
 * Swing module's heuristic), and whether a pack's {@code CaptureMask} names it (Phase 7 security fix S111). Typed with
 * {@code Object} so that the module names no AWT class outside its walk.
 *
 * <p>Immutable.
 */
final class ComponentState {

    /** The state of something that is not a component, or of any object while no event-dispatch thread runs. */
    static final ComponentState UNKNOWN = new ComponentState(false, 0, 0, 1.0, null, null);
    /**
     * {@link #UNKNOWN} while a mask is registered: a state that could not be read is masked (Phase 8 security fix,
     * S167, FE; phase8-contracts.md 5.2 as built (d)), so that no per-call judgement on it lowers {@code Entry.masked}.
     */
    static final ComponentState UNKNOWN_MASKED = new ComponentState(false, 0, 0, 1.0, null, null, true);

    final boolean showing;
    final int width;
    final int height;
    final double scale;
    /** The blocking dialog (a {@code java.awt.Dialog}), or null. */
    final Object blocker;
    /** Its title, or null. */
    final String blockerTitle;
    /**
     * True when a registered {@code CaptureMask} names the component or one of its ancestors, or throws for one (the
     * Swing module's rule, the safe side): its page is masked on every route (phase7-contracts.md 3.6, 4.8; Phase 7
     * security fix S111).
     */
    final boolean masked;

    ComponentState(boolean showing, int width, int height, double scale, Object blocker, String blockerTitle) {
        this(showing, width, height, scale, blocker, blockerTitle, false);
    }

    ComponentState(boolean showing, int width, int height, double scale, Object blocker, String blockerTitle,
                   boolean masked) {
        this.showing = showing;
        this.width = width;
        this.height = height;
        this.scale = scale;
        this.blocker = blocker;
        this.blockerTitle = blockerTitle;
        this.masked = masked;
    }

    @Override
    public String toString() {
        return "ComponentState{showing=" + showing + ", " + width + "x" + height + ", scale=" + scale
                + (blocker == null ? "" : ", blocked by '" + blockerTitle + "'") + (masked ? ", masked" : "") + "}";
    }
}
