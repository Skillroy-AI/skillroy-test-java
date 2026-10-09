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

package ai.skillroy.testjava.fixture.byteman;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code BytemanIT}'s toy target (phase6-contracts.md 7.6), outside the protected prefixes: a tool library's return
 * ceiling per staff code, {@link #ceiling(String)}, called on the toy's main thread when the test asks. It reads
 * commands from stdin and answers on stdout, one line each:
 * <ul>
 *   <li>{@code CEILING <code>}: {@code ceiling=<code>:<result>};</li>
 *   <li>{@code REATTACH <options>}: calls the bootstrap's {@code agentmain} with the kept {@code Instrumentation} (a
 *       re-attach after {@code agent.disable}); {@code reattached=<n>};</li>
 *   <li>{@code SEAM}: sets the Byteman module's test seam (its uninstall then leaves the rules installed, as a
 *       cut-short detach budget would); {@code seam=set};</li>
 *   <li>{@code THREADS}: {@code threads=<the live threads whose name starts with skillroy, comma-separated>}.</li>
 * </ul>
 * It has no listener of its own.
 */
public class LendingToyMain {

    public BigDecimal ceiling(String code) {
        if (code == null || code.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return code.startsWith("SUP") ? new BigDecimal("500.00") : new BigDecimal("150.00");
    }

    public static void main(String[] args) throws Exception {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        System.out.println("pid=" + name.substring(0, name.indexOf('@')));
        LendingToyMain toy = new LendingToyMain();
        System.out.println("first=" + toy.ceiling("STAFF-0"));
        System.out.println("parked");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, Charset.forName("UTF-8")));
        int reattached = 0;
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("CEILING ")) {
                String code = line.substring(8).trim();
                Object result;
                try {
                    result = toy.ceiling(code);
                } catch (Throwable t) {
                    result = t.getClass().getName();
                }
                System.out.println("ceiling=" + code + ":" + result);
            } else if (line.startsWith("REATTACH ")) {
                Instrumentation inst = InstrumentationKeeper.instrumentation();
                Class<?> boot = Class.forName("ai.skillroy.testjava.agent.bootstrap.AgentBootstrap", true,
                        ClassLoader.getSystemClassLoader());
                Method agentmain = boot.getMethod("agentmain", String.class, Instrumentation.class);
                agentmain.invoke(null, line.substring(9).trim(), inst);
                System.out.println("reattached=" + (++reattached));
            } else if (line.equals("SEAM")) {
                System.setProperty("skillroy.agent.byteman.test.keepRulesAtUninstall", "true");
                System.out.println("seam=set");
            } else if (line.equals("THREADS")) {
                Set<String> names = new TreeSet<String>();
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    if (t.isAlive() && t.getName().startsWith("skillroy")) {
                        names.add(t.getName());
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (String n : names) {
                    sb.append(sb.length() == 0 ? "" : ",").append(n);
                }
                System.out.println("threads=" + sb);
            }
            System.out.flush();
        }
        System.exit(0);
    }
}
