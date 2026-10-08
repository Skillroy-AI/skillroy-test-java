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

package ai.skillroy.testjava.inference.resolve;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.CredentialIndex;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A resolver's context as the engine gives one, for the resolver's own tests: the bind's data channel (recording each
 * statement as the engine's provenance does), the run's ledger, the notes, the slot, the scenario, the mode and what
 * is left of the bind budget. The agent is not offered (a tier (b) resolver never calls a primitive).
 */
final class TestContext implements ResolveContext {

    final DataChannel channel;
    final CostLedger ledger;
    final List<String> notes = new ArrayList<>();
    final List<String> statements = new ArrayList<>();
    BindMode mode = BindMode.LIVE;
    long remainingMs = 60_000;
    int maxCandidates = 20;

    TestContext(DataChannel channel, CostLedger ledger) {
        this.channel = channel;
        this.ledger = ledger;
    }

    @Override
    public AgentConnection agent() {
        throw new IllegalStateException("a tier (b) resolver calls no primitive");
    }

    @Override
    public DataChannel data() {
        return new DataChannel() {
            @Override
            public boolean serves(String source) throws AgentLostException {
                return channel.serves(source);
            }

            @Override
            public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
                    throws DataException, AgentLostException, InterruptedException {
                statements.add(sql + " " + params);
                return channel.query(source, sql, params, maxRows, timeout);
            }

            @Override
            public String kind(String source) {
                return channel.kind(source);
            }
        };
    }

    @Override
    public CredentialSpec credential() {
        return null;
    }

    @Override
    public CredentialIndex credentials() {
        return (realm, id) -> false;
    }

    @Override
    public String envId() {
        return "test-env";
    }

    @Override
    public BindMode mode() {
        return mode;
    }

    @Override
    public int maxCandidates() {
        return maxCandidates;
    }

    @Override
    public long remainingMs() {
        return remainingMs;
    }

    @Override
    public CostLedger ledger() {
        return ledger;
    }

    @Override
    public String slot() {
        return "item";
    }

    @Override
    public String scenarioId() {
        return "desk-scenario";
    }

    @Override
    public void note(String text) {
        notes.add(text);
    }

    @Override
    public Rows requireComplete(Rows rows) throws ResolutionException {
        if (rows.truncated()) {
            throw new ResolutionException("TRUNCATED", null, "truncated");
        }
        return rows;
    }

    /** The notes joined, for assertions. */
    String allNotes() {
        return String.join("\n", notes);
    }
}
