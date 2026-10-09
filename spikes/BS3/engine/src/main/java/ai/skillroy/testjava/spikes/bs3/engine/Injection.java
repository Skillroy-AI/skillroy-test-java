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

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What lives in the host's shared loaders (bootstrap, platform or extension, system) at a point in time, for the
 * "no injection" check (BS3 item 6); classes of other loaders are counted per loader type.
 */
final class Injection {

    final Set<String> boot = new TreeSet<String>();
    final Set<String> platform = new TreeSet<String>();
    final Set<String> system = new TreeSet<String>();
    final Map<String, Integer> others = new TreeMap<String, Integer>();

    static Injection snapshot(Instrumentation inst) {
        Injection s = new Injection();
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        ClassLoader plat = sys.getParent();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.isArray() || c.isPrimitive()) {
                continue;
            }
            String n = c.getName();
            if (n.contains("$$Lambda") || n.contains("LambdaForm$") || n.startsWith("jdk.internal.reflect.Generated")
                    || n.startsWith("sun.reflect.Generated") || n.contains("$Proxy")) {
                continue;
            }
            ClassLoader l = c.getClassLoader();
            if (l == null) {
                s.boot.add(n);
            } else if (l == sys) {
                s.system.add(n);
            } else if (l == plat) {
                s.platform.add(n);
            } else {
                String k = l.getClass().getName();
                Integer v = s.others.get(k);
                s.others.put(k, v == null ? 1 : v + 1);
            }
        }
        return s;
    }

    private static boolean jdk(String n) {
        if (n.contains(".skillroy.")) {
            return false;
        }
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.")
                || n.startsWith("com.sun.") || n.startsWith("org.xml.") || n.startsWith("org.w3c.");
    }

    /** @return lines: per shared loader, the classes added since {@code before} that are not JDK classes, and a count of the JDK ones */
    List<String> added(Injection before) {
        List<String> out = new ArrayList<String>();
        added(out, "bootstrap", before.boot, boot);
        added(out, "platform/extension", before.platform, platform);
        added(out, "system", before.system, system);
        for (Map.Entry<String, Integer> e : others.entrySet()) {
            Integer b = before.others.get(e.getKey());
            int d = e.getValue() - (b == null ? 0 : b);
            if (d != 0) {
                out.add("other loader " + e.getKey() + ": " + (d > 0 ? "+" : "") + d + " classes");
            }
        }
        return out;
    }

    private static void added(List<String> out, String label, Set<String> before, Set<String> after) {
        List<String> foreign = new ArrayList<String>();
        int jdkCount = 0;
        for (String n : after) {
            if (!before.contains(n)) {
                if (jdk(n) && !ours(n)) {
                    jdkCount++;
                } else {
                    foreign.add(n);
                }
            }
        }
        out.add(label + ": " + foreign.size() + " non-JDK classes added " + foreign + ", " + jdkCount
                + " JDK classes loaded meanwhile");
    }

    /** @return the non-JDK classes added since {@code before} to one shared loader: boot, platform or system */
    List<String> addedNames(Injection before, String which) {
        Set<String> a = "boot".equals(which) ? boot : "platform".equals(which) ? platform : system;
        Set<String> b = "boot".equals(which) ? before.boot : "platform".equals(which) ? before.platform : before.system;
        List<String> out = new ArrayList<String>();
        for (String n : a) {
            if (!b.contains(n) && (!jdk(n) || ours(n))) {
                out.add(n);
            }
        }
        return out;
    }

    /** True for a name that only the engine or Byte Buddy would put into a shared loader. */
    static boolean ours(String n) {
        String l = n.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("ai.skillroy.testjava.spikes.bs3.engine.") || l.contains("bytebuddy")
                || l.contains("nexus") || l.contains("$auxiliary$") || l.contains(".shaded.");
    }

    /** @return the non-JDK classes of the shared loaders whose names point at Byte Buddy or generated code */
    List<String> suspicious() {
        List<String> out = new ArrayList<String>();
        for (Set<String> s : java.util.Arrays.asList(boot, platform, system)) {
            for (String n : s) {
                String l = n.toLowerCase(java.util.Locale.ROOT);
                if (l.contains("bytebuddy") || l.contains("nexus") || l.contains("$auxiliary$")
                        || l.contains("shaded")) {
                    out.add(n);
                }
            }
        }
        return out;
    }
}
