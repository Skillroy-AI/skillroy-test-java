# Skillroy Test (Java)

AI-driven scenario testing of Java desktop applications, from inside the JVM.

A small **Java 8 agent** is loaded into the application under test (as a `-javaagent`, by dynamic attach, or
embedded through the application's own plugin mechanism) and exposes *primitives* over an HMAC-authenticated
loopback JSON-RPC + SSE channel: read the Swing tree, click and type without stealing focus, answer dialogs, drive
embedded web views, query the application's own database connection, and observe application events. A **Java 25
controller** compiles YAML scenarios into deterministic plans, runs them with an event-based step protocol (no
sleeps), and reports JUnit XML.

The goal of the framework is scenarios that survive environment differences—*which* staff member's limit requires a
supervisor override, *which* items trigger a waiver rule. Those facts are resolved once at run start (by committed
queries, or with a language model's help behind a syntactic SQL guard and an allow-list), verified in-process
against the application's own code, and frozen into a bindings file. CI replays those bindings with **zero model
calls**, enforced by a cost ledger.

There are two ways of running:

- **CI replay (`--mode replay`):** bindings are frozen, zero model calls are permitted, and plans that require
  run-time inference are cleanly skipped. Nothing in the build or CI suite requires a model API key.
- **Interactive & authoring (`approve`, `live`, `session`, `author`, `mcp`):** a person, CLI session, planner or
  MCP client explores a live target, resolves bindings, or runs an optional vision look-and-act loop
  (`skillroy-vision`). Every interactive action goes through the same step protocol and is journaled, so
  interactive exploration exports directly into an ordinary deterministic scenario.

## Documentation

| Topic | File |
|---|---|
| Integration and overlay guide (building a domain pack, agent module, or tenant overlay—or starting with `pack: base`) | [docs/guides/integration-guide.md](docs/guides/integration-guide.md) |
| Base framework architecture and design | [docs/design/base-framework-design.md](docs/design/base-framework-design.md) |
| Threat model | [docs/design/threat-model.md](docs/design/threat-model.md) |
| Security policy and operational limitations | [SECURITY.md](SECURITY.md) |
| Dependency licence audit | [docs/licence-audit.md](docs/licence-audit.md), [LICENSE](LICENSE), [NOTICE](NOTICE) |
| Release checklist | [docs/release-checklist.md](docs/release-checklist.md) |
| Contributing and DCO sign-off | [CONTRIBUTING.md](CONTRIBUTING.md) |

## Layout

```
agent/        Java 8 (--release 8), runs inside the target JVM in an isolated class loader.
              No third-party classes enter the target JVM except inside the optional hooks module
              (optional/skillroy-agent-hooks.jar, loaded only when optionalModules names it), which
              relocates Byte Buddy and ASM under the project's prefix; skillroy-agent-spy is its single
              bootstrap-visible bridge class. skillroy-agent-web provides in-process web.* primitives
              with reflective JCEF and JxBrowser adapters.
controller/   Java 25 (--release 25): attach and RPC client (skillroy-controller), data channels and
              credentials (skillroy-data), scenario compiler, resolution and executor (skillroy-scenario),
              inference, planner, session service and SQL guard (skillroy-inference), MCP server
              (skillroy-mcp), JUnit 5 glue (skillroy-junit), CLI (skillroy-cli), plus optional CDP/Playwright
              (skillroy-cdp), OpenTelemetry export (skillroy-telemetry) and vision loop (skillroy-vision).
samples/      Sample lending desk: a Swing tool-library issue desk with an engine thread, custom widgets,
              modal prompts and HSQLDB, plus its agent module, domain pack, branch overlay and scenarios;
              and, behind the Maven profile jcef, a Swing + JCEF web host sample and scenarios.
ci/           Linux display test image (JDK 25 + Xvfb), runner scripts, licence-header check, example workflows.
docs/         Architecture design, threat model, integration guide, dependency licence audit, release checklist.
```

## Build

Requires Maven 3.9 and JDK 25 or newer. Agent-side modules compile with `--release 8` and controller-side modules
with `--release 25`.

```bash
mvn -q verify
```

This runs every test that needs no display. Note that `agent/skillroy-agent-hooks` compiles against the shaded jar
produced by `agent/skillroy-agent-bytebuddy` during `package`, so run `mvn -q verify` (or
`mvn -o verify -pl <module> -am`) rather than a bare root `mvn compile` or `mvn test`.

Display tests—including end-to-end tests that launch real Swing windows—are skipped by default
(`-Dskillroy.headless=true`). Run them inside the Linux Xvfb container image rather than on your desktop:

```bash
docker build --platform linux/amd64 -t skillroy-test-java-ci:local ci
ci/run-in-linux.sh mvn -o -q verify -Dskillroy.headless=false
```

To launch target applications on Java 8 inside the container, mount a Linux x86-64 JDK 8:

```bash
SKILLROY_JDK8_HOME=/path/to/linux-x64-jdk8 ci/run-in-linux.sh mvn -o -q verify \
  -Dskillroy.headless=false -Dskillroy.it.targetJava=/opt/jdk8/bin/java
```

Adding `-Djvm=/opt/jdk8/bin/java` also runs the Surefire/Failsafe test JVMs on Java 8 for agent-side modules
(`-pl agent/skillroy-protocol,agent/skillroy-agent-api,…`).

The embedded-browser sample modules build under the `jcef` Maven profile (`-Dskillroy.jcef.platform` defaults to
`linux-amd64`, or `linux-arm64` on arm64 containers):

```bash
mvn -q verify -Pjcef
ci/run-in-linux.sh mvn -o -q verify -Pjcef -Dskillroy.jcef.platform=linux-amd64 -Dskillroy.headless=false
```

Avoid `mvn install`; build individual modules with `mvn -o verify -pl <module> -am` so integration tests always
unpack the agent distribution from the current reactor build.

## Try the CLI

### Zero-code onboarding (`pack: base`)

You can attach to any Swing (or Swing + embedded web) application and author scenarios immediately without writing
a Java `DomainPack` or `AgentModule` by setting `pack: base` in your scenario YAML. The built-in `base` pack
exposes all base UI, web, device, wait, fact-capture and vision verbs (`click`, `type`, `press`, `dismiss`,
`waitFor`, `webClick`, `webFill`, `webDispatch`, `webWaitFlag`, `webResize`, `webTap`, `captureFacts`, `pointer`,
`expectImage`, `aiAct`, `aiExpect`, `call`) and base fact roots (`app`, `ui`, `dialogs`, `caps`, `devices`, `web`,
`hooks`, `events`) with `SyncPolicy.SETTLE_ONLY`.

### Sample lending desk walkthrough

To exercise custom domain packs, resolvers, verifiers and bindings against the sample lending desk, create an
owner-only credentials file at `samples/skillroy-sample-lending-scenarios/bindings/seed-a/credentials.yaml`
(modeled on `credentials.example.yaml` in that module, using the test passwords from `SeedData`), then run:

```bash
ci/run-in-linux.sh bash -c '
  S=samples/skillroy-sample-lending-scenarios
  cli=controller/skillroy-cli/target/skillroy-cli/bin/skillroy
  pack=samples/skillroy-sample-lending-pack/target/skillroy-sample-lending-pack-0.2.0-SNAPSHOT.jar
  scenarioA=$S/scenarios/return-over-limit-needs-supervisor.yaml
  java -Dlending.seed=A -cp "$S/target/it-target/skillroy-sample-lending-app.jar:$S/target/it-target/hsqldb.jar" \
       ai.skillroy.testjava.samples.lending.LendingApp > /tmp/lending.log 2>&1 &
  $cli attach $! --home $S/target/skillroy-agent
  $cli caps
  $cli snapshot
  $cli call lending.state
  $cli run $S/scenarios/basic-loan.yaml $S/scenarios/basic-loan-ui.yaml --env seed-a --pack $pack \
       --out /tmp/run --junit /tmp/run/junit.xml
  $cli run $scenarioA --mode replay --env seed-a --pack $pack --out /tmp/run-a
  $cli resolve $scenarioA --env seed-a --dry-run --pack $pack
  $cli diff-bindings --bindings $S/bindings --env seed-a --env seed-b return-over-limit-needs-supervisor
'
```

Each `run` prints `PASS` and `model calls: 0`. With `--out`, each scenario writes `run.jsonl`, `bindings.json`,
`cost-ledger.jsonl` and, on failure, a `failure/` bundle with a UI snapshot and redacted PNG capture. On shared
hosts, run under `umask 077` and point `--out` to an owner-only directory rather than directly under `/tmp`.
Use `skillroy call` to invoke individual primitives interactively (requests and replies are mutually signed with
`Skillroy-HMAC`).

## Embedded web views

Scenarios drive Swing-embedded web views with `webClick`, `webFill`, `webDispatch`, `webWaitFlag`, `webResize` and
`webTap`. The `web` scenario section selects the transport path (`auto`, `cdp` or `agent`). Under `auto`, the
controller uses Playwright over CDP when a remote-debugging port is available and `skillroy-cdp` is on the class
path, falling back automatically to the in-process `WebEngineAdapter` SPI (`jcef` and `jxbrowser` reflective
adapters).

`skillroy-cdp` is optional and is not bundled in the CLI zip. Maven test suites declare it as a dependency; CLI
users pass `skillroy-cdp` and Playwright's jars via `--pack`. Built and tested against Playwright 1.63.0 (1.61+
supported in full; 1.60 without tracing; 1.58–1.59 require `-Dskillroy.cdp.allowDefaultOverrides=true`).

## Vision and pointer input

The vision loop (`skillroy-vision`) and `java.awt.Robot` tier are off by default, and CI `replay` never invokes a
model:

- **Primitives:** `swing.capture` paints windows from their root pane and redacts password fields and pack
  `CaptureMask` components by default; `swing.hitTest` and `web.hitTest` map coordinates to components/elements
  and candidate locators; `swing.pointer` and `web.pointer` dispatch coordinate pointer events.
- **Interactive loop:** `skillroy session --vision v1` (annotated marks) or `v2` (pixel grounding) lets a model
  inspect redacted window captures when `images: true` is enabled in `inference.yaml`. Actions are recorded as
  ordinary steps that export and replay without a model. Where no component locator exists, a pack's
  `RegionResolver` or an image-anchored `pointer` step is used.
- **Scenario verbs:** `pointer` and `expectImage` (deterministic masked baseline comparison), plus live-only
  `aiAct` and `aiExpect` (`skillroy session --play <scenario.yaml>`).
- **Robot tier (`v3`):** native OS pointer input via `java.awt.Robot` requires both the agent option `robot=true`
  and `--allow-robot` on the CLI. Models and MCP clients cannot enable it per call, and glass captures are never
  sent to a model.

## Windows (unverified on a Windows host)

Windows support—including `bin\skillroy.bat`, `PATHEXT` / `cmd.exe` planner handling in `skillroy author`, NTFS
ACL owner-only file checks, and `ci\run-on-windows.cmd`—is implemented and unit-tested via seams on POSIX hosts,
but has not yet been executed on a physical or virtual Windows machine. Prefer `JAVAAGENT` for Windows CI and
services; see [SECURITY.md](SECURITY.md) for Windows ACL notes.

## Exporting spans (OpenTelemetry)

Controller commands (`run`, `session`, `mcp`, `re-resolve`, `triage`, `author`) and the JUnit glue can export
OpenTelemetry spans from the controller (`--telemetry none|otlp|file`, `--telemetry-endpoint <url>`,
`--telemetry-attributes standard|minimal`, `--telemetry-file <path>`). Telemetry is off by default and never
enters the target JVM.

- `--telemetry file` writes `<out>/telemetry/spans.jsonl` (`skillroy-spans/1`).
- `--telemetry otlp` posts OTLP/HTTP protobuf traces to `<endpoint>/v1/traces` (`http://` allowed on loopback
  only; remote endpoints require `https://`). Authentication headers are configured via environment-variable
  references in an owner-only `telemetry.yaml`:

```yaml
version: 1
exporter: otlp
endpoint: https://collector.example.org
headers: {Authorization: env:OTLP_AUTHORIZATION}
attributes: standard
timeoutMs: 10000
```

## Extending to a real application

Once you outgrow `pack: base`, an application-specific integration adds two pieces in its own repository (see
[docs/guides/integration-guide.md](docs/guides/integration-guide.md)):

- an **agent module** (`AgentModule`, Java 8) adapting application threads, custom widgets, dialogs, in-process
  data connections, input vetoes, masks and domain primitives via `skillroy-agent-api`;
- a **domain pack** (`DomainPack`, Java 25—and optional `DomainPackOverlay`s for deployment variants) providing
  domain verbs, fact roots, queries, resolvers, verifiers, oracles, environment fingerprinting and SQL allow-lists.

The bundled sample lending desk (`samples/skillroy-sample-lending-*`) is the complete worked example.

## Licence and contributing

Licensed under the Apache License, Version 2.0 ([LICENSE](LICENSE), [NOTICE](NOTICE)). Every source file carries
the licence header (`ci/license-headers.sh`), and third-party dependencies are inventoried in
[docs/licence-audit.md](docs/licence-audit.md). Contributions are accepted under Apache-2.0 with a DCO sign-off
(`git commit -s`); see [CONTRIBUTING.md](CONTRIBUTING.md).
