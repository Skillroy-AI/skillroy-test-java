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


package ai.skillroy.testjava.agent.hooks;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * The other bytecode agents the engine can see (phase6-contracts.md section 3.2, D154): for each {@code -javaagent:}
 * argument other than the agent's own, the jar's file name only (an agent's option string may carry a licence key),
 * and the marker {@code byteman} when {@code org.jboss.byteman.agent.loaded} is {@code true}. What such an agent
 * changed before the engine's first retransformation is invisible to the byte check (BS3 RESULT section 9 facts 13
 * and 14); the record is where that is said.
 *
 * <p>Stateless; safe from any thread.
 */
final class OtherAgents {

    /** The agent's own bootstrap jar, which is never reported. */
    static final String OWN_JAR = "skillroy-agent-bootstrap.jar";

    private OtherAgents() {
    }

    static List<String> list() {
        List<String> out = new ArrayList<String>();
        try {
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (!argument.startsWith("-javaagent:")) {
                    continue;
                }
                String spec = argument.substring("-javaagent:".length());
                int eq = spec.indexOf('=');
                String path = eq >= 0 ? spec.substring(0, eq) : spec;
                String name = new File(path.replace('\\', '/')).getName();
                if (!name.isEmpty() && !OWN_JAR.equals(name) && !out.contains(name)) {
                    out.add(name);
                }
            }
        } catch (Throwable t) {
            // Management is unavailable: nothing to report.
        }
        try {
            if ("true".equals(System.getProperty("org.jboss.byteman.agent.loaded"))) {
                out.add("byteman");
            }
        } catch (Throwable t) {
            // A security manager refused the read.
        }
        return out;
    }
}
