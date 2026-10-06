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
 * Finding S9 in a child JVM: the operator configures the agent with {@code -Dskillroy.agent.*} system properties
 * (port, token, token file); the application starts the agent through {@link EmbeddedAgent}, stops it and starts it
 * again, and reports the port and token-file properties after each step.
 */
public final class RestartMain {

    private RestartMain() {
    }

    public static void main(String[] args) throws IOException {
        Properties none = new Properties();
        System.out.println("first=" + EmbeddedAgent.start(none, RestartMain.class.getClassLoader()) + ","
                + System.getProperty("skillroy.agent.port"));
        EmbeddedAgent.stop();
        System.out.println("afterStop=" + System.getProperty("skillroy.agent.port") + ","
                + System.getProperty("skillroy.agent.tokenFile") + "," + System.getProperty("skillroy.agent.loadMode"));
        System.out.println("second=" + EmbeddedAgent.start(none, RestartMain.class.getClassLoader()) + ","
                + System.getProperty("skillroy.agent.port"));
        System.out.flush();
        ParkMain.report();
        ParkMain.park();
    }
}
