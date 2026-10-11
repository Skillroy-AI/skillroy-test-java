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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.EventStream;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.Topics;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The soak of the agent's own control listener (TODO.md, "BS2 Control-channel soak", the part that was not shown): the
 * sample lending desk runs as a child process on the Java named by {@code -Dskillroy.it.targetJava} (default: this
 * JVM's) with the agent on the command line, and a controller loads it for {@code -Dskillroy.soak.minutes} minutes
 * with what the first soak (3 minutes, on the JDK HTTP server, before the agent got its own listener) used, at the
 * rate the contract's event stream is meant for:
 *
 * <ul>
 *   <li>{@code loadThreads} (8) threads calling a rotating set of read-only operations (15: {@code agent.info},
 *       {@code agent.capabilities}, {@code jvm.info}, {@code jvm.properties} (which echoes a name unique to the call,
 *       so a reply that reached the wrong caller shows), {@code evt.query}, {@code app.state}, {@code lending.state},
 *       {@code lending.loan}, {@code lending.access.check}, {@code lending.deposit}, {@code swing.windows},
 *       {@code swing.snapshot}, {@code swing.find}, {@code swing.edtState} and {@code GET /capabilities}), each reply
 *       checked for what it must hold;</li>
 *   <li>one thread completing desk transactions in a loop ({@code basic-loan}'s inputs: scan twice, DUE, DEPOSIT),
 *       waiting for the desk's own events ({@code input.requested}, {@code loan.posted}) with {@code sync.await}, and
 *       checking the loan it posted;</li>
 *   <li>one thread holding the event rate at {@code eventsPerSecond} (100) with {@code trace.mark}, which numbers its
 *       marks, on top of what the transactions publish;</li>
 *   <li>two event streams on their own clients: one unfiltered, which closes its connection every 30 to 60 seconds,
 *       waits a moment (so that a backlog builds) and resumes from the last event id it delivered; one filtered by
 *       the topic {@code loan.posted}, which stays connected;</li>
 *   <li>a sampler that every {@code sampleSeconds} (up to 30) records the target's threads and the agent's pools, and
 *       at the start, at every {@code checkpointSeconds} (up to 300) and at the end has the target run a full
 *       collection with a class histogram ({@code jcmd GC.class_histogram} of the target's own JDK, which counts
 *       live objects only), so that its heap figure is the live set and not the position in a collector's
 *       cycle.</li>
 * </ul>
 *
 * <p>What it asserts, and the tolerance of each:
 * <ul>
 *   <li>no error of any kind: every call, wait and sample, and every reply that does not hold what it must;</li>
 *   <li>the unfiltered stream delivered every event from the baseline to the last one the agent published,
 *       exactly once and in order, across every resume, with no {@code gap} frame. The contract lets the ring
 *       overflow only for a client that falls further behind than the ring holds (10,000 events by default, with the
 *       agent queueing 1,000 per client and catching a slow one up from the ring); at 100 events per second that is
 *       100 seconds, and a resume here is offline for 0.2 to 3 seconds (a backlog of a few hundred events), so under
 *       this load a gap frame is a failure, not a permitted overflow;</li>
 *   <li>the trace marks it saw are the ones published, numbered without a break;</li>
 *   <li>the filtered stream delivered exactly the {@code loan.posted} events the unfiltered one saw, which are
 *       exactly the transactions completed;</li>
 *   <li>the 99th percentile of the read load's calls is below 100 ms (the proposed limit of the first soak);</li>
 *   <li>the target's live heap is bounded: the lowest live set among the checkpoints of the last quarter of the run
 *       is at most the lowest among those of the first quarter after the warm-up (a quarter of the run, at most ten
 *       minutes) plus 32 MiB or 25 %, the larger. The lowest of several is compared because a collection under
 *       load also keeps what the calls in flight hold. The number of the agent's threads, and of all the target's
 *       threads, in the last quarter of the run is at most 8 above the most in the first quarter after the warm-up,
 *       and the agent's threads never exceed the sum of its pools (workers, callers, connections) and 8;</li>
 *   <li>the controller's event-stream threads are gone once the streams are closed, and the stream never reconnected
 *       by itself (a connection the agent dropped);</li>
 *   <li>the desk still answers at the end: the agent pings, the desk's state is read, the event thread is alive, and
 *       scenario {@code basic-loan} passes on the executor without a pause.</li>
 * </ul>
 *
 * <p>Opt-in: without {@code -Dskillroy.soak.minutes=<n>} (a number above zero) the test reports itself skipped, so the
 * normal build and the matrix are unchanged. Other properties, all optional: {@code skillroy.soak.loadThreads} (8),
 * {@code skillroy.soak.callsPerSecond} (the read load's total, 0 for no limit), {@code skillroy.soak.eventsPerSecond}
 * (100), {@code skillroy.soak.transactionsPerSecond} (1; 0 for no limit: a transaction publishes about 13 events, so
 * unlimited transactions alone publish about 700 events per second; the desk keeps every slip in a text area and
 * every loan in its database, about 4 KB a transaction, so at 4 a second its own records would pass the heap
 * tolerance within the hour), {@code skillroy.soak.filter}
 * ({@code loan.posted}), {@code skillroy.soak.resumeMinSeconds} and {@code skillroy.soak.resumeMaxSeconds} (30, 60),
 * {@code skillroy.soak.sampleSeconds} and {@code skillroy.soak.checkpointSeconds}.
 *
 * <p>Needs a display; run it with {@code ci/run-in-linux.sh mvn -o -q verify -pl samples/skillroy-sample-lending-scenarios
 * -am -Dskillroy.headless=false -Dit.test=ListenerSoakIT -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=NONE
 * -Dsurefire.failIfNoSpecifiedTests=false -Dskillroy.soak.minutes=60}, and add
 * {@code -Dskillroy.it.targetJava=/opt/jdk8/bin/java} for a Java 8 target. The report is
 * {@code target/it-runs/soak/java-<version>-<minutes>min/report.json}; progress lines go to the console and to
 * {@code target/it-logs/timings.txt}.
 */
final class ListenerSoakIT {

    static final String MINUTES_PROPERTY = "skillroy.soak.minutes";
    /** The most reply failures and the like the run absorbs before it gives up (it would only collect more). */
    private static final long GIVE_UP_AFTER_ERRORS = 2_000;
    private static final CallOptions CALL = CallOptions.defaults().withTimeoutMs(10_000L);
    private static final String BARCODE = "5000000000017";
    private static final String STAFF = "100";
    private static final String READY_TOPICS = "input.requested,prompt.waiting";
    private static final Duration AWAIT = Duration.ofSeconds(30);
    private static final long HEAP_TOLERANCE_BYTES = 32L << 20;
    private static final int THREAD_TOLERANCE = 8;
    private static final long AGENT_OWNED_TOLERANCE_BYTES = 2L << 20;
    /** The packages of the agent: its core and modules, the wire types, and the sample's lending module. */
    private static final List<String> AGENT_PACKAGES = List.of("ai.skillroy.testjava.agent.",
            "ai.skillroy.testjava.protocol.", "ai.skillroy.testjava.samples.lending.agent.");
    /** What the desk itself keeps: its database, the text area its slips are appended to, its own classes. */
    private static final List<String> DESK_PACKAGES = List.of("org.hsqldb.", "javax.swing.text.",
            "ai.skillroy.testjava.samples.lending.");
    private static final double P99_LIMIT_MS = 100.0;
    /** A line of {@code jcmd GC.class_histogram}: rank, instances, bytes, class (the module in brackets is ignored). */
    private static final java.util.regex.Pattern HISTOGRAM_LINE =
            java.util.regex.Pattern.compile("\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+).*");
    private static final long NEVER_SEQ = 1_000_000_000_000L;

    @Test
    void theAgentsOwnListenerSurvivesTheSoak() throws Exception {
        Config config = Config.fromSystemProperties();
        assumeTrue(config != null, "the listener soak is opt-in: give -D" + MINUTES_PROPERTY
                + "=<minutes> (a number above zero)");
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        new Soak(config).run();
    }

    // ------------------------------------------------------------------ configuration

    /** What a run is asked to do; see the class comment for the properties. */
    record Config(double minutes, int loadThreads, int callsPerSecond, int eventsPerSecond, int transactionsPerSecond,
                  String filter, int resumeMinSeconds, int resumeMaxSeconds, int sampleSeconds,
                  int checkpointSeconds) {

        long runSeconds() {
            return Math.max(1L, Math.round(minutes * 60.0));
        }

        /** Null when the soak was not asked for. */
        static Config fromSystemProperties() {
            String raw = System.getProperty(MINUTES_PROPERTY);
            if (raw == null || raw.isBlank() || raw.startsWith("${")) {
                return null;
            }
            double minutes;
            try {
                minutes = Double.parseDouble(raw.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(MINUTES_PROPERTY + " must be a number of minutes, found '" + raw
                        + "'");
            }
            if (!(minutes > 0)) {
                return null;
            }
            long runSeconds = Math.max(1L, Math.round(minutes * 60.0));
            int sample = integer("sampleSeconds", (int) Math.max(5, Math.min(30, runSeconds / 20)));
            int checkpoint = integer("checkpointSeconds",
                    (int) Math.min(300, Math.max(sample, runSeconds / 8)));
            return new Config(minutes, integer("loadThreads", 8), integer("callsPerSecond", 0),
                    integer("eventsPerSecond", 100), integer("transactionsPerSecond", 1),
                    System.getProperty("skillroy.soak.filter", "loan.posted"), integer("resumeMinSeconds", 30),
                    integer("resumeMaxSeconds", 60), sample, checkpoint);
        }

        private static int integer(String name, int fallback) {
            String raw = System.getProperty("skillroy.soak." + name);
            if (raw == null || raw.isBlank() || raw.startsWith("${")) {
                return fallback;
            }
            return Integer.parseInt(raw.strip());
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("minutes", minutes);
            m.put("loadThreads", (long) loadThreads);
            m.put("callsPerSecond", (long) callsPerSecond);
            m.put("eventsPerSecond", (long) eventsPerSecond);
            m.put("transactionsPerSecond", (long) transactionsPerSecond);
            m.put("filter", filter);
            m.put("resumeMinSeconds", (long) resumeMinSeconds);
            m.put("resumeMaxSeconds", (long) resumeMaxSeconds);
            m.put("sampleSeconds", (long) sampleSeconds);
            m.put("checkpointSeconds", (long) checkpointSeconds);
            return m;
        }
    }

    // ------------------------------------------------------------------ small shared things

    /** Every error of the run: counted by kind, the first ones kept with the second they happened at. */
    private static final class Errors {
        private final AtomicLong count = new AtomicLong();
        private final ConcurrentHashMap<String, LongAdder> byKind = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<String> first = new ConcurrentLinkedQueue<>();
        private volatile long startNanos = System.nanoTime();

        void start() {
            startNanos = System.nanoTime();
        }

        void add(String where, Throwable t) {
            count.incrementAndGet();
            String kind = where + ": " + t.getClass().getSimpleName();
            byKind.computeIfAbsent(kind, k -> new LongAdder()).increment();
            if (first.size() < 40) {
                String text = String.valueOf(t.getMessage());
                first.add(String.format(Locale.ROOT, "%.1fs %s: %s: %s", (System.nanoTime() - startNanos) / 1e9, where,
                        t.getClass().getName(), text.length() > 400 ? text.substring(0, 400) + "..." : text));
            }
        }

        long count() {
            return count.get();
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", count.get());
            Map<String, Object> kinds = new LinkedHashMap<>();
            new java.util.TreeMap<>(byKind).forEach((k, v) -> kinds.put(k, v.sum()));
            m.put("byKind", kinds);
            m.put("first", new ArrayList<>(first));
            return m;
        }
    }

    /** A read-only operation of the load. */
    @FunctionalInterface
    private interface Body {
        void run(AgentClient client, int thread, long n) throws Exception;
    }

    private record Op(String name, Body body) {
    }

    /** The threads, heap and ring of the target at one moment. */
    private record Sample(double t, String label, boolean gcForced, long heapUsed, long heapMax, int threadsTotal,
                          int threadsAgent, Map<String, Object> agentByPrefix, long workersActive, long workersMax,
                          long callersActive, long callersMax, long httpThreads, long ringLast, long ringOldest,
                          long calls, long controllerHeapUsed, int controllerThreads, int controllerSseThreads,
                          long histogramBytes, List<String> topClasses, Map<String, long[]> classes,
                          long transactionsSoFar) {

        /** The bytes of live objects of the agent's own classes (the lending module's included). */
        long agentOwned() {
            return ownedBy(AGENT_PACKAGES, List.of());
        }

        /** The bytes of live objects of the desk's own data: its database, its text area, its classes. */
        long deskOwned() {
            return ownedBy(DESK_PACKAGES, AGENT_PACKAGES);
        }

        private long ownedBy(List<String> packages, List<String> except) {
            long sum = 0;
            for (Map.Entry<String, long[]> e : classes.entrySet()) {
                String name = e.getKey();
                if (packages.stream().anyMatch(name::startsWith) && except.stream().noneMatch(name::startsWith)) {
                    sum += e.getValue()[1];
                }
            }
            return sum;
        }

        /**
         * What the target holds that is alive: after a collection with a histogram, the bytes of the live objects (no
         * allocation since the collection, no region slack); else the heap in use.
         */
        long live() {
            return gcForced && histogramBytes > 0 ? histogramBytes : heapUsed;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tSeconds", SoakHistogram.round(t));
            m.put("label", label);
            m.put("gcForced", gcForced);
            m.put("heapUsedMB", mb(heapUsed));
            m.put("heapMaxMB", mb(heapMax));
            m.put("threadsTotal", (long) threadsTotal);
            m.put("threadsAgent", (long) threadsAgent);
            m.put("agentThreadsByName", agentByPrefix);
            m.put("workersActive", workersActive);
            m.put("workersMax", workersMax);
            m.put("callersActive", callersActive);
            m.put("callersMax", callersMax);
            m.put("ringLastSeq", ringLast);
            m.put("ringOldestSeq", ringOldest);
            m.put("callsSoFar", calls);
            m.put("transactionsSoFar", transactionsSoFar);
            m.put("controllerHeapUsedMB", mb(controllerHeapUsed));
            m.put("controllerThreads", (long) controllerThreads);
            m.put("controllerSseThreads", (long) controllerSseThreads);
            if (gcForced) {
                m.put("liveObjectsTotalMB", mb(histogramBytes));
                m.put("liveAgentOwnedMB", mb(agentOwned()));
                m.put("liveDeskOwnedMB", mb(deskOwned()));
                m.put("liveClassesTop", topClasses);
            }
            return m;
        }

        String line() {
            return String.format(Locale.ROOT, "t=%.0fs %s heap=%.1f/%.0f MB%s threads agent=%d total=%d "
                    + "pools workers=%d/%d callers=%d/%d ring=%d..%d", t, label, heapUsed / 1048576.0,
                    heapMax / 1048576.0, gcForced ? " (after GC)" : "", threadsAgent, threadsTotal, workersActive,
                    workersMax, callersActive, callersMax, ringOldest, ringLast);
        }
    }

    private static double mb(long bytes) {
        return SoakHistogram.round(bytes / 1048576.0);
    }

    /** Collects what the controller's event stream logs about reconnecting and failing, while it is open. */
    private static final class SseLog extends Handler implements AutoCloseable {
        final LongAdder reconnects = new LongAdder();
        final List<String> warnings = new CopyOnWriteArrayList<>();
        private final Logger logger = Logger.getLogger("ai.skillroy.testjava.controller.SseEventStream");
        private final Level levelBefore = logger.getLevel();

        SseLog() {
            setLevel(Level.ALL);
            logger.setLevel(Level.ALL);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            if (message == null) {
                return;
            }
            if (message.contains("reconnecting to /events")) {
                reconnects.increment();
            }
            if (record.getLevel().intValue() >= Level.WARNING.intValue() && warnings.size() < 20) {
                warnings.add(message);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(levelBefore);
        }
    }

    // ------------------------------------------------------------------ one event stream's consumer

    /**
     * What one event stream delivered, checked as it arrives: in strictly increasing order, never a duplicate, never a
     * {@code gap} frame, and (for the unfiltered stream, which sees every event) every sequence number from the
     * baseline on. Called on the stream's thread; a resume is a new stream, so calls are serial but not on one thread.
     */
    private static final class Feed {
        final String name;
        final boolean dense;
        final String topics;
        final TopicFilter filter;
        final TopicFilter matcher;
        final long baseline;
        private final Object lock = new Object();
        private volatile long last;
        private long received;
        private long duplicates;
        private long outOfOrder;
        private long missing;
        private long gapFrames;
        private long wrongTopic;
        private long marks;
        private long markBreaks;
        private long lastMarkNumber;
        private long armedNanos;
        private final SoakHistogram firstEvent = new SoakHistogram();
        private final BitSet matching = new BitSet();
        private long[] seqs = new long[1024];
        private int seqCount;
        private final List<String> problems = new ArrayList<>();

        /**
         * @param topics the stream's filter, null for none
         * @param matcher which events the filtered stream must also have delivered (unfiltered stream only)
         */
        Feed(String name, String topics, TopicFilter matcher, long baseline) {
            this.name = name;
            this.dense = topics == null;
            this.topics = topics;
            this.filter = TopicFilter.parse(topics);
            this.matcher = matcher;
            this.baseline = baseline;
            this.last = baseline;
        }

        long last() {
            return last;
        }

        /** The stream is being (re)opened: time to its first event is measured from now. */
        void armed() {
            synchronized (lock) {
                armedNanos = System.nanoTime();
            }
        }

        void accept(Event e) {
            synchronized (lock) {
                if (armedNanos != 0) {
                    firstEvent.record((System.nanoTime() - armedNanos) / 1000);
                    armedNanos = 0;
                }
                if (Topics.GAP.equals(e.topic())) {
                    gapFrames++;
                    problem("gap frame ending at " + e.seq() + ": " + e.payload());
                    last = Math.max(last, e.seq());
                    return;
                }
                long seq = e.seq();
                if (seq <= last) {
                    if (seq == last) {
                        duplicates++;
                    } else {
                        outOfOrder++;
                    }
                    problem((seq == last ? "duplicate " : "out of order ") + seq + " after " + last + " " + e.topic());
                    return;
                }
                if (dense && seq != last + 1) {
                    missing += seq - last - 1;
                    problem("events " + (last + 1) + ".." + (seq - 1) + " missing before " + seq);
                }
                if (!filter.matches(e.topic())) {
                    wrongTopic++;
                    problem("event " + seq + " of topic " + e.topic() + " does not match the stream's filter");
                }
                last = seq;
                received++;
                if (dense) {
                    if (matcher.matches(e.topic())) {
                        matching.set((int) (seq - baseline));
                    }
                    if (Topics.TRACE_MARK.equals(e.topic())) {
                        mark(e);
                    }
                } else {
                    if (seqCount == seqs.length) {
                        seqs = Arrays.copyOf(seqs, seqs.length * 2);
                    }
                    seqs[seqCount++] = seq;
                }
            }
        }

        private void mark(Event e) {
            Object attrs = e.payload().get("attrs");
            Object n = attrs instanceof Map<?, ?> a ? a.get("n") : null;
            if (!(n instanceof Number number)) {
                return;
            }
            marks++;
            if (number.longValue() != lastMarkNumber + 1) {
                markBreaks++;
                problem("trace mark " + number + " after " + lastMarkNumber + " (event " + e.seq() + ")");
            }
            lastMarkNumber = number.longValue();
        }

        private void problem(String text) {
            if (problems.size() < 25) {
                problems.add(text);
            }
        }

        /** The highest sequence number of an event the filtered stream must have delivered; the baseline if none. */
        long lastMatching() {
            synchronized (lock) {
                int bit = matching.length() - 1;
                return bit < 0 ? baseline : baseline + bit;
            }
        }

        long received() {
            synchronized (lock) {
                return received;
            }
        }

        long marks() {
            synchronized (lock) {
                return marks;
            }
        }

        long matchingCount() {
            synchronized (lock) {
                return matching.cardinality();
            }
        }

        /** How the filtered stream's events differ from {@code expected}'s matching set: null when they are the same. */
        String differenceFrom(Feed expected) {
            BitSet mine = new BitSet();
            synchronized (lock) {
                for (int i = 0; i < seqCount; i++) {
                    mine.set((int) (seqs[i] - baseline));
                }
            }
            BitSet theirs;
            synchronized (expected.lock) {
                theirs = (BitSet) expected.matching.clone();
            }
            if (mine.equals(theirs)) {
                return null;
            }
            BitSet missingFromMine = (BitSet) theirs.clone();
            missingFromMine.andNot(mine);
            BitSet extra = (BitSet) mine.clone();
            extra.andNot(theirs);
            return missingFromMine.cardinality() + " events the unfiltered stream saw were not delivered (first "
                    + first(missingFromMine, baseline) + "), " + extra.cardinality() + " were delivered that it did "
                    + "not see (first " + first(extra, baseline) + ")";
        }

        private static List<Long> first(BitSet bits, long baseline) {
            List<Long> out = new ArrayList<>();
            for (int i = bits.nextSetBit(0); i >= 0 && out.size() < 5; i = bits.nextSetBit(i + 1)) {
                out.add(baseline + i);
            }
            return out;
        }

        Map<String, Object> toJson() {
            synchronized (lock) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("filter", topics);
                m.put("received", received);
                m.put("lastSeq", last);
                m.put("duplicates", duplicates);
                m.put("outOfOrder", outOfOrder);
                m.put("missing", missing);
                m.put("gapFrames", gapFrames);
                m.put("wrongTopic", wrongTopic);
                if (dense) {
                    m.put("traceMarks", marks);
                    m.put("traceMarkBreaks", markBreaks);
                    m.put("matchingFilteredTopic", (long) matching.cardinality());
                }
                m.put("problems", new ArrayList<>(problems));
                return m;
            }
        }

        boolean clean() {
            synchronized (lock) {
                return duplicates == 0 && outOfOrder == 0 && missing == 0 && gapFrames == 0 && wrongTopic == 0
                        && markBreaks == 0;
            }
        }

        SoakHistogram firstEventLatency() {
            return firstEvent;
        }
    }

    // ------------------------------------------------------------------ the run

    private static final class Soak {

        private final Config cfg;
        private final CountDownLatch stop = new CountDownLatch(1);
        private final CountDownLatch samplerStop = new CountDownLatch(1);
        private final Errors errors = new Errors();
        private final List<Sample> samples = new CopyOnWriteArrayList<>();
        private final LongAdder calls = new LongAdder();
        private final LongAdder over100ms = new LongAdder();
        private final LongAdder over1s = new LongAdder();
        private final LongAdder marksPublished = new LongAdder();
        private final LongAdder transactions = new LongAdder();
        private final LongAdder resumes = new LongAdder();
        private final SoakHistogram inputLatency = new SoakHistogram();
        private final SoakHistogram awaitLatency = new SoakHistogram();
        private final SoakHistogram transactionLatency = new SoakHistogram();
        private final SoakHistogram markLatency = new SoakHistogram();
        private final LongAdder backlogSum = new LongAdder();
        private final AtomicLong backlogMax = new AtomicLong();
        private final java.util.Set<String> agentLogWarnings = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final List<String> failures = new ArrayList<>();
        private final Map<String, Object> endCheck = new LinkedHashMap<>();
        private final Map<String, Object> bounds = new LinkedHashMap<>();

        private LendingTarget target;
        private AgentClient client;
        private long pid;
        private String javaVersion;
        private long t0;
        private long baseline;
        private Feed unfiltered;
        private Feed filtered;
        private volatile EventStream unfilteredStream;
        private volatile long agentThreadCap = Long.MAX_VALUE;
        private final List<LoadWorker> loadWorkers = new ArrayList<>();
        private long sseReconnects;
        private long finalSeq;
        private Path reportDir;

        Soak(Config cfg) {
            this.cfg = cfg;
        }

        // -------------------------------------------------------------- orchestration

        void run() throws Exception {
            try (SseLog sseLog = new SseLog()) {
                target = LendingTarget.launch(LendingTarget.Mode.JAVAAGENT);
                try {
                    client = target.client;
                    pid = target.pid();
                    soak(sseLog);
                } finally {
                    stop.countDown();
                    samplerStop.countDown();
                    target.close();
                }
            }
        }

        private void soak(SseLog sseLog) throws Exception {
            Map<String, Object> jvm = map(client.call("jvm.info", Map.of(), CALL));
            javaVersion = String.valueOf(jvm.get("javaVersion"));
            String minutesLabel = (cfg.minutes() == Math.rint(cfg.minutes()) ? Long.toString((long) cfg.minutes())
                    : Double.toString(cfg.minutes())) + "min";
            Path dir = ItEnv.runDir("soak", "java-" + javaVersion + "-" + minutesLabel);
            Files.createDirectories(dir);
            reportDir = dir;
            Instant startedAt = Instant.now();
            progress("starting: java=" + javaVersion + " pid=" + pid + " " + Json.write(cfg.toJson()));

            List<Op> ops = operations();
            Transactions transactionLoop = new Transactions();
            transactionLoop.signOnIfNeeded();
            errors.start();
            t0 = System.nanoTime();
            takeSample("start", true);
            baseline = lastSeq();

            unfiltered = new Feed("unfiltered", null, TopicFilter.parse(cfg.filter()), baseline);
            filtered = new Feed("filtered", cfg.filter(), null, baseline);
            AgentClient filteredClient = AgentClient.connect(target.endpoint);
            AgentClient unfilteredClient = AgentClient.connect(target.endpoint);
            EventStream filteredStream = null;
            long runNanos = TimeUnit.SECONDS.toNanos(cfg.runSeconds());
            boolean aborted = false;
            long finishedNanos;
            long stoppedNanos;
            List<Thread> threads = new ArrayList<>();
            Thread resumer;
            try {
                filtered.armed();
                filteredStream = filteredClient.events(baseline, cfg.filter(), filtered::accept);
                resumer = thread("soak-resumer", new Resumer(unfilteredClient));
                for (int i = 0; i < cfg.loadThreads(); i++) {
                    LoadWorker worker = new LoadWorker(i, ops);
                    loadWorkers.add(worker);
                    threads.add(thread("soak-load-" + i, worker));
                }
                Thread transactionThread = thread("soak-transactions", transactionLoop);
                Thread pacer = thread("soak-event-pacer", new Pacer());
                Thread sampler = thread("soak-sampler", this::samplerLoop);
                t0 = System.nanoTime();
                errors.start();
                resumer.start();
                transactionThread.start();
                pacer.start();
                for (Thread t : threads) {
                    t.start();
                }
                sampler.start();

                long deadline = t0 + runNanos;
                String giveUp = null;
                while (true) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    if (errors.count() >= GIVE_UP_AFTER_ERRORS) {
                        giveUp = errors.count() + " errors: giving up";
                    } else if (client.isLost()) {
                        giveUp = "the agent is lost";
                    }
                    if (giveUp != null) {
                        aborted = true;
                        failures.add("the run was ended early after " + elapsedSeconds() + " s: " + giveUp);
                        break;
                    }
                    // The soak driver's pacing: the latch is never released here, so this is a bounded sleep.
                    stop.await(Math.min(left, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
                }
                samplerStop.countDown();
                sampler.join(TimeUnit.MINUTES.toMillis(2));
                takeSample("final", true);
                finishedNanos = System.nanoTime();
                stop.countDown();
                for (Thread t : threads) {
                    joinOrFail(t, 60);
                }
                joinOrFail(pacer, 60);
                joinOrFail(transactionThread, 180);
                joinOrFail(resumer, 60);
                stoppedNanos = System.nanoTime();

                long lastPublished = lastSeq();
                finalSeq = lastPublished;
                progress("load stopped; the agent published events " + (baseline + 1) + ".." + lastPublished);
                waitFor("the unfiltered stream to deliver event " + lastPublished, 120,
                        () -> unfiltered.last() >= lastPublished);
                long wanted = unfiltered.lastMatching();
                waitFor("the filtered stream to deliver event " + wanted, 120, () -> filtered.last() >= wanted);
                verifyEvents(lastPublished);
            } finally {
                stop.countDown();
                samplerStop.countDown();
                if (filteredStream != null) {
                    filteredStream.close();
                }
                EventStream last = unfilteredStream;
                if (last != null) {
                    last.close();
                }
                filteredClient.close();
                unfilteredClient.close();
            }
            checkControllerThreads();
            takeSample("after", true);
            desk(transactionLoop);

            double runSeconds = (finishedNanos - t0) / 1e9;
            sseReconnects = sseLog.reconnects.sum();
            assess(runSeconds);
            Map<String, Object> report = report(startedAt, dir, runSeconds, (stoppedNanos - t0) / 1e9, sseLog,
                    aborted);
            Path file = dir.resolve("report.json");
            Files.writeString(file, Json.writePretty(report) + System.lineSeparator(), StandardCharsets.UTF_8);
            String summary = summary(runSeconds);
            ItEnv.timing(summary);
            progress("report: " + file);
            if (!failures.isEmpty()) {
                fail("the listener soak failed (report " + file + "):\n  " + String.join("\n  ", failures));
            }
        }

        private Thread thread(String name, Runnable body) {
            Thread t = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable e) {
                    errors.add(name, e);
                }
            }, name);
            t.setDaemon(true);
            return t;
        }

        private void joinOrFail(Thread t, int seconds) throws InterruptedException {
            t.join(TimeUnit.SECONDS.toMillis(seconds));
            if (t.isAlive()) {
                failures.add("thread " + t.getName() + " did not stop within " + seconds + " s after the run ended");
            }
        }

        private void waitFor(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > end) {
                    failures.add("gave up waiting " + seconds + " s for " + what);
                    return;
                }
                Thread.sleep(50);
            }
        }

        private double elapsedSeconds() {
            return (System.nanoTime() - t0) / 1e9;
        }

        private void progress(String line) {
            ItEnv.timing("[soak] java=" + javaVersion + " " + line);
        }

        // -------------------------------------------------------------- the read load

        private final class LoadWorker implements Runnable {
            final int index;
            final List<Op> ops;
            final SoakHistogram[] perOp;
            final LongAdder[] errorsPerOp;
            final LongAdder[] callsPerOp;

            LoadWorker(int index, List<Op> ops) {
                this.index = index;
                this.ops = ops;
                this.perOp = new SoakHistogram[ops.size()];
                this.errorsPerOp = new LongAdder[ops.size()];
                this.callsPerOp = new LongAdder[ops.size()];
                for (int i = 0; i < ops.size(); i++) {
                    perOp[i] = new SoakHistogram();
                    errorsPerOp[i] = new LongAdder();
                    callsPerOp[i] = new LongAdder();
                }
            }

            @Override
            public void run() {
                long interval = cfg.callsPerSecond() > 0
                        ? TimeUnit.SECONDS.toNanos(1) * cfg.loadThreads() / cfg.callsPerSecond() : 0;
                long next = System.nanoTime() + interval * index / Math.max(1, cfg.loadThreads());
                long n = 0;
                while (stop.getCount() > 0) {
                    int which = (int) ((index + n) % ops.size());
                    Op op = ops.get(which);
                    long started = System.nanoTime();
                    try {
                        op.body().run(client, index, n);
                    } catch (Throwable e) {
                        errorsPerOp[which].increment();
                        errors.add(op.name(), e);
                    }
                    long micros = (System.nanoTime() - started) / 1000;
                    perOp[which].record(micros);
                    callsPerOp[which].increment();
                    calls.increment();
                    if (micros >= 100_000) {
                        over100ms.increment();
                        if (micros >= 1_000_000) {
                            over1s.increment();
                        }
                    }
                    n++;
                    if (interval > 0) {
                        next = Math.max(next + interval, started);
                        long wait = next - System.nanoTime();
                        if (wait > 0) {
                            try {
                                stop.await(wait, TimeUnit.NANOSECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            }
        }

        private List<Op> operations() {
            long pid = this.pid;
            List<Op> ops = new ArrayList<>();
            ops.add(new Op("agent.info", (c, t, n) -> {
                Map<String, Object> v = map(c.call("agent.info", Map.of(), CALL));
                check(number(v.get("pid")) == pid && Boolean.TRUE.equals(v.get("ready")), "agent.info", v);
            }));
            ops.add(new Op("agent.capabilities", (c, t, n) -> {
                Map<String, Object> v = map(c.call("agent.capabilities", Map.of(), CALL));
                check(!v.isEmpty(), "agent.capabilities", v);
            }));
            ops.add(new Op("jvm.info", (c, t, n) -> {
                Map<String, Object> v = map(c.call("jvm.info", Map.of(), CALL));
                check(number(v.get("pid")) == pid && v.get("heap") instanceof Map<?, ?>, "jvm.info", v);
            }));
            ops.add(new Op("jvm.properties", (c, t, n) -> {
                String name = "soak.probe." + t + "." + n;
                Map<String, Object> v = map(c.call("jvm.properties", Map.of("names", List.of(name)), CALL));
                check(v.get("properties") instanceof Map<?, ?> p && p.size() == 1 && p.containsKey(name)
                        && p.get(name) == null, "jvm.properties echo of " + name, v);
            }));
            ops.add(new Op("evt.query", (c, t, n) -> {
                Map<String, Object> v = map(c.call("evt.query", Map.of("since", 0L, "limit", 20L), CALL));
                check(v.get("events") instanceof List<?> events && events.size() <= 20 && v.get("lastSeq") != null,
                        "evt.query", v);
            }));
            ops.add(new Op("app.state", (c, t, n) -> {
                Map<String, Object> v = map(c.call("app.state", Map.of(), CALL));
                check(v.get("lending") instanceof Map<?, ?>, "app.state", v);
            }));
            ops.add(new Op("lending.state", (c, t, n) -> {
                Map<String, Object> v = map(c.call("lending.state", Map.of(), CALL));
                check(v.get("name") instanceof String, "lending.state", v);
            }));
            ops.add(new Op("lending.loan", (c, t, n) -> {
                Map<String, Object> v = map(c.call("lending.loan", Map.of(), CALL));
                check(v.containsKey("total") || v.containsKey("lines"), "lending.loan", v);
            }));
            ops.add(new Op("lending.access.check", (c, t, n) -> {
                Map<String, Object> v = map(c.call("lending.access.check", Map.of("staff", STAFF, "permission",
                        "RETURN"), CALL));
                check(v.get("allowed") instanceof Boolean, "lending.access.check", v);
            }));
            ops.add(new Op("lending.deposit", (c, t, n) -> {
                Map<String, Object> v = map(c.call("lending.deposit", Map.of("barcode", BARCODE), CALL));
                check(Boolean.TRUE.equals(v.get("found")) && "YRD-001".equals(v.get("code"))
                        && BARCODE.equals(v.get("barcode")) && "3.50".equals(v.get("deposit")), "lending.deposit", v);
            }));
            ops.add(new Op("swing.windows", (c, t, n) -> {
                Map<String, Object> v = map(c.call("swing.windows", Map.of(), CALL));
                check(v.get("windows") instanceof List<?> w && !w.isEmpty(), "swing.windows", v);
            }));
            ops.add(new Op("swing.snapshot", (c, t, n) -> {
                Map<String, Object> v = map(c.call("swing.snapshot", Map.of("maxNodes", 500L), CALL));
                check(v.get("windows") instanceof List<?> w && !w.isEmpty(), "swing.snapshot", v);
            }));
            ops.add(new Op("swing.find", (c, t, n) -> {
                Map<String, Object> v = map(c.call("swing.find", Map.of("by", Map.of("class", "JPanel"), "all", true),
                        CALL));
                check(v.get("matches") instanceof List<?>, "swing.find", v);
            }));
            ops.add(new Op("swing.edtState", (c, t, n) -> {
                Map<String, Object> v = map(c.call("swing.edtState", Map.of(), CALL));
                check(Boolean.TRUE.equals(v.get("dispatchThreadAlive")) && Boolean.TRUE.equals(v.get("awtStarted")),
                        "swing.edtState", v);
            }));
            ops.add(new Op("GET /capabilities", (c, t, n) -> {
                if (c.capabilities().all().isEmpty()) {
                    throw new IllegalStateException("GET /capabilities: an empty matrix");
                }
            }));
            return ops;
        }

        // -------------------------------------------------------------- the desk's transactions

        private final class Transactions implements Runnable {

            /** The desk is signed off at the start; sign the assistant on (password of seed A, staff 100). */
            void signOnIfNeeded() throws Exception {
                Map<String, Object> state = map(client.call("lending.state", Map.of(), CALL));
                if ("SIGNED_OFF".equals(state.get("name"))) {
                    input("Entry", STAFF, null);
                    input("Entry", LoadModeChecks.PASSWORD, "IDLE");
                } else if (!"IDLE".equals(state.get("name"))) {
                    throw new IllegalStateException("the desk is not idle at the start: " + brief(state));
                }
            }

            @Override
            public void run() {
                long interval = cfg.transactionsPerSecond() > 0
                        ? TimeUnit.SECONDS.toNanos(1) / cfg.transactionsPerSecond() : 0;
                long next = System.nanoTime();
                while (stop.getCount() > 0) {
                    long started = System.nanoTime();
                    try {
                        oneTransaction();
                        transactions.increment();
                        transactionLatency.record((System.nanoTime() - started) / 1000);
                    } catch (Throwable e) {
                        errors.add("transaction", e);
                        recover();
                    }
                    if (interval > 0) {
                        // The soak driver's pacing: one transaction start every interval, never a burst to catch up.
                        next = Math.max(next + interval, started);
                        long wait = next - System.nanoTime();
                        if (wait > 0) {
                            try {
                                stop.await(wait, TimeUnit.NANOSECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            }

            private void recover() {
                try {
                    Runs.PACK.recover(client);
                } catch (Throwable e) {
                    errors.add("recovery", e);
                }
            }

            private void oneTransaction() throws Exception {
                Map<String, Object> state = map(client.call("lending.state", Map.of(), CALL));
                if ("SIGNED_OFF".equals(state.get("name"))) {
                    signOnIfNeeded();
                } else if (!"IDLE".equals(state.get("name"))) {
                    throw new IllegalStateException("the desk is not idle: " + brief(state));
                }
                input("Barcode", BARCODE, "LOAN");
                input("Barcode", BARCODE, "LOAN");
                input("Key.DUE", null, "DEPOSIT");
                long before = input("Key.DEPOSIT", "10.00", "IDLE");
                long waitStarted = System.nanoTime();
                Event posted = client.await("loan.posted", before, null, AWAIT);
                awaitLatency.record((System.nanoTime() - waitStarted) / 1000);
                if (posted == null) {
                    throw new IllegalStateException("no loan.posted within " + AWAIT + " after the deposit");
                }
                Map<String, Object> loan = map(client.call("lending.loan", Map.of(), CALL));
                check("7.00".equals(String.valueOf(loan.get("total"))) && Boolean.FALSE.equals(loan.get("open")),
                        "the posted loan", loan);
            }

            /**
             * {@code lending.input}, then the desk's next "waiting" or "asking" event since the call began (the
             * checks' {@code send}); the state that event reports must be {@code expectedState} when one is given.
             *
             * @return the sequence number before the call
             */
            private long input(String source, String value, String expectedState) throws Exception {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("source", source);
                if (value != null) {
                    params.put("value", value);
                }
                long started = System.nanoTime();
                RpcResult result = client.call("lending.input", params, CALL);
                inputLatency.record((System.nanoTime() - started) / 1000);
                check(Boolean.TRUE.equals(map(result).get("acked")), "lending.input " + source, map(result));
                long waitStarted = System.nanoTime();
                Event done = client.await(READY_TOPICS, result.seqBefore(), null, AWAIT);
                awaitLatency.record((System.nanoTime() - waitStarted) / 1000);
                if (done == null) {
                    throw new IllegalStateException("no input.requested or prompt.waiting within " + AWAIT + " after "
                            + source);
                }
                if (expectedState != null && !expectedState.equals(done.payload().get("state"))) {
                    throw new IllegalStateException("after " + source + " the desk reported " + done.topic() + " "
                            + brief(done.payload()) + ", not state " + expectedState);
                }
                return result.seqBefore();
            }
        }

        // -------------------------------------------------------------- the event rate

        private final class Pacer implements Runnable {
            @Override
            public void run() {
                long number = 0;
                long started = System.nanoTime();
                while (stop.getCount() > 0) {
                    try {
                        double elapsed = (System.nanoTime() - started) / 1e9;
                        long due = (long) (cfg.eventsPerSecond() * elapsed);
                        long published = lastSeq() - baseline;
                        long deficit = Math.min(due - published, 100);
                        for (long i = 0; i < deficit && stop.getCount() > 0; i++) {
                            number++;
                            long t = System.nanoTime();
                            Map<String, Object> attrs = new LinkedHashMap<>();
                            attrs.put("n", number);
                            Map<String, Object> params = new LinkedHashMap<>();
                            params.put("label", "soak");
                            params.put("attrs", attrs);
                            RpcResult r = client.call("trace.mark", params, CALL);
                            markLatency.record((System.nanoTime() - t) / 1000);
                            check(map(r).get("seq") instanceof Number, "trace.mark", map(r));
                            marksPublished.increment();
                        }
                    } catch (Throwable e) {
                        errors.add("event pacer", e);
                    }
                    try {
                        stop.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /** The agent's highest event sequence number, from an {@code evt.query} that asks for nothing. */
        private long lastSeq() throws Exception {
            Map<String, Object> v = map(client.call("evt.query", Map.of("since", NEVER_SEQ, "limit", 1L), CALL));
            return number(v.get("lastSeq"));
        }

        // -------------------------------------------------------------- the unfiltered stream and its resumes

        private final class Resumer implements Runnable {
            private final AgentClient streamClient;
            private final Random random = new Random(20261011L);

            Resumer(AgentClient streamClient) {
                this.streamClient = streamClient;
            }

            @Override
            public void run() {
                try {
                    EventStream stream = open();
                    while (true) {
                        long span = cfg.resumeMinSeconds() + random.nextInt(
                                Math.max(1, cfg.resumeMaxSeconds() - cfg.resumeMinSeconds() + 1));
                        if (stop.await(span, TimeUnit.SECONDS)) {
                            break;
                        }
                        stream.close();
                        unfilteredStream = null;
                        // Disconnected: events keep coming, so the resume has a backlog to replay.
                        stop.await(200 + random.nextInt(2800), TimeUnit.MILLISECONDS);
                        resumes.increment();
                        stream = open();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    errors.add("resumer", e);
                }
            }

            private EventStream open() {
                try {
                    long backlog = Math.max(0, lastSeq() - unfiltered.last());
                    backlogSum.add(backlog);
                    backlogMax.accumulateAndGet(backlog, Math::max);
                } catch (Throwable e) {
                    errors.add("resume backlog", e);
                }
                unfiltered.armed();
                EventStream stream = streamClient.events(unfiltered.last(), null, unfiltered::accept);
                unfilteredStream = stream;
                return stream;
            }
        }

        // -------------------------------------------------------------- sampling

        private void samplerLoop() {
            long interval = TimeUnit.SECONDS.toNanos(cfg.sampleSeconds());
            long checkpoint = TimeUnit.SECONDS.toNanos(cfg.checkpointSeconds());
            long nextCheckpoint = t0 + checkpoint;
            long next = t0 + interval;
            try {
                while (true) {
                    long wait = next - System.nanoTime();
                    if (wait > 0 && samplerStop.await(wait, TimeUnit.NANOSECONDS)) {
                        return;
                    }
                    next += interval;
                    boolean isCheckpoint = System.nanoTime() >= nextCheckpoint;
                    if (isCheckpoint) {
                        nextCheckpoint += checkpoint;
                    }
                    takeSample(isCheckpoint ? "checkpoint" : "sample", isCheckpoint);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private synchronized void takeSample(String label, boolean forceGc) {
            try {
                Collected collected = forceGc ? collectGarbage(label) : null;
                boolean forced = collected != null;
                double t = elapsedSeconds();
                Map<String, Object> jvm = map(client.call("jvm.info", Map.of(), CALL));
                Map<?, ?> heap = (Map<?, ?>) jvm.get("heap");
                Map<String, Object> threadReply = map(client.call("jvm.threads", Map.of(), CALL));
                List<?> threads = (List<?>) threadReply.get("threads");
                Map<String, Object> byName = new java.util.TreeMap<>();
                int agent = 0;
                for (Object o : threads) {
                    String name = String.valueOf(((Map<?, ?>) o).get("name"));
                    if (name.contains("skillroy-")) {
                        agent++;
                        byName.merge(name.replaceAll("\\d+$", "#"), 1L, (a, b) -> (Long) a + (Long) b);
                    }
                }
                Map<String, Object> info = map(client.call("agent.info", Map.of(), CALL));
                Map<?, ?> pools = (Map<?, ?>) info.get("threads");
                Map<?, ?> workers = (Map<?, ?>) pools.get("workers");
                Map<?, ?> callers = (Map<?, ?>) pools.get("callers");
                Map<?, ?> options = (Map<?, ?>) info.get("options");
                long httpThreads = options != null && options.get("httpThreads") != null
                        ? Long.parseLong(String.valueOf(options.get("httpThreads"))) : 32L;
                Map<String, Object> ring = map(client.call("evt.query", Map.of("since", NEVER_SEQ, "limit", 1L), CALL));
                collectAgentLog();
                Runtime rt = Runtime.getRuntime();
                int sse = 0;
                for (Thread t2 : Thread.getAllStackTraces().keySet()) {
                    if (t2.getName().startsWith("skillroy-sse-")) {
                        sse++;
                    }
                }
                Sample s = new Sample(t, label, forced, number(heap.get("used")), number(heap.get("max")),
                        threads.size(), agent, byName, number(workers.get("active")), number(workers.get("max")),
                        number(callers.get("active")), number(callers.get("max")), httpThreads,
                        number(ring.get("lastSeq")), number(ring.get("oldestSeq")), calls.sum(),
                        rt.totalMemory() - rt.freeMemory(), Thread.getAllStackTraces().size(), sse,
                        forced ? collected.totalBytes() : 0L, forced ? collected.top() : List.of(),
                        forced ? collected.classes() : Map.of(), transactions.sum());
                agentThreadCap = s.workersMax() + s.callersMax() + s.httpThreads() + 8;
                samples.add(s);
                if (!label.equals("sample")) {
                    progress(s.line() + " | calls=" + calls.sum() + " errors=" + errors.count() + " transactions="
                            + transactions.sum() + " resumes=" + resumes.sum() + " unfiltered=" + unfiltered());
                }
            } catch (Throwable e) {
                errors.add("sampler (" + label + ")", e);
            }
        }

        private String unfiltered() {
            return unfiltered == null ? "-" : unfiltered.received() + "@" + unfiltered.last();
        }

        /** What a collection with a class histogram found: the live objects' bytes in all and the biggest classes. */
        private record Collected(long totalBytes, List<String> top, Map<String, long[]> classes) {
        }

        /**
         * A full collection in the target with a histogram of what survived it, by its own JDK's {@code jcmd}
         * ({@code GC.class_histogram} counts live objects only, so it collects first). The first 80 lines go to
         * {@code histogram-<label>-<seconds>s.txt} beside the report. Null when that is not possible.
         */
        private Collected collectGarbage(String label) {
            Path jcmd = ItEnv.targetJava().resolveSibling("jcmd");
            if (!Files.isExecutable(jcmd) || reportDir == null) {
                return null;
            }
            try {
                Path raw = Files.createTempFile(reportDir, "histogram", ".tmp");
                try {
                    Process p = new ProcessBuilder(jcmd.toString(), Long.toString(pid), "GC.class_histogram")
                            .redirectErrorStream(true).redirectOutput(raw.toFile()).start();
                    if (!Processes.await(p, Duration.ofSeconds(120)) || p.exitValue() != 0) {
                        errors.add("jcmd GC.class_histogram", new IllegalStateException("did not succeed"));
                        return null;
                    }
                    List<String> lines = Files.readAllLines(raw, StandardCharsets.UTF_8);
                    long total = 0;
                    List<String> top = new ArrayList<>();
                    Map<String, long[]> classes = new java.util.HashMap<>();
                    for (String line : lines) {
                        String text = line.strip();
                        if (text.startsWith("Total")) {
                            String[] parts = text.split("\\s+");
                            total = Long.parseLong(parts[2]);
                            continue;
                        }
                        java.util.regex.Matcher m = HISTOGRAM_LINE.matcher(text);
                        if (!m.matches()) {
                            continue;
                        }
                        if (top.size() < 10) {
                            top.add(text.replaceAll("\\s+", " "));
                        }
                        long bytes = Long.parseLong(m.group(2));
                        if (bytes >= 16_384 || m.group(3).startsWith("ai.skillroy.")) {
                            classes.put(m.group(3), new long[] {Long.parseLong(m.group(1)), bytes});
                        }
                    }
                    List<String> kept = new ArrayList<>(lines.subList(0, Math.min(80, lines.size())));
                    kept.addAll(lines.stream().filter(l -> l.strip().startsWith("Total")).toList());
                    Files.write(reportDir.resolve(String.format(Locale.ROOT, "histogram-%s-%.0fs.txt", label,
                            elapsedSeconds())), kept, StandardCharsets.UTF_8);
                    return new Collected(total, top, classes);
                } finally {
                    Files.deleteIfExists(raw);
                }
            } catch (IOException | RuntimeException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                errors.add("jcmd GC.class_histogram", e);
                return null;
            }
        }

        private void collectAgentLog() {
            try {
                Map<String, Object> v = map(client.call("agent.log", Map.of("n", 2000L), CALL));
                for (Object line : (List<?>) v.get("lines")) {
                    String text = String.valueOf(line);
                    if (text.contains(" WARN ") || text.contains(" ERROR ")) {
                        agentLogWarnings.add(text.length() > 600 ? text.substring(0, 600) : text);
                    }
                }
            } catch (Throwable e) {
                errors.add("agent.log", e);
            }
        }

        // -------------------------------------------------------------- the checks

        private void verifyEvents(long finalSeq) {
            long published = finalSeq - baseline;
            if (unfiltered.received() != published) {
                failures.add("the unfiltered stream delivered " + unfiltered.received() + " events; the agent "
                        + "published " + published + " (" + (baseline + 1) + ".." + finalSeq + ")");
            }
            if (!unfiltered.clean()) {
                failures.add("the unfiltered stream was not in order and complete: " + unfiltered.toJson());
            }
            if (unfiltered.marks() != marksPublished.sum()) {
                failures.add("the unfiltered stream delivered " + unfiltered.marks() + " trace marks; "
                        + marksPublished.sum() + " were published");
            }
            if (!filtered.clean()) {
                failures.add("the filtered stream was not in order: " + filtered.toJson());
            }
            String difference = filtered.differenceFrom(unfiltered);
            if (difference != null) {
                failures.add("the filtered stream differs from the matching events of the unfiltered one: "
                        + difference);
            }
            if ("loan.posted".equals(cfg.filter()) && filtered.received() != transactions.sum()) {
                failures.add("the filtered stream delivered " + filtered.received() + " loan.posted events; "
                        + transactions.sum() + " transactions completed");
            }
            long minimumResumes = Math.max(1, cfg.runSeconds() / (cfg.resumeMaxSeconds() + 4));
            if (resumes.sum() < minimumResumes) {
                failures.add("the unfiltered stream resumed " + resumes.sum() + " times; at least " + minimumResumes
                        + " were expected");
            }
        }

        private void checkControllerThreads() throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int sse;
            do {
                sse = 0;
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    if (t.getName().startsWith("skillroy-sse-") && t.isAlive()) {
                        sse++;
                    }
                }
                if (sse > 0) {
                    Thread.sleep(100);
                }
            } while (sse > 0 && System.nanoTime() < end);
            if (sse > 0) {
                failures.add(sse + " event-stream threads of the controller are still alive 10 s after the streams "
                        + "were closed");
            }
        }

        /** The desk still answers: ping, state, event thread, and scenario basic-loan on the executor. */
        private void desk(Transactions transactionLoop) {
            try {
                Map<String, Object> ping = client.ping();
                endCheck.put("pingReady", ping.get("ready"));
                Map<String, Object> state = map(client.call("lending.state", Map.of(), CALL));
                endCheck.put("deskState", state.get("name"));
                Map<String, Object> edt = map(client.call("swing.edtState", Map.of(), CALL));
                endCheck.put("dispatchThreadAlive", edt.get("dispatchThreadAlive"));
                endCheck.put("edtBlocked", edt.get("edtBlocked"));
                if (!Boolean.TRUE.equals(ping.get("ready")) || !Boolean.TRUE.equals(edt.get("dispatchThreadAlive"))) {
                    failures.add("the desk does not answer as it should at the end: ping " + brief(ping) + ", edt "
                            + brief(edt));
                }
                if (!"IDLE".equals(state.get("name"))) {
                    failures.add("the desk is not idle at the end: " + brief(state));
                    transactionLoop.recover();
                }
                RpcResult off = client.call("lending.input", Map.of("source", "Key.SIGN_OFF"), CALL);
                if (client.await(READY_TOPICS, off.seqBefore(), null, AWAIT) == null) {
                    failures.add("no event after the sign-off at the end");
                }
                Runs.Outcome outcome = Runs.run(client, Runs.committed("basic-loan"),
                        ItEnv.runDir("soak", "end-basic-loan-" + javaVersion));
                try {
                    Runs.assertPassedWithoutSleeping(outcome);
                    endCheck.put("basicLoan", "PASS");
                } catch (AssertionError e) {
                    endCheck.put("basicLoan", "FAIL: " + e.getMessage());
                    failures.add("scenario basic-loan after the soak: " + e.getMessage());
                }
            } catch (Throwable e) {
                endCheck.put("error", String.valueOf(e));
                failures.add("the desk does not answer at the end: " + e);
            }
        }

        private void assess(double runSeconds) {
            if (errors.count() > 0) {
                failures.add(errors.count() + " errors; first: " + errors.first.peek());
            }
            SoakHistogram read = merged(null);
            if (read.percentileMicros(99) / 1000.0 >= P99_LIMIT_MS) {
                failures.add(String.format(Locale.ROOT, "the read load's p99 is %.2f ms; the limit is %.0f ms",
                        read.percentileMicros(99) / 1000.0, P99_LIMIT_MS));
            }
            if (sseReconnects > 0) {
                failures.add("the event stream reconnected by itself " + sseReconnects + " times (a dropped "
                        + "connection)");
            }
            double warm = Math.min(600.0, runSeconds / 4);
            heapBound(runSeconds, warm);
            threadBounds(runSeconds, warm);
        }

        private void heapBound(double runSeconds, double warm) {
            Map<String, Object> heap = new LinkedHashMap<>();
            bounds.put("heap", heap);
            heap.put("toleranceRule", "the live heap (after a full GC) is measured at every checkpoint; the lowest "
                    + "of the last quarter's checkpoints is at most the lowest of the first quarter after the warm-up "
                    + "(a quarter of the run, at most ten minutes) plus max(32 MiB, 25 %). The lowest of several is "
                    + "compared because one collection under load also keeps what the calls in flight hold");
            heap.put("warmUpSeconds", SoakHistogram.round(warm));
            double window = (runSeconds - warm) / 4;
            List<Sample> settled = new ArrayList<>();
            List<Sample> lastQuarter = new ArrayList<>();
            List<Sample> afterWarmUp = new ArrayList<>();
            Sample start = null;
            Sample after = null;
            for (Sample s : samples) {
                if (s.label().equals("start")) {
                    start = s;
                }
                if (s.label().equals("after")) {
                    after = s;
                }
                if (!s.gcForced() || s.label().equals("after")) {
                    continue;
                }
                if (s.t() >= warm) {
                    afterWarmUp.add(s);
                }
                if (s.t() >= warm && s.t() <= warm + window) {
                    settled.add(s);
                }
                if (s.t() >= runSeconds - window) {
                    lastQuarter.add(s);
                }
            }
            if (start != null) {
                heap.put("startLiveMB", mb(start.live()));
            }
            if (after != null) {
                heap.put("afterRunLiveMB", mb(after.live()));
            }
            if (settled.isEmpty() || lastQuarter.isEmpty()) {
                heap.put("assessed", false);
                heap.put("note", "no collected sample in the first quarter after the warm-up or in the last quarter: "
                        + "the run is too short for its checkpoints, or jcmd is missing from the target's JDK");
                failures.add("the heap bound could not be assessed (no collected sample in a window)");
                return;
            }
            long settledMin = settled.stream().mapToLong(Sample::live).min().getAsLong();
            long lastMin = lastQuarter.stream().mapToLong(Sample::live).min().getAsLong();
            long tolerance = Math.max(HEAP_TOLERANCE_BYTES, settledMin / 4);
            double sumT = 0;
            double sumY = 0;
            double sumTT = 0;
            double sumTY = 0;
            int n = afterWarmUp.size();
            for (Sample s : afterWarmUp) {
                sumT += s.t();
                sumY += s.live();
                sumTT += s.t() * s.t();
                sumTY += s.t() * s.live();
            }
            double slope = n < 3 ? 0.0 : (n * sumTY - sumT * sumY) / (n * sumTT - sumT * sumT);
            heap.put("assessed", true);
            heap.put("checkpointsAfterWarmUp", (long) n);
            heap.put("firstQuarterLowestLiveMB", mb(settledMin));
            heap.put("firstQuarterCheckpoints", (long) settled.size());
            heap.put("lastQuarterLowestLiveMB", mb(lastMin));
            heap.put("lastQuarterCheckpoints", (long) lastQuarter.size());
            heap.put("highestLiveMB", mb(afterWarmUp.stream().mapToLong(Sample::live).max().orElse(0)));
            heap.put("toleranceMB", mb(tolerance));
            heap.put("trendMBPerHour", SoakHistogram.round(slope * 3600.0 / 1048576.0));
            if (lastMin > settledMin + tolerance) {
                failures.add(String.format(Locale.ROOT, "the live heap grew: lowest %.1f MB in the first quarter after "
                        + "the warm-up, lowest %.1f MB in the last quarter (tolerance %.1f MB)",
                        settledMin / 1048576.0, lastMin / 1048576.0, tolerance / 1048576.0));
            }

            // What the growth is made of: the agent's own classes, the desk's own data, the rest.
            Sample first = settled.stream().min(Comparator.comparingLong(Sample::live)).get();
            Sample last = lastQuarter.stream().min(Comparator.comparingLong(Sample::live)).get();
            long agentFirst = settled.stream().mapToLong(Sample::agentOwned).min().getAsLong();
            long agentLast = lastQuarter.stream().mapToLong(Sample::agentOwned).min().getAsLong();
            heap.put("agentOwnedRule", "the lowest bytes of live objects of the agent's own classes ("
                    + String.join(", ", AGENT_PACKAGES) + ") in the last quarter is at most the lowest in the first "
                    + "quarter after the warm-up plus " + (AGENT_OWNED_TOLERANCE_BYTES >> 20) + " MiB");
            heap.put("agentOwnedFirstQuarterLowestMB", mb(agentFirst));
            heap.put("agentOwnedLastQuarterLowestMB", mb(agentLast));
            heap.put("deskOwnedGrowthMB", mb(last.deskOwned() - first.deskOwned()));
            heap.put("transactionsBetween", last.transactionsSoFar() - first.transactionsSoFar());
            heap.put("growthComparedBetweenSecond", SoakHistogram.round(first.t()) + " and "
                    + SoakHistogram.round(last.t()));
            heap.put("largestGrowthByClass", largestGrowth(first, last));
            if (agentLast > agentFirst + AGENT_OWNED_TOLERANCE_BYTES) {
                failures.add(String.format(Locale.ROOT, "the agent's own live objects grew: lowest %.2f MB in the "
                        + "first quarter after the warm-up, lowest %.2f MB in the last quarter (tolerance %d MB)",
                        agentFirst / 1048576.0, agentLast / 1048576.0, AGENT_OWNED_TOLERANCE_BYTES >> 20));
            }
        }

        /** The classes whose live bytes grew most between two collected samples, as {@code name +n KB}. */
        private static List<String> largestGrowth(Sample from, Sample to) {
            Map<String, Long> growth = new java.util.HashMap<>();
            for (Map.Entry<String, long[]> e : to.classes().entrySet()) {
                growth.merge(e.getKey(), e.getValue()[1], Long::sum);
            }
            for (Map.Entry<String, long[]> e : from.classes().entrySet()) {
                growth.merge(e.getKey(), -e.getValue()[1], Long::sum);
            }
            List<String> out = new ArrayList<>();
            growth.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8)
                    .forEach(e -> out.add(e.getKey() + " " + (e.getValue() >= 0 ? "+" : "") + (e.getValue() >> 10)
                            + " KB"));
            return out;
        }

        private void threadBounds(double runSeconds, double warm) {
            Map<String, Object> threads = new LinkedHashMap<>();
            bounds.put("threads", threads);
            threads.put("toleranceRule", "the most threads in the last quarter of the run is at most " + THREAD_TOLERANCE
                    + " above the most in the first quarter after the warm-up; the agent's threads never exceed its "
                    + "pools (workers + callers + connections) + 8");
            double window = (runSeconds - warm) / 4;
            int settledAgent = -1;
            int lastAgent = -1;
            int settledTotal = -1;
            int lastTotal = -1;
            int peakAgent = 0;
            for (Sample s : samples) {
                if (s.label().equals("after")) {
                    continue;
                }
                peakAgent = Math.max(peakAgent, s.threadsAgent());
                if (s.t() >= warm && s.t() <= warm + window) {
                    settledAgent = Math.max(settledAgent, s.threadsAgent());
                    settledTotal = Math.max(settledTotal, s.threadsTotal());
                }
                if (s.t() >= runSeconds - window) {
                    lastAgent = Math.max(lastAgent, s.threadsAgent());
                    lastTotal = Math.max(lastTotal, s.threadsTotal());
                }
            }
            threads.put("agentCap", agentThreadCap);
            threads.put("agentPeak", (long) peakAgent);
            threads.put("agentMaxFirstQuarter", (long) settledAgent);
            threads.put("agentMaxLastQuarter", (long) lastAgent);
            threads.put("totalMaxFirstQuarter", (long) settledTotal);
            threads.put("totalMaxLastQuarter", (long) lastTotal);
            if (peakAgent > agentThreadCap) {
                failures.add("the agent had " + peakAgent + " threads at most; its pools allow " + agentThreadCap);
            }
            if (settledAgent < 0 || lastAgent < 0) {
                threads.put("assessed", false);
                return;
            }
            threads.put("assessed", true);
            if (lastAgent > settledAgent + THREAD_TOLERANCE) {
                failures.add("the agent's threads grew: at most " + settledAgent + " in the first quarter, " + lastAgent
                        + " in the last");
            }
            if (lastTotal > settledTotal + THREAD_TOLERANCE) {
                failures.add("the target's threads grew: at most " + settledTotal + " in the first quarter, "
                        + lastTotal + " in the last");
            }
        }

        // -------------------------------------------------------------- the report

        private SoakHistogram merged(String opName) {
            SoakHistogram all = new SoakHistogram();
            for (LoadWorker w : loadWorkers) {
                for (int i = 0; i < w.perOp.length; i++) {
                    if (opName == null || w.ops.get(i).name().equals(opName)) {
                        all.add(w.perOp[i]);
                    }
                }
            }
            return all;
        }

        private Map<String, Object> report(Instant startedAt, Path dir, double runSeconds, double totalSeconds,
                                           SseLog sseLog, boolean aborted) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("test", "ListenerSoakIT");
            r.put("verdict", failures.isEmpty() ? "PASS" : "FAIL");
            r.put("failures", new ArrayList<>(failures));
            r.put("startedAt", startedAt.toString());
            r.put("targetJava", javaVersion);
            r.put("targetJavaBinary", ItEnv.targetJava().toString());
            r.put("controllerJava", System.getProperty("java.version"));
            r.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", "
                    + Runtime.getRuntime().availableProcessors() + " CPUs");
            r.put("loadMode", "javaagent");
            r.put("targetPid", pid);
            r.put("targetLog", ItEnv.log(LendingTarget.Mode.JAVAAGENT.slug + ".log").toString());
            r.put("config", cfg.toJson());
            r.put("endedEarly", aborted);
            r.put("loadSeconds", SoakHistogram.round(runSeconds));
            r.put("stoppedAfterSeconds", SoakHistogram.round(totalSeconds));

            SoakHistogram all = merged(null);
            Map<String, Object> callsJson = new LinkedHashMap<>();
            callsJson.put("total", calls.sum());
            callsJson.put("perSecond", SoakHistogram.round(calls.sum() / runSeconds));
            callsJson.put("latency", all.summary());
            callsJson.put("p99LimitMs", P99_LIMIT_MS);
            callsJson.put("atLeast100ms", over100ms.sum());
            callsJson.put("atLeast1s", over1s.sum());
            Map<String, Object> perOp = new LinkedHashMap<>();
            for (Op op : loadWorkers.get(0).ops) {
                SoakHistogram h = merged(op.name());
                Map<String, Object> m = new LinkedHashMap<>(h.summary());
                long errorsOfOp = 0;
                for (LoadWorker w : loadWorkers) {
                    for (int i = 0; i < w.ops.size(); i++) {
                        if (w.ops.get(i).name().equals(op.name())) {
                            errorsOfOp += w.errorsPerOp[i].sum();
                        }
                    }
                }
                m.put("errors", errorsOfOp);
                perOp.put(op.name(), m);
            }
            callsJson.put("perOperation", perOp);
            r.put("readLoad", callsJson);

            Map<String, Object> tx = new LinkedHashMap<>();
            tx.put("completed", transactions.sum());
            tx.put("perSecond", SoakHistogram.round(transactions.sum() / runSeconds));
            tx.put("wholeTransactionLatency", transactionLatency.summary());
            tx.put("lendingInputCallLatency", inputLatency.summary());
            tx.put("syncAwaitLatency", awaitLatency.summary());
            r.put("transactions", tx);

            Map<String, Object> events = new LinkedHashMap<>();
            events.put("baselineSeq", baseline);
            events.put("finalSeq", finalSeq);
            events.put("published", finalSeq - baseline);
            events.put("publishedPerSecond", SoakHistogram.round((finalSeq - baseline) / runSeconds));
            events.put("traceMarksPublished", marksPublished.sum());
            events.put("traceMarkCallLatency", markLatency.summary());
            events.put("unfiltered", unfiltered.toJson());
            events.put("filtered", filtered.toJson());
            r.put("events", events);

            Map<String, Object> sse = new LinkedHashMap<>();
            sse.put("resumes", resumes.sum());
            sse.put("secondsBetweenResumes", cfg.resumeMinSeconds() + " to " + cfg.resumeMaxSeconds());
            sse.put("backlogAtResumeMaxEvents", backlogMax.get());
            sse.put("backlogAtResumeMeanEvents", SoakHistogram.round(backlogSum.sum() / (double) (resumes.sum() + 1)));
            sse.put("msToFirstEventAfterOpen", unfiltered.firstEventLatency().summary());
            sse.put("reconnectsByTheStream", sseLog.reconnects.sum());
            sse.put("streamWarnings", new ArrayList<>(sseLog.warnings));
            r.put("eventStreams", sse);

            List<Object> series = new ArrayList<>();
            for (Sample s : samples) {
                series.add(s.toJson());
            }
            r.put("samples", series);
            r.put("bounds", bounds);
            r.put("deskAtTheEnd", endCheck);
            r.put("errors", errors.toJson());
            r.put("agentLogWarnings", new ArrayList<>(new java.util.TreeSet<>(agentLogWarnings)));
            r.put("reportDirectory", dir.toString());
            return r;
        }

        private String heapFigure(String key) {
            return bounds.get("heap") instanceof Map<?, ?> heap && heap.get(key) != null ? String.valueOf(heap.get(key))
                    : "n/a";
        }

        private String summary(double runSeconds) {
            SoakHistogram all = merged(null);
            Sample first = samples.get(0);
            Sample last = null;
            for (Sample s : samples) {
                if (s.label().equals("final")) {
                    last = s;
                }
            }
            if (last == null) {
                last = samples.get(samples.size() - 1);
            }
            return String.format(Locale.ROOT, "[soak] java=%s %s %.1f min: calls=%d (%.0f/s) errors=%d "
                    + "p50/p95/p99/max=%.2f/%.2f/%.2f/%.2f ms transactions=%d events published=%d "
                    + "unfiltered=%d filtered=%d resumes=%d threads agent %d->%d total %d->%d live heap MB start %.1f, "
                    + "lowest first/last quarter %s/%s%s",
                    javaVersion, failures.isEmpty() ? "PASS" : "FAIL", runSeconds / 60.0, calls.sum(),
                    calls.sum() / runSeconds, errors.count(), all.percentileMicros(50) / 1000.0,
                    all.percentileMicros(95) / 1000.0, all.percentileMicros(99) / 1000.0, all.maxMicros() / 1000.0,
                    transactions.sum(), finalSeq - baseline, unfiltered.received(), filtered.received(),
                    resumes.sum(), first.threadsAgent(), last.threadsAgent(), first.threadsTotal(),
                    last.threadsTotal(), first.heapUsed() / 1048576.0, heapFigure("firstQuarterLowestLiveMB"),
                    heapFigure("lastQuarterLowestLiveMB"),
                    failures.isEmpty() ? "" : " (" + failures.size() + " failures)");
        }
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(RpcResult result) {
        Object value = result.value();
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("expected an object, got " + brief(value));
        }
        return (Map<String, Object>) value;
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("expected a number, got " + brief(value));
    }

    private static void check(boolean holds, String what, Object reply) {
        if (!holds) {
            throw new IllegalStateException("unexpected reply of " + what + ": " + brief(reply));
        }
    }

    private static String brief(Object value) {
        String text = value instanceof String s ? s : Json.write(value);
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
