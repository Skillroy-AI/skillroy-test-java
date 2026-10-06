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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small formatting helpers for human-readable output: aligned tables, durations, JSON-RPC errors, and the
 * neutralisation of control characters that every printed character passes through ({@link #printable}).
 *
 * <p>Stateless; safe from any thread.
 */
final class Text {

    private Text() {
    }

    /**
     * Rows aligned under a header, columns separated by two spaces; the last column is not padded. Every row must
     * have the header's number of cells; a null cell prints as empty.
     */
    static String table(List<String> header, List<List<String>> rows) {
        int columns = header.size();
        int[] widths = new int[columns];
        List<List<String>> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        for (List<String> row : all) {
            for (int c = 0; c < columns; c++) {
                widths[c] = Math.max(widths[c], cell(row, c).length());
            }
        }
        StringBuilder text = new StringBuilder();
        for (List<String> row : all) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < columns; c++) {
                String value = cell(row, c);
                line.append(value);
                if (c < columns - 1) {
                    line.append(" ".repeat(widths[c] - value.length() + 2));
                }
            }
            text.append(line.toString().stripTrailing()).append('\n');
        }
        return text.toString();
    }

    /** {@code 1.234 s}. */
    static String seconds(Duration duration) {
        return String.format(Locale.ROOT, "%.3f s", duration.toNanos() / 1e9);
    }

    /**
     * A JSON-RPC error for stderr: {@code <method> failed: CONFIRM_REQUIRED (-32030): <message>}, then the error's
     * data as indented JSON when it has any.
     */
    static String rpcError(AgentRpcException e) {
        RpcError error = e.error();
        StringBuilder text = new StringBuilder(e.method()).append(" failed: ").append(ErrorCodes.name(error.code()))
                .append(" (").append(error.code()).append("): ").append(error.message());
        if (error.data() != null && !error.data().isEmpty()) {
            text.append("\ndata: ").append(Json.writePretty(error.data()).stripTrailing());
        }
        return text.toString();
    }

    /** {@code {"error": {code, name, message, data}}} for {@code --json} output of a failed call. */
    static Map<String, Object> rpcErrorJson(AgentRpcException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("method", e.method());
        error.put("code", (long) e.error().code());
        error.put("name", ErrorCodes.name(e.error().code()));
        error.put("message", e.error().message());
        error.put("data", e.error().data());
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("error", error);
        return wrapper;
    }

    /**
     * {@code text} with every character a terminal or log viewer could act on made visible (security finding S26):
     * it keeps {@code \n}, {@code \t} and a {@code \r} directly before {@code \n}, and replaces every other C0
     * control (ESC, BEL, backspace, a bare carriage return), DEL, the C1 controls U+0080 to U+009F, U+2028, U+2029 and
     * the bidi overrides and isolates (U+202A to U+202E, U+2066 to U+2069) with a backslash, {@code u} and the four
     * lower-case hex digits of the character. JSON-safe: inside a JSON string that is an equivalent escape.
     */
    static String printable(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = null;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            boolean unsafe = (c < 0x20 && c != '\n' && c != '\t' && !(c == '\r' && i + 1 < n
                    && text.charAt(i + 1) == '\n')) || (c >= 0x7F && c <= 0x9F) || c == '\u2028' || c == '\u2029'
                    || (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069');
            if (unsafe && out == null) {
                out = new StringBuilder(n + 16).append(text, 0, i);
            }
            if (out != null) {
                if (unsafe) {
                    out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            }
        }
        return out == null ? text : out.toString();
    }

    /** {@code text} on one line with its control characters made visible, cut to {@code max} characters. */
    static String oneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = printable(text.replace('\r', ' ').replace('\n', ' '));
        return flat.length() <= max ? flat : flat.substring(0, Math.max(0, max - 3)) + "...";
    }

    /** {@code text} with every line indented by {@code indent}; a trailing newline is kept. */
    static String indent(String text, String indent) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            if (!line.isEmpty()) {
                out.append(indent).append(line);
            }
        }
        return out.toString();
    }

    private static String cell(List<String> row, int column) {
        String value = column < row.size() ? row.get(column) : null;
        return value == null ? "" : value;
    }
}
