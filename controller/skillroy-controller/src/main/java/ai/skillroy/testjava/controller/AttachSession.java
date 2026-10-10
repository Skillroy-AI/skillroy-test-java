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

import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.Protocol;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * ATTACH mode: loads the agent into a JVM that is already running, through the JDK attach API, and proves the result
 * with a handshake. The attach reply itself is never trusted (a Java 8 target reports success as an error, see
 * {@link AttachReply}); only the discovery properties read back through the attach channel plus a verified
 * {@code /ping} count.
 *
 * <p>Sequence of {@link #attach}: write an owner-only options file ({@code token}, {@code nonce}, {@code enabled=true},
 * the extra options) into the discovery directory (created owner-only if missing; an existing one must pass the
 * trust check of contract amendment A3, see {@link Discovery}); attach (a target that started less than 5 s ago is
 * first given that long, because the attach signal ends a JVM that is still starting); read the target's system
 * properties (its Java version, and any agent already published); {@code loadAgent(<bootstrap jar>, "t=<file>")}
 * (the whole argument must stay under 1024 bytes because Java 8 targets truncate longer ones); interpret the reply;
 * poll the target's system properties until {@code skillroy.agent.port} is published with our nonce, or, if an
 * agent was already running, its existing values, which the idempotent {@code agentmain} re-publishes, or
 * {@code skillroy.agent.error} appears; detach (always); connect and ping. The options file is deleted at the end
 * whatever happened.
 *
 * <p>Re-attach (contract amendment A15): an agent that is already running keeps its token and nonce and ignores the
 * ones in the new options file. The endpoint then carries the nonce read back from {@code skillroy.agent.nonce} and
 * the token read from the file named by {@code skillroy.agent.tokenFile}; the {@code /ping} handshake verifies both.
 * That token file is read only when it is {@code skillroy-<pid>-<startMs>.token} of the attached pid directly inside
 * this session's discovery directory (findings S6/S28); an agent whose token file lies elsewhere needs the
 * {@code token} option.
 *
 * <p>Discovery directory: the {@code discoveryDir} option, else {@link Discovery#defaultDir()} (with the fall-back of
 * findings S8/S19); either way the resolved absolute directory is passed to the agent.
 *
 * <p>Unlisted pids (finding S18): attaching signals the target, which ends a process that is not a JVM. A pid that
 * {@code VirtualMachine.list()} does not show is attached to only when the OS reports its executable and that file's
 * name is exactly {@code java} or {@code javaw} (optionally {@code .exe}, any case); otherwise the attach is refused
 * before any signal is sent. The controller-side system property {@value #ALLOW_UNLISTED}{@code =true} overrides this
 * for JVMs started with {@code -XX:-UsePerfData} or behind a custom launcher.
 *
 * <p>Only this class touches {@code com.sun.tools.attach}, and only inside a nested class, so that the rest of the
 * controller (and this class's other methods) load in a runtime without the {@code jdk.attach} module; there
 * {@link #attach} fails with {@link AttachFailedException} and {@link #listJvms()} with
 * {@link IllegalStateException}.
 *
 * <p>Thread-safe. Attach work runs on daemon threads named {@code skillroy-attach-<n>} so that the caller's wait is
 * bounded (120 s for an attach) even if the target never answers.
 */
public final class AttachSession {

    /** JDK 8 targets cap each attach argument at 1024 bytes including the terminator. */
    static final int MAX_AGENT_ARGUMENT_BYTES = 1024;

    private static final String PORT = Protocol.PROP_PREFIX + "port";
    private static final String NONCE = Protocol.PROP_PREFIX + "nonce";
    private static final String TOKEN_FILE = Protocol.PROP_PREFIX + "tokenFile";
    private static final String LOAD_MODE = Protocol.PROP_PREFIX + "loadMode";
    private static final String ERROR = Protocol.PROP_PREFIX + "error";

    /** Controller-side system property that allows attaching to a pid the attach API does not list (S18). */
    public static final String ALLOW_UNLISTED = "skillroy.attach.allowUnlisted";

    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);
    /**
     * A JVM younger than this is not attached to yet. Attaching sends the target a signal (SIGQUIT on Unix) that a
     * JVM handles only once its start-up has installed the handler; before that the signal ends the process (seen
     * on Java 8 targets for the first few hundred milliseconds).
     */
    static final Duration MIN_TARGET_AGE = Duration.ofSeconds(5);
    private static final long POLL_MS = 100;

    private static final System.Logger LOG = System.getLogger(AttachSession.class.getName());
    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "skillroy-attach-" + THREAD_IDS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /**
     * What the target published after the load, read through the attach channel.
     *
     * @param port {@code skillroy.agent.port}
     * @param nonce {@code skillroy.agent.nonce}
     * @param tokenFile {@code skillroy.agent.tokenFile}, may be null
     * @param loadMode {@code skillroy.agent.loadMode}, may be null
     * @param fresh true when this load started the agent (it published our nonce), false for an agent that was
     *     already running (re-attach: its own nonce and token file) or one {@link #decide} could not place
     */
    record Published(String port, String nonce, String tokenFile, String loadMode, boolean fresh) {
    }

    private AttachSession() {
    }

    /**
     * Loads the agent from {@code home} into the running JVM {@code pid} and verifies it (see the class comment).
     *
     * @param extraOptions further agent options (contract §2.4), written into the options file; may be null. A
     *     {@code token} or {@code nonce} given here is used instead of a generated one; {@code enabled} is always
     *     {@code true}; {@code discoveryDir} (default {@link Discovery#defaultDir()}) also decides where the options
     *     file is written and is passed on as an absolute path
     * @return the verified endpoint: token, the agent's nonce, pid, the JVM start time and load mode from
     *     {@code /ping}; after a re-attach the running agent's own token and nonce
     * @throws AttachFailedException if the discovery directory cannot be used (another owner, writable by group or
     *     others, a symbolic link), or attaching, loading, publishing or the handshake fails, or does not finish
     *     within 120 s; the message names the stage and, where available, the agent's {@code skillroy.agent.error}
     */
    public static Endpoint attach(long pid, AgentHome home, Map<String, String> extraOptions)
            throws AttachFailedException {
        Objects.requireNonNull(home, "home");
        if (pid <= 0) {
            throw new AttachFailedException("invalid pid: " + pid);
        }
        if (pid == ProcessHandle.current().pid()) {
            throw new AttachFailedException("cannot attach to the controller's own JVM (pid " + pid + ")");
        }
        requireAttachModule();
        Map<String, String> options = new LinkedHashMap<>();
        if (extraOptions != null) {
            extraOptions.forEach((k, v) -> {
                if (k != null && v != null) {
                    options.put(k, v);
                }
            });
        }
        boolean tokenGiven = options.containsKey("token");
        String token = options.computeIfAbsent("token", k -> SecureFiles.newToken());
        if (!SecureFiles.isValidToken(token)) {
            throw new AttachFailedException("the token option is not a usable token (16 to 1024 letters, digits and "
                    + "._~+/=-; omit it to let the controller generate one)");
        }
        String nonce = options.computeIfAbsent("nonce", k -> SecureFiles.newNonce());
        options.put("enabled", "true");
        Path dir;
        try {
            dir = options.containsKey("discoveryDir") ? Path.of(options.get("discoveryDir")).toAbsolutePath()
                    .normalize() : Discovery.ensureDefaultDir();
        } catch (InvalidPathException e) {
            throw new AttachFailedException("invalid discoveryDir option: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new AttachFailedException("cannot write the agent options file: " + e.getMessage(), e);
        }
        // Always passed on, absolute: the target would resolve a relative directory against its own working
        // directory, and must not compute the default on its own (S8/S19: both sides use the directory resolved here).
        options.put("discoveryDir", dir.toString());
        Path optionsFile;
        try {
            optionsFile = SecureFiles.writeOptionsFile(dir, options);
        } catch (IOException | RuntimeException e) {
            throw new AttachFailedException("cannot write the agent options file in " + dir + ": " + e.getMessage(),
                    e);
        }
        try {
            String jar = home.bootstrapJar().toString();
            String argument = "t=" + optionsFile;
            checkAgentArgument(jar, argument);
            Published published = bounded(() -> Api.loadAndAwaitPublish(pid, jar, argument, nonce), ATTACH_TIMEOUT,
                    "attaching to pid " + pid);
            return handshake(pid, published, tokenFor(pid, published, token, tokenGiven, dir));
        } finally {
            SecureFiles.deleteQuietly(optionsFile);
        }
    }

    /**
     * The JVMs of the current OS user that the attach API can see, sorted by pid.
     *
     * @throws IllegalStateException if the {@code jdk.attach} module is not available
     */
    public static List<JvmInfo> listJvms() {
        if (!attachModulePresent()) {
            throw new IllegalStateException(noAttachModuleMessage());
        }
        try {
            return Api.list();
        } catch (LinkageError e) {
            throw new IllegalStateException(noAttachModuleMessage(), e);
        }
    }

    /**
     * Throws unless {@code <jar>=<argument>} fits the 1024-byte attach argument limit of Java 8 targets (contract
     * §2.4: anything long goes into the options file).
     */
    static void checkAgentArgument(String jar, String argument) throws AttachFailedException {
        String problem = agentArgumentProblem(jar, argument);
        if (problem != null) {
            throw new AttachFailedException(problem);
        }
        int bytes = (jar + "=" + argument).getBytes(StandardCharsets.UTF_8).length;
        if (bytes >= MAX_AGENT_ARGUMENT_BYTES) {
            throw new AttachFailedException("the loadAgent argument is " + bytes + " bytes; it must stay under "
                    + MAX_AGENT_ARGUMENT_BYTES + " (move the agent home or the discovery directory to a shorter "
                    + "path)");
        }
    }

    /**
     * Why {@code <jar>=<argument>} cannot be handed to a JVM (as {@code -javaagent:} or through attach), or null:
     * the JVM cuts the jar path at its first {@code '='}, and the agent splits its argument string at {@code ';'}.
     *
     * @param argument one {@code key=value} pair whose value is a path, with further pairs added by the caller
     */
    static String agentArgumentProblem(String jar, String argument) {
        if (jar.indexOf('=') >= 0) {
            return "the agent home path contains '=', which a JVM cannot take in an agent jar path: " + jar;
        }
        if (argument.indexOf(';') >= 0) {
            return "the options file path contains ';', which the agent argument string cannot carry: " + argument;
        }
        return null;
    }

    /** True when the {@code jdk.attach} module is part of this runtime. */
    static boolean attachModulePresent() {
        return ModuleLayer.boot().findModule("jdk.attach").isPresent();
    }

    private static void requireAttachModule() throws AttachFailedException {
        if (!attachModulePresent()) {
            throw new AttachFailedException(noAttachModuleMessage());
        }
    }

    private static String noAttachModuleMessage() {
        return "the jdk.attach module is not available in this Java runtime (" + System.getProperty("java.home")
                + "); run the controller on a full JDK, or load the agent at launch (JAVAAGENT or EMBEDDED)";
    }

    /**
     * Decides from the target's discovery system properties, read before the load and now, whether the agent has
     * published and whose agent it is. Pure: the attach worker calls it after {@code loadAgent} on every poll.
     *
     * <ul>
     *   <li>{@code port} published with our nonce, and not merely left over from before the load: this load started
     *       the agent ({@code fresh}).</li>
     *   <li>{@code port}, {@code nonce} and {@code tokenFile} published and port and nonce unchanged since before the
     *       load: an agent was already running and its idempotent {@code agentmain} re-published its own values. A
     *       re-attach keeps the running agent's token and nonce (contract amendment A15), so they are read back from
     *       here, not taken from our options file (not {@code fresh}).</li>
     *   <li>{@code skillroy.agent.error} set or changed since before the load: the agent did not start.</li>
     *   <li>Deadline passed with a port and nonce published that fit neither case: returned as not {@code fresh};
     *       the token file and the {@code /ping} handshake decide. Deadline passed with nothing usable: failure.</li>
     *   <li>Otherwise null: keep polling.</li>
     * </ul>
     *
     * @param before the properties read before {@code loadAgent}
     * @param now the properties read now
     * @param ourNonce the nonce in this load's options file
     * @param expired true once the publish deadline has passed
     * @return what was published, or null to keep polling
     * @throws AttachFailedException if the agent reported a start error, or nothing usable appeared in time
     */
    static Published decide(long pid, Properties before, Properties now, String ourNonce, boolean expired)
            throws AttachFailedException {
        String port = now.getProperty(PORT);
        String nonce = now.getProperty(NONCE);
        String tokenFile = now.getProperty(TOKEN_FILE);
        String loadMode = now.getProperty(LOAD_MODE);
        String portBefore = before.getProperty(PORT);
        String nonceBefore = before.getProperty(NONCE);
        boolean unchanged = port != null && nonce != null && port.equals(portBefore) && nonce.equals(nonceBefore);
        if (port != null && ourNonce.equals(nonce) && !unchanged) {
            return new Published(port, nonce, tokenFile, loadMode, true);
        }
        if (unchanged && tokenFile != null) {
            return new Published(port, nonce, tokenFile, loadMode, false);
        }
        String error = now.getProperty(ERROR);
        if (error != null && !error.equals(before.getProperty(ERROR))) {
            throw new AttachFailedException("the agent did not start in pid " + pid + ": " + ERROR + ": " + error);
        }
        if (!expired) {
            return null;
        }
        if (port != null && nonce != null) {
            return new Published(port, nonce, tokenFile, loadMode, false);
        }
        throw new AttachFailedException("the agent was loaded into pid " + pid + " but did not publish " + PORT
                + " within " + PUBLISH_TIMEOUT.toSeconds() + " s" + (error == null ? "" : "; " + ERROR + ": " + error));
    }

    /**
     * The token for what the target published. A fresh load: our token, which the agent took from our options file.
     * An agent that was already running keeps its own token (contract amendment A15): the token in the token file it
     * publishes ({@code skillroy.agent.tokenFile}), read only through the confined reader (findings S6/S28): it must
     * be {@code skillroy-<pid>-<startMs>.token} of this pid directly inside {@code dir}, a regular owner-only file of
     * the current user. Failing that, a token the caller passed explicitly is tried, because it may be the running
     * agent's.
     *
     * @param tokenGiven true when the caller supplied {@code ourToken} as the {@code token} option
     * @param dir this session's (trusted) discovery directory
     * @throws AttachFailedException for an agent that was already running whose token cannot be found
     */
    static String tokenFor(long pid, Published published, String ourToken, boolean tokenGiven, Path dir)
            throws AttachFailedException {
        if (published.fresh()) {
            return ourToken;
        }
        String fromFile = null;
        if (published.tokenFile() != null) {
            Pattern name = Pattern.compile("skillroy-" + pid + "-\\d{1,19}\\.token");
            try {
                fromFile = SecureFiles.readTokenIn(dir, Path.of(published.tokenFile()),
                        n -> name.matcher(n).matches());
            } catch (InvalidPathException ignored) {
                // Not a usable path: no token from it.
            }
        }
        if (fromFile != null) {
            return fromFile;
        }
        if (tokenGiven) {
            return ourToken;
        }
        throw new AttachFailedException("the agent in pid " + pid + " (port " + published.port()
                + ") was already running and keeps its own token on a re-attach, but "
                + (published.tokenFile() == null ? "it publishes no " + TOKEN_FILE
                        : "its token file " + published.tokenFile() + " is not skillroy-" + pid + "-<startMs>.token "
                        + "directly in " + dir + ", or is missing, unreadable, a link, not owner-only, not owned by "
                        + "the current user, or holds no valid token")
                + "; pass that agent's token as the token option");
    }

    /**
     * Finding S18: null when a pid that the attach API does not list may be attached to, else why not. Allowed only
     * with the override, or when {@code command} is known and its file name is exactly {@code java} or
     * {@code javaw}, optionally with {@code .exe}, in any case. Pure.
     */
    static String unlistedRefusal(long pid, Optional<String> command, boolean allowUnlisted) {
        if (allowUnlisted) {
            return null;
        }
        if (command.isEmpty()) {
            return "pid " + pid + " is not a JVM the attach API lists and its executable cannot be read; not "
                    + "attaching, because the attach signal would end a process that is not a JVM (set -D"
                    + ALLOW_UNLISTED + "=true to attach anyway)";
        }
        String name;
        try {
            String text = command.get();
            int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
            name = text.substring(slash + 1).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            name = "";
        }
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.equals("java") || name.equals("javaw") ? null
                : "pid " + pid + " is not a JVM the attach API lists (command " + command.get() + "); not attaching, "
                + "because the attach signal would end a process that is not a JVM (set -D" + ALLOW_UNLISTED
                + "=true to attach anyway)";
    }

    /** Connects to what the target published; only a verified ping counts as attached. */
    private static Endpoint handshake(long pid, Published published, String token)
            throws AttachFailedException {
        int port;
        try {
            port = Integer.parseInt(published.port().strip());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
        } catch (NumberFormatException e) {
            throw new AttachFailedException("pid " + pid + " published an invalid " + PORT + ": " + published.port(),
                    e);
        }
        Endpoint candidate = new Endpoint("127.0.0.1", port, token, published.nonce(), pid, 0, null);
        try (AgentClient client = AgentClient.connect(candidate)) {
            Map<String, Object> ping = client.lastPing();
            long startTimeMs = ping != null && ping.get("startTimeMs") instanceof Long start ? start : 0L;
            LoadMode loadMode = Discovery.parseLoadMode(ping == null ? null : String.valueOf(ping.get("loadMode")));
            if (loadMode == null) {
                loadMode = Optional.ofNullable(Discovery.parseLoadMode(published.loadMode())).orElse(LoadMode.ATTACH);
            }
            return new Endpoint("127.0.0.1", port, token, published.nonce(), pid, startTimeMs, loadMode,
                    client.sessionId());
        } catch (AgentLostException e) {
            throw new AttachFailedException("pid " + pid + " published " + PORT + "=" + port
                    + (published.fresh() ? "" : " (an agent that was already running)")
                    + " but the handshake failed: " + e.getMessage(), e);
        }
    }

    /** Runs {@code task} on an attach worker and waits at most {@code timeout}. */
    private static <T> T bounded(Callable<T> task, Duration timeout, String what) throws AttachFailedException {
        Future<T> future = WORKERS.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AttachFailedException(what + " did not finish within " + timeout.toSeconds() + " s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new AttachFailedException("interrupted while " + what, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AttachFailedException failed) {
                throw failed;
            }
            if (cause instanceof LinkageError) {
                throw new AttachFailedException(noAttachModuleMessage(), cause);
            }
            throw new AttachFailedException(what + " failed: " + cause, cause);
        }
    }

    /**
     * Every reference to {@code com.sun.tools.attach} lives here, so that loading {@link AttachSession} does not need
     * the {@code jdk.attach} module. Runs on attach worker threads only.
     */
    private static final class Api {

        private Api() {
        }

        static List<JvmInfo> list() {
            List<JvmInfo> jvms = new ArrayList<>();
            for (VirtualMachineDescriptor d : VirtualMachine.list()) {
                jvms.add(new JvmInfo(d.id(), d.displayName() == null ? "" : d.displayName()));
            }
            jvms.sort(Comparator.comparingLong(JvmInfo::pid).thenComparing(JvmInfo::id));
            return List.copyOf(jvms);
        }

        static Published loadAndAwaitPublish(long pid, String jar, String argument, String nonce)
                throws AttachFailedException {
            VirtualMachine vm = attachTo(pid);
            try {
                Properties before = properties(vm, pid);
                String specVersion = before.getProperty("java.specification.version");
                AttachReply.Outcome outcome;
                try {
                    vm.loadAgent(jar, argument);
                    outcome = AttachReply.interpret(null, null, null, specVersion);
                } catch (AgentInitializationException e) {
                    outcome = AttachReply.interpret(AttachReply.INITIALIZATION_EXCEPTION, e.getMessage(),
                            e.returnValue(), specVersion);
                } catch (AgentLoadException e) {
                    outcome = AttachReply.interpret(AttachReply.LOAD_EXCEPTION, e.getMessage(), null, specVersion);
                } catch (IOException e) {
                    throw new AttachFailedException("loadAgent into pid " + pid + " failed: " + e, e);
                }
                if (outcome.success() && outcome.message() != null) {
                    String reply = outcome.message();
                    LOG.log(System.Logger.Level.DEBUG, () -> "pid " + pid + " (Java " + specVersion
                            + ") reported the load as \"" + reply + "\": the Java 8 success reply");
                }
                if (!outcome.success()) {
                    String error = quietProperty(vm, ERROR);
                    throw new AttachFailedException("loading " + jar + " into pid " + pid + " (Java " + specVersion
                            + ") failed: " + outcome.describe()
                            + (error == null ? "" : "; " + ERROR + ": " + error));
                }
                return awaitPublish(vm, pid, before, nonce);
            } finally {
                try {
                    vm.detach();
                } catch (IOException | RuntimeException ignored) {
                    // The target keeps running whether or not the detach message arrived.
                }
            }
        }

        private static VirtualMachine attachTo(long pid) throws AttachFailedException {
            String id = Long.toString(pid);
            Optional<ProcessHandle> process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
            if (process.isEmpty()) {
                throw new AttachFailedException("no running process with pid " + pid);
            }
            awaitMinimumAge(process.get());
            boolean listed = VirtualMachine.list().stream().anyMatch(d -> id.equals(d.id()));
            if (!listed) {
                // Attaching signals the target (SIGQUIT on Unix), which would kill a process that is not a JVM.
                String refusal = unlistedRefusal(pid, process.get().info().command(),
                        Boolean.getBoolean(ALLOW_UNLISTED));
                if (refusal != null) {
                    throw new AttachFailedException(refusal);
                }
            }
            try {
                return VirtualMachine.attach(id);
            } catch (AttachNotSupportedException | IOException e) {
                throw new AttachFailedException("cannot attach to pid " + pid + ": " + e.getMessage()
                        + " (the target must run as the same OS user, allow attach, and be a HotSpot-based JVM)", e);
            }
        }

        /** Waits until {@code process} is {@link #MIN_TARGET_AGE} old (when the OS reports its start time). */
        private static void awaitMinimumAge(ProcessHandle process) throws AttachFailedException {
            Optional<Instant> started = process.info().startInstant();
            if (started.isEmpty()) {
                return;
            }
            long waitMs = MIN_TARGET_AGE.toMillis() - (System.currentTimeMillis() - started.get().toEpochMilli());
            if (waitMs <= 0) {
                return;
            }
            try {
                Discovery.awaitExitOrPoll(process, TimeUnit.MILLISECONDS.toNanos(
                        Math.min(waitMs, MIN_TARGET_AGE.toMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AttachFailedException("interrupted while waiting for pid " + process.pid()
                        + " to finish starting", e);
            }
            if (!process.isAlive()) {
                throw new AttachFailedException("pid " + process.pid() + " exited before the agent could be loaded");
            }
        }

        private static Published awaitPublish(VirtualMachine vm, long pid, Properties before, String nonce)
                throws AttachFailedException {
            long deadline = System.nanoTime() + PUBLISH_TIMEOUT.toNanos();
            Optional<ProcessHandle> process = ProcessHandle.of(pid);
            while (true) {
                boolean expired = System.nanoTime() - deadline >= 0;
                Published published = decide(pid, before, properties(vm, pid), nonce, expired);
                if (published != null) {
                    return published;
                }
                try {
                    if (process.isPresent()) {
                        Discovery.awaitExitOrPoll(process.get(), TimeUnit.MILLISECONDS.toNanos(POLL_MS));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AttachFailedException("interrupted while waiting for pid " + pid + " to publish", e);
                }
                if (process.isPresent() && !process.get().isAlive()) {
                    throw new AttachFailedException("pid " + pid + " exited while the agent was being loaded");
                }
            }
        }

        private static Properties properties(VirtualMachine vm, long pid) throws AttachFailedException {
            try {
                return vm.getSystemProperties();
            } catch (IOException e) {
                throw new AttachFailedException("cannot read the system properties of pid " + pid + ": " + e, e);
            }
        }

        private static String quietProperty(VirtualMachine vm, String name) {
            try {
                return vm.getSystemProperties().getProperty(name);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }
}
