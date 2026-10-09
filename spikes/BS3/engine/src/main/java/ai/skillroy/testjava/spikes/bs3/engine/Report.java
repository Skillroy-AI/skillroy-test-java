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

package ai.skillroy.testjava.spikes.bs3.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The probe's output: {@code probe.log} (human-readable, in order) and {@code result.properties} (one key per fact,
 * in the order recorded), both in the run's output directory. Every line also goes to {@code System.out} with a
 * {@code [bs3]} prefix, so the JVM log has it too.
 */
final class Report {

    private final File dir;
    private final Map<String, String> values = new LinkedHashMap<String, String>();
    private final PrintWriter log;

    Report(File dir) throws IOException {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        log = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "probe.log")),
                StandardCharsets.UTF_8), true);
    }

    File dir() {
        return dir;
    }

    synchronized void section(String title) {
        line("");
        line("== " + title);
    }

    synchronized void line(String text) {
        log.println(text);
        System.out.println("[bs3] " + text);
    }

    synchronized void put(String key, Object value) {
        String v = String.valueOf(value).replace('\n', ' ');
        values.put(key, v);
        line(key + " = " + v);
    }

    synchronized String get(String key) {
        return values.get(key);
    }

    synchronized void error(String key, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        put(key, "ERROR " + t);
        log.println(sw);
    }

    static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1e6);
    }

    static String us(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e3);
    }

    synchronized void flush() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "result.properties")),
                StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
        } catch (IOException e) {
            System.err.println("[bs3] cannot write result.properties: " + e);
        }
        log.flush();
    }

    void dump(String name, byte[] bytes) {
        File f = new File(dir, "classes/" + name + ".class");
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        } catch (IOException e) {
            line("cannot dump " + name + ": " + e);
        }
    }
}
