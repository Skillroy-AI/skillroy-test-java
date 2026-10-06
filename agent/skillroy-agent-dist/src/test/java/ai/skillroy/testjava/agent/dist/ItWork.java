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

package ai.skillroy.testjava.agent.dist;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The working material of one IT class: the assembled agent home, a private work directory under {@code target/},
 * the fixture application jar and the probe module jar. Each IT class gets its own directory so that one class's
 * set-up never deletes another's files.
 */
final class ItWork {

    final File home;
    final File work;
    final File appJar;
    final File probeJar;
    private final List<ChildJvm> children = new ArrayList<ChildJvm>();

    private ItWork(File home, File work, File appJar, File probeJar) {
        this.home = home;
        this.work = work;
        this.appJar = appJar;
        this.probeJar = probeJar;
    }

    static ItWork prepare(String name) throws IOException, URISyntaxException {
        String base = System.getProperty("basedir", System.getProperty("user.dir"));
        File home = new File(base, "target/skillroy-agent").getAbsoluteFile();
        assertTrue(new File(home, "skillroy-agent-bootstrap.jar").isFile(), "agent home not assembled: " + home);
        File work = new File(base, "target/it-work-" + name).getAbsoluteFile();
        AgentHomeIT.deleteTree(work);
        assertTrue(work.mkdirs());
        File appJar = new File(work, "app.jar");
        AgentHomeIT.jar(appJar, AgentHomeIT.packageClasses("ai.skillroy.testjava.agent.dist.fixture.app"));
        File probeJar = new File(work, "probe.jar");
        Map<String, byte[]> probe = AgentHomeIT.packageClasses("ai.skillroy.testjava.agent.dist.fixture.module");
        probe.put("META-INF/services/ai.skillroy.testjava.agent.api.AgentModule",
                "ai.skillroy.testjava.agent.dist.fixture.module.ProbeModule\n".getBytes(ChildJvm.UTF8));
        AgentHomeIT.jar(probeJar, probe);
        return new ItWork(home, work, appJar, probeJar);
    }

    File bootstrapJar() {
        return new File(home, "skillroy-agent-bootstrap.jar");
    }

    /** A copy of the home without modules: what the core alone does, whatever the other modules do. */
    File coreOnlyHome() throws IOException {
        File copy = new File(work, "core-only-" + System.nanoTime());
        AgentHomeIT.copyTree(home.toPath(), copy.toPath());
        File[] modules = new File(copy, "modules").listFiles();
        if (modules != null) {
            for (File module : modules) {
                Files.delete(module.toPath());
            }
        }
        return copy;
    }

    File newDir(String prefix) throws IOException {
        return Files.createTempDirectory(work.toPath(), prefix).toFile();
    }

    static String javaagent(File agentHome, String options) {
        return "-javaagent:" + new File(agentHome, "skillroy-agent-bootstrap.jar").getPath() + "=" + options;
    }

    ChildJvm launch(List<String> jvmArgs, String classPath, String mainClass, List<String> args,
                    Map<String, String> env) throws IOException {
        ChildJvm child = ChildJvm.start(work, jvmArgs, classPath, mainClass, args, env);
        children.add(child);
        return child;
    }

    void stopChildren() {
        for (ChildJvm child : children) {
            child.close();
        }
        children.clear();
    }
}
