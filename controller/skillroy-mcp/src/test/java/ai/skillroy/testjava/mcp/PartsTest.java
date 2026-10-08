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

package ai.skillroy.testjava.mcp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server's parts: the stdio pipe's counting and ending, the arguments' inverse of the strict schema, the cut. */
class PartsTest {

    @Test
    void thePipeCountsRequestsAndResponsesAndEndsTheTransportsInputOnlyWhenTold() throws Exception {
        TestPipe client = new TestPipe();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CountDownLatch sourceEnded = new CountDownLatch(1);
        StdioPipe pipe = new StdioPipe(client.in(), sink, sourceEnded::countDown);
        pipe.start();
        client.out().write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n").getBytes(StandardCharsets.UTF_8));
        client.out().write("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"meth".getBytes(StandardCharsets.UTF_8));
        client.out().write("od\":\"tools/list\"}\n".getBytes(StandardCharsets.UTF_8));
        client.closeWriter();
        assertTrue(sourceEnded.await(10, TimeUnit.SECONDS), "the client's end is reported");
        InputStream in = pipe.serverIn();
        byte[] two = new byte[4096];
        int n = in.read(two);
        assertTrue(new String(two, 0, n, StandardCharsets.UTF_8).contains("\"ping\""));
        assertFalse(pipe.awaitAnswered(Duration.ofMillis(50)), "two requests, no response yet");
        assertEquals("2 requests, 0 responses", pipe.counts());
        OutputStream out = pipe.serverOut();
        out.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n".getBytes(StandardCharsets.UTF_8));
        out.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/resources/list_changed\"}\n"
                .getBytes(StandardCharsets.UTF_8));
        out.write("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"error\":{\"code\":-1,\"message\":\"x\"}}".getBytes(
                StandardCharsets.UTF_8));
        assertFalse(pipe.awaitAnswered(Duration.ofMillis(50)), "a line counts when it ends");
        out.write('\n');
        out.flush();
        assertTrue(pipe.awaitAnswered(Duration.ofSeconds(5)));
        assertEquals("2 requests, 2 responses", pipe.counts());
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("list_changed"), "written through");
        pipe.endInput();
        byte[] rest = new byte[4096];
        int total = 0;
        int r;
        while ((r = in.read(rest, total, rest.length - total)) > 0) {
            total += r;
        }
        assertEquals(-1, r, "the transport's input ends after the lines");
        assertTrue(new String(rest, 0, total, StandardCharsets.UTF_8).contains("tools/list"));
        assertEquals(-1, in.read(), "and stays ended");
    }

    @Test
    void aLastLineWithoutANewlineIsStillHandedOver() throws Exception {
        TestPipe client = new TestPipe();
        CountDownLatch ended = new CountDownLatch(1);
        StdioPipe pipe = new StdioPipe(client.in(), new ByteArrayOutputStream(), ended::countDown);
        pipe.start();
        client.out().write("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
        client.closeWriter();
        assertTrue(ended.await(10, TimeUnit.SECONDS));
        pipe.endInput();
        String read = new String(pipe.serverIn().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}\n", read);
        assertEquals("1 requests, 0 responses", pipe.counts());
    }

    @Test
    void nullOptionalsAreDroppedRequiredOnesAndFreeFormOnesKept() {
        Map<String, Object> schema = Desk.object(Map.of("item", Map.of("type", "string"), "borrower",
                Map.of("type", "string"), "slip", Desk.object(Map.of("copies", Map.of("type", "integer"), "printer",
                        Map.of("type", "string")), List.of("copies")), "extra", Map.of("type", "object")),
                List.of("item"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("item", null);
        args.put("borrower", null);
        Map<String, Object> slip = new HashMap<>();
        slip.put("copies", null);
        slip.put("printer", null);
        args.put("slip", slip);
        Map<String, Object> extra = new HashMap<>();
        extra.put("anything", null);
        args.put("extra", extra);
        Map<String, Object> out = Arguments.dropNullOptionals(args, schema);
        assertEquals(Arrays.asList("item", "slip", "extra"), new ArrayList<>(out.keySet()));
        assertEquals(null, out.get("item"), "a required key keeps its null");
        Map<?, ?> nested = (Map<?, ?>) out.get("slip");
        assertTrue(nested.containsKey("copies") && !nested.containsKey("printer"), nested.toString());
        assertTrue(((Map<?, ?>) out.get("extra")).containsKey("anything"), "free-form: kept as given");
        Map<String, Object> noSchema = new HashMap<>();
        noSchema.put("a", null);
        noSchema.put("b", 1L);
        assertEquals(Map.of("b", 1L), Arguments.dropNullOptionals(noSchema, null));
    }

    @Test
    void theCutKeeps25000CharactersAndSaysHowManyMore() {
        String text = "é".repeat(30_000);
        String cut = Masking.cut(text);
        assertEquals("é".repeat(25_000) + "\n[truncated: 5000 more]", cut);
        assertEquals("short", Masking.cut("short"));
        String pair = "a".repeat(24_999) + "😀" + "b".repeat(10);
        String cutPair = Masking.cut(pair);
        assertTrue(cutPair.startsWith("a".repeat(24_999) + "\n[truncated: 12 more]"), "never half a pair");
    }

    @Test
    void jsonFormTurnsRecordsAndNumbersIntoJsonLikeValues() {
        record Inner(BigDecimal amount, Duration took) {
        }
        record Outer(String name, Inner inner, List<Integer> counts) {
        }
        Object json = JsonForm.of(new Outer("hammer", new Inner(new BigDecimal("12.50"), Duration.ofSeconds(2)),
                List.of(1, 2)));
        assertEquals(Map.of("name", "hammer", "inner", Map.of("amount", "12.50", "took", 2000L), "counts",
                List.of(1L, 2L)), json);
        Map<String, Object> raw = new HashMap<>();
        raw.put("n", 3);
        raw.put("big", new java.math.BigInteger("7"));
        assertEquals(Map.of("n", 3L, "big", 7L), JsonForm.arguments(raw));
    }

    @Test
    void theLogIsOneMaskedLine() {
        assertEquals("a b ? c", ServerLog.oneLine("a\nb \u0007 c"));
        assertTrue(ServerLog.oneLine("x".repeat(3000)).endsWith("[...]"));
    }
}
