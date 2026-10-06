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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code error} member of a failed JSON-RPC response: a code from {@link ErrorCodes}, a human-readable message
 * and optional structured detail. Exists so that both ends build and read errors the same way.
 *
 * <p>Immutable ({@code data} is deep-copied, see {@link Event}); safe to share between threads.
 */
public final class RpcError {

    private static final String TYPE = "RpcError";

    private final int code;
    private final String message;
    private final Map<String, Object> data;

    /**
     * @param message null is replaced by {@link ErrorCodes#name(int)}
     * @param data structured detail, or null for none; deep-copied
     * @throws JsonException if {@code data} holds NaN or Infinity or nests deeper than 184
     */
    public RpcError(int code, String message, Map<String, Object> data) {
        this.code = code;
        this.message = message != null ? message : ErrorCodes.name(code);
        this.data = Coerce.freezeMap(data);
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** Structured detail (unmodifiable), or null when there is none. */
    public Map<String, Object> data() {
        return data;
    }

    /** {@code {"code":…,"message":…,"data":{…}}}; {@code data} is omitted when null, as JSON-RPC 2.0 allows. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("code", Long.valueOf(code));
        json.put("message", message);
        if (data != null) {
            json.put("data", data);
        }
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}. A {@code data} member that is not an object (allowed by
     * JSON-RPC 2.0) is wrapped as {@code {"value": data}}.
     *
     * @throws JsonException if {@code code} is missing or not an integer in int range
     */
    public static RpcError fromJson(Map<String, Object> json) {
        Wire.requireObject(json, TYPE);
        long code = Wire.requireLong(json, "code", TYPE);
        if (code < Integer.MIN_VALUE || code > Integer.MAX_VALUE) {
            throw new JsonException(TYPE + ": field \"code\" is outside the int range");
        }
        Object rawMessage = json.get("message");
        String message = rawMessage == null ? null : Coerce.toStr(rawMessage);
        Object rawData = json.get("data");
        Map<String, Object> data;
        if (rawData == null) {
            data = null;
        } else if (rawData instanceof Map) {
            data = Wire.optObject(json, "data", TYPE);
        } else {
            data = new LinkedHashMap<String, Object>();
            data.put("value", rawData);
        }
        return new RpcError((int) code, message, data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RpcError)) {
            return false;
        }
        RpcError other = (RpcError) o;
        return code == other.code && message.equals(other.message) && Objects.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Integer.valueOf(code), message, data);
    }

    @Override
    public String toString() {
        return TYPE + Coerce.safeWrite(toJson());
    }
}
