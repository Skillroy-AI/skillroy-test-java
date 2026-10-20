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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The answers of {@code SUITE}-scoped pre-flight checks and pack capabilities for one suite (phase8-contracts.md
 * 9.3.2, gap 112, decisions D616, D617). A host owns one per suite (the JUnit glue per stream, the CLI per
 * invocation, the MCP server per server) and hands it to its runs and sessions through {@link RunOptions#suite()} and
 * {@link SessionOptions#suite()}; null there means none, and every item then runs per scenario.
 *
 * <p>An answer is kept under (kind, pack id, pack version, check or capability id, environment id, agent session):
 * a new agent start, or another target, is another key, so the item runs again. A connection that knows no agent
 * session ({@code AgentConnection.agentSession()} null) is never cached for. Bounded: beyond {@code maxEntries}
 * nothing more is kept (every further item runs per scenario); no expiry within a suite; never written to disk; it
 * lives with its host. {@link #clear()} forgets everything.
 *
 * <p>Thread-safe. A check or an evaluation never runs under the cache's lock: two racing scenarios may both run an
 * item, and the last answer wins (harmless).
 */
public final class SuiteCache {

    /** The bound of {@link #create()}. */
    public static final int DEFAULT_MAX_ENTRIES = 1000;

    /** The largest bound {@link #create(int)} accepts. */
    static final int MAX_MAX_ENTRIES = 100_000;

    /** {@link Key#kind()} of a pre-flight check's answer. */
    static final String KIND_PREFLIGHT = "preflight";

    /** {@link Key#kind()} of a pack capability's answer. */
    static final String KIND_CAPABILITY = "capability";

    /**
     * What an answer is kept under.
     *
     * @param kind {@link #KIND_PREFLIGHT} or {@link #KIND_CAPABILITY}
     * @param packId the running pack's id
     * @param packVersion the running pack's version (an overlay's composite version included)
     * @param id the check's or the capability's id
     * @param envId the run's environment id
     * @param agentSession the agent start the connection is bound to (the {@code /ping} reply's {@code sessionId});
     *     never null: null is never cached for
     */
    record Key(String kind, String packId, String packVersion, String id, String envId, String agentSession) {

        Key {
            if (!KIND_PREFLIGHT.equals(kind) && !KIND_CAPABILITY.equals(kind)) {
                throw new IllegalArgumentException("a suite cache key's kind is " + KIND_PREFLIGHT + " or "
                        + KIND_CAPABILITY + ", not " + kind);
            }
            Objects.requireNonNull(packId, "packId");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(agentSession, "agentSession: a connection without a session is never cached for");
        }
    }

    private final int maxEntries;
    private final Map<Key, Object> answers = new ConcurrentHashMap<>();
    /** Taken only to keep the bound exact while storing; never while a check or an evaluation runs. */
    private final Object storeLock = new Object();

    private SuiteCache(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /** A cache of {@link #DEFAULT_MAX_ENTRIES} entries. */
    public static SuiteCache create() {
        return new SuiteCache(DEFAULT_MAX_ENTRIES);
    }

    /**
     * A cache of at most {@code maxEntries} entries.
     *
     * @throws IllegalArgumentException unless {@code 1 <= maxEntries <= 100000}
     */
    public static SuiteCache create(int maxEntries) {
        if (maxEntries < 1 || maxEntries > MAX_MAX_ENTRIES) {
            throw new IllegalArgumentException("a suite cache holds 1 to " + MAX_MAX_ENTRIES + " entries, not "
                    + maxEntries);
        }
        return new SuiteCache(maxEntries);
    }

    /** The number of answers kept. */
    public int size() {
        return answers.size();
    }

    /** The bound this cache was created with. */
    int maxEntries() {
        return maxEntries;
    }

    /** Forgets everything: every {@code SUITE} item runs again. */
    public void clear() {
        answers.clear();
    }

    /** The answer kept under {@code key}, or null when there is none. */
    Object get(Key key) {
        return answers.get(Objects.requireNonNull(key, "key"));
    }

    /**
     * Keeps {@code answer} under {@code key}, replacing an earlier one (the last answer wins); nothing is kept for a
     * new key once the cache holds {@code maxEntries} answers.
     *
     * @param answer never null (a pre-flight check's ok is the caller's own marker)
     */
    void put(Key key, Object answer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(answer, "answer");
        synchronized (storeLock) {
            if (answers.size() >= maxEntries && !answers.containsKey(key)) {
                return;
            }
            answers.put(key, answer);
        }
    }

    @Override
    public String toString() {
        return "SuiteCache[" + answers.size() + "/" + maxEntries + "]";
    }
}
