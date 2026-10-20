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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.protocol.CallWarning;
import ai.skillroy.testjava.protocol.text.TextBounds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The warnings one call raises through {@code Invocation.warn} (phase8-contracts.md 7.1, amendment A84). The
 * invocation checks a code's shape and keeps the raw message and data in call order (thread-safe, never blocking:
 * a short synchronised append); the seventeenth and later only count; a warning raised after the primitive returned is
 * dropped and logged once. When the call has returned, {@link #build} redacts the values of the call's secret
 * parameters from each message and data with the routine the call's error answer gets (A34), and only then builds
 * each {@link CallWarning}, whose constructor sanitises and cuts the message at 500 characters and bounds the data
 * at 2048. The raw message is cut first at 500 plus {@link #REDACTION_MARGIN} characters, so that the redaction
 * never sees an unbounded text and a secret that straddles 500 is still whole when it is replaced.
 */
final class CallWarnings {

    /** The raw message is cut at {@link CallWarning#MAX_MESSAGE} plus this before the redaction (as console text). */
    static final int REDACTION_MARGIN = 4096;

    /** Raw data whose estimated size exceeds this is replaced by {@code {truncated: true}} before the redaction. */
    private static final long MAX_RAW_DATA = 4L * CallWarning.MAX_DATA_CHARS + REDACTION_MARGIN;

    private final AgentLog log;
    private final String method;
    /** Guarded by this. */
    private final List<Raw> raw = new ArrayList<Raw>();
    /** Guarded by this. */
    private long dropped;
    /** Guarded by this. */
    private boolean closed;
    /** Guarded by this. */
    private boolean lateLogged;

    CallWarnings(AgentLog log, String method) {
        this.log = log;
        this.method = method;
    }

    /**
     * Records one warning.
     *
     * @throws IllegalArgumentException if {@code code} matches neither {@link CallWarning#BASE_CODE} nor
     *     {@link CallWarning#PACK_CODE} (a module bug, thrown to the primitive)
     */
    void add(String code, String message, Map<String, Object> data) {
        if (!CallWarning.isValidCode(code)) {
            throw new IllegalArgumentException("warning code must match " + CallWarning.BASE_CODE.pattern() + " or "
                    + CallWarning.PACK_CODE.pattern());
        }
        boolean logLate = false;
        synchronized (this) {
            if (closed) {
                logLate = !lateLogged;
                lateLogged = true;
            } else if (raw.size() < CallWarning.MAX_PER_RESULT) {
                raw.add(new Raw(code, message == null ? null : TextBounds.cut(message,
                        CallWarning.MAX_MESSAGE + REDACTION_MARGIN), data));
            } else {
                dropped++;
            }
        }
        if (logLate && log != null) {
            // The code only: the message and the data may hold what the call kept out of its answer.
            log.info("a warning (" + code + ") raised after " + method + " returned was dropped");
        }
    }

    /**
     * Closes the collection (a later {@link #add} is dropped) and builds the warnings: each message and data with the
     * secrets redacted, then bounded; one {@code TOO_MANY_WARNINGS} for the ones beyond sixteen. Empty for none.
     */
    List<CallWarning> build(SecretParams secrets) {
        List<Raw> taken;
        long over;
        synchronized (this) {
            closed = true;
            if (raw.isEmpty() && dropped == 0) {
                return Collections.emptyList();
            }
            taken = new ArrayList<Raw>(raw);
            over = dropped;
        }
        List<CallWarning> out = new ArrayList<CallWarning>(taken.size() + 1);
        for (Raw warning : taken) {
            out.add(new CallWarning(warning.code, secrets.redact(warning.message), redactedData(warning.data,
                    secrets)));
        }
        if (over > 0) {
            out.add(CallWarning.tooMany(over));
        }
        return out;
    }

    /** Marks the call as returned without building (a call that ended in an error). */
    synchronized void close() {
        closed = true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactedData(Map<String, Object> data, SecretParams secrets) {
        if (data == null) {
            return null;
        }
        try {
            if (TextBounds.exceeds(data, MAX_RAW_DATA)) {
                return truncated();
            }
            Object redacted = secrets.redact((Object) data);
            return redacted instanceof Map ? (Map<String, Object>) redacted : truncated();
        } catch (RuntimeException unreadable) {
            // A module map that throws while it is read (a concurrent change) carries nothing out of the call.
            return truncated();
        }
    }

    private static Map<String, Object> truncated() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("truncated", Boolean.TRUE);
        return out;
    }

    /** One warning as the module raised it. */
    private static final class Raw {
        final String code;
        final String message;
        final Map<String, Object> data;

        Raw(String code, String message, Map<String, Object> data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }
    }
}
