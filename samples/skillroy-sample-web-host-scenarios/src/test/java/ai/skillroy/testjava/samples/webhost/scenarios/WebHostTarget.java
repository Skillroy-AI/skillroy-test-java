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

package ai.skillroy.testjava.samples.webhost.scenarios;

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentHome;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.AttachFailedException;
import ai.skillroy.testjava.controller.AttachSession;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.controller.LaunchedTarget;
import ai.skillroy.testjava.controller.TargetLauncher;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.samples.webhost.WebHostApp;
import ai.skillroy.testjava.samples.webhost.WebHostOptions;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One web host process with its agent (phase4-contracts.md 12.2), started through the real {@link TargetLauncher},
 * {@link AttachSession} and {@link AgentClient}: {@link Mode#JAVAAGENT} or {@link Mode#ATTACH} (the host has no
 * embedded start), windowed or off-screen rendering, with or without a remote-debugging port.
 *
 * <p>The command line: the target Java ({@code -Dskillroy.it.targetJava}); {@code -Djava.awt.headless=false};
 * {@code -Dwebhost.rendering=windowed|osr}; {@code -Dwebhost.debugPort=<port>} (0: none; else a free loopback port
 * this class picks just before the launch: CEF cannot report one it picked itself without a cache directory to read
 * it from); {@code -Dwebhost.install=<the natives' install directory of the platform>}; {@code -Dwebhost.cache=<a fresh
 * directory>}; for off-screen rendering on Java 9 or newer the host's {@link WebHostApp#OFF_SCREEN_JVM_ARGS} (JOGL's
 * {@code --add-exports}; a Java 8 target refuses them and does not need them); the agent flags for
 * {@link Mode#JAVAAGENT}; the class path of {@code target/it-target} and the host's main class. Output goes to
 * {@code target/it-logs/<label>.log}. {@link #close()} ends the process and removes the cache directory.
 */
final class WebHostTarget implements AutoCloseable {

    /** How the agent gets into the host. */
    enum Mode {
        JAVAAGENT("javaagent"),
        ATTACH("attach");

        final String slug;

        Mode(String slug) {
            this.slug = slug;
        }
    }

    static final String MAIN_CLASS = "ai.skillroy.testjava.samples.webhost.WebHostApp";
    /** Generous: the image may run under x86-64 emulation, and CEF starts first. */
    static final Duration AGENT_TIMEOUT = Duration.ofSeconds(240);

    final Mode mode;
    final WebHostOptions.Rendering rendering;
    final int port;
    final String label;
    final AgentHome home;
    final Path discoveryDir;
    final Path cacheDir;
    final LaunchedTarget launched;
    Endpoint endpoint;
    AgentClient client;
    long agentMs;
    long readyMs;

    private WebHostTarget(Mode mode, WebHostOptions.Rendering rendering, int port, String label, AgentHome home,
                          Path discoveryDir, Path cacheDir, LaunchedTarget launched) {
        this.mode = mode;
        this.rendering = rendering;
        this.port = port;
        this.label = label;
        this.home = home;
        this.discoveryDir = discoveryDir;
        this.cacheDir = cacheDir;
        this.launched = launched;
    }

    /** The label of a launch: {@code <mode>-<rendering>-<port|noport>}, for logs and output directories. */
    static String label(Mode mode, WebHostOptions.Rendering rendering, boolean withPort) {
        return mode.slug + "-" + rendering.id() + "-" + (withPort ? "port" : "noport");
    }

    /**
     * Launches the web host with the agent in {@code mode} and waits until the agent reports the target ready (the
     * host registered its browsers, the web module probed them).
     */
    static WebHostTarget launch(Mode mode, WebHostOptions.Rendering rendering, boolean withPort) throws Exception {
        AgentHome home = ItEnv.agentHome();
        String label = label(mode, rendering, withPort);
        Path discovery = ItEnv.newDiscoveryDir(label);
        Path cache = ItEnv.newCacheDir(label);
        int port = withPort ? freePort() : WebHostOptions.NO_DEBUG_PORT;
        TargetLauncher.Builder builder = TargetLauncher.java(ItEnv.targetJava())
                .mainClass(MAIN_CLASS)
                .classPath(ItEnv.hostClassPath())
                .jvmArg("-Djava.awt.headless=false")
                .jvmArg("-D" + WebHostOptions.PROP_RENDERING + "=" + rendering.id())
                .jvmArg("-D" + WebHostOptions.PROP_DEBUG_PORT + "=" + port)
                .jvmArg("-D" + WebHostOptions.PROP_INSTALL + "=" + ItEnv.jcefInstall())
                .jvmArg("-D" + WebHostOptions.PROP_CACHE + "=" + cache)
                .discoveryDir(discovery)
                .inheritOutputTo(ItEnv.log(label + ".log"));
        if (rendering == WebHostOptions.Rendering.OSR && ItEnv.targetFeature() >= 9) {
            for (String arg : WebHostApp.OFF_SCREEN_JVM_ARGS) {
                builder.jvmArg(arg);
            }
        }
        Map<String, String> agentOptions = Map.of("discoveryDir", discovery.toString());
        if (mode == Mode.JAVAAGENT) {
            builder.javaAgent(home, agentOptions);
        }
        long t0 = System.nanoTime();
        LaunchedTarget launched = builder.start();
        WebHostTarget target = new WebHostTarget(mode, rendering, port, label, home, discovery, cache, launched);
        try {
            target.endpoint = mode == Mode.ATTACH ? AttachSession.attach(launched.pid(), home, agentOptions)
                    : launched.awaitAgent(AGENT_TIMEOUT);
            target.agentMs = (System.nanoTime() - t0) / 1_000_000L;
            target.client = AgentClient.connect(target.endpoint);
            awaitReady(target.client);
            target.readyMs = (System.nanoTime() - t0) / 1_000_000L;
            return target;
        } catch (Exception | Error e) {
            target.close();
            throw e;
        }
    }

    /** A loopback port nothing listens on now. */
    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Waits for the agent's {@code target.ready {ready: true}}: readiness, modules and probes are done. */
    static Event awaitReady(AgentClient client) throws AgentLostException {
        Event ready;
        try {
            ready = client.await("target.ready", 0, Map.of("ready", true), AGENT_TIMEOUT);
        } catch (AgentRpcException e) {
            throw new AssertionError("waiting for target.ready failed: " + e.getMessage(), e);
        }
        if (ready == null) {
            throw new AssertionError("the agent did not report the target ready within " + AGENT_TIMEOUT);
        }
        return ready;
    }

    /** Attaches an agent again (a fresh agent after {@code agent.disable}). */
    Endpoint attach() throws AttachFailedException {
        return AttachSession.attach(launched.pid(), home, Map.of("discoveryDir", discoveryDir.toString()));
    }

    long pid() {
        return launched.pid();
    }

    boolean offScreen() {
        return rendering == WebHostOptions.Rendering.OSR;
    }

    /** The page names this host shows: the detached page off screen only. */
    List<String> pages() {
        List<String> pages = new ArrayList<>(List.of("front", "back"));
        if (offScreen()) {
            pages.add("detached");
        }
        return pages;
    }

    @Override
    public void close() {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            launched.close();
            deleteTree(cacheDir);
        }
    }

    static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[it] could not remove " + dir + ": " + e);
        }
    }

    @Override
    public String toString() {
        return label.toUpperCase(Locale.ROOT) + "[pid " + launched.pid() + "]";
    }
}
