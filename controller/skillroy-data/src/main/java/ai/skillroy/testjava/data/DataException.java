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

package ai.skillroy.testjava.data;

/**
 * A data channel refused or failed a statement (phase2-contracts.md 7.1, 7.2). Carries the kind, the source, and for
 * SQL failures the SQL state, vendor code and exception class, <b>never the driver's message</b> (finding S22): the
 * driver's text can echo parameter and cell values, including masked ones. For a failure the agent reported,
 * {@link #rpcCode()} names the agent's error code.
 *
 * <p>No cause is attached, for the same reason. Immutable.
 */
public final class DataException extends Exception {

    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind {
        /** The statement guard refused the statement. */
        GUARD,
        /** No channel (or not this one) serves the source. */
        UNKNOWN_SOURCE,
        /** The source exists but cannot be used now: no driver, no connection, no provider form. */
        UNAVAILABLE,
        /** The database reported an error. */
        SQL,
        /** The statement or the connect took longer than allowed. */
        TIMEOUT,
        /** A row mask failed; the value appears nowhere. */
        MASK,
        /** Anything else, with the agent's error code where the agent reported it. */
        OTHER
    }

    private final Kind kind;
    private final String source;
    private final String sqlState;
    private final int errorCode;
    private final String exceptionClass;
    private final String rpcCode;

    /**
     * @param message what happened, without any driver text or value
     * @param sqlState the SQL state, or null
     * @param errorCode the vendor code, 0 when unknown
     * @param exceptionClass the class of the underlying exception, or null
     * @param rpcCode the agent's error code name ({@code "INTERNAL_ERROR"}) for a failure the agent reported, else
     *     null
     */
    public DataException(Kind kind, String source, String message, String sqlState, int errorCode,
                         String exceptionClass, String rpcCode) {
        super(message);
        this.kind = kind == null ? Kind.OTHER : kind;
        this.source = source;
        this.sqlState = sqlState;
        this.errorCode = errorCode;
        this.exceptionClass = exceptionClass;
        this.rpcCode = rpcCode;
    }

    /** A failure without SQL details. */
    public DataException(Kind kind, String source, String message) {
        this(kind, source, message, null, 0, null, null);
    }

    public Kind kind() {
        return kind;
    }

    /** The source id the statement was for. */
    public String source() {
        return source;
    }

    /** The SQL state, or null. */
    public String sqlState() {
        return sqlState;
    }

    /** The vendor error code, 0 when unknown. */
    public int errorCode() {
        return errorCode;
    }

    /** The class name of the underlying exception, or null; never the driver's message (S22). */
    public String exceptionClass() {
        return exceptionClass;
    }

    /** The agent's error code name for {@code OTHER} and for kinds the agent reported, else null. */
    public String rpcCode() {
        return rpcCode;
    }
}
