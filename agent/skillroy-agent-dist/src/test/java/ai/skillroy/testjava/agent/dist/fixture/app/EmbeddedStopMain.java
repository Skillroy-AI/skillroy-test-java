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

import ai.skillroy.testjava.agent.bootstrap.EmbeddedAgent;

import java.io.IOException;
import java.util.Properties;

/**
 * A target application that starts the agent through {@link EmbeddedAgent} and, when it reads a line on standard
 * input, stops it again on its main thread, which the test module declares forbidden: the stop must return at once
 * and the agent must still shut down completely behind it. Arguments: the discovery directory, then the module jar.
 */
public final class EmbeddedStopMain {

    private static final long WAIT_MS = 60000L;

    private EmbeddedStopMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Properties options = new Properties();
        options.setProperty("enabled", "true");
        options.setProperty("discoveryDir", args[0]);
        options.setProperty("modules", args[1]);
        options.setProperty("probe.forbidMain", "true");
        boolean started = EmbeddedAgent.start(options, EmbeddedStopMain.class.getClassLoader());
        System.out.println("embedded=" + started);
        ParkMain.report();
        int c;
        while ((c = System.in.read()) >= 0 && c != '\n') {
            // Wait for the test's "stop" line.
        }
        long before = System.nanoTime();
        EmbeddedAgent.stop();
        System.out.println("stopMs=" + (System.nanoTime() - before) / 1000000L);
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline && !(System.getProperty("probe.uninstalled") != null
                && System.getProperty("skillroy.agent.port") == null)) {
            Thread.sleep(20);
        }
        System.out.println("uninstalled=" + System.getProperty("probe.uninstalled"));
        System.out.println("portAfterStop=" + System.getProperty("skillroy.agent.port"));
        System.out.flush();
        ParkMain.park();
    }
}
