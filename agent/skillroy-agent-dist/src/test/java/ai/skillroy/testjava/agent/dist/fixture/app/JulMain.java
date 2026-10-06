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
import java.util.logging.LogManager;

/**
 * A target application that installs its own {@code java.util.logging} manager and configuration at the top of
 * {@code main}, the usual way to bridge JUL to another logging framework. If anything had initialised JUL before
 * {@code main} (the JDK's HTTP server did so on Java 8, security findings S12 and S16), the JVM would keep the default
 * manager and configuration and the lines printed here would say so.
 *
 * <p>Argument: the path of a logging configuration file that sets {@code fixture.marker}.
 */
public final class JulMain {

    private JulMain() {
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.util.logging.manager", FixtureLogManager.class.getName());
        System.setProperty("java.util.logging.config.file", args[0]);
        LogManager manager = LogManager.getLogManager();
        System.out.println("logManager=" + manager.getClass().getName());
        System.out.println("logConfig=" + manager.getProperty("fixture.marker"));
        ParkMain.report();
        ParkMain.park();
    }
}
