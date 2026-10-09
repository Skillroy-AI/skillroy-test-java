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

package ai.skillroy.testjava.telemetry;

import ai.skillroy.testjava.scenario.BaseSteps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The attribute allow-list of the exported spans (phase6-contracts.md 10.4, decision D142): every attribute, event
 * attribute and span name the module writes is a constant here, with the record field it is taken from. The names
 * follow the OpenTelemetry semantic conventions <b>1.37.0</b> where one exists ({@code test.case.name},
 * {@code test.case.result.status}, {@code error.type}, {@code gen_ai.*}, {@code mcp.method.name},
 * {@code mcp.session.id}); the {@code opentelemetry-semconv} jars are not used (they move between releases and would be
 * two more jars to audit). Everything else is {@code skillroy.*}.
 *
 * <p>Values are identifiers, enumerations, counts, durations, hashes and masked one-line messages, taken from the plan
 * or a record's identifiers and never from a bound or substituted form; never call parameters or values, event
 * payloads, snapshot text, resolved values, SQL, rows, prompts, credentials, the workspace id or a path (P2 11.4).
 * Text values are cut at {@value #MAX_VALUE_LENGTH} characters.
 *
 * <p>{@code minimal} (phase6-contracts.md 10.3) drops exactly {@link #MINIMAL_DROPS}, replaces exactly
 * {@link #MINIMAL_REPLACED} by a class of the value, hashes exactly {@link #MINIMAL_HASHED}, and names the spans of
 * {@link #MINIMAL_SPAN_NAMES} without their ids, the id going hashed into {@value #ID_HASH}; the
 * {@code resolve <slot>} and {@code oracle <name>} event names become {@code resolve} and {@code oracle}, and the
 * {@code expect} event's path becomes its root.
 *
 * <p>Stateless; safe from any thread.
 */
public final class TelemetryAttributes {

    /** The semantic conventions version the names follow. */
    public static final String SEMCONV_VERSION = "1.37.0";

    /** Text values are cut at this length (with "..."). */
    public static final int MAX_VALUE_LENGTH = 256;
    /** Attributes per span (the SDK's default, set explicitly). */
    public static final int MAX_ATTRIBUTES = 64;
    /** Events per span (the SDK's default, set explicitly). */
    public static final int MAX_EVENTS = 128;

    // ---------------------------------------------------------------------------------------- the root span

    /** A random hex id of the invocation ({@code RunStart}). */
    public static final String RUN_ID = "skillroy.run.id";
    /** The command ({@code RunStart.command}). */
    public static final String COMMAND = "skillroy.command";
    /** The suite name ({@code RunStart.suite}). */
    public static final String SUITE = "skillroy.suite";
    /** The bind mode's label ({@code RunStart.bindMode}, {@code ScenarioStart.bindMode}, the {@code bind} record). */
    public static final String BIND_MODE = "skillroy.bind.mode";
    /** The environment id ({@code RunStart.envId}, the {@code bind} record's {@code envId}). */
    public static final String ENV_ID = "skillroy.env.id";
    /** The CLI's (or the JUnit glue's) version ({@code RunStart.cliVersion}). */
    public static final String CLI_VERSION = "skillroy.cli.version";
    /** The target's pid ({@code RunStart.Target.pid}). */
    public static final String TARGET_PID = "skillroy.target.pid";
    /** The target's {@code java.version} ({@code agent.info}). */
    public static final String TARGET_JAVA_VERSION = "skillroy.target.java.version";
    /** The load mode ({@code agent.info}). */
    public static final String TARGET_LOAD_MODE = "skillroy.target.load_mode";
    /** The agent's version ({@code agent.info}). */
    public static final String AGENT_VERSION = "skillroy.agent.version";
    /** The agent's protocol ({@code agent.info}). */
    public static final String AGENT_PROTOCOL = "skillroy.agent.protocol";
    /** The agent connection's session id (the endpoint's). */
    public static final String AGENT_SESSION = "skillroy.agent.session";

    // ---------------------------------------------------------------------------------------- the scenario span

    /** The scenario id ({@code ScenarioStart.scenarioId}); hashed in {@code minimal}. */
    public static final String TEST_CASE_NAME = "test.case.name";
    /** {@code pass} for PASS and DEGRADED, {@code fail} for FAIL and ERROR ({@code RunResult.status}). */
    public static final String TEST_CASE_RESULT_STATUS = "test.case.result.status";
    /** The status ({@code RunResult.status}). */
    public static final String STATUS = "skillroy.status";
    /** The failure kind ({@code RunResult.failureKind}). */
    public static final String FAILURE_KIND = "skillroy.failure.kind";
    /** The failed step's id ({@code RunResult.failedStepId}); hashed in {@code minimal}. */
    public static final String FAILED_STEP = "skillroy.failed_step";
    /** The pack id ({@code ScenarioStart.pack}). */
    public static final String PACK = "skillroy.pack";
    /** The pack version ({@code ScenarioStart.packVersion}). */
    public static final String PACK_VERSION = "skillroy.pack.version";
    /** The plan format version ({@code ScenarioStart.planVersion}). */
    public static final String PLAN_VERSION = "skillroy.plan.version";
    /** The source hash ({@code ScenarioStart.sourceHash}). */
    public static final String SOURCE_HASH = "skillroy.source_hash";
    /** The result's one-line message, masked ({@code RunResult.message}); also the {@code environment} event's. */
    public static final String MESSAGE = "skillroy.message";
    /** How many expectations were skipped ({@code RunResult.skippedExpectations}, a count). */
    public static final String SKIPPED_EXPECTATIONS = "skillroy.skipped_expectations";
    /** How many calls ran at a lower fidelity ({@code RunResult.degradedFidelity}, a count). */
    public static final String DEGRADED_FIDELITY = "skillroy.degraded_fidelity";
    /** How many model calls the run made ({@code RunResult.modelCalls}). */
    public static final String MODEL_CALLS = "skillroy.model_calls";
    /** What the run's (or one call's) model calls cost, a plain decimal in USD. */
    public static final String MODEL_COST_USD = "skillroy.model.cost_usd";
    /** The hooks mode (the {@code hooks} record's {@code mode}). */
    public static final String HOOKS_MODE = "skillroy.hooks.mode";
    /** The installed catalogue's hash (the {@code hooks} record's {@code catalogue.hash}). */
    public static final String HOOKS_CATALOGUE_HASH = "skillroy.hooks.catalogue.hash";
    /** The archived matrix's hash (the {@code capabilities} record's {@code hash}). */
    public static final String CAPABILITIES_HASH = "skillroy.capabilities.hash";
    /** In {@code minimal}, the {@code sha256:} of the id a span name leaves out. */
    public static final String ID_HASH = "skillroy.id.hash";

    // ---------------------------------------------------------------------------------------- bind

    /** The bindings file's name, never a directory (the {@code bind} record's {@code bindingsFile}). */
    public static final String BINDINGS_FILE = "skillroy.bindings.file";
    /** How many guarded cross-checks the bind skipped (the {@code bind} record's {@code skipped}, a count). */
    public static final String BIND_SKIPPED = "skillroy.bind.skipped";

    // ---------------------------------------------------------------------------------------- step

    /** The verb ({@code StepStart.verb}); a pack verb is {@code verb} in {@code minimal}. */
    public static final String STEP_VERB = "skillroy.step.verb";
    /** The scenario line ({@code StepStart.line}). */
    public static final String STEP_LINE = "skillroy.step.line";
    /** The step's status ({@code StepResult.status}, {@code StepOutcome.status}). */
    public static final String STEP_STATUS = "skillroy.step.status";
    /** The step's one-line message, masked ({@code StepResult.message}, {@code StepOutcome.message}). */
    public static final String STEP_MESSAGE = "skillroy.step.message";
    /** How many of the step's expectations were skipped (a count). */
    public static final String STEP_SKIPPED_EXPECTATIONS = "skillroy.step.skipped_expectations";

    // ---------------------------------------------------------------------------------------- call, agent, await

    /** The primitive ({@code CallStart.primitive}); its namespace in {@code minimal} ({@code pack} for a pack's). */
    public static final String PRIMITIVE = "skillroy.primitive";
    /** The call's number in its step ({@code CallStart.index}). */
    public static final String CALL_INDEX = "skillroy.call.index";
    /** The path that served the call ({@code CallEnd.path}: {@code agent}, {@code cdp}). */
    public static final String PATH = "skillroy.path";
    /** The call's correlation id ({@code CallStart.correlationId}). */
    public static final String CORRELATION_ID = "skillroy.correlation_id";
    /** Whether it succeeded ({@code CallEnd.ok}, {@code AwaitRecord.ok}, a record's {@code ok}). */
    public static final String OK = "skillroy.ok";
    /** The error code's name ({@code CallEnd.errorCode}), or a failed model call's error kind. */
    public static final String ERROR_TYPE = "error.type";
    /** The agent's sequence number before the call ({@code CallEnd.seqBefore}). */
    public static final String SEQ_BEFORE = "skillroy.seq.before";
    /** The agent's sequence number after the call ({@code CallEnd.seqAfter}). */
    public static final String SEQ_AFTER = "skillroy.seq.after";
    /** How many events the reply carried ({@code CallEnd.events}, a count). */
    public static final String EVENTS = "skillroy.events";
    /** The names of the parameters announced as secret ({@code CallStart.secretParams}, names only). */
    public static final String SECRET_PARAMS = "skillroy.secret_params";
    /** Whether the call was confirmed ({@code CallStart.confirm}). */
    public static final String CONFIRM = "skillroy.confirm";
    /** The agent's own duration ({@code CallEnd.agentDurationMs}). */
    public static final String AGENT_DURATION_MS = "skillroy.agent.duration_ms";
    /** The agent's thread ({@code CallEnd.agentThread}). */
    public static final String AGENT_THREAD = "skillroy.agent.thread";
    /** Always {@code reconstructed}: the agent's start time is not on the wire (D142). */
    public static final String AGENT_TIMING = "skillroy.agent.timing";
    /** An agent event's topic ({@code CallEnd.EventFact.topic}; the call span's events). */
    public static final String EVENT_TOPIC = "skillroy.topic";
    /** An agent event's sequence number. */
    public static final String EVENT_SEQ_NUMBER = "skillroy.seq";
    /** An agent event's source. */
    public static final String EVENT_SOURCE = "skillroy.source";
    /** The wait's mode ({@code AwaitRecord.mode}). */
    public static final String SYNC_MODE = "skillroy.sync.mode";
    /** The sequence number of the event that ended the wait ({@code AwaitRecord.eventSeq}). */
    public static final String EVENT_SEQ = "skillroy.event.seq";
    /** That event's topic ({@code AwaitRecord.topic}). */
    public static final String TOPIC = "skillroy.topic";
    /** How long the wait took ({@code AwaitRecord.ms}). */
    public static final String SYNC_MS = "skillroy.sync.ms";
    /** Who published the event ({@code AwaitRecord.source}). */
    public static final String SYNC_SOURCE = "skillroy.sync.source";

    // ---------------------------------------------------------------------------------------- model calls

    /** Always {@code chat}. */
    public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
    /** The provider ({@code anthropic} for {@code claude}; {@code recorded}, {@code scripted} as given). */
    public static final String GEN_AI_PROVIDER_NAME = "gen_ai.provider.name";
    /** The model asked (the details' {@code requestedModel}). */
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    /** The model that answered ({@code CostLedger.Entry.model}). */
    public static final String GEN_AI_RESPONSE_MODEL = "gen_ai.response.model";
    /** The request id ({@code CostLedger.Entry.requestId}). */
    public static final String GEN_AI_RESPONSE_ID = "gen_ai.response.id";
    /** Input tokens ({@code CostLedger.Entry.inputTokens}, {@code TurnRecord.inputTokens}). */
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    /** Output tokens. */
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    /** Cache read tokens. */
    public static final String CACHE_READ_TOKENS = "skillroy.cache.read_tokens";
    /** Cache write tokens. */
    public static final String CACHE_WRITE_TOKENS = "skillroy.cache.write_tokens";
    /** The stop reason (the details' {@code stop}), as a one-element array. */
    public static final String GEN_AI_RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";
    /** The call's purpose ({@code resolve}, {@code session}, {@code triage}, …). */
    public static final String PURPOSE = "skillroy.purpose";
    /** The slot it served ({@code CostLedger.Entry.slot}). */
    public static final String SLOT = "skillroy.slot";
    /** The resolution strategy ({@code CostLedger.Entry.strategy}; a {@code resolve} event's). */
    public static final String STRATEGY = "skillroy.strategy";
    /** Whether it went through a batch. */
    public static final String BATCH = "skillroy.batch";

    // ---------------------------------------------------------------------------------------- the session, MCP

    /** The session id ({@code SessionStart.id}); hashed into {@value #ID_HASH} in {@code minimal}. */
    public static final String SESSION_ID = "skillroy.session.id";
    /** The session's policy ({@code SessionStart.policy}). */
    public static final String SESSION_POLICY = "skillroy.session.policy";
    /** The turn's number ({@code TurnRecord.index}). */
    public static final String TURN_INDEX = "skillroy.turn.index";
    /** How the turn ended ({@code TurnRecord.outcome}). */
    public static final String TURN_OUTCOME = "skillroy.turn.outcome";
    /** How many actions the turn took ({@code TurnRecord.actions}). */
    public static final String TURN_ACTIONS = "skillroy.turn.actions";
    /** A session step's failure kind ({@code StepOutcome.failureKind}). */
    public static final String STEP_KIND = "skillroy.step.kind";
    /** How many of a session step's calls were sent ({@code StepOutcome.executed}). */
    public static final String STEP_EXECUTED = "skillroy.step.executed";
    /** Who acted in a session step ({@code StepStart.origin}: PERSON, MODEL, SYSTEM, CLIENT); never the intent. */
    public static final String ORIGIN = "skillroy.origin";
    /** A duration in milliseconds (a turn's, a session step's, a tool call's, a bind event's). */
    public static final String DURATION_MS = "skillroy.duration_ms";
    /** Always {@code tools/call}. */
    public static final String MCP_METHOD_NAME = "mcp.method.name";
    /** The tool's name ({@code ToolCallRecord.tool}); its class in {@code minimal}. */
    public static final String GEN_AI_TOOL_NAME = "gen_ai.tool.name";
    /** The server's session id ({@code ToolCallRecord.sessionId}). */
    public static final String MCP_SESSION_ID = "mcp.session.id";

    // ---------------------------------------------------------------------------------------- record events

    /** A record's {@code id} ({@code preflight}). */
    public static final String RECORD_ID = "skillroy.id";
    /** A list of identifiers of a record ({@code requires}: the capability ids; {@code hooks}: installed ids). */
    public static final String IDS = "skillroy.ids";
    /** A count of a record ({@code requires}: unmet; {@code hooks}: unavailable). */
    public static final String COUNT = "skillroy.count";
    /** The armed stubs' ids (the {@code hooks} record's {@code armed}). */
    public static final String ARMED = "skillroy.armed";
    /** The names of the launch properties checked (never their values). */
    public static final String NAMES = "skillroy.names";
    /** An expectation's path as the plan writes it (unbound); its root in {@code minimal}. */
    public static final String EXPECT_PATH = "skillroy.expect.path";
    /** An expectation's matcher name (the matcher map's first key, or {@code equals}). */
    public static final String MATCHER = "skillroy.matcher";
    /** Whether an expectation held. */
    public static final String HELD = "skillroy.held";
    /** A stored-row check's table. */
    public static final String TABLE = "skillroy.table";
    /** A stored-row check's source. */
    public static final String SOURCE = "skillroy.source";
    /** The data channel that served it. */
    public static final String CHANNEL = "skillroy.channel";
    /** The rows a statement returned (a count). */
    public static final String ROW_COUNT = "skillroy.row_count";
    /** A prompt was expected. */
    public static final String EXPECTED = "skillroy.expected";
    /** The prompt policy's decision. */
    public static final String DECISION = "skillroy.decision";
    /** The fidelity rung requested. */
    public static final String REQUESTED = "skillroy.requested";
    /** The fidelity rung that ran. */
    public static final String ACTUAL = "skillroy.actual";
    /** A resolver's id ({@code resolve} event). */
    public static final String RESOLVER = "skillroy.resolver";
    /** Candidates a resolver offered (a count). */
    public static final String CANDIDATES = "skillroy.candidates";
    /** Candidates rejected (a count). */
    public static final String REJECTED = "skillroy.rejected";
    /** The verifiers' ids, in order. */
    public static final String VERIFIERS = "skillroy.verifiers";
    /** Whether each verifier passed, in the order of {@link #VERIFIERS}. */
    public static final String VERIFIERS_PASSED = "skillroy.verifiers.passed";
    /** A model's stated confidence. */
    public static final String CONFIDENCE = "skillroy.confidence";
    /** The approval state. */
    public static final String APPROVAL = "skillroy.approval";
    /** A value {@code approve} reused. */
    public static final String REUSED = "skillroy.reused";
    /** An oracle's function. */
    public static final String FN = "skillroy.fn";
    /** A fingerprint's hash. */
    public static final String HASH = "skillroy.hash";
    /** A guard's or cross-check's oracle. */
    public static final String ORACLE = "skillroy.oracle";
    /** Whether a cross-check agreed. */
    public static final String AGREED = "skillroy.agreed";
    /** Whether a cross-check or guard was skipped. */
    public static final String SKIPPED = "skillroy.skipped";
    /** The inference record's gateway. */
    public static final String GATEWAY = "skillroy.gateway";
    /** The inference record's contributor ids. */
    public static final String CONTRIBUTORS = "skillroy.contributors";
    /** A record's mode. */
    public static final String MODE = "skillroy.mode";

    // ---------------------------------------------------------------------------------------- minimal

    /**
     * The attributes the {@code minimal} set drops (phase6-contracts.md 10.3): the messages ({@value #MESSAGE}, also
     * the {@code environment} event's, and {@value #STEP_MESSAGE}), {@value #PACK}, {@value #SUITE},
     * {@value #BINDINGS_FILE} and {@value #SLOT}.
     */
    public static final Set<String> MINIMAL_DROPS = Set.of(MESSAGE, STEP_MESSAGE, PACK, SUITE, BINDINGS_FILE, SLOT);

    /**
     * The attributes the {@code minimal} set replaces by a class of the value: {@value #PRIMITIVE} by its namespace
     * (a pack primitive becomes {@code pack}; {@code swing.*} and the other base namespaces stay),
     * {@value #GEN_AI_TOOL_NAME} by {@code verb}, {@code primitive} or {@code workflow}, {@value #STEP_VERB} of a pack
     * verb by {@code verb} (a base verb stays), {@value #EXPECT_PATH} by its root.
     */
    public static final Set<String> MINIMAL_REPLACED = Set.of(PRIMITIVE, GEN_AI_TOOL_NAME, STEP_VERB, EXPECT_PATH);

    /** The attributes the {@code minimal} set writes as the {@code sha256:} of the id. */
    public static final Set<String> MINIMAL_HASHED = Set.of(TEST_CASE_NAME, FAILED_STEP);

    /**
     * The span names {@code minimal} writes without their ids (phase6-contracts.md 10.3: {@code scenario},
     * {@code step}, {@code call}, {@code chat}, {@code tools/call}; as built, Phase 6, TL: {@code agent} too, since its
     * name would otherwise repeat the primitive {@code minimal} replaces), each with the id hashed into
     * {@value #ID_HASH}. The other names have no id ({@code bind}, {@code await}), or one that is no confidential text
     * ({@code skillroy <command>}, {@code session <id>}, {@code turn <n>}).
     */
    public static final Set<String> MINIMAL_SPAN_NAMES = Set.of("scenario", "step", "call", "agent", "chat",
            "tools/call");

    /** The agent's own primitive namespaces: a primitive of another namespace is a pack's. */
    static final Set<String> BASE_NAMESPACES = Set.of("agent", "app", "caps", "data", "evt", "hook", "jvm", "obj",
            "rule", "swing", "sync", "trace", "web");

    private TelemetryAttributes() {
    }

    /** {@code sha256:<64 hex>} of {@code id}'s UTF-8 bytes. */
    public static String hash(String id) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(String.valueOf(id)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * A span's name: {@code <kind> <id>} in {@code standard}, {@code <kind>} in {@code minimal} (the root's
     * {@code skillroy <command>} is the same in both).
     */
    static String spanName(String kind, String id, boolean minimal) {
        return minimal && MINIMAL_SPAN_NAMES.contains(kind) || id == null || id.isEmpty() ? kind : kind + " " + id;
    }

    /** One printable line, cut at {@value #MAX_VALUE_LENGTH} characters. */
    static String cut(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder line = new StringBuilder(Math.min(text.length(), MAX_VALUE_LENGTH + 3));
        for (int i = 0; i < text.length() && line.length() <= MAX_VALUE_LENGTH; i++) {
            char c = text.charAt(i);
            line.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        String out = line.toString();
        return out.length() > MAX_VALUE_LENGTH ? out.substring(0, MAX_VALUE_LENGTH - 3) + "..." : out;
    }

    /** A primitive's class in {@code minimal}: its base namespace, or {@code pack}. */
    static String primitiveClass(String primitive) {
        if (primitive == null) {
            return null;
        }
        int dot = primitive.indexOf('.');
        String namespace = dot < 0 ? primitive : primitive.substring(0, dot);
        return BASE_NAMESPACES.contains(namespace) ? primitive : "pack";
    }

    /** A tool's class in {@code minimal}: {@code workflow}, {@code primitive} or {@code verb}. */
    static String toolClass(String tool) {
        if (tool == null) {
            return null;
        }
        if (tool.startsWith("skillroy_")) {
            return "workflow";
        }
        int underscore = tool.indexOf('_');
        return underscore > 0 && BASE_NAMESPACES.contains(tool.substring(0, underscore)) ? "primitive" : "verb";
    }

    /** A step verb's class in {@code minimal}: a base verb (and {@code verify}) as it is, a pack verb {@code verb}. */
    static String verbClass(String verb) {
        if (verb == null) {
            return null;
        }
        return BaseSteps.VERBS.contains(verb) || "verify".equals(verb) || "call".equals(verb) ? verb : "verb";
    }

    /** An expectation path's root: the text before its first {@code .} or {@code [}. */
    static String pathRoot(String path) {
        if (path == null) {
            return null;
        }
        int end = path.length();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.' || c == '[') {
                end = i;
                break;
            }
        }
        return path.substring(0, end);
    }

    /** {@code pass} for PASS and DEGRADED, {@code fail} otherwise. */
    static String resultStatus(String status) {
        return "PASS".equals(status) || "DEGRADED".equals(status) ? "pass" : "fail";
    }

    /** {@code anthropic} for the provider {@code claude}; the others as given, lower case. */
    static String providerName(String provider) {
        if (provider == null) {
            return null;
        }
        String p = provider.toLowerCase(Locale.ROOT);
        return p.equals("claude") ? "anthropic" : p;
    }

    /** Every attribute name of this class (the tests' allow-list). */
    static List<String> all() {
        List<String> names = new java.util.ArrayList<>();
        for (java.lang.reflect.Field field : TelemetryAttributes.class.getDeclaredFields()) {
            if (field.getType() == String.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && !field.getName().equals("SEMCONV_VERSION")) {
                try {
                    names.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return names;
    }
}
