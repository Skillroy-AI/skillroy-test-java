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

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The resolution SPI's value types (phase2-contracts.md 6, 14.1 item 5): RecordCodec, NumberRange, the catalogue. */
class ResolveSpiTest {

    enum Colour { RED, GREEN }

    record Inner(String name, Integer count) {
    }

    record Everything(String text, Boolean flag, boolean primitive, Long big, int small, BigDecimal money,
                      Colour colour, LocalDate day, Instant at, List<Inner> inners, Map<String, BigDecimal> limits,
                      NumberRange range) {
    }

    record Bad(Optional<String> maybe) {
    }

    record Q(String type) implements Query<BigDecimal> {
    }

    record M(List<String> actions) implements Query<Map<String, String>> {
    }

    record O(String x) implements Query<Optional<String>> {
    }

    @Test
    void recordCodecRoundTripsEverySupportedTypeWithSortedKeys() throws Exception {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("text", "abc");
        json.put("flag", true);
        json.put("primitive", false);
        json.put("big", 9_000_000_000L);
        json.put("small", 7L);
        json.put("money", "34.90");
        json.put("colour", "GREEN");
        json.put("day", "2026-10-02");
        json.put("at", "2026-10-02T09:30:00Z");
        json.put("inners", List.of(Map.of("name", "a", "count", 1L)));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("RETURN", "20.00");
        limits.put("NONE", null);
        json.put("limits", limits);
        json.put("range", Map.of("between", List.of("1", 2.5), "lte", 2L));
        Everything e = (Everything) RecordCodec.DEFAULT.decode(Everything.class, json);
        assertEquals(new BigDecimal("34.90"), e.money());
        assertEquals(Colour.GREEN, e.colour());
        assertEquals(LocalDate.of(2026, 10, 2), e.day());
        assertEquals(7, e.small());
        assertNull(e.limits().get("NONE"));
        assertEquals(new BigDecimal("2"), e.range().upperBound());
        Object encoded = RecordCodec.DEFAULT.encode(e);
        Map<?, ?> map = (Map<?, ?>) encoded;
        assertEquals(List.of("at", "big", "colour", "day", "flag", "inners", "limits", "money", "primitive",
                "range", "small", "text"), new ArrayList<>(map.keySet()), "keys sorted");
        assertEquals("34.90", map.get("money"), "a decimal as its plain string with its scale");
        assertEquals(7L, map.get("small"));
        assertEquals(List.of("NONE", "RETURN"), new ArrayList<>(((Map<?, ?>) map.get("limits")).keySet()));
        assertEquals(e, RecordCodec.DEFAULT.decode(Everything.class, encoded), "round trip");
        assertNull(RecordCodec.DEFAULT.unsupported(Everything.class));
        Map<String, Object> missing = new LinkedHashMap<>(json);
        missing.remove("text");
        assertNull(((Everything) RecordCodec.DEFAULT.decode(Everything.class, missing)).text(),
                "a missing key decodes as null");
    }

    @Test
    void recordCodecErrors() {
        Map<String, Object> base = Map.of("primitive", true, "small", 1L);
        assertEquals("text: expected text, found a number (write codes in quotes)", error(Map.of("primitive",
                true, "small", 1L, "text", 100L)));
        assertTrue(error(Map.of("small", 1L)).startsWith("primitive: a value is required"));
        assertTrue(error(withEntry(base, "colour", "BLUE")).contains("expected one of [RED, GREEN]"));
        assertTrue(error(withEntry(base, "small", 3_000_000_000L)).contains("does not fit an int"));
        assertTrue(error(withEntry(base, "money", "abc")).startsWith("money: expected a decimal"));
        assertTrue(error(withEntry(base, "day", "02.10.2026")).contains("ISO-8601"));
        assertTrue(error(withEntry(base, "nope", 1L)).contains("unknown key 'nope'"));
        assertTrue(error(withEntry(base, "inners", List.of(Map.of("name", 1L)))).startsWith("inners[0].name"));
        assertTrue(error(withEntry(base, "range", Map.of("between", List.of("1")))).contains("refused the value"));
        assertTrue(error(withEntry(base, "flag", "true")).contains("expected true or false"));
        assertTrue(RecordCodec.DEFAULT.unsupported(Bad.class).contains("Optional"));
        assertTrue(RecordCodec.DEFAULT.unsupported(int[].class) != null);
        List<String> problems = RecordCodec.DEFAULT.check(Everything.class, Map.of("text", "$x.y", "money",
                "$amount", "colour", "PURPLE", "zzz", 1L, "primitive", true, "small", 1L));
        assertEquals(2, problems.size(), "references stand for any value: " + problems);
        assertFalse(error(withEntry(base, "text", 100L)).contains("100"), "never quotes a value");
    }

    private static Map<String, Object> withEntry(Map<String, Object> base, String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put(key, value);
        return out;
    }

    private static String error(Object json) {
        return assertThrows(CodecException.class, () -> RecordCodec.DEFAULT.decode(Everything.class, json))
                .getMessage();
    }

    @Test
    void numberRangeIsAConjunctionWithInclusiveBetween() {
        NumberRange range = new NumberRange(new BigDecimal("1"), null, null, new BigDecimal("10"),
                List.of(new BigDecimal("0"), new BigDecimal("5")));
        assertFalse(range.test(new BigDecimal("1")), "gt is strict");
        assertTrue(range.test(new BigDecimal("5")), "between is inclusive");
        assertFalse(range.test(new BigDecimal("5.01")));
        assertFalse(range.test(null));
        assertEquals(new BigDecimal("1"), range.lowerBound());
        assertEquals(new BigDecimal("5"), range.upperBound());
        NumberRange open = new NumberRange(null, null, null, null, null);
        assertNull(open.lowerBound());
        assertTrue(open.test(BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class, () -> new NumberRange(null, null, null, null,
                List.of(BigDecimal.ONE)));
    }

    @Test
    void queryTypeReadsTheValueTypeFromTheDeclaration() {
        assertEquals(BigDecimal.class, QueryType.of("Q", Q.class).valueType());
        Type map = QueryType.of("M", M.class).valueType();
        assertEquals("java.util.Map<java.lang.String, java.lang.String>", map.getTypeName());
        assertThrows(IllegalArgumentException.class, () -> QueryType.of("bad kind", Q.class));
        assertSame(RecordCodec.DEFAULT, QueryType.of("Q", Q.class).codec());
        assertEquals(Strategy.CANONICAL_SQL, Strategy.ofToken("SQL"));
        assertEquals(Tier.C, Strategy.LLM_VISION.tier());
        assertThrows(IllegalArgumentException.class, () -> BindMode.parse("re-resolve"));
        assertEquals(BindMode.APPROVE, BindMode.parse("Approve"));
    }

    private static Resolver<Q, BigDecimal> resolver(String id, Strategy strategy) {
        return new Resolver<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Class<Q> queryClass() {
                return Q.class;
            }

            @Override
            public Strategy strategy() {
                return strategy;
            }

            @Override
            public List<ResolverCandidate<BigDecimal>> candidates(Q query, ResolveContext ctx) {
                return List.of(ResolverCandidate.of(BigDecimal.ONE, id));
            }
        };
    }

    private static Verifier<Q, BigDecimal> verifier(String id) {
        return new Verifier<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Class<Q> queryClass() {
                return Q.class;
            }

            @Override
            public VerifierResult verify(Q query, BigDecimal candidate, VerifyContext ctx) {
                return VerifierResult.pass(id, Map.of());
            }
        };
    }

    static final class Twice implements Oracle<BigDecimal, BigDecimal> {
        @Override
        public String fn() {
            return "Twice";
        }

        @Override
        public BigDecimal compute(BigDecimal input) {
            return input.add(input);
        }
    }

    @Test
    void theCatalogueValidatesOverlaysAndReplaces() {
        ResolutionCatalogue base = ResolutionCatalogue.builder().query(QueryType.of("Q", Q.class))
                .resolver(resolver("p.sql.Q", Strategy.CANONICAL_SQL))
                .resolver(resolver("p.java.Q", Strategy.CANONICAL_JAVA))
                .verifier(verifier("p.check")).oracle(new Twice()).build();
        assertEquals(List.of("p.sql.Q", "p.java.Q"), base.resolvers("Q").stream().map(Resolver::id).toList());
        assertEquals("p.check", base.verifier("Q", "p.check").id());
        assertNull(base.verifier("Q", "other"));
        assertEquals(List.of(), base.resolvers("Nope"));
        Resolver<Q, BigDecimal> variant = resolver("p.sql.Q", Strategy.CANONICAL_SQL);
        ResolutionCatalogue overlay = ResolutionCatalogue.builder(base).replace(variant).build();
        assertSame(variant, overlay.resolvers("Q").get(0), "replaced in place");
        assertEquals(2, overlay.resolvers("Q").size());
        assertThrows(IllegalStateException.class, () -> ResolutionCatalogue.builder(base)
                .replace(resolver("p.sql.Other", Strategy.CANONICAL_SQL)));
        assertThrows(IllegalStateException.class, () -> ResolutionCatalogue.builder(base)
                .replace(verifier("p.other")));
        assertTrue(ResolutionCatalogue.EMPTY.isEmpty());

        assertEquals("two query types share the kind Q", buildFails(ResolutionCatalogue.builder(base)
                .query(QueryType.of("Q", M.class))));
        assertTrue(buildFails(ResolutionCatalogue.builder(base).query(QueryType.of("Q2", Q.class)))
                .contains("two kinds"));
        assertTrue(buildFails(ResolutionCatalogue.builder().resolver(resolver("x", Strategy.CANONICAL_SQL)))
                .contains("no query type declares"));
        assertTrue(buildFails(ResolutionCatalogue.builder(base).resolver(resolver("p.sql.Q",
                Strategy.CANONICAL_SQL))).contains("two resolvers share the id"));
        assertTrue(buildFails(ResolutionCatalogue.builder(base).oracle(new Twice())).contains("two oracles"));
        assertTrue(buildFails(ResolutionCatalogue.builder(base).verifier(verifier("p.check")))
                .contains("two verifiers share the id p.check and the query class"));
        assertTrue(buildFails(ResolutionCatalogue.builder(base).resolver(resolver("p.llm.Q", Strategy.LLM_SQL)))
                .contains("inference module"));
        assertTrue(buildFails(ResolutionCatalogue.builder(base).resolver(resolver("inference.sql.Q",
                Strategy.CANONICAL_SQL))).contains("reserved"));
        assertTrue(buildFails(ResolutionCatalogue.builder().query(QueryType.of("O", O.class)))
                .contains("not supported"));
        assertTrue(buildFails(ResolutionCatalogue.builder(base).oracle(new Oracle<Object, Object>() {
            @Override
            public String fn() {
                return "Raw";
            }

            @Override
            public Object compute(Object input) {
                return input;
            }
        })).contains("not supported"));
    }

    private static String buildFails(ResolutionCatalogue.Builder builder) {
        return assertThrows(IllegalStateException.class, builder::build).getMessage();
    }

    @Test
    void storedTablesAndColumnsValidate() {
        StoredColumn decimal = StoredColumn.of("amount", ColumnKind.DECIMAL).param("CAST(? AS DECIMAL(10,2))");
        assertEquals("amount", decimal.sql());
        assertThrows(IllegalArgumentException.class, () -> decimal.param("CAST(? AS ?)"));
        assertThrows(IllegalArgumentException.class, () -> StoredColumn.of("1x", ColumnKind.TEXT));
        assertThrows(IllegalArgumentException.class, () -> StoredTable.of("t", "s", "t", List.of(decimal),
                List.of("nope")));
        assertThrows(IllegalArgumentException.class, () -> StoredTable.of("t", "s", "t", List.of(decimal, decimal),
                List.of("amount")));
        assertEquals(decimal, StoredTable.of("t", "s", "t", List.of(decimal), List.of("amount")).column("amount"));
    }

    @Test
    void theLedgerIsThreadSafeAndCountsEntries() throws Exception {
        CostLedger ledger = new CostLedger();
        assertTrue(ledger.isEmpty());
        Thread a = new Thread(() -> ledger.record(new CostLedger.Entry("s", null, Strategy.LLM_SQL, "p", "m", "r",
                1, 2, "0.01")));
        a.start();
        a.join();
        assertEquals(1, ledger.size());
        assertEquals("CostLedger[1 entries]", ledger.toString());
    }
}
