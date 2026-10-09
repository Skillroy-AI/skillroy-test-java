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
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.data.JdbcDataChannel;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindListener;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The Phase 2 end-to-end checks that run once per target Java, not per load mode (phase2-contracts.md 14.2, and the
 * project lead's answer Q4), against one desk on seed A with the agent on the command line, launched with the
 * database listener ({@link LendingTarget#launch(LendingTarget.Mode, String, boolean)}): {@code -Dlending.dbPort=0},
 * so that the desk picks a free port itself (read back from {@code app.state}), and {@code -Dlending.dbPassword} set
 * to a password of 12 characters generated for this launch. The generated {@code credentials.yaml} with the
 * {@code jdbc:} section routes the source {@code lending} to the controller-side channel. The JDBC block:
 *
 * <ol>
 *   <li>{@link #theControllerSideJdbcChannelServesResolutionAndTheStoredRowChecks()}: items 1-3, scenario A resolves,
 *       approves to the committed bytes (with the channel {@code jdbc}) and runs in {@code replay}, its pre-flight
 *       and its {@code db.*} checks reading over the controller-side channel; and, as phase3-contracts.md 11.2
 *       adds, scenario B resolves over {@code jdbc} with the cross-check agreeing and runs in {@code replay} with
 *       every {@code db} record, the aggregate included, over {@code jdbc};</li>
 *   <li>{@link #theAccountIsTheBoundary()}: item 4;</li>
 *   <li>{@link #theListenerAnswersOnLoopbackOnly()}: item 5;</li>
 *   <li>{@link #theAssembledLauncherFindsTheDriverInAPackJar()}: item 7;</li>
 *   <li>{@link #theDatabasePasswordAppearsNowhere()}: item 6, after every other check of the class;</li>
 * </ol>
 *
 * and, over the agent channel, the CLI block: {@link #theAssembledLauncherResolvesRunsAndReportsARebind()} for
 * scenario A, and {@link #theAssembledLauncherResolvesAndReplaysScenarioB()} for scenario B (phase3-contracts.md
 * 11.2).
 *
 * <p>Outputs go to {@code target/it-runs/per-java/}, emptied at the start. The generated credentials files hold the
 * database password: they go to an owner-only temporary directory below {@code java.io.tmpdir} and are deleted at the
 * end, whatever the outcome. Needs a display (skipped when headless).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class PerTargetJavaChecks {

    static final Env ENV = Env.SEED_A;
    /** The class HSQLDB registers as {@code java.sql.Driver}, on this module's test class path. */
    static final String HSQLDB_DRIVER = "org.hsqldb.jdbc.JDBCDriver";

    LendingTarget target;
    /** Owner-only, below {@code java.io.tmpdir}: the generated credentials files; deleted by {@link #close()}. */
    Path secrets;
    /** The generated {@code credentials.yaml} with the staff and the JDBC source {@code lending}. */
    Path jdbcCredentials;
    /** {@code target/it-runs/per-java}: every output of this class. */
    Path out;
    /** What item 6 looks at besides the output files: the run results and every message and output the checks saw. */
    final List<RunResult> results = new ArrayList<>();
    final List<String> seen = new ArrayList<>();

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        out = ItEnv.runDir("per-java", "x").getParent();
        ResolutionChecks.deleteTree(out);
        Files.createDirectories(out);
        target = LendingTarget.launch(LendingTarget.Mode.JAVAAGENT, ENV.seed, true);
        secrets = Files.createTempDirectory("skillroy-it-per-java");
        jdbcCredentials = SampleCredentials.write(secrets.resolve("jdbc"), ENV, target.jdbcSource());
    }

    @AfterAll
    void close() throws Exception {
        try {
            if (target != null) {
                target.close();
            }
        } finally {
            if (secrets != null) {
                ResolutionChecks.deleteTree(secrets);
            }
        }
    }

    AgentClient client() {
        return target.client;
    }

    /**
     * Items 1-3 over the controller-side channel: {@code live} resolves the slots with every statement on
     * {@code jdbc}; {@code approve} into an empty root writes the committed file's bytes with {@code "channel":
     * "jdbc"} as the only difference; the committed plan runs in {@code replay} with the committed bindings and
     * passes, its pre-flight reading over that channel, its three stored-row checks on {@code jdbc}, no resolver
     * record.
     */
    @Test
    @Order(1)
    void theControllerSideJdbcChannelServesResolutionAndTheStoredRowChecks() throws Exception {
        Credentials credentials = Credentials.load(jdbcCredentials);
        assertEquals(Set.of("lending"), credentials.jdbcSources());
        BindOptions bind = Runs.bind(ENV).withCredentials(credentials);
        Plan plan = Plan.read(ScenarioA.plan());

        BindOutcome live = Binder.bind(plan, Runs.PACK, client(), bind.withMode(BindMode.LIVE)
                .withBindingsRoot(out.resolve("live-root")), BindListener.NONE);
        for (String slot : List.of("systemMax", "item", "assistant", "supervisor")) {
            Resolution r = live.bindings().resolutions().get(slot);
            assertTrue(!r.provenance().statements().isEmpty() && r.provenance().statements().stream()
                    .map(Provenance.Statement::channel).allMatch("jdbc"::equals), slot + " read over JDBC");
        }
        Path root = out.resolve("approve-root");
        BindOutcome approved = Binder.bind(plan, Runs.PACK, client(), bind.withMode(BindMode.APPROVE)
                .withBindingsRoot(root).withApprover(Approver.APPROVE_ALL), BindListener.NONE);
        assertEquals(Files.readString(ScenarioA.committedBindings(ENV), StandardCharsets.UTF_8)
                        .replace("\"channel\": \"agent\"", "\"channel\": \"jdbc\""),
                Files.readString(approved.written(), StandardCharsets.UTF_8),
                "over JDBC the committed file differs in the channel only");

        Runs.Outcome replay = Runs.run(client(), plan, out.resolve("replay-over-jdbc"), bind);
        results.add(replay.result());
        Runs.assertPassedWithoutSleeping(replay);
        List<Map<String, Object>> log = Runs.runLog(replay.dir());
        List<Map<String, Object>> preflight = records(log, "preflight");
        assertEquals(1, preflight.size(), String.valueOf(preflight));
        assertEquals("lending.database", preflight.get(0).get("id"));
        assertEquals(Boolean.TRUE, preflight.get(0).get("ok"), "the pre-flight read over JDBC: " + preflight);
        List<Map<String, Object>> db = records(log, "db");
        assertEquals(List.of("loan", "loan_line", "loan_authorisation"), db.stream().map(r -> r.get("table"))
                .toList());
        assertTrue(db.stream().allMatch(r -> "jdbc".equals(r.get("channel")) && Boolean.TRUE.equals(r.get("ok"))),
                "the stored-row checks read the desk's rows over the controller-side channel: " + db);
        assertEquals(List.of(), records(log, "resolver"), "no resolver ran in replay");
        ItEnv.timing("java=" + javaVersion() + " jdbc replay ms=" + replay.result().duration().toMillis());

        // Phase 3 (11.2): scenario B as well. live resolves the waiver rule over JDBC and the cross-check agrees;
        // replay passes with every db record, the aggregate included, over the controller-side channel.
        Plan planB = Plan.read(ScenarioB.plan());
        List<String> types = new ArrayList<>();
        BindOutcome liveB = Binder.bind(planB, Runs.PACK, client(), bind.withMode(BindMode.LIVE)
                .withBindingsRoot(out.resolve("b-live-root")), (type, fields) -> types.add(type));
        Resolution rule = liveB.bindings().resolutions().get("rule");
        assertTrue(!rule.provenance().statements().isEmpty() && rule.provenance().statements().stream()
                .map(Provenance.Statement::channel).allMatch("jdbc"::equals), "the waiver rules read over JDBC");
        assertEquals(ScenarioB.expected(ENV).rule(), rule.value());
        assertTrue(liveB.bindings().crossChecks().get("waiver").agreed(), "the cross-check agrees");
        assertEquals(1L, types.stream().filter("crosscheck"::equals).count());
        Runs.Outcome replayB = Runs.run(client(), planB, out.resolve("b-replay-over-jdbc"), bind);
        results.add(replayB.result());
        Runs.assertPassedWithoutSleeping(replayB);
        List<Map<String, Object>> logB = Runs.runLog(replayB.dir());
        List<Map<String, Object>> dbB = records(logB, "db");
        assertEquals(List.of("loan", "loan_line", "loan_waiver"), dbB.stream().map(r -> r.get("table")).toList());
        assertTrue(dbB.stream().allMatch(r -> "jdbc".equals(r.get("channel")) && Boolean.TRUE.equals(r.get("ok"))),
                "scenario B's stored-row checks read over the controller-side channel: " + dbB);
        assertEquals(Map.of("sum(amount)", ScenarioB.expected(ENV).amount()), dbB.get(2).get("aggregates"));
        assertEquals(Boolean.TRUE, records(logB, "crosscheck").get(0).get("agreed"));
        assertEquals(List.of(), records(logB, "resolver"), "no resolver ran in replay");
    }

    /**
     * Item 4, the account is the boundary. An {@code INSERT} sent through the channel is refused by the statement
     * guard before a connection is used: with the real credentials, and with a wrong password, which any connection
     * would have run into (the same channel then reports the refused login, SQL state class 28). On the test's own
     * JDBC connection as {@code reader}, an {@code UPDATE} is refused by the database with a privilege error (HSQLDB
     * 2.7 reports SQL state {@code 42501}, "user lacks privilege or object not found", class 42), and the row is
     * unchanged.
     */
    @Test
    @Order(2)
    void theAccountIsTheBoundary() throws Exception {
        String insert = "INSERT INTO desk_setting (name, value) VALUES ('x', 'y')";
        try (JdbcDataChannel channel = new JdbcDataChannel(Credentials.load(jdbcCredentials), Runs.PACK.rowMasks())) {
            DataException guarded = assertThrows(DataException.class,
                    () -> channel.query("lending", insert, List.of(), 1, null));
            seen.add(guarded.getMessage());
            assertEquals(DataException.Kind.GUARD, guarded.kind(), guarded.getMessage());
            Rows staff = channel.query("lending", "SELECT COUNT(*) FROM staff", List.of(), 1, null);
            assertEquals(7L, ((Number) staff.get(0, 0)).longValue(), "the same channel reads");
        }
        Credentials wrongPassword = Credentials.of(Map.of("version", 1L, "jdbc", Map.of("lending", Map.of("url",
                target.jdbcUrl(), "user", "reader", "password", "not-the-reader-password"))), "item 4");
        try (JdbcDataChannel channel = new JdbcDataChannel(wrongPassword, List.of())) {
            DataException guarded = assertThrows(DataException.class,
                    () -> channel.query("lending", insert, List.of(), 1, null));
            seen.add(guarded.getMessage());
            assertEquals(DataException.Kind.GUARD, guarded.kind(), "refused before a connection: " + guarded);
            DataException login = assertThrows(DataException.class,
                    () -> channel.query("lending", "SELECT COUNT(*) FROM staff", List.of(), 1, null));
            seen.add(login.getMessage());
            assertEquals(DataException.Kind.SQL, login.kind(), login.getMessage());
            assertTrue(login.sqlState() != null && login.sqlState().startsWith("28"),
                    "a connection does meet the wrong password: " + login.getMessage());
        }
        String maxReturn = "SELECT value FROM desk_setting WHERE name = 'max_return'";
        try (Connection reader = readerConnection(); Statement st = reader.createStatement()) {
            assertEquals("500.00", single(st, maxReturn));
            SQLException refused = assertThrows(SQLException.class,
                    () -> st.executeUpdate("UPDATE desk_setting SET value = '1.00' WHERE name = 'max_return'"));
            seen.add(String.valueOf(refused.getMessage()));
            assertEquals("42501", refused.getSQLState(), "a privilege error, class 42: " + refused.getMessage());
            assertEquals("500.00", single(st, maxReturn), "the row is unchanged");
        }
        assertEquals(Map.of("maxReturn", "500.00"), LoadModeChecks.value(client().call("lending.limits", Map.of(),
                ResolutionChecks.CALL)), "and the desk is unchanged");
    }

    /**
     * Item 5: the listener answers on {@code 127.0.0.1} only. A connect to each non-loopback IPv4 address of this
     * host, on the listener's port, is refused; skipped, with the reason, on a host without such an address.
     */
    @Test
    @Order(3)
    void theListenerAnswersOnLoopbackOnly() throws Exception {
        try (Socket loopback = new Socket()) {
            loopback.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), target.dbPort), 2000);
        }
        List<InetAddress> others = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }
            for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements();) {
                InetAddress address = a.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    others.add(address);
                }
            }
        }
        assumeFalse(others.isEmpty(), "this host has no non-loopback IPv4 address to try");
        for (InetAddress address : others) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, target.dbPort), 2000);
                fail("the database listener answered on " + address.getHostAddress() + ":" + target.dbPort);
            } catch (IOException refused) {
                // Refused (or, on a filtering host, timed out): nothing listens there.
                seen.add(String.valueOf(refused.getMessage()));
            }
        }
    }

    /**
     * Item 7, through the assembled launcher: {@code run --mode replay --env seed-a --credentials <the generated file
     * with the jdbc section> --pack <the lending pack> --pack <the HSQLDB jar of the test class path>} exits 0, prints
     * {@code model calls: 0}, and its pre-flight and stored-row checks read over {@code jdbc}: {@code --pack} accepts a
     * jar without a {@code DomainPack}, and the driver is found through the context class loader. The same command
     * without the HSQLDB jar, on a launcher whose {@code lib/} holds no JDBC driver (checked), ends {@code ERROR} /
     * {@code ENVIRONMENT} with {@code UNAVAILABLE} for source {@code lending}, sends nothing to the desk and exits 1.
     */
    @Test
    @Order(4)
    void theAssembledLauncherFindsTheDriverInAPackJar() throws Exception {
        Path cli = launcher();
        Path pack = packJar();
        Path hsqldb = Path.of(Class.forName(HSQLDB_DRIVER).getProtectionDomain().getCodeSource().getLocation()
                .toURI());
        assertTrue(Files.isRegularFile(hsqldb) && hsqldb.getFileName().toString().endsWith(".jar"),
                "the HSQLDB jar of the test class path: " + hsqldb);
        Path lib = cli.getParent().getParent().resolve("lib");
        assertTrue(Files.isDirectory(lib), "the launcher's lib/: " + lib);
        assertEquals(List.of(), jarsWithAJdbcDriver(lib), "the launcher's lib/ holds no JDBC driver");
        Path yaml = layout(out.resolve("cli-jdbc").resolve("module"), true);
        List<String> command = List.of("run", yaml.toString(), "--mode", "replay", "--env", ENV.id,
                "--credentials", jdbcCredentials.toString(), "--pid", String.valueOf(target.pid()),
                "--discovery-dir", target.discoveryDir.toString(), "--pack", pack.toString());

        Path withDriver = out.resolve("cli-jdbc").resolve("out");
        Result with = run(cli, concat(command, List.of("--pack", hsqldb.toString(), "--out",
                withDriver.toString())));
        assertEquals(0, with.exit, shown(with));
        assertNoModelCall(with);
        List<Map<String, Object>> log = Runs.runLog(withDriver.resolve(ScenarioA.ID));
        assertEquals(Boolean.TRUE, records(log, "preflight").get(0).get("ok"), "pre-flight over JDBC");
        List<Map<String, Object>> db = records(log, "db");
        assertEquals(List.of("loan", "loan_line", "loan_authorisation"), db.stream().map(r -> r.get("table"))
                .toList());
        assertTrue(db.stream().allMatch(r -> "jdbc".equals(r.get("channel")) && Boolean.TRUE.equals(r.get("ok"))),
                "read over the controller-side channel with the driver from --pack: " + db);

        Path withoutDriver = out.resolve("cli-jdbc").resolve("out-without-driver");
        Result without = run(cli, concat(command, List.of("--out", withoutDriver.toString())));
        assertEquals(1, without.exit, shown(without));
        assertTrue(without.out.lines().anyMatch(l -> l.startsWith("ERROR") && l.contains(ScenarioA.ID)),
                shown(without));
        assertTrue(without.out.contains("ENVIRONMENT: pre-flight check lending.database: the lending database could"
                + " not be read (UNAVAILABLE): ") && without.out.contains("source 'lending'"), shown(without));
        Path failed = withoutDriver.resolve(ScenarioA.ID);
        List<Map<String, Object>> preflight = records(Runs.runLog(failed), "preflight");
        assertEquals(1, preflight.size(), String.valueOf(preflight));
        assertEquals(Boolean.FALSE, preflight.get(0).get("ok"));
        assertTrue(String.valueOf(preflight.get(0).get("reason")).contains("(UNAVAILABLE)"), String.valueOf(preflight));
        ResolutionChecks.assertNothingSent(failed);
    }

    /**
     * The CLI block over the agent channel: through {@code bin/skillroy} against the running target, given the
     * scenario file only, so that the default bindings root ({@code bindings/} in the parent of the scenario
     * directory) is where the files are. The scenario is copied into a work directory in the module's layout, without
     * {@code bindings/}: {@code resolve --yes} writes the committed file's bytes there (and says so) and exits 0;
     * {@code run --mode replay} on that file exits 0 and prints {@code model calls: 0}; the same with the
     * {@code wrong-assistant} root exits 1 and prints {@code REBIND_NEEDED}; no whitespace-delimited token of any
     * output is a password.
     */
    @Test
    @Order(5)
    void theAssembledLauncherResolvesRunsAndReportsARebind() throws Exception {
        Path cli = launcher();
        Path pack = packJar();
        Path layout = out.resolve("cli").resolve("module");
        Path yaml = layout(layout, false);
        Path written = BindingsFile.locate(layout.resolve("bindings"), ENV.id, ScenarioA.ID);
        assertFalse(Files.exists(written), "resolve starts from an empty bindings root");
        Path credentials = SampleCredentials.write(secrets.resolve("cli"), ENV, null);
        List<String> connection = List.of("--pid", String.valueOf(target.pid()), "--discovery-dir",
                target.discoveryDir.toString(), "--pack", pack.toString(), "--credentials", credentials.toString(),
                "--env", ENV.id);
        Set<String> passwords = Set.of(ScenarioA.expected(ENV).assistantPassword(),
                ScenarioA.expected(ENV).supervisorPassword());

        Result resolve = run(cli, concat(List.of("resolve", yaml.toString(), "--yes"), connection));
        assertEquals(0, resolve.exit, shown(resolve));
        assertTrue(resolve.out.contains(ScenarioA.ID + ": resolved; wrote "), shown(resolve));
        assertTrue(Files.isRegularFile(written), "resolve wrote the default root's file: " + written);
        assertEquals(Files.readString(ScenarioA.committedBindings(ENV), StandardCharsets.UTF_8),
                Files.readString(written, StandardCharsets.UTF_8), "resolve wrote the committed bytes");

        Result replay = run(cli, concat(List.of("run", yaml.toString(), "--mode", "replay", "--out",
                out.resolve("cli").resolve("out").toString()), connection));
        assertEquals(0, replay.exit, shown(replay));
        assertNoModelCall(replay);

        Path wrongRoot = out.resolve("cli").resolve("wrong-root");
        ScenarioA.writeWrongAssistant(ENV, wrongRoot);
        Result wrong = run(cli, concat(List.of("run", yaml.toString(), "--mode", "replay", "--bindings",
                wrongRoot.toString()), connection));
        assertEquals(1, wrong.exit, shown(wrong));
        assertTrue((wrong.out + wrong.err).contains("REBIND_NEEDED"), shown(wrong));

        for (Result result : List.of(resolve, replay, wrong)) {
            for (String token : (result.out + "\n" + result.err).split("\\s+")) {
                assertTrue(!passwords.contains(token), "a password was printed: " + shown(result));
            }
        }
    }

    /**
     * Phase 3 (11.2, once per target Java), over the agent channel through {@code bin/skillroy}: scenario B's YAML,
     * copied into a work directory in the module's layout without {@code bindings/}, so that the committed file is
     * left as it is; {@code resolve --env seed-a --yes} exits 0, prints the cross-check line (with the desk's business
     * date read before or after the process: either is accepted, so that a run across the target's midnight passes)
     * and writes the committed file's bytes; {@code run --mode replay --env seed-a} on that file exits 0 and prints
     * {@code model calls: 0}. No whitespace-delimited token of either output is a password.
     */
    @Test
    @Order(6)
    void theAssembledLauncherResolvesAndReplaysScenarioB() throws Exception {
        Path cli = launcher();
        Path pack = packJar();
        Path layout = out.resolve("cli-b").resolve("module");
        Path yaml = layout(layout, ScenarioB.yaml(), null);
        Path written = BindingsFile.locate(layout.resolve("bindings"), ENV.id, ScenarioB.ID);
        assertFalse(Files.exists(written), "resolve starts from an empty bindings root");
        Path credentials = SampleCredentials.write(secrets.resolve("cli-b"), ENV, null);
        List<String> connection = List.of("--pid", String.valueOf(target.pid()), "--discovery-dir",
                target.discoveryDir.toString(), "--pack", pack.toString(), "--credentials", credentials.toString(),
                "--env", ENV.id);
        ScenarioB.Expected expected = ScenarioB.expected(ENV);
        String before = String.valueOf(LoadModeChecks.lendingState(client()).get("today"));

        Result resolve = run(cli, concat(List.of("resolve", yaml.toString(), "--yes"), connection));
        String after = String.valueOf(LoadModeChecks.lendingState(client()).get("today"));
        assertEquals(0, resolve.exit, shown(resolve));
        Function<String, String> line = day -> ScenarioB.ID + ": cross-check"
                + " lending.waiver.evaluate: agrees (the desk waives " + expected.amount() + " for 3 units of "
                + expected.itemCode() + " under " + expected.ruleCode() + " on " + day + "; the oracle expects "
                + expected.amount() + ")";
        assertTrue(resolve.out.contains(line.apply(before)) || resolve.out.contains(line.apply(after)),
                shown(resolve));
        assertTrue(resolve.out.contains(ScenarioB.ID + ": resolved; wrote "), shown(resolve));
        assertEquals(Files.readString(ScenarioB.committedBindings(ENV), StandardCharsets.UTF_8),
                Files.readString(written, StandardCharsets.UTF_8), "resolve wrote the committed bytes");

        Result replay = run(cli, concat(List.of("run", yaml.toString(), "--mode", "replay", "--out",
                out.resolve("cli-b").resolve("out").toString()), connection));
        assertEquals(0, replay.exit, shown(replay));
        assertNoModelCall(replay);

        for (Result result : List.of(resolve, replay)) {
            for (String token : (result.out + "\n" + result.err).split("\\s+")) {
                assertTrue(!token.equals(expected.assistantPassword()), "a password was printed: " + shown(result));
            }
        }
    }

    /**
     * Item 6: the database password (12 characters, so the substring rule applies) occurs in no file under this
     * class's output directories (run outputs, bindings roots, the launcher's outputs), in the target's log and its
     * discovery directory, in no {@code RunResult}, in no exception message or launcher output the checks saw, and in
     * no line of the agent's log; {@code jvm.properties} reports {@code lending.dbPassword} as {@code <redacted>},
     * {@code jvm.info} lists {@code -Dlending.dbPassword=<redacted>}, and no reply of {@code jvm.properties},
     * {@code jvm.info} or {@code agent.info} holds the value. Runs after every other check of the class.
     */
    @Test
    @Order(7)
    void theDatabasePasswordAppearsNowhere() throws Exception {
        String password = target.dbPassword;
        assertTrue(password.matches("[A-Za-z0-9]{" + LendingTarget.DB_PASSWORD_LENGTH + "}"),
                "a generated password of " + LendingTarget.DB_PASSWORD_LENGTH + " characters");
        assertTrue(SecretKeys.looksSecret("lending.dbPassword"), "the key itself makes the property secret");
        List<String> leaks = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (Path root : List.of(out, target.discoveryDir)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        Path targetLog = target.launched.logFile();
        assertNotNull(targetLog, "the target's output is kept in a log file");
        files.add(targetLog);
        Set<String> kinds = new java.util.TreeSet<>();
        for (Path file : files) {
            // Byte for byte: the password is ASCII, so this finds it in any encoding of text that holds it.
            String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            kinds.add(file.getFileName().toString().replaceAll("^cli.*\\.(out|err)$", "launcher output"));
            if (content.contains(password)) {
                leaks.add("file " + file);
            }
        }
        for (RunResult result : results) {
            String text = result + "\n" + Json.write(Map.of("detail", result.detail(), "steps",
                    String.valueOf(result.steps())));
            if (text.contains(password)) {
                leaks.add("the RunResult of " + result.scenarioId());
            }
        }
        for (int i = 0; i < seen.size(); i++) {
            if (String.valueOf(seen.get(i)).contains(password)) {
                leaks.add("message or output " + (i + 1) + " of " + seen.size() + " the checks saw");
            }
        }
        List<?> lines = (List<?>) LoadModeChecks.value(client().call("agent.log", Map.of("n", 2000L),
                ResolutionChecks.CALL)).get("lines");
        assertFalse(lines.isEmpty(), "the agent's log has lines");
        for (int i = 0; i < lines.size(); i++) {
            if (String.valueOf(lines.get(i)).contains(password)) {
                leaks.add("agent.log line " + (i + 1));
            }
        }
        assertEquals(List.of(), leaks, "the database password was written");
        assertTrue(kinds.containsAll(List.of("run.jsonl", "bindings.json", "cost-ledger.jsonl",
                ScenarioA.ID + ".bindings.json", "launcher output", targetLog.getFileName().toString())),
                "every kind of output was looked at: " + kinds);

        RpcResult propertiesReply = client().call("jvm.properties", Map.of("names", List.of("lending.dbPassword",
                "lending.dbPort")), ResolutionChecks.CALL);
        Map<?, ?> properties = (Map<?, ?>) LoadModeChecks.value(propertiesReply).get("properties");
        assertEquals(SecretKeys.REDACTED, properties.get("lending.dbPassword"), String.valueOf(properties));
        assertEquals("0", properties.get("lending.dbPort"), "the port property as launched");
        RpcResult info = client().call("jvm.info", Map.of(), ResolutionChecks.CALL);
        List<String> arguments = ((List<?>) LoadModeChecks.value(info).get("inputArguments")).stream()
                .map(String::valueOf).toList();
        assertTrue(arguments.contains("-Dlending.dbPassword=" + SecretKeys.REDACTED), "jvm.info lists the property"
                + " redacted: " + arguments);
        assertTrue(arguments.contains("-Dlending.dbPort=0"), String.valueOf(arguments));
        for (RpcResult reply : List.of(propertiesReply, info, client().call("agent.info", Map.of(),
                ResolutionChecks.CALL))) {
            assertFalse(String.valueOf(reply.value()).contains(password), "the password never leaves the target");
        }
    }

    // ------------------------------------------------------------------ helpers

    record Result(int exit, String out, String err) {
        @Override
        public String toString() {
            return "exit " + exit + "\nstdout:\n" + out + "\nstderr:\n" + err;
        }
    }

    /** A result for an assertion message: the database password, should it appear, is shown as a placeholder. */
    String shown(Result result) {
        String text = result.toString();
        return target.dbPassword == null ? text : text.replace(target.dbPassword, "<the database password>");
    }

    /**
     * The run's summary ends with {@code model calls: 0} and then the model cost line, {@code model cost: $0 (...)}
     * (phase5-contracts.md 13.4).
     */
    /**
     * phase6-contracts.md 8.2 (decision D136), the literal proof of "the capability matrix equals the JDK 8 one": when
     * the target runs on another Java than this JVM (the Java 8 leg: {@code -Dskillroy.it.targetJava}), one more
     * target, launched alike ({@code JAVAAGENT}, the database listener), runs on this JVM's own Java, and the two
     * matrices, normalised as the goldens are (ids, states and strategy strict, reason by presence, the allow-list),
     * are equal. On the Java 25 leg both Javas are one: the goldens are the gate there.
     */
    @Test
    @Order(8)
    void theMatrixOnThisJvmsJavaEqualsTheTargetsLive() throws Exception {
        Path own = Path.of(System.getProperty("java.home"), "bin", "java");
        org.junit.jupiter.api.Assumptions.assumeFalse(own.toRealPath().equals(ItEnv.targetJava().toRealPath()),
                "the target runs on this JVM's Java: the goldens are the gate");
        Map<String, Object> theirs = CapabilityGolden.normalise(client().capabilities());
        try (LendingTarget other = LendingTarget.launch(LendingTarget.Mode.JAVAAGENT, ENV.seed, true, null, own)) {
            Map<String, Object> ours = CapabilityGolden.normalise(other.client.capabilities());
            String diff = CapabilityGolden.compare(theirs, ours, CapabilityGolden.allowed(CapabilityGolden.dir()
                    .resolve("allow-differences.json")));
            ItEnv.timing("perTargetJava liveMatrixDiff=" + (diff == null ? "none" : diff.replace('\n', ' ')));
            assertTrue(diff == null, "the matrices of the two Javas differ:\n" + diff);
        }
    }

    void assertNoModelCall(Result result) {
        List<String> lines = result.out.strip().lines().toList();
        assertTrue(lines.size() >= 2 && lines.get(lines.size() - 2).equals("model calls: 0")
                && lines.get(lines.size() - 1).startsWith("model cost: $0 ("), shown(result));
    }

    Result run(Path launcher, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.addAll(args);
        Path logs = out.resolve("cli-logs");
        Files.createDirectories(logs);
        Path stdout = Files.createTempFile(logs, "cli", ".out");
        Path stderr = Files.createTempFile(logs, "cli", ".err");
        ProcessBuilder builder = new ProcessBuilder(command).directory(out.toFile()).redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        process.getOutputStream().close();
        boolean exited = process.waitFor(300, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertTrue(exited, "bin/skillroy " + args.get(0) + " did not finish in time");
        Result result = new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
        seen.add(result.out);
        seen.add(result.err);
        return result;
    }

    /** {@code bin/skillroy} of the assembled launcher ({@code skillroy.it.cliDist}). */
    static Path launcher() {
        Path cli = Path.of(System.getProperty("skillroy.it.cliDist", "missing")).resolve("bin").resolve("skillroy");
        assertTrue(Files.isExecutable(cli), "the assembled launcher: " + cli);
        return cli;
    }

    /** The lending pack jar the launcher is given ({@code skillroy.it.packJar}). */
    static Path packJar() {
        Path pack = Path.of(System.getProperty("skillroy.it.packJar", "missing"));
        assertTrue(Files.isRegularFile(pack), "the lending pack jar: " + pack);
        return pack;
    }

    /**
     * Copies scenario A into {@code layout} the way the module holds it ({@code scenarios/<id>.yaml}), with the
     * committed bindings in {@code bindings/<env>/} when asked, so that nothing here can change a committed file.
     *
     * @return the copied YAML
     */
    static Path layout(Path layout, boolean withBindings) throws Exception {
        return layout(layout, ScenarioA.yaml(), withBindings ? ScenarioA.committedBindings(ENV) : null);
    }

    /**
     * Copies a committed scenario into {@code layout} as the module holds it ({@code scenarios/<file>}), with the
     * given committed bindings file in {@code bindings/<env>/} unless it is null.
     *
     * @return the copied YAML
     */
    static Path layout(Path layout, Path scenario, Path committedBindings) throws Exception {
        ResolutionChecks.deleteTree(layout);
        Path yaml = layout.resolve("scenarios").resolve(scenario.getFileName().toString());
        Files.createDirectories(yaml.getParent());
        Files.copy(scenario, yaml);
        if (committedBindings != null) {
            Path copied = layout.resolve("bindings").resolve(ENV.id).resolve(committedBindings.getFileName()
                    .toString());
            Files.createDirectories(copied.getParent());
            Files.copy(committedBindings, copied);
        }
        return yaml;
    }

    /** The jars in {@code dir} that provide a {@code java.sql.Driver} service (or hold HSQLDB's driver class). */
    static List<String> jarsWithAJdbcDriver(Path dir) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> jars = Files.list(dir)) {
            for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    if (zip.getEntry("META-INF/services/java.sql.Driver") != null
                            || zip.getEntry(HSQLDB_DRIVER.replace('.', '/') + ".class") != null) {
                        found.add(jar.getFileName().toString());
                    }
                }
            }
        }
        return found;
    }

    /** The test's own connection to the listener as {@code reader}, through HSQLDB's driver directly. */
    Connection readerConnection() throws Exception {
        Driver driver = (Driver) Class.forName(HSQLDB_DRIVER).getDeclaredConstructor().newInstance();
        Properties account = new Properties();
        account.setProperty("user", "reader");
        account.setProperty("password", target.dbPassword);
        Connection connection = driver.connect(target.jdbcUrl(), account);
        assertNotNull(connection, "the driver accepts the listener's URL");
        return connection;
    }

    private static String single(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }

    private static List<Map<String, Object>> records(List<Map<String, Object>> log, String type) {
        return log.stream().filter(r -> type.equals(r.get("type"))).toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private String javaVersion() throws Exception {
        return String.valueOf(LoadModeChecks.value(client().call("jvm.info", Map.of(), ResolutionChecks.CALL))
                .get("javaVersion"));
    }
}
