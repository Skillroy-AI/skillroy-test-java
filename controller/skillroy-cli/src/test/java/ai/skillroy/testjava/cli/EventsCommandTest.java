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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code skillroy events} against the fake agent's SSE endpoint. */
class EventsCommandTest {

    /** Generous: the Linux container runs x86-64 under emulation. */
    private static final long DEADLINE_S = 60;

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private ExecutorService background;

    @BeforeEach
    void setUp() throws IOException {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        background = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "events-test-cli");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        agent.close();
        background.shutdownNow();
    }

    private CliHarness.Outcome events(String... args) {
        List<String> all = new ArrayList<>(List.of("events"));
        all.addAll(List.of(args));
        all.addAll(connection);
        return CliHarness.run(tmp, all.toArray(String[]::new)).never(agent.token);
    }

    private CompletableFuture<CliHarness.Outcome> eventsInBackground(String... args) {
        return CompletableFuture.supplyAsync(() -> events(args), background);
    }

    @Test
    void sinceZeroReplaysTheRingAndCountStops() {
        agent.publish("a.one", Map.of("n", 1));
        agent.publish("a.two", Map.of("n", 2));
        agent.publish("a.three", Map.of("n", 3));
        agent.publish("a.four", Map.of("n", 4));
        String[] lines = events("--since", "0", "--count", "3").expectExit(Exit.OK).out().split("\n");
        assertEquals(3, lines.length, String.join("\n", lines));
        assertTrue(lines[0].matches("1 \\d\\d:\\d\\d:\\d\\d\\.\\d{3} a\\.one \\{\"n\":1}"), lines[0]);
        assertTrue(lines[2].startsWith("3 ") && lines[2].endsWith(" a.three {\"n\":3}"), lines[2]);
    }

    @Test
    void topicsFilterAndJsonLines() {
        agent.publish("window.opened", Map.of("title", "Main"));
        agent.publish("test.poked", Map.of("count", 1));
        agent.publish("prompt.waiting", Map.of("text", "Sure?"));
        String out = events("--since", "0", "--topics", "window.*,prompt.*", "--count", "2", "--json")
                .expectExit(Exit.OK).out();
        String[] lines = out.split("\n");
        assertEquals(2, lines.length, out);
        JsonView first = JsonView.of(Json.parseObject(lines[0]));
        JsonView second = JsonView.of(Json.parseObject(lines[1]));
        assertEquals("window.opened", first.str("topic"));
        assertEquals(1L, first.num("seq", 0));
        assertEquals("Main", first.obj("payload").str("title"));
        assertEquals("prompt.waiting", second.str("topic"));
    }

    @Test
    void theTokenIsRedactedFromEventPayloads() {
        agent.publish("app.leak", Map.of("header", "Bearer " + agent.token));
        String out = events("--since", "0", "--count", "1").expectExit(Exit.OK).out();
        assertTrue(out.contains("{\"header\":\"Bearer <redacted>\"}"), out);
    }

    @Test
    void timeoutEndsAQuietStreamSuccessfully() {
        CliHarness.Outcome outcome = events("--timeout", "300ms").expectExit(Exit.OK);
        assertEquals("", outcome.out());
    }

    @Test
    void newEventsArriveWhileTheCommandRuns() throws Exception {
        agent.publish("old.event", Map.of());
        CompletableFuture<CliHarness.Outcome> run = eventsInBackground("--count", "2");
        assertTrue(agent.streamsOpened.tryAcquire(DEADLINE_S, TimeUnit.SECONDS), "the CLI opened no stream");
        agent.publish("new.first", Map.of("k", "v"));
        agent.publish("new.second", Map.of());
        CliHarness.Outcome outcome = run.get(DEADLINE_S, TimeUnit.SECONDS).expectExit(Exit.OK);
        assertTrue(!outcome.out().contains("old.event") && outcome.out().contains("new.first {\"k\":\"v\"}")
                && outcome.out().contains("new.second"), outcome.out());
    }

    @Test
    void aLostAgentEndsTheStreamWithExitCodeThree() throws Exception {
        CompletableFuture<CliHarness.Outcome> run = eventsInBackground();
        assertTrue(agent.streamsOpened.tryAcquire(DEADLINE_S, TimeUnit.SECONDS), "the CLI opened no stream");
        agent.publish("before.loss", Map.of());
        agent.rejectAll = true;
        agent.dropStreams();
        CliHarness.Outcome outcome = run.get(DEADLINE_S, TimeUnit.SECONDS).expectExit(Exit.UNREACHABLE);
        assertTrue(outcome.err().contains("was lost"), outcome.err());
    }

    @Test
    void badOptionValuesAreUsageErrors() {
        assertTrue(events("--count", "0").expectExit(Exit.USAGE).err().contains("--count must be between 1"));
        assertTrue(events("--since", "-5").expectExit(Exit.USAGE).err().contains("--since must be between 0"));
        assertTrue(events("--timeout", "x").expectExit(Exit.USAGE).err().contains("--timeout"));
    }
}
