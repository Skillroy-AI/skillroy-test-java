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

package ai.skillroy.testjava.agent.dist.fixture.app;

import java.io.IOException;
import java.util.Map;
import java.util.TreeSet;

/**
 * Finding S34 in a child JVM: waits (bounded) until the agent has closed, then reports every live thread whose
 * context class loader is the agent's isolated loader ({@code agentLoaderThreads=<names>}, empty when none), then
 * parks like {@link ParkMain}.
 */
public final class ContextLoaderMain {

    private static final String AGENT_LOADER = "ai.skillroy.testjava.agent.bootstrap.AgentClassLoader";

    private ContextLoaderMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ParkMain.report();
        long deadline = System.currentTimeMillis() + 60000L;
        while (System.currentTimeMillis() < deadline && (System.getProperty("skillroy.agent.loadMode") != null
                || !"true".equals(System.getProperty("probe.uninstalled")))) {
            Thread.sleep(50);
        }
        TreeSet<String> pinned = new TreeSet<String>();
        long settle = System.currentTimeMillis() + 10000L;
        do {
            pinned.clear();
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                ClassLoader loader = e.getKey().getContextClassLoader();
                if (e.getKey().isAlive() && loader != null && AGENT_LOADER.equals(loader.getClass().getName())) {
                    pinned.add(e.getKey().getName());
                }
            }
            if (!pinned.isEmpty()) {
                Thread.sleep(50);   // agent threads that are still ending
            }
        } while (!pinned.isEmpty() && System.currentTimeMillis() < settle);
        StringBuilder names = new StringBuilder();
        for (String name : pinned) {
            names.append(names.length() == 0 ? "" : ",").append(name);
        }
        System.out.println("agentLoaderThreads=" + names);
        System.out.flush();
        ParkMain.park();
    }
}
