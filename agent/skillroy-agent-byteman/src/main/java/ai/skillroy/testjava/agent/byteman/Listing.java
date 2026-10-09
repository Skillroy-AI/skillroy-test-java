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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the text of {@code Retransformer.listScripts} (spike BS14 §4): per rule, its {@code # File …} header, its text,
 * and one {@code Transformed in:} block per class it was injected into or tried, with {@code loader:},
 * {@code trigger method: <class>.<method>(…) <type>} or {@code trigger class: <class>}, and a status line:
 * {@code compiled successfully}, {@code failed to compile} (a type error at the first trigger, the error after it) or
 * {@code threw <exception>} (a failure at transform time: the rule was not injected); no status line means injected
 * and not yet triggered. This is the only place where a transform-time failure shows: Byteman prints nothing for it.
 */
final class Listing {

    /** A class status as {@code rule.submit} and {@code rule.list} report it. */
    static final class ClassStatus {
        final String className;
        final String status;
        final boolean failed;

        ClassStatus(String className, String status, boolean failed) {
            this.className = className;
            this.status = status;
            this.failed = failed;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("class", className);
            m.put("status", status);
            return m;
        }
    }

    static final int MAX_DETAIL = 500;

    private Listing() {
    }

    /** Rule name (as Byteman knows it, with the {@code skillroy:} prefix) to its class statuses, in listing order. */
    static Map<String, List<ClassStatus>> parse(String text) {
        Map<String, List<ClassStatus>> rules = new LinkedHashMap<String, List<ClassStatus>>();
        if (text == null) {
            return rules;
        }
        String[] lines = text.replace("\r\n", "\n").split("\n");
        String rule = null;
        boolean afterEnd = false;
        String className = null;
        String status = null;
        boolean failed = false;
        StringBuilder detail = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("# File ")) {
                flush(rules, rule, className, status, failed, detail);
                rule = null;
                afterEnd = false;
                className = null;
                status = null;
                failed = false;
                detail = null;
                continue;
            }
            if (rule == null && line.startsWith("RULE ")) {
                rule = line.substring(5).trim();
                if (!rules.containsKey(rule)) {
                    rules.put(rule, new ArrayList<ClassStatus>());
                }
                continue;
            }
            if (rule == null) {
                continue;
            }
            if (!afterEnd) {
                if (line.equals("ENDRULE")) {
                    afterEnd = true;
                }
                continue;
            }
            if (line.equals("Transformed in:")) {
                flush(rules, rule, className, status, failed, detail);
                className = null;
                status = null;
                failed = false;
                detail = null;
                continue;
            }
            if (line.startsWith("trigger method: ")) {
                String spec = line.substring(16);
                int paren = spec.indexOf('(');
                String qualified = paren > 0 ? spec.substring(0, paren) : spec;
                int dot = qualified.lastIndexOf('.');
                className = dot > 0 ? qualified.substring(0, dot) : qualified;
                continue;
            }
            if (line.startsWith("trigger class: ")) {
                className = line.substring(15).trim();
                continue;
            }
            if (line.startsWith("loader: ")) {
                continue;
            }
            if (className != null && status == null) {
                if (line.equals("compiled successfully")) {
                    status = "compiled";
                } else if (line.equals("failed to compile")) {
                    status = "failed to compile";
                    failed = true;
                    detail = new StringBuilder();
                } else if (line.startsWith("threw ")) {
                    status = "threw: " + line.substring(6);
                    failed = true;
                }
                continue;
            }
            if (detail != null && detail.length() == 0 && !line.isEmpty() && !line.startsWith("at ")) {
                detail.append(line);
            }
        }
        flush(rules, rule, className, status, failed, detail);
        return rules;
    }

    private static void flush(Map<String, List<ClassStatus>> rules, String rule, String className, String status,
                              boolean failed, StringBuilder detail) {
        if (rule == null || className == null) {
            return;
        }
        String s = status == null ? "injected" : status;
        if (detail != null && detail.length() > 0) {
            s = s + ": " + detail;
        }
        if (s.length() > MAX_DETAIL) {
            s = s.substring(0, MAX_DETAIL);
        }
        rules.get(rule).add(new ClassStatus(className, s, failed));
    }

    /** The statuses of one rule, or an empty list. */
    static List<ClassStatus> of(Map<String, List<ClassStatus>> parsed, String storedName) {
        List<ClassStatus> list = parsed.get(storedName);
        return list == null ? Collections.<ClassStatus>emptyList() : list;
    }
}
