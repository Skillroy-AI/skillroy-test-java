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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Amendment A4 in a fresh JVM (the test JVM itself has long started AWT): installing the module and calling
 * {@code onTargetReady} in a JVM whose application has not used AWT starts no AWT thread, does not fix the headless
 * decision, leaves the display capabilities UNAVAILABLE with the stated reason, and lets {@code swing.edtState} answer;
 * once the application starts AWT the module completes its set-up. The child runs on the java named by
 * {@code skillroy.it.targetJava} (else this JVM's), so the Java 8 build proves it on Java 8.
 */
class AwtStartTest {

    private static final long CHILD_TIMEOUT_MS = 300000; // the Linux image may run x86-64 under emulation
    private static final String NOT_STARTED = "AWT not started by the application yet";
    private static final List<String> DISPLAY_CAPS = Arrays.asList("swing.capture.paint", "swing.capture.screen",
            "swing.click.action", "swing.click.component", "swing.click.dispatch", "swing.hittest",
            "swing.pointer.routed", "swing.tree");

    @TempDir
    File dir;

    @Test
    void installAndTargetReadyStartNoAwtAndLeaveTheHeadlessDecisionAlone() throws Exception {
        Map<String, String> out = runChild(new ArrayList<String>(), "flip-headless", "start-awt");
        before(out, NOT_STARTED);
        assertEquals("true", out.get("headlessAfterFlip"),
                "nothing fixed the headless decision before the application asked: " + out);
        // the application starts AWT (headless now): the module confirms with the toolkit and stops watching
        assertEquals("true", out.get("edtRan"));
        assertEquals("none", out.get("watcherAfter"), "the watcher ends once it has seen the dispatch thread");
        for (String id : DISPLAY_CAPS) {
            assertEquals("UNAVAILABLE|headless", out.get("cap.after." + id), id);
        }
        assertEquals("false", out.get("tapInstalled"), "no tap in a headless JVM");
        Map<String, Object> after = Json.parseObject(out.get("edtStateAfter"));
        assertEquals(Boolean.TRUE, after.get("awtStarted"));
        assertEquals(Boolean.TRUE, after.get("dispatchThreadAlive"));
    }

    @Test
    void headlessPropertyGivesTheHeadlessReasonBeforeAwtRuns() throws Exception {
        Map<String, String> out = runChild(Arrays.asList("-Djava.awt.headless=true"), "start-awt");
        before(out, "headless");
        assertEquals("none", out.get("watcherAfter"));
        for (String id : DISPLAY_CAPS) {
            assertEquals("UNAVAILABLE|headless", out.get("cap.after." + id), id);
        }
        assertEquals("false", out.get("tapInstalled"));
    }

    @Test
    void withADisplayTheCapabilitiesBecomeOkOnceTheApplicationStartsAwt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display (run under Xvfb with -Dskillroy.headless=false)");
        Map<String, String> out = runChild(new ArrayList<String>(), "start-awt");
        before(out, NOT_STARTED);
        assertEquals("true", out.get("edtRan"));
        assertEquals("none", out.get("watcherAfter"));
        for (String id : DISPLAY_CAPS) {
            assertEquals("OK|null", out.get("cap.after." + id), id);
        }
        assertEquals("true", out.get("tapInstalled"));
        assertEquals(Boolean.TRUE, Json.parseObject(out.get("edtStateAfter")).get("awtStarted"));
    }

    /** What every child reports before its application starts AWT. */
    private static void before(Map<String, String> out, String reason) {
        assertEquals("", out.get("awtThreadsAtStart"), "a fresh JVM: " + out);
        assertTrue(Integer.parseInt(out.get("watcherLooks")) >= 4, "the watcher kept looking: " + out);
        assertEquals("", out.get("awtThreadsBefore"), "install and onTargetReady started no AWT thread: " + out);
        assertEquals("daemon", out.get("watcherBefore"), "a daemon watcher waits for the dispatch thread");
        for (String id : DISPLAY_CAPS) {
            assertEquals("UNAVAILABLE|" + reason, out.get("cap.before." + id), id);
        }
        assertEquals("UNAVAILABLE|opt-in rung, not implemented", out.get("cap.before.swing.click.robot"));
        // Phase 7: without the agent option robot the Robot ids are set once at install and never probed
        assertEquals("UNAVAILABLE|robot: not enabled (agent option robot=true)",
                out.get("cap.before.swing.pointer.robot"));
        assertEquals("UNAVAILABLE|robot: not enabled (agent option robot=true)",
                out.get("cap.after.swing.capture.robot"));
        Map<String, Object> state = Json.parseObject(out.get("edtStateBefore"));
        assertEquals(map("dispatchThreadAlive", false, "edtBlocked", false, "awtStarted", false,
                "modalDialogs", list(), "windowCount", 0L), new LinkedHashMap<String, Object>(state));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE + "|" + reason, out.get("snapshotBefore"));
        assertEquals("", out.get("awtThreadsAfterCalls"), "swing.edtState started no AWT thread: " + out);
    }

    /** Runs {@link AwtStartChild} and returns its {@code key=value} lines. */
    private Map<String, String> runChild(List<String> jvmArgs, String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.addAll(jvmArgs);
        command.add("-Dapple.awt.UIElement=true"); // macOS: should AWT ever start natively, no Dock icon
        command.add("-cp");
        command.add(testClassPath());
        command.add(AwtStartChild.class.getName());
        command.addAll(Arrays.asList(args));
        File log = new File(dir, "child.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir).redirectErrorStream(true)
                .redirectOutput(log);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process p = builder.start();
        if (!p.waitFor(CHILD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            fail("child did not finish within " + CHILD_TIMEOUT_MS + " ms:\n" + read(log));
        }
        String text = read(log);
        assertEquals(0, p.exitValue(), "child exit code:\n" + text);
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String line : text.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        assertEquals("true", out.get("done"), "child output:\n" + text);
        return out;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String javaExecutable() {
        String target = System.getProperty("skillroy.it.targetJava");
        if (target != null && !target.trim().isEmpty()) {
            return target.trim();
        }
        return new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
    }

    private static String testClassPath() {
        String cp = System.getProperty("surefire.test.class.path");
        return cp != null && !cp.isEmpty() ? cp : System.getProperty("java.class.path");
    }
}
