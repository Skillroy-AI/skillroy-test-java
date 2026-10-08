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

package ai.skillroy.testjava.cdp;

import com.microsoft.playwright.Tracing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

/**
 * Context tracing of an attach (phase4-contracts.md section 5.1; browser research §F18): Playwright's trace of the
 * attached context, written as a zip. A trace holds page content (DOM snapshots with the values of input fields,
 * screenshots, the source of the scripts the path evaluated) and does not pass through the run's secret mask, so a
 * run that handled secrets should not keep one. While tracing runs, Playwright appends the trace to live files in the
 * attach's {@link CdpOptions#artifactsDir()} and never deletes them, whether the trace is saved or discarded.
 *
 * <p>Thread-safe; the work runs on the attach's Playwright thread within 30 s.
 */
public final class CdpTracing {

    /** The bound of starting or stopping a trace. */
    static final long BUDGET_MS = 30_000;

    private final CdpAttach attach;
    private final Path artifactsDir;
    private volatile boolean active;

    CdpTracing(CdpAttach attach, Path artifactsDir) {
        this.attach = attach;
        this.artifactsDir = artifactsDir;
    }

    /**
     * Starts tracing the context with screenshots and DOM snapshots.
     *
     * @throws CdpException {@code TIMEOUT} or {@code PROTOCOL} when Playwright did not start it
     */
    public void start(String name) throws CdpException {
        String traceName = name == null || name.isBlank() ? "skillroy" : name;
        try {
            attach.run(() -> {
                attach.context().tracing().start(new Tracing.StartOptions().setName(traceName).setScreenshots(true)
                        .setSnapshots(true));
                return null;
            }, BUDGET_MS);
        } catch (Exception e) {
            throw failure("start", e);
        }
        active = true;
    }

    /**
     * Stops tracing and writes the trace to {@code path} (a relative path resolves against the artifacts directory
     * when there is one); null discards the trace. Does nothing and returns null when tracing is not active.
     *
     * @return the file written, or null
     * @throws CdpException {@code TIMEOUT} or {@code PROTOCOL} when Playwright did not stop it
     */
    public Path stop(Path path) throws CdpException {
        return stop(path, BUDGET_MS);
    }

    /** {@link #stop(Path)} within {@code budgetMs}. */
    Path stop(Path path, long budgetMs) throws CdpException {
        if (!active) {
            return null;
        }
        Path target = path == null ? null
                : path.isAbsolute() || artifactsDir == null ? path : artifactsDir.resolve(path);
        try {
            attach.run(() -> {
                Tracing.StopOptions stop = new Tracing.StopOptions();
                if (target != null) {
                    Path parent = target.toAbsolutePath().getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    stop.setPath(target);
                }
                attach.context().tracing().stop(stop);
                return null;
            }, budgetMs);
        } catch (Exception e) {
            throw failure("stop", e);
        } finally {
            active = false;
        }
        return target;
    }

    /** True between a successful {@link #start} and the next {@link #stop}. */
    public boolean isActive() {
        return active;
    }

    private static CdpException failure(String what, Exception e) {
        CdpException.Kind kind = e instanceof TimeoutException ? CdpException.Kind.TIMEOUT
                : CdpException.Kind.PROTOCOL;
        return new CdpException(kind, "could not " + what + " the trace: " + CdpAttach.firstLine(String.valueOf(
                e.getMessage())), null, e);
    }
}
