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

import java.time.Duration;

/**
 * Timeouts, back-off and size limits of {@link AgentClient} and its event streams. Package-private so that tests can
 * shorten them; production code always uses {@link #DEFAULTS}.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param connectTimeout TCP connect timeout of the HTTP client
 * @param pingTimeout overall deadline of one {@code /ping} request (headers and body)
 * @param getTimeout overall deadline of {@code /schema} and {@code /capabilities}
 * @param callSlack added to {@code CallOptions.timeoutMs} to get the HTTP deadline of a call, so that the agent's own
 *     TIMEOUT error normally arrives before the HTTP request gives up
 * @param callCeiling HTTP deadline of a call without {@code timeoutMs}; above the agent's 120 s cap, independent of
 *     the primitive's descriptor
 * @param sseHeaderTimeout how long an event stream waits for the response headers of {@code /events}
 * @param sseIdleTimeout an event stream with no line (event or heartbeat) for this long reconnects; the agent sends
 *     a heartbeat every 15 s
 * @param sseInitialBackoff first reconnect delay; doubled after each failed attempt
 * @param sseMaxBackoff reconnect delay cap
 * @param maxReplyBytes the largest reply body accepted from {@code /rpc}, {@code /schema} and {@code /capabilities}
 *     (finding S23); a larger one is an {@link AgentLostException}
 * @param maxPingBytes the largest {@code /ping} reply body accepted
 * @param sseMaxLineBytes the longest line accepted on {@code /events} (finding S25); a longer one ends the stream
 * @param sseMaxEventChars the most {@code data} characters one event may collect; more ends the stream
 */
record ClientSettings(Duration connectTimeout, Duration pingTimeout, Duration getTimeout, Duration callSlack,
                      Duration callCeiling, Duration sseHeaderTimeout, Duration sseIdleTimeout,
                      Duration sseInitialBackoff, Duration sseMaxBackoff, long maxReplyBytes, long maxPingBytes,
                      int sseMaxLineBytes, int sseMaxEventChars) {

    /** 64 MiB: room for the largest legitimate replies (a large screen capture, a big component snapshot). */
    static final long DEFAULT_MAX_REPLY_BYTES = 64L << 20;

    /** 64 KiB: a {@code /ping} reply is a few hundred bytes. */
    static final long DEFAULT_MAX_PING_BYTES = 64L << 10;

    /** 4 MiB per line and per event: far above anything the agent emits (its event values are capped). */
    static final int DEFAULT_SSE_MAX_LINE_BYTES = 4 << 20;

    /** See {@link #DEFAULT_SSE_MAX_LINE_BYTES}. */
    static final int DEFAULT_SSE_MAX_EVENT_CHARS = 4 << 20;

    /** The values documented on {@link AgentClient}. */
    static final ClientSettings DEFAULTS = new ClientSettings(Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(130), Duration.ofSeconds(10),
            Duration.ofSeconds(45), Duration.ofMillis(100), Duration.ofSeconds(2));

    /** Validates the limits. */
    ClientSettings {
        if (maxReplyBytes < 1 || maxPingBytes < 1 || sseMaxLineBytes < 1 || sseMaxEventChars < 1) {
            throw new IllegalArgumentException("size limits must be positive");
        }
    }

    /** The timeouts given and the default size limits. */
    ClientSettings(Duration connectTimeout, Duration pingTimeout, Duration getTimeout, Duration callSlack,
                   Duration callCeiling, Duration sseHeaderTimeout, Duration sseIdleTimeout,
                   Duration sseInitialBackoff, Duration sseMaxBackoff) {
        this(connectTimeout, pingTimeout, getTimeout, callSlack, callCeiling, sseHeaderTimeout, sseIdleTimeout,
                sseInitialBackoff, sseMaxBackoff, DEFAULT_MAX_REPLY_BYTES, DEFAULT_MAX_PING_BYTES,
                DEFAULT_SSE_MAX_LINE_BYTES, DEFAULT_SSE_MAX_EVENT_CHARS);
    }

    /** A copy with another ping timeout (used by discovery to stay inside its own deadline); limits are kept. */
    ClientSettings withPingTimeout(Duration timeout) {
        return new ClientSettings(connectTimeout, timeout, getTimeout, callSlack, callCeiling, sseHeaderTimeout,
                sseIdleTimeout, sseInitialBackoff, sseMaxBackoff, maxReplyBytes, maxPingBytes, sseMaxLineBytes,
                sseMaxEventChars);
    }

    /** A copy with other size limits (tests). */
    ClientSettings withLimits(long replyBytes, long pingBytes, int lineBytes, int eventChars) {
        return new ClientSettings(connectTimeout, pingTimeout, getTimeout, callSlack, callCeiling, sseHeaderTimeout,
                sseIdleTimeout, sseInitialBackoff, sseMaxBackoff, replyBytes, pingBytes, lineBytes, eventChars);
    }
}
