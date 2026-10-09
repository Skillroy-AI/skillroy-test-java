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


package ai.skillroy.testjava.fixture.hooks;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A toy target for the distribution's hook ITs: reports its pid, uses {@link FeeRule}, then reads commands from its
 * standard input until it closes: {@code call} calls the rule; {@code snapshot <label>} prints, per shared loader
 * (bootstrap, platform or extension, system), the classes of the agent's it defines, read through the probe agent's
 * {@code Instrumentation.getInitiatedClasses} (phase6-contracts.md section 13.1 item 13).
 */
public class LoanDeskMain {

    public static void main(String[] args) throws Exception {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        System.out.println("pid=" + name.substring(0, name.indexOf('@')));
        FeeRule rule = new FeeRule();
        System.out.println("fee=" + rule.feeFor("P0"));
        System.out.println("parked");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        int n = 0;
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            n++;
            if ("call".equals(line)) {
                System.out.println("call." + n + "=" + rule.feeFor("P" + n));
            } else if (line.startsWith("snapshot ")) {
                String label = line.substring("snapshot ".length());
                Instrumentation inst = (Instrumentation) Class.forName(
                        "ai.skillroy.testjava.fixture.hooks.probe.ProbeAgent").getMethod("instrumentation").invoke(null);
                ClassLoader system = ClassLoader.getSystemClassLoader();
                System.out.println("snapshot." + label + ".boot=" + agents(inst, null));
                System.out.println("snapshot." + label + ".platform=" + agents(inst, platform()));
                System.out.println("snapshot." + label + ".system=" + agents(inst, system));
            }
            System.out.flush();
        }
        System.exit(0);
    }

    private static ClassLoader platform() {
        try {
            return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
        } catch (Exception java8) {
            return ClassLoader.getSystemClassLoader().getParent();
        }
    }

    /** The classes of the agent's (its package, Byte Buddy, Nexus) that this loader defines, sorted. */
    private static String agents(Instrumentation inst, ClassLoader loader) {
        List<String> out = new ArrayList<String>();
        for (Class<?> c : inst.getInitiatedClasses(loader)) {
            String n = c.getName();
            String lower = n.toLowerCase(java.util.Locale.ROOT);
            if (c.getClassLoader() == loader && (n.startsWith("ai.skillroy.testjava.agent.")
                    || lower.contains("bytebuddy") || n.contains("Nexus"))) {
                out.add(n);
            }
        }
        Collections.sort(out);
        StringBuilder text = new StringBuilder();
        for (String s : out) {
            text.append(text.length() == 0 ? "" : ",").append(s);
        }
        return text.toString();
    }
}
