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

    /** An insertion-ordered map from alternating keys and values. */
    static Map<String, Object> data(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }
}
