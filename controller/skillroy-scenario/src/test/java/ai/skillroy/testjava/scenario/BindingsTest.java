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

package ai.skillroy.testjava.scenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reference substitution, fixture binding and the bind-time reference check. */
class BindingsTest {

    private final Bindings bindings = Bindings.of(Map.of(
            "item", Map.of("code", "YRD-001", "deposit", "3.50", "qty", 2L, "tags", List.of("a", "b")),
            "n", 5L,
            "flag", true));

    @Test
    void wholeValueReferencesKeepTheirType() throws Exception {
        assertEquals(Map.of("code", "YRD-001", "deposit", "3.50", "qty", 2L, "tags", List.of("a", "b")),
                bindings.substitute("$item"));
        assertEquals(2L, bindings.substitute("$item.qty"));
        assertEquals(true, bindings.substitute("$flag"));
        assertEquals("b", bindings.substitute("$item.tags.last"));
        assertEquals("a", bindings.substitute("$item.tags.0"));
        assertEquals(List.of("a", "b"), bindings.substitute("$item.tags"));
    }

    @Test
    void embeddedReferencesAreStringified() throws Exception {
        assertEquals("code YRD-001 x2", bindings.substitute("code $item.code x$item.qty"));
        assertEquals("[\"a\",\"b\"]!", bindings.substitute("$item.tags!"));
        assertEquals("n=5.", bindings.substitute("n=$n."), "a trailing dot is not a field");
        assertEquals("deposit: $item.deposit", bindings.substitute("deposit: $$item.deposit"));
        assertEquals("$item", bindings.substitute("$$item"));
        assertEquals("costs $5 or $ 6", bindings.substitute("costs $5 or $ 6"), "a $ without a slot name stays");
        assertEquals("$", bindings.substitute("$"));
    }

    @Test
    void substitutesInsideStructuresButNotKeys() throws Exception {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("$n", List.of("$n", Map.of("v", "$item.code")));
        template.put("plain", 1L);
        Object result = bindings.substitute(template);
        assertEquals(Map.of("$n", List.of(5L, Map.of("v", "YRD-001")), "plain", 1L), result);
        assertEquals("$n", template.get("$n") instanceof List<?> l ? l.get(0) : null, "the template is unchanged");
    }

    @Test
    void unknownSlotsAndFieldsAreNamed() {
        ResolutionException slot = assertThrows(ResolutionException.class,
                () -> bindings.substitute("$assistant.code"));
        assertEquals("$assistant.code", slot.reference());
        assertTrue(slot.getMessage().contains("unknown slot 'assistant'"), slot.getMessage());
        ResolutionException field = assertThrows(ResolutionException.class,
                () -> bindings.substitute("x $item.colour y"));
        assertEquals("$item.colour", field.reference());
        assertTrue(field.getMessage().contains("no field 'colour'"), field.getMessage());
        assertThrows(ResolutionException.class, () -> bindings.substitute("$item.tags.5"));
        assertThrows(ResolutionException.class, () -> bindings.substitute("$n.x"));
        assertEquals("YRD-001", assertDoesNotThrow(() -> bindings.lookup("item.code")));
        assertThrows(ResolutionException.class, () -> bindings.lookup("not a ref"));
    }

    private static Object assertDoesNotThrow(Callable call) {
        try {
            return call.call();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private interface Callable {
        Object call() throws Exception;
    }

    private static Plan plan(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", new ToyPack());
    }

    @Test
    void fixturesMayReferenceEachOther() throws Exception {
        Plan plan = plan("""
                scenario: t
                resolve:
                  label: { fixture: "item $item.code at $item.deposit" }
                  item: { fixture: { code: YRD-001, deposit: "3.50" } }
                  copy: { fixture: $item }
                steps: []
                """);
        Bindings bound = Binder.bind(plan);
        assertEquals(List.of("label", "item", "copy"), List.copyOf(bound.values().keySet()));
        assertEquals("item YRD-001 at 3.50", bound.values().get("label"));
        assertEquals(bound.values().get("item"), bound.values().get("copy"));
    }

    @Test
    void cyclesAndUnknownSlotsFailBinding() throws Exception {
        // Phase 2 (3.2): a cycle is a compile error that names it; the binder still refuses one in a hand-made plan.
        ScenarioException compiled = assertThrows(ScenarioException.class, () -> plan(
                "scenario: t\nresolve:\n  a: {fixture: $b}\n  b: {fixture: $a}\nsteps: []\n"));
        assertTrue(compiled.getMessage().contains("cycle: a -> b -> a"), compiled.getMessage());
        assertEquals(3, compiled.line());
        Plan handMade = new Plan(1, "t", "toy", "1", "sha256:x", List.of(), List.of(new Plan.Slot("a", "fixture", "$b"),
                new Plan.Slot("b", "fixture", "$a")), List.of(), List.of(), List.of(), Map.of(), List.of(), Map.of(),
                1000);
        ResolutionException cycle = assertThrows(ResolutionException.class, () -> Binder.bind(handMade));
        assertTrue(cycle.getMessage().contains("cycle"), cycle.getMessage());
        ResolutionException unknown = assertThrows(ResolutionException.class, () -> Binder.bind(plan(
                "scenario: t\nresolve:\n  a: {fixture: $zz.q}\nsteps: []\n")));
        assertTrue(unknown.getMessage().contains("unknown slot 'zz'"), unknown.getMessage());
    }

    @Test
    void checkFindsEveryBadReference() throws Exception {
        String base = "scenario: t\nresolve:\n  item: {fixture: {code: A}}\nsteps:\n";
        Plan good = plan(base + "  - enter: { value: $item.code }\n");
        Binder.check(good, Binder.bind(good));
        for (String step : new String[] {
                "  - enter: { value: $item.nope }\n",
                "  - expect: { \"loan.lines[code=$other.code].qty\": 1 }\n",
                "  - expect: { \"loan.total\": $item.deposit }\n",
                "  - lock: { name: $missing }\n"}) {
            Plan plan = plan(base + step);
            ResolutionException e = assertThrows(ResolutionException.class,
                    () -> Binder.check(plan, Binder.bind(plan)), step);
            assertTrue(e.getMessage().startsWith("step s1") || e.getMessage().startsWith("cleanup"), e.getMessage());
        }
        Plan plan = plan(base + "  - nothing: {}\n");
        ResolutionException notBound = assertThrows(ResolutionException.class,
                () -> Binder.check(plan, Bindings.empty()));
        assertTrue(notBound.getMessage().contains("not bound"));
    }

    @Test
    void writesBindingsJson(@TempDir Path dir) throws Exception {
        Plan plan = plan("scenario: t\nresolve:\n  item: {fixture: {code: A}}\nsteps: []\n");
        Bindings bound = Binder.bind(plan);
        Path file = dir.resolve("out/bindings.json");
        bound.write(file, plan);
        String text = Files.readString(file);
        assertTrue(text.contains("\"planHash\": \"" + plan.hash() + "\""), text);
        assertTrue(text.contains("\"strategy\": \"FIXTURE\""), text);
        assertTrue(text.contains("\"code\": \"A\""), text);
        assertEquals(bound, Bindings.of(Map.of("item", Map.of("code", "A"))));
    }
}
