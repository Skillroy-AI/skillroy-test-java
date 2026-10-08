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
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.scenario.web.WebPath;
import ai.skillroy.testjava.scenario.web.WebPathException;
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the CDP path of {@code skillroy-cdp} (id {@code cdp}), registered through the test-only
 * {@code META-INF/services/ai.skillroy.testjava.scenario.web.WebPath}, so that the executor finds it with
 * {@code ServiceLoader} exactly as it finds the real one. Every instance follows {@link #SCRIPT}, which a test
 * resets and programs: what {@code open} answers or throws, which primitives it serves and how, its own events (with
 * sequence numbers from 10001, apart from the agent's), what {@code saveTrace} writes or throws, and what was called,
 * opened, traced and closed.
 */
public final class FakeCdpPath implements WebPath {

    /** One call the path received. */
    record Call(String primitive, Map<String, Object> args, CallOptions options) {
    }

    /** Answers one primitive: a value, or an exception ({@code WebPathException}, {@code AgentRpcException}, …). */
    @FunctionalInterface
    interface Answer {
        Object answer(Map<String, Object> args) throws Exception;
    }

    /** What the instances do and what they saw. */
    static final class Script {
        volatile String id;
        volatile String openReason;
        volatile Exception openFailure;
        volatile RuntimeException closeFailure;
        volatile Exception traceFailure;
        /** What {@code saveTrace} writes to its target: null writes no file (a path whose tracing never started). */
        volatile byte[] trace;
        volatile Map<String, Object> openInfo;
        volatile Set<String> serves;
        final Map<String, Answer> answers = new ConcurrentHashMap<>();
        final List<Event> events = new CopyOnWriteArrayList<>();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<FakeCdpPath> instances = new CopyOnWriteArrayList<>();
        /** Records the path appends through its run context during {@code open} (a {@code web.recover}, say). */
        final List<Map<String, Object>> recordsOnOpen = new CopyOnWriteArrayList<>();
        private long seq;

        /** Back to a usable path that serves every web primitive but the agent's three, answering nothing yet. */
        synchronized void reset() {
            id = "cdp";
            openReason = null;
            openFailure = null;
            closeFailure = null;
            traceFailure = null;
            trace = "PK fake trace".getBytes(StandardCharsets.UTF_8);
            openInfo = new LinkedHashMap<>(Map.of("endpoint", "127.0.0.1:9222", "browserVersion", "Chrome/152",
                    "connectMs", 42L, "targets", 2L, "unresponsive", 0L));
            serves = Set.of("web.evaluate", "web.ariaSnapshot", "web.dom.text", "web.dom.click", "web.dom.fill",
                    "web.dispatchEvent", "web.tap", "web.waitFlag", "web.resize", "web.bitmap");
            answers.clear();
            events.clear();
            calls.clear();
            instances.clear();
            recordsOnOpen.clear();
            seq = 10_000L;
        }

        /** Appends one of the path's own events and returns its sequence number. */
        synchronized long publish(String topic, Map<String, Object> payload) {
            seq++;
            events.add(new Event(seq, 2_000_000L + seq, topic, "cdp", null, null, payload));
            return seq;
        }

        synchronized long seq() {
            return seq;
        }

        /** The calls of one primitive. */
        List<Call> callsTo(String primitive) {
            return calls.stream().filter(c -> c.primitive().equals(primitive)).toList();
        }

        /** The one instance a run opened, or null. */
        FakeCdpPath opened() {
            return instances.stream().filter(p -> p.openedWith != null).findFirst().orElse(null);
        }
    }

    /** The script every instance follows. */
    static final Script SCRIPT = new Script();

    static {
        SCRIPT.reset();
    }

    volatile WebSettings openedWith;
    volatile WebRunContext context;
    volatile int closes;
    volatile Path traceSavedTo;

    /** The public no-argument constructor {@code ServiceLoader} needs. */
    public FakeCdpPath() {
        SCRIPT.instances.add(this);
    }

    @Override
    public String id() {
        return SCRIPT.id;
    }

    @Override
    public String open(AgentConnection agent, WebSettings settings, WebRunContext ctx) throws Exception {
        if (SCRIPT.openFailure != null) {
            throw SCRIPT.openFailure;
        }
        context = ctx;
        for (Map<String, Object> record : SCRIPT.recordsOnOpen) {
            ctx.record(record);
        }
        if (SCRIPT.openReason != null) {
            return SCRIPT.openReason;
        }
        openedWith = settings;
        return null;
    }

    @Override
    public Map<String, Object> openInfo() {
        return SCRIPT.openInfo;
    }

    @Override
    public boolean serves(String primitive) {
        return SCRIPT.serves.contains(primitive);
    }

    @Override
    public RpcResult call(String primitive, Map<String, Object> args, CallOptions options) throws Exception {
        SCRIPT.calls.add(new Call(primitive, new LinkedHashMap<>(args), options));
        Answer answer = SCRIPT.answers.get(primitive);
        if (answer == null) {
            throw WebPathException.unsupported("the fake cdp path has no answer for " + primitive);
        }
        long before = SCRIPT.seq();
        Object value = answer.answer(args);
        List<Event> events = new ArrayList<>();
        for (Event event : SCRIPT.events) {
            if (event.seq() > before) {
                events.add(event);
            }
        }
        return new RpcResult(value, events, before, SCRIPT.seq(), 2, "controller");
    }

    @Override
    public List<Event> eventsSince(long seq, String topics) {
        TopicFilter filter = TopicFilter.parse(topics);
        List<Event> out = new ArrayList<>();
        for (Event event : SCRIPT.events) {
            if (event.seq() > seq && filter.matches(event.topic())) {
                out.add(event);
            }
        }
        return out;
    }

    @Override
    public void saveTrace(Path target) throws Exception {
        if (SCRIPT.traceFailure != null) {
            throw SCRIPT.traceFailure;
        }
        traceSavedTo = target;
        byte[] bytes = SCRIPT.trace;
        if (bytes != null) {
            Files.write(target, bytes);
        }
    }

    @Override
    public void close() {
        closes++;
        if (SCRIPT.closeFailure != null) {
            throw SCRIPT.closeFailure;
        }
    }

    /** A class loader that sees everything this module's tests see except the registration of web paths. */
    static ClassLoader withoutWebPaths() {
        return new ClassLoader(FakeCdpPath.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals("META-INF/services/" + WebPath.class.getName())) {
                    return Collections.emptyEnumeration();
                }
                return super.getResources(name);
            }
        };
    }

    /** A class loader whose only registered web path names a class that does not exist. */
    static ClassLoader withABrokenWebPath(Path dir) throws IOException {
        Path services = Files.createDirectories(dir.resolve("META-INF/services"));
        Path file = Files.writeString(services.resolve(WebPath.class.getName()), "com.example.NoSuchWebPath\n");
        URL url = file.toUri().toURL();
        return new ClassLoader(FakeCdpPath.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals("META-INF/services/" + WebPath.class.getName())) {
                    return Collections.enumeration(List.of(url));
                }
                return super.getResources(name);
            }
        };
    }
}
