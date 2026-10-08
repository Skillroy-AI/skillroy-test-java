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

import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's parts: the stdio pipe's counting, ending and bounds, the list {@code data_query} reads, the arguments'
 * inverse of the strict schema, the cut.
 */
class PartsTest {

    @Test
    void thePipeCountsRequestsAndResponsesAndEndsTheTransportsInputOnlyWhenTold() throws Exception {
        TestPipe client = new TestPipe();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CountDownLatch sourceEnded = new CountDownLatch(1);
        StdioPipe pipe = new StdioPipe(client.in(), sink, error -> sourceEnded.countDown());
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
        StdioPipe pipe = new StdioPipe(client.in(), new ByteArrayOutputStream(), error -> ended.countDown());
        pipe.start();
        client.out().write("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
        client.closeWriter();
        assertTrue(ended.await(10, TimeUnit.SECONDS));
        pipe.endInput();
        String read = new String(pipe.serverIn().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}\n", read);
        assertEquals("1 requests, 0 responses", pipe.counts());
    }

    /**
     * phase5-contracts.md 9.3: a line longer than the bound is a protocol error: the reader stops, reports it, and
     * never hands that line (or anything after it) on; what came before is the transport's.
     */
    @Test
    void aLineLongerThanTheBoundIsAProtocolErrorThatStopsTheReading() throws Exception {
        TestPipe client = new TestPipe();
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        StdioPipe pipe = new StdioPipe(client.in(), new ByteArrayOutputStream(), e -> {
            error.set(e);
            ended.countDown();
        }, 64, 1024);
        pipe.start();
        String first = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n";
        client.out().write(first.getBytes(StandardCharsets.UTF_8));
        client.out().write(("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"" + "x".repeat(100) + "\"}\n")
                .getBytes(StandardCharsets.UTF_8));
        client.out().write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(ended.await(10, TimeUnit.SECONDS), "the end is reported without the client closing its stream");
        assertEquals("a line of more than 64 bytes", error.get());
        pipe.endInput();
        assertEquals(first, new String(pipe.serverIn().readAllBytes(), StandardCharsets.UTF_8), "only the line"
                + " before the long one");
        assertEquals("1 requests, 0 responses", pipe.counts());
        client.closeWriter();
    }

    /**
     * phase5-contracts.md 9.3: at most the bound of bytes waits for the transport; the reader waits for room
     * (back-pressure on the client's stream) and nothing is lost, in order.
     */
    @Test
    void atMostTheBoundWaitsForTheTransportAndTheReaderWaitsForRoom() throws Exception {
        TestPipe client = new TestPipe();
        CountDownLatch ended = new CountDownLatch(1);
        StdioPipe pipe = new StdioPipe(client.in(), new ByteArrayOutputStream(), e -> ended.countDown(), 64, 100);
        pipe.start();
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            String line = String.format("line %04d\n", i);
            sent.append(line);
            client.out().write(line.getBytes(StandardCharsets.UTF_8));
        }
        client.closeWriter();
        assertTrue(pipe.awaitQueued(100, Duration.ofSeconds(10)), "the bound fills");
        assertFalse(pipe.awaitQueued(101, Duration.ofMillis(300)), "and is never passed while nobody reads");
        assertEquals(1, ended.getCount(), "the reader waits for room: the client's stream is not read to its end");
        String read = new String(pipe.serverIn().readNBytes(sent.length()), StandardCharsets.UTF_8);
        assertEquals(sent.toString(), read, "every line, in order, once the transport reads");
        assertTrue(ended.await(10, TimeUnit.SECONDS));
        assertTrue(pipe.peakQueuedBytes() <= 100, "peak " + pipe.peakQueuedBytes());
        pipe.endInput();
        assertEquals(-1, pipe.serverIn().read());
    }

    /**
     * Decision D99: the list {@code data_query} reads hides every personal column and every column a row mask alters
     * (as other columns, so that a statement cannot name them), and leaves out a table keyed by one.
     */
    @Test
    void theDataQueryListHidesPersonalAndMaskedColumnsAndDropsATableKeyedByOne() {
        SqlAllowList list = SqlAllowList.builder("desk", SqlDialect.HSQLDB)
                .table("item", List.of("code"), AllowedColumn.of("code", "VARCHAR"),
                        AllowedColumn.of("title", "VARCHAR"), AllowedColumn.personal("borrower_email", "VARCHAR"),
                        AllowedColumn.of("shelf_note", "VARCHAR"))
                .table("borrower", List.of("email"), AllowedColumn.personal("email", "VARCHAR"),
                        AllowedColumn.of("since", "DATE"))
                .build();
        RowMask mask = (source, column, value) -> column.equals("SHELF_NOTE") ? "<masked>" : value;
        SqlAllowList copy = DataQueryLists.forDataQuery(list, List.of(mask));
        assertEquals(List.of("item"), copy.tables().stream().map(SqlAllowList.AllowedTable::name).toList(),
                "a table keyed by a personal column orders by it: left out");
        SqlAllowList.AllowedTable item = copy.tables().get(0);
        assertEquals(List.of("code", "title"), item.columns().stream().map(AllowedColumn::name).toList());
        assertEquals(List.of("borrower_email", "shelf_note"), item.otherColumns(), "a mask that alters the upper-case"
                + " label is found too");
        assertEquals(null, DataQueryLists.forDataQuery(SqlAllowList.builder("hr", SqlDialect.HSQLDB)
                .table("staff", List.of("code"), AllowedColumn.personal("code", "VARCHAR")).build(), List.of()));
        assertTrue(DataQueryLists.masked("desk", "x", List.of((source, column, value) -> {
            throw new IllegalStateException("a mask that throws hides the column");
        })));
        assertFalse(DataQueryLists.masked("desk", "title", List.of((source, column, value) -> value)));
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
