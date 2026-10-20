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

import ai.skillroy.testjava.agent.core.fixture.CoreTestModule;
import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.CallWarning;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;

import static ai.skillroy.testjava.agent.core.AgentHarness.code;
import static ai.skillroy.testjava.agent.core.AgentHarness.data;
import static ai.skillroy.testjava.agent.core.AgentHarness.map;
import static ai.skillroy.testjava.agent.core.AgentHarness.withMeta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON-RPC dispatch: framing, batches, notifications, every error path and the result envelope. */
class DispatchTest {

    @TempDir
    Path tmp;

    private AgentHarness agent;

    @BeforeEach
    void start() throws Exception {
        TestState.reset();
        agent = AgentHarness.start(tmp);
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
    }

    @AfterEach
    void stop() {
        TestState.blocker.countDown();
        agent.close();
    }

    private AgentHarness.Reply post(String body) throws Exception {
        return agent.send("POST", "/rpc", body, true);
    }

    @Test
    void singleRequestEchoesTheIdAndCarriesTheEnvelope() throws Exception {
        AgentHarness.Reply reply = post("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"test.echo\","
                + "\"params\":{\"value\":\"v\",\"_meta\":{\"correlationId\":\"c-1\",\"traceparent\":\"00-t-01\"}}}");
        assertEquals(200, reply.status);
        Map<String, Object> response = reply.json();
        assertEquals("2.0", response.get("jsonrpc"));
        assertEquals("abc", response.get("id"));
        @SuppressWarnings("unchecked")
        RpcResult result = RpcResult.fromJson((Map<String, Object>) response.get("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertEquals(map("value", "v"), value.get("args"), "args must not contain _meta");
        assertEquals("c-1", value.get("correlationId"));
        assertEquals("00-t-01", value.get("traceparent"));
        assertTrue(((Number) value.get("remainingMs")).longValue() > 0);
        assertTrue(result.thread().startsWith("skillroy-http-"), result.thread());
        assertTrue(result.seqAfter() >= result.seqBefore());
        assertTrue(result.durationMs() >= 0);
    }

    @Test
    void numericAndNullIdsAreEchoed() throws Exception {
        assertEquals(Long.valueOf(42), post("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"agent.info\"}").json()
                .get("id"));
        Map<String, Object> nullId = post("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"agent.info\"}").json();
        assertTrue(nullId.containsKey("id"));
        assertNull(nullId.get("id"));
        assertTrue(nullId.containsKey("result"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchRunsInOrderAndSkipsNotifications() throws Exception {
        AgentHarness.Reply reply = post("["
                + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.mutate\"},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"test.mutate\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"test.mutate\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"no.such\"},"
                + "17]");
        assertEquals(200, reply.status);
        List<Object> responses = (List<Object>) Json.parse(reply.body);
        assertEquals(4, responses.size());
        Map<String, Object> first = (Map<String, Object>) responses.get(0);
        Map<String, Object> second = (Map<String, Object>) responses.get(1);
        assertEquals(Long.valueOf(1), first.get("id"));
        assertEquals(Long.valueOf(1), ((Map<String, Object>) first.get("result")).get("value"));
        assertEquals(Long.valueOf(2), second.get("id"));
        assertEquals(Long.valueOf(3), ((Map<String, Object>) second.get("result")).get("value"),
                "the notification in between ran");
        assertEquals((long) ErrorCodes.METHOD_NOT_FOUND,
                code((Map<String, Object>) ((Map<String, Object>) responses.get(2)).get("error")));
        Map<String, Object> invalid = (Map<String, Object>) responses.get(3);
        assertNull(invalid.get("id"));
        assertEquals((long) ErrorCodes.INVALID_REQUEST, code((Map<String, Object>) invalid.get("error")));
    }

    @Test
    void notificationsAnswer204AndStillRun() throws Exception {
        AgentHarness.Reply single = post("{\"jsonrpc\":\"2.0\",\"method\":\"test.mutate\"}");
        assertEquals(204, single.status);
        assertEquals("", single.body);
        AgentHarness.Reply batch = post("[{\"jsonrpc\":\"2.0\",\"method\":\"test.mutate\"},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"trace.mark\",\"params\":{\"label\":\"n\"}}]");
        assertEquals(204, batch.status);
        assertEquals(2, TestState.MUTATIONS.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void framingErrors() throws Exception {
        Map<String, Object> parse = post("{not json").json();
        assertNull(parse.get("id"));
        assertEquals((long) ErrorCodes.PARSE_ERROR, code((Map<String, Object>) parse.get("error")));
        assertEquals((long) ErrorCodes.INVALID_REQUEST,
                code((Map<String, Object>) post("[]").json().get("error")));
        assertEquals((long) ErrorCodes.INVALID_REQUEST, code((Map<String, Object>) post(
                "{\"id\":1,\"method\":\"agent.info\"}").json().get("error")), "jsonrpc member missing");
        assertEquals((long) ErrorCodes.INVALID_REQUEST, code((Map<String, Object>) post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":5}").json().get("error")));
        assertEquals((long) ErrorCodes.INVALID_REQUEST, code((Map<String, Object>) post(
                "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"agent.info\"}").json().get("error")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void hostileBodiesAreAnsweredAsJsonRpcErrorsAndTheServerKeepsServing() throws Exception {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 50000; i++) {
            deep.append('[');
        }
        assertEquals((long) ErrorCodes.PARSE_ERROR, code((Map<String, Object>) post(deep.toString()).json()
                .get("error")), "nesting far beyond the codec's limit");
        assertEquals((long) ErrorCodes.PARSE_ERROR, code((Map<String, Object>) post("").json().get("error")));
        AgentHarness.Reply binary = agent.send("POST", "/rpc", new String(new char[] {0xFFFD, 0, 1, '{'}), true);
        assertEquals(200, binary.status);
        assertEquals((long) ErrorCodes.PARSE_ERROR, code((Map<String, Object>) binary.json().get("error")));
        assertEquals((long) ErrorCodes.INVALID_REQUEST, code((Map<String, Object>) post("\"agent.info\"").json()
                .get("error")));
        assertEquals((long) ErrorCodes.INVALID_REQUEST, code((Map<String, Object>) post("null").json()
                .get("error")));
        for (String meta : new String[] {"7", "[]", "{\"timeoutMs\":\"soon\",\"confirm\":true}",
                "{\"timeoutMs\":0,\"confirm\":true}", "{\"timeoutMs\":-5,\"confirm\":true}",
                "{\"confirm\":\"perhaps\"}", "{\"correlationId\":{},\"confirm\":true}"}) {
            Map<String, Object> response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.destroy\","
                    + "\"params\":{\"_meta\":" + meta + "}}").json();
            assertEquals((long) ErrorCodes.INVALID_PARAMS, code((Map<String, Object>) response.get("error")),
                    "_meta " + meta);
        }
        // A batch beyond Protocol.MAX_BATCH is refused whole with one error; one at the cap is answered in full.
        Map<String, Object> refused = (Map<String, Object>) Json.parse(post(batch(2000)).body);
        assertEquals((long) ErrorCodes.INVALID_REQUEST, code((Map<String, Object>) refused.get("error")));
        assertEquals(null, refused.get("id"));
        List<Object> answers = (List<Object>) Json.parse(post(batch(Protocol.MAX_BATCH)).body);
        assertEquals(Protocol.MAX_BATCH, answers.size());
        assertEquals(Long.valueOf(Protocol.MAX_BATCH - 1),
                ((Map<String, Object>) answers.get(Protocol.MAX_BATCH - 1)).get("id"));
        assertEquals("ok", agent.value("test.degraded", null), "still serving");
    }

    private static String batch(int elements) {
        StringBuilder batch = new StringBuilder("[");
        for (int i = 0; i < elements; i++) {
            batch.append(i == 0 ? "" : ",").append("{\"jsonrpc\":\"2.0\",\"id\":").append(i)
                    .append(",\"method\":\"test.echo\"}");
        }
        return batch.append(']').toString();
    }

    @Test
    void methodNotFoundAndInvalidParams() throws Exception {
        Map<String, Object> missing = agent.error("no.such", null);
        assertEquals((long) ErrorCodes.METHOD_NOT_FOUND, code(missing));
        assertEquals("no.such", data(missing).get("method"));
        AgentHarness.Reply array = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.echo\",\"params\":[1]}");
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(errorOf(array)));
        AgentHarness.Reply meta = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.echo\","
                + "\"params\":{\"_meta\":3}}");
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(errorOf(meta)));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("jvm.properties", map("names", "x"))));
        assertEquals((long) ErrorCodes.INVALID_PARAMS, code(agent.error("test.echo",
                withMeta(null, "timeoutMs", Long.valueOf(0)))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(AgentHarness.Reply reply) {
        return (Map<String, Object>) reply.json().get("error");
    }

    @Test
    void destructiveNeedsConfirm() throws Exception {
        Map<String, Object> error = agent.error("test.destroy", null);
        assertEquals((long) ErrorCodes.CONFIRM_REQUIRED, code(error));
        assertEquals((long) ErrorCodes.CONFIRM_REQUIRED,
                code(agent.error("test.destroy", withMeta(null, "confirm", Boolean.FALSE))));
        assertEquals("destroyed", agent.value("test.destroy", withMeta(null, "confirm", Boolean.TRUE)));
    }

    /**
     * phase6-contracts.md 12.9, D156 (security fix S42): a primitive that evaluates a caller's expression is refused in
     * read-only mode as a mutating one is, although it is not mutating; served otherwise; its schema entry carries
     * {@code script: true}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aScriptPrimitiveIsReadOnlyLikeAMutatingOne() throws Exception {
        assertEquals(Long.valueOf(1), agent.value("test.script", null), "served when the agent is not read-only");
        Map<String, Object> descriptor = null;
        for (Object d : (List<Object>) agent.get("/schema").json().get("primitives")) {
            if ("test.script".equals(((Map<String, Object>) d).get("name"))) {
                descriptor = (Map<String, Object>) d;
            }
        }
        assertNotNull(descriptor);
        assertEquals(Boolean.TRUE, descriptor.get("script"));
        assertEquals(Boolean.FALSE, descriptor.get("mutating"));
        agent.close();
        TestState.reset();
        agent = AgentHarness.start(tmp, "readOnly", "true");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
        Map<String, Object> error = agent.error("test.script", null);
        assertEquals((long) ErrorCodes.READ_ONLY, code(error));
        assertEquals("test.script", data(error).get("primitive"));
        assertEquals(0, TestState.MUTATIONS.get(), "the expression never ran");
        assertEquals(map("value", "v"), ((Map<String, Object>) agent.value("test.echo", map("value", "v")))
                .get("args"), "a plain read is still served");
    }

    @Test
    void capabilityGates() throws Exception {
        Map<String, Object> off = agent.error("test.off", null);
        assertEquals((long) ErrorCodes.CAPABILITY_UNAVAILABLE, code(off));
        assertEquals("switched off", data(off).get("reason"));
        assertEquals("coretest.off", data(off).get("capability"));
        assertEquals("ok", agent.value("test.degraded", null), "DEGRADED runs");
        assertEquals("ok", agent.value("test.needsProbe", null));
        assertEquals("ok", agent.value("test.needsReady", null));
    }

    @Test
    void timeoutOnTheCallerThreadReportsPrimitiveAndTimeout() throws Exception {
        long start = System.nanoTime();
        Map<String, Object> error = agent.error("test.block", withMeta(null, "timeoutMs", Long.valueOf(300)));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals((long) ErrorCodes.TIMEOUT, code(error));
        assertEquals("test.block", data(error).get("primitive"));
        assertEquals(Long.valueOf(300), data(error).get("timeoutMs"));
        assertFalse(data(error).containsKey("modalOpen"));
        assertTrue(tookMs < 20000, "took " + tookMs);
        awaitNoAgentThreadInside("CoreTestModule");
    }

    /** The timed-out primitive was interrupted: no agent thread is left inside the given class. */
    private static void awaitNoAgentThreadInside(final String className) throws Exception {
        AgentHarness.await("the timed-out task to end", 30000, () -> {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                if (e.getKey().getName().startsWith("skillroy-")) {
                    for (StackTraceElement frame : e.getValue()) {
                        if (frame.getClassName().contains(className)) {
                            return Boolean.FALSE;
                        }
                    }
                }
            }
            return Boolean.TRUE;
        });
    }

    @Test
    void timeoutOnAWorkerInterruptsTheTask() throws Exception {
        Map<String, Object> error = agent.error("test.block.worker", withMeta(null, "timeoutMs", Long.valueOf(300)));
        assertEquals((long) ErrorCodes.TIMEOUT, code(error));
        assertEquals("test.block.worker", data(error).get("primitive"));
        // The agent stays responsive and the interrupted worker does not linger as a blocked call.
        assertEquals("ok", agent.value("test.degraded", null));
        awaitNoAgentThreadInside("CoreTestModule");
    }

    @Test
    void timeoutOnABlockedEdtNeverWaitsForIt() throws Exception {
        final CountDownLatch edtRelease = new CountDownLatch(1);
        final CountDownLatch edtBlocked = new CountDownLatch(1);
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                edtBlocked.countDown();
                try {
                    edtRelease.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        long seqBefore = agent.runtime.context().events().lastSeq();
        try {
            assertTrue(edtBlocked.await(30, TimeUnit.SECONDS));
            Map<String, Object> error = agent.error("test.where.edtSync",
                    withMeta(null, "timeoutMs", Long.valueOf(300)));
            assertEquals((long) ErrorCodes.TIMEOUT, code(error));
            assertEquals("test.where.edtSync", data(error).get("primitive"));
        } finally {
            edtRelease.countDown();
        }
        // The cancelled task never runs once the EDT is free again.
        final CountDownLatch drained = new CountDownLatch(1);
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                drained.countDown();
            }
        });
        assertTrue(drained.await(30, TimeUnit.SECONDS));
        assertTrue(agent.runtime.context().events().since(seqBefore, TopicFilter.parse("test.where"), 10).isEmpty());
        assertEquals("AWT-EventQueue", ((String) agent.value("test.where.edtSync", null)).replaceAll("-\\d+$", ""));
    }

    @Test
    void aTimeoutWhileAModalDialogShowsSaysSoAndTheEdtStillAnswers() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        final JDialog[] dialog = new JDialog[1];
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                dialog[0] = new JDialog((Frame) null, "modal", true);
                dialog[0].setSize(200, 100);
                dialog[0].setVisible(true);   // blocks this event in a nested loop until the dialog is disposed
            }
        });
        try {
            AgentHarness.await("modal dialog showing", 30000, () -> Awt.modalDialogShowing());
            Map<String, Object> error = agent.error("test.block", withMeta(null, "timeoutMs", Long.valueOf(300)));
            assertEquals((long) ErrorCodes.TIMEOUT, code(error));
            assertEquals(Boolean.TRUE, data(error).get("modalOpen"));
            assertTrue(((String) agent.value("test.where.edtSync", null)).startsWith("AWT-EventQueue-"),
                    "a modal dialog pumps events, so EDT reads still work");
        } finally {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (dialog[0] != null) {
                        dialog[0].dispose();
                    }
                }
            });
        }
        AgentHarness.await("modal dialog gone", 30000, () -> !Awt.modalDialogShowing());
        Map<String, Object> error = agent.error("test.block", withMeta(null, "timeoutMs", Long.valueOf(300)));
        assertFalse(data(error).containsKey("modalOpen"));
    }

    @Test
    void exceptionsBecomeInternalErrorsWithDetail() throws Exception {
        Map<String, Object> error = agent.error("test.fail", null);
        assertEquals((long) ErrorCodes.INTERNAL_ERROR, code(error));
        assertEquals("java.lang.IllegalStateException", data(error).get("exception"));
        assertEquals("boom", data(error).get("message"));
        List<?> stack = (List<?>) data(error).get("stack");
        assertFalse(stack.isEmpty());
        assertTrue(stack.size() <= 10);
        Map<String, Object> nan = agent.error("test.nan", null);
        assertEquals((long) ErrorCodes.INTERNAL_ERROR, code(nan), "a value JSON cannot hold");
    }

    @Test
    void rpcExceptionsPassThroughVerbatim() throws Exception {
        Map<String, Object> error = agent.error("test.rpcError", null);
        assertEquals((long) ErrorCodes.NOT_FOUND, code(error));
        assertEquals("nothing here", error.get("message"));
        assertEquals(map("what", "x"), data(error));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resultCarriesTheCallsOwnEventsCappedAtFifty() throws Exception {
        long before = agent.runtime.context().events().lastSeq();
        Map<String, Object> result = agent.result("test.publish",
                withMeta(map("n", Long.valueOf(60)), "correlationId", "burst-1"));
        RpcResult parsed = RpcResult.fromJson(result);
        assertEquals(50, parsed.events().size());
        assertEquals("burst-1", parsed.events().get(0).correlationId());
        assertEquals(Long.valueOf(0), parsed.events().get(0).payload().get("i"));
        assertTrue(parsed.seqBefore() >= before);
        assertTrue(parsed.seqAfter() - parsed.seqBefore() >= 60);
        // Events published by other calls are not mixed in.
        RpcResult other = RpcResult.fromJson(agent.result("test.publish",
                withMeta(map("n", Long.valueOf(2)), "correlationId", "burst-2")));
        assertEquals(2, other.events().size());
        assertEquals("burst-2", other.events().get(1).correlationId());
        RpcResult none = RpcResult.fromJson(agent.result("test.publish", map("n", Long.valueOf(1))));
        assertEquals(1, none.events().size(), "events are collected even without a correlation id");
        assertNull(none.events().get(0).correlationId());
    }

    @Test
    void metaTimeoutIsCappedAndSyncAwaitGetsItsOwnPlusOneSecond() throws Exception {
        Map<String, Object> echo = agent.valueMap("test.echo", withMeta(null, "timeoutMs", Long.valueOf(999999)));
        assertTrue(((Number) echo.get("remainingMs")).longValue() <= Dispatcher.MAX_TIMEOUT_MS);
        Map<String, Object> error = agent.error("sync.await", map("topics", "never.*", "since",
                Long.valueOf(0), "timeoutMs", Long.valueOf(200)));
        assertEquals((long) ErrorCodes.TIMEOUT, code(error));
        assertTrue(data(error).containsKey("lastSeq"), "sync.await's own TIMEOUT, not the dispatcher's");
        assertFalse(data(error).containsKey("primitive"));
    }

    @Test
    void inProcessCallUsesTheSameDispatcher() {
        Map<String, Object> response = agent.runtime.call("test.destroy", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals((long) ErrorCodes.CONFIRM_REQUIRED, code(error));
        @SuppressWarnings("unchecked")
        Map<String, Object> ok = (Map<String, Object>) agent.runtime.call("trace.mark", map("label", "x"))
                .get("result");
        assertTrue(((Map<?, ?>) ok.get("value")).containsKey("seq"));
    }

    // ---- phase8-contracts.md 7.1 (ES): the warning channel ----

    /** Restarts the agent with coretest's semantic-input fixture ({@code coretest.semanticInput=on}). */
    private void restartWithWarnings() throws Exception {
        agent.close();
        agent = AgentHarness.start(Files.createDirectories(tmp.resolve("warnings")), "coretest.semanticInput", "on");
        AgentHarness.await("target ready", 30000, () -> agent.runtime.isReady());
    }

    private static Map<String, Object> warning(String code, String message, Map<String, Object> data) {
        Map<String, Object> w = map("code", code);
        if (message != null) {
            w.put("message", message);
        }
        if (data != null) {
            w.put("data", data);
        }
        return w;
    }

    @Test
    void warningsAreCollectedInOrderIntoTheResultsEnvelope() throws Exception {
        restartWithWarnings();
        Map<String, Object> result = agent.result("test.warn", map("warnings", Arrays.asList(
                warning("VALUE_COERCED", "taken as 4005", map("taken", "4005")),
                warning("lending.PASSWORD_REQUIRED", null, null))));
        RpcResult parsed = RpcResult.fromJson(result);
        assertEquals(map("raised", Long.valueOf(2)), parsed.value(), "the value stays the primitive's own");
        assertEquals(2, parsed.warnings().size());
        assertEquals("VALUE_COERCED", parsed.warnings().get(0).code());
        assertEquals("taken as 4005", parsed.warnings().get(0).message());
        assertEquals(map("taken", "4005"), parsed.warnings().get(0).data());
        assertEquals("lending.PASSWORD_REQUIRED", parsed.warnings().get(1).code());
        assertFalse(agent.result("test.warn", map("warnings", new ArrayList<Object>())).containsKey("warnings"),
                "no warning, no member: the result's JSON is as before");
        assertFalse(agent.result("test.echo", null).containsKey("warnings"));
    }

    @Test
    void theSeventeenthAndLaterBecomeOneTooManyWarnings() throws Exception {
        restartWithWarnings();
        List<Object> twenty = new ArrayList<Object>();
        for (int i = 0; i < 20; i++) {
            twenty.add(warning("VALUE_DROPPED", "w" + i, null));
        }
        RpcResult parsed = RpcResult.fromJson(agent.result("test.warn", map("warnings", twenty)));
        assertEquals(17, parsed.warnings().size());
        assertEquals("w15", parsed.warnings().get(15).message());
        assertEquals(CallWarning.TOO_MANY_WARNINGS, parsed.warnings().get(16).code());
        assertEquals(Long.valueOf(4), parsed.warnings().get(16).data().get("dropped"));
    }

    @Test
    void aSecretParametersValueIsRedactedFromMessageAndData() throws Exception {
        restartWithWarnings();
        String secret = "s3cret-Value9";
        RpcResult parsed = RpcResult.fromJson(agent.result("test.warn", withMeta(map("secret", secret, "warnings",
                Arrays.asList(warning("VALUE_COERCED", "typed " + secret + " here", map("taken", secret, "nested",
                        map("list", Arrays.asList("a", secret + "!")))))), "secretParams",
                Arrays.asList("secret"))));
        CallWarning w = parsed.warnings().get(0);
        assertEquals("typed <redacted> here", w.message());
        assertEquals("<redacted>", w.data().get("taken"));
        assertFalse(Json.write(parsed.toJson()).contains(secret));
        RpcResult shortSecret = RpcResult.fromJson(agent.result("test.warn", withMeta(map("secret", "1234",
                "warnings", Arrays.asList(warning("VALUE_DROPPED", "pin 1234 dropped, 12345 kept", null))),
                "secretParams", Arrays.asList("secret"))));
        assertEquals("pin <redacted> dropped, 12345 kept", shortSecret.warnings().get(0).message(),
                "a short secret is replaced as a whole token (A34)");
    }

    @Test
    void aSecretStraddlingTheCutIsRedactedBeforeTheMessageIsCut() throws Exception {
        restartWithWarnings();
        String secret = "Kq7wZp2xLm4v";   // twelve characters
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 495; i++) {
            message.append('x');
        }
        message.append(secret).append("tail");
        RpcResult parsed = RpcResult.fromJson(agent.result("test.warn", withMeta(map("secret", secret, "warnings",
                Arrays.asList(warning("VALUE_COERCED", message.toString(), null))), "secretParams",
                Arrays.asList("secret"))));
        String cut = parsed.warnings().get(0).message();
        assertEquals(CallWarning.MAX_MESSAGE, cut.length());
        for (int i = 0; i + 8 <= secret.length(); i++) {
            assertFalse(cut.contains(secret.substring(i, i + 8)), "no 8-character piece of the secret survives");
        }
        assertTrue(cut.endsWith("<reda"), cut.substring(480));
        assertFalse(cut.contains(secret.substring(0, 5)), "not even the first characters straddle the cut");
    }

    @Test
    void aMalformedCodeIsAModuleBugThrownToThePrimitive() throws Exception {
        restartWithWarnings();
        Map<String, Object> error = agent.error("test.warn", map("warnings", Arrays.asList(warning("lower", "m",
                null))));
        assertEquals((long) ErrorCodes.INTERNAL_ERROR, code(error));
        assertTrue(String.valueOf(error.get("message")).contains("IllegalArgumentException"),
                String.valueOf(error.get("message")));
    }

    @Test
    void aWarningRaisedAfterTheCallReturnedIsDroppedAndLoggedOnce() throws Exception {
        restartWithWarnings();
        CoreTestModule.lateWarnRelease = new CountDownLatch(1);
        CoreTestModule.lateWarnDone = new CountDownLatch(1);
        RpcResult parsed = RpcResult.fromJson(agent.result("test.warn", map("late", Boolean.TRUE)));
        assertTrue(parsed.warnings().isEmpty());
        CoreTestModule.lateWarnRelease.countDown();
        assertTrue(CoreTestModule.lateWarnDone.await(30, TimeUnit.SECONDS));
        String log = String.valueOf(agent.runtime.context().log().tail(200));
        assertTrue(log.contains("a warning (VALUE_DROPPED) raised after test.warn returned was dropped"), log);
        assertFalse(log.contains("too late"), "the message is not logged");
    }
}
