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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Routing by credentials.yaml (phase2-contracts.md 7.2, decision D16). */
class DataChannelsTest {

    private static TestDatabase db;

    @BeforeAll
    static void createDatabase() throws Exception {
        db = TestDatabase.create();
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        db.close();
    }

    @Test
    void aJdbcSourceGoesToJdbcAndEveryOtherToTheAgent() throws Exception {
        // 'reporting' is named under jdbc: in credentials.yaml, 'db' only by the agent (a source both serve:
        // aSourceBothChannelsServeIsReadOverJdbc).
        FakeAgent agent = WireAgent.over(db);
        RowMask marker = (source, column, value) -> "jdbc:" + value;
        try (DataChannels channels = DataChannels.of(agent, TestDatabase.credentials("reporting",
                db.source(db.url)), List.of(marker))) {
            assertEquals("jdbc", channels.kind("reporting"));
            assertEquals("agent", channels.kind("db"));
            assertTrue(channels.serves("reporting"));
            assertTrue(channels.serves("db"));
            assertFalse(channels.serves("nowhere"));
            assertEquals("jdbc:100", channels.query("reporting", "SELECT code FROM person WHERE code = ?",
                    List.of("100"), 5, null).get(0, 0), "over JDBC, with the pack's row masks");
            assertEquals("100", channels.query("db", "SELECT code FROM person WHERE code = ?", List.of("100"), 5,
                    null).get(0, 0), "through the agent, which applies its own MaskPolicy");
            assertEquals(1, agent.callsTo("data.query").size(), "only the agent source went to the agent");
            DataException e = assertThrows(DataException.class, () -> channels.query("nowhere", "SELECT 1",
                    List.of(), 1, null));
            assertEquals(DataException.Kind.UNKNOWN_SOURCE, e.kind());
            assertTrue(e.getMessage().contains("over JDBC (credentials.yaml): [reporting]"), e.getMessage());
            assertTrue(e.getMessage().contains("through the agent: [db]"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> channels.query("nowhere", "SELECT 1",
                    List.of(1.5d), 1, null), "argument checks come first");
        }
    }

    @Test
    void aSourceBothChannelsServeIsReadOverJdbc() throws Exception {
        // Decision D16: credentials.yaml decides. The agent serves 'db', and so does the JDBC entry of the same id.
        FakeAgent agent = WireAgent.over(db);
        assertTrue(new AgentDataChannel(agent).serves(WireAgent.SOURCE), "the agent does serve it");
        RowMask marker = (source, column, value) -> "jdbc:" + value;
        try (DataChannels channels = DataChannels.of(agent, TestDatabase.credentials(WireAgent.SOURCE,
                db.source(db.url)), List.of(marker))) {
            assertEquals("jdbc", channels.kind(WireAgent.SOURCE));
            assertTrue(channels.serves(WireAgent.SOURCE));
            assertEquals("jdbc:100", channels.query(WireAgent.SOURCE, "SELECT code FROM person WHERE code = ?",
                    List.of("100"), 5, null).get(0, 0), "over JDBC, with the pack's row masks");
            assertTrue(agent.callsTo("data.query").isEmpty(), "the agent is not asked for a source credentials.yaml"
                    + " names");
        }
    }

    @Test
    void theDefaultTimeoutOfAJdbcSourceIsItsStatementTimeoutAndTheAgentsIsUnknown() throws Exception {
        try (DataChannels channels = DataChannels.of(WireAgent.over(db), TestDatabase.credentials("reporting",
                db.source(db.url, "statementTimeout", "5s"), "plain", db.source(db.url)), null)) {
            assertEquals(Duration.ofSeconds(5), channels.defaultTimeout("reporting"));
            assertEquals(Duration.ofSeconds(10), channels.defaultTimeout("plain"), "statementTimeout's default");
            assertNull(channels.defaultTimeout(WireAgent.SOURCE), "the agent's default is in its data.query"
                    + " descriptor, which the binder reads from the schema");
            assertNull(channels.defaultTimeout("nowhere"));
        }
        assertNull(new AgentDataChannel(new FakeAgent()).defaultTimeout(WireAgent.SOURCE));
    }

    @Test
    void withoutAnAgentOnlyJdbcSourcesAreServed() throws Exception {
        try (DataChannels channels = DataChannels.of(null, TestDatabase.credentials("reporting",
                db.source(db.url)), null)) {
            assertTrue(channels.serves("reporting"));
            assertFalse(channels.serves("db"));
            DataException e = assertThrows(DataException.class, () -> channels.query("db", "SELECT 1", List.of(), 1,
                    null));
            assertTrue(e.getMessage().contains("through the agent: no agent"), e.getMessage());
        }
        try (DataChannels channels = DataChannels.of(new FakeAgent().on("data.sources", p -> Map.of("sources",
                List.of())), null, null)) {
            assertFalse(channels.serves("reporting"), "no credentials: nothing over JDBC");
            assertTrue(channels.toString().contains("JdbcDataChannel[]"), channels.toString());
        }
    }
}
