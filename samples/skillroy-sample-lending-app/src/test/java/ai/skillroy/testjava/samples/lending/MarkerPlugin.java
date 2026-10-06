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

package ai.skillroy.testjava.samples.lending;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;

/**
 * Used by {@link LendingAppMainTest} in a child JVM: once the desk waits for input it writes a line describing
 * it to the file named by {@code lending.test.marker} and exits the JVM; its {@link #stop()} (run by the shutdown hook)
 * appends {@code stopped}.
 */
public class MarkerPlugin implements LendingPlugin {

    private static void write(String line) {
        try {
            Files.write(Paths.get(System.getProperty("lending.test.marker")),
                Collections.singletonList(line), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void start(LendingApp app) {
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 60_000;
            while (!app.engine().isAwaitingInput() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
            write("started seed=" + app.seed() + " state=" + app.engine().state() + " title=" + app.title()
                + " thread=" + app.engine().thread().getName() + " java=" + System.getProperty("java.version")
                + " spec=" + System.getProperty("java.specification.version"));
            System.exit(0);
        }, "marker");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void stop() {
        write("stopped");
    }
}
