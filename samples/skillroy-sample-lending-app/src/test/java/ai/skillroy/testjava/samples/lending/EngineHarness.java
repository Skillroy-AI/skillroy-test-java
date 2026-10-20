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

package ai.skillroy.testjava.samples.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A headless desk for engine tests: database, services, a {@link RecordingPromptView} and a {@link Recorder},
 * driven only through the inbox and observed through listeners and SQL.
 */
final class EngineHarness implements AutoCloseable {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T10:15:30Z"), ZoneOffset.UTC);
    private static final AtomicInteger COUNTER = new AtomicInteger();

    final LendingDb db;
    final AccessControl access;
    final Catalogue catalogue;
    final WaiverEngine waiverRules;
    final PromptService prompts = new PromptService();
    final SlipPrinter printer;
    final RecordingPromptView view = new RecordingPromptView();
    final Recorder rec = new Recorder();
    final Engine engine;

    private EngineHarness(Seed seed, long idleLockMillis, boolean servable) throws SQLException {
        this(seed, Edition.STANDARD, idleLockMillis, servable);
    }

    private EngineHarness(Seed seed, Edition edition, long idleLockMillis, boolean servable) throws SQLException {
        this(seed, edition, idleLockMillis, servable, Engine.DEFAULT_IDLE_TICK_MILLIS, null);
    }

    /** @param idle a listener added before the engine starts (phase8-contracts.md 12.1), or null */
    private EngineHarness(Seed seed, Edition edition, long idleLockMillis, boolean servable, long idleTickMillis,
                          EngineListener idle) throws SQLException {
        String name = "engine_" + COUNTER.incrementAndGet();
        db = servable ? LendingDb.createServable(name, seed, edition, LocalDate.now(CLOCK))
            : LendingDb.create(name, seed, edition, LocalDate.now(CLOCK));
        access = AccessControl.load(db.connection(), edition);
        catalogue = Catalogue.load(db.connection());
        waiverRules = WaiverEngine.load(db.connection());
        db.connection().commit();
        printer = new SlipPrinter(db.settings().get("slip_header"));
        prompts.setView(view);
        engine = new Engine(db, catalogue, access, waiverRules, prompts, printer, CLOCK, idleLockMillis, edition);
        engine.idleTickMillis(idleTickMillis);
        engine.addListener(rec);
        if (idle != null) {
            engine.addListener(idle);
        }
        engine.start();
        rec.await(0, e -> e.kind == Recorder.Kind.REQUESTED);
    }

    static EngineHarness start(Seed seed) throws SQLException {
        return new EngineHarness(seed, 0, false);
    }

    /** A desk of {@code edition} (phase6-contracts.md 11.3). */
    static EngineHarness start(Seed seed, Edition edition) throws SQLException {
        return new EngineHarness(seed, edition, 0, false);
    }

    static EngineHarness start(Seed seed, long idleLockMillis) throws SQLException {
        return new EngineHarness(seed, idleLockMillis, false);
    }

    /**
     * A desk with this idle lock and idle tick and {@code idle} listening from the start (phase8-contracts.md 12.1).
     */
    static EngineHarness start(Seed seed, long idleLockMillis, long idleTickMillis, EngineListener idle)
            throws SQLException {
        return new EngineHarness(seed, Edition.STANDARD, idleLockMillis, false, idleTickMillis, idle);
    }

    /** A desk whose database {@link LendingDbServer} may serve ({@link LendingDb#createServable}). */
    static EngineHarness startServable(Seed seed) throws SQLException {
        return new EngineHarness(seed, 0, true);
    }

    /** Offers an input, checks it was accepted, and waits until the engine waits again or shows a prompt. */
    Recorder.Ev send(LendingInput input) {
        int mark = rec.size();
        assertTrue(engine.inbox().offer(input), "inbox refused " + input);
        Recorder.Ev accepted = rec.await(mark, e -> e.kind == Recorder.Kind.ACCEPTED);
        assertEquals(input, accepted.input, "first accepted input");
        return rec.await(accepted.index + 1,
            e -> e.kind == Recorder.Kind.REQUESTED || e.kind == Recorder.Kind.PROMPT_SHOWN);
    }

    Recorder.Ev entry(String value) {
        return send(LendingInput.entry(value));
    }

    Recorder.Ev barcode(String code) {
        return send(LendingInput.barcode(code));
    }

    Recorder.Ev key(KeyAction action) {
        return send(LendingInput.key(action));
    }

    Recorder.Ev key(KeyAction action, String value) {
        return send(LendingInput.key(action, value));
    }

    /** Scans the item's barcode. */
    Recorder.Ev scan(String code) {
        return barcode(catalogue.findByCode(code).barcode());
    }

    /** Presses a prompt button and waits until the engine waits again or shows the next prompt. */
    Recorder.Ev answer(String choice) {
        int mark = rec.size();
        view.press(choice);
        return rec.await(mark, e -> e.kind == Recorder.Kind.REQUESTED || e.kind == Recorder.Kind.PROMPT_SHOWN);
    }

    void signOn(String code, String password) {
        expectRequested(entry(code), LendingState.PASSWORD);
        expectRequested(entry(password), LendingState.IDLE);
    }

    static void expectRequested(Recorder.Ev ev, LendingState state) {
        assertEquals(Recorder.Kind.REQUESTED, ev.kind, "expected the engine to wait for input: " + ev);
        assertEquals(state, ev.state, "state");
    }

    static void expectPrompt(Recorder.Ev ev, String id) {
        assertEquals(Recorder.Kind.PROMPT_SHOWN, ev.kind, "expected a prompt: " + ev);
        assertEquals(id, ev.id, "prompt id");
    }

    /** Runs a query on a separate read-only connection. */
    List<List<Object>> query(String sql, Object... params) throws SQLException {
        List<List<Object>> rows = new ArrayList<List<Object>>();
        try (Connection c = db.openReadOnlyConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<Object> row = new ArrayList<Object>();
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        row.add(v instanceof BigDecimal ? ((BigDecimal) v).toPlainString() : v);
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    @Override
    public void close() {
        engine.stop();
        db.close();
    }
}
