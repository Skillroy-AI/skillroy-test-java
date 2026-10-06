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
 * A target application that starts the agent itself through {@link EmbeddedAgent} (EMBEDDED mode), passing a host
 * object. Arguments: the discovery directory, then the module jar for the {@code modules} option.
 */
public final class EmbeddedMain {

    private EmbeddedMain() {
    }

    public static void main(String[] args) throws IOException {
        Properties options = new Properties();
        options.setProperty("enabled", "true");
        options.setProperty("discoveryDir", args[0]);
        options.setProperty("modules", args[1]);
        boolean started = EmbeddedAgent.start(options, EmbeddedMain.class.getClassLoader(),
                new HostThing("the embedding host"));
        System.out.println("embedded=" + started);
        ParkMain.report();
        ParkMain.park();
    }
}
