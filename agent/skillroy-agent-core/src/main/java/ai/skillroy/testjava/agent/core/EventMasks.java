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
import ai.skillroy.testjava.agent.api.SpiRegistry;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.protocol.SecretKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The pass every event payload takes before the bus keeps it (phase8-contracts.md 5.1, gap 109, D539 to D543): every
 * registered {@link MaskPolicy} to every scalar, as {@code mask("event:" + topic, <dotted column>, value)}, and the
 * core's secret-key rule to every map key.
 *
 * <ul>
 *   <li>The column of a scalar is the map keys from the payload root to it joined with {@code '.'}; a list element
 *       adds no segment ({@code {detail: {lines: [{name: x}]}}} gives {@code detail.lines.name}).</li>
 *   <li>A policy sees the frozen JSON-normal scalars ({@code String}, {@code Long}, {@code Double}, {@code Boolean}
 *       and the big numbers the freeze keeps), never {@code null}, never an application object; policies run in
 *       registration order, each on what the one before returned. A return that is null, a {@code String}, a finite
 *       JDK {@code Number} or a {@code Boolean} replaces the value (null ends the chain: null is not passed on);
 *       anything else, NaN, the infinities and a Number of an application class included (they could not be stored,
 *       or storing them would call application code), becomes {@code <redacted>}.</li>
 *   <li>A policy that throws turns that scalar into {@code <redacted>} (the later policies are not asked), is counted
 *       and logged at WARN once per policy class with the exception's class name only (its message may quote the
 *       value). A {@link VirtualMachineError} other than {@link StackOverflowError} is rethrown, so the bus drops the
 *       event as it drops one it cannot freeze.</li>
 *   <li>The key rule (D540): a map entry whose key {@link Redactor#isSecret} calls secret becomes {@code <redacted>}
 *       whatever its value, a nested map or list under it included (one string in its place). It runs whether or not
 *       a policy is registered; a key test that throws counts as secret.</li>
 *   <li>The bound (D541): while a policy is registered, at most {@link #MAX_SCALARS} scalars are passed to the
 *       policies; every scalar beyond is {@code <redacted>} without asking one, and the payload gains
 *       {@code truncated: true} at its root (counted as {@code overBudget}). Without a policy no scalar is visited
 *       and nothing is cut: the key rule alone walks the keys, as the freeze already did.</li>
 *   <li>Nothing to change, no copy: the walk allocates only along the path of a change, and {@link #apply} returns
 *       the frozen map itself when nothing changed.</li>
 * </ul>
 *
 * <p>Runs on the publishing thread (the EDT, an application thread, an adapter's thread or an agent worker), outside
 * the ring lock; one lock-free SPI lookup and one {@code nanoTime} pair per event, feeding {@code slowPasses} (passes
 * over {@link #SLOW_PASS_MS}) and {@code maxPassMs}. Thread-safe.
 */
final class EventMasks {

    /** No pass at all: the payload is stored as published (the bus's test constructors). */
    static final EventMasks NONE = new EventMasks(null, null, null);
    /** The scalars a pass hands to the policies (D541). */
    static final int MAX_SCALARS = 10000;
    /** A pass longer than this counts as slow ({@code agent.info.events.slowPasses}). */
    static final long SLOW_PASS_MS = 50L;
    /** The prefix of the source id a policy sees for an event (beside {@code hook.<id>} and {@code rule.<name>}). */
    static final String SOURCE_PREFIX = "event:";

    private static final String REDACTED = SecretKeys.REDACTED;
    private static final long SLOW_PASS_NANOS = TimeUnit.MILLISECONDS.toNanos(SLOW_PASS_MS);

    private final SpiRegistry spi;
    private final Redactor redactor;
    private final AgentLog log;
    private final AtomicLong maskFailures = new AtomicLong();
    private final AtomicLong overBudget = new AtomicLong();
    private final AtomicLong slowPasses = new AtomicLong();
    private final AtomicLong maxPassNanos = new AtomicLong();
    /** The policy classes whose failure was logged since start (bounded by the classes registered). */
    private final Set<String> failureLogged = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * @param spi where the {@link MaskPolicy}s are looked up per event (null: none)
     * @param redactor the core's secret-key rule (null: no key rule)
     * @param log where a failing policy is reported (null: nowhere)
     */
    EventMasks(SpiRegistry spi, Redactor redactor, AgentLog log) {
        this.spi = spi;
        this.redactor = redactor;
        this.log = log;
    }

    /**
     * The payload the bus keeps for an event of {@code topic}: {@code frozen} itself when nothing changes, else a new
     * map (whose unchanged branches are {@code frozen}'s own; the bus freezes it again).
     *
     * @param frozen the payload as {@code new Event(...)} froze it, not null
     */
    Map<String, Object> apply(String topic, Map<String, Object> frozen) {
        if (spi == null && redactor == null) {
            return frozen;
        }
        long start = System.nanoTime();
        try {
            List<MaskPolicy> policies = spi == null ? Collections.<MaskPolicy>emptyList()
                    : spi.all(MaskPolicy.class);
            if (policies.isEmpty() && redactor == null) {
                return frozen;
            }
            Pass pass = new Pass(SOURCE_PREFIX + topic, policies);
            Map<String, Object> out = pass.map(frozen, null);
            if (pass.overBudget) {
                if (out == frozen) {
                    out = new LinkedHashMap<String, Object>(frozen);
                }
                out.put("truncated", Boolean.TRUE);
                overBudget.incrementAndGet();
            }
            return out;
        } finally {
            long nanos = System.nanoTime() - start;
            if (nanos > SLOW_PASS_NANOS) {
                slowPasses.incrementAndGet();
            }
            long max;
            do {
                max = maxPassNanos.get();
            } while (nanos > max && !maxPassNanos.compareAndSet(max, nanos));
        }
    }

    /** The policies registered now. */
    int policies() {
        return spi == null ? 0 : spi.all(MaskPolicy.class).size();
    }

    long maskFailures() {
        return maskFailures.get();
    }

    long overBudget() {
        return overBudget.get();
    }

    long slowPasses() {
        return slowPasses.get();
    }

    long maxPassMs() {
        return TimeUnit.NANOSECONDS.toMillis(maxPassNanos.get());
    }

    /**
     * {@code agent.info.events} (D543): {@code {maskPolicies, maskFailures, overBudget, slowPasses, maxPassMs}}, the
     * policies registered now and the counters since start. Counts only, never a value.
     */
    Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("maskPolicies", Long.valueOf(policies()));
        out.put("maskFailures", Long.valueOf(maskFailures()));
        out.put("overBudget", Long.valueOf(overBudget()));
        out.put("slowPasses", Long.valueOf(slowPasses()));
        out.put("maxPassMs", Long.valueOf(maxPassMs()));
        return out;
    }

    private boolean secret(String key) {
        if (redactor == null) {
            return false;
        }
        try {
            return redactor.isSecret(key);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void failed(MaskPolicy policy, String sourceId, Throwable t) {
        maskFailures.incrementAndGet();
        String type = policy.getClass().getName();
        if (log != null && failureLogged.add(type)) {
            try {
                log.warn("MaskPolicy " + type + " failed (" + t.getClass().getName() + ") on " + sourceId
                        + "; the value is " + REDACTED + " (logged once per policy class)");
            } catch (Throwable ignored) {
                // Never fail the publisher.
            }
        }
    }

    /** One event's walk. */
    private final class Pass {
        private final String sourceId;
        private final List<MaskPolicy> policies;
        private int scalars;
        boolean overBudget;

        Pass(String sourceId, List<MaskPolicy> policies) {
            this.sourceId = sourceId;
            this.policies = policies;
        }

        @SuppressWarnings("unchecked")
        private Object value(Object value, String column) {
            if (value == null) {
                return null;
            }
            if (value instanceof Map) {
                return map((Map<String, Object>) value, column);
            }
            if (value instanceof List) {
                return list((List<Object>) value, column);
            }
            return scalar(value, column);
        }

        Map<String, Object> map(Map<String, Object> map, String column) {
            Map<String, Object> copy = null;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                Object masked;
                if (secret(key)) {
                    masked = REDACTED.equals(value) ? value : REDACTED;
                } else {
                    masked = value(value, column == null ? key : column + "." + key);
                }
                if (masked != value) {
                    if (copy == null) {
                        copy = new LinkedHashMap<String, Object>(map);
                    }
                    copy.put(key, masked);
                }
            }
            return copy == null ? map : copy;
        }

        private List<Object> list(List<Object> list, String column) {
            List<Object> copy = null;
            for (int i = 0; i < list.size(); i++) {
                Object value = list.get(i);
                Object masked = value(value, column);
                if (masked != value) {
                    if (copy == null) {
                        copy = new ArrayList<Object>(list);
                    }
                    copy.set(i, masked);
                }
            }
            return copy == null ? list : copy;
        }

        private Object scalar(Object value, String column) {
            if (policies.isEmpty()) {
                return value;
            }
            if (++scalars > MAX_SCALARS) {
                overBudget = true;
                return REDACTED;
            }
            Object current = value;
            for (MaskPolicy policy : policies) {
                try {
                    current = policy.mask(sourceId, column, current);
                } catch (VirtualMachineError e) {
                    if (!(e instanceof StackOverflowError)) {
                        throw e;
                    }
                    failed(policy, sourceId, e);
                    return REDACTED;
                } catch (Throwable t) {
                    failed(policy, sourceId, t);
                    return REDACTED;
                }
                if (current == null) {
                    return null;
                }
                if (!storable(current)) {
                    return REDACTED;
                }
            }
            return current;
        }
    }

    /**
     * A policy's return the bus can keep: a String, a Boolean or a finite JDK Number (a Number of another class is
     * an application object, whose methods the freeze would call on the publishing thread).
     */
    private static boolean storable(Object value) {
        if (value instanceof String || value instanceof Boolean) {
            return true;
        }
        if (!(value instanceof Number) || !value.getClass().getName().startsWith("java.")) {
            return false;
        }
        double d = ((Number) value).doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }
}
