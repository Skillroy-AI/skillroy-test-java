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

package ai.skillroy.testjava.scenario.resolve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value loader of a query kind (phase5-contracts.md 5.4 step 4, decision D90): registered on the catalogue's
 * builder, found by kind, carried into a variant's builder, and refused for an undeclared kind, twice for one kind or
 * without identity columns.
 */
class ValueLoaderCatalogueTest {

    record Holder(String code, List<String> roles, Map<String, String> limits) {
    }

    record Who(List<String> must) implements Query<Holder> {
    }

    record Other(String x) implements Query<String> {
    }

    private record Loader(String kind, List<String> identityColumns) implements ValueLoader<Who, Holder> {
        @Override
        public Holder load(Who query, Map<String, Object> identity, ResolveContext ctx) {
            return new Holder(String.valueOf(identity.get("code")), List.of(), Map.of());
        }
    }

    private static ResolutionCatalogue.Builder base() {
        return ResolutionCatalogue.builder().query(QueryType.of("Who", Who.class))
                .query(QueryType.of("Other", Other.class));
    }

    @Test
    void aLoaderIsFoundByItsKind() {
        Loader loader = new Loader("Who", List.of("code"));
        ResolutionCatalogue catalogue = base().valueLoader(loader).build();
        assertSame(loader, catalogue.valueLoader("Who"));
        assertNull(catalogue.valueLoader("Other"), "a kind without a loader");
        assertNull(catalogue.valueLoader("Nope"));
        assertFalse(catalogue.isEmpty());
        assertTrue(ResolutionCatalogue.builder().valueLoader(new Loader("Who", List.of("code")))
                .query(QueryType.of("Who", Who.class)).build().valueLoader("Who") != null, "order of calls is free");
    }

    @Test
    void aVariantKeepsTheBaseLoaders() {
        Loader loader = new Loader("Who", List.of("code"));
        ResolutionCatalogue variant = ResolutionCatalogue.builder(base().valueLoader(loader).build()).build();
        assertSame(loader, variant.valueLoader("Who"));
    }

    @Test
    void buildRefusesAnUndeclaredKindTwoLoadersAndNoIdentity() {
        assertEquals("value loader Loader names the kind Missing, which no query type declares", assertThrows(
                IllegalStateException.class, () -> base().valueLoader(new Loader("Missing", List.of("code")))
                        .build()).getMessage());
        assertEquals("two value loaders for kind Who", assertThrows(IllegalStateException.class,
                () -> base().valueLoader(new Loader("Who", List.of("code")))
                        .valueLoader(new Loader("Who", List.of("id"))).build()).getMessage());
        assertEquals("the value loader of kind Who names no identity column", assertThrows(
                IllegalStateException.class, () -> base().valueLoader(new Loader("Who", List.of())).build())
                .getMessage());
        assertThrows(IllegalStateException.class,
                () -> base().valueLoader(new Loader("Who", List.of("code", "CODE"))).build(), "repeated column");
        assertThrows(NullPointerException.class, () -> base().valueLoader(null));
    }
}
