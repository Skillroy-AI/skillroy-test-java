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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.RpcError;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result shapes and errors of the CDP path, built so that they equal the agent path's for the same page state
 * (phase4-contracts.md sections 4.2 and 5.2): values come from the shared library, errors are {@link
 * AgentRpcException}s with the agent's codes, which is what {@code AgentConnection.call} throws, so the executor maps
 * them unchanged.
 *
 * <p>Stateless.
 */
final class Shapes {

    /** Error codes the page library may name in an {@code __error} object. */
    private static final Map<String, Integer> LIBRARY_CODES = codes(ErrorCodes.NOT_FOUND, ErrorCodes.AMBIGUOUS,
            ErrorCodes.INVALID_PARAMS, ErrorCodes.SCRIPT_ERROR, ErrorCodes.STALE_HANDLE, ErrorCodes.TIMEOUT,
            ErrorCodes.INTERNAL_ERROR, ErrorCodes.CAPABILITY_UNAVAILABLE);

    private Shapes() {
    }

    /**
     * The {@code web.evaluate} value from the JSON text the wrapper returned: {@code {json, typeofResult}}, plus
     * {@code truncated: true} when the library cut the text.
     *
     * @throws IllegalArgumentException if the text is not the wrapper's shape
     */
    static Map<String, Object> evaluateValue(String text) {
        Map<String, Object> parsed;
        try {
            parsed = Json.parseObject(text);
        } catch (JsonException e) {
            throw new IllegalArgumentException("the wrapper's text is not a JSON object: " + e.getMessage(), e);
        }
        if (!(parsed.get("json") instanceof String json) || !(parsed.get("typeofResult") instanceof String type)) {
            throw new IllegalArgumentException("the wrapper's text lacks json or typeofResult");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("json", json);
        value.put("typeofResult", type);
        if (Boolean.TRUE.equals(parsed.get("truncated"))) {
            value.put("truncated", true);
        }
        return value;
    }

    /**
     * The value inside a guarded script's result (see {@link Scripts}).
     *
     * @param browserRef the browser's ref for the error data, or null when unknown
     * @throws AgentRpcException {@code SCRIPT_ERROR} for a thrown script error, the named code for a library error
     *     object, {@code INTERNAL_ERROR} when the result is not a guarded result at all
     */
    static Object unwrap(String primitive, Object raw, String browserRef) throws AgentRpcException {
        Object value = Values.json(raw);
        if (!(value instanceof Map<?, ?> m)) {
            throw error(primitive, ErrorCodes.INTERNAL_ERROR, "the guarded script returned no object",
                    data("path", "cdp"));
        }
        if (m.containsKey("__scriptError")) {
            throw scriptError(primitive, m.get("__scriptError"), browserRef);
        }
        if (!m.containsKey("ok")) {
            throw error(primitive, ErrorCodes.INTERNAL_ERROR, "the guarded script returned neither ok nor an error",
                    data("path", "cdp"));
        }
        Object ok = m.get("ok");
        if (ok instanceof Map<?, ?> lib && lib.get("__error") instanceof String code) {
            throw libraryError(primitive, code, lib.get("data"));
        }
        return ok;
    }

    /** {@code SCRIPT_ERROR} with {@code data {browser?, message, stack}}. */
    static AgentRpcException scriptError(String primitive, Object detail, String browserRef) {
        String message = "the script threw";
        String stack = "";
        if (detail instanceof Map<?, ?> d) {
            if (d.get("message") instanceof String s && !s.isEmpty()) {
                message = s;
            }
            if (d.get("stack") instanceof String s) {
                stack = s;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (browserRef != null) {
            data.put("browser", browserRef);
        }
        data.put("message", message);
        data.put("stack", stack);
        return error(primitive, ErrorCodes.SCRIPT_ERROR, message, data);
    }

    /** The error a library {@code __error} object names, with the library's {@code data} unchanged. */
    static AgentRpcException libraryError(String primitive, String codeName, Object rawData) {
        Integer code = LIBRARY_CODES.get(codeName);
        Map<String, Object> data = new LinkedHashMap<>();
        if (rawData instanceof Map<?, ?> d) {
            for (Map.Entry<?, ?> e : d.entrySet()) {
                data.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        if (code == null) {
            data.put("error", codeName);
            return error(primitive, ErrorCodes.INTERNAL_ERROR, "the page library answered an unknown error "
                    + codeName, data);
        }
        String message = switch (code) {
            case ErrorCodes.NOT_FOUND -> "no element in the page matches " + Json.write(data);
            case ErrorCodes.AMBIGUOUS -> "several elements in the page match"
                    + (data.get("candidates") instanceof List<?> l ? " (" + l.size() + ")" : "")
                    + "; name one with index";
            case ErrorCodes.INVALID_PARAMS -> "invalid parameters"
                    + (data.get("reason") instanceof String r ? ": " + r : "");
            default -> codeName + " from the page library";
        };
        return error(primitive, code, message, data);
    }

    /** An {@link AgentRpcException} for {@code primitive}, the type and shape {@code AgentConnection.call} throws. */
    static AgentRpcException error(String primitive, int code, String message, Map<String, Object> data) {
        return new AgentRpcException(primitive, new RpcError(code, message, data));
    }

    /**
     * {@code {format: "png", width, height, base64, source: "PROTOCOL"}}, scaled down keeping the aspect ratio when
     * wider than {@code maxWidth}.
     */
    static Map<String, Object> bitmapValue(byte[] png, int maxWidth) throws IOException {
        byte[] scaled = Png.scaleToWidth(png, maxWidth);
        int[] size = Png.size(scaled);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", "png");
        value.put("width", (long) size[0]);
        value.put("height", (long) size[1]);
        value.put("base64", Base64.getEncoder().encodeToString(scaled));
        value.put("source", "PROTOCOL");
        return value;
    }

    /**
     * {@code {ready, ms, polls, lastError?}}, the agent's shape. Playwright does not report how often it evaluated the
     * predicate, so {@code polls} is computed: one evaluation at the start and one per {@code pollMs} after it
     * ({@code 1 + ms / pollMs}). {@code lastError} (A35), the last poll's error as the shared guard cut it, is there
     * only when the flag is not ready and the last poll threw.
     */
    static Map<String, Object> waitFlagValue(boolean ready, long ms, long pollMs, String lastError) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ready", ready);
        value.put("ms", ms);
        value.put("polls", polls(ms, pollMs));
        if (!ready && lastError != null) {
            value.put("lastError", lastError);
        }
        return value;
    }

    static long polls(long ms, long pollMs) {
        return 1 + Math.max(0, ms) / Math.max(1, pollMs);
    }

    /** {@code {applied: "OVERRIDE", viewport: {w, h}}}. */
    static Map<String, Object> resizeValue(long width, long height) {
        return resizeValue(new long[] {width, height});
    }

    /** {@code {applied: "OVERRIDE", viewport?: {w, h}}}: without {@code viewport} when {@code size} is null. */
    static Map<String, Object> resizeValue(long[] size) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("applied", "OVERRIDE");
        if (size != null) {
            Map<String, Object> viewport = new LinkedHashMap<>();
            viewport.put("w", size[0]);
            viewport.put("h", size[1]);
            value.put("viewport", viewport);
        }
        return value;
    }

    /** {@code {installed, timing: "DOCUMENT_START"}}: Playwright's init scripts run before the page's own scripts. */
    static Map<String, Object> tapValue(long installed) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("installed", installed);
        value.put("timing", "DOCUMENT_START");
        return value;
    }

    /** A map of the given keys and values in order; pairs whose value is null are left out. */
    static Map<String, Object> data(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
            }
        }
        return m;
    }

    private static Map<String, Integer> codes(int... codes) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int code : codes) {
            m.put(ErrorCodes.name(code), code);
        }
        return Map.copyOf(m);
    }
}
