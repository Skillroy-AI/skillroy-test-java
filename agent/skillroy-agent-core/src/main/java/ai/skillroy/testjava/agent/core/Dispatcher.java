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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON-RPC 2.0 dispatch (phase1-contracts.md 5): parses single and batch requests, applies the safety gates in
 * contract order (method, params, read-only, confirm, readiness, capability), runs the primitive under its thread
 * policy with the effective timeout and the call's correlation id bound, and builds the result envelope or error.
 *
 * <p>A batch holds at most {@link Protocol#MAX_BATCH} elements; a larger one is answered with one
 * {@code INVALID_REQUEST} error and none of its elements runs, which bounds the work and the reply one request causes.
 *
 * <p>Gates added by the hardening wave: a primitive whose capability was withdrawn answers
 * {@code CAPABILITY_UNAVAILABLE} "capability withdrawn" (contract amendment A18); an {@code APP} primitive while the
 * application thread is not available yet runs on WORKER, or answers {@code NOT_READY} if it is mutating (A19); and a
 * CALLER, WORKER or APP primitive whose earlier call timed out while its body was still running, and is still
 * running, answers {@code BUSY} until that call has ended (finding S30). The last gate caps the threads, and the host
 * resources such as pooled connections, that calls blocked in uninterruptible host code can hold at one per
 * primitive, however often a controller retries.
 *
 * <p>Secret parameters (contract amendment A34): a request may name, in {@code _meta.secretParams}, top-level params
 * whose values are secrets. The primitive receives them unchanged; the error the call answers with, the dispatcher's
 * log lines about the call (an unexpected failure, a timeout that leaves the call running), and (through the call's
 * {@link CallScope}) the agent log lines written while the call runs never hold their values ({@link SecretParams}
 * states the rule). The events published while the call runs keep their payloads: they are what the call does.
 *
 * <p>Safe for concurrent requests; the only state of its own is the table of abandoned calls, guarded by its lock.
 * Batch elements run sequentially.
 */
final class Dispatcher {

    /** Upper bound of {@code _meta.timeoutMs}. */
    static final long MAX_TIMEOUT_MS = 120000L;
    private static final int STACK_FRAMES = 10;
    private static final String DISABLE = "agent.disable";
    static final String APP_UNAVAILABLE = "application thread not available yet";

    /** Lets a primitive derive its default dispatcher timeout from its arguments (sync.await). */
    interface DispatchTimeout {
        /** The dispatcher timeout when {@code _meta.timeoutMs} is absent. */
        long dispatchTimeoutMs(JsonView args);
    }

    private static final int RUNNING = 0;
    private static final int ABANDONED = 1;
    private static final int DONE = 2;

    private final AgentRuntime runtime;
    private final CoreContext ctx;
    private final ExecutorService callerPool;
    /** Calls that timed out while their body kept running, per primitive name; guarded by itself. */
    private final Map<String, List<Call>> abandoned = new HashMap<String, List<Call>>();

    Dispatcher(AgentRuntime runtime, CoreContext ctx, ExecutorService callerPool) {
        this.runtime = runtime;
        this.ctx = ctx;
        this.callerPool = callerPool;
    }

    /**
     * Handles one {@code POST /rpc} body.
     *
     * @return the response (a map, or a list for a batch), or null when nothing is to be sent (notifications only)
     */
    Object handleBody(String body) {
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (JsonException e) {
            return response(null, new RpcError(ErrorCodes.PARSE_ERROR, "parse error: " + e.getMessage(), null));
        }
        if (parsed instanceof List) {
            List<?> batch = (List<?>) parsed;
            if (batch.isEmpty()) {
                return response(null, new RpcError(ErrorCodes.INVALID_REQUEST, "empty batch", null));
            }
            if (batch.size() > Protocol.MAX_BATCH) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("elements", Long.valueOf(batch.size()));
                data.put("maxBatch", Long.valueOf(Protocol.MAX_BATCH));
                return response(null, new RpcError(ErrorCodes.INVALID_REQUEST, "a batch may hold at most "
                        + Protocol.MAX_BATCH + " elements; none was run", data));
            }
            List<Object> responses = new ArrayList<Object>();
            for (Object element : batch) {
                Map<String, Object> one = handleOne(element);
                if (one != null) {
                    responses.add(one);
                }
            }
            return responses.isEmpty() ? null : responses;
        }
        return handleOne(parsed);
    }

    /** Handles one request object; null for a notification. */
    Map<String, Object> handleOne(Object request) {
        if (!(request instanceof Map)) {
            return response(null, new RpcError(ErrorCodes.INVALID_REQUEST, "a request must be a JSON object", null));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> req = (Map<String, Object>) request;
        boolean hasId = req.containsKey("id");
        Object id = req.get("id");
        if (hasId && id != null && !(id instanceof String) && !(id instanceof Number)) {
            return response(null, new RpcError(ErrorCodes.INVALID_REQUEST, "id must be a string, number or null",
                    null));
        }
        if (!"2.0".equals(req.get("jsonrpc"))) {
            return response(id, new RpcError(ErrorCodes.INVALID_REQUEST, "jsonrpc must be \"2.0\"", null));
        }
        Object method = req.get("method");
        if (!(method instanceof String)) {
            return response(id, new RpcError(ErrorCodes.INVALID_REQUEST, "method must be a string", null));
        }
        Object outcome = invoke((String) method, req.get("params"));
        if (!hasId) {
            return null;
        }
        return outcome instanceof RpcError ? response(id, (RpcError) outcome) : result(id, outcome);
    }

    /**
     * Runs one call; returns the result's JSON map or an {@link RpcError}. Never throws. An error answer, and the log
     * line of an unexpected failure, never hold the values of the params the request named in
     * {@code _meta.secretParams} (amendment A34, {@link SecretParams}).
     */
    Object invoke(String method, Object rawParams) {
        // A malformed list is refused in invokeChecked, in contract order, before anything runs.
        SecretParams secrets = SecretParams.lenient(method, rawParams);
        try {
            Object outcome = invokeChecked(method, rawParams);
            return outcome instanceof RpcError ? secrets.redact((RpcError) outcome) : outcome;
        } catch (RpcException e) {
            return secrets.redact(e.error());
        } catch (Throwable t) {
            if (secrets.isEmpty()) {
                ctx.log.warn("dispatch of " + method + " failed", t);
            } else {
                // The Throwable itself would bring its message into the log unredacted.
                ctx.log.warn(secrets.redact("dispatch of " + method + " failed: " + t.getClass().getName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage())));
            }
            return secrets.redact(internalError(t));
        }
    }

    private Object invokeChecked(String method, Object rawParams) {
        Primitive primitive = ctx.primitives.find(method);
        PrimitiveSpec spec = ctx.primitives.specOf(method);
        if (primitive == null || spec == null) {
            return error(ErrorCodes.METHOD_NOT_FOUND, "unknown method " + method, "method", method);
        }
        if (rawParams != null && !(rawParams instanceof Map)) {
            return error(ErrorCodes.INVALID_PARAMS, "params must be an object", "primitive", method);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = rawParams == null
                ? new LinkedHashMap<String, Object>() : (Map<String, Object>) rawParams;
        Object rawMeta = params.get("_meta");
        if (rawMeta != null && !(rawMeta instanceof Map)) {
            return error(ErrorCodes.INVALID_PARAMS, "_meta must be an object", "primitive", method);
        }
        SecretParams secrets = SecretParams.of(method, params);
        @SuppressWarnings("unchecked")
        JsonView meta = JsonView.of((Map<String, Object>) rawMeta);
        Map<String, Object> args = new LinkedHashMap<String, Object>(params);
        args.remove("_meta");
        JsonView argsView = JsonView.of(args);

        boolean exempt = DISABLE.equals(method);
        if (spec.mutating() && ctx.readOnly() && !exempt) {
            return error(ErrorCodes.READ_ONLY, method + " changes the target and the agent is read-only",
                    "primitive", method);
        }
        if (spec.script() && ctx.readOnly() && !exempt) {
            // phase6-contracts.md 12.9, D156 (security fix S42): a caller's expression evaluated in the target may
            // change it, whatever the primitive's own effect.
            return error(ErrorCodes.READ_ONLY, method + " evaluates a caller's expression in the target and the agent"
                    + " is read-only", "primitive", method);
        }
        if (spec.destructive() && !meta.bool("confirm", false)) {
            return error(ErrorCodes.CONFIRM_REQUIRED, method + " is destructive and needs _meta.confirm=true",
                    "primitive", method);
        }
        if (spec.mutating() && !runtime.isReady() && !exempt) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("primitive", method);
            data.put("reason", runtime.readinessReason());
            return new RpcError(ErrorCodes.NOT_READY, "the target is not ready for " + method, data);
        }
        String capabilityId = spec.capabilityId();
        if (capabilityId != null) {
            Capability capability = ctx.capabilities.get(capabilityId);
            if (ctx.capabilities.isPending(capabilityId)) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("primitive", method);
                data.put("capability", capabilityId);
                data.put("reason", capability == null ? CoreCapabilityRegistry.PENDING_REASON : capability.reason());
                return new RpcError(ErrorCodes.NOT_READY, "capability " + capabilityId + " has not been probed yet",
                        data);
            }
            if (capability != null && capability.state() == CapabilityState.UNAVAILABLE) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("primitive", method);
                data.put("capability", capabilityId);
                data.put("reason", capability.reason());
                return new RpcError(ErrorCodes.CAPABILITY_UNAVAILABLE, "capability " + capabilityId
                        + " is UNAVAILABLE", data);
            }
        }
        if (capabilityId != null && ctx.capabilities.get(capabilityId) == null
                && ctx.capabilities.isWithdrawn(capabilityId)) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("primitive", method);
            data.put("capability", capabilityId);
            data.put("reason", "capability withdrawn");
            return new RpcError(ErrorCodes.CAPABILITY_UNAVAILABLE, "capability " + capabilityId
                    + " was withdrawn", data);
        }
        ThreadPolicy policy = spec.thread();
        if (policy == ThreadPolicy.APP && ctx.threads.appThreadUnavailable()) {
            if (spec.mutating()) {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("primitive", method);
                data.put("reason", APP_UNAVAILABLE);
                return new RpcError(ErrorCodes.NOT_READY, APP_UNAVAILABLE, data);
            }
            policy = ThreadPolicy.WORKER;
        }
        if (policy == ThreadPolicy.CALLER || policy == ThreadPolicy.WORKER || policy == ThreadPolicy.APP) {
            RpcError busy = busy(method);
            if (busy != null) {
                return busy;
            }
        }
        long timeoutMs;
        if (meta.has("timeoutMs")) {
            timeoutMs = Math.min(meta.num("timeoutMs", spec.timeoutMs()), MAX_TIMEOUT_MS);
        } else if (primitive instanceof DispatchTimeout) {
            timeoutMs = ((DispatchTimeout) primitive).dispatchTimeoutMs(argsView);
        } else {
            timeoutMs = spec.timeoutMs();
        }
        if (timeoutMs <= 0) {
            return error(ErrorCodes.INVALID_PARAMS, "_meta.timeoutMs must be positive", "primitive", method);
        }
        String correlationId = meta.str("correlationId", null);
        String traceparent = meta.str("traceparent", null);
        return run(method, primitive, policy, argsView, correlationId, traceparent, timeoutMs, secrets,
                secretNames(meta, args));
    }

    private Object run(final String method, final Primitive primitive, ThreadPolicy policy, JsonView args,
                       String correlationId, String traceparent, long timeoutMs, SecretParams secrets,
                       List<String> secretNames) {
        CallScope scope = new CallScope(correlationId, traceparent, secrets);
        long start = System.nanoTime();
        final CallInvocation invocation = new CallInvocation(args, correlationId, traceparent, ctx,
                start + TimeUnit.MILLISECONDS.toNanos(timeoutMs), secretNames,
                new CallWarnings(ctx.log, method));
        final Call call = new Call(start);
        final CoreThreadBroker.Guarded<Object> task = new CoreThreadBroker.Guarded<Object>(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                try {
                    return primitive.invoke(invocation);
                } finally {
                    ended(method, call);
                }
            }
        }, scope, ctx.threads);
        long seqBefore = ctx.events.lastSeq();
        try {
            Object value = ctx.threads.run(policy, task, timeoutMs, callerPool);
            List<Event> events = scope.close();
            long seqAfter = ctx.events.lastSeq();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // Built inside the try: a value that is not JSON-able becomes INTERNAL_ERROR. The call's warnings
            // (phase8-contracts.md 7.1): the secret parameters redacted from each, then bounded.
            return new RpcResult(value, events, seqBefore, seqAfter, durationMs, task.threadName(),
                    invocation.warnings.build(secrets)).toJson();
        } catch (CoreThreadBroker.BrokerTimeout timeout) {
            scope.close();
            invocation.warnings.close();
            String thread = task.threadName();
            if (thread != null && !task.finished()) {
                abandon(method, call, thread, secrets);
            }
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("primitive", method);
            data.put("timeoutMs", Long.valueOf(timeoutMs));
            if (Awt.modalDialogShowing()) {
                data.put("modalOpen", Boolean.TRUE);
            }
            return new RpcError(ErrorCodes.TIMEOUT, method + " timed out after " + timeoutMs + " ms", data);
        } catch (RpcException e) {
            scope.close();
            return e.error();
        } catch (Throwable t) {
            scope.close();
            return internalError(t);
        }
    }

    /** The BUSY error while an abandoned call of {@code method} is still running, else null. */
    private RpcError busy(String method) {
        Call stuck;
        synchronized (abandoned) {
            List<Call> calls = abandoned.get(method);
            stuck = calls == null || calls.isEmpty() ? null : calls.get(0);
        }
        if (stuck == null) {
            return null;
        }
        long runningForMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stuck.startNanos);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("primitive", method);
        data.put("thread", stuck.thread);
        data.put("runningForMs", Long.valueOf(runningForMs));
        return new RpcError(CoreThreadBroker.BUSY, "an earlier call of " + method + " timed out and is still running "
                + "on " + stuck.thread + " (" + runningForMs + " ms); try again when it has ended", data);
    }

    /**
     * Records a call that timed out while its body runs on; it blocks further calls of the primitive until it ends.
     * The line names the primitive and the thread only; it is written on the dispatching thread, outside the call's
     * scope, so it passes through the call's secrets explicitly, like every line the core writes about the call.
     */
    private void abandon(String method, Call call, String thread, SecretParams secrets) {
        synchronized (abandoned) {
            if (!call.state.compareAndSet(RUNNING, ABANDONED)) {
                return;   // it ended in the meantime
            }
            call.thread = thread;
            List<Call> calls = abandoned.get(method);
            if (calls == null) {
                calls = new ArrayList<Call>();
                abandoned.put(method, calls);
            }
            calls.add(call);
        }
        ctx.log.warn(secrets.redact("call of " + method + " timed out and still runs on " + thread
                + "; further calls of it answer BUSY until it ends"));
    }

    /** Called when a call's body has returned or thrown. */
    private void ended(String method, Call call) {
        boolean wasAbandoned;
        synchronized (abandoned) {
            wasAbandoned = call.state.getAndSet(DONE) == ABANDONED;
            if (wasAbandoned) {
                List<Call> calls = abandoned.get(method);
                if (calls != null) {
                    calls.remove(call);
                    if (calls.isEmpty()) {
                        abandoned.remove(method);
                    }
                }
            }
        }
        if (wasAbandoned) {
            ctx.log.info("the abandoned call of " + method + " on " + call.thread + " ended after "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - call.startNanos) + " ms");
        }
    }

    /** {@code {<primitive>: n}} of calls that timed out and still run, for {@code agent.info}. */
    Map<String, Object> abandonedJson() {
        Map<String, Object> out = new TreeMap<String, Object>();
        synchronized (abandoned) {
            for (Map.Entry<String, List<Call>> e : abandoned.entrySet()) {
                out.put(e.getKey(), Long.valueOf(e.getValue().size()));
            }
        }
        return out;
    }

    static RpcError internalError(Throwable t) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("exception", t.getClass().getName());
        data.put("message", t.getMessage());
        List<Object> stack = new ArrayList<Object>();
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < frames.length && i < STACK_FRAMES; i++) {
            stack.add(frames[i].toString());
        }
        data.put("stack", stack);
        String message = t.getMessage() != null ? t.getClass().getSimpleName() + ": " + t.getMessage()
                : t.getClass().getName();
        try {
            return new RpcError(ErrorCodes.INTERNAL_ERROR, message, data);
        } catch (RuntimeException unrepresentable) {
            return new RpcError(ErrorCodes.INTERNAL_ERROR, message, null);
        }
    }

    private static RpcError error(int code, String message, String key, String value) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put(key, value);
        return new RpcError(code, message, data);
    }

    static Map<String, Object> response(Object id, RpcError error) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", error.toJson());
        return out;
    }

    private static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("result", result);
        return out;
    }

    /** The life of one call, for the abandoned-call gate. */
    private static final class Call {
        final long startNanos;
        final AtomicInteger state = new AtomicInteger(RUNNING);
        /** Set when the call is abandoned; read under the table's lock. */
        String thread;

        Call(long startNanos) {
            this.startNanos = startNanos;
        }
    }

    /**
     * The names in {@code _meta.secretParams} that name a top-level argument of the call (A34; the list was checked
     * by {@link SecretParams#of} before), in request order, without duplicates.
     */
    private static List<String> secretNames(JsonView meta, Map<String, Object> args) {
        Object names = meta.raw().get(Protocol.META_SECRET_PARAMS);
        if (!(names instanceof List)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (Object name : (List<?>) names) {
            if (name instanceof String && args.containsKey(name) && !out.contains(name)) {
                out.add((String) name);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The {@link Invocation} of one call. */
    private static final class CallInvocation implements Invocation {
        private final JsonView args;
        private final String correlationId;
        private final String traceparent;
        private final AgentContext context;
        private final long deadlineNanos;
        private final List<String> secretNames;
        /** The call's warnings (phase8-contracts.md 7.1). */
        final CallWarnings warnings;

        CallInvocation(JsonView args, String correlationId, String traceparent, AgentContext context,
                       long deadlineNanos, List<String> secretNames, CallWarnings warnings) {
            this.args = args;
            this.correlationId = correlationId;
            this.traceparent = traceparent;
            this.context = context;
            this.deadlineNanos = deadlineNanos;
            this.secretNames = secretNames;
            this.warnings = warnings;
        }

        /** phase8-contracts.md 7.1: checked, kept raw in call order, redacted and bounded when the call returns. */
        @Override
        public void warn(String code, String message, Map<String, Object> data) {
            warnings.add(code, message, data);
        }

        @Override
        public List<String> secretParams() {
            return secretNames;
        }

        @Override
        public JsonView args() {
            return args;
        }

        @Override
        public String correlationId() {
            return correlationId;
        }

        @Override
        public String traceparent() {
            return traceparent;
        }

        @Override
        public AgentContext context() {
            return context;
        }

        @Override
        public long remainingMs() {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        }
    }
}
