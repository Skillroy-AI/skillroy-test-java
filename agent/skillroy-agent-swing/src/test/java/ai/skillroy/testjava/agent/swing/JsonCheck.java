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

import ai.skillroy.testjava.protocol.RpcException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/** Test helpers: JSON shape checks, map/list builders, polling with a deadline, and error assertions. */
final class JsonCheck {

    private JsonCheck() {
    }

    /** Fails unless the value is made only of Map&lt;String,?&gt;, List, String, Long, Double, Boolean and null. */
    static void requirePlainJson(Object v, String where) {
        if (v == null || v instanceof String || v instanceof Long || v instanceof Double || v instanceof Boolean) {
            return;
        }
        if (v instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new AssertionError(where + ": non-string key " + e.getKey());
                }
                requirePlainJson(e.getValue(), where + "." + e.getKey());
            }
            return;
        }
        if (v instanceof List) {
            int i = 0;
            for (Object o : (List<?>) v) {
                requirePlainJson(o, where + "[" + i++ + "]");
            }
            return;
        }
        throw new AssertionError(where + ": not plain JSON: " + v.getClass().getName() + " " + v);
    }

    /** An insertion-ordered map from alternating keys and values. */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * The result of a click that fired on the rung asked for (amendment A28):
     * {@code {rung, requestedRung, fidelity: "FULL", fired: true}}.
     */
    static Map<String, Object> fired(String rung, String requestedRung) {
        return map("rung", rung, "requestedRung", requestedRung, "fidelity", "FULL", "fired", true);
    }

    static List<Object> list(Object... items) {
        return new ArrayList<Object>(Arrays.asList(items));
    }

    /** Polls every 20 ms until the condition holds; fails after {@code timeoutMs}. */
    static void await(String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out after " + timeoutMs + " ms waiting for " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + what);
            }
        }
    }

    /** Runs the call and asserts it fails with the protocol error code; returns the exception. */
    static RpcException expectError(int code, ThrowingCall call) {
        RpcException e = assertThrows(RpcException.class, call::run);
        assertEquals(code, e.error().code(), "error code of: " + e.getMessage());
        return e;
    }

    /** A call that may throw. */
    interface ThrowingCall {
        void run() throws Exception;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object v) {
        return (List<Object>) v;
    }
}
