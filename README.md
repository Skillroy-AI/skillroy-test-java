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
protocol and is journaled, so that interactive work can be exported as an ordinary scenario. Both are designed in;
today nothing calls a model: the binding modes are built without one, and the interactive session service arrives in
a later phase. Vision-driven input, where a model looks at the screen and the framework turns what it decides into
recorded, replayable steps, is a planned bolt-on that the design keeps room for and does not build yet.

**Status (2026-10-06):** Phase 1, Phase 1.x and Phase 2 are built and verified; nothing is released and nothing is
published. Phase 1 is the thin vertical slice: the agent (core, Swing and data modules), the controller, the scenario
compiler and executor, JUnit glue, a CLI, and a bundled sample application, the lending desk of a tool library, with
its agent module, domain pack and scenarios. Its gates are a Java 8 target and a Java 25 target, in every load mode;
the numbers (tests, configurations, runtimes) are kept in [docs/TODO.md](docs/TODO.md). All twenty decisions are
answered ([docs/DECISIONS.md](docs/DECISIONS.md)); the initial commit was made on 2026-10-06, and the repository is
not yet pushed or published. **Phase 1.x**
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
applies and the printed slip shows the waiver. **Phase 4** (embedded web views: Playwright over CDP from the
controller and an in-process SPI with reflective JCEF and JxBrowser adapters) is specified on 2026-10-06 and not yet
built; its spike BS8 ran a real JCEF browser in the Linux test image the same day. Not yet built: the Phase 4
modules, model-authored resolution and the rest of the model layer, the interactive session service, vision-driven
input, bytecode hooks and the fluent Java API.

## Where to look

| | |
|---|---|
| Status, what was proven, spikes, phase checklist, open gaps, test numbers | [docs/TODO.md](docs/TODO.md) |
| Decisions and their answers | [docs/DECISIONS.md](docs/DECISIONS.md) |
| Phase 1 contract (wire protocol, agent SPI, primitives, CLI, sample application), as built, Phase 1.x included | [docs/design/phase1-contracts.md](docs/design/phase1-contracts.md) |
| Phase 2 contract (resolution, bindings, credentials, stored-row checks, sample scenario A), as built | [docs/design/phase2-contracts.md](docs/design/phase2-contracts.md) |
| Phase 3 contract (oracle cross-checks with `ORACLE_SUSPECT`, stored-row aggregates, the `devices` fact root, sample scenario B), as built | [docs/design/phase3-contracts.md](docs/design/phase3-contracts.md) |
| Phase 4 contract (embedded web views: `skillroy-cdp`, the web SPI, `skillroy-agent-web` with the `jcef` and `jxbrowser` adapters, the adapter contract kit), specified, not built | [docs/design/phase4-contracts.md](docs/design/phase4-contracts.md) |
| Spike BS8 (JCEF in the Linux test image), run 2026-10-06 | [spikes/BS8/RESULT.md](spikes/BS8/RESULT.md) |
| Design overview, all phases | [docs/design/base-framework-design.md](docs/design/base-framework-design.md) |
| Research notes | [docs/research/](docs/research/) |
| Licence, notices, dependency licence audit | [LICENSE](LICENSE), [NOTICE](NOTICE), [docs/licence-audit.md](docs/licence-audit.md) |
| How to contribute | [CONTRIBUTING.md](CONTRIBUTING.md) |

## Layout

```
agent/        Java 8, runs inside the target JVM, no third-party classes
controller/   Java 25: attach and client, data channels and credentials (controller/skillroy-data), scenario
              compiler, resolution and executor, JUnit glue, CLI
samples/      the sample lending desk: the issue desk of a tool library, a small Swing application with an engine
              thread, custom widgets, modal prompts and an embedded database; plus its agent module, domain pack
              and scenarios (skillroy-sample-lending-app, -agent, -pack, -scenarios)
ci/           Linux test image (Xvfb), runner script, licence-header check
docs/         design, research, decisions, todo, dependency licence audit
```

## Build

Needs Maven 3.9 and JDK 25 or newer (the build refuses an older one). Agent-side modules are compiled with
`--release 8`, controller-side modules with `--release 25`.

```bash
mvn -q verify
```

This runs every test that needs no display. Tests that need a display, including the end-to-end tests that launch
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
  pack=samples/skillroy-sample-lending-pack/target/skillroy-sample-lending-pack-0.1.0-SNAPSHOT.jar
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

Every scenario should print `PASS`, and each `run` ends with `model calls: 0`. `-Dlending.seed=A` matters: the Phase
1 scenarios require the target to have been launched with it, and scenario A's `seed-a` bindings fit only that seed
(on another seed `replay` stops with `REBIND_NEEDED` before any step). `diff-bindings` needs no agent and exits 1
when the files differ, as these two do. `skillroy --help` lists the commands and exit codes;
`skillroy <command> --help` the options. With `--out` each scenario leaves `run.jsonl`, `bindings.json`,
`cost-ledger.jsonl` and, on failure, a `failure/` directory with a UI snapshot and a PNG capture.

`skillroy call` is the way to call a primitive by hand: every request and reply is HMAC-signed with the token,
which never travels, so plain `curl` with a bearer token no longer works (the agent answers 401).

## Extending it to a real application

The base knows nothing about any particular application. An application gets:

- an **agent module** (`AgentModule`, Java 8) that adapts its threads, widgets, dialogs, data connection and
  input channel through the SPIs in `skillroy-agent-api`, and adds its own primitives;
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
runs inside a target JVM contains none. Contributions are accepted under the same licence, signed off under the
Developer Certificate of Origin (`git commit -s`); there is no contributor licence agreement. See
[CONTRIBUTING.md](CONTRIBUTING.md).
