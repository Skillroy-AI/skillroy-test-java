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

package ai.skillroy.testjava.controller;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Run by {@link NoAttachModuleTest} in a JVM started with {@code --limit-modules java.base,java.net.http}: proves
 * that the controller's classes load and work without {@code jdk.attach}, and that attach fails cleanly. Prints
 * {@code OK} and exits 0, or prints the problem and exits 1.
 */
public final class NoAttachProbe {

    private NoAttachProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (ModuleLayer.boot().findModule("jdk.attach").isPresent()) {
            fail("jdk.attach is present; the probe proves nothing");
        }
        AgentHome home = AgentHome.of(Path.of(args[0]));
        if (!Discovery.list(Path.of(args[1])).isEmpty()) {
            fail("unexpected discovery entries");
        }
        TargetLauncher.java(Path.of("java")).mainClass("x.Main").classPath(List.of());
        new CapabilityMatrix(Map.of());
        try {
            AgentClient.connect(new Endpoint("127.0.0.1", 1, "token", null, 0, 0, null));
            fail("connected to port 1");
        } catch (AgentLostException expected) {
            // The client class loaded and mapped the refused connection.
        }
        try {
            Discovery.awaitPid(ProcessHandle.current().pid(), Path.of(args[1]), Duration.ofMillis(200));
            fail("found an agent");
        } catch (AgentLostException expected) {
            // Discovery never needs jdk.attach.
        }
        try {
            AttachSession.attach(Long.MAX_VALUE, home, Map.of());
            fail("attach did not fail");
        } catch (AttachFailedException expected) {
            if (!expected.getMessage().contains("jdk.attach")) {
                fail("unexpected message: " + expected.getMessage());
            }
        }
        try {
            AttachSession.listJvms();
            fail("listJvms did not fail");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("jdk.attach")) {
                fail("unexpected message: " + expected.getMessage());
            }
        }
        System.out.println("OK");
    }

    private static void fail(String message) {
        System.out.println("FAIL " + message);
        System.exit(1);
    }
}
