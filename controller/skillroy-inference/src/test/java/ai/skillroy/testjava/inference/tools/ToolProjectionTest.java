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

package ai.skillroy.testjava.inference.tools;

import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.inference.sql.LendingFixture;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 9.3 and 12.4 step 1: primitives and verbs as tools (names, descriptions, strict schemas with
 * optional keys nullable, the hints), the policies (read-only, mutating, a session's), {@code data_query} only with an
 * allow-list, never a destructive primitive or the agent's own; the workflow and session tool constants.
 */
class ToolProjectionTest {

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static PrimitiveDescriptor d(String name, boolean mutating, boolean destructive,
                                         Map<String, Object> params) {
        return new PrimitiveDescriptor(name, "does " + name, params, null, "EDT", 5000, null, mutating, destructive);
    }

    static final List<PrimitiveDescriptor> PRIMITIVES = List.of(
            d("app.getState", false, false, object(Map.of("path", Map.of("type", "string")), List.of())),
            d("swing.click", true, false, object(Map.of("locator", Map.of("type", "object", "properties",
                    Map.of("name", Map.of("type", "string")), "required", List.of("name")), "button",
                    Map.of("type", "string"), "_meta", Map.of("type", "object")), List.of("locator", "_meta"))),
            d("swing.snapshot", false, false, null),
            d("web.evaluate", true, false, object(Map.of("script", Map.of("type", "string")), List.of("script"))),
            d("web.waitFlag", false, false, object(Map.of("expression", Map.of("type", "string")),
                    List.of("expression"))),
            d("lending.deposit", false, false, object(Map.of("item", Map.of("type", "string", "minLength", 1)),
                    List.of("item"))),
            d("lending.loan", true, false, null),
            d("lending.prompt.answer", true, true, null),
            d("data.query", false, false, object(Map.of("source", Map.of("type", "string"), "sql",
                    Map.of("type", "string")), List.of("source", "sql"))),
            d("agent.disable", true, true, null),
            d("agent.info", false, false, null),
            d("device.list", false, false, null));

    /** A pack with two verbs, one with a schema with optional keys, and maybe an allow-list. */
    static DomainPack pack(boolean allowList, Map<String, Object> signOnSchema) {
        return pack(allowList, signOnSchema, Set.of("signOn", "scanItem"));
    }

    /** {@link #pack(boolean, Map)} with these verbs. */
    static DomainPack pack(boolean allowList, Map<String, Object> signOnSchema, Set<String> packVerbs) {
        return new DomainPack() {
            @Override
            public String id() {
                return "lending";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public StepLibrary steps() {
                return new StepLibrary() {
                    @Override
                    public Set<String> verbs() {
                        return packVerbs;
                    }

                    @Override
                    public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) {
                        return List.of();
                    }

                    @Override
                    public Map<String, Object> argsSchema(String verb) {
                        return verb.equals("signOn") ? signOnSchema : null;
                    }
                };
            }

            @Override
            public SyncPolicy syncPolicy() {
                return new SyncPolicy(null, null, null, null, null);
            }

            @Override
            public List<FactProvider> facts() {
                return List.of();
            }

            @Override
            public List<SqlAllowList> sqlAllowLists() {
                return allowList ? List.of(LendingFixture.allowList(SqlDialect.HSQLDB)) : List.of();
            }
        };
    }

    static Map<String, Object> signOnSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("staff", Map.of("type", "string"));
        props.put("pin", new LinkedHashMap<>(Map.of("type", "string", "maxLength", 8)));
        props.put("mode", Map.of("type", "string", "enum", List.of("DESK", "SUPERVISOR")));
        return object(props, List.of("staff"));
    }

    private static List<String> names(List<ToolSpec> tools) {
        return tools.stream().map(ToolSpec::name).toList();
    }

    private static ToolSpec named(List<ToolSpec> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(() ->
                new AssertionError(name + " not in " + names(tools)));
    }

    /** Every object that declares properties requires all of them and refuses others. */
    static boolean strict(Object schema) {
        if (!(schema instanceof Map<?, ?> map)) {
            return true;
        }
        if (map.get("properties") instanceof Map<?, ?> props) {
            if (!Boolean.FALSE.equals(map.get("additionalProperties"))
                    || !(map.get("required") instanceof List<?> required)
                    || !new HashSet<>(required).equals(props.keySet())) {
                return false;
            }
            for (Object p : props.values()) {
                if (!strict(p)) {
                    return false;
                }
            }
        } else if ("object".equals(map.get("type"))) {
            return false;
        }
        return map.get("items") == null || strict(map.get("items"));
    }

    @Test
    void namesUseSnakeCaseOnly() {
        assertEquals("a_b_c", ToolProjection.toolName("a.b.c"));
        assertEquals("app_get_state", ToolProjection.toolName("app.getState"));
        assertEquals("web_aria_snapshot", ToolProjection.toolName("web.ariaSnapshot"));
        assertEquals("lending_access_check", ToolProjection.toolName("lending.access.check"));
        assertEquals("lending_sign_on", ToolProjection.verbToolName("lending", "signOn"));
        assertThrows(IllegalArgumentException.class, () -> ToolProjection.toolName("x".repeat(70)));
        List<PrimitiveDescriptor> clash = List.of(d("app.a_b", false, false, null), d("app.a.b", false, false, null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ToolProjection.project(clash, pack(false, null), ToolProjection.Policy.readOnly()));
        assertEquals("tool name collision: two tools project to app_a_b", e.getMessage());
    }

    @Test
    void readOnlyOffersTheReadsAndDataQueryWithAnAllowList() {
        List<ToolSpec> tools = ToolProjection.project(PRIMITIVES, pack(true, signOnSchema()),
                ToolProjection.Policy.readOnly());
        assertEquals(List.of("app_get_state", "data_query", "lending_deposit", "swing_snapshot"), names(tools));
        for (ToolSpec t : tools) {
            assertTrue(t.readOnly(), t.name());
            assertFalse(t.destructive());
        }
        ToolSpec query = named(tools, "data_query");
        assertEquals(List.of("lending"), ((Map<?, ?>) ((Map<?, ?>) query.inputSchema().get("properties"))
                .get("source")).get("enum"));
        assertTrue(query.description().contains("SqlGuard"));
        assertFalse(names(ToolProjection.project(PRIMITIVES, pack(false, null), ToolProjection.Policy.readOnly()))
                .contains("data_query"), "a source without an allow-list is not offered");
        ToolSpec state = named(tools, "app_get_state");
        assertTrue(state.description().startsWith("[thread: EDT, timeout: 5000 ms] does app.getState"));
        assertEquals(Map.of("type", List.of("string", "null")), ((Map<?, ?>) state.inputSchema().get("properties"))
                .get("path"), "an optional key is nullable");
        assertTrue(strict(state.inputSchema()));
        assertFalse(((Map<?, ?>) ((Map<?, ?>) named(tools, "lending_deposit").inputSchema().get("properties"))
                .get("item")).containsKey("minLength"), "a length the provider does not take is left out");
    }

    @Test
    void mutatingAddsVerbsAndMutatingPrimitivesNeverADestructiveOne() {
        List<ToolSpec> tools = ToolProjection.project(PRIMITIVES, pack(true, signOnSchema()),
                ToolProjection.Policy.mutating());
        assertEquals(List.of("app_get_state", "data_query", "lending_deposit", "lending_loan", "swing_click",
                "swing_snapshot", "lending_scan_item", "lending_sign_on"), names(tools), "never web_evaluate by"
                + " default (D98)");
        assertEquals(names(tools), names(ToolProjection.project(PRIMITIVES, pack(true, signOnSchema()),
                ToolProjection.Policy.mutating(false))));
        assertTrue(names(ToolProjection.project(PRIMITIVES, pack(true, signOnSchema()),
                ToolProjection.Policy.mutating(true))).containsAll(List.of("web_evaluate", "web_wait_flag")),
                "a person's opt-in offers both");
        List<String> readOnlyOptedIn = names(ToolProjection.project(PRIMITIVES, pack(true, signOnSchema()),
                ToolProjection.Policy.readOnly(true)));
        assertTrue(readOnlyOptedIn.contains("web_wait_flag") && !readOnlyOptedIn.contains("web_evaluate"),
                "read-only, opted in: the read that runs a script, never the mutating one: " + readOnlyOptedIn);
        assertEquals(ToolProjection.SCRIPT_PRIMITIVES, ToolProjection.Policy.session(true, true).excluded(),
                "a session never offers either");
        ToolSpec click = named(tools, "swing_click");
        assertFalse(click.readOnly());
        Map<?, ?> props = (Map<?, ?>) click.inputSchema().get("properties");
        assertFalse(props.containsKey("_meta"), "_meta is the executor's, never the model's");
        assertEquals(List.of("locator", "button"), click.inputSchema().get("required"));
        assertTrue(strict(click.inputSchema()), click.inputSchema().toString());
        assertEquals(Map.of("type", "object"), named(tools, "lending_loan").inputSchema(),
                "no params schema: a free-form object");
    }

    @Test
    void aVerbsOptionalKeysBecomeNullableAndThePacksSchemaIsKept() {
        Map<String, Object> original = signOnSchema();
        String before = original.toString();
        ToolSpec signOn = named(ToolProjection.project(List.of(), pack(false, original),
                ToolProjection.Policy.mutating()), "lending_sign_on");
        Map<?, ?> props = (Map<?, ?>) signOn.inputSchema().get("properties");
        assertEquals(Map.of("type", "string"), props.get("staff"));
        assertEquals(Map.of("type", List.of("string", "null")), props.get("pin"), "maxLength left out too");
        Map<?, ?> mode = (Map<?, ?>) props.get("mode");
        assertEquals(List.of("string", "null"), mode.get("type"));
        assertEquals(java.util.Arrays.asList("DESK", "SUPERVISOR", null), mode.get("enum"));
        assertEquals(List.of("staff", "mode", "pin"), signOn.inputSchema().get("required"), "its own, then by name");
        assertEquals(false, signOn.inputSchema().get("additionalProperties"));
        assertTrue(strict(signOn.inputSchema()));
        assertEquals(before, original.toString(), "the scenario schema keeps the pack's own");
        assertFalse(signOn.readOnly());
        ToolSpec scan = named(ToolProjection.project(List.of(), pack(false, original),
                ToolProjection.Policy.mutating()), "lending_scan_item");
        assertEquals(Map.of("type", "object"), scan.inputSchema());
        assertTrue(scan.description().contains("free-form"));
        Map<String, Object> noType = new LinkedHashMap<>();
        noType.put("anyOf", List.of(Map.of("type", "string"), Map.of("type", "integer")));
        Map<String, Object> wrapped = ToolProjection.nullable(noType);
        assertEquals(2, ((List<?>) wrapped.get("anyOf")).size());
        assertEquals(Map.of("type", "null"), ((List<?>) wrapped.get("anyOf")).get(1));
    }

    @Test
    void aPrimitiveWhoseNameAVerbTakesIsNamedWithTheSuffixInEveryPolicy() {
        DomainPack pack = pack(true, signOnSchema(), Set.of("signOn", "deposit"));
        List<ToolSpec> readOnly = ToolProjection.project(PRIMITIVES, pack, ToolProjection.Policy.readOnly());
        assertEquals(List.of("app_get_state", "data_query", "lending_deposit_primitive", "swing_snapshot"),
                names(readOnly), "the read primitive lending.deposit, not the name of the verb deposit");
        assertTrue(named(readOnly, "lending_deposit_primitive").readOnly());
        List<ToolSpec> mutating = ToolProjection.project(PRIMITIVES, pack, ToolProjection.Policy.mutating());
        assertTrue(names(mutating).containsAll(List.of("lending_deposit", "lending_deposit_primitive")),
                names(mutating).toString());
        assertTrue(named(mutating, "lending_deposit").description().startsWith("Pack verb 'deposit'"),
                "the verb keeps the plain name");
        assertTrue(named(mutating, "lending_deposit_primitive").description().contains("does lending.deposit"));
        assertEquals(Set.of("lending_sign_on", "lending_deposit"), ToolProjection.verbToolNames(pack));
        assertEquals("lending_deposit_primitive", ToolProjection.primitiveToolName("lending.deposit",
                ToolProjection.verbToolNames(pack)));
        assertEquals("lending_loan", ToolProjection.primitiveToolName("lending.loan",
                ToolProjection.verbToolNames(pack)));
        DomainPack twice = pack(false, null, Set.of("deposit", "depositPrimitive"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ToolProjection.project(PRIMITIVES, twice, ToolProjection.Policy.mutating()));
        assertEquals("tool name collision: two tools project to lending_deposit_primitive", e.getMessage(),
                "any other collision is still refused");
    }

    @Test
    void aSessionOffersVerbsAndBasePrimitivesByPolicyOnly() {
        assertEquals(List.of(), ToolProjection.project(PRIMITIVES, pack(true, signOnSchema()),
                ToolProjection.Policy.session(false, false)), "no reads (the workflow reads stand for them)");
        assertEquals(List.of("swing_click", "lending_scan_item", "lending_sign_on"),
                names(ToolProjection.project(PRIMITIVES, pack(true, signOnSchema()),
                        ToolProjection.Policy.session(true, true))),
                "never data_query, never evaluate, never a domain primitive");
    }

    /**
     * phase6-contracts.md 4.2, 13.1 item 12: the seven hook and rule primitives that take a spec or a rule are never
     * offered, whatever the policy and whatever their flags ({@code hook.install} and {@code hook.remove} are not
     * mutating, {@code hook.probe} is a read); {@code hook.health}, {@code hook.events} and {@code rule.list} are
     * offered as reads by every policy.
     */
    @Test
    void hookAndRulePrimitivesAreNeverOfferedButTheThreeReads() {
        List<PrimitiveDescriptor> hooks = new java.util.ArrayList<>(PRIMITIVES);
        hooks.add(d("hook.probe", false, false, null));
        hooks.add(d("hook.install", false, false, null));
        hooks.add(d("hook.remove", false, false, null));
        hooks.add(d("hook.stub", true, true, null));
        hooks.add(d("hook.health", false, false, null));
        hooks.add(d("hook.events", false, false, null));
        hooks.add(d("rule.check", false, false, null));
        hooks.add(d("rule.submit", true, true, null));
        hooks.add(d("rule.unload", false, false, null));
        hooks.add(d("rule.list", false, false, null));
        for (ToolProjection.Policy policy : List.of(ToolProjection.Policy.readOnly(),
                ToolProjection.Policy.readOnly(true), ToolProjection.Policy.mutating(),
                ToolProjection.Policy.mutating(true), ToolProjection.Policy.session(true, true),
                ToolProjection.Policy.session(false, false))) {
            List<String> names = names(ToolProjection.project(hooks, pack(true, signOnSchema()), policy));
            for (String never : List.of("hook_probe", "hook_install", "hook_remove", "hook_stub", "rule_check",
                    "rule_submit", "rule_unload")) {
                assertFalse(names.contains(never), never + " under " + policy + ": " + names);
            }
            for (String read : List.of("hook_health", "hook_events", "rule_list")) {
                assertTrue(names.contains(read), read + " under " + policy + ": " + names);
            }
        }
        assertEquals(Set.of("hook.probe", "hook.install", "hook.stub", "hook.remove", "rule.check", "rule.submit",
                "rule.unload"), ToolProjection.NEVER_OFFERED);
        assertTrue(named(ToolProjection.project(hooks, pack(false, signOnSchema()), ToolProjection.Policy.readOnly()),
                "hook_events").readOnly());
    }

    @Test
    void theWorkflowAndSessionTools() {
        List<ToolSpec> readOnly = WorkflowTools.workflow(false, false);
        assertEquals(List.of("skillroy_catalogue", "skillroy_snapshot", "skillroy_facts", "skillroy_events",
                "skillroy_resolve", "skillroy_scenario_compile", "skillroy_session_check", "skillroy_session_observe",
                "skillroy_session_note", "skillroy_session_export"), names(readOnly));
        assertTrue(names(WorkflowTools.workflow(true, false)).containsAll(List.of("skillroy_scenario_run",
                "skillroy_session_ask")));
        List<String> readOnlySession = names(WorkflowTools.workflow(false, true));
        assertTrue(readOnlySession.contains("skillroy_session_ask"));
        assertFalse(readOnlySession.contains("skillroy_scenario_run"));
        List<ToolSpec> session = WorkflowTools.session(PRIMITIVES, pack(true, signOnSchema()), true, false);
        assertEquals(List.of("skillroy_snapshot", "skillroy_facts", "skillroy_events", "skillroy_catalogue",
                "skillroy_resolve", "skillroy_check", "skillroy_observe", "skillroy_note", "lending_scan_item",
                "lending_sign_on", "done", "ask_person"), names(session));
        List<ToolSpec> all = new ArrayList<>(List.of(WorkflowTools.CATALOGUE, WorkflowTools.SNAPSHOT,
                WorkflowTools.FACTS, WorkflowTools.EVENTS, WorkflowTools.RESOLVE, WorkflowTools.SCENARIO_COMPILE,
                WorkflowTools.SCENARIO_RUN, WorkflowTools.SESSION_ASK, WorkflowTools.SESSION_CHECK,
                WorkflowTools.SESSION_OBSERVE, WorkflowTools.SESSION_NOTE, WorkflowTools.SESSION_EXPORT,
                WorkflowTools.CHECK, WorkflowTools.OBSERVE, WorkflowTools.NOTE, WorkflowTools.DONE,
                WorkflowTools.ASK_PERSON));
        assertEquals(all.size(), new HashSet<>(names(all)).size(), "distinct names");
        for (ToolSpec t : all) {
            assertFalse(t.destructive());
            boolean freeForm = t == WorkflowTools.RESOLVE || t == WorkflowTools.CHECK
                    || t == WorkflowTools.SESSION_CHECK;
            assertEquals(!freeForm, strict(t.inputSchema()), t.name());
        }
        assertFalse(WorkflowTools.SCENARIO_RUN.readOnly());
        assertTrue(WorkflowTools.SNAPSHOT.readOnly());
        assertEquals(List.of("integer", "null"), ((Map<?, ?>) ((Map<?, ?>) WorkflowTools.EVENTS.inputSchema()
                .get("properties")).get("sinceSeq")).get("type"));
    }
}
