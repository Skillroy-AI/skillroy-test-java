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

package ai.skillroy.testjava.agent.byteman;

import ai.skillroy.testjava.agent.api.AgentLog;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The stream the module puts into Byteman's trace map ({@code org.jboss.byteman.rule.helper.Helper.traceMap}, keys
 * {@code out}, {@code err}, {@code vrb}, {@code dbg}, {@code nzy}) so that what Byteman would print on the host's
 * console (a rule's type error at its first trigger, a failing action's stack trace, verbose lines when the host sets
 * {@code org.jboss.byteman.verbose}) goes to the agent's log at WARN instead (spike BS14 RESULT condition 3). Lines are
 * cut at {@value #MAX_LINE} characters and at most {@value #MAX_LINES} are written; later ones are counted. Never
 * throws.
 */
final class LogStream extends OutputStream {

    static final int MAX_LINE = 1000;
    static final int MAX_LINES = 2000;

    private final AgentLog log;
    private final byte[] line = new byte[MAX_LINE * 4];
    private int length;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    LogStream(AgentLog log) {
        this.log = log;
    }

    @Override
    public synchronized void write(int b) {
        if (b == '\n') {
            flushLine();
            return;
        }
        if (b == '\r') {
            return;
        }
        if (length < line.length) {
            line[length++] = (byte) b;
        }
    }

    @Override
    public synchronized void flush() {
        if (length > 0) {
            flushLine();
        }
    }

    private void flushLine() {
        String text = new String(line, 0, length, StandardCharsets.UTF_8);
        length = 0;
        if (text.trim().isEmpty()) {
            return;
        }
        if (written.incrementAndGet() > MAX_LINES) {
            dropped.incrementAndGet();
            return;
        }
        try {
            log.warn("byteman: " + (text.length() > MAX_LINE ? text.substring(0, MAX_LINE) + "…" : text));
        } catch (Throwable ignored) {
            // the agent's log is gone
        }
    }

    long dropped() {
        return dropped.get();
    }
}
