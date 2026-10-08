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

import java.io.PrintStream;

/**
 * The server's own log (phase5-contracts.md 9.2): one line per message on stderr (stdout carries the protocol),
 * {@code skillroy mcp: } first, masked, control characters neutralised, at most 2000 characters.
 *
 * <p>Thread-safe: lines are written whole.
 */
final class ServerLog {

    private static final int MAX_LINE = 2000;

    private final PrintStream err;
    private volatile Masking masking;

    ServerLog(PrintStream err) {
        this.err = err;
    }

    /** From now on, every line passes this masking. */
    void mask(Masking newMasking) {
        this.masking = newMasking;
    }

    /** Writes one line; never throws. */
    void info(String message) {
        try {
            Masking m = masking;
            String text = m == null ? String.valueOf(message) : m.text(String.valueOf(message));
            String line = "skillroy mcp: " + oneLine(text);
            synchronized (err) {
                err.println(line);
                err.flush();
            }
        } catch (RuntimeException e) {
            // A log line never fails the server.
        }
    }

    /** Control characters as spaces or {@code ?}, at most {@value #MAX_LINE} characters. */
    static String oneLine(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_LINE + 16));
        for (int i = 0; i < text.length() && out.length() < MAX_LINE; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (Character.isISOControl(c) || c == ' ' || c == ' ') {
                out.append('?');
            } else {
                out.append(c);
            }
        }
        if (text.length() > MAX_LINE) {
            out.append("[...]");
        }
        return out.toString();
    }
}
