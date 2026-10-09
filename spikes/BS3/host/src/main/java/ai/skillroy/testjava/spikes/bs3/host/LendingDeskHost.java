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

package ai.skillroy.testjava.spikes.bs3.host;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.skillroy.testjava.samples.lending.AccessControl;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Seed;

/**
 * The host of a BS3 run: the sample lending desk's access rules on an in-memory database (seed A), the toy rule in the
 * host's own loader, and nothing else. It announces readiness (system property {@code bs3.host.ready} and the file
 * {@code host.ready} with its pid), waits for the probe to finish (bounded), and exits 0 when the probe passed.
 *
 * <pre>
 * java [-javaagent:bs3-agent.jar=out=DIR,...] -cp bs3-host.jar:lib/* LendingDeskHost --out DIR --toy-jar bs3-toy.jar
 *      [--wait-seconds 600]
 * </pre>
 */
public final class LendingDeskHost {

    private LendingDeskHost() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        File out = new File(a.get("out"));
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IOException("cannot create " + out);
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        log("start: java " + System.getProperty("java.version") + ", pid " + pid);
        log("host libraries before: " + hostLibraries());

        LendingDb db = LendingDb.create("bs3", Seed.A, LocalDate.of(2026, 10, 9));
        HostTargets.ACCESS = AccessControl.load(db.connection());
        HostTargets.TOY_JAR = new File(a.get("toy-jar"));
        HostTargets.TOY_LOADER = HostTargets.newToyLoader("tool-shed");
        HostTargets.TOY_RULE = HostTargets.TOY_LOADER
                .loadClass("ai.skillroy.testjava.spikes.bs3.toy.DepositRule").newInstance();
        log("desk ready: " + HostTargets.check("100", AccessControl.RETURN, "45.00"));

        System.setProperty("bs3.host.ready", "true");
        write(new File(out, "host.ready"), pid);

        long waitMs = Long.parseLong(a.containsKey("wait-seconds") ? a.get("wait-seconds") : "600") * 1000L;
        long deadline = System.currentTimeMillis() + waitMs;
        while (!"true".equals(System.getProperty("bs3.probe.done")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        String result = System.getProperty("bs3.probe.result", "NO PROBE (timed out after " + waitMs + " ms)");
        log("host libraries after: " + hostLibraries());
        log("desk after the probe: " + HostTargets.check("100", AccessControl.RETURN, "45.00"));
        log("probe result: " + result);
        write(new File(out, "host.result"), result);
        db.close();
        System.exit("PASS".equals(result) ? 0 : 1);
    }

    /** Item 2: the host's own old libraries, used before and after the probe; "absent" when not on the class path. */
    private static String hostLibraries() {
        try {
            Class<?> k = Class.forName("ai.skillroy.testjava.spikes.bs3.hostlibs.OldLibraries");
            return String.valueOf(k.getMethod("exercise").invoke(null));
        } catch (ClassNotFoundException e) {
            return "absent";
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }

    private static void log(String line) {
        System.out.println("[host] " + line);
    }

    private static void write(File f, String text) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            m.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return m;
    }
}
