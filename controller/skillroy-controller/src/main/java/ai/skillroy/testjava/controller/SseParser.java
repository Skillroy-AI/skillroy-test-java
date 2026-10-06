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

package ai.skillroy.testjava.controller;

/**
 * Line-level parser for {@code text/event-stream} (the WHATWG server-sent events format), separated from the
 * transport so that it can be tested on its own. Fields {@code data} (several lines are joined with {@code "\n"}),
 * {@code event} and {@code id} are collected until an empty line; comment lines (starting with {@code ':'}, such as
 * the agent's {@code ": hb"} heartbeat) and other fields ({@code retry}) are ignored.
 *
 * <p>The {@code data} of one event is limited (finding S25): a peer that sends {@code data:} lines without ever
 * ending the event would otherwise grow it without bound. Past the limit the collected data is dropped and
 * {@link #overflowed()} turns true for good; the caller ends the stream.
 *
 * <p>Not thread-safe: one instance per connection, fed by one thread.
 */
final class SseParser {

    /**
     * One dispatched event block.
     *
     * @param id the {@code id} field of this block, or null
     * @param event the {@code event} field of this block, or null
     * @param data the joined {@code data} lines, never null
     */
    record Frame(String id, String event, String data) {
    }

    private final int maxEventChars;
    private final StringBuilder data = new StringBuilder();
    private boolean hasData;
    private boolean overflowed;
    private String event;
    private String id;

    /** A parser with the default limit of {@link ClientSettings#DEFAULT_SSE_MAX_EVENT_CHARS} data characters. */
    SseParser() {
        this(ClientSettings.DEFAULT_SSE_MAX_EVENT_CHARS);
    }

    /** A parser that accepts at most {@code maxEventChars} characters of joined data per event. */
    SseParser(int maxEventChars) {
        if (maxEventChars < 1) {
            throw new IllegalArgumentException("maxEventChars must be positive");
        }
        this.maxEventChars = maxEventChars;
    }

    /** True once an event exceeded the data limit; every later line is ignored. */
    boolean overflowed() {
        return overflowed;
    }

    /**
     * Feeds one line without its terminator.
     *
     * @return the completed frame when {@code line} is the empty line ending a block that had data, else null
     */
    Frame line(String line) {
        if (overflowed) {
            return null;
        }
        if (line.isEmpty()) {
            Frame frame = hasData ? new Frame(id, event, data.toString()) : null;
            data.setLength(0);
            hasData = false;
            event = null;
            id = null;
            return frame;
        }
        if (line.charAt(0) == ':') {
            return null;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "data" -> {
                long grown = (long) data.length() + (hasData ? 1 : 0) + value.length();
                if (grown > maxEventChars) {
                    overflowed = true;
                    data.setLength(0);
                    data.trimToSize();
                    hasData = false;
                    return null;
                }
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
            case "event" -> event = value;
            case "id" -> {
                if (value.indexOf('\0') < 0) {
                    id = value;
                }
            }
            default -> {
                // "retry" and unknown fields carry nothing the controller uses.
            }
        }
        return null;
    }
}
