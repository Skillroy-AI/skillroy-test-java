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

import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.Protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds running agents through the discovery files they write ({@code <dir>/skillroy-<pid>-<startMs>.json}, contract
 * §2.5), so that a controller can connect to a target it did not load (JAVAAGENT and EMBEDDED modes, or a second
 * controller). Only live entries are reported: the pid must belong to a running process and, when the operating
 * system reports the process start time, that time must lie within 60 s of the JVM start time in the file (the agent
 * reports the JVM's own start time, which is slightly later than the process start). A start time of 0 or less is
 * never a live entry (no agent writes it).
 *
 * <p>A discovery directory is trusted only when it passes the check of contract §2.5 (see
 * {@link SecureFiles#untrustedDirectoryReason(Path)}): on POSIX file systems it belongs to the current user and is not
 * writable by group or others, and a symbolic link naming it belongs to the current user too; on file systems with
 * ACLs the ACL rule of findings S7/S15 applies; elsewhere it is never trusted. A directory that fails the check is
 * ignored as a whole: it lists no entries.
 *
 * <p>The default directory (findings S8/S19) is {@code ${java.io.tmpdir}/skillroy-<user>}, or
 * {@code <user.home>/.skillroy/run} when the former is occupied by another user; when no directory is named, every
 * trusted candidate is read and the results are merged, so an agent that fell back is found even after the squatter
 * went away.
 *
 * <p>Token files (findings S6/S28): the token of an entry is read only from {@code skillroy-<pid>-<startMs>.token}
 * <em>inside the directory being read</em>, through {@link SecureFiles#readTokenIn}; a {@code tokenFile} anywhere
 * else, or a symbolic link, gives a null token. An agent started with a custom {@code tokenFile} elsewhere is reached
 * with {@code --port} and {@code --token-file}.
 *
 * <p>Stale files (amendment A23, controller part): {@link #list} and {@link #entries} delete the discovery and token
 * files of a trusted directory whose process is gone (no live process with that pid, or one whose start time is more
 * than 60 s from the file's), unless the caller switches that off. Files that are not discovery files, cannot be
 * parsed, are owned by another user, or whose content contradicts their name are skipped silently and never deleted.
 *
 * <p>Stateless; every method is safe to call from any thread. {@link #awaitPid} blocks the calling thread for at most
 * its timeout (plus one bounded handshake).
 */
public final class Discovery {

    /** Tolerance between the OS process start time and the JVM start time the agent reports. */
    static final long START_TIME_TOLERANCE_MS = 60_000;

    private static final Pattern FILE_NAME = Pattern.compile("skillroy-(\\d{1,19})-(\\d{1,19})\\.json");
    private static final Pattern AGENT_FILE = Pattern.compile("skillroy-(\\d{1,19})-(\\d{1,19})\\.(json|token)");
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private static final long POLL_MS = 100;
    private static final Duration MAX_WAIT = Duration.ofDays(1);
    private static final String CUSTOM_TOKEN_FILE_HINT = "an agent whose tokenFile lies outside the discovery "
            + "directory is reached with --port and --token-file";

    /**
     * One live discovery entry with the fields that {@link Endpoint} does not carry.
     *
     * @param endpoint loopback endpoint, token read from the token file (null if unreadable), session id from the
     *     file (null if it has none)
     * @param mainCommand the target's {@code sun.java.command} as the agent reported it, may be null
     * @param protocol the protocol version the agent reported, may be null
     * @param file the discovery file
     */
    public record Entry(Endpoint endpoint, String mainCommand, String protocol, Path file) {
    }

    private Discovery() {
    }

    /**
     * The default discovery directory for a target of the same OS user (contract §2.5, findings S8/S19): candidate
     * (a) {@code ${java.io.tmpdir}/skillroy-<user>}, where {@code <user>} is the {@code user.name} system property
     * reduced to the characters {@code [A-Za-z0-9._-]} (every other character is dropped; a missing property gives
     * {@code skillroy-}); or candidate (b) {@code <user.home>/.skillroy/run} exactly when (a) exists (looked at
     * without following links) and is not owned by the current user, and (b) is a candidate at all ({@code user.home}
     * set, not {@code ?}, absolute and an existing directory). The agent applies the same rule, so both name the same
     * directory without being told. The directory returned may not exist yet.
     */
    public static Path defaultDir() {
        return defaultDir(System.getProperty("java.io.tmpdir"), System.getProperty("user.name"),
                System.getProperty("user.home"), SecureFiles.currentUser());
    }

    /** Candidate (a), {@code <tmpDir>/skillroy-<sanitised user>}, for the given temp directory and user name. */
    static Path defaultDir(String tmpDir, String userName) {
        return Path.of(tmpDir, "skillroy-" + sanitiseUserName(userName));
    }

    /** The S8/S19 rule over explicit inputs (tests pass another current user to simulate a squatter). */
    static Path defaultDir(String tmpDir, String userName, String userHome, UserPrincipal me) {
        Path a = defaultDir(tmpDir, userName);
        Path b = homeCandidate(userHome);
        return b != null && occupiedByAnotherUser(a, me) ? b : a;
    }

    /**
     * Candidate (b), {@code <userHome>/.skillroy/run}, or null when {@code userHome} is null, empty, {@code "?"}, not
     * absolute or not an existing directory.
     */
    static Path homeCandidate(String userHome) {
        if (userHome == null || userHome.isEmpty() || "?".equals(userHome)) {
            return null;
        }
        try {
            Path home = Path.of(userHome);
            if (!home.isAbsolute() || !Files.isDirectory(home)) {
                return null;
            }
            return home.resolve(".skillroy").resolve("run");
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** True when {@code dir} exists (not following links) and its owner is not {@code me}. */
    static boolean occupiedByAnotherUser(Path dir, UserPrincipal me) {
        try {
            UserPrincipal owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
            return !SecureFiles.isUser(owner, me);
        } catch (IOException | RuntimeException e) {
            // Missing (the common case), or an owner that cannot be read: no reason to fall back.
            return false;
        }
    }

    /** Both default candidates, (a) first; (b) only where it is a candidate. */
    static List<Path> defaultCandidates() {
        List<Path> candidates = new ArrayList<>(2);
        candidates.add(defaultDir(System.getProperty("java.io.tmpdir"), System.getProperty("user.name")));
        Path b = homeCandidate(System.getProperty("user.home"));
        if (b != null) {
            candidates.add(b);
        }
        return candidates;
    }

    /**
     * Resolves the default directory for writing an options file into it (launcher and attach): {@link #defaultDir()},
     * created owner-only if missing and checked; when creating (a) lost a race to another user, (b) instead.
     *
     * @return the absolute, checked directory
     * @throws IOException if no candidate can be used; the message says why
     */
    static Path ensureDefaultDir() throws IOException {
        Path chosen = defaultDir().toAbsolutePath().normalize();
        try {
            return SecureFiles.ensureOwnerOnlyDirectory(chosen);
        } catch (IOException e) {
            Path b = homeCandidate(System.getProperty("user.home"));
            if (b != null && !chosen.equals(b.toAbsolutePath().normalize())
                    && occupiedByAnotherUser(chosen, SecureFiles.currentUser())) {
                return SecureFiles.ensureOwnerOnlyDirectory(b.toAbsolutePath().normalize());
            }
            throw e;
        }
    }

    /**
     * {@code userName} reduced to {@code [A-Za-z0-9._-]}: every other character (one UTF-16 unit at a time) is
     * dropped; null gives {@code ""}. Must stay identical to the agent's rule, or a controller would look for an
     * agent's files in another directory. The result cannot climb out of the temp directory, because it always
     * follows the {@code skillroy-} prefix in one path segment.
     */
    static String sanitiseUserName(String userName) {
        if (userName == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(userName.length());
        for (int i = 0; i < userName.length(); i++) {
            char c = userName.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Endpoints of all live agents in {@code discoveryDir}, sorted by pid and start time; deletes stale files (see
     * the class comment).
     *
     * @param discoveryDir null means every default candidate (see {@link #defaultDir()}); a missing directory, and
     *     one that cannot be trusted, yield an empty list
     */
    public static List<Endpoint> list(Path discoveryDir) {
        return list(discoveryDir, true);
    }

    /**
     * {@link #list(Path)}, deleting stale discovery and token files only when {@code removeStale} is true.
     *
     * @param discoveryDir null means every default candidate
     */
    public static List<Endpoint> list(Path discoveryDir, boolean removeStale) {
        return entries(discoveryDir, removeStale).stream().map(Entry::endpoint).toList();
    }

    /**
     * Like {@link #list} but with the main command and protocol of each entry (used to recognise a restarted target:
     * a new pid with the same main command). Deletes stale files (see the class comment).
     *
     * @param discoveryDir null means every default candidate
     */
    public static List<Entry> entries(Path discoveryDir) {
        return entries(discoveryDir, true);
    }

    /**
     * {@link #entries(Path)}, deleting stale discovery and token files only when {@code removeStale} is true.
     *
     * @param discoveryDir null means every default candidate
     */
    public static List<Entry> entries(Path discoveryDir, boolean removeStale) {
        return scan(discoveryDir, "skillroy-*.json", removeStale);
    }

    /**
     * The live agent of {@code pid}, if its discovery file is present (the most recent one if, unusually, there are
     * several). Deletes nothing.
     *
     * @param discoveryDir null means every default candidate
     */
    public static Optional<Endpoint> forPid(long pid, Path discoveryDir) {
        return scan(discoveryDir, "skillroy-" + pid + "-*.json", false).stream()
                .map(Entry::endpoint)
                .filter(e -> e.pid() == pid)
                .max(Comparator.comparingLong(Endpoint::startTimeMs));
    }

    /** The directories a scan of {@code discoveryDir} reads. */
    private static List<Path> dirs(Path discoveryDir) {
        return discoveryDir == null ? defaultCandidates() : List.of(discoveryDir);
    }

    /**
     * Live entries among the files of the directories matching {@code glob}, sorted by pid and start time; none from
     * a directory that is missing or cannot be trusted.
     */
    private static List<Entry> scan(Path discoveryDir, String glob, boolean removeStale) {
        List<Entry> entries = new ArrayList<>();
        for (Path dir : dirs(discoveryDir)) {
            if (SecureFiles.untrustedDirectoryReason(dir) != null) {
                continue;
            }
            if (removeStale) {
                removeStale(dir);
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
                for (Path file : files) {
                    Entry entry = read(file);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            } catch (IOException | DirectoryIteratorException e) {
                // A directory that vanishes or cannot be listed has no (further) live entries.
            }
        }
        entries.sort(Comparator.comparingLong((Entry e) -> e.endpoint().pid())
                .thenComparingLong(e -> e.endpoint().startTimeMs()));
        return List.copyOf(entries);
    }

    /**
     * Amendment A23, controller part: deletes {@code skillroy-<pid>-<startMs>.json} and {@code .token} files in the
     * trusted directory {@code dir} whose process is gone ({@link #isLive} false; a start time of 0 is left alone).
     * Only regular files and symbolic links are deleted (a link itself, never its target). Failures are ignored.
     */
    static void removeStale(Path dir) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "skillroy-*")) {
            for (Path file : files) {
                Matcher name = AGENT_FILE.matcher(file.getFileName().toString());
                if (!name.matches()) {
                    continue;
                }
                long pid;
                long startTimeMs;
                try {
                    pid = Long.parseLong(name.group(1));
                    startTimeMs = Long.parseLong(name.group(2));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (startTimeMs <= 0 || isLive(pid, startTimeMs)) {
                    continue;
                }
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if ((attributes.isRegularFile() || attributes.isSymbolicLink())
                            && SecureFiles.ownedByCurrentUser(file)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Someone else's business, or already gone.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Nothing to clean, or the directory cannot be listed.
        }
    }

    /**
     * Waits until the agent in {@code pid} has published a live discovery entry with a readable token and answers a
     * verified {@code /ping}. Polls every 100 ms; the wait between polls ends early when the process exits.
     *
     * @param discoveryDir null means every default candidate
     * @return the verified endpoint (token, nonce, pid, start time and load mode from the discovery file; the session
     *     id the ping verified)
     * @param timeout how long to wait; longer than one day counts as one day
     * @throws AgentLostException if the process is not running or exits first ("not running"), or if the timeout
     *     expires; the timeout message names the last problem seen (for example a discovery directory that is
     *     ignored because it is writable by group or others) and, when {@code pid} is this JVM (an embedded
     *     agent), its {@code skillroy.agent.error} system property. Another JVM's property could only be read by
     *     attaching to it, which this class never does: the target's {@code [skillroy]} line on stderr says why
     *     its agent did not start
     */
    public static Endpoint awaitPid(long pid, Path discoveryDir, Duration timeout) throws AgentLostException {
        return awaitPid(pid, discoveryDir, timeout, null, null);
    }

    /**
     * {@link #awaitPid(long, Path, Duration)} for an agent whose token and nonce the caller generated (a JAVAAGENT
     * launch, findings S5/S6): only the port, pid, start time, load mode and session id are taken from the discovery
     * file, the token file it names is never read, and the nonce is checked inside the first ping.
     *
     * @param token the token the caller gave the agent; null means the token file of the entry
     * @param nonce the nonce the caller gave the agent; null means the entry's
     */
    static Endpoint awaitPid(long pid, Path discoveryDir, Duration timeout, String token, String nonce)
            throws AgentLostException {
        Objects.requireNonNull(timeout, "timeout");
        List<Path> dirs = dirs(discoveryDir);
        String where = dirs.stream().map(Path::toString).collect(Collectors.joining(" or "));
        Duration wait = timeout.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : timeout;
        long deadline = System.nanoTime() + wait.toNanos();
        String lastProblem = null;
        while (true) {
            Optional<ProcessHandle> process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
            if (process.isEmpty()) {
                throw new AgentLostException("process " + pid + " is not running: it exited before its agent answered"
                        + (lastProblem == null ? "" : " (last problem: " + lastProblem + ")"));
            }
            String dirProblem = null;
            for (Path dir : dirs) {
                String reason = SecureFiles.untrustedDirectoryReason(dir);
                if (reason != null && Files.exists(dir)) {
                    dirProblem = "the discovery directory " + dir + " is ignored because " + reason
                            + " (it must belong to the current user and be writable by its owner only; the agent "
                            + "refuses it too)";
                    break;
                }
            }
            Optional<Endpoint> found = forPid(pid, discoveryDir);
            if (found.isEmpty() && dirProblem != null) {
                lastProblem = dirProblem;
            } else if (found.isEmpty()) {
                lastProblem = "no live discovery file for pid " + pid + " in " + where;
            } else if (token == null && found.get().token() == null) {
                lastProblem = "the discovery file names a token file that cannot be read (" + CUSTOM_TOKEN_FILE_HINT
                        + ")";
            } else {
                Endpoint candidate = found.get();
                if (token != null) {
                    candidate = new Endpoint(candidate.host(), candidate.port(), token,
                            nonce != null ? nonce : candidate.nonce(), candidate.pid(), candidate.startTimeMs(),
                            candidate.loadMode(), candidate.sessionId());
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                Duration pingTimeout = Duration.ofMillis(Math.max(500L, Math.min(10_000L, remainingMs)));
                try (AgentClient client = AgentClient.connect(candidate,
                        ClientSettings.DEFAULTS.withPingTimeout(pingTimeout))) {
                    return candidate.withSessionId(client.sessionId());
                } catch (AgentLostException e) {
                    lastProblem = "/ping failed: " + e.getMessage();
                }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                StringBuilder message = new StringBuilder("no agent answered for pid ").append(pid).append(" in ")
                        .append(where).append(" within ").append(wait.toMillis()).append(" ms");
                if (lastProblem != null) {
                    message.append(" (").append(lastProblem).append(')');
                }
                String agentError = pid == ProcessHandle.current().pid()
                        ? System.getProperty(Protocol.PROP_PREFIX + "error") : null;
                if (agentError != null) {
                    message.append("; skillroy.agent.error: ").append(agentError);
                }
                throw new AgentLostException(message.toString());
            }
            try {
                awaitExitOrPoll(process.get(), Math.min(TimeUnit.MILLISECONDS.toNanos(POLL_MS), remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentLostException("interrupted while waiting for the agent in pid " + pid, e);
            }
        }
    }

    /**
     * Waits up to {@code nanos} for {@code process} to exit: the poll interval of {@link #awaitPid}, cut short by
     * the exit so that a dying target is reported at once.
     */
    static void awaitExitOrPoll(ProcessHandle process, long nanos) throws InterruptedException {
        if (process.equals(ProcessHandle.current())) {
            // onExit() is not allowed for the current process (an embedded agent in the controller's own JVM).
            new CountDownLatch(1).await(nanos, TimeUnit.NANOSECONDS);
            return;
        }
        try {
            process.onExit().get(nanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException expected) {
            // Still running.
        } catch (ExecutionException ignored) {
            // Exit detection failed; the caller checks the process directly.
        }
    }

    /**
     * True when a process with {@code pid} is running and, if the OS reports its start time and
     * {@code startTimeMs > 0}, that start time is within 60 s of {@code startTimeMs}.
     */
    static boolean isLive(long pid, long startTimeMs) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        if (startTimeMs <= 0) {
            return true;
        }
        Optional<Instant> start = handle.get().info().startInstant();
        return start.isEmpty() || Math.abs(start.get().toEpochMilli() - startTimeMs) <= START_TIME_TOLERANCE_MS;
    }

    /** Parses one discovery file; null if it is not a live, well-formed entry of the current user. */
    static Entry read(Path file) {
        Matcher name = FILE_NAME.matcher(file.getFileName().toString());
        if (!name.matches()) {
            return null;
        }
        try {
            long pid = Long.parseLong(name.group(1));
            long startTimeMs = Long.parseLong(name.group(2));
            if (startTimeMs <= 0 || !isLive(pid, startTimeMs)) {
                return null;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES
                    || !SecureFiles.ownedByCurrentUser(file)) {
                return null;
            }
            JsonView json = JsonView.of(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)));
            if (json.requireNum("pid") != pid || json.requireNum("startTimeMs") != startTimeMs) {
                return null;
            }
            long port = json.requireNum("port");
            if (port < 1 || port > 65535) {
                return null;
            }
            String tokenFile = json.str("tokenFile");
            String token = null;
            if (tokenFile != null && !tokenFile.isEmpty()) {
                String expected = "skillroy-" + name.group(1) + "-" + name.group(2) + ".token";
                token = SecureFiles.readTokenIn(file.getParent(), Path.of(tokenFile), expected::equals);
            }
            String sessionId = json.str(Protocol.KEY_SESSION_ID);
            Endpoint endpoint = new Endpoint("127.0.0.1", (int) port, token, json.str("nonce"), pid, startTimeMs,
                    parseLoadMode(json.str("loadMode")), Auth.isValidSessionId(sessionId) ? sessionId : null);
            return new Entry(endpoint, json.str("mainCommand"), json.str("protocol"), file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The load mode named by {@code text}, or null when it is missing or unknown. */
    static LoadMode parseLoadMode(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LoadMode.valueOf(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
