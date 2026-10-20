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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Component;
import java.awt.Point;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builders for the protocol errors the Swing primitives raise, so that every primitive words and shapes them the same
 * way. Stateless; safe from any thread.
 */
final class Errors {

    private Errors() {
    }

    static RpcException invalid(String message) {
        return new RpcException(ErrorCodes.INVALID_PARAMS, message);
    }

    static RpcException invalid(String message, Map<String, Object> data) {
        return new RpcException(ErrorCodes.INVALID_PARAMS, message, data);
    }

    static RpcException notFound(String message, Map<String, Object> data) {
        return new RpcException(ErrorCodes.NOT_FOUND, message, data);
    }

    static RpcException ambiguous(String message, Map<String, Object> data) {
        return new RpcException(ErrorCodes.AMBIGUOUS, message, data);
    }

    /** CAPABILITY_UNAVAILABLE with {@code data.capability} and {@code data.reason}. */
    static RpcException unavailable(String capability, String reason) {
        return new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE,
                "capability " + capability + " is unavailable: " + reason, data("capability", capability,
                        "reason", reason));
    }

    /** MODAL_OPEN with {@code data.dialog}, {@code data.title} and {@code data.ref} (the component acted on). */
    static RpcException modalOpen(String message, String dialogRef, String title, String targetRef) {
        return new RpcException(ErrorCodes.MODAL_OPEN, message, data("dialog", dialogRef, "title", title, "ref",
                targetRef));
    }

    /**
     * MODAL_OPEN for a dialog drawn inside the target's window (amendment A27): the fields of
     * {@link #modalOpen} plus {@code data.inWindow = true}; {@code data.dialog} is the ref of the dialog's root.
     */
    static RpcException modalOpenInWindow(String message, String dialogRef, String title, String targetRef) {
        return new RpcException(ErrorCodes.MODAL_OPEN, message, data("dialog", dialogRef, "title", title, "ref",
                targetRef, "inWindow", Boolean.TRUE));
    }

    /**
     * {@code INPUT_REJECTED} for a vetoed input (Phase 8, phase8-contracts.md 3.2 rule 5, D504): message
     * {@code "<describe(aimed)> takes no input from the agent (<route>): <text>"}, data {@code {reason: "vetoed", veto,
     * ref, vetoed, route, hit?}}; {@code ref} is the component the call aimed at, {@code vetoed} the one whose veto
     * refused (itself or an ancestor).
     *
     * @param hit the pointer routes' hit subset, or null
     */
    static RpcException vetoed(ComponentView view, Component aimed, InputVetoes.Refusal r, Map<String, Object> hit) {
        Map<String, Object> data = data("reason", "vetoed", "veto", r.text, "ref", view.ref(aimed), "vetoed",
                view.ref(r.component), "route", r.route);
        if (hit != null) {
            data.put("hit", hit);
        }
        return new RpcException(ErrorCodes.INPUT_REJECTED, view.describe(aimed) + " takes no input from the agent ("
                + r.route + "): " + r.text, data);
    }

    /**
     * {@link #vetoed} for ENTER's default button (phase8-contracts.md 3.2 rule 2): {@code ref} is the key's target,
     * {@code vetoed} the component whose veto refused (the button or an ancestor), {@code defaultButton} the button.
     */
    static RpcException vetoedDefaultButton(ComponentView view, Component target, Component button,
                                            InputVetoes.Refusal r) {
        Map<String, Object> data = data("reason", "vetoed", "veto", r.text, "ref", view.ref(target), "vetoed",
                view.ref(r.component), "route", r.route, "defaultButton", view.ref(button));
        return new RpcException(ErrorCodes.INPUT_REJECTED, view.describe(target) + " takes no input from the agent ("
                + r.route + "): ENTER would press the default button " + view.describe(button) + ": " + r.text, data);
    }

    /**
     * {@code INVALID_PARAMS} for a collapsed component (Phase 8, phase8-contracts.md 3.3, D510): data
     * {@code {ref, reason: "collapsed", by}}, {@code by} the component or ancestor that leaves it no visible area.
     */
    static RpcException collapsed(ComponentView view, Component c, Component by) {
        return invalid(view.describe(c) + " is collapsed: " + view.describe(by) + " leaves it no visible area",
                data("ref", view.ref(c), "reason", "collapsed", "by", view.ref(by)));
    }

    /**
     * {@code INPUT_REJECTED} for a point of the pointer's component form that the component does not show (Phase 8,
     * phase8-contracts.md 3.3, D512): data {@code {reason: "not visible", ref, point: {x, y}, param}}, the point in the
     * component's own coordinates.
     */
    static RpcException notVisible(ComponentView view, Component c, Point local, String param) {
        Map<String, Object> point = data("x", Long.valueOf(local.x), "y", Long.valueOf(local.y));
        return new RpcException(ErrorCodes.INPUT_REJECTED, view.describe(c) + " does not show the point (" + local.x
                + ", " + local.y + "): it is scrolled out of view or clipped", data("reason", "not visible", "ref",
                view.ref(c), "point", point, "param", param));
    }

    /** An insertion-ordered map from alternating keys and values. */
    static Map<String, Object> data(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }
}
