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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builders for the protocol errors the web primitives raise, so that every primitive words and shapes them the same
 * way (copied from the Swing module's helper of the same name: module jars cannot see each other's classes).
 * Stateless; safe from any thread.
 */
final class Errors {

    private Errors() {
    }

    static RpcException invalid(String message, Map<String, Object> data) {
        return new RpcException(ErrorCodes.INVALID_PARAMS, message, data);
    }

    /** INVALID_PARAMS with {@code data.param}. */
    static RpcException invalidParam(String param, String message) {
        return new RpcException(ErrorCodes.INVALID_PARAMS, message, data("param", param));
    }

    static RpcException notFound(String message, Map<String, Object> data) {
        return new RpcException(ErrorCodes.NOT_FOUND, message, data);
    }

    static RpcException ambiguous(String message, Map<String, Object> data) {
        return new RpcException(ErrorCodes.AMBIGUOUS, message, data);
    }

    /** STALE_HANDLE for a browser ref ({@code data.handle}, {@code data.kind = "browser"}, {@code data.reason}). */
    static RpcException staleBrowser(String ref, String reason) {
        return new RpcException(ErrorCodes.STALE_HANDLE, "browser " + ref + " " + reason,
                data("handle", ref, "kind", "browser", "reason", reason));
    }

    /** CAPABILITY_UNAVAILABLE with {@code data.primitive}, {@code data.capability}, {@code data.reason}. */
    static RpcException unavailable(String primitive, String capability, String reason, String browser) {
        Map<String, Object> data = data("primitive", primitive, "capability", capability, "reason", reason);
        if (browser != null) {
            data.put("browser", browser);
        }
        return new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "capability " + capability + " is unavailable"
                + (browser != null ? " for browser " + browser : "") + ": " + reason, data);
    }

    /** TIMEOUT with {@code data.primitive}, {@code data.browser} and {@code data.timeoutMs}. */
    static RpcException timeout(String primitive, String browser, long timeoutMs) {
        return new RpcException(ErrorCodes.TIMEOUT, primitive + " timed out after " + timeoutMs + " ms waiting for "
                + "browser " + browser, data("primitive", primitive, "browser", browser, "timeoutMs",
                Long.valueOf(timeoutMs)));
    }

    /** SCRIPT_ERROR with {@code data {browser, message, stack}} (phase4-contracts.md section 8.1). */
    static RpcException script(String browser, String message, String stack) {
        return new RpcException(ErrorCodes.SCRIPT_ERROR, "script error in browser " + browser + ": " + message,
                data("browser", browser, "message", message, "stack", stack));
    }

    /** INTERNAL_ERROR with {@code data.browser} (when known) and the failure's description. */
    static RpcException internal(String browser, String message) {
        return new RpcException(ErrorCodes.INTERNAL_ERROR, message, browser == null ? data() : data("browser",
                browser));
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
