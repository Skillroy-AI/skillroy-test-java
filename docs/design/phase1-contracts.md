# Phase 1 contracts: build, wire protocol, agent SPI, primitives, controller, sample lending desk

**Status:** as built and verified (2026-10-02), Phase 1.x included, and so are the Phase 2 changes to it (built
2026-10-02, see "Phase 2" below). The text was frozen for the Phase 1 build on 2026-10-01; amendments A1-A16 and the
as-built details recorded during review were merged into it on 2026-10-02, and the Phase 1.x changes of the same day
were first written in as specification and then, once built, corrected to what the code does
(`phase1-contract-amendments.md` lists what changed, where, and where the build differs from the amendment's
wording). Signatures, names, defaults and limits were checked against the source when they were merged; measured
values come from the end-to-end review and the Phase 1.x integration. **Scope:** the thin vertical slice of
`base-framework-design.md` ("Roadmap", Phase 1). This file is the single source of truth for Phase 1. Where it and
the design overview disagree on a Phase 1 detail, this file wins; where it and the code disagree, one of the two has
a defect. Change the contract first, then the code. Names are final (decision B2).
Passages that Phase 5 changes (`phase5-contracts.md` §16.1) were written in before its code and marked
"(Phase 5, built 2026-10-08; P5 §n)"; the build of 2026-10-08 marked each of them "(Phase 5, built 2026-10-08; P5 §n)"; its answers B23 and its
decisions B24 were confirmed by the owner on 2026-10-08.

**Phase 1.x.** Two sets of changes were decided on 2026-10-02 and are written into the sections they change:
amendments A17-A33 (A24 is withdrawn, A26 is superseded) and the hardening items H1-H3, which carry the dispositions
of the security findings S1-S34. All of them were built, integrated and verified the same day. Every such passage,
table row and code member is marked **(1.x, built 2026-10-02)**, with the id of the item; in code blocks the mark is a
comment on the member. The marked text describes the code as it is, like the rest. Where the specification said one
thing and the code does another, the passage states what the code does and keeps the history in one line: "(the
specification said …; built as … because …)".

**Phase 2.** The changes to this contract that `phase2-contracts.md` §16.1 asks for were written in on 2026-10-02,
before the code, marked "(Phase 2, specified 2026-10-02, not yet built)" at the time. Phase 2 was built the same day;
since the as-built pass of 2026-10-03 every such passage, table row and code member is marked **(Phase 2, built
2026-10-02)** and, like the rest of this file, describes the code as it is. Where the build differs from what was
specified, the passage says what the code does; where it replaced Phase 1 behaviour, it keeps that in one line ("in
Phase 1 …"). The detail of each change, and the reason, is in `phase2-contracts.md` (references of the form "P2
§11.4"), and so is everything else Phase 2 added that this file does not repeat: the resolution SPI and the binder's
modes, the run's Phase 2 outputs, the CLI's new options and commands, the sample's primitives `lending.deposit` and
`lending.limits` and its scenario A.

**Phase 3 and Phase 4.** The changes that `phase3-contracts.md` §13.1 and `phase4-contracts.md` §14.1 ask for are
written into the sections they change in the same way, marked **(Phase 3, built 2026-10-03; P3 §n …)** and **(Phase 4,
built 2026-10-07; P4 §n …)**. The Phase 4 passages were written in on 2026-10-07 before the code, marked "(Phase 4,
specified 2026-10-06, not yet built)" at the time, and corrected to what the code does after the build the same day;
the detail of each, and everything else Phase 4 added, is in `phase4-contracts.md`.

Phase 1 delivers: protocol, agent SPI, bootstrap, core, Swing module, data module, controller (client, attach,
launcher, discovery, capability matrix), scenario compiler/executor, JUnit glue, a CLI, the sample lending desk
(application, agent module, domain pack, scenarios) and an end-to-end test in every load mode (§10).
Not in Phase 1: resolvers beyond `fixture`, oracles, the inference gateway, MCP, CDP/web, hooks, Byteman.
What was run, on which JDKs and with what result is in `../TODO.md`.

---

## 1. Build conventions

| Item | Rule |
|---|---|
| Build tool | Maven 3.9, single reactor rooted at `pom.xml` (`ai.skillroy:skillroy-test-java-parent:0.1.0-SNAPSHOT`, packaging `pom`) |
| Build JDK | any JDK >= 25; the build refuses an older one (`skillroy.build.minJava`). Developed on JDK 26; the Linux test image (`eclipse-temurin:25-jdk`) builds and runs the reactor on JDK 25 |
| Agent-side modules (everything under `agent/`, plus `samples/skillroy-sample-lending-app` and `samples/skillroy-sample-lending-agent`) | `maven.compiler.release=8` for main **and** test code. Java 8 language and API only: no `var`, no records, no text blocks, no `List.of`, no `Optional.isEmpty`, no `String.isBlank`, no `InputStream.readAllBytes`. Lambdas and streams are fine |
| Controller-side modules (everything under `controller/`, plus `samples/skillroy-sample-lending-pack` and `samples/skillroy-sample-lending-scenarios`) | `maven.compiler.release=25` (decision B3) |
| Third-party code inside the target JVM | **none**. `skillroy-protocol`, `skillroy-agent-api`, `skillroy-agent-bootstrap`, `skillroy-agent-core`, `skillroy-agent-swing`, `skillroy-agent-data` depend only on the JDK and on each other |
| Controller dependencies | `skillroy-protocol` (wire types and the JSON codec are shared, never duplicated), Jackson (`jackson-databind`, `jackson-dataformat-yaml`) for YAML files, JUnit 5 in `skillroy-junit`. Nothing else without a recorded reason. **(Phase 2, built 2026-10-02)** the two Jackson artifacts are dependencies of the new module `skillroy-data` alone, where the strict YAML reader `StrictYaml` is their only user (P2 §2.1); `skillroy-scenario` declares no Jackson artifact (in Phase 1 it declared both) and reaches them through `skillroy-data`, as do `skillroy-junit`, `skillroy-cli` and the sample pack. Plans and bindings files are read and written with the shared JSON codec of `skillroy-protocol` |
| Sample application | HSQLDB (`org.hsqldb:hsqldb`, `jdk8` classifier) is its only dependency |
| Pinned versions (root pom) | Jackson 2.22.3, HSQLDB 2.7.4, JUnit 5.14.4 |
| Tests | JUnit Jupiter 5.x (Java 8 baseline). Unit tests run with `-Djava.awt.headless=${skillroy.headless}` (default `true`); tests that need a display call `Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())` and therefore run only under `ci/run-in-linux.sh` with `-Dskillroy.headless=false` |
| Java 8 proof | `-Dskillroy.it.targetJava=<java executable>` selects the Java that launched targets (child JVMs) run on. `-Djvm=<java 8 executable>` additionally runs the test JVMs themselves on Java 8; it applies to the agent-side modules only, because a controller-side module needs Java 25 for its own test JVM. For controller-side modules the Java 8 proof is therefore `skillroy.it.targetJava` alone |
| Package root | `ai.skillroy.testjava` |
| Licence | Apache-2.0 (decision B1). Every source file carries the licence header; `ci/license-headers.sh` checks it |
| Encoding, line length | UTF-8, 120 columns, 4-space indent, no tabs, no wildcard imports |
| Javadoc | every public type and SPI method; say *why* and state threading rules; no restating of names |
| Logging in the agent | `AgentLog` only (no `java.util.logging` configuration changes, no `System.out`). **H1, S12/S16/S29 (1.x, built 2026-10-02):** no core class refers to `java.util.logging` or `com.sun.net.httpserver` (a test scans the class files), see §5 |
| Agent threads | daemon threads named `skillroy-*` and nothing else; a domain module's own threads follow the same rule. **S34 (1.x, built 2026-10-02):** every thread the core creates has the *host* loader as its context class loader, never the agent loader, so that host code a module calls on an agent thread, and threads that code creates, do not pin the agent's loader; a module that needs its own loader (for `ServiceLoader`) passes it explicitly |

Module directories and artifact ids:

```
pom.xml
agent/skillroy-protocol            agent/skillroy-agent-api        agent/skillroy-agent-bootstrap
agent/skillroy-agent-core          agent/skillroy-agent-swing      agent/skillroy-agent-data
agent/skillroy-agent-dist          (assembles the agent home directory and zip)
controller/skillroy-controller     controller/skillroy-scenario    controller/skillroy-junit    controller/skillroy-cli
samples/skillroy-sample-lending-app   samples/skillroy-sample-lending-agent
samples/skillroy-sample-lending-pack  samples/skillroy-sample-lending-scenarios
ci/Dockerfile  ci/run-in-linux.sh  ci/license-headers.sh
```

Dependency graph (arrows = "depends on"):

```
agent-api -> protocol          core -> agent-api, protocol        swing -> agent-api, protocol     data -> agent-api, protocol
bootstrap -> (nothing)         dist -> bootstrap, protocol, agent-api, core, swing, data
controller -> protocol         scenario -> controller             junit -> scenario
cli -> scenario, controller, protocol (tests: the dist zip)
lending-app -> hsqldb          lending-agent -> agent-api, protocol, lending-app, bootstrap (all provided; the
                               bootstrap only for the EMBEDDED plugin class)
lending-pack -> scenario       lending-scenarios -> lending-pack, junit, dist (agent home), lending-app,
                               lending-agent (test)
```

**(Phase 2, built 2026-10-02)** One controller-side module is added, **`controller/skillroy-data`** (package
`ai.skillroy.testjava.data`, `maven.compiler.release=25`, listed in the root pom before
`controller/skillroy-scenario`): the data channels, `Credentials`, `SecretMask`, `RowMask` and the strict YAML reader
(`ai.skillroy.testjava.data.yaml.StrictYaml`, §8.1). `skillroy-protocol` gains the package
`ai.skillroy.testjava.protocol.sql` (`StatementGuard`, `ValueConverter`, `ReadOnlyJdbc`, `Rows`; the first two moved
from `skillroy-agent-data`, `ReadOnlyJdbc` is the JDBC part of its `JdbcExecutor`, and `Rows` takes the place of its
`Table`, which stays there only as the converter from `QueryResult`), and `skillroy-agent-data` keeps `data.query`,
the provider lookup and `MaskPolicy` and calls the moved classes; no class outside that package references it, so the
wire types still load without the `java.sql` module (a test scans the compiled classes). The reactor now has sixteen
modules below the root pom. The graph becomes (new edges in bold; P2 §2.1):

```
protocol   (gains …protocol.sql)
agent-data -> agent-api, protocol          controller -> protocol
**data -> controller, protocol**           scenario -> controller, **data**
junit -> scenario                          cli -> scenario, **data**, controller, protocol
lending-pack -> scenario, **data**         lending-scenarios -> lending-pack, junit, **data**, dist, lending-app,
                                           lending-agent (test), **cli (test)**
lending-agent -> …, **agent-data (test)**
```

The sample edges marked here were added in the build (P2 §2.1, "As built"): the pack uses `DataChannel`,
`DataException` and `RowMask`; the scenario tests build `Credentials` and call the CLI for the committed-files
`diff-bindings` check; the sample agent module's test reads through the real `data.query`.

---

## 2. Agent home, loaders, options, discovery

### 2.1 Agent home (output of `skillroy-agent-dist`, directory `target/skillroy-agent/` and `target/skillroy-agent.zip`)

```
skillroy-agent/
  LICENSE  NOTICE                  # copies of the repository's files (1.x, built 2026-10-02)
  skillroy-agent-bootstrap.jar     # the -javaagent / loadAgent jar; also on the host class path in EMBEDDED mode
  lib/skillroy-protocol.jar  lib/skillroy-agent-api.jar  lib/skillroy-agent-core.jar
  modules/skillroy-agent-swing.jar  modules/skillroy-agent-data.jar      # plus any domain module jars dropped in
  modules/skillroy-agent-web.jar   # (Phase 4, built 2026-10-07; P4 §2.1, §11)
  skillroy-agent-spy.jar           # (Phase 6, built 2026-10-09, HK; P6 §2.1) appended to the bootstrap path by the
                                   # hooks module at its first hook.install, never earlier
  optional/skillroy-agent-hooks.jar   # (Phase 6, built 2026-10-09, HK; P6 §2.1) never scanned; loaded only when the
                                      # option optionalModules names it; carries the relocated Byte Buddy and ASM
```

**Phase 6 (built 2026-10-09, HK; P6 §2.1, §2.5).** The home gains `skillroy-agent-spy.jar` at its root (outside
`lib/` and `modules/`, so that no agent loader defines `Spy`) and the directory `optional/`, which `ModuleLoader`
never scans (D114). `DistributionContentIT`'s list has eleven entries (thirteen once the Byteman module adds its two
jars under `optional/`, BW) and two new rules: no jar but `skillroy-agent-spy.jar` holds an entry of
`ai/skillroy/testjava/agent/spy/`, and `optional/skillroy-agent-hooks.jar` holds no entry under `net/`. Every jar
still holds only `ai/skillroy/` and `META-INF/` entries: the relocated Byte Buddy lives under
`ai/skillroy/testjava/agent/hooks/shaded/` and its notices under `META-INF/third-party/`. `HomeConstantPoolIT`
(D152) scans every class of the home for class references outside the JDK's and the project's prefixes.

Jar names inside the home carry **no version** (the bootstrap must be found by a stable name; attach re-runs
`agentmain` of the first-loaded jar and shadows a renamed one). **Licence files (1.x, built 2026-10-02):** every jar
of the reactor carries `META-INF/LICENSE` and `META-INF/NOTICE` (copied from the repository root by a shared
`maven-resources-plugin` execution; the root pom declares `<licenses>` Apache-2.0 and `<inceptionYear>`), and the
agent home directory and zip carry both files at the top level; an integration test checks the exact file list of
the home and that its six jars (Phase 4, built 2026-10-07; P4 §2.1, §11: seven, with `modules/skillroy-agent-web.jar`;
the test is `DistributionContentIT`) hold only `ai/skillroy/` and `META-INF/` entries.

### 2.2 Class loaders

```
host loader (system loader, or the loader that called EmbeddedAgent.start)
  └─ agent loader: lib/*.jar, child-first for ai.skillroy.testjava.* except ai.skillroy.testjava.agent.bootstrap.*
       └─ one module loader per module jar, child-first for the classes that jar contains
```

Modules see `skillroy-agent-api`/`skillroy-protocol` through the agent loader and host application classes through
the host loader. Nothing of the agent except the bootstrap jar is visible to the host.

**Phase 6 (built 2026-10-09, HK; P6 §2.1, D116).** The hooks' `Spy` package `ai.skillroy.testjava.agent.spy.` exists
once in a JVM: `AgentClassLoader.isChildFirst` excludes it as it excludes the bootstrap package, and
`ModuleClassLoader`'s parent-first list (classes and resources) gains it, so that every loader of the agent resolves
`Spy` to the one copy the hooks module appends to the bootstrap search path (in hooks mode only, from the first
`hook.install` on, for the JVM's life). Before that append the parent chain has no `Spy`, and a copy inside a module
jar would be defined there, which is why no jar but the spy jar may hold one (§2.1). Visible to the host in hooks
mode: `Spy`, `Spy$Handler` and `Spy$Stub` in the bootstrap loader, nothing else (`SharedLoadersIT`).

A module loader is parent-first for the JDK (`java.`, `javax.`, `sun.`, `com.sun.`, `jdk.`) and for the shared agent
types (`ai.skillroy.testjava.protocol.`, `ai.skillroy.testjava.agent.api.`), which must exist exactly once or
`ServiceLoader` and every SPI cast would fail; it is child-first for everything else its jar contains. Its parent is
the agent loader, which delegates to the host loader.

### 2.3 Bootstrap (`ai.skillroy.testjava.agent.bootstrap`, a handful of classes, no dependencies)

```java
public final class AgentBootstrap {
    public static void premain(String args, Instrumentation inst);     // JAVAAGENT
    public static void agentmain(String args, Instrumentation inst);   // ATTACH (idempotent; second call re-publishes discovery)
}
public final class EmbeddedAgent {
    /** EMBEDDED mode. Called by the host's own plugin mechanism. Never throws. Returns true if the agent is running
     *  (started now, or already running in this JVM in any load mode), false if it refused or failed. */
    public static boolean start(Properties options);                   // host loader = caller's context class loader
    public static boolean start(Properties options, ClassLoader hostLoader);
    /** hostContext: any object the host wants its own domain module to receive (AgentContext.hostContext()); the
     *  agent never inspects it. Lets a module reach host objects that are not reachable through statics. */
    public static boolean start(Properties options, ClassLoader hostLoader, Object hostContext);
    // A22 (1.x, built 2026-10-02): the non-blocking form, see "Embedded start" below
    public static boolean startAsync(Properties options, ClassLoader hostLoader, Object hostContext);
    public static void stop();
}
```

Manifest: `Premain-Class`, `Agent-Class`, `Launcher-Agent-Class` = `AgentBootstrap`; `Can-Retransform-Classes`,
`Can-Redefine-Classes`, `Can-Set-Native-Method-Prefix` = `true`.

The bootstrap parses options, applies the main-class guard, builds the agent loader and reflectively calls

```java
// in skillroy-agent-core
public final class AgentRuntime implements AutoCloseable {
    public static AgentRuntime start(Map<String,String> options, Instrumentation instOrNull,
                                     ClassLoader hostLoader, String loadMode, Object hostContextOrNull) throws Exception;
    // S32 (1.x, built 2026-10-02): the form the bootstrap calls (reflectively, falling back to the one above);
    // onReleased runs once both the close and the start thread have finished, so the bootstrap releases the loaders then
    public static AgentRuntime start(Map<String,String> options, Instrumentation instOrNull, ClassLoader hostLoader,
                                     String loadMode, Object hostContextOrNull, Runnable onReleased) throws Exception;
    public int port();  public String tokenFile();  public String nonce();  public String discoveryFile();
    public boolean isReady();  public boolean isClosed();
    public void republishDiscovery();          // what a second agentmain calls
    public void onClosed(Runnable hook);       // lets the bootstrap forget a closed agent
    public void close();                       // idempotent, any thread
    // H2 and S33 (1.x, built 2026-10-02):
    public String sessionId();                 // the session id of this start (§5 "Authentication")
    public boolean isForbiddenThread(Thread thread);   // lets the bootstrap decide whether stop() may wait
}
```

(`context()` and `call(method, params)` exist as well, for in-process tests and single-loader embeddings.)

`premain`/`agentmain` never let an exception escape into the host; failures are logged to `System.err` with the
prefix `[skillroy]` and recorded in the system property `skillroy.agent.error`. All entry points are serialised on
one lock. The start sequence runs on a thread `skillroy-start` while the host's start call waits for it, at most the
start timeout (60 s, module installs included). The agent argument `cmd=stop` closes a running agent.

**Embedded start.** `EmbeddedAgent.start` is idempotent and re-entrant:

- a call while an agent is running in this JVM, in any load mode and from any copy of the bootstrap (a running agent
  is recognised by the system property `skillroy.agent.loadMode`), returns `true` and changes nothing: neither the
  new options nor the new host context are applied;
- a call made while the agent is starting, from the thread that is starting it or from one of the agent's own
  threads (a module's `install`), returns `true` at once instead of waiting for itself;
- a call that finds another thread's start or stop in progress waits for it at most the start timeout plus 5 s;
- nothing throws, whatever the input.

The methods use JDK types only and are callable by name
(`Class.forName("…bootstrap.EmbeddedAgent").getMethod("start", Properties.class, ClassLoader.class, Object.class)`),
so a host plugin class links without the bootstrap jar on its class path. `start` blocks the calling host thread
until the agent serves or has refused; do not call it on the event-dispatch thread if a module needs that thread
during `install`. `stop()` stops the agent this bootstrap started; on the event-dispatch thread or on a thread a
module declared forbidden it returns at once and the agent finishes stopping in the background.

**A22 `startAsync` (1.x, built 2026-10-02).** `EmbeddedAgent.startAsync(options, hostLoader, hostContext)` returns
at once: `true` when a start was initiated, a start is already in progress or an agent is already running in this
JVM, `false` when the start is refused outright (the agent is not enabled, or `cmd=stop`) or cannot be initiated. The
`Properties` are copied at once. The start itself runs on a daemon thread `skillroy-start` whose context loader is
the host loader; it waits for the bootstrap's lock like any other host thread, and nobody waits for it. Its outcome is
visible as system properties: `skillroy.agent.loadMode` is set once the agent runs, `skillroy.agent.error` when the
start failed (it is cleared when a start is initiated). The idempotence and re-entrance rules of `start` apply, the
method uses JDK types only and is callable by name, and it never throws. A `stop()` made after `startAsync` and
before that start has begun withdraws the start. It is the form to use from a host plugin hook that must return
quickly or that runs on the event-dispatch thread.

**S31-S33 bootstrap lifecycle (1.x, built 2026-10-02).**

- *S33.* `stop()` never waits on the event-dispatch thread (decided by the thread's name) or on a forbidden thread,
  even when another thread's start or stop holds the bootstrap's lock at that moment. Such a caller returns at once
  and a daemon thread `skillroy-boot-stop` carries the stop out after the operation in progress has finished. While a
  start is in progress the set of forbidden threads is not known yet, so every caller of `stop()` is then treated as
  one that must not wait: a plain host-thread stop during another thread's start returns at once and the stop runs
  after that start (before 1.x it waited up to the start timeout plus 5 s). `start` keeps its documented blocking;
  use `startAsync` where that is not acceptable.
- *S31.* When an agent closes by itself (`agent.disable`, a production-marker refusal), the bootstrap forgets it in
  the runtime's closed hook: no static of the bootstrap keeps the closed runtime, its loaders, modules, event ring or
  host context reachable (one `AtomicReference` holds everything, and the hook does a compare-and-clear, so a hook of
  an older agent never clears a newer one; the loader is collectable after a self-close, proven with a weak
  reference).
- *S32.* A close that overlaps a start leaves nothing behind: the open check runs before the bind, so a start that
  has already lost does not bind the port; a listener bound by a failing start is closed (`ControlServer.start`
  closes its socket when anything after the bind fails); token file and shutdown hook are written or registered under
  the lifecycle lock with a closing check; module loaders and the bootstrap's release hook are released only once
  both the close and the start thread have ended (a module `install` that never returns keeps its loader open and one
  warning is logged). On a timeout or an interrupt of the host's wait the agent is closed first, then the start thread
  is interrupted.

### 2.4 Options

Sources, lowest to highest precedence: defaults; system properties `skillroy.agent.<key>`; the options file named
by `t=`/`config=` (Java properties format, UTF-8); the agent argument string `k=v;k=v` (a segment without `=` is a
flag with the value `true`) or, in EMBEDDED mode, the `Properties` passed to `EmbeddedAgent.start`. Unknown keys are
kept and are visible to modules through `AgentContext.options()`, the snapshot taken at start.

**A20 live options (1.x, built 2026-10-02).** `AgentContext.option(key)` (a default method of the interface)
returns the value in that start-time snapshot if the key is present there, else the *current* value of the system
property `skillroy.agent.<key>`, else null. A module thereby sees an option the host sets after the agent started.

Keep the argument string short and put everything else in the options file. The attach protocol limits
`<jar path>=<options>` as one argument. Measured on Java 8 and Java 21 targets: up to 1024 characters arrive
intact; from 1025 the load is refused with an `IOException` on the attaching side and `agentmain` is never called
(a refusal, not a truncation). `AttachSession` refuses 1024 bytes or more before attaching and passes only
`t=<options file>`; the agent logs a warning for an argument string of 1024 bytes or more.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | must be `true` or the agent does nothing. JAVAAGENT: only the system property `skillroy.agent.enabled` or the environment variable `SKILLROY_AGENT_ENABLED`; neither the agent argument string nor the options file it names can enable it (both may reach, through `JAVA_TOOL_OPTIONS`, JVMs that never asked for the agent). EMBEDDED: also `enabled=true` in the `Properties` passed to `EmbeddedAgent.start`. ATTACH: also `enabled=true` in the attach argument string or in the options file, because attaching is itself a deliberate act by a same-user process |
| `t` / `config` | – | options file; in controller-initiated loads it also carries `token` and `nonce` |
| `token` | generated | the secret an agent and its controller share; if absent the agent generates 32 random bytes (hex) and writes them to `tokenFile`. A token supplied without `tokenFile` is written to the default token file and deleted on close; a supplied `token` + `tokenFile` pair is left untouched. **H2 (1.x, built 2026-10-02):** it is the key of the request and reply MACs and never travels (§5 "Authentication") |
| `tokenFile` | `<discoveryDir>/skillroy-<pid>-<startMs>.token` | written with owner-only permissions on a file system with POSIX attributes. **S7/S15 (1.x, built 2026-10-02; not verified on Windows):** owner-only through an ACL where the file system has ACLs, and refused where it has neither, see §2.5. **S6/S28 (1.x, built 2026-10-02):** discovery reads a token file only inside the discovery directory it is reading and only under the default name (§7), so an agent given a `tokenFile` elsewhere is reached with `--port` and `--token-file` |
| `nonce` | generated | echoed by `/ping`; lets the controller prove it reached the agent it loaded |
| `port` | `0` | loopback port; `0` = ephemeral |
| `home` | directory of the bootstrap jar | agent home |
| `modules` | – | extra module jar paths, comma-separated, in addition to `home/modules/*.jar` |
| `optionalModules` | – | **(Phase 6, built 2026-10-09, HK; P6 §2.1, D114.)** file stems of jars under `<home>/optional/` to load as module jars, comma-separated (`skillroy-agent-hooks`); loaded between `<home>/modules/*.jar` and the `modules` paths. A stem that is not a plain file name, names no jar, or is given without a home, and a jar without a service file, is a `FAILED` record (S14). The `;` of the argument string still separates options: `optionalModules=skillroy-agent-hooks;discoveryDir=…` |
| `hooks.spyJar` | `<home>/skillroy-agent-spy.jar` | **(Phase 6, built 2026-10-09, HK; P6 §3.7.)** the jar the hooks module appends to the bootstrap search path at its first install, after the content check of P6 §3.2 step 3; read live (A20) |
| `hooks.maxClassBytes` | `262144` | **(Phase 6, HK; P6 §3.7.)** a class whose class file is larger is never retransformed; also the byte bound of one retransformation batch; 1024 to 16 MiB |
| `hooks.maxMethods` | `2000` | **(Phase 6, HK; P6 §3.7.)** a class declaring more methods and constructors is never retransformed; 1 to 65535 |
| `hooks.maxClasses` | `64` | **(Phase 6, HK; P6 §3.7.)** the most classes the hooks' ledger holds; 1 to 1024 |
| `hooks.detachBudgetMs` | `8000` | **(Phase 6, HK; P6 §3.5, §3.7.)** the restore budget of the hooks module's uninstall; 1000 to 9000 |
| `hooks.capture.maxBytes` | `16384` | **(Phase 6, HK; P6 §4.3.)** the input budget of one `CAPTURE` event's encoding, also its output bound; 1024 to 1 MiB |
| `mainClassGuard` | – | JAVAAGENT only: start only if the system property `sun.java.command` names this launch target (lets `JAVA_TOOL_OPTIONS` inject the agent into one JVM of a process tree). **S17 (1.x, built 2026-10-02):** the guard must match the whole launch target (the main class, or the jar path for `-jar`): the command, trimmed, equals the trimmed value or continues with white space directly after it. A value that is only a prefix of a longer main-class name does not match |
| `readOnly` | `false` | reject primitives whose spec is `mutating` **or `script`** (Phase 6 security fix S42, A44, built 2026-10-09, SR; P6 D156: a `script` primitive evaluates a caller's expression in the target, `web.evaluate` and `web.waitFlag`) |
| `discoveryDir` | `${java.io.tmpdir}/skillroy-<user>` | where discovery and token files are written; a per-user directory, see §2.5. **S8/S19 (1.x, built 2026-10-02):** when the option is absent and the default is occupied by another user, the fall-back `<user.home>/.skillroy/run` is used (§2.5); a directory named explicitly is never substituted |
| `readyTimeoutMs` | `120000` | how long to wait for `ReadinessSource`s before reporting `target.ready` UNAVAILABLE (the agent keeps polling, §5); at most one day |
| `eventRing` | `10000` | event ring-buffer size; at most 1000000 |
| `eventRingChars` | `67108864` | **(Phase 6 security fix S43, A45, built 2026-10-09, SR.)** the ring is bounded by the total characters of its events' payloads as well as by `eventRing` (64 Mi characters by default; 1 Mi to 1 Gi): the oldest events leave first, and an event whose own payload exceeds the bound is published with `truncated: true` and each of its strings cut to 1/64 of the bound (or, when still over, with the payload `{truncated: true}` alone). A payload's size is the sum of its keys' and strings' characters and 8 for every other value |
| `httpThreads` | `32` | the most threads the control server's pool may have (2 to 1024), see §5. **H1 (1.x, built 2026-10-02):** with one thread per connection it is also the most connections that can be open; the `WORKER` and `CALLER` pools are sized from it (S30, §5) |
| `httpIdleTimeoutMs` | `120000` | **H1 (1.x, built 2026-10-02).** How long an authenticated kept-alive connection may wait idle for its next request head before the agent closes it (§5 "Control server"). Validated at start to 100 to 3600000 ms (one hour at most, because an idle connection holds one of the `httpThreads` threads; `AgentRuntime`, confirmed against the code 2026-10-02). The default is deliberately longer than the controller's HTTP client keep-alive (30 s), so that the client, not the agent, retires an idle connection and a request never meets a connection the agent has just closed |
| `redact` | – | a regular expression (case-insensitive) naming further secret keys, see "Secret redaction" in §5; validated and read at start |
| `allowFailedModules` | `false` | **S14 (1.x, built 2026-10-02).** `true` lets the agent become ready, and so serve mutating calls, although a module failed to load or install and no `ProductionMarker` is registered (§5 "Modules"). Must be `true` or `false` |
| `requireProductionMarker` | `false` | **S14 (1.x, built 2026-10-02).** `true` makes the agent refuse to serve (as for a marker match) when no `ProductionMarker` is registered at the time of the check. A domain layer sets it in its options file, so the rule holds even when its own module fails to load. Must be `true` or `false` |
| `byteman.jar` | – | **Phase 6, built 2026-10-09, BW (P6 §3.7, §7.4).** The user-supplied Byteman jar the optional Byteman module loads on the first confirmed `rule.submit`: an absolute path to a readable jar whose manifest names `Premain-Class: org.jboss.byteman.agent.Main`, in a directory no other user may write to and itself not writable by others. No default and no environment fallback: without it `rule.engine` is `UNAVAILABLE("no byteman.jar configured")`. In an agent argument string it follows `optionalModules=…;` like any option. The module also reads `hooks.maxClasses`, `hooks.maxClassBytes`, `hooks.maxMethods`, `hooks.detachBudgetMs` and `hooks.capture.maxBytes` (P6 §3.7) as the bounds of one rule |

No option switches `TCP_NODELAY`: amendment A24 (an `httpNoDelay` option and a launcher flag for the JDK HTTP server)
is withdrawn, because H1 replaced that server (§5).

**Token reuse.** A supplied `token` that outlives one agent instance (an options file read by a relaunched JVM, a
`token` + `tokenFile` pair) used to be a credential a listener on a freed port could capture. **H2 (1.x, built
2026-10-02)** removes that exposure: the token never travels, and nothing captured from one agent instance is valid
against the next, because every start has a new session id (proven end to end: a request signed for a disabled
agent's session is 401 on the agent that replaces it, §10 check 16).

### 2.5 Discovery

On start the agent sets the system properties `skillroy.agent.port`, `skillroy.agent.nonce`,
`skillroy.agent.tokenFile`, `skillroy.agent.protocol`, `skillroy.agent.loadMode`, `skillroy.agent.discoveryFile`,
`skillroy.agent.session` and writes `<discoveryDir>/skillroy-<pid>-<startMs>.json` (owner-only, written atomically):

```json
{"pid":4242,"startTimeMs":1790000000000,"port":51234,"tokenFile":"/tmp/skillroy-alice/skillroy-4242-1790000000000.token",
 "nonce":"…","sessionId":"<32 lower-case hex characters>","protocol":"0.2","loadMode":"JAVAAGENT",
 "mainCommand":"com.example.Main"}
```

**H2 (1.x, built 2026-10-02):** `sessionId` (the field name is `Protocol.KEY_SESSION_ID`) and the property
`skillroy.agent.session` (`Protocol.PROP_SESSION`) are new; both are removed on close. The session id is not a secret
(§5 "Authentication"); the bootstrap never reads `session` back as an option.

**The discovery directory is per user.** The default is `${java.io.tmpdir}/skillroy-<user>`, where `<user>` is the
`user.name` system property with every character outside `[A-Za-z0-9._-]` dropped (a missing property gives the
empty string, so the directory is `skillroy-`), or the fall-back below. The agent and the controller
(`Discovery.defaultDir()`) apply the same rule character by character, so both name the same directory without being
told. The agent creates the directory, and any parent it has to create, with `rwx------`.

On a file system with POSIX attributes the agent refuses to write token or discovery files into a directory that
another user owns, that group or others may write to, or that is reached through a symbolic link another user owns
(elsewhere the check is skipped). Whoever controls that directory could swap or plant discovery files and lure a
controller to a different agent. A refusal is reported in `skillroy.agent.error` and in the agent log, and then:

- with a **generated** token the agent does not start (the token could not be delivered, so nobody could
  authenticate);
- with a **controller-supplied** token the agent serves, writes nothing into that directory and reports why.

A directory that someone made group-writable by hand is refused, never repaired. The controller applies the same
check before it reads a discovery directory and before it writes an options file there (§7). Two small differences
remain: the agent compares owners by uid where it can and the controller by principal name, and a controller that
cannot determine its own user skips the owner part.

**S7/S15 file systems without POSIX attributes (1.x, built 2026-10-02; not verified on Windows: no Windows host was
available, the ACL judgement is a pure function unit-tested on built ACL values, and the refusal is tested on a zip
file system).** Three kinds of file system, by the same rules on both sides (the agent's and the controller's
`SecureFiles`):

- POSIX attributes (Linux, macOS): directories are created `rwx------` and files `rw-------`; a directory is trusted
  when the current user owns it (and a symbolic link naming it) and group and others cannot write to it.
- ACLs without POSIX attributes (Windows): "me" is the owner of a file the process has just created, not a lookup of
  `user.name`. The directory and every token, discovery and options file are created with an ACL of one ALLOW entry
  for "me" (inherited by the directory's children), and a file's ACL is read back after creation; any other ALLOW
  entry on a file deletes it and refuses. An existing directory is trusted only if its owner is "me" and no ALLOW
  entry gives anybody other than "me", the system account or the administrators group the right to add, delete or
  rename entries or to change its ACL, owner or attributes. If the ACL or the owner cannot be read, or a built-in
  principal cannot be resolved while such entries exist, the directory is refused. It is never repaired.
- Neither (for example FAT): nothing can be restricted or checked, so nothing is written; the agent refuses a
  generated token, as for an untrusted directory (with a controller-supplied token it serves and writes nothing), and
  the controller ignores the directory.

On the controller, a discovery or token file must be owned by "me" on ACL file systems too (`ownedByCurrentUser`
fails closed without a POSIX view).

**S8/S19 fall-back directory (1.x, built 2026-10-02).** Any local user can create `${java.io.tmpdir}/skillroy-<user>`
first on a shared temporary directory, and two user names can reduce to the same `<user>`; the remedy used to be an
explicit `discoveryDir` on both sides. Built: one deterministic fall-back, computed by the same rule on both sides
(the agent's `SecureFiles.defaultDiscoveryDir` and the controller's `Discovery.defaultDir`) and only from system
properties both sides share (no environment variable):

1. candidate (a) is `${java.io.tmpdir}/skillroy-<u>`, where `<u>` is `user.name` with every UTF-16 unit outside
   `[A-Za-z0-9._-]` dropped (null gives ""; S19's injective encoding was not adopted, so that the rule stays
   identical on both sides and unchanged for everyone who has no squatter);
2. candidate (b) is `${user.home}/.skillroy/run`. It is a candidate only when `user.home` is non-null, non-empty,
   not `"?"`, absolute and an existing directory; missing parents of (b) are created `rwx------`;
3. (b) is chosen exactly when it is a candidate and `Files.getOwner(a, NOFOLLOW_LINKS)` succeeds with an owner that
   is not the current user (a foreign directory, file or symbolic link). Failing to read the owner means (a).
   "Current user" on POSIX file systems is decided by uid where it is known, else by name; elsewhere by the principal
   of `user.name` (falling back to the owner of a new file), compared by equality or by name. The controller compares
   against the principal looked up from `user.name`; the two sides disagree only if `user.name` is overridden so
   that it no longer names the process's own user;
4. an explicit `discoveryDir` is never substituted;
5. when (a) was chosen but cannot be prepared because another user owns it by then (a lost race), the agent switches
   to (b) at that point, and re-derives its default token-file name from it;
6. a substitution is logged and also recorded in `skillroy.agent.error` as "default discovery directory <a> is owned
   by <owner>; using <b>" (the specification said the log only, because a value in `skillroy.agent.error` reads as a
   failed start; built with the property as well, so that a controller or operator sees why the files are elsewhere;
   the agent serves normally);
7. a directory the user owns but that group or others may write to is refused, never replaced by (b): that is not
   another user's doing. The directory chosen must pass the trust check above; the refusal behaviour is unchanged
   when it does not, or when there is no (b).

The controller, when no directory is named, reads every trusted candidate and merges what it finds (so an agent that
fell back is found even after the squatter went away); `TargetLauncher` and `AttachSession` always pass the resolved
absolute directory to the agent as `discoveryDir`. The start-time fall-back of item 5 is unit-tested on the rule
only: proving it needs a directory owned by another OS user, which the test environments do not have (the test is
skipped there).

A discovery file is stale when no live process has that pid and start time. The discovery file and a token file the
agent owns are deleted on clean shutdown (`agent.disable`, `EmbeddedAgent.stop`, a JVM shutdown hook) and the
discovery system properties are cleared or restored (S9 below). Files of a JVM that was killed stay behind; readers
skip them, and A23 removes them. Failing to write the discovery file does not stop the agent: a controller that
launched or attached it reads the system properties instead.

**A23 stale files, agent part (1.x, built 2026-10-02).** On start, once the discovery directory has passed its trust
check, the agent deletes the regular files `skillroy-<pid>-<startMs>.json` and `.token` in it that carry **its own
pid** and a different start time: they belong to an earlier JVM that had the same pid. It never touches another pid's
files (on Java 8 it cannot tell portably whether that process is alive), directories, symbolic links or names that do
not match exactly. A failure to delete is logged, never thrown. The controller's part is in §7.

**S9 system properties on close (1.x, built 2026-10-02).** `port`, `nonce` and `tokenFile` are also option keys, so
an operator may have set `skillroy.agent.port` and the others before the agent started. On close those three are
restored to the values they had before the agent first published (cleared if they had none); `protocol`, `loadMode`,
`discoveryFile` and `session` are cleared; nothing is touched when the agent never published (a start that failed
earlier). A second start in the same JVM therefore keeps a fixed port and token file (proven through the real
bootstrap: same port again, no copy of the operator's token, token file untouched).

**Windows (Phase 6, built 2026-10-09, BW; P6 §9.4, D139; documentation, not verified on a Windows host).** On
Windows the controller must run as the same user, in the same logon session and at the same integrity level as the
target, with matching bitness; attaching across logon sessions is not supported, and `JAVAAGENT` is the path for
services and for CI (control-channel §F28, attach §F11). Detecting the session and refusing with a tailored message is
not built (it needs Win32 calls the controller does not make); the failure text is recorded when the host exists.
`ATTACH` on Windows is best effort: `VirtualMachine.list()` (same user), the discovery files, the 5 s start-up margin
kept (a safety net for a signal Windows does not send, and the JDK's Windows client injects a thread into a JVM that
may still be starting; measured on the host, then decided), the S18 rule for `java.exe` and `javaw.exe`. The
`tasklist` / `/proc/<pid>/cmdline` pid fallback of design §2.2 is dropped. `LaunchedTarget.close()` asks for a normal
termination first, but `Process.destroy()` is forcible on Windows, so a test launch there leaves a discovery and a
token file behind for A23's clean-up (`Discovery.removeStale`, run by `Discovery.list(dir, true)`). The ACL branch of
S7/S15 above is now taken by every owner-only file and directory the controller writes (§7, `SecureFiles`).

---

## 3. `skillroy-protocol` (package `ai.skillroy.testjava.protocol`)

Pure Java 8, no dependencies, shared by agent and controller.

```java
public final class Protocol {
    public static final String VERSION = "0.2";           // major "0"; /ping rejects a different major
    // H2 (1.x, built 2026-10-02): was "0.1". The major stays 0, so /ping still accepts a 0.1 peer's version; nothing
    // was ever released on 0.1.
    public static final String PATH_RPC = "/rpc", PATH_EVENTS = "/events", PATH_SCHEMA = "/schema",
                               PATH_CAPABILITIES = "/capabilities", PATH_PING = "/ping";
    public static final String PROP_PREFIX = "skillroy.agent.";   // + port|nonce|tokenFile|protocol|loadMode
                                                                  //   |discoveryFile|session|enabled|error
    public static final String PROP_SESSION = PROP_PREFIX + "session";   // H2 (1.x, built 2026-10-02)
    public static final String KEY_SESSION_ID = "sessionId";      // H2: the field in the discovery file and /ping body
    public static final int MAX_BATCH = 100;                      // H3 (1.x, built 2026-10-02): see POST /rpc in §5
    public static final String ENV_ENABLED = "SKILLROY_AGENT_ENABLED";
}

// H2 (1.x, built 2026-10-02). The one place where MACs are computed, so that agent, controller and every test fake
// agree. Java 8, javax.crypto.Mac "HmacSHA256" and java.security.MessageDigest "SHA-256" only. Key: the token's
// UTF-8 bytes. Input: a label and the listed fields joined with a single "\n", encoded as UTF-8 (the formats rule a
// line break out of every field):
//   "hello\n<clientNonce>"
//   "req\n<sessionId>\n<reqNonce>\n<METHOD>\n<pathAndQuery>\n<bodySha256>"
//   "resp\n<sessionId>\n<reqNonce>\n<status in decimal>\n<bodySha256>"
// Output: 64 lower-case hex characters. Comparison: constant-time (MessageDigest.isEqual). Stateless; a Mac and a
// MessageDigest per call. See §5 "Authentication".
public final class Auth {
    public static final String SCHEME = "Skillroy-HMAC";              // Authorization scheme; WWW-Authenticate value
    public static final String HEADER_AUTHORIZATION = "Authorization", HEADER_WWW_AUTHENTICATE = "WWW-Authenticate";
    public static final String HEADER_MAC = "X-Skillroy-Mac";         // reply header: the response MAC
    public static final String HEADER_SESSION = "X-Skillroy-Session"; // reply header: the agent's session id (the
                                                                      // specification had a request header
                                                                      // X-Skillroy-Body-Sha256 instead; see §5)
    public static final String PING_NONCE_PARAM = "nonce";            // the query parameter of GET /ping
    public static final int MIN_NONCE_LENGTH = 16, MAX_NONCE_LENGTH = 64, SESSION_ID_LENGTH = 32,
                            HEX_DIGEST_LENGTH = 64;
    public static final int MAX_AUTHORIZATION_LENGTH = 256;           // longest header value parsed (formatted: <= 154)
    public static final int MAX_PATH_AND_QUERY_LENGTH = 16384;        // longest request target a MAC covers
    public static final String EMPTY_BODY_SHA256 = "e3b0c442…b855";   // sha256hex of zero bytes: the hash of no body
    public static String newNonce();                                  // 16 SecureRandom bytes as 32 lower-case hex
    public static String newSessionId();                              // the same form; one per agent start
    public static boolean isValidNonce(String text);                  // 16 to 64 characters of [A-Za-z0-9_-]
    public static boolean isValidSessionId(String text);              // exactly 32 lower-case hex characters
    public static boolean isHexDigest(String text);                   // exactly 64 lower-case hex characters
    public static String sha256hex(byte[] data);  public static String sha256hex(byte[] data, int offset, int length);
    public static String helloMac(String token, String clientNonce);                  // HMAC("hello", clientNonce)
    public static String requestMac(String token, String sessionId, String reqNonce, String method,
                                    String pathAndQuery, String bodySha256);          // HMAC("req", …)
    public static String responseMac(String token, String sessionId, String reqNonce, int statusCode,
                                     String bodySha256);                              // HMAC("resp", …)
    public static String helloAuthorization(String token, String clientNonce);        // "Skillroy-HMAC mac=<hex>"
    public static String requestAuthorization(String token, String sessionId, String reqNonce, String method,
                                              String pathAndQuery, String bodySha256);
                                                                      // "Skillroy-HMAC nonce=<n>, mac=<hex>"
    public static String formatAuthorization(String reqNonceOrNull, String mac);
    /** The parsed Authorization value under the strict grammar of §5; IllegalArgumentException (never echoing the
     *  value) when missing, longer than 256, another scheme or malformed. */
    public static Credentials parseAuthorization(String fieldValue);
    public static String onlyValue(List<String> fieldValues);         // the single value; null if absent or repeated
    public static boolean verifyHello(String token, String clientNonce, Credentials c);
    public static boolean verifyRequest(String token, String sessionId, Credentials c, String method,
                                        String pathAndQuery, String bodySha256);
    public static boolean verifyResponse(String token, String sessionId, String reqNonce, int statusCode,
                                         String bodySha256, String macFieldValue);   // the verify methods never throw
    public static final class Credentials { public String nonce(); public String mac(); public boolean isHello(); }
}
// Validation (IllegalArgumentException from the computing and formatting methods): token not null or empty; a
// method of 1 to 16 characters A-Z; pathAndQuery starting with "/", visible ASCII 0x21-0x7E only, at most 16 KB, raw
// as on the request line; status 100 to 599; a body hash of 64 lower-case hex. Test vectors computed independently
// are pinned in AuthTest, including a non-ASCII token (the key is UTF-8).

// A17 (1.x, built 2026-10-02). The secret-key rule, moved here from the core's Redactor so that the controller
// applies the same rule to fixture fields (§8.5).
public final class SecretKeys {
    public static final String DEFAULT_KEYS;          // the regex of §5 "Secret redaction" (a String, compiled
                                                      // case-insensitively by its users; the specification said
                                                      // a Pattern)
    public static final String REDACTED = "<redacted>";
    public static boolean looksSecret(String key);    // DEFAULT_KEYS finds a match in key, ignoring case; null: false
}

/** Minimal JSON codec. Objects = LinkedHashMap<String,Object>, arrays = ArrayList<Object>, strings, Boolean, null,
 *  integral numbers = Long, other numbers = Double. Writer accepts any Map/Iterable/array/Number/CharSequence/Boolean/
 *  Enum (name) and writes other objects as their toString() string. Rejects NaN/Infinity. Deterministic key order
 *  (insertion order). */
public final class Json {
    public static Object parse(String text) throws JsonException;
    public static Map<String,Object> parseObject(String text) throws JsonException;
    public static String write(Object value);
    public static String writePretty(Object value);        // 2-space indent, "\n" line ends, for committed files
    public static String writeCanonical(Object value);     // keys sorted recursively; used for hashing
}
public final class JsonException extends RuntimeException { }

/** Typed, null-tolerant accessors over parsed JSON; throws RpcException(INVALID_PARAMS) naming the offending key. */
public final class JsonView {
    public static JsonView of(Map<String,Object> map);
    public Map<String,Object> raw();
    public boolean has(String key);
    public String str(String key);                 public String str(String key, String dflt);
    public String requireStr(String key);
    public long num(String key, long dflt);        public long requireNum(String key);
    public double dbl(String key, double dflt);
    public boolean bool(String key, boolean dflt);
    public JsonView obj(String key);               // empty view if absent
    public List<Object> list(String key);          // empty list if absent
    public Object get(String key);
}

public enum LoadMode { EMBEDDED, JAVAAGENT, ATTACH }
public enum CapabilityState { OK, DEGRADED, UNAVAILABLE }

public final class Capability {                      // immutable
    public Capability(String id, CapabilityState state, String reason, String strategy);
    public String id(); public CapabilityState state(); public String reason(); public String strategy();
    public Map<String,Object> toJson();  public static Capability fromJson(String id, Map<String,Object> json);
}

public final class ErrorCodes {
    public static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601,
        INVALID_PARAMS = -32602, INTERNAL_ERROR = -32603,
        TIMEOUT = -32001, FORBIDDEN_THREAD = -32002, STALE_HANDLE = -32003, MODAL_OPEN = -32004,
        INPUT_REJECTED = -32005, NOT_FOUND = -32006, AMBIGUOUS = -32007,
        CAPABILITY_DEGRADED = -32010, CAPABILITY_UNAVAILABLE = -32011,
        HOOK_VERIFY_FAILED = -32020, CONFIRM_REQUIRED = -32030, READ_ONLY = -32031, NOT_READY = -32032,
        BUSY = -32033;                                   // S30 (1.x, built 2026-10-02): see "Bounded dispatch" in §5
    // (Phase 4, built 2026-10-07; P4 §8.1: SCRIPT_ERROR = -32008, a script the agent ran in a page threw;
    // data {browser, message, stack})
    // (Phase 6, built 2026-10-09, HK; P6 §4.5: HOOK_CONFLICT = -32021, another hook catalogue is installed and
    // replace is false; RULE_REJECTED = -32022, a Byteman rule the lint or the type check refused; HOOK_VERIFY_FAILED
    // is raised for the first time, by hook.remove, data {classes: [{class, loader, bytesBefore, bytesAfter,
    // foreignEvents}]})
    public static String name(int code);
}

public final class RpcError {                         // immutable; JSON {code, message, data}
    public RpcError(int code, String message, Map<String,Object> data);
    public int code(); public String message(); public Map<String,Object> data();
    public Map<String,Object> toJson();  public static RpcError fromJson(Map<String,Object> json);
}
public class RpcException extends RuntimeException {
    public RpcException(int code, String message);
    public RpcException(int code, String message, Map<String,Object> data);
    public RpcError error();
}

public final class Event {                            // immutable
    public Event(long seq, long ts, String topic, String source, String correlationId, String traceparent,
                 Map<String,Object> payload);
    public long seq(); public long ts(); public String topic(); public String source();
    public String correlationId(); public String traceparent(); public Map<String,Object> payload();
    public Map<String,Object> toJson();  public static Event fromJson(Map<String,Object> json);
}

/** Comma-separated globs; '*' matches any run of characters including dots; empty/null filter matches everything. */
public final class TopicFilter {
    public static TopicFilter parse(String commaSeparatedGlobs);
    public static TopicFilter of(Collection<String> globs);
    public boolean matches(String topic);
    public String toString();                         // round-trips through parse
}

public final class Topics {                           // generic topics; domain modules add their own
    public static final String AGENT_STARTED = "agent.started", AGENT_STOPPING = "agent.stopping",
        MODULE_INSTALLED = "module.installed", TARGET_READY = "target.ready",
        WINDOW_OPENED = "window.opened", WINDOW_CLOSED = "window.closed",
        INPUT_ACKED = "input.acked", INPUT_REQUESTED = "input.requested", INPUT_REJECTED = "input.rejected",
        PROMPT_WAITING = "prompt.waiting", STATE_CHANGED = "state.changed", TRACE_MARK = "trace.mark", GAP = "gap";
    // (Phase 4, built 2026-10-07; P4 §4.3: the constants WEB_LOAD, WEB_READY, WEB_CONSOLE, WEB_TAP, WEB_CLOSED and
    // WEB_BROWSERS_CHANGED, published with source "web": web.load {browser, url, kind, frameKey?, error?},
    // web.ready {browser, url, signal, name?}, web.console {browser, level, text, source?, line?, truncated?},
    // web.tap {browser, name, detail?, truncated?}, web.closed {browser}, web.browsers.changed {added, removed})
    // (Phase 6, built 2026-10-09, HK; P6 §4.4: HOOK_PREFIX = "hook." (every hook's topic, hook.<id> by default),
    // HOOKS_INSTALLED, HOOKS_REMOVED, HOOKS_STUBBED (source "hooks"), RULE_FIRED, RULES_CHANGED (the Byteman module))
}

// (Phase 6, built 2026-10-09, HK; P6 §4.1) package ai.skillroy.testjava.protocol.hook, the wire types of the hook.*
// primitives, shared by the agent's hooks module and the controller's catalogue: HookMode {OBSERVE, CAPTURE, STUB},
// HookState {INSTALLED, FIRING, STALE, ERROR}, HookSpec (with HookTarget and HookCapture; strict fromJson, hash(),
// violation() for the name rules), HookProbe, HookStatus, HookHealth (lenient fromJson, as every answer type).

/** One entry of GET /schema. */
public final class PrimitiveDescriptor {
    public PrimitiveDescriptor(String name, String description, Map<String,Object> paramsSchema,
        Map<String,Object> resultSchema, String thread, long timeoutMs, String capabilityId,
        boolean mutating, boolean destructive);
    // (Phase 6 security fix S42, A44, built 2026-10-09, SR; P6 D156) a second constructor with a trailing
    // boolean script: "evaluates a caller's expression in the target" (web.evaluate, web.waitFlag); script() reads it.
    // JSON key "script", optional, default false (Wire.optBool), written only when true, so that an older reader and
    // the catalogue of an older agent are unchanged; the read-only gate (§5, gate 2) refuses it as it refuses mutating.
    /* getters */  public Map<String,Object> toJson();  public static PrimitiveDescriptor fromJson(Map<String,Object> json);
}

/** The "result" member of every successful JSON-RPC response. */
public final class RpcResult {
    public RpcResult(Object value, List<Event> events, long seqBefore, long seqAfter, long durationMs, String thread);
    public Object value(); public List<Event> events(); public long seqBefore(); public long seqAfter();
    public long durationMs(); public String thread();
    public Map<String,Object> toJson();  public static RpcResult fromJson(Map<String,Object> json);
}
```

Wire shape of a result: `{"value":…, "events":[…], "seq":{"before":1041,"after":1043}, "durationMs":41, "thread":"skillroy-lending-worker"}`.
`events` holds the events published on the calling thread during the call (same `correlationId`), capped at 50.
`seq.before` is the bus sequence number immediately before the primitive ran; waiting "since `seq.before`" can
therefore never miss a consequence of the call.

**A33 rejected inputs (documentation only, decided 2026-10-02; nothing to build).** For an input sent through a
primitive, the `INPUT_REJECTED` error is the whole contract: no event is published for it. The topic
`Topics.INPUT_REJECTED` (`input.rejected`) is for inputs the application rejects on its own (typed or clicked in the
window, or arriving from a device); a module publishes it when it can observe such a rejection.

**A34 `_meta.secretParams` (Phase 2, built 2026-10-02; P2 §11.4).** The request `_meta` object (§5 "Dispatch") gains
an optional key `secretParams` (`Protocol.META_SECRET_PARAMS`): a list of names of **top-level** params of that
request whose values are secret for the purposes of what the agent writes *about* the call. Absent or empty means
nothing; a value that is not an array of at most 100 strings is `INVALID_PARAMS` with
`data {primitive, param: "_meta.secretParams"}` and the message "_meta.secretParams must be an array of at most 100
parameter names" (the specification said "a list of strings"; the bound of 100 was added in the build); a name that
is no param of the request, and the name `_meta`, are ignored. Like the other `_meta` keys it is stripped before the
primitive sees `Invocation.args()`, and the primitive receives the named values untouched. What the core does with it
is under "Secret redaction" in §5; the controller sets it through `CallOptions.secretParams` (§7). It exists because
the Phase 1 redaction works by key name, and a secret can travel under a neutral key (the sign-on password is the
`value` of the sample's `lending.input`).

**S13 numeric text (1.x, built 2026-10-02).** Where a number is expected and text is given (`JsonView.num`,
`requireNum`, `dbl`, the `fromJson` readers), text longer than 64 characters after trimming is not a number (a
pre-check refuses raw text over 80 characters before it is even trimmed): the accessor reports the usual type error
(`INVALID_PARAMS`; `Event.fromJson` throws `JsonException`) without parsing it. `BigInteger` and `BigDecimal` values
are converted directly, never through their text. That bounds the cost of a hostile digit string (a 1 MB one is
refused in well under a second).

---

## 4. `skillroy-agent-api` (package `ai.skillroy.testjava.agent.api`, SPIs in `…agent.api.spi`)

```java
public interface AgentModule {
    String id();                                             // "swing", "data", "lending", …
    String version();
    default Set<String> requires() { return Collections.emptySet(); }   // module ids that must be installed first
    /** Called once at agent start, possibly before the application is initialised. Register primitives, SPIs,
     *  capabilities. Must not touch application singletons. Runs on the agent's start thread while the host's start
     *  call (premain, agentmain, EmbeddedAgent.start) waits for it, bounded by the start timeout: it must not wait
     *  for the event-dispatch thread or for the calling thread, and it must not initialise AWT (in JAVAAGENT mode
     *  it runs before the application's main).
     *  1.x (built 2026-10-02), S12/S16: nor may it initialise java.util.logging. S34: the context class loader of
     *  the thread is the host loader; a module that needs its own loader passes it explicitly. */
    void install(AgentContext ctx) throws Exception;
    /** Called once after every registered ReadinessSource reports ready, on an agent worker thread. */
    default void onTargetReady(AgentContext ctx) throws Exception { }
    /** Reverse of install; must remove every listener it added to the host. Must not throw or block for long. */
    default void uninstall() { }
}

public interface AgentContext {
    Instrumentation instrumentation();        // null when not available (EMBEDDED without self-attach)
    LoadMode loadMode();
    Map<String,String> options();             // merged agent options at start, unmodifiable
    default String option(String key);        // A20 (1.x, built 2026-10-02): the start-time value, else the current
                                              // system property skillroy.agent.<key>, else null
    ClassLoader hostClassLoader();
    Object hostContext();                     // what the host passed to EmbeddedAgent.start; null in other load modes
    PrimitiveRegistry primitives();
    EventBus events();
    HandleRegistry handles();
    ThreadBroker threads();
    CapabilityRegistry capabilities();
    SpiRegistry spi();
    AgentLog log();
    boolean readOnly();
    boolean targetReady();
    // (Phase 6, built 2026-10-09, HK; P6 §3.6, D118) three defaults, so that every test fake compiles unchanged:
    default HookEngine hooks();               // spi().first(HookEngine.class), null without the hooks module
    default boolean isSecretKey(String key);  // the core's contexts: the runtime's Redactor rule (SecretKeys, the
                                              // redact option, every RedactionKeys); the default: SecretKeys only
    default String redactText(String text);   // the core's contexts: the Redactor's free-text redaction; the default:
                                              // the text unchanged
}

public interface Primitive {
    PrimitiveSpec spec();
    /** @return a JSON-able value (Map/List/String/Number/Boolean/null). Throw RpcException for protocol errors;
     *  any other exception becomes INTERNAL_ERROR with the exception class and message in data. */
    Object invoke(Invocation invocation) throws Exception;
}

public final class PrimitiveSpec {
    public static Builder builder(String name);          // name = "<namespace>.<verb>[.<sub>]", lower camel segments
    public static final class Builder {
        public Builder description(String text);
        public Builder param(String name, String jsonType, boolean required, String description);
        public Builder paramsSchema(Map<String,Object> jsonSchema);     // alternative to param(...)
        public Builder resultSchema(Map<String,Object> jsonSchema);
        public Builder thread(ThreadPolicy policy);                     // default CALLER
        public Builder timeoutMs(long ms);                              // default 10000
        public Builder capability(String capabilityId);                 // call fails fast when UNAVAILABLE
        public Builder mutating();  public Builder destructive();       // destructive implies mutating, needs confirm
        public Builder script();   // (Phase 6 security fix S42, A44, built 2026-10-09, SR; P6 D156) evaluates a
                                   // caller's expression in the target; refused in read-only mode; implies nothing else
        public PrimitiveSpec build();
    }
    /* getters */  public PrimitiveDescriptor describe();
}

public interface Invocation {
    JsonView args();                           // params without "_meta"
    String correlationId();  String traceparent();
    AgentContext context();
    long remainingMs();                        // time left before the dispatcher reports TIMEOUT
    List<String> secretParams();               // (Phase 4, built 2026-10-07; P4 §4.3, D68: the names the request's
                                               // _meta.secretParams gave (A34), in order, without duplicates,
                                               // read-only; a default method, empty outside the dispatcher)
}

/** Convenience: Primitives.of(spec, inv -> value). */
public final class Primitives {
    public interface Body { Object invoke(Invocation invocation) throws Exception; }
    public static Primitive of(PrimitiveSpec spec, Body body);
}

public interface PrimitiveRegistry {
    void register(Primitive primitive);        // IllegalStateException on duplicate name
    void unregister(String name);
    Primitive find(String name);               // null if absent
    List<PrimitiveSpec> list();                // sorted by name
}

public enum ThreadPolicy {
    CALLER,     // the control-channel worker thread
    EDT_SYNC,   // EventQueue.invokeAndWait, bounded by the timeout; for reads
    EDT_ASYNC,  // EventQueue.invokeLater, returns once posted; for actions (an action may open a modal dialog)
    WORKER,     // an agent-owned daemon worker
    APP         // an application-registered thread supplied by ThreadPolicyProvider; falls back to WORKER
                // A19 (1.x, built 2026-10-02): while the first provider reports available() == false (or its
                // available() throws), a primitive that is not mutating runs on WORKER and a mutating one answers
                // NOT_READY ("application thread not available yet"); ThreadBroker.call and post under APP run on WORKER
}

public interface ThreadBroker {
    /** Runs the task under the policy and waits at most timeoutMs. Throws RpcException(TIMEOUT) on expiry and
     *  RpcException(FORBIDDEN_THREAD) if the calling thread is one a ThreadPolicyProvider forbids. For EDT_ASYNC the
     *  returned value is always null and the call returns when the task has been posted. */
    <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception;
    void post(ThreadPolicy policy, Runnable task);
    boolean isForbiddenThread(Thread thread);
}

public interface EventBus {
    /** Publishes with the calling thread's current correlation id (set by the dispatcher), or none. */
    long publish(String topic, String source, Map<String,Object> payload);
    long lastSeq();
    List<Event> since(long seqExclusive, TopicFilter filter, int limit);
    /** First event with seq > seqExclusive matching filter (and, if non-null, every payloadMatch entry by
     *  String.valueOf equality); null on timeout. */
    Event await(long seqExclusive, TopicFilter filter, Map<String,Object> payloadMatch, long timeoutMs)
        throws InterruptedException;
    AutoCloseable subscribe(TopicFilter filter, EventSink sink);
    long oldestSeq();                          // oldest seq still in the ring
}
public interface EventSink { void onEvent(Event event); }      // must not block

public interface HandleRegistry {
    /** Same object -> same handle while it is alive (identity, weakly held). Handles look like "h12". */
    String handleFor(Object object, String kind);
    Object resolve(String handle);             // RpcException(STALE_HANDLE) if collected or unknown
    <T> T resolve(String handle, Class<T> type);
    void release(String handle);
}

public interface CapabilityRegistry {
    // A18 (1.x, built 2026-10-02): capabilities can be withdrawn. declare returns a handle (it was void); closing it
    // withdraws the capability, but only while that declaration is still the current one (a stale handle, after the
    // id was declared again, does nothing).
    AutoCloseable declare(String id, Probe probe);   // probed after onTargetReady, and again on agent.capabilities{reprobe}
    void undeclare(String id);                 // withdraws it; no effect for an unknown id
    void set(String id, CapabilityState state, String reason, String strategy);
    Capability get(String id);                 // null if never declared, or withdrawn
    Map<String,Capability> snapshot();         // sorted by id; a withdrawn id is not listed
}
public interface Probe { ProbeResult run(AgentContext ctx) throws Exception; }   // an exception = UNAVAILABLE(message)
public final class ProbeResult {
    public static ProbeResult ok();  public static ProbeResult ok(String strategy);
    public static ProbeResult degraded(String reason);  public static ProbeResult unavailable(String reason);
    public CapabilityState state(); public String reason(); public String strategy();
}

public interface SpiRegistry {
    <T> AutoCloseable register(Class<T> type, T implementation);
    <T> List<T> all(Class<T> type);            // registration order
    <T> T first(Class<T> type);                // null if none
}

public interface AgentLog { void info(String msg); void warn(String msg); void warn(String msg, Throwable t);
                            void error(String msg, Throwable t); List<String> tail(int n); }
```

SPIs (`ai.skillroy.testjava.agent.api.spi`):

```java
/** "The application is initialised enough that domain singletons may be touched." Polled every 250 ms. */
public interface ReadinessSource { String id(); boolean isReady(); String describe(); }

/** Supplies the application thread domain calls must run on, and names threads the agent must never block or run on. */
public interface ThreadPolicyProvider {
    String id();
    boolean isForbidden(Thread thread);
    <T> T callOnAppThread(Callable<T> task, long timeoutMs) throws Exception;
    // A19 (1.x, built 2026-10-02): false while the application thread does not exist yet; a throw counts as false
    default boolean available() { return true; }
}

/** The app{...} section of a UI snapshot: current state name, prompt, anything a scenario asserts on. Cheap, no side effects. */
public interface AppStateProvider { String id(); Map<String,Object> appState(); }

/** Names for components whose accessible name is empty (image-only buttons). Called on the EDT. */
public interface NameEnricher { String nameFor(java.awt.Component component); }      // null = no opinion

/** Extra locator strategies beyond the built-in ones. Called on the EDT. */
public interface LocatorResolver {
    Set<String> strategies();                                    // e.g. "action", used as {action:"DUE"}
    List<java.awt.Component> resolve(String strategy, String value, java.awt.Component root);
}

/** The ACTION rung for custom widgets: trigger the widget's semantic action without focus or mouse events. EDT.
 *  The click is posted to the EDT and fire() runs there afterwards, so the click result's `fired` means "posted":
 *  an exception thrown by fire() is logged by the agent, not reported to the caller of the click. */
public interface ActionFirer { boolean canFire(java.awt.Component component); void fire(java.awt.Component component) throws Exception; }

/** Reads application-specific dialogs the generic extractor cannot (custom-painted message boxes). EDT. */
public interface DialogTextExtractor {
    DialogInfo extract(java.awt.Window window);                                      // null = not mine
    // A27 (1.x, built 2026-10-02): for the roots a DialogLocator reports; existing implementations compile unchanged
    default DialogInfo extract(java.awt.Component root) { return null; }
}
public final class DialogInfo {
    public DialogInfo(String extractorId, String title, String text, List<String> buttons);
    /* getters */
}

// A27 (1.x, built 2026-10-02)
/** Dialogs an application draws inside another window (not java.awt.Dialog). Called on the EDT. */
public interface DialogLocator {
    /** Showing in-window dialog roots inside the window, outermost first; empty when none. */
    java.util.List<java.awt.Component> dialogRoots(java.awt.Window window);
    /** True when the dialog blocks input to the rest of its window (the default). */
    default boolean blocksInput(java.awt.Component root) { return true; }
}

// A21 (1.x, built 2026-10-02)
/** Further secret keys a module knows about. Consulted at call time; case sensitivity is the pattern's own. */
public interface RedactionKeys { java.util.regex.Pattern secretKeys(); }

/** In-process, credential-free read channel to the application's own data. A provider implements ONE of the two
 *  forms: open() for sources reachable through JDBC, or query() for sources that only expose their own statement
 *  API (possibly without column names). data.query tries query() first and falls back to open() when it returns null. */
public interface DataSourceProvider {
    String id(); String describe();
    default java.sql.Connection open() throws java.sql.SQLException { return null; }
    default QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs) throws Exception { return null; }
    // S22 (1.x, built 2026-10-02): true = data.query may return this source's driver error text (read per call)
    default boolean exposesErrorText() { return false; }
}
public final class QueryResult {               // immutable
    /** columnNames may be null when the source cannot name its columns: data.query then reports c1..cn.
     *  columnTypes may be null. rows hold JSON-able values or java.sql/java.math/java.util.Date values. */
    public QueryResult(List<String> columnNames, List<String> columnTypes, List<List<Object>> rows, boolean truncated);
    /* getters */
}

/** Peripherals as observable, actionable nodes. */
public interface DeviceAdapter {
    String id(); String kind();
    Map<String,Object> state();
    Set<String> actions();
    Object act(String action, JsonView args) throws Exception;
}

/** Non-null = "this looks like a production system because …"; the agent then refuses to serve and shuts down. */
public interface ProductionMarker { String productionReason(); }

/** Applied to every value data.query returns before it leaves the JVM. */
public interface MaskPolicy { Object mask(String sourceId, String column, Object value); }
// (Phase 6, built 2026-10-09, HK; P6 §4.3) also applied to every encoded scalar a CAPTURE hook copies, as
// mask("hook.<id>", "arg<i>" | "self" | "return", value), and to a Byteman rule's bindings. The thread rule is
// amended: called on agent worker threads, or, for a CAPTURE hook or a Byteman rule's bindings, on the application
// thread that called the hooked method; it must be thread-safe, cheap, and must never block or take a lock of the
// application. A policy that throws there counts as a hook failure and the value becomes <redacted>.

// (Phase 6, built 2026-10-09, HK; P6 §3.6, D118, D153) package …agent.api.spi.hook: HookEngine {probe, install, stub,
// remove, health, catalogue}, registered by the optional hooks module, found with spi().first or AgentContext.hooks();
// package …agent.api.instrument (shared by the hooks and Byteman modules): ClassFileRefs, ByteVerification,
// RetransformBatches.

// (Phase 4, built 2026-10-07; P4 §3: package …agent.api.spi.web adds WebEngineAdapter, one per embedded-browser
// technology, which turns seeds into WebBrowser handles, and the technology-neutral BrowserRegistryAdapter,
// Collection<BrowserSeed> seeds(), for browsers the Swing-tree scan cannot see; a domain module registers either
// with AgentContext.spi() during install)
```

**S34 host code on agent threads (1.x, built 2026-10-02).** Agent threads are daemon threads in the agent's thread
group, and a thread created by host code that an SPI implementation calls inherits both (the SPI Javadoc says so). A
`DataSourceProvider`, `AppStateProvider`, `DeviceAdapter` or `onTargetReady` therefore must not lazily initialise
host infrastructure that starts threads on the calling thread; where that cannot be avoided, the call goes through
`ThreadPolicy.APP` so that it runs on a host thread. The context class loader of every agent thread is the host
loader (§1; after `agent.disable` no live thread has the agent loader as its context loader, proven in a child JVM).

---

## 5. `skillroy-agent-core` behaviour

**Start sequence** (`AgentRuntime.start`; it runs on the thread `skillroy-start` and the caller waits at most 60 s):
resolve options -> check `enabled` -> with a generated token, check or create the token directory (§2.5), so that
an unsafe directory stops the start before a port opens -> create registries -> start the control server ->
register core primitives -> discover modules (`ServiceLoader<AgentModule>` per module jar), order them by
`requires()`, call `install` -> publish discovery (system properties, file) -> publish `agent.started`
-> on the thread `skillroy-ready`: poll `ReadinessSource`s (250 ms) until all are ready; then check every
`ProductionMarker` (a match logs the reason, sets `skillroy.agent.error` to `refused: production marker: …`,
publishes `agent.stopping` and closes the runtime; a marker that throws counts as a match); then call
`onTargetReady` on each module, run all probes, set capability `target.ready`, publish
`target.ready {ready:true, waitedMs}`. With no `ReadinessSource` registered the target is ready immediately. With
no `ProductionMarker` registered the agent serves and logs a warning.

Additions to the start sequence **(1.x, built 2026-10-02)**, in the order they run on `skillroy-start`: the pid
(without a host-name lookup), the JVM start time, the token and nonce, the discovery directory and the token-file
name are resolved there, not on the host's calling thread, whose constructor does no I/O (S20/S29, "Host safety"
below; a pid that cannot be determined fails the start with "cannot determine the JVM pid"); the discovery directory
is prepared and checked, with the S8 fall-back where it applies; the agent's own stale discovery files are deleted
once the directory has passed its check (A23, §2.5); the session id is generated (`Auth.newSessionId()`) and one MAC
is computed with it, so that a JVM whose crypto provider does not load fails the start rather than every request
(H2); then the control server binds. With no `ProductionMarker` registered the target becomes ready only if no module
`FAILED`, see "Modules" (S14).

**Readiness timeout.** When the sources are not all ready after `readyTimeoutMs`, the capability `target.ready`
becomes `UNAVAILABLE` (the reason names the pending sources) and `target.ready {ready:false, reason}` is published
once. Nothing that needs a ready target runs then: no production-marker check, no `onTargetReady`, no probes;
mutating calls and unprobed capabilities keep answering `NOT_READY`. The poll goes on, so a target that becomes
ready later completes the normal sequence and publishes `target.ready {ready:true}`.

**Modules.** An unknown id in `requires()` blocks that module from installing (logged; the agent continues). A
module whose `install` throws gets `uninstall()` called and its primitives, SPIs and new capabilities rolled back;
it is listed as `FAILED` in `agent.info` and the agent keeps serving. A failing `onTargetReady` is logged and
recorded as that module's `readyMessage`. **Module discovery (1.x, built 2026-10-02):** `ModuleLoader` no longer
uses `ServiceLoader`. Sources, in order (the first module with a given id wins): `<home>/modules/*.jar` sorted by
file name, then the paths of the `modules` option, each in its own module loader; then the `AgentModule` service
files visible from the core's own loader (what `ServiceLoader` would find there, so that in-process tests and
single-loader embeddings work), whose providers the loader reads and creates itself. A provider class that a module
jar already named is skipped on that class-path pass, not loaded twice and not failed. (The specification said
`ServiceLoader<AgentModule>` per module jar; built this way because in the EMBEDDED layout a module jar also sits on
the application's class path, where `ServiceLoader` through the host loader hit a `NoClassDefFoundError` for
`AgentModule`, and under S14 that record closed the production gate: the sample's embedded mode never became ready.)
**Phase 6 (built 2026-10-09, HK; P6 §2.1, D114):** a third source sits between `<home>/modules/*.jar` and the
`modules` paths: the jars under `<home>/optional/` that the option `optionalModules` names by file stem, each in its
own module loader; `optional/` itself is never scanned, so an unnamed jar there is not even opened. A stem that names
no jar, or a jar without a service file, is a `FAILED` record. The events of the hooks module (source `hooks`) are
`hook.<id>` (or the spec's `emits`) per hooked call, and `hooks.installed {catalogue: {id, hash}, hooks, touched}`,
`hooks.removed {ids, restored, verified}`, `hooks.stubbed {id, armed, by}` (P6 §4.3).

**A18 withdrawn capabilities (1.x, built 2026-10-02).** The core records who declared or set each capability (a
module, or the core) and withdraws everything a module owns after its `uninstall()` or a failed install; the core's
own capabilities stay. A withdrawn id is no longer listed by `/capabilities` or `agent.capabilities`. A primitive
whose `capability` was withdrawn answers `CAPABILITY_UNAVAILABLE` with `data.reason` "capability withdrawn"
(`data.primitive`, `data.capability`); declaring or setting the id again lifts this.

**S14 the production gate fails closed (1.x, built 2026-10-02).** A `ProductionMarker` lives in a module, so a
module that does not install can take the gate away with it. Built:

- every module-loading problem is a `FAILED` record in `agent.info` (with no module), not only a log line: a
  `modules=` path that does not exist, an unreadable jar, a jar without a service entry and a provider that cannot be
  created or linked are listed with the path or provider name as id and a message;
- when the production-marker check finds **no** marker registered and at least one module `FAILED`, and
  `allowFailedModules` is not `true`, the target does not become ready: `target.ready` is `UNAVAILABLE` with the
  reason "production gate not evaluated: no ProductionMarker registered and module(s) <id: message> FAILED (set
  allowFailedModules=true to serve anyway)", `target.ready {ready:false, reason}` is published once,
  `skillroy.agent.error` carries the reason, and neither `onTargetReady` nor the probes run. Mutating calls keep
  answering `NOT_READY` with that reason; read-only primitives keep working, so `agent.info` can show which module
  failed;
- a marker that is registered and passes lets the agent serve whatever other modules failed (the rule "keeps
  serving" above holds for unrelated modules);
- with `requireProductionMarker=true` and no marker registered at the time of the check, the agent refuses as for
  a marker match: `skillroy.agent.error` is `refused: production marker: no ProductionMarker registered
  (requireProductionMarker=true)`, followed by `; FAILED modules: <id: message…>` when there are any,
  `agent.stopping` is published and the runtime closes;
- the sample registers no `ProductionMarker`, so in the sample a module that fails to load now blocks readiness
  unless `allowFailedModules=true` is set (none fails today; the end-to-end runs serve with the "no ProductionMarker
  registered" warning as before).

Two safety gates, in this order: the `enabled` flag is checked before anything is started (no thread, no port);
`ProductionMarker`s can only be evaluated once the application is initialised, so until they have passed the agent
serves read-only primitives only (see dispatch step 2), and a match shuts the agent down.

**No AWT initialisation by the agent.** In JAVAAGENT mode the agent starts before the application's `main`;
touching AWT there can freeze the application's headless decision or start the toolkit on the wrong thread. No
start, close or readiness path of the core and no core primitive touches AWT: every AWT reference of the core is in
one class, reached only by the EDT thread policies or after a thread-name check. `jvm.info.headless` is the value
of the `java.awt.headless` system property (a string, or null when unset), never
`GraphicsEnvironment.isHeadless()`. The Swing module's part is in §6.

**Host safety before `main` (S12/S16, S20, S29; 1.x, built 2026-10-02).** The rule for AWT extends to other parts
of the JDK that an application configures in its own `main`:

- *No `java.util.logging`.* No start, close or readiness path of the core and no module `install` initialises
  `java.util.logging.LogManager` before the application does; no core class refers to `java.util.logging` or to
  `com.sun.net.httpserver` (a test scans the class files). The JDK HTTP server, which initialised
  `java.util.logging` in `premain` on Java 8, is gone with H1. The integration test checks the effect, not class
  loading: a `-javaagent` child whose `main` installs its own `LogManager` and logging configuration sees both take
  effect (on JDK 25 `ManagementFactory`, which the start path calls, loads the `LogManager` class without
  initialising it, so "not loaded" cannot be asserted; "the application's manager and configuration are in force"
  is).
- *No host-name lookup.* The pid is read without building the runtime's name: `ProcessHandle` (reflectively) where
  it exists, the JVM's own process id or `/proc/self` on Java 8, and `RuntimeMXBean.getName()` only as a last resort,
  on `skillroy-start` inside its 60 s bound (the specification's separate 2 s thread for it was not built; a pid of
  -1 fails the start). The listener binds to a literal address.
- *Nothing on the host's calling thread.* The thread that calls `premain`, `agentmain` or `EmbeddedAgent.start` only
  resolves options, creates the runtime's objects and threads and waits (bounded) for `skillroy-start`; it resolves
  no name, initialises no logging and opens no socket itself.
- *Residual, stated and not removed.* In JAVAAGENT mode the listener is bound before `main` (on `skillroy-start`,
  while `premain` waits), which initialises the JVM's network classes. An application that sets
  `java.net.preferIPv4Stack` or `java.net.preferIPv6Addresses` in its `main` must pass them as `-D` flags instead
  when the agent is loaded that way. The agent records both properties before it binds and logs a warning at
  `target.ready` when the application has changed either since, naming the property and the `-D` form to use. (The
  verified fix's deferred listener start, `serverStart=deferred`, was not built: H3 limits the fix to the host's
  calling thread, and with H1 there is no logging initialisation left to defer.)

**Control server (H1; 1.x, built 2026-10-02).** The agent's own minimal HTTP/1.1 server on a loopback
`ServerSocket`: Java 8 API only, no third-party classes, no `com.sun.net.httpserver`, no `java.util.logging`, no
reflection into `sun.*`.

- *Listener.* `new ServerSocket(port, 64, InetAddress.getByAddress(new byte[]{127,0,0,1}))`: loopback only, no name
  lookup. One daemon acceptor thread `skillroy-http-accept`. When `accept()` fails (descriptors exhausted by the
  host, for example) it backs off from 50 ms, doubling up to 1 s, and logs one constant, rate-limited line; it never
  spins (the specification said a fixed 100 ms).
- *Connections.* Every accepted socket goes straight to the bounded `skillroy-http-N` pool (no queue); the thread
  that takes it owns the connection from accept until close ("Bounded pool" below). `TCP_NODELAY` is set on every
  socket.
- *Request head.* Request line and header fields are read as bytes into one fixed 16 KB buffer and decoded as
  ISO-8859-1; at most 64 header fields. The whole head must arrive within an **absolute** deadline of 5 s, counted
  from the accept for the first request of a connection, from the first byte for a later one, and from now for a
  pipelined request that is already partly in the buffer (the socket timeout is re-armed with the time that remains
  before every read, so a sender of one byte at a time gains nothing). Over the size cap, or more than 64 fields:
  431 and close. Deadline passed: close, without a response. The grammar is strict: CRLF line ends only (a bare CR or
  LF is 400), no line folding, token field names, no control characters in a field value, a method of 1 to 16
  upper-case letters, an origin-form target (a path of visible ASCII; an absolute-form or `*` target is 400),
  `HTTP/1.0` or `HTTP/1.1` only. A malformed request line or header field, and a `Content-Length` that is repeated,
  empty, longer than 18 digits or not a decimal number: 400 and close. `Expect: 100-continue` is ignored.
- *Order of checks.* On the head alone, before any body byte is read: the `Authorization` header (401 unless it
  parses in the right form for the path, see "Authentication"); `Transfer-Encoding` (501); `Content-Length` (400);
  a `/ping` with a body (401); a `Content-Length` above 8 MB (413, the body is not read). Then the body is read, and
  the MAC is verified over it (401). Only then: the `Host` header, which must equal `127.0.0.1:<port>` or
  `localhost:<port>` ignoring case (403); **no** `Origin` header (403); the path (404; compared as sent, not
  percent-decoded, so `/p%69ng` is 404); the method (405, with an `Allow` header); and a `POST /rpc` without
  `Content-Length` (411).
- *Body.* Exactly `Content-Length` bytes are read under an absolute deadline of 30 s; when it passes, or the client
  closes first, the connection is closed without a reply. The body is buffered as it arrives (the first chunk 64 KB,
  then doubling), never sized by the declared length. **Pre-authentication body budget:** because the request MAC
  covers the body, a request is authenticated only once its body is in, so a client without the key can make the
  agent buffer bodies; all connections that have not authenticated yet share one budget of 16 MB (twice the body
  cap), and a body that would exceed it is answered 503 with `Retry-After: 1` and close.
- *Keep-alive* for HTTP/1.1 requests without `Connection: close`, on a connection whose last request was
  authenticated and passed the `Host` and `Origin` checks; the body of such a request was read completely, since the
  MAC needed it. The connection may then stay idle for `httpIdleTimeoutMs` (default 120 s) waiting for the first byte
  of the next request head. Pipelined requests are served in order from the leftover buffer. After every 400, 401,
  403, 411, 413, 431, 500, 501 and 503, after any deadline, and for HTTP/1.0, the reply carries `Connection: close`
  and the socket is closed: a client that has not authenticated never holds a keep-alive place. A 404 or 405 keeps
  the connection open when the rules above allow it (the body is always read by then).
- *Drain after an error reply.* After a 4xx or 5xx reply with `Connection: close` the agent shuts down its output
  and discards what the client still sends, for at most 2 s or 16 MB, so that the client reads the reply instead of
  a reset.
- *Replies.* Each response is written with **one** write: status line, header fields and body in one buffer;
  `Cache-Control: no-store` on every JSON reply. A `HEAD` request is answered like any other wrong method (405),
  without a body but with the `Content-Length` the body would have had; the MAC then covers an empty body. `/events`
  is answered with `Connection: close` and a body that ends when the connection closes (no `Content-Length`, no
  chunking; the JDK server used chunked encoding); each SSE frame is one write.
- *Stopping.* `stop()` closes the `ServerSocket` and every connection that is not inside a request (idle or
  half-read) at once, ends the event streams, waits the grace period (2 s) for the requests in progress, then closes
  every remaining socket, and joins the acceptor for at most 1 s. Nothing a connection does can throw into the host:
  every connection thread catches everything and closes its socket. The deadlines (`headDeadlineMs`,
  `bodyDeadlineMs`, `idleTimeoutMs`, `drainMs`) are package-private mutable fields so that tests can shorten them.

*What it replaced, and why.* Until 1.x the control server was the JDK's `com.sun.net.httpserver.HttpServer`, bound
to `127.0.0.1`, with the same checks (bearer token 401, `Host` 403, `Origin` 403, then 404, 405 and 413 above 8 MB).
Measured with it: every call cost about 45 ms on Linux, because that server writes the response headers and the body
as two small segments and Nagle's algorithm holds the second until the client's delayed ACK arrives (40 ms); with
`-Dsun.net.httpserver.nodelay=true` on the target a call took 1-2 ms, so a scenario of about 45 calls took 2 s
instead of roughly 0.2 s. The security review of 2026-10-02 added that on older Java 8 runtimes this server reads a
request head of any size before the token is checked, that it has no cap on open connections, that a stalled
connection holds a pool thread, and that creating it in `premain` initialises `java.util.logging` on Java 8 (S3,
S4, S10, S11, S12, S16). Its JVM-wide `sun.net.httpserver.*` properties would change the host's own HTTP servers,
so the agent never set them. Review item R1 is thereby decided as its option (c), the listener described here
(decision B17). `TCP_NODELAY` and the single write removed the 45 ms: measured after the build, a call on a kept-alive
connection takes about 1.4 to 5 ms on a Java 8 target and about the same on a Java 25 target, and a scenario takes
about a tenth of its old time on Java 8 (`phase1-implementation-notes.md`).

**Authentication (H2; 1.x, built 2026-10-02).** The token stays the secret an agent and its controller share
(generated or supplied, stored and discovered as described in §2), but it is never put on the wire. Requests and
replies carry HMAC-SHA256 values computed by `Auth` (§3): the key is the token's UTF-8 bytes, the input is a label
and the listed fields joined with a single `\n` (UTF-8), the output is 64 lower-case hex characters, and comparison is
constant-time.

- *Session id.* At every start the agent generates `sessionId`: 16 random bytes as 32 lower-case hex characters. It
  is not configurable, so it differs even when the operator fixes token and nonce. It is not a secret: it is in the
  discovery file, in the system property `skillroy.agent.session`, in the `/ping` reply and in the reply header
  `X-Skillroy-Session`.
- *`GET /ping?nonce=<clientNonce>`.* `clientNonce` is 16 to 64 characters of `[A-Za-z0-9_-]`, chosen fresh by the
  client for every ping (`Auth.newNonce()`). The request carries `Authorization: Skillroy-HMAC mac=<hex>` with
  `mac = HMAC("hello", clientNonce)`, over the raw value of the single `nonce` query parameter. A `/ping` without a
  well-formed `nonce` parameter, with the parameter repeated, or with a body, is 401.
- *Every other request* carries `Authorization: Skillroy-HMAC nonce=<reqNonce>, mac=<hex>` with
  `mac = HMAC("req", sessionId, reqNonce, METHOD, pathAndQuery, bodyHash)`. `reqNonce` has the form of a client nonce
  and is fresh per request; `METHOD` is the method as on the request line; `pathAndQuery` is the request target
  exactly as sent on the request line, not percent-decoded (`/events?since=12&topics=input.*`); `bodyHash` is the
  SHA-256 of the body in lower-case hex, and `Auth.EMPTY_BODY_SHA256` (that of zero bytes) for a request without a
  body.
- *When a request is authenticated.* The header is checked on the head alone: a missing, repeated or malformed
  `Authorization` value, or the wrong form for the path (a `nonce` parameter on `/ping`, none elsewhere), is 401
  before any body byte is read. A request without a body is then verified at once. A request with a body is verified
  once the body is in, over the SHA-256 of exactly those bytes; nothing in the body is parsed, logged or acted on
  before that, and until then the connection counts as not authenticated (so it can be evicted, and its body bytes
  count against the 16 MB budget above). (The specification said the body hash travels in a request header
  `X-Skillroy-Body-Sha256`, so that the MAC could be verified on the head alone; built without it, so that the wire
  format carries one authenticator, and the budget bounds what a client without the key can make the agent buffer.
  The header remains the way to close that gap fully should it matter.)
- *Header syntax.* `Auth.parseAuthorization` is stricter than RFC 7235: the scheme name `Skillroy-HMAC` exactly
  (case-sensitive), exactly one space, then `name=value` parameters separated by a comma and optional spaces or tabs
  after it; no quotes, no whitespace around `=` or before a comma, no trailing comma or whitespace, parameter names
  in lower case, in any order. `mac` is required; `nonce` is required except on `/ping`, where it is not allowed; no
  other parameter; none twice; a value over 256 characters is rejected before parsing. A request that carries the
  header more than once is 401.
- *No replay cache.* The request MAC is bound to `sessionId`, so a captured request is useless against a later
  agent instance, and the agent keeps no state per nonce. The one value not bound to a session is the hello MAC of
  `/ping` (the client does not know the session yet): replayed, it obtains a `/ping` reply, which holds no secret,
  and nothing else.
- *Replies.* Every reply to a request that passed authentication carries
  `X-Skillroy-Mac: HMAC("resp", sessionId, reqNonce, statusCode, sha256hex(body))`, with the status code in decimal
  and the body as sent (`EMPTY_BODY_SHA256` for a 204, for a `HEAD` reply and for the `/events` head), and
  `X-Skillroy-Session: <sessionId>`, so that a client can verify a `/ping` reply before it has parsed anything; for
  `/ping` the `clientNonce` is the `reqNonce`. That includes error statuses reached after authentication: 400 (a
  protocol-major mismatch on `/ping`, a `since` that is not an integer on `/events`), 403, 404, 405, 411, 500 and the
  stream cap's 503. For `/events` the MAC covers the response head, and the stream then rides that verified
  connection.
- *Replies without a MAC.* The constant 401, and every refusal sent before the request could be authenticated: 400
  for a malformed head or `Content-Length`, 413, 431, 501, and the 503 of the body budget. A client treats these by
  their status and uses nothing of their body (§7).
- *Refusal.* A request with a missing, repeated or malformed `Authorization` header, another scheme (the former
  `Bearer` included, in any case), the wrong form for the path, or a wrong MAC gets one constant 401:
  `WWW-Authenticate: Skillroy-HMAC`, no MAC, no session header, `Connection: close`, nothing that depends on the key,
  and nothing read from the body. The token is never accepted from the query string.

**Bounded pool.** The connection pool has at most `httpThreads` threads (default 32; threads named
`skillroy-http-N`) and no queue, and one thread serves one connection from accept until close, so at most
`httpThreads` connections are open and the agent holds at most that many descriptors, threads and 16 KB head
buffers for its control channel **(H1; 1.x, built 2026-10-02)**. When the pool is full and one of the open
connections has not authenticated yet (no request on it has passed the MAC check; the flag never resets once set),
the **oldest such connection** is taken out of the registry, marked evicted and its socket closed, and its thread
serves the new socket next (so no race with the pool); only when every open connection has authenticated is the new
one closed unserved. Both cases are counted and logged at most once every 10 s. A controller therefore normally
gets in while a process without the key occupies places. What remains: such a process can hold places for the 5 s
head deadline each and come back, which delays the control channel and costs the host nothing beyond the bound
above. At most 8 `/events` streams are open at a time (for a small pool: at most half of `httpThreads`, at least
one); a further one gets HTTP 503 with `Retry-After: 1`. A local process without the token therefore cannot make
the target JVM create threads without limit. `/rpc` dispatch runs on the connection's thread; `CALLER` primitives
run on a second pool (`skillroy-http-call-N`), which only authenticated requests reach, so that their timeout can be
enforced.

**Bounded dispatch (S30; 1.x, built 2026-10-02).** Until 1.x the `WORKER` and `CALLER` pools grew without limit and
a call that timed out was abandoned while its thread went on, so a primitive that blocks accumulated threads. Built:

- a primitive with policy `CALLER`, `WORKER` or `APP` whose earlier call timed out and is still running is refused
  with the error `BUSY` (-32033; `data.primitive`, `data.thread`, `data.runningForMs`) until that call ends, and its
  end is logged. At most one abandoned call per primitive therefore exists, whatever the polling rate;
- the `CALLER` pool has at most `httpThreads + 8` threads and the `WORKER` pool at most `2 * httpThreads + 8` (a
  primitive may itself call the broker); a task a pool cannot take is `BUSY` with `data {reason: "pool full", pool}`;
- lifecycle work never waits for those pools: module `onTargetReady` runs on `skillroy-on-ready-<id>` and
  `uninstall` on `skillroy-uninstall-<id>`, so a saturated pool cannot keep a module's listeners in the host. Probes
  stay on the worker pool (the specification put them on threads of their own); a full pool makes the capability
  `UNAVAILABLE` with the `BUSY` reason;
- `agent.info` reports `threads: {workers: {active, max}, callers: {active, max}, abandoned: {<primitive>: n}}`;
- `BUSY` is not `TIMEOUT`: the controller treats it like any other JSON-RPC error of a step call (`ERROR` /
  `ENVIRONMENT`, §8.5). The optional JDBC watchdog of the verified fix was not built.

**Agent log (S2; 1.x, built 2026-10-02).** The log ring (2,000 lines) bounds every line: a message is cut at 1,000
characters, an exception message and a cause's at 300, a thread name at 100, a stack frame at 200, and the finished
line at 2,000; a cut ends in a `...[+N chars]` marker and never splits a surrogate pair. Every ISO control character
becomes a space, so a line can only break where the ring itself breaks it. The ring therefore holds at most about
8 MB of text on Java 8. Nothing taken from a request is logged before the request has passed authentication, not
even an exception message: such failures are counted and reported in at most one constant line per 10 s, naming only
the exception class ("N control requests ended with a client I/O error (M unauthenticated)" is one of them). For an
authenticated request a failure logs the method (16 characters) and the path without the query (200 characters),
cleaned the same way.

| Endpoint | Behaviour |
|---|---|
| `GET /ping?nonce=<n>` | `{"nonce":<n>,"clientNonce":<n>,"agentNonce":…,"sessionId":…,"protocol":"0.2","pid":…,"startTimeMs":…,"loadMode":…,"ready":bool,"readOnly":bool}`. An optional `protocol=<version>` parameter with a different major is answered with HTTP 400 (with a MAC, and `Connection: close`). **H2 (1.x, built 2026-10-02):** `clientNonce` (the client's nonce; `nonce` keeps carrying it too) and `sessionId` are new, and `protocol` is `"0.2"` |
| `POST /rpc` | JSON-RPC 2.0, single or batch (batch elements run sequentially in order). Always HTTP 200 for well-formed HTTP; errors are JSON-RPC errors. Requests without `id` are notifications: executed, no response element; a single notification, or a batch made only of notifications, answers HTTP 204 with no body. **H3 (1.x, built 2026-10-02):** a batch has at most `Protocol.MAX_BATCH` (100) elements; a longer one is answered with a single `INVALID_REQUEST` error (`id` null, `data {elements, maxBatch}`, message "a batch may hold at most 100 elements; none was run") and none of its elements runs |
| `GET /events?since=<seq>&topics=<globs>` | SSE, close-delimited (no `Content-Length`, no chunking). Each event: `id: <seq>`, `event: <topic>`, `data: <event JSON>`. Comment heartbeat `: hb` after 15 s without a write. Resume point = `Last-Event-ID` header if present, else `since` (400 when it is not an integer), else "from now"; a resume point beyond the last sequence number also means "from now". If the resume point is older than the ring, the stream first sends `event: gap` with `id: <to>` and `data: {"from":<requested+1>,"to":<oldest-1>}`. A gap frame can also occur mid-stream, when a slow client falls behind the ring. **S25 (1.x, built 2026-10-02):** the client accepts a line of at most 4 MiB and an event of at most 4 Mi characters of data; a peer that sends more has left the protocol and the client ends the stream (§7) |
| `GET /schema` | `{"protocol":"0.2","primitives":[PrimitiveDescriptor…]}` sorted by name (`protocol` is `Protocol.VERSION`) |
| `GET /capabilities` | `{"capabilities":{id:{"state":…,"reason":…,"strategy":…}}}` |

**Dispatch** of one request `{"jsonrpc":"2.0","id":…,"method":name,"params":{…,"_meta":{"correlationId","traceparent","timeoutMs","confirm"}}}`
(**(Phase 2, built 2026-10-02)** A34 adds the `_meta` key `secretParams`, §3; it is validated in step 1, after the
method, `params` and `_meta` checks and before any gate of step 2, and honoured under "Secret redaction" below):

1. a request that is not an object, lacks `"jsonrpc":"2.0"` or a string `method`, or an empty batch ->
   `INVALID_REQUEST`; unknown method -> `METHOD_NOT_FOUND`; `params` not an object -> `INVALID_PARAMS`.
2. `readOnly` and spec `mutating` **or `script`** (Phase 6 security fix S42, A44, built 2026-10-09, SR; P6 D156)
   -> `READ_ONLY`. Spec `destructive` and `_meta.confirm != true` -> `CONFIRM_REQUIRED`.
   Spec `mutating` while the target is not yet ready (readiness sources pending, or the production-marker check not
   yet passed) -> `NOT_READY`. `agent.disable` is exempt from `READ_ONLY` and `NOT_READY`; it still needs
   `_meta.confirm`.
3. spec has a capability whose state is `UNAVAILABLE` -> `CAPABILITY_UNAVAILABLE` with `data.reason` (a capability
   that was declared but not yet probed because the target is not ready -> `NOT_READY`). `DEGRADED` runs.
   **(1.x, built 2026-10-02)** A18: a capability that was withdrawn -> `CAPABILITY_UNAVAILABLE` with the reason
   "capability withdrawn". S30: an earlier call of the primitive that timed out and still runs -> `BUSY`.
4. effective timeout = `_meta.timeoutMs` if given (capped at 120000) else `spec.timeoutMs`.
5. run under `spec.thread` through the `ThreadBroker` with the correlation id bound to the executing thread.
   **(1.x, built 2026-10-02)** A19: with thread `APP`, while the first `ThreadPolicyProvider` reports
   `available() == false` (or its `available()` throws), a primitive that is not `mutating` runs on `WORKER` and a
   `mutating` one answers `NOT_READY` ("application thread not available yet", also in `data.reason`). S30: a full
   pool -> `BUSY`.
6. success -> `RpcResult`. `RpcException` -> its error. Timeout -> `TIMEOUT` with `data.primitive`, `data.timeoutMs`
   and, when a modal dialog is showing, `data.modalOpen=true`. Anything else -> `INTERNAL_ERROR` with
   `data.exception`, `data.message` and the first 10 stack frames.

**Secret redaction.** Host applications pass passwords, tokens and licence keys as system properties and JVM
arguments. A key is secret when it contains, case-insensitively, a match of
`password|passwd|pwd|secret|token|credential|licen[cs]e|api[-_.]?key|private[-_.]?key` or of the regex given in the
`redact` option. The value of a secret key is replaced by `<redacted>` in `jvm.info` (`inputArguments`,
`mainCommand`), `jvm.properties` and `agent.info.options`:

- a whole JVM argument `-Dkey=value`, `-key=value` or `--key=value`: everything after the first `=`;
- a `key=value` pair inside any other text (an agent argument string, a JDBC URL): the value up to the next `;`,
  `,`, `&` or whitespace;
- a property or option whose own name is secret: the whole value.

`jvm.properties` reports a secret property that is set as `<redacted>` and one that is not set as null, so a
scenario can still require its presence. Secrets written without `=` (`--password hunter2` as two program
arguments) cannot be recognised; `mainCommand` never carries program arguments for that reason.

Additions to redaction **(1.x, built 2026-10-02)**:

- **A17.** The key rule above lives in `skillroy-protocol` as `SecretKeys` (§3); the core uses it, with unchanged
  behaviour, and the controller uses it for fixture fields (§8.5).
- **A21.** A module can name further secret keys by registering a `RedactionKeys` (§4). A key is secret when
  `SecretKeys`, the `redact` option or any registered `RedactionKeys` pattern matches it. The `redact` option is
  validated and read at start; the registered `RedactionKeys` are read at every call, so a registration takes effect
  at once and an uninstalled module's keys stop counting (the specification read the option live too; built this
  way because the option is validated at start and nothing changes it later). A provider that throws makes every
  key secret (fail closed) and is logged once. The sample module registers one for a property of its own (§9.2).
- **S21.** The password in URL user information is redacted inside any value that is not hidden as a whole: in
  `scheme://user:password@host` the text between the `:` and the last `@` of the authority, and in the form
  `jdbc:oracle:<driver>:user/password@` the text between the `/` and the `@`. A user without a password
  (`ssh://git@host`) is left alone. Not recognised: a password containing an unencoded `/`, `?`, `#` or white space,
  and a credential carried as a bare user name (`https://<token>@host`); for both the `redact` option naming the
  property remains the remedy.

**A34 `secretParams` (Phase 2, built 2026-10-02; P2 §11.4).** The key rules above cannot see a secret under a
neutral key. For a request whose `_meta.secretParams` names a param (§3), the core (class `SecretParams`) replaces
that param's value by `SecretKeys.REDACTED` wherever it logs, traces or reports the call, and nowhere else. The
secret values are, of each named param that is present, its text, a number's decimal text, and every scalar below it
when it is an object or an array; booleans, nulls and empty strings are not secrets. They are replaced:

- in every `AgentLog` line written on a thread bound to the call's scope, the core's and a module's alike (message,
  exception message and cause message, replaced before they are cut); the dispatcher's line for an unexpected
  failure is written without the `Throwable`, so that no unreplaced message slips in; the broker's line about a
  failed posted task is written with the task's scope bound; the timed-out line, written outside the scope, is
  passed through the replacement explicitly (reviewed and fixed 2026-10-03). The timed-out and abandoned-call lines
  name only the primitive and the thread;
- **not** in events: the payload of an event published under the call's scope, by the application or a module,
  reaches the ring, every subscription and the result's `events` as published (the build of 2026-10-02 rewrote those
  payloads too, which could turn a value equal to a short secret into `<redacted>` for every reader; the review of
  2026-10-03 removed that, because what the call does is not what the core writes about it);
- in the error the call answers with: its message and every string or number in its `data`, however deep (an
  `RpcException` of the primitive, `INTERNAL_ERROR`'s `data.message` and stack frames, `TIMEOUT`'s data, any other
  error); an `INVALID_PARAMS` on a named param names the key and never its value.

The replacement is by value: a string or number equal to a secret becomes `<redacted>`; inside a longer text a secret
of 8 or more characters is replaced wherever it occurs, and a shorter one where it stands as a token of its own (no
letter or digit directly before or after it). Map keys are never changed. The agent's threshold of 8 is fixed: the
controller's setting per environment (P2 §4 `masking.minLengthInText`, 2026-10-06) does not travel to the agent. (The
specification took the controller's
A25 part (2) as the model, which replaces a shorter value inside a text only where it stands in double quotes; the
build replaces it where it stands as a token, so that the code in `rejected 1111` is hidden and `11112` is left
alone.) The primitive receives the value untouched and its result value is not changed; a module's message is covered
where it reaches the call's error or a log line of the call's thread, not on other threads (a module that echoes an
argument it was told is secret still has a defect; the sample's `lending.input` does not echo `value`). A request
without the key behaves exactly as in Phase 1.

**Core primitives** (all `CALLER` unless stated):

| Primitive | Params | Result `value` |
|---|---|---|
| `agent.info` | – | `{protocol, agentVersion, pid, startTimeMs, loadMode, instrumentation:bool, readOnly, ready, javaVersion, modules:[{id, version, state: INSTALLED\|FAILED\|UNINSTALLED, message?, readyMessage?}], options:{…token and nonce removed, secret values redacted…}, threads:{workers:{active,max}, callers:{active,max}, abandoned:{<primitive>: n}}}`. **(1.x, built 2026-10-02)** S30: `threads` is new. S14: `modules` also lists modules that could not be loaded |
| `agent.capabilities` | `reprobe?:bool` | `{capabilities:{id:{state,reason,strategy}}}`; spec timeout 60000 |
| `agent.log` | `n?:int (100, at most 2000)` | `{lines:[…]}` |
| `agent.disable` (destructive) | – | `{stopping:true}`; after replying: uninstall modules in reverse order, publish `agent.stopping`, stop the server, delete discovery files, clear the discovery system properties and restore the ones an operator had set (**S9, 1.x, built 2026-10-02**, §2.5). No thread of the core or of the generic modules remains (a domain module may keep one bounded daemon thread to finish removing a listener, §9.2; (Phase 4, built 2026-10-07; P4 §2.4: the web module's tap poller ends at uninstall, and a built-in web adapter's threads end for good at `detach()`, so that a late callback of the technology starts none)); a later attach starts a fresh agent with a new nonce and a new session id (**H2, 1.x, built 2026-10-02**) |
| `jvm.info` | – | `{javaVersion, javaVendor, vmName, pid, uptimeMs, mainCommand, inputArguments:[…], headless: string\|null, osName, osArch, user, heap:{used,max}}` (`mainCommand` and `inputArguments` redacted) |
| `jvm.threads` | `stacks?:bool, nameContains?:string, maxDepth?:int (20)` | `{threads:[{id,name,state,daemon,stack?:[…]}]}` |
| `jvm.properties` | `names:[string]` (at most 50) | `{properties:{name:value\|null}}`: only the named system properties, never a dump; secret values as `<redacted>` |
| `sync.await` | `topics:string\|[string], since:long, match?:{k:v}, timeoutMs?:long (10000, capped at 600000)` | the matching event JSON; `TIMEOUT` error with `data.lastSeq` if none. `match` compares top-level payload values by their text. Dispatcher timeout = `timeoutMs + 1000` |
| `evt.query` | `since?:long (0), topics?, limit?:int (200, max 1000)` | `{events:[…], lastSeq, oldestSeq}` |
| `trace.mark` | `label:string, attrs?:{}` | `{seq}`; publishes `trace.mark` |
| `app.state` | – | `{providerId: {…}, …}` merged from every `AppStateProvider` (thread `APP`) |
| `device.list` | – | `{devices:[{id,kind,actions:[…]}]}` |
| `device.state` | `id` | the adapter's state map (thread `APP`) |
| `device.act` (mutating) | `id, action, args?:{}` | the adapter's result (thread `APP`) |

Capabilities declared by core: `target.ready`, `jvm.instrumentation` (OK with strategy `Instrumentation` when
`Instrumentation` is present, else `UNAVAILABLE("no Instrumentation in EMBEDDED mode")`).

**Closing and re-attach.** `AgentRuntime.close()`, `EmbeddedAgent.stop()` and `agent.disable` called on the
event-dispatch thread or on a thread a `ThreadPolicyProvider` forbids return at once; the agent finishes closing on
a thread `skillroy-stop`. An `agentmain` on a JVM that already runs an agent only re-publishes discovery: the running
agent keeps its token and nonce and ignores the ones in the new options file, so the controller reads them back
(§7). The bootstrap waits for a stop in progress before it starts the next agent. **S33 (1.x, built 2026-10-02):** a
stop requested on the event-dispatch thread or on a forbidden thread returns at once even while another thread's
start or stop is in progress, and is carried out in the background afterwards; while a start is in progress every
caller of `stop()` is treated that way (§2.3).

---

## 6. `skillroy-agent-swing` (module id `swing`) and `skillroy-agent-data` (module id `data`)

Component references (`ref`) are `HandleRegistry` handles of kind `component`; the same live component always has
the same ref. All tree reads run `EDT_SYNC`; all actions are posted `EDT_ASYNC` and return as soon as they are
posted (the caller then uses `swing.settle`).

**The module never initialises AWT.** Its `install` and `onTargetReady` load no AWT class that initialises the
toolkit and call nothing in AWT. They only look for an event-dispatch thread (a live thread whose name starts with
`AWT-EventQueue`). While there is none, a daemon thread `skillroy-swing-tap` looks again four times a second and
ends once one exists or the module is uninstalled. Until then every display capability is
`UNAVAILABLE("AWT not started by the application yet")`, or `UNAVAILABLE("headless")` while the system property
`java.awt.headless` is `true` (the only headless signal that can be read without AWT). Once an event-dispatch
thread exists the toolkit is initialised and the headless decision taken, so the module asks
`GraphicsEnvironment.isHeadless()`: a headless application keeps `UNAVAILABLE("headless")` and gets no tap;
otherwise the capabilities become `OK` and the AWT tap is installed. A `swing.*` call made within a quarter of a
second of the application starting AWT can still be rejected with the "not started" reason; a readiness source that
waits for the UI avoids that, because `onTargetReady` checks synchronously.

**Node** (used by snapshot/find): `{ref, role, class, id?, name, text, enabled, visible, showing, focused, bounds:{x,y,w,h},
selected?, value?, children?:[Node]}`. `class` is the simple class name. `role` is one of `window, dialog, panel,
button, toggle, checkbox, radio, textbox, password, label, combobox, list, table, tree, tab, menu, menuitem,
scrollpane, text, unknown` derived from the component type (not from the accessible role, which custom widgets
often leave wrong). `id` is the component's explicitly set `Component.getName()`, omitted when there is none; names
AWT makes up itself (`frame0`, `dialog3`, `panel2`) are not ids. `name` is the first non-empty of: `NameEnricher`
results, accessible name, `Component.getName()`. `text` is the visible text (button/label text, text-component
content except passwords, selected item). `bounds` are relative to the containing window. Password fields never
expose their content. `children` appears only when non-empty. Values the contract leaves to the module: a combo
box's `value` is its selected index, a list's its selected indices, a table's `{rowCount, columnCount, columns,
selectedRows, cells}` capped at 50 x 50; HTML text is reduced to plain text; a widget whose own code throws yields a
node without `text`/`value` instead of failing the call.

**Locator** (the `by` object): exactly one built-in key or one custom strategy, plus optional `role` and `index`:
`{ref}`, `{id}`, `{name}`, `{text}`, `{class}`, `{path}` (child-index path "0/2/1" from the root window; indexes
count hidden children), or any strategy a `LocatorResolver` registered (for example `{action:"DUE"}`; the built-in
keys cannot be registered). A string value matches exactly; `{contains:"…"}` and `{regex:"…"}` objects are accepted
for `name` and `text` (a regex has a budget of 1000 ms per resolution); `{id}` matches exactly only. Only showing
components match unless `includeHidden`. Zero matches -> `NOT_FOUND`; more than one without `index` -> `AMBIGUOUS`
with the candidate list in `data`. Use `{id}` when a button's text hides its component name: the `name` rule puts
the accessible name first.

| Primitive | Params | Result `value` |
|---|---|---|
| `swing.windows` | – | `{windows:[{ref,type:frame\|dialog\|window,title,id?,name,class,visible,active,focused,modal,bounds}]}` |
| `swing.snapshot` | `root?:ref, maxDepth?:int (40, capped at 89), maxNodes?:int (2000, capped at 50000), includeHidden?:bool` | `{seq, truncated, windows:[Node with children]}` |
| `swing.find` | `by:Locator, root?:ref, all?:bool, includeHidden?:bool` | `{matches:[Node without children]}` (with `all`, zero matches is an empty list, not an error) |
| `swing.click` (mutating) | `target:ref` or `by:Locator`, `rung?: AUTO\|ACTION\|COMPONENT\|DISPATCH\|ROBOT (AUTO)`, `focusTimeoutMs?:int (500)`, `activateWindow?:bool (false)`, `force?:bool (false)`. **A28 (1.x, built 2026-10-02):** `fallback?: "ACTION"` (any case; with rung `COMPONENT` only) | `{rung, requestedRung, fidelity: FULL\|DEGRADED, reason?, fired:true}` when the click fired; `{rung:"FOCUS_FAILED", fired:false, reason}` (no fidelity) when it did not. **A28 (1.x, built 2026-10-02):** `requestedRung` and `fidelity` are new, see "Click rungs" |
| `swing.type` (mutating) | `target:ref` or `by`, `text`, `mode?: SET\|DISPATCH (SET)`, `clear?:bool (true)`, `enter?:bool`, `force?:bool (false)` | `{mode, typed:int}` |
| `swing.key` (mutating) | `key` (`KeyEvent.VK_` name without prefix, e.g. `ENTER`, `ESCAPE`, `F2`), `target?:ref\|by` (default: focus owner, else active window, else topmost modal dialog, else first showing window), `modifiers?:[SHIFT\|CTRL\|ALT\|META]`, `force?:bool (false)` | `{dispatchedTo:ref}` |
| `swing.dialogs` | – | `{dialogs:[{ref,title,modal,text,buttons:[{ref?,text}],extractor}]}`; modeless dialogs are listed too; `extractor` is `generic` when no registered extractor claims the dialog. **A27 (1.x, built 2026-10-02):** in-window dialogs follow the window dialogs, each with `inWindow: true` and `window`, see "Dialogs that are not windows"; window dialogs keep their shape (no `inWindow` key) |
| `swing.dismiss` (mutating) | `dialog?:ref` (a showing `java.awt.Dialog`, or **A27 (1.x, built 2026-10-02)** the root of an in-window dialog; default: the topmost modal dialog window, else the in-window dialog when it is the only dialog showing), `button?:string` (button text or name, exact then case-insensitive), `close?:bool` | `{dismissedBy: BUTTON_ACTION\|CLOSE, button?}`. `button` goes through the ACTION click rung; several matches are `AMBIGUOUS`. `close` delivers a `WINDOW_CLOSING` event to the dialog (what the title-bar close box does, so the dialog's own close handling runs); `dispose()` and `setVisible(false)` are never called, because they leave application dialogs without an answer. With neither, a dialog with exactly one button has it pressed; otherwise `INVALID_PARAMS` with `data.buttons`. Without `dialog`: no dialog at all is `NOT_FOUND`; several dialogs of which none is a modal window is `AMBIGUOUS` with `data.dialogs` (window dialogs first, then in-window ones); a `dialog` ref that is neither kind is `INVALID_PARAMS` with `{param: "dialog", ref}` |
| `swing.capture` | `target?:ref (active window, else topmost modal dialog, else first showing window)`, `mode?: PAINT (PAINT)` | `{format:"png", width, height, base64}`; anything larger than 4096 x 4096 is scaled down keeping the aspect ratio. (Phase 4, built 2026-10-07; P4 §2.5, §14.1: `PAINT` guards embedded-browser components (BS8: a JOGL canvas throws): when the target's `printAll` throws, the image is painted again part by part, a part whose `printAll` throws on its own becomes a crossed placeholder rectangle, the heavyweight descendants are printed on their own on top, and the result gains `skipped: [{ref, className, error}]` when it is not empty) |
| `swing.edtState` | – | `{dispatchThreadAlive, edtBlocked, awtStarted, modalDialogs:[ref], windowCount, focusOwner?:ref}` (thread `CALLER`, never blocks on the EDT for more than 500 ms; `edtBlocked:true` if the marker does not run). It names no capability and answers without starting the toolkit: before the application has started AWT it reports `{dispatchThreadAlive:false, edtBlocked:false, awtStarted:false, modalDialogs:[], windowCount:0}`. `modalDialogs` lists dialog windows only, not in-window dialogs (A27) |
| `swing.settle` | `timeoutMs?:int (5000, at most 120000), quietMs?:int (50)` | `{settled:bool, ms}`: two consecutive EDT marker round trips with no `window.opened`/`window.closed` in between and for `quietMs` after. It answers `settled:false` before the call budget runs out instead of letting the dispatcher report `TIMEOUT` |

`swing.click`, `swing.type` and `swing.key` accept `target` as a ref or as a locator object, as well as `by`.

Click rungs: **ACTION** = the first `ActionFirer` that `canFire`, else `AbstractButton.doClick()` posted to the EDT
(model-level; needs no focus), else for other components a synthesized click as in DISPATCH. **COMPONENT** =
`requestFocusInWindow()`, wait up to `focusTimeoutMs` for `hasFocus()`, then `doClick()` (a non-button gets a
dispatched mouse click instead); if focus never arrives the result is `{rung:"FOCUS_FAILED", fired:false, reason}`
and nothing is clicked. With `activateWindow: true` the rung first calls `toFront()` + `requestFocus()` on the
component's window and waits for it to be the focused window, re-issuing `toFront()` + `requestFocus()` once a
second while it waits (4.x, A39, built 2026-10-08); `focusTimeoutMs` bounds window activation and component focus
together (the re-issue stays within it and stops once it is spent), nothing is activated when the window already
has the focus, and the flag is ignored by the other rungs. **DISPATCH** = `MOUSE_PRESSED`, `MOUSE_RELEASED`,
`MOUSE_CLICKED` delivered with
`Component.dispatchEvent` at the component centre (never posted to the system queue). **ROBOT** = `UNAVAILABLE` in
Phase 1 (`reason: "opt-in rung, not implemented"`; the call fails with `CAPABILITY_UNAVAILABLE`). **AUTO** = ACTION.
Clicking a disabled or non-showing component is `INVALID_PARAMS` with the reason. In every rung `fired:true` means
"posted": an exception thrown later on the event-dispatch thread, by an `ActionFirer` or by the application's
listener, is logged and not reported.

Focus without a window manager (measured on the WM-less Xvfb, Java 8 and Java 21 alike): a freshly shown frame is
the focused window within about 30 ms and `requestFocusInWindow()` in the focused window succeeds at once. In any
other window it returns false and the focus never arrives, so COMPONENT reports `FOCUS_FAILED` there unless
`activateWindow` is set; activation took between 2 and about 400 ms. When a modal dialog closes, nothing gives the
focus back to the frame behind it. COMPONENT is therefore not usable as a default rung without window activation.
Decision B12 (2026-10-02): the COMPONENT rung is part of the base and stays; the default of the *primitive* stays
`ACTION`, which needs no focus and does not bring a window to the front beside a person at the screen; a scenario
whose purpose is UI verification opts in with `ui: {clickRung: COMPONENT}` (§8.1, A29).

**A28 COMPONENT with fall-back (1.x, built 2026-10-02).** `swing.click` takes an optional `fallback` whose only
accepted value is `"ACTION"` (case-insensitive). With rung `COMPONENT` and `fallback: "ACTION"`, a click whose focus
does not arrive, for any of the three focus failures (the window is not focusable, window activation timed out, the
component's focus timed out), is fired through the ACTION rung instead: the component is checked again first (still
clickable and, unless forced, not blocked by a modal dialog, so a `MODAL_OPEN` can arrive after the focus wait), then
ACTION is posted. The result is
`{rung: "ACTION", requestedRung: "COMPONENT", fidelity: "DEGRADED", reason: "FOCUS_FAILED: …", fired: true}`.
Without `fallback` nothing changes: `{rung: "FOCUS_FAILED", fired: false, reason}`, with no fidelity field. A click
that fired on the rung that was asked for reports `{rung, requestedRung, fidelity: "FULL", fired: true}`, where
`requestedRung` is the rung exactly as asked, upper-cased (an `AUTO` click reports `requestedRung: "AUTO"` with
`rung: "ACTION"`). `fallback` with any rung other than `COMPONENT`, or any other value, is `INVALID_PARAMS`, checked
before the rung's capability check; with a fall-back the `swing.click.action` capability is required as well. A
fall-back reserves 400 ms of the call's budget (on top of the existing 100 ms margin) for the ACTION click, so the
focus wait ends that much earlier.

**A39 window activation re-issued (4.x, built 2026-10-08).** The window activation of the COMPONENT rung was one
`toFront()` + `requestFocus()` on the EDT, then a wait. Under an X server without a window manager that is a single
`XSetInputFocus` (`toFront()` only raises there, since no window manager ever sets `WM_STATE`), and it can lose
against the activation of a window shown a few milliseconds earlier: that window's own activation ends on the EDT
with the focus moving to its most recent focus owner, for which the X toolkit issues one more `XSetInputFocus` on
that window; the last request wins, the X server and the toolkit end consistent with the earlier window focused,
and nothing re-issues the lost request, so the activated window stays unfocused for the whole wait. Evidence: the
Swing module's display tests under the WM-less Xvfb (the open flake of `docs/TODO.md`, 2026-10-02 to 2026-10-07),
reproduced on 2026-10-08 with the toolkit's focus log (`sun.awt.X11.focus` and `java.awt.focus` at FINEST), where
a second frame shown right after the test frame, and the test frame activated right after a focused frame was
disposed, each stayed unfocused for 30 s while the next request took the focus at once. The rung therefore
re-issues `toFront()` + `requestFocus()` (posted to the EDT, never waited for) after every second of waiting in
which the window has not become the focused window, within the unchanged `focusTimeoutMs` bound and never once it
is spent; a window that is already focused, undisplayable, or blocked by a modal dialog is not touched (`toFront()`
and `requestFocus()` do nothing for a blocked window). With the default `focusTimeoutMs` of 500 ms nothing is
re-issued; the sample's UI scenario (`focusTimeoutMs: 5000`) re-issues up to four times. The `FOCUS_FAILED` reason
of a window that never became focused says how many times the activation was re-issued. Nothing else changes: the
result shapes, the fall-back and the listener clean-up are as A28. The display tests wait for a window to become
the focused window through one helper that re-issues the same activation once a second (`DisplayTestBase`).

**Modal blocking.** `swing.click`, `swing.type` and `swing.key` with an explicit target whose window is blocked by
a showing modal dialog fail with `MODAL_OPEN` (`data.dialog` = the dialog's ref, `data.title`, `data.ref` = the
target) unless `force: true` is passed. Blocked means: a showing dialog with a modality type other than `MODELESS`
exists and the target's window is neither that dialog nor owned by it, directly or transitively; of several
blocking dialogs the topmost is named. This is a heuristic: document modality and modal-exclusion settings are not
modelled, so a window AWT would let through can be reported as blocked, and `force` overrides. `swing.key` without
a target goes where a real key press would (into the dialog) and is not checked; `swing.dismiss` is not checked.

Type modes: **SET** = `JTextComponent.setText` (after optional clear; `clear:false` appends), then if `enter`
`JTextField.postActionEvent()` (a dispatched `ENTER` for other text components). **DISPATCH** = per character
`KEY_PRESSED`/`KEY_TYPED`/`KEY_RELEASED` delivered with `dispatchEvent` directly to the component (never posted:
posted key events are retargeted to the focus owner or dropped when the window is not focused), then `VK_ENTER` if
`enter`; at most 10000 characters. Non-editable text components, and SET on a component that is not a text
component, are `INVALID_PARAMS`.

Dialog text: `DialogTextExtractor`s first, in registration order; otherwise generic extraction (`JOptionPane`
message, else all showing `JLabel`/text-component texts in reading order; buttons = showing `AbstractButton`s with
text). Without a `DialogLocator`, extractors are consulted, and `swing.dismiss` acts, only for `java.awt.Dialog`
windows.

**A27 dialogs that are not windows (1.x, built 2026-10-02).** A module that knows how its application draws dialogs
inside another window registers a `DialogLocator` (§4). The locators are asked on the EDT, once per primitive call;
with none registered nothing is done. A reported root counts only if it is a showing component, not a window itself,
and lies inside the window it was reported for; a root reported twice counts once; a locator that throws is logged
and skipped, and a `blocksInput` that throws counts as blocking. Then:

- `swing.dialogs` lists the roots it reports after the window dialogs, each as `{ref, title, modal, text, buttons,
  extractor, inWindow: true, window}`: `ref` is the handle of the root component, `window` the ref of the containing
  window, `modal` = `blocksInput(root)`, and title, text and buttons come from `DialogTextExtractor.extract(Component)`,
  falling back to the generic reading of the component tree under the root. The generic title is a `JInternalFrame`'s
  title, else a `TitledBorder`'s (also inside a `CompoundBorder`), with HTML reduced to text, and may be null. A
  window is still passed to `extract(Window)`;
- `swing.dismiss` accepts such a dialog, named with `dialog: <ref>` or taken by default when it is the only dialog
  showing (no dialog window of any kind; a modal dialog window always comes first, see the table). `button` fires
  the named button inside the root through the usual `ActionFirer` / `doClick` path. `close: true` is
  `INVALID_PARAMS` for an in-window dialog ("an in-window dialog has no window to close; name a button") with
  `data {param: "close", dialog, inWindow: true}`;
- `swing.click`, `swing.type` and `swing.key` aimed at a component of the same window that lies outside a blocking
  in-window dialog answer `MODAL_OPEN` unless `force` is set, with the data fields of "Modal blocking" plus
  `inWindow: true`; dialog windows are checked first, then the in-window dialogs, and of several blocking in-window
  dialogs the innermost (the last one reported) is named. The title in `MODAL_OPEN` is the extractor's, else the
  generic one.

AWT tap: one `AWTEventListener` for window and window-focus events, installed when AWT is first seen running. It
publishes `window.opened` / `window.closed` with `{ref, type, title, modal}`. AWT raises `WINDOW_OPENED` only the
first time a window is shown and nothing when a window is hidden or shown again, so the tap keeps the set of showing
windows and compares it with what is showing on every window and window-focus event and whenever a primitive asks
(`swing.settle` in each of its markers and once more after the quiet period, `swing.snapshot`, `swing.windows`,
`swing.dialogs`). A window that became showing again gets `window.opened` with `reshown: true` (a first opening has
no `reshown` key); one that stopped showing, hidden or disposed, gets `window.closed`. A window hidden and shown
again between two comparisons is not seen. A window already showing when the tap is installed (ATTACH: every window
of the running application) gets its `window.opened` from the first comparison.

Capabilities: `swing.tree`, `swing.click.action`, `swing.click.component`, `swing.click.dispatch`,
`swing.capture.paint` (states as described at the top of this section), and `swing.click.robot` (always
`UNAVAILABLE`). Every `swing.*` primitive except `swing.edtState` and `swing.capture` names `swing.tree`;
`swing.capture` names `swing.capture.paint`; `swing.click` checks its rung's own capability itself.

`skillroy-agent-data`:

| Primitive | Params | Result `value` |
|---|---|---|
| `data.sources` | – | `{sources:[{id,describe}]}` (thread `WORKER`, timeout 5000); of two providers with the same id the first registered wins |
| `data.query` | `source:string, sql:string, params?:[…], maxRows?:int (200, max 200)`; spec timeout 10000 | `{columns:[{name,type}], rows:[[…]], rowCount, truncated}` |

`data.query` runs on `WORKER`. The provider's `query(sql, params, maxRows + 1, timeoutMs)` is tried first; if it
returns `null` the JDBC form is used: `open()`, `Connection.setReadOnly(true)`, a forward-only read-only
`PreparedStatement`, `setQueryTimeout`, `setMaxRows(maxRows+1)` (a `SQLFeatureNotSupportedException` from any of the
three is ignored). A provider that returns `null` from both is `CAPABILITY_UNAVAILABLE`. A JDBC connection obtained
from `open()` is closed after every call; before that a manual-commit connection is rolled back and a connection
that was writable is set writable again. `maxRows: 0` returns the columns only; a value above 200 is capped.
Capability `data.query` is OK when at least one `DataSourceProvider` is registered at probe time; a provider
registered later needs `agent.capabilities {reprobe:true}`.

**Statement guard, as built** (stricter than first specified; a safety net, not the security boundary: the
boundary is the data source the provider hands out). A small tokenizer, never a pattern over the raw text, requires:

1. after leading whitespace and comments, the first token is the whole word `SELECT` or `WITH` (case-insensitive);
2. a `;` only as the last non-whitespace character (literals, quoted identifiers and comments do not count); a
   comment after it is rejected, and the trailing `;` is stripped before the SQL reaches the provider;
3. no unterminated literal, quoted identifier or block comment;
4. outside literals, quoted identifiers and comments, none of the words `INSERT`, `UPDATE`, `DELETE`, `MERGE`,
   `TRUNCATE`, `DROP`, `ALTER`, `CREATE`, `GRANT`, `REVOKE`, `DENY`, `CALL`, `EXEC`, `EXECUTE`, `INTO`, `SHUTDOWN`,
   `KILL`, `BACKUP`, `RESTORE`, `DBCC`, `UPDATETEXT`, `WRITETEXT`, `RECONFIGURE`, `SETUSER`. That rejects
   data-changing CTEs, `SELECT … INTO`, `FOR UPDATE`, data-change table references and batches that need no `;`
   **when they carry one of these words or a T-SQL transaction, control-of-flow or lock-hint word; `FOR SHARE`,
   `FOR KEY SHARE` and `LOCK IN SHARE MODE` are rejected as word sequences (Phase 6 security fix S36, A41, built
   2026-10-09, SR)**: the words `BEGIN`, `COMMIT`, `ROLLBACK`, `SAVE`, `DECLARE`, `WAITFOR`, `RAISERROR`, `THROW`,
   `PRINT`, `GOTO`, `USE`, `CHECKPOINT`, `RECEIVE`, `SEND`, `OPENROWSET`, `OPENQUERY`, `OPENDATASOURCE`, `HOLDLOCK`,
   `TABLOCK`, `TABLOCKX`, `XLOCK`, `UPDLOCK`, `PAGLOCK` are added to the list, and the sequences are matched on the
   same tokens in every reading; `SET` is not a forbidden word (MySQL's `CAST(x AS CHAR CHARACTER SET utf8mb4)` stays
   admitted).
   `TRUNCATE(` and `INSERT(` are the scalar functions and are allowed; an identifier that is one of these words
   must be quoted.

Rules 1, 2 and 4 are applied once with standard SQL lexical rules and again with those of MySQL, PostgreSQL and
SQL Server; a statement passes only if every reading accepts it. The cost is a few false rejections of valid SQL
(for example a `--` comment without a following space that contains a `;`). A rejection is `INVALID_PARAMS` with
`data {param:"sql", reason}`; the guard runs before the source lookup. A `SELECT` can still have effects the guard
cannot see (a function that writes, a sequence that advances).

Column values: JSON booleans stay booleans; numbers stay numbers (`BigDecimal` -> its plain string); dates,
timestamps and `java.time` values -> ISO-8601 strings; text, character large objects and every other value's
`toString()` are capped at 10000 characters; binary values become `base64:<first 1024 bytes>`; a cut value ends
with a `...[truncated: N chars|bytes]` marker. Then each `MaskPolicy` is applied; a `MaskPolicy` that throws fails the whole call with
`INTERNAL_ERROR` and the value appears neither in the error nor in the log. Columns a source cannot name are
reported as `c1..cn`. Errors: an unknown source is `NOT_FOUND` with `data {source, known}`; an SQL error is
`INTERNAL_ERROR` with `data {source, sqlState, errorCode, exception}` and, under the rule below, either `message` or
`messageWithheld: true`. The `params` list itself is never logged or returned.

**S22 driver messages withheld (1.x, built 2026-10-02; it replaces amendment A26).** When `data.query` fails with an
`SQLException` anywhere in the cause chain (searched at most 16 deep), or a provider's `open()` or `query()` throws,
the error carries `data.source`, `data.sqlState`, `data.errorCode` and `data.exception` (the class name), and its
message is generic: "query on data source '<source>' failed: sqlState=<s>, errorCode=<n> (<exception class>)". For a
provider failure without an `SQLException` in its cause chain, `sqlState` and `errorCode` are null and the message is
"query on data source '<source>' failed in query()|open() (<exception class>)". The driver's own text is never written
to the agent log (log lines carry class, SQL state and code only; the JDBC clean-up lines too) and never part of that
message. It is returned, as `data.message`, only when the provider opted in (`exposesErrorText()` returns true, §4)
**and** no `MaskPolicy` is registered, both checked per call, the masks first; in every other case `data` carries
`messageWithheld: true` instead. A provider whose `exposesErrorText()` throws counts as not opted in and is logged by
class name only. The reason: a `MaskPolicy` works on column values and cannot scrub free text, and a driver's message
can echo the very values a policy masks. (The verified fix returned the text whenever no `MaskPolicy` was registered;
built with the opt-in as well, as H3 and this contract specified.)

---

## 7. `skillroy-controller` (package `ai.skillroy.testjava.controller`, Java 25)

```java
public record Endpoint(String host, int port, String token, String nonce, long pid, long startTimeMs, LoadMode loadMode,
                       String sessionId) { }                      // toString() hides the token
    // H2 (1.x, built 2026-10-02): sessionId (32 lower-case hex, or null) is the eighth component; the seven-argument
    // constructor and withSessionId(String) exist; equals and toString include it

public interface AgentConnection extends AutoCloseable {          // the seam the executor is written against
    RpcResult call(String method, Map<String,Object> params) throws AgentRpcException, AgentLostException;
    RpcResult call(String method, Map<String,Object> params, CallOptions options) throws …;   // correlationId, timeoutMs, confirm
    Event await(String topics, long sinceSeq, Map<String,Object> match, Duration timeout)
        throws AgentRpcException, AgentLostException;             // null on timeout; A31 (1.x, built 2026-10-02): an error
                                                                  // answer other than TIMEOUT is thrown as AgentRpcException
    CapabilityMatrix capabilities() throws AgentLostException;
    List<PrimitiveDescriptor> schema() throws AgentLostException;
    Map<String,Object> ping() throws AgentLostException;
    default boolean isLost();                                      // H2 (1.x, built 2026-10-02): the lost latch, see below
    @Override void close();                                        // idempotent, never throws
}
public record CallOptions(String correlationId, Long timeoutMs, boolean confirm, List<String> secretParams) {
    /* defaults(), with…(), and the three-argument constructor of Phase 1 */ }
    // A34 (Phase 2, built 2026-10-02; P2 §11.4): the fourth component secretParams (default empty; the three-argument
    // constructor stays; withSecretParams(List<String>); MAX_SECRET_PARAMS = 100, and more names or a blank one is an
    // IllegalArgumentException). AgentClient writes it into params._meta.secretParams and omits the key when the list
    // is empty; a _meta.secretParams already present in params is merged with it as the other _meta keys are (the
    // options win: a non-empty list replaces it). The executor sets it for every step call and cleanup call whose
    // argument template references a joined credential field (§8.5).
    // As built (2026-10-08, the Phase 5 fix wave; P5 §12.2): AgentClient builds params._meta from the options alone
    // and drops a _meta the params carry, so the merge above is gone: a {_meta: {confirm: true}} among a call's
    // params confirmed a destructive call without CallOptions.confirm. No caller relied on it (the CLI's call has
    // --confirm and --timeout); a traceparent, which only a params _meta could carry, has no source on the
    // controller now.
public final class AgentClient implements AgentConnection {      // java.net.http.HttpClient; no proxy; 127.0.0.1 only
    public static AgentClient connect(Endpoint endpoint) throws AgentLostException;   // pings: the reply MAC first (H2), then
                                                                                      // session, nonce, protocol major
    public Endpoint endpoint();
    public boolean isLost();
    public EventStream events(long sinceSeq, String topics, Consumer<Event> onEvent); // SSE with Last-Event-ID resume
}
public interface EventStream extends AutoCloseable { boolean isOpen(); long lastSeq(); void close(); }
public final class AgentRpcException extends Exception { public RpcError error(); public String method(); }
public final class AgentLostException extends Exception { }      // see "Error mapping" below

public final class CapabilityMatrix {
    public CapabilityMatrix(Map<String,Capability> capabilities);
    public static CapabilityMatrix fromJson(Map<String,Object> json);       // the {"capabilities":{…}} shape
    public Capability get(String id);  public boolean isOk(String id);  public Map<String,Capability> all();
    /** Ids from `required` that are missing or UNAVAILABLE, with reasons. "a | b" means either suffices. */
    public List<String> unmet(Collection<String> required);
}

public final class AgentHome { public static AgentHome locate();   // -Dskillroy.agent.home, env SKILLROY_AGENT_HOME
    public static AgentHome of(Path dir);  public Path bootstrapJar();  public Path dir();  public Path modulesDir(); }

public final class Discovery {
    public static Path defaultDir();                             // ${java.io.tmpdir}/skillroy-<user>, or the fall-back,
                                                                 // by the agent's rule (§2.5; S8/S19, 1.x, built 2026-10-02)
    public static List<Endpoint> list(Path discoveryDir);        // live agents only (pid + start time checked); a null
                                                                 // directory reads every trusted default candidate
    public record Entry(Endpoint endpoint, String mainCommand, String protocol, Path file) { }
    public static List<Entry> entries(Path discoveryDir);
    // A23 (1.x, built 2026-10-02): the two listings above also remove stale files; these keep them
    public static List<Endpoint> list(Path discoveryDir, boolean removeStale);
    public static List<Entry> entries(Path discoveryDir, boolean removeStale);
    public static Optional<Endpoint> forPid(long pid, Path discoveryDir);          // never deletes
    public static Endpoint awaitPid(long pid, Path discoveryDir, Duration timeout) throws AgentLostException;   // never deletes
}

public final class AttachSession {
    /** ATTACH mode: writes an owner-only options file (token, nonce, enabled=true, extra options) into the discovery
     *  directory, loads the bootstrap jar into the target with the argument "t=<file>", reads the discovery system
     *  properties back through the attach API, then pings. The attach reply is never trusted: only a successful
     *  /ping with the right nonce counts. An AgentLoadException whose message is "0" or ends with ": 0" is success
     *  when the target is Java 8 or older. Bounded at 120 s. */
    public static Endpoint attach(long pid, AgentHome home, Map<String,String> extraOptions) throws AttachFailedException;
    public static List<JvmInfo> listJvms();                      // id + display name of same-user JVMs
}

public final class TargetLauncher {                              // used by tests and by CI wrappers
    public static Builder java(Path javaExecutable);
    public static final class Builder {
        public Builder classPath(List<Path> entries);  public Builder mainClass(String name);
        public Builder jvmArg(String arg);  public Builder arg(String arg);  public Builder env(String k, String v);
        public Builder javaAgent(AgentHome home, Map<String,String> options);   // JAVAAGENT mode, generates token + nonce
        public Builder viaJavaToolOptions(boolean on);          // inject -javaagent through JAVA_TOOL_OPTIONS + mainClassGuard
        public Builder discoveryDir(Path dir);                  // default Discovery.defaultDir()
        public Builder inheritOutputTo(Path logFile);
        public LaunchedTarget start() throws IOException;
    }
}
public final class LaunchedTarget implements AutoCloseable {
    public long pid();  public Process process();
    public List<String> command();  public Map<String,String> environment();   // what was launched, for assertions
    public Path discoveryDir();  public Path optionsFile();  public String agentNonce();  public Path logFile();
    public Endpoint awaitAgent(Duration timeout) throws AgentLostException;   // JAVAAGENT/EMBEDDED: discovery file for this pid
    @Override public void close();                               // destroys the process tree, deletes the options file
    // S5 (1.x, built 2026-10-02): awaitAgent deletes the options file once a ping with the launch nonce has been
    // verified; close() remains the fall-back. S6/S28: for a JAVAAGENT launch awaitAgent connects with the launcher's
    // own token and nonce (Discovery.awaitPid(pid, dir, timeout, token, nonce), package-private), never reads the token
    // file, and takes only port, pid and start time from the discovery file; the nonce is checked inside the first ping.
}
```

`skillroy-controller` needs the `jdk.attach` module at run time for `AttachSession` only; everything else works
without it (`attach` then fails with `AttachFailedException`, `listJvms` with `IllegalStateException`).

**Client.** HTTP/1.1 to `http://127.0.0.1:<port>` only (an endpoint host of `localhost` is normalised, anything
else rejected), no proxy, no redirects, connect timeout 5 s, no `Origin` header, no `Expect: 100-continue`. `connect`
and every `ping()` send a fresh nonce and require the reply to echo it, to report the same protocol major and, when
the endpoint knows them, the endpoint's agent nonce, pid and JVM start time. A call's HTTP timeout is its `timeoutMs`
+ 5 s (130 s without one), so the agent's own `TIMEOUT` normally arrives first. Long-lived event streams set no
request timeout (newer `HttpClient`s apply it to the whole response body); a stream reconnects with `Last-Event-ID`
after a dropped connection or 45 s without a line, with a capped back-off (0.1 s to 2 s), and accepts gap frames with
or without an id, first or mid-stream. A negative `sinceSeq` means "from now". The JDK client keeps idle connections
for 30 s; the agent keeps them for `httpIdleTimeoutMs` (120 s by default, §2.4), so normally the client retires an
idle connection first. **Retry once (1.x, built 2026-10-02):** when a `POST` on a pooled connection fails because
the connection was closed or reset before any response byte arrived, and a probe finds that the port still accepts
connections, the client sends the request exactly once more on a fresh connection (the JDK client does not retry a
`POST` by itself); the request is idempotent at that point because no byte of it was answered, and a squatter on the
port gains nothing, since it cannot sign a reply.

**Client authentication (H2; 1.x, built 2026-10-02).** The client never sends the token (the class Javadoc says
so); it authenticates every request and verifies every reply with `Auth` (§3), as §5 "Authentication" describes.

- `connect` and every `ping()` send a fresh client nonce as `GET /ping?nonce=<n>` with
  `Authorization: Skillroy-HMAC mac=HMAC("hello", n)`. On the reply, a 401 latches the client lost. Otherwise the
  client takes the session id from the `X-Skillroy-Session` header (which must be well-formed), verifies
  `X-Skillroy-Mac` over the status and the body **before it looks at anything else** in the reply, and only then
  requires the body's `sessionId` to equal the header's, the echoed client nonce (`nonce`, and `clientNonce` when
  present), the protocol major and, when the endpoint knows them, the agent nonce, pid and start time. The first
  verified ping fixes the session id for the life of the client; a later ping that reports another one means a
  restarted agent, and latches. When the endpoint already names a session (from the discovery file), the agent must
  report exactly that one. A reply without a valid MAC and session header latches; a verified reply with a status
  other than 200 is reported by that status (`AgentLostException`, not latched). (The specification took the session
  id from the reply body and could therefore verify only a 200; built with the header, so that every `/ping` reply is
  verified before its body is parsed.)
- Every other request carries a fresh `reqNonce` and `Authorization: Skillroy-HMAC nonce=<r>, mac=<request MAC>`
  over the exact body bytes sent; the client verifies the reply MAC before it parses the body. Before a verified ping
  no such request can be signed ("no verified session yet (ping first)"). For an event stream the request MAC covers
  `GET`, the path with its query and an empty body; the client verifies the MAC of the 200 head over an empty body
  before it reads the first frame, on every reconnect, and a non-200 reply over its body (capped at 64 KiB).
- *Unsigned refusals.* A reply with status 400, 413, 431, 501 or 503 and no `X-Skillroy-Mac` is one the agent sent
  before it could authenticate the request (§5): a call fails with `AgentLostException` **without** latching, and
  nothing of its body is used (an impostor gains nothing, and a transient 503 does not end the client). The event
  stream treats such a reply as a failed attempt under its reconnect policy (capped back-off until the stream is
  closed or the client is lost), not as an impostor. A wrong MAC, or an unsigned reply with any other status (a 200
  included), latches the client lost ("the peer on port N is not the agent (or does not hold this token)").
- *The `lost` latch (verified fix of S1, part B).* `AgentClient` has a terminal state `lost` (`isLost()`), set by:
  a refused connection; a reset or closed connection after which a 1 s TCP probe finds that the port no longer
  accepts connections (otherwise the call fails as a plain `AgentLostException`, or is retried once as above: a
  stale pooled connection must not end a whole run); an HTTP 401; a missing or wrong reply MAC, or a missing or
  wrong session header; a failed handshake, a changed session id included; a successful `agent.disable` issued
  through this client; an `agent.stopping` event seen by one of its event streams (the event is delivered first,
  then the stream ends); a refused SSE reconnect; and, when the endpoint names a pid, that process no longer being
  live (S1 part B.2), checked before every request and every stream connect. Once lost, every `call`, `ping`,
  `schema` and `capabilities` of the client throws `AgentLostException` **without any I/O**, and `events()` returns a
  stream that ends at once without connecting (`isOpen()` false; the specification said it throws, built this way so
  that the CLI's `skillroy events` reports "lost" as before). A plain timeout does not set it. `PlanExecutor` starts
  its cleanup from that state, so no cleanup call goes out after a loss (§8.5).
- The CLI's direct mode (`--port` with `--token-file`) looks the same to the user; the peer is verified by the reply
  MAC, and an endpoint with no nonce, pid or session works that way. A primitive is called by hand with
  `skillroy call`: a plain HTTP client with a bearer token no longer works (401).
- *Test fakes.* Everything that stands in for an agent or builds raw requests in tests (`FakeAgent` in the
  controller and CLI tests, the Java 8 fixture agent, the core's and the dist's test harnesses, the sample's raw
  socket client) uses `Auth` too; the in-process `MiniAgent` of the JUnit glue speaks no HTTP. Tests prove: a peer
  without the key is rejected, sees only one hello MAC, and that MAC replayed as `/rpc` gets 401; a captured request
  replayed against a restarted agent (new session id) is 401; the former `Bearer` header is 401; unsigned or wrongly
  signed replies latch; a timeout does not.

**Reply limits (S23, S25; 1.x, built 2026-10-02).** A request's timeout is one deadline for the response headers
**and** the body (`sendAsync` with a capped body handler). A reply body is at most 64 MiB (64 KiB for `/ping`; a
`Content-Length` above the cap fails at once); a late or oversized reply is an `AgentLostException` and the
connection is dropped. The event stream splits bytes into lines itself (LF, CRLF or a lone CR), caps a line at 4 MiB
and an event's `data` at 4 Mi characters, and keeps a 64-line back-pressure window; either overflow ends the stream
for good without marking the client lost, and there is no reconnect. The limits are `ClientSettings` fields
(`maxReplyBytes`, `maxPingBytes`, `sseMaxLineBytes`, `sseMaxEventChars`), package-private, for tests.

**Error mapping.** A JSON-RPC error is `AgentRpcException`. Connection refused or reset, an HTTP timeout, an HTTP
status other than 200 (401 = authentication rejected, 403 = `Host`/`Origin` rejected), a reply that is not valid
protocol JSON, a handshake mismatch and a changed pid or start time are `AgentLostException`; which of them latch is
listed above. `await` returns null on the agent's `TIMEOUT`; the agent caps `sync.await` at 600 s, so a longer wait
returns null after 10 minutes. **A31 (1.x, built 2026-10-02):** `await` declares and throws `AgentRpcException` for
any other error answer (it used to surface as an `IllegalStateException` wrapping it, because the signature allowed
only `AgentLostException`); the handling of `TIMEOUT` is unchanged.

**Discovery.** A discovery directory that fails the trust check of §2.5 is ignored as a whole. Entries owned by
another user, unparsable files (at most 64 KiB are read) and files whose content contradicts their name are skipped.
A live entry needs a running process with that pid whose start time, where the operating system reports it, lies
within 60 s of the JVM start time in the file (the agent reports the JVM's own start time, which is slightly later
than the process start). `awaitPid` never attaches to the target: when a JAVAAGENT or EMBEDDED start fails, the
reason is the target's `[skillroy]` line on stderr, which `LaunchedTarget.awaitAgent` includes in its message when
the launch captured the output. An entry's `sessionId` is taken from the file when it is well-formed, and a `/ping`
against that entry must report the same one.

**A23 stale files, controller part (1.x, built 2026-10-02).** When `Discovery.list` or `entries` reads a directory
that passed the trust check, it first deletes the `skillroy-<pid>-<startMs>.json` and `.token` files of every entry
that is stale by the rule above, that is exactly the entries the listing skips as not live: no live process has that
pid, or the process's start time differs from the file's by more than the 60 s tolerance. (The specification said a
start time off by more than 2 s; built with the listing's 60 s, because Linux derives process start times from a
boot time recorded to the whole second plus any clock adjustment since, so 2 s could delete a live agent's files, and
because a listing must not delete an entry it reports as live.) Where the operating system does not report a start
time, a live pid keeps its files; a start time of 0 is never judged. Only regular files and symbolic links (the link
itself, never its target) owned by the current user are deleted; failures to delete are ignored, and an untrusted
directory is never cleaned. `list(dir, false)` and `entries(dir, false)` leave stale files alone; the one-argument
forms and the CLI's `jvms` remove them; `forPid` and `awaitPid` never delete.

**Token files (S6/S28; 1.x, built 2026-10-02).** A token file is read only **inside the discovery directory being
read** (`SecureFiles.readTokenIn`): its parent directory, as a real path, is that directory's real path; looked at
without following links, the file is a regular file (not a symbolic link) of at most 4 KiB owned by the current
user and, with POSIX attributes, without any group or other permission bit and with a single hard link; it is opened
without following links. Its name must be exactly `skillroy-<pid>-<startMs>.token`, taken from the discovery file's
own name (the specification said the name is free; built with the fixed name, so that a discovery file cannot point
at another agent's token file in the same directory). Any other `tokenFile` gives an entry without a token, and the
message says that only an owner-only token file inside the discovery directory is read and that such an agent is
reached with `--port` and `--token-file`. A discovery file whose name carries a start time of 0 or less is skipped.
`AttachSession` applies the same rule to the token file a re-attach reads back, with the name pattern
`skillroy-<attached pid>-<digits>.token` inside the session's directory.

**`SecureFiles` public (Phase 6, built 2026-10-09, BW; P6 §9.3, D138).** The controller's `SecureFiles` is a
`public final class` with exactly five public static members: `ensureOwnerOnlyDirectory(Path)` (the discovery
wording), `ensureOwnerOnlyDirectory(Path, String what)` (the refusal names `what`), `createOwnerOnlyTempDirectory(Path
parent, String prefix)` (new: a random name under `parent`, created exclusively through the POSIX or ACL branch and
read back; the replacement for every `Files.createTempDirectory`), `writeOwnerOnly(Path, byte[])` (the file must not
exist) and `untrustedDirectoryReason(Path)`; the token readers and `writeOptionsFile` stay package-private (S6/S28).
Each refuses a file system with neither view in the agent's wording, naming the role and the remedy ("use a directory
on a file system that has one of them"), and creates nothing. Its callers outside this module: the planner's work
directory and MCP configuration file (P5 §10.2), the CDP trace directory (P4 D59), the MCP server's temporary
`--out`, the end-to-end tests' discovery directories. `CredentialFiles` (P2 §4) is unchanged.

**Attach.** The measurements in this list were taken with a JDK 21 attach client, on Java 8 and Java 21 targets,
before the controller baseline moved to Java 25 (decision B3). They are kept as measured; what the Java 25 runs
show is recorded in `../TODO.md`.

- *Start-up margin.* A target younger than 5 s is given that long before the attach. The attach mechanism sends
  the target a signal (SIGQUIT on Unix) that a JVM survives only once its start-up has installed the handler.
  Measured with a raw signal: a Java 8 JVM dies when it arrives up to about 80-130 ms after spawn, a Java 21 JVM up
  to about 10 ms. The JDK 21 attach client on Linux already waits until the target catches the signal, so through
  it the hazard did not occur in 78 attaches made 0-2000 ms after spawn; the margin is a safety net for attach
  clients without that check.
- *Argument limit.* `<bootstrap jar>=t=<options file>` must be shorter than 1024 bytes (§2.4); a longer one is
  refused before attaching, with a message that says to shorten the agent home or discovery directory path. A jar
  path containing `=` and an options file path containing `;` are refused as well.
- *The `0` reply.* A JDK 21 client reports a successful load into a Java 8 target as
  `AgentLoadException: Failed to load agent library: 0`. It is accepted only for targets of Java 8 or older, and
  only the handshake confirms it. Codes 100, 101, 102 and -4 are reported as `BADJAR`, `NOTONCP`, `STARTFAIL` and
  `ENOMEM`.
- *Re-attach.* When an agent already runs in the target, the load re-publishes that agent's discovery properties.
  The endpoint then carries the nonce read back from `skillroy.agent.nonce` and the token read from the file named
  by `skillroy.agent.tokenFile`; the `/ping` handshake verifies both. A fresh load must publish the nonce this
  session generated.
- *Options file.* Written owner-only into the discovery directory (created owner-only if missing; an existing one
  must pass the trust check of §2.5) and deleted when the attach ends, however it ends. `TargetLauncher` writes its
  options file the same way and `LaunchedTarget.close()` deletes it. **S5 (1.x, built 2026-10-02):** the launch
  credential is short-lived: `LaunchedTarget.awaitAgent` deletes the options file as soon as a verified `/ping` with
  the launch nonce has shown that the agent read it, and `close()` remains the fall-back. A JVM of the same launch
  that starts later finds no options file, prints one `[skillroy]` line and starts no agent, so it cannot come up
  with the same token and nonce.
- *Dynamic loading on newer JVMs.* A Java 21 target prints the JEP 451 warning on its stderr at every dynamic load;
  with `-XX:-EnableDynamicAgentLoading` the load is refused and the JVM's own message is passed on (measured on a
  Java 21 target, before the gates moved to Java 8 and Java 25).
- *Unlisted pid* **(S18; 1.x, built 2026-10-02).** The attach mechanism's signal ends a process that is not a JVM.
  A pid the attach API does not list as a JVM is therefore attached to only when the operating system reports its
  executable and the file name (after the last `/` or `\`) is exactly `java` or `javaw` (with or without `.exe`,
  ignoring case); otherwise the attach is refused before any signal is sent (proven on a non-JVM child, which stays
  alive). The override, for JVMs started with `-XX:-UsePerfData` or behind a launcher of their own, is the
  controller-side system property `skillroy.attach.allowUnlisted=true` (`AttachSession.ALLOW_UNLISTED`); it is not
  an agent option and never reaches the options file.
- *Discovery directory* **(S8/S19; 1.x, built 2026-10-02).** `AttachSession` and `TargetLauncher` resolve the
  directory by the rule of §2.5 and always pass the resolved absolute path to the agent as `discoveryDir`
  (`AttachSession` now always sets it).

---

**Telemetry's trace parent (Phase 6, built 2026-10-09, TL; P6 §10.2, D142).** `CallOptions` gains a fifth component,
`traceparent` (the W3C trace parent of the controller's open call span; the four- and three-argument constructors
stay and carry none; `withTraceparent` copies), and `AgentClient` writes it as `_meta.traceparent` when it is set:
"the controller sends correlation id, timeout, confirmation and secret params, **and nothing else**" is amended to
"and, when telemetry supplies one, the trace parent". A `_meta` among the params is still dropped, its `traceparent`
included. The agent is unchanged: it already reads `_meta.traceparent` and stamps it on the call's events.

## 8. `skillroy-scenario`, `skillroy-junit`, `skillroy-cli`

### 8.1 Scenario YAML (Phase 1 subset; the keys are final, the supported values grow in later phases)

```yaml
scenario: basic-loan                # id, [a-z0-9-]+
pack: lending                       # DomainPack id; may be omitted when exactly one pack is on the class path
tags: [smoke]
requires: [lending.input, lending.state, lending.loan, lending.slip]   # capability ids; "a | b" = either
launchProperties: { lending.seed: A }   # system properties the target must have been launched with (checked, never set)
resolve:                            # named slots; Phase 1 supports only fixture values
  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }   # Phase 2: see below
  item:      { fixture: { code: YRD-001, barcode: "5000000000017", deposit: "3.50" } }
steps:                              # each item is a single-key map: verb -> args
  - signOn: { staff: $assistant }
  - expect: { "state.name": IDLE, "state.staff.code": $assistant.code }
  - scan: { item: $item, times: 2 }
  - expect: { "loan.lines[code=$item.code].qty": 2, "loan.total": "7.00" }
  - key: { action: DUE }
  - deposit: { amount: "10.00" }
  - expect: { "loan.authorisations": { size: 0, capability: lending.loan.authorisations } }   # guarded expectation
verify:                             # expectations evaluated after the last step
  - { "loan.change": "3.00" }
  - { "slip.last.text": { contains: "DEPOSIT DUE" } }
on_prompt: { unexpected: fail }     # fail | <pack-defined policy>
artifacts: [capture]                # extra evidence to keep even on success
timeouts: { step: 30s }
```

These eleven top-level keys, plus `ui` below and, since Phase 2, `oracle` (Phase 2, built 2026-10-02; P2 §3), are
the only ones (`ScenarioLoader.KEYS`, thirteen entries; twelve in Phase 1.x;
(Phase 4, built 2026-10-07; P4 §7.2: fourteen with `web`, which sets the web path, a default browser, frozen-target
recovery and tracing)); an unknown key is an error that names the key and its line.

**A29 the twelfth key, `ui` (1.x, built 2026-10-02).**

```yaml
ui: { clickRung: COMPONENT, strict: false }   # clickRung: ACTION | COMPONENT (default ACTION); strict: bool (default false)
```

`ui` sets how the base verb `click` clicks in this scenario (decision B12); the record is `UiSettings(clickRung,
strict)` with `DEFAULT`. Without the key nothing changes. `clickRung` is accepted in any case; with `COMPONENT` a
`click` step that names no `rung` is compiled with the COMPONENT rung, window activation and, unless `strict` is
true, the fall-back to ACTION; the exact rule is with the base verbs in §8.3 and the way a fall-back is reported in
§8.5. An unknown key inside `ui` or another value is a compile error naming the key and line.

**A fixture may not hold a secret (Phase 2, built 2026-10-02; P2 §3.1 rule 5, §4).** A fixture field whose key
`SecretKeys.looksSecret` (§3), and a fixture slot whose own name looks secret, are compile errors that say to move
the value to `credentials.yaml` and add `credential:`. The example's assistant therefore has the credential form
(in Phase 1 it was `{ fixture: { code: "100", password: "1111" } }`):

```yaml
  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
```

`credential: {realm, key}` joins the slot to the entry `<realm>/<id>` of `credentials.yaml` whose id is the text of
the slot value's field `key`; the entry's secret fields (`password`) become fields of the slot value in memory
only, at bind time, after resolution, and resolve only in the arguments of step calls and cleanup calls (never in an
expectation, a `db` check, `resolve` or `oracle`). Where the file is found, and its format, is P2 §4. The as-built
masking of fixture secrets (A25, §8.5) stays as the backstop for a version 1 plan. The thirteenth top-level key,
`oracle`, arrived with Phase 2 too (P2 §3).

`$slot` and `$slot.field.sub` references may appear anywhere inside step args and expectations, as a whole value or
embedded in a string. They are resolved at **bind time** (run start), never at compile time. A string that is
exactly one reference is replaced by the value with its type; a reference inside a longer string by the value's
text; `$$` writes a literal `$`; map keys are never substituted. A fixture may reference other slots (a cycle is an
error).

**YAML is read more strictly than plain YAML**, so that a code cannot silently change value:

- an unquoted number must be a plain decimal: `0100` (octal in YAML 1.1), `0x1F`, `1_000`, `.inf`, `.nan` and an
  integer beyond 64 bits are errors that say "write it in quotes if it is a code";
- only `true` and `false` are booleans; `yes`, `no`, `on`, `off` stay strings;
- aliases, duplicate keys, a second document and nesting deeper than 100 are errors.

**(Phase 2, built 2026-10-02)** The reader that enforces these rules (in Phase 1 `YamlReader`, package-private in
`skillroy-scenario`, throwing `ScenarioException`; deleted) is the public class
`ai.skillroy.testjava.data.yaml.StrictYaml` of `skillroy-data` (§1), with the same reading rules:
`StrictYaml.read(byte[])` returns a `Doc` (`root()`, `lineOf(container)`, `keyLine(map, key)`) or throws
`YamlException(int line, String reason)`; a second constructor and `quotesInput()` let a reader hold back a reason
that could quote the input (the credentials reader does, P2 §4). `ScenarioLoader` wraps the exception in
`ScenarioException`, and the reader's tests moved with it (`StrictYamlTest`). One reader, so that `credentials.yaml`
and scenarios are strict in the same way (P2 §2.1).

`launchProperties` names system properties the target must have been launched with; a property the application
merely defaults is "not set". `on_prompt: {unexpected: fail}` (any case) selects `PromptPolicy.FAIL`; any other
value, and the absence of the key, selects the pack's `promptPolicy()` (the name is not passed to the pack).
`artifacts`: only `capture` is implemented (kept on success as `artifacts/capture.png`); another kind is logged and
ignored, and there is no SPI for pack-defined kinds yet. `timeouts` has one key, `step` (`10s`, `500ms`, `2m`, or
a number of milliseconds; default 10 s).

(Phase 5, built 2026-10-08; P5 §13.1) The base verb `call` (§8.3) is reserved: a pack may not declare
a verb named `call`, and a pack that does is refused. The row for `call` in the §8.3 table says what Phase 5 confirmed
of the verb, whose shape it does not change. As built (2026-10-08, the Phase 5 fix wave; P5 §13.1): a `_meta` key at
any depth of a step's arguments (every verb but `expect`) is a compile error ("a step's arguments may not carry
_meta").
No top-level key is added: `inference` is a plan field the compiler writes (P5 §13.2), not a YAML key.

### 8.2 Plan

`PlanCompiler.compile(Path yaml, DomainPack pack)` is deterministic: the same YAML and pack version produce a
byte-identical `plan.json` (written with `Json.writePretty`; no timestamps, no absolute paths; the keys of every
call's args are written sorted). A load or compile error is a `ScenarioException` with file, line and reason; an
unknown verb lists the available verbs.

```json
{ "planVersion": 1, "scenarioId": "basic-loan", "pack": "lending", "packVersion": "0.1.0",
  "sourceHash": "sha256:…",                 // of the YAML bytes
  "requires": ["lending.input", "lending.state", "lending.loan", "lending.slip"],
  "slots": [ {"name":"assistant","kind":"fixture","value":{"code":"100","password":"1111"}} ],
  "steps": [ {"id":"s1","verb":"signOn","source":{"line":13},
              "calls":[ {"primitive":"lending.input","args":{"source":"Entry","value":"$assistant.code"},
                         "sync":"READY","timeoutMs":30000} ],
              "expect":[] } ],
  "verify": [ {"path":"slip.last.text","matcher":{"contains":"DEPOSIT DUE"}} ],
  "cleanup": [ {"primitive":"lending.input","args":{"source":"Key.SIGN_OFF"},"sync":"READY","timeoutMs":30000} ],
  "launchProperties": {"lending.seed":"A"}, "artifacts": ["capture"],
  "onPrompt": {"unexpected":"fail"}, "stepTimeoutMs": 30000 }
```

A call's non-positive timeout becomes the step timeout. Two optional call keys:

- `"await": {"topics":…, "match":…, "timeoutMs":…}`: present exactly when `sync` is `EVENT`. `match` is always
  written (null = any payload) with its keys sorted; a 0 timeout is replaced by the step timeout at compile time.
- `"handlesPrompt": true`: the call answers or dismisses an application prompt (omitted when false).

**A29 (1.x, built 2026-10-02):** a scenario that has the `ui` key gets the plan member
`"ui": {"clickRung": …, "strict": …}`, written last (after `stepTimeoutMs`) with both values; a scenario without the
key gets no such member, so its plan does not change (the committed `basic-loan.plan.json` stayed byte-identical). A
plan read back from disk validates `ui` the same way. The arguments the setting adds to a `click` call (`rung`,
`activateWindow`, `fallback`) are written into that call's `args` like any others: the plan shows what will be sent.

**(Phase 2, built 2026-10-02; P2 §5.)** `planVersion` is 2 (`Plan.VERSION`; 1.x had left it at 1): the compiler
always writes 2, the reader accepts 1 and 2 and refuses any other. The example above is the Phase 1 form, kept for
its call shapes; compiled now, its slot is
`{"name":"assistant","kind":"fixture","value":{"code":"100"},"credential":{"realm":"staff","key":"code"}}`: a
version 2 plan can hold no secret, because a fixture key that looks secret is a compile error (§8.1), and the plan
reader enforces the same rule on a version 2 plan it reads (a hand-edited plan cannot bring a secret back); a
version 1 plan is read as before and relies on A25. The committed sample plans were regenerated (version 2). A call
gains an optional key `capture` (§8.3), `verify` and a step's `expect` may hold `db` elements, and the members
`inference`, `oracles`, `resolutionGraph` and `bindTimeoutMs` are added; their shapes are P2 §5. The reader also
refuses a version 2 plan whose `inference` is not `none` and a `bindTimeoutMs` above 10 minutes.

`sync` per call: `NONE` (return when the call returns), `ACK` (then await the pack's ack topic since `seq.before`),
`READY` (ACK if the pack has an ack topic, then await the first ready topic since `seq.before`), `SETTLE`
(then `swing.settle`), `EVENT` (then await the first event matching the call's `await` since `seq.before`; the
pack's ack topic is not awaited). A pack verb chooses the sync of each call it emits. `EVENT` exists because an
application's acknowledgement can mean only "consumed", not "accepted", and some primitives publish no
acknowledgement at all: such a verb uses `EVENT` with the pack's ready topics instead of `READY`.

### 8.3 Domain pack SPI (package `ai.skillroy.testjava.scenario.pack`, discovered by `ServiceLoader<DomainPack>`)

```java
public interface DomainPack {
    String id();  String version();
    StepLibrary steps();
    SyncPolicy syncPolicy();
    List<FactProvider> facts();
    default PromptPolicy promptPolicy() { return PromptPolicy.FAIL; }
    /** Environment checks run before binding (desk open, device usable, …); a failure is ENVIRONMENT, not a test failure. */
    default List<PreflightCheck> preflight() { return List.of(); }
    /** Called after a scenario that did not PASS, to bring the application back to a known idle state so the next
     *  scenario can run without a restart. Must be idempotent and bounded. */
    default void recover(AgentConnection agent) throws Exception { }
}
public interface PreflightCheck { String id(); /** null = ok, else the reason */ String check(AgentConnection agent) throws Exception;
    // (Phase 2, built 2026-10-02; P2 §6.1) the entry the executor calls since Phase 2; the
    // default delegates to check(agent), so a Phase 1 check keeps working. data() routes a read by source id to the
    // agent's data.query or to a controller-side JDBC source, as every other read does (P2 §7.2):
    default String check(PreflightContext ctx) throws Exception { return check(ctx.agent()); }
    interface PreflightContext { AgentConnection agent(); DataChannel data(); String envId(); }
}
/** ackTopic: null when the domain has none (ACK cannot be used then, READY waits for a ready topic only).
 *  promptTopics: topics that mean "the application is asking a question"; the executor applies PromptPolicy to
 *  them. They are usually also ready topics. Defaults to ["prompt.waiting"] when null; an empty list means the
 *  executor never looks for unexpected prompts. Timeouts: null means the call's own timeout. */
public record SyncPolicy(String ackTopic /*nullable*/, List<String> readyTopics, List<String> promptTopics,
                         Duration ackTimeout, Duration readyTimeout) { }

public interface StepLibrary {
    Set<String> verbs();
    /** args is the YAML value of the verb (Map, List, String, Number, …) with $references left intact. */
    List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException;
    // (Phase 5, built 2026-10-08; P5 §9.5) default Map<String,Object> argsSchema(String verb)
    // { return null; }: the JSON Schema of a verb's arguments, for MCP tools and the scenario schema; null = a
    // free-form object. compile stays the authority; the schema is not checked at compile time
}
/** ctx.addCleanup(call): a call that must run when the scenario ends, however it ends (for example re-enabling a
 *  timer a verb switched off). Cleanup calls run in reverse registration order; their failures are logged in
 *  run.jsonl and never change the verdict.
 *  ctx.markHandlesPrompt(call): marks a call this verb returns as one that answers or dismisses an application
 *  prompt (matched by identity). A prompt event that arrives before such a call in the same or the next step is
 *  expected. Calls to sync.await on a prompt topic and calls to swing.dismiss are recognised without marking; a
 *  call with Sync.EVENT is not, even when its AwaitSpec names a prompt topic. */
public interface CompileContext {
    void addCleanup(PlannedCall call);
    int sourceLine();                                   // 1-based line of the step, 0 when unknown
    DomainPack pack();
    default long stepTimeoutMs() { return 10_000L; }    // the scenario's timeouts.step
    default void markHandlesPrompt(PlannedCall call) { }
    default UiSettings ui() { return UiSettings.DEFAULT; }   // A29 (1.x, built 2026-10-02): the scenario's ui key
    // (Phase 2, built 2026-10-02; P2 §6.1) what the scenario declares for a slot, or null when
    // there is no such slot; never a value. It lets a verb reject, at compile time, a staff argument whose slot
    // has no credential (in Phase 1 a verb saw only its own arguments):
    default SlotInfo slot(String name) { return null; }
    record SlotInfo(String name, String kind /*fixture|query|capture*/, String queryKind /*nullable*/,
                    CredentialSpec credential /*nullable*/, Set<String> captureFields /*empty unless capture*/) { }
}
/** topics: comma-separated globs; match: top-level payload equality with scalar values, compared by text (may be
 *  null); timeoutMs: 0 = the step timeout, at most 600 s (the agent's limit for sync.await). */
public record AwaitSpec(String topics, Map<String,Object> match, long timeoutMs) {
    public static AwaitSpec of(String topics);  public static AwaitSpec anyOf(Collection<String> topics);
    public AwaitSpec withMatch(Map<String,Object> match);  public AwaitSpec withTimeoutMs(long timeoutMs);
}
/** await is required when sync is EVENT and must be null otherwise (IllegalArgumentException). */
public record PlannedCall(String primitive, Map<String,Object> args, Sync sync, long timeoutMs, AwaitSpec await) {
    public PlannedCall(String primitive, Map<String,Object> args, Sync sync, long timeoutMs);   // the other sync levels
    public static PlannedCall of(String primitive, Map<String,Object> args, Sync sync);         // step timeout
    public static PlannedCall event(String primitive, Map<String,Object> args, AwaitSpec await);
    // (Phase 2, built 2026-10-02; P2 §3.4, §6.1) a sixth, nullable component CaptureSpec capture (the five-component
    // constructor stays); every constructor and factory above stays (capture = null). withCapture is allowed on a
    // call whose sync is EVENT or whose primitive is sync.await, else IllegalArgumentException. When the awaited
    // event arrives the executor binds the slot to the named payload fields; a path that leads nowhere fails the
    // step (FAIL / ASSERTION):
    public PlannedCall withCapture(CaptureSpec capture);
}
/** (Phase 2, built 2026-10-02) fields: slot field name -> path into the awaited event's payload; at least one, field
 *  names [A-Za-z0-9_]+, paths dotted names; kept sorted by field name. */
public record CaptureSpec(String slot, Map<String,String> fields) { public static CaptureSpec of(String slot, Map<String,String> fields); }
public enum Sync { NONE, ACK, READY, SETTLE, EVENT }

/** A root name usable in expectation paths ("loan", "app", "slip", "ui", …) and how to fetch its current value. */
public interface FactProvider { String root(); Object fetch(AgentConnection agent) throws Exception; }
    // (Phase 2, built 2026-10-02; P2 §3.1 rule 6) the root "db" is reserved for stored-row checks
    // (db.<table>): a pack whose FactProvider claims it fails compilation. No base or sample root uses it.

public interface PromptPolicy {        // what the executor does when a prompt topic arrives that no step expected
    PromptPolicy FAIL = …;             // fails the scenario on every unexpected prompt
    /** May call the agent (to answer the prompt); must be bounded. A null result counts as FAIL. */
    PromptDecision onUnexpectedPrompt(Event promptEvent, AgentConnection agent);
}
/** FAIL: the scenario fails (FAIL / ASSERTION). HANDLED: the policy answered or dismissed the prompt; continue.
 *  IGNORE: the prompt is harmless and stays; continue. The decision is recorded in run.jsonl. */
public record PromptDecision(Action action, String reason) {
    public enum Action { FAIL, HANDLED, IGNORE }
    public static PromptDecision fail(String reason);  public static PromptDecision handled(String reason);
    public static PromptDecision ignore(String reason);
}
```

Base verbs available in every pack (`BaseSteps`, looked up after the pack's own verbs; unknown argument keys are
errors):

| Verb | Compiles to |
|---|---|
| `call: {primitive, args?, sync?, await?}` | any primitive; `sync` defaults to `NONE`. `sync: EVENT` requires `await: {topic\|topics, match?, within?}` (`within` defaults to the step timeout, at most 600 s); any other sync forbids `await` |
| `call`, confirmed (Phase 5, built 2026-10-08; P5 §13.1) | Phase 5 confirmed the verb as it stands and amends nothing of its shape: `call: {primitive, args?, sync?, await?}` as in the row above, `sync` defaulting to `NONE`, the capture inside `await`, unknown keys errors (no `timeoutMs`, no `capture` key). It confirms that `call` is a reserved name a pack may not declare, that a destructive primitive's `confirm: true` in `args` reaches the call (the compiler passes it through and the executor sends it as `_meta.confirm`, P5 §12.2, D89), and that a journal export relies on the verb for a step no verb covers (P5 §12.3). (Corrected in the Phase 5 fix wave of 2026-10-08: this row first described an amendment that was never built, `sync` defaulting to the primitive's descriptor and the keys `timeoutMs` and `capture`.) As built (2026-10-08, fix wave; P5 §12.2, §13.1): a `_meta` key at any depth of a step's arguments, `call`'s `args` included, is a compile error, and the executor drops a parameter named `_meta` from every call |
| `click: {by\|target, rung?, focusTimeoutMs?, activateWindow?, force?, fallback?, strict?, await?}` | `swing.click`, SETTLE; with `await`, EVENT then a settle call. **(1.x, built 2026-10-02)** `fallback` and `strict` (A29) and `await` (A32), see below |
| `type: {by\|target, text, enter?, mode?, clear?, force?, await?}` | `swing.type`, SETTLE; with `await`, EVENT then a settle call. **(1.x, built 2026-10-02)** `await` (A32) |
| `press: {key, target?\|by?, modifiers?, force?, await?}` | `swing.key`, SETTLE; with `await`, EVENT then a settle call. **(1.x, built 2026-10-02)** `await` (A32) |
| `dismiss: {button?, dialog?, close?}` | `swing.dismiss`, SETTLE; counts as handling a prompt |
| `waitFor: {topic, match?, within?}` | `sync.await`; `within` defaults to the step timeout and is at most 119 s (the agent caps a call at 120 s). The executor adds `since`: the `seq.before` of the last call before it that was not a wait, a mark or a `swing.settle` (**A32, 1.x, built 2026-10-02:** no `swing.settle` call moves `since`, whether the executor emitted it for an `await` or a scenario sent it through `call`; the settle cannot be the action the wait refers to). A wait on a prompt topic counts as handling the prompt |
| `expect: {path: matcher, …}` | no calls; the expectations are attached to the step |
| `mark: label` or `{label, attrs?}` | `trace.mark` |
| `webClick`, `webFill`, `webDispatch`, `webWaitFlag`, `webResize`, `webTap` (Phase 4, built 2026-10-07; P4 §7.1) | `web.dom.click`, `web.dom.fill`, `web.dispatchEvent` and `web.resize` (SETTLE; with `await`, EVENT then a settle call, as A32), `web.waitFlag` (NONE; `timeoutMs` at most 119000), `web.tap` (NONE; a cleanup call removes it), each on the web path of P4 §6.1 |

SETTLE covers the event-dispatch thread only. A UI step whose effect is produced by an application thread is
followed by a `waitFor` on the application's own event (the sample's `basic-loan-ui` does this after every such
step).

**A32 event sync for the base UI verbs (1.x, built 2026-10-02).** `click`, `type` and `press` accept
`await: {topic | topics, match?, within?}`, the same shape as `call`'s (messages are prefixed `click.await` and so
on). With it the verb compiles to two calls: the primitive call with sync `EVENT` and that `AwaitSpec`, then
`swing.settle` with sync `NONE`, the arguments `{timeoutMs: min(step timeout, 118000)}` and a call timeout of that
value plus 2000 ms (the specification said no arguments; built with a bound, so that the settle is capped by the
step rather than by its own 5 s default). The step therefore ends when the application's own event has arrived
**and** the event-dispatch thread is quiet, and the separate `waitFor` is not needed. A missing event is
`SYNC_TIMEOUT`; a settle that answers `settled: false` fails the step as `SYNC_TIMEOUT` with the usual diagnostics
(§8.5).

**A29 click fidelity (1.x, built 2026-10-02).** The base verb `click` also accepts `fallback: ACTION` (the only
value, any case) and `strict: bool` (a boolean, else a compile error). `strict` is a compile-time setting: it decides
whether the compiled call carries a fall-back and is never sent to the agent. A step's own arguments win over the
scenario's `ui` setting. The arguments of the compiled `swing.click`:

1. *Rung.* The step's `rung` if it names one, and then nothing is added. Otherwise, in a scenario with
   `ui: {clickRung: COMPONENT}`: `rung: COMPONENT`, and `activateWindow: true` unless the step gives
   `activateWindow` itself. Otherwise no `rung` argument, as before.
2. *Fall-back.* `fallback: ACTION` together with `strict: true` on the same step is a compile error. Otherwise the
   step's own `fallback` when it names one. Otherwise `fallback: ACTION` exactly when rule 1 took the rung from
   `ui.clickRung: COMPONENT` and strictness does not apply; strictness is the step's `strict` when it has one, else
   `ui.strict`.
3. A `fallback` that remains needs the effective rung `COMPONENT`; with any other rung the step is a compile error
   (the primitive would answer `INVALID_PARAMS`). A rung given as a `$reference` is not checked at compile time.

So under `ui: {clickRung: COMPONENT}` an unfocusable target degrades to an ACTION click that is recorded as degraded
fidelity; with `strict: true`, for the scenario or for one step, the click does not fire and the step fails (§8.5).

Base facts (consulted after the pack's own roots; (Phase 3, built 2026-10-03; P3 §3.4: a fifth root
`devices`, every `DeviceAdapter` as `{id, kind, actions, state}` sorted by id);
(Phase 4, built 2026-10-07; P4 §6.2: a sixth root `web` after `devices`, `web.browsers[]` built from `web.browsers` with
each page's `text`, read through the run's web path; a pack root named `web` hides it)): `app` = the `app.state` value
`{providerId: {…}}`; `ui` = the `swing.snapshot` value `{seq, truncated, windows}`; `dialogs` = the list inside
`swing.dialogs` (`dialogs[0].text`); `caps` = `agent.capabilities` as a list of `{id, state, reason, strategy}` sorted
by id, because an id with dots cannot be a path field (`caps[id=swing.tree].state`).

(Phase 6, built 2026-10-09, CP; P6 §6.3, D131) A seventh base root `hooks`, after `web`, a list as `caps` is (a hook id
holds dots): `[{id, state, count, lastFiredMs, armed, last}]` sorted by id, read with the filter form
(`hooks[id=limit.applied].last.returned`). The ids and states come from `hook.health {}`; per id,
`hook.events {since: stepsSinceSeq, id, limit: 1000}` gives `count` (the number of those events), `last` (the newest
one's payload) and `lastFiredMs` (its time), where `stepsSinceSeq` is the start mark's sequence (§8.5), so that the root
describes the run's steps only (a session counts from its own start mark). `METHOD_NOT_FOUND`,
`CAPABILITY_UNAVAILABLE` or `NOT_READY` from `hook.health` (or `hook.events`) give an empty list. A pack root named
`hooks` hides it. `DomainPack` gains `hooks()` and `settings()` (P2 §6.1).

### 8.4 Expectations

Path grammar: `root(.field | [n] | [field=value] | [field~regex])*`; `last` and `first` are accepted as list
indices; a `[field=value]` filter on a list yields the single matching element (zero or several -> the path does not
resolve); `[field~regex]` searches for the regex in the field's text. Matchers: a scalar (equality), or exactly one
of `{equals}`, `{contains}` (substring, or list membership), `{matches: regex}` (searched for; anchor with `^…$` for
a full match), `{in: […]}`, `{gt|gte|lt|lte: n}`, `{size: n}` (list or map size, string length), `{exists: bool}`
(the path resolves to a non-null value), `{not: matcher}`. When a path does not resolve, only `exists` (and `not` of
it) can hold.

Numeric equality: numbers compare as decimals, and a number equals a string holding the same decimal value, so
`2 == "2.00"`. A string with a superfluous leading zero is text, not a number: `"0100"` never equals `"100"`, so
staff codes and barcodes do not compare equal to shorter ones.

An expectation is retried (poll 100 ms) until it holds or the step timeout expires; failure produces
`ASSERTION` with the path as written, the matcher, the last actual value and a UI snapshot.

**S24 bounded regular expressions (1.x, built 2026-10-02).** The regex of a `matches` matcher or of a
`[field~regex]` filter runs on text the target supplies (`SafeRegex`). Every evaluation has a budget of 1000 ms,
checked every 1024 character reads: one budget per matcher evaluation, and one per path resolution, shared by all
elements of a filtered list. A regex that runs longer, or that recurses too deeply (a `StackOverflowError`, reported
as "the regex recurses too deeply…"), is stopped, and a subject over 1 Mi characters is rejected, never truncated;
the expectation then does not hold and its message says why. It is retried like any other, so the step ends at its
timeout as an ordinary `FAIL` / `ASSERTION`. **The same bound applies to the web module's `by.url` and `by.title`
and to the CDP path's selection (Phase 6 security fix S46, A48, built 2026-10-09, SR)**: one budget of 1000 ms per
selection (never more than the call has left), checked every 1024 character reads, a `StackOverflowError` caught, a
subject over 1 Mi characters refused before the search, through the shared Java 8 class
`ai.skillroy.testjava.protocol.text.BoundedRegex`; a search that runs out is `INVALID_PARAMS` with `data {param:
"by.url" | "by.title", reason: "regex too slow"}` (`"text too long"` for the subject) on both paths.

**Guarded expectations.** A matcher object may carry `capability: <id>` next to its matcher key (in plan.json:
`{"path":…, "matcher":…, "capability": id}`). If that capability is not `OK` on this target the expectation is not
evaluated; it is recorded as skipped with the capability's state and reason, and the scenario can no longer be
`PASS`: its best outcome is `DEGRADED`. Use this only for evidence whose availability genuinely differs between
targets; anything a scenario cannot do without belongs in `requires:`.

**Java shapes, as built in Phase 1, and their Phase 2 additions (Phase 2, built 2026-10-02; P2 §5.1).**

```java
public record Expectation(String path, Object matcher, String capability) { }     // Phase 1
public final class ResolutionException extends Exception {                         // Phase 1
    public ResolutionException(String reference, String message);  public String reference();
}
// Phase 2: an element of verify and of a step's expect is a fact expectation or a stored-row check; the reader
// of a plan element decides by key (path -> Expectation, db -> RowCheck; both or neither is refused):
public sealed interface Check permits Expectation, RowCheck { Map<String,Object> toJson(); String describe();
    String capability(); }                                                        // the guard, or null
public record Expectation(String path, Object matcher, String capability) implements Check { /* as in Phase 1 */ }
// Phase 2: a reason code, so that a failure says why a reference did not resolve ("REFERENCE" for the Phase 1
// constructor; a resolver's own reason, "TRUNCATED", "RESOLVER" and the others are P2 §10):
public ResolutionException(String reason, String reference, String message);  public String reason();
public static final String REFERENCE = "REFERENCE";  public boolean hasReason();  // true for an explicit reason
```

`Plan.verify` and `Plan.Step.expect` become `List<Check>`; every Phase 1 constructor stays, so Phase 1 callers
and tests compile unchanged.

### 8.5 Executor, results, reports

```java
public final class PlanExecutor {
    public PlanExecutor(DomainPack pack, AgentConnection agent, RunOptions options);
    public RunResult run(Plan plan, Bindings bindings);          // never throws for scenario failures
}
public enum FailureKind { PRECONDITION_FAILED, REBIND_NEEDED, RESOLUTION_FAILED, ORACLE_SUSPECT, SYNC_TIMEOUT,
                          ASSERTION, TARGET_RESTARTED, AGENT_LOST, ENVIRONMENT }
public enum Status { PASS, DEGRADED, FAIL, ERROR }
/** DEGRADED = every evaluated expectation held, but at least one guarded expectation was skipped or at least one
 *  UI call ran at degraded fidelity (A29). 
 *  (Phase 3, built 2026-10-03; P3 §6.2): or at least one guarded oracle cross-check was skipped. */
public record RunResult(String scenarioId, Status status, FailureKind failureKind, String failedStepId,
                        String message, Map<String,Object> detail, List<StepResult> steps,
                        List<String> skippedExpectations, Duration duration, List<String> degradedFidelity) {
    /* a nine-argument constructor without degradedFidelity; degradedSummary() */ }
public record StepResult(String id, String verb, int line, Status status, String message,
                         List<String> skippedExpectations, Duration duration, List<String> degradedFidelity) {
    /* a seven-argument constructor without degradedFidelity */ }
/** targetRestarted: asked after the agent is lost; true classifies the run TARGET_RESTARTED, otherwise AGENT_LOST. */
public record RunOptions(Path outputDir, Duration stepTimeout, DegradedAs degradedAs /*SKIPPED|FAILURE|PASS*/,
                         Clock clock, Duration pollInterval, Sleeper sleeper, Supplier<Boolean> targetRestarted,
                         boolean captureOnFailure) { /* defaults(), with…() */ }
// A29 (1.x, built 2026-10-02): degradedFidelity is the last component of RunResult and StepResult (the
// specification put it directly after skippedExpectations; built last, so that the old constructors stay prefixes
// and existing callers compile); Scenario and Plan gained a last component UiSettings ui the same way.
```

Status per failure kind:

| Status | Failure kinds |
|---|---|
| `FAIL` (the application did not do what the scenario expects) | `ASSERTION`, `SYNC_TIMEOUT` |
| `ERROR` (the run could not be carried out) | `PRECONDITION_FAILED`, `RESOLUTION_FAILED`, `ENVIRONMENT`, `AGENT_LOST`, `TARGET_RESTARTED` (and, from Phase 2, `REBIND_NEEDED`, `ORACLE_SUSPECT`) |
| `PASS`, `DEGRADED` | none |

Run sequence:

1. check `plan.requires` against the capability matrix (`PRECONDITION_FAILED` naming each unmet id and its reason)
   and `plan.launchProperties` through `jvm.properties` (`PRECONDITION_FAILED` naming the property, the expected and
   the actual value); (Phase 4, built 2026-10-07; P4 §6.1: then step 1a, only when the plan has a `web.*` call and
   never in `resolve`: the web path, `cdp` or `agent`, is selected by the scenario's `web.path` and recorded in a
   `web.path` record; when the scenario traces, the executor decides here whether a call may carry a secret and then
   opens the path without tracing, P4 §6.4)
2. run the pack's `preflight()` checks (`ENVIRONMENT`);
3. bind slots (`Binder`; Phase 1: fixtures only) and check every reference in the plan; an unresolvable `$ref` is
   `RESOLUTION_FAILED` before any step touches the application;
4. call `trace.mark` with the label `skillroy.scenario.start`. Its sequence number is where the run's waits and
   prompt checks start, so that no event of an earlier scenario is seen; if the call fails the run is
   `ERROR` / `ENVIRONMENT` and no step runs;
5. per step: for each call substitute references, call, apply its sync (`SYNC_TIMEOUT` carries `swing.edtState`,
   `swing.dialogs` and `app.state` in `detail`), look for unexpected prompts, then evaluate the step's expectations;
6. evaluate `verify`.

A JSON-RPC error from a step call is `ERROR` / `ENVIRONMENT`, except `INPUT_REJECTED`, `NOT_FOUND`, `AMBIGUOUS` and
`MODAL_OPEN` (`FAIL` / `ASSERTION`) and `TIMEOUT` (`FAIL` / `SYNC_TIMEOUT`).
(Phase 4, built 2026-10-07; P4 §8.1: `SCRIPT_ERROR` and, from a web verb, `STALE_HANDLE` join `NOT_FOUND` and
`AMBIGUOUS` as `FAIL` / `ASSERTION`; a base `webWaitFlag` that did not turn ready is `FAIL` / `SYNC_TIMEOUT`; a web
verb's `CAPABILITY_UNAVAILABLE` and `INVALID_PARAMS`, and the CDP path's failures, are `ERROR` / `ENVIRONMENT`; a pinned
web path that is absent, whose `web.cdp` is not `OK` or whose `open()` gives a reason is `ERROR` /
`PRECONDITION_FAILED`, and one whose attach fails `ERROR` / `ENVIRONMENT`; a call the CDP path served fails with the
agent's codes and maps the same way.)
**A30 (1.x, built 2026-10-02; decision
B18):** `MODAL_OPEN` from a step call is `FAIL` / `ASSERTION`, like `INPUT_REJECTED` (it used to be `ERROR` /
`ENVIRONMENT`): the application is showing something the scenario did not expect. The agent's `MODAL_OPEN` data
carries the dialog's ref and title but no text, so the executor makes one bounded `swing.dialogs` call to find it;
the detail gets `dialogTitle` and `dialogText`, and the message ends with ` (dialog "<title>": "<text>")` (when the
text cannot be read, the message says so). `BUSY` (S30) stays `ERROR` / `ENVIRONMENT`. `AgentLostException` ->
`AGENT_LOST`, or `TARGET_RESTARTED` when `RunOptions.targetRestarted` says so (the executor sees only an
`AgentConnection`; the CLI supplies a probe that looks for a discovery file with a new pid and the same main
command).

**Click fidelity (A29; 1.x, built 2026-10-02).**

- A result of a call emitted by the base verbs `click`, `type` or `press` (a step whose verb is one of those and
  whose pack does not override it) that says `fired: false` fails the step as `FAIL` / `ASSERTION` with the message
  "<verb> did not fire: <rung>: <reason>", checked before the step's sync. It used to be ignored.
- A result of **any** step call that says `fidelity: "DEGRADED"` (the specification said the base verbs' calls; built
  for every call, so that a pack verb's fall-back is never hidden) is recorded in `run.jsonl` as
  `{type: "fidelity", step, primitive, fidelity, requestedRung, rung, reason}`, listed in the step's and the run's
  `degradedFidelity` as a line of the form "s5: click fell back from COMPONENT to ACTION (FOCUS_FAILED: …)", listed
  in run-end's `degradedFidelity`, and makes `DEGRADED` the best possible outcome of the run. The mapping of decision
  B14 applies (`RunOptions.degradedAs`), the JUnit `<skipped>` (or `<failure type="DEGRADED">`) message reads
  `DEGRADED: skipped expectations: …; degraded fidelity: …` and the step log has `degraded …` lines, the JUnit
  glue uses the same summary, the CLI's text output shows `degraded:` lines and its `--json` carries `degradedFidelity`
  per result and per step. Exit codes are unchanged: `DEGRADED` gives 0, or 1 with `--degraded-as failure`.
- `Status.DEGRADED` therefore reads: every evaluated expectation held, but at least one guarded expectation was
  skipped or at least one click was delivered with degraded fidelity. A `DEGRADED` run is not recovered, like a
  passing one.

**No error escapes a run (S24; 1.x, built 2026-10-02).** A `StackOverflowError` or `LinkageError` thrown by matcher,
path or pack code while steps run is turned into `ERROR` / `ENVIRONMENT` ("internal error: …"), so that evidence,
cleanup and recover still happen and `run` keeps its promise never to throw for a scenario failure. Other
virtual-machine errors are not caught.

**Unexpected prompts.** After each call's sync the executor reads, with `evt.query`, the events on the pack's prompt
topics since its last check. A prompt is expected when the call that saw it dealt with it (a wait on a prompt topic
that returned it, or a prompt-answering call made after it was published), or when a later call of the same step,
or a call of the next step that has calls, handles prompts. Any other prompt goes to the `PromptPolicy`.

**The only timed wait** the executor performs itself is the pause between expectation attempts
(`RunOptions.pollInterval`, 100 ms, through `RunOptions.sleeper`); every other wait ends on an event or a settled
UI. The end-to-end test asserts that the sleeper is never called for the committed scenarios (§10 check 5).

**At the end**, whatever the outcome (since Phase 2 only once the start mark was written, see below): failure
evidence is collected first, then the plan's `cleanup` calls run in reverse order (they stop at the first
`AgentLostException`), then, if the outcome is not `PASS`/`DEGRADED` and the agent was not lost, the pack's `recover`
is called. Neither can change the verdict. **H2 (1.x, built 2026-10-02):** cleanup starts from the lost state: when
the agent was already lost, no cleanup call is sent at all (before 1.x the first one still went out), and a loss seen
while reading diagnostics or evidence latches too, so after a loss no call goes out: no evidence, no cleanup, no
recover. With the client's `lost` latch (§7) the scenarios that follow on the same client end as `AGENT_LOST` without
touching the port.

**Nothing is sent to the target for a failure before the start mark (Phase 2, built 2026-10-02; P2 §10, §11.2, D30
and Q11).** Cleanup calls and `recover` run only if the start mark of step 4 was written. A failure in steps 1-3 (an
unmet `requires`, a launch-property mismatch, a failed pre-flight, a failed bind, and from Phase 2 an unserved `db`
source) and a failed start mark itself end the run with no further call: no failure evidence from the UI, no cleanup
call, no `recover`. Reason: before the mark the scenario did nothing that could need undoing, a bind is read-only,
and "a bind leaves the target as it found it" must hold by construction; a desk left dirty by an earlier scenario is
that scenario's `recover` to fix, and a pre-flight that fails on it should say so. `run.jsonl` of such a run has
neither a `cleanup` nor a `recover` record, and failure evidence is collected only for a failure during the steps or
`verify`. (In Phase 1, cleanup ran "whatever the outcome and including failures before any step", and `runCleanup`
re-bound the fixtures to do so; the H2 lost-state rule is unchanged.) The rule also governs the Phase 2 `resolve`
command, which binds and stops (P2 §12.1).

**Step 0b: hooks (Phase 6, built 2026-10-09, CP; P6 §5.3, §6.1, §6.4, D127, D129, D130).** Between the first read of the
capability matrix and the `requires` check (`requires:` may name a `hook.<id>` capability that exists only after the
install), `HookInstaller` installs the pack's hook catalogue under `RunOptions.hooks` (`AUTO`, the default, `ON` or
`OFF`; the eleventh component, every earlier constructor kept): one `hooks` record (`{mode, skipped}`,
`{mode, alreadyInstalled, catalogue, armed}` or `{mode, catalogue, installed, unavailable: [{id, state, reason,
fallback}], armed, touched, ms, otherAgents}`); an armed stub under `replay`, another catalogue on the target or an
agent error of the install is ERROR / ENVIRONMENT, hooks `ON` without a catalogue or a usable `hook.engine`
PRECONDITION_FAILED (nothing else is sent: the rule above holds). The matrix is read again when something was
installed or found installed, archived as `capabilities.json` with a `capabilities {hash, ids, file}` record, and then
checked against `requires`. The start mark's sequence is kept as `stepsSinceSeq` for the base root `hooks` (§8.3).
Hooks stay installed after the run; nothing of them enters `plan.json` or the fingerprint.

Outputs in `RunOptions.outputDir` (one directory per scenario: files of an earlier run there are replaced or
removed):

| File | Content |
|---|---|
| `run.jsonl` | one JSON object per line, flushed per line, each with `type` and `t` (ms since run start). Types: `run-start`, `pack`, `requires`, `launchProperties`, `preflight`, `bind`, `call` (params, result, duration; **(Phase 2, built 2026-10-02)** A34: also `secretParams`, the names a step call carried in `_meta.secretParams`, omitted when empty; (Phase 4, built 2026-10-07; P4 §8.2: a `web.*` call also carries `path`, `cdp` or `agent`, after `primitive`; the web record types are P4 §8.2)), `sync`, `prompt`, `expect`, `evidence`, `artifact`, `cleanup`, `recover`, `fidelity` (**A29, 1.x, built 2026-10-02**), `run-end`. The Phase 2 record types are P2 §11.3. (Phase 6 security fix, pass 2, P2-L6-F2, built 2026-10-09, SC) No record carries an image's bytes: a `call` record's `value` (and `params`), a `sync`, `cleanup` or `trace.mark` record's value and an abort's detail that hold an image, a map with `format: png` and `base64`, are written with the bytes elided (`base64: "<png: N bytes>"`, the other entries kept), in the line and in the telemetry observer's copy alike; the pixels go to `artifacts/capture.png` and `failure/capture.png` only (P5 §9.4, `…scenario.Images`); the `failure/` JSON and text files likewise |
| `bindings.json` | `{scenarioId, planHash, resolutions:{slot:{strategy, value}}}`, the Phase 1 subset of the design's shape. **(Phase 2, built 2026-10-02)** written when the bind ends and again at run end, in the shape of P2 §11.3 (with `resolveHash`, `mode`, `envId`, `env`, `oracles`, `captured`, and per slot its credential reference, never a joined field) |
| `failure/` (failures during steps or verify; never after the agent is lost) | `snapshot.json`, `app-state.json`, `dialogs.json`, `events.json` (the last 200 events) and `capture.png` (when `swing.capture.paint` is OK). The UI as indented text is also in `RunResult.detail.snapshot`. (Phase 4, built 2026-10-07; P4 §6.4: for a target with an indexed browser also `web-browsers.json` and, for each browser visible in Swing, at most four, `web-<ref>-aria.json` and `web-<ref>.png`, or `web-<ref>-error.txt` for a failed read; `web-trace.zip` when the scenario traced the CDP path and no call may carry a secret; P4 §6.2: `RunResult.detail.snapshot` renders a `WEB` surface after the Swing windows) |
| `artifacts/capture.png` | kept on success when the scenario lists `artifacts: [capture]` |
| `capabilities.json` (Phase 6, built 2026-10-09, CP; P6 §6.4, D130) | the matrix the run used, once final (after step 0b), in the wire shape of `GET /capabilities` (`CapabilityMatrix.toJson()`), ids sorted, masked; `run.jsonl` names its hash (`CapabilityMatrix.hash()`) in the `capabilities` record. `ExecutorSession.open` writes the same file into its output directory, the MCP server into its `--out` |
| `inference.jsonl` (Phase 5, built 2026-10-08; P5 §7.4) | beside `cost-ledger.jsonl` (P2 §11.3): one masked record per gateway call of the run, with the provider, the requested and the answering model, the request id and fingerprint, the stop reason, the token counts, the cost and the masked structured answer; the masked prompt text only under `--log-prompts`. `re-resolve`, `triage` and `session` write one into their own output directories |
| `failure/triage.json`, `failure/triage.md` (Phase 5, built 2026-10-08; P5 §11.2) | written by `skillroy triage` over a finished run that ended `FAIL` or `ERROR`, never by the run: the label and the summary a model gave (both answers, the models, request ids and cost) and a readable copy. The command's own `inference.jsonl` and `cost-ledger.jsonl` go to `<run dir>/triage/`; the run's `run.jsonl`, `junit.xml` and status are untouched |

**A25 fixture secrets masked (1.x, built 2026-10-02; decision B18).** A fixture field whose key
`SecretKeys.looksSecret` (§3), everything below such a field, and a whole slot whose name looks secret, are secrets
(`SecretMask`, built from the run's bindings, or from the raw fixtures when binding fails). Their values are used for
binding and for calls exactly as before, but everywhere the run writes to disk or returns evidence (every record
type of `run.jsonl`, `bindings.json`, `failure/*.json`, and the `RunResult`: message, detail, step messages, the
skipped and fidelity lists; so also the JUnit XML, the CLI's output and the JUnit glue) they are replaced by
`<redacted>`:

1. by key, wherever the fixture structure itself is written;
2. by value: any string value equal to a secret value, and a number whose text equals one (booleans never); any
   occurrence of a secret value of `masking.minLengthInText` or more characters (default 8; since 2026-10-06 the
   environment's `credentials.yaml` may set it, P2 §4) inside a longer string; and a shorter secret where it stands
   in double quotes inside a longer string (`"1111"`, the way JSON text and the executor's messages write a string
   value). The "UI at failure" text is rendered from masked values.

A secret value shorter than the threshold is therefore not masked where it stands unquoted inside free text (masking
every occurrence of "1111" would erase unrelated numbers from every message); an environment whose test accounts
have short credentials may lower the threshold and accept that (P2 §4). The sample's assistant password has
four characters, and no such leak occurred. The sample's end-to-end test checks that the password is written as a
value in none of the run outputs (§10 check 18). Before 1.x a fixture password appeared in `run.jsonl` and
`bindings.json` as it was.

**A34 on the controller side (Phase 2, built 2026-10-02; P2 §11.4).** A25 protects what the *controller* writes;
what the *agent* writes about a call is covered by A34 (§3, §5). The executor names, in `CallOptions.secretParams`
(§7) of every step call and cleanup call, each top-level argument whose template references a joined secret field
of a `credential` slot (`$assistant.password`) or the whole value of such a slot and, since 2026-10-03, an A25
fixture secret of a version 1 plan (a reference whose slot name or a field on its path looks secret, or a structure
holding such a key); the `call` record lists the names. Naming is by provenance, never by value, so that a
legitimate literal equal to some credential is not declared (P2 §11.4). With Phase 2 the mask's by-value part
moved to `skillroy-data` as `SecretMask` and is built from the credentials file as well (P2 §4); the key rule (1)
stays with the writer of a fixture structure, in the scenario module's package-private `FixtureSecrets` (in Phase 1
that module's `SecretMask`), and the rule itself does not change.

`JunitXmlReporter` writes one `<testcase>` per scenario plus an `environment` testcase for attach/handshake/
capability failures of the whole run. `FAIL` is a `<failure>`, `ERROR` an `<error>` (both with `type` = the failure
kind), and `DEGRADED` a `<skipped>` whose message lists the skipped expectations (visible, neither green nor red;
`RunOptions.degradedAs` = `SKIPPED` (default) | `FAILURE` | `PASS`, see decision B14). **S27 (1.x, built
2026-10-02):** values are scrubbed before they are escaped into the XML
(`render(suiteName, results, environmentFailure, UnaryOperator<String> scrub)`, which the CLI's `run` uses), never
the finished document, so that a `<redacted>` mark arrives as text and the report stays well-formed.

**Telemetry (Phase 6, built 2026-10-09, TL; P6 §10.2-§10.4).** `RunOptions` gains the component `observer` (a
`RunObserver`, default `NONE`; `withObserver`; P6 §6.1 places it twelfth after CP's `hooks`), which the executor tells
what the run does beside the records it writes: `scenarioStarted` before `run-start` (whose record then carries the
observer's `traceId`), each step and its end, each step and cleanup call with its waits (the call sending the open
call span as `_meta.traceparent`), every bind record and the final `bind` record, the `requires`,
`launchProperties`, `preflight`, `hooks`, `capabilities`, `recover`, `cleanup`, `expect`, `db`, `prompt`,
`fidelity` and `capture` records as written (`RunLog.observe`), each model call of the ledger, and `scenarioEnded`
after `run-end`. A throwing observer is logged once and replaced by `NONE` for the rest of the run; nothing of it
changes a verdict. With telemetry on, the CLI's `--telemetry file` writes `<out>/telemetry/spans.jsonl` beside the
scenario directories, and `JunitXmlReporter.withTraceId` ends the report with `<system-out>traceId=<hex></system-out>`.

### 8.6 `skillroy-junit` and `skillroy-cli`

`skillroy-junit`: `ScenarioTests.dynamicTests(ScenarioSource source, Supplier<AgentConnection> connection)` (also
with `RunOptions` and with an explicit `DomainPack`) returns JUnit 5 `DynamicTest`s, one per scenario.
`ScenarioSource.directory(Path)` and `ScenarioSource.classpath(String…)` list the scenario files. PASS returns,
FAIL fails the test, ERROR throws `ScenarioRunException`, DEGRADED follows `RunOptions.degradedAs` (aborted, shown
as skipped, by default). A file that does not load or compile is a test that throws its `ScenarioException`. The
connection is obtained lazily and never closed by the glue. (Phase 6, built 2026-10-09, CP; P6 §5.3, §6.1) The
overload without `RunOptions` reads `-Dskillroy.hooks=auto|on|off` (default `auto`) into `RunOptions.hooks`; another
value is the single test `environment`.

`skillroy-cli` (`ai.skillroy.testjava.cli.Main`). The build assembles `target/skillroy-cli/` (`bin/skillroy`, a
POSIX `sh` launcher, plus `lib/*.jar`, and at the top level `LICENSE`, `NOTICE` and `THIRD-PARTY.txt`; **1.x, built
2026-10-02**) and `target/skillroy-cli.zip`. `THIRD-PARTY.txt` lists the five redistributed jars (Jackson and
SnakeYAML, copied unmodified) with Maven coordinates, SPDX licence identifier and file, plus their attributions and
the components embedded in `jackson-core`; an integration test keeps it in step with `lib/` (coordinates read from
each jar's own `pom.properties`, only Apache-2.0 allowed and evidenced inside each jar, every licence or notice file
inside a third-party jar named in the list). The launcher finds Java through `JAVA_HOME` or `PATH`, refuses a Java
older than 25 ("the CLI needs Java 25 or newer"), and adds the `jdk.attach` module only for `jvms` and `attach`. The
agent home is not bundled. **`bin/skillroy.bat` (Phase 6, built 2026-10-09, BW; P6 §9.1, D137; not verified on a
Windows host):** the Windows twin of `bin/skillroy`, line for line (the same three exit-69 messages, the Java 25 check
from the runtime's `release` file, `--add-modules jdk.attach` for `jvms` and `attach` only, `-Dskillroy.launcher`
naming itself, the exit code relayed), CRLF in the source (`.gitattributes`, below the LF rule) and in the
distribution (`cli-layout.xml`'s per-file `lineEnding`), mode 0644; `BatLauncherTest` checks its text,
`LauncherIT` its bytes in the directory and the zip, `DistributionNoticesIT` its place outside `lib/`.

| Command | Does |
|---|---|
| `jvms` | lists the current user's JVMs; `*` marks those running an agent. **A23 (1.x, built 2026-10-02):** it removes stale discovery files on the way (§7) |
| `attach <pid> [--home <dir>] [--module <jar>]… [--option k=v]…` | loads the agent (§7 `AttachSession`), verifies it, prints version and modules. The agent home comes from `--home`, else `-Dskillroy.agent.home`, else `SKILLROY_AGENT_HOME`. **S18 (1.x, built 2026-10-02):** a pid that is not listed as a JVM is refused unless the CLI's JVM runs with `-Dskillroy.attach.allowUnlisted=true` (for example through `SKILLROY_JAVA_OPTS`) |
| `info` | agent version, load mode, readiness, modules, options (never the token or nonce) |
| `caps [--reprobe]` | the capability matrix |
| `schema [--name <glob>]` | the primitives with thread, timeout, capability and parameters |
| `call <method> [json \| @file \| -] [--confirm] [--timeout <d>]` | calls one primitive and prints its value |
| `snapshot [--root <ref>] [--max-depth n] [--include-hidden] [--max-chars n]` | the UI tree and the application state as indented text |
| `events [--since n] [--topics globs] [--count n] [--timeout <d>]` | streams events until interrupted |
| `compile <yaml\|dir>… [--out <dir>] [--check] [--pack <jar>]…` | writes `<scenario-id>.plan.json` next to the YAML or into `--out`; `--check` writes nothing and reports a missing or stale plan |
| `run <yaml\|plan.json\|dir>… [--out <dir>] [--junit <file>] [--suite <name>] [--degraded-as skipped\|failure\|pass] [--step-timeout <d>] [--pack <jar>]…` | compiles and loads every file first (a broken file means nothing runs), then runs them in order; with `--out` each scenario writes into `<out>/<scenario-id>/`. **(Phase 2, built 2026-10-02; P2 §7.2)** `--pack <jar>` accepts any jar (or class directory), with or without a `DomainPack` in it, so that a JDBC driver for a controller-side source reaches the loader the CLI sets as context class loader while scenarios run (that loader is as in Phase 1); the Phase 2 `run` options (`--mode`, `--env`, `--bindings`, `--credentials`, `--yes`, `--approved-by`, `--write-bindings`) and the commands `resolve` and `diff-bindings` are P2 §12.1 |
| `hooks status\|install\|remove`, `--hooks` (Phase 6, built 2026-10-09, CP; P6 §6.1, §6.2) | `skillroy hooks status` shows the target's `hook.health` (states, counts, the armed stubs, the spy's counters, the installed catalogue) and the probe's `otherAgents` beside the pack's catalogue (id, version, hash, entries, warnings) and whether they match; `install` runs `HookInstaller` in mode `ON` and prints the `hooks` record (exit 1 when refused); `remove [--ids a,b]` runs `hook.remove` and prints each class's verification (exit 1 for `HOOK_VERIFY_FAILED`). Listed after `session`. `run`, `session` and `mcp` take `--hooks auto\|on\|off` (a usage error, 64, before the agent is contacted otherwise); `mcp` installs at the server's start through its session. (Phase 6 security fix, pass 2, P2-L6-F1, P2-L6-F4, built 2026-10-09, SC) `author` takes `--hooks` as well and hands it to the planner's server; the server's `skillroy_scenario_run` runs under the server's switch |
| `re-resolve`, `mcp`, `author`, `triage`, `session` (Phase 5, built 2026-10-08; P5 §13.4) | the commands of the AI layer: batch re-resolution of an environment's bindings (P5 §8.2; it needs a job budget), the MCP server over stdio (P5 §9.2), the planner (P5 §10.2), triage of a finished run (P5 §11.2) and the interactive session (P5 §12.4). Each takes `--help` and `--json`, the connection options below where it connects, and the exit codes below unless P5 states otherwise |
| `run` and `resolve`, inference options (Phase 5, built 2026-10-08; P5 §13.4) | `--inference claude\|recorded\|none`, `--recordings <dir>`, `--inference-file <path>`, `--budget-scenario <usd>` and `--log-prompts`; the summary prints `model cost: $x (budget $y)` after `model calls: N`, and `--json` carries `modelCost`, `budget` and a summary of the `inference` records. `run --mode re-resolve` is refused (P2 §12.1) |

Every command takes `--json` and `--help`. Connection (all commands except `jvms`, `attach`, `compile`):
`--pid <pid>` (through its discovery file), or `--port <port>` with `--token-file <file>`, or neither (the only live
agent in the discovery directory); `--discovery-dir <dir>` overrides the per-user default, whose help text names the
`<user.home>/.skillroy/run` fall-back (the CLI always passes an explicit directory, so the controller's candidate
merging happens only when none is given). The token is never printed. The help and the messages say "token", not
"bearer token"; a token file that does not parse "does not hold a token".

Built for the CLI **(1.x, built 2026-10-02)**:

- **H2.** The direct mode (`--port` with `--token-file`) looks the same to the user; the token file is the key that
  verifies the peer, and is never sent. An agent whose token file lies outside the discovery directory (S6/S28) is
  reached this way, and the "token file cannot be read" messages of `--pid` and of the no-argument connection say
  so.
- **S26.** Human-readable output neutralises control characters at the one place where the CLI prints (a
  `SafePrintStream` around `out` and `err`, and the one-line helpers for role, ref, name, text, value and
  application-state keys): apart from line feed, tab and a carriage return directly before a line feed, every C0 and
  C1 control character, DEL, the line and paragraph separators and the bidirectional overrides and isolates are
  written as a backslash, `u` and four lower-case hex digits. Text that comes from the agent (component texts, error
  messages, event payloads) can therefore not drive the terminal. `--json` output stays valid JSON with the same
  content.
- **S24.** An internal error in matcher or pack code (a `StackOverflowError` included) ends the command with exit
  code 70, and `run --junit` still writes its report with the results gathered so far and the environment failure
  "the run stopped after N of M scenarios: internal error: …".

| Exit code | Meaning |
|---|---|
| 0 | done; every scenario passed (DEGRADED counts as passed unless `--degraded-as failure`) |
| 1 | a scenario FAIL or ERROR, a JSON-RPC error from `call`, a stale plan under `compile --check` |
| 2 | a scenario or plan file could not be loaded or compiled (`file:line: reason`); nothing ran |
| 3 | the agent could not be found, reached, attached or kept |
| 64 | usage error |
| 70 | internal error (a bug in the CLI or a pack) |

**Telemetry options (Phase 6, built 2026-10-09, TL; P6 §10.3).** `run`, `session`, `mcp`, `re-resolve`, `triage`
and `author` take `--telemetry none|otlp|file`, `--telemetry-endpoint <url>`, `--telemetry-attributes
standard|minimal` and `--telemetry-file <path>`; a settings error is exit code 64 before the agent is contacted (and
`run`'s `environment` testcase); with telemetry on, `run --json` carries `telemetry: {exporter, traceId}` and every
command ends with `telemetry: <exporter> <host:port or directory>, trace <id>, <n> batches dropped`. The JUnit glue's
overload without `RunOptions` reads `skillroy.telemetry`, `skillroy.telemetry.endpoint`,
`skillroy.telemetry.attributes`, `skillroy.telemetry.out`, `skillroy.telemetry.serviceName`,
`skillroy.telemetry.timeoutMs` and `skillroy.telemetry.file`, finds the provider through `ServiceLoader` and closes
it at JVM exit; an unusable setting is the `environment` test.

---

## 9. Sample lending desk

The bundled sample is the issue desk of a small community tool library: staff lend items to borrowers against a
deposit that is handed back when the items come back (decision B10). Its domain id is `lending`: the primitive
namespace, the agent module id, the pack id and the data source id.

### 9.1 Application (`samples/skillroy-sample-lending-app`, package `ai.skillroy.testjava.samples.lending`, Java 8)

A deliberately awkward little desk application. It knows nothing about the framework.

- **Main class** `LendingApp`. System properties: `lending.seed=A|B` (two differently seeded environments, default
  `A`), `lending.plugins=<comma-separated LendingPlugin class names>` (instantiated and `start(LendingApp)`ed after
  start-up), `lending.idleLockSeconds=<n>` (0 = never, default 0), `lending.title` (default "Tool Library Lending
  Desk"). **(Phase 2, built 2026-10-02; P2 §13.1)** `lending.dbPort=<port>` (0 = any free port; default: not set,
  no listener): also serve the database read-only on `127.0.0.1` (see "Database" below); `lending.dbPassword=<text>`
  (required when `lending.dbPort` is set, else ignored; built 2026-10-03): the password of the read-only account,
  secret by key (A17), so `jvm.properties`, `jvm.info` and `agent.info.options` report it as `<redacted>`.
- **Engine thread** named `LendingEngine` (non-daemon: it is the application's own thread): a state machine that
  blocks in `Inbox.take()`. States (`LendingState`):
  `SIGNED_OFF` (awaits staff code) -> `PASSWORD` -> `IDLE` -> `LOAN` (items entered) -> `DEPOSIT` -> back to `IDLE`;
  `RETURN` (return mode, toggled from `IDLE`); `LOCKED` after the idle timeout. Quantity entry and the supervisor
  code and password steps are sub-steps: they keep the state and change only the prompt line and the valid inputs.
  After start-up (create, seed and the one-off load of reference data run on the launching thread) all database work
  happens on this thread.
- **Inputs**: `LendingInput(source, value)`. Sources: `Entry` (value = the entry-field text, submitted with the
  Enter key), `Barcode` (value = the scanned code), `Key.<ACTION>` for the keypad actions (`KeyAction`) `DUE`,
  `DEPOSIT`, `RETURN`, `CANCEL`, `SIGN_OFF`, `QTY`; `DEPOSIT` and `QTY` may carry a value. An input that is not valid
  in the current state is **silently dropped** (logged at `FINE` only). `Engine.validInputs()` lists what the current
  state accepts; it is **empty while the engine is busy or a modal prompt is open**, so a caller must synchronise on
  the engine's "waiting for input" callback before the next input.

  | State (sub-step) | Prompt line | Valid inputs |
  |---|---|---|
  | `SIGNED_OFF` | Enter staff code | `Entry` |
  | `PASSWORD` | Enter password | `Entry`, `Key.SIGN_OFF` |
  | `IDLE` | Scan or enter item | `Barcode`, `Entry`, `Key.RETURN`, `Key.SIGN_OFF`, `Key.QTY` |
  | `LOAN` | Scan or enter item, or DUE | `Barcode`, `Entry`, `Key.DUE`, `Key.CANCEL`, `Key.QTY` |
  | `DEPOSIT` | Deposit received | `Entry`, `Key.DEPOSIT`, `Key.CANCEL` |
  | `RETURN`, no lines yet | Return: scan or enter item | `Barcode`, `Entry`, `Key.RETURN`, `Key.CANCEL`, `Key.QTY` |
  | `RETURN`, with lines | Return: scan or enter item | `Barcode`, `Entry`, `Key.DUE`, `Key.CANCEL`, `Key.QTY` |
  | `IDLE`, `LOAN` or `RETURN` after `Key.QTY` without a value | Enter quantity | `Entry`, `Key.CANCEL` |
  | `RETURN`, supervisor code | Enter supervisor code | `Entry`, `Key.CANCEL` |
  | `RETURN`, supervisor password | Enter supervisor password | `Entry`, `Key.CANCEL` |
  | `LOCKED` | Locked: enter password | `Entry`, `Key.SIGN_OFF` |

  With a quantity pending, the `IDLE`, `LOAN` and `RETURN` prompt lines are prefixed with `Qty <n> - `. In `DEPOSIT`,
  after an amount was entered, the line is `Deposit received <amount>: press DEPOSIT`.
- **Prompts**: `PromptService.ask(id, text, buttons)` blocks the engine thread and shows `PromptDialog`, an
  undecorated modal `JDialog` built from custom-painted components (no `JOptionPane`, no `JLabel`: the text is
  painted with `Graphics.drawString`; its buttons are `PromptButton`s, plain `JComponent`s and not
  `AbstractButton`s). It is answered by a button or by `PromptService.answer(choice)`; Enter presses the first
  button, Escape and `WINDOW_CLOSING` the last. The prompts:

  | Id | Text | Buttons |
  |---|---|---|
  | `unknown-staff` | Unknown staff code | `OK` |
  | `staff-inactive` | Staff member is not active | `OK` |
  | `wrong-password` | Wrong password | `OK` |
  | `sign-on-not-allowed` | Sign-on not allowed | `OK` |
  | `unknown-item` | Unknown item | `OK` |
  | `item-not-for-loan` | Item not for loan | `OK` |
  | `days-required` | Per-day item: enter the days with QTY first | `OK` |
  | `invalid-quantity` | Invalid quantity | `OK` |
  | `induction-check` | Has the borrower completed the safety induction? | `Yes`, `No` |
  | `return-not-permitted` | Return not permitted | `OK` |
  | `return-not-allowed` | Return not allowed | `OK` |
  | `supervisor-required` | Supervisor required | `OK`, `Cancel` |
  | `authorisation-failed` | Authorisation failed | `OK` |
  | `invalid-amount` | Invalid amount | `OK` |
  | `insufficient-amount` | Deposit received is less than the amount due | `OK` |
  | `posting-failed` | Loan could not be recorded | `OK` |

- **UI** (`LendingFrame`, 1024x768): prompt line and status line; an entry field (`EntryField`, a `JTextField`
  named `entry` that masks passwords itself); a loan table; totals; a keypad of `IconKey`s (icon only, **no text, no
  accessible name and no component name**, each with an `actionId` that is a `KeyAction` name); a slip panel
  (`JTextArea` named `slip`, the "printer"). An `IconKey` debounces: a press starts a 60 ms `javax.swing.Timer`, and
  when it expires the action is delivered only if the key owns the focus or its `focusGated` flag is false (default
  true). A naive `doClick()` on an unfocused window therefore does nothing. `IconKey.perform()` delivers the action
  directly. **Fix wave (Phase 6 review, FD, 2026-10-09):** the entry field submits on Enter through `JTextField`'s
  own key binding, whose action submits the text field that most recently had the focus, not the key event's
  target. Typed characters delivered with `dispatchEvent` reach the field without the focus; an Enter delivered that
  way submits it only once it has had the focus (a freshly shown frame gives it the focus by itself, after a round
  trip to the X server); `postActionEvent()` always does. The behaviour is unchanged; it is recorded because the
  sample's own display test raced it.
- **Listeners**: `EngineListener { stateChanged(from,to); inputRequested(state, prompt); inputAccepted(input);
  promptShown(id,text,buttons); promptAnswered(id,choice); loanPosted(loanId); slipPrinted(loanId, lines) }`,
  registered with `Engine.addListener` (a plain `ArrayList`: register only while the engine is blocked waiting for
  input, as the Javadoc says). `Engine.listenerCount()` is 1 when the window is up (the frame's own listener).
- **Business rules**:
  - *Sign-on* needs permission `SIGN_ON` and the right password (staff flagged `auto_sign_on` sign on with the code
    alone).
  - *Items.* `Barcode` looks the barcode up; `Entry` looks up a barcode, then an item code. An unknown item shows
    the prompt "Unknown item" with `OK`. `QTY` then a number sets the quantity for the next item only. Scanning a
    counted item that is already on the loan adds to its line.
  - *Returns.* In `RETURN` mode a scanned line is negative and needs permission `RETURN`. If the cumulative return
    value of the loan (the absolute sum of its return lines, the new one included) exceeds the signed-on staff
    member's return limit (the maximum `role_cap.amount` over their roles for kind `RETURN`; no row = unlimited;
    the table was renamed in Phase 2, see "Database" below) and is within the system maximum
    (`desk_setting.max_return`), the engine prompts "Supervisor required" and asks
    for a supervisor code and password (permission `RETURN_OVERRIDE` and a sufficient limit; self-authorisation is
    refused), recording a `loan_authorisation` of kind `RETURN_OVERRIDE`, which also covers later lines up to the
    supervisor's limit; a failed authorisation prompts "Authorisation failed" and the line is not added. Above the
    system maximum the engine prompts "Return not allowed"; without the permission, "Return not permitted".
  - *Deposit due.* `DUE` applies the waiver rules (`BORROW_N_WAIVE_M`: for every `borrow_qty + waived_qty` units
    across the rule's items the lowest `waived_qty` deposits are waived; not on a return) as `loan_waiver` rows
    (positive amounts; total = subtotal - waivers) and moves to `DEPOSIT`.
  - *Deposit received.* `DEPOSIT` with an amount >= the amount due records the loan (the tables below, in one
    transaction), prints the slip and returns to `IDLE`. `DEPOSIT` without an amount uses the amount entered before
    it, else exactly the amount due. A smaller amount prompts "Deposit received is less than the amount due". A
    return hands its (negative) total back and gives no change. A loan's id is null until it is recorded.
  - *The slip* (40 columns, deterministic): header line(s); `LOAN <id>` or `RETURN <id>` with `STAFF <code>`; per
    line the title, then code, quantity x unit deposit and amount; `SUBTOTAL` and one `WAIVER <rule code>` line per
    waiver when there are waivers; `DEPOSIT DUE`; `RECEIVED`; `CHANGE`; one `RETURN_OVERRIDE AUTHORISED BY <code>`
    line per authorisation.
  - *Cancel and lock.* `CANCEL` abandons the open loan and any pending sub-step and returns to `IDLE`; nothing is
    recorded. With a positive idle-lock time, waiting that long in `IDLE`, `LOAN`, `RETURN` or `DEPOSIT` with no
    sub-step pending locks the desk; the signed-on staff member's password unlocks it to the same state, and
    `SIGN_OFF` from `LOCKED` abandons any open loan.
  - Beyond these the application has induction-required items (a `Yes`/`No` prompt on a loan), per-day items (the
    number of days is entered with `QTY` first; half days are allowed) and inactive items ("Item not for loan"); the
    Phase 1 scenarios do not use them.
- **Database**: HSQLDB in-memory (`jdbc:hsqldb:mem:lending`), created and seeded at start-up. Tables: `staff(code,
  name, password, active, auto_sign_on)`, `role(id, name)`, `staff_role(staff_code, role_id)`, `role_permission(
  role_id, permission)`, `role_cap(role_id, kind, amount)` (**Phase 2, built 2026-10-02:** the limit table was
  renamed to `role_cap`, columns unchanged, as the first step of Phase 2, in the application, its seeds, the agent
  module, the pack, every test and every document; the pack's fingerprinter needs the name as an upper-case literal
  for the database's catalogue, and the project's neutrality check rejects the upper-case form of the Phase 1 name,
  which this file therefore no longer spells; P2 §13), `desk_setting(name, value)`, `item(code, barcode,
  title, deposit, active, induction_required, per_day)`, `waiver_rule(id, code, description, kind, borrow_qty,
  waived_qty, active, starts_on, ends_on)`, `waiver_rule_item(rule_id, item_code)`, `loan(id, staff_code, posted_at,
  total)`, `loan_line(loan_id, line_no, item_code, qty, unit_deposit, amount, returned)`, `loan_waiver(loan_id,
  rule_code, amount)`, `loan_deposit(loan_id, amount)`, `loan_authorisation(loan_id, kind, staff_code)`.
  Money columns are `DECIMAL(10,2)`, quantities `DECIMAL(10,3)`. Loan ids come from the sequence `loan_id_seq`,
  which starts at 1001 in seed `A` and at 50001 in seed `B`. Settings: `max_return` (500.00 in `A`, 350.00 in `B`)
  and `slip_header` ("TOOL LIBRARY - MILL LANE" in `A`, "TOOL LIBRARY - CANAL STREET" in `B`). Permissions:
  `SIGN_ON`, `RETURN`, `RETURN_OVERRIDE`; the one limit kind is `RETURN`. **(Phase 2, built 2026-10-02; P2 §13.1)**
  With `lending.dbPort` the desk also serves this database through HSQLDB's own server (`LendingDbServer`:
  `127.0.0.1` only, at most 16 connections, daemon threads; `LendingApp.dbServer()`) to the read-only account
  `reader` (`LendingDb.READER_USER`), whose password is the value of `lending.dbPassword`
  (`LendingDb.createReadOnlyUser(String)`; built 2026-10-03) and which may read every table and change nothing;
  `app.state` reports the bound port as `dbPort`. Such a database is created by
  `LendingDb.createServable`, with one administrator of random name and password and no `SA`, because HSQLDB 2.7
  lets any connected user change the password of any user whose name it knows.
- **Seeds** `A` and `B` hold the same kinds of people and items under different codes, names, deposits and limits,
  so that a scenario written against one cannot hard-code values that work on the other: a desk assistant with a low
  return limit, one without the `RETURN` permission, a supervisor, a manager without a limit, an inactive and an
  auto-sign-on staff member, one whose role cannot sign on; hand tools with small deposits, power tools with larger
  ones, an item above the system maximum, an induction-required, a per-day and a retired item; one effective waiver
  rule, one inactive and one outside its dates. Used by the committed scenarios, from seed `A`: assistant `100`
  (password `1111`, role `DESK_ASSISTANT`, return limit 20.00) and item `YRD-001` "Garden spade" (barcode
  `5000000000017`, deposit 3.50, in no active waiver rule).

### 9.2 Agent module (`samples/skillroy-sample-lending-agent`, module id `lending`, package `…samples.lending.agent`)

Compiled against the application jar (`provided`), loaded from `modules/` or the `modules=` option.
Registers: `ReadinessSource` (`lending.engine`), `ThreadPolicyProvider` (one daemon thread
`skillroy-lending-worker`, created on first use; the `LendingEngine` thread is forbidden), `AppStateProvider`
`lending`, `NameEnricher` (`IconKey` -> its `actionId`; it also names the prompt's painted buttons),
`LocatorResolver` strategy `action`, `ActionFirer` for `IconKey` (calls `perform()`) and for the prompt's buttons
(so `dismiss: {button: OK}` works on the desk's prompts), `DialogTextExtractor` (`lending`) for `PromptDialog`,
`DataSourceProvider` `lending` (a read-only JDBC connection), `DeviceAdapter` `printer` (kind `printer`; state
`{slips, kept, header, last}`; action `lastSlip`), and one `EngineListener` that publishes events. How the desk is
found: in EMBEDDED mode the `LendingApp` the host passed to `EmbeddedAgent.start`; in the other modes
`LendingApp.instance()`, looked at only once a thread named `LendingEngine` exists, so that no application class is
loaded before the application's own `main`.

**When the engine listener is attached, and readiness.** The application allows a listener to be added or removed
only while its engine waits for input. The readiness source attaches the listener at the first poll that finds the
engine waiting for input, so normally the module is ready exactly when its listener is in place. A desk that is
asking a question does not wait for input and may stay that way until somebody answers; only a ready agent can
answer it. So:

- the module is ready when the engine waits for input **or** a prompt is open;
- in the second case there is no listener yet and the capability `lending.input` is `UNAVAILABLE` with a reason that
  names the prompt;
- `lending.prompt.answer` answers the prompt, then waits (at most 2 s) for the engine to wait for input again,
  attaches the listener and reports `listening`;
- if a person answers at the desk instead, a daemon thread `skillroy-lending-attach` (4 Hz, alive only while the
  module is ready without a listener) attaches the listener;
- on such a late attach `lending.input` becomes `OK` and one `input.requested` event is published from the engine's
  current status, so that a controller waiting for the desk has its event.

This is the pattern a domain module needs whenever its application can sit in a modal question at attach time.

`uninstall` makes the listener inert at once and removes it when the engine next waits for input (it waits up to
3 s for that); if a prompt stays open, a daemon thread `skillroy-lending-detach` finishes the removal later (bounded
at 30 minutes), and the inert listener does nothing until then. Because one agent's removal and the next agent's
attach can fall on the same moment, every change of the engine's listener list happens under one JVM-wide monitor
shared by every class-loader copy of the module.

| Primitive | Params | Result `value` / behaviour |
|---|---|---|
| `lending.input` (mutating, `APP`, timeout 15000) | `source, value?, ackTimeoutMs? (5000, at most 60000)` | pre-checks `validInputs()` (`INPUT_REJECTED` with `data.source`, `data.state`, `data.validInputs`, `data.prompt`, `data.awaitingInput`, `data.modalPrompt`), queues the input, waits for `inputAccepted` -> `{accepted:true, acked:bool, ackMs}`. `NOT_READY` while the engine listener is not attached |
| `lending.state` (`APP`) | – | `{name, prompt, validInputs:[…], staff:{code,name}\|null, returnMode, locked, awaitingInput}` |
| `lending.loan` (`APP`) | – | the open loan, else the last recorded one: `{id\|null, open, returning, staffCode, lines:[{lineNo,code,title,qty,unitDeposit,amount,returned,perDay}], waivers:[{ruleCode,amount}], subtotal, waiverTotal, total, deposits:[{amount}], change, authorisations:[{kind,staffCode}], postedAt}`; money as decimal strings with two decimals, quantities as decimal strings. Before the first loan: the same shape with `id: null`, `open: false`, empty lists and zero amounts |
| `lending.prompt` (`APP`) | – | `{id,text,buttons}` or `null` |
| `lending.prompt.answer` (mutating, `APP`) | `choice, id?` | `{answered:true, id, choice, listening:bool}`; `NOT_FOUND` when no prompt is open (or the open prompt is not `id`), `INVALID_PARAMS` when `choice` is not one of its buttons. It publishes no `input.acked` |
| `lending.slip` (`APP`) | `last?:int (1, at most 100)` | `{slips:[{loanId, lines:[…], text}]}`, newest first |
| `lending.access.check` (`APP`) | `staff, permission, amount?` | `{allowed:bool, limit:string\|null, reason}` using the application's own access code. **(Phase 2, built 2026-10-02; P2 §13.2)** the answer gains `autoSignOn:bool`, the application's own flag for the staff member (`true` when entering the code signs on without a password), so that a bind-time verifier can check "is asked for a password" against the application. `false` for an unknown or inactive staff member, the member's own flag (`Staff.autoSignOn()`, which the engine reads at sign-on) otherwise (the build of 2026-10-02 reported an inactive member's own flag; corrected 2026-10-03) |
| `lending.waiver.evaluate` (`APP`) **(Phase 3, built 2026-10-03; P3 §10.2)** | `lines:[{code\|barcode, qty}], date?` | the desk's own dry run of its waiver rules on synthetic counted lines: `{date, waivers:[{ruleCode, amount}]}` |

All but `lending.input` have a timeout of 5000.

Events (source `lending`): `state.changed {from,to}`, `input.requested {state,prompt,validInputs}`,
`input.acked {source}`, `prompt.waiting {id,text,buttons}`, `prompt.answered {id,choice}`,
`loan.posted {loanId,total}`, `slip.printed {loanId}`. `input.acked` is published by `lending.input` itself, on the
calling thread, so it exists only for inputs that come through `lending.input`; an input typed or clicked in the
window has none, which is why UI steps wait for `input.requested`. A rejected input raises the `INPUT_REJECTED`
error and publishes no event (§3, A33).
Capabilities: one per primitive name, plus `lending.sql` and `lending.loan.authorisations` (OK; it exists so that
guarded expectations have something real to point at); all are probed against the running desk. `app.state` section
`lending`: `running`, the `lending.state` fields, and `modalPrompt`, `loanOpen`, `lastLoanId`, `slips`,
`engineListeners`, `seed` and, since 2026-10-03, `dbPort` (the database listener's bound port, null without one; not
a fingerprint component); (Phase 3, built 2026-10-03; P3 §10.2) `today`, the desk's business date.
Scenario cleanup: the pack's `signOn` verb registers a `Key.SIGN_OFF` cleanup call, and `recover` answers any open
prompt with its first button, cancels any open loan and signs off.
EMBEDDED mode: the jar also contains `…samples.lending.agent.embedded.SkillroyLendingPlugin implements
LendingPlugin`, which calls `EmbeddedAgent.start` with the running `LendingApp` as host context. It never enables
the agent itself and stops it only if it started it.

**Phase 1.x in the module (1.x, built 2026-10-02).** A21: the module registers a `RedactionKeys` (`LendingSecrets`)
whose pattern, `^lending\.(?:[A-Za-z0-9_-]+\.)*pin$` ignoring case, matches `lending.pin` and `lending.<…>.pin`
(not `lending.pinned` or `lending.spin`), property names of its own that the built-in rule does not recognise; the
module's tests show which keys match, and the end-to-end test launches the desk with `-Dlending.pin=…` and shows
that `jvm.properties` and `jvm.info` report `<redacted>` and that the value appears in no reply (§10 check 3). A18:
the module keeps the handles its capability declarations return and closes them in `uninstall()`, so it no longer
relies on the core to withdraw them. A19: the module's `ThreadPolicyProvider` keeps the default `available()`; its
worker thread is created on first use, so there is no moment at which the application thread it supplies does not
exist. A31: the pack's tests let the fake `await` throw `AgentRpcException`, and recovery stops at such an error
instead of trying again.

### 9.3 Domain pack and scenarios

`samples/skillroy-sample-lending-pack` (`DomainPack` `lending`, version `0.1.0`; **(Phase 2, built 2026-10-02)**
`0.2.0`, with the resolution catalogue, fingerprinter, stored tables, row mask and the verb `authorise` of P2 §13.3):
`SyncPolicy(ack = input.acked, ready = [input.requested, prompt.waiting], prompts = [prompt.waiting])`; pre-flight
check `lending.database` (`data.query` on source `lending` answers and finds staff; since Phase 2 it reads through
`PreflightContext.data()`, so over JDBC where the environment serves `lending` that way); facts `loan` (->
`lending.loan`), `state` (-> `lending.state`), `slip` (-> `lending.slip` with the last 10, oldest first, so that
`slip.last` is the newest), `prompt` (-> `lending.prompt`). Verbs:

| Verb | Compiles to |
|---|---|
| `signOn {staff}` | `lending.input` Entry with the code, then Entry with the password, each READY; registers the cleanup call `lending.input Key.SIGN_OFF` (READY, so that the next scenario's first input does not land while the engine is still busy). `staff` is a `$slot` reference to a fixture with `code` and `password`, or a mapping with those keys. Use an assistant without `auto_sign_on`. **(Phase 2, built 2026-10-02; P2 §13.3)** `staff` must be a `$slot` reference whose slot declares a `credential` (checked at compile time through `CompileContext.slot`, §8.3); the mapping form `{code, password}` is removed, and a fixture holding the password is a compile error (§8.1). The password reaches the second `lending.input` call from the credential join at bind time, and that call carries `_meta.secretParams: ["value"]` (A34), set by the executor because the call's `value` references the joined field `password` |
| `signOff` | `lending.input Key.SIGN_OFF`, READY |
| `scan {item\|barcode, times?}` | `times` (1 to 99, default 1) x `lending.input Barcode`, READY. `item` is a `$slot` reference (its `barcode` field is used) or a mapping with `barcode` |
| `key {action, value?}` | `lending.input Key.<action>`, READY; `action` is one of `DUE`, `DEPOSIT`, `RETURN`, `CANCEL`, `SIGN_OFF`, `QTY` |
| `enter {value}` | `lending.input Entry`, READY |
| `quantity {n}` | `lending.input Key.QTY` with the quantity, READY |
| `deposit {amount?}` | `lending.input Key.DEPOSIT` with the deposit received (exactly the amount due when absent), READY. `amount` is the only argument |
| `expectPrompt {text?, id?, within?}` | `sync.await` on `prompt.waiting` (`within` defaults to the step timeout and is at most 119 s); counts as handling the prompt |
| `answerPrompt {choice, id?}` | `lending.prompt.answer` with sync `EVENT` on the pack's ready topics (the answer publishes no acknowledgement); marked as handling the prompt |

Unknown argument keys are errors, here as for the base verbs. Values are sent as strings: codes, barcodes and money
are written in quotes in the YAML.

`recover` is bounded (at most 20 actions and 60 s); every action is followed by a wait for the desk's own event,
never by a pause. When `lending.prompt.answer` reports `listening: false` it does not wait for an event the module
could not have published.

`samples/skillroy-sample-lending-scenarios`: `scenarios/basic-loan.yaml` (engine-level inputs: sign on, scan two
garden spades, `DUE`, a deposit of 10.00 against 7.00 due) and `scenarios/basic-loan-ui.yaml` (the same loan driven
through `type` into the entry field, found with `by: {name: entry}`, and `click: {by: {action: …}}`; see the next
paragraph for how its steps wait), their committed `plan/*.plan.json` (`PlansUpToDateTest` compares them byte for
byte; regenerate with `-Dskillroy.plans.update=true`), seven negative scenarios under `src/test/resources/negative/`
(`wrong-expectation`, `missing-capability`, `guarded-unavailable`, `prompt-left-open`, and the three below), and the
end-to-end tests of §10. The module's build unpacks the agent home into `target/skillroy-agent/` (with the lending
module in `modules/`) and copies the target's class path into `target/it-target/`.

**Phase 1.x in the scenarios (1.x, built 2026-10-02).** `basic-loan-ui.yaml` sets `ui: { clickRung: COMPONENT }`
(A29) and requires `swing.click.component`; seven of its UI steps carry `await:` (A32) instead of a separate
`waitFor` (`await: {topic: input.requested, match: {state: …}}`, and `loan.posted` with `total: "7.00"` for the
deposit), and one explicit `waitFor` stays (the desk returning to `IDLE` after the deposit, where it publishes two
events in turn), so that both forms remain covered. Its three clicks carry `focusTimeoutMs: 5000`: the primitive's
500 ms default is tight for bringing the window to the front on a display without a window manager under emulation,
and without the margin a slow activation would fall back to the ACTION rung and the scenario would wrongly end
`DEGRADED` (decision B12). Three negative scenarios were added (the specification named two, `click-fallback` and
`click-strict`): `click-fallback-degraded` (a COMPONENT click, not strict, on the prompt's painted OK button, which
can never take the focus, so the fall-back is deterministic on any display; the run must end `DEGRADED`),
`click-strict-fails` (the same click with `strict: true`; `FAIL` / `ASSERTION`) and `modal-open-step` (clicking
`DUE` while the prompt is open; `FAIL` / `ASSERTION` with `MODAL_OPEN`, A30). The specification's own example of a
fall-back relied on the focus not returning after a dialog, which depends on the window manager.
`PlansUpToDateTest` accepts the `swing.settle` call (sync `NONE`) that follows an awaited UI call, and checks the
exact click arguments of the UI scenario (three COMPONENT clicks with window activation and the ACTION fall-back,
seven awaited UI steps, one explicit wait) and of each new negative scenario.

---

## 10. End-to-end acceptance for Phase 1

(Phase 6, built 2026-10-09, CP; P6 §8, §9.5, §13.2) `LoadModeChecks` gains check **1b** (no JEP 451 text in the
target's merged log in `JAVAAGENT`, `JAVA_TOOL_OPTIONS`, the hooks leg and `EMBEDDED`, on Java 8 and Java 25; in
`ATTACH` on a target of Java 21 or newer the texts must be there, the positive control) and check **1c** (the matrix
after `target.ready` equals the committed golden of the mode, `src/test/resources/capabilities/golden/<mode slug>.json`,
normalised by `CapabilityGolden`: ids, states and strategy strict, reason by presence, the allow-list
`allow-differences.json`, empty; `-Dskillroy.golden.write=true` rewrites). The `@Order` values are renumbered in tens
(check 1 is 10, 1b and 1c are 20 and 30, checks 2 to 19 are 40 to 210) so that `HookChecks` takes values between them.
Check 13 also clicks the keypad's `SIGN_OFF` on the `DISPATCH` rung; check 14 expects seven committed scenarios (E is
DEGRADED, reported as skipped, without hooks and PASS in the hooks leg) and every glue run's `hooks` record and
`capabilities.json`; checks 1 and 15 list the `hooks` module in the hooks leg, where `hook.engine` is `OK` (absent in
every other mode). The hooks leg is a fifth mode, `JAVAAGENT_HOOKS` (`-javaagent` with
`optionalModules=skillroy-agent-hooks`; `LendingTarget.attach()` reuses the launch's options), run by
`LendingEndToEndIT.JavaAgentHooks` with `HookChecks` (P6 §13.2 items 1-3, 6); `ResolutionJavaAgentHooksSeedA/B`,
`InferenceEndToEndIT.JavaAgentHooksSeedA/B` and `BranchEndToEndIT` are P6 §13.2 items 4, 5 and 8.

`LendingEndToEndIT` (failsafe, needs a display) launches the sample lending desk as a child process with the Java
executable named by `-Dskillroy.it.targetJava` (default: the running JVM's), with `-Dlending.pin=<a value>`, and,
for **each load mode** `JAVAAGENT`, `JAVAAGENT via JAVA_TOOL_OPTIONS`, `ATTACH`, `EMBEDDED`, runs the checks of
`LoadModeChecks` against that one process, in order, through the real `PlanExecutor` and `AgentClient` over HTTP,
and where a check must send what the client never sends, over a plain bounded loopback socket (`RawHttp`). There are
19 checks per mode; the JUnit-glue check is three dynamic tests, so each mode runs **21 tests, 84 in all** (before
1.x: 13 checks, 52 in all; none was weakened). Each check leaves the desk signed off, except the last, which ends the
target. The checks, numbered as they run (the Phase 1 items are kept as they were and say what 1.x added to them):

1. *Handshake, load mode, instrumentation.* Connects and passes the handshake; `agent.info.loadMode` is right;
   `jvm.instrumentation` is OK except in EMBEDDED; the target is the launched process (pid) on the configured Java
   (`java.home`). Per mode the test also asserts how the agent got there: `-javaagent` in the JVM's own arguments
   (JAVAAGENT); no `-javaagent` in the command and `JAVA_TOOL_OPTIONS` carrying it with `mainClassGuard=`
   (JAVA_TOOL_OPTIONS); no agent in command, environment or JVM arguments, and the attach reply a Java 8 target must
   show (ATTACH); `loadMode EMBEDDED` and `instrumentation false` (EMBEDDED). For the record, not asserted, the
   check prints the per-call cost: the median and mean of 40 `agent.info` calls on the kept-alive connection and of
   20 `/ping`s.
2. *HMAC handshake, Bearer refused (H2, H1; 1.x).* On raw sockets against the real agent: `Bearer <token>` on
   `/ping`, on `/ping?nonce=…` and on `POST /rpc`, and no header at all, each get 401 with
   `WWW-Authenticate: Skillroy-HMAC`, no MAC, no session header and `Connection: close`. A hello signed with the
   token gets 200; the reply MAC, the session header, the echoed client nonce and the agent nonce all check out with
   the token, and the MAC does not check out with any other key. A signed `POST /rpc agent.info` executes and its
   reply MAC checks out. A hello or a request signed with another key gets 401. No reply contains the token, and
   none of this disturbs the client.
3. *Module redaction keys (A21; 1.x).* `jvm.properties` returns `lending.pin` as `<redacted>` (and `lending.seed` as
   it is); `jvm.info` lists `-Dlending.pin=<redacted>`; the PIN appears in no `jvm.info`, `jvm.properties` or
   `agent.info` reply.
4. *Keypad keys.* `swing.snapshot` shows the keypad buttons named by their `actionId`; `swing.find
   {by:{action:"DUE"}}` returns one.
5. *Committed scenarios pass without sleeping.* The committed plans of `basic-loan` and `basic-loan-ui` -> `PASS`,
   with zero sleeps in the executor: no call of the executor's sleeper, every expectation held at its first attempt,
   every wait ended by an event or a settled UI; `run.jsonl` and the JUnit XML are written and asserted. 1.x: every
   passing run must have no degraded-fidelity entry, and `basic-loan-ui` fires its three clicks on the COMPONENT
   rung at full fidelity (A29).
6. *Wrong expectation.* A scenario with a wrong expectation fails as `ASSERTION` with a snapshot and a PNG capture
   (the image must decode, be at least 800x600 and have at least 8 colours).
7. *Recover.* After a scenario that fails with a prompt open, `recover` leaves the desk signed off and the next
   scenario passes.
8. *Missing capability, guarded expectation.* A scenario requiring a missing capability fails as
   `PRECONDITION_FAILED` and runs no step; a guarded expectation on an unavailable capability yields `DEGRADED`.
9. *COMPONENT rung on a blocked window.* `swing.click` with rung `COMPONENT` on a window blocked by the modal
   prompt is `MODAL_OPEN`; with `force`, and with `force` + `activateWindow`, it reports `FOCUS_FAILED` with
   `fired:false` and nothing is clicked. Positive control: once the prompt is gone the same rung without activation
   is still `FOCUS_FAILED` (nothing returns the focus to the frame without a window manager) and with
   `activateWindow` it clicks and the desk reaches `DEPOSIT`.
10. *Forced fall-back (A28/A29; 1.x).* `click-fallback-degraded` ends `DEGRADED` with no failure kind and no skipped
    expectation; exactly one entry, `s6: click fell back from COMPONENT to ACTION (FOCUS_FAILED: …)`, is listed in
    the run result, in that step's result and as one `fidelity` record in `run.jsonl`, whose `requestedRung` is
    `COMPONENT` and `rung` `ACTION`; the sent `swing.click` carried `rung: COMPONENT` and `fallback: ACTION`; every
    other step passed; the JUnit XML shows it under `<skipped>`; no recover ran, the desk ends signed off and the
    prompt was answered by the fall-back click.
11. *Strict (A29; 1.x).* `click-strict-fails` ends `FAIL` / `ASSERTION` at `s6` with the message
    `click did not fire: FOCUS_FAILED: …`, `fired: false` in the detail and nothing listed as degraded; no
    fall-back was sent; recover ran, succeeded and answered the prompt.
12. *Modal dialog in the way (A30; 1.x).* `modal-open-step` ends `FAIL` / `ASSERTION` at `s6`; the message contains
    `MODAL_OPEN` and "Unknown item", the detail has `dialogText` "Unknown item" and a `dialogTitle` key,
    `failure/dialogs.json` and `failure/app-state.json` show the open prompt, and recover succeeds.
13. *UI scenario after a prompt (A29 with window activation; 1.x).* Whether the desk window was focused before the
    run is recorded, not asserted (it depends on the window manager; it was unfocused in every run). The committed
    `basic-loan-ui` passes, all three clicks fire on COMPONENT at full fidelity and there is no degraded entry: window
    activation works on the test display.
14. *JUnit glue.* The dynamic-test glue runs the committed scenarios (three dynamic tests); 1.x: at full fidelity
    and with COMPONENT clicks.
15. *Second attach.* A second attach to a running agent reuses it (same port, nonce and start time).
16. *`agent.disable` (H2; 1.x additions).* `agent.disable` leaves the application running and removes the engine
    listener: a fresh attach gets a new nonce and the application's listener count is back at 2 (the window's and
    the new agent's); no thread of the disabled agent is alive (compared by thread id), exactly one control server
    (one `skillroy-http-accept` thread) exists, every agent thread is a daemon, and both committed scenarios pass
    again, the UI one at COMPONENT and full fidelity. 1.x: after the disable the client is latched lost (`isLost()`;
    `call` and `ping` throw `AgentLostException`), and a request signed for the old session, accepted before the
    disable, gets 401 from the new agent, whose session differs, without disturbing the new client.
17. *Agent attached while a prompt is open.* Such an agent is ready and the pack recovers (§9.2); 1.x: the same
    latch assertions after the disable.
18. *Password in no run output (A25; 1.x).* Every file below the mode's run directory is checked: in JSON files
    every string (keys and values) is searched, numbers are not (a time stamp such as `"t":1111` is a number, and the
    password is a string); the JUnit XML is searched as text; the only other files are PNG captures, verified to be
    images. The check tests its own detector, requires that every kind of output was seen (`run.jsonl`,
    `bindings.json`, the failure evidence, the JUnit XML) and that the password really was sent: it is recorded
    masked in `run.jsonl` (the typed text) and in `bindings.json`. **(Phase 2, built 2026-10-02)** The password now
    comes from `credentials.yaml`: the typed text is recorded masked and its `call` record lists `secretParams:
    ["text"]` (A34); `bindings.json` names the credential (`{realm: staff, id: "100"}`) and holds no password field;
    and no line of the agent's own log (`agent.log`) holds the password.
19. *Stale files (A23; 1.x; ends the target).* The target is killed with SIGKILL, so its agent cannot delete its
    files. The client latches lost without a request reaching anything; the discovery file is still there;
    discovery with clean-up off lists nothing for that pid and deletes nothing; `Discovery.list(dir)` then deletes
    the dead process's discovery and token files.

Every exchange of these runs is authenticated as §5 describes (H2), on the agent's own listener (H1).

The matrix runs under `ci/run-in-linux.sh` (WM-less Xvfb) on **a Java 8 target and a Java 25 target** (decisions B3
and B4: these are the two gates; a Java 21 target is no longer run). The runs are recorded in `../TODO.md` and
`phase1-implementation-notes.md`. Measured on 2026-10-02 before the sample was replaced, with the first sample
application and the controller on Java 21: 52 of 52 checks green on a Java 8 target and on a Java 21 target, twice
each, with no flake. One false positive was found and fixed while 1.x was integrated: the run log's relative
timestamp happened to equal the password (`"t":1111`), which is why check 18 searches strings only.

(Phase 4, built 2026-10-07; P4 §12.2: this section is unchanged, except that the load-mode checks now find the module
`web` installed in every agent beside `swing`, `data` and `lending`; the acceptance of the embedded web views,
`WebHostEndToEndIT` against the web host sample under the Maven profile `jcef`, is P4 §12.2.)
