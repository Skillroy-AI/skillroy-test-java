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
import java.lang.management.ManagementFactory;

/**
 * A tiny target application for the child-JVM tests: reports its pid and the agent's system properties (the agent,
 * if any, started before main), then parks until its standard input closes, so that it never outlives the test.
 */
public class ParkMain {

    public static void main(String[] args) throws IOException {
        report();
        park();
    }

    static void report() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        System.out.println("pid=" + name.substring(0, name.indexOf('@')));
        System.out.println("agentPort=" + System.getProperty("skillroy.agent.port"));
        System.out.println("agentError=" + System.getProperty("skillroy.agent.error"));
        System.out.println("parked");
        System.out.flush();
    }

    static void park() throws IOException {
        while (System.in.read() >= 0) {
            // Ignore input; end when the test closes the pipe or dies.
        }
        System.exit(0);
    }
}
