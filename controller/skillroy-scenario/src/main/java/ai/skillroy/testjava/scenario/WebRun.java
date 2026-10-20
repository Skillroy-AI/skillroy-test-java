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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PayloadMatch;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.web.WebLib;
import ai.skillroy.testjava.scenario.web.AgentWebPath;
import ai.skillroy.testjava.scenario.web.WebPath;
import ai.skillroy.testjava.scenario.web.WebPathException;
import ai.skillroy.testjava.scenario.web.WebPathOpenException;
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The web side of one run (phase4-contracts.md 6.1, 6.3): which {@link WebPath} serves the run's {@code web.*} calls,
 * how a call reaches it, and how a wait reads the path's own events beside the agent's bus.
 *
 * <ul>
 *   <li><b>Selection</b> ({@link #select}, step 1a of the run sequence, only for a plan with a {@code web.*} call):
 *       {@code agent} pinned is the {@link AgentWebPath}; {@code auto} takes the path with id {@code cdp} that
 *       {@code ServiceLoader<WebPath>} finds through the class loader that loads the run's domain packs, when the
 *       target's {@code web.cdp} capability is OK and its {@code open} returns null, and otherwise the agent path with
 *       the reason; {@code cdp} pinned makes each of those reasons a {@link SelectionFailure}
 *       ({@code PRECONDITION_FAILED}, or {@code ENVIRONMENT} for an attach that threw). The choice is written once as
 *       the {@code web.path} record: {@code {path, reason?, cdp?, libHash, policy}}.</li>
 *   <li><b>Calls</b> ({@link #call}): the chosen path when it serves the primitive, else the agent;
 *       {@link WebPathException} ({@code UNSUPPORTED}) sends that one call to the agent and writes a
 *       {@code web.fallback} record {@code {step, primitive, reason, from}}. A failure of the path's call arrives as
 *       the agent's own {@code AgentRpcException}; any other failure of a non-agent path is a {@link PathFailure}.
 *       Before a call the path serves, the agent's current sequence number is read ({@code evt.query}) and the
 *       highest sequence number among the path's own events, so that a wait after the call starts on both where the
 *       call started; the sequence numbers inside a non-agent path's result are never used. That read is the only one
 *       (amendment A36: the path reads none), and its number replaces both of the result's sequence numbers, so that
 *       a {@code call} record's {@code seq} is in the agent's numbering whichever path served the call; a read the
 *       executor makes on its own reads nothing and takes the highest agent sequence number the run has seen.</li>
 *   <li><b>Waits</b> ({@link #await}): when the chosen path is not the agent's and the topics can match a
 *       {@code web.*} topic, the wait reads {@link WebPath#eventsSince} and the agent's bus in turn, the agent's wait
 *       cut into slices of {@link #SLICE_MS}; the first matching event from either ends it. The path's events have
 *       their own sequence numbers, which never mix with the agent's.</li>
 * </ul>
 *
 * <p>Used by the executor's thread only; not thread-safe.
 */
final class WebRun {

    /** The topics of the agent's web module (phase4-contracts.md 4.3); a wait on any of them reads the path too. */
    static final List<String> TOPICS = List.of("web.load", "web.ready", "web.console", "web.tap", "web.closed",
            "web.browsers.changed");

    /** The id of the controller-side path that {@code auto} prefers and {@code cdp} pins. */
    static final String CDP = "cdp";

    /** One slice of a combined wait: how long the agent is asked to wait before the path's events are read again. */
    static final long SLICE_MS = 100L;

    /** The capability that says the target has a remote-debugging endpoint (the agent's view, P4 4.4). */
    static final String CDP_CAPABILITY = "web.cdp";

    private static final long SEQ_QUERY_TIMEOUT_MS = 5_000L;
    private static final int MAX_PROVIDERS = 100;

    /** A pinned path that cannot be used: the run ends with this kind, message and detail before any step. */
    static final class SelectionFailure extends Exception {

        private static final long serialVersionUID = 1L;

        final FailureKind kind;
        final transient Map<String, Object> detail;

        SelectionFailure(FailureKind kind, String message, Map<String, Object> detail) {
            super(message, null, false, false);
            this.kind = kind;
            this.detail = detail;
        }
    }

    /** A non-agent path failed a call in a way that is not one of the agent's error codes (ERROR / ENVIRONMENT). */
    static final class PathFailure extends Exception {

        private static final long serialVersionUID = 1L;

        PathFailure(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /**
     * One served call.
     *
     * @param result the result as the serving path gave it, except that a non-agent path's sequence numbers are
     *     replaced by {@code agentSince} (A36)
     * @param path the id of the path that served it
     * @param agentSince the agent's sequence number before the call: where a wait on the agent's bus starts (for a
     *     call the agent served, its {@code seq.before}; otherwise read with {@code evt.query} just before the call)
     * @param pathSince the highest sequence number among the path's own events before the call: where a wait on the
     *     path's events starts (0 while the agent path serves the run)
     */
    record Served(RpcResult result, String path, long agentSince, long pathSince) {
    }

    /** The event that ended a wait, and whether it came from the path (its sequence number is then the path's). */
    record Waited(Event event, boolean fromPath) {
    }

    /** What a path lookup found: the path, or why there is none. */
    record Found(WebPath path, String problem) {
    }

    private final AgentConnection agent;
    private final RunLog log;
    private final Clock clock;
    private final ClassLoader loader;
    private final AgentWebPath agentPath;
    private WebPath chosen;
    private WebPath opened;
    private long pathSeq;
    private Map<String, Object> pathRecord;
    private final List<Map<String, Object>> callRecords = new ArrayList<>();
    private final List<Map<String, Object>> fallbackRecords = new ArrayList<>();
    private final Set<String> noted = new HashSet<>();

    /**
     * @param loader the class loader {@code ServiceLoader<WebPath>} uses, or null for the thread's context class
     *     loader at selection time
     */
    WebRun(AgentConnection agent, RunLog log, Clock clock, ClassLoader loader) {
        this.agent = agent;
        this.log = log;
        this.clock = clock;
        this.loader = loader;
        this.agentPath = new AgentWebPath(agent);
        this.chosen = agentPath;
    }

    /** True for a primitive of the web module. */
    static boolean isWeb(String primitive) {
        return primitive != null && primitive.startsWith("web.");
    }

    /** True when a filter of comma-separated globs can match one of the web module's topics. */
    static boolean touchesWeb(String topics) {
        TopicFilter filter = TopicFilter.parse(topics);
        for (String topic : TOPICS) {
            if (filter.matches(topic)) {
                return true;
            }
        }
        return false;
    }

    /** The id of the path that serves the run's web calls ({@code agent} until a selection chose another). */
    String pathId() {
        return chosen.id();
    }

    /** True when a wait on {@code topics} reads the path's events as well as the agent's bus. */
    boolean combines(String topics) {
        return chosen != agentPath && topics != null && touchesWeb(topics);
    }

    // ------------------------------------------------------------------------------------------------ selection

    /**
     * Chooses the run's path (see the class description) and writes the {@code web.path} record. A path that was
     * opened and is not chosen is closed again at once.
     *
     * @throws SelectionFailure when {@code web.path} is {@code cdp} and the CDP path cannot be used
     * @throws AgentLostException when the agent was lost while the path opened
     * @throws InterruptedException when the thread was interrupted while the path opened
     */
    void select(WebSettings settings, CapabilityMatrix caps, WebRunContext ctx)
            throws SelectionFailure, AgentLostException, InterruptedException {
        String policy = settings.path().name().toLowerCase(Locale.ROOT);
        if (settings.path() == WebSettings.Path.AGENT) {
            record(AgentWebPath.ID, policy, "the scenario pins web.path: agent", null);
            return;
        }
        boolean pinned = settings.path() == WebSettings.Path.CDP;
        Found found = find(CDP, loader);
        String problem = found.path() == null ? found.problem() : capabilityProblem(caps);
        if (problem != null) {
            refuse(pinned, policy, FailureKind.PRECONDITION_FAILED, problem, Map.of());
            return;
        }
        WebPath cdp = found.path();
        String reason;
        try {
            reason = cdp.open(agent, settings, ctx);
        } catch (WebPathOpenException e) {
            closeQuietly(cdp);
            Map<String, Object> detail = new LinkedHashMap<>(e.detail());
            detail.put("attach", e.kind().name());
            refuse(pinned, policy, FailureKind.ENVIRONMENT, "the cdp path could not attach (" + e.kind() + "): "
                    + message(e), detail);
            return;
        } catch (AgentLostException | InterruptedException e) {
            closeQuietly(cdp);
            throw e;
        } catch (Exception | LinkageError e) {
            closeQuietly(cdp);
            refuse(pinned, policy, FailureKind.ENVIRONMENT, "the cdp path failed to open: " + e,
                    Map.of("exception", e.toString()));
            return;
        }
        if (reason != null) {
            closeQuietly(cdp);
            refuse(pinned, policy, FailureKind.PRECONDITION_FAILED, "the cdp path is not usable: " + reason,
                    Map.of());
            return;
        }
        chosen = cdp;
        opened = cdp;
        record(cdp.id(), policy, null, openInfo(cdp));
    }

    /**
     * A reason why the CDP path is not taken: under {@code auto} the agent path is chosen and the reason recorded;
     * under {@code cdp} the run ends.
     */
    private void refuse(boolean pinned, String policy, FailureKind kind, String why, Map<String, Object> extra)
            throws SelectionFailure {
        if (!pinned) {
            record(AgentWebPath.ID, policy, why, null);
            return;
        }
        record(null, policy, why, null);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("webPath", policy);
        detail.put("reason", why);
        detail.putAll(extra);
        throw new SelectionFailure(kind, "web.path is cdp, but " + why, detail);
    }

    /** Null when the target's {@code web.cdp} is OK, else what it is ({@code capability web.cdp is …}). */
    private static String capabilityProblem(CapabilityMatrix caps) {
        Capability capability = caps == null ? null : caps.get(CDP_CAPABILITY);
        if (capability == null) {
            return "capability " + CDP_CAPABILITY + " is missing";
        }
        if (capability.state() == CapabilityState.OK) {
            return null;
        }
        String reason = capability.reason();
        return "capability " + CDP_CAPABILITY + " is " + capability.state()
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")");
    }

    /**
     * The first {@code WebPath} with this id that {@code ServiceLoader} finds through {@code loader} (null: the
     * thread's context class loader, else {@code WebPath}'s), or why there is none: none is registered, or the ones
     * that are cannot be loaded. Every lookup instantiates fresh paths, so one run never shares a path with another.
     */
    static Found find(String id, ClassLoader loader) {
        ClassLoader effective = loader != null ? loader : Thread.currentThread().getContextClassLoader();
        if (effective == null) {
            effective = WebPath.class.getClassLoader();
        }
        String error = null;
        try {
            Iterator<WebPath> paths = ServiceLoader.load(WebPath.class, effective).iterator();
            for (int i = 0; i < MAX_PROVIDERS; i++) {
                try {
                    if (!paths.hasNext()) {
                        break;
                    }
                    WebPath path = paths.next();
                    if (id.equals(path.id())) {
                        return new Found(path, null);
                    }
                } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                    if (error == null) {
                        error = message(e);
                    }
                }
            }
        } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
            error = message(e);
        }
        return new Found(null, error != null ? "the web path " + id + " cannot be loaded: " + error
                : "no web path with id " + id + " is on the class path");
    }

    private static Map<String, Object> openInfo(WebPath path) {
        try {
            Map<String, Object> info = path.openInfo();
            return info == null ? null : new LinkedHashMap<>(info);
        } catch (RuntimeException | LinkageError e) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("error", e.toString());
            return failed;
        }
    }

    /** Writes the {@code web.path} record: {@code {path, reason?, cdp?, libHash, policy}}. */
    private void record(String path, String policy, String reason, Map<String, Object> cdp) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("path", path);
        if (reason != null) {
            record.put("reason", reason);
        }
        if (cdp != null) {
            record.put("cdp", cdp);
        }
        record.put("libHash", libHash());
        record.put("policy", policy);
        log.write("web.path", record);
        pathRecord = record;
    }

    private static String libHash() {
        try {
            return WebLib.hash();
        } catch (RuntimeException | LinkageError e) {
            return "unavailable: " + e;
        }
    }

    // ------------------------------------------------------------------------------------------------ calls

    /**
     * Calls a {@code web.*} primitive (see the class description).
     *
     * @param step the step id for a {@code web.fallback} record ({@code cleanup}, or the current step of a read)
     * @param read true for a read the executor makes on its own (a fact, the snapshot surface, evidence): it needs no
     *     sequence numbers, and its fall-back is recorded once per primitive and reason
     * @param agentSeq the highest agent sequence number the run has seen, the floor of {@code agentSince}
     * @param servedBy receives the id of the path the call went to, also when it then fails
     * @throws AgentRpcException with the agent's error code, from either path
     * @throws PathFailure when a non-agent path failed otherwise
     */
    Served call(String step, String primitive, Map<String, Object> params, CallOptions options, boolean read,
                long agentSeq, String[] servedBy) throws AgentRpcException, AgentLostException, PathFailure {
        WebPath path = chosen;
        if (path != agentPath && serves(path, primitive)) {
            long agentSince = read ? agentSeq : agentSeqNow(agentSeq);
            long pathSince = read ? pathSeq : pathSeqNow();
            servedBy[0] = path.id();
            try {
                RpcResult result = path.call(primitive, params, options);
                if (result == null) {
                    throw new PathFailure(primitive + " on the " + path.id() + " path answered no result", null);
                }
                // A36: the executor's read is the only one; its number stands in for the path's sequence numbers.
                RpcResult stamped = new RpcResult(result.value(), result.events(), agentSince, agentSince,
                        result.durationMs(), result.thread());
                return new Served(stamped, path.id(), agentSince, pathSince);
            } catch (WebPathException e) {
                fallback(step, primitive, message(e), path.id(), read);
            } catch (AgentRpcException | AgentLostException | PathFailure e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PathFailure(primitive + " on the " + path.id() + " path was interrupted", e);
            } catch (Exception | LinkageError e) {
                throw new PathFailure(primitive + " failed on the " + path.id() + " path: " + e, e);
            }
        }
        servedBy[0] = AgentWebPath.ID;
        long pathSince = read ? pathSeq : pathSeqNow();
        RpcResult result = agent.call(primitive, params, options);
        return new Served(result, AgentWebPath.ID, result.seqBefore(), pathSince);
    }

    private static boolean serves(WebPath path, String primitive) {
        try {
            return path.serves(primitive);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The {@code web.fallback} record; a read's is written once per primitive and reason. */
    private void fallback(String step, String primitive, String reason, String from, boolean read) {
        if (read && !noted.add("fallback\n" + primitive + "\n" + reason)) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("step", step);
        record.put("primitive", primitive);
        record.put("reason", reason);
        record.put("from", from);
        log.write("web.fallback", record);
        fallbackRecords.add(record);
    }

    /** The agent's current sequence number ({@code evt.query}), at least {@code known}; {@code known} if it fails. */
    private long agentSeqNow(long known) throws AgentLostException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("since", known);
        params.put("limit", 1L);
        try {
            RpcResult result = agent.call("evt.query", params,
                    CallOptions.defaults().withTimeoutMs(SEQ_QUERY_TIMEOUT_MS));
            long last = result.value() instanceof Map<?, ?> value && value.get("lastSeq") instanceof Number n
                    ? n.longValue() : result.seqAfter();
            return Math.max(known, last);
        } catch (AgentRpcException | RuntimeException e) {
            return known;
        }
    }

    /** The highest sequence number among the path's events (0 on the agent path), read now. */
    long pathSeqNow() {
        if (chosen == agentPath) {
            return pathSeq;
        }
        try {
            for (Event event : chosen.eventsSince(pathSeq, "*")) {
                pathSeq = Math.max(pathSeq, event.seq());
            }
        } catch (RuntimeException | LinkageError e) {
            // Best effort: the path's own list could not be read; the last known number stands.
        }
        return pathSeq;
    }

    /**
     * The chosen path's own events after {@code since} on {@code topics} (comma-separated globs), in its order; empty
     * while the agent path serves the run (its events are on the agent's bus) or when the path cannot list them.
     */
    List<Event> pathEventsSince(long since, String topics) {
        if (chosen == agentPath) {
            return List.of();
        }
        try {
            List<Event> events = chosen.eventsSince(since, topics == null || topics.isBlank() ? "*" : topics);
            return events == null ? List.of() : List.copyOf(events);
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /** Lists a {@code web.*} call for the run's summary ({@code run --json}). */
    void noteCall(String step, long index, String primitive, String path, boolean ok) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("step", step);
        record.put("index", index);
        record.put("primitive", primitive);
        record.put("path", path);
        record.put("ok", ok);
        callRecords.add(record);
    }

    // ------------------------------------------------------------------------------------------------ waits

    /**
     * The first event after {@code agentSince} on the agent's bus, or after {@code pathSince} among the path's events,
     * whose topic matches {@code topics} and whose payload holds every {@code match} entry (by text, as
     * {@code sync.await} compares); null when none arrived within {@code timeoutMs}.
     *
     * @throws AgentRpcException when the agent rejects the wait (a malformed topic filter)
     */
    Waited await(String topics, long agentSince, long pathSince, Map<String, Object> match, long timeoutMs)
            throws AgentRpcException, AgentLostException {
        long deadline = clock.millis() + timeoutMs;
        long slices = Math.max(1L, (timeoutMs + SLICE_MS - 1) / SLICE_MS);
        // The clock bounds a real wait; the count bounds one whose clock does not move (a test's).
        for (long i = 0; i < 2 * slices + 10; i++) {
            Event fromPath = firstPathEvent(topics, pathSince, match);
            if (fromPath != null) {
                return new Waited(fromPath, true);
            }
            long remaining = deadline - clock.millis();
            if (remaining <= 0) {
                break;
            }
            Event fromAgent = agent.await(topics, agentSince, match, Duration.ofMillis(Math.max(1L,
                    Math.min(SLICE_MS, remaining))));
            if (fromAgent != null) {
                return new Waited(fromAgent, false);
            }
        }
        Event last = firstPathEvent(topics, pathSince, match);
        return last == null ? null : new Waited(last, true);
    }

    private Event firstPathEvent(String topics, long since, Map<String, Object> match) {
        List<Event> events;
        try {
            events = chosen.eventsSince(since, topics);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        TopicFilter filter = TopicFilter.parse(topics);
        Event first = null;
        for (Event event : events == null ? List.<Event>of() : events) {
            if (event != null && event.seq() > since && filter.matches(event.topic()) && matches(event, match)
                    && (first == null || event.seq() < first.seq())) {
                first = event;
            }
        }
        if (first != null) {
            pathSeq = Math.max(pathSeq, first.seq());
        }
        return first;
    }

    /**
     * Every match entry holds on the payload by the agent's own rule, {@link PayloadMatch} (a scalar equal by text, or
     * {@code {has: scalar}} a list member equal by text; phase8-contracts.md 8.9).
     */
    private static boolean matches(Event event, Map<String, Object> match) {
        return PayloadMatch.matches(event.payload(), match);
    }

    // ------------------------------------------------------------------------------------------------ end of run

    /** True when a non-agent path was opened and is still open. */
    boolean hasOpenedPath() {
        return opened != null;
    }

    /**
     * Asks the opened path to write its trace to {@code file} and says whether it did (phase4-contracts.md 6.4): true
     * only when the file is there afterwards and not empty, so that a path that had no trace to write (its tracing
     * never started, say) is not taken for one that saved it. A file already at {@code file} is removed first, and an
     * empty one the path left is removed again. False, with nothing asked, when no path was opened.
     */
    boolean saveTrace(java.nio.file.Path file) throws Exception {
        WebPath path = opened;
        if (path == null) {
            return false;
        }
        Files.deleteIfExists(file);
        path.saveTrace(file);
        if (Files.isRegularFile(file) && Files.size(file) > 0) {
            return true;
        }
        if (Files.isRegularFile(file)) {
            Files.delete(file);
        }
        return false;
    }

    /**
     * The run's web summary for {@code run --json}: the {@code web.path} record's fields, then {@code calls}
     * ({@code {step, index, primitive, path, ok}} per {@code web.*} call) and {@code fallbacks}; null when no path
     * was selected (the plan has no {@code web.*} call).
     */
    Map<String, Object> summary() {
        if (pathRecord == null) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>(pathRecord);
        summary.put("calls", new ArrayList<>(callRecords));
        summary.put("fallbacks", new ArrayList<>(fallbackRecords));
        return summary;
    }

    /** Closes the opened path once; a failure is written as a {@code web.close} record and never thrown. */
    void close() {
        WebPath path = opened;
        opened = null;
        chosen = agentPath;
        if (path == null) {
            return;
        }
        try {
            path.close();
        } catch (RuntimeException | LinkageError e) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("path", path.id());
            record.put("ok", false);
            record.put("error", e.toString());
            log.write("web.close", record);
        }
    }

    private static void closeQuietly(WebPath path) {
        try {
            path.close();
        } catch (RuntimeException | LinkageError e) {
            // A path that failed to open may fail to close; it holds nothing the run still needs.
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
