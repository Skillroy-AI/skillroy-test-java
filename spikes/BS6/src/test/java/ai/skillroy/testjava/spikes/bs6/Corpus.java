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

package ai.skillroy.testjava.spikes.bs6;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.skillroy.testjava.spikes.bs6.GuardViolation.Reason;

/**
 * The corpora: {@code corpus/<dialect>-accept.sql}, {@code corpus/<dialect>-reject.sql} and
 * {@code corpus/parser-limits.sql}. Everything before the first entry is the file's header. An entry starts with a
 * line {@code @@ <label>} (accept), {@code @@ <REASON> <label>} (reject) or {@code @@ <DIALECT> <ACCEPT|REASON> <label>}
 * (limits); the statement follows on one or more lines, comments included; then, optionally, {@code ?? v1 | v2} (the
 * parameter values for the HSQLDB run) and {@code => <text>} (the expected rewrite). {@code {{repeat N TEXT}}} expands
 * to TEXT written N times, for the size cases.
 */
record Corpus(String file, List<Entry> entries) {

    enum Kind { ACCEPT, REJECT, LIMIT }

    record Entry(String file, int line, Kind kind, SqlDialect dialect, String label, String sql, Reason reason,
                 boolean limitAccepted, List<String> params, String expected) {
        @Override
        public String toString() {
            String head = file.replace(".sql", "") + ":" + line + " " + label;
            return kind == Kind.REJECT ? head + " [" + reason + "]" : head;
        }
    }

    private static final Pattern REPEAT = Pattern.compile("\\{\\{repeat (\\d+) (.*?)}}");

    static Corpus accept(SqlDialect dialect) {
        return load(dialect.name().toLowerCase(Locale.ROOT) + "-accept.sql", Kind.ACCEPT, dialect);
    }

    static Corpus reject(SqlDialect dialect) {
        return load(dialect.name().toLowerCase(Locale.ROOT) + "-reject.sql", Kind.REJECT, dialect);
    }

    static Corpus limits() {
        return load("parser-limits.sql", Kind.LIMIT, null);
    }

    private static Corpus load(String file, Kind kind, SqlDialect dialect) {
        String text;
        try (InputStream in = Corpus.class.getResourceAsStream("/corpus/" + file)) {
            if (in == null) {
                throw new IllegalStateException("no corpus file " + file);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Entry> entries = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length && !lines[i].startsWith("@@ ")) {
            i++;
        }
        while (i < lines.length) {
            int start = i + 1;
            String head = lines[i].substring(3).trim();
            i++;
            List<String> body = new ArrayList<>();
            List<String> params = List.of();
            String expected = null;
            while (i < lines.length && !lines[i].startsWith("@@ ")) {
                String l = lines[i];
                if (l.startsWith("?? ")) {
                    params = LendingFixture.splitParams(l.substring(3));
                } else if (l.startsWith("=> ")) {
                    expected = l.substring(3);
                } else {
                    body.add(l);
                }
                i++;
            }
            while (!body.isEmpty() && body.getLast().isBlank()) {
                body.removeLast();
            }
            String sql = expand(String.join("\n", body).strip());
            entries.add(switch (kind) {
                case ACCEPT -> new Entry(file, start, kind, dialect, head, sql, null, true, params, expected);
                case REJECT -> {
                    int space = head.indexOf(' ');
                    yield new Entry(file, start, kind, dialect, head.substring(space + 1),
                            sql, Reason.valueOf(head.substring(0, space)), false, params, expected);
                }
                case LIMIT -> {
                    String[] parts = head.split(" ", 3);
                    boolean accepted = parts[1].equals("ACCEPT");
                    yield new Entry(file, start, kind, SqlDialect.valueOf(parts[0]), parts[2], sql,
                            accepted ? null : Reason.valueOf(parts[1]), accepted, params, expected);
                }
            });
        }
        return new Corpus(file, entries);
    }

    private static String expand(String sql) {
        Matcher m = REPEAT.matcher(sql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(2).repeat(Integer.parseInt(m.group(1)))));
        }
        m.appendTail(out);
        return out.toString();
    }
}
