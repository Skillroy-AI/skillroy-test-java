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

package ai.skillroy.testjava.agent.web.fake;

import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The neutral fake's engine options ({@code remoteDebuggingPort()}, {@code userDataDir()}, {@code renderingMode()})
 * and a {@code licenseKey()} that fails the test when called: the adapter must never read a licence key
 * (browser-spi C5).
 */
public final class FakeOptions {

    static {
        ClassInitialisations.record(FakeOptions.class);
    }

    private final Integer port;
    private final Path userDataDir;
    private final FakeRenderingMode mode;
    private final FakeEngine.Violations violations;

    FakeOptions(Integer port, Path userDataDir, FakeRenderingMode mode, FakeEngine.Violations violations) {
        this.port = port;
        this.userDataDir = userDataDir;
        this.mode = mode;
        this.violations = violations;
    }

    public Optional<Integer> remoteDebuggingPort() {
        return Optional.ofNullable(port);
    }

    public Optional<Path> userDataDir() {
        return Optional.ofNullable(userDataDir);
    }

    public FakeRenderingMode renderingMode() {
        return mode;
    }

    /** Never to be called by an adapter. */
    public String licenseKey() {
        violations.add("licenseKey() was read");
        throw new AssertionError("an adapter read the licence key");
    }
}
