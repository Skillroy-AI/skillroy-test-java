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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.ReadinessSource;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.samples.lending.LendingApp;
import ai.skillroy.testjava.samples.lending.Edition;
import ai.skillroy.testjava.samples.lending.LendingOptions;
import ai.skillroy.testjava.samples.lending.Seed;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A headless desk (no window, prompts answered through the prompt service) with the lending module installed on a
 * {@link FakeAgentContext}, ready for primitives. The desk is the module's host context, as in EMBEDDED mode,
 * so that tests never depend on the application's static instance.
 */
final class LendingFixture implements AutoCloseable {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    final LendingApp app;
    final FakeAgentContext ctx;
    final LendingModule module;
    private boolean uninstalled;

    private LendingFixture(LendingOptions options) throws Exception {
        app = LendingApp.launch(options.ui(false).dbName("lending_agent_test_" + COUNTER.incrementAndGet()));
        ctx = new FakeAgentContext(app, LoadMode.EMBEDDED);
        module = new LendingModule();
        module.install(ctx);
        awaitReady(ctx, 10000);
        module.onTargetReady(ctx);
        ctx.probeAll();
    }

    static LendingFixture start() throws Exception {
        return start(Seed.A);
    }

    static LendingFixture start(Seed seed) throws Exception {
        return new LendingFixture(LendingOptions.defaults().seed(seed));
    }

    /**
     * A desk on the given clock: its business date, and so the seeded waiver rules' dates, which are relative to the
     * day the desk was seeded, come from that clock.
     */
    /** A desk of {@code edition} on seed A (phase6-contracts.md 11.3). */
    static LendingFixture start(Edition edition) throws Exception {
        return new LendingFixture(LendingOptions.defaults().seed(Seed.A).edition(edition));
    }

    /** A desk with these options (phase8-contracts.md 13.1 row 13: an idle lock and an idle tick of its own). */
    static LendingFixture start(LendingOptions options) throws Exception {
        return new LendingFixture(options);
    }

    static LendingFixture start(Seed seed, Clock clock) throws Exception {
        return new LendingFixture(LendingOptions.defaults().seed(seed).clock(clock));
    }

    /**
     * A desk (seed A) that also serves its database to the read-only account on a loopback port it picks itself
     * ({@code lending.dbPort=0}), with the given password.
     */
    static LendingFixture startServing(String readerPassword) throws Exception {
        return new LendingFixture(LendingOptions.defaults().seed(Seed.A).dbPort(0).dbPassword(readerPassword));
    }

    /** A desk (seed A) launched with its scanner driver ({@code lending.scannerDriver=true}; P8 12.1, ES). */
    static LendingFixture startWithScannerDriver() throws Exception {
        return new LendingFixture(LendingOptions.defaults().seed(Seed.A).scannerDriver(true));
    }

    /**
     * Polls the module's readiness source the way the agent core does (it has no event to wait on: the engine gives
     * no notification before the module's listener is attached), bounded by {@code timeoutMs}.
     */
    static void awaitReady(FakeAgentContext ctx, long timeoutMs) throws InterruptedException {
        ReadinessSource readiness = ctx.spi.first(ReadinessSource.class);
        assertNotNull(readiness, "readiness source");
        long deadline = System.currentTimeMillis() + timeoutMs;
        Object monitor = new Object();
        while (!readiness.isReady()) {
            long remaining = deadline - System.currentTimeMillis();
            assertTrue(remaining > 0, "not ready within " + timeoutMs + " ms: " + readiness.describe());
            synchronized (monitor) {
                monitor.wait(Math.min(remaining, 20L));
            }
        }
    }

    /**
     * Sends an input through {@code lending.input} and waits for its consequence: the engine waiting again
     * ({@code input.requested}) or asking a question ({@code prompt.waiting}).
     *
     * @return the event the engine published when it was done
     */
    Event send(String source, String value) throws Exception {
        long before = ctx.events.lastSeq();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) ctx.call("lending.input", value == null
                ? FakeAgentContext.args("source", source) : FakeAgentContext.args("source", source, "value", value));
        assertEquals(Boolean.TRUE, result.get("acked"), "input " + source + " acknowledged: " + result);
        Event done = ctx.events.await(before, TopicFilter.parse("input.requested,prompt.waiting"), null, 10000);
        assertNotNull(done, "no input.requested or prompt.waiting after " + source + "; events: "
                + ctx.events.topics());
        return done;
    }

    /** Signs on assistant 100 (seed A). */
    void signOn() throws Exception {
        send("Entry", "100");
        Event idle = send("Entry", "1111");
        assertEquals("IDLE", idle.payload().get("state"));
    }

    void uninstall() {
        if (!uninstalled) {
            uninstalled = true;
            module.uninstall();
        }
    }

    @Override
    public void close() {
        uninstall();
        app.shutdown();
    }
}
