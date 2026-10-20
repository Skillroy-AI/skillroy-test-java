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
import ai.skillroy.testjava.scenario.pack.CheckScope;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The pack's pre-flight, one implementation for every host (phase8-contracts.md 9.3.3, gap 112, decisions D618,
 * D619): a run ({@code PlanExecutor}), a session's open ({@code ExecutorSession}), the CLI's {@code author} and the
 * re-resolve job, each with its own context.
 *
 * <p>The checks run in the pack's order and stop at the first failure. A {@link CheckScope#SUITE} check is answered
 * from the host's {@link SuiteCache} when the cache holds an answer for it under (pack id, pack version, check id,
 * environment id, agent session); otherwise it runs and both answers, ok and the failure reason, are kept for the
 * rest of the suite. A {@link CheckScope#SCENARIO} check, and any check when the host has no cache or the connection
 * knows no agent session, runs every time. An exception from a check is its failure ({@code "failed: " + e}, kept
 * like any failure); an {@link InterruptedException} re-interrupts the thread and fails the check
 * {@code "interrupted"} without keeping it; an {@link AgentLostException} propagates and nothing is kept.
 *
 * <p>Stateless. A check never runs under a lock (two racing scenarios may both run it, and the last answer wins); there
 * is no timed wait here: a check is bounded by its own reads, as before (phase1-contracts.md 8.3).
 */
public final class Preflight {

    private Preflight() {
    }

    /**
     * One check's outcome as the {@code preflight} record holds it.
     *
     * @param id the check's id
     * @param ok true when it passed
     * @param reason why it failed, or null
     * @param scope the check's scope ({@link CheckScope#SCENARIO} when it answered none)
     * @param cached true when the answer came from the suite cache (the check did not run)
     */
    public record Record(String id, boolean ok, String reason, CheckScope scope, boolean cached) {

        /**
         * The record's fields: {@code {id, ok, reason}} (byte-identical to the record before Phase 8 for a
         * {@code SCENARIO} check), then {@code scope: "suite"} and {@code cached} for a {@code SUITE} check.
         */
        public Map<String, Object> fields() {
            Map<String, Object> fields = RunLog.fields("id", id, "ok", ok, "reason", reason);
            if (scope == CheckScope.SUITE) {
                fields.put("scope", "suite");
                fields.put("cached", cached);
            }
            return fields;
        }
    }

    /**
     * What the pre-flight found.
     *
     * @param records the checks in order, up to and including the first failure
     * @param failure {@code "pre-flight check <id>: <reason>"} (with {@code " (checked once for this suite)"} when
     *     the failure was answered from the suite cache), {@code "pack <id>: its pre-flight checks cannot be read:
     *     <e>"}, or null when every check passed
     */
    public record Outcome(List<Record> records, String failure) {

        public Outcome {
            records = List.copyOf(records);
        }

        /** The record of the failed check, or null when every check passed or the checks could not be read. */
        public Record failed() {
            return failure == null || records.isEmpty() || records.get(records.size() - 1).ok() ? null
                    : records.get(records.size() - 1);
        }
    }

    /** The suffix of a failure answered from the suite cache. */
    public static final String CACHED_SUFFIX = " (checked once for this suite)";

    /** What the suite cache keeps for a check: its reason, null for ok (the cache refuses a null answer). */
    private record Answer(String reason) {
    }

    /**
     * Runs the pack's pre-flight checks.
     *
     * @param pack the running pack
     * @param ctx the host's context (its connection, data channels, environment id and the pack's settings)
     * @param cache the host's suite cache; null = none, every check runs
     * @param agentSession the agent start the connection is bound to ({@code AgentConnection.agentSession()}); null =
     *     nothing is cached
     * @throws AgentLostException if a check found the agent gone (nothing is kept)
     */
    public static Outcome run(DomainPack pack, PreflightCheck.PreflightContext ctx, SuiteCache cache,
                              String agentSession) throws AgentLostException {
        List<PreflightCheck> checks;
        try {
            checks = pack.preflight();
        } catch (RuntimeException e) {
            return new Outcome(List.of(), "pack " + pack.id() + ": its pre-flight checks cannot be read: " + e);
        }
        if (checks == null || checks.isEmpty()) {
            return new Outcome(List.of(), null);
        }
        List<Record> records = new ArrayList<>();
        for (PreflightCheck check : checks) {
            String id = check.id();
            CheckScope scope = scopeOf(check);
            SuiteCache.Key key = scope == CheckScope.SUITE ? key(pack, id, ctx, cache, agentSession) : null;
            if (key != null && cache.get(key) instanceof Answer answer) {
                records.add(new Record(id, answer.reason() == null, answer.reason(), scope, true));
                if (answer.reason() != null) {
                    return new Outcome(records, "pre-flight check " + id + ": " + answer.reason() + CACHED_SUFFIX);
                }
                continue;
            }
            String reason;
            boolean keep = true;
            try {
                reason = check.check(ctx);
            } catch (AgentLostException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reason = "interrupted";
                keep = false;
            } catch (Exception e) {
                reason = "failed: " + e;
            }
            if (key != null && keep) {
                cache.put(key, new Answer(reason));
            }
            records.add(new Record(id, reason == null, reason, scope, false));
            if (reason != null) {
                return new Outcome(records, "pre-flight check " + id + ": " + reason);
            }
        }
        return new Outcome(Collections.unmodifiableList(records), null);
    }

    /** The check's scope; {@link CheckScope#SCENARIO} when it answers null or throws (the restrictive default). */
    static CheckScope scopeOf(PreflightCheck check) {
        try {
            CheckScope scope = check.scope();
            return scope == null ? CheckScope.SCENARIO : scope;
        } catch (RuntimeException e) {
            return CheckScope.SCENARIO;
        }
    }

    /** The cache key of a {@code SUITE} check, or null when nothing may be cached (no cache, no session, no id). */
    private static SuiteCache.Key key(DomainPack pack, String id, PreflightCheck.PreflightContext ctx,
                                      SuiteCache cache, String agentSession) {
        if (cache == null || agentSession == null || id == null || pack.id() == null) {
            return null;
        }
        return new SuiteCache.Key(SuiteCache.KIND_PREFLIGHT, pack.id(), pack.version(), id, ctx.envId(), agentSession);
    }
}
