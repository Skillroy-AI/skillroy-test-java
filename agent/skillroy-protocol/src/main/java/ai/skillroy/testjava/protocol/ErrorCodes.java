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

/**
 * JSON-RPC error codes of the control channel: the reserved JSON-RPC 2.0 codes plus the framework's own range, so
 * that the controller can classify a failure (scenario failure versus environment error) by code alone.
 *
 * <p>Constants only; usable from any thread.
 */
public final class ErrorCodes {

    /** JSON-RPC 2.0 reserved codes, then the framework's own codes. */
    public static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601,
            INVALID_PARAMS = -32602, INTERNAL_ERROR = -32603,
            // framework codes; phase1-contracts.md section 5 says when each is raised
            TIMEOUT = -32001, FORBIDDEN_THREAD = -32002, STALE_HANDLE = -32003, MODAL_OPEN = -32004,
            INPUT_REJECTED = -32005, NOT_FOUND = -32006, AMBIGUOUS = -32007,
            CAPABILITY_DEGRADED = -32010, CAPABILITY_UNAVAILABLE = -32011,
            HOOK_VERIFY_FAILED = -32020, CONFIRM_REQUIRED = -32030, READ_ONLY = -32031, NOT_READY = -32032,
            // the agent's pools are full, or an earlier timed-out call of the same primitive is still running (S30)
            BUSY = -32033;

    private ErrorCodes() {
    }

    /** The constant's name for a known code (for logs and reports), otherwise {@code "UNKNOWN(<code>)"}. */
    public static String name(int code) {
        switch (code) {
            case PARSE_ERROR:
                return "PARSE_ERROR";
            case INVALID_REQUEST:
                return "INVALID_REQUEST";
            case METHOD_NOT_FOUND:
                return "METHOD_NOT_FOUND";
            case INVALID_PARAMS:
                return "INVALID_PARAMS";
            case INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case TIMEOUT:
                return "TIMEOUT";
            case FORBIDDEN_THREAD:
                return "FORBIDDEN_THREAD";
            case STALE_HANDLE:
                return "STALE_HANDLE";
            case MODAL_OPEN:
                return "MODAL_OPEN";
            case INPUT_REJECTED:
                return "INPUT_REJECTED";
            case NOT_FOUND:
                return "NOT_FOUND";
            case AMBIGUOUS:
                return "AMBIGUOUS";
            case CAPABILITY_DEGRADED:
                return "CAPABILITY_DEGRADED";
            case CAPABILITY_UNAVAILABLE:
                return "CAPABILITY_UNAVAILABLE";
            case HOOK_VERIFY_FAILED:
                return "HOOK_VERIFY_FAILED";
            case CONFIRM_REQUIRED:
                return "CONFIRM_REQUIRED";
            case READ_ONLY:
                return "READ_ONLY";
            case NOT_READY:
                return "NOT_READY";
            case BUSY:
                return "BUSY";
            default:
                return "UNKNOWN(" + code + ")";
        }
    }
}
