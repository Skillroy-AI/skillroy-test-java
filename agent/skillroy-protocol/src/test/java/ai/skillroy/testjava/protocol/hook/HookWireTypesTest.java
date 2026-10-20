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

package ai.skillroy.testjava.protocol.hook;

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hook wire types of phase6-contracts.md section 4.1: JSON round trips, the refusals of {@code HookSpec.fromJson},
 * the name rules of section 3.3 item 1 and the hash's stability under key order (section 13.1 item 4).
 */
class HookWireTypesTest {

    private static final String LIMIT = "{\"id\":\"limit.applied\",\"mode\":\"CAPTURE\",\"target\":{"
            + "\"class\":\"ai.skillroy.testjava.samples.lending.AccessControl\",\"method\":\"returnLimit\","
            + "\"descriptors\":[\"(Ljava/lang/String;)Ljava/math/BigDecimal;\"]},"
            + "\"capture\":{\"args\":[0],\"self\":false,\"result\":true},\"secretArgs\":[1],\"secretResult\":true,"
            + "\"note\":\"the limit the desk applies to a return line\"}";

    private static HookSpec limit() {
        return HookSpec.fromJson(Json.parseObject(LIMIT));
    }

    @Test
    void aSpecRoundTripsThroughItsJson() {
        HookSpec spec = limit();
        assertEquals("limit.applied", spec.id());
        assertEquals(HookMode.CAPTURE, spec.mode());
        assertEquals("ai.skillroy.testjava.samples.lending.AccessControl", spec.target().className());
        assertEquals("returnLimit", spec.target().method());
        assertEquals(Collections.singletonList("(Ljava/lang/String;)Ljava/math/BigDecimal;"),
                spec.target().descriptors());
        assertNull(spec.target().loader());
        assertEquals(Collections.singletonList(0), spec.capture().args());
        assertTrue(spec.capture().result());
        assertFalse(spec.capture().self());
        assertEquals(Collections.singletonList(1), spec.secretArgs());
        assertTrue(spec.secretResult(), "secretResult survives the round trip");
        assertEquals("hook.limit.applied", spec.topic(), "the default topic");
        assertEquals("hook.limit.applied", spec.capabilityId());
        assertNull(spec.violation());
        HookSpec back = HookSpec.fromJson(Json.parseObject(Json.write(spec.toJson())));
        assertEquals(spec, back);
        assertEquals(spec.hashCode(), back.hashCode());
        assertEquals(LIMIT, Json.write(spec.toJson()), "the written form is the read form");
    }

    @Test
    void theOptionalKeysAreWrittenOnlyWhenTheySaySomething() {
        HookSpec plain = new HookSpec("line.amount", HookMode.STUB, new HookTarget("a.B", "money",
                Collections.singletonList("(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;"), "system"), null, null,
                null, false, null);
        assertEquals("{\"id\":\"line.amount\",\"mode\":\"STUB\",\"target\":{\"class\":\"a.B\",\"method\":\"money\","
                + "\"descriptors\":[\"(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;\"],\"loader\":\"system\"}}",
                Json.write(plain.toJson()));
        assertTrue(plain.secretArgs().isEmpty());
        assertEquals(plain, HookSpec.fromJson(plain.toJson()));
        HookSpec custom = new HookSpec("x", HookMode.OBSERVE, plain.target(), "hook.custom.topic", null, null, false,
                null);
        assertEquals("hook.custom.topic", custom.topic());
    }

    @Test
    void theHashIsStableUnderKeyOrderAndChangesWithTheContent() {
        String reordered = "{\"note\":\"the limit the desk applies to a return line\",\"secretResult\":true,"
                + "\"secretArgs\":[1],\"capture\":{\"result\":true,\"self\":false,\"args\":[0]},\"target\":{"
                + "\"descriptors\":[\"(Ljava/lang/String;)Ljava/math/BigDecimal;\"],\"method\":\"returnLimit\","
                + "\"class\":\"ai.skillroy.testjava.samples.lending.AccessControl\"},\"mode\":\"CAPTURE\","
                + "\"id\":\"limit.applied\"}";
        String hash = limit().hash();
        assertTrue(hash.matches("sha256:[0-9a-f]{64}"), hash);
        assertEquals(hash, HookSpec.fromJson(Json.parseObject(reordered)).hash());
        assertEquals(HookSpec.sha256(Json.writeCanonical(Json.parseObject(LIMIT))), hash,
                "the hash is the SHA-256 of the canonical JSON");
        HookSpec observe = HookSpec.fromJson(Json.parseObject(LIMIT.replace("CAPTURE", "OBSERVE")
                .replace(",\"capture\":{\"args\":[0],\"self\":false,\"result\":true}", "")));
        assertNotEquals(hash, observe.hash(), "another mode, another hash");
    }

    @Test
    void fromJsonRefusesTheShapeErrorsTheContractNames() {
        refused(LIMIT.replace("\"note\"", "\"notes\""), "unknown field \"notes\"");
        refused(LIMIT.replace("\"id\":\"limit.applied\",", ""), "\"id\"");
        refused(LIMIT.replace("\"mode\":\"CAPTURE\",", ""), "\"mode\"");
        refused(LIMIT.replace("\"class\":\"ai.skillroy.testjava.samples.lending.AccessControl\",", ""), "\"class\"");
        refused(LIMIT.replace("\"method\":\"returnLimit\",", ""), "\"method\"");
        refused(LIMIT.replace(",\"descriptors\":[\"(Ljava/lang/String;)Ljava/math/BigDecimal;\"]", ""),
                "\"descriptors\"");
        refused(LIMIT.replace("[\"(Ljava/lang/String;)Ljava/math/BigDecimal;\"]", "[]"), "empty");
        refused(LIMIT.replace("\"secretArgs\":[1]", "\"secretArgs\":[-1]"), "negative index");
        refused(LIMIT.replace("\"args\":[0]", "\"args\":[0,-2]"), "negative index");
        refused(LIMIT.replace("\"mode\":\"CAPTURE\"", "\"mode\":\"TRACE\""), "unknown mode");
        refused(LIMIT.replace("\"self\":false", "\"self\":false,\"other\":1"), "unknown field \"other\"");
        refused(LIMIT.replace("\"method\":\"returnLimit\"", "\"method\":\"returnLimit\",\"x\":1"),
                "unknown field \"x\"");
        refused(LIMIT.replace("\"secretArgs\":[1]", "\"secretArgs\":[\"1\"]"), "array of integers");
        refused(LIMIT.replace("\"secretArgs\":[1]", "\"secretArgs\":[1.5]"), "array of integers");
        refused("{\"id\":\"a\",\"mode\":\"OBSERVE\"}", "\"target\"");
        assertThrows(JsonException.class, () -> HookSpec.fromJson(null));
    }

    private static void refused(String json, String fragment) {
        JsonException e = assertThrows(JsonException.class, () -> HookSpec.fromJson(Json.parseObject(json)), json);
        assertTrue(e.getMessage().contains(fragment), e.getMessage() + " should mention " + fragment);
    }

    @Test
    void theNameRulesAreReportedAsViolationsNotAsShapeErrors() {
        assertViolation("id", spec("Limit", "a.B", "m", "()V", null, null));
        assertViolation("id", spec("limit..applied", "a.B", "m", "()V", null, null));
        assertViolation("id", spec(repeat('a', 65), "a.B", "m", "()V", null, null));
        assertNull(spec(repeat('a', 64), "a.B", "m", "()V", null, null).violation());
        for (String reserved : HookSpec.RESERVED_IDS) {
            assertViolation("id", spec(reserved, "a.B", "m", "()V", null, null));
        }
        assertNull(spec("engine.room", "a.B", "m", "()V", null, null).violation(), "only the exact ids are reserved");
        assertViolation("target.class", spec("a", "a/B", "m", "()V", null, null));
        assertViolation("target.class", spec("a", "a..B", "m", "()V", null, null));
        assertNull(spec("a", "a.B$Inner", "m", "()V", null, null).violation(), "a nested class's binary name");
        assertNull(spec("a", "a.B", "<init>", "()V", null, null).violation(),
                "a constructor is the agent's per-hook state UNAVAILABLE, not a name rule (section 3.3 item 1)");
        HookSpec.Violation clinit = spec("a", "a.B", "<clinit>", "()V", null, null).violation();
        assertEquals("target.method", clinit.field());
        assertEquals("a static initialiser is not hookable", clinit.message());
        assertViolation("target.method", spec("a", "a.B", "<other>", "()V", null, null));
        assertViolation("target.method", spec("a", "a.B", "1m", "()V", null, null));
        assertViolation("target.descriptors[0]", spec("a", "a.B", "m", "(Ljava.lang.String;)V", null, null));
        assertViolation("target.descriptors[0]", spec("a", "a.B", "m", "(I)", null, null));
        assertViolation("target.descriptors[0]", spec("a", "a.B", "m", "(Q)V", null, null));
        assertNull(spec("a", "a.B", "m", "([[ILjava/util/List;JD)[Ljava/lang/String;", null, null).violation());
        assertViolation("emits", spec("a", "a.B", "m", "()V", "custom.topic", null));
        assertViolation("emits", spec("a", "a.B", "m", "()V", "hook.", null));
        assertViolation("note", spec("a", "a.B", "m", "()V", null, repeat('n', 201)));
        assertNull(spec("a", "a.B", "m", "()V", null, repeat('n', 200)).violation());
    }

    private static HookSpec spec(String id, String className, String method, String descriptor, String emits,
                                 String note) {
        return new HookSpec(id, HookMode.OBSERVE, new HookTarget(className, method,
                Collections.singletonList(descriptor), null), emits, null, null, false, note);
    }

    private static void assertViolation(String field, HookSpec spec) {
        HookSpec.Violation v = spec.violation();
        assertTrue(v != null, "a violation of " + field + " in " + spec);
        assertEquals(field, v.field(), v.toString());
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void theConstructorsRefuseWhatFromJsonRefuses() {
        assertThrows(IllegalArgumentException.class, () -> new HookTarget("a.B", "m",
                Collections.<String>emptyList(), null));
        assertThrows(IllegalArgumentException.class, () -> new HookCapture(Arrays.asList(1, -1), false, false));
        assertThrows(IllegalArgumentException.class, () -> new HookSpec("a", HookMode.OBSERVE,
                new HookTarget("a.B", "m", Collections.singletonList("()V"), null), null, null,
                Collections.singletonList(-3), false, null));
        assertThrows(NullPointerException.class, () -> new HookSpec(null, HookMode.OBSERVE,
                new HookTarget("a.B", "m", Collections.singletonList("()V"), null), null, null, null, false, null));
    }

    @Test
    void aProbeAnswerRoundTrips() {
        HookProbe ok = new HookProbe("limit.applied", CapabilityState.OK, null, "advice", 2,
                Arrays.asList("system", "1a2b3c"), null, 9810L, 12, HookProbe.SpyVisibility.VISIBLE);
        assertEquals("{\"id\":\"limit.applied\",\"state\":\"OK\",\"strategy\":\"advice\",\"loaders\":2,"
                + "\"loaderIds\":[\"system\",\"1a2b3c\"],\"sizeBytes\":9810,\"methods\":12,"
                + "\"spyVisibility\":\"VISIBLE\"}", Json.write(ok.toJson()));
        assertEquals(ok, HookProbe.fromJson(Json.parseObject(Json.write(ok.toJson()))));
        HookProbe missing = new HookProbe("x", CapabilityState.UNAVAILABLE, "no method m(I)V", null, null, null,
                Arrays.asList("()V", "(J)V"), null, null, null);
        assertEquals(HookProbe.SpyVisibility.UNCHECKED, missing.spyVisibility(), "null means unchecked");
        HookProbe back = HookProbe.fromJson(Json.parseObject(Json.write(missing.toJson())));
        assertEquals(missing, back);
        assertEquals(Arrays.asList("()V", "(J)V"), back.declared());
        Map<String, Object> lenient = new LinkedHashMap<String, Object>(missing.toJson());
        lenient.put("later", "a field of a newer agent");
        assertEquals(missing, HookProbe.fromJson(lenient), "answers are read leniently");
        assertThrows(JsonException.class, () -> HookProbe.fromJson(Json.parseObject(
                "{\"id\":\"x\",\"state\":\"FINE\"}")));
    }

    // ---- Phase 8 (phase8-contracts.md section 6.3, section 13.1 row 22) ----

    /** The hash of {@link #LIMIT} as v0.1.0 computed it: a spec without the new key keeps it. */
    private static final String LIMIT_HASH = "sha256:4448f89527d1dd627ab97319465d20c4375b89a2f307b52f7f5dc02953d7e5c1";

    @Test
    void aCodeSourceQualifierRoundTripsAndAnAbsentOneIsNotWritten() {
        HookSpec plain = limit();
        assertNull(plain.target().codeSource());
        assertFalse(plain.target().toJson().containsKey("codeSource"), "absent: not written");
        assertEquals(LIMIT_HASH, plain.hash(), "a spec without a qualifier keeps its v0.1.0 hash");
        String qualified = LIMIT.replace("\"descriptors\":[\"(Ljava/lang/String;)Ljava/math/BigDecimal;\"]}",
                "\"descriptors\":[\"(Ljava/lang/String;)Ljava/math/BigDecimal;\"],"
                        + "\"codeSource\":\"skillroy-sample-lending-app(-[^/]*)?\\\\.jar\"}");
        HookSpec spec = HookSpec.fromJson(Json.parseObject(qualified));
        assertEquals("skillroy-sample-lending-app(-[^/]*)?\\.jar", spec.target().codeSource());
        assertNull(spec.violation());
        assertEquals(qualified, Json.write(spec.toJson()), "the written form is the read form");
        assertEquals(spec, HookSpec.fromJson(Json.parseObject(Json.write(spec.toJson()))));
        assertNotEquals(plain, spec);
        assertNotEquals(plain.target().hashCode(), spec.target().hashCode());
        assertNotEquals(LIMIT_HASH, spec.hash(), "a qualifier changes the spec's hash");
        assertEquals(spec.target(), plain.target().withCodeSource(spec.target().codeSource()));
        assertEquals(plain.target(), spec.target().withCodeSource(null));
        HookTarget five = new HookTarget("a.B", "m", Collections.singletonList("()V"), "system", "app\\.jar");
        assertEquals("{\"class\":\"a.B\",\"method\":\"m\",\"descriptors\":[\"()V\"],\"loader\":\"system\","
                + "\"codeSource\":\"app\\\\.jar\"}", Json.write(five.toJson()));
        assertEquals(five, HookTarget.fromJson(five.toJson()));
        refused(LIMIT.replace("\"method\":\"returnLimit\"", "\"method\":\"returnLimit\",\"codeSources\":\"x\""),
                "unknown field \"codeSources\"");
        refused(LIMIT.replace("\"method\":\"returnLimit\"", "\"method\":\"returnLimit\",\"codeSource\":1"),
                "codeSource");
    }

    @Test
    void aBadCodeSourceIsAViolationOfTargetCodeSource() {
        HookSpec.Violation bad = qualified("lending-app(.jar").violation();
        assertEquals("target.codeSource", bad.field());
        assertTrue(bad.message().startsWith("not a regular expression: "), bad.message());
        HookSpec.Violation tooLong = qualified(repeat('a', 201)).violation();
        assertEquals("target.codeSource", tooLong.field());
        assertEquals("codeSource longer than 200 characters", tooLong.message());
        assertNull(qualified(repeat('a', 200)).violation());
        assertNull(HookTarget.codeSourceViolation(null));
        assertEquals("codeSource longer than 200 characters", HookTarget.codeSourceViolation(repeat('x', 201)));
    }

    private static HookSpec qualified(String codeSource) {
        return new HookSpec("a", HookMode.OBSERVE, new HookTarget("a.B", "m", Collections.singletonList("()V"), null,
                codeSource), null, null, null, false, null);
    }

    @Test
    void aProbeAnswerCarriesTheCodeSourcesAndIsReadLeniently() {
        HookProbe ok = new HookProbe("limit.applied", CapabilityState.OK, null, "advice", 2,
                Arrays.asList("system", "1a2b3c"), Arrays.asList("skillroy-sample-lending-app.jar", "test-classes"),
                null, 9810L, 12, HookProbe.SpyVisibility.VISIBLE);
        assertEquals("{\"id\":\"limit.applied\",\"state\":\"OK\",\"strategy\":\"advice\",\"loaders\":2,"
                + "\"loaderIds\":[\"system\",\"1a2b3c\"],\"codeSources\":[\"skillroy-sample-lending-app.jar\","
                + "\"test-classes\"],\"sizeBytes\":9810,\"methods\":12,\"spyVisibility\":\"VISIBLE\"}",
                Json.write(ok.toJson()));
        HookProbe back = HookProbe.fromJson(Json.parseObject(Json.write(ok.toJson())));
        assertEquals(ok, back);
        assertEquals(Arrays.asList("skillroy-sample-lending-app.jar", "test-classes"), back.codeSources());
        HookProbe ten = new HookProbe("x", CapabilityState.UNAVAILABLE, "class not loaded", null, null, null, null,
                null, null, null);
        assertNull(ten.codeSources(), "the ten-argument form carries none");
        assertFalse(ten.toJson().containsKey("codeSources"), "written only when set");
        assertNull(HookProbe.fromJson(ten.toJson()).codeSources(), "an answer without the key reads as none");
        java.util.List<String> many = new java.util.ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            many.add("lib" + i + ".jar");
        }
        HookProbe capped = new HookProbe("x", CapabilityState.OK, null, "advice", 20, null, many, null, null, null,
                null);
        assertEquals(HookProbe.MAX_CODE_SOURCES, capped.codeSources().size(), "at most 16 names");
        assertEquals("lib15.jar", capped.codeSources().get(15));
    }

    @Test
    void aStatusRoundTripsWithItsCapabilityAlwaysPresent() {
        HookStatus installed = new HookStatus("limit.applied", CapabilityState.OK, null, "advice",
                "hook.limit.applied");
        assertEquals("{\"id\":\"limit.applied\",\"state\":\"OK\",\"strategy\":\"advice\","
                + "\"capability\":\"hook.limit.applied\"}", Json.write(installed.toJson()));
        assertEquals(installed, HookStatus.fromJson(Json.parseObject(Json.write(installed.toJson()))));
        HookStatus refused = new HookStatus("x", CapabilityState.UNAVAILABLE, "class not loaded", null, null);
        assertTrue(refused.toJson().containsKey("capability"));
        assertNull(refused.toJson().get("capability"));
        assertEquals(refused, HookStatus.fromJson(Json.parseObject(Json.write(refused.toJson()))));
    }

    @Test
    void aHealthRoundTripsAndNeverCarriesTheStubValue() {
        Map<String, Object> last = new LinkedHashMap<String, Object>();
        last.put("hook", "limit.applied");
        last.put("durationNs", 1200L);
        HookHealth firing = new HookHealth(HookState.FIRING, 3, 1760000000000L, false, null, "advice", last);
        HookHealth back = HookHealth.fromJson(Json.parseObject(Json.write(firing.toJson())));
        assertEquals(firing, back);
        assertEquals(HookState.FIRING, back.state());
        assertEquals(3, back.count());
        assertEquals(Long.valueOf(1760000000000L), back.lastFiredMs());
        HookHealth installed = new HookHealth(HookState.INSTALLED, 0, null, true, null, "advice", null);
        assertEquals("{\"state\":\"INSTALLED\",\"count\":0,\"armed\":true,\"strategy\":\"advice\"}",
                Json.write(installed.toJson()));
        HookHealth readBack = HookHealth.fromJson(Json.parseObject(Json.write(installed.toJson())));
        assertTrue(readBack.armed(), "armed survives the round trip");
        assertEquals(HookState.INSTALLED, readBack.state(), "INSTALLED survives the round trip");
        assertFalse(installed.toJson().containsKey("value"));
        assertThrows(UnsupportedOperationException.class, () -> firing.last().put("x", 1));
        assertThrows(JsonException.class, () -> HookHealth.fromJson(Json.parseObject("{\"count\":1}")));
    }
}
