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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcError;

import java.util.Objects;

/**
 * The agent answered a call with a JSON-RPC error. Checked, so that the executor must decide per call whether the
 * error is a scenario failure (for example INPUT_REJECTED) or an environment error.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class AgentRpcException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient RpcError error;
    private final String method;

    /**
     * @param method the JSON-RPC method that failed
     * @param error the error the agent returned
     */
    public AgentRpcException(String method, RpcError error) {
        super(method + " failed: " + ErrorCodes.name(Objects.requireNonNull(error, "error").code()) + " ("
                + error.code() + "): " + error.message());
        this.error = error;
        this.method = method;
    }

    /** The error as the agent sent it. */
    public RpcError error() {
        return error;
    }

    /** The JSON-RPC method that failed. */
    public String method() {
        return method;
    }
}
