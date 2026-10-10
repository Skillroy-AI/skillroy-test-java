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
package ai.skillroy.testjava.spikes.bs11;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The BS11 harness's entry point.
 *
 * <pre>
 *   run --out DIR --label L --java PATH --dist DIR --desk-home DIR --plain-home DIR --app-jar JAR
 *       [--jvm-arg ARG]... [--captures 100] [--launches 5] [--move-tool xdotool|none]
 *   compare RUN_A RUN_B OUT.json
 *   pair RUN STATE_A STATE_B OUT.json
 *   table NOISE.json... -- CHANGE.json...
 * </pre>
 */
public final class Bs11 {

    private Bs11() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: run|compare|pair|table ...");
            System.exit(2);
        }
        switch (args[0]) {
            case "run" -> new Runner(options(args)).run();
            case "compare" -> Compare.runs(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            case "pair" -> Compare.pair(Path.of(args[1]), args[2], args[3], Path.of(args[4]));
            case "table" -> {
                List<Path> noise = new ArrayList<>();
                List<Path> changes = new ArrayList<>();
                boolean second = false;
                for (int i = 1; i < args.length; i++) {
                    if (args[i].equals("--")) {
                        second = true;
                    } else {
                        (second ? changes : noise).add(Path.of(args[i]));
                    }
                }
                Compare.table(noise, changes);
            }
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
        System.exit(0);
    }

    /** {@code --key value} pairs (a key may repeat); a value may start with "-" (JVM options). */
    static Map<String, List<String>> options(String[] args) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected --key value at " + a);
            }
            m.computeIfAbsent(a.substring(2), k -> new ArrayList<>()).add(args[++i]);
        }
        return m;
    }
}
