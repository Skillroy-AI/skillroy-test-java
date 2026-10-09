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

package ai.skillroy.testjava.spikes.bs14.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** result.properties (one key per fact; check.* keys are PASS or FAIL) and probe.log. */
final class Report {

    private final File dir;
    private final Map<String, String> facts = new TreeMap<String, String>();
    private final StringBuilder log = new StringBuilder();

    Report(File dir) {
        this.dir = dir;
    }

    synchronized void put(String key, Object value) {
        String v = String.valueOf(value).replace("\r", "").replace("\n", "\\n");
        facts.put(key, v);
        log("  " + key + " = " + v);
    }

    synchronized void check(String key, boolean ok) {
        put("check." + key, ok ? "PASS" : "FAIL");
    }

    synchronized void log(String line) {
        log.append(System.currentTimeMillis() % 100000).append(' ').append(line).append('\n');
    }

    synchronized void fail(String key, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        put(key, t.toString());
        log(sw.toString());
    }

    synchronized boolean allPass() {
        for (Map.Entry<String, String> e : facts.entrySet()) {
            if (e.getKey().startsWith("check.") && !"PASS".equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    synchronized String failures() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : facts.entrySet()) {
            if (e.getKey().startsWith("check.") && !"PASS".equals(e.getValue())) {
                sb.append(e.getKey()).append(' ');
            }
        }
        return sb.toString().trim();
    }

    synchronized void write() throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "result.properties")),
                StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : facts.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "probe.log")),
                StandardCharsets.UTF_8)) {
            w.write(log.toString());
        }
    }
}
