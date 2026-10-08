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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and checks the parameters of a web call; a wrong type or a missing required parameter is {@code
 * INVALID_PARAMS} with {@code data.param}, as the agent's own parameter reader answers.
 *
 * <p>Stateless.
 */
final class Params {

    private Params() {
    }

    /** A string parameter; null when absent and not required. */
    static String string(String primitive, Map<String, Object> args, String name, boolean required)
            throws AgentRpcException {
        Object value = args.get(name);
        if (value == null) {
            if (required) {
                throw invalid(primitive, name, name + " is required");
            }
            return null;
        }
        if (!(value instanceof String s)) {
            throw invalid(primitive, name, name + " must be a string");
        }
        return s;
    }

    /** An integral parameter; null when absent. */
    static Long integer(String primitive, Map<String, Object> args, String name) throws AgentRpcException {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) < 9.0e15) {
            return (long) n.doubleValue();
        }
        throw invalid(primitive, name, name + " must be an integer");
    }

    /** An integral parameter with a default, clamped to {@code [min, max]}. */
    static long integer(String primitive, Map<String, Object> args, String name, long dflt, long min, long max)
            throws AgentRpcException {
        Long value = integer(primitive, args, name);
        long v = value == null ? dflt : value;
        return Math.max(min, Math.min(max, v));
    }

    /** A boolean parameter with a default. */
    static boolean bool(String primitive, Map<String, Object> args, String name, boolean dflt)
            throws AgentRpcException {
        Object value = args.get(name);
        if (value == null) {
            return dflt;
        }
        if (!(value instanceof Boolean b)) {
            throw invalid(primitive, name, name + " must be true or false");
        }
        return b;
    }

    /** A required list of strings. */
    static List<String> strings(String primitive, Map<String, Object> args, String name) throws AgentRpcException {
        Object value = args.get(name);
        if (!(value instanceof List<?> list)) {
            throw invalid(primitive, name, name + " must be a list of strings");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw invalid(primitive, name, name + " must hold non-blank strings");
            }
            out.add(s);
        }
        return out;
    }

    /** {@code INVALID_PARAMS} with {@code data {param}}. */
    static AgentRpcException invalid(String primitive, String param, String message) {
        return Shapes.error(primitive, ErrorCodes.INVALID_PARAMS, message, Shapes.data("param", param));
    }
}
