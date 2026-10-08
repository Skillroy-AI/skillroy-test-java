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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.web.WebPath;
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * A toy CDP path (id {@code cdp}) for the CLI tests: it serves {@code web.dom.click} and {@code web.dom.fill} and
 * records what it served. It is on the test class path but registered nowhere there; only the jar
 * {@link #jar(Path)} builds registers it, so that a run finds it exactly when that jar is given with {@code --pack}
 * (phase4-contracts.md 6.1, D49).
 */
public final class CliCdpPath implements WebPath {

    /** The primitives the instances served, in order. */
    static final List<String> SERVED = new CopyOnWriteArrayList<>();

    /** Instances that were opened and later closed. */
    static final List<String> LIFECYCLE = new CopyOnWriteArrayList<>();

    private volatile boolean open;

    /** The public no-argument constructor {@code ServiceLoader} needs. */
    public CliCdpPath() {
    }

    @Override
    public String id() {
        return "cdp";
    }

    @Override
    public String open(AgentConnection agent, WebSettings settings, WebRunContext ctx) {
        open = true;
        LIFECYCLE.add("open");
        return null;
    }

    @Override
    public Map<String, Object> openInfo() {
        return Map.of("endpoint", "127.0.0.1:9222", "connectMs", 7L);
    }

    @Override
    public boolean serves(String primitive) {
        return primitive.equals("web.dom.click") || primitive.equals("web.dom.fill");
    }

    @Override
    public RpcResult call(String primitive, Map<String, Object> args, CallOptions options) {
        SERVED.add(primitive);
        return new RpcResult(Map.of("matched", 1L, "fidelity", "DOM"), List.of(), 0, 0, 1, "controller");
    }

    @Override
    public List<Event> eventsSince(long seq, String topics) {
        return List.of();
    }

    @Override
    public void close() {
        if (open) {
            open = false;
            LIFECYCLE.add("close");
        }
    }

    /** A jar whose only content registers this class as a {@code WebPath} (as {@code skillroy-cdp.jar} does). */
    static Path jar(Path dir) throws IOException {
        Path jar = dir.resolve("toy-cdp.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("META-INF/services/" + WebPath.class.getName()));
            out.write((CliCdpPath.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
