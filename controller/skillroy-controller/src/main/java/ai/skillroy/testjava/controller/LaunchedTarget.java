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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A target JVM started by {@link TargetLauncher}: its process, how it was launched, and the way to its agent. Owns
 * the process: {@link #close()} ends it together with every process it started, so a test cannot leak targets.
 *
 * <p>Thread-safe; {@link #awaitAgent} blocks the calling thread for at most its timeout.
 */
public final class LaunchedTarget implements AutoCloseable {

    private static final Duration GRACEFUL_EXIT = Duration.ofSeconds(5);
    private static final Duration FORCED_EXIT = Duration.ofSeconds(5);
    private static final long LOG_TAIL_BYTES = 256 * 1024;
    private static final int LOG_LINES = 10;

    private final Process process;
    private final List<String> command;
    private final Map<String, String> environment;
    private final Path optionsFile;
    private final String agentNonce;
    private final String agentToken;
    private final Path discoveryDir;
    private final Path logFile;
    private final AtomicBoolean closed = new AtomicBoolean();

    LaunchedTarget(Process process, List<String> command, Map<String, String> environment, Path optionsFile,
                   String agentNonce, String agentToken, Path discoveryDir, Path logFile) {
        this.process = process;
        this.command = command;
        this.environment = environment;
        this.optionsFile = optionsFile;
        this.agentNonce = agentNonce;
        this.agentToken = agentToken;
        this.discoveryDir = discoveryDir;
        this.logFile = logFile;
    }

    /** The pid of the launched process. */
    public long pid() {
        return process.pid();
    }

    /** The launched process. */
    public Process process() {
        return process;
    }

    /** The command line the process was started with (unmodifiable). */
    public List<String> command() {
        return command;
    }

    /**
     * The environment variables the launcher set on top of the inherited environment (unmodifiable; a null value
     * means removed), including {@code JAVA_TOOL_OPTIONS} in that mode.
     */
    public Map<String, String> environment() {
        return environment;
    }

    /** The discovery directory {@link #awaitAgent} watches. */
    public Path discoveryDir() {
        return discoveryDir;
    }

    /**
     * The owner-only options file of a JAVAAGENT launch, or null. It holds the launch's token and nonce, so it lives
     * only as long as needed (finding S5): it is deleted once {@link #awaitAgent} has verified the agent (a verified
     * ping with the launch nonce proves the agent has read it), at the latest by {@link #close()}. A JVM that the
     * target starts later with the same agent flags then finds no options file and starts no agent.
     */
    public Path optionsFile() {
        return optionsFile;
    }

    /** The nonce generated for a JAVAAGENT launch, or null for a launch without agent flags (EMBEDDED). */
    public String agentNonce() {
        return agentNonce;
    }

    /** The log file given to {@code inheritOutputTo}, or null. */
    public Path logFile() {
        return logFile;
    }

    /**
     * Waits for the agent in this process: the discovery file for {@link #pid()} in {@link #discoveryDir()} and a
     * verified {@code /ping} ({@link Discovery#awaitPid}). For a JAVAAGENT launch only the port, start time and
     * session id come from the discovery file: the client authenticates with the token this launcher generated (the
     * token file the discovery file names is never read, finding S6) and the first ping must report the launch's
     * nonce; then the options file is deleted (finding S5).
     *
     * @throws AgentLostException if the process exits first, the timeout expires, or the agent found is not the one
     *     this launch configured; the message includes the exit code and the target's {@code [skillroy]} log lines
     *     when they are known
     */
    public Endpoint awaitAgent(Duration timeout) throws AgentLostException {
        Endpoint endpoint;
        try {
            endpoint = agentNonce != null && agentToken != null
                    ? Discovery.awaitPid(pid(), discoveryDir, timeout, agentToken, agentNonce)
                    : Discovery.awaitPid(pid(), discoveryDir, timeout);
        } catch (AgentLostException e) {
            throw new AgentLostException(e.getMessage() + diagnostics(), e);
        }
        if (agentNonce != null && !agentNonce.equals(endpoint.nonce())) {
            throw new AgentLostException("the agent in pid " + pid() + " has nonce " + endpoint.nonce()
                    + ", not the one this launch generated: it was not started from this launch's options file"
                    + diagnostics());
        }
        if (agentNonce != null) {
            SecureFiles.deleteQuietly(optionsFile);
        }
        return endpoint;
    }

    /**
     * Ends the process and all its descendants: a normal termination request first (so the agent can delete its
     * discovery file), then, after 5 s, a forced one; waits at most 5 s more. Deletes the options file if
     * {@link #awaitAgent} has not already. Idempotent; never throws.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            List<ProcessHandle> descendants = new ArrayList<>();
            try {
                process.descendants().forEach(descendants::add);
            } catch (RuntimeException ignored) {
                // Without the list only the process itself is ended.
            }
            process.destroy();
            descendants.forEach(LaunchedTarget::destroyQuietly);
            if (!awaitExit(descendants, GRACEFUL_EXIT)) {
                process.destroyForcibly();
                descendants.forEach(h -> {
                    try {
                        h.destroyForcibly();
                    } catch (RuntimeException ignored) {
                        // Already gone, or not ours to end.
                    }
                });
                awaitExit(descendants, FORCED_EXIT);
            }
        } catch (RuntimeException ignored) {
            // close() never throws.
        } finally {
            SecureFiles.deleteQuietly(optionsFile);
        }
    }

    @Override
    public String toString() {
        return "LaunchedTarget[pid=" + pid() + ", alive=" + process.isAlive() + "]";
    }

    private static void destroyQuietly(ProcessHandle handle) {
        try {
            handle.destroy();
        } catch (RuntimeException ignored) {
            // Already gone, or not ours to end.
        }
    }

    private boolean awaitExit(List<ProcessHandle> descendants, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (!process.waitFor(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return false;
            }
            for (ProcessHandle handle : descendants) {
                if (!handle.isAlive()) {
                    continue;
                }
                try {
                    handle.onExit().get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    return false;
                } catch (ExecutionException ignored) {
                    // Treated as exited; checked again below.
                }
                if (handle.isAlive()) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Exit code (if the process ended) and the last {@code [skillroy]} lines of the log (if one was kept). */
    private String diagnostics() {
        StringBuilder text = new StringBuilder();
        try {
            if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                text.append(" (exit code ").append(process.exitValue()).append(')');
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<String> lines = agentLogLines();
        if (!lines.isEmpty()) {
            text.append("; target log: ").append(String.join(" | ", lines));
        }
        return text.toString();
    }

    private List<String> agentLogLines() {
        List<String> found = new ArrayList<>();
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return found;
        }
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = file.length();
            long start = Math.max(0L, length - LOG_TAIL_BYTES);
            byte[] bytes = new byte[(int) (length - start)];
            file.seek(start);
            file.readFully(bytes);
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                if (line.contains("[skillroy]")) {
                    found.add(line.strip());
                }
            }
        } catch (IOException | RuntimeException e) {
            return found;
        }
        return found.size() <= LOG_LINES ? found : found.subList(found.size() - LOG_LINES, found.size());
    }
}
