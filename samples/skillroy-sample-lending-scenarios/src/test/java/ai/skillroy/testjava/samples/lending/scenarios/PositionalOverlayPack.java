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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.scenario.pack.DelegatingDomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.StoredTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The lending pack with its stored tables {@code loan}, {@code loan_line} and {@code loan_authorisation} re-sourced to
 * the agent module's positional source {@value #SOURCE} (phase8-contracts.md 4.4, 12.3): an overlay in the
 * {@link OverlayPack} spirit, built on {@link DelegatingDomainPack} so that every other member (the verbs, the masks,
 * the fingerprint, the hooks, the capabilities) is the pack's own. Test-only: it shows that a {@code db.*} check over a
 * source that answers by position holds, a {@code rows: 0} absence check included, because the agent names the columns
 * from the statement and takes a zero-row answer's width from its select list.
 */
final class PositionalOverlayPack extends DelegatingDomainPack {

    /** The agent module's report reader. */
    static final String SOURCE = "lending-report";
    /** The tables this overlay reads over {@value #SOURCE}. */
    static final Set<String> TABLES = Set.of("loan", "loan_line", "loan_authorisation");

    private final List<StoredTable> tables;

    PositionalOverlayPack(DomainPack pack) {
        super(pack, "positional", "1");
        List<StoredTable> out = new ArrayList<>();
        for (StoredTable table : pack.storedTables()) {
            out.add(TABLES.contains(table.name()) ? StoredTable.of(table.name(), SOURCE, table.tableSql(),
                    table.columns(), table.orderBy()) : table);
        }
        this.tables = List.copyOf(out);
    }

    /** The overlay over the lending pack. */
    static PositionalOverlayPack lending() {
        return new PositionalOverlayPack(Runs.PACK);
    }

    /** {@code src/test/resources/positional/positional-loan.yaml}, the scenario this overlay runs. */
    static Path scenario() {
        return ItEnv.moduleDir().resolve("src").resolve("test").resolve("resources").resolve("positional")
                .resolve("positional-loan.yaml");
    }

    /** The scenario's text. */
    static byte[] scenarioBytes() throws IOException {
        return Files.readAllBytes(scenario());
    }

    @Override
    public List<StoredTable> storedTables() {
        return tables;
    }
}
