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

import ai.skillroy.testjava.agent.api.AgentLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The agent log: a bounded in-memory ring of formatted lines served by {@code agent.log}. The agent must not write
 * to {@code System.out} or reconfigure the host's logging, so this is the only place agent diagnostics go.
 *
 * <p>Bounded in entries and in characters (security finding S2): every part of a line is cut before it is appended
 * (the message to {@link #MAX_MESSAGE} characters, each exception message to {@link #MAX_EXCEPTION_MESSAGE}, the
 * thread name to {@link #MAX_THREAD_NAME}) and the finished line to {@link #MAX_LINE} characters, ending in
 * {@code "...[+N chars]"} when something was cut. Control characters in what callers pass are replaced by a space,
 * so a line can only break at the {@code "\n    at "} and {@code "\n  caused by "} separators added here. The
 * ring therefore holds at most {@code CAPACITY * MAX_LINE} characters, about 8 MB as UTF-16 on Java 8, whatever
 * callers log; no caller can retain a large string through it. A line written on a thread that runs a call which named
 * secret parameters has their values replaced in its message and exception texts (contract amendment A34,
 * {@link SecretParams}).
 *
 * <p>Thread-safe; every method returns quickly and never throws.
 */
final class RingLog implements AgentLog {

    static final int CAPACITY = 2000;
    /** Longest line kept, suffix included. */
    static final int MAX_LINE = 2000;
    /** Longest message kept. */
    static final int MAX_MESSAGE = 1000;
    /** Longest exception or cause message kept. */
    static final int MAX_EXCEPTION_MESSAGE = 300;
    /** Longest thread name kept. */
    static final int MAX_THREAD_NAME = 100;
    private static final int STACK_FRAMES = 8;
    private static final int MAX_FRAME = 200;
    private static final int MAX_CLASS_NAME = 200;

    private final String[] lines = new String[CAPACITY];
    private final SimpleDateFormat format;
    private long count;

    RingLog() {
        format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Override
    public void info(String msg) {
        add("INFO", msg, null);
    }

    @Override
    public void warn(String msg) {
        add("WARN", msg, null);
    }

    @Override
    public void warn(String msg, Throwable t) {
        add("WARN", msg, t);
    }

    @Override
    public void error(String msg, Throwable t) {
        add("ERROR", msg, t);
    }

    @Override
    public synchronized List<String> tail(int n) {
        int size = (int) Math.min(count, CAPACITY);
        int take = Math.max(0, Math.min(n, size));
        List<String> out = new ArrayList<String>(take);
        for (long i = count - take; i < count; i++) {
            out.add(lines[(int) (i % CAPACITY)]);
        }
        return out;
    }

    private void add(String level, String msg, Throwable t) {
        try {
            // Amendment A34: a line written while a call with secret parameters runs on this thread never holds
            // their values. Applied to each part before it is cut, so that a cut cannot leave half a secret.
            SecretParams secrets = CallScope.currentSecrets();
            StringBuilder sb = new StringBuilder(128);
            synchronized (this) {
                sb.append(format.format(new Date()));
            }
            sb.append(' ').append(level).append(" [");
            appendClean(sb, Thread.currentThread().getName(), MAX_THREAD_NAME);
            sb.append("] ");
            appendClean(sb, secrets.redact(String.valueOf(msg)), MAX_MESSAGE);
            if (t != null) {
                sb.append(": ");
                appendClean(sb, t.getClass().getName(), MAX_CLASS_NAME);
                if (t.getMessage() != null) {
                    sb.append(": ");
                    appendClean(sb, secrets.redact(t.getMessage()), MAX_EXCEPTION_MESSAGE);
                }
                StackTraceElement[] stack = t.getStackTrace();
                for (int i = 0; i < stack.length && i < STACK_FRAMES && sb.length() <= MAX_LINE; i++) {
                    sb.append("\n    at ");
                    appendClean(sb, String.valueOf(stack[i]), MAX_FRAME);
                }
                Throwable cause = t.getCause();
                if (cause != null && cause != t && sb.length() <= MAX_LINE) {
                    sb.append("\n  caused by ");
                    appendClean(sb, cause.getClass().getName(), MAX_CLASS_NAME);
                    if (cause.getMessage() != null) {
                        sb.append(": ");
                        appendClean(sb, secrets.redact(cause.getMessage()), MAX_EXCEPTION_MESSAGE);
                    }
                }
            }
            String line = bound(sb);
            synchronized (this) {
                lines[(int) (count % CAPACITY)] = line;
                count++;
            }
        } catch (Throwable ignored) {
            // Logging must never fail the caller.
        }
    }

    /**
     * Appends at most {@code max} characters of {@code text} with control characters replaced by a space, and a
     * {@code "...[+N chars]"} marker when it was cut. Reads only the characters it keeps.
     */
    static void appendClean(StringBuilder sb, String text, int max) {
        int length = text.length();
        int keep = Math.min(length, max);
        if (keep < length && keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1))) {
            keep--;
        }
        for (int i = 0; i < keep; i++) {
            char c = text.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (keep < length) {
            sb.append("...[+").append(length - keep).append(" chars]");
        }
    }

    /** The line, cut to {@link #MAX_LINE} characters including the marker. */
    static String bound(StringBuilder sb) {
        if (sb.length() <= MAX_LINE) {
            return sb.toString();
        }
        // The marker is at most 22 characters ("...[+" + 10 digits + " chars]").
        int keep = MAX_LINE - 24;
        if (Character.isHighSurrogate(sb.charAt(keep - 1))) {
            keep--;
        }
        return sb.substring(0, keep) + "...[+" + (sb.length() - keep) + " chars]";
    }
}
