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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.sql.Rows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The data channel through the agent (phase2-contracts.md 7.2): {@code data.query} inside the target JVM, on the
 * application's own connections, with the agent's statement guard and {@code MaskPolicy} list. It serves the source
 * ids {@code data.sources} lists, asked once per instance; a target without the data module
 * ({@code METHOD_NOT_FOUND}) serves nothing.
 *
 * <p>Errors map to {@link DataException.Kind} as the contract's table says: {@code INVALID_PARAMS} on {@code sql} is
 * {@code GUARD}; {@code NOT_FOUND} {@code UNKNOWN_SOURCE}; {@code CAPABILITY_UNAVAILABLE} {@code UNAVAILABLE};
 * {@code TIMEOUT} {@code TIMEOUT}; {@code INTERNAL_ERROR} with {@code data.maskPolicy} {@code MASK}, with a
 * {@code data.sqlState} {@code SQL}; every other error {@code OTHER}. Each carries the agent's error code; none
 * carries the driver text an agent may return in {@code data.message}.
 *
 * <p>Thread-safe as the {@link AgentConnection} is; the source list is cached once read.
 */
public final class AgentDataChannel implements DataChannel {

    /** The longest {@code data.sources} may take (the agent's own limit for it is 5 s as well). */
    static final long SOURCES_TIMEOUT_MS = 5_000L;

    private final AgentConnection agent;
    private volatile Set<String> sources;

    /** @param agent the connection; its calls run on the caller's thread */
    public AgentDataChannel(AgentConnection agent) {
        if (agent == null) {
            throw new IllegalArgumentException("an agent connection is required");
        }
        this.agent = agent;
    }

    @Override
    public boolean serves(String source) throws AgentLostException {
        return serves(source, null);
    }

    @Override
    public boolean serves(String source, Duration timeout) throws AgentLostException {
        return source != null && servedSources(timeout).contains(source);
    }

    /**
     * The ids {@code data.sources} lists, in its order; empty when the target has no data module. Asked once, with a
     * timeout of 5 s; an error other than {@code METHOD_NOT_FOUND} is not cached and reads as no source.
     */
    public Set<String> servedSources() throws AgentLostException {
        return servedSources(null);
    }

    /**
     * {@link #servedSources()}, with the {@code data.sources} call bounded by {@code timeout} when that is shorter
     * than 5 s (a bind passes what is left of its budget).
     */
    public Set<String> servedSources(Duration timeout) throws AgentLostException {
        Set<String> known = sources;
        if (known != null) {
            return known;
        }
        long ms = timeout == null ? SOURCES_TIMEOUT_MS : Math.min(SOURCES_TIMEOUT_MS, Queries.millis(timeout));
        RpcResult result;
        try {
            result = agent.call("data.sources", Map.of(), CallOptions.defaults().withTimeoutMs(ms));
        } catch (AgentRpcException e) {
            if (e.error().code() == ErrorCodes.METHOD_NOT_FOUND) {
                sources = Set.of();
                return sources;
            }
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        if (result.value() instanceof Map<?, ?> value && value.get("sources") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> source && source.get("id") instanceof String id) {
                    ids.add(id);
                }
            }
        }
        sources = Collections.unmodifiableSet(ids);
        return sources;
    }

    @Override
    public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
            throws DataException, AgentLostException, InterruptedException {
        Queries.check(source, sql, params, maxRows, timeout);
        if (Thread.interrupted()) {
            throw new InterruptedException("interrupted before data.query");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("source", source);
        args.put("sql", sql);
        args.put("params", new ArrayList<>(params));
        args.put("maxRows", (long) maxRows);
        CallOptions options = timeout == null ? CallOptions.defaults()
                : CallOptions.defaults().withTimeoutMs(Queries.millis(timeout));
        RpcResult result;
        try {
            result = agent.call("data.query", args, options);
        } catch (AgentRpcException e) {
            throw map(source, e.error());
        }
        if (!(result.value() instanceof Map<?, ?> value)) {
            throw new DataException(DataException.Kind.OTHER, source, "data.query on source '" + source
                    + "' answered without rows");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> json = (Map<String, Object>) value;
            return Rows.fromJson(json);
        } catch (IllegalArgumentException malformed) {
            throw new DataException(DataException.Kind.OTHER, source, "data.query on source '" + source
                    + "' answered with malformed rows");
        }
    }

    @Override
    public String kind(String source) {
        return "agent";
    }

    /** Null: the default is the agent's, in the {@code data.query} descriptor of its schema. */
    @Override
    public Duration defaultTimeout(String source) {
        return null;
    }

    /** The {@link DataException} for an error the agent answered {@code data.query} with (the contract's table). */
    static DataException map(String source, RpcError error) {
        int code = error.code();
        String rpc = ErrorCodes.name(code);
        Map<String, Object> data = error.data() == null ? Map.of() : error.data();
        String prefix = "data.query on source '" + source + "' ";
        if (code == ErrorCodes.INVALID_PARAMS && "sql".equals(data.get("param"))) {
            Object reason = data.get("reason");
            return new DataException(DataException.Kind.GUARD, source, prefix + "was refused by the statement guard"
                    + (reason instanceof String r ? ": " + r : ""), null, 0, null, rpc);
        }
        if (code == ErrorCodes.NOT_FOUND) {
            Object known = data.get("known");
            return new DataException(DataException.Kind.UNKNOWN_SOURCE, source, "the agent does not serve data"
                    + " source '" + source + "'" + (known instanceof List<?> ids ? "; it serves " + ids : ""), null, 0,
                    null, rpc);
        }
        if (code == ErrorCodes.CAPABILITY_UNAVAILABLE) {
            return new DataException(DataException.Kind.UNAVAILABLE, source, prefix + "is unavailable in the target"
                    + (data.get("reason") instanceof String r ? " (" + r + ")" : ""), null, 0, null, rpc);
        }
        if (code == ErrorCodes.TIMEOUT) {
            return new DataException(DataException.Kind.TIMEOUT, source, prefix + "timed out"
                    + (data.get("timeoutMs") instanceof Number ms ? " after " + ms + " ms" : ""), null, 0, null,
                    rpc);
        }
        String exception = data.get("exception") instanceof String s ? s : null;
        if (code == ErrorCodes.INTERNAL_ERROR && data.containsKey("maskPolicy")) {
            Object column = data.get("column");
            return new DataException(DataException.Kind.MASK, source, prefix + "failed in a MaskPolicy"
                    + (column instanceof String c ? " on column '" + c + "'" : "") + "; no rows were returned", null,
                    0, exception, rpc);
        }
        if (code == ErrorCodes.INTERNAL_ERROR && data.get("sqlState") instanceof String state) {
            int vendor = data.get("errorCode") instanceof Number n ? n.intValue() : 0;
            return new DataException(DataException.Kind.SQL, source, prefix + "failed: sqlState=" + state
                    + ", errorCode=" + vendor + (exception == null ? "" : " (" + exception + ")"), state, vendor,
                    exception, rpc);
        }
        return new DataException(DataException.Kind.OTHER, source, prefix + "failed with " + rpc
                + (exception == null ? "" : " (" + exception + ")"), null, 0, exception, rpc);
    }

    @Override
    public String toString() {
        return "AgentDataChannel" + (sources == null ? "[sources not read yet]" : sources);
    }
}
