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

package ai.skillroy.testjava.protocol.hook;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link HookMode#CAPTURE} hook copies out of a call (phase6-contracts.md section 4.1): JSON
 * {@code {args[], self, result}}, the argument indexes, the receiver and the return value. Every copy is encoded,
 * masked and bounded by the engine (section 4.3).
 *
 * <p>Immutable; safe to share between threads.
 */
public final class HookCapture {

    private static final String TYPE = "HookCapture";

    private final List<Integer> args;
    private final boolean self;
    private final boolean result;

    /**
     * @param args argument indexes to capture, null for none
     * @throws IllegalArgumentException on a negative or null index
     */
    public HookCapture(List<Integer> args, boolean self, boolean result) {
        List<Integer> copy = args == null ? new ArrayList<Integer>() : new ArrayList<Integer>(args);
        for (Integer index : copy) {
            if (index == null || index.intValue() < 0) {
                throw new IllegalArgumentException("a capture index must be a non-negative integer: " + index);
            }
        }
        this.args = Collections.unmodifiableList(copy);
        this.self = self;
        this.result = result;
    }

    /** Unmodifiable, never null. */
    public List<Integer> args() {
        return args;
    }

    public boolean self() {
        return self;
    }

    public boolean result() {
        return result;
    }

    /** True when nothing is asked for. */
    public boolean isEmpty() {
        return args.isEmpty() && !self && !result;
    }

    /** {@code {"args","self","result"}}, all keys present; a new map on each call. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("args", new ArrayList<Object>(args));
        json.put("self", Boolean.valueOf(self));
        json.put("result", Boolean.valueOf(result));
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}; every field is optional ({@code args} empty, the flags false).
     *
     * @throws JsonException on an unknown field, a negative index or a field of the wrong type
     */
    public static HookCapture fromJson(Map<String, Object> json) {
        HookFields.requireObject(json, TYPE);
        HookFields.refuseUnknown(json, TYPE, "args", "self", "result");
        return new HookCapture(HookFields.indexes(json, "args", TYPE), HookFields.optBool(json, "self", false, TYPE),
                HookFields.optBool(json, "result", false, TYPE));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HookCapture)) {
            return false;
        }
        HookCapture other = (HookCapture) o;
        return args.equals(other.args) && self == other.self && result == other.result;
    }

    @Override
    public int hashCode() {
        return Objects.hash(args, Boolean.valueOf(self), Boolean.valueOf(result));
    }

    @Override
    public String toString() {
        return TYPE + Json.write(toJson());
    }
}
