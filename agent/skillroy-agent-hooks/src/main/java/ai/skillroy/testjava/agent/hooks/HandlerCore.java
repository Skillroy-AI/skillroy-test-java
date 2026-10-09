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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.EventBus;
import ai.skillroy.testjava.protocol.hook.HookCapture;
import ai.skillroy.testjava.protocol.hook.HookMode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine's side of the handler behind {@code Spy} (phase6-contracts.md sections 3.2 to 3.4, 4.3): the runtime
 * registry lookup by site key, the mode, the timing, the stub, the capture and the event. It names no {@code Spy} type
 * (the {@link HookHandler} that does is loaded only after the bootstrap append), so the engine can create it at any
 * time.
 *
 * <p><b>Threads.</b> Called on the application's thread that called a hooked method; never blocks (the event bus never
 * blocks), never takes a lock of the application. Its per-thread state is one {@code ThreadLocal<long[]>} (a
 * bootstrap type, so an application thread's map never pins the module's loader): the re-entrancy flag, the depth and
 * the start times of the hooked calls in flight on the thread. An {@code enter} while the same thread is already inside
 * the handler (a mask policy that calls a hooked method) returns at once.
 *
 * <p><b>Disarmed</b> (at detach, before the restore): every {@code enter} answers null and every {@code exit} is a
 * no-op, so that what the restore leaves behind is inert.
 */
final class HandlerCore {

    /** A value a STUB hook returns while armed, already of the method's return type. */
    static final class Armed {
        final Object value;

        Armed(Object value) {
            this.value = value;
        }
    }

    /** Thrown to {@code Spy} after the event was published, so that its failure counter counts a policy that threw. */
    static final class MaskFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MaskFailure() {
            super("a mask policy threw", null, false, false);
        }
    }

    private static final MaskFailure MASK_FAILURE = new MaskFailure();
    private static final int FRAMES = 64;
    private static final int IN_HANDLER = 0;
    private static final int DEPTH = 1;

    private final ConcurrentHashMap<String, Site> sites;
    private final EventBus events;
    private final Capture capture;
    private volatile ThreadLocal<long[]> frames = new ThreadLocal<long[]>();
    volatile boolean disarmed;

    HandlerCore(ConcurrentHashMap<String, Site> sites, EventBus events, Capture capture) {
        this.sites = sites;
        this.events = events;
        this.capture = capture;
    }

    /** Drops the thread-local (uninstall): the entries left in application threads hold {@code long[]} only. */
    void dropThreadState() {
        frames = new ThreadLocal<long[]>();
    }

    private long[] frames() {
        ThreadLocal<long[]> local = frames;
        long[] f = local.get();
        if (f == null) {
            f = new long[FRAMES + 2];
            local.set(f);
        }
        return f;
    }

    /** @return null to run the original body, or the {@link Armed} value of a STUB hook to skip it */
    Object enter(String key, Object self, Object[] args) {
        if (disarmed) {
            return null;
        }
        Site site = sites.get(key);
        if (site == null) {
            return null;
        }
        long[] f = frames();
        if (f[IN_HANDLER] != 0) {
            return null;
        }
        f[IN_HANDLER] = 1;
        try {
            HookEntry hook = site.hook;
            if (hook.mode == HookMode.STUB) {
                Armed armed = site.armed;
                if (armed != null) {
                    Map<String, Object> payload = payload(site, 0L, true);
                    publish(hook, payload);
                    return armed;
                }
            }
            int depth = (int) f[DEPTH];
            if (depth >= FRAMES) {
                depth = 0;
            }
            f[2 + depth] = System.nanoTime();
            f[DEPTH] = depth + 1;
            return null;
        } catch (RuntimeException e) {
            site.hook.error = "the handler threw " + e.getClass().getName();
            throw e;
        } catch (Error e) {
            site.hook.error = "the handler threw " + e.getClass().getName();
            throw e;
        } finally {
            f[IN_HANDLER] = 0;
        }
    }

    /** After the original body ran: one event (section 4.3). */
    void exit(String key, Object self, Object[] args, Object returned, Throwable thrown) {
        if (disarmed) {
            return;
        }
        Site site = sites.get(key);
        if (site == null) {
            return;
        }
        long[] f = frames();
        if (f[IN_HANDLER] != 0) {
            return;
        }
        long end = System.nanoTime();
        f[IN_HANDLER] = 1;
        boolean maskFailed = false;
        try {
            int depth = (int) f[DEPTH];
            long start = 0;
            if (depth > 0) {
                depth--;
                start = f[2 + depth];
                f[DEPTH] = depth;
            }
            HookEntry hook = site.hook;
            Map<String, Object> payload = payload(site, start == 0 ? 0L : end - start, false);
            if (thrown != null) {
                Map<String, Object> t = new LinkedHashMap<String, Object>();
                t.put("class", thrown.getClass().getName());
                t.put("message", capture.message(thrown));
                payload.put("thrown", t);
            }
            if (hook.mode == HookMode.CAPTURE) {
                maskFailed = capture(hook, site, payload, self, args, returned, thrown);
            }
            publish(hook, payload);
            if (maskFailed) {
                hook.error = "mask policy threw";
            }
        } catch (RuntimeException e) {
            site.hook.error = "the handler threw " + e.getClass().getName();
            throw e;
        } catch (Error e) {
            site.hook.error = "the handler threw " + e.getClass().getName();
            throw e;
        } finally {
            f[IN_HANDLER] = 0;
        }
        if (maskFailed) {
            throw MASK_FAILURE;
        }
    }

    private static Map<String, Object> payload(Site site, long durationNs, boolean stubbed) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("hook", site.hook.id);
        payload.put("class", site.className);
        payload.put("method", site.method);
        payload.put("descriptor", site.descriptor);
        payload.put("thread", Thread.currentThread().getName());
        payload.put("durationNs", Long.valueOf(durationNs));
        payload.put("stubbed", Boolean.valueOf(stubbed));
        return payload;
    }

    /** Adds {@code args}, {@code self} and {@code returned} as asked; @return true if a mask policy threw. */
    private boolean capture(HookEntry hook, Site site, Map<String, Object> payload, Object self, Object[] args,
                            Object returned, Throwable thrown) {
        HookCapture asked = hook.capture;
        Capture.Run run = capture.run(hook.spec.capabilityId());
        Map<String, Object> captured = new LinkedHashMap<String, Object>();
        for (Integer index : asked.args()) {
            int i = index.intValue();
            if (args == null || i >= args.length) {
                continue;
            }
            Object value = hook.secretArgs.contains(index) ? Capture.REDACTED : run.top(args[i], "arg" + i);
            captured.put(String.valueOf(i), value);
        }
        if (!asked.args().isEmpty()) {
            payload.put("args", captured);
        }
        if (asked.self() && self != null) {
            payload.put("self", run.top(self, "self"));
        }
        if (asked.result() && !site.isVoid && thrown == null) {
            payload.put("returned", hook.secretResult ? Capture.REDACTED : run.top(returned, "return"));
        }
        if (run.truncated) {
            payload.put("truncated", Boolean.TRUE);
        }
        if (Sizes.estimate(payload) > capture.maxBytes()) {
            for (String key : new String[] {"args", "self", "returned"}) {
                if (payload.remove(key) != null) {
                    payload.put("truncated", Boolean.TRUE);
                    if (Sizes.estimate(payload) <= capture.maxBytes()) {
                        break;
                    }
                }
            }
        }
        return run.maskFailed;
    }

    private void publish(HookEntry hook, Map<String, Object> payload) {
        hook.fired(payload);
        events.publish(hook.topic, HooksModule.SOURCE, payload);
    }

    /** The approximate JSON size of a payload, for the output bound of section 4.3. */
    static final class Sizes {
        private Sizes() {
        }

        static long estimate(Object value) {
            if (value == null) {
                return 4;
            }
            if (value instanceof String) {
                return ((String) value).length() + 2;
            }
            if (value instanceof Map) {
                long n = 2;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    n += String.valueOf(e.getKey()).length() + 4 + estimate(e.getValue());
                }
                return n;
            }
            if (value instanceof java.util.List) {
                long n = 2;
                for (Object o : (java.util.List<?>) value) {
                    n += 1 + estimate(o);
                }
                return n;
            }
            return 24;
        }
    }
}
