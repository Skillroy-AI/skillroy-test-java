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

package ai.skillroy.testjava.samples.lending.scenarios;

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
import ai.skillroy.testjava.protocol.LoadMode;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * One sample lending desk process with its agent, started in one of the four ways contract section 10 lists, through
 * the real {@link TargetLauncher}, {@link AttachSession} and {@link AgentClient}. Its stdout and stderr go to
 * {@code target/it-logs/<mode>.log}; its discovery directory is a fresh owner-only one below {@code target/}.
 * {@link #close()} ends the process.
 */
final class LendingTarget implements AutoCloseable {

    /** How the agent gets into the target. */
    enum Mode {
        JAVAAGENT("javaagent", LoadMode.JAVAAGENT),
        JAVA_TOOL_OPTIONS("java-tool-options", LoadMode.JAVAAGENT),
        ATTACH("attach", LoadMode.ATTACH),
        EMBEDDED("embedded", LoadMode.EMBEDDED),
        /**
         * The hooks leg (phase6-contracts.md 13.2): {@code -javaagent} with {@code optionalModules=skillroy-agent-hooks},
         * so that the agent loads the optional hooks module from the home's {@code optional/}; and (BW, 13.2 item 7) the
         * optional Byteman module beside it, {@code optionalModules=skillroy-agent-hooks,skillroy-agent-byteman} with
         * {@code byteman.jar} the Byteman of this module's test class path ({@link #bytemanJar()}).
         */
        JAVAAGENT_HOOKS("javaagent-hooks", LoadMode.JAVAAGENT);

        final String slug;
        final LoadMode loadMode;

        Mode(String slug, LoadMode loadMode) {
            this.slug = slug;
            this.loadMode = loadMode;
        }
    }

    static final String MAIN_CLASS = "ai.skillroy.testjava.samples.lending.LendingApp";
    static final String PLUGIN_CLASS = "ai.skillroy.testjava.samples.lending.agent.embedded.SkillroyLendingPlugin";
    /** The agent option that loads the optional hooks module (phase6-contracts.md 2.1, D114). */
    static final String OPTIONAL_MODULES = "optionalModules";
    /** The hooks leg's optional modules: the hooks module and (BW, phase6-contracts.md 13.2 item 7) Byteman's. */
    static final String HOOKS_LEG_MODULES = "skillroy-agent-hooks,skillroy-agent-byteman";
    /** The agent option naming the user's Byteman jar (phase6-contracts.md 3.7, 7.4). */
    static final String BYTEMAN_JAR = "byteman.jar";
    /**
     * A desk PIN the target is launched with ({@code -Dlending.pin}): a key only the lending module's
     * {@code RedactionKeys} names secret (amendment A21), so the agent must never show this value.
     */
    static final String PIN = "975310";
    /** Generous: the Java 8 target runs under x86-64 emulation in the container. */
    static final Duration AGENT_TIMEOUT = Duration.ofSeconds(240);
    /** Length of the generated password of the desk's read-only database account (phase2-contracts.md 14.2). */
    static final int DB_PASSWORD_LENGTH = 12;
    private static final String DB_PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    final Mode mode;
    final AgentHome home;
    final Path discoveryDir;
    /** The agent options the target was launched (or first attached) with; {@link #attach()} reuses them. */
    final Map<String, String> agentOptions;
    final LaunchedTarget launched;
    Endpoint endpoint;
    AgentClient client;
    /**
     * With the database listener: the password of the read-only account, generated for this launch and given to the
     * desk as {@code -Dlending.dbPassword}. It goes only into the owner-only generated {@code credentials.yaml}
     * ({@link #jdbcSource()}) and into the tests' own JDBC connections; never print, log or write it anywhere else.
     * Null without the listener.
     */
    final String dbPassword;
    /**
     * With the database listener: the port the desk picked ({@code -Dlending.dbPort=0}), read back from the
     * {@code lending} section of {@code app.state} ({@code dbPort}); -1 without the listener.
     */
    final int dbPort;
    /** Milliseconds from launch until the agent answered, and until it reported the target ready. */
    final long agentMs;
    final long readyMs;
    /**
     * ATTACH mode only: what {@link AttachSession} logged about the attach client's reply to the load that started
     * this agent. A Java 8 target makes a newer attach client report its successful load as an error with the
     * message "0", which the session must read as success; a newer target gives no such reply ("none").
     */
    String attachReply;

    private LendingTarget(Mode mode, AgentHome home, Path discoveryDir, Map<String, String> agentOptions,
                          LaunchedTarget launched, Endpoint endpoint, AgentClient client, long agentMs, long readyMs,
                          String dbPassword, int dbPort) {
        this.mode = mode;
        this.home = home;
        this.discoveryDir = discoveryDir;
        this.agentOptions = agentOptions;
        this.launched = launched;
        this.endpoint = endpoint;
        this.client = client;
        this.agentMs = agentMs;
        this.readyMs = readyMs;
        this.dbPassword = dbPassword;
        this.dbPort = dbPort;
    }

    /**
     * Launches the lending desk (seed A) with the agent in {@code mode} and waits until the agent reports the target
     * ready.
     */
    static LendingTarget launch(Mode mode) throws Exception {
        return launch(mode, "A", false);
    }

    /**
     * Launches the lending desk on {@code seed} with the agent in {@code mode} and waits until the agent reports the
     * target ready. With {@code databaseListener} the desk also serves its database on a loopback port for the
     * controller-side JDBC channel (the project lead's answer Q4): it is launched with {@code -Dlending.dbPort=0}, so
     * that it picks a free port itself and no other process can take the port between a choice and the bind, and
     * with {@code -Dlending.dbPassword} set to a password of {@value #DB_PASSWORD_LENGTH} characters
     * {@code [A-Za-z0-9]} generated for this launch from {@link SecureRandom}; the port is read back from
     * {@code app.state} once the target is ready ({@link #dbPort}).
     */
    static LendingTarget launch(Mode mode, String seed, boolean databaseListener) throws Exception {
        return launch(mode, seed, databaseListener, null);
    }

    /**
     * {@link #launch(Mode, String, boolean)} of the desk's {@code edition} ({@code -Dlending.edition}, null for the
     * default, the standard edition; phase6-contracts.md 11.3). A branch desk's log is {@code <mode>-branch-seed-<seed>}.
     */
    static LendingTarget launch(Mode mode, String seed, boolean databaseListener, String edition) throws Exception {
        return launch(mode, seed, databaseListener, edition, ItEnv.targetJava());
    }

    /**
     * {@link #launch(Mode, String, boolean, String)} on the given {@code java} (phase6-contracts.md 8.2: the Java 8
     * leg's extra target on the test JVM's own Java, whose matrix is compared live with the Java 8 target's); its log
     * is {@code <label>-java-<feature>}.
     */
    static LendingTarget launch(Mode mode, String seed, boolean databaseListener, String edition, Path javaBinary)
            throws Exception {
        AgentHome home = ItEnv.agentHome();
        boolean otherJava = !javaBinary.equals(ItEnv.targetJava());
        String label = mode.slug + (edition == null ? "" : "-" + edition) + "-seed-"
                + seed.toLowerCase(java.util.Locale.ROOT) + (databaseListener ? "-jdbc" : "")
                + (otherJava ? "-java-" + Runtime.version().feature() : "");
        Path dir = ItEnv.newDiscoveryDir(label);
        List<Path> classPath = new ArrayList<>(ItEnv.applicationClassPath());
        TargetLauncher.Builder builder = TargetLauncher.java(javaBinary)
                .mainClass(MAIN_CLASS)
                .jvmArg("-Dlending.seed=" + seed)
                .jvmArg("-Dlending.pin=" + PIN)
                .discoveryDir(dir)
                .inheritOutputTo(ItEnv.log((seed.equals("A") && !databaseListener && edition == null && !otherJava
                        ? mode.slug : label) + ".log"));
        if (edition != null) {
            builder.jvmArg("-Dlending.edition=" + edition);
        }
        String dbPassword = databaseListener ? generatePassword() : null;
        if (databaseListener) {
            builder.jvmArg("-Dlending.dbPort=0").jvmArg("-Dlending.dbPassword=" + dbPassword);
        }
        Map<String, String> agentOptions = mode == Mode.JAVAAGENT_HOOKS
                ? Map.of("discoveryDir", dir.toString(), OPTIONAL_MODULES, HOOKS_LEG_MODULES, BYTEMAN_JAR, bytemanJar())
                : Map.of("discoveryDir", dir.toString());
        switch (mode) {
            case JAVAAGENT, JAVAAGENT_HOOKS -> builder.javaAgent(home, agentOptions);
            case JAVA_TOOL_OPTIONS -> builder.javaAgent(home, agentOptions).viaJavaToolOptions(true);
            case ATTACH -> {
                // Launched without the agent; it is attached once the JVM runs.
            }
            case EMBEDDED -> {
                // The host's own plugin mechanism starts the agent: the bootstrap jar and the plugin class (in the
                // lending module jar) go on the application's class path; the agent itself comes from the agent home.
                classPath.add(home.bootstrapJar());
                classPath.add(ItEnv.targetJar("skillroy-sample-lending-agent"));
                builder.jvmArg("-Dlending.plugins=" + PLUGIN_CLASS).jvmArg("-Dskillroy.agent.enabled=true");
            }
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
        builder.classPath(classPath);
        long t0 = System.nanoTime();
        LaunchedTarget launched = builder.start();
        AgentClient client = null;
        try {
            Endpoint endpoint;
            String attachReply = null;
            if (mode == Mode.ATTACH) {
                try (AttachLog log = new AttachLog()) {
                    endpoint = AttachSession.attach(launched.pid(), home, agentOptions);
                    attachReply = log.replies.isEmpty() ? "none" : String.join(" | ", log.replies);
                }
            } else {
                endpoint = launched.awaitAgent(AGENT_TIMEOUT);
            }
            long tAgent = System.nanoTime();
            client = AgentClient.connect(endpoint);
            awaitReady(client);
            long tReady = System.nanoTime();
            int dbPort = databaseListener ? listenerPort(client) : -1;
            LendingTarget target = new LendingTarget(mode, home, dir, agentOptions, launched, endpoint, client,
                    millis(t0, tAgent), millis(t0, tReady), dbPassword, dbPort);
            target.attachReply = attachReply;
            return target;
        } catch (Exception | Error e) {
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException closing) {
                    e.addSuppressed(closing);
                }
            }
            launched.close();
            throw e;
        }
    }

    /**
     * The Byteman jar of this module's test class path (test scope, D134; never in the agent home): the code source of
     * {@code org.jboss.byteman.agent.Main}, loaded without initialising it (phase6-contracts.md 7.6).
     */
    static String bytemanJar() {
        try {
            Class<?> main = Class.forName("org.jboss.byteman.agent.Main", false, LendingTarget.class.getClassLoader());
            return Path.of(main.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath()
                    .toString();
        } catch (ReflectiveOperationException | java.net.URISyntaxException e) {
            throw new IllegalStateException("no Byteman on the test class path", e);
        }
    }

    /** A password for the desk's read-only account: {@value #DB_PASSWORD_LENGTH} characters {@code [A-Za-z0-9]}. */
    static String generatePassword() {
        StringBuilder password = new StringBuilder(DB_PASSWORD_LENGTH);
        for (int i = 0; i < DB_PASSWORD_LENGTH; i++) {
            password.append(DB_PASSWORD_ALPHABET.charAt(RANDOM.nextInt(DB_PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    /** The port of the desk's database listener as the {@code lending} section of {@code app.state} reports it. */
    private static int listenerPort(AgentClient client) throws Exception {
        Object port = LoadModeChecks.lendingState(client).get("dbPort");
        if (!(port instanceof Number number) || number.intValue() <= 0 || number.intValue() > 65535) {
            throw new AssertionError("the desk reports no database listener in app.state (lending.dbPort: " + port
                    + ")");
        }
        return number.intValue();
    }

    /**
     * The JDBC source {@code lending} for the generated {@code credentials.yaml} (phase2-contracts.md 13.4): the
     * listener's URL on the port the desk picked, the read-only account and this launch's password.
     *
     * @throws IllegalStateException for a target launched without the database listener
     */
    SampleCredentials.JdbcSource jdbcSource() {
        if (dbPassword == null) {
            throw new IllegalStateException("this target was launched without the database listener");
        }
        return new SampleCredentials.JdbcSource(jdbcUrl(), "reader", dbPassword);
    }

    /** The listener's JDBC URL, {@code jdbc:hsqldb:hsql://127.0.0.1:<dbPort>/lending}. */
    String jdbcUrl() {
        return "jdbc:hsqldb:hsql://127.0.0.1:" + dbPort + "/lending";
    }

    /**
     * Collects what {@link AttachSession} logs (at debug level, through {@code System.Logger}, which the JDK backs
     * with {@code java.util.logging}) about an attach reply it had to interpret, while this object is open.
     */
    private static final class AttachLog extends Handler implements AutoCloseable {

        final List<String> replies = new CopyOnWriteArrayList<>();
        private final Logger logger = Logger.getLogger(AttachSession.class.getName());
        private final Level levelBefore = logger.getLevel();

        AttachLog() {
            setLevel(Level.ALL);
            logger.setLevel(Level.ALL);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            if (message != null && message.contains("reported the load as")) {
                replies.add(message);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(levelBefore);
        }
    }

    /** Waits for the agent's {@code target.ready {ready:true}}: readiness, modules and probes are done. */
    static Event awaitReady(AgentClient client) throws AgentLostException {
        Event ready;
        try {
            ready = client.await("target.ready", 0, Map.of("ready", true), AGENT_TIMEOUT);
        } catch (AgentRpcException e) {
            throw new AssertionError("waiting for target.ready failed: " + e.getMessage(), e);
        }
        if (ready == null) {
            String state;
            try {
                state = String.valueOf(client.call("agent.info", Map.of()).value());
            } catch (AgentRpcException | AgentLostException | RuntimeException e) {
                state = "agent.info failed: " + e;
            }
            throw new AssertionError("the agent did not report the target ready within " + AGENT_TIMEOUT + "; "
                    + state);
        }
        return ready;
    }

    /**
     * Attaches the agent again (re-attach to a running agent, or a fresh agent after {@code agent.disable}) with the
     * options of the launch (phase6-contracts.md 13.2: the hooks leg's re-attach loads the hooks module again).
     */
    Endpoint attach() throws AttachFailedException {
        return AttachSession.attach(launched.pid(), home, agentOptions);
    }

    long pid() {
        return launched.pid();
    }

    private static long millis(long from, long to) {
        return (to - from) / 1_000_000L;
    }

    @Override
    public void close() {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            launched.close();
        }
    }
}
