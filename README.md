# Skillroy Test (Java)

AI-driven scenario testing of Java desktop applications, from inside the JVM.

A small **Java 8 agent** is loaded into the application under test (as a `-javaagent`, by dynamic attach, or
embedded through the application's own plugin mechanism) and exposes *primitives* over a loopback JSON-RPC
channel: read the Swing tree, click and type without stealing focus, answer dialogs, query the application's own
database connection, observe application events. A **Java 25 controller** compiles YAML scenarios into committed
plans, runs them deterministically with an event-based step protocol (no sleeps), and reports JUnit XML.

The point of the framework is scenarios that survive environment differences: *which* staff member's limit needs a
supervisor override, *which* items trigger a waiver rule. Those facts are resolved once at run start (by committed
queries, or with a language model's help), verified in-process, and frozen into a bindings file. CI replays the
bindings with **zero model calls**.

There are two ways of running. In CI the scenarios run in replay only: the bindings are frozen, no model is called,
and a cost ledger asserts it. In interactive use, a person or an authoring tool drives a live target and inference at
run time is allowed, at the start of a run and between steps; every interactive step goes through the same step
protocol and is journaled, so that interactive work can be exported as an ordinary scenario. Both are built: the
binding modes without a model (Phase 2) and, since Phase 5 (2026-10-08), a model behind a syntactic guard, a verifier
and a person's approval for the SQL that finds a binding, the interactive session service with its journal and export,
batch re-resolution, an MCP server and a planner; CI and `replay` still never call a model, and nothing in the build
needs a model key (every test of the AI layer runs against a recorded or scripted gateway). Vision-driven input, where
a model looks at the screen and the framework turns what it decides into recorded, replayable steps, is a bolt-on built
on 2026-10-10 (Phase 7; [docs/design/phase7-contracts.md](docs/design/phase7-contracts.md)): the optional controller module
`skillroy-vision` runs the look-and-act loop inside an interactive session, off by default, and what it did is exported as
ordinary steps that replay with zero model calls. Its answers (B29) and the decisions of its build, its code review's fixes and its security fixes (B30) were provisional while
the build ran on them and were confirmed by the owner on 2026-10-11; it was code-reviewed, security-reviewed (a delta review,
S101-S148) and fixed on 2026-10-10/11, and the owner signed that review off on 2026-10-11 (B31).

**Status (2026-10-11):** Phase 1, Phase 1.x, Phase 2 and Phase 3 are built and verified, Phase 4 is built, reviewed
and fixed, and **Phase 5** (the AI layer) is built, reviewed, fixed and closed live (2026-10-08; its answers B23 and the decisions of its build and review, B24, confirmed by the owner the same day; the live items ran the same afternoon with the owner's model key: the live profiles, live recordings for the sample's tier (b) scenario, a sample scenario authored by the planner and reviewed, and the nightly job's cache figures; the live session's three decisions, B25, confirmed by the owner on 2026-10-09). **Phase 6** (hooks, Byteman, Windows, OpenTelemetry export, hardening) is built, reviewed and fixed (2026-10-09, in six packages, then a code review of three lenses and its fix wave, and the repeat security review's two passes with their fix rounds; spikes BS3 and BS14 passed with conditions; the contract's answers are B26, the 127 decisions of the build, the review's fixes and the security fixes are B27; both were provisional while the build ran on them and were confirmed by the owner on 2026-10-10): the hook engine and the in-process Byteman module as optional pieces of the agent home, a JDK 25 target through `JAVAAGENT`, the Windows path built where Linux can test it and not verified on a Windows host, OpenTelemetry export from the controller (off by default), the tenant-pack overlay proven on a second variant of the sample desk, and the repeat security review (S35-S100: 66 findings, two high, 18 medium, 46 low; everything of medium or above is fixed; eight lows are accepted by the owner, each with a release note in [SECURITY.md](SECURITY.md)). The owner signed the review off on 2026-10-10 (B28); no outside reader has read its record (recommended, not a gate; waived by the owner for 0.1.0). **Phase 7** (the vision bolt-on and the vision loop of interactive sessions) is **built, reviewed, fixed and security-reviewed** (built 2026-10-10, the day its contract was specified and reviewed after the spikes BS9-BS12, in four worktree packages merged into `main`; a code review of three lenses with a sceptic each, its fix wave and a race fix, then a security delta review of eight lenses with its four fix packages, 2026-10-10/11; the contract's answers are B29 and the 128 decisions of the build, the fix wave and the security fixes are B30, both provisional while the build ran on them and confirmed by the owner on 2026-10-11; the delta review, S101-S148: 48 confirmed findings, 2 high, 13 medium, 33 low, everything of medium or above and 31 lows fixed, two lows accepted by the owner with a release note in [SECURITY.md](SECURITY.md), was signed off by the owner on 2026-10-11, B31): the extended `swing.capture` (redacted by default, painting a window from its root pane), `swing.hitTest` and `swing.pointer` (routed through the window, or through `java.awt.Robot` behind a double opt-in), their `web.*` counterparts for an embedded page, the optional module `skillroy-vision` with the tiers V1 to V3, pixel-anchored replay behind an exact image guard, `expectImage`, the verbs `aiAct` and `aiExpect`, plans that `replay` reports as skipped, and the sample's number pad; the Linux matrix of its final tree is green on `223c5fb` (arm64 twice on every leg, amd64 on the Java 25 leg), its amd64 Java 8 legs green on `832fcb2` later on 2026-10-11 and its live acceptance ran three times (the last on the final code, 7 of 7, USD 0.148). **Not verified:** everything on Windows (the access-control branches of every owner-only file, the `.bat` launcher, the planner started on a Windows host, `JAVAAGENT` as the logged-on user, the input rungs), where no host has run this code; the `jxbrowser` adapter beyond the one version and platform its kit ran on (JxBrowser 8.18.3 on macOS arm64 with a JDK 25 test JVM, 2026-10-11: 19 of 19 in three variants, its pointer input and a Retina display included), that is other JxBrowser lines, Linux, Windows and a Java 8 test JVM; the paths that need a live model key beyond the live runs recorded in `docs/TODO.md` (the planner's stdin route among them, checked live on macOS on 2026-10-10); a hook detach that ran out of its time budget on a real application; and, from Phase 7, Robot input on macOS with the Accessibility permission granted, on Windows, under Wayland and in a locked session, any display scale other than 1 (JxBrowser's pointer on a Retina display excepted), the vision loop against a live model beyond the short sessions of the spikes and the live class's three short runs, and the minimised-frame and off-screen refusals off the Linux image's display; and the rest of [`docs/design/threat-model.md`](docs/design/threat-model.md) section 8. Phase 1 is the thin vertical slice: the agent (core, Swing and
data modules), the controller, the scenario compiler and executor, JUnit glue, a CLI, and a bundled sample application,
the lending desk of a tool library, with its agent module, domain pack and scenarios. Its gates are a Java 8 target and
a Java 25 target, in every load mode; the numbers (tests, configurations, runtimes) are kept in
[docs/TODO.md](docs/TODO.md). B1 to B31 are answered, confirmed or signed off by the owner (B26 and B27 confirmed and B28, the security review's sign-off, given on 2026-10-10; B29, the Phase 7 contract's thirty-nine answers, and B30, the 128 decisions of its build, its code review's fixes and its security fixes, confirmed and B31, the sign-off of its security delta review, given on 2026-10-11; [docs/DECISIONS.md](docs/DECISIONS.md)); the
initial commit was made on 2026-10-06, and **0.1.0**, the first release, was cut on 2026-10-12 as the tag `v0.1.0` (its
record: [docs/release-notes/0.1.0.md](docs/release-notes/0.1.0.md); no jars or zips are published, build them from the tag). **Phase 1.x**
added a set of contract amendments and the hardening that followed from a security review of the control channel: an
agent-owned HTTP listener, keyed request and reply authentication instead of a bearer token on the wire, and the
fixes of the review's other findings (the one path that needs a Windows host is not verified on Windows). **Phase 2**
added resolution without a model: tier (a) resolvers (committed SQL and committed Java in the domain pack) whose
answers the application's own decision code verifies, and oracles with bind-time guards; bindings files per
environment, made in `approve` (or `live`) and replayed in `replay`; `credentials.yaml` as the only source of
passwords; stored-row checks over the agent's in-process channel or a controller-side read-only JDBC channel; and a
cost ledger that asserts zero model calls in `replay`. The sample's scenario A passes on two differently seeded desks
with one YAML file. Phase 3 (2026-10-03) added oracle cross-checks against the application's own dry run, reported as
`ORACLE_SUSPECT` when they disagree (a guarded cross-check that cannot run degrades the outcome instead), aggregates in
stored-row checks, a `devices` fact root for peripherals, and the sample's scenario B, in which a quantity waiver rule
applies and the printed slip shows the waiver. **Phase 4** (2026-10-07) added embedded web views: every web verb of
a scenario runs either over CDP, with Playwright attached to the remote-debugging port of the embedded Chromium, or in
process, through one SPI with reflective JCEF and JxBrowser adapters, and each step records which path served it. A
second sample target, a small JCEF web host behind the Maven profile `jcef`, runs one committed scenario over both
paths, and an adapter contract kit runs against real JCEF in the Linux test image. Phase 5 (2026-10-08) added the model layer and Phase 6
(2026-10-09) the bytecode hooks, Byteman, the Windows path and OpenTelemetry export, as described above. **Phase 7** (the vision bolt-on and
the vision loop of interactive sessions) was specified and reviewed on 2026-10-10 (after the spikes BS9-BS12 of the same day; its
answers are B29) and built the same day (its decisions are B30); both were provisional while the build ran on them and were confirmed by the owner
on 2026-10-11. It was code-reviewed and fixed, then security-reviewed (a delta review) and fixed, on 2026-10-10/11; the owner
signed that review off on 2026-10-11 (B31). Not yet
built: the fluent Java API.

## Where to look

| | |
|---|---|
| Status, what was proven, spikes, phase checklist, open gaps, test numbers | [docs/TODO.md](docs/TODO.md) |
| Decisions and their answers | [docs/DECISIONS.md](docs/DECISIONS.md) |
| Phase 1 contract (wire protocol, agent SPI, primitives, CLI, sample application), as built, Phase 1.x included | [docs/design/phase1-contracts.md](docs/design/phase1-contracts.md) |
| Phase 2 contract (resolution, bindings, credentials, stored-row checks, sample scenario A), as built | [docs/design/phase2-contracts.md](docs/design/phase2-contracts.md) |
| Phase 3 contract (oracle cross-checks with `ORACLE_SUSPECT`, stored-row aggregates, the `devices` fact root, sample scenario B), as built | [docs/design/phase3-contracts.md](docs/design/phase3-contracts.md) |
| Phase 4 contract (embedded web views: `skillroy-cdp`, the web SPI, `skillroy-agent-web` with the `jcef` and `jxbrowser` adapters, the adapter contract kit, the web host sample), as built | [docs/design/phase4-contracts.md](docs/design/phase4-contracts.md) |
| Phase 5 contract (the AI layer: the `InferenceGateway` SPI with its Claude, recorded and scripted implementations, model-written SQL behind `SqlGuard`, approval and budgets, batch re-resolution, `skillroy-mcp`, the planner, triage, the interactive profile), specified and built 2026-10-08 (its answers B23 and the build's and review's decisions B24 confirmed by the owner the same day; the live items closed the same afternoon, their decisions B25 confirmed by the owner on 2026-10-09), with an "As built" note under every section the build changed | [docs/design/phase5-contracts.md](docs/design/phase5-contracts.md) |
| Phase 6 contract (hooks: `skillroy-agent-spy` and `skillroy-agent-hooks` on shaded Byte Buddy, the hook catalogue and the `hook.*` primitives; Byteman in process; a JDK 25 target through `JAVAAGENT`; the Windows path; OpenTelemetry export; the tenant-pack overlay and the sample's branch edition; the repeat security review), built, reviewed and fixed 2026-10-09, the repeat security review run and fixed (B26 and B27 confirmed, B28 signed off, 2026-10-10); the threat model | [docs/design/phase6-contracts.md](docs/design/phase6-contracts.md), [docs/design/threat-model.md](docs/design/threat-model.md) |
| Phase 7 contract (the vision bolt-on and the vision loop of interactive sessions: `swing.hitTest`, `swing.pointer` routed and through Robot behind a double opt-in, the extended `swing.capture`, their `web.*` counterparts, the optional `skillroy-vision` with the tiers V1-V3, the recorder, pixel-anchored replay, `expectImage`, plans skipped in `replay`), specified and reviewed 2026-10-10 after the spikes BS9-BS12 and built the same day in four packages, with an "As built" note under every section the build changed, then code-reviewed, security-reviewed (a delta review) and fixed on 2026-10-10/11, with a "Fix wave" or "Phase 7 security fix" note under every passage a fix changed (its answers B29 and the decisions of the build and the fixes B30 confirmed by the owner, and the delta review signed off by the owner, B31, on 2026-10-11) | [docs/design/phase7-contracts.md](docs/design/phase7-contracts.md), [docs/design/threat-model.md](docs/design/threat-model.md) |
| Spike BS8 (JCEF in the Linux test image), run 2026-10-06; spike BS5 (Playwright over CDP), met by the Phase 4 acceptance, with the manual procedure against a stock Chromium | [spikes/BS8/RESULT.md](spikes/BS8/RESULT.md), [spikes/BS5/README.md](spikes/BS5/README.md) |
| Spike BS3 (Byte Buddy retransformation, inline Advice, a bootstrap `Spy`, restore on detach), run 2026-10-09, passed with conditions | [spikes/BS3/RESULT.md](spikes/BS3/RESULT.md) |
| Spike BS14 (Byteman loaded in process through the agent's `Instrumentation`, no listener), run 2026-10-09, passed with conditions | [spikes/BS14/RESULT.md](spikes/BS14/RESULT.md) |
| Design overview, all phases | [docs/design/base-framework-design.md](docs/design/base-framework-design.md) |
| Integration and overlay guide: building a private product layer (and tenant overlays) for one application on this base | [docs/guides/integration-guide.md](docs/guides/integration-guide.md) |
| Research notes | [docs/research/](docs/research/) |
| Licence, notices, dependency licence audit | [LICENSE](LICENSE), [NOTICE](NOTICE), [docs/licence-audit.md](docs/licence-audit.md) |
| Security policy (reporting, known limitations) and the release checklist | [SECURITY.md](SECURITY.md), [docs/release-checklist.md](docs/release-checklist.md) |
| How to contribute | [CONTRIBUTING.md](CONTRIBUTING.md) |

## Layout

```
agent/        Java 8, runs inside the target JVM, no third-party classes outside the optional hooks module;
              agent/skillroy-agent-web holds the web.* primitives and the reflective jcef and jxbrowser adapters, and
              its test-jar the adapter contract kit;
              agent/skillroy-agent-hooks is the optional hook engine (home: optional/skillroy-agent-hooks.jar, loaded
              only with the agent option optionalModules=skillroy-agent-hooks), the one module that carries third-
              party code: Byte Buddy and its ASM, relocated by agent/skillroy-agent-bytebuddy; agent/skillroy-agent-spy
              is its one class in front of the host, appended to the bootstrap search path in hooks mode only
controller/   Java 25: attach and client, data channels and credentials (controller/skillroy-data), scenario
              compiler, resolution and executor, JUnit glue, CLI; controller/skillroy-cdp is the optional CDP path
              (Playwright), which no other module depends on; controller/skillroy-telemetry the OpenTelemetry export
              (Phase 6), which the CLI bundles and nothing else depends on; controller/skillroy-vision is the optional
              vision loop (Phase 7), found by the session service through ServiceLoader and bundled in the CLI
samples/      the sample lending desk: the issue desk of a tool library, a small Swing application with an engine
              thread, custom widgets, modal prompts and an embedded database; plus its agent module, domain pack
              and scenarios (skillroy-sample-lending-app, -agent, -pack, -scenarios); and, behind the Maven profile
              jcef, the web host: a small Swing application with embedded JCEF browsers, its agent module, pack and
              scenarios (skillroy-sample-web-host, -web-host-agent, -web-host-scenarios)
spikes/       BS8 (JCEF in the Linux test image) and BS5 (the manual procedure against a stock Chromium)
ci/           Linux test image (Xvfb), runner script, licence-header check
docs/         design, research, decisions, todo, dependency licence audit
```

## Build

Needs Maven 3.9 and JDK 25 or newer (the build refuses an older one). Agent-side modules are compiled with
`--release 8`, controller-side modules with `--release 25`.

```bash
mvn -q verify
```

This runs every test that needs no display. The hooks module (`agent/skillroy-agent-hooks`) compiles against the
packaged, relocated Byte Buddy of `agent/skillroy-agent-bytebuddy`, so it builds only in a reactor run that reaches
`package`: `mvn -q verify` and `mvn -o verify -pl <module> -am` work, a root `mvn compile` or `mvn test` fails in that
module by design (`package …shaded.bytebuddy does not exist`). Tests that need a display, including the end-to-end tests that launch
the sample lending desk, are skipped (`-Dskillroy.headless=true` is the default). Do not switch that off on a
desktop: the tests open real windows. Run them in the Linux image (JDK 25), under an Xvfb without a window
manager:

```bash
docker build --platform linux/amd64 -t skillroy-test-java-ci:local ci
ci/run-in-linux.sh mvn -o -q verify -Dskillroy.headless=false
```

The script mounts the repository, the local Maven repository and the host's Maven installation; `-o` assumes the
native build has already filled the local repository. To run the launched targets on Java 8, mount a Linux x86-64
JDK 8 and name its `java`:

```bash
SKILLROY_JDK8_HOME=/path/to/linux-x64-jdk8 ci/run-in-linux.sh mvn -o -q verify \
  -Dskillroy.headless=false -Dskillroy.it.targetJava=/opt/jdk8/bin/java
```

Adding `-Djvm=/opt/jdk8/bin/java` also runs the test JVMs themselves on Java 8. Use it for the agent-side modules
only (`-pl agent/skillroy-protocol,agent/skillroy-agent-api,…`); the controller-side tests need Java 25.

The web host and its two companions are built only with the Maven profile `jcef`. Their tests need the JCEF natives
of one platform (about 152 MB for Linux x86-64) in the local Maven repository: `-Dskillroy.jcef.platform` picks them
(default `linux-amd64`; `linux-arm64` for an arm64 build of the image), and nothing is downloaded at test time. The
web acceptance (the contract kit against real JCEF, the end-to-end checks over both paths and the frozen-target soak)
needs a display, so it runs in the Linux image:

```bash
mvn -q verify -Pjcef        # natively, once: fills the local repository (natives, Playwright) and builds everything
ci/run-in-linux.sh mvn -o -q verify -Pjcef -Dskillroy.jcef.platform=linux-amd64 -Dskillroy.headless=false
```

With `-Dskillroy.it.targetJava=/opt/jdk8/bin/java` the web host is launched on Java 8, where only off-screen rendering
is run. Never `mvn install` this project: build one module with `mvn -o verify -pl <module> -am`, so that the agent
distribution its tests unpack comes from the same build ([docs/TODO.md](docs/TODO.md), "Environment notes").

## Try the CLI against the sample lending desk

After a build, start the sample lending desk in the Linux image, attach the agent, run the two Phase 1 scenarios
(`basic-loan` and `basic-loan-ui`), replay scenario A (`return-over-limit-needs-supervisor`) against its committed
`seed-a` bindings, resolve it again without writing anything, and compare the committed bindings of the two seeds.

The scenarios take their passwords from a credentials file, which is never committed. The sample's own tests generate
theirs; to run the commands below, write one for seed A, readable by you only, as
`samples/skillroy-sample-lending-scenarios/bindings/seed-a/credentials.yaml` (a git-ignored name, which
`--env seed-a` finds there; `--credentials <file>` names another). `credentials.example.yaml` in that module
documents the format; the made-up passwords of staff `100` and `200` are in the application's seed data (`SeedData`).

```bash
ci/run-in-linux.sh bash -c '
  S=samples/skillroy-sample-lending-scenarios
  cli=controller/skillroy-cli/target/skillroy-cli/bin/skillroy
  pack=samples/skillroy-sample-lending-pack/target/skillroy-sample-lending-pack-0.2.0-SNAPSHOT.jar
  scenarioA=$S/scenarios/return-over-limit-needs-supervisor.yaml
  java -Dlending.seed=A -cp "$S/target/it-target/skillroy-sample-lending-app.jar:$S/target/it-target/hsqldb.jar" \
       ai.skillroy.testjava.samples.lending.LendingApp > /tmp/lending.log 2>&1 &
  $cli attach $! --home $S/target/skillroy-agent      # waits until the JVM is 5 s old, then loads the agent
  $cli caps                                           # the capability matrix
  $cli snapshot                                       # the UI tree and the application state
  $cli call lending.state
  $cli run $S/scenarios/basic-loan.yaml $S/scenarios/basic-loan-ui.yaml --env seed-a --pack $pack \
       --out /tmp/run --junit /tmp/run/junit.xml
  $cli run $scenarioA --mode replay --env seed-a --pack $pack --out /tmp/run-a   # committed bindings, no resolver
  $cli resolve $scenarioA --env seed-a --dry-run --pack $pack   # resolves live, prints the file it would write
  $cli diff-bindings --bindings $S/bindings --env seed-a --env seed-b return-over-limit-needs-supervisor
'
```

Every scenario should print `PASS`, and each `run` prints `model calls: 0` (and, since Phase 5, a `model cost` line after it). `-Dlending.seed=A` matters: the Phase
1 scenarios require the target to have been launched with it, and scenario A's `seed-a` bindings fit only that seed
(on another seed `replay` stops with `REBIND_NEEDED` before any step). `diff-bindings` needs no agent and exits 1
when the files differ, as these two do. `skillroy --help` lists the commands and exit codes;
`skillroy <command> --help` the options. With `--out` each scenario leaves `run.jsonl`, `bindings.json`,
`cost-ledger.jsonl` and, on failure, a `failure/` directory with a UI snapshot and a PNG capture.

Run outputs take the process umask (Phase 6 security fix S37, SR): `--out` of `run`, `re-resolve`, `mcp` and `session`, and the
JUnit glue's output directories, are created with whatever permissions the process gives new files (a `session` without
`--out` writes into a new owner-only directory under the system's temporary directory and prints its path, Phase 7 security fix
S148), so another user
of a shared host may read what a run leaves behind (values the mask did not know, captures of the screen). On a
shared host run the controller under `umask 077` (for example `(umask 077; skillroy run … --out /tmp/run)`). The CDP
trace directory is owner-only regardless. Owner-only output directories by default are not built (an accepted low
finding of the repeat security review). A run refuses an output directory another user owns or others may write to
without the sticky bit, and creates every output file exclusively, never writing through a file or symbolic link
already in its place (Phase 6 security fix, pass 2, P2-L8-F3, P2-L8-S1, SF): give `--out` a directory you own, not a
path directly under `/tmp`.

`skillroy call` is the way to call a primitive by hand: every request and reply is HMAC-signed with the token,
which never travels, so plain `curl` with a bearer token no longer works (the agent answers 401).

## Embedded web views

A scenario drives the web views a Swing application embeds with the base verbs `webClick`, `webFill`, `webDispatch`,
`webWaitFlag`, `webResize` and `webTap` (a click and a fill by CSS selector or by role and name, a page event, a wait
for a condition in the page, a viewport size, and the page events to listen for), and the scenario key `web` chooses
the path (`auto`, `cdp` or `agent`), a default browser and what to do with a frozen page. With `auto`, a run takes the
CDP path when the target's browser engine was started with a remote-debugging port and `skillroy-cdp` is on the class
path, and the in-process path otherwise; every web call in `run.jsonl` names the path that served it.

`skillroy-cdp` is optional (decision D49): the executor, the JUnit glue and the CLI do not depend on it, and the CLI
distribution ships without Playwright. A Maven suite declares it as a dependency. The CLI takes it as `--pack` jars,
one option per jar, together with Playwright's jars from the local Maven repository: `skillroy-cdp-0.2.0-SNAPSHOT.jar`,
`playwright-1.63.0.jar`, `driver-1.63.0.jar`, `driver-bundle-1.63.0.jar`, `gson-2.14.0.jar`, `jspecify-1.0.1.jar`
and `opentest4j-1.3.0.jar`. Without them the same command runs the web verbs through the agent. Playwright extracts its
driver (about 140 MB) on first use in each JVM, and never downloads a browser: it attaches to the one the application
runs.

The Playwright line (Phase 8): built and tested with Playwright 1.63.0. The CDP path runs with 1.61 and later in full,
with 1.60 without tracing, and with 1.58 and 1.59 only under the operator's opt-in `-Dskillroy.cdp.allowDefaultOverrides=true`
(Playwright's default-context overrides then apply to the application's browser context); a line below 1.58 is refused
by the same rule and is otherwise untried. Only 1.63.0 was run; the attach records the line and what it could not apply
in the `web.path` record.

The Playwright driver inherits the controller's environment (Phase 6 security fix S48, SR). These change what it runs
or what it logs, so set them only on purpose: `PLAYWRIGHT_NODEJS_PATH` and the system property `playwright.nodejs.path`
(the Node.js executable it runs), `PLAYWRIGHT_DRIVER_DIR` (an extracted driver to use instead of the bundled one),
`PLAYWRIGHT_DRIVER_TMPDIR` and the system property `playwright.driver.tmpdir` (where it extracts the driver),
`NODE_OPTIONS` (options of that Node.js process, `--require` included), and `DEBUG=pw:*`, which logs the protocol
traffic: the expressions the path evaluates and the texts it types that are not secret (a call naming secret
parameters is served by the agent, never through the driver). A minimal driver environment is not built (an accepted
low finding of the repeat security review).

## Vision and pointer input

(Phase 7, built 2026-10-10; [phase7-contracts.md](docs/design/phase7-contracts.md); the contract's answers B29 and the
build's decisions B30 confirmed by the owner on 2026-10-11.) The vision loop and the Robot tier are off by default, and CI's `replay` makes no model call, asserted.

- **The primitives.** `swing.capture` redacts by default (a password field and every component a pack's `CaptureMask` names
  are blanked, and their text is withheld from every reader of the tree) and paints a window from its root pane;
  `swing.hitTest` maps a point to a component and the locators that name it; `swing.pointer` acts at coordinates, routed
  through the window by AWT's own dispatcher, so that the component under the point receives the event; `web.hitTest` and
  `web.pointer` do the same in an embedded page.
- **The loop.** `skillroy session --vision v1` (marks on a capture, answered by mark; `v2` adds coordinates through the
  computer toolset or provider-neutral custom tools) lets a model look at the target's own windows and act in an interactive
  session; the action is recorded as the step that will be replayed, and `export` writes ordinary steps with their
  provenance. It needs a model gateway and the inference settings' `images: true`, the one switch that lets pixels leave for
  the model provider; the start-up line says why vision is off when it is. A session is bounded to USD 1.00, 30 UI actions
  and 20 model turns. Where a pixel cannot be mapped to a component a pack's `RegionResolver` comes first, then an
  image-anchored `pointer` step behind an exact image guard, then a plan compiled with `inference: run` that `replay`
  reports as skipped (JUnit `<skipped>`, exit 0).
- **The verbs.** `aiAct` and `aiExpect` (live only; `skillroy session --play <scenario.yaml>` runs a scenario that has them),
  `pointer` and `expectImage` (an exact comparison with a masked baseline kept beside the bindings file, approved at its step
  by a person; a rendering the project has no baseline for is `REBIND_NEEDED`).
- **The Robot tier.** `--vision v3` acts through `java.awt.Robot` and needs both halves of an opt-in, the agent option
  `robot=true` and `--allow-robot` on the command; a model or an MCP client can never choose it per call, and the model still
  sees a redacted `PAINT` capture, never the glass. Do not commit either half (the JUnit glue warns whenever
  `skillroy.robot` is true). It is verified on the Linux image's display only.
- **What masking cannot do.** A mask blanks a component's rectangle, so the layout and a value's width stay visible, and
  pixels carry no value mask: name every component that can show a secret (a mask's `regions` and a masked label's name are
  withheld with it, and a masked browser is masked on every route). The limitations are listed in [SECURITY.md](SECURITY.md).
- **What a model or a client cannot do** (the delta review's fixes, 2026-10-11). It cannot use a credential in any spelling,
  rebind a slot the person bound, paste, copy or cut through the person's clipboard or the X11 selection, press the middle
  button, or click a masked component or a page under a modal dialog; Robot refuses a point in a minimised frame or off every
  screen for any caller; an `approve` run on a client's behalf never writes the bindings root. Findings S101-S148 of the review are fixed
  except two lows accepted by the owner (a Windows junction and the Windows ACL inheritance of the pixel
  files); the owner signed the review off on 2026-10-11 (B31).

## Windows (not verified on a Windows host)

The Windows path is specified in full and built where a Linux image can test it ([phase6-contracts.md](docs/design/phase6-contracts.md)
§9); no Windows host has run any of it yet, so everything below is **not verified on Windows**.

- The CLI distribution carries `bin\skillroy.bat`, the twin of `bin/skillroy` (the same Java 25 check, the same
  messages and exit code 69, `--add-modules jdk.attach` for `jvms` and `attach` only, `SKILLROY_JAVA_OPTS`). Call the
  file in the installation's `bin` directory: `cmd.exe` cannot follow a link to it.
- `skillroy author` finds the planner as `claude` plus any `PATHEXT` extension but never starts a batch file: npm's
  `claude.cmd` is passed over for a native `claude.exe` (Claude Code's native installer) later on the `PATH`, and
  refused with that remedy when it is the only one (`--claude-command` names another executable). The prompt goes to
  the planner on stdin, never on its command line (security fix P2-L7-F3, pass 2). The command
  writes the planner's MCP server as `cmd.exe /c <path>\bin\skillroy.bat mcp …`. Until a Windows host has shown which
  quoted form the planner's client passes through intact, it refuses an installation or pack path that holds a space,
  `"`, `%`, `^`, `&`, `|`, `<` or `>`: move the installation or the pack to a plain path.
- Every owner-only file and directory (the discovery directory, token and options files, the planner's work directory
  and configuration, the CDP trace directory, the MCP server's temporary output) is created with an ACL of one entry
  for the current user where the file system has no POSIX permissions, read back, and refused on a volume with
  neither, decided per volume: a FAT or exFAT volume is refused on every platform (security fix P2-L7-F1, pass 2),
  and so is a credentials file kept there.
- On a Windows whose built-in account names are not English (German, French, ...) the owner-only checks cannot recognise
  the system account and the administrators group by name and fail closed: the framework refuses to run rather than
  expose anything. The remedy (a directory with its inheritance removed, `java.io.tmpdir`, `discoveryDir` and the
  credentials file pointed at it) is in [SECURITY.md](SECURITY.md) (security pass 2, P2-L7-F4; accepted by the
  owner, B28, 2026-10-10).
- **Attach:** the controller must run as the same user, in the same logon session and at the same integrity level as
  the target, with matching bitness; attaching across logon sessions (a service in session 0, another user's session,
  an elevated target from a non-elevated controller) is not supported. `JAVAAGENT` is the path for services and for
  CI; `ATTACH` is best effort.
- `ci\run-on-windows.cmd` runs `mvn verify` with the Java 8 and the Java 25 targets (`JAVA_HOME` a JDK 25,
  `SKILLROY_JDK8_HOME` a JDK 8). Run it from the repository root in an interactive logon session: the tests open real
  windows.

## Exporting spans (OpenTelemetry)

(Phase 6, built 2026-10-09, TL; [phase6-contracts.md](docs/design/phase6-contracts.md) section 10.) A run, a session and
the MCP server can export what they do as OpenTelemetry spans, from the controller only: nothing of OpenTelemetry
enters a target JVM. It is **off by default**: without `--telemetry` (or the property or the file naming an exporter)
no OpenTelemetry class loads and no thread starts. `run`, `session`, `mcp`, `re-resolve`, `triage` and `author` take
`--telemetry none|otlp|file`, `--telemetry-endpoint <url>`, `--telemetry-attributes standard|minimal` and
`--telemetry-file <path>`; the JUnit glue reads `skillroy.telemetry`, `skillroy.telemetry.endpoint`,
`skillroy.telemetry.attributes`, `skillroy.telemetry.out`, `skillroy.telemetry.serviceName`,
`skillroy.telemetry.timeoutMs` and `skillroy.telemetry.file`, with `skillroy-telemetry` on the test class path, and
flushes at JVM exit. One trace per invocation (or JUnit stream): `skillroy <command>`, then `scenario <id>`, `bind`,
`step <id>`, `call <primitive>` with a reconstructed `agent <primitive>` and its `await`s, and `chat <model>` for each
model call; a session's `session <id>`, `turn <n>` and steps; an MCP `tools/call <tool>` continues the client's trace.
Attributes are ids, enumerations, counts, durations, hashes and masked one-line messages, never a call's parameters,
values, payloads, rows, SQL or prompts; `minimal` also drops the messages and the pack, suite, slot and file names,
reduces primitives, tools, verbs, paths and topics to a class, and hashes every other identifier (the scenario, step,
correlation, pack version, hook, resolver, verifier, oracle, table and source ids among them): an attribute it does not
list as kept is hashed.

`--telemetry file` writes `<out>/telemetry/spans.jsonl` beside the run's outputs, one span per line in the project's
own format `skillroy-spans/1` (a CI artifact without a collector). `--telemetry otlp` posts OTLP/HTTP (protobuf) to
`<endpoint>/v1/traces` through the JDK's HTTP client, default `http://127.0.0.1:4318`: `http://` is accepted only for
a literal loopback address or `localhost`, any other host needs `https://` (the JDK's default trust store; there is
no insecure setting). Nothing is read from `OTEL_*` variables. A backend's token goes into `telemetry.yaml` (beside the
credentials file, or `--telemetry-file`) as the name of an environment variable, never as a value, and never one of
the model key's (`ANTHROPIC_*`); a header is sent to an `https://` endpoint only, so a loopback collector without TLS
takes none, and the file is read only when no other user could have written it (your own, not writable by others or
a shared group; Phase 6 security fix, pass 2, P2-L8-F1, P2-L8-F2, SF):

```yaml
version: 1
exporter: otlp
endpoint: https://collector.example.org
headers: {Authorization: env:OTLP_AUTHORIZATION}
attributes: standard
timeoutMs: 10000
```

With telemetry on, `run-start` in `run.jsonl`, `--json` (`telemetry: {exporter, traceId}`) and the JUnit report name
the trace, and the command ends with `telemetry: otlp <host:port>, trace <id>, <n> batches dropped`. An endpoint that
cannot be reached is reported once on stderr and never fails or slows the run beyond the timeout. To try it against a
real OpenTelemetry Collector (an opt-in, not part of the acceptance): start one on the operator's machine with an
OTLP/HTTP receiver on `127.0.0.1:4318` and a debug exporter, then add `--telemetry otlp` to a `run` of the commands
above and look for the trace id the summary prints.

## Extending it to a real application

The base knows nothing about any particular application. An application gets:

- an **agent module** (`AgentModule`, Java 8) that adapts its threads, widgets, dialogs, data connection and
  input channel through the SPIs in `skillroy-agent-api`, and adds its own primitives; for embedded browsers that the
  Swing tree cannot show, a `BrowserRegistryAdapter` hands their seeds to the agent (the web host's agent module is the
  example);
- a **domain pack** (`DomainPack`, Java 25) with scenario verbs and facts, and, since Phase 2, its query kinds,
  resolvers, verifiers and oracles (`DomainPack.resolution()`), an environment fingerprinter (`fingerprinter()`), the
  tables stored-row checks may read (`storedTables()`) and the masks for rows read over JDBC (`rowMasks()`).

The sample lending desk is the worked example of both. Layers for proprietary applications live in their own
repositories and depend on this one; nothing application-specific belongs here.

## Licence and contributing

Licensed under the Apache License, Version 2.0: see [LICENSE](LICENSE) and [NOTICE](NOTICE). Every source file
carries the licence header; `ci/license-headers.sh` checks it (`--apply` adds a missing one). Every jar carries
both files in `META-INF`, and so do the agent home and the CLI distribution at their top level; the CLI
distribution's `THIRD-PARTY.txt` lists the third-party jars it redistributes with their licences. The third-party
dependencies and their licences are inventoried in [docs/licence-audit.md](docs/licence-audit.md); the agent that
runs inside a target JVM contains none, except that its optional hooks module (`optional/skillroy-agent-hooks.jar`,
loaded only when the agent option `optionalModules` names it) carries a relocated copy of Byte Buddy and the ASM it
bundles, whose notices travel inside that jar (decision B4; wording confirmed by the owner in B26 Q1). Contributions
are accepted under the same licence, signed off under the Developer Certificate of Origin (`git commit -s`); there
is no contributor licence agreement. See [CONTRIBUTING.md](CONTRIBUTING.md).
