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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.pack.CheckScope;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PackCapability;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Pack capabilities evaluated into a host's matrix (phase8-contracts.md 9.1, gap 106, decisions D609 to D614): one
 * class carries the rules, so that no host drifts. Every host that merges the matrix calls {@link #evaluate} after the
 * controller-side contributors' merge and before the matrix is archived and {@code requires} is checked: a run
 * ({@code PlanExecutor}), a session's open ({@code ExecutorSession}), {@code skillroy caps --pack} and
 * {@code skillroy re-resolve}.
 *
 * <p>The declarations are checked first, and a violation refuses the host before anything is evaluated
 * ({@link CapabilityMerge.Refused}): the capabilities cannot be read, more than {@value #MAX_PER_PACK}, an id that is
 * not {@code <packId>.<name>} with {@code name} matching {@code [a-z0-9]+([.-][a-z0-9]+)*} or is longer than
 * {@value #MAX_ID_LENGTH} characters, an id declared twice, an id the agent's matrix or a contributor already holds
 * (equality only: the pack's agent module shares the prefix). Then every declared id is evaluated, always (not only
 * those a plan names), with the host's pre-flight context: no bind mode, no inference settings, no gateway, so a pack
 * capability is honoured in {@code replay} (D610). A {@link CheckScope#SUITE} capability is answered from the host's
 * {@link SuiteCache} when the cache holds an answer under (pack id, pack version, id, environment id, agent session).
 *
 * <p>The failure path (D611): an evaluation that answers null is {@code UNAVAILABLE("no answer")}; one that answers
 * another id refuses the host; one that throws an {@link Exception}, a {@link LinkageError} or a
 * {@link StackOverflowError} makes the id {@code UNAVAILABLE("failed: <exception's simple class name>")}, its text
 * (masked, cut at {@value #MAX_ERROR_LENGTH} characters) going to the controller's log at WARNING and to the entry's
 * {@code error} only, never to the matrix; an {@link AgentLostException} propagates; an {@link InterruptedException}
 * re-interrupts the thread and propagates, nothing kept.
 *
 * <p>Stateless; thread-safe. An evaluation runs on the caller's thread with no timeout of the host's (the pack's reads
 * are bounded by their own timeouts) and never under a lock.
 */
public final class PackCapabilities {

    /** The most capabilities one pack declares. */
    public static final int MAX_PER_PACK = 64;

    /** The longest pack capability id. */
    public static final int MAX_ID_LENGTH = 128;

    /** The longest {@code error} an entry carries (the exception's masked {@code toString}). */
    public static final int MAX_ERROR_LENGTH = 500;

    /** The name after {@code <packId>.}. */
    static final Pattern NAME = Pattern.compile("[a-z0-9]+([.-][a-z0-9]+)*");

    /** The reason of an evaluation that answered null. */
    public static final String NO_ANSWER = "no answer";

    private static final System.Logger LOG = System.getLogger(PackCapabilities.class.getName());

    private PackCapabilities() {
    }

    /**
     * One evaluated id, as the {@code capabilities} record's {@code pack} list holds it.
     *
     * @param id the capability's id
     * @param state its state
     * @param reason its reason, or null
     * @param strategy its strategy, or null
     * @param scope its scope
     * @param cached true when the answer came from the suite cache (nothing was evaluated)
     * @param error the masked {@code toString} of the exception a failed evaluation threw, cut at
     *     {@value #MAX_ERROR_LENGTH} characters; null otherwise
     */
    public record Entry(String id, CapabilityState state, String reason, String strategy, CheckScope scope,
                        boolean cached, String error) {

        /** An entry without an error (the contract's six components). */
        public Entry(String id, CapabilityState state, String reason, String strategy, CheckScope scope,
                     boolean cached) {
            this(id, state, reason, strategy, scope, cached, null);
        }

        /**
         * {@code {id, state, reason, strategy, scope: "scenario" | "suite", cached}}, {@code reason} and
         * {@code strategy} null when absent, and {@code error} only when an evaluation failed.
         */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("state", state.name());
            json.put("reason", reason);
            json.put("strategy", strategy);
            json.put("scope", scope == CheckScope.SUITE ? "suite" : "scenario");
            json.put("cached", cached);
            if (error != null) {
                json.put("error", error);
            }
            return json;
        }

        /** The capability the matrix holds for this entry. */
        Capability capability() {
            return new Capability(id, state, reason, strategy);
        }
    }

    /**
     * What an evaluation gives.
     *
     * @param merged the matrix given with the pack's entries added (source {@code pack:<packId>}); the matrix given
     *     itself when the pack declares none
     * @param entries the evaluated ids, sorted by id; empty when the pack declares none
     */
    public record Result(CapabilityMatrix merged, List<Entry> entries) {

        /** Copies the list. */
        public Result {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        /** The evaluated ids, sorted. */
        public List<String> ids() {
            List<String> ids = new ArrayList<>();
            for (Entry entry : entries) {
                ids.add(entry.id());
            }
            return ids;
        }

        /** The entries as the {@code capabilities} record's {@code pack} list holds them. */
        public List<Object> toJson() {
            List<Object> json = new ArrayList<>();
            for (Entry entry : entries) {
                json.add(entry.toJson());
            }
            return json;
        }
    }

    /** What the suite cache keeps for a {@code SUITE} capability. */
    private record Answer(Capability capability, String error) {
    }

    /**
     * {@link #evaluate(CapabilityMatrix, DomainPack, PreflightCheck.PreflightContext, SuiteCache, String, SecretMask)}
     * with no mask: an evaluation's error text is kept as thrown (the host's records mask it when written).
     */
    public static Result evaluate(CapabilityMatrix merged, DomainPack pack, PreflightCheck.PreflightContext ctx,
                                  SuiteCache cache, String agentSession) throws CapabilityMerge.Refused,
            AgentLostException, InterruptedException {
        return evaluate(merged, pack, ctx, cache, agentSession, SecretMask.NONE);
    }

    /**
     * Evaluates the pack's capabilities into {@code merged}.
     *
     * @param merged the agent's matrix after step 0b with the contributors' entries
     *     ({@link CapabilityMerge.Result#merged()})
     * @param pack the running pack
     * @param ctx the host's pre-flight context
     * @param cache the host's suite cache; null = nothing cached
     * @param agentSession the connection's agent session; null = nothing cached (phase8-contracts.md 9.3.2)
     * @param mask applied to an evaluation's error text before it is cut and logged (null = none)
     * @throws CapabilityMerge.Refused a declaration error (phase8-contracts.md 9.1.2), or an answer under another id
     * @throws AgentLostException if an evaluation found the agent gone
     * @throws InterruptedException if an evaluation was interrupted (the thread's flag is set again)
     */
    public static Result evaluate(CapabilityMatrix merged, DomainPack pack, PreflightCheck.PreflightContext ctx,
                                  SuiteCache cache, String agentSession, SecretMask mask)
            throws CapabilityMerge.Refused, AgentLostException, InterruptedException {
        CapabilityMatrix base = merged == null ? new CapabilityMatrix(Map.of()) : merged;
        List<Declared> declared = declarations(base, pack);
        if (declared.isEmpty()) {
            return new Result(base, List.of());
        }
        SecretMask textMask = mask == null ? SecretMask.NONE : mask;
        String packId = pack.id();
        Map<String, Capability> answers = new TreeMap<>();
        List<Entry> entries = new ArrayList<>();
        for (Declared d : declared) {
            SuiteCache.Key key = d.scope() == CheckScope.SUITE && cache != null && agentSession != null
                    ? new SuiteCache.Key(SuiteCache.KIND_CAPABILITY, packId, pack.version(), d.id(), ctx.envId(),
                    agentSession) : null;
            if (key != null && cache.get(key) instanceof Answer answer) {
                Capability c = answer.capability();
                entries.add(new Entry(d.id(), c.state(), c.reason(), c.strategy(), d.scope(), true, answer.error()));
                answers.put(d.id(), c);
                continue;
            }
            Capability answer;
            String error = null;
            try {
                answer = d.capability().evaluate(ctx);
            } catch (AgentLostException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception | LinkageError | StackOverflowError e) {
                error = cut(textMask.apply(String.valueOf(e)));
                LOG.log(System.Logger.Level.WARNING, "pack " + packId + ": the evaluation of its capability '"
                        + d.id() + "' failed: " + error);
                answer = new Capability(d.id(), CapabilityState.UNAVAILABLE, "failed: "
                        + e.getClass().getSimpleName(), null);
            }
            if (answer == null) {
                answer = new Capability(d.id(), CapabilityState.UNAVAILABLE, NO_ANSWER, null);
            } else if (!d.id().equals(answer.id())) {
                throw new CapabilityMerge.Refused("pack " + packId + " answered '" + answer.id()
                        + "' for its capability '" + d.id() + "'");
            }
            if (key != null) {
                cache.put(key, new Answer(answer, error));
            }
            entries.add(new Entry(d.id(), answer.state(), answer.reason(), answer.strategy(), d.scope(), false,
                    error));
            answers.put(d.id(), answer);
        }
        entries.sort(Comparator.comparing(Entry::id));
        return new Result(base.with(answers, CapabilityMatrix.SOURCE_PACK_PREFIX + packId), entries);
    }

    /** An error text cut at {@value #MAX_ERROR_LENGTH} characters (after masking, so that no secret is split). */
    static String cut(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    /** One declared capability, its id and scope read once. */
    private record Declared(PackCapability capability, String id, CheckScope scope) {
    }

    /** The pack's declarations after the rules of phase8-contracts.md 9.1.2, in declaration order. */
    private static List<Declared> declarations(CapabilityMatrix base, DomainPack pack)
            throws CapabilityMerge.Refused {
        String packId = pack.id();
        List<PackCapability> listed;
        try {
            listed = pack.capabilities();
        } catch (RuntimeException e) {
            throw new CapabilityMerge.Refused("pack " + packId + ": its capabilities cannot be read: " + e);
        }
        if (listed == null || listed.isEmpty()) {
            return List.of();
        }
        if (listed.size() > MAX_PER_PACK) {
            throw new CapabilityMerge.Refused("pack " + packId + " declares " + listed.size() + " capabilities; at most "
                    + MAX_PER_PACK);
        }
        List<Declared> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PackCapability capability : listed) {
            String id;
            try {
                id = capability == null ? null : capability.id();
            } catch (RuntimeException e) {
                throw new CapabilityMerge.Refused("pack " + packId + ": its capabilities cannot be read: " + e);
            }
            if (!validId(packId, id)) {
                throw new CapabilityMerge.Refused("pack " + packId + " declares the capability '" + id + "': a pack"
                        + " capability id is <packId>.<name>, name [a-z0-9]+([.-][a-z0-9]+)*");
            }
            if (!seen.add(id)) {
                throw new CapabilityMerge.Refused("pack " + packId + " declares the capability '" + id + "' twice");
            }
            out.add(new Declared(capability, id, scopeOf(capability)));
        }
        for (Declared d : out) {
            if (base.get(d.id()) != null) {
                String source = base.source(d.id());
                if (source != null && source.startsWith(CapabilityMatrix.SOURCE_CONTROLLER_PREFIX)) {
                    throw new CapabilityMerge.Refused("capability '" + d.id() + "' is contributed by "
                            + source.substring(CapabilityMatrix.SOURCE_CONTROLLER_PREFIX.length())
                            + " and declared by pack " + packId);
                }
                if (source != null && source.startsWith(CapabilityMatrix.SOURCE_PACK_PREFIX)) {
                    throw new CapabilityMerge.Refused("capability '" + d.id() + "' is declared by "
                            + source.substring(CapabilityMatrix.SOURCE_PACK_PREFIX.length()) + " and by pack "
                            + packId);
                }
                throw new CapabilityMerge.Refused("capability '" + d.id() + "' is declared by the agent and by pack "
                        + packId);
            }
        }
        return out;
    }

    /** True for {@code <packId>.<name>} with a valid name, at most {@value #MAX_ID_LENGTH} characters. */
    static boolean validId(String packId, String id) {
        if (packId == null || id == null || id.isBlank() || id.length() > MAX_ID_LENGTH
                || !id.startsWith(packId + ".")) {
            return false;
        }
        return NAME.matcher(id.substring(packId.length() + 1)).matches();
    }

    /** The capability's scope; {@link CheckScope#SCENARIO} when it answers null or throws (the restrictive default). */
    private static CheckScope scopeOf(PackCapability capability) {
        try {
            CheckScope scope = capability.scope();
            return scope == null ? CheckScope.SCENARIO : scope;
        } catch (RuntimeException e) {
            return CheckScope.SCENARIO;
        }
    }
}
