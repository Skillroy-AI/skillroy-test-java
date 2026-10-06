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

import ai.skillroy.testjava.agent.api.EventSink;
import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.SecretKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static ai.skillroy.testjava.agent.core.AgentHarness.withMeta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract amendment A34: a request's {@code _meta.secretParams} hides the named params' values from what the core
 * writes about the call (the agent log lines of the call and the error it answers with), while the primitive receives
 * them unchanged and the events published during the call keep their payloads as published (they are what the call
 * does, and every reader of the ring sees them).
 */
class SecretParamsTest {

    private static final String SHORT = "4711";
    private static final String LONG = "Corr3ct-Horse-Battery";
    /** A four-digit secret whose digits are also a year, an amount and a code in an application's event. */
    private static final String YEAR = "2026";

    @TempDir
    Path tmp;

    private AgentHarness agent;

    @AfterEach
    void stop() {
        TestState.leakRelease.countDown();
        TestState.appAvailable = true;
        if (agent != null) {
            agent.close();
        }
    }

    private void start(String... options) throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp, options);
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
    }

    private static SecretParams secrets(Object... keyValues) {
        Map<String, Object> params = map(keyValues);
        return SecretParams.of("p", withMeta(params, "secretParams", Arrays.asList(params.keySet().toArray())));
    }

    /** An application's event in which a timestamp, an amount and a code hold the digits of {@link #YEAR}. */
    private static Map<String, Object> appEvent() {
        return map("signedOnAt", "2026-10-03T10:00:00Z", "total", Long.valueOf(2026), "code", YEAR, "note",
                "staff 20261 ok");
    }

    @Test
    void theRuleOnTexts() {
        SecretParams s = secrets("value", SHORT, "other", LONG);
        assertEquals("<redacted>", s.redact(SHORT));
        assertEquals("<redacted>", s.redact(LONG));
        assertEquals("rejected <redacted> at entry", s.redact("rejected " + SHORT + " at entry"));
        assertEquals("'<redacted>'", s.redact("'" + SHORT + "'"));
        assertEquals("v=<redacted>;", s.redact("v=" + SHORT + ";"));
        assertEquals("47112 and x4711 stay", s.redact("47112 and x4711 stay"), "a short secret only as a token");
        assertEquals("pw<redacted>!", s.redact("pw" + LONG + "!"), "a long secret wherever it occurs");
        assertEquals("<redacted> <redacted>", s.redact(SHORT + " " + SHORT));
        assertEquals("nothing here", s.redact("nothing here"));
        assertEquals(null, s.redact((String) null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRuleOnValuesKeepsKeys() {
        SecretParams s = secrets("value", 4711L, "flag", true, "name", "");
        assertFalse(s.isEmpty());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("n", 4711L);
        data.put("d", 4711.0);
        data.put("other", 4712L);
        data.put("4711", "the key stays");
        data.put("list", Arrays.<Object>asList("4711", true, null));
        Map<String, Object> out = (Map<String, Object>) s.redact((Object) data);
        assertEquals("{\"n\":\"<redacted>\",\"d\":\"<redacted>\",\"other\":4712,\"4711\":\"the key stays\","
                + "\"list\":[\"<redacted>\",true,null]}", Json.write(out));
        assertEquals(Long.valueOf(4711), data.get("n"), "the original is not changed");
        assertEquals("true", s.redact("true"), "booleans are never secrets");
    }

    @Test
    void structuredParamsContributeTheirLeaves() {
        SecretParams s = secrets("login", map("user", "kim", "pin", Arrays.asList(SHORT)));
        assertEquals("<redacted>", s.redact("kim"));
        assertEquals("<redacted>", s.redact(SHORT));
    }

    @Test
    void absentMalformedAndUnnamed() {
        assertSame(SecretParams.NONE, SecretParams.of("p", null));
        assertSame(SecretParams.NONE, SecretParams.of("p", "not an object"));
        assertSame(SecretParams.NONE, SecretParams.of("p", map("value", SHORT)));
        assertSame(SecretParams.NONE, SecretParams.of("p", withMeta(map("value", SHORT), "correlationId", "c")));
        assertSame(SecretParams.NONE, SecretParams.of("p", withMeta(map("value", SHORT), "secretParams",
                Arrays.asList("missing"))), "a name that is not a param");
        assertSame(SecretParams.NONE, SecretParams.of("p", withMeta(map("value", SHORT), "secretParams",
                Arrays.asList("_meta"))));
        for (Object bad : Arrays.<Object>asList("value", Arrays.asList(1L), Collections.nCopies(101, "value"))) {
            RpcException e = assertThrows(RpcException.class, () -> SecretParams.of("p",
                    withMeta(map("value", SHORT), "secretParams", bad)), String.valueOf(bad));
            assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
            assertEquals("_meta.secretParams", e.error().data().get("param"));
            assertSame(SecretParams.NONE, SecretParams.lenient("p", withMeta(map("value", SHORT), "secretParams",
                    bad)));
        }
        assertEquals("x " + SHORT, SecretParams.NONE.redact("x " + SHORT));
        RpcError error = new RpcError(1, "m " + SHORT, null);
        assertSame(error, SecretParams.NONE.redact(error));
    }

    @Test
    void errorsAreRedactedInMessageAndData() {
        SecretParams s = secrets("value", SHORT);
        Map<String, Object> data = map("text", SHORT, "deep", map("list", Arrays.asList("got " + SHORT)));
        RpcError error = s.redact(new RpcError(ErrorCodes.INPUT_REJECTED, "rejected " + SHORT, data));
        assertEquals(ErrorCodes.INPUT_REJECTED, error.code());
        assertEquals("rejected <redacted>", error.message());
        assertEquals("{\"text\":\"<redacted>\",\"deep\":{\"list\":[\"got <redacted>\"]}}", Json.write(error.data()));
    }

    @Test
    void anEventPublishedUnderANamedCallKeepsItsPayloadForEveryReader() {
        // An event is what the call does, not what the core writes about it: the ring, every subscription and the
        // copy the call's result carries hold the payload as published, even where its values equal a secret.
        RingEventBus bus = new RingEventBus(16, new RingLog());
        final List<Event> delivered = new ArrayList<Event>();
        bus.subscribe(null, new EventSink() {
            @Override
            public void onEvent(Event event) {
                delivered.add(event);
            }
        });
        CallScope scope = new CallScope("c-1", null, secrets("value", YEAR));
        CallScope previous = CallScope.bind(scope);
        try {
            assertEquals(1L, bus.publish("app.signedOn", "app", appEvent()));
        } finally {
            CallScope.restore(previous);
        }
        List<Event> recorded = scope.close();
        String published = Json.write(appEvent());
        assertEquals(published, Json.write(bus.since(0, null, 10).get(0).payload()), "the ring");
        assertEquals(published, Json.write(delivered.get(0).payload()), "a subscription");
        assertEquals(published, Json.write(recorded.get(0).payload()), "the result's copy");
        assertEquals("c-1", recorded.get(0).correlationId(), "still stamped with the call's correlation id");
    }

    // --- through the running agent ---

    @SuppressWarnings("unchecked")
    private List<Object> logLines() throws Exception {
        return (List<Object>) agent.valueMap("agent.log", map("n", Long.valueOf(2000))).get("lines");
    }

    @SuppressWarnings("unchecked")
    private List<Object> events(String topic) throws Exception {
        Map<String, Object> value = agent.valueMap("evt.query", map("topics", topic));
        return (List<Object>) value.get("events");
    }

    /** The payload of the only event of {@code topic} in a list of event JSON objects. */
    private static Object payloadOf(Object events, String topic) {
        Object found = null;
        for (Object event : (List<?>) events) {
            if (topic.equals(((Map<?, ?>) event).get("topic"))) {
                assertNull(found, "one " + topic + " event: " + events);
                found = ((Map<?, ?>) event).get("payload");
            }
        }
        assertNotNull(found, "a " + topic + " event: " + events);
        return found;
    }

    /** Waits until one log line contains {@code text} and returns it. */
    private String awaitLine(final String text) throws Exception {
        final String[] hit = new String[1];
        AgentHarness.await("a log line with '" + text + "'", 10000, () -> {
            for (Object line : logLines()) {
                if (String.valueOf(line).contains(text)) {
                    hit[0] = String.valueOf(line);
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        });
        return hit[0];
    }

    @Test
    void theCoreHidesSecretParamsInWhatItWritesAboutTheCallOnly() throws Exception {
        start();

        // Success: the primitive gets the value, and the events it published carry it as published.
        Map<String, Object> ok = agent.result("test.leak", withMeta(map("value", LONG), "secretParams",
                Arrays.asList("value")));
        assertEquals(Arrays.<Object>asList(LONG), TestState.LEAK_SEEN, "the primitive receives the value unchanged");
        assertEquals(LONG, ((Map<?, ?>) ok.get("value")).get("seen"), "the result value is the primitive's own");
        String carried = Json.write(ok.get("events"));
        assertTrue(carried.contains("\"echo\":\"" + LONG + "\""), carried);
        assertTrue(carried.contains("entered " + LONG), carried);
        assertFalse(carried.contains(SecretKeys.REDACTED), carried);

        // A JSON-RPC error from the primitive and an unexpected exception.
        Map<String, Object> rejected = agent.error("test.leak", withMeta(map("value", SHORT, "mode", "rpcError"),
                "secretParams", Arrays.asList("value")));
        assertEquals((long) ErrorCodes.INPUT_REJECTED, code(rejected));
        assertEquals("rejected input <redacted>", rejected.get("message"));
        assertEquals("{\"text\":\"<redacted>\",\"list\":[\"<redacted>\",\"keep\"]}", Json.write(data(rejected)));
        Map<String, Object> failed = agent.error("test.leak", withMeta(map("value", SHORT, "mode", "fail"),
                "secretParams", Arrays.asList("value")));
        assertEquals((long) ErrorCodes.INTERNAL_ERROR, code(failed));
        assertEquals("IllegalStateException: bad value <redacted>", failed.get("message"));
        assertEquals("bad value <redacted>", data(failed).get("message"));
        assertEquals(Arrays.<Object>asList(LONG, SHORT, SHORT), TestState.LEAK_SEEN);

        // The module's events, read back from the ring, carry the values as published ...
        String events = Json.write(events("test.leak"));
        assertTrue(events.contains("\"echo\":\"" + LONG + "\""), events);
        assertTrue(events.contains("\"echo\":\"" + SHORT + "\""), events);
        assertTrue(events.contains("entered " + SHORT), events);
        assertFalse(events.contains(SecretKeys.REDACTED), events);
        // ... while the agent log lines about the calls never hold them.
        String log = String.valueOf(logLines());
        assertFalse(log.contains(LONG), log);
        assertFalse(log.matches("(?s).*(^|[^0-9A-Za-z])" + SHORT + "([^0-9A-Za-z]|$).*"), log);
        assertTrue(log.contains("typed <redacted> into the entry"), log);
        assertTrue(log.contains("value <redacted>"), log);
        assertTrue(log.contains("cause <redacted>"), log);

        // Without the list, nothing is hidden (the key rule does not know 'value'): the control for the above.
        Map<String, Object> plain = agent.error("test.leak", map("value", SHORT, "mode", "rpcError"));
        assertEquals("rejected input " + SHORT, plain.get("message"));
        assertTrue(String.valueOf(logLines()).contains("typed " + SHORT + " into the entry"));

        // A malformed list is refused before the primitive runs, in contract order after the method check.
        TestState.LEAK_SEEN.clear();
        Map<String, Object> malformed = agent.error("test.leak", withMeta(map("value", SHORT), "secretParams",
                "value"));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(malformed));
        assertEquals("_meta.secretParams", data(malformed).get("param"));
        assertTrue(TestState.LEAK_SEEN.isEmpty());
        assertEquals((long) ErrorCodes.METHOD_NOT_FOUND, code(agent.error("no.such.method",
                withMeta(map("value", SHORT), "secretParams", "value"))));
    }

    @Test
    void anApplicationEventWhoseValuesEqualAShortSecretReachesEveryReaderAsPublished() throws Exception {
        // A four-digit PIN equal to a year: the application's event of the same call holds a timestamp, an amount
        // and a code with those digits, and a wait, a query and the result see them as published.
        start();
        long before = agent.runtime.context().events().lastSeq();
        Map<String, Object> ok = agent.result("test.leak", withMeta(map("value", YEAR, "event", appEvent()),
                "secretParams", Arrays.asList("value")));
        String published = Json.write(appEvent());
        assertEquals(published, Json.write(payloadOf(ok.get("events"), "test.app")), "the result's events");
        assertEquals(published, Json.write(payloadOf(events("test.app"), "test.app")), "evt.query");
        Map<String, Object> awaited = agent.valueMap("sync.await", map("topics", "test.app", "since",
                Long.valueOf(before), "match", map("signedOnAt", "2026-10-03T10:00:00Z", "total", YEAR, "code", YEAR),
                "timeoutMs", Long.valueOf(2000)));
        assertEquals(published, Json.write(awaited.get("payload")), "sync.await matches the published values");
        assertEquals(YEAR, ((Map<?, ?>) payloadOf(ok.get("events"), "test.leak")).get("echo"),
                "the module's echo is its own doing too");

        // What the core writes about the call still hides the value: its log lines and its error.
        Map<String, Object> rejected = agent.error("test.leak", withMeta(map("value", YEAR, "mode", "rpcError"),
                "secretParams", Arrays.asList("value")));
        assertEquals("rejected input <redacted>", rejected.get("message"));
        assertEquals("{\"text\":\"<redacted>\",\"list\":[\"<redacted>\",\"keep\"]}", Json.write(data(rejected)));
        String log = String.valueOf(logLines());
        assertTrue(log.contains("typed <redacted> into the entry"), log);
        assertFalse(log.contains("typed " + YEAR), log);
        assertFalse(log.contains("cause " + YEAR), log);
    }

    @Test
    void aFailedPostedTaskIsLoggedUnderItsCallsScopeOnEveryPath() throws Exception {
        // The broker logs the failure of a task it ran for a call after the task restored the thread's previous
        // scope; the line still hides the call's value. Every path: the dispatch of an EDT_ASYNC primitive,
        // call(EDT_ASYNC), post under each policy, APP through the provider and APP without an available provider
        // (it then runs on WORKER).
        start("coretest.appThread", "true");
        agent.result("test.leak.async", withMeta(map("value", LONG), "secretParams", Arrays.asList("value")));
        List<String[]> paths = new ArrayList<String[]>();
        for (String policy : new String[] {"CALLER", "WORKER", "EDT_SYNC", "EDT_ASYNC", "APP"}) {
            paths.add(new String[] {"post", policy, "post " + policy});
        }
        paths.add(new String[] {"call", "EDT_ASYNC", "call EDT_ASYNC"});
        for (String[] path : paths) {
            agent.result("test.leak.post", withMeta(map("value", LONG, "how", path[0], "policy", path[1], "tag",
                    path[2]), "secretParams", Arrays.asList("value")));
        }
        TestState.appAvailable = false;
        agent.result("test.leak.post", withMeta(map("value", LONG, "policy", "APP", "tag", "post APP fallback"),
                "secretParams", Arrays.asList("value")));

        String line = awaitLine("via dispatch EDT_ASYNC.");
        assertTrue(line.contains("posted task failed: java.lang.IllegalStateException: bad value <redacted> via "
                + "dispatch EDT_ASYNC."), line);
        assertTrue(line.contains("caused by java.lang.IllegalArgumentException: cause <redacted>"), line);
        for (String[] path : paths) {
            line = awaitLine("via " + path[2] + ".");
            String failed = "APP".equals(path[1]) ? "posted APP task failed" : "posted task failed";
            assertTrue(line.contains(failed + ": java.lang.IllegalStateException: bad value <redacted> via " + path[2]
                    + "."), line);
        }
        line = awaitLine("via post APP fallback.");
        assertTrue(line.contains("posted task failed: java.lang.IllegalStateException: bad value <redacted> via post "
                + "APP fallback."), line);
        String log = String.valueOf(logLines());
        assertFalse(log.contains(LONG), log);
        assertEquals(paths.size() + 2, TestState.LEAK_SEEN.size(), "every primitive received the value");
        for (Object seen : TestState.LEAK_SEEN) {
            assertEquals(LONG, seen);
        }
    }

    /**
     * Calls {@code test.leak.slow} with {@code value} named secret and a timeout it outlives, lets it end, and
     * returns the agent log lines written from the call on.
     */
    private List<String> timedOutCall(String value) throws Exception {
        int before = logLines().size();
        Map<String, Object> timeout = agent.error("test.leak.slow", withMeta(map("value", value), "secretParams",
                Arrays.asList("value"), "timeoutMs", Long.valueOf(300)));
        assertEquals((long) ErrorCodes.TIMEOUT, code(timeout));
        assertFalse(Json.write(timeout).contains(value), Json.write(timeout));
        TestState.leakRelease.countDown();
        awaitLine("ended after");
        List<String> lines = new ArrayList<String>();
        List<Object> all = logLines();
        for (Object line : all.subList(before, all.size())) {
            lines.add(String.valueOf(line));
        }
        return lines;
    }

    private static void assertLine(List<String> lines, String text) {
        for (String line : lines) {
            if (line.contains(text)) {
                return;
            }
        }
        fail("no line with '" + text + "' in " + lines);
    }

    @Test
    void theLinesOfATimedOutAndAbandonedCallNeverHoldTheValue() throws Exception {
        start();
        List<String> lines = timedOutCall(LONG);
        assertLine(lines, "call of test.leak.slow timed out and still runs on skillroy-worker-");
        assertLine(lines, "the abandoned call of test.leak.slow on skillroy-worker-");
        assertLine(lines, "waiting with <redacted>");
        assertLine(lines, "released with <redacted>");
        assertFalse(String.valueOf(lines).contains(LONG), String.valueOf(lines));
        assertEquals(Arrays.<Object>asList(LONG), TestState.LEAK_SEEN);
    }

    @Test
    void theTimedOutAndAbandonedCallLinesPassThroughTheCallsReplacement() throws Exception {
        // The two lines name only the primitive and the thread, so no realistic value reaches them; a secret that
        // equals the primitive's name shows that both are replaced like every line the core writes about the call.
        start();
        List<String> lines = timedOutCall("test.leak.slow");
        assertLine(lines, "call of <redacted> timed out and still runs on skillroy-worker-");
        assertLine(lines, "the abandoned call of <redacted> on skillroy-worker-");
        assertFalse(String.valueOf(lines).contains("test.leak.slow"), String.valueOf(lines));
    }
}
