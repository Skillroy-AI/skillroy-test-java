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
import ai.skillroy.testjava.samples.lending.LendingApp;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Data source {@value #ID} for {@code data.query}: the desk's own database through a connection the application
 * opens read-only for any thread ({@code LendingDb.openReadOnlyConnection()}). Why the application's connection and not
 * a JDBC URL: no credentials leave the JVM, and the database's MVCC means readers never wait for the engine's open
 * transaction and see committed loans only. The engine's own connection is never handed out.
 *
 * <p>Thread-safe: each {@link #open()} returns a new connection, which the data module closes after one query.
 */
final class LendingDataSource implements DataSourceProvider {

    /** Source id. */
    static final String ID = "lending";

    private final LendingAccess access;

    LendingDataSource(LendingAccess access) {
        this.access = access;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String describe() {
        return "the sample lending desk's HSQLDB database (in memory), read-only connection";
    }

    @Override
    public Connection open() throws SQLException {
        LendingApp app = access.app();
        if (app == null) {
            throw new SQLException("the lending application is not running");
        }
        return app.db().openReadOnlyConnection();
    }
}
