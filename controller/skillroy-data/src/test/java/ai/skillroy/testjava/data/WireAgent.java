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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.sql.ReadOnlyJdbc;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.protocol.sql.StatementGuard;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fake agent that answers {@code data.sources} and {@code data.query} the way the agent's data module does, with the
 * real {@link StatementGuard} and {@link ReadOnlyJdbc} on a connection of the read-only account, and passes request and
 * response through {@code Json.write} and {@code Json.parse} as the wire does (phase2-contracts.md 14.1 item 3).
 */
final class WireAgent {

    static final String SOURCE = "db";

    private WireAgent() {
    }

    @SuppressWarnings("unchecked")
    static FakeAgent over(TestDatabase db) {
        FakeAgent agent = new FakeAgent();
        agent.on("data.sources", params -> Json.parse(Json.write(Map.of("sources",
                List.of(Map.of("id", SOURCE, "describe", "test database"))))));
        agent.on("data.query", params -> {
            Map<String, Object> request = Json.parseObject(Json.write(params));
            String source = (String) request.get("source");
            if (!SOURCE.equals(source)) {
                throw new AgentRpcException("data.query", new RpcError(ErrorCodes.NOT_FOUND, "unknown data source '"
                        + source + "'", Map.of("source", source, "known", List.of(SOURCE))));
            }
            String statement;
            try {
                statement = StatementGuard.check((String) request.get("sql"));
            } catch (RpcException rejected) {
                throw new AgentRpcException("data.query", rejected.error());
            }
            List<Object> sqlParams = (List<Object>) request.get("params");
            int maxRows = ((Number) request.get("maxRows")).intValue();
            try (Connection connection = db.reader()) {
                Rows rows = ReadOnlyJdbc.executeLenient(connection, statement, sqlParams, maxRows, 10, line -> { });
                return Json.parse(Json.write(rows.toJson()));
            } catch (SQLException e) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("source", source);
                data.put("sqlState", e.getSQLState());
                data.put("errorCode", (long) e.getErrorCode());
                data.put("exception", e.getClass().getName());
                data.put("messageWithheld", true);
                throw new AgentRpcException("data.query", new RpcError(ErrorCodes.INTERNAL_ERROR, "query on data"
                        + " source '" + source + "' failed: sqlState=" + e.getSQLState(), data));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentRpcException("data.query", new RpcError(ErrorCodes.INTERNAL_ERROR, "interrupted",
                        null));
            }
        });
        return agent;
    }
}
