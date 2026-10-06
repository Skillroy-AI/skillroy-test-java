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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent channel's error mapping (phase2-contracts.md 7.2), row by row, and what it serves. */
class AgentDataChannelTest {

    private static final String DRIVER_TEXT = "Driver-Text-With-Value-8080";

    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            data.put((String) keyValues[i], keyValues[i + 1]);
        }
        return data;
    }

    private static DataException failsWith(int code, Map<String, Object> data) {
        FakeAgent agent = new FakeAgent().fails("data.query", code, "agent message " + DRIVER_TEXT, data);
        AgentDataChannel channel = new AgentDataChannel(agent);
        DataException e = assertThrows(DataException.class, () -> channel.query("app", "SELECT 1", List.of(), 1,
                null));
        assertEquals(ErrorCodes.name(code), e.rpcCode(), "the agent's code is always carried");
        assertEquals("app", e.source());
        assertFalse(e.getMessage().contains(DRIVER_TEXT), "neither the agent's message nor data.message: "
                + e.getMessage());
        assertNull(e.getCause());
        return e;
    }

    @Test
    void theListOfSourcesIsAskedForWithinFiveSecondsOrTheCallersShorterTimeout() throws Exception {
        FakeAgent agent = new FakeAgent().on("data.sources", p -> Map.of("sources", List.of(Map.of("id", "app"))));
        assertEquals(Set.of("app"), new AgentDataChannel(agent).servedSources());
        assertEquals(5_000L, agent.callsTo("data.sources").get(0).options().timeoutMs());
        assertTrue(new AgentDataChannel(agent).serves("app", Duration.ofMillis(800)));
        assertEquals(800L, agent.callsTo("data.sources").get(1).options().timeoutMs());
        assertTrue(new AgentDataChannel(agent).serves("app", Duration.ofSeconds(60)));
        assertEquals(5_000L, agent.callsTo("data.sources").get(2).options().timeoutMs(), "never longer than 5 s");
        try (DataChannels channels = DataChannels.of(agent.on("data.query", p -> Map.of("columns", List.of(),
                "rows", List.of(), "truncated", false)), null, null)) {
            channels.query("app", "SELECT 1", List.of(), 1, Duration.ofMillis(700));
            assertEquals(700L, agent.callsTo("data.sources").get(3).options().timeoutMs(), "a statement's timeout"
                    + " bounds the lookup of its source");
        }
    }

    @Test
    void everyRowOfTheMappingTable() {
        DataException guard = failsWith(ErrorCodes.INVALID_PARAMS, data("param", "sql", "reason",
                "the first word must be SELECT or WITH"));
        assertEquals(DataException.Kind.GUARD, guard.kind());
        assertTrue(guard.getMessage().endsWith("the first word must be SELECT or WITH"), guard.getMessage());

        DataException unknown = failsWith(ErrorCodes.NOT_FOUND, data("source", "app", "known", List.of("a", "b")));
        assertEquals(DataException.Kind.UNKNOWN_SOURCE, unknown.kind());
        assertTrue(unknown.getMessage().contains("[a, b]"), unknown.getMessage());

        assertEquals(DataException.Kind.UNAVAILABLE, failsWith(ErrorCodes.CAPABILITY_UNAVAILABLE,
                data("reason", "no DataSourceProvider")).kind());
        DataException timeout = failsWith(ErrorCodes.TIMEOUT, data("primitive", "data.query", "timeoutMs", 10000L));
        assertEquals(DataException.Kind.TIMEOUT, timeout.kind());
        assertTrue(timeout.getMessage().contains("10000 ms"), timeout.getMessage());

        DataException sql = failsWith(ErrorCodes.INTERNAL_ERROR, data("source", "app", "sqlState", "42501",
                "errorCode", -5501L, "exception", "java.sql.SQLSyntaxErrorException", "message", DRIVER_TEXT));
        assertEquals(DataException.Kind.SQL, sql.kind());
        assertEquals("42501", sql.sqlState());
        assertEquals(-5501, sql.errorCode());
        assertEquals("java.sql.SQLSyntaxErrorException", sql.exceptionClass());

        DataException mask = failsWith(ErrorCodes.INTERNAL_ERROR, data("source", "app", "column", "PASSWORD",
                "maskPolicy", "org.example.Mask", "exception", "java.lang.IllegalStateException"));
        assertEquals(DataException.Kind.MASK, mask.kind());
        assertTrue(mask.getMessage().contains("PASSWORD"), mask.getMessage());
        assertEquals("java.lang.IllegalStateException", mask.exceptionClass());

        // INTERNAL_ERROR without a state (a provider failure without an SQLException, S22), and every other error.
        Map<String, Object> noState = new HashMap<>();
        noState.put("source", "app");
        noState.put("sqlState", null);
        noState.put("errorCode", null);
        noState.put("exception", "java.lang.IllegalStateException");
        DataException provider = failsWith(ErrorCodes.INTERNAL_ERROR, noState);
        assertEquals(DataException.Kind.OTHER, provider.kind());
        assertEquals("java.lang.IllegalStateException", provider.exceptionClass());
        assertEquals(DataException.Kind.OTHER, failsWith(ErrorCodes.INTERNAL_ERROR, null).kind());
        assertEquals(DataException.Kind.OTHER, failsWith(ErrorCodes.INVALID_PARAMS, data("param", "maxRows")).kind());
        assertEquals(DataException.Kind.OTHER, failsWith(ErrorCodes.INVALID_PARAMS, data("param", "params[0]"))
                .kind());
        assertEquals(DataException.Kind.OTHER, failsWith(ErrorCodes.BUSY, data("primitive", "data.query")).kind());
        assertEquals(DataException.Kind.OTHER, failsWith(ErrorCodes.NOT_READY, data("reason", "starting")).kind());
        DataException missing = assertThrows(DataException.class, () -> new AgentDataChannel(new FakeAgent())
                .query("app", "SELECT 1", List.of(), 1, null));
        assertEquals(DataException.Kind.OTHER, missing.kind(), "METHOD_NOT_FOUND on data.query");
        assertEquals("METHOD_NOT_FOUND", missing.rpcCode());
    }

    @Test
    void aTargetWithoutTheDataModuleServesNothingAndIsAskedOnce() throws Exception {
        FakeAgent agent = new FakeAgent();
        AgentDataChannel channel = new AgentDataChannel(agent);
        assertFalse(channel.serves("app"));
        assertFalse(channel.serves("other"));
        assertEquals(Set.of(), channel.servedSources());
        assertEquals(1, agent.callsTo("data.sources").size(), "METHOD_NOT_FOUND is an answer, kept");
    }

    @Test
    void servedSourcesAreReadOnceAndOtherErrorsAreNotKept() throws Exception {
        FakeAgent agent = new FakeAgent().on("data.sources", params -> Map.of("sources", List.of(
                Map.of("id", "app", "describe", "x"), Map.of("id", "audit", "describe", "y"))));
        AgentDataChannel channel = new AgentDataChannel(agent);
        assertTrue(channel.serves("app"));
        assertTrue(channel.serves("audit"));
        assertFalse(channel.serves("db"));
        assertFalse(channel.serves(null));
        assertEquals(List.of("app", "audit"), List.copyOf(channel.servedSources()));
        assertEquals(1, agent.callsTo("data.sources").size());
        assertEquals("agent", channel.kind("app"));

        FakeAgent busy = new FakeAgent().fails("data.sources", ErrorCodes.BUSY, "busy", null);
        AgentDataChannel later = new AgentDataChannel(busy);
        assertFalse(later.serves("app"));
        assertFalse(later.serves("app"));
        assertEquals(2, busy.callsTo("data.sources").size(), "a transient error is asked again");
    }

    @Test
    void theRequestCarriesTheStatementAndTheTimeout() throws Exception {
        FakeAgent agent = new FakeAgent().on("data.query", params -> Map.of("columns",
                List.of(Map.of("name", "N", "type", "INTEGER")), "rows", List.of(List.of(1L)), "rowCount", 1L,
                "truncated", false));
        AgentDataChannel channel = new AgentDataChannel(agent);
        java.util.ArrayList<Object> params = new java.util.ArrayList<>();
        params.add("x");
        params.add(null);
        params.add(2L);
        params.add(true);
        assertEquals(1L, channel.query("app", "SELECT ?", params, 7, Duration.ofMillis(2500)).get(0, 0));
        FakeAgent.Call call = agent.callsTo("data.query").get(0);
        assertEquals("app", call.params().get("source"));
        assertEquals("SELECT ?", call.params().get("sql"));
        assertEquals(params, call.params().get("params"));
        assertEquals(7L, call.params().get("maxRows"));
        assertEquals(2500L, call.options().timeoutMs());
        channel.query("app", "SELECT 1", List.of(), 1, null);
        assertNull(agent.callsTo("data.query").get(1).options().timeoutMs(), "the source's default");
    }

    @Test
    void malformedAnswersAreOther() {
        AgentDataChannel noRows = new AgentDataChannel(new FakeAgent().on("data.query", params -> "rows?"));
        assertEquals(DataException.Kind.OTHER, assertThrows(DataException.class,
                () -> noRows.query("app", "SELECT 1", List.of(), 1, null)).kind());
        AgentDataChannel bad = new AgentDataChannel(new FakeAgent().on("data.query", params -> Map.of("columns",
                "x", "rows", List.of())));
        assertEquals(DataException.Kind.OTHER, assertThrows(DataException.class,
                () -> bad.query("app", "SELECT 1", List.of(), 1, null)).kind());
    }

    @Test
    void aLostAgentIsNotADataError() {
        FakeAgent gone = new FakeAgent().on("data.query", params -> {
            throw new AgentLostException("gone");
        });
        assertThrows(AgentLostException.class, () -> new AgentDataChannel(gone).query("app", "SELECT 1", List.of(),
                1, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentDataChannel(null));
        assertSame(DataException.Kind.OTHER, new DataException(null, "s", "m").kind());
    }

    @Test
    void anInterruptedCallerDoesNotCallTheAgent() {
        FakeAgent agent = new FakeAgent();
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> new AgentDataChannel(agent).query("app", "SELECT 1",
                List.of(), 1, null));
        assertFalse(Thread.currentThread().isInterrupted());
        assertTrue(agent.calls.isEmpty());
    }
}
