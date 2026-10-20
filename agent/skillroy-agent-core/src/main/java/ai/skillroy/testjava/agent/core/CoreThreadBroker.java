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

import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.Lane;
import ai.skillroy.testjava.agent.api.QuiescentPoint;
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.ThreadBroker;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The {@link ThreadBroker}. Every wait is bounded; the EDT is never blocked by the agent (EDT_SYNC posts with
 * {@code invokeLater} and waits on a future; a task the EDT has not started when the wait expires is cancelled and
 * never runs) and never interrupted; WORKER tasks are interrupted on timeout (best effort); APP uses the first
 * registered {@link ThreadPolicyProvider} and falls back to WORKER, also while that provider reports
 * {@link ThreadPolicyProvider#available()} false (contract amendment A19; the dispatcher refuses mutating APP
 * primitives in that case before they get here). A task never runs on a thread a provider forbids, except a task the
 * core submitted through a {@link QuiescentPoint}, at the point the provider itself runs it (phase8-contracts.md 4.1,
 * D520), and a forbidden thread may not wait in {@link #call}. {@code call(CALLER, …)} runs the task inline on
 * the calling thread, where no timeout can be enforced; the dispatcher runs CALLER primitives on a control-channel
 * thread of their own so that their timeout is enforced.
 *
 * <p>AWT is touched only by the EDT policies, and only when a task is actually run or posted under one of them
 * (through {@link Awt}); the other policies never load an AWT class, so a target that does not use AWT never has it
 * initialised by the agent.
 *
 * <p>The worker and caller pools are bounded (finding S30): a task that finds every thread busy is not queued but
 * answered at once with {@link #BUSY}.
 *
 * <p>Phase 8 (phase8-contracts.md 4.1, 4.2): {@code APP_QUIESCENT} runs a task on the application's own thread
 * through the first provider's {@link QuiescentPoint}, admitted by its budget, never interrupted; the one exemption to
 * the forbidden-thread rule is the task the core wraps for it ({@link QuiescentTask}), which only that point runs, and
 * from inside which a nested {@code call}, {@code callQuiescent}, {@code callOnLane} or {@code QuiescentPoint.await}
 * is refused {@code FORBIDDEN_THREAD}. A task that runs past its budget is counted and logged once per primitive; one
 * that runs past twice its budget penalises its primitive until {@code agent.capabilities {reprobe: true}}.
 * {@code LANE} runs a task on a module's declared lane ({@link Lanes}). Neither policy is ever posted, and neither
 * falls back to another thread.
 *
 * <p>The caller's {@link CallScope} travels with the task, so events published on the executing thread carry the
 * call's correlation id, and the agent log lines written there hide the call's secret parameter values (contract
 * amendment A34). The line that reports the failure of a task run on another thread (an EDT_ASYNC call, a posted
 * task) is written with the task's scope bound again, because the task restores the thread's previous scope before
 * its exception reaches the catch. Thread-safe.
 */
final class CoreThreadBroker implements ThreadBroker {

    /** How long a posted APP task may take on the application thread. */
    static final long POST_APP_TIMEOUT_MS = 60000L;
    /** Extra time the outer wait gives a provider to report its own timeout. */
    private static final long PROVIDER_GRACE_MS = 250L;
    /**
     * Error code of a call refused because its pool is full or an earlier call of the same primitive timed out and
     * is still running (finding S30): {@link ErrorCodes#BUSY}. The controller classifies it as an environment error,
     * not a timeout.
     */
    static final int BUSY = ErrorCodes.BUSY;

    private final ExecutorService workers;
    private final SpiRegistry spi;
    private final AgentLog log;
    private final Lanes lanes;
    /** The application thread running a core-wrapped quiescent task right now, or null (D520). */
    private volatile Thread quiescentRunner;
    /**
     * The key prefix of a module's own quiescent tasks ({@code callQuiescent} and {@code call(APP_QUIESCENT, …)}
     * through its context's broker) in {@link #penalties} and {@code agent.info.threads.quiescent.penalised} (Phase 8
     * review fix L2-F3, FB).
     */
    static final String MODULE_KEY = "module:";

    /**
     * Primitives, and modules ({@value #MODULE_KEY}{@code <id>}), penalised for a quiescent overrun of more than twice
     * their budget: {budgetMs, ranMs}; guarded.
     */
    private final Map<String, long[]> penalties = new TreeMap<String, long[]>();
    /** Primitives whose overrun was logged, and providers whose quiescentPoint() threw; guarded by penalties. */
    private final Set<String> overrunLogged = new HashSet<String>();
    private final Set<String> pointFailureLogged = new HashSet<String>();
    /** The last overrun of a core-wrapped task: {primitive, budgetMs, ranMs}; guarded by penalties. */
    private Map<String, Object> lastOverrun;

    CoreThreadBroker(ExecutorService workers, SpiRegistry spi, AgentLog log) {
        this(workers, spi, log, new Lanes(Thread.currentThread().getThreadGroup(),
                CoreThreadBroker.class.getClassLoader(), log));
    }

    CoreThreadBroker(ExecutorService workers, SpiRegistry spi, AgentLog log, Lanes lanes) {
        this.workers = workers;
        this.spi = spi;
        this.log = log;
        this.lanes = lanes;
    }

    @Override
    public <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception {
        return call(null, policy, task, timeoutMs);
    }

    /**
     * {@link #call(ThreadPolicy, Callable, long)} for a module's context ({@code module} its id, or null for the
     * core's own calls): {@code call(APP_QUIESCENT, …)} is then {@link #callQuiescent(String, Callable, long, long)}
     * with the default budget, penalised per module (L2-F3).
     */
    <T> T call(String module, ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception {
        if (policy == null || task == null) {
            throw new IllegalArgumentException("policy and task must not be null");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        refuseForbiddenCaller();
        Guarded<T> guarded = new Guarded<T>(task, CallScope.current(), this);
        if (policy == ThreadPolicy.APP_QUIESCENT) {
            // phase8-contracts.md 4.1: call(APP_QUIESCENT, task, t) is callQuiescent(task, 50, t).
            return quiescent(module, guarded, QuiescentPoint.DEFAULT_BUDGET_MS, timeoutMs);
        }
        return run(policy, guarded, timeoutMs, null);
    }

    /**
     * FORBIDDEN_THREAD when the calling thread is one a provider forbids, or runs a core-wrapped quiescent task: such
     * a thread may not wait for agent work (P1 4; phase8-contracts.md 4.1).
     */
    private void refuseForbiddenCaller() {
        Thread caller = Thread.currentThread();
        if (caller == quiescentRunner) {
            throw forbidden(caller, "a quiescent task may not wait for agent work");
        }
        if (isForbiddenThread(caller)) {
            throw forbidden(caller, "may not wait for agent work");
        }
    }

    @Override
    public <T> T callQuiescent(Callable<T> task, long budgetMs, long timeoutMs) throws Exception {
        return callQuiescent(null, task, budgetMs, timeoutMs);
    }

    /**
     * {@link #callQuiescent(Callable, long, long)} for a module's context: {@code module} (its id) keys the overrun
     * penalty and its log line ({@value #MODULE_KEY}{@code <id>}), so that a module whose task overran twice its
     * budget is refused until {@code agent.capabilities {reprobe: true}} and another module is not (L2-F3); null for
     * the core's own calls, which carry no penalty.
     */
    <T> T callQuiescent(String module, Callable<T> task, long budgetMs, long timeoutMs) throws Exception {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        refuseForbiddenCaller();
        return quiescent(module, new Guarded<T>(task, CallScope.current(), this), budgetMs, timeoutMs);
    }

    /** The gates, then the run, of a module's (or the core's) own quiescent task. */
    private <T> T quiescent(String module, Guarded<T> task, long budgetMs, long timeoutMs) throws Exception {
        RpcException refused = quiescentGate(null, module, budgetMs);
        if (refused != null) {
            throw refused;
        }
        return runQuiescent(null, module, task, budgetMs, timeoutMs);
    }

    @Override
    public Lane declareLane(String name, int threads, int queue) {
        return lanes.declare(null, name, threads, queue);
    }

    /** Declares a lane owned by a module (the module context's scoped broker). */
    Lane declareLane(String owner, String name, int threads, int queue) {
        return lanes.declare(owner, name, threads, queue);
    }

    @Override
    public <T> T callOnLane(String lane, Callable<T> task, long timeoutMs) throws Exception {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        refuseForbiddenCaller();
        return runOnLane(lane, new Guarded<T>(task, CallScope.current(), this), timeoutMs);
    }

    /**
     * Runs a guarded task on a declared lane (the dispatcher's path for a LANE primitive, phase8-contracts.md 4.2).
     *
     * @throws BrokerTimeout when the wait expires
     */
    <T> T runOnLane(String lane, Guarded<T> task, long timeoutMs) throws Exception {
        return lanes.call(lane, task, timeoutMs);
    }

    /** {@code agent.info.threads.lanes}. */
    Map<String, Object> lanesJson() {
        return lanes.toJson();
    }

    /** Refuses later lane declarations (the agent started closing). */
    void startClosingLanes() {
        lanes.startClosing();
    }

    /** Retires every lane (agent close, after the modules' uninstall and before the pools shut down). */
    void retireLanes() {
        lanes.closeAll();
    }

    /**
     * The dispatcher's gates for an {@code APP_QUIESCENT} call (phase8-contracts.md 4.1), in order, or null when the
     * call may go on: no point offered (CAPABILITY_UNAVAILABLE), the provider not available (NOT_READY), a budget above
     * the allowance (CAPABILITY_UNAVAILABLE), the primitive penalised (CAPABILITY_UNAVAILABLE). {@code primitive} is
     * null for {@link #callQuiescent}; the refusal's data then has no {@code primitive}.
     */
    RpcException quiescentGate(String primitive, long budgetMs) {
        return quiescentGate(primitive, null, budgetMs);
    }

    /**
     * {@link #quiescentGate(String, long)} for a primitive or, with {@code primitive} null, a module's own task
     * ({@code module} its id; null for the core's own calls): a module's penalty refusal carries {@code module}.
     */
    RpcException quiescentGate(String primitive, String module, long budgetMs) {
        ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
        QuiescentPoint point = provider == null ? null : pointOf(provider);
        if (point == null || point.isClosed()) {
            return refusal(ErrorCodes.CAPABILITY_UNAVAILABLE, primitive, "no quiescent point offered", null);
        }
        if (!isAvailable(provider)) {
            return refusal(ErrorCodes.NOT_READY, primitive, Dispatcher.APP_UNAVAILABLE, null);
        }
        if (budgetMs > point.allowanceMs()) {
            Map<String, Object> more = new LinkedHashMap<String, Object>();
            more.put("budgetMs", Long.valueOf(budgetMs));
            more.put("allowanceMs", Long.valueOf(point.allowanceMs()));
            return refusal(ErrorCodes.CAPABILITY_UNAVAILABLE, primitive, "quiescent budget exceeds the allowance",
                    more);
        }
        String key = penaltyKey(primitive, module);
        if (key != null) {
            long[] penalty;
            synchronized (penalties) {
                penalty = penalties.get(key);
            }
            if (penalty != null) {
                Map<String, Object> more = new LinkedHashMap<String, Object>();
                if (primitive == null) {
                    more.put("module", module);
                }
                more.put("budgetMs", Long.valueOf(penalty[0]));
                more.put("ranMs", Long.valueOf(penalty[1]));
                RpcException refused = refusal(ErrorCodes.CAPABILITY_UNAVAILABLE, primitive,
                        "quiescent task overran its budget", more);
                return primitive != null ? refused : new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE,
                        "module " + module + ": quiescent task overran its budget", refused.error().data());
            }
        }
        return null;
    }

    /** The penalty key: the primitive's name, else {@value #MODULE_KEY}{@code <module>}, else null (no penalty). */
    static String penaltyKey(String primitive, String module) {
        return primitive != null ? primitive : module != null ? MODULE_KEY + module : null;
    }

    private static RpcException refusal(int code, String primitive, String reason, Map<String, Object> more) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (primitive != null) {
            data.put("primitive", primitive);
        }
        data.put("reason", reason);
        if (more != null) {
            data.putAll(more);
        }
        return new RpcException(code, primitive == null ? reason : primitive + ": " + reason, data);
    }

    /**
     * Runs a guarded task at the first provider's quiescent point (phase8-contracts.md 4.1): the core waits no longer
     * than the point ({@code timeoutMs}); a task admitted and still running at the deadline becomes this broker's
     * timeout, so that the dispatcher's abandoned-call gate (S30) applies. Never interrupts the application thread.
     *
     * @param primitive the primitive whose task this is, or null (a module's {@link #callQuiescent})
     * @throws BrokerTimeout when the task was admitted and still runs at the deadline
     */
    <T> T runQuiescent(String primitive, Guarded<T> task, long budgetMs, long timeoutMs) throws Exception {
        return runQuiescent(primitive, null, task, budgetMs, timeoutMs);
    }

    /** {@link #runQuiescent(String, Guarded, long, long)} for a primitive or a module's own task (L2-F3). */
    <T> T runQuiescent(String primitive, String module, Guarded<T> task, long budgetMs, long timeoutMs)
            throws Exception {
        ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
        QuiescentPoint point = provider == null ? null : pointOf(provider);
        if (point == null) {
            throw refusal(ErrorCodes.CAPABILITY_UNAVAILABLE, primitive, "no quiescent point offered", null);
        }
        QuiescentTask<T> wrapped = new QuiescentTask<T>(task, primitive, module, budgetMs, this);
        try {
            return point.await(wrapped, budgetMs, timeoutMs);
        } catch (RpcException e) {
            if (e.error().code() == ErrorCodes.TIMEOUT && task.threadName() != null && !task.finished()) {
                throw new BrokerTimeout(timeoutMs);
            }
            throw e;
        }
    }

    /**
     * The point of the provider, or null: a provider whose {@code quiescentPoint()} throws counts as offering none
     * (logged once per provider class).
     */
    QuiescentPoint pointOf(ThreadPolicyProvider provider) {
        try {
            return provider.quiescentPoint();
        } catch (Throwable t) {
            String name = provider.getClass().getName();
            boolean first;
            synchronized (penalties) {
                first = pointFailureLogged.add(name);
            }
            if (first) {
                log.warn("ThreadPolicyProvider " + name + " failed in quiescentPoint(); treated as none: "
                        + t.getClass().getName());
            }
            return null;
        }
    }

    /** Closes the first provider's quiescent point (agent disable and close, before the modules' uninstall). */
    void closeQuiescentPoint() {
        ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
        if (provider != null) {
            closePoint(provider);
        }
    }

    /** Closes the provider's point, if it offers one; never throws. */
    void closePoint(ThreadPolicyProvider provider) {
        QuiescentPoint point = pointOf(provider);
        if (point != null) {
            try {
                point.close();
            } catch (Throwable t) {
                log.warn("closing a quiescent point failed: " + t.getClass().getName());
            }
        }
    }

    /** Clears every overrun penalty ({@code agent.capabilities {reprobe: true}}, phase8-contracts.md 4.1, D521). */
    void clearQuiescentPenalties() {
        synchronized (penalties) {
            penalties.clear();
        }
    }

    /**
     * Records the run time of a core-wrapped quiescent task: overruns logged once per primitive or module, 2x
     * penalised (a module's own task under {@value #MODULE_KEY}{@code <id>}, L2-F3); the core's own unkeyed calls are
     * logged once in all and not penalised.
     */
    private void ran(String primitive, String module, long budgetMs, long ranMs) {
        if (ranMs <= budgetMs) {
            return;
        }
        String key = penaltyKey(primitive, module);
        String who = primitive != null ? primitive
                : module != null ? "a quiescent task of module " + module : "a quiescent task";
        boolean penalised = key != null && ranMs > 2 * budgetMs;
        boolean first;
        synchronized (penalties) {
            Map<String, Object> last = new LinkedHashMap<String, Object>();
            last.put("primitive", primitive);
            last.put("budgetMs", Long.valueOf(budgetMs));
            last.put("ranMs", Long.valueOf(ranMs));
            lastOverrun = last;
            first = overrunLogged.add(key != null ? key : who);
            if (penalised) {
                penalties.put(key, new long[] {budgetMs, ranMs});
            }
        }
        if (first) {
            log.warn(who + " held the application's thread " + ranMs + " ms, over its quiescent budget of " + budgetMs
                    + " ms" + (penalised
                    ? "; it answers CAPABILITY_UNAVAILABLE until agent.capabilities {reprobe: true}" : ""));
        }
    }

    /**
     * {@code agent.info.threads.quiescent}: {@code {provider, allowanceMs, capacity, queued, ran, notReady, refused,
     * overruns, lastOverrun: {primitive, budgetMs, ranMs} | null, penalised: [...]}} when the first provider offers a
     * point, else null.
     */
    Map<String, Object> quiescentJson() {
        ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
        QuiescentPoint point = provider == null ? null : pointOf(provider);
        if (point == null) {
            return null;
        }
        Map<String, Object> stats = point.stats();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String id;
        try {
            id = provider.id();
        } catch (Throwable t) {
            id = null;
        }
        out.put("provider", id == null ? provider.getClass().getName() : id);
        for (String key : new String[] {"allowanceMs", "capacity", "queued", "ran", "notReady", "refused",
                "overruns"}) {
            out.put(key, stats.get(key));
        }
        synchronized (penalties) {
            Map<String, Object> last = lastOverrun == null ? null : new LinkedHashMap<String, Object>(lastOverrun);
            if (last == null && stats.get("lastOverrun") instanceof Map) {
                // An overrun of a task the core did not wrap (a module's own await): no primitive to name.
                last = new LinkedHashMap<String, Object>();
                last.put("primitive", null);
                last.putAll(castMap(stats.get("lastOverrun")));
            }
            out.put("lastOverrun", last);
            out.put("penalised", Collections.unmodifiableList(new ArrayList<Object>(penalties.keySet())));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object map) {
        return (Map<String, Object>) map;
    }

    /**
     * Runs a guarded task under the policy. With a {@code callerPool}, CALLER hands the task to that pool and waits
     * with the timeout (the dispatcher's path); without one CALLER runs inline and cannot be bounded.
     *
     * @throws BrokerTimeout when the wait expires
     */
    <T> T run(ThreadPolicy policy, Guarded<T> task, long timeoutMs, ExecutorService callerPool) throws Exception {
        switch (policy) {
            case CALLER:
                return callerPool == null ? task.call() : onPool(callerPool, task, timeoutMs);
            case WORKER:
                return onPool(workers, task, timeoutMs);
            case EDT_SYNC:
                return onEdt(task, timeoutMs);
            case EDT_ASYNC:
                Awt.invokeLater(logging(task));
                return null;
            case APP:
                return onApp(task, timeoutMs);
            case APP_QUIESCENT:
                // phase8-contracts.md 4.1: call(APP_QUIESCENT, task, t) is callQuiescent(task, 50, t).
                return quiescent(null, task, QuiescentPoint.DEFAULT_BUDGET_MS, timeoutMs);
            case LANE:
                // phase8-contracts.md 4.2, D-ET-4: a policy without a lane name; callOnLane names one.
                throw laneUnnamed();
            default:
                throw new IllegalArgumentException("unknown thread policy " + policy);
        }
    }

    /** CAPABILITY_UNAVAILABLE for {@code call(LANE, …)}, which names no lane. */
    private static RpcException laneUnnamed() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("reason", "unknown lane");
        data.put("lane", null);
        return new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "LANE names no lane: use callOnLane", data);
    }

    /** A runnable that runs the task and logs (never throws) its failure under the task's call scope. */
    private Runnable logging(final Guarded<?> task) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    task.call();
                } catch (Throwable t) {
                    task.logFailure(log, "posted task failed", t);
                }
            }
        };
    }

    @Override
    public void post(ThreadPolicy policy, final Runnable task) {
        try {
            if (policy == null || task == null) {
                throw new IllegalArgumentException("policy and task must not be null");
            }
            final Guarded<Object> guarded = new Guarded<Object>(new Callable<Object>() {
                @Override
                public Object call() {
                    task.run();
                    return null;
                }
            }, CallScope.current(), this);
            final Runnable logged = logging(guarded);
            switch (policy) {
                case EDT_SYNC:
                case EDT_ASYNC:
                    Awt.invokeLater(logged);
                    break;
                case APP:
                    final ThreadPolicyProvider provider = appProvider();
                    if (provider == null) {
                        workers.execute(logged);
                    } else {
                        workers.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    provider.callOnAppThread(guarded, POST_APP_TIMEOUT_MS);
                                } catch (Throwable t) {
                                    guarded.logFailure(log, "posted APP task failed", t);
                                }
                            }
                        });
                    }
                    break;
                case APP_QUIESCENT:
                case LANE:
                    // phase8-contracts.md 4.1, 4.2, 4.8 defect 5: refused, never run on a worker: a posted task has no
                    // caller whose time bounds it, and post names no lane.
                    log.warn("a task posted under " + policy + " is refused: use ThreadBroker.call"
                            + (policy == ThreadPolicy.LANE ? "OnLane" : "Quiescent"));
                    break;
                case CALLER:
                case WORKER:
                default:
                    workers.execute(logged);
                    break;
            }
        } catch (Throwable t) {
            log.warn("could not post a task under " + policy, t);
        }
    }

    @Override
    public boolean isForbiddenThread(Thread thread) {
        return forbiddenBy(thread) != null;
    }

    /** The id of the first provider that forbids the thread, or null. A failing provider does not forbid. */
    String forbiddenBy(Thread thread) {
        if (thread == null) {
            return null;
        }
        for (ThreadPolicyProvider provider : spi.all(ThreadPolicyProvider.class)) {
            try {
                if (provider.isForbidden(thread)) {
                    String id = provider.id();
                    return id == null ? provider.getClass().getName() : id;
                }
            } catch (Throwable t) {
                log.warn("ThreadPolicyProvider.isForbidden failed", t);
            }
        }
        return null;
    }

    /**
     * The provider that backs APP now: the first registered {@link ThreadPolicyProvider}, or null when there is none
     * or it reports {@link ThreadPolicyProvider#available()} false (APP then runs on WORKER).
     */
    private ThreadPolicyProvider appProvider() {
        ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
        return provider == null || !isAvailable(provider) ? null : provider;
    }

    /** True when a provider is registered and its application thread does not exist yet (contract amendment A19). */
    boolean appThreadUnavailable() {
        ThreadPolicyProvider provider = spi.first(ThreadPolicyProvider.class);
        return provider != null && !isAvailable(provider);
    }

    private boolean isAvailable(ThreadPolicyProvider provider) {
        try {
            return provider.available();
        } catch (Throwable t) {
            log.warn("ThreadPolicyProvider.available failed; treated as not available", t);
            return false;
        }
    }

    RpcException forbidden(Thread thread, String what) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("thread", thread.getName());
        data.put("provider", forbiddenBy(thread));
        return new RpcException(ErrorCodes.FORBIDDEN_THREAD,
                "thread '" + thread.getName() + "' is forbidden by a ThreadPolicyProvider: " + what, data);
    }

    /** The BUSY error for a pool that refused a task. */
    static RpcException poolFull(String what) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("reason", "pool full");
        data.put("pool", what);
        return new RpcException(BUSY, "every agent " + what + " thread is busy; try again later", data);
    }

    private static <T> Future<T> submit(ExecutorService pool, Callable<T> task, String what) {
        try {
            return pool.submit(task);
        } catch (RejectedExecutionException e) {
            throw poolFull(what);
        }
    }

    private <T> T onPool(ExecutorService pool, Callable<T> task, long timeoutMs) throws Exception {
        Future<T> future = submit(pool, task, pool == workers ? "worker" : "caller");
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BrokerTimeout(timeoutMs);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private <T> T onEdt(Guarded<T> task, long timeoutMs) throws Exception {
        if (Awt.onEventDispatchThread()) {
            return task.call();
        }
        FutureTask<T> future = new FutureTask<T>(task);
        Awt.invokeLater(future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Never interrupt the EDT; an unstarted task is cancelled and will not run.
            future.cancel(false);
            throw new BrokerTimeout(timeoutMs);
        } catch (InterruptedException e) {
            future.cancel(false);
            throw e;
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private <T> T onApp(final Guarded<T> task, final long timeoutMs) throws Exception {
        final ThreadPolicyProvider provider = appProvider();
        if (provider == null) {
            return onPool(workers, task, timeoutMs);
        }
        Future<T> future = submit(workers, new Callable<T>() {
            @Override
            public T call() throws Exception {
                return provider.callOnAppThread(task, timeoutMs);
            }
        }, "worker");
        try {
            // The grace must not overflow a caller's "practically forever".
            return future.get(Math.min(timeoutMs, Long.MAX_VALUE - PROVIDER_GRACE_MS) + PROVIDER_GRACE_MS,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BrokerTimeout(timeoutMs);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Exception cause = unwrap(e);
            if (cause instanceof RpcException && ((RpcException) cause).error().code() == ErrorCodes.TIMEOUT
                    && !task.finished()) {
                // The provider gave up waiting for its thread: the dispatcher's timeout, not the primitive's.
                throw new BrokerTimeout(timeoutMs);
            }
            throw cause;
        }
    }

    private static Exception unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return e;
    }

    /**
     * The broker's own timeout (as opposed to a TIMEOUT a primitive raises itself), so that the dispatcher can add
     * its standard detail.
     */
    static final class BrokerTimeout extends RpcException {
        private static final long serialVersionUID = 1L;

        BrokerTimeout(long timeoutMs) {
            super(ErrorCodes.TIMEOUT, "timed out after " + timeoutMs + " ms");
        }
    }

    /**
     * The task the core hands a {@link QuiescentPoint} (phase8-contracts.md 4.1, D520): the one variant of
     * {@link Guarded} that runs on a thread a provider forbids. It binds the call's scope (restored with
     * {@code remove()} when the thread had none, so nothing stays on the application's thread), marks the thread as
     * running a quiescent task (a nested wait for agent work is then refused), measures the run time and reports an
     * overrun. Constructed by the core only; reaches the application's thread only through the point, which holds it
     * privately.
     */
    static final class QuiescentTask<T> implements Callable<T> {
        private final Guarded<T> task;
        private final String primitive;
        private final String module;
        private final long budgetMs;
        private final CoreThreadBroker broker;

        QuiescentTask(Guarded<T> task, String primitive, String module, long budgetMs, CoreThreadBroker broker) {
            this.task = task;
            this.primitive = primitive;
            this.module = module;
            this.budgetMs = budgetMs;
            this.broker = broker;
        }

        @Override
        public T call() throws Exception {
            Thread current = Thread.currentThread();
            broker.quiescentRunner = current;
            long start = System.nanoTime();
            try {
                return task.callAtQuiescentPoint();
            } finally {
                broker.quiescentRunner = null;
                broker.ran(primitive, module, budgetMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        }
    }

    /**
     * Wraps a task: binds the caller's {@link CallScope} on the executing thread, refuses to run on a forbidden
     * thread, and records which thread ran it and whether it finished.
     */
    static final class Guarded<T> implements Callable<T> {
        private final Callable<T> task;
        private final CallScope scope;
        private final CoreThreadBroker broker;
        private volatile String threadName;
        private volatile boolean finished;

        Guarded(Callable<T> task, CallScope scope, CoreThreadBroker broker) {
            this.task = task;
            this.scope = scope;
            this.broker = broker;
        }

        @Override
        public T call() throws Exception {
            Thread current = Thread.currentThread();
            if (broker.isForbiddenThread(current)) {
                finished = true;
                throw broker.forbidden(current, "agent work may not run on it");
            }
            return run(current);
        }

        /**
         * Runs the task on a thread a provider may forbid: only for the core's {@link QuiescentTask}, which only the
         * first provider's quiescent point runs, at the point the provider itself chose (phase8-contracts.md 4.1,
         * D520).
         */
        T callAtQuiescentPoint() throws Exception {
            return run(Thread.currentThread());
        }

        private T run(Thread current) throws Exception {
            threadName = current.getName();
            CallScope previous = CallScope.bind(scope);
            try {
                return task.call();
            } finally {
                finished = true;
                CallScope.restore(previous);
            }
        }

        /**
         * Logs a failure of this task with its call's scope bound on the logging thread for the duration of the
         * line, so that the line hides the call's secret parameter values like every other line about the call
         * (contract amendment A34). {@link #call} has restored the thread's previous scope before its exception
         * reaches a catch, and the APP path logs on a worker thread that never had the scope.
         */
        void logFailure(AgentLog log, String message, Throwable t) {
            CallScope previous = CallScope.bind(scope);
            try {
                log.warn(message, t);
            } finally {
                CallScope.restore(previous);
            }
        }

        /** Name of the thread that ran (or is running) the task; null if it has not started. */
        String threadName() {
            return threadName;
        }

        /** True once the task body has returned or thrown (or was refused). */
        boolean finished() {
            return finished;
        }
    }
}
