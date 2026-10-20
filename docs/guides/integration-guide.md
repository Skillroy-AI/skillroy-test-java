# Integration and overlay guide: a product layer on the base

**Status:** written 2026-10-12 against the base's first release, `0.1.0` (the tag `v0.1.0`; in no Maven repository, §4.2).
This guide is a map. Every rule it states is specified, with its reasons, in the contract it cites; where this guide and
a contract differ, the contract wins and this guide has a defect. Names in backticks are the base's own (types,
options, files, commands, primitives). Names in angle brackets (`<product>`, `<tenant>`, `<env>`) are placeholders for
the layer's own.

**Who it is for.** The people and the AI agents who build a private, application-specific layer on this base for a
large, real Swing application: one **product layer** per application, and **tenant overlays** for the deployment
variants of that application that particular customers run. Read [`README.md`](../../README.md) first, then this guide,
then each contract section as the guide points to it.

**Citations.** `design §n` is [`base-framework-design.md`](../design/base-framework-design.md); `P1 §n` to `P7 §n` are
[`phase1-contracts.md`](../design/phase1-contracts.md) to [`phase7-contracts.md`](../design/phase7-contracts.md);
`threat model §n` is [`threat-model.md`](../design/threat-model.md); `TODO` is [`docs/TODO.md`](../TODO.md), with the
name of its section; `Bn` is an entry of [`docs/DECISIONS.md`](../DECISIONS.md); "gap n" is a row of TODO's "Open SPI and
DSL gaps"; `Dn` is a decision inside a contract.

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

```
tenant overlay      one customer's deployment variant      private: overrides parts of the product layer
  over
product layer       one target application                 private: agent module, domain pack, hook catalogues,
                                                            scenarios, bindings, CI
  over
base                this repository                        Apache-2.0: agent, controller, SPIs, CLI, JUnit glue,
                                                            the sample lending desk that proves every SPI
```

The framework learns an application through **two halves with one id** (design glossary "domain pack"; design §2.6,
§3.6; P1 §4, §8.3):

| Half | Runs in | Java | Base type | What it knows about the application |
|---|---|---|---|---|
| agent module | the target JVM, in an isolated class loader | 8 (`--release 8`) | `AgentModule` (`skillroy-agent-api`) | its threads and readiness, its widgets and dialogs, its input seam, its data connection, its devices and embedded browsers, its secrets |
| domain pack | the controller | 25 (`--release 25`) | `DomainPack` (`skillroy-scenario`) | the verbs scenarios write, the sync topics, the fact roots, resolvers, verifiers and oracles, the fingerprint, the tables and masks, the hook catalogue, the constants |

The id is at once the module id, the pack id and the primitive namespace. In the sample it is `lending`: the module
`LendingModule` (id `lending`), the pack `lending`, the primitives `lending.*`, the MCP tools `lending_*` (P1 §9.2,
§9.3; P5 §9.3).

| The base owns | The product layer owns | A tenant overlay owns |
|---|---|---|
| the agent (bootstrap, core, Swing, data and web modules, optional hooks and Byteman modules), the wire protocol and its HMAC authentication, the controller, scenario compiler and executor, resolution engine, bindings, inference layer, MCP server, CLI, JUnit glue, telemetry export, vision bolt-on | one agent module per application, its domain pack, its hook catalogues, its scenarios with their committed plans and bindings, its credentials files (never committed), its CI and its leak check against the base | the overrides of one deployment variant: resolvers, constants, the SQL allow-list, the hook catalogue, its own environments' bindings |

The base proves every piece on its own sample, which is the worked example to read beside this guide:

| Piece | Sample module |
|---|---|
| the application (Java 8, a custom engine thread, custom-painted widgets, modal prompts, an embedded database) | `samples/skillroy-sample-lending-app` |
| its agent module | `samples/skillroy-sample-lending-agent` (`LendingModule`) |
| its domain pack | `samples/skillroy-sample-lending-pack` (`LendingPack`) |
| a tenant overlay (the "branch edition") | `samples/skillroy-sample-lending-pack-branch` (`LendingBranchOverlay`, `BranchPack`) |
| scenarios, plans, bindings, the end-to-end tests | `samples/skillroy-sample-lending-scenarios` |
| a host of embedded JCEF browsers, its agent module, pack and scenarios (Maven profile `jcef`) | `samples/skillroy-sample-web-host`, `-web-host-agent`, `-web-host-scenarios` |

---

## 2. Layering: how a tenant overlay resolves

The overlay mechanism is controller-side. It was specified and proven in Phase 6 on the sample's branch edition (P6 §11;
design §3.6; D144-D146; `BranchEndToEndIT`).

### 2.1 Resolution

A tenant overlay is a `DomainPackOverlay` (package `ai.skillroy.testjava.scenario.pack`): `baseId()` names the pack it
overlays, `id()` is its own id (`[a-z][a-z0-9-]*`), `version()` its version, and `overlay(DomainPack base)` returns the
pack to use instead, normally a subclass of `DelegatingDomainPack`, which forwards every member to the base and lets the
subclass override what it changes (P6 §11.1).

1. `DomainPacks.discover(loader)` loads every `DomainPack` service of the loader, then every `DomainPackOverlay`
   service of the same loader (`META-INF/services/ai.skillroy.testjava.scenario.pack.DomainPackOverlay`).
2. The overlays are sorted by `id()`. For each base pack, every overlay whose `baseId()` matches is applied in that
   order, each over the previous result (they chain).
3. The result is listed under the base's id: `DelegatingDomainPack.id()` is final and returns the base's id, so a
   scenario's `pack: <product>` selects the overlaid pack and **no scenario changes**.
4. `version()` becomes `<base version>+<overlay id>@<overlay version>`, which `plan.json` records as `packVersion`.
5. An overlay whose base is absent, one that returns null, and one that returns a pack with another id are refused
   with a `ScenarioException` before anything runs (the CLI's `Packs` exits 2).

Decision B26 Q4 fixes how a private layer consumes an overlay: **as a second jar** beside the pack it overlays. A
layer that wraps its pack in a class of its own moves that wrapper into `overlay(base)`, the same seam.

### 2.2 What an overlay may override

Everything `DomainPack` declares (§8.1) can be overridden in a `DelegatingDomainPack` subclass, except `id()`. Override
as little as possible: everything an overlay changes is code that must be kept in step with the product pack.

| Need of the variant | Override | How the sample does it (P6 §11.3) |
|---|---|---|
| a constant (a setting name, a threshold, a code) | `settings()`: `PackSettings` is an immutable string map; the product pack's code reads `ctx.settings().get("<pack>.<name>", default)` through `ResolveContext`, `VerifyContext`, `FingerprintContext` or `PreflightCheck.PreflightContext`, so the overlay changes the value and not the code | `settings()` sets `lending.maxReturnSetting = return_ceiling`; the base's system-limit resolver reads it |
| a resolver, verifier, oracle or cross-check whose logic differs | `resolution()`: `ResolutionCatalogue.builder(base.resolution()).replace(…).build()` (replace by id; the builder refuses an id that is absent) | `BranchStaffResolver` replaces the staff resolver with SQL that names the variant's column |
| the tables and columns model-written SQL may read | `sqlAllowLists()` | `BranchAllowList` lists the renamed column |
| hooked methods that differ | `hooks()`: §2.3 | the overlay's catalogue retargets `limit.applied` |
| verbs, sync policy, facts, prompt policy, pre-flight, recovery, fingerprint, stored tables, row masks, screen legend | the member itself | not overridden by the sample: the proof is that the product's surface stays untouched |

Two consequences of the base's design make overrides cheap and safe:

- **Write the product pack for overlays from the start.** The sample's pack exposes its query records, value types,
  resolver and verifier ids and SQL texts as public API, and its resolver classes take their SQL in a public
  constructor, so that an overlay subclasses instead of copying (P6 §11.3, "the pack's public surface"). A layer's
  product pack should do the same, and should read every variant-sensitive literal through `settings()` with a default.
- **The base stays restrictive by default; an overlay or an environment may relax it.** Two owner decisions have this
  form (B19 Q10 and Q14): what a pack puts into binding values is the pack's choice, and the shortest secret that is also
  masked inside longer text is the per-environment setting `masking.minLengthInText` in `credentials.yaml` (P2 §4).

### 2.3 The hook catalogue of an overlay

An overlay's `hooks()` answers `HookCatalogues.overlay(base.hooks(), …)` or `HookCatalogues.overlayResource(…)` over
the resource `META-INF/skillroy/hooks/<overlayId>.yaml`, which has the catalogue format of §9.2 with two differences:
`catalogue:` is the overlay's id, and its `hooks` entries **replace** the base's entries of the same id (every field)
or are added when the id is new; `disable: [id…]` turns base entries off. The result has its own hash. Because the
overlay's catalogue id differs from the product's, a target that already carries the product's catalogue makes the
installer stop with `ENVIRONMENT` and the remedy (`skillroy hooks remove`); launch a fresh target per variant (P6 §11.2,
§5.3 step 3). The sample's resource is `samples/skillroy-sample-lending-pack-branch/src/main/resources/META-INF/skillroy/hooks/lending-branch.yaml`.

### 2.4 Scenarios, plans and bindings of a variant

- The variant compiles **its own plans from the same YAML** with the overlaid pack; they differ at least in
  `packVersion`. The sample commits them under `variants/branch/plan/` and proves them up to date with
  `BranchPlansUpToDateTest` (P6 §11.4).
- Bindings are per environment (`<bindings root>/<env>/<scenario>.bindings.json`, P2 §9), so a variant's environments
  get their own ids and directories (the sample: `variants/branch/bindings/branch-a/`).
- The fingerprint should separate variants by itself: the sample's schema checksum already differs between editions, so
  bindings never cross them, and no fingerprint component was added for the overlay (adding one would have changed the
  hash of every committed bindings file of the standard edition) (P6 §11.3).
- Prove that the overlay is needed, not decorative: the sample runs scenario E with the base pack alone against the
  variant and expects it to fail (P6 §11.4, "the negative").

### 2.5 Packaging, loading and the class-loader caveat

Packs and overlays are found by `ServiceLoader` on one class loader: the CLI's `--pack` jars (`--pack <product-pack>.jar
--pack <tenant-overlay>.jar`), or the test class path of a Maven scenarios module, where the JUnit glue looks on the
thread's context class loader (`ScenarioTests`).

**The caveat, recorded as built (P6 §11.4, D189).** An overlay jar on a class path overlays **every** pack lookup in that
JVM. In the base's scenarios module the overlay is therefore a test dependency only for the reactor order: it is
excluded from the surefire and failsafe class paths (`classpathDependencyExcludes`), its file is handed to the tests as
the system property `skillroy.it.overlayJar`, and `BranchVariant` loads it in a class loader of its own over the test
class path (`samples/skillroy-sample-lending-scenarios/pom.xml`). For a layer this means:

- never put a tenant overlay on a class path that also runs the plain product's scenarios;
- give each variant its own test JVM: its own scenarios module (simplest), its own surefire or failsafe execution with
  its own class path, or a class loader of its own as `BranchVariant` does;
- in the CLI, pass the overlay's `--pack` jar only to the commands that run that variant.

### 2.6 The agent side has no overlay SPI

There is no agent-side counterpart of `DomainPackOverlay`. The agent half of a variant is handled with what exists:

- **configuration of the product's module**: an option read with `AgentContext.option(key)`, which answers the
  start-time option or else the live system property `skillroy.agent.<key>` (A20, P1 §2.4). The web host's agent module
  reads `webhost.mask` this way (P4 §9.2);
- **the application's own variant setting reported in `app.state`**, so that facts, pre-flight checks and a scenario's
  expectations can see which variant runs (the sample's `edition` key, P6 §11.3);
- **an additional agent module jar** in the home's `modules/` whose `requires()` names the product module. It can add
  implementations; it cannot reliably replace the product's, because several implementations of one SPI combine by that
  SPI's own rule: every `ReadinessSource` must report ready, `CaptureMask`s combine by union, the first
  `ThreadPolicyProvider` serves `ThreadPolicy.APP`, `RegionResolver`s are asked in registration order. Read the
  Javadoc of each SPI before relying on two implementations.

---

## 3. A suggested layout for the product layer

### 3.1 Modules

A layout that mirrors the base's (P1 §1) and keeps each module's Java level and class-path rule obvious:

```
<product>-layer/                          private repository; Maven reactor; DCO sign-off as in the base
  pom.xml                                 parent: pins one base version (a property) for every base artifact
  agent/
    skillroy-<product>-constants/         Java 8, no dependencies: ids, primitive and topic names, setting keys
    skillroy-<product>-agent/             Java 8: the AgentModule; the application's jars at provided scope
    skillroy-<product>-plugin/            Java 8, optional: what the application's plugin or include mechanism loads
                                          to start the agent in EMBEDDED mode (§5.3)
    skillroy-<product>-web/               Java 8, optional: a WebEngineAdapter for an engine the base does not cover
  controller/
    skillroy-<product>-pack/              Java 25: the DomainPack, its hook catalogues, its SQL allow-lists
    skillroy-<product>-scenarios/         Java 25: scenario YAML, committed plans, bindings/<env>/, the JUnit glue,
                                          the end-to-end tests that launch the application with the agent
  tenants/<tenant>/                       or a repository of its own per tenant (§3.4)
    skillroy-<product>-<tenant>-pack/     Java 25: the DomainPackOverlay (second jar, §2.5)
    skillroy-<product>-<tenant>-scenarios/  the variant's plans and bindings; its own test JVM (§2.5)
  ci/                                     the image and runner adapted from the base's ci/ (§12), the pipeline,
                                          the leak check against the base (§13)
```

| Module | Java | Depends on (scope) | Notes |
|---|---|---|---|
| constants | `--release 8` | nothing | Shared by both sides. On the agent side its classes must travel **inside** the agent module's jar (§3.3) |
| agent | `--release 8` (main and test code) | `skillroy-agent-api`, `skillroy-protocol` (`provided`); the application's jars (`provided`); `skillroy-agent-bootstrap` (`provided`, only if a class calls `EmbeddedAgent` directly) | Nothing is bundled: the agent API comes from the agent home's `lib/`, the application's classes from the host loader. No third-party classes (§7). The sample's POM: `samples/skillroy-sample-lending-agent/pom.xml` |
| plugin | `--release 8` | the application's plugin API (`provided`); nothing of the agent if it calls `EmbeddedAgent` by reflection | Its jar goes on the **application's** class path, not into `modules/` |
| web | `--release 8` | `skillroy-agent-api` (`provided`); for its tests the `skillroy-agent-web` test-jar (the contract kit, §10.5) | No vendor artifact in any scope (P4 §3) |
| pack | `--release 25` | `skillroy-scenario`, `skillroy-data`; the constants | The sample's POM: `samples/skillroy-sample-lending-pack/pom.xml` |
| scenarios | `--release 25` | the pack; `skillroy-junit`; `skillroy-agent-dist` as type `zip` at test scope with every transitive dependency excluded (§4.5); optionally `skillroy-cdp`, `skillroy-telemetry`, `skillroy-inference`, `skillroy-vision` at test scope | Pattern: `samples/skillroy-sample-lending-scenarios/pom.xml` |
| tenant pack | `--release 25` | the product pack, `skillroy-scenario` | Pattern: `samples/skillroy-sample-lending-pack-branch/pom.xml` |

### 3.2 Naming

The `ai.skillroy` groupId family is the owner's (B2, confirmed B33). Give the layer a sub-namespace of its own:

- groupId `ai.skillroy.<product>` (and `ai.skillroy.<product>.<tenant>` for tenant modules), artifact ids
  `skillroy-<product>-agent`, `skillroy-<product>-pack` and so on, mirroring the base's `skillroy-*` names;
- Java packages under `ai.skillroy.<product>`, **outside `ai.skillroy.testjava`**. The base's loaders treat its own
  prefixes specially: `ai.skillroy.testjava.protocol.` and `ai.skillroy.testjava.agent.api.` are parent-first in every
  module loader, and `ai.skillroy.testjava.agent.` and `ai.skillroy.testjava.protocol.` are on the hook engine's deny-list
  (P1 §2.2; design §2.6, "Hook policy"). A separate root keeps the layer clear of every such rule;
- the domain id (module, pack and primitive namespace): short, lower case, neutral, and stable, because it is in every
  scenario's `pack:` key, every committed plan and every MCP tool name. Primitive names follow
  `<namespace>.<verb>[.<sub>]` in lower camel segments (P1 §4, `PrimitiveSpec`); MCP replaces the dots (P5 §9.3);
- overlay ids `[a-z][a-z0-9-]*`, by convention `<product>-<tenant>` (the sample: `lending-branch`).

### 3.3 Rules that follow from the agent's class loaders

The agent home has one agent loader for `lib/` and **one child-first loader per module jar** (P1 §2.2). Three rules
follow for the agent module:

- **Code shared by two of your module jars must be inside each.** Two module jars do not see each other's classes. Embed
  the constants module's classes in the agent module's jar (for example with the shade plugin; this is the layer's own
  code, not third-party code).
- **Every jar in `modules/` must be an agent module.** A jar there without a
  `META-INF/services/ai.skillroy.testjava.agent.api.AgentModule` entry is a `FAILED` record in `agent.info`. When no
  `ProductionMarker` is in place and any module `FAILED`, the target never becomes ready and mutating calls answer
  `NOT_READY`, unless the option `allowFailedModules=true` is set (S14, P1 §5 "Modules"). Do not drop helper jars there.
- **`install` must not touch the application or AWT**, and must not wait for the event-dispatch thread or the thread
  that called the start: it runs before the application's `main` in `JAVAAGENT` mode (P1 §4, `AgentModule.install`).

### 3.4 Tenant overlays: modules or repositories

Keep a tenant's overlay in the product repository when the same team maintains both and the tenant's facts may be
shared with everyone who can read the product layer; give it a repository of its own when the tenant's names, schema or
hook targets must stay with fewer readers. Either way the overlay is a **second jar** that depends on the product pack
(B26 Q4), with its own scenarios module or test JVM (§2.5), its own environments in `credentials.yaml` and bindings, and
the product's leak check extended to the tenant's own names where a tenant repository must not leak into the product's.

---

## 4. Consuming the base

### 4.1 What to depend on

Coordinates are `ai.skillroy:<artifactId>:<version>` (parent `ai.skillroy:skillroy-test-java-parent`; no BOM). The ones
a layer uses:

| Artifact | Side | Used for |
|---|---|---|
| `skillroy-agent-api`, `skillroy-protocol` | agent (Java 8) | the SPIs of §6; `provided` scope |
| `skillroy-agent-bootstrap` | agent | `EmbeddedAgent`, only for a plugin class that links against it (§5.3) |
| `skillroy-agent-web` (its `test-jar`) | agent tests | the adapter contract kit, for a new `WebEngineAdapter` (§10.5) |
| `skillroy-agent-dist` (type `zip`) | build | the agent home (§4.5) |
| `skillroy-scenario`, `skillroy-data` | controller (Java 25) | the `DomainPack` SPI, resolution, data channels, credentials |
| `skillroy-controller` | controller | the client, attach, `TargetLauncher` (reached through `skillroy-scenario`) |
| `skillroy-junit` | scenarios | `ScenarioTests`, one dynamic test per scenario (§8.10) |
| `skillroy-cdp` | scenarios, optional | the CDP path for web verbs (§10.3) |
| `skillroy-inference`, `skillroy-mcp`, `skillroy-vision`, `skillroy-telemetry` | scenarios or tools, optional | the AI layer, the MCP server, the vision loop, OpenTelemetry export |

The CLI distribution (`bin/skillroy`, `bin/skillroy.bat`, `lib/`) is a build output of `controller/skillroy-cli`
(`target/skillroy-cli/` and `target/skillroy-cli.zip`) and is **not attached as a Maven artifact**; the agent home zip
is (`skillroy-agent-dist`, type `zip`, no classifier). 0.1.0 is released as the tag `v0.1.0` only: the owner chose on
2026-10-12 to publish neither the zips nor the jars, so both are built from the tag
([`release-notes/0.1.0.md`](../release-notes/0.1.0.md) §1).

### 4.2 Obtaining a release

`0.1.0` is in no Maven repository: build it from the tag `v0.1.0` and install or deploy it as §4.3 says. Once a
release is published, depend on it through the organisation's repository manager if there is one. Pin the version once
in the layer's parent POM. The CLI distribution and the agent home come from the same release.

### 4.3 Until a release is published: the `mvn install` trap and the safe ways

**Never `mvn install` in the base's working tree** (TODO "Environment notes"; README "Build"). The base builds partial
reactors with `mvn -o verify -pl <module> -am`; a module whose tests unpack the agent home takes the
`skillroy-agent-dist` zip from the reactor only when it declares it. An installed copy of the version the base's working tree
carries (on `main` a `-SNAPSHOT`) in the local Maven repository shadows the reactor where it does not: on 2026-10-07 a partial build of the CLI module took a
stale zip from there, unpacked jars older than the HMAC handshake into its test agent home, and failed with a 401 on
`/ping`. An install into the default `~/.m2` therefore breaks the base's own development on the same machine.

Safe ways for a layer to obtain the base before it is published:

1. **A separate clone or worktree of a release tag** (`v0.1.0`), never the working tree someone develops in, built with
   `mvn verify` and installed from there **into a dedicated local repository** that only the layer's builds use
   (`-Dmaven.repo.local=<dir>` for both the install and the layer's builds, or `<localRepository>` in a settings file
   the layer's builds name; it fills from the network or the organisation's mirror on first use). The base's working
   tree and its `~/.m2` stay untouched.
2. As (1), but **deployed to an internal repository** that the layer's builds resolve from; the layer's CI then needs
   no clone of the base.
3. If the default `~/.m2` must be used, give the installed build a version of its own in the clone first (for example
   with `mvn versions:set`), so that it can never be mistaken for the working tree's own version; delete it when a
   published release arrives.

Whichever way: record the base commit the layer builds against (in the layer's parent POM or its README), and take the
agent home zip, the CLI distribution and the base jars of the pack **from the same build**.

### 4.4 Keep versions aligned

- **One base version for everything**: the agent home the target loads, the CLI or the JUnit glue that drives it, and
  the `skillroy-scenario` the pack was compiled against. Mixing them is not a supported configuration.
- **The handshake checks the protocol, not your module.** Every connection begins with `/ping`, which carries the
  protocol version (`Protocol.VERSION`, `0.2`) and must report the same major on both sides (P1 §3, §7); a mismatch is
  refused. Nothing checks that the product's agent module and pack belong together: `agent.info` reports every module's
  `version()`, so a pack can check it in a `PreflightCheck` (a layer practice, not a base feature).
- **Bump `AgentModule.version()` whenever the module's primitives or SPIs change**, as the sample does
  (`LendingModule.VERSION`, P1 §9.2), and the pack's `version()` whenever a compiled plan would change; plans record
  `packVersion`, and a different version at run time is logged, not an error (P2 §8.3, Q13).
- **Plan versions move with the base**: each phase that changed the plan format raised `planVersion` (version 6 since
  Phase 7, P7 §8.2; **version 7 since Phase 8**, P8 §8.2, *(Phase 8, built 2026-10-19, EX)*, whose reader also refuses
  an unknown step or call key). After a base upgrade, recompile and commit the plans (`skillroy compile`, or the sample's
  `-Dskillroy.plans.update=true` pattern, TODO "Environment notes", Phase 7 build).

### 4.5 Assembling an agent home for the application

The agent home is the unpacked `skillroy-agent-dist` zip (P1 §2.1). The application's agent module joins it as a jar in
`modules/`; jar names in the home carry no version. The sample's scenarios module shows the whole assembly in its POM
(`maven-dependency-plugin` executions `unpack-agent-home` and `lending-module-into-agent-home`):

```
target/skillroy-agent/                    unpacked from ai.skillroy:skillroy-agent-dist:<version>:zip
  skillroy-agent-bootstrap.jar            the -javaagent and attach jar; on the host class path in EMBEDDED mode
  skillroy-agent-spy.jar                  appended to the bootstrap path by the hooks module only
  lib/        skillroy-protocol.jar skillroy-agent-api.jar skillroy-agent-core.jar
  modules/    skillroy-agent-swing.jar skillroy-agent-data.jar skillroy-agent-web.jar
              skillroy-<product>-agent.jar          <- the layer's module, copied in
  optional/   skillroy-agent-hooks.jar skillroy-agent-byteman.jar skillroy-agent-byteman-sys.jar
```

Alternatives to copying the module in: the agent option `modules=<jar>,…` (each jar must pass the shared jar rule
`JarTrust`: not writable by anyone but its owner, P1 §2.4), or `skillroy attach … --module <jar>` (§5.5). There is no
upload of a module over the channel (design §3.6 mentions `POST /modules`; it is not built).

---

## 5. Getting the agent into the target JVM

### 5.1 Three load modes, identical primitives

| Mode | How | `Instrumentation` (hooks, Byteman) | Use it for |
|---|---|---|---|
| `EMBEDDED` | the application's own plugin mechanism calls `EmbeddedAgent.start` | none: `hook.engine` is `UNAVAILABLE("no Instrumentation in EMBEDDED mode")`; self-attach is not built | suites without hooks where the launch command cannot change |
| `JAVAAGENT` | `-javaagent:<home>/skillroy-agent-bootstrap.jar[=<options>]`, or the same through `JAVA_TOOL_OPTIONS` | yes | CI and services; hooks; Java 21+ targets (no JEP 451 warning) |
| `ATTACH` | `skillroy attach <pid> --home <home>` | yes | ad-hoc work on a running target; not CI |

The handshake reports the mode in use; the capability matrix states every difference (design §1.3, §2.2; P1 §2.3, §7).
CI loads the agent at launch and starts the target once per suite, never attaching (design §6).

### 5.2 The enable flag and where options come from

The agent does nothing unless it is enabled, checked before any thread or port exists (design §2.2 step 7; B13):

- `JAVAAGENT`: only the system property `-Dskillroy.agent.enabled=true` or the environment variable
  `SKILLROY_AGENT_ENABLED=true`. Neither the `-javaagent` argument string nor the options file it names can enable it,
  because `JAVA_TOOL_OPTIONS` may hand them to JVMs that never asked for the agent.
- `EMBEDDED`: also `enabled=true` in the `Properties` passed to `EmbeddedAgent.start`.
- `ATTACH`: also `enabled=true` in the attach options (attaching is itself a deliberate act by a same-user process).

Options, lowest to highest precedence: defaults; system properties `skillroy.agent.<key>`; the options file named by
`t=` or `config=` (Java properties); the argument string `k=v;k=v` or the `EMBEDDED` `Properties` (P1 §2.4). Keep the
argument string short and put the rest in the options file: the attach protocol refuses an argument of 1025 bytes or
more. An options file is read only when it is a regular file of the current user that nobody else may write (and, when
it holds a `token`, read); a refusal starts nothing.

### 5.3 `EMBEDDED`: through the application's plugin mechanism

If the application loads plugins or includes of its own (a class named in a configuration file or a system property),
a small plugin class can start the agent with the application's own objects in hand (P1 §2.3; design §2.2):

- `EmbeddedAgent.start(Properties options, ClassLoader hostLoader, Object hostContext)` blocks until the agent serves or
  refused (bounded by the start timeout, 60 s); `EmbeddedAgent.startAsync(…)` with the same arguments returns at once and
  is the form for a hook that must return quickly or runs on the event-dispatch thread. Both are idempotent and
  re-entrant, never throw, and use JDK types only, so the plugin can call them by reflection
  (`Class.forName("ai.skillroy.testjava.agent.bootstrap.EmbeddedAgent").getMethod("start", Properties.class,
  ClassLoader.class, Object.class)`) and link without the bootstrap jar.
- `hostContext` reaches the module as `AgentContext.hostContext()`: hand it the application object the module needs,
  instead of letting the module find it through statics.
- `EmbeddedAgent.stop()` stops the agent this bootstrap started; the plugin should stop only what it started.
- The bootstrap jar must be on the host class path. Use the one **inside the agent home**: the option `home` defaults to
  the bootstrap jar's directory, which is how the agent finds `lib/` and `modules/`.
- The plugin never enables the agent itself (the sample's does not): leaving it configured is then harmless.

Worked example: `samples/skillroy-sample-lending-agent/src/main/java/ai/skillroy/testjava/samples/lending/agent/embedded/SkillroyLendingPlugin.java`,
started through the sample application's `lending.plugins` property (`LendingPlugin`). The sample keeps the plugin class
inside the agent module's jar; a layer may keep it in a module of its own (§3.1).

### 5.4 `JAVAAGENT`

`premain` keeps `Instrumentation` and starts the core on a thread `skillroy-start` while the JVM waits (bounded). When
the launch command cannot be edited, put the flag in `JAVA_TOOL_OPTIONS` and set `mainClassGuard=<main class or jar
path>` so that only the target JVM of a process tree starts the agent; the guard must match the whole launch target
(S17, P1 §2.4). A Java 25 target loaded this way prints no JEP 451 warning and shows the same capability matrix as a
Java 8 target (P6 §8). With the hooks module and a Java 25 target, one class-data-sharing warning line appears on the
target's standard error the first time the spy jar is appended (B26 Q14; `SECURITY.md`). `TargetLauncher` (in
`skillroy-controller`, builder methods `javaAgent(home, options)` and `viaJavaToolOptions(true)`) launches a target this
way from a test.

### 5.5 `ATTACH`

`skillroy attach <pid> --home <agent home> [--module <jar>]… [--option key=value]… [--discovery-dir <dir>]` loads the agent
into a running JVM through the JDK attach API and proves it with `/ping` (P1 §7; `AttachSession`). The controller writes
the token, nonce and options into an owner-only options file and passes only `t=<file>`. It gives a JVM younger than 5 s
that long first (the attach signal can end a JVM that is starting up) and maps Java 8's bare `0` reply. A JDK 21+
target prints the JEP 451 warning and will be refused by a future JDK unless launched with
`-XX:+EnableDynamicAgentLoading`: prefer `JAVAAGENT` there (`SECURITY.md`). Same OS user only; on Windows also the same
logon session, integrity level and bitness (§5.9). After attach, connect with `--pid <pid>` (or `--port` and
`--token-file`).

### 5.6 Options a layer sets

The full table is P1 §2.4. The ones a layer meets:

| Option | Default | What it does |
|---|---|---|
| `enabled` | `false` | see §5.2 |
| `home` | the bootstrap jar's directory | the agent home |
| `modules` | – | extra module jar paths, each checked by `JarTrust` |
| `optionalModules` | – | stems of jars under `optional/` to load: `skillroy-agent-hooks`, `skillroy-agent-byteman` (§9) |
| `discoveryDir` | `${java.io.tmpdir}/skillroy-<user>` (fallback `<user.home>/.skillroy/run` when another user owns that) | where the discovery file and the token file are written, owner-only (P1 §2.5) |
| `tokenFile` | `<discoveryDir>/skillroy-<pid>-<startMs>.token` | the shared secret's file; the token never travels (HMAC, H2) |
| `token` | generated (32 random bytes) | a supplied token must have at least 16 characters; generate it |
| `port` | `0` (ephemeral) | the loopback port; fix it per target instance when a remote CI step connects with `--port` |
| `readOnly` | `false` | refuses mutating and script primitives |
| `requireProductionMarker` | `false` | §5.7 |
| `allowFailedModules` | `false` | §3.3 |
| `mainClassGuard` | – | §5.4 |
| `readyTimeoutMs` | `120000` | how long readiness may take before `target.ready` is reported `UNAVAILABLE` (polling continues) |
| `redact` | – | a regular expression of further secret keys (the module's own `RedactionKeys` is better, §6.11) |
| `robot` | `false` | one half of the Robot opt-in (§11.6); never commit it |
| `byteman.jar` | – | the user-supplied Byteman jar (§9.6) |
| `hooks.*` | see P1 §2.4 | the hook engine's bounds (§9.3) |
| `web.jxbrowser.chainInjectJs` | off | §10.2 |

Unknown keys are kept and visible to modules (`AgentContext.options()`, `option(key)`): a layer may define its own,
prefixed with its id (`<product>.<name>`), as the web host does with `webhost.mask`.

### 5.7 Refusing production

A test agent enabled on a live installation is the risk `ProductionMarker` exists for (design §2.2 step 7; P1 §4, §5):

- A `ProductionMarker` returns non-null ("this looks like production because …") or throws, and the agent refuses to
  serve and shuts down, with the reason in `skillroy.agent.error`. It is evaluated once the target is ready, before any
  module's `onTargetReady`; until it passes, only read-only primitives are served.
- With no marker in place the agent serves and logs a warning. The sample has none (it is a sample); **a
  product layer must**: decide what identifies production for the application (a licence mode, a configuration flag, a
  host naming rule, the absence of a test-only setting) and make the marker cheap and non-blocking.
- Set `requireProductionMarker=true` in the layer's options file, so that the agent refuses to serve when no marker is
  in place at all, which is what happens when the layer's own module failed to load. An options file without a token
  may stay readable by others (P1 §2.4).
- `-XX:+DisableAttachMechanism` closes the same-user attach route on a JVM that must never be instrumented
  (`SECURITY.md`, "Attach is the platform's trust boundary").

### 5.8 Target Javas 8 to 25

The agent is Java 8 bytecode and runs on targets from Java 8 to 25 (B4); the gates are a Java 8 and a Java 25 target in
every load mode (design §6; Java 21 is no longer run as a gate). The layer's agent module is held to the same level:
`--release 8` for main and test code, so no `var`, records, `List.of`, `String.isBlank` and the like (P1 §1). Run the
agent module's own tests on a real Java 8 JVM as the base does (`-Djvm=<java 8>`, README "Build"); surefire reports
"Tests run: 0" for some configurations on Java 8, so read counts, not exit codes (TODO "Environment notes", Phase 6).

### 5.9 Windows

The Windows path is built where Linux can test it and **not verified on a Windows host** (README "Windows"; P6 §9;
threat model §8). What a layer must know:

- **`JAVAAGENT` is the path for services and CI**, with the agent loaded in the logged-on user's session. That load with
  the `SEMANTIC`, `ACTION`, `COMPONENT`, `DISPATCH` rungs and `PAINT` capture is Phase 6's open exit criterion: it waits
  for a Windows host (design §8, Phase 6 row; P6 §13.3).
- `ATTACH` needs the same user, logon session, integrity level and bitness; across sessions it is not supported and not
  detected (P1 §2.5 "Windows").
- Owner-only files use an ACL with one entry for the current user; on a Windows whose built-in account names are not
  English the checks fail closed, with the `icacls` remedy in `SECURITY.md`; FAT and exFAT volumes are refused.
- `bin\skillroy.bat` is the launcher; `ci\run-on-windows.cmd` runs the base's `mvn verify` with a Java 8 and a Java 25
  target from an interactive session (`JAVA_HOME` a JDK 25, `SKILLROY_JDK8_HOME` a JDK 8).
- Run outputs on Windows inherit the parent directory's access list (S144) and a directory junction in the output tree
  is followed (S142): give `--out` a directory under the user's profile (`SECURITY.md`).

A product layer that ships on Windows should plan its own verification run early and report the result to the base in
neutral terms (§13).

---

## 6. The agent-side SPIs

All live in `agent/skillroy-agent-api`: the module types in `ai.skillroy.testjava.agent.api`, the SPIs in
`ai.skillroy.testjava.agent.api.spi` (and `….spi.web`, `….spi.hook`). The contract is P1 §4, with the later additions
marked there; the design's table is design §2.6.

### 6.1 Writing an `AgentModule`

- `id()`, `version()`, `requires()` (module ids that must install first; an unknown id keeps the module from installing).
- `install(AgentContext)`: on the agent's start thread, possibly before the application exists. Add primitives,
  SPI implementations (through `ctx.spi()`, the `SpiRegistry`) and capabilities (`ctx.capabilities()`). Touch no
  application singleton and no AWT, wait for no thread, initialise no `java.util.logging`.
- `onTargetReady(AgentContext)`: once every `ReadinessSource` reports ready, on an agent thread. Wire listeners here (or,
  as the sample does, at the first quiescent point the readiness source finds, §6.3).
- `uninstall()`: reverse everything, never throw, never block for long. Keep the handles that the registration and
  declaration methods return and close them (A18; the sample's `LendingModule.uninstall`).
- Agent threads are daemon threads named `skillroy-*` with the host loader as context class loader (S34): a thread host
  code creates on them inherits both, so an SPI call must not lazily start host infrastructure that spawns threads
  (pools, timers); route such a call through `ThreadPolicy.APP` instead.
- Log through `ctx.log()` (`AgentLog`), never `System.out`.
- One service file: `META-INF/services/ai.skillroy.testjava.agent.api.AgentModule`.

### 6.2 The SPI map

| SPI | What it is for | When a layer needs it | Thread | Sample | Contract |
|---|---|---|---|---|---|
| `ReadinessSource` | "domain singletons may be touched now"; polled every 250 ms | always | agent worker; cheap, non-blocking | `LendingModule.Readiness` | P1 §4; design §2.2 step 3 |
| `ThreadPolicyProvider` | the application thread a domain call must run on (`ThreadPolicy.APP`), and the threads the agent must never block or run on; (Phase 8, built 2026-10-19, ET) `quiescentPoint()`: the point the application's idle callback drives, for `APP_QUIESCENT` | whenever domain state is confined to an application thread | any agent thread; thread-safe; `quiescentPoint()` cheap, non-blocking, the same instance each call | `LendingWorker` | P1 §4 (A19 `available()`); P8 §4.1 |
| `AppStateProvider` | the `app{…}` section of a snapshot and `app.state`: state name, prompt, accepted inputs | always | `ThreadPolicy.APP` | `LendingModule.AppState` | P1 §4; design §2.5 |
| `NameEnricher` | a stable name for a component whose accessible name is empty | image-only buttons, painted keys | EDT; fast | `LendingWidgets` | P1 §4 |
| `LocatorResolver` | locators in the application's vocabulary (`{action: "DUE"}`), and `describe` for the hit-test | custom widgets without stable names | EDT | `LendingWidgets` | P1 §4; P7 §3.7 |
| `ActionFirer` | the `ACTION` rung: fire a custom widget's action without focus | widgets that ignore `doClick()` unfocused | EDT | `LendingWidgets` | P1 §4; design §3.7 |
| `DialogTextExtractor` | title, text and buttons of a custom-painted dialog | dialogs the generic reader cannot read | EDT | `LendingWidgets` | P1 §4 |
| `DialogLocator` | dialogs drawn inside another window (glass pane, internal frame); whether they block input | in-window dialogs | EDT | – (P1 §4, A27) | P1 §4 |
| `RegionResolver` | stable region ids inside a custom-painted component (a key pad's keys) | vision steps and pointer steps on painted surfaces | EDT | `LendingRegions` | P7 §3.7 |
| `CaptureMask` | which components show a secret: blanked in captures, text withheld from every reader | every component that can show a secret, and every control that feeds one | EDT; fast, lock-free | `LendingCaptureMask` | P7 §3.6 |
| `InputVeto` | the components the agent must never deliver input to, on any route, `force` included; readers flag them `vetoed` | every control whose action ends the JVM or cannot be undone in a test (Phase 8, built 2026-10-19, EA) | EDT; fast, lock-free; never changes the tree | `LendingInputVeto` | P8 §3.2 |
| `MaskPolicy` | masks values `data.query` returns, `CAPTURE` hook values and Byteman bindings, and (Phase 8) every event payload at publish, `event:<topic>` | personal or secret columns and event fields | agent workers, the hooked thread, or the thread that publishes an event; never blocks, never waits on the EDT | `PasswordMask` | P1 §4; P6 §4.3; P8 §5.1 |
| `RedactionKeys` | the application's own secret-looking keys in JVM arguments, properties, options | always worth one | any; thread-safe | `LendingSecrets` | P1 §4 (A21) |
| `DataSourceProvider` | an in-process, credential-free read channel (`data.query`); (Phase 8, built 2026-10-19, ET) the five-argument `query()` with a `QueryControl`, `enforcesTimeout()`, `lane()`, `columns(table)` | resolvers and stored-row checks against the application's own data | agent workers, or the source's lane; `lane()`, `enforcesTimeout()`, `columns()` non-blocking | `LendingDataSource`; `LendingRowsSource` (positional, on a lane) | P1 §4, §6; P8 §4.2-§4.4 |
| `DeviceAdapter` | peripherals as snapshot nodes and `device.*` actions | printers, scanners, readers, displays | `state`/`act` under `APP` | `PrinterDevice` | P1 §4; P3 §3.4 |
| `ProductionMarker` | refuse to serve on production | always (§5.7) | agent worker, once | – (core tests: `ProductionGateTest`) | P1 §4, §5 |
| `BrowserRegistryAdapter` | seeds for embedded browsers the Swing tree cannot see | cached, hidden or view-less browsers | agent workers; cheap | `WebHostModule` | P4 §3, §9.2 |
| `BrowserMask` | (Phase 8) names (TRUE) or releases (FALSE) a browser by its seed tag or vendor object | a browser that shows a secret without a component a `CaptureMask` could name, or a pre-loaded view-less page a scenario must read while masks are registered | agent workers; fast, never calls the vendor object | `DetachedRelease`, `BackTagMask` (web host) | P4 §3 (A79); P8 §5.2 |
| `WebEngineAdapter` | one per browser technology | an engine other than JCEF or JxBrowser | agent workers | built-ins `jcef`, `jxbrowser` | P4 §3, §4.5 |
| `SurfaceSource` | non-Swing areas of the tree, for captures and hit-tests | rarely (the web module supplies one) | EDT | web module | P7 §3.7 |
| `HookEngine` | hooks from Java instead of a catalogue | rarely (§9.5) | agent threads only | – | P6 §3.6 |
| `ClassDeny` *(Phase 8, built 2026-10-19, EH)* | refuse a class by name before any module changes its bytecode (§9.3) | a hot path, a licensed or timing-sensitive class | agent workers, under the engine's or the Byteman module's lock; cheap, never blocks | `LendingClassDeny` (behind the agent option `lending.hooks.deny`) | P8 §6.5 |

`SyncPolicy` is **not** an agent SPI: it is the pack's (§8.2). The module's part is to publish the topics it names.

### 6.3 Readiness and the quiescent point

Touching application singletons too early can create stray instances or deadlock start-up, so `install` only declares
and `onTargetReady` waits for every `ReadinessSource` (design §2.2 steps 3 and 4). After `readyTimeoutMs` the agent
reports `target.ready` `UNAVAILABLE` once and keeps polling.

Many applications allow a listener to be added only at a quiescent point, for example while their engine thread waits
for input, because their listener lists are not thread-safe. The sample shows the full pattern (P1 §9.2, "When the
engine listener is attached"; `ListenerSlot`):

- the readiness source attaches the listener at the first poll that finds the engine waiting for input, so the module is
  ready exactly when its listener is in place;
- an application that sits in a **modal question** has no quiescent point until somebody answers, and only a ready
  agent may answer: the module reports ready without its listener, its input primitive's capability says
  `UNAVAILABLE` with a reason naming the prompt, and a watcher thread attaches the listener once the question is
  answered, then publishes one ready event so that a waiting controller has its event;
- detaching makes the listener inert at once and removes it at the next quiescent point (bounded), under a JVM-wide
  monitor shared by every class-loader copy of the module, because a disabled agent and its successor can meet.

**Running a task at the idle point (Phase 8, built 2026-10-19, ET; P8 §4.1).** When state is confined to the
application's own thread and is consistent only between inputs (a pending quantity, a sub-step), a primitive declares
`ThreadPolicy.APP_QUIESCENT` with a budget (`PrimitiveSpec.Builder.quiescentBudgetMs`, 1 to 1000 ms, default 50). The
provider creates one `QuiescentPoint(allowanceMs, capacity)`, returns it from `quiescentPoint()`, calls
`runOne(availableMs)` from the application's idle callback (an application listener, as the sample's
`EngineEvents.idle`, or the `EventSink` of an `OBSERVE` hook on the idle method) and closes it in `uninstall`; the core
closes it too on `agent.disable` and close, before any module's `uninstall`. A task runs exactly once or never: at the
FIFO head, only when its budget fits both what the application says is free and what its caller has left; it is never
interrupted, an overrun is counted, and a primitive whose task ran past twice its budget answers
`CAPABILITY_UNAVAILABLE` until `agent.capabilities {reprobe: true}`. The thread rules for a quiescent task: it runs on
the application's thread (not the EDT), touches no AWT, does not block, takes no application lock the idle point does
not already hold, and never waits for agent work (`ThreadBroker.call*` and `QuiescentPoint.await` are refused
`FORBIDDEN_THREAD`; `EventBus.await` is forbidden and not checked); `AgentLog` and `EventBus.publish` are fine; its
result is JSON-able like any primitive's. The sample: `lending.engine.view` (budget 20 ms) reads `Engine.idleView()`.
A layer that offers no point makes such a primitive `CAPABILITY_UNAVAILABLE`; it never falls back to another thread.

### 6.4 The thread rule

`ThreadPolicy` (P1 §4) says where each primitive runs: `CALLER`, `EDT_SYNC` (a bounded `invokeAndWait`, for reads),
`EDT_ASYNC` (`invokeLater`, returns once posted, for actions, because an action may open a modal dialog), `WORKER`, or
`APP` (the first `ThreadPolicyProvider`'s thread). `ThreadBroker.call` applies the bound and throws
`RpcException(TIMEOUT)` on expiry and `RpcException(FORBIDDEN_THREAD)` on a thread the provider forbids.

For the provider:

- `isForbidden(thread)` names every thread the agent must never block or run work on: the application's engine or
  state-machine thread, a thread that holds application locks, a device thread. It must be cheap.
- `callOnAppThread(task, timeoutMs)` runs a domain call where the application allows one and waits at most the bound.
  The sample runs domain calls on one daemon worker of its own (`skillroy-lending-worker`) because the application
  publishes immutable snapshots any thread may read; an application whose state may be read only on its own thread
  needs a provider that posts to that thread and waits with the bound, or (Phase 8, built 2026-10-19, ET) a
  `QuiescentPoint` for `APP_QUIESCENT` (§6.3).
- `available()` answers false while the application thread does not exist yet: read-only `APP` primitives then run on a
  worker and mutating ones answer `NOT_READY` (A19); an `APP_QUIESCENT` primitive answers `NOT_READY` either way.
- One `APP` lane: everything under `APP` queues behind the provider's thread, so keep `APP` calls short. **Corrected
  (Phase 8, built 2026-10-19, ET; P8 §4.8 defect 3):** `data.query` itself runs on `WORKER`, not `APP` (P1 §6); the real
  hazard is a provider that hops to `APP` itself for a slow read. Never hop to `APP` for a slow read: declare a lane
  (`ThreadBroker.declareLane(name, threads, queue)`, by convention `<module id>.<word>`, 1 to 4 threads and a queue of
  0 to 16, at most 8 lanes per agent, retired with the module) and name it in the source's `lane()`, or run a
  primitive there with `PrimitiveSpec.Builder.lane(name)` (`ThreadPolicy.LANE`). A lane whose every thread is held by a
  task whose caller gave up answers `BUSY "lane stuck"` at once; `APP` stays unbounded (gap 136).

### 6.5 The input seam and the sync topics

The fastest and most dependable input path is the application's own: the `SEMANTIC` rung is a primitive of the
module (`<product>.input` by convention; `lending.input` in the sample) that hands an input to the application the way
its UI would and returns the application's acknowledgement (design §3.7; P1 §9.2). There is no SPI for it: it is a
primitive the module adds (§6.14).

- **Chain, never replace.** If the application offers its own test-input or automation seam (an input source slot, a
  scripted-input interface, a prompt answerer, a single-slot callback), the module chains to the previous occupant and
  restores it on detach (design §2.2 step 4). The agent must leave the host as it found it, and another tool may own
  that slot. The JxBrowser adapter's opt-in to chain `InjectJsCallback` follows the same rule (P4 §3, rule 2).
- **Pre-check and reject explicitly.** Check whether the current state accepts the input and answer the error
  `INPUT_REJECTED` with the state and the valid inputs instead of queueing an input the application would drop
  silently (P1 §9.2; A33).
- **Publish the topics the pack's `SyncPolicy` names** from the seam itself: an acknowledgement topic when an input was
  taken (`input.acked` in the sample) and a ready topic when the application waits for input again
  (`input.requested`), plus `prompt.waiting` when it asks a question. "Ready" is then a fact the application states,
  not a guess from quiet time (design §2.5). Generic topic names are in `Topics` (P1 §3); a module adds its own.
- An input typed or clicked in the window produces no acknowledgement through the seam; UI steps therefore wait for the
  ready topic, or name the event they wait for (`await:` on `click`, `type`, `press`; A32).

*(Phase 8, built 2026-10-19, ES; P8 §7.1, §7.2, §7.4.)* **What the input primitive answers.** Adopt the semantic-input
result convention (`InputResult`): `accepted`, an `inputId` the module mints (opaque, `[A-Za-z0-9._:-]{1,64}`, unique
within the agent's life; echo one the caller supplies), `consumed: CONSUMED` (the application consumed the input
before the call returned) or `QUEUED` (it took it and has not consumed it yet), `taken` with the value the application
consumed when it differs from the one sent (never for a secret parameter, `Invocation.secretParams()`), `answeredBy`
when another question consumed it. Report the caveats as warnings (`inv.warn`, §6.14): `VALUE_COERCED` with `taken`,
`VALUE_DROPPED`, `ANSWERED_NESTED` with `answeredBy`, or a code of your pack `<packId>.<NAME>` that the pack declares;
the executor derives `INPUT_QUEUED` itself. `INPUT_REJECTED` keeps meaning "refused before it was handed over", with
`data.reason` `notAccepted` or `inboxFull`; a caveat is never an error and an error never a warning. **Acknowledge by
input**: put the `inputId` in the acknowledgement's payload (never the value), publish an acknowledgement that comes
after the call from the application's own callback (keep the input in a bounded pending table, at most 256, cleared
on detach; exactly one acknowledgement per input; the four-argument `publish` of §6.13 with the call's correlation id),
and let the pack set `SyncPolicy.ackByInputId` (§8.2). For an acknowledgement that is not the pack's ack topic, or a pack
that cannot opt in, the **EVENT pattern**: the verb mints a deterministic literal at compile time (`"L" +
ctx.sourceLine() + "." + <position>`), passes it as the primitive's `inputId` parameter and awaits `EVENT` on the
acknowledgement topic with `match: {inputId: <the literal>}`; only literals or `$slot` values can be matched.

### 6.6 Application state for the snapshot

`AppStateProvider.appState()` returns a JSON-able map: cheap, no side effects, under `APP`. Put there what scenarios
and people need to see at a glance: the state name, the open prompt, whether a transaction is open, the variant
(§2.6), the business date, and diagnostics such as the module's listener count (the sample's `lending` section, P1 §9.2).
Values that change from launch to launch (ports, dates) must stay out of the fingerprint (§8.5).

### 6.7 Custom widgets and dialogs

The snapshot names a Swing node by the first non-empty of `NameEnricher`, the accessible name and `Component.getName()`
(design §2.5). For each custom widget class:

- give it a name (`NameEnricher`) and, where the application has a vocabulary for it, a locator strategy
  (`LocatorResolver`, with `describe` so that the hit-test proposes the domain locator first, P7 §3.7);
- give it an `ActionFirer` when it ignores `doClick()` without focus; the `fired` of a click means "posted", so observe
  the effect, not the call's answer;
- read custom dialogs with a `DialogTextExtractor`; report in-window dialogs with a `DialogLocator`, so that
  `swing.dialogs` lists them, `swing.dismiss` presses their buttons, and input aimed past them answers `MODAL_OPEN`;
- give a custom-painted surface a `RegionResolver` if vision or pointer steps will act on it.

Prefer a locator in the application's vocabulary, then an explicitly set name, then name, text and class, with the
child-index `path` last (design §3.7, "Locators"). The click rungs (`ACTION` by default, `COMPONENT` with window
activation on request, `DISPATCH`) are the base's; the scenario key `ui` chooses (B12, A28, A29).

**Input vetoes, the visible area, heavyweight containers, AWT controls (Phase 8, built 2026-10-19, EA; P8 §3).**

- Register an `InputVeto` for every control whose action ends the JVM or cannot be undone in a test (§7 rule 4).
  `refuse(component, route)` answers null to allow, else one line why (cut at 200 characters); it is asked on the EDT
  for the component and each ancestor up to its window, so one answer for a panel or a window vetoes everything in it.
  A veto that discriminates routes **must answer the route `ANY` with a refusal whenever it refuses any route**: the
  readers ask `ANY` to flag `vetoed: true`, so that the recorder and a model are never offered the component; a veto
  that answers `ANY` with null is still enforced, but invisible until it acts. Keep it fast and lock-free: it runs
  inside the agent's bounded EDT read, and a throwing veto refuses (fail closed, logged once).
- Key bindings, mnemonics and menu accelerators are not followed: a key delivered to one component may fire an action
  another component owns (only the delivery target's chain and ENTER's default button are asked), so **veto the
  window, or the bound control**. **A veto on a window or panel does not cover `web.*` input into a browser inside
  it**; a layer that must keep page input out vetoes nothing more for that in Phase 8 (gap 126).
- A veto that depends on application state and refuses inside the posted task leaves the step `fired: true` (the
  call already answered "posted"; the refusal is logged and counted in the Swing module's `vetoWithheld`, which
  `skillroy info` shows): the step's `sync` or an expectation on the effect is what fails it.
- "On screen" is more than `isShowing()`: a component whose effective area is empty (a zero-width pane of a frame set,
  a component clipped away by its parent, one not yet laid out) is *collapsed*: locators skip it, snapshots leave it
  out, a click on it is `INVALID_PARAMS collapsed`, and an in-window dialog root without an area blocks nothing (report
  the component that has the area). A component scrolled out of a `JViewport` stays on screen; a custom scroller that
  moves its child without a viewport makes a scrolled-out child collapsed (gap 130).
- The routed pointer reaches components inside a heavyweight container (an application drawn inside one AWT `Panel`):
  its events are sourced from the innermost heavyweight at the point that no `SurfaceSource` claims.
- AWT `Button` and `Checkbox` are fired semantically on the `ACTION` and `COMPONENT` rungs (one `ActionEvent` or
  `ItemEvent`); `DISPATCH` stays a mouse click, whose effect on an AWT control depends on the toolkit's peer (the X
  peers act on it, Windows' are not known to). Other AWT controls have no semantic rung (gap 129).

### 6.8 Embedded browsers the Swing tree cannot see

The web module finds browsers by scanning the Swing tree and from the seeds every `BrowserRegistryAdapter` returns
(`BrowserSeed.of(object)` or `BrowserSeed.of(object, tag)`). Hand in browsers that are cached, hidden or have no view in a
window; a tag gives scenarios a stable selection (`browser: {tag: …}`). `seeds()` returns objects the application
already holds: it must never create, initialise or configure a browser or an engine, and must not block (P4 §3, §9.2;
`WebHostModule`). Section 10 has the rest.

(Phase 8, built 2026-10-19, EW; P8 §5.2.) While a layer registers any `CaptureMask` or `BrowserMask`, a browser the
adapter reports no Swing component for (a pre-loaded or never-shown page) is **masked by default**: listed with no URL or
title, its reads refused, its events without page data. A layer that masks anything and must read such a page registers
a `BrowserMask` answering `FALSE` for it (the release form: `tag == "detached" ? Boolean.FALSE : null`); one that must
mask a browser no `CaptureMask` can name answers `TRUE` by tag or by vendor object (compared by identity, never called).
A `FALSE` never overrides a `CaptureMask` that names the browser's component once it has one.

### 6.9 The in-process data channel

`DataSourceProvider` gives `data.query` (one guarded `SELECT` or `WITH`, at most 200 rows, a timeout) a connection to the
application's own data without credentials leaving the target (P1 §4, §6; design §3.9). Implement one form:

- `open()`: a JDBC `Connection` the agent sets read-only, uses for one query in a transaction it rolls back itself, and
  closes; it may come from the application's pool, whose auto-commit and read-only flags the agent restores;
- `query(sql, params, maxRows, timeoutMs)`: for a data layer with its own statement API (columns may be unnamed).

Keep `exposesErrorText()` false unless the driver's messages cannot echo values. Add a `MaskPolicy` for personal
columns: it runs on every value before it leaves the JVM. The statement guard is a safety net, not the boundary: the
connection's account should be read-only (`SECURITY.md`). Not provided: a "prepared read" that runs a procedure and a
select on one session (gap 13).

**(Phase 8, built 2026-10-19, ET; P8 §4.2-§4.4.)** `data.query` hands the provider's work to the source's lane
(`lane()`) or a nested worker task and waits the call's remaining time less 250 ms. Override the five-argument
`query(sql, params, maxRows, timeoutMs, QueryControl control)` to be stoppable: register the means with
`control.onCancel(action)` (a statement's `cancel`, the application's own abort; at most 4, run once, off the
dispatching worker, 2 s each), read `control.remainingMs()` and `control.cancelled()`. The JDBC form is cancelled by
the agent itself while its statement is open. `enforcesTimeout()` says the read ends within its `timeoutMs` + 1 s
whatever the source does. On expiry the module answers `TIMEOUT {source, lane, waitedMs, cancelled, cancelPending?,
stillRunning, enforcesTimeout}`; without a lane a read that outlives its call makes the next read of that source
`BUSY` until it ends, so one stuck source blocks only itself. `open()` still has no timeout: bound any pool wait in the
provider. Columns the provider leaves unnamed take the labels of the statement's select list (the alias, or a bare
column's name), and for `SELECT * FROM <table>` the provider's `columns(table)` (the table's columns in declared order,
answering the same relation `query()` reads), so a positional source is read by name and its zero-row answers keep
their width; the controller's allow-list check (D100) needs `columns(table)` from such a source. **Version skew:** a
provider that overrides only the five-argument form needs a Phase 8 agent (on a 0.1.0 one the four-argument default
answers null and the read falls to `open()`); mixed versions are unsupported (§4.4).

### 6.10 Devices

A `DeviceAdapter` presents each peripheral as a snapshot node with `state()` and named `act(action, args)` actions,
reached through `device.list`, `device.state` and `device.act` and the base fact root `devices` (P3 §3.4). Input devices
inject where the real device hands data to the application; output devices report what the application sent, as events,
and as rendered text where a simulator gives it. Prefer the application's own device simulators in CI (design §3.9). The
sample's printer: `PrinterDevice`, with the slips as a fact root.

*(Phase 8, built 2026-10-19, ES; P8 §7.6.)* An action with a physical path and a shortcut declares its ladder,
`DeviceAdapter.rungs(action)` (best first; `PHYSICAL`, `SEMANTIC`, or names of your own matching `[A-Z][A-Z0-9_]{0,31}`):
`device.list` and the `devices` root show it. Such an action reports the rung that acted (`RungResult`: `rung`,
`requestedRung`, `fidelity`, `reason`), refuses to act below a `minRung` argument (`CAPABILITY_UNAVAILABLE {rung,
minRung, capability?}`, before anything reaches the application) and echoes `minRung` in its result when it received
one: the executor fails closed without the echo. The sample's scanner: `ScannerDevice`, `PHYSICAL` through the desk's
scanner driver, `SEMANTIC` through its inbox, with the capability `lending.scanner.physical`.

### 6.11 Masking and redaction

Four mechanisms, each for one route out of the JVM:

| Route | Mechanism | Rule |
|---|---|---|
| rows from `data.query`, values a `CAPTURE` hook copies, Byteman bindings | `MaskPolicy` | thread-safe, cheap, never takes an application lock; a throwing policy redacts |
| pixels and text of a component (captures, snapshot, hit-test, marks, dialog reader) | `CaptureMask` | name **every** component that can show a secret, and every control that feeds one (the sample masks its number pad while the entry field is masked); a password field is masked without one; a throwing mask masks |
| secret-looking keys in JVM arguments, system properties, agent options | `RedactionKeys` | the application's own keys (`lending.pin` in the sample); a throwing provider makes every key secret |
| rows read over the controller-side JDBC channel | `DomainPack.rowMasks()` | the controller-side twin of `MaskPolicy` (§8.5) |
| event payloads (Phase 8, P8 §5.1) | `MaskPolicy` with `sourceId` `event:<topic>` and the dotted column (`detail.lines.name`; a list element adds no segment); the key rule always | mask before you publish; the ring pass is the backstop, and the pass is what `evt.query`, waits and run outputs see |

**Migration (Phase 8).** A `MaskPolicy` written for rows that ignores `sourceId` now also masks event fields whose key
equals a column it masks: check `sourceId.startsWith("event:")` (or the exact data source id) in each rule. Do not mask
a field a wait or the executor matches on (a prompt's `id`, an acknowledgement's `inputId`, `web.tap`'s `name`). A
browser without a view is masked while any mask is registered (§6.8); release it with a `BrowserMask`.

What masking cannot do is in `SECURITY.md` ("Redaction hides a rectangle, not a value"; "A page's pixels are masked as a
whole or not at all"): a mask blanks a rectangle, the layout stays visible, and a component no mask names reaches a
model as rendered. An application's own Paste control is an ordinary click: mask it (threat model §7).

**Parity of a name-keyed `MaskPolicy` (Phase 8, built 2026-10-19, ET; P8 §4.4).** A row policy keyed by column name
sees the label the statement gives: a caller who renames a column (`password AS p`) defeats it on a JDBC source, and
on a positional source too now that its columns take the statement's labels, no weaker. Cover every source id the
module registers (the sample's `PasswordMask` covers `lending` and `lending-report`).

### 6.12 Capabilities and probes

Declare a capability for everything a scenario may depend on: `ctx.capabilities().declare(id, probe)` with a bounded
`Probe` that runs after the target is ready and again on `agent.capabilities {reprobe}`, or `set(…)` for states known
without probing (P1 §4). States are `OK`, `DEGRADED(reason)` and `UNAVAILABLE(reason)`; a primitive that names a
capability fails fast with `CAPABILITY_UNAVAILABLE`. Scenarios use the ids in `requires:` (missing means
`PRECONDITION_FAILED`) and in guards (§8.8). The sample declares one capability per primitive, plus `lending.sql` and
`lending.loan.authorisations`, which guarded expectations point at. Keep the handles to withdraw them in `uninstall()`
(A18). `skillroy caps` shows the matrix; each run archives it as `capabilities.json` (P6 §6.4).

*(Phase 8, built 2026-10-19, EP; P8 §9.1, §9.4.)* A fact only the environment holds (a setting row, a table's
contents) has a second source: the **pack** evaluates it at run start, `DomainPack.capabilities()` (§8.1), reading
through the run's data channel, overlay-aware through `settings()`, honoured in `replay`, costing no agent change and no
capability golden. Application configuration a scenario depends on is a capability either way: the decision and the
choice between the two sources are in §8.8.

### 6.13 Events

`ctx.events().publish(topic, source, payload)` may be called from any thread, the EDT and application threads
included, and never blocks; the bus is a bounded ring with increasing sequence numbers, read by `sync.await`,
`evt.query` and the SSE stream (P1 §4, §5). Publish with your module id as `source` and small payloads (the ring is also
bounded by characters, `eventRingChars`). A call's result carries the events published on its thread during the call and
its `seq.before`, so waiting "since `seq.before`" never misses a consequence of the call (P1 §3). Event sinks run on
the publishing thread and must not block. (Phase 8, P8 §5.1) Every payload is masked before it is stored: a value under a
secret-looking key becomes `<redacted>`, and the registered `MaskPolicy`s see every scalar as `event:<topic>`; mask
personal values before you publish all the same, and prove it with `skillroy-junit`'s `OutputScan` (`Scope.EVENTS`)
over a run.

*(Phase 8, built 2026-10-19, ES; P8 §7.4.)* An event that belongs to an earlier call but is published later, from the
application's own callback (a late acknowledgement of an input), uses `publish(topic, source, payload, correlationId)`
with the call's `Invocation.correlationId()` kept for it, so that telemetry links it to that call; it never blocks.

*(Phase 8, built 2026-10-19, EX; P8 §8.6.)* The base fact root `events` reads the ring for the run's window, so a
scenario can count your events, compare against a count captured earlier and assert that one never happened
(`events[*topic=…][*payload.source=…]`, `{size: 0}`). Publish small, structured payloads with the fields a scenario
filters on at the top level or one level down (a list for membership, `{has: x}`); a chatty module that publishes more
than `eventRing` events in a run makes every such expectation `ERROR` (the root refuses rather than under-counts), and
a JSON string in a payload is read with `[json]` at a cost.

### 6.14 Primitives of the module's own

`Primitives.of(PrimitiveSpec.builder("<product>.<verb>")…build(), body)` and `ctx.primitives()`. A spec declares its
parameters, its `ThreadPolicy`, its timeout (default 10 s), its capability, and whether it is `mutating`, `destructive`
(needs `confirm`) or `script` (evaluates a caller's expression) (P1 §4). Read-only mode refuses mutating and script
primitives. Typical set, after the sample: an input seam, a state read, the current transaction, the open prompt and an
answer to it, a decision-code check for verifiers (`lending.access.check`), dry runs of the application's own rules for
cross-checks (`lending.waiver.evaluate`). `skillroy schema` lists what a target offers; `skillroy call <primitive>`
calls one by hand.

*(Phase 8, built 2026-10-19, ES; P8 §7.1, §7.5.)* **Warnings.** `inv.warn(code, message, data)` reports a caveat of a
call that did what it was asked; it travels in the result's envelope, never in the value. A code is a base code or
`<packId>.<NAME>` your pack declares; a malformed one throws to your primitive (a module bug). At most sixteen per
call; never put a secret value in the message or the data (the dispatcher redacts the call's secret parameters, the
executor's secret-echo check fails a result that still carries one). **Composites.** Logic that depends on which
question the application asks next belongs in **one primitive that owns its nested questions** (plans stay linear): it
is `mutating`, bounded (every wait within `inv.remainingMs()` less a margin, never on the EDT or a forbidden thread),
takes every nested answer as a parameter (a secret one through the credential join), never answers a question it does
not own (it returns and leaves the question to the step's prompt check), and reports with `CompositeResult`: `composite
{branch, answered: [{question: {topic, id?, text?, seq?}, by: <parameter name>, choice?, inputId?}]}`, plus `inputId`,
`inputIds` and `consumed`. List the `seq` of every prompt event you answered: the executor expects exactly those that
fall inside the call's window. Check a result with `CompositeResult.problems` in your module tests. The sample:
`lending.signOn`, `lending.scanChecked` (`LendingComposites`).

---

## 7. Safety rules a layer must keep

The base's rules bind a layer's code as much as the base's, because the module runs inside the host and the pack's code
runs in the controller with the token (threat model §3: the pack is reviewed code, and a wrong pack is a defect the
framework must survive without harming the host).

1. **Never throw into, block or slow the host.** Every wait is bounded; actions on the EDT are posted (`invokeLater`);
   reads are a bounded `invokeAndWait`; a thread the application forbids is never used; nothing in `install` touches
   the application or AWT (`CLAUDE.md`; design §2.2, §3.7 "EDT discipline"; P1 §4).
2. **No sleeps.** Wait on events (`sync.await`, the sync topics) and on `swing.settle`, in primitives, verbs and recovery
   alike (`CLAUDE.md`; the sample's `recover` is bounded and waits for the application's own event after every action,
   P1 §9.3).
3. **No third-party classes inside the target JVM.** The agent module compiles against the application at `provided`
   scope and bundles nothing else; the base's one carve-out (shaded Byte Buddy in the optional hooks module) is the
   base's (B4, B26 Q1; `CLAUDE.md`).
4. **Never route an action or an assertion through application code that can end the JVM** (an application test API's
   shutdown or restart, an exit on error): no primitive calls application code known to end the JVM (design §2.2 step
   7). Recovery returns the application to idle; it does not restart it (design §3.6, `recover`). **(Phase 8, built
   2026-10-19, EA; P8 §3.2)** Register an `InputVeto` for every such control, so that no route of the base (a click on
   any rung, a key, typing, the pointer, a dialog's dismissal) reaches it, `force` included; key bindings are not
   followed: veto the window or the bound control; a window veto does not cover `web.*` input into a browser inside it
   (gap 126).
5. **Chain seams, never replace them** (§6.5), and restore them on detach.
6. **Keep hooks optional and small.** Zero hooks on the default path; the first fallback of every hook is a public seam;
   never retransform very large classes (the engine refuses above 256 KiB of class file or 2,000 methods); scope a
   catalogue to the application's own packages; third-party targets are off by default and a licence question
   (design §2.6 "Hook policy"; P6 §3.3, §5.1; §9).
7. **Byteman stays user-supplied and optional.** It is LGPL-2.1-or-later and host-visible; the base never bundles it,
   and neither may a layer (B8; P6 §7).
8. **Loopback and token only.** The agent binds loopback; every request and reply is HMAC-signed with the token, which
   never travels; the token file is owner-only (H1, H2; threat model §4). Do not add another listener to the target.
   Bind a JDWP port of a test configuration to loopback (design §6, "Security").
9. **Mask before anything leaves.** `MaskPolicy`, `CaptureMask`, `RedactionKeys` and `rowMasks()` before any value can
   reach a report, a prompt or a model; credentials only in `credentials.yaml`, never in a fixture, a scenario or a
   prompt (P2 §4, §11.4; P5 §6; P7 §11). (Phase 8) Events too: mask personal values before you publish; the ring masks
   every payload by key and by your `MaskPolicy`s (`event:<topic>`) as the backstop (P8 §5.1).
10. **A model proposes, a person confirms.** Destructive primitives need `confirm`, which a model's or an MCP client's
    request never supplies; a layer's verbs and tools must not route around that (threat model §5; P5 D87; P6 D158).
11. **Production is refused** (§5.7), and the enable flag is never set by the layer's code.
12. **Run outputs are sensitive.** They hold screenshots, UI text, events and row samples; on a shared host run under
    `umask 077` with `--out` in a directory you own (`SECURITY.md`, "Run outputs take the process umask").

---

## 8. The controller side: the domain pack

### 8.1 `DomainPack` members

`ai.skillroy.testjava.scenario.pack.DomainPack`, found by `ServiceLoader`
(`META-INF/services/ai.skillroy.testjava.scenario.pack.DomainPack`). The worked example is `LendingPack`.

| Member | Default | Contributes | Contract |
|---|---|---|---|
| `id()`, `version()` | – | the namespace; the version recorded in every plan | P1 §8.3 |
| `steps()` | – | the `StepLibrary`: the pack's verbs (§8.2) | P1 §8.3; P5 §9.5 |
| `syncPolicy()` | – | the ack, ready and prompt topics and their timeouts (§8.2) | P1 §8.3 |
| `facts()` | – | `FactProvider`s: fact roots for expectations (§8.3) | P1 §8.3, §8.4 |
| `promptPolicy()` | `PromptPolicy.FAIL` | what to do with a prompt no step expected, unless `on_prompt: {unexpected: fail}` | P1 §8.3; P8 §9.2 |
| `preflight()` | none | `PreflightCheck`s run before each scenario; scope `SUITE` once per suite and agent start; a failure is `ENVIRONMENT` | P1 §8.3; P8 §9.3 |
| `capabilities()` | none | `PackCapability`s: environment facts the pack contributes to the matrix, ids `<packId>.<name>`, source `pack:<packId>`, honoured in `replay` (§6.12, §8.8) | P8 §9.1 |
| `recover(agent)` | nothing | return the application to idle after a scenario that did not pass | P1 §8.3 |
| `resolution()` | empty | the `ResolutionCatalogue`: query kinds, resolvers, verifiers, oracles, cross-checks, value loaders (§8.4) | P2 §6; P3 §5 |
| `fingerprinter()` | none | the `EnvFingerprinter` (§8.5) | P2 §6.6, §8.2 |
| `storedTables()` | none | the tables `verify:` may read (`db.<table>`) (§8.5) | P2 §7.4 |
| `rowMasks()` | none | masks for rows read over controller-side JDBC (§8.5) | P2 §7.3 |
| `sqlAllowLists()` | none | what model-written SQL may read (§11.3) | P5 §5.2 |
| `assistAnswerPrimitive()` | null | the primitive an in-application assistant overlay answers through (§11.5) | P5 §12.4 |
| `hooks()` | the resource `META-INF/skillroy/hooks/<id>.yaml` | the hook catalogue (§9.2) | P6 §5.2 |
| `settings()` | empty | `PackSettings`, the constants an overlay may change (§2.2) | P6 §11.1 |
| `screenLegend()` | null | a legend of the screen for a model that looks at captures (§11.6) | P7 §12.3 |

Two further contribution points sit beside the pack, found by `ServiceLoader` on the pack's class loader:
`ResolverContributor` (a module contributes resolvers, as `skillroy-inference` contributes `inference.sql`, P5 §5.5) and
`CapabilityContributor` (controller-side capabilities merged into the matrix; ids must start with `inference.`, P7 §8.5).
A pack's own capabilities are not contributors: they are the member `capabilities()`, their ids start with
`<packId>.` and their source is `pack:<packId>` (Phase 8, built 2026-10-19, EP; P8 §9.1).

There is no MCP tool-pack member (design §3.6 lists one as "later"): the MCP server projects the pack's verbs as tools
named `<domain>_<verb>` with schemas from `StepLibrary.argsSchema`, and the base primitives by policy (P5 §9.3, §9.5).

### 8.2 Verbs and sync

A verb compiles to planned calls: `StepLibrary.compile(verb, args, ctx)` returns `PlannedCall`s
(`primitive, args, sync, timeoutMs, await, capture, literalArgs`), and `CompileContext.addCleanup` adds calls the
executor runs in reverse order at scenario end (P1 §8.3). Base verbs are a closed set; unknown argument keys are errors.
Each call carries a `Sync` level the verb chooses:

| `Sync` | The executor then |
|---|---|
| `NONE` | returns when the call returns |
| `ACK` | awaits the pack's ack topic since `seq.before` |
| `READY` | awaits the ack topic (if the pack has one), then the first ready topic |
| `SETTLE` | calls `swing.settle` (the UI processed the input; no window opened or closed) |
| `EVENT` | awaits the event the call's `AwaitSpec` names, with a payload match (*Phase 8, built 2026-10-19, EX; P8 §8.9:* each value a scalar, equal by text, or `{has: scalar}`, a list member equal by text; anything else a compile error); for acknowledgements that mean only "consumed", or primitives that publish none |

`SyncPolicy(ackTopic, readyTopics, promptTopics, ackTimeout, readyTimeout)`: `ackTopic` may be null (the web host has
none and waits on `web.ready`), and `promptTopics` are the ready topics that mean "the application asks a question", to
which the `PromptPolicy` applies (design §2.5). The sample:
`SyncPolicy(ack = input.acked, ready = [input.requested, prompt.waiting], prompts = [prompt.waiting])` (P1 §9.3).

Verb guidance from the sample: the sign-on verb takes a `$slot` whose slot declares a `credential`, so the password
reaches the call from `credentials.yaml` at bind time and the call carries `_meta.secretParams` (A34; P2 §13.3); a verb
that answers a prompt is marked as handling it; a verb that opens a session adds the cleanup call that closes it.

*(Phase 8, built 2026-10-19, ES; P8 §7.3, §7.4, §7.6.)* `SyncPolicy` has a sixth component, `ackByInputId`: when it is
true, every `ACK` and `READY` acknowledgement wait matches `{inputId: <the result's inputId>}`, so the acknowledgement
of an earlier, queued input never ends a later wait, and a call result without an `inputId` is an `ERROR` /
`ENVIRONMENT` ("the pack's acknowledgements are correlated by inputId; P returned none"). Turn it on once your module
puts the id in its acknowledgement (§6.5); the sample's pack does. A planned call carries `accept` (warning codes the
step tolerates), `rungs` (the call's ladder, `Rung(name, capability)`, two to eight, best first) and `minRung` (the
lowest rung the step allows). A verb whose call has a ladder declares it (`PlannedCall.withRungs`) and forwards
`ctx.minRung()` to the primitive (the sample's `deviceScan` passes it in `args.minRung`); a verb that answers a
question the application may or may not ask compiles to one composite primitive's call (`signOnAny`, `scanChecked`),
never to a conditional step. A verb whose call always tolerates a caveat pre-accepts it on the call
(`PlannedCall.withAccept`; the compiler checks the codes as it checks a step's `accept`). Declare your pack's warning codes, `ScenarioPack.warningCodes()` (`<packId>.<NAME>`;
the compiler refuses a code that does not start with the pack's id).

### 8.3 Facts, prompts, pre-flight, recovery

- A `FactProvider` names a root (`root()`) and fetches its current value (`fetch(agent)`); expectations address it with
  the path grammar (`loan.lines[code=$item.code].amount`, P1 §8.4). The base's own roots are `app`, `ui`, `dialogs`,
  `caps`, `devices`, `web`, `hooks` and, *(Phase 8, built 2026-10-19, EX; P8 §8.6)*, `events`, the agent's bus for the
  run's window (`BaseFacts`; P1 §8.4; P3 §3.4; P4 §6.2; P6 §6.3); a pack root of the same name hides a base root.
- `PromptPolicy.onUnexpectedPrompt` answers `HANDLED`, `IGNORE` or `FAIL` for known interrupting prompts (a session
  lock, a reminder) deterministically (design §3.5). A scenario overrides it with `on_prompt: {unexpected: fail}`;
  `on_prompt: {unexpected: pack}` names the pack's policy explicitly; those are the two values that load (Phase 8,
  built 2026-10-19, EP; P8 §9.2: any other value is refused, where it used to select the pack's policy silently).
- `PreflightCheck`s check the environment (backend reachable, data present, device usable) through a context that
  offers the agent, the data channel, the environment id and the pack's settings; a failure is `ENVIRONMENT` for the
  scenario. *(Phase 8, built 2026-10-19, EP; P8 §9.3.)* They run before each scenario (the default scope,
  `SCENARIO`); a check whose fact belongs to the suite returns `CheckScope.SUITE` from `scope()` and runs once per
  suite, environment and agent start (the JUnit stream, the CLI invocation, the MCP server): a `SUITE` check that failed
  fails every later scenario of the suite without running again (`... (checked once for this suite)`), so a transient
  failure fails the suite. Keep `SCENARIO` checks cheap, and keep `SCENARIO` for anything that may recover.
- `recover(agent)` must be bounded and event-driven: the sample answers any open prompt, cancels an open transaction and
  signs off, at most 20 actions and 60 s (P1 §9.3).

*(Phase 8, built 2026-10-19, ES; P8 §7.5.)* A prompt a composite primitive answered is not unexpected: the executor
reads the `seq` of every question the result lists as answered and expects the prompt events with those `seq`s inside
the call's window, without asking the `PromptPolicy` (`prompt {expected: true, answeredBy}`); a listed `seq` outside
the window vouches for nothing and is logged (`ignoredSeqs`). An unlisted prompt goes to the policy as before, so a
composite cannot hide a prompt it did not list.

### 8.4 Resolution: queries, resolvers, verifiers, oracles, cross-checks

The model (design §3.3; P2 §6; P3 §5): a scenario states what it needs; a resolver finds candidates; the application's
own decision code verifies them; the result is frozen.

- **Queries** are records the pack declares, with a kind: `QueryType.of(kind, queryClass)`; `Query` is not sealed, so
  every pack brings its own (design §10).
- **Resolvers** (`Resolver`): `candidates(query, ctx)` returns ordered, deterministic candidates with a non-secret
  label; the strategy is `CANONICAL_SQL` (`sql`) or `CANONICAL_JAVA` (`java`) for tier (a), `LLM_SQL` (`llm-sql`) for tier
  (b) (§11.3).
- **Verifiers** (`Verifier`) are the gate regardless of tier: deterministic, in the target JVM, normally a domain
  primitive that runs the application's own check (the sample's `lending.access.check`, `lending.deposit`).
- **Oracles** (`Oracle`) are committed, pure, unit-tested Java that compute expected outcomes from bindings. `must_equal`
  makes an oracle a bind-time guard: a binding under which the scenario would not test what it claims is rejected.
- **Cross-checks** (`CrossCheck`) compare an oracle with the application's own dry run of the same rule at bind time;
  disagreement is `ORACLE_SUSPECT`, an `ERROR` in every mode; a guarded cross-check that cannot run makes the outcome
  `DEGRADED` (P3 §3.1, §5.1, §6). Give every oracle a cross-check where the application can dry-run the rule
  (`lending.waiver.evaluate`).
- **Credential joins**: a slot with `credential: {realm, key}` joins the resolved value to an entry of
  `credentials.yaml`; a candidate without an entry is dropped (P2 §8.4).

### 8.5 Fingerprint, stored tables, row masks, allow-lists

- **`EnvFingerprinter`** supplies named components; the base hashes them with the protocol major and the fingerprint
  version. A mismatch in `replay` is `REBIND_NEEDED`, never a silent re-resolution. Include what makes bindings valid or
  stale: a schema checksum of the tables the queries read, the application version and build, the data version, site
  and device identity, the embedded-browser version. Exclude what changes from launch to launch (ports, dates), the hook
  catalogue (D128) and rendering inputs (image baselines have render keys instead, D330). Too strict invalidates
  constantly; too loose lets bindings go stale (design §3.4; P2 §8.2; P6 §6.5).
- **`storedTables()`** lists the tables `verify:` may read with `db.<table>` checks (with aggregates since Phase 3), over
  the agent's `data.query` or a controller-side read-only JDBC source named in `credentials.yaml` (P2 §7.4; P3 §3.2).
- **`rowMasks()`** masks values read over that JDBC channel and the row samples of a provenance; the controller-side
  JDBC rows never pass the agent's `MaskPolicy`, so supply the same rule here (design §3.6, "Closed in Phase 2").
- **`sqlAllowLists()`**: §11.3.

### 8.6 The scenario YAML and the committed plan

Scenarios are YAML (B5; design §3.2; P1 §8.1, with additions in P2 §3, P3 §3, P4 §7, P5 §13.1, P7 §8.1). A real
example is the sample's scenario A,
`samples/skillroy-sample-lending-scenarios/scenarios/return-over-limit-needs-supervisor.yaml`, in outline:

```yaml
scenario: return-over-limit-needs-supervisor
pack: lending
requires: [lending.input, lending.state, lending.loan, lending.loan.authorisations, lending.prompt,
           lending.prompt.answer, lending.slip]
resolve:
  systemMax:
    query: SystemLimit
    args: { type: RETURN }
  item:
    query: LendableItem
    args: { deposit: { between: ["20.00", "200.00"], lte: $systemMax } }
    strategy: [sql]
    verify: lending.deposit
  assistant:
    query: StaffWithPermission
    args: { perform: [SIGN_ON, RETURN], limit: { type: RETURN, lt: $item.deposit }, interactiveSignOn: true }
    verify: lending.access.check
    credential: { realm: staff, key: code }
  # … the supervisor and the action keys
oracle:
  auth:
    fn: ExpectedAuthorisation
    args: { staffLimit: $assistant.limits.RETURN, systemMax: $systemMax, amount: $item.deposit }
    must_equal: SUPERVISOR_PROMPT              # a binding under which no supervisor is needed is rejected, not run
  # … returnLine
steps:
  - signOn: { staff: $assistant }
  - expect: { "state.name": IDLE, "state.staff.code": $assistant.code }
  # … the RETURN key
  - scan: { item: $item }
  - expectPrompt: { id: supervisor-required, within: 10s }    # its text is captured, not asserted
  - authorise: { staff: $supervisor }
  # … the remaining steps, ending with a waitFor that captures the posted loan's id into the slot loan
verify:
  - db.loan_line: { where: { loan_id: $loan.id, item_code: $item.code }, returned: true, amount: $oracle.returnLine }
  # … three more checks
on_prompt: { unexpected: fail }       # fail | pack (default: the pack's policy); nothing else loads
timeouts: { step: 30s }
```

*(Phase 8, built 2026-10-19, EX; P8 §8.3-§8.9.)* Counts, deltas, membership and events, from the sample's
`loan-counts-and-events.yaml`:

```yaml
steps:
  - signOn: { staff: $assistant }
  - scan: { item: $item }
  - captureFacts:                                   # read once where the step stands; a slot, as a capture is
      slot: before
      fields:
        scans: { size: "events[*topic=input.acked][*payload.source=Barcode]" }
        lines: { size: "loan.lines" }
        qty:   "loan.lines[code=$item.code].qty"
  - scan: { item: $item }
  - expect:
      "loan.lines[*code=$item.code]": { size: 1 }                          # a list filter, counted
      "loan.lines[code=$item.code].qty": { gt: $before.qty }               # a delta of a value
      "events[*topic=input.acked][*payload.source=Barcode]": { size: { gt: $before.scans } }
      "loan.lines": { containsWhere: { code: $item.code, qty: 2 } }        # some line where
  - key: { action: DUE }
  - waitFor: { topic: input.requested, match: { state: DEPOSIT, validInputs: { has: Key.DEPOSIT } } }
verify:
  - { "slip.last.text": { anyOf: [ { contains: "NOT ON ANY SLIP" }, { contains: "DEPOSIT DUE" } ] } }
  - { "events[*topic=prompt.waiting]": { size: 0 } }                       # negation is a count
```

Quote every key that holds `[` (a plain YAML scalar never starts with `[` or `*`). Capture counts on state that is the
scenario's own (an open loan, the run's window), not on what an earlier run left. Put a negative check after a sync
point or in `verify`: an expectation holds at its first true attempt. The plan is version 7.

The schema of what a scenario may contain for a given pack is generated by `ScenarioSchema.generate` and served to the
planner and MCP clients as `skillroy://schema/scenario` (P5 §13.3).

`skillroy compile` (or the executor) turns YAML into `plan.json`, byte-identical for the same YAML and pack version, with
no timestamps or absolute paths (design §3.2; P1 §8.2). **Commit the plan beside the YAML** (the sample: `plan/`) and
fail the build when it is stale: `skillroy compile --check` exits 1 for a missing or different plan; the sample's
`PlansUpToDateTest` does the same in a test. `$slot` references are resolved at bind time, never at compile time.

*(Phase 8, built 2026-10-19, ES; P8 §7.3, §7.6.)* **Step modifiers.** Beside its one verb key a step may carry
`accept: [CODE, …]` (warning codes the step tolerates: base codes, or codes the pack declares; `INVALID_WARNING` and
`TOO_MANY_WARNINGS` never) and `minRung: NAME` (the lowest rung of the verb's ladder the step allows). Both are reserved
words, never verbs; a step whose verb compiles to no call (`expect`, `aiAct`, …) refuses them, and `minRung` on a verb
without a ladder, or naming no rung of it, is a compile error. A plan with them is written as version 7, the call
carrying `accept` (sorted), `rungs` (`[{name, capability}]`) and `minRung`; an older reader refuses them. Without
`accept`, a warning fails the step (`FAIL` / `ASSERTION`: "V: P reported CODE: …"); `INPUT_QUEUED` is the executor's
own, derived from `consumed: QUEUED` when acknowledgements are not correlated. From the sample's
`scenarios/semantic-input-warnings.yaml` and `scenarios/scanner/device-scan-physical.yaml`:

```yaml
steps:
  - scan: { barcode: " 5000000000017 " }
    accept: [VALUE_COERCED]          # the desk trims the barcode; the step says it does not care
  - enter: { value: "   " }
    accept: [VALUE_DROPPED]          # the desk ignores an entry of spaces
  - deviceScan: { item: $item }
    minRung: PHYSICAL                # refused at run start on a desk without the scanner driver
```

### 8.7 Credentials and bindings; the bind modes

- **`credentials.yaml`**, one per environment, **never committed**, read by the controller only, owner-only (mode 600
  on POSIX): realms of secret fields, JDBC sources (`url`, `user`, `password`, timeouts) and
  `masking.minLengthInText` (P2 §4). Lookup: `--credentials`, the system property `skillroy.credentials`, the
  environment variable `SKILLROY_CREDENTIALS`, else `<bindings root>/<env>/credentials.yaml`. The sample's
  `credentials.example.yaml` documents the format. Use a read-only database role.
- **Bindings** are `<bindings root>/<env>/<scenario>.bindings.json`: the resolved values (semantic ids, no secrets),
  their strategy, provenance, verifier evidence and approval, oracle values, the fingerprint (P2 §9; design §3.4). They
  are reviewed and committed like code. `skillroy diff-bindings` compares environments.
- **Bind modes** (`--mode`, or the JUnit glue's `skillroy.mode`; `BindMode`):

| Mode | Who | Model calls | Writes bindings |
|---|---|---|---|
| `replay` | CI, every run | none (asserted) | no; verifiers re-run; fingerprint or verifier mismatch is `REBIND_NEEDED` |
| `approve` | a person, for a new or changed scenario | tier (b) only | yes, after the person approves (a terminal question, `--yes`, `--approved-by`) |
| `live` | a developer or an authoring tool | allowed | only with `--write-bindings` |
| `re-resolve` | the nightly job (`skillroy re-resolve`) | batched, budgeted | yes, for review in a pull request the pipeline opens |

### 8.8 Guarded expectations, `DEGRADED` and the failure taxonomy

- `requires:` names what a scenario cannot do without (missing: `PRECONDITION_FAILED`). A guard on one expectation names
  what it can do without: `{ gte: 1, capability: hook.limit.applied }`; when the capability is not `OK` the expectation
  is skipped and the best outcome is `DEGRADED` (B14; P1 §8.4). The sample's scenario E guards its hook expectations
  this way and passes with hooks on, `DEGRADED` with hooks off (P6 §6.3).
- **Application configuration a scenario depends on is a capability** (Phase 8, built 2026-10-19, EP; P8 §9.4, gap 24):
  name it in `requires:` when the scenario cannot run without it (`PRECONDITION_FAILED` before anything mutating is
  sent, before pre-flight, bind and the start mark), or in a guard when one piece of evidence depends on it
  (`DEGRADED`). There is no `preconditions:` key: one matrix states everything a scenario needs, `skillroy caps` shows
  it, each run archives it, the `caps` root reads it and the scenario schema lists the ids. Two sources, the layer's
  choice per fact: the **agent module probes** the setting in process (`ctx.capabilities().declare(id, probe)`, or
  `set(…)` from the application's own change notification when it can change while the target runs; one id per
  condition, the value in the name when needed, `<module>.setting.<name>.<value>`; it costs the capability goldens an
  id each, and a probe runs at readiness and on `reprobe` only, so a mirrored setting goes stale unless the module
  `set()`s it on change), or the **pack evaluates** it from the environment at run start (`DomainPack.capabilities()`,
  `PackCapability`: read-only through the data channel, overlay-aware through `settings()`, honoured in `replay`, no
  agent change and no golden; `SUITE` scope for a slow fact). The sample: `lending.returns.ceiling` is the pack's
  ("the desk has a system maximum return configured"), and scenario A names it in `requires:`; a guard reads the same
  way, `"db.loan_authorisation": { where: { loan_id: $returned.id }, rows: 1, capability: lending.returns.ceiling }`.
  Ids carry no arguments. **The namespace:** the agent module and the pack of one domain share the prefix `<packId>.`,
  and only an id both declare is a conflict (it refuses every run naming both): give the pack's ids a leaf the module
  never declares, and run `skillroy caps --pack <jar>` before CI, which shows both sides in one table.
- Classification is deterministic and happens before any model sees a failure (design §3.5): `ASSERTION` and
  `SYNC_TIMEOUT` are `FAIL`; `PRECONDITION_FAILED`, `REBIND_NEEDED`, `RESOLUTION_FAILED`, `ORACLE_SUSPECT`,
  `TARGET_RESTARTED`, `AGENT_LOST`, `ENVIRONMENT` are `ERROR`; `MODAL_OPEN` and `INPUT_REJECTED` from a step call are
  `FAIL` / `ASSERTION`; a click that fell back to a lower rung makes the run `DEGRADED`; a plan that needs a model while
  steps run is `SKIPPED` in `replay` (P7 §8.6).

*(Phase 8, built 2026-10-19, ES; P8 §7.6, §7.7.)* The executor checks a call's result in a fixed order: secret echo
(a value of a secret parameter anywhere in the result is an `ERROR` / `ENVIRONMENT`, the parameter named, never the
value), warnings (one the step did not accept is `FAIL` / `ASSERTION`; an accepted one is a `warning` record and a
line in the summary), composite problems, the rung, then the base checks. A call that reports a lower rung of its ladder
than the first makes the run `DEGRADED` with a `fidelity` record; a rung below the step's `minRung`, a rung missing or
not on the ladder, or a `minRung` the device did not echo is `FAIL` / `ASSERTION`. At run start, a step with `minRung`
whose rungs from the first to `minRung` all have a capability that is not `OK` is `PRECONDITION_FAILED` before
anything runs (the `rungs` record).

### 8.9 Image baselines and render keys

`expectImage` compares one redacted capture exactly (after masks) with a committed baseline; `pointer` steps may carry an
exact image guard (P7 §6.7, §8.1). Baselines and anchors live beside the bindings file in `<scenarioId>.images/`, keyed
by a **render key** of the rendering inputs (JDK line, look and feel, fonts, scale), not by the fingerprint (D330); a
rendering the project has no baseline for is `REBIND_NEEDED(IMAGE)`, and a new JDK line or font set needs one `approve`
run per environment (gap 83). Baselines are screenshots of the application's data: mask with `expectImage`'s `mask`
what must not be committed, and review the PNGs as you review the YAML (P7 §8.7; `SECURITY.md`). Prefer expectations on
facts; use images only for purely visual properties.

### 8.10 Running scenarios: JUnit glue and CLI

- **Maven**: a `@TestFactory` returns `ScenarioTests.dynamicTests(ScenarioSource.classpath("scenarios"), () -> connection)`
  (or `ScenarioSource.directory(path)`), one dynamic test per scenario, plus one `environment` test for setup failures
  and, in `replay` when a scenario of the stream needs bindings, `skillroy: zero model calls`. System properties:
  `skillroy.mode`, `skillroy.env`, `skillroy.bindings`, `skillroy.credentials`, `skillroy.approve`, `skillroy.hooks`,
  the `skillroy.telemetry*` set, `skillroy.robot` (warned about when true). A CI run is
  `mvn -pl <scenarios module> verify -Dskillroy.mode=replay -Dskillroy.env=<env>` (design §6; `ScenarioTests` Javadoc).
- **CLI** (`skillroy --help`): `jvms`, `attach`, `info`, `caps`, `schema`, `call`, `snapshot`, `events`, `compile`,
  `run`, `resolve`, `diff-bindings`, `re-resolve`, `mcp`, `author`, `triage`, `session`, `hooks`. Packs come in as
  `--pack <jar>` (one option per jar); the target as `--pid`, or `--port` with `--token-file`. With `--out` each scenario
  writes `run.jsonl`, `bindings.json`, `cost-ledger.jsonl`, `capabilities.json` and, on failure, `failure/` (README,
  "Try the CLI").

---

## 9. Hooks

Hooks are optional, catalogued bytecode observation points for facts the application exposes through no public seam
(design §2.6 "Hook policy"; P6 §3-§6). The framework is complete without them.

### 9.1 Turning them on

- The agent: `optionalModules=skillroy-agent-hooks` and a load mode with `Instrumentation` (`JAVAAGENT` or `ATTACH`).
  The capability `hook.engine` reports the engine's state; one `hook.<id>` per installed hook reports its health.
- The run: `--hooks auto|on|off` on `run`, `session`, `mcp` and `author`, or `-Dskillroy.hooks=auto|on|off` (default
  `auto`: install the pack's catalogue when the hooks module is loaded). It is a run-level switch, never a scenario key
  (P6 §6.1).
- Installed hooks **stay installed** after a run, idempotent by catalogue hash, until `skillroy hooks remove`, the agent
  is disabled, or the target exits (D129). A `replay` run against an armed stub is `ERROR` / `ENVIRONMENT`: CI never
  runs against stubbed behaviour (P6 §5.3).

### 9.2 The catalogue

One YAML resource per pack, `META-INF/skillroy/hooks/<packId>.yaml`, read with the strict reader; the agent receives
JSON `HookSpec`s (`ai.skillroy.testjava.protocol.hook`), never YAML (P6 §5.1). The sample's
`samples/skillroy-sample-lending-pack/src/main/resources/META-INF/skillroy/hooks/lending.yaml` is the example:

```yaml
catalogue: <packId>                    # [a-z][a-z0-9-]*
version: 1                             # bump on every change; part of the hash
application: <application> <version>  # free text for the record
hooks:
  - id: limit.applied                  # capability hook.limit.applied, topic hook.limit.applied
    mode: CAPTURE                      # OBSERVE | CAPTURE | STUB
    target: { class: <fully qualified class>, method: <name>, descriptors: ["(Ljava/lang/String;)Ljava/math/BigDecimal;"] }
    capture: { args: [0], result: true }
    secretArgs: []                     # argument indexes never captured
    secretResult: false
    fallbacks:
      - seam: <product>.limits         # always first, always a public seam: a primitive the target serves
    note: what the hook observes
packages: [<the application's own packages>]   # decides the default of enabled
```

Rules the reader enforces: the first fallback is a `seam`; at most 64 hooks; `capture` only on `CAPTURE`; an entry on a
class outside `packages` is off unless written `enabled: true`, with a warning. Pin every descriptor of an overloaded
method that reaches the behaviour. A `reflect` fallback is refused ("not in this version").

**Code sources and the deny (Phase 8, built 2026-10-19, EH; P8 §6.2, §6.3, §6.8).** A `packages` item may be a map
that names the jar the package's classes must come from, and an entry may carry the same qualifier itself; both are a
Java regular expression matched against the **whole** code-source name (anchored: `desk-app.*` does not match
`desk-app-scheduler.jar` unless written so), and an entry's own wins over the longest `packages` name its class is
under. The name is the jar's file name, never a directory; for a class directory it is the directory's last segment,
for a nested jar the outer jar and the entry inside it:

```yaml
version: 2                             # bump on every change, as always
packages:
  - { name: <your package>, codeSource: '<your-app>(-[^/]*)?\.jar' }   # the IT copy and a versioned jar match
  - <a package you share with nobody>  # the plain form stays
deny: [<your package>.Inbox, <your package>.licensed.**]               # class patterns, at most 64
```

| Where the class was loaded from | Its code-source name |
|---|---|
| `file:/opt/desk/lib/desk-app-1.2.jar` (a versioned jar) | `desk-app-1.2.jar` |
| `file:/opt/it-target/desk-app.jar` (an unversioned IT copy) | `desk-app.jar` |
| `file:/build/desk/target/classes/` (a class directory) | `classes` |
| `jar:file:/opt/app.jar!/BOOT-INF/lib/lib.jar!/` (a nested jar) | `app.jar!/BOOT-INF/lib/lib.jar` |
| the bootstrap loader, a generated proxy | `(none)` |

A qualifier is a filter, like `loader`: a copy of the name from another jar is left alone, not a failure of the hook
(`class from another code source: <names>` when no copy matches). `deny:` takes class patterns (`a.b.C` with its nested
classes, `a.b.*` the package, `a.b.**` everything under it, `a.b.Input*` a glob in the last segment); an **enabled**
entry on a denied class is an error at its `class` line, a disabled one a warning. An overlay unions both lists and can
never lift a base deny; an enabled base entry the overlay denies must be disabled by the overlay.

**Per application version.** The pack's default `hooks()` reads one catalogue. Choosing among catalogues per application
version is the layer's: one pack build per supported application line, or a `hooks()` override that reads the right
resource (`HookCatalogues.readResource(type, name)` or `HookCatalogues.read(reader, source)`) by something the layer
controls, such as a pack setting. The `application:` field is for the record only; nothing selects by it (P6 §5.1).

### 9.3 What the engine refuses

The engine probes each spec before installing it (`hook.probe`; P6 §3.3): it never loads a target class, refuses the
JDK's and the agent's packages and loaders, refuses a class larger than `hooks.maxClassBytes` (262144) or with more than
`hooks.maxMethods` (2000) methods, holds at most `hooks.maxClasses` (64) classes, and refuses a class whose loader cannot
see the bootstrap `Spy` (a strict-delegation loader, gap 50). Detach restores and verifies the original bytes within
`hooks.detachBudgetMs` (8000) (P6 §3.5). A failed install falls back to the first `seam` the target serves; scenarios
that need the hook name it in `requires:` or guard on it.

**The class deny (Phase 8, built 2026-10-19, EH; P8 §6.4-§6.6, §6.9).** A class is refused **by name, before anything
of it is read**, by three sources asked in order, the first refusal winning: the agent option `hooks.deny` (the
operator's; comma-separated class patterns, read by the hooks and the Byteman modules alike), the catalogue's `deny:`
(delivered with `hook.install` and held by the engine) and a domain module's `ClassDeny` (§6.2). The held catalogue list
is **add-only for the agent instance's life**: it survives `hook.remove {}` and a replace, so loosening a deny needs a
target restart; `hook.health.deny` (and `skillroy hooks status`) shows the effective list. The agent echoes the held
list in every `hook.install` answer and the installer fails closed (`ENVIRONMENT`, "upgrade the agent") when the echo
is missing. A run that skips the install (`--hooks off`, or no usable hooks module) records `denyNotDelivered` with the
patterns and whether the remedy, the agent option `hooks.deny`, is in force on the target (`agentOption`, read from
`rule.list` when the Byteman module is loaded, else `unknown`); set it on a target that runs without the hooks module.
A hook installed before its class was denied is **flagged, not removed** (`deniedNow`; remove it yourself). The deny is
policy (a hot path, a licence, timing-sensitive code), not checksum protection: retransformation changes neither the
jar nor the bytes the class's resource returns.

### 9.4 Reading hook facts and the CLI

The base fact root `hooks` is a list, filtered by id: `hooks[id=limit.applied].count`,
`hooks[id=limit.applied].last.args.0`, `hooks[id=limit.applied].last.returned`; `count` and `last` describe the current
run's steps only (P6 §6.3). `skillroy hooks status|install|remove [--ids a,b] [--pack <jar>]…` shows the target's hooks
beside the pack's catalogue, installs, or removes with verification (P6 §6.2). Hook events are evidence about
non-adversarial application code, not a tamper-proof record (threat model §3).

### 9.5 Hooks from Java

A domain module that needs hooks from code finds the `HookEngine` with `ctx.hooks()` in `onTargetReady` (null without
the hooks module), never through `requires()`, and calls it from agent threads only, never the EDT or an application
thread: an install retransforms classes (P6 §3.6).

### 9.6 Byteman (optional)

For ad-hoc rules beyond the catalogue: `optionalModules=skillroy-agent-byteman` and `byteman.jar=<absolute path>` to a
Byteman jar **the user supplies** (4.0.27 is the line the base was built and tested with), in a directory nobody else may
write. Byteman is loaded in process on the first confirmed `rule.submit`, with no listener; `rule.check` lints without
loading it, `rule.submit` is destructive and needs `confirm`, `rule.unload` restores, `rule.list` lists (P6 §7;
`JAVAAGENT` or `ATTACH` only). Only rules unload; Byteman stays loaded for the JVM's life, and a rule left installed by a
detach that ran out of time keeps running until the target restarts (`SECURITY.md`).

*(Phase 8, built 2026-10-19, EH; P8 §6.7.)* The Byteman module honours the class deny of §9.3 before it loads: a rule
whose `CLASS` or `INTERFACE` names a denied class is reported by `rule.check` and refused by `rule.submit` before the
count and before Byteman is loaded, and a `^` or `INTERFACE` rule that reaches a denied loaded subtype is refused too;
`rule.list.deny` shows the deny in force. A denied subtype loaded **after** a `^`/`INTERFACE` submit still receives
Byteman's trigger at its load (gap 140): deny the supertype or write the rule on the class itself.

---

## 10. Embedded web views

Every web verb runs on one of two paths, and each step records which (design §3.8; P4).

### 10.1 What the layer decides

| Question | Where it is answered |
|---|---|
| Which engine does the application embed, which line, on which Java? | §10.2 |
| Can every browser be found? (in the Swing tree, or only through the application's own objects) | `BrowserRegistryAdapter` (§6.8) |
| Does the application start its engine with a remote-debugging port in test configurations? | §10.3; it must be set at engine start: the base never sets one |
| What are the pages' readiness signals? | page flags and events: `webWaitFlag`, `webTap` (§10.4) |
| Which pages show personal data? | mask the browser component (§10.4) |

### 10.2 The in-process path: the built-in adapters

One SPI (`WebEngineAdapter`, `WebBrowser`, in `….agent.api.spi.web`) with two built-in reflective adapters in
`skillroy-agent-web`; neither has a compile-time vendor dependency, and no vendor code or licence ships (B9; P4 §3, §4.5).

- **`jcef`**: in process through the browser's own DevTools methods, no debug port needed. It never creates a `CefApp`
  (only reads an initialised one) and takes no handler slot of the application. A JCEF older than 2024-01 is served on
  neither path (gap 43); windowed JCEF on a Java 8 target is unverified (gap 44); a hidden JCEF browser has no bitmap
  (gap 39). The base's own web host (profile `jcef`) and the contract kit run against real JCEF in the Linux image.
- **`jxbrowser`**: the public members lines 7, 8 and 9 share, resolved by reflection from a descriptor. The library needs
  a commercial licence **at run time, supplied by the application**; the adapter never reads, logs or forwards a licence
  key, and nothing of JxBrowser is in any of the base's artifacts. Enumeration is by seeds only: an engine whose browser
  is in no window is found only through a `BrowserRegistryAdapter` seed. Only line 7 runs on a Java 8 target, and it is
  past its end of life. Document-start init scripts need the opt-in `web.jxbrowser.chainInjectJs=true`, which chains
  the application's `InjectJsCallback` and restores it on detach. **Verified only against JxBrowser 8.18.3 on macOS
  arm64 with a JDK 25 test JVM (2026-10-11, the contract kit 19 of 19 in three variants)**; other lines, Linux, Windows
  and a Java 8 test JVM are not verified (P4 §4.5 "Against the product"; TODO "Walls"; `SECURITY.md`). A layer whose
  application embeds JxBrowser should run the kit against its own line and platform first (§10.5).

The capability `web.adapter.<id>` reports which adapter and line a target uses; `web.browsers` lists the browsers with
their refs, tags and frames.

### 10.3 The CDP path

`skillroy-cdp` attaches Playwright (1.63.0) to the embedded Chromium with `connectOverCDP` on a loopback
remote-debugging port, under an external deadline with a liveness probe, and never creates a context or page
(P4 §5). It is optional: the executor, the JUnit glue and the CLI do not depend on it. A Maven suite declares it; the CLI
takes it and Playwright's jars as `--pack` jars (README "Embedded web views"). The scenario key
`web: {path: auto|cdp|agent, browser: {tag: …}, frozenTargets: …}` chooses: `auto` takes CDP when a port is configured
and `skillroy-cdp` is present, else the agent. `web.cdp.status` reports the ports the adapters found configured.

- The port is per process on JCEF and per engine on JxBrowser and must be unique per target instance on a shared host.
- Pin Playwright to what attaches to the embedded Chromium; the newest is the default, and a pack pins older only when
  a spike shows the newest cannot attach (design §1.3, §3.8).
- (Phase 8, P8 §5.3) **The floor.** Built and tested with Playwright 1.63.0. The CDP path runs with 1.61 and later in
  full; with 1.60 without tracing (a traced run opens untraced); with 1.58 and 1.59 only under the operator's opt-in
  `-Dskillroy.cdp.allowDefaultOverrides=true` on the controller (Playwright's download, focus and media overrides then
  apply to the application's own browser context, logged at WARNING); a line below 1.58 is refused by the same rule and
  is otherwise untried. The module's other Playwright API is older than 1.58 by its release notes, but no other line was
  run: a layer that pins one spikes it and audits it. The `web.path` record says which line ran (`playwright`) and what
  the attach applied (`connectOptions`).
- Bind the port before any other process can, or run with `web.path: agent`: a process that holds the port first can
  redirect Playwright's handshake (`SECURITY.md`, "A squatted debug port").
- Playwright's driver inherits the controller's environment; set `PLAYWRIGHT_*`, `NODE_OPTIONS` and `DEBUG=pw:*` only on
  purpose (README).

### 10.4 Verbs, primitives, readiness and masks

- Base verbs: `webClick`, `webFill`, `webDispatch`, `webWaitFlag`, `webResize`, `webTap` (P4 §7.1). Primitives:
  `web.browsers`, `web.evaluate`, `web.waitFlag`, `web.dom.click`, `web.dom.fill`, `web.dom.text`, `web.ariaSnapshot`,
  `web.dispatchEvent`, `web.tap`, `web.resize`, `web.bitmap`, `web.reload`, `web.initScript`, `web.cdp.status`, and
  since Phase 7 `web.hitTest` and `web.pointer`. `web.evaluate` and `web.waitFlag` carry the `script` flag, which
  read-only mode refuses.
- Readiness: declare the pages' readiness contract (a flag, an event); `web.tap` installs an in-page event tap from a
  document-load observer, so a single-fire event is not lost; the step records which signal satisfied it (design §3.8).
  The web host's pack uses `readyTopics: [web.ready]` and no ack topic.
- Masks: there is no mask for a DOM element. A page that shows personal data is masked by a `CaptureMask` on the whole
  browser component while it does, and a masked browser is masked on every route: no pixels, text or tree reach a model
  or a client (`SECURITY.md`; P7 §3.6). The web host's `FrontCardMask` is the example. (Phase 8, P8 §5.2) A
  `BrowserMask` names or releases a browser by its seed tag or vendor object (`BackTagMask`, `DetachedRelease`); a
  browser without a view is masked while any mask is registered; a masked browser's `web.load`, `web.ready`,
  `web.console` and `web.tap` withhold page data (`name` and `signal` stay, so waits still complete), `by: {url|title}`
  never selects it (select it by `tag`), and `web.pointer`, `web.dom.click` and `web.dom.fill` on it answer without page
  data (`redact: false` is a person's argument). The CDP path hands what it cannot judge to the agent.
- Navigation is the application's: scenarios drive the application, which drives its web views.

### 10.5 Adding an adapter for another engine

A layer may add a `WebEngineAdapter` for a technology the base does not cover, in an agent module (Java 8), added
through `ctx.spi()` during `install` (P4 §3). Its rules, each tested by the contract kit: create, initialise or configure
no engine; take nothing single-slot of the application (or chain and restore it on an explicit opt-in); never wait on
the EDT; leave no enumerable global in the page; no vendor type in a signature, on the wire or in any Maven scope, no
licence key read; a missing optional member is a missing capability, not an error.

Prove it with **the adapter contract kit**, the `skillroy-agent-web` test-jar (package `ai.skillroy.testjava.agent.web.kit`):
subclass `WebEngineAdapterContract` and implement `WebAdapterFixture` (the adapter, seeds for a visible, a hidden and
optionally a view-less browser, page loading, the thread that must never block, the expected rendering and enumeration
state). The kit's items cover probing, enumeration, evaluation, frames, events, init scripts, resize, bitmaps, target
ids, closure, globals, reload, the guard, console budgets, hit-tests and pointer input (P4 §10.1; P7 §4.9). An engine
that needs a licence is tested **in the layer's repository**, which holds the licence, never in the base: that is how the
JxBrowser run of 2026-10-11 was made (P4 §4.5).

---

## 11. AI features and policy

### 11.1 CI never calls a model

`replay` makes **zero model calls**, asserted by the cost ledger and, in the JUnit glue, by the test
`skillroy: zero model calls`; the model key is absent from CI agents; a plan that needs a model while steps run is
reported `SKIPPED` (B6; design §5, §6). Nothing in the build needs a key: every test of the AI layer runs against
recorded or scripted gateways. The owner's key is for live work only, read from the environment
(`ANTHROPIC_API_KEY`) and never from a file, an option or a command line (P5 §2.3).

### 11.2 Inference settings

`inference.yaml`, beside `credentials.yaml` (or `--inference-file`), holds no secret and may be committed; it is read
only when nobody but the current user could have written it (P5 §4.4). The sample's
`samples/skillroy-sample-lending-scenarios/inference.example.yaml` shows every key: `gateway: claude|recorded|none`
(`replay` forces `none`), `recordings`, `models`, `effort`, `budget` (`scenarioUsd` 0.50 per bind, `sessionUsd` 1.00 per
interactive session, `jobUsd` required by `re-resolve`), `thresholds.confidence` (0.6), `images` (false: the one switch
that lets pixels leave for the provider), `logPrompts`. Every call is metered by `BudgetMeter` with a `PriceTable` and
written to the ledger (P5 §7).

### 11.3 Tier (b): model-written SQL

A slot with `strategy: [llm-sql]` (or `[sql, llm-sql]`) is resolved by a model in `live` and `approve` through the
contributed resolver `inference.sql`, behind `SqlGuard` (one `SELECT`, the pack's allow-list, a deterministic
`ORDER BY`, a row limit), the verifier, the confidence threshold, and **a person's approval** (the slot stays
`REQUIRED` until approved) (P5 §5, §7.1). The pack declares `sqlAllowLists()`: per source and dialect, the tables and
columns a model may read, each column marked `personal` and `sample` as it applies, with descriptions that become the
model's catalogue. The allow-list is checked against the database at start (D100). The sample's scenario D is the
worked example, with committed recordings so CI needs no key. **(Phase 8, built 2026-10-19, ET; P8 §4.4, §4.5)**
Procedures are not served: `SqlGuard` refuses every `CALL` and `EXEC` ("no data channel runs a procedure call (gap
119)"), and an allow-list that names procedures is refused at load and at the MCP server's start (`procedures` is
reserved for gap 13 and must be empty). A source that answers by position needs its provider to answer
`columns(table)` for the start check to pass.

### 11.4 Data policy: what may reach a model

- `PromptMask` masks before any request is built: credentials never, personal columns by the allow-list's flags and the
  row masks, a secret-named property's whole subtree (P5 §6; threat model §5).
- Images reach a provider only under `images: true`, only as redacted `PAINT` captures of the target's own windows,
  never a capture of the screen glass (P7 §11).
- What a model reads from the target is untrusted input: its tools are the target's own primitives and the pack's verbs;
  nothing it produces reaches `data.query` except through the guard and the allow-list, nor installs hooks, submits
  rules or runs a script primitive; it never confirms (threat model §5).
- Choose models whose data handling suits the application's data; the design notes which are zero-data-retention
  eligible (design §5, "Personal data").

### 11.5 Approve, authoring and interactive sessions

- **Approve and review**: `skillroy run --mode approve` shows value, SQL, rationale, cost and verifier evidence and
  writes the bindings on approval; `skillroy diff-bindings` and the nightly `skillroy re-resolve` report make changes
  reviewable; the pipeline opens the pull request (P5 §7.1, §8; `ci/examples/nightly-re-resolve.yml`).
- **Authoring**: `skillroy author --brief <text>` runs the planner (`claude` on `PATH`, headless) against the MCP server
  and emits a scenario that a person reviews; exported model-found slots stay `REQUIRED` until approved (P5 §10; D97).
- **Interactive sessions**: `skillroy session` (`ask`, `do`, `call`, `resolve`, `check`, `export`) drives a live target
  with every action journaled; `export` writes an ordinary scenario that replays with zero model calls (B15; P5 §12).
  The MCP server (`skillroy mcp`, read-only by default, `--mutating` by flag, never destructive) is another host.
- **An assistant inside the application**: the session service's `OverlayBridge` subscribes to `assist.requested`
  events and answers through the primitive the pack names in `assistAnswerPrimitive()`. The overlay's presentation and
  its agent module are a tenant layer's; no CLI command hosts the bridge, so a layer that wants one embeds it in its own
  controller code (design §5; P5 §12.4; P7 §9.5).

### 11.6 Vision tiers and the Robot opt-in

`skillroy session --vision v1|v2|v3|off` (P7 §6; design §3.10): V1 (the model sees a redacted capture and answers by
mark or ref), V2 (it answers with a pixel, mapped through `swing.hitTest` and acted through `swing.pointer` routed by
AWT), V3 (it acts through `java.awt.Robot`). Every action is recorded as the step that will be replayed and exported as
ordinary steps. V3 needs **both halves** of a double opt-in: the agent option `robot=true` and a person's
`--allow-robot`; neither is ever a model's or a client's choice, and neither belongs in a committed file. A session is
bounded to USD 1.00, 30 UI actions and 20 model turns. For a vision-friendly application: a `CaptureMask` for every
secret, a `RegionResolver` for painted surfaces, `LocatorResolver.describe`, and a pack `screenLegend()`. Robot is
verified on the Linux image's display only.

---

## 12. CI and environments

### 12.1 The Linux image

The base's `ci/Dockerfile` (`eclipse-temurin:25-jdk`, Xvfb at 1024x768x24 without a window manager, fonts, Chromium's
run-time libraries for JCEF) and `ci/run-in-linux.sh` (mounts the repository, the local Maven repository and the host's
Maven; `SKILLROY_JDK8_HOME` mounts a Linux JDK 8 at `/opt/jdk8`; `SKILLROY_PLATFORM` and `SKILLROY_CI_IMAGE` choose an
arm64 build) are Apache-2.0: copy them into the layer's `ci/` with their headers, or build the base's image and point
the layer's runner at it. The script mounts the directory above its own `ci/`, so a copy works for the layer's
repository. Display tests must skip when headless and run only in the image; never switch headless off on a desktop:
the tests open real windows (README "Build"; `CLAUDE.md`).

Lessons recorded by the base (TODO "Environment notes") that apply to a layer: keep JDKs in a durable directory, never
under `/tmp` (macOS's clean-up emptied one); under QEMU emulation (the amd64 image on an arm64 host) run the test JVMs
with `-Djdk.lang.Process.launchMechanism=FORK`, because the JDK's spawn helper fails now and then there; read test
counts, not exit codes; touch restored sources before trusting an incremental build.

### 12.2 The matrix

Run the layer's suites the way the base gates itself (design §6; `docs/release-checklist.md` item 1): every scenario
in `replay` against a Java 8 and a Java 25 target of the application, in each load mode the layer supports (at least
`JAVAAGENT`, plus `EMBEDDED` if the application loads the agent that way), with hooks on and off if the pack has a
catalogue, and the web profile if the application embeds browsers. Start the target **once per suite** with the agent
loaded at launch; `recover` keeps it usable between scenarios. Keep live and recording profiles out of the gate (the
base's `inference-live` and `inference-record` profiles report themselves skipped without a key).

### 12.3 Windows

`ci\run-on-windows.cmd` is the base's runner, to be started from the repository root in an interactive logon session
because the tests open real windows (README "Windows"). The layer's equivalent must run its display tests the same
way, never as a service in session 0, where `java.awt.Robot` cannot work (§5.9; design §3.7).

### 12.4 Artifacts and their sensitivity

A run's outputs (`run.jsonl`, `bindings.json`, ledgers, `capabilities.json`, `failure/` with a UI snapshot and a PNG,
`captures/` of a vision session, `telemetry/spans.jsonl`) hold screenshots, UI text, events and row samples. Treat CI
artifacts as the application's data: restrict who can read them, set retention, run under `umask 077` on shared hosts,
and give `--out` a directory the CI user owns (`SECURITY.md`; threat model §2, A13). Bindings and image baselines are
committed and reviewed; credentials files never are.

### 12.5 OpenTelemetry

Off by default; `--telemetry otlp|file` (CLI) or `-Dskillroy.telemetry=…` (JUnit glue, with `skillroy-telemetry` on the
test class path); `http://` to loopback only, `https://` otherwise; a backend's header from an environment variable that
`telemetry.yaml` names; attributes from an allow-list (`standard` or `minimal`), never parameters, values, rows, SQL or
prompts (README "Exporting spans"; P6 §10). Nothing of OpenTelemetry enters the target JVM.

---

## 13. Keeping the base neutral

The base's hard rule (`CLAUDE.md`, "Neutrality rule"): nothing in the base may name or describe a customer, tenant or
proprietary application, in code, tests, fixtures, comments or docs, and the history is permanent once pushed. The
layer carries the responsibility for what flows back.

- **A generic need goes back as a generic SPI, proven on the sample.** When the layer meets a need the base cannot
  serve, write it up in the base's terms (a *target application*, a *domain pack*, the sample lending desk's
  vocabulary: items, loans, deposits, waiver rules, slips, staff), add a row to TODO's "Open SPI and DSL gaps"
  (`# | Gap | Why it matters | Proposed shape | Phase`), and propose the shape. Never the layer's names: no class,
  package, table, column, config key, file path or product name, and no citation into the layer's sources.
- **Contract first.** A change to the base changes its contract before its code (`CLAUDE.md`; CONTRIBUTING "Contract
  first"): the contract section is amended and marked, the decisions it takes are recorded as `Dn` rows (question,
  default taken, the alternative, the reason) and put to the owner as a `Bn` entry in `docs/DECISIONS.md` in its
  "question, default, veto form" style, and the sample gains whatever proves the change.
- **Contributions** are Apache-2.0 under the DCO (`git commit -s`), every source file with the licence header
  (`ci/license-headers.sh`), every new dependency in `docs/licence-audit.md`, and nothing but the JDK visible to a
  target JVM (CONTRIBUTING).
- **The layer keeps its own leak check against the base.** The private layer holds a deny-list of its own names (the
  application's product, package, class and table names, tenant names, paths) and a script that greps the base against
  it; the deny-list never enters the base. Run it on every base upgrade, on every change the layer proposes to the base,
  and on the base's release commit (release checklist item 12). Run it on a clean export (`git archive`) of the base, not
  on a tree with agent worktrees inside (TODO "Environment notes", Phase 6).
- **Verification results are welcome in neutral form.** A Windows run, a JxBrowser line, a soak on a real target: report
  what ran and what held without naming the application (the JxBrowser kit run is recorded that way, P4 §4.5).

---

## 14. The first week: bringing up a new application

Each step has an exit check. Steps 1-4 can be done in a throwaway spike module before the real layer exists.

1. **Get the base** (§4.3): a clean clone of a tagged commit, `mvn verify`, installed into a dedicated local repository;
   note the commit. *Done when* the layer's empty parent POM resolves `skillroy-agent-api` and `skillroy-scenario`.
2. **Spike readiness and threads.** Read the application's start-up and threading: which thread owns domain state, which
   threads must never be blocked, when singletons are safe, when listeners may be added, whether it can start in a modal
   question. Write a spike module with only a `ReadinessSource`, a `ThreadPolicyProvider` and an `AppStateProvider`.
   *Done when* `skillroy call app.state` answers on a running application and `target.ready` is `OK`.
3. **Load the agent.** Assemble an agent home with the spike module (§4.5) and start the application with
   `-javaagent` and `-Dskillroy.agent.enabled=true` on a test installation; try `EMBEDDED` if the application has a
   plugin mechanism (§5.3). Add the `ProductionMarker` and `requireProductionMarker=true` now (§5.7).
   *Done when* `skillroy info`, `skillroy caps` and `skillroy snapshot` work on a Java 8 and a Java 25 target, and the
   agent refuses to serve on a configuration made to look like production.
4. **Snapshot and names.** Walk the snapshot of every main screen: nodes without names, custom widgets, custom dialogs,
   in-window dialogs, components that show secrets. Add `NameEnricher`, `LocatorResolver`, `ActionFirer`,
   `DialogTextExtractor`, `DialogLocator`, `CaptureMask` and `RedactionKeys` as needed (§6.7, §6.11).
   *Done when* every control a scenario will touch has a stable locator and `swing.capture` blanks every secret.
5. **The input seam and the sync topics.** Add `<product>.input` (chained, pre-checked, publishing ack and ready
   topics), the open-prompt read and answer, and a state read (§6.5, §6.14). *Done when* a sequence of inputs through
   `skillroy call` is acknowledged and followed by the ready topic each time (`skillroy events`).
6. **The pack and a first scenario in replay.** A `DomainPack` with the `SyncPolicy`, three or four verbs, one fact root,
   a `PromptPolicy`, a pre-flight check and `recover`; one scenario with fixture slots only; compile and commit its plan.
   *Done when* `skillroy run` passes it twice in a row on one target without a restart, `skillroy compile --check` is
   clean, and the JUnit glue runs it with `-Dskillroy.mode=replay`.
7. **The data channel and resolution.** A `DataSourceProvider` and `MaskPolicy`; a stored table; a tier (a) resolver
   whose verifier calls the application's own decision code; an oracle with a cross-check where the application can dry
   run the rule; a fingerprinter; `credentials.yaml` for one environment (§6.9, §8.4, §8.5, §8.7).
   *Done when* `--mode approve` writes bindings, `replay` passes on them, and a deliberately wrong binding fails as
   `REBIND_NEEDED`.
8. **Hooks, only if a fact has no seam.** One `OBSERVE` or `CAPTURE` hook in a catalogue with a seam fallback, guarded in
   the scenario (§9). *Done when* the scenario passes with hooks on and is `DEGRADED`, not failed, with hooks off.
9. **Web views, if any.** Identify the engine and line; seeds for browsers outside the tree; the debug port decision;
   readiness flags; masks (§10). *Done when* one web scenario passes through the agent path, and over CDP if a port is
   configured.
10. **CI.** The Linux image and runner (§12.1), a replay-only job on both target Javas, artifact retention and access
    (§12.4), and the leak check against the base (§13). *Done when* the job is green twice in a row and the test
    `skillroy: zero model calls` is in its report.

---

## 15. Not provided by the base, and known limits

### 15.1 Needs a layer may have that have no SPI in the base

| Need | What exists instead |
|---|---|
| an SPI for the application's own input seam | a primitive of the module (the `SEMANTIC` rung, §6.5); the chaining rule is design §2.2 step 4 |
| an agent-side overlay | options, the application's variant in `app.state`, or an additional module that adds (§2.6) |
| choosing a hook catalogue per application version | one pack build per line, or a `hooks()` override (§9.2) |
| an MCP tool pack per domain (`McpToolPack`, listed as "later" in design §3.6) | the MCP server projects the pack's verbs and the base primitives (P5 §9.3) |
| module upload over the channel (`POST /modules`) | modules in the home's `modules/`, the `modules` option, or `attach --module` (§4.5) |
| hooks in `EMBEDDED` mode (self-attach) | `JAVAAGENT` for suites that need hooks (design §2.2) |
| hosting an in-application assistant | `OverlayBridge` exists in `skillroy-inference`; no CLI hosts it, and the overlay's presentation is the tenant layer's (§11.5) |
| opening the nightly bindings pull request | the job writes files and a report; the pipeline opens it (P5 §8.2) |
| the CLI distribution as a Maven artifact | the build output of `controller/skillroy-cli` (§4.1) |
| a fluent Java API for scenarios | YAML only (README; B5) |
| a disable flag file for the agent | `agent.disable`, `EmbeddedAgent.stop()`, the agent argument `cmd=stop` (design §2.2 step 6) |
| pack-defined artifact kinds; a pack-contributed CLI subcommand | not built (gaps 28, 29) |
| a model provider other than Claude; tier (c) reasoning at bind time | the `InferenceGateway` SPI has one provider implementation; tier (c) only in interactive sessions (B7; design §3.3) |
| a mask for one DOM element | the whole browser component (§10.4) |
| owner-only run outputs by default | `umask 077` (accepted, B28) |

### 15.2 Open gaps a layer is likely to meet

From TODO "Open SPI and DSL gaps" (read the row for the proposed shape). (Phase 8, built 2026-10-19, ET: gaps 9, 10
and 11 are taken, P8 §4.1-§4.3, and their rows are removed here.)

| Gap | In short |
|---|---|
| 13 | no session-scoped data reads (a procedure call then a select on one connection) |
| 35, 39, 43, 44 | a view-less browser without a seed is invisible; hidden JCEF has no bitmap; JCEF before 2024-01; windowed JCEF on Java 8 |
| 50 | a class behind a strict-delegation loader cannot be hooked |
| 77, 78, 81 | no Robot key input; HiDPI unverified; no key input into a page through `web.pointer` |
| 83, 84 | one baseline per rendering; redaction hides a rectangle, not a value |
| 140 *(Phase 8, EH)* | a denied subtype loaded after a `^`/`INTERFACE` Byteman submit still receives the trigger (§9.6) |
| 142 *(Phase 8, ES)* | no conditional planned call (`when`): a flow that cannot be one composite primitive (two packs' primitives, a base verb in the branch) has no conditional step |

### 15.3 Not verified

From README's status paragraph, `SECURITY.md` "Known limitations" and threat model §8:

- **Windows**, entirely: the ACL branches of every owner-only file, `bin\skillroy.bat`, the planner on Windows,
  `JAVAAGENT` as the logged-on user, the input rungs, the Robot tier, the run-output ACL judgement.
- **The `jxbrowser` adapter** beyond JxBrowser 8.18.3 on macOS arm64 with a JDK 25 test JVM: other lines, Linux, Windows,
  a Java 8 test JVM.
- **Robot input** off the Linux image's display (macOS with the Accessibility permission, Windows, Wayland, a locked
  session), any display scale other than 1.
- **Hooks on a real application**: a detach that ran out of its time budget; the retransformation pause on a painting
  event-dispatch thread (gap 53); Byteman beside the application's own agents.
- **Live model paths** beyond the recorded live runs; the vision loop beyond short sessions.
- **A soak on a target other than the sample** (TODO "Not yet assigned to a phase"): the first real application will be
  the first such target; record what it shows.

### 15.4 Security limitations to plan around

`SECURITY.md` "Known limitations" in full; the ones that shape a layer's environment: same-user attach is the platform's
boundary (`-XX:+DisableAttachMechanism` where it must not happen); `StatementGuard` is a safety net (use a read-only
role; on PostgreSQL `readOnlyMode: always`); run outputs take the umask; settings files must be the operator's own; pin
the CI example's image and actions; a squatted debug port; a loopback `http` model base URL only for a proxy you run;
a Windows whose account names are not English fails closed; redaction blanks rectangles; pixels leave for the provider
only under `images: true`; the Robot opt-in must not be committed.

---

## 16. Where to look

| Topic | Source |
|---|---|
| What the base is and how to build it | [`README.md`](../../README.md) |
| The whole design, every phase | [`base-framework-design.md`](../design/base-framework-design.md) |
| Agent home, options, discovery, protocol, agent SPI, sample module | [`phase1-contracts.md`](../design/phase1-contracts.md) §2-§4, §9 |
| Resolution, credentials, bindings, stored rows | [`phase2-contracts.md`](../design/phase2-contracts.md) |
| Cross-checks, `ORACLE_SUSPECT`, devices root | [`phase3-contracts.md`](../design/phase3-contracts.md) |
| Embedded web views, adapters, the kit | [`phase4-contracts.md`](../design/phase4-contracts.md) |
| The AI layer, MCP, planner, sessions | [`phase5-contracts.md`](../design/phase5-contracts.md) |
| Hooks, Byteman, Windows, telemetry, the overlay | [`phase6-contracts.md`](../design/phase6-contracts.md) |
| Vision, pointer input, redaction, image baselines | [`phase7-contracts.md`](../design/phase7-contracts.md) |
| Threat model and security policy | [`threat-model.md`](../design/threat-model.md), [`SECURITY.md`](../../SECURITY.md) |
| Status, gaps, environment notes, walls | [`docs/TODO.md`](../TODO.md) |
| Decisions and the owner's answers | [`docs/DECISIONS.md`](../DECISIONS.md) |
| Licences of every dependency | [`docs/licence-audit.md`](../licence-audit.md) |
| Release checklist and the 0.1.0 record | [`release-checklist.md`](../release-checklist.md), [`release-notes/0.1.0.md`](../release-notes/0.1.0.md) |
| Background research (attach, channel, Swing driving, browsers, inference, vision) | [`docs/research/`](../research/) |
| How to contribute | [`CONTRIBUTING.md`](../../CONTRIBUTING.md) |
