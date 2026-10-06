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

import java.util.Map;

/**
 * Thrown by primitives and agent code to fail a call with a specific JSON-RPC error. The dispatcher turns it into
 * the response's {@code error} member verbatim; any other exception becomes {@link ErrorCodes#INTERNAL_ERROR}.
 * Unchecked so that it passes through lambdas and SPI implementations.
 *
 * <p>Immutable; safe to share between threads. Not final so that modules may define descriptive subclasses.
 */
public class RpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient RpcError error;

    public RpcException(int code, String message) {
        this(code, message, null);
    }

    /**
     * @param data structured detail for the client, or null; deep-copied
     * @throws JsonException if {@code data} holds NaN or Infinity or nests deeper than 184
     */
    public RpcException(int code, String message, Map<String, Object> data) {
        super(message != null ? message : ErrorCodes.name(code));
        this.error = new RpcError(code, message, data);
    }

    /** The error the client will receive. */
    public RpcError error() {
        return error;
    }

    @Override
    public String toString() {
        return getClass().getName() + ": " + ErrorCodes.name(error.code()) + ": " + error.message();
    }
}
