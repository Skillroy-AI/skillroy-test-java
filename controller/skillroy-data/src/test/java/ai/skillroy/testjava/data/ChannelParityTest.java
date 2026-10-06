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

import ai.skillroy.testjava.protocol.sql.Rows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Over either channel" (phase2-contracts.md 2.2, 14.1 item 3): every statement of the contract, run through the JDBC
 * channel and through the agent channel over the wire, gives equal {@link Rows}, and the two channels record identical
 * parameters.
 */
class ChannelParityTest {

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
    void theSameStatementGivesEqualRowsOnBothChannels() throws Exception {
        FakeAgent agent = WireAgent.over(db);
        try (JdbcDataChannel jdbc = new JdbcDataChannel(TestDatabase.credentials(WireAgent.SOURCE,
                db.source(db.url)), null)) {
            AgentDataChannel overAgent = new AgentDataChannel(agent);
            int compared = 0;
            for (DataChannelContract.Statement s : DataChannelContract.STATEMENTS) {
                Rows viaJdbc = jdbc.query(WireAgent.SOURCE, s.sql(), new ArrayList<>(s.params()), s.maxRows(), null);
                Rows viaAgent = overAgent.query(WireAgent.SOURCE, s.sql(), new ArrayList<>(s.params()), s.maxRows(),
                        null);
                assertEquals(viaJdbc, viaAgent, s.sql());
                compared++;
            }
            assertEquals(DataChannelContract.STATEMENTS.size(), compared);
            // What went over the wire are the same Java values the JDBC channel bound.
            List<FakeAgent.Call> queries = agent.callsTo("data.query");
            assertEquals(compared, queries.size());
            for (int i = 0; i < compared; i++) {
                assertEquals(DataChannelContract.STATEMENTS.get(i).params(), queries.get(i).params().get("params"));
            }
            assertTrue(jdbc.connected(WireAgent.SOURCE), "one kept connection served every statement");
        }
    }
}
