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

package ai.skillroy.testjava.agent.data;

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.agent.api.spi.QueryResult;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.protocol.sql.StatementGuard;
import ai.skillroy.testjava.protocol.sql.ValueConverter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The bodies of {@code data.sources} and {@code data.query}: parameter checks, the statement guard, provider lookup,
 * the two {@code DataSourceProvider} forms ({@code query()} first, then {@code open()}), masking and error mapping.
 *
 * <p>Errors: a bad parameter or a rejected statement is {@code INVALID_PARAMS}; an unknown source is
 * {@code NOT_FOUND} listing the known ids; a provider that offers neither form is {@code CAPABILITY_UNAVAILABLE}; a
 * {@code SQLException}, or any other failure of the provider's {@code query()} or {@code open()}, is
 * {@code INTERNAL_ERROR} with {@code source}, {@code sqlState}, {@code errorCode} (both null when no
 * {@code SQLException} is in the cause chain) and {@code exception} (the class name) in {@code data}, and a generic
 * message; a failing {@code MaskPolicy} is {@code INTERNAL_ERROR} too.
 *
 * <p>Driver text (finding S22, which replaces amendment A26): the text of the exception can echo parameter or cell
 * values, including values a {@code MaskPolicy} masks, and a {@code MaskPolicy} cannot scrub free text. So it is never
 * written to the log and never part of an error message; it is returned as {@code data.message} only when the
 * provider opted in ({@code DataSourceProvider.exposesErrorText()}) and no {@code MaskPolicy} is registered, and
 * otherwise {@code data} carries {@code messageWithheld: true}. Parameter values and cell values never appear in an
 * error message or in the log.
 *
 * <p>Stateless apart from its collaborators; safe for concurrent calls on agent worker threads.
 */
final class DataQuery {

    /** Default and upper bound of {@code maxRows}. */
    static final int MAX_ROWS = 200;

    private final SpiRegistry spi;
    private final AgentLog log;
    /** Replaces the provider's own opt-in answer; null in production. */
    private final Predicate<DataSourceProvider> optInOverride;

    /**
     * @param optIn answers in place of the providers' own {@code exposesErrorText()} (tests only); null asks the
     *     providers
     */
    DataQuery(SpiRegistry spi, AgentLog log, Predicate<DataSourceProvider> optIn) {
        this.spi = spi;
        this.log = log;
        this.optInOverride = optIn;
    }

    /** {@code {sources:[{id, describe}]}} in registration order; a repeated id is listed once. */
    Map<String, Object> sources() {
        List<Object> sources = new ArrayList<Object>();
        Set<String> seen = new LinkedHashSet<String>();
        for (DataSourceProvider provider : spi.all(DataSourceProvider.class)) {
            String id = idOf(provider);
            if (id == null || !seen.add(id)) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("id", id);
            source.put("describe", describeOf(provider, id));
            sources.add(source);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sources", sources);
        return result;
    }

    /**
     * Runs one {@code data.query} call.
     *
     * @param remainingMs time left before the dispatcher gives up, read when each stage starts
     * @throws InterruptedException if the worker is interrupted while reading rows
     */
    Map<String, Object> query(JsonView args, LongSupplier remainingMs) throws InterruptedException {
        String source = args.requireStr("source");
        String sql = args.requireStr("sql");
        int maxRows = maxRows(args);
        List<Object> params = params(args);
        String statement = StatementGuard.check(sql);
        DataSourceProvider provider = find(source);
        Rows rows = run(provider, source, statement, params, maxRows, remainingMs);
        return mask(rows, source).toJson();
    }

    private Rows run(DataSourceProvider provider, String source, String statement, List<Object> params,
                      int maxRows, LongSupplier remainingMs) throws InterruptedException {
        try {
            QueryResult result;
            try {
                result = provider.query(statement, params, maxRows + 1, Math.max(1L, remainingMs.getAsLong()));
            } catch (RpcException | SQLException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw providerFailure(provider, source, "query", e);
            }
            if (result != null) {
                return Table.fromQueryResult(result, maxRows);
            }
            Connection connection;
            try {
                connection = provider.open();
            } catch (RuntimeException e) {
                if (e instanceof RpcException) {
                    throw e;
                }
                throw providerFailure(provider, source, "open", e);
            }
            if (connection == null) {
                String reason = "data source '" + source + "' offers neither query() nor open()";
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("source", source);
                data.put("reason", reason);
                throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, reason, data);
            }
            long seconds = Math.max(1L, remainingMs.getAsLong() / 1000L);
            return JdbcExecutor.execute(connection, statement, params, maxRows,
                    (int) Math.min(Integer.MAX_VALUE, seconds), log);
        } catch (SQLException e) {
            throw sqlFailure(provider, source, e);
        }
    }

    /** The rows with every registered {@code MaskPolicy} applied to every cell, in registration order. */
    private Rows mask(Rows rows, String source) {
        List<MaskPolicy> masks = spi.all(MaskPolicy.class);
        if (masks.isEmpty()) {
            return rows;
        }
        List<String> names = new ArrayList<String>(rows.columns().size());
        for (Rows.Column column : rows.columns()) {
            names.add(column.name());
        }
        List<List<Object>> masked = new ArrayList<List<Object>>(rows.rowCount());
        for (List<Object> cells : rows.rows()) {
            List<Object> row = new ArrayList<Object>(cells);
            for (int c = 0; c < row.size(); c++) {
                Object value = row.get(c);
                for (MaskPolicy mask : masks) {
                    try {
                        value = mask.mask(source, names.get(c), value);
                        if (!ValueConverter.isNormal(value)) {
                            value = ValueConverter.convert(value);
                        }
                    } catch (RpcException e) {
                        throw e;
                    } catch (SQLException | RuntimeException e) {
                        throw maskFailure(source, names.get(c), mask, e);
                    }
                }
                row.set(c, value);
            }
            masked.add(row);
        }
        return Rows.of(rows.columns(), masked, rows.truncated());
    }

    private DataSourceProvider find(String source) {
        List<String> known = new ArrayList<String>();
        for (DataSourceProvider provider : spi.all(DataSourceProvider.class)) {
            String id = idOf(provider);
            if (source.equals(id)) {
                return provider;
            }
            if (id != null && !known.contains(id)) {
                known.add(id);
            }
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("source", source);
        data.put("known", known);
        throw new RpcException(ErrorCodes.NOT_FOUND,
                "unknown data source '" + source + "'; known sources: " + known, data);
    }

    private static int maxRows(JsonView args) {
        long requested = args.num("maxRows", MAX_ROWS);
        if (requested < 0) {
            throw invalid("maxRows", "parameter 'maxRows' must not be negative");
        }
        return (int) Math.min(requested, MAX_ROWS);
    }

    /** The {@code params} array as bindable values: String, Boolean, Long, Double, other Numbers, null. */
    private static List<Object> params(JsonView args) {
        List<Object> given = args.list("params");
        List<Object> params = new ArrayList<Object>(given.size());
        for (int i = 0; i < given.size(); i++) {
            Object v = given.get(i);
            if (v == null || v instanceof String || v instanceof Boolean || v instanceof Long
                    || v instanceof Double) {
                params.add(v);
            } else if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
                params.add(Long.valueOf(((Number) v).longValue()));
            } else if (v instanceof Float) {
                params.add(Double.valueOf(v.toString()));
            } else if (v instanceof Number) {
                params.add(v);
            } else if (v instanceof CharSequence || v instanceof Character) {
                params.add(v.toString());
            } else {
                String name = "params[" + i + "]";
                throw invalid(name, "parameter '" + name + "' must be a string, number, boolean or null");
            }
        }
        return Collections.unmodifiableList(params);
    }

    private String idOf(DataSourceProvider provider) {
        try {
            return provider.id();
        } catch (RuntimeException e) {
            log.warn("data: DataSourceProvider " + provider.getClass().getName() + " failed in id()", e);
            return null;
        }
    }

    private String describeOf(DataSourceProvider provider, String id) {
        try {
            return provider.describe();
        } catch (RuntimeException e) {
            log.warn("data: data source '" + id + "' failed in describe()", e);
            return null;
        }
    }

    /**
     * {@code INTERNAL_ERROR} for an {@code SQLException}: {@code data {source, sqlState, errorCode, exception}} and
     * either the driver's text as {@code message} or {@code messageWithheld: true} (see the class comment); the log
     * line and the error message carry no driver text.
     */
    private RpcException sqlFailure(DataSourceProvider provider, String source, SQLException e) {
        String exception = e.getClass().getName();
        log.warn("data: query on source '" + source + "' failed: sqlState=" + e.getSQLState() + ", errorCode="
                + e.getErrorCode() + " (" + exception + ")");
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("source", source);
        data.put("sqlState", e.getSQLState());
        data.put("errorCode", Long.valueOf(e.getErrorCode()));
        data.put("exception", exception);
        driverText(data, provider, e);
        return new RpcException(ErrorCodes.INTERNAL_ERROR, "query on data source '" + source + "' failed: sqlState="
                + e.getSQLState() + ", errorCode=" + e.getErrorCode() + " (" + exception + ")", data);
    }

    /**
     * {@code INTERNAL_ERROR} for any other failure of the provider's {@code query()} or {@code open()}, shaped like
     * {@link #sqlFailure}: {@code sqlState} and {@code errorCode} come from the first {@code SQLException} in the cause
     * chain (a provider that wraps one), else they are null. The log gets the exception's class name only, never the
     * Throwable itself, because the log would append its message and its cause's message.
     */
    private RpcException providerFailure(DataSourceProvider provider, String source, String method, Exception e) {
        SQLException sql = Failures.sqlCause(e);
        String exception = e.getClass().getName();
        log.warn("data: data source '" + source + "' failed in " + method + "(): " + Failures.describe(e));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("source", source);
        data.put("sqlState", sql == null ? null : sql.getSQLState());
        data.put("errorCode", sql == null ? null : Long.valueOf(sql.getErrorCode()));
        data.put("exception", exception);
        driverText(data, provider, e);
        String message = sql == null
                ? "query on data source '" + source + "' failed in " + method + "() (" + exception + ")"
                : "query on data source '" + source + "' failed: sqlState=" + sql.getSQLState() + ", errorCode="
                        + sql.getErrorCode() + " (" + exception + ")";
        return new RpcException(ErrorCodes.INTERNAL_ERROR, message, data);
    }

    /**
     * Puts the exception's own text into {@code data.message} when the provider opted in and no {@code MaskPolicy} is
     * registered (both read now), else {@code data.messageWithheld = true}.
     */
    private void driverText(Map<String, Object> data, DataSourceProvider provider, Exception e) {
        if (spi.all(MaskPolicy.class).isEmpty() && optedIn(provider)) {
            data.put("message", e.getMessage());
        } else {
            data.put("messageWithheld", Boolean.TRUE);
        }
    }

    /** True when the provider opted in to returning its driver's error text; false if asking it fails. */
    private boolean optedIn(DataSourceProvider provider) {
        try {
            return optInOverride != null ? optInOverride.test(provider) : exposesErrorText(provider);
        } catch (RuntimeException | LinkageError t) {
            log.warn("data: DataSourceProvider " + provider.getClass().getName() + " failed in exposesErrorText(): "
                    + t.getClass().getName());
            return false;
        }
    }

    /** What {@code provider.exposesErrorText()} answers (contract section 4); a provider that throws rethrows. */
    static boolean exposesErrorText(DataSourceProvider provider) {
        return provider.exposesErrorText();
    }

    private RpcException maskFailure(String source, String column, MaskPolicy mask, Exception e) {
        // Log and report without the value: the mask exists because the value must not leave the JVM.
        log.warn("data: MaskPolicy " + mask.getClass().getName() + " failed on column '" + column + "' of source '"
                + source + "': " + e.getClass().getName());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("source", source);
        data.put("column", column);
        data.put("maskPolicy", mask.getClass().getName());
        data.put("exception", e.getClass().getName());
        return new RpcException(ErrorCodes.INTERNAL_ERROR, "MaskPolicy " + mask.getClass().getName()
                + " failed on column '" + column + "'; no rows are returned", data);
    }

    private static RpcException invalid(String param, String message) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("param", param);
        return new RpcException(ErrorCodes.INVALID_PARAMS, message, data);
    }
}
