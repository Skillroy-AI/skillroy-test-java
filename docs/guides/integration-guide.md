# Integration and Overlay Guide: Building a Product Layer on the Base

**Who it is for.** Engineers and AI agents building a private, application-specific testing layer on this base framework for a real Swing desktop application: one **product layer** per application, plus optional **tenant overlays** for customer or deployment variants. Read [`README.md`](../../README.md) first for a quick overview, [`base-framework-design.md`](../design/base-framework-design.md) (`design §n` below) for architectural background, and [`threat-model.md`](../design/threat-model.md) / [`SECURITY.md`](../../SECURITY.md) for trust boundaries.

Names in backticks are the base framework's own (types, options, files, commands, primitives). Names in angle brackets (`<product>`, `<tenant>`, `<env>`) are placeholders for your layer's identifiers.

## Contents

1. [The layers at a glance](#1-the-layers-at-a-glance)
2. [Layering: how a tenant overlay resolves](#2-layering-how-a-tenant-overlay-resolves)
3. [A suggested layout for the product layer](#3-a-suggested-layout-for-the-product-layer)
4. [Consuming the base](#4-consuming-the-base)
5. [Getting the agent into the target JVM](#5-getting-the-agent-into-the-target-jvm)
6. [The agent-side SPIs](#6-the-agent-side-spis)
7. [Safety rules a layer must keep](#7-safety-rules-a-layer-must-keep)
8. [The controller side: the domain pack](#8-the-controller-side-the-domain-pack)
9. [Hooks](#9-hooks)
10. [Embedded web views](#10-embedded-web-views)
11. [AI features and policy](#11-ai-features-and-policy)
12. [CI and environments](#12-ci-and-environments)
13. [Keeping the base neutral](#13-keeping-the-base-neutral)
14. [The first week: bringing up a new application](#14-the-first-week-bringing-up-a-new-application)
15. [Not provided by the base, and known limits](#15-not-provided-by-the-base-and-known-limits)
16. [Where to look](#16-where-to-look)

---

## 1. The layers at a glance

```text
tenant overlay      one customer's deployment variant      private: overrides parts of the product layer
  over
product layer       one target application                 private: agent module, domain pack, hook catalogues,
                                                            scenarios, bindings, CI
  over
base                this repository                        Apache-2.0: agent, controller, SPIs, CLI, JUnit glue,
                                                            the sample lending desk that proves every SPI
```

The framework learns an application through **two halves sharing one id** (design §2.6, §3.6):

| Half | Runs in | Java | Base type | What it knows about the application |
|---|---|---|---|---|
| agent module | the target JVM, in an isolated class loader | 8 (`--release 8`) | `AgentModule` (`skillroy-agent-api`) | its threads and readiness, its widgets and dialogs, its input seam, its data connection, its devices and embedded browsers, its secrets |
| domain pack | the controller | 25 (`--release 25`) | `DomainPack` (`skillroy-scenario`) | the verbs scenarios write, the sync topics, the fact roots, resolvers, verifiers and oracles, the fingerprint, the tables and masks, the hook catalogue, the constants |

The id is simultaneously the module id, the pack id, and the primitive namespace. In the sample it is `lending`: the module `LendingModule` (id `lending`), the pack `lending`, the primitives `lending.*`, and the MCP tools `lending_*`.

| The base owns | The product layer owns | A tenant overlay owns |
|---|---|---|
| the agent (bootstrap, core, Swing, data and web modules, optional hooks and Byteman modules), the wire protocol and its HMAC authentication, the controller, scenario compiler and executor, `BaseDomainPack`, resolution engine, bindings, inference layer, MCP server, CLI, JUnit glue, telemetry export, vision bolt-on | one agent module per application, its domain pack, its hook catalogues, its scenarios with their plans and bindings, its credentials files (never committed), its CI, and its leak check against the base | the overrides of one deployment variant: resolvers, constants, the SQL allow-list, the hook catalogue, and its own environments' bindings |

The base proves every piece on its own sample, which is the worked example to read beside this guide:

| Piece | Sample module |
|---|---|
| the application (Java 8, a custom engine thread, custom-painted widgets, modal prompts, an embedded database) | `samples/skillroy-sample-lending-app` |
| its agent module | `samples/skillroy-sample-lending-agent` (`LendingModule`) |
| its domain pack | `samples/skillroy-sample-lending-pack` (`LendingPack`) |
| a tenant overlay (the "branch edition") | `samples/skillroy-sample-lending-pack-branch` (`LendingBranchOverlay`, `BranchPack`) |
| scenarios, plans, bindings, end-to-end tests | `samples/skillroy-sample-lending-scenarios` |
| a host of embedded JCEF browsers, its agent module, pack and scenarios (Maven profile `jcef`) | `samples/skillroy-sample-web-host`, `-web-host-agent`, `-web-host-scenarios` |

---

## 2. Layering: how a tenant overlay resolves

The overlay mechanism is controller-side and proven on the sample's branch edition (design §3.6; `BranchEndToEndIT`).

### 2.1 Resolution

A tenant overlay implements `DomainPackOverlay` (package `ai.skillroy.testjava.scenario.pack`): `baseId()` names the pack it overlays, `id()` is its own id (`[a-z][a-z0-9-]*`), `version()` its version, and `overlay(DomainPack base)` returns the replacement pack — normally a subclass of `DelegatingDomainPack`, which forwards every member to `base` and lets the subclass override only what changes.

1. `DomainPacks.discover(loader)` loads every `DomainPack` service on the loader, then every `DomainPackOverlay` service (`META-INF/services/ai.skillroy.testjava.scenario.pack.DomainPackOverlay`).
2. Overlays are sorted by `id()`. For each base pack, every overlay whose `baseId()` matches is applied in order, chaining over the previous result.
3. The result is registered under the base's id: `DelegatingDomainPack.id()` is final and returns `base.id()`, so a scenario's `pack: <product>` selects the overlaid pack and **no scenario YAML changes**.
4. `version()` becomes `<base version>+<overlay id>@<overlay version>`, which `plan.json` records as `packVersion`.
5. An overlay whose base is absent, returns `null`, or returns a pack with a different id fails fast with `ScenarioException` before anything runs (`skillroy packs` exits 2).

Consume an overlay **as a second jar** beside the product pack jar it overlays.

### 2.2 What an overlay may override

Everything `DomainPack` declares (§8.1) can be overridden in a `DelegatingDomainPack` subclass except `id()`. Override as little as possible: everything an overlay changes must stay in step with the product pack.

| Need of the variant | Override | How the sample does it |
|---|---|---|
| a constant (a setting name, a threshold, a code) | `settings()`: `PackSettings` is an immutable string map; the product pack reads `ctx.settings().get("<pack>.<name>", default)` through `ResolveContext`, `VerifyContext`, `FingerprintContext`, or `PreflightCheck.PreflightContext` | `settings()` sets `lending.maxReturnSetting = return_ceiling`; the base's system-limit resolver reads it |
| a resolver, verifier, oracle, or cross-check whose logic differs | `resolution()`: `ResolutionCatalogue.builder(base.resolution()).replace(…).build()` (replace by id; refuses an unknown id) | `BranchStaffResolver` replaces the staff resolver with SQL naming the variant's column |
| the tables and columns model-written SQL may read | `sqlAllowLists()` | `BranchAllowList` lists the renamed column |
| hooked methods that differ | `hooks()` (§2.3) | the overlay's catalogue retargets `limit.applied` |
| verbs, sync policy, facts, prompt policy, pre-flight, recovery, fingerprint, stored tables, row masks, screen legend | the member itself | left untouched in the sample so the product's surface stays shared |

Two practices make overrides clean and low-maintenance:

- **Write the product pack for overlays from the start.** Expose query records, value types, resolver/verifier ids, and SQL strings as public API, and accept custom SQL via public constructors so an overlay subclasses rather than copies. Read variant-sensitive literals through `settings()` with defaults.
- **Keep sensible defaults in the base and relax per environment only when needed.** For example, the shortest secret masked inside free text is controlled per environment via `masking.minLengthInText` in `credentials.yaml`.

### 2.3 The hook catalogue of an overlay

An overlay's `hooks()` calls `HookCatalogues.overlay(base.hooks(), …)` or `HookCatalogues.overlayResource(…)` over `META-INF/skillroy/hooks/<overlayId>.yaml`. That file uses the format of §9.2 with two differences: `catalogue:` is the overlay's id, its `hooks` entries **replace** base entries of the same id (or add new ids), and `disable: [id…]` disables base entries. Because the overlay's catalogue id differs from the product's, attempting to install it onto a target JVM that already holds the product's catalogue stops with `ENVIRONMENT` and suggests `skillroy hooks remove`; launch a fresh target per variant. See `samples/skillroy-sample-lending-pack-branch/src/main/resources/META-INF/skillroy/hooks/lending-branch.yaml`.

### 2.4 Scenarios, plans, and bindings of a variant

- If pre-compiling plans, compile the variant's plans from the same YAML using the overlaid pack (they differ at least in `packVersion`; see `variants/branch/plan/` and `BranchPlansUpToDateTest` in the sample). Alternatively, run `.yaml` files directly on the fly (§8.6).
- Bindings are stored per environment (`<bindings root>/<env>/<scenario>.bindings.json`), so variant environments get their own environment ids and directories (e.g. `variants/branch/bindings/branch-a/`).
- Let the environment fingerprint separate variants naturally (for example through the database schema checksum) rather than adding overlay-only fingerprint fields that would invalidate standard-edition bindings.
- Include a negative test running a variant-sensitive scenario with the plain product pack against the variant target to confirm the overlay is genuinely exercised.

### 2.5 Packaging, loading, and the class-loader caveat

Packs and overlays are discovered via `ServiceLoader` on a single class loader: either the CLI's `--pack` jars (`--pack <product-pack>.jar --pack <tenant-overlay>.jar`) or the test class path of a Maven scenarios module (`ScenarioTests`).

**Class-loader caveat:** Any `DomainPackOverlay` jar on a class path overlays **every** lookup of its base pack in that JVM. Therefore:

- never place a tenant overlay jar on the same class path that runs plain product scenarios;
- give each variant its own test JVM: a dedicated `<tenant>-scenarios` Maven module (simplest), a separate Failsafe execution, or an isolated child class loader (as `BranchVariant` does in `samples/skillroy-sample-lending-scenarios/pom.xml`);
- in the CLI, pass `--pack <tenant-overlay>.jar` only when targeting that variant.

### 2.6 Handling variants on the agent side

There is no agent-side `DomainPackOverlay` SPI. Handle target-side variant differences using:

- **module options**: `AgentContext.option(key)` reads start-time options or falls back to live system properties `skillroy.agent.<key>` (for example `webhost.mask` in the web-host sample);
- **variant state in `app.state`**: expose the target's edition or mode in `AppStateProvider` so facts, pre-flight checks, and expectations can inspect it (such as `edition` in the lending sample);
- **an additional agent module jar** in `modules/` whose `requires()` names the product module (additive SPIs combine: all `ReadinessSource`s must pass, `CaptureMask`s union, `RegionResolver`s run in registration order, and the first `ThreadPolicyProvider` owns `ThreadPolicy.APP`).

---

## 3. A suggested layout for the product layer

### 3.1 Modules

```text
<product>-layer/                          private repository; Maven reactor
  pom.xml                                 parent: pins one base version property for all base artifacts
  agent/
    skillroy-<product>-constants/         Java 8, zero dependencies: ids, primitive/topic names, setting keys
    skillroy-<product>-agent/             Java 8: AgentModule; application jars at provided scope
    skillroy-<product>-plugin/            Java 8, optional: host plugin bootstrap for EMBEDDED mode (§5.3)
    skillroy-<product>-web/               Java 8, optional: custom WebEngineAdapter (§10.5)
  controller/
    skillroy-<product>-pack/              Java 25: DomainPack, hook catalogues, SQL allow-lists
    skillroy-<product>-scenarios/         Java 25: scenario YAML, optional plans, bindings/<env>/, JUnit glue
  tenants/<tenant>/                       in-repo or in a separate repository (§3.4)
    skillroy-<product>-<tenant>-pack/     Java 25: DomainPackOverlay jar (§2.5)
    skillroy-<product>-<tenant>-scenarios/  variant bindings (and plans if committed); isolated test JVM (§2.5)
  ci/                                     container image, runner scripts, and neutrality leak check (§12, §13)
```

| Module | Java | Depends on (scope) | Notes |
|---|---|---|---|
| constants | `--release 8` | nothing | Shared by agent and controller. Embed its classes inside the agent module jar (§3.3). |
| agent | `--release 8` | `skillroy-agent-api`, `skillroy-protocol` (`provided`); application jars (`provided`); `skillroy-agent-bootstrap` (`provided`, only if linking `EmbeddedAgent` directly) | Bundle zero third-party libraries (§7). Reference: `samples/skillroy-sample-lending-agent/pom.xml`. |
| plugin | `--release 8` | application plugin API (`provided`); zero agent compile dependencies when invoking `EmbeddedAgent` reflectively | Deployed on the **application's** class path, never in `modules/`. |
| web | `--release 8` | `skillroy-agent-api` (`provided`); `skillroy-agent-web` `test-jar` (`test`) | No browser vendor artifacts in any scope (§10.5). |
| pack | `--release 25` | `skillroy-scenario`, `skillroy-data`, constants | Reference: `samples/skillroy-sample-lending-pack/pom.xml`. |
| scenarios | `--release 25` | product pack, `skillroy-junit`, `skillroy-agent-dist` (`zip`, `test` scope with transitive exclusions); optionally `skillroy-cdp`, `skillroy-telemetry`, `skillroy-inference`, `skillroy-vision` (`test`) | Reference: `samples/skillroy-sample-lending-scenarios/pom.xml`. |
| tenant pack | `--release 25` | product pack, `skillroy-scenario` | Reference: `samples/skillroy-sample-lending-pack-branch/pom.xml`. |

### 3.2 Naming

- **Coordinates:** `ai.skillroy.<product>` (and `ai.skillroy.<product>.<tenant>`), with artifact ids `skillroy-<product>-agent`, `skillroy-<product>-pack`, etc.
- **Java packages:** `ai.skillroy.<product>.*`, **outside `ai.skillroy.testjava`**. The base treats `ai.skillroy.testjava.protocol.` and `ai.skillroy.testjava.agent.api.` as parent-first in module loaders and denies hooks on `ai.skillroy.testjava.agent.` and `ai.skillroy.testjava.protocol.`.
- **Domain id:** short, lowercase, neutral, and stable (`pack:` in YAML, `<domain>.*` primitives, `<domain>_*` MCP tools). Primitive segments use lowerCamelCase (`<namespace>.<verb>[.<sub>]`).
- **Overlay ids:** `[a-z][a-z0-9-]*`, typically `<product>-<tenant>` (e.g. `lending-branch`).

### 3.3 Rules from the agent's class-loader hierarchy

The agent home creates one loader for `lib/` and **one child-first loader per jar in `modules/`**:

- **Sibling module jars cannot see each other's classes.** Shade your constants module's classes directly into your agent module jar.
- **Every jar in `modules/` must register an `AgentModule`** (`META-INF/services/ai.skillroy.testjava.agent.api.AgentModule`). A jar without one records a `FAILED` module entry in `agent.info`, keeping mutating calls `NOT_READY` (when no `ProductionMarker` is set) unless `allowFailedModules=true`. Never drop plain helper jars into `modules/`.
- **`install(AgentContext)` must not touch application classes or AWT** and must never block on the EDT or startup thread, because `JAVAAGENT` runs `install` inside `premain` before the application's `main`.

### 3.4 Tenant overlays: in-repo vs separate repository

Keep tenant overlays in the product repository when the same team maintains both; split a tenant into its own repository when tenant identifiers, schema details, or hook targets have stricter access controls. Either way, ship the overlay as a second jar with its own test JVM (§2.5), its own `credentials.yaml` and bindings, and its own leak-check deny-list (§13).

---

## 4. Consuming the base

### 4.1 Base artifacts

Coordinates are `ai.skillroy:<artifactId>:<version>` (parent `ai.skillroy:skillroy-test-java-parent`):

| Artifact | Side | Used for |
|---|---|---|
| `skillroy-agent-api`, `skillroy-protocol` | agent (Java 8) | Agent SPIs (§6); `provided` scope |
| `skillroy-agent-bootstrap` | agent | `EmbeddedAgent`, only if linking directly rather than reflectively (§5.3) |
| `skillroy-agent-web` (`test-jar`) | agent tests | `WebEngineAdapterContract` test kit (§10.5) |
| `skillroy-agent-dist` (`zip`) | build / ITs | Versionless agent home assembly (§4.4) |
| `skillroy-scenario`, `skillroy-data` | controller (Java 25) | `DomainPack` SPI, `BaseDomainPack`, resolution, data channels, credentials |
| `skillroy-controller` | controller | `AgentClient`, attach, `TargetLauncher` (transitively via `skillroy-scenario`) |
| `skillroy-junit` | scenarios | `ScenarioTests` JUnit 5 `@TestFactory` harness and `OutputScan` (§8.10) |
| `skillroy-cdp` | scenarios, optional | Playwright CDP path for `web.*` verbs (§10.3) |
| `skillroy-inference`, `skillroy-mcp`, `skillroy-vision`, `skillroy-telemetry` | scenarios / tooling, optional | LLM resolvers, MCP server, V1–V3 vision loop, OpenTelemetry span export |

The CLI distribution (`bin/skillroy`, `bin/skillroy.bat`, `lib/`) is built under `controller/skillroy-cli/target/skillroy-cli/` (and `skillroy-cli.zip`); `skillroy-agent-dist` is attached as a Maven `zip` artifact.

### 4.2 Building and installing the base locally without polluting `~/.m2`

**Do not run `mvn install` inside a base development working tree.** Partial reactor builds in the base resolve `skillroy-agent-dist` from `~/.m2` if a snapshot is installed there, which can shadow fresh reactor changes with stale jars.

Safe ways for a private layer repository to consume the base before artifacts are hosted in a shared Maven repository:

1. **Dedicated local repository from a clean tag/commit checkout:** Clone or check out a tagged commit in a separate directory, verify and install into an isolated local repo (`mvn verify install -DskipTests -Dmaven.repo.local=<layer-m2-dir>` after a clean `mvn verify`), and point the layer's builds at `-Dmaven.repo.local=<layer-m2-dir>`.
2. **Internal artifact repository:** Deploy the verified tag from a clean checkout to your organization's internal Maven repository (Nexus, Artifactory, GitHub Packages) and resolve normally.
3. **Distinct local version:** If using `~/.m2`, set a dedicated version string in the clean checkout first (`mvn versions:set -DnewVersion=0.2.0-local1`) so it never collides with `0.2.0-SNAPSHOT` in the base working tree.

Always use the **same base build** for the agent home zip, the CLI distribution, and the controller Maven dependencies.

### 4.3 Version alignment

- **Keep agent and controller on the same base version.** Mixing versions across the wire is unsupported.
- **Protocol vs module versions:** `/ping` enforces matching `Protocol.VERSION` major (`0.2`). To guard against mismatches between your own `AgentModule` and `DomainPack`, bump `AgentModule.version()` whenever primitives/SPIs change and check `agent.info` in a `PreflightCheck` if desired.
- **Plan format (`planVersion` 7):** If you commit pre-compiled `plan.json` files alongside `.yaml` scenarios, re-run `skillroy compile` (or `-Dskillroy.plans.update=true` if using a test like `PlansUpToDateTest`) whenever upgrading the base or changing `DomainPack.version()`.

### 4.4 Assembling an agent home

Unpack `skillroy-agent-dist:<version>:zip` and copy `skillroy-<product>-agent.jar` into `modules/` (see `unpack-agent-home` and `lending-module-into-agent-home` in `samples/skillroy-sample-lending-scenarios/pom.xml`):

```text
target/skillroy-agent/
  skillroy-agent-bootstrap.jar            -javaagent / attach entry; host class path in EMBEDDED mode
  skillroy-agent-spy.jar                  appended to bootstrap path on first hook.install only
  lib/        skillroy-protocol.jar  skillroy-agent-api.jar  skillroy-agent-core.jar
  modules/    skillroy-agent-swing.jar  skillroy-agent-data.jar  skillroy-agent-web.jar
              skillroy-<product>-agent.jar
  optional/   skillroy-agent-hooks.jar  skillroy-agent-byteman.jar  skillroy-agent-byteman-sys.jar
```

Alternatively, pass external module paths via the agent option `modules=<jar>,…` (verified owner-writable-only by `JarTrust`) or `skillroy attach <pid> --module <jar>`.

---

## 5. Getting the agent into the target JVM

### 5.1 Three load modes, identical primitives

| Mode | How | `Instrumentation` (hooks, Byteman) | Typical use |
|---|---|---|---|
| `EMBEDDED` | Application plugin calls `EmbeddedAgent.start` / `startAsync` | No (`hook.engine` is `UNAVAILABLE`) | Suites without hooks when launch flags cannot be edited |
| `JAVAAGENT` | `-javaagent:<home>/skillroy-agent-bootstrap.jar[=<options>]` (or via `JAVA_TOOL_OPTIONS`) | Yes | CI and services; hook suites; Java 21+ targets (no JEP 451 warning) |
| `ATTACH` | `skillroy attach <pid> --home <home>` | Yes | Ad-hoc exploration on an already-running local JVM |

CI should always load the agent at launch (`JAVAAGENT` or `EMBEDDED`) and start the target once per suite (design §6).

### 5.2 The enable gate and option precedence

The agent starts zero threads and binds zero sockets unless explicitly enabled:

- `JAVAAGENT`: `-Dskillroy.agent.enabled=true` or `SKILLROY_AGENT_ENABLED=true` (never accepted from the `-javaagent` option string or options file, protecting child processes when `JAVA_TOOL_OPTIONS` is set).
- `EMBEDDED`: also accepts `enabled=true` in the `Properties` passed to `EmbeddedAgent.start`.
- `ATTACH`: also accepts `enabled=true` in attach options.

Option precedence (lowest to highest): built-in defaults -> `skillroy.agent.<key>` system properties -> options file (`t=` or `config=`, owner-writable-only, and `0600` if it contains `token`) -> argument string `k=v;k=v` or `EMBEDDED` `Properties`. Keep argument strings short (< 1024 bytes for HotSpot attach) and place longer settings in the options file.

### 5.3 `EMBEDDED` mode

- `EmbeddedAgent.start(Properties options, ClassLoader hostLoader, Object hostContext)` waits up to 60 s for startup; `EmbeddedAgent.startAsync(...)` returns immediately and is preferred on the EDT or latency-sensitive startup hooks. Both are idempotent, never throw, and use JDK types only so a host plugin can invoke them reflectively without compile-time coupling.
- `hostContext` is exposed via `AgentContext.hostContext()` so your `AgentModule` receives live application references cleanly rather than scraping statics.
- Place `skillroy-agent-bootstrap.jar` from inside the unpacked agent home on the host class path (`home` defaults to that jar's directory). Never hardcode `enabled=true` inside the plugin itself.
- Worked example: `SkillroyLendingPlugin` in `samples/skillroy-sample-lending-agent`.

### 5.4 `JAVAAGENT` mode

Use `-javaagent:<home>/skillroy-agent-bootstrap.jar=<options>`. When injecting via `JAVA_TOOL_OPTIONS`, always set `mainClassGuard=<mainClassOrJar>` so build tools or helper JVMs ignore the agent. Programmatic test launches can use `TargetLauncher.javaAgent(home, options)` (and `.viaJavaToolOptions(true)`).

### 5.5 `ATTACH` mode

`skillroy attach <pid> --home <home> [--module <jar>]… [--option k=v]…` writes an owner-only options file, waits out the 5 s JVM startup margin, maps JDK 8's `"0"` response, and completes the mutual `/ping` handshake. On JDK 21+, dynamic attach emits a JEP 451 warning unless the target started with `-XX:+EnableDynamicAgentLoading`.

### 5.6 Common agent options

| Option | Default | Purpose |
|---|---|---|
| `enabled` | `false` | Master enable switch (§5.2) |
| `home` | bootstrap jar directory | Agent home path |
| `modules` | – | Comma-separated external module jars (`JarTrust`-checked) |
| `optionalModules` | – | Optional module stems under `optional/`: `skillroy-agent-hooks`, `skillroy-agent-byteman` (§9) |
| `discoveryDir` | `${java.io.tmpdir}/skillroy-<user>` | Owner-only directory for discovery and token files |
| `tokenFile` | `<discoveryDir>/skillroy-<pid>-<startMs>.token` | Shared 32-byte HMAC secret file (never sent over the wire) |
| `port` | `0` (ephemeral) | Loopback HTTP port |
| `readOnly` | `false` | Blocks all `mutating` and `script` primitives and `STUB` hooks |
| `requireProductionMarker` | `false` | Refuses to serve if no `ProductionMarker` is registered (§5.7) |
| `allowFailedModules` | `false` | Allows readiness even if a jar in `modules/` failed to load (§3.3) |
| `mainClassGuard` | – | Exact launch main-class / jar prefix required in `premain` (§5.4) |
| `readyTimeoutMs` | `120000` | Deadline before `target.ready` reports `UNAVAILABLE` (polling continues) |
| `redact` | – | Regex of extra secret property/option keys (prefer `RedactionKeys` SPI, §6.11) |
| `robot` | `false` | Agent half of V3 `java.awt.Robot` double opt-in (§11.6) |
| `byteman.jar` | – | Path to user-supplied Byteman jar (§9.6) |
| `hooks.deny` | – | Operator class deny-list patterns for hooks and Byteman (§9.3) |
| `web.jxbrowser.chainInjectJs` | `false` | Opt-in chaining of JxBrowser `InjectJsCallback` for document-start scripts (§10.2) |

Custom `<product>.*` keys are preserved in `AgentContext.options()` / `AgentContext.option(key)`.

### 5.7 Refusing production

Register a fast, non-blocking `ProductionMarker` in your `AgentModule` that returns a non-null reason string (or throws) if the target environment looks like production (e.g. production licence flag, missing test mode marker, or live cluster hostname), and set `requireProductionMarker=true` in your options file so the agent also shuts down if your module failed to load. On production JVMs that must never accept same-user dynamic attach, pass `-XX:+DisableAttachMechanism`.

### 5.8 Target Java 8 through 25

Compile both main and test code in agent modules with `<release>8</release>` (no `var`, records, `List.of`, `String.isBlank`, etc.) and run agent tests on both a real Java 8 JVM and Java 25 (`ci/run-in-linux.sh`).

### 5.9 Windows notes

Windows support (`bin\skillroy.bat`, `ci\run-on-windows.cmd`, NTFS single-ACE `SecureFiles`) is implemented and unit-tested where portable, but **unverified on a live Windows host** (`SECURITY.md`):

- Prefer `JAVAAGENT` in an interactive user logon session (never Session 0 for display/Robot tests).
- `ATTACH` requires matching user, logon session, integrity level, and bitness.
- Non-English localized Windows built-in account names cause `SecureFiles` to fail closed (see `icacls` guidance in `SECURITY.md`); FAT/exFAT volumes are rejected.
- Place `--out` inside a private directory under the user's profile.

---

## 6. The agent-side SPIs

All agent SPIs live in `agent/skillroy-agent-api` (`ai.skillroy.testjava.agent.api` and `….spi`, `….spi.web`, `….spi.hook`; design §2.6).

### 6.1 Writing an `AgentModule`

- `install(AgentContext)`: register primitives (`ctx.primitives()`), SPIs (`ctx.spi()`), and capabilities (`ctx.capabilities()`). Do not touch application singletons, AWT, or `java.util.logging`.
- `onTargetReady(AgentContext)`: runs on an agent worker once all `ReadinessSource`s pass. Attach application listeners and chain seams here, saving registration handles.
- `uninstall()`: close registration handles, detach listeners, restore chained seams, and never throw.
- Route calls that might lazily initialize application thread pools or timers through `ThreadPolicy.APP` so spawned host threads do not inherit `skillroy-*` daemon status.
- Log via `ctx.log()` (`AgentLog`), never `System.out`, and register `META-INF/services/ai.skillroy.testjava.agent.api.AgentModule`.

### 6.2 The SPI map

| SPI | Purpose | When needed | Thread | Sample reference |
|---|---|---|---|---|
| `ReadinessSource` | Signals when domain singletons are safe to access (polled every 250 ms) | Always | Agent worker (non-blocking) | `LendingModule.Readiness` |
| `ThreadPolicyProvider` | Routes `ThreadPolicy.APP`, flags forbidden threads, and optionally exposes `quiescentPoint()` for `APP_QUIESCENT` | Whenever domain state has thread-affinity rules | Any agent thread (thread-safe) | `LendingWorker` |
| `AppStateProvider` | Populates `app.state` and `UiSnapshot.app` | Always | `ThreadPolicy.APP` | `LendingModule.AppState` |
| `NameEnricher` | Supplies stable names for components with empty accessible names | Icon-only buttons, custom widgets | EDT (fast) | `LendingWidgets` |
| `LocatorResolver` | Custom domain locator keys (`{action: "DUE"}`) and `describe()` for hit-testing | Custom widgets | EDT | `LendingWidgets` |
| `ActionFirer` | Fires custom widgets on the `ACTION` rung without focus | Focus-sensitive custom buttons | EDT | `LendingWidgets` |
| `DialogTextExtractor` | Extracts title, message, and options from custom dialogs | Non-standard dialogs | EDT | `LendingWidgets` |
| `DialogLocator` | Finds in-window modal overlays (glass panes, internal frames) | In-window modal prompts | EDT | `DialogLocator` Javadoc |
| `RegionResolver` | Maps coordinates inside custom-painted surfaces to stable region ids | Painted keypads, canvases | EDT | `LendingRegions` |
| `CaptureMask` | Blanks sensitive components in captures and withholds their text across readers | Password/PIN fields, secret screens, secret-feeding keypads | EDT (fast, lock-free) | `LendingCaptureMask` |
| `InputVeto` | Blocks input delivery to dangerous controls on every route (`force` included) | Exit/shutdown/irreversible buttons | EDT (fast, lock-free) | `LendingInputVeto` |
| `MaskPolicy` | Masks columns in `data.query`, hook captures, Byteman bindings, and `event:<topic>` payloads | PII / secret columns and event fields | Worker, hooked, or publishing thread | `PasswordMask` |
| `RedactionKeys` | Extra secret key patterns for JVM args, system properties, and options | Application-specific secret properties | Any (thread-safe) | `LendingSecrets` |
| `DataSourceProvider` | In-process read-only SQL channel (`open()` or cancellable 5-arg `query` with `QueryControl`, `lane()`, `columns()`, `exposesErrorText()`) | Resolvers and `db.*` checks over target DB | Worker or declared lane | `LendingDataSource`, `LendingRowsSource` |
| `DeviceAdapter` | Peripherals (`device.list`, `device.state`, `device.act`) with optional rung ladders | Simulated/physical scanners, printers | `ThreadPolicy.APP` | `PrinterDevice`, `ScannerDevice` |
| `ProductionMarker` | Shuts agent down if environment is production | Always (§5.7) | Agent worker (once) | `ProductionGateTest` |
| `BrowserRegistryAdapter` | Seeds (with optional tags) for embedded browsers outside the Swing tree | Cached, hidden, or view-less browsers | Agent worker (non-blocking) | `WebHostModule` |
| `BrowserMask` | Masks (`TRUE`) or releases (`FALSE`) browsers by seed tag or vendor instance | Sensitive or detached embedded browsers | Agent worker | `DetachedRelease`, `BackTagMask` |
| `WebEngineAdapter` | Reflective adapter for an embedded browser engine | Engines other than JCEF or JxBrowser | Agent worker | Built-in `jcef`, `jxbrowser` |
| `SurfaceSource` | Marks non-Swing surfaces for captures and hit-tests | Custom native surfaces (web module built in) | EDT | `skillroy-agent-web` |
| `HookEngine` | Programmatic hook management from Java via `ctx.hooks()` | Rarely (prefer YAML catalogues, §9) | Agent workers only | §9.5 |
| `ClassDeny` | Denies bytecode instrumentation of named classes before bytes are read | Hot, licensed, or timing-critical classes | Agent worker under lock | `LendingClassDeny` |

### 6.3 Readiness and `APP_QUIESCENT`

- Attach non-thread-safe application listeners at a quiescent point (for example when the engine first blocks waiting for input). If the application boots into a modal prompt before reaching quiescence, report ready with the input capability `UNAVAILABLE(reason)` so the agent can answer the prompt first, then attach the listener and emit a ready event (`ListenerSlot` in `skillroy-sample-lending-agent`).
- When state is confined to an application thread and safe to inspect only between inputs, declare primitives with `ThreadPolicy.APP_QUIESCENT` and a millisecond budget (`quiescentBudgetMs`, 1–1000 ms, default 50 ms). Expose a `QuiescentPoint(allowanceMs, capacity)` from `ThreadPolicyProvider.quiescentPoint()` and drive `runOne(availableMs)` from the application's idle callback (closing it in `uninstall()`). Quiescent tasks must never touch AWT, block, or call back into `ThreadBroker` (`lending.engine.view` in the sample).

### 6.4 Thread discipline and named lanes

`ThreadPolicy` routes each primitive to `CALLER`, `EDT_SYNC` (bounded `invokeAndWait` for reads), `EDT_ASYNC` (`invokeLater` for UI actions so modal dialogs never deadlock workers), `WORKER`, `APP`, `APP_QUIESCENT`, or `LANE`.

- Implement `ThreadPolicyProvider.isForbidden(thread)` cheaply for every application thread the agent must never block.
- Keep `APP` calls fast because they serialize on a single application lane. Never hop to `APP` for slow I/O or queries: declare an isolated worker lane via `ThreadBroker.declareLane("<module>.<name>", threads, queue)` (1–4 daemon threads, max 8 lanes per agent) and bind slow primitives (`PrimitiveSpec.Builder.lane(name)`) or `DataSourceProvider.lane()` to it.

### 6.5 The semantic input seam and sync topics

Expose your application's native input seam as a mutating primitive `<product>.input` (the `SEMANTIC` rung):

- **Chain, never replace** any existing input source or prompt handler, restoring the prior occupant in `uninstall()`.
- **Pre-check validity** against current state and fail fast with `INPUT_REJECTED` (`reason: notAccepted | inboxFull`) rather than silently dropping invalid inputs.
- **Return `InputResult` conventions:** `{accepted, inputId, consumed: CONSUMED | QUEUED, taken?, answeredBy?}`, emitting caveats via `inv.warn(...)` (`VALUE_COERCED`, `VALUE_DROPPED`, `ANSWERED_NESTED`, or `<packId>.<NAME>`).
- **Correlate acknowledgements by `inputId`:** Mint (or echo) a unique `inputId`, include `{inputId}` in your acknowledgement event payload (using the 4-argument `events().publish(topic, source, payload, correlationId)` when publishing asynchronously from an application callback), and enable `SyncPolicy.ackByInputId` in your `DomainPack` (§8.2) so late acknowledgements from queued inputs never satisfy a subsequent step's wait.

### 6.6 Application state for snapshots

`AppStateProvider.appState()` returns a small, side-effect-free JSON-compatible map under `ThreadPolicy.APP` containing key state at a glance (current screen/mode, open prompt, active transaction summary, edition/variant). Keep launch-ephemeral values (ports, timestamps) out of `EnvFingerprinter` (§8.5).

### 6.7 Custom widgets, dialogs, visibility, and `InputVeto`

- Supply `NameEnricher`, `LocatorResolver` (including `describe()`), `ActionFirer`, `DialogTextExtractor`, `DialogLocator`, and `RegionResolver` for custom Swing components so snapshots, semantic clicks, modal checks, and vision grounding work seamlessly.
- **Effective visible area:** Locators and snapshots automatically skip *collapsed* components (zero effective area after ancestor clipping, while preserving components scrolled off-screen inside a `JViewport` or `ScrollPane`).
- **`InputVeto`:** Register an `InputVeto` for every control that exits the JVM or triggers an irreversible external side effect. `refuse(component, route)` is checked on the target and every ancestor up to the window (including popup invokers and `ENTER` default buttons). Answer `Route.ANY` with a non-null reason whenever any route is vetoed so snapshots mark `vetoed: true` and models/recorders never target it. Because keyboard mnemonics/accelerators and `web.*` DOM actions inside embedded browsers are not traced back to Swing menu items, veto the enclosing window when needed.

### 6.8 Embedded browsers outside the Swing tree

Return `BrowserSeed.of(browserOrEngine, tag)` from `BrowserRegistryAdapter.seeds()` for cached, hidden, or pre-loaded browsers without creating or initializing an engine. Whenever any `CaptureMask` or `BrowserMask` is registered, **view-less browsers default to masked** (URLs/titles hidden, DOM reads refused, page event payloads stripped); register a `BrowserMask` returning `Boolean.FALSE` for specific benign tags (`"detached".equals(tag) ? Boolean.FALSE : null`) if scenarios inspect them, or `Boolean.TRUE` to mask sensitive browsers by tag or object identity.

### 6.9 In-process data channel (`DataSourceProvider`)

`DataSourceProvider` backs `data.query` (single guarded `SELECT` / `WITH`, <= 200 rows, bounded timeout) using either:

- `open()`: returns a JDBC `Connection` configured read-only, rolled back, and closed by the agent; or
- `query(sql, params, maxRows, timeoutMs, QueryControl)`: executes against a custom data API, registering cancellation hooks on `control.onCancel(...)` and optionally running on a dedicated `lane()` and supplying `columns(table)` for positional sources.

`exposesErrorText()` defaults to `true` so SQL syntax and schema errors appear directly in `error.data.message` during scenario authoring and debugging (even when a `MaskPolicy` is active); override it to return `false` only if a database driver echoes sensitive literals in exception messages. Always pair `DataSourceProvider` with a `MaskPolicy` and a read-only database account.

### 6.10 Devices

Implement `DeviceAdapter` for simulated or physical peripherals (`PrinterDevice`, `ScannerDevice`), exposing `state()`, `act(action, args)`, and optional fidelity ladders via `rungs(action)` (`PHYSICAL`, `SEMANTIC`). Actions with ladders return `RungResult`, enforce `minRung` (`CAPABILITY_UNAVAILABLE` if unmet), and echo `minRung` in their result.

### 6.11 Masking and redaction

| Surface | Mechanism | Key guidance |
|---|---|---|
| `data.query` rows, `CAPTURE` hooks, Byteman bindings | `MaskPolicy` | Thread-safe, lock-free, case-insensitive on column labels; inspect `sourceId` to distinguish data sources from `event:<topic>`. Unnamed expressions on positional sources redact to `<redacted>` while any policy is registered. |
| Captures, snapshots, hit-tests, dialog text | `CaptureMask` | Mask every secret-displaying component *and* input pads feeding it (`JPasswordField` is masked automatically). |
| JVM args, system properties, agent options | `RedactionKeys` | Add product-specific secret key patterns (`lending.pin`). |
| Controller-side JDBC rows and provenance samples | `DomainPack.rowMasks()` | Mirror agent-side row masking rules on the controller (§8.5). |
| Agent event ring (`EventBus`) | Secret-key filter + `MaskPolicy` (`event:<topic>`) | Mask sensitive values before publishing; avoid masking correlation keys (`id`, `inputId`, `name`) that waits match on. Verify with `OutputScan` (`Scope.EVENTS`). |

### 6.12 Capabilities and probes

Declare capabilities via `ctx.capabilities().declare(id, probe)` (or `set(...)`) on the agent, or via `DomainPack.capabilities()` (`PackCapability`, source `pack:<packId>`, evaluated over the data channel at run start and honoured in `replay`) on the controller. Use capability ids in `requires:` for hard preconditions (`PRECONDITION_FAILED`) or in `capability:` expectation guards (`DEGRADED`). Avoid declaring the same capability id in both the agent module and the pack; check the merged table with `skillroy caps --pack <jar>`.

### 6.13 Events

`ctx.events().publish(topic, source, payload)` is non-blocking and safe from any thread (use the 4-argument overload with `Invocation.correlationId()` for asynchronous callbacks). Keep event payloads small and structured so neither `eventRing` count nor `eventRingChars` truncates the run window when scenarios query the `events` fact root (`events[*topic=...]`).

### 6.14 Custom primitives and composites

Build domain primitives with `Primitives.of(PrimitiveSpec.builder("<product>.<verb>")...build(), body)`, marking `mutating`, `destructive` (requires `confirm`), or `script` accurately. When an operation involves dynamic application sub-prompts (such as optional password-expiry or induction questions during sign-on or item scan), encapsulate the branch inside **one bounded composite primitive** (`CompositeResult`, listing the `seq` of every prompt answered within the call window; see `LendingComposites`) so scenario plans stay linear and deterministic.

---

## 7. Safety rules a layer must keep

1. **Never throw into, block, or slow the host application.** Bound every wait, post UI actions via `invokeLater`, use bounded `invokeAndWait` for EDT reads, respect `isForbidden(thread)`, and touch nothing of the host or AWT inside `install()`.
2. **Zero sleeps.** Synchronize strictly on events (`sync.await`, `SyncPolicy` topics) and `swing.settle` across primitives, verbs, and `recover()`.
3. **Zero third-party libraries in the target JVM.** Compile agent modules against host jars at `provided` scope and bundle no third-party classes.
4. **Never trigger JVM exit or restart.** Register an `InputVeto` on every quit/shutdown/irreversible control; implement `recover()` to return the UI to idle without restarting the process.
5. **Chain host seams and restore on detach** (§6.5).
6. **Keep hooks optional, small, and backed by public seams.** Default path uses zero hooks; scope catalogues to application packages (`packages` + `codeSource`) and `deny:` sensitive classes (§9).
7. **Never bundle Byteman.** Byteman is LGPL-2.1-or-later and stays user-supplied via `byteman.jar` (§9.6).
8. **Loopback and `Skillroy-HMAC` only.** Never open unauthenticated listeners in the target JVM, and bind any test JDWP port strictly to `127.0.0.1`.
9. **Mask data before it leaves.** Configure `MaskPolicy`, `CaptureMask`, `BrowserMask`, `RedactionKeys`, and `rowMasks()`, and store secrets exclusively in uncommitted `0600` `credentials.yaml` files.
10. **Models propose; humans confirm.** Never bypass `confirm: true` or `CallPolicy` restrictions in pack verbs.
11. **Refuse production** (`ProductionMarker` + `requireProductionMarker=true`, §5.7) and never self-enable the agent in code.
12. **Protect run outputs.** Treat `--out` directories (screenshots, snapshots, `run.jsonl`) as sensitive application data (`umask 077` on shared hosts).

---

## 8. The controller side: the domain pack

### 8.1 `DomainPack` members and `BaseDomainPack`

`DomainPack` (`ai.skillroy.testjava.scenario.pack.DomainPack`) is discovered via `ServiceLoader`. For immediate bring-up or PoC exploration before writing any Java pack code, the framework includes `BaseDomainPack` (`id = "base"`, `version = "1"`), selected automatically when a scenario sets `pack: base` or omits `pack:` with no custom pack on the class path.

| Member | Default | Contributes |
|---|---|---|
| `id()`, `version()` | – | Pack/primitive namespace and version recorded in compiled plans |
| `steps()` | – | `StepLibrary` compiling domain verbs to `PlannedCall`s (§8.2) |
| `syncPolicy()` | – | Ack, ready, and prompt topics, timeouts, and `ackByInputId` (§8.2) |
| `facts()` | – | `FactProvider` roots for expectations alongside the 8 base roots (§8.3) |
| `promptPolicy()` | `PromptPolicy.FAIL` | Deterministic handler for unexpected prompts when `on_prompt` is `pack` |
| `preflight()` | empty | `PreflightCheck`s (`SCENARIO` or `SUITE` scope) failing fast as `ENVIRONMENT` |
| `capabilities()` | empty | Environment-backed `PackCapability` probes (`<packId>.*`), honoured in `replay` |
| `warningCodes()` | empty | Declared `<packId>.<NAME>` warning codes usable in `accept:` |
| `recover(agent)` | no-op | Bounded, event-driven cleanup returning the application to idle after failure |
| `resolution()` | empty | `ResolutionCatalogue` of queries, resolvers, verifiers, oracles, and cross-checks (§8.4) |
| `fingerprinter()` | none | `EnvFingerprinter` components guarding frozen bindings (§8.5) |
| `storedTables()` | empty | Allow-listed `db.<table>` definitions for `verify:` checks (§8.5) |
| `rowMasks()` | empty | Controller-side row masks for JDBC reads and provenance samples (§8.5) |
| `sqlAllowLists()` | empty | Schema catalogue and personal-column flags for `llm-sql` (§11.3) |
| `assistAnswerPrimitive()` | `null` | Target primitive used by `OverlayBridge` for in-app assistant replies (§11.5) |
| `hooks()` | `META-INF/skillroy/hooks/<id>.yaml` | Bytecode hook catalogue (§9.2) |
| `settings()` | empty | `PackSettings` map overridable by `DomainPackOverlay` (§2.2) |
| `screenLegend()` | `null` | Concise UI layout legend supplied to vision grounding prompts (§11.6) |

### 8.2 Verbs and synchronization

`StepLibrary.compile(verb, args, ctx)` emits linear `PlannedCall`s with a `Sync` mode (`NONE`, `ACK`, `READY`, `SETTLE`, or `EVENT` with scalar / `{has: scalar}` `PayloadMatch`) and optional `CompileContext.addCleanup(...)` hooks executed in reverse at scenario end.

- Enable `SyncPolicy.ackByInputId` once your `<product>.input` primitive echoes `inputId` in its ack events (§6.5).
- Pass credential-joined `$slot`s into sign-on/authorise verbs so passwords resolve from `credentials.yaml` at bind time with `_meta.secretParams` protection.
- Declare rung ladders on device/input calls (`PlannedCall.withRungs(...)`, forwarding `ctx.minRung()`) and pre-accepted warnings (`PlannedCall.withAccept(...)`) where appropriate.

### 8.3 Facts, prompts, pre-flight, and recovery

- **Fact roots:** Eight base roots (`app`, `ui`, `dialogs`, `caps`, `devices`, `web`, `hooks`, `events`) are built in; custom `FactProvider`s add domain roots (`loan`, `state`, `slip`).
- **Prompts:** `on_prompt: { unexpected: fail | pack }` selects strict failure or `DomainPack.promptPolicy()`. Prompts answered internally by a `CompositeResult` within its call window are automatically accounted for.
- **Pre-flight:** Use `CheckScope.SCENARIO` (default) for checks that can change between tests and `CheckScope.SUITE` for one-time environment invariants cached per suite and agent start.
- **Recovery:** Bound `recover(agent)` strictly by action count and wall-clock time (e.g. <= 20 actions, <= 60 s in `LendingPack`).

### 8.4 Resolution: queries, resolvers, verifiers, oracles, cross-checks

- **Queries (`Query<T>`):** Unsealed pack-defined records registered via `QueryType.of(kind, queryClass)` (design §9).
- **Resolvers (`Resolver`):** Return deterministic, ordered `ResolverCandidate<T>` lists (`CANONICAL_SQL`, `CANONICAL_JAVA`, or `LLM_SQL`). Slots resolve in DAG order without backtracking, so place the most constrained slot first.
- **Verifiers (`Verifier`):** Deterministic in-JVM checks invoking the application's own logic (`lending.access.check`, `lending.deposit`) — mandatory gate for every tier.
- **Oracles (`Oracle`) and `CrossCheck`s:** Pure unit-tested Java functions computing expected values from bound slots, guarded by `must_equal` and verified at bind time against application dry-run primitives (`ORACLE_SUSPECT` on mismatch).
- **Credential joins:** `credential: { realm, key }` filters candidates to those present in `credentials.yaml`.

### 8.5 Fingerprint, stored tables, and row masks

- **`EnvFingerprinter`:** Hash schema checksums, application build/version, data seed version, and browser engine version; exclude ephemeral ports, dates, hook catalogues, and display rendering properties.
- **`storedTables()` and `rowMasks()`:** Declare tables queryable in `verify:` (`db.<table>`, including aggregates) and supply `RowMask`s matching your agent-side `MaskPolicy`.

### 8.6 Scenario YAML, on-the-fly execution, and `resolveHash`

Scenarios use expressive expectations including list filters (`"loan.lines[*code=$item.code]": { size: 1 }`), `captureFacts` deltas (`{ gt: $before.qty }`), `containsWhere`, `anyOf`, `[json]`, and `events` bus counts (`"events[*topic=prompt.waiting]": { size: 0 }`), plus step modifiers `accept: [CODE]` and `minRung: NAME` (see `samples/skillroy-sample-lending-scenarios/scenarios/` for full examples).

- **Direct YAML or committed `plan.json`:** Both `skillroy run` and `ScenarioTests` compile and run `.yaml` files directly in memory, or execute pre-compiled `plan.json` files verified by `skillroy compile --check`.
- **Resilient bindings (`resolveHash`):** `.bindings.json` files are tied to `resolveHash` (and per-slot `argsHash`) rather than `planHash`, so editing `steps:` or `verify:` expectations never invalidates committed bindings unless `resolve:` or `oracle:` changes.

### 8.7 Credentials, bindings, and bind modes

- **`credentials.yaml`:** Per-environment, uncommitted, `0600` owner-only file holding user passwords/PINs, controller JDBC credentials, and optional `masking.minLengthInText`.
- **Bind modes (`--mode` / `-Dskillroy.mode`):**
  - `replay` (CI): zero LLM calls (asserted), re-verifies frozen bindings, fails fast as `REBIND_NEEDED` on drift.
  - `approve` (interactive): resolves unapproved/changed slots, presents SQL/rationale/verifier proof/cost, and writes `.bindings.json` upon confirmation.
  - `live` (authoring/dev): resolves dynamically; writes bindings only with `--write-bindings`.
  - `re-resolve` (scheduled): batches nightly re-resolution via `skillroy re-resolve` and outputs a diff report for a PR.

### 8.8 Guarded expectations, `DEGRADED`, and failure classification

Use `requires:` for mandatory capabilities (`PRECONDITION_FAILED`) and `capability:` on individual expectations for optional evidence (`DEGRADED` when unavailable). Model environment/configuration switches as agent capabilities or controller `PackCapability` entries so `skillroy caps` and `capabilities.json` capture the complete run contract.

### 8.9 Image baselines and render keys

`expectImage` compares redacted root-pane `PAINT` captures pixel-for-pixel (after masks) against committed baselines under `<scenarioId>.images/`, keyed by the target's **render key** (JDK line, look-and-feel, font set, scale) rather than `EnvFingerprint`. Prefer structured fact expectations for business logic and reserve `expectImage` for visual layout/rendering checks.

### 8.10 Running scenarios: JUnit 5 and CLI

- **JUnit 5 (`skillroy-junit`):** Return `ScenarioTests.dynamicTests(ScenarioSource.classpath("scenarios"), () -> connection)` from a `@TestFactory` method. Run CI with `mvn -pl <scenarios-module> verify -Dskillroy.mode=replay -Dskillroy.env=<env>`.
- **CLI (`skillroy --help`):** `jvms`, `attach`, `info`, `caps`, `schema`, `call`, `snapshot`, `events`, `compile`, `run`, `resolve`, `diff-bindings`, `re-resolve`, `mcp`, `author`, `triage`, `session`, `hooks`, `packs`.

---

## 9. Hooks

Hooks are optional bytecode observation points (`optionalModules=skillroy-agent-hooks` in `JAVAAGENT` or `ATTACH` mode, controlled per run via `--hooks auto|on|off` or `-Dskillroy.hooks=auto|on|off`).

### 9.1 Catalogue format, `codeSource`, and `deny`

Place `META-INF/skillroy/hooks/<packId>.yaml` in your pack jar:

```yaml
catalogue: <packId>
version: 1
application: <application> <version>
packages:
  - { name: <your.app.pkg>, codeSource: '<your-app>(-[^/]*)?\.jar' }
deny: [<your.app.pkg>.licencing.**]
hooks:
  - id: limit.applied
    mode: CAPTURE                          # OBSERVE | CAPTURE | STUB
    target:
      class: <your.app.pkg>.LimitService
      method: effectiveLimit
      descriptors: ["(Ljava/lang/String;)Ljava/math/BigDecimal;"]
    capture: { args: [0], result: true }
    secretArgs: []
    secretResult: false
    fallbacks:
      - seam: <product>.limits             # first fallback must always be a public seam primitive
    note: Observe applied role limit
```

- `codeSource` filters hooked classes by exact regex match on the loaded jar filename so third-party classes sharing a package stay untouched.
- Class `deny` patterns (`hooks.deny` agent option, catalogue `deny:`, and `ClassDeny` SPI) are additive for the life of the agent instance and refuse matching classes before any bytecode is read.
- `replay` fails closed with `ERROR` / `ENVIRONMENT` if any `STUB` hook is armed.

### 9.2 Engine limits, facts, and CLI

The engine refuses classes > 256 KiB or > 2,000 methods, caps hooked classes at 64, refuses strict-delegation loaders that hide bootstrap `Spy`, and byte-verifies class restoration on `skillroy hooks remove` or detach (`hooks.detachBudgetMs`, 8 s). Scenarios assert hook evidence through the `hooks` fact root (`hooks[id=limit.applied].count`, `.last.args.0`, `.last.returned`).

### 9.3 Optional Byteman (`skillroy-agent-byteman`)

For ad-hoc diagnostic rules outside the catalogue, enable `optionalModules=skillroy-agent-byteman` and point `byteman.jar=<path>` to a user-supplied Byteman 4.0.27 jar in an owner-only directory. Byteman loads in-process with `listener:false` on the first confirmed `rule.submit`, honours the class `deny` list upfront, and exposes `rule.check`, `rule.submit` (destructive, requires `confirm`), `rule.unload`, and `rule.list`.

---

## 10. Embedded web views

Every `web.*` verb (`webClick`, `webFill`, `webDispatch`, `webWaitFlag`, `webResize`, `webTap`) executes over either Playwright CDP (`skillroy-cdp`) or the in-process agent SPI (`skillroy-agent-web`), selectable via `web: { path: auto | cdp | agent }`.

- **In-process adapters (`skillroy-agent-web`):**
  - `jcef`: Uses JCEF's in-process DevTools protocol methods (`executeDevToolsMethod` + message observer) without needing a debug port or occupying single-slot client handlers.
  - `jxbrowser`: Reflectively resolves common APIs across JxBrowser lines 7, 8, and 9 from `BrowserRegistryAdapter` seeds (`web.jxbrowser.chainInjectJs=true` opts into chaining `InjectJsCallback` for document-start scripts). Verified against JxBrowser 8.18.3 on macOS arm64 (JDK 25); run `WebEngineAdapterContract` against your exact version and OS when adopting.
- **Playwright CDP (`skillroy-cdp`):** Attaches to a launch-time loopback remote-debugging port (`connectOverCDP` with `noDefaults` and `isLocal`), running an upfront `/json/list` + `Runtime.evaluate("1")` liveness check under an external deadline and mapping pages by `cdpTargetId`. Built against Playwright 1.63.0 (supports >= 1.61 in full, 1.60 untraced, and 1.58–1.59 only under `-Dskillroy.cdp.allowDefaultOverrides=true`).
- **Web masking:** Mask sensitive browser views with `CaptureMask` on the Swing component or `BrowserMask` by seed tag / vendor instance (§6.8). On masked browsers, snapshots/events withhold page data and DOM locator actions require human-only `redact: false`.
- **Custom browser engines:** Implement `WebEngineAdapter` + `WebBrowser` (`--release 8`, zero compile-time vendor types in signatures) and validate with `WebEngineAdapterContract` from `skillroy-agent-web`'s `test-jar`.

---

## 11. AI features and policy

- **Zero LLM calls in CI:** `replay` forces `gateway: none` and asserts an empty cost ledger. `ANTHROPIC_API_KEY` is read exclusively from the environment for live developer/authoring workflows.
- **`inference.yaml` and pricing:** Configures `gateway` (`claude | recorded | none`), `models`, `effort`, `budget` (`scenarioUsd` default `0.50`, `sessionUsd` default `1.00`, `jobUsd` for `re-resolve`), `thresholds.confidence` (`0.6`), and `images` (`false` by default — pixels never leave for a model provider unless explicitly set `true`). Unlisted newer model IDs automatically use `PriceTable.effective(model)`'s fallback rate unless overridden under `prices:`.
- **Tier (b) `llm-sql`:** Guarded by `SqlGuard` (`SELECT`-only; procedure `CALL`/`EXEC` calls are refused, deterministic `ORDER BY`, `LIMIT <= 200`), `AllowListCheck` (verifies declared tables/columns exist while tolerating extra unenumerated database columns), lenient `Record` candidate decoding (ignoring extra `ORDER BY` expressions), in-process `Verifier`s, and human `approve` sign-off.
- **Authoring, interactive sessions, and vision:** `skillroy author` drafts YAML via headless `claude -p` + `skillroy-mcp`; `skillroy session` (`--vision v1|v2|v3|off`) journals live steps through `ExecutorSession` and exports replayable YAML scenarios. V3 `java.awt.Robot` requires double opt-in (`robot=true` on the agent + `--allow-robot` on the controller). `OverlayBridge` connects optional tenant-built in-app assistant UIs over existing SSE/RPC primitives.

---

## 12. CI and environments

- **Linux container (`ci/Dockerfile` & `ci/run-in-linux.sh`):** Runs `eclipse-temurin:25-jdk` with mounted JDK 8 (`SKILLROY_JDK8_HOME`), 24-bit `Xvfb` (`1024x768x24`, no WM), DejaVu fonts, and JCEF libraries. Copy or reuse it for your layer's CI; keep `-Dskillroy.headless=true` on developer desktops so display tests never pop up windows locally. Under cross-architecture QEMU emulation, pass `-Djdk.lang.Process.launchMechanism=FORK` to test JVMs.
- **Verification matrix:** Gate every scenario in `replay` against Java 8 and Java 25 targets in your supported launch modes (`JAVAAGENT` and `EMBEDDED`), starting the target once per suite (`release-checklist.md`).
- **Artifacts and OpenTelemetry:** Restrict access to CI `--out` bundles (`umask 077`). Enable optional controller-side span export with `--telemetry otlp` (`https://` or loopback `http://`, auth token via env var in `telemetry.yaml`) or `--telemetry file` (`telemetry/spans.jsonl`).

---

## 13. Keeping the base neutral

Per [`CLAUDE.md`](../../CLAUDE.md) and [`CONTRIBUTING.md`](../../CONTRIBUTING.md), nothing in this base repository — code, tests, fixtures, comments, docs, or git history — may ever name or describe a customer, tenant, or proprietary application:

- Express upstream feature requests, bug reports, and pull requests strictly in generic framework terms or using the sample lending desk's domain (`lending`: items, loans, deposits, waivers, slips, staff), accompanied by unit/integration tests and DCO sign-off (`git commit -s`).
- Maintain a **private leak-check script** in your product/tenant repository holding a deny-list of proprietary product, package, class, schema, and tenant names, and run it against a clean `git archive` export of the base before pushing upstream changes or cutting releases.

---

## 14. The first week: bringing up a new application

1. **Install the base** (§4.2) from a clean checkout into a dedicated local or internal Maven repository and verify your empty layer POM resolves `skillroy-agent-api` and `skillroy-scenario`.
2. **Spike readiness and threading** (§6.3, §6.4): implement a minimal `AgentModule` with `ReadinessSource`, `ThreadPolicyProvider`, and `AppStateProvider`; verify `skillroy call app.state` returns `OK` (`target.ready`).
3. **Load and guard the agent** (§4.4, §5.4, §5.7): assemble the agent home, launch with `-javaagent` and `-Dskillroy.agent.enabled=true`, add your `ProductionMarker` + `requireProductionMarker=true`, and verify `skillroy info`, `skillroy caps`, and `skillroy snapshot` on Java 8 and Java 25 targets (plus refusal on a simulated production config).
4. **Enrich widgets and mask secrets** (§6.7, §6.11): walk `skillroy snapshot` across primary screens and add `NameEnricher`, `LocatorResolver`, `ActionFirer`, `DialogTextExtractor`, `DialogLocator`, `InputVeto`, `CaptureMask`, and `RedactionKeys` until every target control has a clean locator and `swing.capture` blanks every secret.
5. **Wire the semantic input seam** (§6.5, §6.14): add `<product>.input` (chained, state-checked, publishing correlated `inputId` ack and ready events) and prompt read/answer primitives; verify event flow via `skillroy events`.
6. **Run a first scenario in `replay`** (§8.1, §8.6): start immediately with `pack: base` (zero pack code) to run a smoke `.yaml` scenario using built-in UI/event verbs (`click`, `type`, `expect`, `waitFor`). Then introduce your custom `DomainPack` (`SyncPolicy`, core verbs, fact roots, `PreflightCheck`, `recover`) and verify `ScenarioTests` passes twice back-to-back without restarting the target.
7. **Add data resolution and verification** (§6.9, §8.4, §8.5, §8.7): wire `DataSourceProvider` + `MaskPolicy`, `storedTables()`, a tier (a) resolver + in-process `Verifier`, an `Oracle` + `CrossCheck`, `EnvFingerprinter`, and `credentials.yaml`; verify `--mode approve` freezes bindings and `replay` catches tampered bindings as `REBIND_NEEDED`.
8. **Add optional hooks if needed** (§9): define `META-INF/skillroy/hooks/<product>.yaml` with public-seam fallbacks and guard hook expectations so suites pass with hooks on and report `DEGRADED` with hooks off.
9. **Add embedded web views if present** (§10): supply `BrowserRegistryAdapter` seeds, page readiness signals, and `CaptureMask` / `BrowserMask` rules; verify over `agent` and `cdp` paths.
10. **Automate CI and leak checks** (§12, §13): run the `replay` matrix in the Linux container, verify `skillroy: zero model calls` in JUnit reports, and enforce your private neutrality leak check.

---

## 15. Not provided by the base, and known limits

### 15.1 Intentional boundaries (handled outside base SPIs)

| Need | How it is handled |
|---|---|
| Application input seam SPI | Implemented as a domain primitive (`<product>.input`, §6.5) chaining any host seam |
| Agent-side variant overlay SPI | Module options (`AgentContext.option`), `app.state` variant fields, or additive module jars (§2.6) |
| Per-application-version hook catalogues | One pack artifact per application line, or overriding `DomainPack.hooks()` via `PackSettings` (§9.1) |
| Dynamic module upload over HTTP | Modules are loaded from `modules/`, `modules=<jar>`, or `skillroy attach --module` (§4.4) |
| Hooks in `EMBEDDED` mode | Use `JAVAAGENT` for suites requiring `Instrumentation` (§5.1) |
| In-application assistant UI | `OverlayBridge` runs in `skillroy-inference`; UI presentation lives in the tenant layer (§11) |
| Opening `re-resolve` pull requests | `skillroy re-resolve` writes updated bindings and a Markdown report; CI pipelines open the PR |
| Scenario authoring DSL | Declarative YAML (compiled to `plan.json` or executed directly, §8.6) |
| DOM-element-granular raster masking | Mask the browser component (`CaptureMask`) or seed/instance (`BrowserMask`, §10) |
| Non-Anthropic model gateways | Implement the `InferenceGateway` SPI in `skillroy-inference` (design §5) |

### 15.2 Unverified platforms and configurations

- **Windows hosts:** `bin\skillroy.bat`, `ci\run-on-windows.cmd`, and NTFS ACL handling in `SecureFiles` are unverified on a live Windows machine (§5.9).
- **JxBrowser combinations:** Verified against JxBrowser 8.18.3 on macOS arm64 (JDK 25) via `WebEngineAdapterContract`; run the contract kit against other JxBrowser lines, OSes, or Java 8 targets (§10).
- **Native `Robot` display environments:** Verified on the Linux container's 24-bit Xvfb display at scale `1.0`; macOS Accessibility, Windows desktops, Wayland, and HiDPI fractional scaling are unverified.
- **Byteman alongside third-party agents:** Test in staging before enabling on complex multi-agent JVMs.

### 15.3 Security considerations to plan around

Review [`SECURITY.md`](../../SECURITY.md) and [`threat-model.md`](../design/threat-model.md) when setting up environments: OS same-user attach boundaries (`-XX:+DisableAttachMechanism` on production JVMs), read-only DB roles behind `SqlGuard`, `umask 077` and private `--out` directories for run artifacts, loopback debug-port ownership before launching Chromium/CDP, rectangle-level capture redaction, opt-in `images: true` egress, and never committing `credentials.yaml` or Robot opt-ins.

---

## 16. Where to look

| Topic | Location |
|---|---|
| Quick start, zero-code `pack: base`, CLI walkthrough, build | [`README.md`](../../README.md) |
| Architecture, protocol, SPIs, resolution, vision, CI design | [`base-framework-design.md`](../design/base-framework-design.md) |
| Trust model, actors, HMAC boundary, LLM/MCP constraints | [`threat-model.md`](../design/threat-model.md), [`SECURITY.md`](../../SECURITY.md) |
| Third-party dependencies and licences | [`licence-audit.md`](../licence-audit.md) |
| Pre-release verification gates | [`release-checklist.md`](../release-checklist.md) |
| Development rules and neutrality policy | [`CLAUDE.md`](../../CLAUDE.md), [`CONTRIBUTING.md`](../../CONTRIBUTING.md) |
| Reference Swing + HSQLDB application, agent module, pack, overlay, scenarios | `samples/skillroy-sample-lending-app`, `-agent`, `-pack`, `-pack-branch`, `-scenarios` |
| Reference embedded JCEF host, agent module, and CDP/in-process scenarios | `samples/skillroy-sample-web-host`, `-web-host-agent`, `-web-host-scenarios` (`-Pjcef`) |
