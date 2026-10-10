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

package ai.skillroy.testjava.agent.web.reflect;

import ai.skillroy.testjava.protocol.web.WebLib;

import java.util.concurrent.TimeUnit;

/**
 * The receive budget of one browser's event channel (security fix P2-L4-S1, phase4-contracts.md 4.3): what a page
 * can make a built-in adapter receive without a call of the agent's, its console output above all, is bounded on the
 * technology's own thread, before anything is queued.
 *
 * <ul>
 *   <li><b>The hand-off.</b> An event is handed to the adapter's serial executor only while the characters of the
 *     events queued there and not yet run stay within {@link #QUEUE_CHARS}; every event counts at least
 *     {@link #MIN_EVENT_COST}, so that the queue is bounded in length as well. An event past the bound is dropped
 *     unparsed and counted ({@link #admit}, {@link #release}).</li>
 *   <li><b>The console budget.</b> The characters of the browser's console events (console calls and uncaught
 *     exceptions, an oversized one included) are spent from a bucket of {@link #CONSOLE_BURST_CHARS} that refills at
 *     {@link #CONSOLE_CHARS_PER_SECOND}; the event that empties it stops console capture ({@link #spend} answers
 *     {@link Verdict#STOP} once): the adapter then stops the technology from sending console events, reports
 *     {@link #STOPPED_TEXT} once as a {@code web.console} warning, and drops the console events still on their way
 *     ({@link Verdict#DROP}). Capture resumes, with a full bucket, at the browser's first main-frame navigation that
 *     comes at least {@link #RESUME_AFTER_MS} ms after it stopped ({@link #resumeInMs}, {@link #resume}): a page that
 *     floods its console costs one bucket per document, not one per second for the life of the run.</li>
 * </ul>
 *
 * <p>Thread-safe; every method is short and never blocks, so the technology's thread may call it.
 */
public final class EventBudget {

    /** At most this many characters of a browser's events wait for its serial executor (two channel bounds). */
    public static final long QUEUE_CHARS = 2L * WebLib.MAX_CHANNEL_CHARS;

    /** Every event counts at least this many characters against {@link #QUEUE_CHARS}. */
    public static final int MIN_EVENT_COST = 256;

    /** The console bucket: a burst of at most this many characters (two channel bounds) ... */
    public static final long CONSOLE_BURST_CHARS = 2L * WebLib.MAX_CHANNEL_CHARS;

    /** ... refilled at this many characters a second (1 Mi). */
    public static final long CONSOLE_CHARS_PER_SECOND = WebLib.MAX_JSON_CHARS;

    /** Stopped console capture resumes at a main-frame navigation no sooner than this after it stopped. */
    public static final long RESUME_AFTER_MS = 10000;

    /** The {@code web.console} warning an adapter publishes once when it stops a browser's console capture. */
    public static final String STOPPED_TEXT = "skillroy: console capture of this browser stopped: its page's console"
            + " output exceeded " + CONSOLE_BURST_CHARS + " characters at once or " + CONSOLE_CHARS_PER_SECOND
            + " a second; it resumes at the first main-frame navigation " + (RESUME_AFTER_MS / 1000)
            + " s or more after it stopped";

    /** What {@link #spend} decides for one console event. */
    public enum Verdict {
        /** Within the budget: the event goes on to the hand-off. */
        PASS,
        /** This event emptied the bucket: drop it and stop console capture (answered once until resumed). */
        STOP,
        /** Capture is stopped: drop the event. */
        DROP
    }

    private long queued;
    private long dropped;
    private double tokens = CONSOLE_BURST_CHARS;
    private long refilledAt;
    private boolean started;
    private boolean stopped;
    private long stoppedAt;

    /** The cost of an event of {@code chars} characters against {@link #QUEUE_CHARS}. */
    public static long cost(long chars) {
        return Math.max(MIN_EVENT_COST, chars);
    }

    /**
     * Admits an event of the given cost to the queue: false, and the event counted as dropped, when the queued
     * characters would pass {@link #QUEUE_CHARS}. An admitted event's cost is {@link #release}d once it ran (or was
     * refused by the executor).
     */
    public synchronized boolean admit(long cost) {
        if (queued + cost > QUEUE_CHARS) {
            dropped++;
            return false;
        }
        queued += cost;
        return true;
    }

    /** Releases an admitted event's cost. */
    public synchronized void release(long cost) {
        queued = Math.max(0, queued - cost);
    }

    /** The characters queued now (for tests). */
    public synchronized long queued() {
        return queued;
    }

    /** How many events the hand-off dropped (for tests). */
    public synchronized long dropped() {
        return dropped;
    }

    /** Spends a console event's characters at {@code nowNanos} ({@code System.nanoTime()}). */
    public synchronized Verdict spend(long chars, long nowNanos) {
        if (stopped) {
            return Verdict.DROP;
        }
        if (started) {
            double seconds = Math.max(0, nowNanos - refilledAt) / 1e9;
            tokens = Math.min(CONSOLE_BURST_CHARS, tokens + seconds * CONSOLE_CHARS_PER_SECOND);
        }
        started = true;
        refilledAt = nowNanos;
        tokens -= chars;
        if (tokens < 0) {
            stopped = true;
            stoppedAt = nowNanos;
            return Verdict.STOP;
        }
        return Verdict.PASS;
    }

    /** True while console capture is stopped. */
    public synchronized boolean stopped() {
        return stopped;
    }

    /** How long until stopped capture may resume (0: now); -1 when it is not stopped. */
    public synchronized long resumeInMs(long nowNanos) {
        if (!stopped) {
            return -1;
        }
        long left = RESUME_AFTER_MS - TimeUnit.NANOSECONDS.toMillis(nowNanos - stoppedAt);
        return Math.max(0, left);
    }

    /** Resumes console capture with a full bucket. */
    public synchronized void resume(long nowNanos) {
        stopped = false;
        tokens = CONSOLE_BURST_CHARS;
        refilledAt = nowNanos;
        started = true;
    }
}
