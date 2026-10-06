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

import ai.skillroy.testjava.controller.EventStream;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * {@code skillroy events}: the agent's event stream on standard output, for watching what the application does
 * while a person or a scenario drives it.
 *
 * <p>Events arrive on the stream's own daemon thread ({@code skillroy-sse-<n>}) and are printed under a lock; the
 * command's thread waits on that lock (waking at least every 250 ms to notice a lost agent) until the stream ends,
 * the count or timeout is reached, standard output is closed, or the process is interrupted.
 */
final class EventsCommand implements Command {

    private static final String SINCE = "--since";
    private static final String TOPICS = "--topics";
    private static final String COUNT = "--count";
    private static final String TIMEOUT = "--timeout";
    private static final long WAKE_MS = 250;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private static final CommandSpec SPEC = new CommandSpec("events", "stream the agent's events until interrupted",
            "", """
            Streams the agent's events (GET /events, server-sent events) to standard output, one line per event:
            sequence number, local time, topic and payload. After a dropped connection the stream resumes where it
            stopped; events that fell out of the agent's ring meanwhile are reported as a 'gap' line. Runs until
            interrupted (Ctrl-C), until --count events were printed or --timeout has passed, or until standard
            output is closed; exit code 3 when the agent is lost.""",
            Connector.withConnection(
                    OptionSpec.value(SINCE, "seq", "print the events after this sequence number (0: all still in\n"
                            + "the agent's ring); default: only new events"),
                    OptionSpec.value(TOPICS, "globs", "only these topics: comma-separated globs, '*' matches dots\n"
                            + "too (window.*,prompt.*)"),
                    OptionSpec.value(COUNT, "n", "stop after n events"),
                    OptionSpec.value(TIMEOUT, "duration", "stop after this long (10s, 2m, ...)"),
                    OptionSpec.flag("--json", "print one event JSON object per line (JSON Lines)")),
            0, 0);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        Long since = args.longValue(SINCE, 0, Long.MAX_VALUE);
        String topics = args.value(TOPICS);
        Long count = args.longValue(COUNT, 1, Long.MAX_VALUE);
        Long timeoutMs = args.durationMs(TIMEOUT);
        boolean json = args.flag("--json");
        try (Target target = Connector.connect(args, cli)) {
            Printer printer = new Printer(cli.out(), cli.err(), json, count == null ? Long.MAX_VALUE : count,
                    target::scrub);
            EventStream stream = target.client().events(since == null ? -1 : since, topics, printer::print);
            boolean lost;
            try {
                lost = printer.await(stream, timeoutMs);
            } finally {
                stream.close();
            }
            if (lost) {
                throw new CliException(Exit.UNREACHABLE, "the event stream ended: the agent at 127.0.0.1:"
                        + target.endpoint().port() + " was lost");
            }
            return Exit.OK;
        }
    }

    /** Prints events from the stream thread and tells the command thread when to stop. */
    private static final class Printer {

        private final PrintStream out;
        private final PrintStream err;
        private final boolean json;
        private final long limit;
        private final UnaryOperator<String> scrub;
        private long printed;
        private boolean done;

        Printer(PrintStream out, PrintStream err, boolean json, long limit, UnaryOperator<String> scrub) {
            this.out = out;
            this.err = err;
            this.json = json;
            this.limit = limit;
            this.scrub = scrub;
        }

        /** Called on the stream's thread. */
        synchronized void print(Event event) {
            if (done) {
                return;
            }
            if (json) {
                out.println(scrub.apply(Json.write(event.toJson())));
            } else {
                out.println(scrub.apply(event.seq() + " " + TIME.format(Instant.ofEpochMilli(event.ts())) + " "
                        + event.topic() + " " + Json.write(event.payload())));
            }
            out.flush();
            if ("gap".equals(event.topic())) {
                err.println("skillroy events: events " + event.payload().get("from") + " to "
                        + event.payload().get("to") + " were lost (they fell out of the agent's ring)");
            }
            printed++;
            if (printed >= limit || out.checkError()) {
                done = true;
                notifyAll();
            }
        }

        /**
         * Waits until the count is reached, standard output fails, the timeout passes or the stream stops.
         *
         * @return true when the stream stopped by itself (the agent was lost)
         */
        synchronized boolean await(EventStream stream, Long timeoutMs) {
            long deadline = timeoutMs == null ? Long.MAX_VALUE
                    : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            try {
                while (!done) {
                    if (!stream.isOpen()) {
                        return true;
                    }
                    long remainingMs = timeoutMs == null ? WAKE_MS
                            : TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMs <= 0) {
                        break;
                    }
                    wait(Math.min(remainingMs, WAKE_MS));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done = true;
            return false;
        }
    }
}
