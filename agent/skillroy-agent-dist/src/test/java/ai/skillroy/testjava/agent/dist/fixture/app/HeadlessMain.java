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

/**
 * A target application without a UI that makes its own headless decision programmatically, as some applications
 * do: it waits (while the test talks to the agent) for a line on standard input, then sets
 * {@code java.awt.headless=true} before its first use of AWT and reports what AWT decided. If the agent had touched
 * AWT earlier, the decision would already be frozen at the launch value and the line would say {@code false}.
 */
public final class HeadlessMain {

    private HeadlessMain() {
    }

    public static void main(String[] args) throws IOException {
        ParkMain.report();
        int c;
        while ((c = System.in.read()) >= 0 && c != '\n') {
            // Wait for the test's "go" line.
        }
        if (c < 0) {
            System.exit(0);
        }
        System.setProperty("java.awt.headless", "true");
        System.out.println("headless=" + FirstAwtUse.headless());
        System.out.flush();
        ParkMain.park();
    }

    /** Kept apart so that no AWT class is linked before the application decides. */
    private static final class FirstAwtUse {
        static boolean headless() {
            return java.awt.GraphicsEnvironment.isHeadless();
        }
    }
}
