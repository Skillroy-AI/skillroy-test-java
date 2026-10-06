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

package ai.skillroy.testjava.scenario.resolve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deep unmodifiable copies of JSON-like values, for the records of this package. Stateless. */
final class Frozen {

    private Frozen() {
    }

    static Object freeze(Object value) {
        return freeze(value, false, 0);
    }

    /**
     * {@link #freeze} with the keys of every map, at every depth, in sorted order (lists keep theirs): for pack-built
     * maps that reach a bindings file or a run record, since {@code Map.of} iterates in an order that differs between
     * JVMs (phase2-contracts.md 9: two approvals of one environment agree byte for byte).
     */
    static Object freezeSorted(Object value) {
        return freeze(value, true, 0);
    }

    private static Object freeze(Object value, boolean sorted, int depth) {
        if (depth > 200) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = sorted ? new TreeMap<>() : new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(String.valueOf(e.getKey()), freeze(e.getValue(), sorted, depth + 1));
            }
            return Collections.unmodifiableMap(sorted ? new LinkedHashMap<>(copy) : copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(freeze(item, sorted, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> freezeMap(Map<String, ?> map) {
        return map == null ? Map.of() : (Map<String, Object>) freeze(map);
    }

    /** {@link #freezeSorted} of a map; null is empty. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> freezeSortedMap(Map<String, ?> map) {
        return map == null ? Map.of() : (Map<String, Object>) freezeSorted(map);
    }
}
