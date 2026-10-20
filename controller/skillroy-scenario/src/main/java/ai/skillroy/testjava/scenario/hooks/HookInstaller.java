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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Installs a pack's hook catalogue on a target once per target, idempotent by the catalogue's hash
 * (phase6-contracts.md 5.3, decision D129). Called by {@code PlanExecutor} at every run's step 0b (between the first
 * read of the capability matrix and the {@code requires} check, because {@code requires:} may name a {@code hook.<id>}
 * capability that exists only after the install), by {@code ExecutorSession.open}, by the MCP server at its start and
 * by the CLI command {@code skillroy hooks install}.
 *
 * <ol>
 *   <li>Step 0 (fix wave of the Phase 6 review, FC, decision D231 of the fix wave): whenever the matrix shows
 *       {@code hook.engine} {@code OK}, before anything below can skip, {@code hook.health {}}: an armed stub is ERROR
 *       / ENVIRONMENT when armed stubs are refused (a {@code replay} run: CI must never run against stubbed behaviour,
 *       D129), and so is a failed read; else the ids are recorded, in a {@code skipped} record too ({@code armed},
 *       only when a stub is armed), and a failed read is left out of a skip.</li>
 *   <li>{@link HooksMode#OFF}, or a pack without a catalogue (or without an enabled entry) under {@link HooksMode#AUTO}:
 *       the record {@code {mode, skipped: "off" | "no catalogue"}}, nothing sent but step 0's read. {@code ON} without
 *       one: PRECONDITION_FAILED.</li>
 *   <li>The matrix: {@code hook.engine} absent (the module is not loaded) or not {@code OK}: under {@code AUTO} the
 *       record {@code {mode, skipped: "<reason>"}}, nothing sent; under {@code ON} PRECONDITION_FAILED with the
 *       reason.</li>
 *   <li>Step 0's answer: the same catalogue id and hash installed: {@code alreadyInstalled}, nothing more is sent. The
 *       same id with another hash: the install below with {@code replace: true}. Another id: ERROR / ENVIRONMENT, so
 *       that two suites never silently take turns retransforming one JVM.</li>
 *   <li>{@code hook.install {specs, catalogue: {id, version, hash, application}}} under the executor's diagnostic
 *       timeout plus 60 s; for every hook whose status is not {@code OK}, the fallback is the first {@code seam} whose
 *       primitive the target serves with its capability {@code OK}, recorded per hook.</li>
 *   <li>The record {@code {mode, catalogue: {id, version, hash}, installed, unavailable: [{id, state, reason,
 *       fallback}], armed, touched, ms, otherAgents}}. {@code HOOK_CONFLICT}, {@code HOOK_VERIFY_FAILED}, a transport
 *       error or a timeout from the install is ERROR / ENVIRONMENT with the agent's error as the detail, and so is a
 *       {@code replace: true} install whose answer's {@code replaced.verified} is {@code FAILED} (the implicit
 *       remove's {@code HOOK_VERIFY_FAILED}; Phase 6 security fix, pass 2, P2-L2-F3).</li>
 * </ol>
 *
 * <p>Phase 8 (phase8-contracts.md 6.9): a catalogue with a non-empty {@code deny} is something to install even without
 * an enabled entry (a zero-spec {@code hook.install} delivers it); the install answer's {@code catalogue.deny}, and on
 * the {@code alreadyInstalled} path step 0's {@code hook.health.deny.catalogue}, must hold every pack pattern, else
 * ERROR / ENVIRONMENT "upgrade the agent" (the echo rule, D565); before an install, step 0's {@code hook.health} must
 * carry its top-level {@code deny} when the catalogue has one, so that an agent that would ignore the deny is refused
 * before it retransforms anything (fix wave of the Phase 8 review, L2-F4); an {@code INVALID_PARAMS} on a spec while
 * the catalogue uses {@code target.codeSource} carries the upgrade hint (D560); a skip of a pack whose catalogue has a
 * deny records {@code denyNotDelivered: {patterns, agentOption}}, {@code agentOption} read from
 * {@code rule.list.deny.option} when a {@code rule.*} capability exists (D566); the record gains {@code deny} and
 * {@code deniedNow}.
 *
 * <p>Hooks stay installed after the run: the next run of the same pack finds them ({@code alreadyInstalled}); they go
 * with {@code skillroy hooks remove}, a detach or the target's exit. The caller re-reads the matrix when
 * {@link Result#skipped()} is false (it then holds the {@code hook.<id>} capabilities).
 *
 * <p>Stateless; safe from any thread (it makes its calls on the caller's thread).
 */
public final class HookInstaller {

    /** The capability of the agent's hook engine. */
    public static final String ENGINE = "hook.engine";

    /** The executor's diagnostic timeout ({@code CallRunner.DIAGNOSTIC_TIMEOUT_MS}). */
    static final long DIAGNOSTIC_TIMEOUT_MS = 5_000L;

    /** {@code hook.install}'s budget: the diagnostic timeout plus 60 s (phase6-contracts.md 5.3 step 4). */
    public static final long INSTALL_TIMEOUT_MS = DIAGNOSTIC_TIMEOUT_MS + 60_000L;

    /** {@code hook.health}'s budget, its primitive's default. */
    public static final long HEALTH_TIMEOUT_MS = 10_000L;

    /** {@code rule.list}'s budget, its primitive's default (the {@code agentOption} read of a skip). */
    public static final long RULE_LIST_TIMEOUT_MS = 10_000L;

    /** {@code denyNotDelivered.agentOption} when the agent's option cannot be read (phase8-contracts.md 6.9). */
    public static final String UNKNOWN = "unknown";

    /** The hint appended to a spec's {@code INVALID_PARAMS} when the catalogue uses {@code target.codeSource}. */
    static final String CODE_SOURCE_HINT = "; the catalogue uses target.codeSource, which this agent does not know:"
            + " upgrade the agent";

    private HookInstaller() {
    }

    /**
     * What an install did.
     *
     * @param record the {@code hooks} record's fields (phase6-contracts.md 5.3 step 5), {@code mode} first
     * @param skipped true when nothing was sent beyond what the skip needed (off, no catalogue, no usable engine; step
     *     0's {@code hook.health} read at most): the matrix is unchanged
     * @param alreadyInstalled true when the catalogue was on the target already
     */
    public record Result(Map<String, Object> record, boolean skipped, boolean alreadyInstalled) {

        /** Copies the record. */
        public Result {
            record = new LinkedHashMap<>(Objects.requireNonNull(record, "record"));
        }
    }

    /**
     * The install refused or failed: PRECONDITION_FAILED (hooks {@code ON} without a catalogue or a usable engine) or
     * ENVIRONMENT (an armed stub under {@code replay}, another catalogue installed, an agent error of the install).
     */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final FailureKind kind;
        private final transient Map<String, Object> record;
        private final transient Map<String, Object> detail;

        Refused(FailureKind kind, String message, Map<String, Object> record, Map<String, Object> detail) {
            super(message);
            this.kind = kind;
            this.record = new LinkedHashMap<>(record);
            this.detail = detail == null ? new LinkedHashMap<>() : new LinkedHashMap<>(detail);
        }

        /** {@link FailureKind#PRECONDITION_FAILED} or {@link FailureKind#ENVIRONMENT}. */
        public FailureKind kind() {
            return kind;
        }

        /** The {@code hooks} record's fields: the mode, {@code ok: false} and the error. A copy. */
        public Map<String, Object> record() {
            return new LinkedHashMap<>(record);
        }

        /** The failure's detail (the agent's error, the armed ids, the installed catalogue). A copy. */
        public Map<String, Object> detail() {
            return new LinkedHashMap<>(detail);
        }
    }

    /**
     * Steps 0 to 5 of phase6-contracts.md 5.3 (step 0: the fix wave of the Phase 6 review, FC).
     *
     * @param pack the running pack; its {@link DomainPack#hooks()} is read once
     * @param agent the target's agent
     * @param caps the capability matrix read before the install
     * @param mode the run's switch; null means {@link HooksMode#AUTO}
     * @param refuseArmedStubs true for a {@code replay} run: an armed stub on the target is ERROR / ENVIRONMENT
     * @param clock measures {@code ms}
     * @throws Refused when the install is refused or fails (see the class comment)
     * @throws AgentLostException if the agent is gone
     */
    public static Result install(DomainPack pack, AgentConnection agent, CapabilityMatrix caps, HooksMode mode,
                                 boolean refuseArmedStubs, Clock clock) throws Refused, AgentLostException {
        HooksMode effective = mode == null ? HooksMode.AUTO : mode;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("mode", effective.label());
        if (effective == HooksMode.OFF) {
            HookCatalogue off;
            try {
                off = pack.hooks();
            } catch (RuntimeException e) {
                off = null; // hooks off: a catalogue that cannot be read is not this run's concern
            }
            return skip(record, "off", agent, caps, refuseArmedStubs, off);
        }
        HookCatalogue catalogue;
        try {
            catalogue = pack.hooks();
        } catch (RuntimeException e) {
            throw refused(FailureKind.ENVIRONMENT, record, "pack " + pack.id() + ": its hook catalogue cannot be read: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()), null);
        }
        if (catalogue == null || (catalogue.enabled().isEmpty() && catalogue.deny().isEmpty())) {
            if (effective == HooksMode.ON) {
                throw refused(FailureKind.PRECONDITION_FAILED, record, "hooks ON, but pack " + pack.id() + " has no"
                        + (catalogue == null || catalogue.isEmpty() ? " hook catalogue" : " enabled hook in its"
                        + " catalogue " + catalogue.id()), null);
            }
            return skip(record, "no catalogue", agent, caps, refuseArmedStubs, null);
        }
        String engineProblem = engineProblem(caps);
        if (engineProblem != null) {
            if (effective == HooksMode.ON) {
                throw refused(FailureKind.PRECONDITION_FAILED, record, "hooks ON, but " + engineProblem, null);
            }
            // Step 0 reads nothing without hook.engine OK: no stub can be armed through an engine that is not.
            return skip(record, engineProblem, agent, caps, refuseArmedStubs, catalogue);
        }
        Map<?, ?> health = health(agent, caps, record, refuseArmedStubs, false);
        List<String> armed = strings(health.get("stubs"));
        List<String> deniedNow = deniedNow(health);
        boolean replace = false;
        if (health.get("catalogue") instanceof Map<?, ?> installed && installed.get("id") != null) {
            String installedId = String.valueOf(installed.get("id"));
            String installedHash = installed.get("hash") == null ? null : String.valueOf(installed.get("hash"));
            if (installedId.equals(catalogue.id()) && catalogue.hash().equals(installedHash)) {
                record.put("alreadyInstalled", true);
                record.put("catalogue", catalogueRef(catalogue, false));
                record.put("armed", armed);
                denyKeys(record, catalogue, deniedNow);
                Object held = health.get("deny") instanceof Map<?, ?> deny ? deny.get("catalogue") : null;
                requireEcho(catalogue, held, record);
                return new Result(record, false, true);
            }
            if (!installedId.equals(catalogue.id())) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("installed", installedId);
                detail.put("wanted", catalogue.id());
                throw refused(FailureKind.ENVIRONMENT, record, "catalogue " + installedId + " is installed on this"
                        + " target; `skillroy hooks remove` first", detail);
            }
            replace = true;
        }
        if (!catalogue.deny().isEmpty() && !(health.get("deny") instanceof Map<?, ?>)) {
            // Fix wave (Phase 8 review, FD, L2-F4): step 0's hook.health already tells an agent that knows the deny
            // (its top-level deny is always present, P8 6.6) from one that ignores it; refuse before hook.install,
            // which would otherwise retransform the denied classes first. The answer's echo stays the second line.
            Map<String, Object> early = new LinkedHashMap<>(record);
            early.put("catalogue", catalogueRef(catalogue, true));
            denyKeys(early, catalogue, deniedNow);
            requireEcho(catalogue, null, early);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        List<Object> specs = new ArrayList<>();
        for (HookSpec spec : catalogue.toSpecs()) {
            specs.add(spec.toJson());
        }
        params.put("specs", specs);
        params.put("catalogue", catalogue.descriptor());
        if (replace) {
            params.put("replace", true);
        }
        long t0 = clock.millis();
        Map<?, ?> answer;
        try {
            answer = call(agent, "hook.install", params, INSTALL_TIMEOUT_MS, record);
        } catch (Refused e) {
            throw withCodeSourceHint(e, catalogue);
        }
        long ms = Math.max(0, clock.millis() - t0);
        if (replace && answer.get("replaced") instanceof Map<?, ?> replaced
                && "FAILED".equals(String.valueOf(replaced.get("verified")))) {
            // Phase 6 security fix, pass 2, P2-L2-F3: the implicit remove of the installed version left a reference
            // to Spy in a restored class; that is the remove's HOOK_VERIFY_FAILED, ERROR / ENVIRONMENT as step 5 says,
            // whatever the new version installed.
            record.put("catalogue", catalogueRef(catalogue, true));
            record.put("replaced", true);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", ErrorCodes.HOOK_VERIFY_FAILED);
            error.put("message", "the remove of the installed version (replace: true) left a reference to Spy in a"
                    + " restored class");
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("primitive", "hook.install");
            detail.put("error", error);
            detail.put("restored", replaced.get("restored"));
            throw refused(FailureKind.ENVIRONMENT, record, "hook.install failed: "
                    + ErrorCodes.name(ErrorCodes.HOOK_VERIFY_FAILED) + ": " + error.get("message")
                    + "; `skillroy hooks status`, then `skillroy hooks remove`", detail);
        }
        Map<String, Object> echoRecord = new LinkedHashMap<>(record);
        echoRecord.put("catalogue", catalogueRef(catalogue, true));
        denyKeys(echoRecord, catalogue, deniedNow);
        requireEcho(catalogue, answer.get("catalogue") instanceof Map<?, ?> echoed ? echoed.get("deny") : null,
                echoRecord);
        List<String> installed = new ArrayList<>();
        List<Object> unavailable = new ArrayList<>();
        Map<String, PrimitiveDescriptor> schema = null;
        if (answer.get("hooks") instanceof List<?> statuses) {
            for (Object s : statuses) {
                if (!(s instanceof Map<?, ?> status) || status.get("id") == null) {
                    continue;
                }
                String id = String.valueOf(status.get("id"));
                if (CapabilityState.OK.name().equals(String.valueOf(status.get("state")))) {
                    installed.add(id);
                    continue;
                }
                if (schema == null) {
                    schema = schema(agent);
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("state", status.get("state") == null ? null : String.valueOf(status.get("state")));
                entry.put("reason", status.get("reason") == null ? null : String.valueOf(status.get("reason")));
                HookEntry hook = catalogue.entry(id);
                entry.put("fallback", hook == null ? null : fallback(hook, caps, schema));
                unavailable.add(entry);
            }
        }
        record.put("catalogue", catalogueRef(catalogue, true));
        if (replace) {
            record.put("replaced", true);
        }
        record.put("installed", installed);
        record.put("unavailable", unavailable);
        record.put("armed", armed);
        record.put("touched", answer.get("touched") instanceof List<?> touched ? (long) touched.size() : 0L);
        record.put("ms", ms);
        record.put("otherAgents", strings(answer.get("otherAgents")));
        denyKeys(record, catalogue, deniedNow);
        return new Result(record, false, false);
    }

    /** The record's {@code deny} (the pack's patterns, when it has some) and {@code deniedNow} (when not empty). */
    private static void denyKeys(Map<String, Object> record, HookCatalogue catalogue, List<String> deniedNow) {
        if (!catalogue.deny().isEmpty()) {
            record.put("deny", new ArrayList<>(catalogue.deny()));
        }
        if (!deniedNow.isEmpty()) {
            record.put("deniedNow", deniedNow);
        }
    }

    /**
     * A skip of steps 1 and 2: step 0's read first, then the record {@code {mode, skipped: reason}} with
     * {@code armed} when a stub is armed (and not refused), and {@code denyNotDelivered} when the pack's catalogue has
     * a deny the skip does not deliver (phase8-contracts.md 6.9).
     *
     * @param catalogue the pack's catalogue when it was read, else null
     */
    private static Result skip(Map<String, Object> record, String reason, AgentConnection agent, CapabilityMatrix caps,
                               boolean refuseArmedStubs, HookCatalogue catalogue)
            throws Refused, AgentLostException {
        Map<?, ?> health = health(agent, caps, record, refuseArmedStubs, true);
        record.put("skipped", reason);
        List<String> armed = health == null ? List.of() : strings(health.get("stubs"));
        if (!armed.isEmpty()) {
            record.put("armed", armed);
        }
        if (catalogue != null && !catalogue.deny().isEmpty()) {
            Map<String, Object> notDelivered = new LinkedHashMap<>();
            notDelivered.put("patterns", new ArrayList<>(catalogue.deny()));
            notDelivered.put("agentOption", agentOption(agent, caps));
            record.put("denyNotDelivered", notDelivered);
        }
        return new Result(record, true, false);
    }

    /**
     * {@code denyNotDelivered.agentOption} (phase8-contracts.md 6.9, D566): the agent option {@code hooks.deny} as
     * {@code rule.list.deny.option} answers it, read once when the matrix shows a {@code rule.*} capability;
     * {@value #UNKNOWN} without one (and then nothing is called), when the answer has no {@code deny} key (an agent
     * that predates the option ignores it silently), or when the read fails.
     */
    static Object agentOption(AgentConnection agent, CapabilityMatrix caps) throws AgentLostException {
        boolean rules = false;
        if (caps != null) {
            for (String id : caps.all().keySet()) {
                rules |= id.startsWith("rule.");
            }
        }
        if (!rules) {
            return UNKNOWN;
        }
        Object value;
        try {
            value = agent.call("rule.list", Map.of(), CallOptions.defaults().withTimeoutMs(RULE_LIST_TIMEOUT_MS))
                    .value();
        } catch (AgentRpcException e) {
            return UNKNOWN;
        }
        if (value instanceof Map<?, ?> list && list.get("deny") instanceof Map<?, ?> deny
                && deny.get("option") instanceof List<?>) {
            return strings(deny.get("option"));
        }
        return UNKNOWN;
    }

    /**
     * The echo rule (phase8-contracts.md 2.1, 6.9, D565): a pack catalogue with a non-empty deny needs {@code answered}
     * (the install answer's {@code catalogue.deny}, or step 0's {@code hook.health.deny.catalogue}) to be a list
     * holding every pack pattern; else ERROR / ENVIRONMENT, detail {@code {missing, answered}}.
     */
    private static void requireEcho(HookCatalogue catalogue, Object answered, Map<String, Object> record)
            throws Refused {
        if (catalogue.deny().isEmpty()) {
            return;
        }
        List<String> held = answered instanceof List<?> ? strings(answered) : null;
        List<String> missing = new ArrayList<>();
        for (String pattern : catalogue.deny()) {
            if (held == null || !held.contains(pattern)) {
                missing.add(pattern);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("missing", missing);
        detail.put("answered", held);
        throw refused(FailureKind.ENVIRONMENT, record, "the agent does not honour the catalogue's deny list ("
                + String.join(", ", missing) + "); upgrade the agent", detail);
    }

    /** The ids of the hooks step 0's {@code hook.health} marks {@code deniedNow: true}, sorted. */
    private static List<String> deniedNow(Map<?, ?> health) {
        List<String> out = new ArrayList<>();
        if (health != null && health.get("hooks") instanceof Map<?, ?> hooks) {
            for (Map.Entry<?, ?> e : hooks.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> h && Boolean.TRUE.equals(h.get("deniedNow"))) {
                    out.add(String.valueOf(e.getKey()));
                }
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * The old-agent hint (phase8-contracts.md 6.9, D560): an {@code INVALID_PARAMS} from {@code hook.install} whose
     * {@code data.param} starts with {@code specs[} while a sent spec carries {@code codeSource}, with the upgrade hint
     * appended; any other refusal as it is.
     */
    private static Refused withCodeSourceHint(Refused e, HookCatalogue catalogue) {
        boolean qualified = catalogue.toSpecs().stream().anyMatch(spec -> spec.target().codeSource() != null);
        Object error = e.detail().get("error");
        if (!qualified || !(error instanceof Map<?, ?> rpc)) {
            return e;
        }
        Object code = rpc.get("code");
        Object param = rpc.get("data") instanceof Map<?, ?> data ? data.get("param") : null;
        if (!(code instanceof Number n) || n.intValue() != ErrorCodes.INVALID_PARAMS
                || !String.valueOf(param).startsWith("specs[")) {
            return e;
        }
        Map<String, Object> record = e.record();
        String message = e.getMessage() + CODE_SOURCE_HINT;
        record.put("error", message);
        return new Refused(e.kind(), message, record, e.detail());
    }

    /**
     * Step 0 (phase6-contracts.md 5.3, fix wave of the Phase 6 review, FC): {@code hook.health {}} when the matrix
     * shows {@code hook.engine} {@code OK}, else null (nothing read). An armed stub refused under
     * {@code refuseArmedStubs} is ERROR / ENVIRONMENT, and so is a failed read, unless the install is being skipped and
     * armed stubs are not refused (the read is then left out: null).
     *
     * @param skipping true when steps 1 or 2 end the install
     */
    private static Map<?, ?> health(AgentConnection agent, CapabilityMatrix caps, Map<String, Object> record,
                                    boolean refuseArmedStubs, boolean skipping) throws Refused, AgentLostException {
        if (engineProblem(caps) != null) {
            return null;
        }
        Map<?, ?> health;
        try {
            health = call(agent, "hook.health", Map.of(), HEALTH_TIMEOUT_MS, record);
        } catch (Refused e) {
            if (skipping && !refuseArmedStubs) {
                return null;
            }
            throw e;
        }
        List<String> armed = strings(health.get("stubs"));
        if (!armed.isEmpty() && refuseArmedStubs) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("armed", armed);
            throw refused(FailureKind.ENVIRONMENT, record, "a stub is armed on the target: " + String.join(", ",
                    armed) + "; `skillroy hooks status`", detail);
        }
        return health;
    }

    /**
     * Why the matrix offers no usable hook engine, or null when {@code hook.engine} is {@code OK}: {@code "hook.engine
     * is absent: the hooks module is not loaded"} or {@code "hook.engine is UNAVAILABLE: <reason>"}.
     */
    public static String engineProblem(CapabilityMatrix caps) {
        Capability engine = caps == null ? null : caps.get(ENGINE);
        if (engine == null) {
            return ENGINE + " is absent: the hooks module is not loaded";
        }
        if (engine.state() != CapabilityState.OK) {
            return ENGINE + " is " + engine.state().name() + (engine.reason() == null || engine.reason().isBlank()
                    ? "" : ": " + engine.reason());
        }
        return null;
    }

    /**
     * {@code "seam:<primitive>"} of the first seam fallback whose primitive the target serves with its capability
     * {@code OK} (a primitive without a capability is served when the target's catalogue lists it), or null.
     */
    static String fallback(HookEntry hook, CapabilityMatrix caps, Map<String, PrimitiveDescriptor> schema) {
        for (HookEntry.Fallback fallback : hook.fallbacks()) {
            if (fallback instanceof HookEntry.Fallback.Seam seam && served(seam.primitive(), caps, schema)) {
                return seam.toString();
            }
        }
        return null;
    }

    private static boolean served(String primitive, CapabilityMatrix caps, Map<String, PrimitiveDescriptor> schema) {
        PrimitiveDescriptor descriptor = schema == null ? null : schema.get(primitive);
        if (descriptor == null) {
            // Without the target's catalogue, a primitive that names a capability of the same id is served when the
            // capability is OK (the lending module's primitives mostly do).
            return caps != null && caps.isOk(primitive);
        }
        String capability = descriptor.capabilityId();
        return capability == null || capability.isBlank() || (caps != null && caps.isOk(capability));
    }

    private static Map<String, PrimitiveDescriptor> schema(AgentConnection agent) throws AgentLostException {
        Map<String, PrimitiveDescriptor> out = new LinkedHashMap<>();
        try {
            List<PrimitiveDescriptor> descriptors = agent.schema();
            for (PrimitiveDescriptor d : descriptors == null ? List.<PrimitiveDescriptor>of() : descriptors) {
                if (d != null) {
                    out.putIfAbsent(d.name(), d);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    private static Map<String, Object> catalogueRef(HookCatalogue catalogue, boolean withVersion) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", catalogue.id());
        if (withVersion) {
            ref.put("version", (long) catalogue.version());
        }
        ref.put("hash", catalogue.hash());
        return ref;
    }

    private static Map<?, ?> call(AgentConnection agent, String primitive, Map<String, Object> params, long timeoutMs,
                                  Map<String, Object> record) throws Refused, AgentLostException {
        Object value;
        try {
            value = agent.call(primitive, params, CallOptions.defaults().withTimeoutMs(timeoutMs)).value();
        } catch (AgentRpcException e) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("primitive", primitive);
            detail.put("error", e.error().toJson());
            throw refused(FailureKind.ENVIRONMENT, record, primitive + " failed: " + ErrorCodes.name(e.error().code())
                    + ": " + e.error().message(), detail);
        }
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Refused refused(FailureKind kind, Map<String, Object> record, String message,
                                   Map<String, Object> detail) {
        Map<String, Object> failed = new LinkedHashMap<>(record);
        failed.put("ok", false);
        failed.put("error", message);
        if (detail != null && !detail.isEmpty()) {
            failed.put("detail", detail);
        }
        return new Refused(kind, message, failed, detail);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}
