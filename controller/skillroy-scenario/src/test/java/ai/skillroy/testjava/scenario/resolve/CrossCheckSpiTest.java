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

/**
 * The cross-check SPI (phase3-contracts.md 5.1, 5.2, 11.1 item 1): {@link CrossCheck}'s type defaults,
 * {@link CrossCheckResult}, the catalogue's registration, lookups, overlays and every {@code build()} refusal.
 */
class CrossCheckSpiTest {

    /** A money oracle: the input twice. */
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

    /** An oracle of other types. */
    static final class Label implements Oracle<String, String> {
        @Override
        public String fn() {
            return "Label";
        }

        @Override
        public String compute(String input) {
            return "label " + input;
        }
    }

    /** A check whose types come from a generic base class: they are resolved through it. */
    abstract static class Base<I, O> implements CrossCheck<I, O> {
        @Override
        public CrossCheckResult check(I input, O output, BigDecimal tolerance, VerifyContext ctx) {
            return CrossCheckResult.agree("agrees", output, Map.of());
        }
    }

    /** A check for {@code Twice}. */
    static class TwiceCheck extends Base<BigDecimal, BigDecimal> {
        private final String id;

        TwiceCheck(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String oracleFn() {
            return "Twice";
        }
    }

    /** A check that declares other types than its oracle's. */
    static final class LabelForTwice extends Base<String, String> {
        @Override
        public String id() {
            return "p.label.twice";
        }

        @Override
        public String oracleFn() {
            return "Twice";
        }
    }

    private static ResolutionCatalogue.Builder base() {
        return ResolutionCatalogue.builder().oracle(new Twice()).oracle(new Label());
    }

    private static String buildFails(ResolutionCatalogue.Builder builder) {
        return assertThrows(IllegalStateException.class, builder::build).getMessage();
    }

    @Test
    void theDefaultsReadTheTypesFromTheDeclarationThroughAGenericBase() {
        TwiceCheck check = new TwiceCheck("p.twice");
        assertEquals(BigDecimal.class, check.inputType());
        assertEquals(BigDecimal.class, check.outputType());
        assertEquals(List.of(), check.requires(), "no requirement by default");
        assertFalse(check.acceptsTolerance(), "no tolerance by default");
    }

    @Test
    void theResultFreezesItsValuesWithSortedKeys() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("zeta", 1L);
        evidence.put("alpha", Map.of("y", 1L, "b", 2L));
        CrossCheckResult result = CrossCheckResult.disagree("no", Map.of("z", 1L, "a", 2L), evidence);
        assertFalse(result.agreed());
        assertEquals(List.of("alpha", "zeta"), List.copyOf(result.evidence().keySet()));
        assertEquals(List.of("b", "y"), List.copyOf(((Map<?, ?>) result.evidence().get("alpha")).keySet()));
        assertEquals(List.of("a", "z"), List.copyOf(((Map<?, ?>) result.actual()).keySet()));
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().put("x", 1));
        assertTrue(CrossCheckResult.agree("yes", null, null).agreed());
        assertEquals(Map.of(), CrossCheckResult.agree("yes", null, null).evidence());
    }

    @Test
    void theCatalogueFindsChecksByIdAndByOracleFunction() {
        TwiceCheck b = new TwiceCheck("p.twice.b");
        TwiceCheck a = new TwiceCheck("p.twice.a");
        ResolutionCatalogue catalogue = base().crossCheck(b).crossCheck(a).build();
        assertSame(a, catalogue.crossCheck("p.twice.a"));
        assertNull(catalogue.crossCheck("p.nope"));
        assertEquals(List.of(a, b), catalogue.crossChecks("Twice"), "by id, whatever the registration order");
        assertEquals(List.of(), catalogue.crossChecks("Label"));
        assertEquals(List.of(), catalogue.crossChecks("Nope"));
        assertFalse(catalogue.isEmpty());
        assertTrue(catalogue.toString().contains("2 cross-checks"), catalogue.toString());
        assertFalse(ResolutionCatalogue.builder().crossCheck(a).oracle(new Twice()).build().isEmpty());
    }

    @Test
    void aVariantReplacesACheckInPlace() {
        TwiceCheck original = new TwiceCheck("p.twice");
        ResolutionCatalogue catalogue = base().crossCheck(original).crossCheck(new TwiceCheck("p.other")).build();
        TwiceCheck variant = new TwiceCheck("p.twice");
        ResolutionCatalogue overlay = ResolutionCatalogue.builder(catalogue).replace(variant).build();
        assertSame(variant, overlay.crossCheck("p.twice"));
        assertSame(original, catalogue.crossCheck("p.twice"), "the base is unchanged");
        assertEquals(2, overlay.crossChecks("Twice").size());
        IllegalStateException none = assertThrows(IllegalStateException.class, () -> ResolutionCatalogue
                .builder(catalogue).replace(new TwiceCheck("p.missing")));
        assertEquals("no cross-check p.missing to replace", none.getMessage());
    }

    @Test
    void buildRefusesEveryInvalidCheck() {
        assertEquals("two cross-checks share the id p.twice", buildFails(base().crossCheck(new TwiceCheck("p.twice"))
                .crossCheck(new TwiceCheck("p.twice"))));
        assertEquals("cross-check p.orphan names the oracle function Nope, which no oracle declares",
                buildFails(base().crossCheck(new TwiceCheck("p.orphan") {
                    @Override
                    public String oracleFn() {
                        return "Nope";
                    }
                })));
        String mismatch = buildFails(base().crossCheck(new LabelForTwice()));
        assertTrue(mismatch.startsWith("cross-check p.label.twice has input java.lang.String and output"
                + " java.lang.String, but oracle Twice has input java.math.BigDecimal"), mismatch);
        assertTrue(buildFails(base().crossCheck(new TwiceCheck("p.twice") {
            @Override
            public Type outputType() {
                return String.class;
            }
        })).contains("types must equal its oracle's"), "an output that differs alone is refused too");
        for (String reserved : List.of("inference", "inference.check")) {
            assertEquals("cross-check id " + reserved + ": the prefix 'inference' is reserved for what the controller"
                    + " contributes", buildFails(base().crossCheck(new TwiceCheck(reserved))));
        }
        assertTrue(buildFails(base().crossCheck(new TwiceCheck(" "))).contains("has no id"));
        Type optional;
        try {
            optional = CrossCheckSpiTest.class.getDeclaredMethod("optional").getGenericReturnType();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        String unsupported = buildFails(base().crossCheck(new TwiceCheck("p.optional") {
            @Override
            public Type inputType() {
                return optional;
            }
        }));
        assertTrue(unsupported.startsWith("input type of cross-check p.optional is not supported by its codec"),
                unsupported);
        assertEquals("cross-check p.null returned null from requires()", buildFails(base().crossCheck(
                new TwiceCheck("p.null") {
                    @Override
                    public List<String> requires() {
                        return null;
                    }
                })));
    }

    /** A type the codec does not support. */
    private static Optional<String> optional() {
        return Optional.empty();
    }
}
