# Threat model of the base framework

This document states the actors, trust boundaries, intentional trust assumptions and out-of-scope threats for the
Skillroy Test (Java) base framework. See [`base-framework-design.md`](base-framework-design.md) for the overall
architecture and [`../../SECURITY.md`](../../SECURITY.md) for operational guidance and vulnerability reporting.

## 1. System overview

An **agent** (Java 8) runs inside a target JVM—a desktop application under test—loaded at launch (`EMBEDDED`,
`JAVAAGENT`) or dynamically (`ATTACH`). It serves JSON-RPC and SSE on a loopback port, mutually authenticated with
HMAC-SHA256 using a per-instance 32-byte token that never travels on the wire. A **controller** (Java 25) drives
the application through its input seam, Swing/web UI, in-process verifiers, read-only data channels, and optional
bytecode hooks or Byteman rules.

A **domain pack** (controller) and an **agent module** (target)—or the built-in `base` pack for zero-code
onboarding—adapt the framework to an application, and **tenant overlays** customize a pack per deployment. A
**language model** may be used at authoring time, at bind time (`live` and `approve`), and during interactive
sessions, never in CI (`replay` enforces zero model calls). An **MCP server** exposes primitives and pack verbs as
tools for local authoring clients. Runs produce local artifacts (`run.jsonl`, bindings, ledgers, failure bundles)
and optional OpenTelemetry traces.

## 2. Actors

| Id | Actor | Capabilities without the framework | Trusted? |
|---|---|---|---|
| A1 | **Another OS user on a shared host** | Observe loopback ports and process lists; attempt to bind freed ports; plant files/links in world-writable directories (`/tmp`) or permissive parent directories | No |
| A2 | **Same-user local process without the token** | Everything A1 can do, plus read non-owner-only user files and invoke the JVM Attach API against same-user JVMs | No (note: same-user JVM attach is the platform's own boundary; see §4) |
| A3 | **Embedded web content inside the target** | Execute JavaScript inside its own page, tamper with injected page helpers, flood console/event taps, and return hostile strings/JSON to the controller or CDP bridge | No |
| A4 | **Target application data and UI content** | Render arbitrary strings, rows, events and dialogs that reach controller parsers, regexes, logs and model prompts | No (application code is under test; application data is untrusted input) |
| A5 | **Language model** | Propose tool calls, SQL queries, scenarios and pixel coordinates; susceptible to prompt injection from A3/A4 | No: proposes only, never confirms (§5) |
| A6 | **MCP client** | Call exposed MCP tools and read MCP resources using an operator-started `skillroy mcp` server | Policy-gated (`CLIENT` origin: read-only by default, never confirms destructive actions) |
| A7 | **Planner CLI (`claude` on `PATH`)** | Spawned by `skillroy author` with a temporary MCP config; writes its own CLI session transcript | Trusted as operator binary; transcript storage noted in `SECURITY.md` |
| A8 | **Remote network peer** | Cannot reach the loopback-bound agent; controller initiates outbound TLS connections only to configured model/telemetry endpoints | No |
| A9 | **Telemetry backend operator** | Reads exported OTLP span attributes | No: spans carry allow-listed metadata only, never parameters, rows, SQL or credentials |
| A10 | **Pack / module author** | Supplies `DomainPack`, `AgentModule`, overlays, hook catalogues and committed scenarios/baselines | **Yes** (reviewed code, §3) |
| A11 | **Operator** | Controls shell, environment variables, `PATH`, credentials file, agent home and CLI flags; holds the token | **Yes** (§3, local test-harness boundary) |
| A12 | **Other code in the target JVM** | Other agents, plugins or application code sharing the JVM | Non-adversarial assumption; hook/rule events are test evidence, not tamper-proof audit logs |
| A13 | **Reader of run outputs** | Reads `run.jsonl`, failure captures, bindings, ledgers and trace bundles | Mitigated via secret redaction, `MaskPolicy`/`CaptureMask`, exclusive file creation and `umask 077` guidance |
| A14 | **Model provider** | Receives masked prompts and (when `images: true`) redacted window captures | Prompts/captures masked before transmission; provider retention governed by operator model choice |
| A15 | **Other desktop windows during Robot (`V3`) runs** | May overlap target windows or receive native OS pointer events | Mitigated via double opt-in, focus/bounds checks and delivery probes (§6) |

## 3. Intentional trust assumptions

- **Local test-harness boundary.** The operator, local CLI, MCP harness and configured model session are part of
  the test-authoring harness. Diagnostic error details from target queries and UI/DOM locators are returned to
  help authors debug scenarios, while credentials and masked fields remain redacted from logs, prompts and saved
  artifacts.
- **Packs, modules and committed fixtures as reviewed code.** `DomainPack`, `AgentModule`, `DomainPackOverlay`,
  hook catalogues, committed scenarios, bindings and image baselines are reviewed repository artifacts. Where pack
  policies (`InputVeto`, `ClassDeny`, `BrowserMask`, `MaskPolicy`) throw or misbehave, the framework fails closed
  and bounds execution time so a buggy pack does not harm the target host.
- **Operator environment and build toolchain.** Environment variables (`ANTHROPIC_API_KEY`, OTLP header variables,
  `PATH`, `JAVA_HOME`), the local Maven repository, `credentials.yaml`, and jars explicitly passed to the agent or
  controller are trusted when owned by the current user and not writable by others (`JarTrust`, `SecureFiles`).
- **Filesystem permissions.** Owner-only files use POSIX `0600`/`0700` or single-owner Windows ACLs, and are
  refused on volumes that support neither (such as FAT/exFAT, with a warning fallback only where documented for
  read-only developer setups). Output writers refuse directories owned by other users or writable without the
  sticky bit and never write through pre-existing files or symlinks.

## 4. The token and attach boundary

The 32-byte per-instance token stored in an owner-only discovery/token file is the HMAC key for the control
channel and represents authorized control of the agent within its configured gates (read-only mode, `ProductionMarker`,
`InputVeto`, and `confirm` on destructive primitives).

- Each agent startup generates a fresh random token and session ID; captured MACs cannot be replayed against
  another JVM or a restarted target.
- The enable flag (`skillroy.agent.enabled=true`, `SKILLROY_AGENT_ENABLED`, or explicit `EMBEDDED`/`ATTACH`
  options) is checked before any thread or socket is created, and cannot be enabled via `-javaagent` option
  strings inherited through `JAVA_TOOL_OPTIONS`.
- **Same-user attach:** Any process running under the same OS user can attach to a local JVM unless the target was
  launched with `-XX:+DisableAttachMechanism`.

## 5. Model and MCP client rules

Models and MCP clients **propose and never confirm**:

- **Origin stripping and policy gates:** Calls originating from `MODEL` or `CLIENT` strip `confirm`, `force`,
  `redact: false`, `mode: ROBOT` and per-call `via: ROBOT`. Destructive primitives, module uploads, hook
  installation/removal/stubbing and Byteman rule submission/unloading are never offered to models or MCP clients.
- **SQL guard and allow-list:** Model-written SQL (`llm-sql`) runs only as a single read-only `SELECT` validated
  by `SqlGuard` and `SqlAllowList` (`CALL`/`EXEC` procedures are refused on all channels), verified in-process,
  and marked `REQUIRED` until approved by a person. CI `replay` never calls a model.
- **Credential isolation and masking:** Credentials live exclusively in `credentials.yaml`, are never serialized
  into bindings or prompts, and cannot be referenced as raw `$slot.secret` arguments by a model or MCP client.
  Row masks, secret-key rules and event-publish masks redact sensitive fields before prompts, snapshots or logs
  are built.
- **Vision and screen captures:** Captures are painted from the target's own Swing windows (`PAINT`) and redacted
  inside the target JVM (`CaptureMask` + password-field blanking) before leaving the process. Pixels are sent to a
  model only when `images: true` is enabled in `inference.yaml`. Glass captures (`mode: ROBOT`) are never sent to
  a model or MCP client. Clipboard paste/cut/copy chords and middle-click paste are blocked on model/client routes.
- **Input vetoes:** Components matched by an `InputVeto` reject input across all Swing and pointer routes,
  regardless of `force`.

## 6. Trust boundaries and mechanisms

| Boundary | Mechanism |
|---|---|
| Controller ↔ Agent (`/rpc`, `/events`, `/ping`) | Loopback-only HTTP/1.1 listener; mutual `Skillroy-HMAC` with per-request nonce and `X-Skillroy-Body-Sha256` verified on headers before reading request bodies; bounded header buffer (16 KiB), body cap (8 MiB), connection cap (`httpThreads`), deadlines, `Host`/`Origin` checks, and client terminal *lost* state on session mismatch |
| Discovery & owner-only files | Per-user owner-only discovery directory; strict owner/permission checks on options files, tokens, `credentials.yaml`, `inference.yaml`, `telemetry.yaml` and output directories; stale discovery cleanup |
| Agent ↔ Host JVM | Isolated child-first class loader; daemon `skillroy-*` threads; `invokeLater` for EDT actions and bounded `invokeAndWait` for reads; `QuiescentPoint` budget admission for `APP_QUIESCENT` reads; bounded named worker lanes (`ThreadBroker`) with query cancellation; `ProductionMarker` gate before `onTargetReady` |
| Input veto (`InputVeto`) | Checked on resolved Swing targets, ancestor chains up to the window, popup invokers, ENTER default buttons, posted tasks, routed pointer presses/releases and Robot coordinates; fails closed |
| Bytecode hooks & `Spy` (optional) | Single bootstrap-visible `Spy` class with identity-held weak handler slot; relocated Byte Buddy loaded only when `optionalModules` includes `skillroy-agent-hooks`; package/loader/code-source/class-name deny-lists; class size caps (256 KiB / 2,000 methods); byte-verified restore on detach |
| Byteman (optional) | User-supplied jar (`byteman.jar`, checked by `JarTrust`) loaded in process with `listener:false` only on confirmed `rule.submit`; curated `RuleHelper`; identifier lint and class-count bounds; honors class deny-list |
| Data channels | `SELECT`-only (`StatementGuard` in agent, `SqlGuard` in controller); `CALL`/`EXEC` refused; row caps (<= 200), statement timeouts, cancellable lanes, `MaskPolicy` / `RowMask` redaction |
| Embedded web views | Loopback CDP endpoint ownership check and external connect deadline; Playwright setter gating by version (1.60+ default floor unless `skillroy.cdp.allowDefaultOverrides=true`); bounded page evaluations and console budgets; whole-browser masking (`CaptureMask` / `BrowserMask`), with view-less browsers masked while any mask is registered unless released |
| Controller ↔ Model / MCP / Telemetry | Environment-only API keys; prompt/result scrubbing and size bounds; session/scenario/job `BudgetMeter`; OTLP off by default, loopback `http` or `https` only, auth headers over `https` only, allow-listed span attributes |
| Robot tier (`V3`) | Double opt-in (`robot=true` agent option + `--allow-robot` CLI flag); refuses points outside showing target windows, minimized windows or covered target windows; checks target focus on EDT before press; pairs press/release in `finally`; delivery probe reports `NOT_DELIVERED` when blocked (e.g. macOS without Accessibility permission) |

## 7. Out of scope

- Compromised operator account, shell, JDK, Maven cache or reviewed pack/module code.
- Physical or administrative host access by another user.
- In-JVM code using reflection (`setAccessible`) against agent internals.
- Operator-induced denial of service against their own target (e.g. hooking a hot loop).
- Target listeners outside the agent (such as an unauthenticated JDWP port).
- External model or telemetry providers' internal data handling once requests arrive.
- Swing key bindings/accelerators outside the target/root-pane chain (packs should veto the enclosing window or
  bound control) and OS window changes occurring in the sub-millisecond gap after an EDT focus check on the Robot
  route.

## 8. Unverified platforms and configurations

- **Windows hosts:** NTFS ACL owner-only file and directory checks, localized non-English built-in Windows account
  names, `bin\skillroy.bat`, `cmd.exe` planner launching, and Windows UIPI / Session 0 behavior are unit-tested
  against simulated inputs on Linux/macOS and have not yet been executed on a Windows machine.
- **Desktop display variants for `Robot` (`V3`):** Verified on the Linux test image's Xvfb display; macOS with
  Accessibility granted, Windows desktops, Wayland/XWayland, locked sessions and HiDPI scale factors != 1 are not
  verified in CI.
- **Browser & driver combinations:** `jxbrowser` has been verified on JxBrowser 8.18.3 (macOS arm64, JDK 25);
  other JxBrowser lines/OSes, Playwright versions other than 1.63.0, and JDBC `Statement.cancel()` behavior on
  drivers other than HSQLDB should be verified against your target stack.
