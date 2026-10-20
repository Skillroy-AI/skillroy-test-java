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
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.agent.api.spi.QueryResult;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.protocol.sql.SelectLabels;
import ai.skillroy.testjava.protocol.sql.StatementGuard;
import ai.skillroy.testjava.protocol.sql.ValueConverter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * <p>Phase 8 (phase8-contracts.md 4.2 to 4.4, D527, D529 to D532, D638): the provider's work (either form, and
 * {@code columns(table)}) is handed to the source's lane ({@code DataSourceProvider.lane()}) or, without one, to a
 * nested agent worker task, and the dispatching worker waits at most the call's remaining time less a reserve of
 * {@value #RESERVE_MS} ms. On expiry it marks the read's {@link Control} cancelled, starts the registered cancel
 * actions off the dispatching worker, waits at most {@code reserve - 50 ms} for them and the read together and
 * answers its own {@code TIMEOUT} inside the reserve ({@code data {primitive, timeoutMs, source, lane, waitedMs,
 * cancelled, cancelPending?, stillRunning, enforcesTimeout}}), so the dispatcher never abandons the call. A read of a
 * source without a lane that still runs after its call ended makes the next read of that source answer
 * {@code BUSY} until it ends (the per-source gate); a lane's own {@code BUSY} applies instead on a lane. Columns a
 * provider leaves unnamed take the labels of the statement's select list ({@link SelectLabels}), or, for
 * {@code SELECT * FROM <table>}, the provider's {@code columns(table)}, before the masks run ({@link Table}).
 *
 * <p>Phase 8 security fixes (FB): the masks are asked about a column under every name {@link Table} gives it (a
 * derived label's other spellings, S163; the names an unnamed item references, S160) and the first answer that alters
 * the value is kept; while a mask is registered, the non-null cells of a column no name can be given (a list the
 * statement does not let the agent read, or one the result is wider than, S160, S164) are withheld as
 * {@code SecretKeys.REDACTED}. A {@code TIMEOUT} from the hand-off is the module's own expiry unless the read itself
 * threw it (L1-F5).
 *
 * <p>Safe for concurrent calls on agent worker threads; its state (the per-source gate and the live controls) is
 * guarded by its own locks.
 */
final class DataQuery {

    /** Default and upper bound of {@code maxRows}. */
    static final int MAX_ROWS = 200;
    /** The module answers this long before the dispatcher would (phase8-contracts.md 4.2). */
    static final long RESERVE_MS = 250L;
    /** Of the reserve, what is left when the module answers after giving up on a read (D638). */
    static final long ANSWER_MARGIN_MS = 50L;

    private final SpiRegistry spi;
    private final AgentLog log;
    private final ThreadBroker threads;
    /** Replaces the provider's own opt-in answer; null in production. */
    private final Predicate<DataSourceProvider> optInOverride;
    /** Reads of sources without a lane that still run after their call ended, per source id; guarded by itself. */
    private final Map<String, List<Read>> lingering = new HashMap<String, List<Read>>();
    /** The controls of the reads running now (cancelled on uninstall). */
    private final Set<Control> live = Collections.newSetFromMap(new ConcurrentHashMap<Control, Boolean>());

    /**
     * @param threads the broker the reads are handed to
     * @param optIn answers in place of the providers' own {@code exposesErrorText()} (tests only); null asks the
     *     providers
     */
    DataQuery(SpiRegistry spi, AgentLog log, ThreadBroker threads, Predicate<DataSourceProvider> optIn) {
        this.spi = spi;
        this.log = log;
        this.threads = threads;
        this.optInOverride = optIn;
    }

    /**
     * {@code {sources:[{id, describe, lane, enforcesTimeout}]}} in registration order; a repeated id is listed once
     * ({@code lane} and {@code enforcesTimeout} are Phase 8's, phase8-contracts.md 4.2, 4.3).
     */
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
            source.put("lane", laneOf(provider, id));
            source.put("enforcesTimeout", Boolean.valueOf(enforcesTimeoutOf(provider, id)));
            sources.add(source);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sources", sources);
        return result;
    }

    /**
     * Runs one {@code data.query} call.
     *
     * @param remainingMs time left before the dispatcher gives up, read when the call starts and when it expires
     * @throws InterruptedException if the provider or the waiting worker was interrupted
     */
    Map<String, Object> query(JsonView args, LongSupplier remainingMs) throws InterruptedException {
        String source = args.requireStr("source");
        String sql = args.requireStr("sql");
        int maxRows = maxRows(args);
        List<Object> params = params(args);
        String statement = StatementGuard.check(sql);
        DataSourceProvider provider = find(source);
        long startNanos = System.nanoTime();
        long callMs = Math.max(0L, remainingMs.getAsLong());
        String lane = laneOf(provider, source);
        boolean enforces = enforcesTimeoutOf(provider, source);
        if (lane == null) {
            checkSourceGate(source);
        }
        long budget = Math.max(1L, callMs - RESERVE_MS);
        Control control = new Control(startNanos + TimeUnit.MILLISECONDS.toNanos(budget), log);
        Read read = new Read(provider, source, statement, params, maxRows, budget, control,
                SelectLabels.of(statement), SelectLabels.references(statement), SelectLabels.starTable(statement));
        live.add(control);
        boolean outlived = false;
        try {
            Table.Named rows;
            try {
                rows = lane == null ? threads.call(ThreadPolicy.WORKER, read, budget)
                        : threads.callOnLane(lane, read, budget);
            } catch (RpcException e) {
                if (e.error().code() == ErrorCodes.TIMEOUT) {
                    RpcException own = read.ownTimeout();
                    if (own != null) {
                        // The provider's own TIMEOUT passes through, whichever exception the hand-off rethrew.
                        throw own;
                    }
                    // The hand-off's wait expired (an interruptible read may have ended meanwhile: L1-F5).
                    RpcException timeout = expired(source, lane, enforces, callMs, read, control, startNanos,
                            remainingMs);
                    outlived = !read.ended();
                    throw timeout;
                }
                throw e;
            } catch (InterruptedException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                // Read.call maps every failure but an interrupt; nothing else is expected from the broker.
                throw new IllegalStateException("data.query: unexpected " + e.getClass().getName(), e);
            }
            return mask(rows, source).toJson();
        } finally {
            if (!outlived) {
                live.remove(control);
            }
        }
    }

    /**
     * The module's own {@code TIMEOUT} after its wait on the read expired (D638): cancels the read, starts its cancel
     * actions off this thread, waits at most {@code reserve - 50 ms} (and at most what the dispatcher has left, less
     * 50 ms) for them and the read together, and arms the per-source gate when the read outlives the answer.
     */
    private RpcException expired(String source, String lane, boolean enforces, long callMs, Read read,
                                 Control control, long startNanos, LongSupplier remainingMs)
            throws InterruptedException {
        control.cancel();
        long wait = answerWaitMs(remainingMs.getAsLong());
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait);
        control.startActions(threads);
        control.awaitActions(until);
        read.awaitEnd(until);
        boolean stillRunning = read.started() && !read.ended();
        if (stillRunning) {
            linger(source, lane, read, control);
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("primitive", DataModule.QUERY);
        data.put("timeoutMs", Long.valueOf(callMs));
        data.put("source", source);
        data.put("lane", lane);
        data.put("waitedMs", Long.valueOf(waitedMs));
        data.put("cancelled", Boolean.valueOf(control.cancelledByAction()));
        if (control.actionsPending()) {
            data.put("cancelPending", Boolean.TRUE);
        }
        if (control.batchNotStarted()) {
            // L1-F4: the worker pool rejected the cancel batch (it has no queue); no action runs for this read.
            data.put("cancelNotStarted", Boolean.TRUE);
            log.warn("data: the cancel actions of the read of source '" + source + "' did not start: no agent worker"
                    + " was free");
        }
        data.put("stillRunning", Boolean.valueOf(stillRunning));
        data.put("enforcesTimeout", Boolean.valueOf(enforces));
        log.warn("data: the read of source '" + source + "' did not end within " + read.budgetMs + " ms"
                + (stillRunning ? "; it still runs" : ""));
        return new RpcException(ErrorCodes.TIMEOUT, "data.query on source '" + source + "' timed out after " + callMs
                + " ms", data);
    }

    /**
     * How long the module waits, after giving up on a read, for its cancel actions and the read together (D638,
     * L3-F2): the reserve less {@value #ANSWER_MARGIN_MS} ms, and at most what the dispatcher has left less
     * {@value #ANSWER_MARGIN_MS} ms; at least 1 ms.
     */
    static long answerWaitMs(long remainingMs) {
        return Math.max(1L, Math.min(RESERVE_MS - ANSWER_MARGIN_MS, remainingMs - ANSWER_MARGIN_MS));
    }

    /**
     * Arms the per-source gate for a read that outlives its call (no lane: a lane's own BUSY applies there) and logs
     * its end, which clears the gate and the control.
     */
    private void linger(final String source, String lane, final Read read, final Control control) {
        if (lane == null) {
            synchronized (lingering) {
                List<Read> reads = lingering.get(source);
                if (reads == null) {
                    reads = new ArrayList<Read>();
                    lingering.put(source, reads);
                }
                reads.add(read);
            }
        }
        read.onEnd(new Runnable() {
            @Override
            public void run() {
                synchronized (lingering) {
                    List<Read> reads = lingering.get(source);
                    if (reads != null) {
                        reads.remove(read);
                        if (reads.isEmpty()) {
                            lingering.remove(source);
                        }
                    }
                }
                live.remove(control);
                log.info("data: the read of source '" + source + "' ended after " + read.ranMs() + " ms");
            }
        });
    }

    /** BUSY while an earlier read of the source (no lane) still runs after its call ended. */
    private void checkSourceGate(String source) {
        Read stuck;
        synchronized (lingering) {
            List<Read> reads = lingering.get(source);
            stuck = reads == null || reads.isEmpty() ? null : reads.get(0);
        }
        if (stuck == null) {
            return;
        }
        long runningForMs = stuck.ranMs();
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("reason", "an earlier read of the source still runs");
        data.put("source", source);
        data.put("runningForMs", Long.valueOf(runningForMs));
        throw new RpcException(ErrorCodes.BUSY, "an earlier read of data source '" + source + "' timed out and still"
                + " runs (" + runningForMs + " ms); try again when it has ended", data);
    }

    /**
     * Agent close (phase8-contracts.md 4.3): cancels every live read, starts each one's actions the same way (one
     * posted worker task per read) and waits for them at most {@code waitMs} in all.
     */
    void cancelAll(long waitMs) {
        List<Control> controls = new ArrayList<Control>(live);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        for (Control control : controls) {
            if (control.cancel()) {
                control.startActions(threads);
            }
        }
        try {
            for (Control control : controls) {
                control.awaitActions(until);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The provider's work for one read, on the source's lane or a nested worker task: the five-argument
     * {@code query()} (then {@code columns(table)} for the star form whose result named no column, and the
     * conversion), else {@code open()} and the JDBC read under the read's control. Every failure but an interrupt is
     * mapped here, as before Phase 8.
     */
    private final class Read implements Callable<Table.Named> {
        final DataSourceProvider provider;
        final String source;
        final String statement;
        final List<Object> params;
        final int maxRows;
        final long budgetMs;
        final Control control;
        final List<String> labels;
        final List<List<String>> references;
        final String starTable;
        private final CountDownLatch end = new CountDownLatch(1);
        private volatile boolean started;
        private volatile long startNanos;
        private volatile long endNanos;
        /** The TIMEOUT the read itself threw (the provider's own), or null (L1-F5). */
        private volatile RpcException ownTimeout;
        /** Run once the read has ended, when set before (guarded by end's state through this). */
        private Runnable onEnd;

        Read(DataSourceProvider provider, String source, String statement, List<Object> params, int maxRows,
             long budgetMs, Control control, List<String> labels, List<List<String>> references, String starTable) {
            this.provider = provider;
            this.source = source;
            this.statement = statement;
            this.params = params;
            this.maxRows = maxRows;
            this.budgetMs = budgetMs;
            this.control = control;
            this.labels = labels;
            this.references = references;
            this.starTable = starTable;
        }

        @Override
        public Table.Named call() throws Exception {
            startNanos = System.nanoTime();
            started = true;
            try {
                return read();
            } catch (RpcException e) {
                if (e.error().code() == ErrorCodes.TIMEOUT) {
                    ownTimeout = e;
                }
                throw e;
            } finally {
                endNanos = System.nanoTime();
                Runnable after;
                synchronized (this) {
                    end.countDown();
                    after = onEnd;
                }
                if (after != null) {
                    after.run();
                }
            }
        }

        private Table.Named read() throws InterruptedException {
            try {
                QueryResult result;
                try {
                    result = provider.query(statement, params, maxRows + 1, budgetMs, control);
                } catch (RpcException | SQLException | InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    throw providerFailure(provider, source, "query", e);
                }
                if (result != null) {
                    List<String> starColumns = starTable != null && !named(result)
                            ? columnsOf(provider, source, starTable) : null;
                    return Table.fromQueryResult(result, maxRows, labels, references, starColumns,
                            new Consumer<String>() {
                                @Override
                                public void accept(String line) {
                                    log.info("data: source '" + source + "': " + line);
                                }
                            });
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
                long seconds = Math.max(1L, control.remainingMs() / 1000L);
                return Table.Named.byOwnNames(JdbcExecutor.execute(connection, statement, params, maxRows,
                        (int) Math.min(Integer.MAX_VALUE, seconds), log, control));
            } catch (SQLException e) {
                throw sqlFailure(provider, source, e);
            }
        }

        boolean started() {
            return started;
        }

        /** The TIMEOUT the read itself threw, or null. */
        RpcException ownTimeout() {
            return ownTimeout;
        }

        boolean ended() {
            return end.getCount() == 0;
        }

        void awaitEnd(long untilNanos) throws InterruptedException {
            end.await(Math.max(0L, untilNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        }

        /** How long the read ran (or has run so far). */
        long ranMs() {
            long endAt = ended() ? endNanos : System.nanoTime();
            return started ? TimeUnit.NANOSECONDS.toMillis(endAt - startNanos) : 0L;
        }

        /** Runs {@code after} once the read has ended (at once if it has). */
        void onEnd(Runnable after) {
            boolean now;
            synchronized (this) {
                now = ended();
                if (!now) {
                    onEnd = after;
                }
            }
            if (now) {
                after.run();
            }
        }
    }

    /** True when the provider named at least one column. */
    private static boolean named(QueryResult result) {
        List<String> names = result.columnNames();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code columns(table)} once for the star form (gap 117, D532): null for null, for a list with a null or empty
     * entry, and for a provider that throws (logged by the exception's class only, S22).
     */
    private List<String> columnsOf(DataSourceProvider provider, String source, String table) {
        List<String> columns;
        try {
            columns = provider.columns(table);
        } catch (RuntimeException | LinkageError e) {
            log.warn("data: data source '" + source + "' failed in columns(): " + e.getClass().getName());
            return null;
        }
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        for (String column : columns) {
            if (column == null || column.isEmpty()) {
                log.info("data: data source '" + source + "' answered columns() with an unnamed entry; not used");
                return null;
            }
        }
        return new ArrayList<String>(columns);
    }

    /** The provider's lane, or null; a throw counts as null (logged by class name). */
    private String laneOf(DataSourceProvider provider, String source) {
        try {
            return provider.lane();
        } catch (RuntimeException | LinkageError e) {
            log.warn("data: data source '" + source + "' failed in lane(); its reads run on a worker: "
                    + e.getClass().getName());
            return null;
        }
    }

    /** The provider's {@code enforcesTimeout()}; a throw counts as false (logged by class name). */
    private boolean enforcesTimeoutOf(DataSourceProvider provider, String source) {
        try {
            return provider.enforcesTimeout();
        } catch (RuntimeException | LinkageError e) {
            log.warn("data: data source '" + source + "' failed in enforcesTimeout(): " + e.getClass().getName());
            return false;
        }
    }

    /**
     * The rows with every registered {@code MaskPolicy} applied to every cell, in registration order: each policy is
     * asked under the column's mask names in turn and its first answer that alters the value is kept (S160, S163); a
     * column without mask names has its non-null cells withheld as {@link SecretKeys#REDACTED} (S160, S164).
     */
    private Rows mask(Table.Named named, String source) {
        Rows rows = named.rows;
        List<MaskPolicy> masks = spi.all(MaskPolicy.class);
        if (masks.isEmpty()) {
            return rows;
        }
        if (named.withheld > 0 && rows.rowCount() > 0) {
            log.info("data: source '" + source + "': " + named.withheld + " column(s) could not be named from the"
                    + " statement; their cells are withheld from the answer");
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
                List<String> ask = named.maskNames.get(c);
                if (ask == null) {
                    row.set(c, value == null ? null : SecretKeys.REDACTED);
                    continue;
                }
                for (MaskPolicy mask : masks) {
                    try {
                        Object out = mask.mask(source, ask.get(0), value);
                        for (int k = 1; k < ask.size() && same(out, value); k++) {
                            out = mask.mask(source, ask.get(k), value);
                        }
                        value = out;
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

    /** True when a policy left the value as it was (the same object, or an equal one). */
    private static boolean same(Object out, Object value) {
        return out == value || out != null && out.equals(value);
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
