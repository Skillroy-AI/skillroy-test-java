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

package ai.skillroy.testjava.scenario.hooks;

import ai.skillroy.testjava.protocol.hook.HookCapture;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.protocol.hook.HookTarget;
import ai.skillroy.testjava.scenario.ScenarioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hook catalogue's reader, hash, builder and overlay (phase6-contracts.md 5.1, 11.2, 13.1 item 5): a catalogue of
 * the sample's shape read and its defaults, a corpus of malformed ones each refused at its line, the warnings, the
 * hash's sensitivity, {@code builder(base).replace/add/disable} and an overlay resource.
 */
class HookCataloguesTest {

    /** The sample pack's catalogue, in the form of phase6-contracts.md 6.3 (the class names of a generic desk). */
    static final String SAMPLE = """
            catalogue: desk
            version: 1
            application: a desk 0.1.0
            hooks:
              - id: limit.applied
                mode: CAPTURE
                target:
                  class: org.example.desk.AccessControl
                  method: returnLimit
                  descriptors: ["(Ljava/lang/String;)Ljava/math/BigDecimal;"]
                capture: { args: [0], result: true }
                fallbacks:
                  - seam: desk.limits
                note: the limit the desk applies
              - id: access.checked
                mode: OBSERVE
                target:
                  class: org.example.desk.AccessControl
                  method: check
                  descriptors: ["(Ljava/lang/String;)Z"]
                fallbacks:
                  - seam: desk.access.check
              - id: sign.on.checked
                mode: CAPTURE
                enabled: false
                target:
                  class: org.example.desk.AccessControl
                  method: passwordMatches
                  descriptors: ["(Ljava/lang/String;Ljava/lang/String;)Z"]
                capture: { args: [0], result: true }
                secretArgs: [1]
                fallbacks:
                  - seam: desk.access.check
              - id: line.amount
                mode: STUB
                target:
                  class: org.example.desk.Money
                  method: round
                  descriptors: ["(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;"]
                emits: hook.line.amount
                fallbacks:
                  - seam: desk.deposit
              - id: vendor.call
                mode: OBSERVE
                target:
                  class: com.vendor.Lib
                  method: call
                  descriptors: ["()V"]
                fallbacks:
                  - seam: desk.state
            packages: [org.example.desk]
            """;

    static HookCatalogue read(String yaml) throws Exception {
        return HookCatalogues.read(new StringReader(yaml), "test.yaml");
    }

    @Test
    void theSampleShapeIsReadWithItsDefaults() throws Exception {
        HookCatalogue catalogue = read(SAMPLE);
        assertEquals("desk", catalogue.id());
        assertEquals(1, catalogue.version());
        assertEquals("a desk 0.1.0", catalogue.application());
        assertEquals(List.of("org.example.desk"), catalogue.packages());
        assertEquals(List.of("limit.applied", "access.checked", "sign.on.checked", "line.amount", "vendor.call"),
                catalogue.hooks().stream().map(HookEntry::id).toList());
        assertEquals(List.of("limit.applied", "access.checked", "line.amount"),
                catalogue.enabled().stream().map(HookEntry::id).toList(),
                "enabled by default inside packages, off outside them and where written");
        HookEntry limit = catalogue.entry("limit.applied");
        assertEquals(HookMode.CAPTURE, limit.mode());
        assertEquals(new HookCapture(List.of(0), false, true), limit.capture());
        assertEquals(List.of(new HookEntry.Fallback.Seam("desk.limits")), limit.fallbacks());
        assertEquals("hook.limit.applied", limit.capabilityId());
        assertEquals(List.of(1), catalogue.entry("sign.on.checked").secretArgs());
        List<HookSpec> specs = catalogue.toSpecs();
        assertEquals(3, specs.size());
        assertEquals(new HookTarget("org.example.desk.AccessControl", "returnLimit",
                List.of("(Ljava/lang/String;)Ljava/math/BigDecimal;"), null), specs.get(0).target());
        assertEquals("hook.line.amount", specs.get(2).emits());
        assertNull(specs.get(0).emits(), "no emits written: the default topic");
        assertEquals(Map.of("id", "desk", "version", 1L, "hash", catalogue.hash(), "application", "a desk 0.1.0"),
                catalogue.descriptor());
        // The spec the agent receives reads back from its JSON (the protocol's own codec).
        assertEquals(specs.get(0), HookSpec.fromJson(specs.get(0).toJson()));
        // The secret-name warning: passwordMatches captures its result without secretResult.
        assertEquals(1, catalogue.warnings().size(), String.valueOf(catalogue.warnings()));
        assertTrue(catalogue.warnings().get(0).contains("sign.on.checked"), catalogue.warnings().get(0));
    }

    @Test
    void enabledOutsideThePackagesIsAcceptedWithAWarning() throws Exception {
        HookCatalogue catalogue = read(SAMPLE.replace("      method: call\n", "      method: call\n"
                + "      loader: system\n").replace("      descriptors: [\"()V\"]\n", "      descriptors: [\"()V\"]\n"
                + "    enabled: true\n"));
        assertTrue(catalogue.entry("vendor.call").enabled());
        assertEquals("system", catalogue.entry("vendor.call").target().loader());
        assertTrue(catalogue.warnings().stream().anyMatch(w -> w.contains("vendor.call")
                && w.contains("outside the catalogue's packages")), String.valueOf(catalogue.warnings()));
    }

    @Test
    void aSecretLookingCaptureWithoutSecretArgsIsWarned() throws Exception {
        HookCatalogue catalogue = read(SAMPLE.replace("    secretArgs: [1]\n", ""));
        assertTrue(catalogue.warnings().stream().anyMatch(w -> w.contains("sign.on.checked")
                && w.contains("no secretArgs")), String.valueOf(catalogue.warnings()));
        HookCatalogue marked = read(SAMPLE.replace("    secretArgs: [1]\n", "    secretArgs: [1]\n"
                + "    secretResult: true\n"));
        assertEquals(List.of(), marked.warnings());
    }

    /** One entry on lines 4 to 13 with one line replaced: the line numbers of the corpus below refer to it. */
    private static String entry(int replaceLine, String replacement) {
        String[] lines = {
            "catalogue: desk",                                                  // 1
            "version: 1",                                                       // 2
            "hooks:",                                                           // 3
            "  - id: limit.applied",                                            // 4
            "    mode: CAPTURE",                                                // 5
            "    target:",                                                      // 6
            "      class: org.example.desk.AccessControl",                      // 7
            "      method: returnLimit",                                        // 8
            "      descriptors: [\"(Ljava/lang/String;)Ljava/math/BigDecimal;\"]", // 9
            "    capture: { args: [0], result: true }",                         // 10
            "    fallbacks:",                                                   // 11
            "      - seam: desk.limits",                                        // 12
            "    note: the limit",                                              // 13
        };
        if (replaceLine > 0) {
            lines[replaceLine - 1] = replacement;
        }
        return String.join("\n", lines) + "\n";
    }

    /** Every rule of 5.1, each refused at the line of the offending key or entry. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            unknown top key        | 2  | versions: 1                                       | 2  | unknown key 'versions'
            bad catalogue id       | 1  | catalogue: Desk_1                                 | 1  | catalogue id must match
            zero version           | 2  | version: 0                                        | 2  | positive integer
            text version           | 2  | version: one                                      | 2  | positive integer
            unknown entry key      | 13 | '    colour: red'                                 | 13 | unknown key 'colour'
            bad mode               | 5  | '    mode: WATCH'                                 | 5  | mode must be OBSERVE
            capture on OBSERVE     | 5  | '    mode: OBSERVE'                               | 10 | only allowed on a CAPTURE
            negative capture index | 10 | '    capture: { args: [-1] }'                     | 10 | non-negative
            negative secretArgs    | 13 | '    secretArgs: [-2]'                            | 13 | non-negative
            reflect fallback       | 12 | '      - reflect: { class: a.B, field: f }'        | 12 | not in this version
            seam not a primitive   | 12 | '      - seam: limits'                            | 12 | names a primitive id
            reserved id            | 4  | '  - id: engine'                                  | 4  | reserved
            bad id                 | 4  | '  - id: Limit'                                   | 4  | does not match
            bad descriptor         | 9  | '      descriptors: ["(Ljava/lang/String)V"]'     | 9  | not a JVM method descriptor
            static initialiser     | 8  | '      method: "<clinit>"'                        | 8  | a static initialiser is not hookable
            topic without hook.    | 13 | '    emits: limits.applied'                       | 13 | must start with 'hook.'
            empty descriptors      | 9  | '      descriptors: []'                           | 9  | non-empty list
            long note              | 13 | '    note: NOTE201'                               | 13 | note longer than 200
            capture self not bool  | 10 | '    capture: { self: yes }'                      | 10 | self must be true or false
            """)
    void malformedCataloguesAreRefusedWithTheirLine(String name, int replaced, String replacement, int line,
                                                   String reason) {
        String text = entry(replaced, replacement.replace("NOTE201", "x".repeat(201)));
        ScenarioException e = assertThrows(ScenarioException.class, () -> read(text), name + ":\n" + text);
        assertEquals("test.yaml", e.scenarioId(), e.getMessage());
        assertTrue(e.getMessage().contains(reason), name + ": " + e.getMessage());
        assertEquals(line, e.line(), name + ": " + e.getMessage() + "\n" + text);
    }

    /**
     * Section 3.3 item 1 (the Phase 6 review's L1-F3): a constructor entry is read; the agent answers it with the state
     * {@code UNAVAILABLE("constructors are not hookable")} and the entry's fallback applies, as for any refused hook.
     */
    @Test
    void aConstructorEntryIsReadAndLeftToTheAgentsState() throws Exception {
        HookCatalogue catalogue = read(entry(8, "      method: \"<init>\"")
                .replace("(Ljava/lang/String;)Ljava/math/BigDecimal;", "(Ljava/lang/String;)V")
                .replace("result: true", "result: false"));
        assertEquals("<init>", catalogue.hooks().get(0).toSpec().target().method());
        assertNull(catalogue.hooks().get(0).toSpec().violation());
    }

    @Test
    void theCorpusBaseIsValidAndAFallbackListMustNotBeEmpty() throws Exception {
        assertEquals(List.of("limit.applied"), read(entry(0, null)).hooks().stream().map(HookEntry::id).toList());
        String noFallbacks = entry(12, "    note2: x").replace("    fallbacks:\n", "    fallbacks: []\n")
                .replace("    note2: x\n", "");
        ScenarioException e = assertThrows(ScenarioException.class, () -> read(noFallbacks));
        assertTrue(e.getMessage().contains("at least one fallback"), e.getMessage());
        assertEquals(11, e.line(), e.getMessage());
        String noTarget = entry(0, null).replaceAll("(?s)    target:.*?BigDecimal;\"\\]\n", "");
        ScenarioException target = assertThrows(ScenarioException.class, () -> read(noTarget));
        assertTrue(target.getMessage().contains("target must be a map"), target.getMessage());
        ScenarioException hooks = assertThrows(ScenarioException.class, () -> read("catalogue: desk\nversion: 1\n"));
        assertTrue(hooks.getMessage().contains("no 'hooks'"), hooks.getMessage());
    }

    @Test
    void duplicateIdsAndTooManyHooksAreRefused() throws Exception {
        String twice = SAMPLE.replace("id: access.checked", "id: limit.applied");
        ScenarioException duplicate = assertThrows(ScenarioException.class, () -> read(twice));
        assertTrue(duplicate.getMessage().contains("duplicate hook id limit.applied"), duplicate.getMessage());
        assertEquals(15, duplicate.line(), duplicate.getMessage());
        StringBuilder many = new StringBuilder("catalogue: desk\nversion: 1\nhooks:\n");
        for (int i = 0; i < 65; i++) {
            many.append("  - id: h").append(i).append("\n    mode: OBSERVE\n    target: { class: a.B, method: m,"
                    + " descriptors: [\"()V\"] }\n    fallbacks: [{ seam: desk.state }]\n");
        }
        ScenarioException tooMany = assertThrows(ScenarioException.class, () -> read(many.toString()));
        assertTrue(tooMany.getMessage().contains("at most 64"), tooMany.getMessage());
    }

    @Test
    void theHashFollowsEveryEntryTheVersionAndTheEnabledFlags() throws Exception {
        HookCatalogue base = read(SAMPLE);
        assertTrue(base.hash().matches("sha256:[0-9a-f]{64}"), base.hash());
        assertEquals(base.hash(), read(SAMPLE).hash(), "stable");
        // The order of the entries does not count (sorted by spec hash), their content does.
        HookCatalogue.Builder reversed = HookCatalogue.builder("desk", 1).application("other text")
                .packages(base.packages());
        for (int i = base.hooks().size() - 1; i >= 0; i--) {
            reversed.add(base.hooks().get(i));
        }
        assertEquals(base.hash(), reversed.build().hash(), "order and application text do not count");
        assertNotEquals(base.hash(), read(SAMPLE.replace("id: vendor.call", "id: vendor.other")).hash(), "an id");
        assertNotEquals(base.hash(), read(SAMPLE.replace("version: 1", "version: 2")).hash(), "the version");
        assertNotEquals(base.hash(), HookCatalogue.builder(base).enable("sign.on.checked").build().hash(),
                "an enabled flag");
        assertNotEquals(base.hash(), read(SAMPLE.replace("note: the limit the desk applies",
                "note: the limit applied")).hash(), "any field of an entry");
        assertEquals(base.hash(), HookCatalogue.builder(base).disable("vendor.call").build().hash(),
                "vendor.call was off already");
        assertNotEquals(base.hash(), HookCatalogue.builder(base).id("other").build().hash(), "the id");
    }

    @Test
    void theBuilderReplacesAddsDisablesAndRefusesWhatIsNotThere() throws Exception {
        HookCatalogue base = read(SAMPLE);
        HookEntry limit = base.entry("limit.applied");
        HookEntry moved = new HookEntry(limit.id(), limit.mode(), true, new HookTarget("org.example.desk.Ceilings",
                "applicable", limit.target().descriptors(), null), limit.capture(), limit.secretArgs(),
                limit.secretResult(), limit.emits(), limit.fallbacks(), limit.note());
        HookCatalogue changed = HookCatalogue.builder(base).replace(moved).disable("line.amount")
                .add(new HookEntry("extra.one", HookMode.OBSERVE, true, new HookTarget("org.example.desk.X", "m",
                        List.of("()V"), "system"), null, List.of(), false, null,
                        List.of(new HookEntry.Fallback.Seam("desk.state")), null)).build();
        assertEquals("org.example.desk.Ceilings", changed.entry("limit.applied").target().className());
        assertEquals(0, changed.hooks().indexOf(changed.entry("limit.applied")), "replaced in its place");
        assertFalse(changed.entry("line.amount").enabled());
        assertEquals("extra.one", changed.hooks().get(changed.hooks().size() - 1).id());
        assertEquals("system", changed.entry("extra.one").target().loader());
        HookEntry absent = new HookEntry("absent", HookMode.OBSERVE, true, moved.target(), null, null, false, null,
                moved.fallbacks(), null);
        assertThrows(IllegalStateException.class, () -> HookCatalogue.builder(base).replace(absent));
        assertThrows(IllegalStateException.class, () -> HookCatalogue.builder(base).add(limit));
        assertThrows(IllegalStateException.class, () -> HookCatalogue.builder(base).disable("absent"));
        assertThrows(IllegalStateException.class, () -> HookCatalogue.builder("desk", 0).build());
        assertSame(HookCatalogue.EMPTY, HookCatalogue.builder(HookCatalogue.EMPTY).build());
        assertTrue(HookCatalogue.EMPTY.isEmpty() && HookCatalogue.EMPTY.toSpecs().isEmpty());
    }

    /** phase6-contracts.md 11.2: an overlay's entries replace by id or are added; disable turns base entries off. */
    @Test
    void anOverlayReplacesAddsAndDisables() throws Exception {
        HookCatalogue base = read(SAMPLE);
        HookCatalogue overlaid = HookCatalogues.overlay(base, new StringReader("""
                catalogue: desk-branch
                version: 3
                hooks:
                  - id: limit.applied
                    mode: CAPTURE
                    target:
                      class: org.example.desk.Ceilings
                      method: applicable
                      descriptors: ["(Ljava/lang/String;)Ljava/math/BigDecimal;"]
                    capture: { args: [0], result: true }
                    fallbacks:
                      - seam: desk.limits
                  - id: branch.only
                    mode: OBSERVE
                    target: { class: org.example.branch.ReturnDesk, method: open, descriptors: ["()V"] }
                    fallbacks: [{ seam: desk.state }]
                disable: [access.checked]
                packages: [org.example.branch]
                """), "desk-branch.yaml");
        assertEquals("desk-branch", overlaid.id());
        assertEquals(3, overlaid.version());
        assertEquals("a desk 0.1.0", overlaid.application(), "the base's application unless given");
        assertEquals(List.of("org.example.desk", "org.example.branch"), overlaid.packages());
        assertEquals("org.example.desk.Ceilings", overlaid.entry("limit.applied").target().className());
        assertTrue(overlaid.entry("branch.only").enabled(), "inside the overlay's packages");
        assertFalse(overlaid.entry("access.checked").enabled());
        assertEquals(List.of("limit.applied", "line.amount", "branch.only"),
                overlaid.enabled().stream().map(HookEntry::id).toList());
        assertNotEquals(base.hash(), overlaid.hash());
        ScenarioException unknown = assertThrows(ScenarioException.class, () -> HookCatalogues.overlay(base,
                new StringReader("catalogue: x\nversion: 1\nhooks: []\ndisable: [nope]\n"), "x.yaml"));
        assertTrue(unknown.getMessage().contains("disable names hook nope"), unknown.getMessage());
        assertEquals(4, unknown.line());
    }

    /** {@code ofPack}: the resource of the pack's loader, read once; EMPTY without one; a malformed one refused. */
    @Test
    void ofPackReadsTheResourceOnceAndAnswersEmptyWithoutOne() {
        HookCatalogue catalogue = HookCatalogues.ofPack(new ResourcePacks.Hooky());
        assertEquals("hooky", catalogue.id());
        assertEquals(List.of("toy.applied"), catalogue.enabled().stream().map(HookEntry::id).toList());
        assertSame(catalogue, HookCatalogues.ofPack(new ResourcePacks.Hooky()), "read once per pack class");
        assertSame(HookCatalogue.EMPTY, HookCatalogues.ofPack(new ResourcePacks.Plain()));
        IllegalStateException broken = assertThrows(IllegalStateException.class,
                () -> HookCatalogues.ofPack(new ResourcePacks.Broken()));
        assertTrue(broken.getMessage().contains("META-INF/skillroy/hooks/broken.yaml"), broken.getMessage());
        assertTrue(broken.getMessage().contains("line 2"), broken.getMessage());
        assertThrows(IllegalStateException.class, () -> new ResourcePacks.Broken().hooks(), "the default method");
    }

    @Test
    void hooksModeParsesItsThreeValues() {
        assertEquals(HooksMode.AUTO, HooksMode.parse(null));
        assertEquals(HooksMode.AUTO, HooksMode.parse(" "));
        assertEquals(HooksMode.ON, HooksMode.parse("On"));
        assertEquals(HooksMode.OFF, HooksMode.parse(" off "));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> HooksMode.parse("yes"));
        assertTrue(e.getMessage().contains("auto, on or off"), e.getMessage());
        assertEquals("auto", HooksMode.AUTO.label());
    }
}
