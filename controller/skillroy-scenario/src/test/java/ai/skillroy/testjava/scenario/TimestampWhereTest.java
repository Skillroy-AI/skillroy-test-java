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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.sql.ReadOnlyJdbc;
import ai.skillroy.testjava.protocol.sql.StatementGuard;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TIMESTAMP} where values against a real database (HSQLDB, as in the sample) through both channels
 * (phase2-contracts.md 7.4 step 1): an ISO-8601 local date-time, with a {@code T} or a space, travels as
 * {@code yyyy-MM-dd HH:mm:ss[.fraction]}, the one form HSQLDB 2.7.4 accepts for a string bound to a timestamp, with
 * a plain {@code ?} and with {@code CAST(? AS TIMESTAMP)} alike; an offset, {@code Z} or a date alone is
 * RESOLUTION_FAILED / REFERENCE before anything is sent.
 */
class TimestampWhereTest {

    /** Accepted input to the row it must find (41 at 10:00:00, 42 at 10:00:00.123). */
    private static final Map<String, Long> ACCEPTED = new LinkedHashMap<>();

    static {
        ACCEPTED.put("2026-10-02 10:00:00", 41L);
        ACCEPTED.put("2026-10-02T10:00:00", 41L);
        ACCEPTED.put("2026-10-02T10:00", 41L);
        ACCEPTED.put("2026-10-02 10:00:00.000", 41L);
        ACCEPTED.put("2026-10-02 10:00:00.123", 42L);
        ACCEPTED.put("2026-10-02T10:00:00.123000", 42L);
    }

    private static final List<String> REFUSED = List.of("2026-10-02T10:00:00Z", "2026-10-02T10:00:00+02:00",
            "2026-10-02 10:00:00+0200", "2026-10-02", "10:00:00", "yesterday");

    @TempDir
    Path dir;

    private String url;
    private Connection admin;
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private final List<List<Object>> sentToTheAgent = new ArrayList<>();

    @BeforeEach
    void createDatabase() throws SQLException {
        url = "jdbc:hsqldb:mem:timestamp-where-" + System.nanoTime();
        admin = DriverManager.getConnection(url, "SA", "");
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE TABLE loan (id INT PRIMARY KEY, posted_at TIMESTAMP)");
            st.execute("INSERT INTO loan VALUES (41, TIMESTAMP '2026-10-02 10:00:00'),"
                    + " (42, TIMESTAMP '2026-10-02 10:00:00.123'), (43, TIMESTAMP '2026-10-02 00:00:00')");
        }
        // The agent's data.query as the data module answers it: guard, read-only path, JSON on the wire.
        agent.value("data.sources", Map.of("sources", List.of(Map.of("id", "toy"))));
        agent.on("data.query", params -> {
            Map<String, Object> request = Json.parseObject(Json.write(params));
            @SuppressWarnings("unchecked")
            List<Object> sqlParams = (List<Object>) request.get("params");
            sentToTheAgent.add(sqlParams);
            try (Connection c = DriverManager.getConnection(url, "SA", "")) {
                String sql = StatementGuard.check((String) request.get("sql"));
                return Json.parse(Json.write(ReadOnlyJdbc.executeLenient(c, sql, sqlParams,
                        ((Number) request.get("maxRows")).intValue(), 10, line -> { }).toJson()));
            } catch (RpcException e) {
                throw new AgentRpcException("data.query", e.error());
            } catch (SQLException e) {
                throw new AgentRpcException("data.query", new RpcError(ErrorCodes.INTERNAL_ERROR, "query failed",
                        Map.of("source", "toy", "sqlState", String.valueOf(e.getSQLState()), "errorCode",
                                (long) e.getErrorCode())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentRpcException("data.query", new RpcError(ErrorCodes.INTERNAL_ERROR, "interrupted",
                        null));
            }
        });
    }

    @AfterEach
    void dropDatabase() throws SQLException {
        try (Statement st = admin.createStatement()) {
            st.execute("SHUTDOWN");
        }
        admin.close();
    }

    private RunResult run(String value, boolean cast, boolean overJdbc) throws Exception {
        ToyPack pack = new ToyPack();
        StoredColumn postedAt = StoredColumn.of("posted_at", ColumnKind.TIMESTAMP);
        pack.storedTables = List.of(StoredTable.of("loan", "toy", "loan", List.of(
                StoredColumn.of("id", ColumnKind.INTEGER), cast ? postedAt.param("CAST(? AS TIMESTAMP)") : postedAt),
                List.of("id")));
        String yaml = """
                scenario: t
                pack: toy
                resolve:
                  when: { fixture: "%s" }
                steps: []
                verify:
                  - db.loan: { where: { posted_at: $when }, rows: 1 }
                """.formatted(value);
        Plan plan = PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        Credentials credentials = overJdbc ? TestCredentials.of(Map.of("version", 1L, "jdbc", Map.of("toy",
                Map.of("url", url, "user", "SA")))) : Credentials.empty();
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withStepTimeout(Duration.ofMillis(300)).withOutputDir(dir.resolve("out"))
                .withBind(BindOptions.defaults().withCredentials(credentials).withEnvId("seed-a"));
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    @Test
    void everyAcceptedFormFindsItsRowOnBothChannelsWithAndWithoutACast() throws Exception {
        for (boolean overJdbc : List.of(false, true)) {
            for (boolean cast : List.of(false, true)) {
                for (Map.Entry<String, Long> e : ACCEPTED.entrySet()) {
                    String what = e.getKey() + (overJdbc ? " over JDBC" : " through the agent")
                            + (cast ? " with CAST" : " with a plain ?");
                    sentToTheAgent.clear();
                    RunResult result = run(e.getKey(), cast, overJdbc);
                    assertEquals(Status.PASS, result.status(), what + ": " + result.message());
                    if (!overJdbc) {
                        String expected = e.getValue() == 41L ? "2026-10-02 10:00:00" : "2026-10-02 10:00:00.123";
                        assertEquals(List.of(List.of(expected)), sentToTheAgent, what);
                    } else {
                        assertTrue(sentToTheAgent.isEmpty(), what);
                    }
                }
            }
        }
    }

    @Test
    void theRowIsFoundByItsOwnTimestampAndNoOther() throws Exception {
        String yamlValue = "2026-10-02T10:00:00.123";
        RunResult result = run(yamlValue, true, true);
        assertEquals(Status.PASS, result.status(), result.message());
        try (Statement st = admin.createStatement()) {
            st.execute("UPDATE loan SET posted_at = TIMESTAMP '2026-10-02 10:00:00.124' WHERE id = 42");
        }
        RunResult other = run(yamlValue, true, true);
        assertEquals(Status.FAIL, other.status(), "a millisecond apart is another timestamp");
        assertEquals(FailureKind.ASSERTION, other.failureKind());
    }

    @Test
    void anOffsetZOrADateAloneIsAReferenceFailureAndNothingIsRead() throws Exception {
        for (boolean overJdbc : List.of(false, true)) {
            for (String value : REFUSED) {
                sentToTheAgent.clear();
                RunResult result = run(value, true, overJdbc);
                assertEquals(Status.ERROR, result.status(), value);
                assertEquals(FailureKind.RESOLUTION_FAILED, result.failureKind(), value);
                assertEquals("REFERENCE", result.detail().get("reason"), value);
                assertEquals("TIMESTAMP", result.detail().get("kind"), value);
                assertTrue(result.message().contains("yyyy-MM-dd HH:mm:ss[.fraction]"), result.message());
                assertTrue(sentToTheAgent.isEmpty(), value + ": no statement ran");
            }
        }
    }

    @Test
    void theConversionRule() {
        assertEquals("2026-10-02 10:00:00", PlanExecutor.sqlTimestamp("2026-10-02T10:00"));
        assertEquals("2026-10-02 10:00:00.5", PlanExecutor.sqlTimestamp("2026-10-02 10:00:00.500"));
        assertEquals("2026-10-02 10:00:00.123456789", PlanExecutor.sqlTimestamp("2026-10-02T10:00:00.123456789"));
        assertNull(PlanExecutor.sqlTimestamp("2026-10-02"), "a date alone is not a date-time");
        assertNull(PlanExecutor.sqlTimestamp("2026-10-02T10:00:00Z"));
        assertNull(PlanExecutor.sqlTimestamp("2026-02-30T10:00:00"), "no such day");
        assertNull(PlanExecutor.sqlTimestamp(1_790_000_000_000L), "not text");
        assertNull(PlanExecutor.sqlTimestamp(null));
    }
}
