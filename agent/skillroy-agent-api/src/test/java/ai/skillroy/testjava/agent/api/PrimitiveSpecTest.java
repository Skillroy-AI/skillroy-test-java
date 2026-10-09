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

package ai.skillroy.testjava.agent.api;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveSpecTest {

    @ParameterizedTest
    @ValueSource(strings = {"swing.click", "a.b", "lending.prompt.answer", "jvm.info", "a1.b2c3", "swing.edtState",
        "agent.capabilities", "x.yZ.w9"})
    void acceptsDottedLowerCamelNames(String name) {
        assertEquals(name, PrimitiveSpec.builder(name).build().name());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "swing", ".a", "a.", "a..b", "Swing.click", "swing.Click", "1a.b", "a.1b", "a-b.c",
        "a.b c", "a_b.c", " a.b", "a.b.", "a.b\n", "é.b"})
    void rejectsOtherNames(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PrimitiveSpec.builder(name));
        assertTrue(e.getMessage().contains("invalid primitive name"), e.getMessage());
    }

    @Test
    void defaults() {
        PrimitiveSpec spec = PrimitiveSpec.builder("jvm.info").build();
        assertEquals(ThreadPolicy.CALLER, spec.thread());
        assertEquals(10000L, spec.timeoutMs());
        assertEquals("", spec.description());
        assertNull(spec.capabilityId());
        assertFalse(spec.mutating());
        assertFalse(spec.destructive());
        assertNull(spec.resultSchema());
        assertEquals("{\"type\":\"object\",\"properties\":{},\"required\":[]}", Json.write(spec.paramsSchema()));
    }

    @Test
    void describeBuildsAJsonSchemaFromParams() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", "object");
        PrimitiveSpec spec = PrimitiveSpec.builder("sync.await")
                .description("Waits for an event")
                .param("topics", "string|array", true, "comma-separated globs or a list of globs")
                .param("since", "integer", true, null)
                .param("match", "object", false, "payload entries to match")
                .param("timeoutMs", "integer", false, null)
                .resultSchema(result)
                .thread(ThreadPolicy.WORKER)
                .timeoutMs(11000)
                .capability("core.events")
                .build();
        PrimitiveDescriptor descriptor = spec.describe();
        assertEquals("{\"name\":\"sync.await\",\"description\":\"Waits for an event\",\"paramsSchema\":{"
                + "\"type\":\"object\",\"properties\":{"
                + "\"topics\":{\"type\":[\"string\",\"array\"],"
                + "\"description\":\"comma-separated globs or a list of globs\"},"
                + "\"since\":{\"type\":\"integer\"},"
                + "\"match\":{\"type\":\"object\",\"description\":\"payload entries to match\"},"
                + "\"timeoutMs\":{\"type\":\"integer\"}},"
                + "\"required\":[\"topics\",\"since\"]},"
                + "\"resultSchema\":{\"type\":\"object\"},\"thread\":\"WORKER\",\"timeoutMs\":11000,"
                + "\"capabilityId\":\"core.events\",\"mutating\":false,\"destructive\":false}",
                Json.write(descriptor.toJson()));
        assertEquals(descriptor, PrimitiveDescriptor.fromJson(Json.parseObject(Json.write(descriptor.toJson()))));
        assertEquals("core.events", spec.capabilityId());
        assertEquals(ThreadPolicy.WORKER, spec.thread());
        assertEquals(11000L, spec.timeoutMs());
    }

    @Test
    void destructiveImpliesMutating() {
        PrimitiveSpec destructive = PrimitiveSpec.builder("agent.disable").destructive().build();
        assertTrue(destructive.destructive());
        assertTrue(destructive.mutating());
        assertTrue(destructive.describe().mutating());
        PrimitiveSpec mutating = PrimitiveSpec.builder("swing.click").mutating().build();
        assertTrue(mutating.mutating());
        assertFalse(mutating.destructive());
    }

    /** phase6-contracts.md 12.9, D156 (security fix S42): {@code script()} implies nothing else; off by default. */
    @Test
    void scriptIsAFlagOfItsOwn() {
        PrimitiveSpec script = PrimitiveSpec.builder("web.waitFlag").script().build();
        assertTrue(script.script());
        assertTrue(script.describe().script());
        assertFalse(script.mutating(), "a script read stays a read for the step protocol");
        assertFalse(script.destructive());
        assertFalse(PrimitiveSpec.builder("web.dom.text").build().script());
        assertFalse(PrimitiveSpec.builder("swing.click").mutating().build().script());
        PrimitiveSpec both = PrimitiveSpec.builder("web.evaluate").mutating().script().build();
        assertTrue(both.mutating() && both.script());
    }

    @Test
    void customParamsSchemaIsUsedAsGivenAndCopied() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("additionalProperties", Boolean.FALSE);
        PrimitiveSpec spec = PrimitiveSpec.builder("a.b").paramsSchema(schema).build();
        schema.put("changed", Boolean.TRUE);
        assertEquals("{\"type\":\"object\",\"additionalProperties\":false}", Json.write(spec.paramsSchema()));
        assertThrows(UnsupportedOperationException.class, () -> spec.paramsSchema().put("x", 1));
    }

    @Test
    void paramAndParamsSchemaAreExclusive() {
        assertThrows(IllegalStateException.class, () -> PrimitiveSpec.builder("a.b")
                .paramsSchema(new LinkedHashMap<String, Object>()).param("x", "string", true, null));
        assertThrows(IllegalStateException.class, () -> PrimitiveSpec.builder("a.b")
                .param("x", "string", true, null).paramsSchema(new LinkedHashMap<String, Object>()));
    }

    @Test
    void rejectsBadParams() {
        PrimitiveSpec.Builder builder = PrimitiveSpec.builder("a.b").param("x", "string", true, null);
        assertThrows(IllegalArgumentException.class, () -> builder.param("x", "string", false, null));
        assertThrows(IllegalArgumentException.class, () -> builder.param("_meta", "object", false, null));
        assertThrows(IllegalArgumentException.class, () -> builder.param(" ", "string", false, null));
        assertThrows(IllegalArgumentException.class, () -> builder.param(null, "string", false, null));
        assertThrows(IllegalArgumentException.class, () -> builder.param("y", "str", false, null));
        assertThrows(IllegalArgumentException.class, () -> builder.param("y", "string|", false, null));
        assertThrows(IllegalArgumentException.class, () -> builder.param("y", null, false, null));
        assertThrows(IllegalArgumentException.class, () -> builder.timeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> builder.timeoutMs(-1));
        assertThrows(NullPointerException.class, () -> builder.thread(null));
        assertThrows(NullPointerException.class, () -> PrimitiveSpec.builder("a.b").paramsSchema(null));
    }

    @Test
    void specsAreImmutableValues() {
        PrimitiveSpec.Builder builder = PrimitiveSpec.builder("a.b").param("x", "string", true, null);
        PrimitiveSpec first = builder.build();
        builder.param("y", "integer", false, null).mutating();
        PrimitiveSpec second = builder.build();
        assertEquals("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}",
                Json.write(first.paramsSchema()));
        assertFalse(first.mutating());
        assertNotEquals(first, second);
        assertEquals(second, builder.build());
        assertEquals(second.hashCode(), builder.build().hashCode());
        assertTrue(second.toString().contains("a.b"));
    }

    @Test
    void primitivesOfDelegatesToTheBody() throws Exception {
        PrimitiveSpec spec = PrimitiveSpec.builder("test.echo").build();
        Primitive primitive = Primitives.of(spec, inv -> inv.correlationId());
        assertEquals(spec, primitive.spec());
        assertEquals("c-9", primitive.invoke(new FakeInvocation("c-9")));
        assertTrue(primitive.toString().contains("test.echo"));
        assertThrows(NullPointerException.class, () -> Primitives.of(null, inv -> null));
        assertThrows(NullPointerException.class, () -> Primitives.of(spec, null));
    }

    /** Minimal Invocation for exercising primitives without an agent. */
    private static final class FakeInvocation implements Invocation {
        private final String correlationId;

        FakeInvocation(String correlationId) {
            this.correlationId = correlationId;
        }

        @Override
        public ai.skillroy.testjava.protocol.JsonView args() {
            return ai.skillroy.testjava.protocol.JsonView.of(null);
        }

        @Override
        public String correlationId() {
            return correlationId;
        }

        @Override
        public String traceparent() {
            return null;
        }

        @Override
        public AgentContext context() {
            return null;
        }

        @Override
        public long remainingMs() {
            return 1000;
        }
    }
}
