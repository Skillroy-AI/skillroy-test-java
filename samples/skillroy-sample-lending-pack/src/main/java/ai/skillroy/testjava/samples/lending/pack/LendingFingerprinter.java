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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.FingerprintContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The lending pack's fingerprint components (phase2-contracts.md 13.3): what identifies the desk a bindings file was
 * made for.
 *
 * <ul>
 *   <li>{@code lending.schema}: a checksum over the columns (name, type, nullability, in order) of the tables the pack
 *       reads, one short statement per table from {@code information_schema.columns}, so the checksum cannot be
 *       truncated and ignores tables the pack never touches;</li>
 *   <li>{@code lending.site}: the desk's slip header;</li>
 *   <li>{@code lending.seed}: the seed the desk reports in {@code app.state}.</li>
 * </ul>
 *
 * <p>No transactional data, no secret and no personal data, and not the desk's business date ({@code app.state}'s
 * {@code today}, which changes every day: phase3-contracts.md 10.2, decision D44). Why table names in upper case: the
 * sample database stores unquoted names that way, and {@code information_schema} compares them as stored. A failure
 * (a table without columns, no slip header, no seed) is an exception, which the binder reports as ENVIRONMENT.
 *
 * <p>Stateless; called once per bind on the executor's thread.
 */
final class LendingFingerprinter implements EnvFingerprinter {

    /**
     * The tables the pack reads (resolvers, verifiers' reasons aside, and stored-row checks), as stored: from
     * phase3-contracts.md 10.3 on also the waiver rules and their items, which the waiver rule resolver reads.
     */
    static final List<String> TABLES = List.of("STAFF", "STAFF_ROLE", "ROLE_PERMISSION", "ROLE_CAP", "DESK_SETTING",
            "ITEM", "WAIVER_RULE", "WAIVER_RULE_ITEM", "LOAN", "LOAN_LINE", "LOAN_WAIVER", "LOAN_AUTHORISATION");

    static final String COLUMNS_SQL = "SELECT column_name, data_type, is_nullable FROM information_schema.columns"
            + " WHERE table_schema = 'PUBLIC' AND table_name = ? ORDER BY ordinal_position";

    @Override
    public Map<String, String> components(FingerprintContext ctx)
            throws DataException, AgentRpcException, AgentLostException, InterruptedException {
        Map<String, Object> schema = new TreeMap<>();
        for (String table : TABLES) {
            Rows rows = ctx.data().query(LendingResolvers.SOURCE, COLUMNS_SQL, List.of(table), DataChannel.MAX_ROWS,
                    null);
            if (rows.rowCount() == 0) {
                throw new IllegalStateException("the lending schema has no table " + table);
            }
            schema.put(table, rows.rows());
        }
        Rows site = ctx.data().query(LendingResolvers.SOURCE, LendingResolvers.SYSTEM_LIMIT_SQL,
                List.of("slip_header"), DataChannel.MAX_ROWS, null);
        if (site.rowCount() != 1 || site.get(0, 0) == null) {
            throw new IllegalStateException("the desk has no slip_header setting");
        }
        Object state = ctx.agent().call("app.state", Map.of()).value();
        Object seed = state instanceof Map<?, ?> m && m.get(LendingPack.ID) instanceof Map<?, ?> lending
                ? lending.get("seed") : null;
        if (seed == null) {
            throw new IllegalStateException("app.state reports no lending seed");
        }
        Map<String, String> components = new LinkedHashMap<>();
        components.put("lending.schema", sha256(Json.writeCanonical(schema)));
        components.put("lending.site", String.valueOf(site.get(0, 0)));
        components.put("lending.seed", String.valueOf(seed));
        return components;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
