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

package ai.skillroy.testjava.spikes.bs3.host;

import java.lang.reflect.InvocationTargetException;

/**
 * The attach client of the ATTACH runs (run on the image's JDK 25, as the controller is): {@code VirtualMachine.attach}
 * and {@code loadAgent} through reflection, so that this jar stays Java 8 bytecode. A Java 8 target answers a
 * successful load with a bare {@code 0}, which a JDK 10+ client reports as an {@code AgentLoadException} (attach F1);
 * the message is printed as it is and the exit code says what it means.
 *
 * <pre>java -cp bs3-host.jar ai.skillroy.testjava.spikes.bs3.host.Attach PID AGENT_JAR OPTIONS</pre>
 * Exit codes: 0 loaded, 2 loaded with the Java 8 "0" reply, 1 failed.
 */
public final class Attach {

    private Attach() {
    }

    public static void main(String[] args) throws Exception {
        String pid = args[0];
        String jar = args[1];
        String options = args.length > 2 ? args[2] : null;
        Class<?> vmc = Class.forName("com.sun.tools.attach.VirtualMachine");
        long t0 = System.nanoTime();
        Object vm = vmc.getMethod("attach", String.class).invoke(null, pid);
        long t1 = System.nanoTime();
        int code = 0;
        try {
            vmc.getMethod("loadAgent", String.class, String.class).invoke(vm, jar, options);
            System.out.println("[attach] loadAgent returned normally");
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            String m = String.valueOf(c.getMessage());
            System.out.println("[attach] loadAgent threw " + c.getClass().getName() + ": " + m);
            if (c.getClass().getName().endsWith("AgentLoadException") && ("0".equals(m) || m.endsWith(": 0"))) {
                System.out.println("[attach] the Java 8 '0' reply: the agent was loaded");
                code = 2;
            } else {
                code = 1;
            }
        } finally {
            vmc.getMethod("detach").invoke(vm);
        }
        long t2 = System.nanoTime();
        System.out.printf(java.util.Locale.ROOT, "[attach] attach %.1f ms, loadAgent %.1f ms%n", (t1 - t0) / 1e6,
                (t2 - t1) / 1e6);
        System.exit(code);
    }
}
