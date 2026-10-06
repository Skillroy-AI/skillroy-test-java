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

package ai.skillroy.testjava.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed, null-tolerant accessors over a parsed JSON object, so that primitives read their parameters in one line
 * and every type error reaches the caller as {@code RpcException(INVALID_PARAMS)} naming the offending key.
 *
 * <p>Absent keys and keys mapped to JSON null are treated alike: the default is returned, or the {@code require*}
 * method fails. Coercions: a number asked for as a string gives its decimal form ({@code 42 -> "42"},
 * {@code 2.5 -> "2.5"}); a string holding a number is accepted where a number is asked for ({@code "42"});
 * an integral double is accepted where an integer is asked for; the strings {@code "true"}/{@code "false"} are
 * accepted as booleans. Anything else of the wrong type is an error. Keys of nested views are reported with their
 * path ({@code "by.name"}).
 *
 * <p>A view reads the map it wraps without copying it; it is thread-safe as long as that map is not mutated.
 */
public final class JsonView {

    private final Map<String, Object> map;
    private final String path;

    private JsonView(Map<String, Object> map, String path) {
        this.map = Collections.unmodifiableMap(map);
        this.path = path;
    }

    /** A view over {@code map}; null gives an empty view. */
    public static JsonView of(Map<String, Object> map) {
        return new JsonView(map == null ? new LinkedHashMap<String, Object>() : map, "");
    }

    /** The wrapped map, read-only. */
    public Map<String, Object> raw() {
        return map;
    }

    /** True if the key is present with a non-null value. */
    public boolean has(String key) {
        return map.get(key) != null;
    }

    /** The value as a string, or null if absent. */
    public String str(String key) {
        return str(key, null);
    }

    /** The value as a string, or {@code dflt} if absent. */
    public String str(String key, String dflt) {
        Object v = map.get(key);
        if (v == null) {
            return dflt;
        }
        String s = Coerce.toStr(v);
        if (s == null) {
            throw mismatch(key, "a string", v);
        }
        return s;
    }

    /** The value as a string; an empty string is accepted. */
    public String requireStr(String key) {
        String s = str(key, null);
        if (s == null) {
            throw missing(key);
        }
        return s;
    }

    /** The value as an integer, or {@code dflt} if absent. */
    public long num(String key, long dflt) {
        Object v = map.get(key);
        return v == null ? dflt : toLong(key, v);
    }

    /** The value as an integer. */
    public long requireNum(String key) {
        Object v = map.get(key);
        if (v == null) {
            throw missing(key);
        }
        return toLong(key, v);
    }

    /** The value as a double, or {@code dflt} if absent. */
    public double dbl(String key, double dflt) {
        Object v = map.get(key);
        if (v == null) {
            return dflt;
        }
        Double d = v instanceof Boolean ? null : Coerce.toDouble(v);
        if (d == null) {
            throw mismatch(key, "a number", v);
        }
        return d.doubleValue();
    }

    /** The value as a boolean, or {@code dflt} if absent. */
    public boolean bool(String key, boolean dflt) {
        Object v = map.get(key);
        if (v == null) {
            return dflt;
        }
        Boolean b = Coerce.toBool(v);
        if (b == null) {
            throw mismatch(key, "a boolean", v);
        }
        return b.booleanValue();
    }

    /** A view of a nested object; an empty view if absent. */
    @SuppressWarnings("unchecked")
    public JsonView obj(String key) {
        Object v = map.get(key);
        if (v == null) {
            return new JsonView(new LinkedHashMap<String, Object>(), path + key + ".");
        }
        if (v instanceof Map) {
            return new JsonView((Map<String, Object>) v, path + key + ".");
        }
        throw mismatch(key, "an object", v);
    }

    /** A read-only list for an array value (a Java array or collection is also accepted); empty if absent. */
    @SuppressWarnings("unchecked")
    public List<Object> list(String key) {
        Object v = map.get(key);
        if (v == null) {
            return Collections.emptyList();
        }
        if (v instanceof List) {
            return Collections.unmodifiableList((List<Object>) v);
        }
        if (v instanceof Object[]) {
            return Collections.unmodifiableList(Arrays.asList((Object[]) v));
        }
        if (v instanceof Collection) {
            return Collections.unmodifiableList(new ArrayList<Object>((Collection<Object>) v));
        }
        throw mismatch(key, "an array", v);
    }

    /** The raw value, or null. */
    public Object get(String key) {
        return map.get(key);
    }

    private long toLong(String key, Object v) {
        Long l = v instanceof Boolean ? null : Coerce.toLong(v);
        if (l == null) {
            throw mismatch(key, "an integer", v);
        }
        return l.longValue();
    }

    private RpcException missing(String key) {
        String name = path + key;
        return new RpcException(ErrorCodes.INVALID_PARAMS, "missing required parameter '" + name + "'",
                detail(name));
    }

    private RpcException mismatch(String key, String expected, Object actual) {
        String name = path + key;
        return new RpcException(ErrorCodes.INVALID_PARAMS,
                "parameter '" + name + "' must be " + expected + " but was " + Coerce.typeName(actual),
                detail(name));
    }

    private static Map<String, Object> detail(String name) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("param", name);
        return data;
    }

    @Override
    public String toString() {
        return "JsonView" + Coerce.safeWrite(map);
    }
}
