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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.QueryControl;
import ai.skillroy.testjava.agent.api.spi.QueryResult;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.LendingDb;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Data source {@value #ID} for {@code data.query}: the desk's report reader, which answers by position
 * ({@code LendingDb.readRows}), over the same read-only connection as {@link LendingDataSource} (phase8-contracts.md
 * 4.2 to 4.4, 12.2; D536). It is what a provider of the statement-API form looks like with every Phase 8 member:
 *
 * <ul>
 *   <li>the five-argument {@code query()}: it registers the read's {@code ReadHandle.cancel} with the
 *       {@link QueryControl}, so that the agent stops a read whose caller's time ran out, and returns rows without
 *       column names (the data module names them from the statement's select list);</li>
 *   <li>{@link #enforcesTimeout()}: true, the reader sets the statement's query timeout;</li>
 *   <li>{@link #lane()}: {@value #LANE}, the lane the module declares (1 thread, a queue of 2), so that a slow report
 *       never holds the desk's application lane or the agent's workers; null while no lane was declared (an agent
 *       without lanes), when the reads run on a worker as before;</li>
 *   <li>{@link #columns(String)}: the table's columns from the database's information schema, so that the
 *       controller's allow-list check can read the columns of {@code SELECT * FROM <table> WHERE 1 = 0} (gap 117).</li>
 * </ul>
 *
 * <p>Thread-safe: every read opens a connection of its own, which the reader closes.
 */
final class LendingRowsSource implements DataSourceProvider {

    /** Source id. */
    static final String ID = "lending-report";
    /** The lane the module declares for the source's reads. */
    static final String LANE = "lending.reads";
    /** Its threads and queue. */
    static final int LANE_THREADS = 1;
    static final int LANE_QUEUE = 2;

    private final LendingAccess access;
    private volatile String lane;

    LendingRowsSource(LendingAccess access) {
        this.access = access;
    }

    /** Names the lane the module declared for this source (null: none, the reads run on a worker). */
    void lane(String declared) {
        this.lane = declared;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String describe() {
        return "the desk's report reader, which answers by position";
    }

    @Override
    public QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs, QueryControl control)
            throws Exception {
        LendingApp app = access.app();
        if (app == null) {
            throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "the lending application is not running");
        }
        final LendingDb.ReadHandle handle = new LendingDb.ReadHandle();
        AutoCloseable registration = control.onCancel(new Runnable() {
            @Override
            public void run() {
                try {
                    handle.cancel();
                } catch (SQLException e) {
                    // The agent logs the class name only; the driver's text stays here.
                    throw new IllegalStateException("ReadHandle.cancel failed: " + e.getClass().getName());
                }
            }
        });
        try {
            int seconds = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, timeoutMs / 1000L));
            List<Object[]> rows = app.db().readRows(sql, params, maxRows, seconds, handle);
            List<List<Object>> out = new ArrayList<List<Object>>(rows.size());
            for (Object[] row : rows) {
                out.add(Arrays.asList(row));
            }
            return new QueryResult(null, null, out, false);
        } finally {
            registration.close();
        }
    }

    @Override
    public boolean enforcesTimeout() {
        return true;
    }

    @Override
    public String lane() {
        return lane;
    }

    @Override
    public List<String> columns(String table) {
        LendingApp app = access.app();
        if (app == null) {
            return null;
        }
        try {
            return app.db().columnsOf(table);
        } catch (SQLException e) {
            return null;
        }
    }
}
