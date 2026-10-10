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

/**
 * A target application with a UI for the Robot check of the host-safety tests (phase7-contracts.md section 13.2 item
 * 11): it waits (while the test looks at what the agent loaded) for a line on standard input, then starts AWT itself
 * by showing a frame with a button ({@code shown=true}), or, with the argument {@code headless}, decides headless and
 * starts only the event-dispatch thread ({@code awt=headless}); then parks.
 */
public final class SwingMain {

    private SwingMain() {
    }

    public static void main(String[] args) throws Exception {
        ParkMain.report();
        int c;
        while ((c = System.in.read()) >= 0 && c != '\n') {
            // Wait for the test's "go" line.
        }
        if (c < 0) {
            System.exit(0);
        }
        if (args.length > 0 && "headless".equals(args[0])) {
            System.setProperty("java.awt.headless", "true");
            Ui.headless();
            System.out.println("awt=headless");
        } else {
            Ui.show();
            System.out.println("shown=true");
        }
        System.out.flush();
        ParkMain.park();
    }

    /** Kept apart so that no AWT class is linked before the application decides. */
    private static final class Ui {
        static void show() throws Exception {
            java.awt.EventQueue.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    javax.swing.JFrame frame = new javax.swing.JFrame("Host safety fixture");
                    frame.add(new javax.swing.JButton("Press"));
                    frame.setBounds(0, 0, 300, 200);
                    frame.setVisible(true);
                }
            });
        }

        static void headless() throws Exception {
            java.awt.EventQueue.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    // starts the event-dispatch thread only
                }
            });
        }
    }
}
