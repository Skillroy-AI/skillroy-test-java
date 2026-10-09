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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small tool-library lending desk for the server's tests: the pack {@code desk} (verbs {@code deposit}, {@code enter},
 * {@code lend} and the free-form {@code poke}, of which {@code deposit} shares its tool name with the read primitive
 * {@code desk.deposit} and {@code lend} and {@code poke} with mutating ones; the fact root {@code desk}; one query kind
 * {@code Item} resolved through
 * {@code data.query} and verified through {@code desk.item}; the oracle {@code Twice}; an SQL allow-list over the
 * source {@code desk}), and the fake application behind the fake agent: its catalogue (reads, mutating primitives, a
 * destructive one, the agent's own), its state, its events, a capture, an oversized answer, its database's table
 * {@code item} (whose columns the allow-list check reads, {@link #itemColumns}), and a credential value that the
 * application leaks into its answers so that the tests can show that it never leaves the server.
 */
final class Desk {

    /** The pack's credential value; the application leaks it into its answers. */
    static final String SECRET = "pw-staff-100-secret";

    /** The title of the planted item (category {@code planted}): a quote and a set operation over a hidden column. */
    static final String PLANTED_TITLE = "x' UNION SELECT password AS code FROM staff WHERE 'a'='a";

    /** The SQL of the Item resolver. */
    static final String ITEM_SQL = "SELECT code, title FROM item WHERE category = ? ORDER BY code";
    /**
     * The allow-list check's statement on table {@code item} ({@code AllowListCheck}, decision D100: the server runs
     * it at its start, a tier (b) bind before its catalogue).
     */
    static final String ITEM_SCHEMA_CHECK = "SELECT * FROM item WHERE 1 = 0";

    final FakeAgent agent = new FakeAgent();
    final Pack pack = new Pack();
    final Map<String, Object> state = new LinkedHashMap<>(Map.of("mode", "IDLE", "operator", "100"));
    final AtomicInteger recovered = new AtomicInteger();
    /**
     * The columns the desk's database reports for table {@code item}: those the allow-list names, unless a test
     * changes them.
     */
    final List<Rows.Column> itemColumns = new CopyOnWriteArrayList<>(List.of(new Rows.Column("CODE", "VARCHAR"),
            new Rows.Column("TITLE", "VARCHAR"), new Rows.Column("CATEGORY", "VARCHAR"),
            new Rows.Column("BORROWER_EMAIL", "VARCHAR")));

    Desk() {
        agent.capability("desk.input", CapabilityState.OK).capability("data.query", CapabilityState.OK)
                .capability("swing.capture", CapabilityState.OK);
        Map<String, Object> inputParams = object(Map.of("source", Map.of("type", "string"), "value",
                Map.of("type", "string")), List.of("source"));
        agent.describe("desk.input", true, false, inputParams)
                .describe("desk.lend", true, false, null)
                .describe("desk.poke", true, false, null)
                .describe("desk.state", false, false, null)
                .describe("desk.deposit", false, false, object(Map.of("code", Map.of("type", "string")),
                        List.of("code")))
                .describe("desk.item", false, false, null)
                .describe("desk.dump", false, false, null)
                .describe("desk.echo", false, false, object(Map.of("text", Map.of("type", "string"), "pad",
                        Map.of("type", "integer")), List.of("text")))
                .describe("desk.wipe", true, true, null)
                .describe("app.state", false, false, null)
                .describe("swing.snapshot", false, false, null)
                .describe("swing.capture", false, false, object(Map.of("target", Map.of("type", "string")),
                        List.of()))
                .describe("swing.click", true, false, object(Map.of("target", Map.of("type", "string"), "button",
                        Map.of("type", "string")), List.of("target")))
                .describe("web.evaluate", true, false, object(Map.of("script", Map.of("type", "string")),
                        List.of("script")))
                .describe("web.waitFlag", false, false, object(Map.of("expression", Map.of("type", "string")),
                        List.of("expression")))
                .describe("jvm.info", false, false, null)
                .describe("data.query", false, false, object(Map.of("source", Map.of("type", "string"), "sql",
                        Map.of("type", "string"), "params", Map.of("type", "array"), "maxRows",
                        Map.of("type", "integer")), List.of("source", "sql")))
                .describe("data.sources", false, false, null)
                .describe("agent.disable", true, true, null)
                .describe("agent.info", false, false, null)
                .describe("evt.query", false, false, null)
                .describe("trace.mark", false, false, null);
        agent.on("desk.input", params -> {
            agent.publish("input.acked", Map.of("source", String.valueOf(params.get("source"))));
            state.put("mode", "LOAN");
            state.put("last", String.valueOf(params.get("value")));
            agent.publish("input.requested", Map.of("state", "LOAN", "pin", SECRET));
            return Map.of("accepted", true, "echo", "entered " + params.get("value") + " as " + SECRET);
        });
        agent.on("desk.lend", params -> Map.of("lent", params.get("item"), "params", new LinkedHashMap<>(params)));
        agent.value("desk.poke", Map.of("poked", true));
        agent.on("desk.state", params -> new LinkedHashMap<>(state));
        agent.on("desk.deposit", params -> Map.of("found", true, "code", params.get("code"), "deposit", "5.00"));
        agent.on("desk.item", params -> Map.of("found", true, "active", true, "code", params.get("code")));
        agent.value("desk.dump", Map.of("text", "x".repeat(60_000)));
        agent.on("desk.echo", params -> Map.of("echo", params.get("text"), "password", "hunter22-plain",
                "note", "token=" + SECRET));
        agent.value("desk.wipe", Map.of("wiped", true));
        agent.on("app.state", params -> Map.of("desk", new LinkedHashMap<>(state), "operatorPin", SECRET));
        agent.value("swing.snapshot", Map.of("seq", 9L, "truncated", false, "windows", List.of(Map.of("ref", "h1",
                "role", "window", "name", "Lending desk " + SECRET, "children", List.of(Map.of("ref", "h12",
                        "role", "button", "name", "Lend", "enabled", true))))));
        agent.value("swing.capture", Map.of("format", "png", "width", 1L, "height", 1L, "base64", FakeAgent.PNG_1X1));
        agent.value("swing.click", Map.of("clicked", true, "fired", true));
        agent.value("web.evaluate", Map.of("value", "evaluated"));
        agent.value("web.waitFlag", Map.of("ready", true));
        agent.value("jvm.info", Map.of("javaVersion", "25", "vendor", "test", "pid", 4242L));
        agent.value("data.sources", Map.of("sources", List.of(Map.of("id", "desk"))));
        agent.on("data.query", params -> query(String.valueOf(params.get("sql")), (List<?>) params.get("params"))
                .toJson());
        agent.publish("desk.opened", Map.of("by", "100", "pin", SECRET));
    }

    /**
     * What the fake database answers: the allow-list check's statement, the resolver's statement, and any guarded
     * statement over {@code item}.
     */
    private Rows query(String sql, List<?> params) {
        if (sql.equals(ITEM_SCHEMA_CHECK)) {
            return itemSchema();
        }
        List<Rows.Column> columns = List.of(new Rows.Column("CODE", "VARCHAR"), new Rows.Column("TITLE", "VARCHAR"));
        if (sql.equals(ITEM_SQL)) {
            String category = params == null || params.isEmpty() ? null : String.valueOf(params.get(0));
            // "planted": a row whose title a user of the application wrote, holding a quote and a set operation
            // (security pass 1, S55: a resolved value is target content).
            List<List<Object>> rows = "tools".equals(category) ? List.of(List.of("HAM-1", "Claw hammer"),
                    List.of("SAW-2", "Panel saw")) : "planted".equals(category) ? List.of(List.of("PL-1",
                    PLANTED_TITLE)) : List.of();
            return Rows.of(columns, rows, false);
        }
        if (sql.contains("FROM item")) {
            // A column the allow-list does not name, as a defective source might add: the prompt mask drops it.
            List<Rows.Column> wide = List.of(new Rows.Column("CODE", "VARCHAR"), new Rows.Column("TITLE", "VARCHAR"),
                    new Rows.Column("SHELF", "VARCHAR"));
            return Rows.of(wide, List.of(List.of("HAM-1", "Claw hammer", "B-7")), false);
        }
        throw new IllegalStateException("desk database: no rows for " + sql);
    }

    /** The answer to {@link #ITEM_SCHEMA_CHECK}: the columns of table {@code item} ({@link #itemColumns}), no row. */
    Rows itemSchema() {
        return Rows.of(List.copyOf(itemColumns), List.of(), false);
    }

    /** The {@code data.query} calls but the allow-list check's ({@link #ITEM_SCHEMA_CHECK}), in order. */
    List<FakeAgent.Call> queries() {
        return agent.callsTo("data.query").stream()
                .filter(c -> !ITEM_SCHEMA_CHECK.equals(String.valueOf(c.params().get("sql")))).toList();
    }

    /** The credentials of the desk's environment: realm {@code staff}, entry {@code 100} with its password. */
    static Credentials credentials() {
        try {
            return Credentials.of(Map.of("version", 1L, "credentials", Map.of("staff", Map.of("100",
                    Map.of("password", SECRET)))), "test credentials");
        } catch (CredentialsException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        schema.put("required", required);
        return schema;
    }

    /** The desk's pack with these row masks (the pack itself declares none). */
    DomainPack packWithRowMasks(List<ai.skillroy.testjava.data.RowMask> masks) {
        DomainPack base = pack;
        return new DomainPack() {
            @Override
            public String id() {
                return base.id();
            }

            @Override
            public String version() {
                return base.version();
            }

            @Override
            public StepLibrary steps() {
                return base.steps();
            }

            @Override
            public SyncPolicy syncPolicy() {
                return base.syncPolicy();
            }

            @Override
            public List<FactProvider> facts() {
                return base.facts();
            }

            @Override
            public void recover(AgentConnection connection) throws Exception {
                base.recover(connection);
            }

            @Override
            public ResolutionCatalogue resolution() {
                return base.resolution();
            }

            @Override
            public List<SqlAllowList> sqlAllowLists() {
                return base.sqlAllowLists();
            }

            @Override
            public List<ai.skillroy.testjava.data.RowMask> rowMasks() {
                return masks;
            }
        };
    }

    /**
     * The desk's pack whose verb {@code lend} declares this argument schema (phase6 security fix S49: a schema with a
     * secret-looking property and data in it).
     */
    DomainPack packWithLendSchema(Map<String, Object> schema) {
        DomainPack base = pack;
        StepLibrary baseSteps = base.steps();
        StepLibrary steps = new StepLibrary() {
            @Override
            public Set<String> verbs() {
                return baseSteps.verbs();
            }

            @Override
            public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
                return baseSteps.compile(verb, args, ctx);
            }

            @Override
            public Map<String, Object> argsSchema(String verb) {
                return verb.equals("lend") ? schema : baseSteps.argsSchema(verb);
            }
        };
        return new DomainPack() {
            @Override
            public String id() {
                return base.id();
            }

            @Override
            public String version() {
                return base.version();
            }

            @Override
            public StepLibrary steps() {
                return steps;
            }

            @Override
            public SyncPolicy syncPolicy() {
                return base.syncPolicy();
            }

            @Override
            public List<FactProvider> facts() {
                return base.facts();
            }

            @Override
            public void recover(AgentConnection connection) throws Exception {
                base.recover(connection);
            }

            @Override
            public ResolutionCatalogue resolution() {
                return base.resolution();
            }

            @Override
            public List<SqlAllowList> sqlAllowLists() {
                return base.sqlAllowLists();
            }
        };
    }

    /**
     * The desk's pack with the two verbs of the security review's probe (pass 1, S57), each of which compiles to the
     * destructive {@code desk.wipe} with its own {@code confirm} only for some arguments, so that no probe at the
     * server's start finds it: {@code tidy {hard?}} ({@code desk.wipe} when {@code hard} is true, else
     * {@code desk.poke}) and {@code stamp {amount}} ({@code desk.wipe} for a decimal string such as {@code 5.00}, and no
     * compile for anything else).
     */
    DomainPack packWithProbeVerbs() {
        DomainPack base = pack;
        StepLibrary baseSteps = base.steps();
        StepLibrary steps = new StepLibrary() {
            @Override
            public Set<String> verbs() {
                Set<String> verbs = new java.util.TreeSet<>(baseSteps.verbs());
                verbs.addAll(List.of("tidy", "stamp"));
                return verbs;
            }

            @Override
            public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
                Map<?, ?> a = args instanceof Map<?, ?> m ? m : Map.of();
                switch (verb) {
                    case "tidy": {
                        if (Boolean.TRUE.equals(a.get("hard"))) {
                            Map<String, Object> params = new LinkedHashMap<>();
                            params.put("confirm", true);
                            return List.of(new PlannedCall("desk.wipe", params, Sync.NONE, 0));
                        }
                        return List.of(new PlannedCall("desk.poke", Map.of(), Sync.NONE, 0));
                    }
                    case "stamp": {
                        if (!(a.get("amount") instanceof String amount) || !amount.matches("[0-9]+[.][0-9][0-9]")) {
                            throw new ScenarioException("stamp: 'amount' must be a decimal string such as 5.00");
                        }
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("confirm", true);
                        return List.of(new PlannedCall("desk.wipe", params, Sync.NONE, 0));
                    }
                    default:
                        return baseSteps.compile(verb, args, ctx);
                }
            }

            @Override
            public Map<String, Object> argsSchema(String verb) {
                return switch (verb) {
                    case "tidy" -> object(Map.of("hard", Map.of("type", "boolean")), List.of());
                    case "stamp" -> object(Map.of("amount", Map.of("type", "string")), List.of("amount"));
                    default -> baseSteps.argsSchema(verb);
                };
            }
        };
        return new DomainPack() {
            @Override
            public String id() {
                return base.id();
            }

            @Override
            public String version() {
                return base.version();
            }

            @Override
            public StepLibrary steps() {
                return steps;
            }

            @Override
            public SyncPolicy syncPolicy() {
                return base.syncPolicy();
            }

            @Override
            public List<FactProvider> facts() {
                return base.facts();
            }

            @Override
            public void recover(AgentConnection connection) throws Exception {
                base.recover(connection);
            }

            @Override
            public ResolutionCatalogue resolution() {
                return base.resolution();
            }

            @Override
            public List<SqlAllowList> sqlAllowLists() {
                return base.sqlAllowLists();
            }
        };
    }

    /** An item of a category. */
    record Item(String category) implements Query<ItemRef> {
    }

    /** The answer to {@link Item}. */
    record ItemRef(String code, String title) {
    }

    /** The desk's pack. */
    final class Pack implements DomainPack {

        @Override
        public String id() {
            return "desk";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public StepLibrary steps() {
            return new Steps();
        }

        @Override
        public SyncPolicy syncPolicy() {
            return new SyncPolicy("input.acked", List.of("input.requested", "prompt.waiting"),
                    List.of("prompt.waiting"), null, null);
        }

        @Override
        public List<FactProvider> facts() {
            return List.of(new FactProvider() {
                @Override
                public String root() {
                    return "desk";
                }

                @Override
                public Object fetch(AgentConnection connection) throws Exception {
                    return connection.call("desk.state", Map.of()).value();
                }
            });
        }

        @Override
        public void recover(AgentConnection connection) {
            recovered.incrementAndGet();
        }

        @Override
        public ResolutionCatalogue resolution() {
            return ResolutionCatalogue.builder()
                    .query(QueryType.of("Item", Item.class))
                    .resolver(new ItemResolver())
                    .verifier(new ItemCheck())
                    .oracle(new Twice())
                    .build();
        }

        @Override
        public List<SqlAllowList> sqlAllowLists() {
            return List.of(SqlAllowList.builder("desk", SqlDialect.HSQLDB)
                    .table("item", List.of("code"), AllowedColumn.of("code", "VARCHAR"),
                            AllowedColumn.of("title", "VARCHAR"), AllowedColumn.sampled("category", "VARCHAR"),
                            AllowedColumn.personal("borrower_email", "VARCHAR"))
                    .build());
        }
    }

    /**
     * The desk's verbs: {@code deposit {amount}} (the DEPOSIT key), {@code enter {value}}, {@code lend {item,
     * borrower?}}, {@code poke} (free-form).
     */
    static final class Steps implements StepLibrary {

        @Override
        public Set<String> verbs() {
            return Set.of("deposit", "enter", "lend", "poke");
        }

        @Override
        public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
            Map<?, ?> a = args instanceof Map<?, ?> m ? m : Map.of();
            switch (verb) {
                case "deposit": {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("source", "Key.DEPOSIT");
                    params.put("value", String.valueOf(a.get("amount")));
                    return List.of(new PlannedCall("desk.input", params, Sync.READY, 0));
                }
                case "enter": {
                    if (a.get("value") == null) {
                        throw new ScenarioException("enter: 'value' is required");
                    }
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("source", "Entry");
                    params.put("value", a.get("value"));
                    return List.of(new PlannedCall("desk.input", params, Sync.READY, 0));
                }
                case "lend": {
                    Map<String, Object> params = new LinkedHashMap<>();
                    a.forEach((k, v) -> params.put(String.valueOf(k), v));
                    return List.of(new PlannedCall("desk.lend", params, Sync.NONE, 0));
                }
                case "poke":
                    return new ArrayList<>(List.of(new PlannedCall("desk.poke", Map.of(), Sync.NONE, 0)));
                default:
                    throw new ScenarioException("unknown desk verb " + verb);
            }
        }

        @Override
        public Map<String, Object> argsSchema(String verb) {
            return switch (verb) {
                case "deposit" -> object(Map.of("amount", Map.of("type", "string", "description",
                        "the deposit taken, as a decimal string")), List.of("amount"));
                case "enter" -> object(Map.of("value", Map.of("type", "string", "description",
                        "the entry, or a $reference")), List.of("value"));
                case "lend" -> object(Map.of("item", Map.of("type", "string"), "borrower", Map.of("type", "string")),
                        List.of("item"));
                default -> null;
            };
        }
    }

    /** Items of a category, from the desk's database. */
    static final class ItemResolver implements Resolver<Item, ItemRef> {

        @Override
        public String id() {
            return "desk.sql.Item";
        }

        @Override
        public Class<Item> queryClass() {
            return Item.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_SQL;
        }

        @Override
        public List<ResolverCandidate<ItemRef>> candidates(Item query, ResolveContext ctx) throws
                ai.skillroy.testjava.data.DataException, ai.skillroy.testjava.controller.AgentLostException,
                InterruptedException {
            Rows rows = ctx.data().query("desk", ITEM_SQL, List.of(query.category()), 200, null);
            List<ResolverCandidate<ItemRef>> out = new ArrayList<>();
            for (int r = 0; r < rows.rowCount(); r++) {
                String code = String.valueOf(rows.get(r, 0));
                out.add(ResolverCandidate.of(new ItemRef(code, String.valueOf(rows.get(r, 1))), "item " + code));
            }
            return out;
        }
    }

    /** The application knows the item and it is active. */
    static final class ItemCheck implements Verifier<Item, ItemRef> {

        @Override
        public String id() {
            return "desk.item.check";
        }

        @Override
        public Class<Item> queryClass() {
            return Item.class;
        }

        @Override
        public VerifierResult verify(Item query, ItemRef candidate, VerifyContext ctx) throws
                ai.skillroy.testjava.controller.AgentRpcException, ai.skillroy.testjava.controller.AgentLostException {
            Map<?, ?> answer = (Map<?, ?>) ctx.agent().call("desk.item", Map.of("code", candidate.code())).value();
            return Boolean.TRUE.equals(answer.get("active")) ? VerifierResult.pass("active", Map.of())
                    : VerifierResult.fail("not active", Map.of());
        }
    }

    /** Twice an amount. */
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
}
