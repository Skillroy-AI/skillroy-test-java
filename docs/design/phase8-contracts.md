# Phase 8 contracts: the second domain's wave 1 (the input veto, the effective visible area, routed input into heavyweight containers and AWT controls; `APP_QUIESCENT`, named lanes, query cancellation, positional columns and the retired procedure call; event payloads masked at publish, the view-less browser and the Playwright line; hook ownership by code source and the class deny-list; the warning channel, acknowledgement by input, composite primitives and rung ladders; list filters, captured facts, the `events` root and plan version 7; pack capabilities, `on_prompt` values, suite-scoped pre-flight and configuration as a capability)

**Status: specified 2026-10-19, revised the same day after the contract review (three lenses, each with a sceptic; 48
findings applied, 1 refuted) and rechecked once (15 further corrections); not yet built.** Written after the nine reader maps of 2026-10-19 (kept in the session
notes, not in this repository; `map A §n` to `map I §n` below), which verified every claim of the second domain's wave
1 against the code at `fcfb8dc` (every `.java` file of `main` equals the tag `v0.1.0`, the released base), and assembled
from seven section fragments written in parallel under one set of shared defaults by the authors EA (§3), ET (§4), EW
(§5), EH (§6), ES (§7), EX (§8) and EP (§9), harmonised by the editor EE, who wrote §1, §2 and §10-§18. Written in the
manner of `phase7-contracts.md`: the contract is changed first, then the code, and the build marks every passage it
builds or deviates from with an "As built (Phase 8, <TAG>)" note under a two-letter implementer tag (§2.5); the marks
into the earlier contracts read "(Phase 8, specified 2026-10-19, not yet built; P8 §n)" when the build starts and
"(Phase 8, built …; P8 §n)" after it. Section references `P1 §n` to `P7 §n` are to the seven earlier contracts, `design
§n` is the design overview `base-framework-design.md`, `guide §n` is `../guides/integration-guide.md`, `threat model §n`
is `threat-model.md`. Decisions taken here are **D501 to D642** (§17: D501-D623 are the fragments' decisions in section
order, D624-D635 the editor's, D636-D642 the reviser's after the contract review); amendments to the frozen Phase 1
contract are **A67 to A99** (§10), the next free number
after P7's A61-A66 (none of A67 onwards was used by the Phase 7 build); gaps continue from **126** (§16.2: 126-150 are
opened here); the security delta review's findings start at **S149** (§15); the plan version becomes **7** (§8.2); the
bindings file version stays **2**, the script library's version **2**, the fingerprint format **1**; the protocol stays
**0.2** (§2.1, D624). The questions of §18 are answered provisionally by the orchestrator and recorded as decision
**B38** (`../DECISIONS.md`); the build's, the review's and the fix wave's decisions will be **B39**; the security delta
review's sign-off, over everything changed since `223c5fb` (the tree B31 covers), will be **B40** (§15). The second
domain compiles against `v0.1.0`: nothing here breaks a pack or a module built against that tag (§2.3), and the sample
is extended behind launch properties and agent options only (§12).

What Phase 8 is for, in one paragraph. Phases 1 to 7 built the base and released it as 0.1.0; a second domain pack and
its agent module were then built against that release outside this repository, and their wave 1 (rows 9-13, 18-22, 24,
28, 29 and 105-125 of `../TODO.md`, "Open SPI and DSL gaps") names what the base could not say or do for a real desktop
application: a control the agent must never touch, an engine thread with an idle point, slow provider reads that must
not block the application lane, personal data in module events, a browser that has no view yet, a hook target shared
with a third-party jar, an input the application takes but consumes later, a flow whose next question depends on the
application, a count or a delta over the application's own events, and an environment fact only the pack knows. Phase
8 answers every high and medium row and the cheap lows with **generic SPIs, result conventions and DSL forms, each
proven on the sample lending desk** (§12), and defers six rows with the reason and the seam (§1). Every default is
restrictive: a veto fails closed, a quiescent task is admitted by budget and never pre-empted, an unknown warning fails
the step, a lost event window fails the read, an unknown `on_prompt` value fails to load. **Nothing changes for a
target or a pack that uses none of the new SPIs, keys or options, except two privacy protections that apply by default
because that is their point**: every event payload passes the registered `MaskPolicy`s and the secret-key rule before it
is stored (§5.1), and a browser the agent has no component for is masked while any mask is registered (§5.2); the other
behaviour changes are marked **Behaviour change** where they stand and listed in §11. `replay` still makes zero model
calls, and nothing in Phase 8 needs a model key.

---

## 1. Scope and non-goals

Phase 8 delivers:

- **Swing input** (Java 8, `skillroy-agent-swing`, one new SPI in `agent-api`, §3): the SPI `InputVeto`, asked on
  every input route that resolves a target (twelve of the thirteen; the `ROBOT` click rung is refused before
  resolution) after the clickable test and before the modal test, again inside every posted
  task and per event in the routed pointer, with an ancestor rule to the window, failing closed and never skipped by
  `force`; readers flag `vetoed` and the recorder never offers a vetoed component (gap 105); "on screen" as a non-empty
  effective visible area with the viewport exemption, `INVALID_PARAMS collapsed`, locators and snapshots skipping
  collapsed components, the pointer's component form refusing a point its component does not show (gap 116); the routed
  pointer sourced from the innermost unclaimed heavyweight under the point, with the hit-test descending into it (gap
  115); AWT `Button` and `Checkbox` fired semantically on the ACTION and COMPONENT rungs and read by the dialog reader
  (gap 122);
- **threads, lanes and the data channel** (§4): `ThreadPolicy.APP_QUIESCENT` backed by the base-owned `QuiescentPoint`
  a provider drives from the application's idle callback, admission by budget and the caller's remaining time, the one
  narrow exemption to the forbidden-thread rule, an overrun penalty, `agent.info.threads.quiescent` (gap 10); base-owned
  named lanes, `ThreadPolicy.LANE`, `DataSourceProvider.lane()`, `data.query`'s hand-off and per-source gate (gap 9);
  `QueryControl` and `enforcesTimeout()`, the JDBC statement sink (gap 11); column names derived from the statement's
  select list inside the target, the zero-row width defect fixed, `DataSourceProvider.columns(table)` for the allow-list
  start check (gaps 12, 117); the procedure call retired honestly on every path (gap 119);
- **privacy and the web side** (§5): every event payload masked at publish by the registered `MaskPolicy`s under
  `event:<topic>` and by the secret-key rule, bounded, never failing publish, with `agent.info.events` and the public
  `OutputScan` helper (gap 109); a view-less browser masked while any `CaptureMask` or `BrowserMask` is registered, the
  new SPI `BrowserMask`, a masked browser's events withholding page data, the selection rules, the action answers
  without page data (D637) and the CDP path's hand-offs (gap 110); the Playwright line detected by field presence, the sub-1.60 opt-in, the driver leak fixed, the
  floor stated (gap 121);
- **hooks** (§6): `target.codeSource` and qualified `packages` entries, `CodeSources` reading a location's fields only,
  code sources reported by the probe, `touched`, `hook.health.classes` and `skillroy hooks status` (gap 113); the
  catalogue's `deny:` in the `ClassPattern` grammar, the agent option `hooks.deny`, the SPI `ClassDeny`, the held
  add-only deny with the install echo, Byteman honouring it before it loads (gap 114);
- **semantic input** (§7): the warning channel in the RPC envelope (`RpcResult.warnings`, `Invocation.warn`), the
  standard semantic-input result (`InputResult`), the step modifiers `accept` and `minRung`, `DomainPack.warningCodes()`
  (gap 18); `inputId` minted by the module, late acknowledgements from the seam, `SyncPolicy.ackByInputId` (gap 107); the
  composite-primitive rule with `CompositeResult`, nested-prompt reconciliation and the secret-echo check on every call
  with secret parameters (gap 19); call-level rung ladders (`rungs`, `minRung`), the rung chosen agent-side, enforced by
  the executor and pre-checked at run start, `DeviceAdapter.rungs(action)` (gap 22);
- **expectations, events and plan version 7** (§8): list filters `[*field=value]` and `[*field~regex]` with dotted
  filter fields, counted by the existing `size` matcher (gap 108); the base verb `captureFacts` and deltas against a
  captured count (gap 20); `anyOf` and `containsWhere` under one regex budget per evaluation (gap 118); the base fact
  root `events` over the agent's ring for the run's window, failing on loss, the `[json]` path segment, negation as a
  count (gap 21); `{has: scalar}` in an event wait through the shared Java 8 `PayloadMatch`, `waitFor`'s match validated
  (gap 120); **one `plan.json` version bump to 7**, strict step and call keys from 7 on;
- **the pack** (§9): `DomainPack.capabilities()` with `PackCapability`, ids `<packId>.<name>`, source `pack:<packId>`,
  evaluated by every host after the contributors' merge and honoured in `replay` (gap 106); `on_prompt.unexpected` is
  `fail` or `pack`, anything else refused at load and in the plan reader (gap 111); `PreflightCheck.scope()`
  `SCENARIO|SUITE` with the host-owned `SuiteCache` keyed by pack, version, id, environment and agent session, one
  `Preflight` helper for every host (gap 112); the decision that application configuration a scenario depends on is a
  capability, in `requires:` or a guard, no `preconditions:` key (gap 24); `re-resolve`'s agent-only `requires` check
  fixed in passing;
- **the protocol and the catalogue** (§10: A67-A99, no new error code, no new topic, four new sample agent capability
  ids and one pack-side id), **safety and data** (§11, and `threat-model.md` extended), **the sample** (§12: desk, agent
  module 0.3.0, pack 0.7.0, hook catalogue version 2, thirteen new scenario files: six committed (five top-level and
  `scanner/`'s one), one test-only positional scenario and six negatives, with six new plans), **tests and acceptance**
  (§13), **exit
  criteria** (§14) and **the security delta review** (§15, B40).

**Non-goals.** Named so that their seams are respected, not built:

| Not in Phase 8 | Seam reserved or rule stated here | Phase |
|---|---|---|
| Gap 13, session-scoped data reads (a guarded procedure `CALL` followed by a `SELECT` on a session table on one connection) | not taken: the second domain's own agent primitives cover its two cases today (row 13: they call the application's in-process pricing and waiver code), and admission of any procedure call is this gap's "prepared read" (a provider-declared unit, by id, run as one read-only, rolled-back transaction), not a lone `CALL`: §4.5 retires `SqlAllowList.procedures` to a reserved field required empty and states why admission is not taken now (a definer's-rights routine writes through a read-only account, the read-only flag is a hint on two drivers, a rollback undoes neither an autonomous transaction nor an outside effect) (D533) | later, with a security pass |
| Gap 28, pack-defined artifact kinds | not taken (low): `web: {trace: true}` kept on failure is the second domain's workaround; an SPI in `DomainPack` that produces a named artifact is the seam; `artifacts:` keeps logging and ignoring an unknown kind | later |
| Gap 29, a pack-contributed CLI subcommand | not taken (low): the second domain ships its exporter as a standalone `main`; a `ServiceLoader` of a small command SPI is the seam; `Cli.java`'s command list is unchanged | later |
| Gap 123, the text of custom-painted components | not taken (low): `ComponentText` reads the standard classes; a painted-text SPI consulted by snapshots, locators and marks is the seam; the sample's custom components keep their `LocatorResolver`/`DialogTextExtractor` route | later |
| Gap 124, a `DISPATCH` click at a region of a component | not taken (low): a region acts through the routed pointer (`pointer {by, region}`, P7 §8.1) or Robot; a non-routed `DISPATCH` at a region with the component as source is the seam | later |
| Gap 125, a posted-key rung | not taken (low): `swing.key` and `swing.type` dispatch without posting, so an application's event-queue hooks never see agent keys; an `EventQueue.postEvent` rung with gap 77's focus check is the seam; Robot keys stay out (P7 D345) | later |
| `web.*` input into a browser component an `InputVeto` names | not refused in Phase 8 (gap 126, D509): the web routes act through the page; only `web.pointer` reads the browser's component, for the modal check (`ComponentReads.stateOf`, the seam if the owner takes it, Q3); `web.dom.*`, `web.dispatchEvent` and the CDP path read no component. A veto on a window or panel does **not** cover `web.*` input into a browser inside it (the guide, `SECURITY.md` (a) and threat model §7 say so) | later, **medium** (the guide advises vetoing a window, and a window may hold a browser) |
| An option-configured veto (`inputVeto=<ids>`), key bindings, mnemonics and accelerators followed by the veto, other AWT controls, custom scrollers | gaps 127-130: the SPI, the "veto the window or the bound control" rule and `Clicks.semantic` are the seams | later, low |
| A bound on `APP` calls in flight; provider-backed lanes; `open(QueryControl)`; a general `Invocation.onCancel`; a `data.columns` metadata primitive; the controller's `JdbcDataChannel` cancelling a statement; MCP `data_query` through `DataChannels` | gaps 131-137 (D528, D529): `APP` stays unbounded by decision (Q13); the lanes, `QueryControl` and `columns(table)` are the seams | later |
| The CDP path's own events through a pack's row masks; events published before a policy is registered | gaps 138, 139 (D551): a masked page's own events are withheld on both paths; parity of `rowMasks()` on `PathEvents` is the seam; the events of the module-install window on attach are masked by key only (§5.1) | 138 next phase, 139 later; low |
| A guard transformer behind Byteman's for a denied subtype loaded after a `^`/`INTERFACE` submit; loosening a deny without a restart | gaps 140, 141 (D569, D564): the residual P6 §7.3 already records, and the add-only rule | later, low |
| The conditional planned call (`when`); session step modifiers; a composite's branch as a fact | gaps 142-144 (D584): the composite rule of §7.5 covers the second domain; `when` is M-L across sessions, export and cleanup | later, low |
| Quantifiers `some`/`every` over list elements; the `events` root on a non-agent web path; projection in expectation paths; a `json:` matcher; an `expectNoEvent` verb | gaps 145-147 (D598, D603, D601, D607): `size`, `containsWhere` and `[json]` cover the stated needs; negation is a count | later, low |
| Step 0b in `re-resolve` and `caps`; credentials for `caps --pack`; a TTL on a `SUITE` answer; named pack prompt policies (`promptPolicies()`) | gaps 148-150 and D615: `pack` is the explicit spelling of the default and an unknown name is already refused, so names can come later without a break | later, low |
| A `0.3` protocol minor; a new error code; a new base event topic; a new core capability id | never in this phase (D624, D523, D594): every change is additive and the major is in every bindings fingerprint; the three safety-relevant parameters are acknowledged by an echo instead (§2.1) | – |
| Windows, macOS and Wayland peers for routed AWT events; a Playwright line other than 1.63.0; JxBrowser's view-less browsers; `Statement.cancel()` on drivers other than HSQLDB; a `SecurityManager` target; a real application server's code-source URLs | specified where the maps and the JDK's sources reach; verified nowhere here (§13.3) | when a host exists |

Phase 8 adds **no module** (agent or controller), **no third-party dependency** anywhere, no jar to the agent home
(§2.7), and no sample module: the lending desk, its agent module, its pack, its scenarios and the web host sample grow.

---

## 2. Versions, compatibility, build and packages

### 2.1 The wire protocol stays 0.2, and the echo rule for safety-relevant parameters (D624)

`Protocol.VERSION` stays `"0.2"` (D262, D299 restated): every change of §3-§9 is an added primitive, an optional
parameter, an optional result field, a new `data` key on an existing error code, a capability id, an SPI type or a
default method; `/schema` and `/capabilities` tell a controller what the agent has; `fromJson` of every answer type
stays lenient; **no new error code and no new topic** (the sample's `staff.signedOn` is the sample's, not `Topics`).
The major is in every committed bindings file's fingerprint (`skillroy.protocolMajor`), so it never moves; a minor
would buy nothing (`/ping` compares majors). Mixed versions stay unsupported (guide §4.4): a 0.1.0 agent under a 0.2.0
controller, or the reverse, is not a configuration the base promises anything for.

**The echo rule.** A 0.1.0 agent ignores a parameter it does not know (`Dispatcher.java:195-205`). Where the controller
relies on the agent *applying* a new parameter whose omission would loosen a protection, the agent echoes it in its
answer and the controller fails closed without the echo; where a new parameter loosens nothing, no echo is needed.
Applied:

| Parameter or field | The protection | How the controller knows the agent applied it |
|---|---|---|
| `hook.install`'s `catalogue.deny` (§6.6, §6.9) | a class the pack denies must not be instrumented | the answer's `catalogue.deny` (an empty list on a new agent holding nothing; no key on an old one), and `hook.health.deny.catalogue` on the `alreadyInstalled` path; missing: ERROR / ENVIRONMENT "upgrade the agent" (D565) |
| `target.codeSource` on a hook spec (§6.3) | a hook must bind the named jar's copy only | `HookTarget` stays strict: a 0.1.0 agent answers `INVALID_PARAMS` for the whole install, which the installer maps to ERROR / ENVIRONMENT with an upgrade hint (D560) |
| `minRung` forwarded to a ladder primitive (§7.6) | a required rung must never be acted through a shortcut | the result's `minRung` echo; absent or different: FAIL / ASSERTION (D587) |
| `web.browsers.masksRegistered` (§5.2) | an unmapped page may be a masked view-less browser | an absent field counts as true on the CDP path (fail closed, D548) |
| `{has: scalar}` in `sync.await`'s `match` (§8.9) | none loosened: a 0.1.0 agent never matches and the wait ends `SYNC_TIMEOUT` | no echo (D606) |
| `InputVeto`, `APP_QUIESCENT`, `LANE`, `QueryControl`, `BrowserMask`, `ClassDeny`, `columns(table)` | agent-side rules a layer registers or a module declares; a layer that uses one needs a Phase 8 agent, which it gets by installing on one | no controller parameter: the agent is the enforcer |

### 2.2 `plan.json` version 7 (D625; §8.2)

One bump for the whole phase, owned by §8 and landed as the phase's first commit: `Plan.VERSION = 7`, the compiler
always writes 7, the reader accepts 1 to 7 and names each Phase 8 item it refuses below 7 ("needs a version 7 plan":
a list filter, a dotted filter field, `[json]`, `anyOf`, `containsWhere`, `{has}` in a match, a step's `facts`, a
call's `accept`, `rungs` or `minRung`), and **from 7 on refuses an unknown step or call key** (`Plan.STEP_KEYS_V7`,
`Plan.CALL_KEYS_V7`; today an unknown key is silently dropped). The step-modifier grammar of §7.3 is one grammar: a
YAML step holds exactly one verb key plus the reserved modifiers `accept` and `minRung`, which compile into the step's
planned calls (`accept` into every call, `minRung` into the calls that declare a ladder), so the reserved keys are
**call** keys in the plan skeleton and no step key is reserved for §7; `captureFacts` (§8.7) takes no modifier, as
`expect`, `aiAct` and `aiExpect` do not (the compiler refuses a modifier on all four, §7.3). New base verb names become
reserved (`captureFacts`; `accept` and `minRung` are reserved modifier names), and a pack that declares one fails at
compiler construction (a break for such a layer; §16.1's P2 §3.3 row, Q45). The `on_prompt.unexpected` value check of
§9.2 is a reader rule whatever the version, not a gate. Records keep their old constructors (§2.3). Every committed plan
and bindings hash is regenerated **twice, once per cause** (D626, D641): at the skeleton commit for the version alone
(every plan read back is written as version 7, `Plan.java:1057`, so the byte-for-byte and `planHash` comparisons of
`PlansUpToDateTest`, `BranchPlansUpToDateTest`, `BindingsUpToDateTest`, `PlanCompilerTest` and the web host's
`PlansUpToDateTest` would otherwise be red from that commit on), and in step R for the pack version, scenario A's
`requires` and the new plans (§2.8).

### 2.3 Agent-API and controller-API compatibility with `v0.1.0` (D636; `settled` §4, map I §2.6)

The second domain's pack and module compile against `v0.1.0`. Rules, each followed by every section:

- **Agent API (Java 8): grows by default methods, new types and appended enum constants only.** `ThreadPolicy`'s
  pinned order becomes `CALLER, EDT_SYNC, EDT_ASYNC, WORKER, APP, APP_QUIESCENT, LANE` (`ValueTypesTest`, D524); every
  new SPI method has a default (`ThreadPolicyProvider.quiescentPoint`, `DataSourceProvider.query/5`, `enforcesTimeout`,
  `lane`, `columns`, `ThreadBroker.callQuiescent`, `declareLane`, `callOnLane`, `Invocation.warn`, `EventBus.publish/4`,
  `DeviceAdapter.rungs`, `HookEngine.deny`, `AgentModule.info`); every new SPI is a new interface (`InputVeto`,
  `QueryControl`, `BrowserMask`, `ClassDeny`) that no existing fake must implement; the new types `QuiescentPoint` and
  `CodeSources` are classes; `Lane` is an interface the core implements and a layer only holds. `DefaultMethodsTest`,
  `SwingSpiDefaultsTest`, `WebSpiShapeTest` and `ValueTypesTest` pin it.
- **Controller records grow by components with the older constructors kept**: `Plan.Step` (`facts`), `Plan.Call`
  (`accept`, `rungs`, `minRung`), `PlannedCall` (the same three), `SyncPolicy` (`ackByInputId`; its first kept
  constructor), `StepResult`, `RunResult`, `StepOutcome` (`warnings`), `RunOptions` (`suite`, 14th), `SessionOptions`
  (`suite`, 15th), `CdpOptions` (`allowDefaultOverrides`), `Scenario.Step` (`modifiers`). A record pattern in a layer's
  code still breaks on a new component (minor; stated).
- **`DomainPack` grows by default methods** (`capabilities()`, `warningCodes()`), each forwarded by
  `DelegatingDomainPack` and enforced by `PackOverlayTest`; `PreflightCheck.scope()` and `AgentConnection.agentSession()`
  are defaults too. Hand-written delegating packs are not enforced and already miss members (map I §2.6): stated.
- **Reserved names**: the verb `captureFacts` and the modifiers `accept`, `minRung` join `aiAct aiExpect pointer
  expectImage call` (P2 §3.3); the base fact root `events` is **shadowed** by a pack root of the same name, so no pack
  breaks on it; the source-id prefix `event:` is reserved for the ring pass (§5.1); the pack capability namespace
  `<packId>.` is stated with its equality rule (§9.1.6).
- **Wire types and the JSON codec** stay in `skillroy-protocol`, shared: `PayloadMatch`, `CallWarning`,
  `InputResult`, `RungResult`, `CompositeResult`, `ClassPattern`, `SelectLabels`, `SqlTokens` (a move) are Java 8 there;
  the Java 8 classes `RpcResult` (`warnings`), `HookTarget` (`codeSource`) and `HookProbe` (`codeSources`) grow by a
  constructor each, the older kept; `toJson` writes the new key only when set and `fromJson` reads it leniently
  (`HookTarget` stays strict on unknown keys by D560).

### 2.4 Module, pack and catalogue versions (D626)

| What | Today | Phase 8 | Bumped where |
|---|---|---|---|
| Reactor | `0.2.0-SNAPSHOT` | unchanged | – |
| `LendingModule.VERSION` (the sample's agent module) | `0.2.0` | **`0.3.0`** (one bump: a new SPI implemented, three new primitives and four new capability ids, a second data source, a second device, the point, the lane) | step R |
| `LendingPack.VERSION` | `0.6.0` | **`0.7.0`** (one bump: `capabilities()`, `warningCodes()`, `ackByInputId`, three verbs, the `SUITE` check, the catalogue) | step R |
| `DataModule.VERSION` | the stale literal `"0.1.0"` | **`"0.2.0"`** (D534; the literal is the contract's own number, so it stays a literal) | step R |
| `WebHostModule.VERSION` (the web host's agent module) | `0.2.0` | **`0.3.0`** (`webhost.mask` grows two values and two `BrowserMask`s) | step R |
| the sample's hook catalogue `lending.yaml` | `version: 1` | **`version: 2`** (a qualified `packages` entry and `deny:`) | §6's package (EH), with its hash pinned by its tests |
| `LendingBranchOverlay.VERSION`, `WebHostPack.VERSION`, `SkillroyMcpServer.SERVER_VERSION`, `WebLib.VERSION`, `BindingsFile.VERSION`, `EnvFingerprint.VERSION` | as today | unchanged | – |
| `skillroy-agent-web` (`web-module.properties`) and `skillroy-agent-swing` (`swing-module.properties`) | `${project.version}` | follow the POM: no module bump, although §5.2 changes the web module's behaviour by default (`settled` §5 asks for a bump "if their behaviour changes"; there is no literal to bump); the behaviour changes are listed in §5.4 and §11 | – |
| the Anthropic SDK, Playwright, Byte Buddy, HSQLDB | as today (`../licence-audit.md`) | unchanged; the Playwright line stated as 1.63.0 with the floor of §5.3 (an audit note, no new dependency) | – |

**New capability ids only where a gate needs one**, and each agent id changes the five capability goldens, regenerated
once in step R: `lending.engine.view` (§4.1), `lending.signOn`, `lending.scanChecked` (§7.5), `lending.scanner.physical`
(§7.6); `lending.returns.ceiling` is pack-side and never in a golden (§9.1). No core id (D523, D594).

### 2.5 Build conventions and the seven work packages

As P7 §2.3: Java 8 language and API with no third-party class in `agent/**` and the Java 8 sample modules (the B4
carve-out unchanged), Java 25 in `controller/**` and the Java 25 sample modules; the Apache-2.0 header on every new
file (`ci/license-headers.sh`); no `mvn install`, offline Maven; every display test skips when headless and runs with
`ci/run-in-linux.sh`; the lending vocabulary only, and nothing that names the second domain. Seven implementers build in
parallel worktrees under the tags **EA** (§3), **ET** (§4), **EW** (§5), **EH** (§6), **ES** (§7), **EX** (§8) and
**EP** (§9); the work packages, the file ownership, the merge rules and the integration order are in the session
notes' `contract-notes.md`. The integration order: EX's plan-7 skeleton and EP's early API commit (the pack defaults,
`warningCodes()` included, the records with kept constructors, `AgentConnection.agentSession()`) first, each reactor
green; then the agent-side packages EA, ET, EW (EW's publish-side masking before EX's query-side work and before ES's
`publish` overload), EH; then the controller bodies ES and EX; then **step R** (§2.8); then the marks, the matrix, the
code review, the security delta review and B39/B40. Each package writes "As built" notes under its tag and never
rewrites another's passage; the orchestrator merges by tag and numbers the build's decisions from D643 at
integration, and assigns any further amendment id from A100 (never an implementer). **The branch-truth rule (D641):**
a pinned assertion that several packages change (a type list, a registration count, a scenario or negative list, the
JUnit glue's id list) is edited by each package on its own branch to its own branch's truth, and the integrator resolves
the literal conflicts to the final values at merge (`contract-notes.md` §3 names the files and the final values); a
package that adds a committed scenario compiles that scenario's plan on its own branch; the windows that stay red until
step R are named there too (EP's scenario A files, ES's recordings and exports, the golden check of the image on ET's
and ES's branches) and nothing else is expected red.

### 2.6 Threads and bounds

The agent's rules are unchanged (P1 §5, §6; design §3.7; P7 §2.4): every read is one bounded `EDT_SYNC` task with the
call's remaining time, every action is posted, no wait lacks a deadline, nothing runs on a thread the application
forbids **but the one exemption of §4.1**, and the dispatcher's timeout (120 s cap) is the backstop. Phase 8's bounds,
gathered (each section's own table has the detail):

| Where | Bound |
|---|---|
| `InputVeto` (§3.2) | EDT only, inside the call's read or posted task; at most 64 ancestors per ask, memoised per call and route; a throwing veto refuses; the text one line of 200 characters; no new timeout |
| `Visibility` (§3.3) | EDT only; O(depth) per component, O(nodes) per walk |
| `swing.pointer` ROUTED (§3.4) | at most **101** events per call (P7's 100 plus the exit on a source change); every task checks `cancelled`, the modal state and the per-event veto; the balance into a heavyweight leaf adds at most one `MOUSE_EXITED` |
| `Clicks.semantic` (§3.5) | one dispatched `ActionEvent`/`ItemEvent` inside the posted task |
| `QuiescentPoint` (§4.1) | allowance 1..1000 ms, capacity 1..16, one task per `runOne`, FIFO head only, admission by budget and the caller's remaining time, exactly once or never, never interrupted; a 2x overrun penalised; a task's default budget 50 ms; `await`'s `timeoutMs` at most 120,000 and refused at once on the application's thread (D639) |
| lanes (§4.2) | at most 8 per agent, 1..4 threads and a queue of 0..16 each (at most 32 lane threads), idle end 30 s, retired with the module and on close; a running task interrupted on expiry (best effort) |
| `data.query` (§4.2, §4.3) | `WORKER` as today; the read on the source's lane or a nested worker task; the module waits `remainingMs - 250` (at least 1 ms); on expiry the cancel actions (at most 4, 2 s each) run on their own nested worker task and the module answers inside the reserve (D638); one `columns(table)` call per read; `SelectLabels` linear, depth 32, 1000 items |
| the ring pass (§5.1) | the publishing thread, outside the ring lock; at most 10,000 scalars per event, the rest `<redacted>` with `truncated: true`; the freeze's depth 184; then the character bound as today; a throwing policy logged once per class; a pass over 50 ms counted (`slowPasses`, `maxPassMs`) |
| `BrowserMask`, `SeedWalk.masked` (§5.2) | an agent worker at rebuild and before a masked-read check; one bounded EDT read per rebuild for all components, only while masks are registered |
| `ConnectSupport` (§5.3) | three `getField` calls once per JVM on the first attach's Playwright thread |
| `CodeSources.of`, `target.codeSource` (§6.2-§6.4) | one `getProtectionDomain` per candidate copy per call on the worker, field getters only, the name cut at 200, control characters replaced; the regex at most 200 characters, compiled once per spec per call and matched through `BoundedRegex` (`BUDGET_MS`; too slow refuses the copy, D640); 16 names in a probe, 4 in a refusal |
| `ClassPattern`, `ClassDeny` (§6.3, §6.5) | at most 200 characters, linear match, 64 per source, 256 held per engine; a `ClassDeny` on agent workers under the engine's or the module's lock, never blocking, the reason cut at 200 |
| `Invocation.warn` (§7.1) | 16 per call, message 500 characters, data 2048 characters, the bounds applied after the dispatcher's redaction; non-blocking |
| late acknowledgements (§7.4) | a pending table of 256 entries per module, cleared on detach; one publish per input |
| composites, the secret-echo walk, ladders (§7.5-§7.7) | `answered` 32, `branch` 64 characters, waits within `remainingMs() - 250`; the walk 10,000 scalars and depth 32, beyond fail closed; 2..8 rungs, names 32 characters; `accept` 32 codes per step |
| list filters, `containsWhere`, `[json]`, `anyOf` (§8.3-§8.5) | one 1 s `SafeRegex.Budget` per path resolution and one per matcher evaluation (at most 2 s of regex per expectation per attempt); `[json]` subject 1 Mi characters, depth 200; 16 alternatives, 32 fields |
| the `events` root (§8.6) | at most 10 `evt.query` calls of 1000 events per fetch under the root's 10 s `READ` options; fails on loss; the agent's control thread only |
| `captureFacts` (§8.7) | 1 to 32 fields; one fetch per root; no retry |
| `PayloadMatch` (§8.9) | linear, text only, no regex or parsing under the ring lock |
| `actual` in `run.jsonl` (§8.10) | 200 list elements per level, `actualTruncated: true` beyond |
| pack capabilities, `SuiteCache`, pre-flight (§9) | at most 64 capabilities per pack, ids of 128 characters; the cache 1,000 entries by default (1..100,000), in memory, never written; an evaluation on the host's thread bounded by its own reads only (as a `PreflightCheck`) |

### 2.7 What a target JVM sees

Unchanged in kind (P6 §2.5, P7 §2.5): the agent home's `lib/` and `modules/` jars hold project classes only;
`optional/` holds the hooks and Byteman modules. Phase 8 adds no jar and no third-party class; `HomeConstantPoolIT` and
`DistributionContentIT` keep guarding the home. What a target JVM may newly do, each behind a gate: run a module's task
on the application's own thread (only a task the core submitted through a `QuiescentPoint` the provider itself drives,
§4.1); start up to 32 daemon lane threads (only for a module that declared lanes, §4.2); read a loaded class's
`ProtectionDomain` location fields (only at `hook.probe`/`hook.install`, on a worker, §6.2); dispatch an `ActionEvent`
or `ItemEvent` to an AWT control (only inside a posted click task, §3.5); run a `MaskPolicy` on the thread that
publishes an event (every publish, once a policy is registered, §5.1).

### 2.8 The regeneration step R (D626, D641)

Regeneration happens **once per cause** (D626 as revised). The first cause is the plan version: EX's skeleton commit
(§2.2, §8.2) regenerates the 19 committed plans (18 with `-Dskillroy.plans.update=true` in the lending and branch tests
and the web host's under `-Pjcef`, `toy-loan.plan.json` by a one-off compile) and the 17 bindings files' `planHash`
(`-Dskillroy.bindings.rehash=true`; `packVersion` stays 0.6.0, the rehash takes it from the compiled plan,
`BindingsUpToDateTest:95-115`), key-free and native, and moves the web host's `PlansUpToDateTest:90` to 7; without
that the skeleton is red in `controller/skillroy-scenario`, upstream of most of the reactor (C3-F1). Step R is the
second cause: once, after every package has landed and before the matrix, in this order (P7's step 2b; every flag is
in map I §5.4):

1. the version literals: `LendingModule.VERSION` 0.3.0, `LendingPack.VERSION` 0.7.0, `DataModule.VERSION` 0.2.0,
   `WebHostModule.VERSION` 0.3.0, and their pins (`LendingModuleTest`, `LendingPackTest`, `LendingAllowListTest`,
   `LendingHooksAndSettingsTest`, `BranchVariant`, `LendingBranchOverlayTest`, `DataModuleTest`, `WebHostModuleTest`);
   the pinned type lists and counts are **not** step R's: each package edits them on its branch to its branch's truth
   and the integrator resolves the literal conflicts at merge (D641; the final values are in §12.2 and
   `contract-notes.md` §3);
2. every committed plan (19 files at the skeleton, 25 with the six new ones) rewritten with `packVersion: 0.7.0`
   (`-Dskillroy.plans.update=true` in `PlansUpToDateTest` and `BranchPlansUpToDateTest`; the web host's with `-Pjcef`;
   `toy-loan.plan.json` by a one-off compile), scenario A's two plans (the standard and the branch variant) carrying
   its `requires` entry (§9), the six new plans (five top-level scenarios and `scanner/`'s one, all in `plan/`, first
   compiled by the package that added each, D641) recompiled;
3. every committed bindings file's `planHash` and `packVersion` (17 files; `-Dskillroy.bindings.rehash=true`;
   `resolveHash` unchanged, so nothing is `REBIND_NEEDED`; scenario A's three bindings files, `seed-a`, `seed-b` and
   `branch-a`, are the ones whose plan changed);
4. the five capability goldens in the image (`-Dskillroy.golden.write=true`): `lending.engine.view`, `lending.signOn`,
   `lending.scanChecked` `OK` in every leg, `lending.scanner.physical` `UNAVAILABLE("scanner driver not enabled …")` in
   every standard leg; no `allow-differences.json` entry expected;
5. the five session stand-in recordings and `exported/session-basic-loan.*` (key-free,
   `-Dtest=SessionExportTest -Dskillroy.inference.fixtures.update=true`: the pack version and the three new verbs' schemas
   are in their system text), and `exported/session-vision-loan.*` in the image (`InferenceChecks` item 8, the same flag);
6. the pinned lists and counts verified at their final values (D641: the packages' branch edits, resolved by the
   integrator; step R edits nothing here): `PlansUpToDateTest` (the committed list of 17 names, `scanner/`'s one
   included; `SEED_A`; 17 negatives), `BindingsUpToDateTest` (its "needing bindings" list untouched: no new scenario
   needs bindings), `ScenarioSchemaCommittedTest` (34, §12.4), the JUnit glue's id list (§13.2 item 32);
7. the committed scenario JSON schema copy.

Not regenerated: `keypad-image`'s baselines (the main frame is untouched), scenario D's two live recordings (nothing
enters its tier (b) request), the twelve fingerprinted tables and the seed rows (unchanged), the web host's goldens
(none). The one inference fixture ET changes (`fcbda729…json`, the scripted-model recording whose request carried the
catalogue's procedures line) is regenerated by ET itself, key-free, in its own build (§4.5, §12.5).

---

## 3. Swing input: the input veto, the visible area, heavyweight containers, AWT controls

Four changes to `skillroy-agent-swing` (Java 8, JDK API only), one new SPI in `agent-api`, three controller hunks, no
protocol, capability, option or plan change. **What stays the same for a target or a pack that uses none of it:**
every rung, route and reader answers as today unless an `InputVeto` is registered (§3.2), the tree holds a component
with an empty effective area (§3.3), a routed pointer lands in a heavyweight container (§3.4) or an AWT `Button` or
`Checkbox` is clicked on `ACTION`/`COMPONENT` (§3.5); the three behaviour changes are marked.

### 3.1 The order of checks on every input route (D517)

The Swing module has thirteen input routes, all through the five mutating primitives `swing.click`, `swing.type`,
`swing.key`, `swing.pointer`, `swing.dismiss` (map A §0.1, R1-R13), of which **twelve resolve a target**: R5, the
`ROBOT` click rung, is refused `CAPABILITY_UNAVAILABLE` before any target is resolved (`SwingPrimitives.java:705-707`)
and has no read in which to ask a veto. From Phase 8 every route that resolves a target does so in one bounded EDT
read (`Edt.read`, unchanged) and checks, in this order, failing on the first:

1. **clickable** (`Clicks.requireClickable`, `Clicks.java:452-461`): not showing → `INVALID_PARAMS` `reason:
   "not showing"`; **collapsed** (new, §3.3) → `INVALID_PARAMS` `reason: "collapsed"`; disabled → `INVALID_PARAMS`
   `reason: "disabled"`;
2. **the input veto** (new, §3.2): `INPUT_REJECTED` `reason: "vetoed"`; never skipped by `force`;
3. **modal blocking** (`Windows.requireNotBlocked`, `Windows.java:359-375`; the routed pointer's pre-check,
   `Pointer.java:731-760`): `MODAL_OPEN`, `INPUT_REJECTED covered`, `target changed`; `force` skips the first two as
   today (P1 §6 "Modal blocking", P7 §3.3 step 1).

The veto is checked after the clickable test because an argument error names a component that cannot take input at
all, and before the modal test because a veto is the more permanent fact (a dialog closes; a veto does not). The
routes that check less today keep their shape: `swing.type`/`swing.key` run the modal test for an explicit target
only (`Typing.java:92-94, 188-190`), `swing.dismiss` runs none (P1 §6); **both run the veto on every target** (§3.2
rule 2). Every posted task re-asks the veto before it acts (§3.2 rule 4); nothing else is re-checked in a task.

### 3.2 `InputVeto`: a layer vetoes the agent's input to a component (gap 105; D501 to D509)

**Decision (D501).** A new SPI in `agent-api`, asked by every route of §3.1 on the EDT inside the call's read and
again inside each posted task, with an ancestor rule, failing closed, never skipped by `force`, and shown by every
reader so that the recorder and a model are never offered the component. Guide §7 rule 4 ("never route through
application code that ends the JVM") gains its mechanism: a layer registers one `InputVeto` for every control whose
action ends the JVM or cannot be undone in a test.

**The SPI** (new file `agent-api/.../spi/InputVeto.java`, Java 8, no default methods; an older layer compiles
unchanged, a layer that implements it needs a Phase 8 agent: it fails to install on an older agent as any new SPI
type does, guide §4.4):

```java
package ai.skillroy.testjava.agent.api.spi;

/** Components the agent must never deliver input to: a key whose action ends the JVM, a control whose effect cannot
 *  be undone in a test. Asked on the EDT inside a primitive's bounded read and again inside each posted input task,
 *  for the component and each of its ancestors up to and including its window; must be fast, lock-free, must not
 *  block and must not change the component tree. A veto that throws refuses (the safe side). */
public interface InputVeto {
    String CLICK_ACTION    = "swing.click.action";     // swing.click rungs ACTION and AUTO; COMPONENT's fall-back to ACTION
    String CLICK_COMPONENT = "swing.click.component";  // before the window activation and the focus request, and again before the click
    String CLICK_DISPATCH  = "swing.click.dispatch";
    String TYPE_SET        = "swing.type.set";
    String TYPE_DISPATCH   = "swing.type.dispatch";
    String KEY             = "swing.key";
    String POINTER_ROUTED  = "swing.pointer.routed";
    String POINTER_ROBOT   = "swing.pointer.robot";
    String DISMISS_BUTTON  = "swing.dismiss.button";
    String DISMISS_CLOSE   = "swing.dismiss.close";
    /** The readers' question (snapshot, find, hit-test, capture marks, dialogs): would input on any route be refused? */
    String ANY             = "*";

    /** @return null to allow, else one line saying why (the agent cuts it at 200 characters) */
    String refuse(java.awt.Component component, String route);
}
```

The route strings equal the rung's capability id where one exists (`SwingModule.java:79-99`), so a layer that reads
`/capabilities` recognises them. A veto that discriminates routes **must answer `ANY` with a refusal whenever it
would refuse at least one route** (normative, in the Javadoc and guide §6.7): the module uses `ANY` for the flags
readers show, never for a refusal itself, so a veto that answers `ANY` with null while refusing a route is still
enforced but invisible to the recorder until it acts.

**Rules.**

1. **Ancestor rule (D501).** The module asks about the component and each ancestor up to and including its window
   (at most 64 levels, `Windows.hidden`'s bound, `Windows.java:139-148`); the first refusal, nearest the component,
   wins and names the component that refused (`vetoed` below). A layer vetoes a whole panel or window with one
   answer. Memoised per call and route (`ComponentView` holds the evaluator as it holds `Redaction`,
   `ComponentView.java:65-69`; the chain mirrors `masked`'s, `:84-97`). **Fix wave (Phase 8 review, FA, 2026-10-19):
   a popup is asked with its invoker's chain** (L2-F2): the walk continues from a `JPopupMenu` with an invoker to the
   invoker, and from a window of type `Window.Type.POPUP` with an owner (Swing's heavyweight popup window, which also
   hands its owner its window-wide key bindings) to the owner; anything else continues with its parent and stops at
   any other window. One successor (`InputVetoes.next`) serves the depth bound, the memoised chain, the readers and
   rule 2's root-pane walk, and the 64-level bound applies across the hops (a cycle, an invoker inside its own popup,
   is refused at the bound). So a window veto covers the items of its menus whether their popup is drawn in the layered
   pane or as a window of its own (a popup that does not fit, a cascaded submenu, `setLightWeightPopupEnabled(false)`),
   a vetoed `JComboBox` covers its open popup list (a click there sets the vetoed combo's selection), and a veto on a
   `JMenu` or on a context menu's invoker covers that popup's items.
2. **Where it is asked (D502)**, in every route, after `requireClickable` and before the modal test (§3.1):
   - R1/R4 (`swing.click` ACTION, AUTO, DISPATCH) in `Clicks.click`'s read (`Clicks.java:133-144`) with
     `CLICK_ACTION` / `CLICK_DISPATCH`;
   - R2 (COMPONENT) in the first read (`:161-194`) **before** `toFront()`/`requestFocus()`/`requestFocusInWindow()`
     (the activation runs application focus listeners, map A §0.1 note), with `CLICK_COMPONENT`; and again inside the
     final posted click (`:265-274`, rule 4);
   - R3 (the fall-back) in `focusFailed`'s read (`:292-302`) with `CLICK_ACTION`;
   - R6/R7/R8 (`swing.type` SET/DISPATCH, `swing.key`) for **every** delivery target, explicit or default
     (`Typing.defaultKeyTarget`, `Typing.java:220-232`; a window's root pane included): a focused vetoed key takes
     SPACE or ENTER like a click. For `swing.key` with `ENTER` (no CTRL/ALT/META) and for `swing.type` with `enter`,
     also the target's root pane's default button (`SwingUtilities.getRootPane(c).getDefaultButton()`, when not
     null), since Swing's `WHEN_IN_FOCUSED_WINDOW` binding fires it; route `KEY` / `TYPE_SET` / `TYPE_DISPATCH`.
     **Fix wave (Phase 8 review, FA, 2026-10-19):** (i) the default buttons asked are those of **every** `JRootPane`
     on rule 1's walk from the target up to its window, nearest first, each once (L2-S1): a `JInternalFrame`'s root
     pane registers its ENTER under the internal frame only while it has a default button, and Swing then offers the
     key to the window's own bindings, so ENTER into an internal frame without a default button presses the enclosing
     window's; asking the outer button while a nearer one exists refuses on the safe side, and the error's
     `defaultButton` names the button whose veto refused; (ii) a `'\n'` in `swing.type` DISPATCH text is the ENTER key
     stroke (`Keys.type` dispatches `KEY_PRESSED VK_ENTER` for it, the same three events as `Keys.press(c, VK_ENTER,
     0)`), so it asks the default buttons as `enter` does, in the read and in that character's task (L1-F1, L2-F1);
     multi-line DISPATCH text into a `JTextArea` is therefore refused while a default button on the walk is vetoed
     (SET is the way around it); `'\r'` maps to no key code and asks nothing more;
   - R9/R10 (the routed pointer) in `precheck` (`Pointer.java:731-760`, routed only) for the hit's `target` and
     `deepest` (both chains), for the component form additionally the addressed component (`:300-325`), and for
     `DRAG` the `target` and `deepest` of a second hit-test at `to`; route `POINTER_ROUTED`; **per event** in
     `Pointer.dispatch` (rule 6);
   - R11 (Robot) in `RobotPath.check` (`RobotPath.java:357-404`, both reads: the probe's `:269-277` and the press's
     `:282-287`) for `target`, `deepest` and the drag's `to`; route `POINTER_ROBOT`; refused before anything moves;
     **(Phase 8 security fix, S156, FA)** and the target and deepest component at the point, and at the drag's end, of
     every other window of the JVM whose screen rectangle holds it (`HitTest.coveringTargets`), since a Robot press
     lands on whichever window is really on top: an owner gap 101 exempts from the covering rule, and every covering
     window under `force`; a covering list that is full (`HitTest.MAX_COVERING`, 32) refuses, since a further window
     could be the one on top; `force` acts past the covering rule, never past a veto;
   - R12 (`swing.dismiss {button}`) for the button after `requireClickable` (`SwingPrimitives.java:811`), route
     `DISMISS_BUTTON`; R13 (`{close}`) for the dialog window itself, route `DISMISS_CLOSE`, before the
     `WINDOW_CLOSING` is planned (`:791-796`).
3. **Not skippable (D502).** No argument skips a refusal, `force` included (a person's `force`, P7 §9.1 D294, acts
   past a dialog, never past a veto). Read-only mode is unaffected (mutating primitives are refused earlier).
4. **Atomic with the action (D503).** Every posted input task re-asks the veto on the EDT immediately before it
   acts, with a fresh evaluator and the task's route: `Clicks.actionPlan`'s chosen action (the `ActionFirer`, the
   semantic click of §3.5, the mouse click), COMPONENT's final click, every `swing.type` task (the SET task; the
   prepare task; each character's task; the ENTER task) and `swing.key`'s task, `swing.dismiss`'s task;
   **fix wave (Phase 8 review, FA, 2026-10-19; L2-F5)** COMPONENT's re-issued activation (A39, `toFront()` and
   `requestFocus()` run the application's focus listeners: a refusal skips it, counted once per call with the final
   click, the wait runs out and `FOCUS_FAILED` follows, or the fall-back's own `CLICK_ACTION` read) and its second
   read, before the component's `requestFocusInWindow()` (a read, not a task: it answers `INPUT_REJECTED vetoed` as
   the first read does), since up to `focusTimeoutMs` lies between the first read and either. A refusal
   there acts on nothing, is logged once per call at `warn` ("swing: <route> withheld inside the task: <text>"), is
   counted (`vetoWithheld: <n>` in the Swing module's `info()`, which `agent.info.modules[]` shows as the record's
   `info` entry when non-empty through the new additive default `AgentModule.info()`, `Map<String, Object>`, empty by
   default; `skillroy info` prints it) and is **not** reported in the result (the call already answered "posted", P1 §6
   "fired means posted"). This closes the window between the read and the task for a veto that depends on application
   state. **Residual, stated** (guide §6.7, §11 rule 1): no run host reads `agent.info` after a step, so a step whose
   control's veto can change with application state passes with `fired: true` when the veto refuses inside the task;
   the step's `sync` or an expectation on the effect is what fails it.
5. **Error (D504).** `INPUT_REJECTED` (-32005, no new code), message
   `"<describe(c)> takes no input from the agent (<route>): <text>"`, `data`:

   | Key | Type | Meaning |
   |---|---|---|
   | `reason` | `"vetoed"` | the token, beside `covered`, `target changed`, `not focused` (P7 §3.9) |
   | `veto` | string | the veto's text, sanitised (rule 8) |
   | `ref` | ref | the component the call aimed at (the resolved target; for the pointer the hit's `target`, else the window) |
   | `vetoed` | ref | the component whose veto refused: `ref` itself or an ancestor (the ancestor rule) |
   | `route` | string | the route constant |
   | `hit` | object | the pointer routes only: the pre-check's hit subset (`target, targetKind, deepest, enabled, masked, vetoed, covering`) |

   The executor maps `INPUT_REJECTED` to `FAIL / ASSERTION` today (`CallRunner.java:626-627`); the step's failure
   detail carries the error's data as for every refused call; no controller mapping changes.
6. **The pointer mid-sequence (D505).** `Pointer.dispatch` (`Pointer.java:569-613`) checks, before each event and
   after the `cancelled` and modal tests, the component AWT will deliver that event to: while a press of this call is
   down (`MOUSE_DRAGGED`, the drag's `MOUSE_RELEASED`), the component the press went to (the one under the press's
   point, kept from the press's own check); otherwise the component under the event's point in the event's source
   (`HitTest.mouseTarget(source, x, y)`, else `SwingUtilities.getDeepestComponentAt(source, x, y)`); the ancestor
   rule covers the rest. **(Phase 8 security fix, S155, FA)** The `MOUSE_RELEASED` of a press of this call also asks
   the target and the deepest component at the release's screen point in the window (a lightweight popup lies in its
   layered pane) and in every other window of the JVM that holds the point and is ranked above the window or is of
   type `POPUP` (a heavyweight popup, a window the application showed while the button was down;
   `HitTest.releaseTargets`), a full covering list refusing: Swing's `MenuSelectionManager` and a popup's
   `MouseGrabber` hand the drag and the release of a press on a `JMenu`, or on a popup's invoker, to the item under the
   release's screen point, so a drag pressed on an allowed menu and released on a vetoed item would otherwise fire it.
   The release is enough: the balance's drag and release land at (-1, -1) of the source, where no item lies, and the
   menu clears its path. For a click the release point is the press point, so it refuses only what the press put
   there. **The Robot route** (`RobotPath.act`) asks a `DRAG`'s release again on the worker after the press and the
   moves, immediately before the release: once the press was observed (bounded, 1000 ms) and the event queue has
   passed twice (what the press posted, such as a lightweight popup's layout, runs first), one bounded EDT read asks
   the same question at the end point over every window of the JVM that holds it; unless it allows (a refusal, a read
   that fails or times out, an interruption, a press never observed), the pointer moves back to the press point and
   the release lands there (on a menu the menu stays open and nothing fires; a drag and drop drops on its source; a
   button pressed there may fire, the press point being allowed), and a refusal answers `fired: false, reason:
   "VETOED"` with `vetoed {ref, veto, route}`. **Residual, stated:** the Robot's read is not atomic with the native
   release, so a popup or drop zone the application shows after it (a submenu's open delay, a timer, a native drag's
   own window) is not seen on that route. A refusal stops the sequence as a modal dialog does: `st.stopped`, `fired: false`,
   `reason: "VETOED"` (beside `EDT_BUSY`, `MODAL_OPENED`), result field `vetoed: {ref, veto, route}`, a dispatched
   press balanced (D352, `:629-645`), `dispatched {sent, of}` honest; the executor's A29 rule fails the step. The
   per-event check is **not** skipped by `force` (the modal re-read is, `:576`). Seam for the headless test:
   `Plan.veto`, an `EventVeto` (`InputVetoes.Refusal check(MouseEvent e)`) beside `Plan.blocked` (`:364-373`).
7. **Readers (D506).** Asked with route `ANY` through `ComponentView.vetoed(Component)` (memoised, ancestor rule):
   - `Node` (`ComponentView.node`, `:321-357`) gains `vetoed: true` **only when true**, after `masked` (the pinned
     key order of `RolesAndTextTest.nodeShape:221-239` and every golden of a desk without vetoes stay byte-identical);
     `swing.snapshot` does **not** prune vetoed components (they are on screen; a model must see them flagged);
   - `swing.hitTest` gains `vetoed: true|false` **always**, right after `masked` (`HitTest.java:234`); the pointer's
     `hit` subset carries it (`Pointer.hitSubset`, `:763-769`);
   - `swing.capture {annotateRefs}` draws no mark for a vetoed component (as for a masked one,
     `Capture.java:742-759`); its pixels are untouched (a veto is safety, not privacy: nothing is blanked);
   - `swing.dialogs` button entries may carry `vetoed: true` when true (optional key, `DialogReader.java:100-115`);
   - `swing.find` answers nodes with the flag.
8. **Failure policy (D501).** A veto that throws refuses with the text `"veto <SimpleClassName> failed"`, logged
   once per instance (`Redaction`'s fail-closed policy, `Redaction.java:155-167`), `VirtualMachineError` rethrown
   except `StackOverflowError` (`SwingSpis.java:207-210`). Text sanitising: control characters become spaces, the
   text is trimmed to one line and cut at 200 characters; an empty text becomes `"vetoed by <SimpleClassName>"`. A
   veto that throws for everything locks every route out, visibly (every input `INPUT_REJECTED vetoed`, one warning).
9. **Bounds and threads.** EDT only, inside the call's existing bounded read or task; at most `depth` calls per
   input check (≤ 64), `nodes × 1` per snapshot or capture (memoised); no new timeout: a slow veto makes the read
   time out as any EDT read (`TIMEOUT`, the dispatcher's backstop). With `allowFailedModules=true` a failed layer
   module serves **without its veto** (`AgentRuntime.java:788-802`); the agent logs the failed module as today.
10. **Not covered, stated.** Swing's key bindings, mnemonics and menu accelerators can route a key dispatched to
    one component to an action another component owns; only the delivery target's chain and ENTER's default button
    are asked. The guide's rule (§6.7, §7 rule 4): *veto the window, or the bound control*. **Fix wave (Phase 8
    review, FA, 2026-10-19):** a menu item's own veto does not cover the keyboard of an open menu (`swing.key DOWN`
    and `ENTER` to the focus owner select and fire the armed item): veto the window; the control characters of DISPATCH
    text other than `'\n'` (`'\t'` moves the focus, ESC fires a window-wide "close" binding) are key strokes this rule
    leaves to the bindings; a `JRootPane` placed in a window off the target's walk and outside a `JInternalFrame`
    (rare: a root pane embedded in a plain panel) registers its default button under the window and is not asked.
    `web.*` input into a
    browser component a veto names is not refused (gap 126, **medium**; Q3): the web routes act through the page;
    only `web.pointer` reads the browser's AWT component, for the modal check (`agent-web/.../Pointing.java:218-227`
    → `ComponentReads.stateOf`, `ComponentReads.java:45-57`, which already walks the ancestor chain for `masked`:
    asking an `InputVeto` there is one more field of a read already made, the seam if the owner takes it);
    `web.dom.click`, `web.dom.fill`, `web.dispatchEvent` and the CDP path read no component. **A veto on a window or
    panel does not cover `web.*` input into a browser inside it**: a layer that follows "veto the window" with a
    window holding a browser gets `swing.pointer` into the canvas refused (§3.4 rule 2) and `web.*` into the same page
    allowed, for any caller `CallPolicy` admits; the guide (§6.7, §7 rule 4), `SECURITY.md` (a) and threat model §7
    say so in one sentence each, and the layer vetoes nothing more for it in Phase 8.

**Controller (D507).** `Recorder` (`controller/skillroy-vision/.../Recorder.java`) refuses a hit with
`vetoed: true` before any step is built, on every gesture (`pointer`, `:171-175`), for a vetoed focus owner (`type`
`:659`, `key` `:697`) and in V2's raw pointer (`VisionSessionTools.java:459-463`), with the fixed text
`Recorder.VETOED = "the application's agent module refuses input to that component"` beside `MASKED` (`:66-67`).
`UiSnapshotText.node` (`controller/skillroy-scenario/.../UiSnapshotText.java:172-186`) adds the flag `vetoed` to a
node's flag list (after `selected`). The MCP and session raw calls need no change: the agent refuses. `CallPolicy`
is unchanged (`force` is already a person's argument, `CallPolicy.java:62`).

**Alternatives rejected** (D501): a three-valued `ActionFirer` (ACTION only); `LocatorResolver` hiding
components (refs, coordinates and the routed pointer bypass it); a controller-side deny-list in the pack (the routed
pointer's real target, snapshot refs and raw MCP calls bypass it; may complement later); a built-in veto configured
by an agent option (no code for simple cases; gap 127, on top of the SPI); a `STUB` hook on the JVM-ending method
(the hooks module is optional and off by default); reusing `masked` (conflates privacy with safety and would refuse
a person's own steps).

**As built (Phase 8, EA, 2026-10-19).** The evaluator is the package-private `InputVetoes` (`agent-swing`): one per
call's read (held lazily by `ComponentView.vetoes()`, memoised per route and component, the chain result of an
ancestor reused), a fresh one per posted task (`InputVetoes.Withheld`, `InputVetoes.guarded`) and per routed event.
Choices beyond the text above, each recorded for the integrator: (a) **ENTER with any modifier** asks the default
button, not only an ENTER without CTRL, ALT or META (rule 2): the Basic look and feel binds `ctrl ENTER` to the root
pane's default button as well as `ENTER` (`RootPane.defaultButtonWindowKeyBindings`), so the narrower rule would let
`swing.key {key: ENTER, modifiers: [CTRL]}` press a vetoed default button; a refusal through the default button is the
rule-5 error with `ref` the key's target, `vetoed` the component whose veto refused (the button or an ancestor, by the
ancestor rule) and the added key **`defaultButton`** (the button's ref; since the fix wave, FA, the ref of the default
button whose veto refused, rule 2 (i)), message "… takes no input from the agent
(<route>): ENTER would press the default button <describe(button)>: <text>"; `swing.type {enter: true}` asks the
default button in both modes (SET's `postActionEvent` does not press it, the safe side). (b) **Deeper than 64
levels is refused** (rule 1's bound): a component whose chain from itself to its window holds more than 64
components is refused on every route with the agent's own text ("the component lies more than 64 levels below its
window; the input veto cannot be asked for every ancestor"), and the readers flag it `vetoed`, rather than asking 64
levels and allowing what lies above them (a window veto must not be escaped by depth; the snapshot's own depth cap is
89). (c) The hit-test's **`vetoed` is true when the target or the deepest component is vetoed** (both chains, the
pre-check's question, rule 2), so the recorder never offers a hit whose replay the pre-check would refuse. (d) The
routed pointer's per-event check (rule 6) runs in two phases: the `cancelled` and modal tests under the call's state
lock, then the veto outside it (it is layer code; the caller's `cancel` never waits for it), then the state is
re-read (a cancellation meanwhile wins); the call's first event, an exit to the previous source (§3.4 rule 3), is
asked too; every event is dispatched to its own source. (e) An in-task refusal is counted and logged **once per call**
(`vetoWithheld` counts calls, not tasks: a `swing.type DISPATCH` whose veto switches on withholds all its remaining
character tasks for one count and one warning). (f) `AgentModule.info()` is shown by `ModuleLoader.Record.toJson`
(`agent-core`) after `readyMessage`, only for an installed module and only when non-empty; a module whose `info()`
throws is logged once (the exception's class only) and its entry left out; the Swing module answers
`{vetoWithheld: n}` from install on (never empty once installed), without taking the module's lock; `skillroy info`
prints a module's `info` after its state, in braces (`swing 0.2.0 INSTALLED {vetoWithheld=0}`).

### 3.3 The effective visible area: "on screen" is more than `isShowing()` (gap 116; D510 to D512)

**Definition (D510).** A component is **on screen** when it is showing and its **effective area** is not empty.
The effective area is the component's own rectangle `(0, 0, width, height)` intersected, walking up, with the bounds
of every ancestor up to and including its window, where a `javax.swing.JViewport` and an AWT `java.awt.ScrollPane`
**do not clip their view**: scrolling is not hiding, so when the part inside the view is non-empty the walk carries
the viewport's own rectangle upward unchanged. A showing component that is not on screen is **collapsed**: a
zero-width or zero-height ancestor or self, or lying wholly outside an ancestor's bounds or the window's. The rule
is hereditary (a collapsed component's descendants are collapsed), so walkers prune. A component scrolled out of a
viewport stays on screen: `ACTION` clicks and locators keep working on it. Window roots keep `isShowing()`.

**Helper** (new, package-private `Visibility.java`, EDT only, O(depth)): `static Rectangle area(Component c)` (the
effective area in the component's own coordinates; empty when collapsed), `static Component collapsedBy(Component c)`
(self, or the ancestor whose bounds empty it; null when on screen), `static boolean onScreen(Component c)`;
`ComponentView.onScreen(Component)` and `collapsedBy` memoised per call; walkers carry the parent's area down (O(1)
per node).

**Applied in (D510):**

| Where | Rule | Failure path |
|---|---|---|
| `Clicks.requireClickable` (so every `swing.click` rung, `swing.type`, `swing.key`, `swing.dismiss {button}`) | collapsed refused after "not showing", before "disabled" | `INVALID_PARAMS`, message `"<describe(c)> is collapsed: <describe(by)> leaves it no visible area"`, `data {ref, reason: "collapsed", by: <ref of collapsedBy>}` |
| `Pointer.address` component form (`Pointer.java:301-304`), `swing.hitTest` target form (`SwingPrimitives.java:640-643`), `swing.capture` target (`:587-590`) | the same test replaces the bare `isShowing()` | the same error |
| `LocatorEngine.collect` and `accepts` (`LocatorEngine.java:143, 172`) | a collapsed component does not match unless `includeHidden` (the pruning `isShowing()` test at `:143` prunes a collapsed subtree too) | `NOT_FOUND` where the hidden copy alone matched; one match where `AMBIGUOUS` was |
| `SnapshotWalker.walk` (`SnapshotWalker.java:68`) | collapsed children left out unless `includeHidden` | – |
| `Node` | `collapsed: true` only when true (seen with `includeHidden`, in `AMBIGUOUS` candidates and in the hit-test's `deepest`), after `masked`/`vetoed` | – |
| `DialogReader.collectShowing`, `collectButtonCandidates` (`DialogReader.java:246-257, 325-335`) | collapsed components contribute no text and no button | `swing.dismiss` `NOT_FOUND` for a collapsed button |
| `InWindowDialogs.in` (`InWindowDialogs.java:66, 83`) | a collapsed in-window dialog root is not a dialog: it blocks nothing and is not listed; a reported root dropped as collapsed is logged once per root class ("swing: in-window dialog root <class> has no visible area and blocks nothing"), so the loss is visible in `agent.log` | **Behaviour change** (D511): an application whose `DialogLocator` reports a zero-size root (a marker component plus a glass pane; a root not yet laid out when the locator answers) no longer gets `MODAL_OPEN` from it. Stated where the layer author reads it: the `DialogLocator` Javadoc (`agent-api/.../spi/DialogLocator.java:23-30`, EA) gains "a root without an effective area is not a dialog: it blocks nothing and is not listed; report the component that has the area"; `InWindowDialogDisplayTest` asserts the log line |
| `Capture` marks (`Capture.java:926-935`) | a non-`JComponent`'s mark uses the effective area as a `JComponent`'s `getVisibleRect()` is used today | – |

**The pointer's component form refuses a point its component does not show (D512).** After `Pointer.address`
computes the point from `at` (`:321`), the module checks that the point lies in the component's **strict** visible
rectangle (`JComponent.getVisibleRect()`, else the effective area), i.e. the part a viewport shows; for `DRAG`
`to` likewise. Otherwise `INPUT_REJECTED` with message `"<describe(c)> does not show the point (<x>, <y>): it is
scrolled out of view or clipped"`, `data {reason: "not visible", ref, point: {x, y}, param: "at" | "to"}`. Today
such a call's events land on whatever lies at that window point (another row, another component): a recorded
`pointer {by, at}` step on a scrolled-out row would click elsewhere. Point forms are unchanged (they deliver to
what lies at the point by definition).

**Behaviour change (D510).** For an application with zero-size fillers, a frame set that gives hidden panes
zero width, or components not yet laid out: locators that matched a hidden copy answer `NOT_FOUND` or one match
where they answered `AMBIGUOUS` or the wrong copy; snapshots and the session's text snapshot shrink by the collapsed
subtrees; a click on a collapsed component is `INVALID_PARAMS collapsed` instead of a dispatched click into
nothing; `swing.settle` covers transient layout as before. The desk has none of these (its `CellRendererPane`s are
already invisible, its hidden scroll bars `setVisible(false)`): its committed snapshots, plans, bindings, goldens
and baselines are unchanged, verified by §13.2 item 6. A custom scroller that moves its child without a viewport
counts a scrolled-out child as collapsed (gap 130, documented).

No new parameter, capability, protocol or plan change. Bounds: EDT only, O(depth) per check, O(nodes) per walk.

**As built (Phase 8, EA, 2026-10-19).** `Visibility` (package-private, `agent-swing`) computes a component's area from
its parent's (`childArea`: empty when the child's own rectangle or the parent's area is empty; the child's own
rectangle when the parent is a `JViewport` or an AWT `ScrollPane`; else the intersection), memoised per instance
(`ComponentView` holds one per call, so a walk is O(1) per node); the static `area`/`collapsedBy`/`onScreen` use a
fresh instance (O(depth)); `area` and `collapsedBy` are geometry only (no `isShowing()`, so the headless test reaches
them), `onScreen` adds `isShowing()`; a window's area is its own rectangle and a window is never collapsed itself. The
culprit `by` is chosen as: an ancestor's culprit when the parent is collapsed (hereditary; so a button inside the
zero-width pane names the pane), else the component itself when it has no size, else the parent (whose area does not
meet the component). Node `collapsed` is set for a showing component only (a non-showing one is hidden, not
collapsed). The component form's point check (D512) reports `point` in the component's own coordinates. The table's
`swing.dismiss` row holds: a collapsed button is no candidate, so `swing.dismiss {button}` answers `NOT_FOUND` (and a
dialog whose only button is collapsed has no button), not `INVALID_PARAMS collapsed` (`CollapsedPaneDisplayTest`).
The once-per-root-class log of D511 is bounded at 64 classes per agent class loader. The desk is unchanged by the
rule: the four load-mode legs' `LoadModeChecks.keypadKeysAreNamedByTheirActionId` and the Phase 7 `VisionSwingChecks`
items pass (the `JavaAgent` leg on a Java 25 target in the image, 2026-10-19; the matrix runs the rest).

### 3.4 The routed pointer into heavyweight containers (gap 115; D513, D514)

**Decision (D513).** The routed event's source is the **innermost showing heavyweight component on the
containment path at the point**, else the window; the point is converted to it; the hit-test searches as that
source's own dispatcher would. Every heavyweight `Container` owns a `LightweightDispatcher` from `addNotify`, so an
event sourced from it is retargeted to its lightweight descendants exactly as a window-sourced one is (map A §3,
verified against `java.awt.Container`); today the window's dispatcher retargets to lightweight descendants only, so
nothing inside a heavyweight `java.awt.Panel` is ever reached (`Pointer.java:411, 601`; `HitTest.java:381-404`).

1. **Source search** (new package-private `HitTest.sourceAt(Container root, Point p, List<SurfaceSource.Surface>
   claimed)`, EDT; production passes the window, the headless test a container root, since no `Window` can be built
   headless): `deepest = SwingUtilities.getDeepestComponentAt(root, p)` (`:132`, already descends heavyweights);
   walking up from `deepest` to `root`, the first component that `isDisplayable() && !isLightweight()` and is not
   `root` is the source; none → `root`. The point: `SwingUtilities.convertPoint(root, p, source)`; `xAbs/yAbs` and
   the call's `when` tag unchanged (`:653-721`). A `DRAG` keeps the press's source for the whole sequence (the native
   implicit grab), `to` converted into it.
2. **Exception: claimed surfaces (D513).** When the source or any component on the path below `w` is claimed by
   a registered `SurfaceSource` (`SwingSpis.claiming`, `SwingSpis.java:162-171`; the web module's browsers,
   `surface: WEB`), the window stays the source (today's behaviour): a browser canvas that forwards Java mouse events
   into its page would otherwise open a second page-input route around `web.pointer`'s checks (the masked browser,
   page modality; P7 §3.6, §4.3; §5.2). Alternative rejected: refusing such a point (`INPUT_REJECTED "web
   surface"`) would break today's routed clicks on a windowed browser's chrome.
3. **Enter and exit (D514).** `lastRouted` (`:103`) is keyed per source (weakly, as today per window) and the
   module keeps, per window, the last source weakly. When a call's source differs from the window's last routed
   source and that source is still showing, the sequence starts with one `MOUSE_EXITED` to the previous source at
   its last point (one more event in `dispatched.of`), approximating the native leave; then the move of step 2
   (P7 §3.3) when the point differs from the new source's last position.
4. **Received (D514).** The call's listener (`:437-455`) counts the source itself when it is a heavyweight leaf
   (not a `Container`: a `Button`, `Checkbox`, `Canvas`; its peer or its listeners take the event) or when it
   listens (`HitTest.listens`, generalising `:448`); a retargeted event's component is counted as today.
5. **Balance (D514).** Into a heavyweight leaf source the balance (`:629-645`) dispatches the `MOUSE_DRAGGED` to
   (-1, -1), then one `MOUSE_EXITED` at (-1, -1) (the X peers disarm on exit; a `Panel` source needs none: its
   dispatcher delivers the exit), then the `MOUSE_RELEASED` with click count 0 and no click. Verified on the Linux
   image, Java 8 and Java 25 targets (§13.2 item 7); the Windows and macOS peers are not verified (§13.3).
6. **Hit-test (D513).** `mouseTarget` searches below the innermost heavyweight as its dispatcher would: lightweight
   children only (`:386`), the heavyweight itself when it listens; for a heavyweight **leaf** source, `mouseTarget`
   is the source when it listens or is an AWT control whose peer acts on a press (`Button`, `Checkbox`, `Choice`,
   `List`, `Scrollbar`, `TextComponent`), else null (`deepest` is it either way, `target` falls back to `deepest`).
   New result field **`source`** (ref of the routed event's source: the window or the heavyweight), placed after
   `surface`. `surface` keeps its meaning (`HEAVYWEIGHT` when `target` or an ancestor below the window is an
   unclaimed heavyweight, `:332-339`), so the recorder keeps anchoring such hits (P7 §6.5, D423), whose replay now
   reaches inside. HW/LW mixing cut-outs are not modelled (stated beside `mouseTargetInferred`, gap 79).
7. **Pointer result** gains `source` (ref) after `window`; `window` stays the window. The modal re-read stays on the
   window (`:413-418`); `MODAL_OPEN`, `covered`, `expectTarget` unchanged.
8. **Robot path.** Delivery unchanged (native); it benefits from the hit-test (`target` becomes the component inside
   the `Panel`; `received elsewhere`, `RobotPath.java:332-337`, compares against it).

**Behaviour change (D513).** A routed call whose point lies in an unclaimed heavyweight container now reaches the
components inside it: `received` may name a component inside, `source` differs from `window`, and `dispatched.of`
may be one larger (the exit of rule 3). On X11 the AWT peers act on Java mouse events dispatched to them (through
`Component.dispatchEventImpl` → `peer.handleEvent`), so a routed click on an AWT `Button` fires its action there;
on Windows the peers do not (by reading the JDK, not measured): routed clicks on AWT controls are toolkit-dependent,
which is why §3.5 exists. Alternatives rejected: dispatching to the deepest component (loses enter/exit, retargeting
and drags); Robot for heavyweight areas (the double opt-in); posting with the heavyweight as source (D293's reasons);
a documented limit (the second domain draws its whole UI inside one `Panel`, row 115).

**As built (Phase 8, EA, 2026-10-19).** `HitTest.sourceAt(Container, Point, List<Surface>)` walks the whole path from
the deepest component to the root, so a claimed component above the innermost heavyweight keeps the root as well;
`HitTest.mouseTarget(root, source, point)` is rule 6's search, `HitTest.deliveryTarget(source, x, y)` the per-event
veto's question (the source's mouse target, else the deepest component, else the source), `HitTest.targetsAt` the
drag end's target and deepest component for the veto. **An AWT control is its own source**: by rule 1 the innermost
heavyweight on the path at an AWT `Button` or `Checkbox` is the control itself (a heavyweight leaf, rules 4 and 5), so
`source` names the control there and the enclosing `Panel` only for a lightweight inside it (§13.2 item 7 is read so).
The last routed position is kept per source and the last source per window under one lock, both weakly; the exit of
rule 3 is the call's first event (counted in `dispatched.of`) and is sent whenever the previous source is still
showing and has a last point. Verified in the image on Java 25 and Java 8 test JVMs (`HeavyweightRoutingDisplayTest`:
an AWT `Button`, a `Checkbox` and a listening `Canvas` inside a `Panel` fire through their X peers on a routed click; a
cancelled press into the AWT `Button` fires nothing) and on Java 25 and Java 8 targets (the tools leg, §13.2 item 7).

### 3.5 AWT `Button` and `Checkbox` on the ACTION and COMPONENT rungs (gap 122; D515)

**Decision (D515).** A package-private `Clicks.semantic(Component c)` returning an `Edt.Action` or null, used by
`actionPlan` (`Clicks.java:399-426`) after the `ActionFirer`s and by the COMPONENT rung's final click
(`:265-274`) after the focus arrives (so COMPONENT keeps "focus, then the semantic click"); order with §3.2: the
in-task veto, then the action:

- `javax.swing.AbstractButton` → `doClick()` (unchanged);
- `java.awt.Button` → `c.dispatchEvent(new ActionEvent(c, ActionEvent.ACTION_PERFORMED, c.getActionCommand(),
  System.currentTimeMillis(), 0))` inside the posted task (as `doClick` runs inside it); no mouse event is delivered,
  so the peer fires nothing beside it (one event per click on every toolkit);
- `java.awt.Checkbox` → as the X peer: in a `CheckboxGroup` and already selected, nothing changes and no event is
  sent (the call still reports fired, as a click on a selected radio does); otherwise `setState(!getState())` (in a
  group: `setState(true)`), then `c.dispatchEvent(new ItemEvent(c, ItemEvent.ITEM_STATE_CHANGED, c.getLabel(),
  SELECTED | DESELECTED))`;
- else null: the dispatched mouse click (`mouseClickAction`, unchanged).

`DISPATCH` stays a mouse click by definition (its toolkit dependence for AWT controls is documented in P1 §6).
Results unchanged (`rung: ACTION`, `fidelity: FULL`). `DialogReader` treats `java.awt.Button` as a button:
`collectButtonCandidates` (`DialogReader.java:246-257`) admits it, `textButtons` (`:262-272`) returns components
with a label (`view.text`, which already reads an AWT `Button`'s label), `labelsOf` (`:240-245`) uses it; so
`swing.dialogs` lists an AWT `Dialog`'s buttons and `swing.dismiss {button}` presses one through the same plan.
Roles are already right (`Roles.java:110-114`). AWT `List`, `Choice`, `TextField`, `Scrollbar` and `MenuItem` are
out (gap 129). **Behaviour change:** a layer that relied on the X peer's reaction to the dispatched click now gets
the semantic event (one event either way; a listener reading `getModifiers()` sees 0); a listener that opens a modal
dialog runs its loop inside the posted task, as `doClick` does today.

**As built (Phase 8, EA, 2026-10-19).** `Clicks.semantic` serves `actionPlan` (after the `ActionFirer`s, before the
mouse click) and the COMPONENT rung's final click; the in-task veto wraps the whole plan (`InputVetoes.guarded`), so
the order is the in-task veto, then the firer or the semantic action. On the Linux image a `DISPATCH` click on an AWT
`Button` fires its action through the X peer on Java 25 and Java 8 targets (the tools leg logs it; not asserted).

### 3.6 Capabilities, options, errors, events, versions

No new capability id from this section (it moves no golden and no matrix hash; the sample ids of §4.1, §7.5 and §7.6 move the five goldens once, §2.8), no agent option, no protocol change,
no new topic, no new error code, no `plan.json` change, no bindings change. `INPUT_REJECTED` gains the
`data.reason` values `vetoed` (§3.2) and `not visible` (§3.3); `INVALID_PARAMS` gains `collapsed` (§3.3); the
pointer's `fired: false` `reason` gains `VETOED`. Additive result keys: Node `vetoed`, `collapsed` (only when true);
`swing.hitTest` `vetoed` (always), `source`; `swing.pointer` `source`, `vetoed` (on a `VETOED` result);
`swing.dialogs` button `vetoed` (only when true). `PrimitiveSpec` descriptions of `swing.click`, `swing.type`,
`swing.key`, `swing.pointer`, `swing.dismiss`, `swing.hitTest`, `swing.snapshot` and `swing.find` gain one sentence
each (the veto, `collapsed`, `source`); the session stand-in recordings that embed the tool list are regenerated once
in step R anyway (§2.8 item 5). `swing-module.properties` follows the POM: no module bump. The Swing module's
`AgentModule.info()` answers `{vetoWithheld: <n>}` (§3.2 rule 4; `agent.info.modules[]` carries it as `info`).

### 3.7 Bounds and threads (joins P7 §2.4's table)

| Where | Bound |
|---|---|
| `InputVeto` | EDT only, inside the call's read or posted task; ≤ 64 ancestors per ask, memoised per call and route; a throwing veto refuses; no new timeout |
| `Visibility` | EDT only; O(depth) per component, O(nodes) per walk with the parent's area carried down |
| `HitTest.sourceAt` | one `getDeepestComponentAt` and one walk up, inside the existing pre-check read; `Container`-typed for the headless test, the window in production |
| `swing.pointer` ROUTED | at most **101** events per call (P7's 100 plus the exit of §3.4 rule 3); each task now checks `cancelled`, the modal state and the per-event veto; the balance adds at most one `MOUSE_EXITED` |
| `Clicks.semantic` | one dispatched `ActionEvent`/`ItemEvent` inside the posted task |

---

## 4. Threads, lanes and the data channel

Phase 8 gives the agent two more places to run work, both bounded and both visible in `agent.info`: the application's own
thread at its quiescent point (gap 10) and named lanes of agent threads (gap 9); it makes a provider-executed read
stoppable (gap 11); it names the columns of a positional source from the statement itself (gap 12) and lets a provider
name a table's columns for the allow-list start check (gap 117); and it retires the procedure call the allow-list
promised and no channel ever ran (gap 119). Everything is additive on the wire (protocol `0.2`, §2.1): new enum
constants appended, default methods, optional descriptor keys, new `data` keys on existing error codes, new keys under
`agent.info.threads` and in `data.sources` entries. No DSL change, no `plan.json` change, no new topic, no new error
code. **Nothing changes for a target or a module that uses none of the new policies, methods or keys**, except the five
behaviour corrections of §4.8 (items 1, 2, 4, 5 and 6; items 3 and 7 correct a document and a literal), each a
correction of what the contracts already promised.

### 4.1 `ThreadPolicy.APP_QUIESCENT` and `QuiescentPoint` (gap 10; D519 to D523)

**Decision (D519).** A task may run on the application's own thread (the one its `ThreadPolicyProvider` forbids) at
that thread's next quiescent point, **admitted by a declared budget and never pre-empted**: the application thread is
never interrupted by the agent (in the sample an interrupt ends the engine, `app/Engine.java:276, 434-435`), so
"bounded" means admission and measurement, not enforcement. The queue, the admission, the exactly-once-or-never rule
and the statistics are owned by the base in one helper, so that every module gets the same semantics and the core can
see, bound and report the runner (the row's "the module builds the runner itself" is what this replaces).

**Types (`agent-api`, Java 8, additive; A72).**

1. `ThreadPolicy.APP_QUIESCENT`, appended after `APP` (the order `CALLER, EDT_SYNC, EDT_ASYNC, WORKER, APP,
   APP_QUIESCENT, LANE` is the new pinned order, `ValueTypesTest.threadPolicyNamesAreStable`; D524). Javadoc: "runs on
   the application's own thread at its next quiescent point, through the first `ThreadPolicyProvider`'s
   `quiescentPoint()`; never falls back to another thread".
2. `public final class ai.skillroy.testjava.agent.api.QuiescentPoint implements AutoCloseable` (depends on `protocol`'s
   `RpcException` and `ErrorCodes` only):

   | Member | Contract |
   |---|---|
   | `QuiescentPoint(long allowanceMs, int capacity)` | `allowanceMs` 1..1000 (`MAX_ALLOWANCE_MS`): the most one task may hold the application's thread; `capacity` 1..16 (`MAX_CAPACITY`): queued tasks; `IllegalArgumentException` outside |
   | `<T> T await(Callable<T> task, long budgetMs, long timeoutMs) throws Exception` | **agent threads only** (the core calls it; a caller the broker forbids is refused `FORBIDDEN_THREAD` by the broker before it gets here). The helper guards the one route the broker cannot see (D639): it keeps `volatile Thread appThread`, set by every `runOne` call, and `await` called on that thread, or while a task of this point runs on the calling thread, throws `RpcException(FORBIDDEN_THREAD, data {reason: "await on the application's thread"})` at once (the next idle point cannot come while the only application thread waits, so such a wait would always run to `timeoutMs` and end `NOT_READY`). `budgetMs` 1..`allowanceMs()`, `timeoutMs` 1..120,000 (the dispatcher's `MAX_TIMEOUT_MS`), else `IllegalArgumentException`. Queue full: `RpcException(BUSY, data {reason: "quiescent queue full", capacity})` at once. Not admitted within `timeoutMs`: the task is **withdrawn atomically** (a CAS `QUEUED -> WITHDRAWN`; it runs exactly once or never, never later) and `RpcException(NOT_READY, data {reason: "no quiescent point within <t> ms", budgetMs, waitedMs})`. Admitted and still running when `timeoutMs` expires: the task keeps running, the caller gets `RpcException(TIMEOUT)` (the core turns it into its own `BrokerTimeout`, so the dispatcher's S30 gate applies). A task that threw: its exception is rethrown here |
   | `boolean runOne(long availableMs)` | **the application's thread, at its idle point** (the provider calls it from the application's idle callback). Never blocks, never throws (a `Throwable` from the task is delivered to its caller). Runs **at most one** task per call, the FIFO head only, and only when `budgetMs <= availableMs` **and** the caller still has at least `budgetMs` of its time left; a head whose caller has less is withdrawn with `NOT_READY {reason: "too little of the call's time left for its budget", budgetMs, remainingMs}` and `runOne` returns false without looking further (FIFO is kept: the next head waits for the next point). `availableMs` is what the application says is free until its next scheduled work (`Long.MAX_VALUE` when unknown; a negative value counts as 0). Returns true when a task ran |
   | `void close()` | withdraws every queued task with `NOT_READY {reason: "quiescent point closed"}`; later `await` answers `CAPABILITY_UNAVAILABLE {reason: "quiescent point closed"}`; `runOne` runs nothing; idempotent. The provider calls it from `uninstall`, the core on agent close (§4.1 "Lifecycle") |
   | `long allowanceMs()`, `int capacity()`, `boolean isClosed()`, `Map<String, Object> stats()` | `stats()` = `{allowanceMs, capacity, queued, ran, notReady, refused, overruns, lastOverrun: {budgetMs, ranMs} \| null}`, a new map each call |

3. `ThreadPolicyProvider`: `default QuiescentPoint quiescentPoint() { return null; }`. "Null: the provider offers no
   quiescent point. Return the **same instance** on every call; must be cheap and must not block; a throw counts as null
   (logged once per provider by class name)." The provider creates one point and calls `runOne(available)` from the
   application's idle callback: an application listener (the sample's `EngineListener.idle`, §12.1) or the `EventSink`
   of an `OBSERVE` hook on the application's idle method (P6 §4; a hook-entered runner runs inside the hook handler with
   its re-entrancy flag set, so hooked methods it calls publish nothing, `HandlerCore.java:105-108`).
4. `PrimitiveSpec.Builder.quiescentBudgetMs(long ms)`: 1..1000; legal only with `thread(APP_QUIESCENT)`, and
   `build()` throws `IllegalStateException` when the budget is set under another policy; default **50** for an
   `APP_QUIESCENT` spec. `PrimitiveSpec.quiescentBudgetMs()` (0 for other policies). `PrimitiveDescriptor` gains the
   optional key `quiescentBudgetMs`, written only for that policy and read leniently (the `script` precedent,
   `PrimitiveDescriptor.toJson`), so every existing `/schema` entry reads as before; `thread` carries the new name
   `"APP_QUIESCENT"` (the controller only prints it).
5. `ThreadBroker`: `default <T> T callQuiescent(Callable<T> task, long budgetMs, long timeoutMs) throws Exception`
   (default throws `UnsupportedOperationException`, so every test fake in `agent-data`, `agent-swing`, `agent-web`,
   `agent-byteman`, `agent-hooks` and the sample modules compiles unchanged); `call(APP_QUIESCENT, task, t)` =
   `callQuiescent(task, 50, t)`. **`post(APP_QUIESCENT, …)` is refused** (one `warn` line, the task never runs: a
   posted task has no caller whose time bounds it). `post`'s switch must name it: today an unnamed policy falls into
   `default: workers.execute` (`CoreThreadBroker.java:174-176`), which would run it on a worker (§4.8 defect 5).

**Dispatch (`agent-core`; A72).** For a spec with `APP_QUIESCENT`, `Dispatcher.invokeChecked` adds, after the
capability gates and in place of the A19 branch, in this order (each a refusal with `data.primitive`):

| Condition | Answer |
|---|---|
| no `ThreadPolicyProvider` registered, or its `quiescentPoint()` is null, throws, or is closed | `CAPABILITY_UNAVAILABLE {reason: "no quiescent point offered"}` |
| the provider's `available()` is false (or throws) | `NOT_READY {reason: "application thread not available yet"}`, **for reads and mutating calls alike** (no `WORKER` fall-back: the read is correct only at that point; D522) |
| `spec.quiescentBudgetMs() > point.allowanceMs()` | `CAPABILITY_UNAVAILABLE {reason: "quiescent budget exceeds the allowance", budgetMs, allowanceMs}` |
| the overrun penalty of this primitive is set (below) | `CAPABILITY_UNAVAILABLE {reason: "quiescent task overran its budget", budgetMs, ranMs}` |
| an earlier call of this primitive timed out and still runs | `BUSY` as S30 (`APP_QUIESCENT` joins the gate's policy list, `Dispatcher.java:274`) |

Then `CoreThreadBroker.run` case `APP_QUIESCENT`: `point.await(quiescentGuarded(task), budgetMs, timeoutMs)`; the
core waits no longer than the helper (no provider grace: the base owns the helper). `RpcResult.thread` is the
application's thread name (in the sample `LendingEngine`), recorded in `run.jsonl` as today.

**The one exemption (D520).** `CoreThreadBroker.Guarded` refuses to run on a forbidden thread
(`CoreThreadBroker.java:362-367`). A quiescent task is wrapped in a `Guarded` variant that **does not** refuse the
forbidden thread; the variant is constructed by the core for `APP_QUIESCENT` only and reaches the application thread
only through `QuiescentPoint.runOne`, which holds it privately. Inside it: the `CallScope` is bound and restored with
`remove()` when there was none (no `ThreadLocal` left on the application thread); `ranMs` is measured; a nested
`ThreadBroker.call`, `callQuiescent` or `QuiescentPoint.await` from the task answers `FORBIDDEN_THREAD` (the caller
check at `CoreThreadBroker.java:93-96` stays; the helper's own check is D639); `EventBus.await`
(`RingEventBus.java:229-251`) has no forbidden-thread check today (EDT code may call it, and adding one would change an
API every module uses for a route that is not new in Phase 8) and is **forbidden to a quiescent task by the rule
below, unchecked**, as it is to EDT code; `post` and `EventBus.publish` are allowed (they never block). The contract
sentence "a task never runs on a thread a provider
forbids" (P1 §4, `ThreadBroker` Javadoc, `CoreThreadBroker.java:44`) becomes "… except a task the core submitted
through a `QuiescentPoint`, at the point the provider itself runs it". The core never calls `interrupt()` or
`cancel(true)` for this policy.

**Overrun penalty (D521; restrictive default).** When `ranMs > budgetMs` the core logs once per primitive and counts
`overruns`; when `ranMs > 2 * budgetMs` the primitive is penalised: every later call answers `CAPABILITY_UNAVAILABLE
{reason: "quiescent task overran its budget", budgetMs, ranMs}` until `agent.capabilities {reprobe: true}` (which clears
the penalties) or the agent restarts. A module that cannot keep its budget is thereby found at the first run, not
after it has held the application's thread a hundred times.

**Reporting.** `agent.info.threads.quiescent` = `{provider, allowanceMs, capacity, queued, ran, notReady, refused,
overruns, lastOverrun: {primitive, budgetMs, ranMs} | null, penalised: [<primitive>…]}` when the first provider offers
a point, else `null` (additive key beside `workers`, `callers`, `abandoned`; `AgentRuntime.threadsJson`).

**Lifecycle.** On `agent.disable` and on close the core calls `close()` on the first provider's point (idempotent with
the module's own call) **before** the module's `uninstall` returns the thread to the host, so that no module code runs
on the application's thread after the agent is gone. `ModuleContext.rollback()` (a failed install) does the same.

**Capability (D523).** No core capability id (it would move all five goldens for a core id): a primitive on
`APP_QUIESCENT` names its own capability, whose probe checks that a point is offered (the sample: `lending.engine.view`,
§12.2). The dispatcher's `CAPABILITY_UNAVAILABLE` of the table above fails such a call fast without one.

**Thread rules for a quiescent task** (contract text, also for the guide §6.4): `runOne` only on the application's
thread at an idle point; the task touches no AWT (it is not the EDT), does not block, takes no application lock the idle
point does not already hold, never waits for agent work (`ThreadBroker.call*` and `QuiescentPoint.await` are refused
from it, `FORBIDDEN_THREAD`; `EventBus.await` is forbidden to it and not checked); `AgentLog` and `EventBus.publish` are
allowed; its result is JSON-able as any primitive's.

**As built (Phase 8, ET).** `QuiescentPoint` publishes its bounds as `MAX_ALLOWANCE_MS` (1000), `MAX_CAPACITY` (16),
`MAX_TIMEOUT_MS` (120,000) and `DEFAULT_BUDGET_MS` (50, the spec's default). One task runs at a time per point: a
`runOne` called while another call of it is running a task (another thread, or the task itself) returns false at once.
A null task is an `IllegalArgumentException`. `stats().refused` counts the `await` calls refused at once (a full queue,
a closed point, the application's own thread), `notReady` the tasks withdrawn unrun (no point in time, too little of the
call's time left, the point closed, an interrupted wait). The `TIMEOUT` of an admitted task carries `{reason: "quiescent
task still running", budgetMs, waitedMs}`. An `await` whose waiting thread is interrupted withdraws its task when it is
still queued and rethrows the `InterruptedException`. The point's monitor guards the queue and the statistics only and is
never held while a task runs; the exactly-once-or-never decision is one compare-and-set on the task's state, whose
terminal value carries the outcome, so a caller that lost the race reads the result without waiting again.
`PrimitiveDescriptor` gains a twelve-argument constructor (the nine- and ten-argument ones kept, both keys unset);
`quiescentBudgetMs` is written when positive and `lane` when non-null, both take part in `equals`, and both are read with
the descriptor's usual optional readers (a value of the wrong type is a `JsonException`, as for `script`).

**As built (Phase 8, ET, the body).** The gates, the wrapper and the penalties live in `CoreThreadBroker`
(`quiescentGate`, `runQuiescent`, `QuiescentTask`): the dispatcher asks `quiescentGate(primitive, budget)` and runs
`runQuiescent(primitive, task, budget, timeout)`; `callQuiescent` and `call(APP_QUIESCENT, …)` (budget 50) pass the same
gates without the penalty, which is per primitive. The wrapper marks its thread as running a quiescent task, so a nested
`call`, `callQuiescent` or `callOnLane` from it is `FORBIDDEN_THREAD` even when the provider does not forbid that thread;
it measures `ranMs` itself, logs the first overrun of each primitive, records `lastOverrun {primitive, budgetMs, ranMs}`
(when only a module's own `await` overran, `agent.info` shows the point's `lastOverrun` with `primitive: null`) and
penalises above twice the budget; `penalised` lists the primitives in name order. `agent.capabilities {reprobe: true}`
clears the penalties whether or not the target is ready (the probes still run only when it is; the one hunk in
`CorePrimitives`, in `agent.capabilities`; `agent.info.threads` comes from `AgentRuntime.threadsJson`, so no hunk there).
Besides the core's close of the first provider's point on close and `agent.disable`, `ModuleContext.closeQuiescentPoints()`
closes the point of every provider a module registered before that module's `uninstall` runs, after a failed install
too, and `rollback()` does it again (idempotent). The core test fixture's provider offers a point (allowance 100 ms,
capacity 4) only with `coretest.quiescent=true`, driven every 2 ms by a `coretest-forbidden-engine` thread.

### 4.2 Named lanes: `ThreadBroker.declareLane`, `ThreadPolicy.LANE`, `DataSourceProvider.lane()` (gap 9; D524 to D528)

**Decision (D525).** A **lane** is a named, bounded group of agent-owned daemon threads with a bounded FIFO, declared
by a module, visible in `agent.info`, retired with the module. The base owns the threads (not the provider), so the
bound, the queue and the report are the base's and S34 applies to them as to workers (`DaemonThreads`: agent group, host
context class loader, daemon, idle end after 30 s). The row's "per-provider thread policy for `data.query`" is rejected
(it would move reads onto the one `APP` lane, which is the problem).

**Types (`agent-api`, Java 8, additive; A73).**

```java
// new, package ai.skillroy.testjava.agent.api
public interface Lane extends AutoCloseable {
    String name(); int threads(); int queue();
    /** Retires the lane (§4.2 "Retirement"); idempotent. */ @Override void close();
}
// ThreadBroker, two default methods (both throw UnsupportedOperationException by default; fakes keep compiling)
default Lane declareLane(String name, int threads, int queue);
default <T> T callOnLane(String lane, Callable<T> task, long timeoutMs) throws Exception;
```

| Rule | Value |
|---|---|
| name | `^[a-z][a-z0-9-]*(\.[a-z0-9-]+)+$`, at most 64 characters; by convention `<module id>.<word>` (`lending.reads`) |
| `threads` | 1..4; `queue` 0..16 (0: no queue, a task that finds every thread busy is refused at once) |
| lanes per agent | at most 8, counted over every module; `IllegalStateException` for the ninth, for a duplicate name, for a declaration after the agent started closing |
| threads | named `skillroy-lane-<name>-<n>`, created on demand, ended after 30 s idle (`DaemonThreads.boundedPool` with an `ArrayBlockingQueue(queue)`, or the `SynchronousQueue` for 0, and `AbortPolicy`) |
| `callOnLane` | a forbidden caller: `FORBIDDEN_THREAD` (as `call`); `timeoutMs <= 0`: `IllegalArgumentException`; unknown or retired lane: `CAPABILITY_UNAVAILABLE {reason: "unknown lane", lane}` (a retired one: `"lane closed"`); every thread busy and the queue full: `BUSY {reason: "lane full", lane, threads, queue}`; every thread held by a task whose caller gave up: `BUSY {reason: "lane stuck", lane, runningForMs}` at once, before queueing; expiry while queued: the task is withdrawn and never runs, `TIMEOUT`; expiry while running: the thread is interrupted (best effort), the task is counted `abandoned` for the lane until it ends, `TIMEOUT`. The `CallScope` travels with the task (A34), as on a worker |
| `ThreadPolicy.LANE` (D526) | `PrimitiveSpec.Builder.lane(String name)` sets the policy to `LANE` and the name (`build()`: `IllegalStateException` for `LANE` without a name, or a name under another policy); `PrimitiveSpec.lane()`; descriptor `thread: "LANE"` plus the optional key `lane`, written only when set. The dispatcher runs such a primitive through `callOnLane(spec.lane(), task, timeoutMs)` with the S30 per-primitive gate as for `WORKER`; a lane the module has not declared (or retired) answers the `CAPABILITY_UNAVAILABLE` above. `post(LANE, …)` is refused and logged (no lane name in `post`) |
| retirement | `Lane.close()`: queued tasks fail `CAPABILITY_UNAVAILABLE {reason: "lane closed", lane}`, running ones are interrupted (best effort) and finish on their own, threads end; the name may be declared again. **`ModuleContext` wraps `threads()`** (today it returns the core broker unwrapped, `ModuleContext.java:108-110`) in a scoped broker that records the lanes declared through it and retires them in `rollback()` and after the module's `uninstall` (the A18 pattern for capabilities). On agent close every lane is retired after the modules' `uninstall` and before the pools shut down |
| reporting | `agent.info.threads.lanes` = `{<name>: {owner: <module id>, threads, queue, active, queued, abandoned}}` (additive) |

**`DataSourceProvider.lane()`** (A73): `default String lane() { return null; }`. Null: today's behaviour (the read
runs on an agent worker task). A name: the module's declared lane, on which `data.query` runs this source's
`query()`, or `open()` and the JDBC read, and `columns(table)` (§4.4). Asked once per call on the dispatching worker;
must not block; a throw counts as null (logged by class name).

**`data.query`'s hand-off and the per-source gate (D527; `data/DataQuery.run`).** `data.query` keeps `WORKER` and its
spec (the `/schema` entry and `DataModuleTest`'s four parameters are unchanged). Its body hands the provider's work
(either form, `columns(table)` included) to the source's lane through `callOnLane`, or, when the source has none, to a
nested `WORKER` task through `ctx.threads().call(WORKER, …)` (the worker pool is sized for nested calls,
`AgentRuntime.java:224-226`; a full pool is `BUSY {reason: "pool full"}` as today), and waits **`remainingMs - 250`**
(at least 1 ms) on it, so that the module answers before the dispatcher's deadline and the `data.query` call itself is
never abandoned. On expiry the module answers its own `TIMEOUT` (§4.3 says what it does first, inside the reserve):
`data {primitive: "data.query", timeoutMs: <the call's budget>, source, lane: <name|null>, waitedMs, cancelled,
cancelPending?, stillRunning, enforcesTimeout}`; `primitive` and `timeoutMs` keep the keys `AgentDataChannel.map` reads
(`AgentDataChannel.java:183-187`). **Per-source gate:** without a lane, while an earlier read of the **same source** still
runs after its call ended, a new read of that source answers `BUSY {reason: "an earlier read of the source still runs",
source, runningForMs}`; with a lane, the lane's own `BUSY` applies and the per-source gate does not. The per-primitive S30
gate stays on `data.query` but no longer fires for it, so one stuck source no longer makes `data.query` `BUSY` for every
source (§4.8 defect 4; map B §2.1 found it). `data.sources` entries gain `lane` (null or the name) and
`enforcesTimeout` (§4.3); `AgentDataChannel.servedSources` reads `id` only, so the controller is unaffected.

**Keeping `APP` free (D528).** The contract and the guide §6.4 say: never hop to `APP` for a slow read; declare a lane.
`APP` is **not** bounded in Phase 8 (a bound of 8 calls in flight would add a `BUSY` path for every existing `APP`
primitive, a behaviour change for the sake of a case no target has shown); the guide's sentence that `data.query` runs
on `APP` is corrected (it runs on `WORKER`, `DataModule.java:161`; §4.8 defect 3). Recorded as Q13 and gap 136.

**As built (Phase 8, ET).** `Lane` carries its bounds as constants (`MAX_THREADS` 4, `MAX_QUEUE` 16, `MAX_LANES` 8,
`MAX_NAME_LENGTH` 64, `NAME_PATTERN`) and a static `isValidName(String)`. `PrimitiveSpec.Builder.lane(name)` refuses a
name outside the grammar at once (`IllegalArgumentException`), so that a spec can only name a lane that can be
declared; `declareLane` answers `IllegalArgumentException` for a name, thread count or queue outside its bounds and
keeps `IllegalStateException` for the ninth lane, a duplicate and a declaration while the agent closes.
`ThreadBroker.call(LANE, …)` is refused as `post(LANE, …)` is (it names no lane; `callOnLane` does). The core test
fixture's `test.where.lane` names the lane `coretest.where`.

**As built (Phase 8, ET, the body).** `Lanes` (core) holds the registry. A lane is a `ThreadPoolExecutor` whose core
size is its bound with `allowCoreThreadTimeOut(true)` and 30 s keep-alive (a pool of core size 0, as
`DaemonThreads.boundedPool` builds, would start a second thread only once its queue is full), the agent's
`DaemonThreads` factory (`skillroy-lane-<name>-<n>`, the agent's group, the host's loader), an `ArrayBlockingQueue(queue)`
or a `SynchronousQueue` for 0, and the abort policy. A task is withdrawn by a compare-and-set from its queued state and
removed from the queue, so it runs exactly once or never; a running one is interrupted under the task's own monitor
(the interrupt flag is cleared when the task ends, so it never reaches the lane's next task) and counted abandoned until
it ends ("the abandoned task on lane n ended after m ms"); "lane stuck" answers while the abandoned tasks number at
least the lane's threads, with the oldest one's `runningForMs`. A retired name is remembered (the newest 64) so that a
call names it `"lane closed"` rather than `"unknown lane"`; declaring it again forgets that. The core's own
`declareLane` gives the owner `core`; a module's scoped broker (`ModuleContext.threads()`, the same object for the
module's life) gives its module id and retires its lanes in `rollback()`. On close the core refuses declarations from the
start of the close and retires every lane after the modules' `uninstall`, waiting at most 500 ms per lane for its
threads. `BUSY "lane full"` carries `threads` and `queue` as numbers.

### 4.3 Query cancellation: `QueryControl`, `enforcesTimeout()` (gap 11; D529, D530)

**Decision (D529).** A provider-executed read receives a **scoped cancellation handle** through a five-argument
default overload of `query`; the data module sets it when the caller's time runs out or the agent closes, runs the
provider's registered actions on an agent thread, bounded, and reports what happened. A provider may also say that it
enforces the timeout itself. The general form (`Invocation.onCancel` for every primitive) is kept as gap 132.

```java
// new, api spi: what the agent hands a provider-executed read
public interface QueryControl {
    /** Time left of the call for the read, in ms (the module's reserve of 250 ms already taken); never negative. */
    long remainingMs();
    /** True once the agent has given up on this read: its caller's time ran out, or the agent is closing. */
    boolean cancelled();
    /** Registers an action the agent runs once when it gives up (typically statement::cancel, or the application's own
     *  abort): on an agent thread of its own (a nested worker task), never the dispatching worker, an application
     *  thread or the EDT, bounded (2 s, then abandoned there and logged by class name). Registered after cancellation:
     *  run at once on the registering thread. At most 4 per read (IllegalStateException beyond). Closing the handle
     *  unregisters the action. */
    AutoCloseable onCancel(Runnable action);
}
// DataSourceProvider, two default methods (A74)
default QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs, QueryControl control)
        throws Exception { return query(sql, params, maxRows, timeoutMs); }   // a 0.1.0 provider keeps working
/** True when query() itself returns or throws within timeoutMs + 1 s whatever the source does (a statement
 *  timeout the source enforces, a server-side limit). Asked per call on the dispatching worker; must not block; a
 *  throw counts as false. Default false. */
default boolean enforcesTimeout() { return false; }
```

**The module (`DataQuery.run`, on the hand-off of §4.2; D638).** One `QueryControl` per call, `remainingMs` = the
call's remaining time minus the 250 ms reserve. The five-argument form is called (a provider that overrides only the
four-argument one is served through the default). On expiry of the module's wait: `cancelled()` becomes true and the
registered actions are started **off the dispatching worker, as one nested `WORKER` task**
(`ctx.threads().call(WORKER, …)`, the pool being sized for nested calls) that runs them in registration order, each
through `ctx.threads().call(WORKER, action, 2000)`; an action still running after its 2 s is abandoned there, counted
and logged by its class name (S30's own mechanism; a throwing one is logged by class name). The dispatching worker
waits at most **`reserve - 50 ms`** for the actions and the read together, then answers the `TIMEOUT` of §4.2 **inside
the reserve**, with `cancelled` (a boolean: true when an action returned without throwing, or the JDBC statement was
cancelled), `cancelPending: true` (written only when an action was still running at the answer; a new key, §2.1),
`stillRunning` (the read had not ended when the answer was written) and `enforcesTimeout` (the provider's answer). The
per-source gate (or the lane's own `BUSY`) keeps at most one cancel batch per source in flight. A read or action that
ends after the answer is logged ("the read of source s ended after n ms") and clears the per-source gate. Why not on
the dispatching worker (the fragment's form): up to four actions of 2 s plus 500 ms is 8.5 s of work started 250 ms
before the dispatcher's deadline, so any cancel action slower than about 250 ms (a driver whose `cancel()` makes a
network round trip) would let the dispatcher abandon the call by primitive name and bring back §4.8 defect 4, and a
synchronous action holding the only thread cannot also be "abandoned after 2 s".

**The JDBC form (D530).** `proto/sql/ReadOnlyJdbc` gains an overload `executeLenient(Connection, String,
List<Object>, int maxRows, int timeoutSeconds, Consumer<String> warn, Consumer<Statement> statementSink)` (Java 8;
the six-argument form delegates with a null sink): the sink receives the `PreparedStatement` right after
`prepareStatement` and **null after the statement is closed**, so a caller can cancel only while the statement is open
(never a later statement on a pooled connection). `data/JdbcExecutor.execute` registers `control.onCancel(() -> { s =
current; if (s != null) s.cancel(); })` through that sink; a `SQLException` or `SQLFeatureNotSupportedException` from
`cancel()` is logged by class name and counted as not cancelled. `setQueryTimeout` keeps today's seconds
(`remainingMs / 1000`, at least 1; `DataQueryJdbcTest.queryTimeoutComesFromTheRemainingTime` stays as it is: the cancel
now covers what the rounding leaves). `open()` is unchanged and still has no timeout: the provider bounds any pool wait
(stated in the SPI's Javadoc; `open(QueryControl)` is gap 135). The controller's `JdbcDataChannel` may adopt the sink
later (gap 137; not required).

**Agent close.** `DataModule.uninstall` keeps the set of live controls, sets each cancelled, starts its actions the
same way (one nested worker task per control) and waits for them within its own share of `UNINSTALL_TIMEOUT_MS`
(`AgentRuntime.java:129`) before it unregisters the primitives.

**Restrictive default.** A provider that neither enforces the timeout nor registers an action is still bounded by the
module's wait; its stuck read blocks its own source (the per-source gate) or its own lane, nothing else.

**Version skew.** Default methods keep a 0.1.0 provider working on a Phase 8 agent. A module that overrides only the
five-argument form requires a Phase 8 agent (on a 0.1.0 agent the four-argument default answers null, the module falls
to `open()` and answers `CAPABILITY_UNAVAILABLE`); the guide says so and names the rule of §4.4 (mixed versions are
unsupported).

**As built (Phase 8, ET).** `QueryControl` carries `MAX_ACTIONS` (4) and `ACTION_TIMEOUT_MS` (2000) as constants.

**As built (Phase 8, ET, the body).** The read is one task (`DataQuery.Read`) on the source's lane or a nested
`WORKER` call, bounded by the budget (`remainingMs - 250`, at least 1), which is also the `timeoutMs` the provider's
`query()` receives (a 0.1.0 test that pinned the call's whole remaining time now sees the budget); it runs the
provider's form, `columns(table)` and the conversion of the rows (so a large object is read on the read's own thread),
and maps every failure but an interrupt as before Phase 8; the masks run on the dispatching worker. A `TIMEOUT` from the
hand-off counts as the module's expiry only while the read has not ended (a provider's own `TIMEOUT` passes through).
The cancel batch is started with `ThreadBroker.post(WORKER, …)`, not `call` (a `call` would hold the dispatching worker
for the batch, or, bounded to the answer's wait, interrupt the batch and with it the action it waits on); each action
runs through `call(WORKER, action, 2000)`. The answer waits at most `min(200 ms, the dispatcher's remaining time - 50 ms)`,
at least 1 ms. `cancelled` is true once an action returned without throwing, or the JDBC action found the statement
open and its `cancel()` returned; `cancelPending` while an action of the batch had not ended at the answer. The fifth
registration (closed ones included) is refused. `DataModule.uninstall` cancels the live reads and waits for their batches
at most 3,000 ms in all (`UNINSTALL_CANCEL_MS`) before it unregisters the primitives. The `TIMEOUT` message is
"data.query on source 's' timed out after t ms".

### 4.4 Column names for a positional source, and `columns(table)` (gaps 12, 117; D531, D532)

**Decision (D531).** When a provider of the statement-API form leaves a column unnamed, `data.query` names it from
the statement's own top-level select list, **inside the target, before the masks run**: a JDBC source's label also
comes from the select list (`getColumnLabel`), so a positional source then behaves like a JDBC one, a name-keyed
`MaskPolicy` sees real names, and every caller (`db.*` checks, the binder's samples, a model's guarded statement, the
D100 check) benefits without a new parameter. The provider's own names always win. No wire change.

**`proto/sql/SelectLabels`** (new, Java 8, public final, stateless, never throws; A75):

```java
/** One entry per top-level select item of a statement StatementGuard accepted: the alias (AS x, or a trailing
 *  identifier that is not a keyword), else the last part of a bare [qualified] column reference, quotes removed;
 *  null for any other item (an expression without alias). Null for the whole list when it cannot be read: a top-level
 *  set operation, a * or t.* item, a dialect reading that splits the list differently, more than 1000 items,
 *  parentheses nested beyond 32. */
public static List<String> of(String sql);
/** For SELECT * FROM <table> [[AS] alias] [WHERE …] [ORDER BY …] [FETCH/LIMIT …] (one table, no join, no set
 *  operation): the table as written, quotes removed, a schema part kept; else null. */
public static String starTable(String sql);
```

It reuses `StatementGuard`'s lexical rules through a package-private `SqlTokens` (the tokenizer moved out of
`StatementGuard.java:167-293` as a **pure refactor first**, `StatementGuardTest` unchanged and green, the S36 and
P2-L3-F1 cases included; D537); never a regular expression over the raw text. Grammar: leading comments; an optional
`WITH` list skipped by balanced parentheses; `SELECT`; optional `DISTINCT`/`ALL`; optional T-SQL `TOP n`/`TOP (n)`;
items split at depth-0 commas until a depth-0 `FROM` or the end (`SELECT 1` has no `FROM`). Conservative: on any doubt
the entry, or the list, is null. Linear in the text, bounded depth.

**The naming and width rule** (`data/Table.fromQueryResult(result, maxRows, derivedLabels, starColumns)` and
`DataQuery.run`; amends P1 §6 "Columns a source cannot name are reported as `c1..cn`" and the `QueryResult` Javadoc):

1. column *i*'s name: the provider's name when non-null and non-empty; else the derived label *i* when the derived list
   has the result's width; else, for the star form with no provider names, `columns(table)` entry *i* (below); else
   `c<i+1>` as today;
2. width: the maximum of the provider's names, **the provider's types** (§4.8 defect 2), every row the provider
   **returned** (kept or not: `maxRows: 0` asks for one row and keeps none, and P1 §6 promises the columns then), and,
   when the provider returned no row, the derived list's size or the `columns(table)` list's size (§4.8 defect 1: a
   zero-row positional result had width 0, so a `db.*` absence check ended `ERROR / ENVIRONMENT` and the D100 check
   could never pass);
3. a derived or `columns(table)` list whose size differs from a non-zero width taken from names or rows is ignored for
   naming, with one `data:` log line naming the source and both counts, never the statement or a value;
4. masks (`DataQuery.mask`) receive the final names. Parity, stated in the guide §6.11: a caller who renames a column
   (`password AS p`) defeats a name-keyed mask on a JDBC source today (`PasswordMask` Javadoc); a derived label gives a
   positional source exactly that behaviour, no weaker. The JDBC form is unchanged (the driver's labels).

**`DataSourceProvider.columns(String table)` (gap 117; D532; A75).**

```java
/** The column names of {@code table} in the table's declared order, or null (the default) when the provider cannot
 *  tell. data.query asks it only for a statement of the form SELECT * FROM <table> … (SelectLabels.starTable) whose
 *  query() result named no column, and names that result's columns with it; the controller's allow-list check (D100)
 *  reads those names. {@code table} is the table as the statement spells it, quotes removed, a schema part kept; the
 *  provider compares it as its database folds names, and must answer the same relation query() reads. All or nothing:
 *  a list with a null or empty entry counts as null. Called on the source's lane or the read's worker task, at most
 *  once per data.query call, within that call's budget; must not block and must not start host infrastructure
 *  (S34). A throw counts as null; the agent logs the exception's class only (S22). */
default List<String> columns(String table) { return null; }
```

`…inference.sql.AllowListCheck` keeps its logic and its statement text byte for byte (scenario D's committed bindings
record it); only its refusal message gains a hint when the source reported no column, or only names of the form
`c<digits>`: "source X reports no column names for table T (a provider that answers by position); its
`DataSourceProvider` should answer `columns(table)` (gap 117)". A message, never a pass; a count-only pass is rejected
(the list has no total column order, `AllowedTable.columns` and `otherColumns` are separate; a count cannot see a
renamed column). `null` keeps meaning "unknown" and the check keeps refusing.

**As built (Phase 8, ET).** `SqlTokens` is a token stream over one dialect reading (`WORD`, `SEMICOLON`, `STRING`,
`IDENTIFIER`, `OTHER`, an unterminated literal or comment ending the stream); the dialect features and the four
readings moved with it, and `StatementGuard`'s rules read its tokens unchanged (`StatementGuardTest` untouched and green;
a differential run of the old and the new guard over 4.5 million generated and corpus statements found no difference in
any answer). `SelectLabels` answers null for a statement the guard rejects (it runs the guard itself) and reads each
dialect reading; the readings are combined conservatively: a reading that meets an unterminated literal or comment, or
brackets that do not balance, does not count (the statement is not valid in that dialect); an item holding a backtick or
a square bracket that the reading does not take as a quote does not count in that reading (the dialect that quotes with
it decides); the readings that count must split the list alike (else the list is null) and agree on an entry (else the
entry is null). Square brackets and braces nest with parentheses, so the commas of `ARRAY[1, 2]` and `{fn f(a, b)}` stay
inside their item, and the depth bound of 32 counts all three. `DISTINCT ON (…)` is skipped; `IS [NOT] DISTINCT FROM`
and `WITHIN GROUP` do not end the list; `WHERE`, `GROUP`, `HAVING`, `ORDER`, `LIMIT`, `OFFSET`, `FETCH`, `WINDOW`,
`INTO`, `FOR` and the set operations end it as `FROM` does. An alias after `AS` is any identifier but a reserved word; an
alias without `AS` must follow a token that ends an expression (`)`, a literal, a quoted identifier, a number, a name, or
`END`/`NULL`/`TRUE`/`FALSE`/the `CURRENT_*` words), must not be a soft keyword (a type name, an interval unit, an ordering
or frame word) and must not follow one that leads (`AT`, `ZONE`, `TO`, …), and a square-bracket identifier is an alias
only after `AS` (`x [y]` is also PostgreSQL's subscript). A string literal is never a label, after `AS` included
(`AS 'x'`, which MySQL and SQL Server read as an alias, gives null). Labels keep the case they are written in.
`starTable` takes at most three name parts, refuses a quoted part holding a `.`, and refuses a reading whose rest after
`WHERE`, `ORDER`, `FETCH`, `LIMIT` or `OFFSET` holds a set operation, `JOIN`, `GROUP`, `HAVING`, `WINDOW`, `INTO`, `FOR`
or a row-pattern or pivot word at the top level; `MAX_ITEMS` (1000) and `MAX_DEPTH` (32) are public constants.

**As built (Phase 8, ET, the body).** `Table.fromQueryResult(result, maxRows, derivedLabels, starColumns, mismatch)`
(the two-argument form kept): the select list is the candidate when it could be read, else, when the provider named no
column, the `columns(table)` list; a candidate whose size differs from a non-zero width taken from the names or the rows
is not used, and neither is one whose size differs from the final width (types alone can set it); each such case writes
one `data: source 's': … names n column(s), the result has m; not used for naming` line. `columns(table)` is asked on the
read's lane or worker task only for the star form whose result named no column; a null, empty or partly unnamed list,
and a throw (logged by class), count as none.

### 4.5 Procedures retired honestly (gap 119; D533)

**Decision (D533).** No data channel runs a procedure call, and the contracts stop saying one does. As built:
`SqlGuard.call` accepts `CALL p(?, …)` of an allow-listed procedure (`SqlGuard.java:251-284`), both channels then refuse
it at `StatementGuard` rule 1 ("the statement must start with SELECT or WITH, not 'CALL'", `StatementGuard.java:236-240`;
the JDBC escape `{call …}` at the non-word first token), `ReadOnlyJdbc` has no `prepareCall`, and P5 §5.3 rule 2's "which
the channel runs through the JDBC escape `{call …}`" was never built. Phase 8:

1. `SqlGuard.call` refuses every `Execute` with `Reason.PROCEDURE`, fragment "no data channel runs a procedure call
   (gap 119)"; `GuardViolation.Reason.PROCEDURE`'s Javadoc and `SqlGuard`'s class comment follow. A model or an MCP
   client is thereby told why where the guard speaks (a rejected proposal, `LlmSqlResolver.java:432-438`), instead of
   the slot ending `ENVIRONMENT` through the channel's `GUARD` `DataException` (`LlmSqlResolver.java:460`) or a failed
   step through `data_query`.
2. `SqlAllowList.procedures` **stays a record component** (0.1.0 source and binary compatibility: the canonical
   constructor is called by `DataQueryLists.java:85`, `OverlayPack.java:83` and packs outside the repository), documented
   "reserved for gap 13's prepared reads; must be empty"; `Builder.procedure` is `@Deprecated` and keeps working. A
   non-empty list is refused **where a list is put to use**, never in the record constructor
   (`SkillroyMcpServer.allowListsOf` swallows a `RuntimeException` from `pack.sqlAllowLists()` and would serve no list,
   hiding the refusal): in `InferenceContributor.resolvers` (`ContributionException` -> `ENVIRONMENT` at load: "the SQL
   allow-list of source s names procedures [p]; no data channel runs a procedure call (gap 119; the field is reserved for
   a prepared read, gap 13)") and in `SkillroyMcpServer.checkAllowLists` (`SessionOpenException(ENVIRONMENT)` with the
   same text, before any statement runs). `DataQueryLists.forDataQuery` writes an empty list.
3. `SchemaCatalogue.render` stops printing "Procedures you may call…" (`SchemaCatalogue.java:211-214`); `RULES` never
   mentioned `CALL`, so scenario D's committed recordings are unaffected (§12).
4. The eight accept corpora's `CALL` entries move to the reject corpora with `PROCEDURE`; `parser-limits.sql`'s
   `{call …}` stays. **The guard's corpora now diverge from BS6's frozen spike on `CALL`**; written down in P5 §5.3 and in
   `spikes/BS6/RESULT.md`'s successor note so that no later reader "restores" the spike's rule.
5. Admission, if ever, is gap 13's "prepared read" (a provider-declared unit: id, parameters, the `CALL` and the `SELECT`
   on one connection in one read-only, rolled-back transaction, named by id), recorded in §16.2's deferral of 13 with
   the reasons admission is not taken now: definer's-rights routines write through a read-only account, the read-only
   flag is a hint on two drivers, a rollback undoes neither an autonomous transaction nor an outside effect, and a lone
   `CALL` serves none of the second domain's cases (row 13: its procedures fill session tables a later `SELECT` reads).
   Nothing in a target JVM changes; `replay` is unaffected; the threat model's "SELECT only" row becomes literally true.

**As built (Phase 8, ET).** `SqlGuard.call` throws `PROCEDURE` with the fragment `SqlGuard.PROCEDURE_RETIRED` for every
`Execute` (`CALL` and T-SQL `EXEC`/`EXECUTE`), whatever the list names. The refusal text is one helper,
`AllowListCheck.proceduresRefusal(list)`, used by `InferenceContributor.resolvers` (a `ContributionException`, before
the dialect check) and by `SkillroyMcpServer.checkAllowLists`, which applies it to every list before anything else,
also when the target offers no `data.query` (prefixed "pack <id>: "). The eight accept corpora's `CALL` entries are the
reject corpora's last entry, "PROCEDURE CALL of a procedure: no data channel runs a procedure call (gap 119; BS6
admitted this one)"; `spikes/BS6/RESULT.md` gains the successor note. The regenerated inference fixture is
`21b501e3114ec44612ff43c702a7d0873ae7421bc661bd80f426a95060654dbf.json` (the old file deleted; it differs only by the
fingerprint and the catalogue's procedures line).

### 4.6 Thread rules and bounds (this section's rows for §2.4)

| Where | Bound |
|---|---|
| `QuiescentPoint` | allowance 1..1000 ms, capacity 1..16, one task per `runOne`, FIFO head only, admission by budget and the caller's remaining time, exactly once or never, never interrupted; an overrun counted, 2x penalised; `await` waits at most the call's time, `timeoutMs` ≤ 120,000, refused `FORBIDDEN_THREAD` at once on the application's thread (D639); `runOne` never blocks (a CAS and a hand-over, no lock held while the task runs beyond the point's own monitor) |
| a quiescent task | on the application's thread; no AWT, no blocking, no nested `call`, `callQuiescent` or `QuiescentPoint.await` (`FORBIDDEN_THREAD`), no `EventBus.await` (forbidden, unchecked), `post` and `publish` allowed; budget 1..allowance, default 50 ms |
| lanes | at most 8 per agent, 1..4 threads and a queue of 0..16 each (at most 32 lane threads), named `skillroy-lane-<name>-<n>`, idle end 30 s, retired with the module and on close; `callOnLane` bounded by `timeoutMs`; a running task interrupted on expiry (best effort) and counted abandoned; a lane stuck answers `BUSY` at once |
| `data.query` | `WORKER` as today; the read on the source's lane or a nested worker task; the module waits `remainingMs - 250`; on expiry it starts at most 4 cancel actions (2 s each) on their own nested worker task, waits at most `reserve - 50 ms` for them and the read together and answers inside the reserve (D638); one `columns(table)` call per read; `SelectLabels` linear, depth 32, 1000 items; `lane()`, `enforcesTimeout()` asked once per call, non-blocking |
| `DataSourceProvider.lane()`, `enforcesTimeout()`, `columns()`, `ThreadPolicyProvider.quiescentPoint()` | non-blocking, a throw counts as the default (logged by class name, once per provider for `quiescentPoint()`) |
| `SqlGuard`, `AllowListCheck`, the MCP start check | unchanged bounds; the procedure refusal is a parse-time answer |

### 4.7 Versions, wire and catalogue

Protocol `0.2` (§2.1). Agent API: appended enum constants, default methods, the new types `QuiescentPoint`,
`Lane`, `QueryControl`, `SelectLabels` (protocol). Descriptor keys `quiescentBudgetMs` and `lane`, optional, written only
when set, read leniently. `data.sources` entries gain `lane` and `enforcesTimeout`. `agent.info.threads` gains
`quiescent` and `lanes`. `DataModule.VERSION` `"0.1.0"` -> `"0.2.0"` (its literal never moved although the module
changed in 1.x and Phase 6; D534; landed in step R with the other bumps, §2.4, though nothing committed
holds it). `plan.json` unchanged by this section; no bindings, fingerprint or recording moves except the one inference
fixture of §12.5.

### 4.8 Defects found and fixed by this section

1. A positional provider's zero-row result has no columns (`Table.java:49-52`, pinned by
   `ProviderQueryFormTest.noRowsAndNoNamesGiveNoColumns`): a `db.*` absence check (`rows: 0`) ends `ERROR /
   ENVIRONMENT` ("returned 0 columns") against P2 §7.4's closing sentence, and the D100 check can never pass. Fixed by
   §4.4's width rule.
2. `columnTypes` never contribute to the width (`ProviderQueryFormTest.typesWithoutNames` passes only because a row is
   present). Fixed with 1.
3. Guide §6.4 says a slow `data.query` provider "on `APP`" delays every `APP` primitive; `data.query` runs on `WORKER`
   (`DataModule.java:161`, P1 §6). Corrected; the real `APP` hazard (a provider that hops to `APP` itself) is stated.
4. The S30 abandoned-call gate is keyed by primitive name (`Dispatcher.java:274-279, 346-362`): one `data.query` whose
   provider still runs after the call's timeout makes `data.query` `BUSY` for **every** source. Fixed by §4.2's
   hand-off and per-source gate.
5. `CoreThreadBroker.post`'s `default:` branch runs any policy it does not name on a worker (`:174-176`); `run` throws
   for one. Both switches name every constant; `post` refuses `APP_QUIESCENT` and `LANE`.
6. P5 §5.3 rule 2, P5 §5.3's SQL Server sentence, P5 §16.2 row 13, design §3.9 and `SqlGuard`'s class comment promise a
   `CALL` no channel runs (§4.5).
7. `DataModule.VERSION`'s stale literal (§4.7).

---

## 5. Privacy and the web side: event payloads, the view-less browser, the Playwright line

Three privacy rules that the earlier contracts left to the module's good conduct become the base's own: every event
payload passes the registered `MaskPolicy`s before it is stored (gap 109); a browser the agent has no component for
is masked while a pack masks anything, and a pack can name or release a browser by what it knows of it (gap 110); the
CDP path attaches with the Playwright line it finds, records what it could not apply and never leaves a driver running
(gap 121). Everything here is additive on the wire (protocol 0.2, §2.1): no new primitive, error code,
topic, capability id or plan key; one new SPI (`BrowserMask`), two additive answer fields (`web.browsers.masksRegistered`,
`agent.info.events`), additive fields in the `web.path` record.

### 5.1 Event payloads pass every `MaskPolicy` at publish (gap 109; D539 to D544)

**Decision (D539).** `RingEventBus.publish` (`agent-core/.../RingEventBus.java:95-149`) applies, on the publishing
thread, after the payload is frozen (`new Event(...)`, `:105`) and **before** it is sized and bounded (`:106-110`), one
pass over the frozen payload, **outside the ring lock** (the lock, `:112`, is taken only for the ring copy and the
delivery, as today; §8.9's rule that nothing heavy runs under it, since every publisher and every `await` sits behind
it): every registered `MaskPolicy` to every scalar, and the core's secret-key rule to every map
key. The masked payload is what is stored, delivered to every subscription (the SSE stream among them), returned by
`since` and `await`, matched by `payloadMatches` (`:257`) and recorded on the call scope (`:137-139`): one object,
masked once. Nothing on the wire of `Event` changes. The bus needs the policies: `RingEventBus(int capacity, long
maxChars, AgentLog log, EventMasks masks)`; the two existing constructors stay for the tests and mean `EventMasks.NONE`.
`AgentRuntime` constructs `CoreSpiRegistry` and `Redactor` before the bus (today the bus comes first,
`AgentRuntime.java:238-241`: a reordering of three lines) and hands the bus `new EventMasks(spi, redactor, log)`.

**The pass** (package-private `EventMasks` in `agent-core`, Java 8; `apply(String topic, Map<String, Object> frozen)`):

- `policies = spi.all(MaskPolicy.class)` once per event, registration order (`CoreSpiRegistry.all` is lock-free); a
  policy registered by a module applies from its `install` on and stops with its uninstall (SPI rollback, A18);
  events published before a policy is registered are not masked by it: the core's start-up events and, on an attach
  to a running application, what the base modules publish between their own install and the domain module's
  (`window.opened` titles, `web.load` URLs, `web.console` text; modules install in dependency order on the start
  thread, `ModuleLoader.java:338`, and the view-less rule of §5.2 is not in force either while `masksRegistered()` is
  still false). A short window, on attach only (under `premain` the installs finish before `main`); the key rule
  (D540) covers it from the bus's construction, the pack's policies do not, and the events stay readable for as long
  as the ring holds them (`evt.query`, the SSE stream, the MCP `events/recent` resource). Recorded as gap 139 (no shape
  while the rule stands: modules install before `target.ready` and application events flow after their listeners
  attach; re-masking the ring at a registration is the shape if the owner wants the window closed, Q64).
- `sourceId = "event:" + topic`. The prefix `event:` is reserved for this route beside the conventions `hook.<id>`
  and `rule.<name>`; a data source id must not start with `event:` (nothing checks it; a layer that uses one gets
  its row policy applied to the topic of that name, its own doing). A topic cannot carry the prefix: topics are the
  module's dotted names (`TopicFilter` accepts any non-empty string, so the colon cannot collide with a legal topic
  a scenario waits on).
- `column` = the map keys from the payload root to the scalar joined with `.`; list elements add no segment
  (`{detail: {lines: [{name: x}]}}` gives `detail.lines.name`; `{buttons: ["OK"]}` gives `buttons`). A top-level
  field's column is its key, as `hook.<id>`'s columns are flat names (P6 §4.3).
- The scalars a policy sees are the frozen JSON-normal ones: `String`, `Long`, `Double`, `Boolean` (`Coerce.freeze`);
  `null` is not passed (as `PasswordMask` leaves null alone); never an application object (the freeze already ran its
  `toString()`). A return that is `null`, a `String`, a `Number` or a `Boolean` replaces the value (the type may
  change, as on the hook route); any other return becomes the string `<redacted>`.
- **Failure path.** A policy that throws (any `Throwable` but a `VirtualMachineError` other than `StackOverflowError`,
  which still drops the event as today, `:141-147`) turns that scalar into `<redacted>`, is counted
  (`maskFailures`) and logged at WARN once per policy class per agent start; publishing never fails because of a
  policy, and the event's seq is returned as today.
- **The key rule (D540).** A map entry whose key the core calls secret (`CoreContext.isSecretKey`, `:153`: the
  protocol's `SecretKeys` rule, the `redact` option, every `RedactionKeys`) becomes `<redacted>` whatever its value,
  a nested map or list under it included (one string in its place). It runs whether or not a policy is registered:
  it is the hook encoder's rule (P6 §4.3) extended to the one route that lacked it, by key only. **Behaviour change**
  for a target that publishes under a secret-looking key (`password`, `pin`, `token`, …): the value no longer reaches
  any reader. A34's by-value rule stays out of events (D540; `SecretParamsTest:169-180, 264-268` stay green: their
  keys are neutral).
- **Bound (D541).** The pass visits at most **10,000 scalars** per event; every scalar beyond is `<redacted>`
  without asking a policy, and the payload gains `truncated: true` at its root (the restrictive side; counted as
  `overBudget`). Depth is the freeze's 184. Then the character bound runs as today (`TextBounds.size`, `bounded()`),
  so a stored event always honours `eventRingChars` and a policy always sees whole values (a policy that lengthens a
  value, `x` to `<personal>`, is sized after it did).
- **No policy, no secret key: no copy.** The walk reads the frozen map and allocates only along the path of a change;
  with nothing to change the stored payload is the frozen one (`equals` the published one; `EventBusTest` pins it).

**Which events.** Every event of every publisher, the core's and the base modules' included (`web.tap`'s `detail`,
`web.console`'s `text`, `window.opened`'s title are application content too). A hook `CAPTURE` payload and a
`rule.fired` payload, already masked as `hook.<id>` / `rule.<name>` at the encoder, pass a second time under
`event:hook.<id>` / `event:rule.fired`: harmless for an idempotent policy; a policy that is not (one that appends)
checks `sourceId`. The envelope (`topic`, `source`, `seq`, `ts`, `correlationId`, `traceparent`) is never masked.

**Thread rule (D542; A77).** `MaskPolicy`'s Javadoc and P1 §4 gain the third caller: *also called on the thread
that publishes an event, which may be the EDT, an application thread (the desk's engine thread), an adapter's thread
or an agent worker, once per scalar of every event; it must not block, must not wait on the EDT, must take no
application lock and must be fast.* `EventBus.publish`'s Javadoc says that the payload is masked before it is stored.

**Consequences, stated.** `sync.await`'s `match`, a captured slot (P2 §3.4), the executor's prompt handling and gap
21's `events` fact root see the masked value: a pack must not mask a field a wait or the executor matches on (a
prompt's `id`, gap 107's `inputId`, `web.tap`'s `name`, `web.ready`'s `signal`). **Behaviour change**: a policy
written for rows that ignores `sourceId` now also masks event fields whose key equals a column it masks (the P6 hook
route did the same); the guide tells a layer to check `sourceId.startsWith("event:")`. Replay is unaffected (zero
model calls; the masked value is what the run recorded). The CDP path's own events (`skillroy-cdp/.../PathEvents.java`:
`web.tap`, `web.ready`, `web.load` the path saw itself) never reach the agent's ring, so this pass does not cover them:
a residual recorded as gap 138 (§5.2 withholds a masked page's data from them; the pack's row masks do not run on them).

**Diagnostics (D543).** `agent.info` gains `events: {maskPolicies: <n>, maskFailures: <m>, overBudget: <k>, slowPasses:
<s>, maxPassMs: <t>}` (the registered policies now; the counters since start; `slowPasses` counts passes over 50 ms on
the publishing thread and `maxPassMs` the longest, measured per event with one `nanoTime` pair, so that an operator
sees a policy that slows the host: the scalar bound limits the count of policy calls, not their time, and the pass
runs on the EDT and the application's own thread), one method in `CorePrimitives.agentInfo` (`:234`).

**The guide's rule and the kit (D544).** Guide §6.11's table gains the row *event payloads | `MaskPolicy` with
`sourceId` `event:<topic>` and the dotted column; the key rule always | mask before you publish; the ring pass is the
backstop, and the pass is what `evt.query`, waits and run outputs see*; §6.13 says the same in one sentence; §7 rule 9
names events. The planted-value scan the sample keeps as test code (`LST/SecretScan.java`, package-private) becomes a
public helper for layers in `skillroy-junit` (Java 25, the layer-facing glue; the sample's scenarios module already
depends on it):

```java
package ai.skillroy.testjava.junit;
/** Where a run's outputs hold a planted value: the three-layer reader of the sample's secret scans (JSON parsed,
 *  JSON-in-JSON and labelled blocks parsed, the rest split on separators; a value is found as a whole string or a
 *  token). Bytes (PNGs) are never scanned, only named. Stateless. */
public final class OutputScan {
    public enum Scope { ALL, EVENTS }   // EVENTS: run.jsonl records of kind call, prompt, capture, step; failure/events.json; *.xml
    public record Hit(Path file, long line, String token) {}
    public static List<Hit> leaks(Path runDir, Set<String> planted, Scope scope) throws IOException;  // every regular file under runDir, sorted
    public static List<String> leaks(Path file, Set<String> planted) throws IOException;   // one regular file: its name and, unless a PNG, its content (the callers' form)
    public static List<String> leaksIn(String text, Set<String> planted);                             // one text, or a file name
}
```

`SecretScan` is deleted and its **ten** callers (`InferenceChecks:757`, `SessionExportTest:178`,
`TelemetryStreamTest:83`, `ScenarioCReplayTest:70`, `BindingsUpToDateTest:248-336`, `AuthorEnvelopeTest:270`,
`ScenarioDRecordIT:177`, `InferenceLiveIT:506`, `VisionLiveIT:583`, `TelemetryEndToEndIT:165`; all in `src/test/java`,
so all compile in every build) change `SecretScan.` to `OutputScan.` and nothing else: every one of them uses the
per-file forms `leaks(Path file, Set)` and `leaks(String name, Set)` (`SecretScan.java:80, 109`) inside its own
directory walk, asserting `List.of()` against a `List<String>`, which is why the per-file form stays beside the
directory form (D544 as revised); `SecretScanTest` moves with it as `OutputScanTest` on the same two forms. The `EVENTS` scope
exists because a personal value in an event may legitimately be on screen too (the desk's status line shows the signed-on
staff member's name, `LendingFrame.java:184`), so a whole-run scan would find the snapshot, not the leak.

**As built (Phase 8, EW, step 1, 2026-10-19).** `EventMasks` (package-private, `agent-core`), the bus's fourth
constructor argument and `agent.info.events` as specified, with these completions. (a) The scalar bound counts the
scalars handed to a policy, so it applies only while a policy is registered: without one no scalar is visited and
nothing is cut (the key rule alone walks the keys, as the freeze did), which keeps "nothing changes for a target with
no `MaskPolicy`" true for a payload of more than 10,000 scalars (D-EW-3). (b) A policy's failure is logged with the
exception's class name only (its message may quote the value, and the agent log is served by `agent.log`); a throw or
a return that cannot be stored ends that scalar's chain as `<redacted>` (the hook encoder's precedent); NaN, the
infinities and a `Number` of an application class count as not storable (the freeze would drop the event, or call
application code on the publishing thread); a `null` return ends the chain and is kept (D-EW-4). (c) The key rule's
secret-looking keys are the protocol's (`password`, `passwd`, `pwd`, `secret`, `token`, `credential`, `licence`,
`apiKey`, `privateKey`, …); `pin` above is secret only through the `redact` option or a `RedactionKeys`; a key test that
throws counts as secret, and a value that is already `<redacted>` under a secret key is left as is (no copy: the hook
and rule payloads pass the second time unchanged). (d) `OutputScan` keeps `leaks(String, Set)` beside `leaksIn` (the
same method), so that the one caller of the name form (`BindingsUpToDateTest:248`) and any package that follows the
sentence above change only the class name (D-EW-1). (e) `Scope.EVENTS` reads records by their `type` field (the field
`run.jsonl` and the journal write) and reads a session's `journal.jsonl` beside `run.jsonl`, since `step` records exist
only there; a line of either file that does not parse is read whole (D-EW-2). (f) The per-file form decodes UTF-8 with
replacement where `SecretScan` threw on a malformed byte (a binary file cannot hide an ASCII value), and a PNG is
recognised by its name or its signature (D-EW-6). (g) The end-to-end fixture is `CoreTestModule` behind the option
`coretest.eventMask=on|throw` (a new discovered fixture module would change the module lists the lifecycle tests pin),
exercised by the new `EventMaskingTest` (D-EW-5).

**As built (Phase 8, EW, the body, 2026-10-19).** The sample's proof as specified (`staff.signedOn {code, name}` from
`EngineEvents.stateChanged` after `state.changed`, `PasswordMask`'s event rule, `LoadModeChecks` slot 172). §13.1 row
18: `skillroy-agent-hooks` and `skillroy-agent-byteman` do not depend on the core and cannot reach its package-private
pass, so `CaptureTest` and `BindingEncoderTest` take the real encoders' payloads under an idempotent policy and re-apply
the policy by this section's column rule (`event:hook.take`, `event:rule.fired`, dotted columns), and the core's
`EventBusTest` publishes payloads of the same shapes, already masked and with a `<redacted>` secret key, through the real
pass and asserts them stored unchanged and uncopied (D-EW-18).

### 5.2 The view-less browser and the `BrowserMask` SPI (gap 110; D545 to D551)

**The rule (D545). Behaviour change.** While at least one `CaptureMask` or one `BrowserMask` is registered
(`SeedWalk.masksRegistered()`, `SwingSeeds.java:128`, counts both), an open browser whose adapter reports no Swing
component (`Entry.component == null` at the last rebuild **and** `WebBrowser.component()` null when asked again, as
`refuseMasked` asks today, `WebPrimitives.java:1057`) is **masked** unless a `BrowserMask` releases it. When nothing is
registered nothing changes (S111's rule: a pack that declared no secret has none; the web host's item 2 keeps listing
`detached` with URL and title). When the browser gains a component, the `CaptureMask` rule on that component governs
from the next rebuild. Today such an entry is never masked: `entryState` answers null without a component
(`WebPrimitives.java:408-413`) and `refuseMasked` returns early (`:1058-1060`), so `web.browsers` lists its URL and
title and the four reads serve its page; a browser found only through an engine, client, app or profile seed has no
component at all (JxBrowser: the view is known from a view seed only, `JxBrowserAdapter.java:457-470`; JCEF: the UI
component only when it lies in a `Window`, `JcefBrowser.java:368-376`) and gets no tag (`BrowserIndex.java:256-268`:
the tag goes to the entry whose vendor object or component is the seed's object), so no `CaptureMask` could ever name
it.

**The SPI (D546; A79).** A new interface in `…agent.api.spi.web`, Java 8, no AWT type, no vendor type:

```java
package ai.skillroy.testjava.agent.api.spi.web;
/** Which embedded browsers show a secret, by what the pack knows of them: the seed's tag and the vendor's browser
 *  object (Phase 8, gap 110). Called on an agent worker at each index rebuild and before each masked-read check,
 *  never on the EDT; must be fast, must not block and must call no method of the vendor object (compare it by
 *  identity: it may belong to a vendor and block or throw). A mask that throws masks (the safe side). */
public interface BrowserMask {
    /** TRUE: mask this browser on every route, whatever its view; FALSE: it shows nothing secret while it has no
     *  view (releases it from the view-less rule; a FALSE never overrides a CaptureMask that names its component);
     *  null: no opinion. Masks combine by union: one TRUE masks.
     *  @param tag the browser's own seed tag, or null (a browser found through an engine, client, app or profile seed,
     *      or by the Swing-tree scan alone)
     *  @param vendorObject WebBrowser.vendorObject(): the vendor's browser object, never null
     *  @param hasView whether the adapter reports a Swing component for it at this rebuild */
    Boolean masks(String tag, Object vendorObject, boolean hasView);
}
```

Registered through `ctx.spi().register(BrowserMask.class, …)` during `install`, looked up with `spi().all` per
evaluation, withdrawn with the module (A18). `WebSpiShapeTest`'s `TYPES` list gains it (`:51-57`); `package-info` names
it beside `BrowserRegistryAdapter` as the second type a domain pack implements.

**Where the flag is computed (D547).** `Entry` gains `volatile boolean masked`, set at every rebuild
(`BrowserIndex.build`, `:197-283`, on the worker) and refreshed by every per-call read:

1. `registered = walk.masksRegistered()`; if false, `masked = false` for every entry and no mask is called.
2. `named = union of every BrowserMask.masks(e.tag, e.vendor(), c != null)`: TRUE if any TRUE; FALSE if any FALSE and
   none TRUE; null otherwise; a mask that throws counts TRUE, logged once per mask class per agent start.
3. `named == TRUE` → `masked = true` (whatever the view).
4. no component (`c == null`) → `masked = (named != FALSE)` (the view-less rule; no EDT read).
5. a component → one bounded EDT read for **all** components of the rebuild together, `SeedWalk.masked(Collection<?>
   components, long budgetMs)` (new default method, beside `showing`, `SeedWalk.java:31-67`; `SwingSeeds` reads
   `ComponentReads.masked` over the parents as `state` does, `ComponentReads.java:45-90`; `FakeWalk` answers from its
   registry as it does for `state`), made only while masks are registered and the rebuild has time; a read that fails
   or has no time leaves every component-bearing entry `masked = true` (the safe side, as `UNREAD` does today,
   `:425`). A `FALSE` never releases a component a `CaptureMask` names.

`web.browsers` keeps its per-call `entryState` read for component-bearing entries (fresh, as S111 built it) and uses
the entry's flag for view-less ones (no EDT read; `FakeWalk.stateReads` unchanged in the view-less tests); `masked(e,
state)` becomes `state != null ? state.masked : e.masked`. `refuseMasked` (`:1053-1074`) evaluates step 2-4 inline for
an entry without a component (no EDT read) and keeps its EDT read for one with a component. A browser that is masked
on one route is masked on every route: the four reads, `web.browsers`, the events, the selection error data, the
answers of the actions that stay allowed on it (iv), the session's snapshot text, the recorder and the tools (they
follow the agent's flag, `UiSnapshotText.java:195-250`, `Recorder.java:173, 659, 697`, `VisionSessionTools.java:460`).

**Effects, S111's unchanged and three extended.**

- `web.browsers` lists a masked view-less browser as S111 lists a masked one: `masked: true`, `url: ""`, no `title`,
  its frames' `name` and `url` `""` (keys kept), `tag`, `cdpTargetId`, `rendering`, `capabilities` as today. The
  answer gains the additive top-level field **`masksRegistered: true|false`** (`{browsers: [...], masksRegistered}`;
  D548), what `walk.masksRegistered()` answered for this call, so that a controller knows whether an unmapped page
  may be masked.
- `web.bitmap`, `web.dom.text`, `web.ariaSnapshot`, `web.hitTest` of a masked browser answer `INVALID_PARAMS` with
  `data {param: "redact", reason: "masked", browser: <ref>}` before the page is evaluated, unless `redact: false` (a
  person's argument; `CallPolicy` refuses it for a model or a client, P7 §9.1). A view-less JCEF browser's `web.bitmap`
  is refused as masked before the adapter would answer `CAPABILITY_UNAVAILABLE` (P4 §3 as built): the mask wins.
- **(i) A masked browser's events withhold page data (D549). Behaviour change.** `EventBridge.onEvent` and
  `ready` (`EventBridge.java:171-262`) and `Taps.publish` (`Taps.java:476-504`) read `e.masked` (no EDT read on the
  adapter's thread): `web.load {browser, url: "", frameKey?, kind, masked: true}` (no `error` text); `web.ready
  {browser, url: "", signal, name?, masked: true}`; `web.console {browser, level, text: "", masked: true}` (no
  `source`, no `line`, no `truncated`); `web.tap {browser, name, masked: true}` (no `detail`, no `truncated`: `name`
  is the pattern a step waits on, `detail` is page data). `web.closed` and `web.browsers.changed` carry refs only and
  are unchanged. The `masked: true` key is additive; a step that waits on `web.ready {signal: LOAD}` or `web.tap
  {name}` of a masked browser still completes.
- **(ii) Selection error data withholds a masked browser's URL and title (D550). Behaviour change.**
  `Selection.summary` (`Selection.java:323-353`) answers `url: ""`, no `title`, `masked: true` for a masked entry
  (`NOT_FOUND`'s `browsers` and `AMBIGUOUS`'s `candidates`; an S111 residual closed), and `by: {url: <regex>}` and `by:
  {title: <regex>}` **never match a masked entry** (`Selection.match`, `:160-184`: an oracle on withheld text would
  reveal it); `by: {tag}`, `by: {nativeId}` and `browser: <ref>` still select it, so a scenario can act on a masked
  browser (`web.pointer`, `web.dom.click`, `web.dom.fill`, `web.evaluate`, `web.tap` stay allowed on it for any caller
  `CallPolicy` admits: a pack's verb, a person, or a model or client a person opted in to the raw `web_*` tools,
  `SessionPolicy.java:33-34`, `ToolProjection.java:158-161`; S111's standing residual, unchanged), and the answers of
  `web.pointer`, `web.dom.click` and `web.dom.fill` carry no page data (iv).
- **(iv) Acting on a masked browser returns no page data (D637). Behaviour change.** Today `web.pointer` hit-tests
  the point before dispatching and returns the summary `{role, name, tag, id?}` (`Pointing.java:234-246`,
  `WebInput.java:440-445`): `name` is the accessible name, page text; `web.dom.click` returns `clicked: {role, name,
  tag, id?}` and `web.dom.fill` `filled: {tag, type, id?}` (`web-lib.js:1268-1279, 1449, 1471-1475`); and a selector
  matching several elements answers `AMBIGUOUS {candidates, count}` with up to 20 `{role, name, tag, id?}` maps
  (`web-lib.js:1158-1173, 1203-1229`, turned into an `RpcException` with that data at `WebPrimitives.java:776-786`),
  so `web.dom.click {browser: <masked ref>, selector: "*"}` clicks nothing and returns twenty accessible names. None
  of these paths checks the mask (the four `refuseMasked` users are the reads, `WebPrimitives.java:644, 661, 982`,
  `Pointing.java:110`), and a session step's last result and failure detail reach a model masked by secret value only
  (`ExecutorSession.java:818`), which page text is not. From Phase 8: `web.pointer` of a masked browser carries no
  `hit` (on the agent path `Pointing.pointer` skips `hitIn` when `state != null ? state.masked : e.masked` is true,
  the component read it already makes at `:219`; the `NOT_FOUND` "outside" check runs on the point alone, as it does
  after the hit today); `web.dom.click` and `web.dom.fill` keep acting, and their answers, the result's
  `clicked`/`filled` and the `AMBIGUOUS` data's `candidates`, carry `role` and `tag` only (no `name`, no `id`; `count`
  stays), the agent stripping the keys after `libraryIn` returns or throws under the same masked test (one bounded EDT
  read made only while masks are registered, as `refuseMasked` already makes); on the CDP path, while
  `masksRegistered` is true, `web.pointer`, `web.dom.click` and `web.dom.fill` of a page whose agent entry is masked
  or absent are handed to the agent (`UNSUPPORTED`, D404's method, as (iii) does for the four reads; the path serves
  all three itself today, `CdpWebPath.java:177-183`, `:836-890`). `redact: false` keeps today's answers, and
  `CallPolicy.MASKED_WEB_READS` gains the three primitives so that `redact: false` stays a person's argument there as
  well (`CallPolicy.java:69`).
- **(iii) The CDP path hands what it cannot judge to the agent (D551).** `AgentBrowser.parse` (`AgentBrowser.java:
  50-63`) returns `AgentBrowser.Listing(List<AgentBrowser> browsers, boolean masksRegistered)`; `refreshBrowsers`
  (`CdpWebPath.java:1562`) keeps the flag on the `Session`; an answer without the field (an older agent) counts as
  **true** (fail closed; mixed versions are unsupported anyway, guide §4.4). While it is true: the four reads of a page
  with **no agent ref** (`refOf` null, `:1586`; today served without any check) are handed to the agent
  (`UNSUPPORTED`, D404's method), as are `by: {url|title}` selections (`cdp/Selection.java:31-45` matches the pages'
  own values; the agent then answers by the rule of (ii)); and the path's own events (`PathEvents`) of a page whose
  agent entry is `masked`, or that has no agent entry, carry `url: ""`, no `detail` and `masked: true` as (i) does.
  `redact: false` bypasses the hand-off as today (`:1278-1281`). Pages the agent has mapped are handled as S111 built
  it: the agent reports the view-less `detached` browser masked, the path hands its reads over (the web host's
  `detached` has `CDP_TARGET_ID`, `WebHostChecks.java:226-227`).

**What stays open, stated.** `web.evaluate` and `web.waitFlag` on a masked browser are not refused (script primitives:
read-only mode refuses them and `CallPolicy` never offers them to a model or a client); a view gained between two
rebuilds is seen at the next one (the per-call reads re-ask `component()`, so a read is never served stale on the
unmasked side); JxBrowser's view-less browsers and its off-screen `bitmap` are specified from the adapter's code and
unverified against the real library (gap 41, the wall).

**As built (Phase 8, EW, 2026-10-19).** As specified, with these completions. (a) Steps 1 to 4 live in one
package-private class of the web module, `BrowserMasks`, which the index, the per-call reads and the selection share:
the union stops at the first `TRUE`; a mask that throws, a registry that throws and a vendor object that cannot be read
count `TRUE`; a failing mask is logged once per class by the exception's class name only (D-EW-7). (b) A `BrowserMask`
`TRUE` masks a browser with a component too, also where `web.browsers` and `web.pointer` read the component's state in
the call: the per-call test is "nothing registered: not masked; a `TRUE`: masked; no component: masked unless released;
else the call's `CaptureMask` read (else the entry's flag)", so `masked(e, state)` above is completed by the `TRUE` of
step 3, and every per-call judgement refreshes `Entry.masked` (D-EW-8). (c) The selection judges a view-less entry and a
`TRUE` afresh without an EDT read and reads the entry's flag for a component-bearing one; a masked entry's title is never
read for `by.title` (D-EW-9). (d) `SeedWalk.masked`'s default (a walk without a registry) and `SwingSeeds` without an
event-dispatch thread answer every component masked; a registry that cannot answer counts as one with masks
(`masksRegistered` true) (D-EW-10). (e) `web.dom.fill`'s `filled` keeps `tag` only (it has no `role`; `type` and `id`
dropped), read literally from "role and tag only". (f) `web.pointer`, `web.dom.click` and `web.dom.fill` declare the
parameter `redact` in their descriptors (a person's argument, now in `CallPolicy.MASKED_WEB_READS`), so that `/schema`
names it as the four reads' descriptors do (D-EW-11). (g) The CDP path hands a `by: {url|title}` selection to the agent
while masks are registered whatever `redact` says (the agent's rule (ii) ignores `redact`, so the two paths agree), after
one fresh `web.browsers` read; the path's own events read its cached agent list (read at open and by every call that
reads it), so a mask registered after the last read leaves the path's events of that browser unmasked until the next
read (the restrictive side holds for every mask present at open) (D-EW-12). (h) The refusal's message names no SPI:
"browser <ref> is masked (a pack's mask names it, or it has no view while a mask is registered), so its page is not
read on this route (a person may pass redact: false)".

### 5.3 The Playwright line: version-gated options, the driver leak, the floor (gap 121; D552 to D555)

**Today.** `CdpAttach.open` (`CdpAttach.java:357-363`) always calls `setNoDefaults` (Playwright 1.60) and `setIsLocal`
(1.58), and `setArtifactsDir` (1.61) when the scenario traces; on an older line the first `connect` throws
`NoSuchMethodError` from `open` (lazy linking), `PwThread.call` rethrows an `Error` as is (`PwThread.java:118-125`),
`CdpAttach.connect` catches `TimeoutException`, `InterruptedException`, `Forbidden` and `Exception` only (`:147-164`),
so the Playwright instance created at `:350` (its driver process) and the attach's thread **leak**; `WebRun.select`
catches the `LinkageError` (`WebRun.java:236-240`) and falls back under `auto` or fails `ENVIRONMENT` under `cdp`. The
design promised the gate ("before 1.60 the call carries only `setTimeout`, and Playwright's default-context overrides
are accepted and logged", `base-framework-design.md:606`) and no document states a floor.

**Detection (D552).** A package-private `ConnectSupport` in `skillroy-cdp` decides **once per JVM**, by field
presence on `BrowserType.ConnectOverCDPOptions` (its option fields are public in every line that has the setter;
`javap` of 1.63.0: `artifactsDir, headers, isLocal, noDefaults, slowMo, timeout`), which setters may be called; a
setter is called only when its field exists, so on a line without it the instruction is never executed and never
linked (JVMS §5.4). No reflective invocation.

```java
final class ConnectSupport {
    static ConnectSupport detected();                      // memoised: detect(BrowserType.ConnectOverCDPOptions.class)
    static ConnectSupport detect(Class<?> optionsClass);   // getField("noDefaults" | "isLocal" | "artifactsDir")
    static ConnectSupport of(Set<String> present, String version);   // the test seam
    boolean noDefaults(); boolean isLocal(); boolean artifactsDir();
    String version();   // Playwright.class.getPackage().getImplementationVersion(), or null (a repackaged jar)
}
```

`CdpAttach.connect(endpoint, options, probe)` uses `ConnectSupport.detected()`; the package-private form the tests
use gains the parameter (`connect(endpoint, options, probe, factory, support)`).

**Per missing option (D553; the restrictive default of §1).**

| Missing | Line | What the attach does | Recorded |
|---|---|---|---|
| `isLocal` | < 1.58 | connects without it (Playwright treats the browser as remote) | `connectOptions.isLocal: "unsupported"` |
| `noDefaults` | < 1.60 | **the CDP path is not used**: `CdpWebPath.open` returns the reason *"the Playwright on the class path (<version or unknown>) cannot attach without overriding the application's browser context (no noDefaults; Playwright 1.60 or later has it); set -Dskillroy.cdp.allowDefaultOverrides=true to accept the overrides"* before any driver starts; `WebRun` then falls back to the agent path under `auto` and refuses `PRECONDITION_FAILED` under a pinned `cdp` (`WebRun.java:257-269`, the existing reason route). With the opt-in (below) it connects without `noDefaults`: Playwright's download, focus and media overrides apply to the application's context, accepted and logged at WARN; an engine that refuses them (CEF, research F8) fails the attach `PROTOCOL` as any attach failure | `connectOptions.noDefaults: "unsupported"`, `defaultOverridesAccepted: true` |
| `artifactsDir` | < 1.61 | with `web.trace: true` the path opens **untraced**: it never starts `Tracing` (so no live trace goes to Playwright's own temporary directory, D138 kept) and `saveTrace` answers false, which the executor already records as `web.trace {ok: false, reason: "the cdp path wrote no trace"}` (`PlanExecutor.java:1371-1374`); without a trace nothing changes | `connectOptions.artifactsDir: "unsupported"` |

The opt-in is the operator's, a system property of the controller JVM, `skillroy.cdp.allowDefaultOverrides=true`,
read by `CdpWebPath.open` into `CdpOptions.allowDefaultOverrides` (a new record component, default `false`, with
`withAllowDefaultOverrides(boolean)`; the six-component constructor kept, `CdpOptions.java:49-50`); never a scenario's
or a model's choice (a person's flag, as `skillroy.robot` is). Why not the pack: D553 (§17).

**The `web.path` record (A80, additive).** `openInfo` (`CdpWebPath.java:646-654`) gains `playwright: "<version>" |
null` and `connectOptions: {noDefaults: true | "unsupported", isLocal: true | "unsupported", artifactsDir: true | false |
"unsupported", defaultOverridesAccepted?: true}` (`artifactsDir: false` when no trace was asked for).
`CdpWebPathOpenTest:183`'s key list becomes `endpoint, browserVersion, connectMs, targets, unresponsive, playwright,
connectOptions`.

**The leak, fixed regardless (D554).** `CdpAttach.connect` also catches `LinkageError` (and any other `Error` that is
not a `VirtualMachineError`): `closeQuietly(pending.abandon(), CLOSE_GRACE_MS)`, `pw.shutdownNow()`, then
`CdpException(PROTOCOL, "the Playwright on the class path (<version>) lacks <error message>; nothing was attached")`,
so an incompatible Playwright never leaves a driver process or a `skillroy-cdp-playwright` thread behind; `WebRun`'s
`LinkageError` catch stays as the backstop for errors thrown elsewhere.

**The floor (D555; guide §10.3, README "Embedded web views", `docs/licence-audit.md` notes).** *Built and tested
with Playwright 1.63.0. The CDP path runs with 1.61 and later in full; with 1.60 without tracing; with 1.58 and 1.59
only under the operator's opt-in; a line below 1.58 is refused by the same rule and is otherwise untried. The module's
other Playwright API (`getByRole`, `Locator.waitFor`, `Tracing`, `CDPSession`, `CreateOptions.setEnv`) is older than
1.58 by its release notes, but no line other than 1.63.0 was run (nothing offline resolves one); a layer that pins
another line spikes it (design §1.3) and audits it (the licence audit covers 1.63.0 only). The attach records what it
could not apply.* `CdpAttach`'s Javadoc and the threat model's "Playwright 1.63 has no option to refuse" a redirect
(`threat-model.md:232-236`) are unchanged: a redirect option is not on any line.

**As built (Phase 8, EW, 2026-10-19).** As specified, with these completions. (a) The detection runs on the thread that
first needs it: `CdpWebPath.open`, which must know `noDefaults` before any driver starts, reads `ConnectSupport` through
a supplier (the tests' seam) on its caller's thread; `CdpAttach.connect` called directly reads it on its caller's thread
too; memoised once per JVM either way (D-EW-13). (b) `CdpAttach.connect` also refuses, `PROTOCOL` before anything starts,
a line without `noDefaults` whose options do not allow the overrides (defence in depth for a direct caller; the path's
own refusal is the reason string), and passes no artifacts directory to a line without `artifactsDir` (logged at
WARNING) (D-EW-14). (c) `connectOptions.noDefaults` and `isLocal` record the option's value where the line has it (true
by default; `true | "unsupported"` above for the defaults) and `defaultOverridesAccepted: true` appears exactly when the
line has no `noDefaults` (the attach then happened under the opt-in) (D-EW-15). (d) The `LinkageError` message is "the
Playwright on the class path (<version or unknown>) lacks <the error's class and first line>; nothing was attached",
and a `VirtualMachineError` still propagates as is.

### 5.4 Threads and bounds; the behaviour changes of this section

| Where | Thread | Bound |
|---|---|---|
| the ring pass (§5.1) | the publishing thread (EDT, application, adapter or worker), **outside the ring lock** | one lock-free SPI lookup; at most 10,000 scalars visited, each through every policy; the freeze's depth 184; then the character bound as today; a throwing policy logged once per class; one `nanoTime` pair per event feeding `slowPasses`/`maxPassMs` |
| a masked browser's action answers (§5.2 (iv)) | the agent worker, after `libraryIn` returned or threw | one bounded EDT read of the component's state only while masks are registered (the read `web.pointer` already makes); `name` and `id` dropped, never emptied |
| `BrowserMask` (§5.2) | an agent worker, at rebuild and before a masked-read check; never the EDT | one call per registered mask per browser per rebuild; no method of the vendor object |
| `SeedWalk.masked` (§5.2) | one bounded EDT read per rebuild, all components together, within the rebuild's budget (`BUDGET_MS`) | made only while masks are registered; a failed or skipped read masks |
| a masked browser's events (§5.2) | the adapter's thread, the tap poller | a volatile read; no EDT read |
| `ConnectSupport` (§5.3) | the first attach's Playwright thread | three `getField` calls once per JVM |

**Behaviour changes for existing users**, each marked above: (1) a registered `MaskPolicy` now applies to every event
payload (`event:<topic>`), so a row policy that ignores `sourceId` masks event fields of the same column name; (2) a
secret-looking map key in any event payload is `<redacted>`; (3) a view-less browser is masked while any mask is
registered, so a layer that masks anything and reads a pre-loaded page registers a `BrowserMask` answering FALSE for
it; (4) a masked browser's `web.load`, `web.ready`, `web.console` and `web.tap` withhold page data; (5) a masked browser
never matches `by: {url|title}` and the selection error data withholds its URL and title; (6) the CDP path hands
unmapped-page reads and `by: {url|title}` selections to the agent while masks are registered; (7) `web.pointer` of a
masked browser carries no `hit`, and `web.dom.click`/`web.dom.fill` answer with `role` and `tag` only, the CDP path
handing the three over for a masked or unmapped page while masks are registered. Nothing changes for a target with no
`MaskPolicy`, no `CaptureMask`, no `BrowserMask` and no secret-looking event key, on Playwright 1.61+.

---

## 6. Hooks: code sources and the class deny-list

### 6.1 Scope

Two protections for the hook engine, both restrictive, both honoured where bytecode is changed and nowhere else:

- **Gap 113, ownership by code source.** A hook target may say which jar its class must come from
  (`target.codeSource`, a regex on the code-source *name*), a catalogue's `packages` entries may carry the same
  qualifier for every entry under them, and the probe, `hook.install`'s `touched`, `hook.health` and `skillroy hooks
  status` report each class's code source. Today `packages` is plain names (`HookCatalogues.java:312-331`), the probe
  selects copies by name and `target.loader` only (`Probes.java:138-146`), and no answer says which jar a class came
  from (`HookProbe.java:44-53`, `HE:773-780`, `HE:1347-1386`).
- **Gap 114, a class deny-list.** A catalogue's `deny:` patterns, the agent option `hooks.deny` and a domain module's
  `ClassDeny` refuse a class *by name* before anything else is decided, in the hooks module (`hook.probe`,
  `hook.install`, the `HookEngine` SPI) and in the Byteman module (`rule.check`, `rule.submit`), before Byteman loads.
  Today the only name deny is the fixed `PROTECTED` list (`Probes.java:47-48`, D124) and the only bounds are the size
  options, per JVM (`HookOptions.java:80-81`, `Settings.java:65-73`).

Unchanged: the `enabled` default ("under `packages`", `HookCatalogues.java:424-425`), D178 (all copies or none), D183
(nothing installed, nothing recorded), the catalogue hash algorithm for every catalogue that uses no new key, the
`hooks.installed` event, `Plan.VERSION`, bindings, goldens, the never-offered set of `hook.*`/`rule.*` primitives
(P6 §4.2). Protocol stays `0.2` (§2.1): every wire change below is an optional key or an additive answer field,
with one exception stated as such, the strict `HookTarget` (§6.3).

The deny is **policy** (a hot path, licensing, support, timing-sensitive code), not checksum protection: retransformation
changes neither the jar nor the bytes `getResourceAsStream` returns, so the TODO row's checksum rationale is not
promised (D572). A deny refuses *instrumenting* a class, not a Byteman `DO` clause calling into it (D226's tripwire
stance stands).

### 6.2 `CodeSources` (`skillroy-agent-api`, `…agent.api.instrument`, Java 8; D556)

```java
public final class CodeSources {
    public static final String NONE = "(none)";             // no ProtectionDomain, CodeSource or location
    public static final String UNREADABLE = "(unreadable)"; // getProtectionDomain threw (a SecurityException)
    public static final int MAX_LENGTH = 200;
    /** The code-source name of a loaded class; never throws, never null. Agent threads only. */
    public static String of(Class<?> type);
    /** The naming rule on a location's parts, for tests and the guide's examples. */
    public static String name(String protocol, String host, String path);
}
```

**What `of` calls, and nothing else:** `Class.getProtectionDomain()` (a `SecurityException` is caught: `UNREADABLE`;
on Java 8 a host's `SecurityManager.checkPermission` then ran on the agent worker, as it already does for
`getDeclaredMethods`), `ProtectionDomain.getCodeSource()`, `CodeSource.getLocation()`, and on the `URL` **the field
getters `getProtocol()`, `getHost()`, `getPath()` only**. Never `toString`, `toExternalForm`, `toURI`, `openConnection`,
`getContent`, `equals`, `hashCode`: the first three run the URL's `URLStreamHandler`, which for a custom protocol (an
application server's virtual file system, an OSGi bundle) is application code; `equals`/`hashCode` may resolve the host
over the network. A `URL` is therefore never a map key or a set member anywhere in this section. A null at any step is
`NONE` (bootstrap classes, `defineClass(name, b, off, len)`'s default domain, generated proxies).

**The name** (`name(protocol, host, path)`): (1) strip every leading scheme prefix `[A-Za-z][A-Za-z0-9+.-]*:` from the
path (a `jar:` URL's path begins with the inner URL: `file:`, `nested:`); (2) split at every `!/` and `/!`, in order;
(3) part 0, the outer location: trailing `/` stripped, the text after the last `/`, percent-decoded (`%XX` to UTF-8,
`+` kept, an undecodable sequence kept as written); parts 1 and later, entries inside an archive: leading and trailing
`/` stripped, percent-decoded, kept whole (a path inside a jar is not a file-system path); after decoding, control
characters (U+0000-U+001F, U+007F) become spaces, as §3.2 rule 8 does for the veto's text (a jar named with `%0A` or
`%1B` would otherwise carry a newline or an escape into `hook.probe.codeSources`, `hook.health.classes[]`, the `hooks`
record's `touched` lines and the CLI's raw-text table of §6.10); (4) drop empty parts; none left:
`<protocol>:<host>`; (5) join with `!/`; longer than 200 characters: the first 200. Directories are never
reported (D154's rule: a path may hold a user name).

| Location | Name |
|---|---|
| `file:/opt/desk/lib/lending-app-1.2.jar` | `lending-app-1.2.jar` |
| `file:/C:/Users/x/desk/lib/desk.jar` | `desk.jar` |
| `file:/build/my%20desk/classes/` | `classes` |
| `jar:file:/opt/app.jar!/BOOT-INF/lib/lib.jar!/` | `app.jar!/BOOT-INF/lib/lib.jar` |
| `jar:nested:/opt/app.jar/!BOOT-INF/lib/lib.jar!/` | `app.jar!/BOOT-INF/lib/lib.jar` |
| `jrt:/java.base` | `java.base` |
| `bundleresource://12.fwk/` | `bundleresource:12.fwk` |
| no domain, no location, `String.class` | `(none)` |

**Cost and thread:** read once per candidate copy per `hook.probe`/`hook.install` on the agent worker (both are
`WORKER`), cached in the ledger entry at its creation (`Ledger.Entry.codeSource`, final), never on an application thread,
never at transform time.

**As built (Phase 8, EH, 2026-10-19).** `CodeSources` as specified; a malformed escape (`%` not followed by two
hexadecimal digits) and a run of escapes that is not valid UTF-8 are kept as written (strict decoding); `of(null)` is
`NONE`; a name with no part and no host is `<protocol>:`. `CodeSourcesTest` proves the field-getter rule with a stream
handler armed only after the class was defined: the JDK's `defineClass` itself calls `CodeSource.getLocation()
.toString()` for the `-verbose:class` source text, so a handler that throws from the start cannot be the location of
a defined class.

### 6.3 The wire (`skillroy-protocol`, `…protocol.hook`; P6 §4.1 amended)

- **`HookTarget`** gains `codeSource?: string` (D557): a Java regular expression matched against the **whole**
  code-source name (`Pattern.compile(regex).matcher(name).matches()`, anchored: `lending-app.*` does not match
  `lending-app-scheduler.jar` unless written so). A five-argument constructor `HookTarget(className, method,
  descriptors, loader, codeSource)` beside the kept four-argument one; `codeSource()` null when absent; `equals`,
  `hashCode` include it; `toJson` writes it only when set, so **every existing spec keeps its hash**; `fromJson` accepts
  the key (and still refuses any other: the strictness of `HookTarget.java:109` is kept, see "Versioning").
- **`HookSpec.violation()`** gains the field `target.codeSource`: longer than 200 characters ("codeSource longer than
  200 characters"), or not compilable (`PatternSyntaxException`: "not a regular expression: <description>"). So the
  catalogue reader reports it at the key's line and the agent answers `INVALID_PARAMS` with `data.param`
  `specs[n].target.codeSource` (`HE:243-265`, unchanged mechanism).
- **`HookProbe`** gains `codeSources?: [string]` (at most 16 names; `loaders` keeps the count), aligned with `loaderIds`:
  an eleven-argument constructor beside the kept ten-argument one; written only when set; read leniently (additive).
- **`ClassPattern`** (new, Java 8; D561), the one deny grammar both sides apply:

```java
public final class ClassPattern {
    public static final int MAX_LENGTH = 200;
    public static final int MAX_PER_SOURCE = 64;
    /** Null, or why {@code pattern} is not a class pattern. */
    public static String violation(String pattern);
    /** @throws IllegalArgumentException with {@link #violation}'s text */
    public static ClassPattern compile(String pattern);
    public boolean matches(String binaryName);
    public String pattern();
    /** The first of {@code patterns} that matches, or null; each is compiled once per call. */
    public static String firstMatch(List<String> patterns, String binaryName);
}
```

  Characters `[A-Za-z0-9_$*.]` only; segments separated by `.`, none empty; every segment but the last a Java
  identifier; the last segment an identifier, `*`, `**`, or an identifier with `*` wildcards; at most 200 characters.
  Matching (name = a binary name, `last` the pattern's last segment, `prefix` the rest): `a.b.C` matches exactly
  `a.b.C` and its nested classes (`a.b.C$X…`); `a.b.*` every class whose package is exactly `a.b` (nested included:
  the remainder after `a.b.` holds no `.`); `a.b.**` every class under `a.b.`; `a.b.Input*` every class of `a.b` whose
  last segment (simple name with any `$nested` suffix) matches the glob, each `*` any run of characters other than `.`;
  a one-segment pattern applies to the default package by the same rules. Linear in the name's length; no regex, no
  backtracking. Violations: "empty", "longer than 200 characters", "character '<c>' is not allowed", "an empty
  segment", "'**' is allowed only as the whole last segment", "'*' is allowed only in the last segment", "segment
  '<s>' is not an identifier".

**Versioning.** `Protocol.VERSION` stays `0.2`. A 0.1.0 agent answers a spec carrying `codeSource` with `INVALID_PARAMS`
for the whole `hook.install` (`HookTarget.fromJson` refuses the key, `HookPrimitives.java:181-189`): **fail closed**,
which the installer maps to ERROR / ENVIRONMENT with an upgrade hint (§6.9; D560). A 0.1.0 agent ignores
`catalogue.deny` silently (`HE:287-295` reads `id`, `hash`, `version`, `application` only): the echo of §6.6 and the
installer's check of §6.9 make that fail closed too (the safety rule of §2.1; D565). Mixed versions stay unsupported
(guide §4.4).

**As built (Phase 8, EH, 2026-10-19).** Public members beyond the list above, so that one rule serves both sides
(D-EH-8): `HookTarget.MAX_CODE_SOURCE_LENGTH` (200), `HookTarget.codeSourceViolation(String)` (the check
`HookSpec.violation()` and the catalogue reader's `packages` map apply), `HookTarget.withCodeSource(String)`;
`HookProbe.MAX_CODE_SOURCES` (16; the constructor keeps the first 16). `ClassPattern.violation(null)` is "empty"; a
glob segment is checked as an identifier with each `*` read as an identifier character (`9*` is "segment '9*' is not
an identifier", `*9` is a glob); a control character in a refused pattern is printed as `\uXXXX`; `equals` and
`hashCode` are the pattern text's.

### 6.4 The probe amended (P6 §3.3 items 2 and 3; D558, D563)

Between item 2 (the fixed deny by name) and item 3 ("loaded?") a new **item 2b, the class deny**: the sources of §6.5
are asked in order (the option's patterns, then every registered `ClassDeny` in registration order), the first refusal
wins, and the spec is `UNAVAILABLE("<reason>")` with the reason texts of §6.5. Before "loaded?" on purpose: a denied class
answers the same whether or not it is loaded, and nothing of it is read. Item 2b applies to `hook.probe`, `hook.install`
step 2 (`HE:379-380`) and the SPI's `probe`/`install` (one `Probes.probe`).

Item 3 amended, **the code-source filter** (gap 113): the candidates are the loaded copies of the name whose loader
passes `target.loader` (as today) **and, when `target.codeSource` is set, whose code-source name matches it**. In order:
(a) copies by name and loader; none: `UNAVAILABLE("class not loaded")` as today; (b) `CodeSources.of` of every copy
(filled into `codeSources`, at most 16, for **every** answer from here on, qualifier or not); (c) only under a qualifier:
any copy `UNREADABLE`: `UNAVAILABLE("the class's code source cannot be read: SecurityException")` (restrictive, all
copies or none as D178); then the copies whose name matches are kept; none kept: `UNAVAILABLE("class from another code
source: <name>[, <name>…]")` naming at most four, then "and <n> more". `NONE` matches only a regex that matches the text
`(none)` literally (`\(none\)`); `UNREADABLE` never matches. A filter, like `loader` (D558): a copy of the name from
another jar in another loader is left alone, not a failure of the hook; D178's "all or none" applies to the filtered
set. Without a qualifier nothing changes but the report. The regex is compiled once per spec per call and **matched
through `BoundedRegex.find`** (`skillroy-protocol`'s `text/BoundedRegex.java`, security fix S46: the clocked
`CharSequence` technique the Swing locator and `SafeRegex` use, `StackOverflowError` caught, JDK only) on the pattern
anchored as `^(?:<regex>)$` with `BoundedRegex.BUDGET_MS` (converted to the nanoseconds `find` takes), which keeps the
whole-name semantics of §6.3; a `TooSlow` answers `UNAVAILABLE("codeSource regex too slow")` for that copy (fail
closed; all copies or none as D178) (D640). The probe runs under the engine's lock (`HookEngineImpl.java:296-331`, the
probe at `:380`), which every other `hook.*` call waits on with a plain `lock()`, so a backtracking pattern from a
catalogue typo would otherwise hold one worker and queue every later hook call behind it; a pattern is a reviewed
catalogue's or a person's own, never a model's (P6 §4.2), but a name is text the target supplies.

**Reporting (D559).** `hook.probe`: `HookProbe.codeSources`. `hook.install`: every `touched` line gains
`codeSource` (`HE:773-780`: `{class, loader, codeSource, bytes, methods, ms}`). `hook.health` (`HE:1347-1386`) gains a
top-level `classes: [{class, loader, codeSource, bytes, methods, hooks: [id…], deniedNow?: "<reason>"}]` from the ledger,
sorted by class then loader, at most `hooks.maxClasses` lines (the ledger's bound); an answer-map key, `HookHealth`
untouched. This is what `skillroy hooks status` reads (D180: it calls nothing but `hook.health`). The run's `hooks`
record keeps its shape; a code-source refusal's reason reaches `unavailable[].reason` as every reason does.

**As built (Phase 8, EH, 2026-10-19).** The refusal's names are the copies' distinct code-source names **sorted**
(`getAllLoadedClasses` answers in the JVM's order; D-EH-10), at most four, then " and <n> more". The qualifier's budget
is **one** `BoundedRegex.BUDGET_MS` per spec per call, shared by its copies through a deadline, so that the time under
the engine's lock does not grow with the number of copies (D-EH-9). `loaders` and `loaderIds` keep every copy by name
and loader, `codeSources` is aligned with them, and items 4 to 7 run over the kept copies; a ledger entry takes the
copy's name from the probe (`Probes.Match.codeSource`), never reading it again. The package-private reader seam is
`Probes.reader` (`HookEngineImpl.probes()` for the tests).

### 6.5 `ClassDeny` and the three sources (D562, D563)

`agent-api`, `…agent.api.spi.hook` (Java 8), parallel to gap 105's `InputVeto.refuse` (null to allow, else a reason; a
throw refuses):

```java
/** Refuses a class by binary name before any module changes its bytecode. Agent worker threads only; cheap, never
 *  blocking, never touching the application; a throw counts as a refusal. Consulted at every hook.probe, hook.install,
 *  rule.check and rule.submit, whatever the registration order. */
public interface ClassDeny {
    /** Null to allow, else a short reason (cut to 200 characters by the caller). */
    String refuse(String className);
}
```

The sources, in the order asked (first refusal wins; the reason text is the one answered as `UNAVAILABLE`, in Byteman's
`errors`, and in `hook.health.classes[].deniedNow`):

1. **The agent option `hooks.deny`** (A81): comma-separated `ClassPattern`s, trimmed, empty items dropped, at most
   64, read once at the module's install by `HookOptions.read` and by the Byteman `Settings.read` (both; so one key
   rules whichever module is loaded); an invalid pattern or more than 64 is `IllegalArgumentException("option
   hooks.deny: pattern <i> <violation>")`, the module's FAILED record naming the key (S14, as the other `hooks.*` keys).
   Reason: `denied by the agent option hooks.deny: <pattern>`. **Version skew, stated:** a 0.1.0 agent ignores the
   option silently (`HookOptions.read` reads its six keys and ignores the rest, `HookOptions.java:70-85`; no agent code
   refuses an unknown option, and §2.1's echo table covers wire parameters only); `rule.list` and `hook.health` then
   carry no `deny` key and `skillroy hooks status` prints `unknown`: the operator's check before relying on the option,
   and the reason `denyNotDelivered` reads `rule.list` back (§6.9).
2. **The catalogue's deny**, held by the hooks engine (§6.6), which registers its own `ClassDeny` in the shared
   `SpiRegistry` at the module's install (`HooksModule.install`, beside `HookEngine`), so that the Byteman module sees
   it through `ctx.spi().all(ClassDeny.class)` (`SpiRegistry.java:33`, shared by every module). Reason: `denied by the
   catalogue <id>: <pattern>` (`<id>` the catalogue that delivered the pattern first).
3. **A domain module's `ClassDeny`** (`ctx.spi().register(ClassDeny.class, …)` in its `install`). Reason: `denied by a
   module: <its text>` (cut to 200). A throw: `denied by a module: the deny policy <implementation class> threw
   <exception class>` (a refusal, logged once at WARN).

Name-only (no loader argument): the row asks for "refused by name", and the Byteman check runs before any class is
found. Evaluated on agent workers under the engine's lock (hooks) or the module's lock (Byteman) only; a `ClassDeny`
that blocks holds that lock, which is why the SPI says "never blocking" and why it is reviewed module code.

**As built (Phase 8, EH, 2026-10-19).** **The catalogue's `ClassDeny` is the engine itself**: `HooksModule.install`
registers the `HookEngineImpl` under `ClassDeny` beside `HookEngine`, and its `refuse` answers `denied by the catalogue
<id>: <pattern>`; every reader tells it apart from a domain module's by `instanceof HookEngine`, an `agent-api` type both
module loaders share, so the Byteman module uses that reason as it is and wraps every other in `denied by a module: `
(D-EH-1). **The order is the numbered list above**, whatever the registration order: the option, then the catalogue,
then the other modules in registration order (D-EH-2; §6.4's "every registered `ClassDeny` in registration order"
would put a domain module installed before the hooks module ahead of the catalogue). A `VirtualMachineError` thrown by
a `ClassDeny` is rethrown (`InputVeto`'s rule), any other throw refuses. `hooks.deny` keeps the first of equal
patterns, `<i>` counts the non-empty items from 0, and more than 64 is `option hooks.deny: more than 64 patterns (<n>)`
(D-EH-13). The sample's `LendingClassDeny` reads `lending.hooks.deny` with the same rule through `ctx.option`.

### 6.6 `hook.install`'s `catalogue.deny`, the held list, `hook.health.deny` (D564)

`hook.install {specs, catalogue: {id, version?, hash, application?, deny?: [pattern…]}, replace?}`. Validation, before
the lock: `deny` absent or a list of strings; each `ClassPattern.violation` null, else `INVALID_PARAMS` "deny pattern
<i>: <violation>" with `data.param` `catalogue.deny[i]`; more than 64: `INVALID_PARAMS` with `data.param`
`catalogue.deny`; a union with the held list beyond 256 distinct patterns: `INVALID_PARAMS` "the engine holds 256 deny
patterns; restart the target", `data.param catalogue.deny`.

**The held list is add-only for the engine instance's life.** Under the lock, after the detached check (`HE:298-301`)
and **before the conflict check** (`HE:302-315`), the call's patterns are merged (distinct, with the delivering catalogue
id recorded for the first delivery of each), so that the call's own specs are checked against them at step 2 and so
that a catalogue refused with `HOOK_CONFLICT` has still tightened the engine (the safe direction). The list survives
`hook.remove {}` (unlike the `Catalogue` record, which `HE:1200` clears), `replace: true` (union, never replace,
D564), and an install of which nothing installs (D183's case); it is lost only with the agent instance (a re-attach
starts empty and the next run's install delivers it again). Loosening a deny therefore needs a target restart (the
guide says so: §16.1, guide §9.3). A zero-spec `hook.install` (`specs: []`, legal today: `HE:243-247` bounds the count only) delivers a
deny and installs nothing.

**Echo.** Every `hook.install` answer, the `alreadyInstalled` one included (`HE:335-348`), carries `catalogue.deny:
[held patterns, sorted]`, an empty list when none is held (so a new agent holding nothing is told apart from an old
agent, whose answer has no key). `hook.health` gains a top-level `deny: {catalogue: [sorted held patterns], from: [the
delivering catalogue ids, at most 16], option: [the option's patterns], modules: <number of other ClassDeny
registrations>}`, always present on a new agent. A hook installed **before** its class was denied (a domain module's
SPI install, then a catalogue; or a later catalogue with a wider deny) is **not removed** (a removal nobody asked for;
D568): `hook.health.hooks.<id>` gains `deniedNow: true` and `classes[]` carries the reason; the installer records it
(§6.9). `rule.list` gains the same `deny` object without `catalogue`/`from` when the hooks module is absent (the Byteman
module reads the option and the registry; read-only, so a Byteman-only target can be inspected).

**The SPI.** `HookEngine` gains `default List<String> deny()` (the held catalogue patterns; `Collections.emptyList()` by
default; the hooks engine overrides it). The four-argument `install` is unchanged: a module's install carries no
catalogue and so no deny; a deny reaches the engine through `hook.install` only (D575). The rule of §2.3 holds: a default
method, no abstract change.

**As built (Phase 8, EH, 2026-10-19).** Texts: a `deny` that is not a list is `catalogue.deny must be a list of class
patterns` (`data.param catalogue.deny`), an item that is not a string `deny pattern <i>: not a string`, more than 64
`at most 64 deny patterns per catalogue`; the 256 check runs under the lock, beside the merge (the held list can only be
read there). The held list is an immutable snapshot (patterns compiled once) replaced under the lock and read without
it by the probe and by the Byteman module's threads. `from` lists the delivering catalogue ids in the order of their
first delivery. `hook.health.classes[].hooks` names the installed hooks and the residue hooks whose classes include the
entry; `classes[].deniedNow` is the reason through the engine's `wire` redaction. **`rule.list.deny`** is `{catalogue?,
option, modules}`: `catalogue` from `HookEngine.deny()` when the hooks module is loaded, **never `from`**, which the SPI
does not carry (D575; D-EH-3).

### 6.7 The Byteman module (P6 §7.3 amended; D569)

`ClassBound` takes a deny view (`interface Deny { String refuse(String className); }`, package-private) that `Engine`
builds per call from `Settings.deny` (the option's patterns) and `ctx.spi().all(ClassDeny.class)` read then (so the
hooks module's catalogue registration counts when that module is loaded, and a domain module's too).

- `ClassBound.check` (`ClassBound.java:118-155`): after `nameProblem` and **before the count and before the load**
  (`Engine.java:277-282`, L2-F5's order kept), the rule's `CLASS`/`INTERFACE` name (the `^` stripped) that the view
  refuses is `errors.add("<name>: <reason>")` and the check returns; so a confirmed `rule.submit` on a denied name is
  `RULE_REJECTED("the rule was refused: <name>: denied by …")` with `data.errors`, appends nothing and runs no
  `premain`, and `rule.list.loaded` stays `false`.
- `ClassBound.problem(c)` (`:190-213`): after the loader rule, a matched loaded class the view refuses (a `^` or
  `INTERFACE` rule reaching a denied subtype) is `"<class>: <reason>"`.
- `rule.check` (`Engine.java:228-257`, read-only, loads nothing): after the lint, the name-level check of the rule's
  class against the view goes into `errors` (so `ok: false`); the loaded-subtype check is `rule.submit`'s bound only
  (`rule.check` reads no class list today and keeps that). `typeChecked` unchanged.
- **Residual, recorded as P6 §7.3 records its own** (and opened as gap 140): a denied subtype loaded **after** a `^` or
  `INTERFACE` submit receives Byteman's trigger at its load; closing it needs a guard transformer behind Byteman's, a
  cost on every class load (BS3 fact 20), not taken now. The guide (§9.6), `threat-model.md` §8 and `SECURITY.md` (d)
  say so (§16.1).

**As built (Phase 8, EH, 2026-10-19).** `rule.check` of a denied rule class reports the deny in `errors` and skips the
type check (D-EH-14). The deny view reads `ctx.spi().all(ClassDeny.class)` once per call, as specified; a throwing
module `ClassDeny` is logged once per implementation class.

### 6.8 The catalogue (`skillroy-scenario`, `…scenario.hooks`; P6 §5.1, §11.2 amended)

An illustrative catalogue showing every new form (the sample's own is §12.3: the qualified `packages` entry and the
`Inbox` deny only, no entry-level qualifier and no second package; no `desk-core` jar and no `plugins` package exist):

```yaml
catalogue: example
version: 2
deny: [example.app.Inbox]                                    # ClassPattern grammar; at most 64; part of the hash when present
packages:
  - { name: example.app, codeSource: 'example-app(-[^/]*)?\.jar' }
  - example.app.plugins                                       # the plain form stays
hooks:
  - id: limit.applied
    target:
      class: example.app.AccessControl
      method: returnLimit
      descriptors: ["(Ljava/lang/String;)Ljava/math/BigDecimal;"]
      codeSource: 'desk-core(-[^/]*)?\.jar'                   # an entry's own qualifier wins over packages'
```

**Keys.** `TOP_KEYS` and `OVERLAY_KEYS` gain `deny` (`HookCatalogues.java:69-71`); `TARGET_KEYS` gains `codeSource`
(`:74`); a `packages` item is a string (today's `PACKAGE` pattern, `:76`) or a map with the keys `name` (required, the
same pattern) and `codeSource` (optional: at most 200 characters, compilable).

**Errors, each at its line** (the strict reader's rule, `ScenarioException` with source and line): `deny` not a list
or an item not a string ("deny must be a list of class patterns"); a pattern's violation ("deny pattern '<p>':
<violation>", the item's line where the reader has one, else the key's); more than 64; an **enabled** entry whose class
a deny pattern matches ("hook <id> targets <class>, which the catalogue denies (<pattern>)", at the entry's `class`
line); a `packages` map with an unknown key, without `name`, with a bad name or a bad `codeSource` (its line);
`target.codeSource`'s violation (its line, through `violationLine`, `:440-448`). A **disabled** entry on a denied class
is a warning in `warnings()` ("hook <id> is off and its class <class> is denied by the catalogue (<pattern>): it can
never be enabled"), so that an overlay can deny a base class and `disable` the base's entry. `PlanCompiler` validates
the catalogue at compile time (`PlanCompiler.java:369-374`), so each is also a compile error naming the resource.

**Inheritance (D558).** An entry's effective `target.codeSource` is its own, else the qualifier of the **longest**
`packages` name its class is under (`inPackages`' rule, `HookCatalogue.java:162-169`), else none. The reader bakes the
effective value into the entry's `HookTarget`, so the spec the agent receives, the entry hash and the catalogue hash
agree; `Builder.build()` applies the same rule to entries built in Java that carry no qualifier. `enabled`'s default is
unchanged; the agent's refusal is what makes a third-party copy under a shared package safe.

**Overlay** (`HookCatalogues.overlay`, `:131-167`): package rules are unioned by name, an overlay's qualifier for a name
replacing the base's **for the overlay's own entries** (base entries keep the value baked when the base was read, unless
the overlay replaces the entry; D570); `deny` is the **union** of the base's and the overlay's (an overlay can never
lift a base deny); overlay entries and replaced entries are checked against the union; a base entry that is enabled,
not disabled by this overlay, and denied by the overlay's `deny` is an error at the overlay's `deny` line ("hook <id> of
catalogue <base> targets <class>, which this overlay denies (<pattern>); add it to disable").

**API** (`HookCatalogue`, v0.1.0's public members kept): `packages()` stays `List<String>` (the rules' names);
`record PackageRule(String name, String codeSource)` (nested, `codeSource` null for the plain form) and
`List<PackageRule> packageRules()`; `List<String> deny()` (distinct, in order) and `String denied(String className)`
(the first matching pattern or null); `MAX_DENY = 64`; `Builder.packages(List<String>)` kept (adds rules without a
qualifier), `Builder.packageRules(List<PackageRule>)` (adds; the same name replaces its qualifier),
`Builder.deny(List<String>)` (adds distinct); `build()` validates every pattern, bakes the qualifiers and applies the
enabled-denied rule (`IllegalStateException`, as `:358-363` does for violations; the YAML reader maps it to the
`hooks` line as `:534-540` does). `equals`/`hashCode` include the rules and the deny. **Hash** (D571): `{id, version,
hooks: [{hash, enabled}…], deny: [sorted]}` with `deny` written **only when non-empty**: every existing catalogue keeps
its hash byte for byte, and a baked qualifier changes the entry hash as any target change does. **`descriptor()`**
gains `deny` when non-empty (what `hook.install` carries).

**As built (Phase 8, EH, 2026-10-19).** `HookEntry.withTarget(HookTarget)` is new (the bake). **The builder bakes only
the entries it did not take from the base** (`builder(base)` marks the base's entries baked, `add` and `replace` unmark
them), so a base entry that the base left without a qualifier is not re-baked when an overlay adds a qualified rule
over its package (D570's "re-baking would change base entries' hashes"; D-EH-4). **An overlay's plain `packages` name
never drops a base qualifier**; only its map form replaces one (D-EH-5). An item's line is the key's for `deny` (the
strict reader keeps no line of a scalar list item). The overlay refuses a union beyond 64 patterns at its `deny` line.
`build()` returns `EMPTY` only when the id, the entries, the rules and the deny are all empty, so a deny-only
catalogue built in Java is a catalogue (`isEmpty()` stays "no entry").

### 6.9 The installer (`HookInstaller`, P6 §5.3 amended; D565, D566, D567)

- **Something to install:** a catalogue with an enabled entry **or a non-empty deny**. A deny-only catalogue is sent as
  a zero-spec `hook.install` when the engine is `OK` (it delivers the deny; `installed: []`); under `ON` it is not a
  precondition failure. A catalogue with neither is "no catalogue" as today (`:172-179`).
- **The echo requirement** (§2.1): when the pack's catalogue has a non-empty deny, the `hook.install` answer's
  `catalogue.deny` must be a list holding every pack pattern; on the `alreadyInstalled` path, step 0's `hook.health`
  answer's `deny.catalogue` must. Otherwise ERROR / ENVIRONMENT: "the agent does not honour the catalogue's deny list
  (<missing patterns>); upgrade the agent", the detail `{missing, answered}`, the record `ok: false`. The same
  acknowledgement idea as gap 107's `inputId`.
- **The old-agent hint** (D560): an `INVALID_PARAMS` from `hook.install` whose `data.param` starts with `specs[`
  while any sent spec carries `codeSource` is ERROR / ENVIRONMENT as today (`:391-404`) with "; the catalogue uses
  target.codeSource, which this agent does not know: upgrade the agent" appended.
- **`denyNotDelivered`:** on every skip (`off`; `hook.engine` absent or not `OK` under `AUTO`) of a pack whose
  catalogue has a deny, the record gains `denyNotDelivered: [patterns]`; the guide names the remedy (`hooks.deny` on
  the target, or load the hooks module). Because a 0.1.0 agent ignores `hooks.deny` silently (§6.5 item 1), the record
  also says whether the remedy is in force: `agentOption: unknown | [patterns]`, read from `rule.list.deny.option` when
  the matrix shows a `rule.*` capability (one `rule.list` read, a client the installer already has; `unknown` when the
  answer has no `deny` key or no `rule.*` capability exists). Under `ON` without an engine the install is
  `PRECONDITION_FAILED` as today.
- **The record** gains `deny: [patterns]` when the pack has one (install and `alreadyInstalled` paths), `deniedNow:
  [id…]` when step 0's health marks installed hooks so, and the lines above. `touched` stays a count.
- Everything else of P6 §5.3 (step 0's armed-stub rule, replace, fallbacks, the matrix re-read) is unchanged.

**As built (Phase 8, EH, 2026-10-19).** **`denyNotDelivered` is an object** `{patterns: [...], agentOption: "unknown" |
[...]}` (the passage above says both `denyNotDelivered: [patterns]` and `denyNotDelivered.agentOption`; D-EH-6). The
`rule.list` read happens when any `rule.*` id is in the matrix, whatever its state; a failed read is `unknown`
(D-EH-11). Under `off` the catalogue is read for the record only, and a catalogue that cannot be read records nothing.
The record's `deny` and `deniedNow` come after its other keys, so a record without them is byte-identical to today's;
the echo's ERROR record carries the catalogue reference and both keys.

### 6.10 `skillroy hooks status` (P6 §6.2 amended)

Text: after "other agents": `deny (target): option <p, q | none>; catalogue <id> <p | none>; modules <n>` and `deny
(pack catalogue <id>): <p, q | none>`; after the hook table, the table `CLASS LOADER CODE SOURCE HOOKS` from
`health.classes` ("no class touched" when empty; `DENIED: <reason>` appended in `HOOKS` for `deniedNow`); the pack
table becomes `HOOK MODE ENABLED TARGET CODE SOURCE` (`-` without a qualifier); the catalogue's warnings as today.
`--json`: `health` raw (it carries `deny` and `classes`); `catalogue` gains `deny: [...]` and `packages: [{name,
codeSource}]`, and each entry's spec JSON already holds its baked `target.codeSource`. Against an old agent the deny
lines read `unknown (the agent reports no deny list)`. Exit codes unchanged.

**As built (Phase 8, EH, 2026-10-19).** The class table is printed only when `hook.health` carries `classes` (an old
agent reports none, and then no table); the catalogue line of `deny (target)` names the `from` ids before the
patterns; a deny-only pack catalogue prints "no hook entry" in place of its empty table (D-EH-15).

### 6.11 Bounds, threads and the failure paths in one table

| Where | Bound or rule | On failure |
|---|---|---|
| `CodeSources.of` | one `getProtectionDomain` per candidate copy per call, on the worker; field getters only; name cut at 200 | `SecurityException` → `UNREADABLE` → `UNAVAILABLE("the class's code source cannot be read: SecurityException")` under a qualifier; never thrown |
| `target.codeSource` | regex ≤ 200 characters, compiled once per spec per call, matched through `BoundedRegex.find` under `BUDGET_MS` on the anchored pattern; the name ≤ 200 | not compilable → `INVALID_PARAMS specs[n].target.codeSource` (agent) / the line (reader); too slow → `UNAVAILABLE("codeSource regex too slow")` for the copy, all copies or none (D640) |
| `codeSources` in a probe | at most 16 names; the refusal names at most 4 | – |
| `hook.health.classes` | at most `hooks.maxClasses` lines | – |
| `ClassPattern` | ≤ 200 characters; linear match; 64 per source; 256 held | `INVALID_PARAMS catalogue.deny[i]` / `catalogue.deny`; the option: the module's FAILED record; the reader: the line |
| `ClassDeny` | agent workers under the engine's or the module's lock; cheap; never blocks; reason cut at 200 | a throw refuses the class (`denied by a module: … threw …`), logged once |
| Byteman `ClassBound` | the deny before the count and before the load; the view read once per call | `RULE_REJECTED` with `data.errors`; nothing appended, no `premain` |
| The installer | one `hook.install`, the echo checked; one `rule.list` read on a skip with a deny when a `rule.*` capability is present (`denyNotDelivered.agentOption`), otherwise no new call | ERROR / ENVIRONMENT with the texts of §6.9 |

Nothing new runs on an application thread, at transform time (only ledger classes are transformed,
`Transformers.java:282-301`, and a denied class never enters the ledger), on the EDT, or in a new thread; the URL
handler and DNS are never touched; nothing third-party enters a target JVM.

### 6.12 Behaviour changes

- **Behaviour change (the sample only):** with `lending.yaml` version 2, a lending desk run **from a class directory**
  (`target/classes`) with the hooks module answers every catalogue hook `UNAVAILABLE("class from another code source:
  classes")` and scenario E is DEGRADED there; the IT copies (`skillroy-sample-lending-app.jar`,
  `samples/skillroy-sample-lending-scenarios/pom.xml:240-262`) and a versioned jar match the qualifier. No committed
  test runs the desk from classes with hooks (`SignOnCheckedHeadlessIT` uses the IT jars, `:80`).
- A catalogue that uses neither key: nothing changes but additive report fields (`codeSources`, `touched[].codeSource`,
  `hook.health.classes` and `.deny`, `hook.install`'s `catalogue.deny: []`, `rule.list.deny`) and the CLI's extra lines
  and column.
- A 0.1.0 agent under a 0.2.0 pack that uses either key: the install is ERROR / ENVIRONMENT (fail closed; unsupported mix).
- A 0.1.0 agent given the option `hooks.deny` ignores it silently (§6.5 item 1): a Byteman-only 0.1.0 target then
  instruments a denied class under a person's confirmed `rule.submit` with nothing failing closed; `rule.list` and
  `hook.health` carry no `deny` key, `skillroy hooks status` prints `unknown`, and a run's `hooks` record reads
  `denyNotDelivered.agentOption: unknown` (§6.9). The one Phase 8 protection whose loss on an old agent is silent on
  the wire, stated so.

---

## 7. Semantic input: warnings, acknowledgement by input, composite primitives, rung ladders

Phases 1 to 7 gave a primitive two outcomes: a value, or an error the executor maps to a verdict. A semantic input
has more: taken but consumed later, consumed with another value, consumed by another question than the step's
(gap 18); an acknowledgement that belongs to an earlier input (gap 107); a flow whose next question depends on the
application, owned by one agent-side primitive that today hides it (gap 19); and an action that has a physical path
and a shortcut, with no way to require one or to record which ran (gap 22). This section adds **one warning channel
in the call envelope**, **standard result conventions** the executor reads, **two step modifiers** (`accept`,
`minRung`), **acknowledgements correlated by `inputId`**, **the composite-primitive rule with two executor checks**,
and **call-level rung ladders**. Everything is additive: the protocol stays `0.2` (§2.1), a target or pack
that uses none of it behaves as in 0.1.0 except where this section says **Behaviour change**.

### 7.1 The warning channel (gap 18; D576, D577, D578)

**Wire (`skillroy-protocol`, Java 8; A83).** `RpcResult` gains a seventh, optional member `warnings`: a list of
objects `{code, message?, data?}`, **written only when non-empty** (`toJson`; so every existing result's JSON text is
unchanged), read leniently by `fromJson` (absent = empty; a 0.1.0 controller ignores it, `RpcResult.fromJson` reads
known keys only, `PROTO/RpcResult.java:119-146`). New 7-argument constructor `RpcResult(value, events, seqBefore,
seqAfter, durationMs, thread, List<CallWarning> warnings)`; the 6-argument one stays (empty). New class
`ai.skillroy.testjava.protocol.CallWarning` (immutable):

```java
public final class CallWarning {
    public static final String INPUT_QUEUED = "INPUT_QUEUED", VALUE_COERCED = "VALUE_COERCED",
        VALUE_DROPPED = "VALUE_DROPPED", ANSWERED_NESTED = "ANSWERED_NESTED",
        INVALID_WARNING = "INVALID_WARNING", TOO_MANY_WARNINGS = "TOO_MANY_WARNINGS";
    public static final Set<String> BASE_CODES;                 // the six above
    public static final Pattern BASE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    public static final Pattern PACK_CODE = Pattern.compile("[a-z][a-z0-9-]{0,31}\\.[A-Z][A-Z0-9_]{0,63}");
    public static final int MAX_PER_RESULT = 16, MAX_MESSAGE = 500, MAX_DATA_CHARS = 2048;
    public CallWarning(String code, String message, Map<String, Object> data);   // IllegalArgumentException: bad code
    public String code(); public String message(); public Map<String, Object> data(); public boolean isPackCode();
    public Map<String, Object> toJson();
    public static CallWarning fromJson(Map<String, Object> json);   // lenient, see below
    public static List<CallWarning> readAll(Object rawList);         // RpcResult.fromJson's reader
}
```

`code` matches `BASE_CODE` or `PACK_CODE`; `message` is one printable line, truncated at 500 characters, control
characters replaced by spaces; `data` is a frozen JSON object whose serialised text is at most 2048 characters (a
larger one becomes `{"truncated": true}`). `fromJson` of an element that is not an object or whose `code` is absent
or malformed yields `INVALID_WARNING` with `data {raw: <the element's text, at most 200 chars>}`; `readAll` reads at
most 16 elements and replaces the rest by one `TOO_MANY_WARNINGS {dropped: n}`. Both codes fail closed (7.1 below).

**Agent API (`agent-api`, Java 8; A84).** `Invocation` gains
`default void warn(String code, String message, Map<String, Object> data) { }` (the default records nothing: an
invocation made outside the dispatcher). The core's invocation checks the code against `BASE_CODE`/`PACK_CODE` inside
`warn` (an `IllegalArgumentException` from a bad code is thrown to the primitive: a module bug, found in its tests) and
stores the **raw** message and data, in call order, thread-safe, never blocking; the 17th and later are replaced by one
`TOO_MANY_WARNINGS`; a `warn` after `invoke` returned is dropped and logged once at FINE. **The dispatcher redacts every
value of a parameter named in `_meta.secretParams` from the raw `message` and `data`** with the routine it already
applies to the call's error answer (`SecretParams.redact`, P1 §5 "Secret redaction"; A34: a secret of 8 or more
characters replaced wherever it occurs, a shorter one as a whole token, `SecretParams.java:184-212`), and **only then
builds each `CallWarning`**, whose constructor sanitises and cuts the message at 500 and bounds the data at 2048. The
order matters: a cut before the redaction would leave a secret straddling the cut as a prefix that equals no secret
(the agent already masks before it cuts for console text, `EventBridge.java:229-245`,
`WebPrimitives.CONSOLE_REDACTION_MARGIN`), so the dispatcher cuts the raw message first at 500 plus that margin, redacts,
then lets the constructor cut at 500; the `CallWarning` Javadoc says "the bounds apply after redaction; a caller that
builds one from text holding a secret redacts first". No new error code, no new topic.

**Codes.** A warning's code is a base code or a pack code `<packId>.<NAME>` the pack declares through
`DomainPack.warningCodes()` (7.3). Standard meanings (restated in guide §6.5 and §6.14):

| Code | Meaning | Emitted by |
|---|---|---|
| `INPUT_QUEUED` | the application took the input and had not consumed it when the call returned, and nothing in the call's sync waits for that input's own acknowledgement | the executor (7.2), never a module |
| `VALUE_COERCED` | the application consumed a different value than the one sent | the module |
| `VALUE_DROPPED` | the application consumed the input and ignored its value | the module |
| `ANSWERED_NESTED` | the input was consumed by another question than the one the step addressed | the module |
| `INVALID_WARNING`, `TOO_MANY_WARNINGS` | a malformed or overfull channel | the reader; never acceptable |
| `<packId>.<NAME>` | the pack's own meaning, documented by the pack | the module |

**Executor (`SC/CallRunner.java`, every caller: plan steps, cleanup, sessions, MCP).** After a successful call,
in the order of 7.7: `warnings = result.warnings()` plus the derived `INPUT_QUEUED` (7.2). For each warning one
`run.jsonl` record `warning {step, index, primitive, code, message?, data?, accepted: bool, source: agent | executor}`
(masked by the run mask as every record is) and one line `"<step>: <verb>: <primitive> <CODE> (<message>)"` (built
with `Values.printableLine`, masked as the `degradedFidelity` lines are) appended to new last components
`StepResult.warnings`, `RunResult.warnings` and `StepOutcome.warnings` (`List<String>`; every older constructor is
kept and gives an empty list). A warning is **accepted** when its code is in the call's `accept` set (7.3). Then:

- every warning accepted: the verdict is untouched (`PASS` stays `PASS`; D577);
- any warning not accepted: the step fails **`FAIL / ASSERTION`** after all of the call's warnings were recorded, with
  the message `"<verb>: <primitive> reported <CODE>: <message> (a step accepts it with accept: [<CODE>])"` (the first
  unaccepted one; the detail holds `{primitive, warnings: [...], unaccepted: [codes]}`). A code that is neither a base
  code nor one the pack declares is reported `"... reported the undeclared warning <code>"` and can never be accepted
  (compile refuses it in `accept`, 7.3). `INVALID_WARNING` and `TOO_MANY_WARNINGS` are never accepted. This is today's
  outcome for a module that throws `INPUT_REJECTED` instead: the restrictive default;
- a **cleanup call's** warnings are recorded (`step: null, cleanup: true`) and never change the verdict, as its errors
  do not (P1 §8.3);
- a **session step** (`ExecutorSession.execute(verb, args, …)` and the raw `execute(PlannedCall, …)`) accepts only
  what the verb's own calls pre-accept (7.3): an unaccepted warning is the step's `FAIL / ASSERTION` outcome, which a
  model or a client sees with the code (D579). `StepOutcome.warnings` carries every line; the session service's
  model view (`SessionService.step`, `controller/skillroy-inference/.../SessionService.java:1404-1433`) and the MCP
  step reply (`ToolCalls.stepJson`) gain a key `warnings` **only when non-empty**, so the five recorded sessions stay
  valid (map I §2.3).

Reports: the JUnit XML's `<system-out>` gets one `warning <line>` per entry beside the `degraded` lines; the JUnit
glue's summary and the CLI's text output print `warning:` lines; `run --json` carries `warnings` per result and per
step as it carries `degradedFidelity`. `RunLog.OBSERVED` (`SC/RunLog.java:78-79`) gains `warning`, `rung`, `rungs`
and the telemetry observer forwards them as scenario records (no new span). No model is involved; `replay` is
unaffected.

**Bounds and threads.** Controller: O(16) per call, no new wait. Agent: `warn` is a synchronised append; the
dispatcher's redaction runs on the dispatch thread after the primitive returned, within the call's budget.

### 7.2 The semantic-input result convention (gap 18; D580)

Constants and readers in `ai.skillroy.testjava.protocol.InputResult` (Java 8; A86): keys `accepted`, `inputId`,
`consumed` (values `CONSUMED`, `QUEUED`), `taken`, `answeredBy`; the `INPUT_REJECTED` data key `reason` with the
standard values `notAccepted`, `inboxFull` (§3.2's `vetoed` and `not visible` join them); `INPUT_ID_PATTERN =
[A-Za-z0-9._:-]{1,64}`; `static String inputId(Object value)` and `consumed(Object value)` (null when absent or not a
string). A module's input primitive (`<domain>.input`, a device action that injects an input, a composite's last
input) adopts what it can observe; every field is optional and additive:

```json
{ "accepted": true,
  "inputId": "lending-42",             // minted by the module (7.4): opaque, unique within the agent's life
  "consumed": "CONSUMED" | "QUEUED",   // consumed before the call returned, or taken and not yet consumed
  "taken": "T1001",                    // only with VALUE_COERCED: the value the application consumed; never when the
                                       // sent value is a secret parameter (Invocation.secretParams())
  "answeredBy": {"kind": "question" | "prompt", "id": "…"?, "text": "…"?}   // only with ANSWERED_NESTED
}
```

and reports the caveats through `inv.warn(...)` (7.1): `VALUE_COERCED` with `taken`, `VALUE_DROPPED`,
`ANSWERED_NESTED` with `answeredBy`. **`INPUT_REJECTED` keeps meaning "refused before it was handed over"** (A33):
refused = error, handed over with a caveat = warning; the two never overlap. A module never puts a secret value in
`taken`, a message or `data` (the dispatcher's redaction and 7.5's secret-echo check are the backstops).

**`INPUT_QUEUED`, derived by the executor.** When a step call's result says `consumed: QUEUED` and the call's sync is
not correlated to that input, the executor adds `INPUT_QUEUED` (`source: executor`, message
`"input <inputId> was queued and nothing waits for its acknowledgement"`). A sync is correlated when it is `ACK` or
`READY` under a `SyncPolicy` with `ackByInputId` (7.4), or `EVENT` whose resolved match has the key `inputId`. The
failure path is 7.1's: unaccepted, the step fails; a module that returns no `consumed` field triggers nothing.

### 7.3 Step modifiers `accept` and `minRung`; the pack's warning codes (gaps 18, 22; D579, D581)

**Grammar (`SC/ScenarioLoader.java:472-495`, `SC/Scenario.java:195`; §2.2).** A step is a mapping with
**exactly one verb key** and any of the reserved modifier keys `accept` and `minRung`
(`ScenarioLoader.MODIFIERS = Set.of("accept", "minRung")`). `Scenario.Step` gains a fourth component
`Map<String, Object> modifiers` (the 3-argument constructor stays, empty). Failure paths at load: two non-modifier
keys, or none, is the existing error ("must be a single-key mapping verb: args …") extended with "plus the
modifiers accept, minRung"; a modifier with a wrong shape is an error naming it (`accept`: a non-empty list of 1..32
distinct strings matching `BASE_CODE` or `PACK_CODE`; `minRung`: a string matching `RungResult.NAME`); a verb named
like a modifier is refused ("'accept' is a reserved modifier, not a verb"); `PlanCompiler.RESERVED_VERBS` gains
`accept` and `minRung`, so a pack that declares a verb of that name fails at compiler construction (**Behaviour
change** for such a pack; none exists).

```yaml
- scan: { barcode: " 4005 " }
  accept: [VALUE_COERCED]
- deviceScan: { barcode: "4005" }
  minRung: PHYSICAL
```

**Compiler (`SC/PlanCompiler.java:860-911`).** After the verb compiled, for every call of the step: `accept` = the
call's own `accept` (7.3 next paragraph) ∪ the step's; an `accept` code that is not a base code (other than the two
never-acceptable ones) and not in `pack.warningCodes()` is a compile error ("accept: unknown warning code X; the base
codes are …; pack 'lending' declares …", the lesson of gap 111). `minRung`: every call that declares `rungs` (7.6)
gets it, after the check that it names one of that call's rungs ("minRung PHYSICAL is not a rung of lending.input's
ladder [..]"); a step none of whose calls declares a ladder refuses `minRung` ("minRung: no call of 'scan' declares a
rung ladder"). `expect`, `aiAct`, `aiExpect` and `captureFacts` steps (no calls) refuse both modifiers ("'accept' is
not allowed on captureFacts: it has no calls"; without the explicit refusal `accept` on a step with no call would be
silently dropped, since it is applied per call, and its unknown-code check would never run). **`ui: {strict: true}`**
(A29) extends to ladders: a ladder call without a step `minRung` gets its first rung as `minRung` (D587; the step's
own wins, as for `click`; **Behaviour change** only for a scenario that sets `strict` and uses a ladder verb, none
today). `CompileContext` gains `default String minRung() { return null; }`, the step's own value, so that a verb may
forward it as the primitive's parameter (7.6); under `strict` alone a ladder verb forwards its own first rung.

**`PlannedCall`** (`SC/pack/PlannedCall.java`) gains three components after `literalArgs`: `Set<String> accept`
(empty), `List<Rung> rungs` (empty), `String minRung` (null); the 7-, 6-, 5- and 4-argument constructors and the
factories stay; `withAccept(Set<String>)`, `withRungs(List<Rung>)`, `withMinRung(String)`; the canonical constructor
checks the code shapes, 2..8 rungs with distinct names, and `minRung` ∈ `rungs`. A verb may **pre-accept** codes on
its own calls (`withAccept`): the sample's `signOnAny` does not; the facility exists for a verb whose primitive
documents a caveat the verb knows is harmless. **`Plan.Call`** (`SC/Plan.java:480-571`) gains `accept`, `rungs`,
`minRung` as its ninth to eleventh components (the 8-argument and older constructors stay); `toJson` writes
`"accept": [...]` sorted only when non-empty, `"rungs": [{"name": "PHYSICAL", "capability": "lending.scanner.physical"},
{"name": "SEMANTIC", "capability": null}]` only when non-empty, `"minRung": "PHYSICAL"` only when present; the reader
refuses each below **plan version 7** ("a call's 'accept' needs a version 7 plan", likewise `rungs`, `minRung`;
§8.2 owns the version and the gating helper). `ScenarioSchema`'s step definition (`SC/ScenarioSchema.java:170-176`)
gives every verb object the optional properties `accept` (array of string, `minItems: 1`) and `minRung` (string).

**`DomainPack.warningCodes()`** (A87): `default Set<String> warningCodes() { return Set.of(); }`, forwarded by
`DelegatingDomainPack` (an overlay overrides to add its own), enforced by `PackOverlayTest`; each code must match
`PACK_CODE` with the pack's own id before the dot, checked once when the compiler is constructed (error naming the
pack and the code). `CallRunner` receives the set at construction for the "undeclared" check of 7.1.

**Sessions.** A session's `execute(verb, args, …)` compiles the verb without modifiers; a raw
`execute(PlannedCall, …)` whose call carries a non-empty `accept`, `rungs` or `minRung` is refused **before anything
is sent**: outcome `ERROR / ENVIRONMENT` "a raw call carries accept, rungs or minRung; they belong to a scenario step
or a pack verb" (D579). A verb's compiled calls may carry all three and are honoured. The journal's call records
(`SC/StepJournal.java` `step.calls[]`) gain `accept?`, `rungs?`, `minRung?` when present (information); the export is
unchanged (it writes verbs and arguments, which recompile to the same calls).

### 7.4 Acknowledgement by input (gap 107; D582, D583)

**`inputId`, minted by the module.** Only the module sees the application's input object and its acknowledgement,
so the module mints the id (`[A-Za-z0-9._:-]{1,64}`, unique within the agent's life; the sample: `lending-<n>` from an
`AtomicLong`), returns it as `inputId` (7.2) and puts it in the acknowledgement payload: the sample's `input.acked`
becomes `{source, inputId}` (never the value). A caller-supplied `inputId` parameter, when the module's input
primitive accepts one, is echoed instead of minted (the EVENT pattern below). The controller never mints one.

**Late acknowledgements are published from the seam.** A module whose application consumes an input after the call
returned keeps the input in a bounded pending table (at most 256 entries, the oldest dropped when a new one is added
to a full table; every entry cleared on detach) and publishes the acknowledgement with its `inputId` from the
application's own callback, on whatever thread that is, without blocking (`publish` never blocks, guide §6.13); an
acknowledgement that arrived within the call is published on the calling thread as today, so the call's `events` and
the recorded sessions' topic lists are unchanged (map D §0.5); exactly one acknowledgement per input. For telemetry
linkage `EventBus` gains (A84) `default long publish(String topic, String source, Map<String, Object> payload,
String correlationId)` whose default ignores the id and calls the 3-argument form; `RingEventBus` implements it by
stamping the given id (null: the thread's current one, as today), so a late acknowledgement carries the originating
call's `Invocation.correlationId()`. An inert listener (after `deactivate`) publishes nothing.

**`SyncPolicy.ackByInputId`** (`SC/pack/SyncPolicy.java`; A87): a sixth component `boolean ackByInputId`, the
5-argument constructor kept (`false`), `withAckByInputId(boolean)`. When true, `CallRunner.applySync` (which gains the
call's result value as a parameter, also on the cleanup path `SC/PlanExecutor.java:2460-…`) reads
`InputResult.inputId(value)` for every `ACK` and `READY` call and awaits the ack topic with `match: {inputId: <it>}`
(the `sync` record shows the `match`; the `SYNC_TIMEOUT` message names it: `"no ack event (input.acked) matching
{inputId=lending-42} within 5000 ms after lending.input"`). **Failure path:** a result without a string `inputId`
under such a policy is `ERROR / ENVIRONMENT` ("the pack's acknowledgements are correlated by inputId; lending.input
returned none"), nothing awaited: fail closed. When false, behaviour is exactly today's (match `null`,
`PlanExecutorTest.syncAckAwaitsTheAckTopicSinceSeqBefore` keeps its assertion). The ready wait of `READY` stays
"since `seq.before`" (the sample's ack/ready ordering race, map D §0.1); a domain whose acknowledgement means
"consumed" uses `ACK` (now correlated) and lets the next call's own sync cover readiness, or `EVENT` on its ready
topic.

**The EVENT pattern (guide §6.5, §8.2), for an acknowledgement that is not the pack's ack topic or a pack that
cannot opt in:** the verb mints a deterministic literal at compile time (for example `"L" + ctx.sourceLine() + "." +
<call position>`), passes it as the primitive's `inputId` parameter and awaits `EVENT` on the acknowledgement topic
with `match: {inputId: <the literal>}`; the module echoes a caller-supplied id. Only literals or `$slot` values can be
matched (`AwaitRules` resolves a match before the call, `SC/AwaitRules.java:72-87`).

Plan: no change from this gap alone (the sync level stays `ACK`/`READY`; correlation is a pack property and a result
field). Protocol: nothing beyond `InputResult`.

### 7.5 Composite primitives (gap 19; D584, D585, D586)

**The rule (also guide §6.14, §8.2, §8.3).** Logic that depends on which question the application asks next lives in
**one agent-side composite primitive that owns its nested questions**; plans stay linear (the "only if the next ready
event matches" form is gap 142, not built). A composite: is `mutating`; is bounded (every wait on the application
within `inv.remainingMs()` less a margin, never on the EDT, never on a thread the application forbids); takes every
nested answer as a **parameter** (a secret one through the credential join, so the executor announces it in
`_meta.secretParams`, A34); never answers a question it does not own (it returns, and the open question reaches the
prompt check as usual); and reports in the standard object below. It may keep publishing `prompt.waiting` for every
prompt (the sample's listener does): the recorder and a model then see the nested questions as events.

**Result convention** (`ai.skillroy.testjava.protocol.CompositeResult`, Java 8; A86), a nested object so that it
never collides with a flat key (`lending.prompt.answer` already answers `answered: true`):

```json
{ "composite": {
    "branch": "password",                 // [A-Za-z][A-Za-z0-9_.-]{0,63}, from the set the primitive documents
    "answered": [                         // every question the composite answered, in order, at most 32
      { "question": {"topic": "input.requested", "id": "induction-check"?, "text": "…"?, "seq": 412?},
        "by": "password",                 // the parameter that answered it: its name, never its value
        "choice": "Yes"?,                 // a button label; allowed only when `by` is not a secret parameter
        "inputId": "lending-18"? } ] },
  "inputId": "lending-18"?, "inputIds": ["lending-17", "lending-18"]?, "consumed": "CONSUMED"? }
```

`question.seq` is required when the module published an event for that question (the prompt check reconciles by
it), absent otherwise. `CompositeResult` has the key constants, `MAX_ANSWERED = 32`, a Java 8 builder
(`branch(...)`, `answered(topic, id, text, seq, by, choice, inputId)`, `build()`),
`static List<String> problems(Object value, Set<String> parameterNames, Set<String> secretParameterNames)` (the
violations: `composite` not an object, `branch` missing or malformed, `answered` not a list or over 32, an entry
without `by` or whose `by` names no parameter, a `choice` on a secret parameter, a `seq` that is not a positive
integer) and `static List<Long> answeredSeqs(Object value)`. A layer calls `problems` from its module tests; the
executor calls it for every result that has a `composite` key: **any violation is `ERROR / ENVIRONMENT`** naming it.

**Executor check 1: nested prompts the composite answered are expected** (`SC/CallRunner.checkPrompts`, `:859-900`).
A prompt event whose `seq` is in `answeredSeqs(value)` and in `(seq.before, seq.after]` of the call is expected and
logged `prompt {step, event, expected: true, answeredBy: <primitive>}`; `handledUpTo` (`:602-617`) is unchanged, so a
composite cannot hide a prompt it did not list, and a listed `seq` outside the call's window is ignored (and reported
in the `prompt` record as `ignoredSeqs`).

**Executor check 2: a result never carries a secret parameter's value** (every call with `secretParams`, not only
composites; D585). After the `call` record is written (masked, as always) and before anything else of 7.7, the
executor walks the result's value, its warnings' messages and data: a string scalar **equal** to a secret parameter's
value, or **containing** one whose length is at least the run mask's `minLengthInText` (the A25 rule, B19,
`SecretMask.minLengthInText()`, default 8; equality only for shorter secrets, since the sample's 4-digit passwords
occur inside barcodes), fails the step **`ERROR / ENVIRONMENT`** ("lending.signOn returned the value of its secret
parameter 'password'"; the detail never carries the value). The walk is bounded: at most 10,000 scalars and depth 32,
beyond which the check fails closed with "result too large to check for secrets". No base primitive echoes a typed
value (`swing.type` answers a count, `web.dom.fill` likewise), so nothing built changes verdict. **Behaviour change**:
a layer primitive that echoes a secret parameter's value fails its step from Phase 8 on; that is the point.

### 7.6 Rung ladders (gap 22; D588, D589, D590, D591)

**Declaration (controller).** `record Rung(String name, String capability)` in `…scenario.pack` (`name` matches
`RungResult.NAME = [A-Z][A-Z0-9_]{0,31}`; `capability` null or a capability id of either side, an agent id or a pack
capability of §9). `PlannedCall.rungs` is the ordered ladder, **best first**, 2..8 entries; `minRung` is the rung the
step requires (7.3). The plan shows both (7.3). Standard names for devices (`RungResult.PHYSICAL`: through the
device's own path, a simulator or the application's driver code, where the real device hands data over;
`RungResult.SEMANTIC`: a shortcut into the application, design §3.7's name); a pack may name others.

**The primitive chooses and reports** (A86, `ai.skillroy.testjava.protocol.RungResult`: keys `rung`,
`requestedRung`, `fidelity` (`FULL` | `DEGRADED`), `reason`, `minRung`; a Java 8 builder `of(rung, requestedRung,
fidelity, reason)`). The agent side knows at the moment of acting whether the physical path is there. A ladder
primitive: reports `rung` always (required for a ladder call); accepts an optional `minRung` parameter, **refuses to
act below it** (`CAPABILITY_UNAVAILABLE` with `data {rung, minRung, capability?}` naming the first unavailable rung's
capability) so that a required rung never acts through a shortcut first, and **echoes `minRung` in its result** when
it received one (the controller fails closed without the echo: §2.1, a 0.1.0 agent ignores unknown
parameters); it does not retry below the minimum on a transient failure of a permitted rung (the rung's own error is
the answer). The verb decides whether to forward `minRung` (`ctx.minRung()`, or its first rung under `strict`); the
plan shows the argument.

**The executor enforces** (`SC/CallRunner`, a call with `rungs`): result `rung` missing -> `FAIL / ASSERTION`
("deviceScan: device.act reported no rung; its ladder is [PHYSICAL, SEMANTIC]"); `rung` not in the ladder -> `FAIL /
ASSERTION` ("… reported rung X, not one of [..]"); `rung` after `minRung` in the ladder -> `FAIL / ASSERTION` ("… acted
on rung SEMANTIC below the required PHYSICAL"); `minRung` among the call's arguments and the result's `minRung` echo
absent or different -> `FAIL / ASSERTION` ("… did not apply minRung PHYSICAL"); `rung` not the first -> one `fidelity`
record `DEGRADED` (`requestedRung` = the first rung, `rung`, `reason` from the result or "a lower rung of the ladder")
and a `degradedFidelity` line `"s4: deviceScan fell back from PHYSICAL to SEMANTIC (…)"`, the run `DEGRADED` at best,
**de-duplicated** with a `fidelity: DEGRADED` the primitive reported itself (`noteFidelity`, `:520-534`, skips a call
the ladder check already recorded; a primitive reporting `DEGRADED` on the first rung is recorded by `noteFidelity` as
today). Every ladder call writes `rung {step, index, primitive, rungs: [names], minRung?, rung, fidelity, ok}`. Cleanup
calls: logged only. Sessions: a verb's ladder is enforced; raw calls carry none (7.3).

**At run start** (`SC/PlanExecutor.checkRequires`, `:1084-1098`, after `unmet` is empty and after §9.1's merge of pack
capabilities, §9): for every step call with `minRung`, at least one rung from the first to `minRung` inclusive must
have no capability or one whose state is `OK` in the merged matrix; otherwise `ERROR / PRECONDITION_FAILED` naming the
step, the rung and the capability's reason ("step s3 (deviceScan) needs rung PHYSICAL or better: lending.scanner.physical
is UNAVAILABLE (scanner driver not enabled …)"), **before bind and before any side effect**, with one record
`rungs {step, index, primitive, minRung, ok, states: [{name, capability, state, reason}]}` per such call. Cleanup
calls are not pre-checked.

**Devices (A85).** `DeviceAdapter` gains `default List<String> rungs(String action) { return
Collections.emptyList(); }`; `device.list` (`CORE/CorePrimitives.java:426-444`) adds `rungs: {<action>: [..]}` to a
device's entry only when some action has a non-empty ladder (a throwing `rungs` is logged and read as empty); the base
fact root `devices` mirrors it (`devices[id=scanner].rungs.scan[0]`); a device action with a ladder reports by the
convention above. The printer's entry is unchanged.

### 7.7 Order of the executor's result checks, bounds, what changes for existing users

`CallRunner.executeCall` after a successful call, in this fixed order (D592): the `call` record (masked) ->
**secret-echo check** (7.5) -> **warnings** (7.1, with `INPUT_QUEUED` of 7.2) -> **composite shape** (`problems`,
7.5) -> **rung checks** (7.6) -> `noteFidelity` (de-duplicated) -> `checkFired` / `checkSettled` / `checkWaitFlag`
(unchanged) -> **sync** (correlated, 7.4) -> capture -> prompt check (with reconciliation, 7.5). The first failure
wins; every record of an earlier stage is written before a later stage aborts.

| Where | Bound |
|---|---|
| `Invocation.warn` | 16 per call, message 500 chars, data 2048 chars; non-blocking |
| late acknowledgements | pending table 256 entries per module; cleared on detach; one publish per input |
| composite | `answered` 32; `branch` 64 chars; waits within `remainingMs() - 250` |
| secret-echo walk | 10,000 scalars, depth 32; beyond: fail closed |
| ladder | 2..8 rungs, names 32 chars; `accept` 32 codes per step |
| run-start rung pre-check | one matrix lookup per rung; no agent call |

**For existing users, nothing changes** unless: a module emits a warning (then a step must accept it); a pack sets
`ackByInputId` (then its module must return `inputId`); a primitive echoes a secret parameter (then its step errors);
a pack names a verb `accept` or `minRung`; a scenario sets `ui.strict` and uses a ladder verb. `rule.check`'s
`warnings: [string]` in its value (`agent/skillroy-agent-byteman/.../Engine.java:254`) stays information: the channel
is the envelope, never a value key (D576).

**As built (Phase 8, ES), 2026-10-19.** Where §7 was silent or the build chose among readings (the report's
`D-ES-n`):

- **7.1 wire (D-ES-1).** `CallWarning` also has `NEVER_ACCEPTABLE` (the two reader codes), `isValidCode`,
  `isPackCodeShape` and `tooMany(n)`. Data that cannot be written as JSON becomes `{"truncated": true}` like an
  oversized one; `fromJson` also turns an element with a non-string `message` or a non-object `data` into
  `INVALID_WARNING`; `readAll` of a value that is not a list gives one `INVALID_WARNING`; a `TOO_MANY_WARNINGS`
  element beyond the 16th adds its own `dropped`, so a folded list folds to itself (the 7-argument `RpcResult`
  folds the same way).
- **7.1 agent (D-ES-3).** The agent's log has no FINE level: a `warn` after the call returned is dropped and logged
  once per call at INFO, naming the code and the primitive, never the message or data. The dispatcher's margin before
  redaction is 4096 characters; data whose text exceeds four times 2048 plus that margin is `{"truncated": true}`
  before redaction.
- **7.1 reports (D-ES-9, D-ES-10, D-ES-11).** A cleanup call's warnings and rungs are records only (`cleanup: true`),
  never lines of `RunResult.warnings`. The run-end record, the journal's step record and the MCP scenario-run reply
  carry `warnings` only when non-empty; `run --json` always carries `warnings` (an empty list, as
  `degradedFidelity`), and the text output prints the `warning:` lines for every status. Telemetry: the `warning`
  event carries `skillroy.warning.code` and `skillroy.accepted` (both kept by `minimal`); `rung` and `rungs` reuse
  `skillroy.requested`, `skillroy.actual` and `skillroy.ok`.
- **7.2 (D-ES-2).** `InputResult.inputId(value)` is null also for a string that does not match `INPUT_ID_PATTERN` (a
  malformed id counts as none, the fail-closed path of 7.4); extra constants `ACCEPTED`, `INPUT_IDS`, `TAKEN`,
  `STATE_CONSUMED`, `STATE_QUEUED`, `VETOED`, `NOT_VISIBLE` and a reader `queued(value)`. The sample refuses a
  caller's malformed `inputId` with `INVALID_PARAMS {param: inputId, reason: malformed}`, and reports caveats only
  for an input the desk consumed within the call (`ANSWERED_NESTED` when the desk took it under another prompt line
  than the one it showed at the post).
- **7.3 (D-ES-8).** Every verb that compiles to no call refuses both modifiers, not only the four named; codes a verb
  pre-accepts (`withAccept`) are checked at compile time like a step's ("pre-accepts …"). The reader's refusal below
  7 reads "call P: 'accept' needs a version 7 plan" (after the reader's "invalid plan: " prefix).
- **7.4 (D-ES-14).** A `SYNC_TIMEOUT` names the match as JSON (`matching {"inputId":"lending-42"}`), the form the
  existing `EVENT` wait message uses. The sample's pending table, when full, evicts the oldest entry and releases its
  waiter as not taken (one acknowledgement per input still holds: an evicted input is never acknowledged late).
- **7.5 (D-ES-5, D-ES-7, D-ES-13).** The secret-echo check compares the text of every scalar leaf (numbers too) with
  every leaf of a structured secret; its detail key is `parameter` (`parameters` for the too-large failure), never
  `secretParam`, which the run mask would hide as a secret-looking key. Listed `seq`s outside the window are one
  `prompt {step, primitive, ignoredSeqs}` record, apart from the per-event records. A composite that runs out of
  time is the agent's `TIMEOUT` error; the sample's composites list a `question.seq` for `input.requested` from the
  listener's last request.
- **7.6 (D-ES-4, D-ES-6, D-ES-12).** `RungResult.of` returns a mutable map, so a primitive adds its own keys; readers
  `rung(value)`, `minRung(value)`, `isName`. A `Rung` capability is null or a non-blank id of at most 128 characters
  without whitespace; `Rung.check` is shared by `PlannedCall` and `Plan.Call`. The executor reads a forwarded
  `minRung` at the call's top level or in `args.minRung` (where `device.act` carries an action's arguments). A device
  action has no `Invocation`: the sample's scanner raises no warning, waits a fixed 5 s, publishes a late
  acknowledgement without a correlation id, refuses a `minRung` outside its ladder with `INVALID_PARAMS`, and
  reports a malformed frame with only its `length` (never the barcode).

---

## 8. Expectations: list filters, captured facts, matchers, the events root, list-valued matches, plan version 7

What this section is for, in one paragraph. The expectation language of P1 §8.4 reads one element of a list, compares one
value with one matcher, and sees events only as the thing a step waits for. The second domain's wave (gaps 108, 20, 118,
21, 120) asks for the four things that language cannot say: *how many* elements match ("exactly two lines for this item",
"no stray sign-off since the RETURN key"), *since when* (a count captured at a step and a delta later), *any of / some
element where* (a slip that names the item or its code; a deposit line with these three fields), and the agent's *events
as a fact* (a regex on an event's source, a value inside a JSON string, "this must not have happened"). Phase 8 answers
with six additive pieces that compose: a list form of the filter whose result the existing `size` matcher counts (§8.3),
a verb that captures facts into a slot (§8.7), two matchers (§8.4), a `[json]` path segment (§8.5), a base fact root
`events` over the agent's ring for the run's window (§8.6), and `{has: x}` in an event wait through one shared Java 8 rule
(§8.9). Negation is an expectation on a count, not a new wait (§8.8). Every piece is controller-side except the last,
which touches the agent's ring under its lock and is therefore a text comparison and nothing more. All of it rides on one
`plan.json` version bump, **7**, owned here and landed first (§8.2), after which the reader refuses what it does not know
in a step or a call, which it never did before.

### 8.1 Decision and shape

| Piece | Gap | Where it lives | Wire | Plan |
|---|---|---|---|---|
| `[*field=value]`, `[*field~regex]` (a list of every match); filter fields as dotted sub-paths | 108, 21 | `S/ExpectationPath.java` | none | needs 7 |
| `size` with a nested matcher (built; documented) | 108 | `S/Matchers.java` (doc) | none | none |
| `anyOf: [m…]`, `containsWhere: {field: m…}`; one regex budget per evaluation | 118 | `S/Matchers.java` | none | needs 7 |
| `[json]` path segment | 21 | `S/ExpectationPath.java` | none | needs 7 |
| base fact root `events` | 21 | `S/BaseFacts.java`, `S/PlanExecutor.java`, `S/ExecutorSession.java` | `evt.query` as it stands | none |
| `captureFacts` verb; step member `facts` | 20 | `S/BaseSteps.java`, `S/PlanCompiler.java`, `S/Plan.java`, `S/PlanExecutor.java`, `S/ExecutorSession.java`, `S/ScenarioSchema.java` | none | needs 7 |
| `{has: scalar}` in `match`; `PayloadMatch`; `waitFor` validated | 120 | `agent/skillroy-protocol` (new `PayloadMatch`), `agent-core` (`RingEventBus`, `CorePrimitives`), `S/AwaitRules.java`, `S/BaseSteps.java`, `S/PlanCompiler.java`, `S/WebRun.java`, nine fakes | additive semantics of an existing parameter; `INVALID_PARAMS` for a bad `match` | needs 7 |
| plan version 7; strict step and call keys | all | `S/Plan.java` | none | **is** 7 |

No new capability id, topic, error code, agent option or SPI (D594). The protocol stays `0.2` (§2.1, D299);
`{has: …}` sent to a 0.1.0 agent never matches and ends in `SYNC_TIMEOUT`, the documented unsupported mix (guide §4.4),
and it loosens no protection, so the echo rule of §2.1 does not apply (D606). Nothing changes for a
scenario that uses none of the new syntax, except the plan's version number and what §8.11 lists.

### 8.2 `plan.json` version 7 (D595, D596; §2.2)

`Plan.VERSION = 7`; the compiler always writes 7 (guide §4.4's rule; P7 §8.2's precedent); the reader accepts 1 to 7 and
its refusal reads "planVersion N is not supported (expected 1, 2, 3, 4, 5, 6 or 7)". A version 6 plan is read as version 7
without the Phase 8 content. No new top-level key (`TOP_KEYS_V6` serves 7; the class doc `S/Plan.java:60-91` gains the
version 7 paragraph). The reader names each Phase 8 item it refuses below 7, in the Phase 7 style:

| Item | Refusal below 7 (message) | Where the reader sees it |
|---|---|---|
| a list filter `[*…]`, a filter field holding `.`, a `[json]` segment, in any expectation path or `facts` path | "expectation `<path>`: a list filter / a dotted filter field / `[json]` needs a version 7 plan" | `ExpectationPath.minPlanVersion()` (7 when any of the three is present, else 1) in `PlanReader.expectation` |
| `anyOf`, `containsWhere` anywhere inside a matcher (an expectation's, a row check's `rows`, column or aggregate matcher) | "matcher `anyOf` needs a version 7 plan" | `Matchers.minPlanVersion(Object normalised)` (recursive through `not`, `size`, `anyOf`, `containsWhere`) |
| an operator object (`{has: …}`) in a call's `await.match` or in the `match` argument of a `sync.await` call | "call `<primitive>` await: `{has}` needs a version 7 plan" | `PlanReader.await` and `PlanReader.call` (the `sync.await` args) through `PayloadMatch.problem` and `PayloadMatch.usesOperator` |
| a step's `facts` member (the verb `captureFacts`) | "the verb 'captureFacts' needs a version 7 plan" | `PlanReader` step loop (`S/Plan.java:960-981`), as `PHASE7_VERBS` is gated |

**Strict step and call keys from version 7 on (D596).** Today the reader reads named members of a step and of a call and
ignores the rest (`S/Plan.java:960-981`, `:1364-1424`): an unknown key is silently dropped, which is why a 0.1.0 reader could
not have been trusted with a Phase 8 plan at all. From 7 on: a step object may hold exactly `id, verb, source, calls,
expect` and optionally `facts` (§8.7) (`Plan.STEP_KEYS_V7`; a YAML step modifier of §7 compiles into the step's calls,
§2.2, so no step key is reserved for §7; should §7 need one, it adds it to this set in its own hunk); a call
object may hold exactly `primitive, args, sync, timeoutMs, await, handlesPrompt, capture, guard` and the §7 keys
`accept`, `rungs`, `minRung`, whose readers are §7's and whose names this skeleton reserves (`Plan.CALL_KEYS_V7`). Any
other key is "step s3: unknown key 'x'" / "call swing.click: unknown key 'x'" (`ScenarioException`, as the top-level check
at `:929`). Below 7 the reader keeps today's leniency (a plan an older compiler wrote is read as before). Until §7's
package lands, a reserved key passes the key check and is read by nobody; no committed plan carries one, so the window is
harmless (§2.5). `Plan.Step` and `Plan.Call` keep every existing constructor (§2.3; the five-argument `Step`
constructor, the three older `Call` constructors `S/Plan.java:518-536`).

**Regeneration (the skeleton commit, then step R; D626, D641).** `Plan.fromJson` builds every plan it reads with
`VERSION` (`S/Plan.java:1057`) and `hash()` is over `toJson()` (`:845-847`), so from the commit that sets `Plan.VERSION
= 7` every version 6 plan reads back and hashes as a version 7 one: `PlanCompiler.isUpToDate`'s byte-for-byte
comparison (`PlanCompiler.java:251-257`, in `PlansUpToDateTest`, `BranchPlansUpToDateTest`),
`BindingsUpToDateTest:153`, `PlanCompilerTest.compilesTheCommittedPlanByteForByte`/`committedPlanRoundTrips`,
`SessionExportTest:143-147` and the web host's `PlansUpToDateTest:90` (`assertEquals(6, …)`) would all be red from
the skeleton on. The skeleton commit therefore regenerates the version alone, key-free and native: every committed
plan (19 files: `LS/plan/*` 11, `LS/scenarios/exported/plan/*` 2, `LS/variants/branch/plan/*` 3,
`samples/skillroy-sample-web-host-scenarios/plan/*` 2 under `-Pjcef`,
`controller/skillroy-scenario/src/test/resources/scenarios/toy-loan.plan.json` 1, by a one-off compile) is rewritten
with `planVersion: 7` (`-Dskillroy.plans.update=true` in `PlansUpToDateTest`, `BranchPlansUpToDateTest`, the web host's
`PlansUpToDateTest`); every committed bindings file's `planHash` (17 files) is rewritten (`-Dskillroy.bindings.rehash=true`;
`packVersion` stays 0.6.0); `resolveHash` is unchanged in content (`S/Plan.java:854-866`), so no bindings file is
`REBIND_NEEDED`. Step R (§2.8) regenerates them once more for the pack version, scenario A's `requires` and the new
plans. The version-pinned tests move to 7 in the skeleton: `ST/Phase3CompileTest.java:279-319` (the message "expected 1,
2, 3, 4, 5, 6 or 7"; `8` refused; the `v3`/`v2` replaces at `:292-310`; the version loop of
`theSecretFixtureRuleAppliesToVersion2AndLater`, `:316-319`, gains `"7"`, since with its replace a no-op it would
silently stop testing 2-6), `ST/Phase5CompileTest.java:82, 140-159` (its string replaces of `"planVersion": 6`),
`ST/Phase7CompileTest.java:71, 201-229` (five replaces that gate `inference run`, `images`, `visual` and `pointer`
below 6, and the `startsWith` at `:229`), `ST/WebCompileTest.java:409-427`, `ST/ResolutionCompileTest.java:69, 93-99`
(the replace, "7 is refused" becomes 8), the web host's `PlansUpToDateTest.java:90`: **every `"planVersion": 6` replace
becomes 7, every refused version 8**. `ContributedResolutionTest:138` and `InferenceContributorTest:277` call the full
`Plan` constructor and are unaffected (no `Plan` component is added).

**As built (Phase 8, EX, step 1).** The skeleton commit holds `Plan.VERSION = 7`, the message, `STEP_KEYS_V7` and
`CALL_KEYS_V7` (§7's three keys reserved), every gate of the table above, and the whole `facts` member (`FactCapture`,
`FactField`, `Step.facts` with the five-argument constructor kept, written after `expect` and read back as §8.7 says),
so that EX's later hunks leave `Plan.java` to ES and EP. Where the text above is silent: a `captureFacts` step without
`facts` is "step sN: captureFacts needs 'facts'"; a `facts` member on another verb below 7 is "step sN: 'facts' needs a
version 7 plan"; a check on a `captureFacts` step is refused with the same "captureFacts has no calls"; the gate on a
`sync.await` call's `args.match` uses the await message ("call sync.await await: {has} needs a version 7 plan"), and
that match is checked by `AwaitRules.checkMatch` at every version; the path gate names the item it finds
(`ExpectationPath.version7Item()`: "a list filter" for a filter field starting with `*`, "a dotted filter field" for one
holding a `.`) and the matcher gate the first of `anyOf` and `containsWhere` it finds (`Matchers.version7Matcher`,
through `not`, `size`, `anyOf` and `containsWhere`; an operand of `equals`, `contains` or `in` is a value); the reader's
`containsWhere` refusal in stored-row checks (§8.4) is wired already. Until §8.3-§8.5 land, `[*f=v]` in a version 7 plan
still parses as a single filter on the field `*f`, and `[json]`, `anyOf` and `containsWhere` are refused by the parsers
at every version (no committed plan holds one). The 19 plans and the 17 bindings hashes are regenerated in the same
commit, the version line and `planHash` alone.

### 8.3 List filters and dotted filter fields (gap 108, gap 21(b); D597, D598)

Grammar (additive to P1 §8.4; `S/ExpectationPath.java:26-37` doc, `:96-170` parser):

```
path     := root segment*
segment  := '.' name | '[' index ']' | '[' filter ']' | '[json]'
index    := digits{1,9} | 'first' | 'last'
filter   := ['*'] field ('=' value | '~' regex)          -- '*' = the list form
field    := name ('.' (name | index))*                   -- a dotted sub-path (new); spaces around it stripped
root     := [A-Za-z_][A-Za-z0-9_-]*                      -- unchanged
```

- **`[*field=value]` / `[*field~regex]` on a list** yields a **list** (possibly empty) of every element that is a map on
  which `field` resolves and matches, in list order. It always resolves when the current value is a list; on anything else
  (null, a map, a scalar) the path is **unresolved** ("`<walked>` is not a list (…)", the existing message), so `{size: 0}`
  never holds on a missing list (restrictive, D597). Value and regex rules are the single filter's exactly: numeric-aware
  equality (`Values.equal`), `$slot` references in the value (bound by `bind`, `:192-205`; regex values as text, equality
  values keep their type), the regex searched (`SafeRegex.find`) on scalar text only (a map or list field never matches),
  brackets balanced inside the regex (`regexEnd` `:173-189`), and **one `SafeRegex.Budget` per `resolve` call shared by every
  filter of the path** (`:208-212`, `:259-261`, unchanged; S24).
- **After a list filter the value is a list**, so what applies to a list applies: `[n]`, `[first]`, `[last]`, `.n`/`.first`/
  `.last` (the index meaning of `.field` on a list, `:223-229`, unchanged), another filter, single or list. `.field` with a
  name that is not an index does not resolve ("'qty' is not an index of … (a list of N)"). **No projection** in Phase 8: a
  map over elements would give `.field` two meanings on a list (D598; the alternatives rejected are in §17).
- **The single filter `[field=value]` keeps its meaning** (exactly one match, else unresolved) and gains the dotted field
  only.
- **Filter fields are dotted sub-paths** in both forms (`[*payload.source=Key.SIGN_OFF]`, `[lines.0.code=YRD-001]`): the
  field text is split at `.`; each part is a map key on a map, or a list index (`digits`, `first`, `last`) on a list, as
  `References.navigate` walks (`S/References.java:306-331`); an element on which the sub-path does not resolve (no such key,
  not a list, index out of range) does not match. **Behaviour change** only for a filter whose field is a literal key
  holding a dot: no committed scenario or plan has one (checked; `caps[id=swing.tree]` and `hooks[id=limit.applied]` carry
  the dot in the value), and the reader refuses such a field in a plan below 7, so a recompile is the only way to meet it.
- **Counting is the existing `size` matcher** (`S/Matchers.java:129-139`, `:306-325`: list or map size, string length; `n`
  a non-negative integer, a reference, or a nested matcher): `"loan.lines[*code=$item.code]": {size: 2}`, `{size: {gte: 1}}`,
  `{size: 0}`, `{size: {gt: $before.auths}}`. It is already built and tested (`ST/ExpectationsTest.java:194-195`); P1 §8.4 is
  annotated (A93). `exists` on a list filter holds for an empty list too (the path resolved): use `size`.
- **Parse errors** (`IllegalArgumentException` with the position, as today): `[*]`, `[*=x]`, `[* =x]` ("empty filter
  field"), `[*field]` ("'[*field]' is neither an index … nor a filter [field=value] / [*field=value] …"), `[*a~(]` (bad
  regex); messages name the list form beside the single one. A `*` anywhere but first in the brackets is part of the field
  name and fails as such.
- **Java.** `ExpectationPath.Filter(String field, boolean regex, Object value, boolean all)` with `List<String>
  fieldPath()` (the split, computed at parse); the sealed `Segment` gains `JsonText` (§8.5); `int minPlanVersion()`. The
  class is package-private: no API change. `References.collectPath` (`S/References.java:142-155`) collects references from
  the new form through the same `bind`.
- **Bounds.** The filtered list is bounded by the fact itself; the `expect` record's `actual` is cut as §8.10 says.
- **YAML.** Quote a key holding `[*…]` (the sample quotes every bracketed path already); a plain scalar never starts with
  `[` or `*`, and StrictYaml refuses aliases, so an unquoted `*` at the start of a value is an error, not a filter.
- **Sessions** (`skillroy_check`, `skillroy_session_check`, `ExecutorSession.fact`): the same parser, so the forms work
  there unchanged (`S/ExecutorSession.java:1226`, `:1319`).

Failure path: a path that does not resolve fails its matcher as today (only `exists`/`not` can hold); a regex that runs out
of budget leaves the path unresolved with the reason (`:273-275`); both end at the step timeout as `FAIL / ASSERTION`.

**As built (Phase 8, EX).** `ExpectationPath.Filter` keeps a three-argument constructor for the single form;
`fieldPath()` splits the field at `.` when asked, and the parser refuses an empty part ("empty part in the filter field
'a..b'"); `[*]` and `[* ]` are "empty filter field", `[*field]` names the list forms in its message (`[*field=value] /
[*field~regex]`); a filter field is read on each element as `References.navigate` walks (a missing key, an index out
of range or a scalar in the middle does not match; only a map element can match); the description in messages and in
`walked` is the segment as written (`[*code~^YRD]`).

### 8.4 Matchers: `size` (documented), `anyOf`, `containsWhere`, one regex budget (gap 118, gap 108; D599, D600)

`Matchers.NAMES` becomes `equals, contains, matches, in, gt, gte, lt, lte, size, exists, not, anyOf, containsWhere`
(the schema's alternatives follow, `S/ScenarioSchema.java:422-441`). Normal form and evaluation (`normalise` `:87-154`,
`evaluate` `:173-215`):

| Matcher | Normalise (compile and read) | Holds when | Fails with |
|---|---|---|---|
| `size: n \| matcher` | as built (`:129-139`) | the list or map size, or the string length, equals `n` or satisfies the nested matcher | "expected size N but was M", "size M: <inner reason>" |
| `anyOf: [m1, …]` | a list of 1 to 16 entries, each normalised (a scalar or list becomes `equals`; `capability` inside refused with the existing message); empty, more than 16, or not a list: "'anyOf' needs a list of 1 to 16 matchers, found …" | any alternative holds; on an unresolved path each alternative sees unresolved, so only `exists`/`not` forms can hold (consistent with `not`) | "none of the N alternatives holds: <r1>; <r2>; …" (each reason bounded by `Values.brief(…, 200)`, the whole by 2000 characters) |
| `containsWhere: {field: m, …}` | a map of 1 to 32 entries; each key a dotted relative path (`[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*`, the grammar of a `CaptureSpec` payload path), each value normalised; `capability` refused inside; empty, more than 32, a bad key or not a map: "'containsWhere' needs a mapping of 1 to 32 fields to matchers, found …" | the actual value is a list and some element is a map for which every field's matcher holds, where a field is navigated as `References.navigate` does and a field that does not resolve on an element is evaluated as unresolved (so `{exists: false}` says "has no such field") | on a non-list: "'containsWhere' needs a list, but the value is …"; else "no element of the list of N matches {…}" (the normal form, `Values.brief(…, 300)`) |

`{not: {containsWhere: …}}` is "no element where …"; `{not: {anyOf: […]}}` is "none of". `containsWhere` and `anyOf` nest
inside `not`, `size` and each other (depth bounded by the YAML's own depth; the plan reader's depth bound of 200 applies).

**In stored-row checks** (`rows`, column and aggregate matchers, `S/PlanExecutor.java:2081-2147`): `anyOf` works per cell;
`containsWhere` is **refused** at compile (`PlanCompiler.rowCheck`) and by the reader ("db check <table>: 'containsWhere'
needs a list, and a stored-row cell is a scalar") (D600, restrictive).

**One regex budget per top-level evaluation (D599).** Today every `matches` evaluation starts its own 1 s budget
(`S/Matchers.java:258`); inside `containsWhere` over the `events` root (up to 10,000 entries) that would allow 10,000 s.
`Matchers.evaluate(matcher, resolved, actual, problem)` keeps its signature and starts one `SafeRegex.Budget` that a
private overload threads through every nested evaluation (`not`, `size`, `anyOf`, `containsWhere`); a `matches` that runs
out fails with the existing reason, and every later `matches` of the same evaluation fails at once with "regex budget
spent". P1 §8.4's "one budget per matcher evaluation" then reads literally (A93). The path's own budget (§8.3) stays
separate: at most 2 s of regex per expectation per attempt.

**As built (Phase 8, EX).** `Matchers.evaluate(matcher, resolved, actual, problem)` starts one `SafeRegex.Budget` and
calls a package-private overload taking the budget (the tests pass a small one); a `matches` on a spent budget fails at
once with "'matches' /<regex>/: regex budget spent (one budget of 1000 ms for every regex of this matcher)". `anyOf`'s
reason cuts each alternative's reason to 200 characters and the whole to 2000, each with `...`; `containsWhere` passes
over an element that is not a map and names a bad field in its refusal ("the field 'a b' (a field is a dotted name such
as payload.source)"); the constants `MAX_ALTERNATIVES` 16 and `MAX_WHERE_FIELDS` 32. The compiler's stored-row refusal
reads "<where> db.<table>: 'containsWhere' needs a list, and a stored-row cell is a scalar" for a column, a `rows` or an
aggregate matcher (`PlanCompiler.noContainsWhere`).

### 8.5 The `[json]` path segment (gap 21(c); D601)

`[json]` parses the current value, which must be a string, as JSON (`ai.skillroy.testjava.protocol.Json.parse`: depth at
most 200, `Json.MAX_DEPTH`) and continues with the result: `events[*topic=input.observed].last.payload.value[json].qty`.
Bounds: a string longer than 1 Mi characters (`SafeRegex.MAX_SUBJECT`) is refused before parsing. Unresolved, with the
reason, when the value is not a string ("`<walked>` is not a string (…)"), is too long ("… is longer than 1048576
characters"), or is not JSON ("… is not JSON: <JsonException message>"). Several `[json]` segments in one path are allowed
(each bounded). A plain regex on the text (`[*payload.value~"qty":2]`) works without it. The segment is a free token today
(`[json]` is "neither an index nor a filter", `:132-135`), so an old reader refuses it; the version 7 gate names it (§8.2).
Not in Phase 8: a `json: matcher` matcher form (it cannot be followed by segments; D601).

**As built (Phase 8, EX).** The segment is `ExpectationPath.JsonText`; `[ json ]` is the segment too (an index's spaces
are stripped as before). The reasons read "<walked> is not a string (…)", "<walked> is longer than 1048576 characters"
and "<walked> is not JSON: <message>"; a number is what `Json.parse` gives (an integer a `Long`), compared numerically by
the matchers as any value.

### 8.6 The base fact root `events` (gap 21(a); D602, D603)

`BaseFacts.ROOTS` becomes `app, ui, dialogs, caps, devices, web, hooks, events` (eight; `ST/WebEvidenceTest.java:143`
pins the list). A pack root named `events` hides it, as `web`, `hooks` and `caps` are hidden (`S/PlanExecutor.java:312-326`,
`S/PlanCompiler.java:347-357`; map I §2.6), so no layer's pack breaks.

**Value.** A list, oldest first, of `{seq, ts, topic, source, correlationId, payload}` (the `Event` JSON without
`traceparent`) for every event of the agent's bus with `seq > since`, where `since` is the run's start mark
(`stepsSinceSeq`, `S/PlanExecutor.java:1608`: a bind-time verifier's events and an earlier run's never appear) and, in a
session, the session's own start mark (`runner.lastActionSeq` at construction, as `hooks`, `S/ExecutorSession.java:218-222`);
`BaseFacts.all()`'s own provider reads from 0, as its `hooks` does. Payloads are as the agent published them (§5.1's masking at
publish applies here unchanged: this root reads the ring and never unmasks; D603 records the
overlap).

**Read** (`BaseFacts.events(LongSupplier since)`): `evt.query {since: cursor, limit: 1000}` (no `topics`, so a page's
sequence numbers are contiguous), paged oldest first, the cursor moving to each full page's last `seq`, at most
`EVENTS_PAGES = 10` pages per fetch (10,000 events, the default `eventRing`; the `hooks` precedent D232), each call with
the root's 10 s `READ` options. **Restrictive on loss (D602):** the read **fails** (the expectation is `ERROR /
ENVIRONMENT` "fact 'events' could not be read: …", never a wrong pass of `{size: 0}`) when the first page's `oldestSeq >
since + 1` ("the ring evicted events N..M since the run's start; raise the agent option eventRing"), when a later page's
first `seq` is not the cursor + 1 (evicted while paging; the same message), or when the tenth page is full ("more than
10,000 events since the run's start: the events root cannot see them all; raise eventRing or shorten the scenario"). An
`evt.query` error or a lost agent fails the read as any root's does. The root is fetched lazily, once per attempt, only
for a scenario that names it (`S/PlanExecutor.java:2156-2170`'s cache), on the executor's thread; on the agent `evt.query`
runs on the control thread (no EDT, no application thread), one to ten calls per attempt.

**Filters on it are §8.3's**: `events[*topic=input.acked][*payload.source=Key.SIGN_OFF]`, `events[*source~^lending$]` (a
regex on the source: the row's first item), `events[*topic~^prompt\.]`, `events[*correlationId~^<scenarioId>/s5/]` (what
step `s5`'s own calls published: the agent stamps the call's correlation id `<scenarioId>/<stepId>/<n>` on every event
published on the call's thread during the call, `RingEventBus.java:101-105`, `S/PlanExecutor.java:1625-1626`; events of an
engine thread carry none). Single filters work too: `events[topic=loan.posted].payload.loanId`.

**Scope (D603).** The agent's bus only. A CDP path's own events (a separate sequence, `S/WebRun.java`) are not in the root
in Phase 8 (gap 146); a scenario on a non-agent web path asserts on them through the web verbs' `await` as before.

**As built (Phase 8, EX).** `BaseFacts.events(LongSupplier)` is public (§10's table) and `BaseFacts.all()` gives eight
providers. Each page is checked whole: besides the first page's `oldestSeq`, every event's `seq` must be the previous
one plus one (a gap inside a page fails as one between pages: "the ring evicted events N..M …", `M` at least `N`), an
empty page whose `oldestSeq` is past the cursor + 1 fails (a ring the character bound emptied), and an answer without
an `events` list fails ("evt.query answered no events list"). The read's failure is the expectation's fetch error, so
the step retries it until its timeout like any root's failed read and then ends `ERROR / ENVIRONMENT` "fact 'events'
could not be read: …" (§13.2 item 34's scenario uses a 3 s step timeout). In a run the provider is put into the run's
facts unless the pack has a root `events` (`PlanExecutor.packEventsRoot`), and a session's from its start mark, as
`hooks` is.

### 8.7 The verb `captureFacts` and the step member `facts` (gap 20; D604, D605)

```yaml
- captureFacts:
    slot: before                                   # [A-Za-z_][A-Za-z0-9_]*; not reserved; not in resolve; once per scenario
    fields:                                        # 1..32; names [A-Za-z0-9_]+
      scans:    { size: "events[*topic=input.acked][*payload.source=Barcode]" }   # the count, as size measures it
      total:    "loan.total"                       # a value at a path
```

- **Semantics.** A base verb (`BaseSteps.VERBS` gains it, nineteen; **reserved**, so a pack that declares a verb of that
  name is refused as `call` is, `PlanCompiler.RESERVED_VERBS`, §2.2, D604). The step has **no calls**. It runs
  where it stands: one fetch per named root (the attempt's cache), each path bound (`lookupPublic`, as expectations) and
  resolved once, **no retry** (a retry would capture a later state). A `{size: path}` field binds the size as the `size`
  matcher measures it (a list's or map's size, a string's length) as an integer; a plain path binds the value (frozen JSON).
  The slot is bound with `Bindings.withCaptured` (`S/Bindings.java:284-292`), appears under `captured` in the run's
  `bindings.json` and never in a committed bindings file (P2 §3.4's rule).
- **Compile.** The compiler special-cases the verb as it does `expect` (`S/PlanCompiler.java:861-868`): the argument map
  must hold exactly `slot` and `fields`; the slot passes the captured-slot rules (`PlanCompiler.capture` `:1003-1017`:
  reserved names, not in `resolve`, captured once, by an event or by facts); each field is a non-blank path string or
  `{size: path}` (exactly one key); each path parses, names a known root (the pack's or the base's; "unknown fact root"
  as `checks` says), and may hold `$slot` references in filter values; a path whose root is `db` is refused
  ("captureFacts reads fact roots; db.* is a stored-row check"). The slot's declaration is the existing `{name, kind:
  capture, step, fields}` (`Plan.CaptureDecl`); references to it obey the existing ordering (`checkRefs` `:1360-1385`:
  later steps and `verify`, declared fields only); its paths' references take part in `checkReferences` through
  `References.collectPath`. `ScenarioSchema.baseVerbs` gains `captureFacts: {slot: string, fields: object}` (required
  both).
- **plan.json (version 7).** `{"id":"s2","verb":"captureFacts","source":{"line":14},"calls":[],"expect":[],
  "facts":{"slot":"before","fields":{"scans":{"size":"events[*topic=input.acked][*payload.source=Barcode]"},
  "total":{"path":"loan.total"}}}}`: field keys sorted; each field exactly one of `path` / `size` (a string). The reader
  validates the member (keys `slot`, `fields`; the slot name; 1 to 32 field names; each field's one key and path grammar
  through `ExpectationPath.parse`), requires `calls` and `expect` empty on such a step ("captureFacts has no calls"),
  requires `facts` only on that verb ("'facts' is allowed on captureFacts only"), and refuses it below 7 (§8.2).
- **Java.** `Plan.Step` gains the sixth component `FactCapture facts` (null for every other verb; the five-argument
  constructor kept); `public record Plan.FactCapture(String slot, Map<String, FactField> fields)` (fields sorted, 1 to 32)
  and `public record Plan.FactField(String path, boolean size)`; `Plan.Step.toJson` writes `facts` after `expect` only
  when present (so no older plan changes but for its version).
- **Run.** `PlanExecutor.runStep` (`S/PlanExecutor.java:1617-1629`) performs the capture before `evaluate` (which has
  nothing to do on such a step). Failure paths: a path that does not resolve, or a `size` of a value that has none (a
  number, a boolean, null) → `FAIL / ASSERTION` "captureFacts before.scans: `<path>` did not resolve: <problem>" (the
  event capture's precedent `S/CallRunner.java:503-510`), detail `{slot, field, path, reason}`; a fact read failure →
  `ERROR / ENVIRONMENT` "fact 'x' could not be read: …"; an unknown root at run time → `ERROR / ENVIRONMENT` as an
  expectation's (`:1836-1839`); a reference that does not resolve → `ERROR / RESOLUTION_FAILED`.
- **Secrets (D605).** The record and `bindings.json` are written through the run's mask as every record is. With
  `RunOptions.expectationsOnMaskedValues()` (model- and client-written plans, P5 §12.2) a captured value the mask changes
  fails the step `FAIL / ASSERTION` with `Matchers.HOLDS_A_SECRET` ("the value holds a secret …"), so that a captured secret
  can never become a matcher operand or a call argument; without the option (a person's committed scenario) the value is
  captured as read, as an expectation is evaluated on it.
- **Record.** `run.jsonl` `capture {step, slot, value, source: "facts"}` (the event form keeps its shape; the new field
  tells them apart), mirrored to the observer as the existing `capture` scenario record (`RunLog.OBSERVED`).
- **Sessions (D605).** Carried, not dropped: `ExecutorSession.execute` rebuilds the compiled step with its `facts`
  (`S/ExecutorSession.java:601`, `:612`), `run` performs the capture before `expectNow` (one read, no retry, the same
  failures as a run's), the step's `captured` detail already carries the slot (`:727-783`), and `JournalExport` exports the
  verb as given (a session step exports verbatim), so an exported scenario replays it. The session's secret rule is the
  session's: `Matchers.maskedSecret` with the session mask always (as `evaluateCheck` does, `:1233-1241`).
- **Replay.** No model; the verb reads the agent only.
- **Timing.** A capture happens where the step stands; place it after a sync point (a `READY` step, a `waitFor`) when the
  count must include a late event (§8.8).

**As built (Phase 8, EX).** The compiler (`PlanCompiler.captureFacts`) answers "captureFacts: needs exactly 'slot' and
'fields', found keys […]", "captureFacts: 'fields' needs 1 to 32 fields, found N", "captureFacts <slot>.<field>: a field
is a path or {size: path}, found …", "captureFacts <slot>.<field>: unknown fact root 'x' in path 'p'; roots: …" and "…:
captureFacts reads fact roots; db.* is a stored-row check"; the slot rules are those of an event's capture with the
prefix "captureFacts:" (reserved, in `resolve`, "already captured by step sN"), and a field name `FactCapture`'s
("the field name 'a-b' must match [A-Za-z0-9_]+"). `BaseSteps.compile` answers no call for the verb (the compiler takes
it before any step library, as `expect`). The run and the session share `FactCapturing.capture` (package-private): the
step's own one read per root, the paths bound against the public values; a `size` of a value without one is
"captureFacts s.f: <path> did not resolve: 'size' needs a list, a map or a string, but the value is …"; a masked value's
failure carries it masked as `detail.actual`. A session's capture writes the same `capture` record to its log (its
observer) and binds the slot as a run does, so the step's `captured` detail carries it.

### 8.8 Negation and deltas: an expectation, not a wait (gap 21(d); D607)

No `expectNoEvent` verb. "Never during the run" is `{size: 0}` on the filtered `events` root; "none since step N" is a
count captured at N (§8.7) and `{size: $before.n}` later; "at least one new" is `{size: {gt: $before.n}}`. Exact while no
event of the window is lost: the root fails rather than under-count (§8.6). **Documented rule:** an expectation holds at
its first true attempt, so a negative check placed right after an action can pass before a late event is published; put
negative checks after a sync point or in `verify` (after the last step, before cleanup: the cleanup's own events, the
sample's `Key.SIGN_OFF` among them, are never in `verify`'s window, `S/PlanExecutor.java` class doc `:111-114`). The
alternative (an explicit negated wait with a window) is rejected in §17 (D607).

### 8.9 `{has: scalar}` in an event wait: `PayloadMatch` (gap 120; D606)

**One rule in `skillroy-protocol`** (Java 8, `--release 8`, no third-party class; the `TopicFilter` precedent "so that the
agent and the controller interpret … identically"):

```java
package ai.skillroy.testjava.protocol;
/** The payload rule of sync.await's match and of every controller-side wait on an event (P1 5, P8 8.9). */
public final class PayloadMatch {
    public static final String HAS = "has";
    /** True when every entry of match holds on payload; null match = any payload; a null payload = empty. */
    public static boolean matches(Map<String, Object> payload, Map<String, Object> match);
    /** Null when match is valid, else the reason ("'match.sources' must be a scalar or {has: scalar}, found …"). */
    public static String problem(Map<String, Object> match);
    /** True when any entry is an operator object (the plan reader's version gate). */
    public static boolean usesOperator(Map<String, Object> match);
}
```

Semantics per entry (all must hold): a scalar or null value is today's rule, `String.valueOf(expected)` equal to
`String.valueOf(payload.get(key))` (numbers by text: `2` and `2.0` stay unequal, as today); **`{has: <scalar>}`**: the
payload value is a list and some element's `String.valueOf` text equals the scalar's text (a map or list element never
equals; a non-list payload value never matches; no substring meaning; `{has: null}` is invalid); any other map or list
value, a `has` object with another key beside it, or an empty object is **invalid** (`problem`). Keys: the controller's
blank-key rule stays in `AwaitRules.checkMatch`. The word is `has`, not `contains`: the expectation matcher `contains`
compares numerically and does substring on scalars; the wait rule is text only (D606).

**Agent (host safety).** `RingEventBus.payloadMatches` (`agent-core/.../RingEventBus.java:256-268`) delegates to
`PayloadMatch.matches`: a linear text comparison over one list under the ring lock, as cheap as today's rule. **No regex,
no JSON parsing, no nested paths in agent-side matching** (they would run under the lock that application threads and
the EDT take on `publish`, `:112-136`). `SyncAwait.invoke` (`CorePrimitives.java:497-503`) validates `match` with
`PayloadMatch.problem` before waiting and answers `INVALID_PARAMS` with `data {param: "match", reason: "<problem>"}` (an
unknown operator no longer times out silently); the param description (`:474`) reads "payload entries that must hold:
a scalar (equal by text) or {has: scalar} (a list member equal by text)". `EventBus.await`'s javadoc
(`agent-api/.../EventBus.java:47-50`) says the same; the Java 8 signature does not change.

**Controller.** `AwaitRules.checkMatch` → blank keys, then `PayloadMatch.problem`; `AwaitRules.resolve` re-checks the
substituted match (a `$ref` that resolves to a list or map is `ResolutionException` reason `REFERENCE`, "await match
'sources' resolved to a list; a match value is a scalar or {has: scalar}", so `ERROR / RESOLUTION_FAILED`); `WebRun.matches`
(`S/WebRun.java:535-546`) → `PayloadMatch.matches`; **`waitFor`'s `match` is validated at compile** (`S/BaseSteps.java:1010-
1013` gains `PayloadMatch.problem` after `Values.normalise`; today a list or map there compiles and compares whole-list
text on the agent, map E finding 2) and so is the `match` of every `sync.await` call a pack verb emits (`PlanCompiler.toCall`
for that primitive; the lending `expectPrompt` is scalar); the plan reader validates `await.match` (as today) and a
`sync.await` call's `args.match` (new) and refuses an operator object below 7 (§8.2). `{has: $item.code}` is allowed
(resolved at run, re-checked). The nine copies of the rule (map E §120.2: `ST/FakeAgentConnection.java:335`, the MCP and
CLI `FakeAgent`s, `LST/HeadlessDesk.java:255`, the Swing, web and lending `FakeContext`/`FakeAgentContext`s) call
`PayloadMatch`.

**YAML.** `waitFor: {topic: input.requested, match: {state: DEPOSIT, validInputs: {has: Key.DEPOSIT}}}`; the same in every
`await:` (`call`, `click`, `type`, `press`, `pointer`, the web verbs). **plan.json:** the shape is unchanged
(`"match": {"state": "DEPOSIT", "validInputs": {"has": "Key.DEPOSIT"}}`, keys sorted).

**As built (Phase 8, EX, step 1).** `PayloadMatch.matches` lets an invalid entry hold never (a list or map value other
than a valid `{has: scalar}`; callers refuse it with `problem` first, so the whole-list text comparison of today ends with
the rule); a scalar is anything but a map, a collection or an array, so a set or array element never equals either;
`problem` quotes at most 100 characters of a key or of an object's key list.

**Behaviour change:** a hand-written `waitFor` whose `match` held a list or a map compiled and never matched (whole-list
text); it is now a compile error (none committed). A `sync.await` sent by a client (`skillroy call`, MCP) with an invalid
`match` is `INVALID_PARAMS` instead of `TIMEOUT`.

**As built (Phase 8, EX).** `AwaitRules.checkMatch` checks blank keys, then `PayloadMatch.problem`; `AwaitRules.resolve`
refuses a substituted match with "await match '<key>' resolved to a list; a match value is a scalar or {has: scalar}"
(or "await match is not valid: <problem>"), reason `REFERENCE`, nothing sent. The agent answers `INVALID_PARAMS` "invalid
match: <problem>" with `data {param: "match", reason: <problem>}`. `waitFor`'s check reads "waitFor: <problem>"; the
compiler checks the `match` of every `sync.await` call any verb emits, the base `waitFor`'s included ("verb 'v': call
sync.await: <problem>", `PlanCompiler.syncAwaitMatch`). The substituted arguments of a `sync.await` call are sent as
they are (`CallRunner` is §7's file), so a `waitFor` whose `{has: $x}` resolves to a list is refused by the agent's
check (`INVALID_PARAMS`) rather than by `AwaitRules.resolve`. The seven test fakes' rule and `WebRun.matches` call
`PayloadMatch.matches`; `AwaitSpec`'s Javadoc states the rule.

### 8.10 Threads, bounds, records

| Where | Bound |
|---|---|
| list filters, `containsWhere` | the fact's own size; one 1 s `SafeRegex.Budget` per path resolution (§8.3) and one per matcher evaluation (§8.4): at most 2 s of regex per expectation per attempt, per S24 |
| `[json]` | subject ≤ 1 Mi characters; depth ≤ 200 (`Json.MAX_DEPTH`) |
| `anyOf`, `containsWhere` | 16 alternatives, 32 fields; reasons cut (`Values.brief`) |
| `events` root | ≤ 10 `evt.query` calls × 1000 events per fetch, each under the 10 s `READ` options; fails on loss; the agent's control thread only |
| `captureFacts` | 1 to 32 fields; one fetch per root; no retry; no call to the target but the fact reads |
| `PayloadMatch` | linear in the match's entries and one list's length; under the ring lock on the agent, so text only |
| `actual` in `run.jsonl` (D608) | **Behaviour change:** the `expect` record's `actual` and a failure's `detail.actual` are written whole up to 200 elements per list at any depth; a longer list is cut to its first 200 and the record gains `actualTruncated: true` (`RunLog` writes it, where the depth bound `MAX_DEPTH = 200` lives, `S/RunLog.java:75`, `:206`). The verdict is unchanged; `failure/` evidence is unchanged. Today no root is cut (`ui`, `events` would write everything) |

Records: `capture {step, slot, value, source: "facts"}` (new form); `expect` unchanged in keys but for `actualTruncated`;
`sync` unchanged (`match` is logged as sent). No new record type from this section (`capture` is already observed; §7.1 adds `warning`, `rung` and `rungs` to `RunLog.OBSERVED`).

**As built (Phase 8, EX).** `RunLog.cutActual(fields)` cuts every list of `actual` to its first 200 elements at any depth
(up to the codec's depth bound) and inserts `actualTruncated: true` right after `actual`, answering the same map when
nothing is cut; `RunLog.write` applies it to every `expect` record (the observer's copy too), and the executor to an
expectation failure's detail, so `RunResult.detail.actual` is cut the same way.

### 8.11 Behaviour changes for existing users, in one list

1. `planVersion` 7 in every compiled plan; a 0.1.0 reader refuses a Phase 8 plan outright (§8.2).
2. From version 7, an unknown step or call key is refused (§8.2).
3. A filter field holding a dot is a sub-path, no longer a literal key (§8.3; none committed).
4. A `waitFor` or pack `sync.await` `match` with a list or map value is a compile error; an invalid `match` on the wire is
   `INVALID_PARAMS` (§8.9).
5. `anyOf`, `containsWhere`, `captureFacts`, `events` and `[json]` are new names: a pack root `events` hides the base root
   (no break); a pack verb `captureFacts` is refused (a break for a layer that has one; §2.2, Q45).
6. `actual` in `run.jsonl` cut at 200 list elements with `actualTruncated: true` (§8.10).
7. `BaseFacts.ROOTS` has eight entries; `Matchers.NAMES` thirteen; `BaseSteps.VERBS` nineteen (tests that pin them move).

---

## 9. The pack: pack capabilities, prompt values, pre-flight scope, application preconditions

Four things a domain pack could not say in 0.1.0: an environment fact only the pack knows (gap 106), that an
`on_prompt` value is wrong (gap 111), that a pre-flight check is a suite fact rather than a scenario fact (gap 112),
and that a scenario depends on an application setting (gap 24). This section adds two controller-side SPI members
with defaults (`DomainPack.capabilities()`, `PreflightCheck.scope()`), one value type (`PackCapability`), one enum
(`CheckScope`), one host-owned cache (`SuiteCache`), one helper that replaces four copies of the pre-flight loop
(`Preflight`), one no-I/O default method on `AgentConnection` (`agentSession()`), a narrower value domain for
`on_prompt.unexpected`, and one decision written into the guide. **No agent (Java 8) change, no wire change
(protocol 0.2, §2.1), no `plan.json` content of its own** (the `onPrompt` value check is a reader rule, not
a version; plan version 7 is §8's, §2.2). Nothing changes for a pack that declares no capability and keeps
every check `SCENARIO`-scoped, except the `on_prompt` refusal (§9.2, a **behaviour change** for authors) and the
`re-resolve` fix (§9.1.5).

### 9.1 Pack-contributed capabilities (gap 106)

#### 9.1.1 The SPI (D609)

```java
package ai.skillroy.testjava.scenario.pack;                                   // skillroy-scenario, Java 25

/** How often a pack's pre-flight check or capability is evaluated (P8 §9.1, §9.3). */
public enum CheckScope {
    /** Once per run: today's behaviour, the default. */
    SCENARIO,
    /** Once per suite, environment and agent start; the host's SuiteCache keeps the answer (§9.3). */
    SUITE
}

/** An environment fact the running pack contributes to the run's capability matrix (gap 106). */
public interface PackCapability {
    /** {@code <packId>.<name>}: the pack's id, a dot, then {@code [a-z0-9]+([.-][a-z0-9]+)*}; at most 128 chars. */
    String id();
    /** One line for the scenario schema, {@code skillroy caps} and the MCP catalogue; null (default) = none. */
    default String description() { return null; }
    /** SCENARIO (default): evaluated in every run; SUITE: once per suite, environment and agent start. */
    default CheckScope scope() { return CheckScope.SCENARIO; }
    /**
     * The state in this environment. Called on the host's thread, once per host (a run, a session open, a command);
     * read-only; bounded by its own reads (every ctx.data() query carries a timeout; the host adds none, as for a
     * PreflightCheck); never a model call, never a mutating primitive, never anything the step protocol would wait
     * on. The answer's id() must equal id(); null means UNAVAILABLE("no answer"). A reason is evidence every run
     * archives and a model may read through the caps root: name the setting, the table or the condition, never a
     * row value.
     * @throws Exception an environmental failure: the id becomes UNAVAILABLE("failed: <exception class>"), the
     *     exception's text going to the log and the run's record only (§9.1.3)
     */
    Capability evaluate(PreflightCheck.PreflightContext ctx) throws Exception;
}

// DomainPack (additive default; map I §2.6: every DomainPack implementation compiles unchanged):
/** The pack's capabilities (P8 §9.1); empty by default. Read once per host. */
default List<PackCapability> capabilities() { return List.of(); }
// DelegatingDomainPack: @Override public List<PackCapability> capabilities() { return base().capabilities(); }
// PackOverlayTest.theDelegatingPackForwardsEveryMember: case "capabilities" -> List.of()
```

`Capability` and `CapabilityState` are the wire types of `skillroy-protocol` (unchanged). `PreflightContext` is
unchanged: `agent()`, `data()` (the guarded read-only channel, routed by source: JDBC where `credentials.yaml` serves
the source, else the agent's `data.query`), `envId()`, `settings()` (the running pack's, so an overlay changes what
an evaluation reads without replacing it).

#### 9.1.2 Declaration rules: what is refused before anything is evaluated (D614)

Checked by the host on every pack, in this order; a violation is **`CapabilityMerge.Refused` → `ERROR /
ENVIRONMENT`** for the run (`SessionOpenException(ENVIRONMENT)` for a session, `Exit.FAILED` for a command), the
message naming the pack and the id; nothing is evaluated and nothing is sent:

1. `pack.capabilities()` returns null → treated as empty; throws a `RuntimeException` → refused `"pack <id>: its
   capabilities cannot be read: <e>"`.
2. More than **64** entries → refused `"pack <id> declares <n> capabilities; at most 64"`.
3. An entry whose `id()` is null, blank, not `pack.id() + "." + name` with `name` matching
   `[a-z0-9]+([.-][a-z0-9]+)*`, or longer than 128 characters → refused `"pack <id> declares the capability '<x>':
   a pack capability id is <packId>.<name>, name [a-z0-9]+([.-][a-z0-9]+)*"`. (An id starting with `inference.`
   cannot pass this rule, since the pack id `inference` is reserved, `DomainPacks.reservedId`; P2 §3.3 unchanged.)
4. The same id twice → refused `"pack <id> declares the capability '<x>' twice"`.
5. An id the agent's matrix (after step 0b) holds → refused `"capability '<x>' is declared by the agent and by pack
   <id>"`; an id a `CapabilityContributor` answered → refused `"capability '<x>' is contributed by <contributor> and
   declared by pack <id>"`. **Equality only**: the agent module of the same domain declares `<packId>.*` ids too
   (the sample's `lending.sql`, `lending.loan.authorisations`), so the namespace is shared and the rule is a conflict
   on the same id, never on a prefix.

#### 9.1.3 Evaluation: when, with what, and the failure path (D610, D611, D612)

- **When.** In every host that merges the matrix (§9.1.5), **after** the controller-side contributors' merge (P7 §8.5)
  and **before** `capabilities.json` is archived and `requires` is checked: for a run, inside `checkRequires` after
  `installHooks` (step 0b) and `mergeContributed`, so the order is agent matrix → step 0b → contributors → **pack** →
  archive → `requires`. The records' order (`run-start, hooks, capabilities, requires, …`) is unchanged.
- **All declared ids, always** (D612): not only those the plan names. `capabilities.json`, the `caps` root and
  `skillroy caps` are then the same for every scenario of a pack. The cost is bounded by the pack (64 ids, each a
  bounded read); a slow fact takes `SUITE` scope (§9.3).
- **With what.** The same `PreflightContext` the run's pre-flight gets: the run's data channel (`Run.this.data()`),
  the run's `envId`, the running pack's `settings()`, the agent. **The context carries no bind mode, no inference
  settings and no gateway**, so an answer cannot depend on the mode: this is why a pack capability is **honoured in
  `replay`** (D610), unlike a contributed `inference.*` id (P7 §8.5, D326: forced `UNAVAILABLE("replay")`). The D19
  ledger check still ends any `replay` run that made a model call `ERROR / ENVIRONMENT`.
- **Caching.** A `SUITE`-scoped capability is answered from the host's `SuiteCache` under the key of §9.3.2 (kind
  `capability`); the first run evaluates it; a connection without an agent session never caches. A `SCENARIO` one is
  evaluated in every run.
- **The answer.** `OK`, `DEGRADED(reason)` or `UNAVAILABLE(reason)` as the pack says, `strategy` kept; the matrix's
  rules then apply unchanged: `requires:` is met by `OK` or `DEGRADED` and unmet by `UNAVAILABLE`
  (`PRECONDITION_FAILED` naming `<id>: UNAVAILABLE (<reason>)`); a guard needs `OK` (anything else skips the check and
  the run is `DEGRADED` at best, P1 §8.4); a primitive never names a pack id (`CAPABILITY_UNAVAILABLE` is agent-side).
- **Failure path** (D611, differs from D367 on purpose): `evaluate` returns null → `UNAVAILABLE("no answer")`;
  returns a `Capability` whose `id()` differs → a declaration error, refused `"pack <id> answered '<y>' for its
  capability '<x>'"`; **throws any `Exception`, `LinkageError` or `StackOverflowError`** → the id becomes
  `UNAVAILABLE("failed: " + e.getClass().getSimpleName())` (the agent side's rule for the same case, S22: a driver's
  `SQLException` or a `DataException` can quote a value or the statement, and the reason reaches `capabilities.json`,
  the `caps` root, `skillroy caps` and `skillroy_catalogue` masked by known secret values only); the exception's
  `toString`, masked, goes to the controller's log at WARN and to the `capabilities` record's `pack[]` entry as
  `error` (cut at 500 characters): a person's evidence, never the matrix; the run goes on and `requires` or a guard
  decides; `AgentLostException` propagates (`AGENT_LOST`); `InterruptedException` re-interrupts the thread
  and ends the run `ERROR / ENVIRONMENT "interrupted"` without caching. Reason: unlike a contributor (a pure function
  of its context) an evaluation reads the environment and can fail for environmental reasons; a hiccup must degrade
  one expectation, not refuse every scenario.
- **Bounds.** A pack's evaluation runs on the host's thread with no host timeout, as a `PreflightCheck` does (P1
  §8.3): the data channel's per-query `timeout` is the bound; reasons are cut to 160 characters by `skillroy caps`'
  table only (the archive keeps them whole, masked).

#### 9.1.4 Where the answer shows (D613)

| Where | What |
|---|---|
| the merged `CapabilityMatrix` | the pack's entries with source **`pack:<packId>`** (`CapabilityMatrix.SOURCE_PACK_PREFIX = "pack:"`, beside `SOURCE_AGENT` and `SOURCE_CONTROLLER_PREFIX`; `with(entries, "pack:" + id)`, `source(id)`, `agentOnly()` drops them); the wire shape (`toJson`, `hash`) carries no source |
| `capabilities.json` | the pack's entries like every other (wire shape; masked) |
| the `capabilities` record of `run.jsonl` | `{hash, ids, file, agentHash, contributed}` **gains `pack`**: a list, sorted by id, of `{id, state, reason, strategy, scope: "scenario" \| "suite", cached: bool, error?}` (`reason`, `strategy` null when absent; `error` only on a failed evaluation: the exception's masked `toString`, cut at 500, §9.1.3); **absent when the pack declares no capability**, so a pack without them writes byte-identical records. No new record type (the sample's pinned sequences stay) |
| the session journal | `session-start.options.pack: [ids]` when non-empty, beside `contributed` |
| the base fact root `caps` | `caps[id=lending.returns.ceiling].source` = `pack:lending` (`BaseFacts.caps` is generic: no change) |
| `requires:` and guards | as any id; the compiler validates no id against anything (unchanged) |
| the scenario schema | `ScenarioSchema.requiresItem` appends `"; the pack contributes: <id> (<description>), …"` (the description omitted when null), so a planner can write `requires:` and guards with them |
| `skillroy caps` | a row per pack id with `SOURCE pack:<packId>` (table and `--json`); `skillroy_catalogue` the same through `ExecutorSession.capabilities()` (its `source` field is generic) |

#### 9.1.5 The hosts (D612, D623)

One public class carries the rules, so that no host drifts:

```java
package ai.skillroy.testjava.scenario;
/** Pack capabilities evaluated into a host's matrix (P8 §9.1). Stateless; thread-safe. */
public final class PackCapabilities {
    public static final int MAX_PER_PACK = 64;
    public static final int MAX_ID_LENGTH = 128;
    /** One evaluated id, as the capabilities record lists it. */
    public record Entry(String id, CapabilityState state, String reason, String strategy, CheckScope scope,
                        boolean cached) { public Map<String, Object> toJson(); }
    /** @param merged the matrix with the pack's entries added (source pack:<id>); @param entries sorted by id */
    public record Result(CapabilityMatrix merged, List<Entry> entries) {}
    /**
     * @param merged the agent's matrix after step 0b with the contributors' entries (CapabilityMerge.Result.merged)
     * @param cache null = nothing cached; @param agentSession null = nothing cached (§9.3.2)
     * @throws CapabilityMerge.Refused a declaration error of §9.1.2
     */
    public static Result evaluate(CapabilityMatrix merged, DomainPack pack, PreflightCheck.PreflightContext ctx,
                                  SuiteCache cache, String agentSession) throws CapabilityMerge.Refused,
            AgentLostException, InterruptedException;
}
```

| Host | Change |
|---|---|
| `PlanExecutor.mergeContributed` | after the contributors' merge: `PackCapabilities.evaluate(caps, pack, preflightContext(), options.suite(), agent.agentSession())`; the context is the one `runPreflight` builds (factored into a method); a `Refused` is logged as today (`capabilities {ok: false, error}`) and ends the run `ERROR / ENVIRONMENT`; `archiveCapabilities` writes `pack` when non-empty. **Behaviour change**: a pack with capabilities sends reads (`data.query`, or JDBC) before `requires`; P1 §8.5's rule holds (reads are allowed before the start mark; nothing mutating, no cleanup, no `recover`) |
| `ExecutorSession.open` | after its contributors' merge, with the session's `DataChannels`, `envId`, settings, `options.suite()`; `session.capabilities()` answers the merged matrix; `capabilities.json` written once, as today |
| `skillroy caps --pack` | evaluates the single loaded pack's capabilities with `Credentials.empty()` (every source through the agent's `data.query`), `envId` `BindOptions.DEFAULT_ENV` (`default`), the pack's settings, no cache; `--reprobe` unchanged (agent side only). A source served over JDBC only shows `UNAVAILABLE` with the channel's error there: `skillroy run`'s `capabilities.json` is the authoritative view for such an environment (gap 149) |
| `skillroy re-resolve` (the fix, D622) | **today checks `requires` against the agent-only matrix** (`ReResolveCommand.java:194-203`, no contributors: a scenario requiring `inference.sql` reads "missing" there). Now: after `target.client().capabilities()`, merge the contributors (`CapabilityMerge.merge` with the job's inference settings, `BindMode.RE_RESOLVE`, no host features, the pack) and evaluate the pack's capabilities (`DataChannels.of(client, credentials, pack.rowMasks())`, `settings.envId()`, `pack.settings()`, no cache), then `Preconditions.check(plan, merged, client)`; a `Refused` is `CliException(Exit.FAILED)` naming it. Step 0b stays absent there (gap 148) |
| MCP `skillroy_scenario_run` | through `PlanExecutor` with the server's cache (§9.3.3); `skillroy_catalogue` through the session's merged matrix |

#### 9.1.6 The namespace, stated once (D614; map I §11 item 7)

Agent-declared ids are lower-case dotted ids a module declares (D298); contributed ids start with `inference.`
(P2 §3.3, P7 §8.5); pack ids start with `<packId>.`. The agent module of a domain and its pack share the prefix, so
the only enforceable rule is equality: an id both declare refuses every run naming both. The guide advises a leaf the
module never declares and `skillroy caps --pack` (both sides in one table) as the check before CI.

### 9.2 `on_prompt.unexpected`: `fail` or `pack`, nothing else (gap 111; D615)

As specified in P1 §8.1 ("any other value selects the pack's policy") a scenario that writes
`on_prompt: {unexpected: failWithBundle}` loads and does the opposite of its author's intent. **Behaviour change**:

- **The loader** (`ScenarioLoader.Reader.onPrompt`): `unexpected` accepts, ignoring case and surrounding blanks,
  exactly `fail` (every unexpected prompt fails the scenario, `FAIL / ASSERTION`) or **`pack`** (the pack's
  `promptPolicy()`, the explicit spelling of the default); any other string → `ScenarioException` at the key's line:
  `'on_prompt.unexpected' must be fail or pack (or leave on_prompt out for the pack's policy), found "failWithBundle"`.
  The value is kept normalised in lower case (every committed plan holds `"fail"`: no committed plan changes). A
  non-string, a blank, and any other key under `on_prompt` are refused as today.
- **The plan reader** (`Plan.fromJson`, a reader rule beside §8.2's version gates): `onPrompt` may hold the key `unexpected` only,
  with the value `fail` or `pack`; anything else is refused `"onPrompt.unexpected must be fail or pack, found …"`
  whatever the plan version, so a hand-written or old `plan.json` cannot carry the silent meaning. No version bump.
- **The executor** (`PlanExecutor.init`): unchanged logic (`fail` → `PromptPolicy.FAIL`; `pack` or absent → the
  pack's policy, `FAIL` when the pack answers null); `ExecutorSession.configure` unchanged (always the pack's).
- **The schema**: `on_prompt.unexpected` = `{"enum": ["fail", "pack"]}` (`ScenarioSchema`, the planner's and the MCP
  server's copy).
- **Named pack policies** (`DomainPack.promptPolicies()`, the row's second option) are **not built** (Q52): nobody
  has asked for a second policy; the loader's message names the rule so that names can be added later without a
  break (an unknown name is already refused).
- Docs: P1 §8.1's example comment `# fail | <pack-defined policy>` becomes `# fail | pack (default: the pack's
  policy)` and its prose (A94); `Plan.java:107`, `PromptPolicy.java:29-30`, `DomainPack.java:55`,
  `ScenarioLoader.java:541-542`, guide §8.3, §8.6 (`:899`), design §3.2 (`:383`).

### 9.3 Pre-flight scope and the suite cache (gap 112)

As built, every `PreflightCheck` runs in every run (P1 §8.5 step 2, P2 §11.2 step 2); the guide (§8.1 `:765`, §8.3
`:815-817`) and the design (§3.9 `:665`, §6 `:935`) say "before the first scenario, one `environment` failure". The
code is right and the prose is corrected (§16.1: guide §8.1, §8.3, design §3.9, §6, P2 §11.2); the cost of a slow
check is answered by a scope.

#### 9.3.1 The scope (D616)

```java
// PreflightCheck (additive default): SCENARIO = today's behaviour, the restrictive default.
default CheckScope scope() { return CheckScope.SCENARIO; }
```

A `SUITE` check is evaluated once per **suite, environment and agent start** and its answer, **ok or the failure
reason**, is kept by the host's `SuiteCache` for the rest of the suite: a check that passed is not run again; a check
that failed fails every later scenario of the suite `ERROR / ENVIRONMENT "pre-flight check <id>: <reason> (checked
once for this suite)"` without being run again (that is what "one environment failure" means; a transient failure
therefore fails the suite: say so in the guide, keep `SCENARIO` for anything that may recover). A stale `OK` (the
environment breaks mid-suite) is caught where it bites, at bind or a step, as today.

#### 9.3.2 The cache and the agent session (D616, D617)

```java
package ai.skillroy.testjava.scenario;
/** The answers of SUITE-scoped pre-flight checks and pack capabilities for one suite (P8 §9.3). Thread-safe. */
public final class SuiteCache {
    public static final int DEFAULT_MAX_ENTRIES = 1000;
    public static SuiteCache create();                      // DEFAULT_MAX_ENTRIES
    public static SuiteCache create(int maxEntries);        // 1..100000, else IllegalArgumentException
    public int size();
    public void clear();                                    // forgets everything: every SUITE item runs again
    // package-private: record Key(String kind /* "preflight" | "capability" */, String packId, String packVersion,
    //                            String id, String envId, String agentSession); Object get(Key); void put(Key, Object)
}
```

- **Key** = `(kind, pack id, pack version, check or capability id, envId, agent session)`. The agent session is the id
  the agent minted when it started (`Protocol.KEY_SESSION_ID`, the `/ping` reply's `sessionId`; not a secret): a new
  agent start, or another target, is another key, so the check runs again ("re-run when the agent restarts").
- **Bounded**: beyond `maxEntries` nothing more is kept (every further `SUITE` item runs per scenario and records
  `cached: false`); no expiry within a suite (gap 150); never written to disk; lives with its host.
- **The agent session seam** (D617): a default method on `AgentConnection`, **no I/O**:

```java
/** The agent start this connection is bound to (the sessionId its /ping reply carries, as last verified); null
 *  when unknown (the default). Never I/O. A SUITE-scoped item is cached under it; null is never cached for. */
default String agentSession() { return null; }
```
  `AgentClient` answers its verified `sessionId` (set by the first `/ping` of the connect handshake, or given by the
  endpoint; latched lost on a change, so one client = one agent start). Wrappers forward it: `PlanExecutor.FactConnection`,
  `ExecutorSession`'s wrapper, the sample's `Recording` and `DeskConnection`. Fakes answer a settable one:
  `FakeAgentConnection.session(String)`, `MiniAgent`, the CLI's and the MCP module's `FakeAgent`, `FakeDesk`;
  `HeadlessDesk` mints a fresh id per `launch` (and its `ping()` reports it). A connection that answers null is
  **never cached for**: the restrictive default, and why no extra `/ping` is sent (the alternative, Q55).

#### 9.3.3 One helper, five hosts (D618, D619)

```java
package ai.skillroy.testjava.scenario;
/** The pack's pre-flight, one implementation for every host (P8 §9.3). Stateless. */
public final class Preflight {
    /** One check's outcome as recorded: {id, ok, reason} and, for a SUITE check only, {scope: "suite", cached}. */
    public record Record(String id, boolean ok, String reason, CheckScope scope, boolean cached) {
        public Map<String, Object> fields();      // id, ok, reason, then scope and cached for SUITE
    }
    /** @param records in order, up to and including the first failure
     *  @param failure "pre-flight check <id>: <reason>" (+ " (checked once for this suite)" when answered from the
     *      cache), "pack <id>: its pre-flight checks cannot be read: <e>", or null when every check passed */
    public record Outcome(List<Record> records, String failure) {}
    public static Outcome run(DomainPack pack, PreflightCheck.PreflightContext ctx, SuiteCache cache,
                              String agentSession) throws AgentLostException;
}
```

Rules of `run`: `pack.preflight()` null = none, a `RuntimeException` there = the "cannot be read" failure with no
record; checks in order, **stopping at the first failure** (`PlanExecutorTest.preflightFailureIsEnvironment` pins
`[desk, printer]`); per check: `SUITE` with a cache and a session → the cached answer when present (`cached: true`),
else run and keep both answers; `SCENARIO`, or no cache, or no session → run (`cached: false`, the record without
`scope`/`cached` for `SCENARIO`); an exception → reason `"failed: " + e` (kept like a failure);
`InterruptedException` → re-interrupt, reason `"interrupted"`, never kept; `AgentLostException` propagates, never
kept. A check never runs under a lock; two racing scenarios may both run it (the last answer wins; harmless). No
timed wait anywhere.

| Host | Today | Now |
|---|---|---|
| `PlanExecutor.runPreflight` | its own loop | `Preflight.run(pack, ctx, options.suite(), agent.agentSession())`; one `preflight` record per `Record` (`fields()`); a failure ends the run `ERROR / ENVIRONMENT` with the message and detail `{preflight: id, reason, cached?}` |
| `ExecutorSession.preflight` | its own loop (once per open) | the helper with `options.suite()`; a failure is `SessionOpenException(ENVIRONMENT)` as today |
| `cli Preconditions.preflight` | its own loop (`author`, once) | the helper; gains `SuiteCache suite` and `String agentSession` parameters |
| `inference ReResolver.preflight` | its own loop (once per job) | the helper, no cache (the one Phase 8 change in that module) |
| hosts that own a cache | – | **JUnit glue** `ScenarioTests.dynamicTests`: one `SuiteCache.create()` per stream whenever the effective options carry none (`options == null` and the overloads with options alike: the stream is the suite; a caller shares a cache across streams by passing one); **CLI `run` and `resolve`**: one per invocation in `base`/`options`; **`author`**: one shared by `Preconditions.preflight` and its `resolve`/`--run` executors; **MCP server**: one per server (`server.suite()`), set on `SessionOptions` before `open` and on every `skillroy_scenario_run`'s `RunOptions`; `re-resolve`: none (once per job already) |

Records: a `SCENARIO` check's record is byte-identical to today's `{id, ok, reason}` (the sample's pinned sequences
stay: `ResolutionChecks.java:366-367`, `PerTargetJavaChecks.java:191-194, 357-360`); a `SUITE` check's record adds
`scope: "suite"` and `cached: true|false`. `RunLog.OBSERVED` is unchanged (`preflight` is already observed; the
fields are additive for telemetry).

#### 9.3.4 Options (D618, D619)

- `RunOptions` gains the **fourteenth component `SuiteCache suite`** (null = none: every item per scenario) with
  `withSuite(SuiteCache)`; the thirteen-argument constructor and every older one are kept; `defaults()` has none.
- `SessionOptions` gains the **fifteenth component `SuiteCache suite`** with `withSuite`; the fourteen-argument
  constructor and the older ones kept; `open` reads it for its pre-flight and its pack capabilities.

### 9.4 Application configuration a scenario depends on is a capability (gap 24; D621)

The decision, written into guide §8.8 (with a pointer from §6.12 and the §15.2 row 24 removed), P1 §8.4 (A97) and
here: **application configuration a scenario depends on is a capability**, named in `requires:` when the scenario
cannot run without it (`PRECONDITION_FAILED` before anything mutating is sent, before pre-flight, bind and the start
mark) or in a guard when one piece of evidence depends on it (`DEGRADED`). Two sources, the layer's choice per fact:

1. **the agent module probes the setting in process** (`ctx.capabilities().declare(id, probe)`; or `set(...)` from the
   application's own change notification when the setting can change while the target runs), one id per condition,
   the value in the name when needed (`<module>.setting.<name>.<value>`), bounded, read-only, never on a forbidden
   thread; it costs the layer's capability goldens an id each;
2. **the pack evaluates it from the environment at run start** (`DomainPack.capabilities()`, §9.1: read through the
   data channel, overlay-aware through `settings()`, honoured in `replay`), which costs no agent change and no golden.

Limits stated with it: a probe runs at readiness and on `reprobe` only, so a mirrored setting goes stale unless the
module `set()`s it on change (or the pack evaluates it per run); ids carry no arguments; an unmet `requires` is
`ERROR` ("this environment cannot run this scenario"), evidence only some environments have is a guard. **There is no
`preconditions:` key** (the row's second option; Q56): one matrix states everything a scenario needs, `skillroy
caps` shows it, each run archives it, the `caps` root reads it, the scenario schema lists the ids. P2 §15 row 24
closes. The sample proof is §12.3 (scenario A requires `lending.returns.ceiling`).

### 9.5 Threads and bounds

| Where | Thread | Bound |
|---|---|---|
| `PackCapability.evaluate`, `PreflightCheck.check` | the host's thread (executor, session open, command) | the pack's own reads (`DataChannel.query`'s `timeout`, `MAX_ROWS`); no host timeout (as P1 §8.3); at most 64 capabilities per pack |
| `SuiteCache` | any; concurrent map | 1,000 entries by default; a check never runs under its lock |
| `AgentConnection.agentSession()` | caller's | no I/O |
| the loader's and the reader's `on_prompt` check | compile / read | a string comparison |

### 9.6 Unchanged, and the behaviour changes in one place

Unchanged: the agent API and every Java 8 module; the wire (0.2); the capability goldens (pack ids are never in the
agent's matrix, `CapabilityGolden` compares `client.capabilities()`); `plan.json`'s shape; `CapabilityMerge`'s
contributor rules (D326, D327, D367); the guard rule (P1 §8.4); `ExecutorSession.configure` (always the pack's
policy); the per-scenario `PlanExecutor`. **Behaviour changes**: (a) an `on_prompt.unexpected` value other than
`fail`/`pack` no longer loads (§9.2); (b) a pack that declares capabilities sends reads before `requires` (§9.1.5);
(c) a `SUITE` check that failed fails every later scenario of its suite without running (§9.3.1); (d) the JUnit glue
and the CLI own a suite cache (visible only through `SUITE` items); (e) `re-resolve` checks `requires` against the
merged matrix (§9.1.5).

### 9.7 As built (Phase 8, EP, 2026-10-19)

Built as §9.1-§9.6 specify, with these completions and corrections (the build's decisions `D-EP-n`, numbered by the
orchestrator):

- **`PackCapabilities`** (§9.1.5). `Entry` has a seventh component, `error` (the masked `toString` of a failed
  evaluation's exception, cut at `MAX_ERROR_LENGTH` = 500; null otherwise), with the contract's six-argument
  constructor kept; `toJson()` writes `error` only when set. `Result` adds `ids()` and `toJson()`. Beside the contract's
  five-argument `evaluate`, an overload takes the host's `SecretMask`: the error text is masked **before** it is cut and
  logged at WARNING, so that the cut never splits a known secret (the run, the session and `re-resolve` pass theirs;
  `caps --pack` has none). `NO_ANSWER` = `"no answer"`. A capability whose `id()` throws refuses the host as "its
  capabilities cannot be read"; one whose `scope()` answers null or throws counts as `SCENARIO` (the restrictive
  default), as a pre-flight check's does. A `SUITE` capability keeps its answer, `UNAVAILABLE` and a failed
  evaluation's included, and an entry answered from the cache repeats the stored `error`; an `InterruptedException` is
  never kept. An id another pack already contributed (`pack:` source) would read "declared by <pack> and by pack <id>"
  (no host merges two packs today).
- **The run** (§9.1.5). `mergeContributed` throws `AgentLostException` too; an interrupted evaluation ends the run
  `ERROR / ENVIRONMENT "interrupted"` with detail `{capabilities: interrupted}`; a refusal keeps the contributor
  refusal's shape (`capabilities {ok: false, error}`, detail `{capabilities: refused}`). **The session** (§9.1.5) creates
  its data channels before the merge and closes them on every failure path of `open`; its journal key is
  `session-start.options.pack`; a refusal or an interruption is `SessionOpenException(ENVIRONMENT)`.
- **`Preflight`** (§9.3.3). `Outcome.failed()` (the failed check's record, null when every check passed or the checks
  could not be read) and `CACHED_SUFFIX`; a run's failure detail is `{preflight, reason}` plus `cached: true` when
  answered from the cache. **Behaviour change:** a pack whose `preflight()` throws is now `ERROR / ENVIRONMENT "pack <id>:
  its pre-flight checks cannot be read: <e>"` in a run as well (before Phase 8 a run ended "internal error: <class>"
  there, while the session and the CLI already used the text). `ReResolver` keeps its behaviour on an interrupted
  check (the job ends with `InterruptedException`).
- **The hosts** (§9.3.3). `skillroy caps` evaluates the pack's capabilities only with `--pack` (with `--inference`
  alone none, even when one pack is on the class path). The MCP server uses the session options' own cache when the
  caller gave one, else its own (`server.suite()`); both reach the session's open and every `skillroy_scenario_run`.
  `Preconditions.context(pack, agent, data, envId)` is the CLI's one context (pre-flight, `caps --pack`, `re-resolve`).
- **`on_prompt`** (§9.2). The plan reader accepts `fail` and `pack` ignoring case and surrounding blanks, as the
  loader reads them (the compiler always writes the lower-case form); its message is `invalid plan:
  onPrompt.unexpected must be fail or pack, found <the onPrompt object>`.
- **The sample** (§12.3). `LendingPack.CAP_RETURNS_CEILING`; the class `ReturnsCeiling` reads
  `LendingResolvers.SYSTEM_LIMIT_SQL` with one row and 10 s; a failed read's reason is `the lending database could not be
  read (<kind>): <message>` (the kind only, without the RPC code the pre-flight check's text adds). The sample's
  hand-written delegating test packs (`ResolutionChecks.FailingPreflight`, `OverlayPack`) forward `capabilities()`:
  without it scenario A, which requires the id, would stop at `requires` in the pre-flight case (item 37).
  `HeadlessDesk` numbers its sessions `headless-desk-<n>` (deterministic in a JVM, never random); `DeskConnection`
  answers one per instance.
- **Tests** (§13.1 rows 42-50). Row 42: the calls before `requires` are `data.sources` and `data.query` (the agent's
  data channel asks once which sources it serves), not `data.query` alone; the contributor conflict, unreachable
  through a run (a contributed id starts with `inference.`, a pack's with `<packId>.`), is proven on
  `PackCapabilities.evaluate` with a hand-built matrix. Row 48: the CLI test pack's id is `clitest`, so its capability is
  **`clitest.env.ready`** with `SOURCE pack:clitest` (the contract's `cli.env.ready` would be refused by §9.1.2 rule 3);
  the CLI cases are in the new `Phase8CommandsTest` (`run`, `resolve`, `caps`) and in `AuthorCommandTest` and
  `ReResolveCommandTest`, not in `RunCommandTest`; the `re-resolve` case requires `inference.gateway` (`OK` under the
  recorded gateway; `inference.sql` is `UNAVAILABLE("no allow-list")` for that pack). Row 49 is the new
  `SuiteCacheOverMcpTest`. Row 50: `PackCapabilityHeadlessTest` compiles scenario A from its YAML (its committed plan is
  regenerated in step R); on the headless desk every read goes over the controller-side JDBC channel, so the overlay
  case sends **nothing** to the desk after the matrix (no `data.query` either) and writes a `pack` note record (the
  overlay's composite version); `PreflightSuiteHeadlessTest` streams scenario A, `return-refunds-deposit` and
  `return-within-limit-observed` (no launch properties, which the headless desk cannot answer). `ScenarioSchemaCommittedTest`'s
  keyword check looks for the keywords as keys (`"maximum":`): `requires`' description now quotes the capability's
  description, prose that says "maximum".
- **End to end** (§13.2 items 36-42). Item 36 (slot 177): `contributed` is the three `inference.*` ids of the
  contributors on the scenarios module's class path (as slot 156 asserts), not `[]`; slot 156's "the archive is the
  agent's matrix plus the contributed ids" also excludes the pack's id. Items 37 and 41: `beforeRequires()` is
  `capabilities, data.sources, data.query` (the hooks leg: `capabilities, hook.health, capabilities, data.sources,
  data.query`); the hooks leg runs no `ResolutionChecks`, so slot 177 also runs an unmet-`requires` probe through
  `Recording` in every leg, asserting that the pack's reads come last and only the matrix and step 0b's hook calls before
  them. Item 38: without the driver, scenario A now stops at its `requires` (`lending.returns.ceiling: UNAVAILABLE (the
  lending database could not be read (UNAVAILABLE): …)`, since its capability reads the same JDBC source first), and
  `basic-loan`, run beside it in the invocation, shows the pre-flight failure `ok: false, cached: false` and
  `ENVIRONMENT` as before; with the driver, `basic-loan` (first by name) evaluates the `SUITE` check and scenario A's
  record is `cached: true`.

---

## 10. Protocol amendments, capability ids, topics, errors, options and SPIs

**The wire stays 0.2** (§2.1, D624). The additive changes to the frozen Phase 1 contract go through its amendment
process as marked passages (P1 header; P6 §15.1; P7 §5) with the ids below, written into P1 (and, where the passage
lives there, into P2, P4 and P5, as A66 marked P4) as "(Phase 8, specified 2026-10-19, not yet built; P8 §n)" when the
build starts and corrected to "(Phase 8, built …; P8 §n)" after it; the build appends the rows to the "A34 onwards"
index of `phase1-contract-amendments.md` and moves its closing sentence to "From A100 …" (D301's rule).

| Id | Change (additive) | P1 passage | P8 |
|---|---|---|---|
| **A67** | the SPI `InputVeto`: the interface, the route constants, the ancestor rule, fail closed | §4 (the SPI table) | §3.2 |
| **A68** | the veto on every input primitive that resolves a target: the `INPUT_REJECTED vetoed` data, Node `vetoed`, hit-test `vetoed`, the pointer's `VETOED` and `vetoed`, the dialogs' button flag, the recorder's refusal; a paragraph "Input vetoes" before "Modal blocking"; `AgentModule.info()` (default empty) shown as `info` in `agent.info.modules[]`, the Swing module's `vetoWithheld` | §4, §5, §6 | §3.2 |
| **A69** | the effective visible area: the Locator and snapshot rule, Node `collapsed`, `INVALID_PARAMS collapsed`, the pointer component form's `INPUT_REJECTED not visible`, A27's collapsed root | §6 | §3.3 |
| **A70** | the routed pointer's source (the innermost unclaimed heavyweight), hit-test and pointer `source`, the exit on a source change, the balance into a heavyweight leaf | §6 | §3.4 |
| **A71** | "Click rungs" and `swing.dismiss`: AWT `Button`/`Checkbox` fire `ActionEvent`/`ItemEvent` on ACTION and COMPONENT; `DialogReader` treats AWT `Button` as a button | §6 | §3.5 |
| **A72** | `ThreadPolicy.APP_QUIESCENT`; `QuiescentPoint`; `ThreadPolicyProvider.quiescentPoint()`; `PrimitiveSpec.Builder.quiescentBudgetMs` and the descriptor key `quiescentBudgetMs`; `ThreadBroker.callQuiescent`; `post(APP_QUIESCENT)` refused; the dispatch gates; `agent.info.threads.quiescent`; the forbidden-thread exemption | §4, §5 | §4.1 |
| **A73** | `ThreadPolicy.LANE`; `Lane`; `ThreadBroker.declareLane`, `callOnLane`; `PrimitiveSpec.Builder.lane` and the descriptor key `lane`; `post(LANE)` refused; `DataSourceProvider.lane()`; `data.query`'s hand-off, its own `TIMEOUT` data and the per-source gate; `data.sources.lane`; `agent.info.threads.lanes` | §4, §5, §6 | §4.2 |
| **A74** | `QueryControl`; `DataSourceProvider.query/5`, `enforcesTimeout()`; `ReadOnlyJdbc`'s statement sink; `TIMEOUT` data `cancelled`, `cancelPending`, `stillRunning`, `enforcesTimeout`, `waitedMs`, `source`, `lane`; `data.sources.enforcesTimeout` | §4, §6; P2 §2.2, §7.2 | §4.3 |
| **A75** | `SelectLabels`; the naming and width rule in place of "reported as `c1..cn`"; `DataSourceProvider.columns(String)`; the `AllowListCheck` hint | §4, §6; P2 §7.4; P5 §5.2 | §4.4 |
| **A76** | procedures retired: `SqlGuard` refuses every `CALL`/`EXEC`; `SqlAllowList.procedures` reserved and required empty; no catalogue line (P5 is marked, as P4 was for A66) | P5 §5.2, §5.3, §16.2 | §4.5 |
| **A77** | `MaskPolicy`: the events route (`event:<topic>`, the dotted column) and the publishing-thread rule | §4 (the SPI table, `MaskPolicy`'s block) | §5.1 |
| **A78** | the ring masks at publish, outside the ring lock: the key rule, the registered policies, the 10,000-scalar budget, `truncated: true`; A34's events bullet qualified ("as published, after the key rule and the registered policies, never by value; from a policy's registration on"); `agent.info.events` (`maskPolicies`, `maskFailures`, `overBudget`, `slowPasses`, `maxPassMs`) | §5 (the ring, "Secret redaction", `agent.info`) | §5.1 |
| **A79** | `BrowserMask`; `web.browsers.masksRegistered`; the view-less rule; a masked browser's event payloads; the selection rule; the action answers without page data (`web.pointer` without `hit`, `web.dom.click`/`fill` and `AMBIGUOUS` candidates with `role` and `tag` only; `redact: false` a person's) (P4 is marked, as A37 and A66 were) | P4 §3, §4.1-§4.3 | §5.2 |
| **A80** | `CdpOptions.allowDefaultOverrides`; the `web.path` record's `playwright` and `connectOptions` | P4 §5.1, §5.2, §8.2 | §5.3 |
| **A81** | the agent option `hooks.deny` | §2.4 (the options table) | §6.5 |
| **A82** | the SPI `ClassDeny`; `HookEngine.deny()` (default); `…agent.api.instrument.CodeSources` | §4 (the SPI table's hook and instrument lines) | §6.2, §6.5, §6.6 |
| **A83** | `RpcResult.warnings` (written when non-empty, read leniently); `CallWarning` | §3 | §7.1 |
| **A84** | `Invocation.warn(code, message, data)` default; `EventBus.publish(topic, source, payload, correlationId)` default; the dispatcher collects, caps and redacts warnings | §4, §5 | §7.1, §7.4 |
| **A85** | `DeviceAdapter.rungs(action)` default; `device.list` entries' `rungs` | §4, §5 | §7.6 |
| **A86** | the result conventions `InputResult` (and `INPUT_REJECTED data.reason`), `RungResult`, `CompositeResult` | §3, §9.2 | §7.2, §7.5, §7.6 |
| **A87** | `SyncPolicy.ackByInputId`; `PlannedCall.accept/rungs/minRung`; `CompileContext.minRung`; `DomainPack.warningCodes`; the step modifiers; the reserved names `accept`, `minRung` | §8.1, §8.3 | §7.3, §7.4 |
| **A88** | the executor's result checks in their order, the records `warning`, `rung`, `rungs`, `prompt.answeredBy`, the rung pre-check after `requires`, the new result components, the JUnit and CLI lines | §8.5 | §7.7 |
| **A89** | the sample's primitives, device, events and capabilities of §12 | §9.2, §9.3 | §12 |
| **A90** | `sync.await`'s `match` gains `{has: scalar}`; an invalid `match` is `INVALID_PARAMS {param: "match", reason}`; `EventBus.await`'s documented rule; `PayloadMatch` in `skillroy-protocol` | §4, §5 | §8.9 |
| **A91** | `plan.json` version 7; strict step and call keys from 7 | §8.2 | §8.2 |
| **A92** | the base verb `captureFacts` (reserved); `waitFor`'s `match` validated at compile; the eighth base root `events` | §8.3 | §8.7, §8.9, §8.6 |
| **A93** | the path grammar's list filters, dotted filter fields and `[json]`; the matchers `anyOf`, `containsWhere`; `size` with a nested matcher documented; "one budget per matcher evaluation" now literal | §8.4 | §8.3-§8.5 |
| **A94** | `on_prompt.unexpected` ∈ {`fail`, `pack`}; another value refused at load and in the plan reader | §8.1 | §9.2 |
| **A95** | `DomainPack.capabilities()`, `PackCapability`, `CheckScope`, `PreflightCheck.scope()` | §8.3 | §9.1.1, §9.3.1 |
| **A96** | the run sequence: the pack's capabilities evaluated after the contributors' merge, reads allowed before `requires`; a `SUITE` check answered from the suite cache; `capabilities.pack`, `preflight.scope`, `preflight.cached` | §8.5 | §9.1.3, §9.3.3 |
| **A97** | a pack-contributed id may guard; configuration is a capability (no `preconditions:`) | §8.4 | §9.4 |
| **A98** | `AgentConnection.agentSession()` (default null, no I/O); `AgentClient` answers its verified session | §7 | §9.3.2 |
| **A99** | the sample: `lending.returns.ceiling`, `lending.database` `SUITE`, scenario A's `requires` | §9.2 | §12 |

**Capability ids Phase 8 adds**, for the goldens and `requires:` (no base id, D523, D594):

| Id | Side | Phase 8 state |
|---|---|---|
| `lending.engine.view` | agent, the sample's module (§4.1, §12.2) | `OK` when the desk runs and the module's quiescent point is offered; in all five goldens |
| `lending.signOn`, `lending.scanChecked` | agent, the sample's module (§7.5, §12.2) | `OK` on every leg; in all five goldens |
| `lending.scanner.physical` | agent, the sample's module (§7.6, §12.2) | `UNAVAILABLE("scanner driver not enabled: launch the desk with -Dlending.scannerDriver=true")` in every standard leg, `OK` on the scanner leg; in all five goldens |
| `lending.returns.ceiling` | the sample's pack, source `pack:lending` (§9.1, §12.3) | `OK` in every committed environment (the seeds create the row); `UNAVAILABLE("no system maximum return configured (desk_setting <name>)")` without it; never in a golden |
| `swing.*`, `web.*`, `hook.*`, `rule.*`, `inference.*`, `target.ready`, `jvm.instrumentation`, `data.query` | as today | unchanged; the route constants of `InputVeto` equal the Swing rung ids |

**Event topics.** No new topic in `Topics` and no new base event. The sample's module publishes `staff.signedOn {code,
name}` (§12.2); its `input.acked` payload becomes `{source, inputId}` (§7.4); the web module's `web.load`, `web.ready`,
`web.console` and `web.tap` gain `masked: true` on a masked browser with their page data withheld (§5.2).

**Errors and `data.reason` values.** No new error code (`ErrorCodes` and `ErrorCodesTest` unchanged). New `data`
uses of existing codes, each a token a consumer switches on (P7 §3.9's style, D504):

| Code | `data.reason` or `data` key | From | §
|---|---|---|---|
| `INPUT_REJECTED` (-32005) | `reason: "vetoed"` (+ `veto`, `ref`, `vetoed`, `route`, `hit?`) | every Swing input route | §3.2 |
| `INPUT_REJECTED` | `reason: "not visible"` (+ `ref`, `point`, `param: "at" \| "to"`) | `swing.pointer`'s component form | §3.3 |
| `INPUT_REJECTED` | `reason: notAccepted \| inboxFull` (the standard semantic-input values); the sample's `malformedFrame` | a module's input primitive | §7.2, §12.2 |
| `INVALID_PARAMS` (-32602) | `reason: "collapsed"` (+ `ref`, `by`) | `requireClickable` and the target forms | §3.3 |
| `INVALID_PARAMS` | `{param: "redact", reason: "masked", browser}` reused for a view-less browser | the four web reads | §5.2 |
| `INVALID_PARAMS` | `{param: "match", reason: "<problem>"}` | `sync.await` | §8.9 |
| `INVALID_PARAMS` | `data.param` `specs[n].target.codeSource`, `catalogue.deny[i]`, `catalogue.deny` | `hook.install`, `hook.probe` | §6.3, §6.6 |
| `NOT_READY` (-32032) | `"no quiescent point within <t> ms"` (+ `budgetMs`, `waitedMs`); `"too little of the call's time left for its budget"` (+ `budgetMs`, `remainingMs`); `"quiescent point closed"`; `"application thread not available yet"` (now for `APP_QUIESCENT` reads too) | the dispatcher, `QuiescentPoint` | §4.1 |
| `CAPABILITY_UNAVAILABLE` (-32011) | `"no quiescent point offered"`; `"quiescent budget exceeds the allowance"` (+ `budgetMs`, `allowanceMs`); `"quiescent task overran its budget"` (+ `budgetMs`, `ranMs`); `"unknown lane"`, `"lane closed"` (+ `lane`) | the dispatcher, the broker | §4.1, §4.2 |
| `FORBIDDEN_THREAD` (-32002) | `"await on the application's thread"` | `QuiescentPoint.await` called on the thread that runs `runOne` or from a task of the point | §4.1 |
| `CAPABILITY_UNAVAILABLE` | `{rung, minRung, capability?}` from a ladder primitive refusing to act below `minRung` | a ladder primitive | §7.6 |
| `BUSY` (-32033) | `"quiescent queue full"` (+ `capacity`); `"lane full"` (+ `lane`, `threads`, `queue`); `"lane stuck"` (+ `lane`, `runningForMs`); `"an earlier read of the source still runs"` (+ `source`, `runningForMs`) | the broker, `data.query` | §4.1, §4.2 |
| `TIMEOUT` (-32001) | from `data.query`: `primitive`, `timeoutMs` (existing) + `source`, `lane`, `waitedMs`, `cancelled` (boolean), `cancelPending` (only when true), `stillRunning`, `enforcesTimeout` | `data.query` | §4.2, §4.3 |
| the pointer's `fired: false` `reason` | `VETOED` beside `EDT_BUSY`, `MODAL_OPENED` (+ `vetoed {ref, veto, route}`) | `swing.pointer` ROUTED | §3.2 |
| hook state reasons (`UNAVAILABLE`) | `denied by the catalogue <id>: <p>`, `denied by the agent option hooks.deny: <p>`, `denied by a module: <text>`, `class from another code source: <names>`, `the class's code source cannot be read: SecurityException`, `codeSource regex too slow` | `hook.probe`, `hook.install` | §6.4, §6.5 |
| `RULE_REJECTED` (-32022) `data.errors` | `<class>: denied by …` | `rule.submit` | §6.7 |
| `GuardViolation.Reason.PROCEDURE` | the fragment `"no data channel runs a procedure call (gap 119)"` | `SqlGuard` | §4.5 |
| `ERROR / ENVIRONMENT` texts (controller) | "the agent does not honour the catalogue's deny list (…); upgrade the agent"; "the pack's acknowledgements are correlated by inputId; <primitive> returned none"; "<verb> returned the value of its secret parameter '<name>'"; "pre-flight check <id>: <reason> (checked once for this suite)"; "fact 'events' could not be read: the ring evicted events N..M since the run's start; raise the agent option eventRing" | the executor | §6.9, §7.4, §7.5, §9.3, §8.6 |

**Agent options and controller properties.**

| Kind | Name | Change |
|---|---|---|
| agent option | `hooks.deny` | new: comma-separated `ClassPattern`s read at install by the hooks and the Byteman modules; invalid → the module's FAILED record naming the key (A81, §6.5) |
| agent option (sample) | `lending.hooks.deny` | new: the sample module's `LendingClassDeny` behind it (§12.2) |
| agent option (sample) | `webhost.mask` | a comma-separated list: `front`, `release-detached`, `tag-back` (§12.3) |
| agent option | `eventRing`, `eventRingChars`, `robot`, `readOnly`, the others | unchanged; `eventRing` is named in the `events` root's loss message (§8.6); the character bound runs after the ring pass (§5.1) |
| controller system property | `skillroy.cdp.allowDefaultOverrides` | new: the operator's opt-in for a Playwright line without `noDefaults` (§5.3); never a scenario's or a model's |
| launch properties (sample) | `lending.tools`, `lending.idleTickMillis`, `lending.scannerDriver` | new (§12.1) |
| run and session options | `RunOptions.suite` (14th), `SessionOptions.suite` (15th) | new (§9.3.4) |
| primitive descriptor keys | `quiescentBudgetMs`, `lane` (optional, written only when set) | new (§4.1, §4.2); `thread` may read `APP_QUIESCENT` or `LANE` |

**SPIs and public types.**

| Side | New | Default methods added | Javadoc amended |
|---|---|---|---|
| `agent-api` (Java 8) | `spi.InputVeto`, `spi.QueryControl`, `spi.web.BrowserMask`, `spi.hook.ClassDeny`, `Lane` (`…agent.api`) (interfaces); `QuiescentPoint` (`…agent.api`), `instrument.CodeSources` (classes); the constants `ThreadPolicy.APP_QUIESCENT`, `LANE` | `ThreadPolicyProvider.quiescentPoint()`; `ThreadBroker.callQuiescent`, `declareLane`, `callOnLane`; `DataSourceProvider.query/5`, `enforcesTimeout()`, `lane()`, `columns(String)`; `Invocation.warn`; `EventBus.publish/4`; `DeviceAdapter.rungs(String)`; `HookEngine.deny()`; `AgentModule.info()`; `PrimitiveSpec.Builder.quiescentBudgetMs`, `lane` | `MaskPolicy` (the events route and the publishing thread), `EventBus.await` (`{has}`; forbidden to a quiescent task), `EventBus.publish` (masked before stored), `QueryResult` (the naming rule), `ThreadBroker` (the one exemption), `DialogLocator` (a root without an effective area is not a dialog) |
| `skillroy-protocol` (Java 8) | `PayloadMatch`, `CallWarning`, `InputResult`, `RungResult`, `CompositeResult`, `hook.ClassPattern`, `sql.SelectLabels`, `sql.SqlTokens` (the tokenizer moved out of `StatementGuard`, a pure refactor) | `RpcResult` (7-argument constructor, `warnings`); `HookTarget` (5-argument, `codeSource`); `HookProbe` (11-argument, `codeSources`); `ReadOnlyJdbc.executeLenient` (the statement-sink overload); `PrimitiveDescriptor` (the two optional keys) | – |
| `skillroy-scenario` (Java 25) | `pack.PackCapability`, `pack.CheckScope`, `pack.Rung`, `PackCapabilities`, `Preflight`, `SuiteCache`, `Plan.FactCapture`, `Plan.FactField` | `DomainPack.capabilities()`, `warningCodes()` (forwarded by `DelegatingDomainPack`); `PreflightCheck.scope()`; `CompileContext.minRung()`; `SyncPolicy.withAckByInputId`; `PlannedCall.withAccept/withRungs/withMinRung`; `BaseFacts.events(LongSupplier)` | `PromptPolicy`, `CapabilityMerge` |
| `skillroy-controller` | `CapabilityMatrix.SOURCE_PACK_PREFIX` | `AgentConnection.agentSession()` | – |
| `skillroy-junit` | `ai.skillroy.testjava.junit.OutputScan` (public; the sample's `SecretScan` deleted) | – | – |
| `skillroy-cdp` | `ConnectSupport` (package-private); `AgentBrowser.Listing` | `CdpOptions.withAllowDefaultOverrides` | – |

---

## 11. Safety and data

The rules, each with where it is enforced; `threat-model.md` carries the judgement (its status paragraph, §2 (actors
A4 and A13), §3, §5, §6, §7 and §8 are extended with this phase, and a short §9 lists the Phase 8 mechanisms in one
place):

1. **A component a layer vetoes is never acted on, for any origin, on any route, `force` included** (§3.2). The veto
   is asked on the EDT inside the read of every input route that resolves a target (twelve of the thirteen; the
   `ROBOT` click rung is refused before resolution) after the clickable test, again inside every posted task
   immediately before it acts, and per event in the routed pointer (the pressed component while a press is down, and at
   that press's release also the components at the release's screen point, S155); the Robot path refuses before
   anything moves, asks the components of every window of the JVM at its point (S156) and asks a drag's release again
   before it releases (S155); default type and key targets and ENTER's default buttons (every root pane up to the
   window, a `'\n'` in DISPATCH text included) are asked; a popup is asked with its invoker's chain; a veto that throws
   refuses; nothing a model, a client or a person passes skips it (`force` acts past a dialog, never past a veto). The
   recorder and V2's raw pointer refuse a vetoed component as they refuse a masked one (it becomes no step,
   journal line or export); the text snapshot flags it `vetoed`, its text kept (a veto is safety, not privacy). Not
   covered, stated: Swing key bindings, mnemonics and accelerators (veto the window or the bound control; a menu item's
   own veto does not cover the keyboard of an open menu: veto the window); a popup or drop zone the application shows
   after the Robot route's release read (fix wave of the Phase 8 review, FA); `web.*`
   input into a vetoed browser component (gap 126, medium), so that **a veto on a window or panel does not cover page
   input into a browser inside it**; a step whose control's veto can change with application state passes with `fired:
   true` when the veto refuses inside the posted task, counted in `agent.info` and failed only by the step's `sync` or
   an expectation on the effect (D503); a failed layer module served under `allowFailedModules=true` without its veto
   (logged).
2. **Agent work runs on the application's own thread only through a `QuiescentPoint` the provider itself drives, at the
   point it chooses, admitted by a declared budget and the caller's remaining time, and never interrupted** (§4.1): the
   one exemption to "a thread the application forbids is never used", granted to the core's wrapped task alone; a
   nested `call`, `callQuiescent` or `QuiescentPoint.await` from it is refused `FORBIDDEN_THREAD` (`EventBus.await` is
   forbidden to it by rule, unchecked); `close()` runs before the module's `uninstall` returns; a 2x overrun penalises
   the primitive until `reprobe`. Lanes are base-owned daemon threads, at most 32, retired with the module (§4.2); a
   provider's cancel action runs on a nested agent worker task of its own, bounded to 2 s, never on the dispatching
   worker (§4.3); no `data.query` is ever abandoned on the dispatcher's side (the module answers its own `TIMEOUT`
   inside its reserve, `cancelPending: true` when an action still runs).
3. **Every event payload is masked before it is stored, from each policy's registration on** (§5.1): the registered
   `MaskPolicy`s under `event:<topic>` and the secret-key rule, on the publishing thread, outside the ring lock,
   bounded (10,000 scalars, then `<redacted>` and `truncated: true`; a slow pass counted), never failing publish; one
   masked object feeds the ring, every subscription, SSE, `evt.query`, `sync.await`, the call's `events`, the `events`
   fact root and so every run output and client. The guide's rule stands: a module masks before it publishes; the ring
   pass is the backstop, and `OutputScan` (`Scope.EVENTS`) is the kit that plants a value and scans a run. The CDP
   path's own events never pass the ring (gap 138); on attach, the events of the module-install window are masked by
   key only (gap 139).
4. **A browser the agent has no component for is masked while any `CaptureMask` or `BrowserMask` is registered** (§5.2,
   fail closed; released only by a `BrowserMask` answering FALSE, which never overrides a `CaptureMask`); a masked
   browser is masked on every route, its `web.load`, `web.ready`, `web.console` and `web.tap` withhold page data, `by:
   {url|title}` never matches it (a regex on withheld text would be an oracle), the selection error data withholds
   its URL and title, and the actions that stay allowed on it answer without page data (`web.pointer` without `hit`,
   `web.dom.click`/`web.dom.fill` and their `AMBIGUOUS` candidates with `role` and `tag` only, D637); the CDP path
   hands unmapped-page reads, url/title selections and the three actions on a masked or unmapped page to the agent
   while masks are registered and counts an absent `masksRegistered` as true. `web.evaluate` and `web.waitFlag` on a
   masked browser stay S111's recorded residual (read-only mode refuses them; `CallPolicy` never offers them to a model
   or a client).
5. **The CDP path attaches with the Playwright line it finds and never leaves a driver behind** (§5.3): the setters are
   gated by field presence, once per JVM; without `noDefaults` the path is not used unless the operator sets
   `skillroy.cdp.allowDefaultOverrides` (never a scenario's, a pack's or a model's choice: the overrides change the
   application's own browser context); without `artifactsDir` a traced run opens untraced and records why; a
   `LinkageError` closes the pending Playwright and ends the thread; the `web.path` record says what was applied.
6. **A hook binds the named jar's copy only, and a class the pack, the operator or a module denies is never read, probed,
   instrumented or handed to Byteman** (§6): `CodeSources` reads a location's field getters only (never a URL handler,
   never a map key), names never a directory; the deny is asked before "loaded?" from three sources (the option first),
   first refusal wins, a throw refuses; the held catalogue deny is add-only for the agent instance's life and bounded
   at 256; the installer requires the echo and fails closed without it; Byteman checks the deny before the count, before
   the load and on matched subtypes; a hook installed before its class was denied is flagged, not removed; `hook.probe`,
   `hook.install` and `rule.*` stay never offered to a client or a model.
7. **A result never carries a secret parameter's value** (§7.5): after the masked `call` record and before anything
   else, the executor walks every result's value and warnings on a call with `secretParams` (equality for short secrets,
   substring from `minLengthInText`), fails the step `ERROR / ENVIRONMENT` and never writes the value; the dispatcher
   redacts secret parameters from warnings before they are cut and before the result is built; `taken` is never a
   secret value; a composite's
   `answered[].by` is a parameter name, `choice` is forbidden on a secret parameter, and a composite never answers a
   question it does not own (an unlisted prompt reaches the prompt check as today). A module's result is trusted code's
   output: the executor's checks are consistency controls, not a trust boundary (threat model §6).
8. **A required rung is never acted through a shortcut** (§7.6): a ladder primitive refuses to act below `minRung`
   (`CAPABILITY_UNAVAILABLE`) and echoes `minRung`; the executor fails a missing, undeclared or below-minimum rung and
   a missing echo, records a lower rung as `DEGRADED` once, and refuses at run start, before bind and any side effect,
   a step whose required rung no `OK` capability reaches.
9. **More target content comes under the controller's parsers, each read bounded** (§8): list filters and
   `containsWhere` under one regex budget per path and one per evaluation, `[json]` at 1 Mi and depth 200, the `events`
   root at ten pages of 1000 that **fails rather than under-counts** (a lost window would let a negative check pass),
   `actual` cut at 200 list elements; the agent-side `PayloadMatch` is a linear text comparison under the ring lock, no
   regex, no parsing. Under `expectationsOnMaskedValues` a captured value the mask changes fails the step, so a captured
   secret can never become an operand or an argument; a session's `captureFacts` runs under the session mask.
10. **Pack capability evaluation is pack code at run start that reads only** (§9.1): through the guarded read-only data
    channel, with no bind mode, no inference settings and no gateway in its context (so it is honoured in `replay` and
    the D19 ledger check still catches a model call), after the contributors' merge and before `requires`; nothing
    mutating, no cleanup, no `recover` before the start mark; reasons are masked like every reason, the SPI's Javadoc
    tells a pack not to quote row values in one, and a failed evaluation's reason names the exception's class only
    (its text goes to the log and the record's `error`, never the matrix); the `SuiteCache` is in memory, per host,
    keyed by the agent's session id (not a secret), never written; a connection that cannot name its session is never
    cached for.
11. **Read-only mode, `confirm`, `CallPolicy` and the credential rule stand.** `redact: false` and `force` stay a
    person's arguments on every new refusal; a session refuses a raw call carrying `accept`, `rungs` or `minRung` before
    anything is sent (a model never sets a modifier; a verb's pre-acceptance is the pack author's); a client's own
    scenario may accept warnings through `skillroy_scenario_run` (its scenario, its verdict); `on_prompt.unexpected`
    narrowed to `fail` or `pack` removes a way for a model-written scenario to select the pack's policy under a
    misleading name; `hook.health` and `rule.list` now show jar file names, patterns and deny reasons (never a
    directory, never personal).
12. **No model call in `replay`**, asserted (C0): unchanged; nothing in Phase 8 calls a model, and the five session
    recordings are regenerated key-free.

**Behaviour changes for existing users, in one list** (each marked where it stands):

1. a registered `MaskPolicy` applies to every event payload under `event:<topic>`, so a row policy that ignores
   `sourceId` masks event fields of the same column name; a secret-looking map key in any event payload is
   `<redacted>` whether or not a policy is registered (§5.1);
2. a view-less browser is masked while any mask is registered; a masked browser's events withhold page data; `by:
   {url|title}` never matches a masked entry and the selection error data withholds its URL and title; `web.pointer`
   of a masked browser carries no `hit` and `web.dom.click`/`web.dom.fill` answer with `role` and `tag` only; the CDP
   path hands unmapped-page reads, such selections and the three actions to the agent while masks are registered
   (§5.2);
3. the CDP path is unused below Playwright 1.60 without the operator's property (§5.3);
4. a collapsed in-window dialog root blocks nothing; locators that matched a hidden copy answer `NOT_FOUND` or one
   match; snapshots shrink by collapsed subtrees; a click on a collapsed component is `INVALID_PARAMS collapsed`; the
   pointer's component form refuses a point its component does not show (§3.3);
5. a routed call whose point lies in an unclaimed heavyweight container reaches the components inside it, `source` may
   differ from `window`, `dispatched.of` may be one larger (§3.4); AWT `Button`/`Checkbox` get a semantic event on
   ACTION and COMPONENT instead of a dispatched click (§3.5);
6. `CoreThreadBroker.post` refuses `APP_QUIESCENT` and `LANE` instead of running an unnamed policy on a worker; one
   stuck `data.query` source no longer makes `data.query` `BUSY` for every source; a positional provider's zero-row
   result has columns (§4.8);
7. every `CALL`/`EXEC` is refused by `SqlGuard` with a reason, and a non-empty `SqlAllowList.procedures` is refused where
   a list is put to use (§4.5);
8. a target publishing more than `eventRing` events in a run makes an `events` expectation `ERROR` (§8.6); `actual` in
   `run.jsonl` is cut at 200 list elements (§8.10); a filter field holding a dot is a sub-path (§8.3); a `waitFor` or
   pack `sync.await` `match` holding a list or a map is a compile error, and an invalid `match` on the wire is
   `INVALID_PARAMS` instead of `TIMEOUT` (§8.9);
9. `planVersion` 7 in every compiled plan; an unknown step or call key is refused from 7; a pack verb named
   `captureFacts`, `accept` or `minRung` is refused (§8.2, §7.3);
10. a module that emits a warning fails a step that does not accept it; a pack that sets `ackByInputId` needs its
    module to return `inputId`; a primitive that echoes a secret parameter's value fails its step; `ui.strict` extends
    to ladders (§7);
11. with `lending.yaml` version 2 a desk run from a class directory with the hooks module answers every catalogue hook
    `UNAVAILABLE("class from another code source: classes")` (the sample only; the IT jars match) (§6.12); a 0.1.0 agent
    under a pack whose catalogue uses `codeSource` or `deny` fails the install closed (§6.3);
12. an `on_prompt.unexpected` value other than `fail`/`pack` no longer loads; a pack with capabilities sends reads
    before `requires`; a `SUITE` check that failed fails every later scenario of its suite without running; the JUnit
    glue and the CLI own a suite cache; `re-resolve` checks `requires` against the merged matrix (§9.6).

**The limitation sentences the build writes into `SECURITY.md`'s "Known limitations"** (§15's L2 and L8 check that
they are there; `threat-model.md` §7 and §8 carry the same facts): (a) Swing key bindings, mnemonics and accelerators are
not followed by the input veto (veto the window or the bound control); a veto on a window or panel does not cover
`web.*` input into a browser inside it, and a layer that must keep page input out vetoes nothing more for that (gap
126); a failed layer module served under `allowFailedModules=true` serves without its veto; (b) a quiescent task that
overruns its budget holds the application's thread until it returns (admission and a penalty, no pre-emption), and a
provider read that ignores cancellation keeps its lane or its source busy until it ends; (c) events are masked by key
and by the pack's policies at publish (on attach, the events of the module-install window are masked by key only);
the CDP path's own events are not; a browser without a view is masked while any mask is registered and released only
by a `BrowserMask`; (d) a denied subtype loaded after a `^`/`INTERFACE` Byteman submit receives Byteman's trigger, and a deny
list only tightens for the life of an agent instance; (e) the secret-echo check compares short secrets by equality
only; a composite that answers a question it does not own cannot be detected by the base; `taken` records application
data; (f) the `events` root sees the run's window only while the ring holds it: a target that publishes more than
`eventRing` events in a run makes such expectations `ERROR`, by design; (g) a pack capability's evaluation and a
pre-flight check run pack code on the controller with no host timeout (the data channel's per-query bound is the
bound); (h) the CDP path's gate for an older Playwright is proved through a seam: only 1.63.0 was run.

---

## 12. The sample

Every addition is planted behind a launch property, an agent option or a new verb, never in the desk's main frame
(`keypad-image`'s baselines and `VisionSwingChecks` depend on it); no column of the twelve fingerprinted tables changes,
no existing seed row changes, and nothing enters scenario D's tier (b) request (its two live recordings stay valid; no
key in Phase 8). A proof that needs a differently launched desk takes a new `LendingTarget.Mode` with its own nested
class in `LendingEndToEndIT` (§13.2). Versions and every regeneration are step R's (§2.8).

### 12.1 The desk (`skillroy-sample-lending-app`, Java 8)

- **The "Desk tools" window** (§3; D516), behind `LendingOptions.PROP_TOOLS = "lending.tools"` (`true`/`false`, default
  false; `tools()`/`tools(boolean)`). With it, `LendingApp.launch` opens, after `createWindow()` and on the EDT, a
  second frame `DeskTools` (title "Desk tools", name `deskTools`, `DO_NOTHING_ON_CLOSE` with a `WindowListener`
  counting `WINDOW_CLOSING` as a close request, `setAutoRequestFocus(false)`, not owned by the main frame, at (624, 400),
  400 x 360: inside the 1024 x 768 display, overlapping the desk frame's lower right, never activated by the
  application); `LendingApp.tools()` returns it or null. Its content, each control counting in `DeskTools.Counters`
  (component names are the `{id}` locators):

  | Component | Name | Counts | For |
  |---|---|---|---|
  | `JPanel` titled "Close" | `closeMenu` | – | the ancestor rule (§3.2) |
  | ├ `JButton` "Close the desk" (the root pane's default button) | `closeDesk` | `closeRequests` | every route, ENTER's default button |
  | └ `IconKey("CLOSE")` (the enricher names it `CLOSE`; `{action: CLOSE}` resolves) | – | `closeRequests` | the `ActionFirer` path never fires a vetoed key |
  | `JButton` "Print test slip" | `printTestSlip` | `testSlips` | an allowed sibling |
  | `JTextField` | `toolsNote` | – | ENTER with a target |
  | a null-layout `JPanel` holding `visiblePane` (180 wide) and `collapsedPane` (0 wide), each with a `JButton` "Print slip" | `printSlipVisible`, `printSlipHidden` | `slipsVisible`, `slipsHidden` | §3.3 (`{text: "Print slip"}` is unique only with the rule) |
  | `JScrollPane` (viewport 120 high) over a 400-high panel with a `JButton` "Reprint slip" at y 300 | `toolsScroll`, `reprintSlip` | `reprints` | the viewport exemption |
  | AWT `java.awt.Panel` "Scanner" (`FlowLayout`) | `scanner` | – | §3.4 |
  | ├ `java.awt.Button` "Scan" | `scanButton` | `scans` | §3.4, §3.5 |
  | ├ `java.awt.Checkbox` "Deposit box lid open" | `lidOpen` | `lidChanges`, state `lidOpen` | §3.5 |
  | └ `JButton` "Re-read member card" | `rereadCard` | `rereads` | a lightweight inside the heavyweight |
  | `JButton` "Open deposit box" | `openDepositBox` | `depositBoxOpens` | a Swing button routed from the window |

  `DeskTools` is built only with a display (the AWT constructors throw `HeadlessException`; the desk already guards
  `options.ui()`); `LendingPlugin` is not used.

  **As built (Phase 8, EA, 2026-10-19).** `lending.tools` accepts `true` or `false` (any case, trimmed; empty is the
  default); anything else refuses to start (`IllegalArgumentException` naming the property, as the desk's other
  properties do). `DeskTools` lays its content out with explicit bounds (null layout; the same geometry on every JDK
  and font): `closeMenu` (8, 8, 384 x 64; `FlowLayout` holding `closeDesk` and the `CLOSE` key at 56 x 28),
  `printTestSlip`, `toolsNote`, `openDepositBox` on one row at y 78, the frame set at y 112 (`visiblePane` 180 wide,
  `collapsedPane` 0 wide, each with a "Print slip" button), `toolsScroll` (8, 154, 200 x 124, the view 170 x 400 with
  `reprintSlip` at y 300) and the AWT `Panel` `scanner` (216, 154, 176 x 124, `FlowLayout`). `DeskTools.Counters`
  holds atomic counters and `lidOpen` (set from the checkbox's item events); `toMap()` is the `tools` section's shape.
  `LendingApp.shutdown` disposes the window. `DeskToolsDisplayTest` (green in the image on Java 25 and Java 8 test
  JVMs) and `LendingOptionsTest` cover it.
- **The idle callback and the idle tick** (§4.1; D535): `EngineListener.idle(LendingState state, long availableMs)`, a
  default no-op, called on the engine thread while it waits for input, right after `inputRequested` and then every
  `idleTickMillis` until an input is taken, never while an input is processed or a prompt is open; `availableMs` is the
  time left until the idle lock fires (`Long.MAX_VALUE` when no lock applies). `Engine.nextInput` polls `min(tick,
  lockDeadline - now)` instead of `take()`/`poll(idleLockMillis)`; the lock deadline is computed when a wait starts and,
  as today, again after a dropped input (the idle-lock timing is unchanged; `EngineTest.idleLockAndUnlock` passes as it
  is). The property `lending.idleTickMillis` (`LendingOptions.PROP_IDLE_TICK`, default 100; 0 = only the call right
  after `inputRequested`), wired in `LendingApp.launch`. **The plant:** `Engine.idleView()` returns an immutable
  `IdleView {state, step, pendingQty, returnMode, lockedFrom, lineCount, idleLockRemainingMs}` and throws
  `IllegalStateException` unless called on the engine thread inside an `idle` callback; `step` and `pendingQty` are in
  no published snapshot, so a correct answer proves the read ran on that thread at that point.
- **The report reader** (§4.4): `LendingDb.readRows(String sql, List<?> params, int maxRows, int timeoutSeconds,
  ReadHandle handle)`, "the desk's report reader, which answers by position": a read-only connection, `setQueryTimeout`,
  rows as `Object[]`, no names; `ReadHandle.cancel()` wraps `Statement.cancel()` while the statement is open;
  `LendingDb.columnsOf(String table)` from `INFORMATION_SCHEMA.COLUMNS` by `ORDINAL_POSITION`, null for an unknown table.
- **The scanner driver** (§7.6; D591): `ScannerDriver`, where a real scanner would hand a raw frame over:
  `LendingInput decode(byte[] frame)` (frame = `STX` 0x02, 1..64 printable ASCII characters, `CR` 0x0D; anything else
  null) and `boolean post(LendingInput input)` (`inbox.offer`); behind `lending.scannerDriver=true`
  (`LendingOptions.PROP_SCANNER_DRIVER`, default false: a training desk has no driver); `LendingApp.scannerDriver()`
  (null when disabled).
- Nothing else: no main-frame change, no table or seed change, no page.

**As built (Phase 8, ET).** `Engine.idleTickMillis(long)` sets the tick before `start()` (`LendingApp.launch` passes
`LendingOptions.idleTickMillis()`; a negative value or a malformed property is an `IllegalArgumentException`).
`IdleView.step()` is the sub-step's name (`NONE`, `QUANTITY`, `SUPERVISOR_CODE`, `SUPERVISOR_PASSWORD`),
`pendingQty()` a `BigDecimal` or null, `idleLockRemainingMs()` a `Long`, null when no idle lock applies. After `Key.QTY`
then `Entry 3` the sub-step has ended: the view shows `pendingQty` 3 and step `NONE`; after `Key.QTY` alone it shows
`QUANTITY` (§13.2 item 11 checks both). Because the engine now reads its listener list while it waits (for `idle`),
`addListener`, `removeListener`, `listenerCount` and the engine's snapshot of the list hold the list's monitor; the rule
"add only while the engine waits for input" stands, and no callback runs under the monitor. `readRows` asks the driver
for `maxRows + 1` rows and returns at most `maxRows`; `ReadHandle` is `LendingDb.ReadHandle`, whose `cancel()` answers
whether an open statement was cancelled; `columnsOf("schema.table")` names the schema, a bare name `PUBLIC`, compared
without regard to case.

### 12.2 The agent module (`skillroy-sample-lending-agent`, Java 8; version `0.2.0` → `0.3.0` in step R)

| Addition | Section | What |
|---|---|---|
| `LendingInputVeto implements InputVeto` | §3.2 | registered in `install` (one line after `CaptureMask`); refuses a component whose `getName()` is `closeMenu` with `"closes the desk (the application would end)"` for every route and `ANY`; null otherwise (names only: no application class is touched). `AppState.appState()` adds `tools: {closeRequests, testSlips, slipsVisible, slipsHidden, reprints, scans, lidOpen, lidChanges, rereads, depositBoxOpens}` **only when the window exists** (absent on every other target: the committed `app.state` shapes are unchanged) |
| `LendingWorker.quiescentPoint()` | §4.1 | one `QuiescentPoint(50, 4)`; `EngineEvents.idle(state, availableMs)` calls `point.runOne(availableMs)` while active (inert after `deactivate()`); `LendingModule.uninstall` closes the point before `worker.shutdown()` |
| the primitive `lending.engine.view` | §4.1 | `APP_QUIESCENT`, budget 20 ms, timeout 5000, read-only: the `IdleView` as JSON; capability `lending.engine.view`, probed: desk running and a point offered (`RunningProbe` plus the point); `LendingPrimitives.NAMES` grows by one; not in the `lending` `app.state` section |
| `LendingRowsSource implements DataSourceProvider`, id **`lending-report`** | §4.2-§4.4 | describe "the desk's report reader, which answers by position"; the five-argument `query()` over `LendingDb.readRows` with `control.onCancel(handle::cancel)`, `columnNames` null, `enforcesTimeout()` true, `lane()` = `lending.reads`, `columns(table)` from `columnsOf`; the lane declared in `install` (`threads 1, queue 2`) and retired in `uninstall`; always registered (D536: a second read path over the same read-only connection adds no exposure) |
| `PasswordMask`'s rules | §4.4, §5.1 | the row rule covers both source ids `lending` and `lending-report` (one line, ET); a second rule answers `<personal>` for `sourceId` `event:staff.signedOn` and column `name` (EW); the class name is unchanged |
| `EngineEvents.stateChanged` publishes **`staff.signedOn {code, name}`** | §5.1 | after `state.changed`, when the state changes to `IDLE` from `PASSWORD` or `SIGNED_OFF` and `engine.status().staff()` is non-null (seed A's `100` is `Alice Archer`: the sample's realistic personal value in an event); topic constant `STAFF_SIGNED_ON` beside `LOAN_POSTED` |
| `LendingClassDeny implements ClassDeny` | §6.5 | registered only when the agent option `lending.hooks.deny` names patterns (comma-separated `ClassPattern`s; invalid → `IllegalArgumentException("option lending.hooks.deny: …")`, the module's FAILED record), answering `the lending module denies <pattern>`; no launch sets it (a unit demonstration of source 3, behind an option) |
| `lending.input`'s result and `EngineEvents`' acknowledgements | §7.2, §7.4 | the result gains `inputId` (minted `lending-<n>` from an `AtomicLong`, or the new optional parameter `inputId` echoed), `consumed` (`CONSUMED` when acknowledged within the call, else `QUEUED`; `ackTimeoutMs: 0` returns at once as `QUEUED`), `taken` with `VALUE_COERCED` when `value.trim()` differs from the sent value and `value` is not in `inv.secretParams()`, `VALUE_DROPPED` when the trimmed value of an `Entry` or `Barcode` input is empty, `ANSWERED_NESTED` with `answeredBy {kind: question, text}` when the status's prompt line at take time differs from the pre-check's; `INPUT_REJECTED` data gains `reason: notAccepted \| inboxFull`; `acked` and `ackMs` stay. The posting and acknowledgement logic moves into a shared helper `InputPosting`. `EngineEvents`: pending entries keyed by identity carry `{inputId, source, promptLine, correlationId, createdNanos}`, kept after the call returned (bound 256), `inputAccepted` publishes `input.acked {source, inputId}` itself when the call has already returned (late, from the engine thread, with the call's correlation id); `lastPrompt()` and `awaitChange(seenRequested, seenPromptSeq, timeoutMs)` beside `awaitRequested` |
| `LendingComposites`: `lending.signOn {staff, password?}` and `lending.scanChecked {barcode, inducted}` | §7.5 | `lending.signOn` (`APP`, mutating, 15 s, capability `lending.signOn`): pre-check state `SIGNED_OFF` (`INPUT_REJECTED` reason `notAccepted`); post `Entry` staff code; wait; a prompt open (`unknown-staff`, `staff-inactive`, `sign-on-not-allowed`) → branch `refused`, the prompt left open; state `IDLE` → `auto`; state `PASSWORD` with `password` → post it (a secret parameter), wait, branch `password` (`answered` lists both questions, `by: staff` and `by: password`, no `choice`), or `refused` on `wrong-password`; state `PASSWORD` without `password` → branch `passwordRequired` and the pack code **`lending.PASSWORD_REQUIRED`** through `inv.warn`, the desk left waiting. `lending.scanChecked` (capability `lending.scanChecked`): pre-check `Barcode`; post it; wait for the engine to wait again or to show a prompt; `induction-check` → answered with `inducted ? Yes : No` through `app.prompts().answer`, `answered: [{question: {topic: prompt.waiting, id: induction-check, seq}, by: inducted, choice}]`, branch `induction`; another prompt (`unknown-item`, `item-not-for-loan`) → `refused`, left open; none → `plain`. Both return `inputId`, `inputIds`, `consumed`, never a password |
| `ScannerDevice` (a second `DeviceAdapter`; id `scanner`, kind `scanner`) | §7.6 | action `scan {barcode, minRung?, inputId?}`, `rungs("scan") = [PHYSICAL, SEMANTIC]`, `state() = {driver: bool, scans: n}`: pre-check `Barcode` as `lending.input` does; driver enabled → `PHYSICAL`: `decode` the frame built from `barcode` (a code with a control character or over 64 characters is `INPUT_REJECTED` reason `malformedFrame`) and `post`; driver absent → `minRung: PHYSICAL` is `CAPABILITY_UNAVAILABLE {capability: lending.scanner.physical, rung: PHYSICAL, minRung}`, else `SEMANTIC` through the inbox with `fidelity: DEGRADED, reason: "scanner driver not enabled"`; then the acknowledgement as `lending.input`; result `{rung, requestedRung: PHYSICAL, fidelity, reason?, minRung?, accepted, inputId, consumed}`. Capability **`lending.scanner.physical`**: `OK` with the driver, else `UNAVAILABLE("scanner driver not enabled: launch the desk with -Dlending.scannerDriver=true")` |

**The counts** (D630, D641). `LendingModule.install` makes **16 registrations across 14 SPI types** (the thirteen of
0.2.0 plus `InputVeto`; `DataSourceProvider` and `DeviceAdapter` twice), **17 across 15 types** with `lending.hooks.deny`
(`ClassDeny` is a new type). `LendingModuleTest.installRegistersEverythingAndTouchesNoApplicationObject` (`:73-100`)
asserts one registration per type, the total (`13`) and the literal primitive and probe lists; under the branch-truth
rule (D641) each package edits those assertions on its own branch to its own branch's truth (EA `InputVeto` and the
count; ET `DataSourceProvider` 2, `lending.engine.view`; ES `DeviceAdapter` 2, `lending.signOn`, `lending.scanChecked`,
`lending.scanner.physical`; EH the conditional `ClassDeny` in its own method) and the integrator resolves the literal
conflicts to the values above at merge; no branch carries the test red. The module declares **four new capability ids**
(`lending.engine.view`, `lending.signOn`, `lending.scanChecked`, `lending.scanner.physical`), all in the five goldens;
`LendingPrimitives.NAMES` grows by three. `LendingModule.VERSION`'s Javadoc keeps its rule ("whenever the module's
primitives or SPIs change"); the one bump is 0.3.0.

**As built (Phase 8, ET).** `LendingRowsSource` names its lane only after the module's `declareLane` succeeded (an agent
without lanes, a test broker for example, leaves `lane()` null and the reads on a worker; the module logs it). Its
cancel action wraps a `SQLException` from `ReadHandle.cancel()` in an `IllegalStateException` that carries the
exception's class only. `EngineEvents.idle` finds the module's point once through `ctx.spi()` (the `LendingWorker` among
the registered providers), so `ListenerSlot` is unchanged. `lending.engine.view`'s capability probe answers the
strategy `quiescent point` (in the goldens from step R). `lending.engine.view`'s body reads only
`Engine.idleView()` and never touches the listener slot (it runs inside an engine callback, where listeners may not be
added).

### 12.3 The pack (`skillroy-sample-lending-pack`; version `0.6.0` → `0.7.0` in step R), the overlay, the web host

- **Pack capabilities and pre-flight scope** (§9; D620): `capabilities()` = one `PackCapability`
  **`lending.returns.ceiling`** (`CAP_RETURNS_CEILING`, "the desk has a system maximum return configured", scope
  `SCENARIO`): reads the `desk_setting` row named by the setting `lending.maxReturnSetting` (`max_return`; the branch
  overlay's `return_ceiling`) through `ctx.data()` on source `lending` (`SELECT value FROM desk_setting WHERE name = ?
  ORDER BY name`, 1 row, 10 s); `OK` when exactly one row holds a decimal, else `UNAVAILABLE("no system maximum return
  configured (desk_setting <name>)")`; a `DataException` → `UNAVAILABLE("the lending database could not be read
  (<kind>): <message>")`. `DatabaseCheck` (`lending.database`) gains `scope() = CheckScope.SUITE` (the fact it checks is a
  suite fact; one record per run stays). The Javadoc line "`0.7.0`: `capabilities()`, `warningCodes()`, `ackByInputId`,
  the three verbs and the suite-scoped database check (phase8-contracts.md 7, 9)".
- **Semantic input** (§7): `SyncPolicy(..., ackByInputId = true)`; `warningCodes() = Set.of("lending.PASSWORD_REQUIRED")`;
  new verbs (schemas in `LendingStepSchemas`): `signOnAny: {staff}` → `lending.signOn {staff: $x.code, password:
  $x.password}` (`password` only when `ctx.slot(x)` declares a credential), `READY`, cleanup `Key.SIGN_OFF` as `signOn`;
  `scanChecked: {item|barcode, inducted}` → `lending.scanChecked`, `READY`; `deviceScan: {item|barcode}` → `device.act
  {id: scanner, action: scan, args: {barcode, minRung?}}` with `rungs [PHYSICAL (lending.scanner.physical), SEMANTIC
  (null)]`, `READY`, `minRung` forwarded from `ctx.minRung()` or `PHYSICAL` under `strict`. `signOn`, `scan` and the
  other verbs are unchanged (their plans change by version and pack version only).
- **The password mask** (§4.4): `LendingTables.PASSWORD_MASK` covers `lending-report` as well (a lambda change, no
  catalogue text); `LendingAllowList.LIST`, `LendingTables.ALL`, the prompts and the catalogue text are untouched.
- **The hook catalogue** (§6.8): `lending.yaml` `version: 2`; `packages: [{name: ai.skillroy.testjava.samples.lending,
  codeSource: 'skillroy-sample-lending-app(-[^/]*)?\.jar'}]` (the IT copy and a versioned jar match; every enabled entry
  inherits it); `deny: [ai.skillroy.testjava.samples.lending.Inbox]` (the desk's input queue: every input source posts to
  `offer`, the engine takes from it on every input). `lending-branch.yaml` is unchanged: its `limit.applied` on
  `ReturnCeilings` (in the app jar) inherits the qualifier from the merged rules.
- **Scenario A** (`return-over-limit-needs-supervisor.yaml`) gains `lending.returns.ceiling` at the end of its
  `requires:` (it resolves `SystemLimit`, which reads that row): the gap 24 proof.
- **The branch overlay** (`skillroy-sample-lending-pack-branch`): unchanged in code; its three plans and bindings are
  regenerated in step R; `BranchPack` reads `return_ceiling` through `settings()`.
- **The web host agent module** (`skillroy-sample-web-host-agent`, Java 8; `0.2.0` → `0.3.0` in step R; §5.2): the
  option `webhost.mask` takes a comma-separated list: `front` (as today, the `CaptureMask` `FrontCardMask`);
  `release-detached` registers a `BrowserMask` (`DetachedRelease`) answering FALSE for tag `detached`, null otherwise;
  `tag-back` registers a `BrowserMask` (`BackTagMask`) answering TRUE for tag `back`, null otherwise; an unknown value is
  logged at WARN and ignored. The web host, its pack and its scenarios are unchanged (`detached` already exists off
  screen); §13.2 item 16 extends `WebHostChecks`' gap-95d item (`@Order(14)`) and items 17 and 18 are new items
  (`@Order(16)` and `@Order(17)`; orders 1-15 are taken today).
- **Test fixtures** (the scenarios module): `HeadlessDesk`, `FakeDesk` and `LendingFixture` mirror `lending.input`'s
  result and the `input.acked` payload (`inputId`); `HeadlessDesk.matches` goes through `PayloadMatch`; `HeadlessDesk`
  mints an agent session per `launch` and reports it in `ping()`, `Recording` and `DeskConnection` forward
  `agentSession()`, `ResolutionChecks.beforeRequires()` gains `data.query` after `capabilities`; `HeadlessDesk.hookHealth`
  echoes `deny: {catalogue: Runs.PACK.hooks().deny(), from: ["lending"], option: [], modules: 0}` and `classes: []`; a
  test-only `PositionalOverlayPack` (the `OverlayPack` pattern) re-sources `loan`, `loan_line`, `loan_authorisation` to
  `lending-report`; `LST/SecretScan.java` is deleted for `OutputScan` (§5.1).

### 12.4 The Phase 8 scenarios (`skillroy-sample-lending-scenarios/scenarios/`, seed A, no bindings file unless said)

| Scenario | What it shows | Replay outcome |
|---|---|---|
| `loan-counts-and-events.yaml` (tags `smoke, expectations`; §8) | `captureFacts` of the scan, sign-off and line counts after sign-on; `scan` twice; `"loan.lines[*code=$item.code]": {size: 1}`, a delta `{size: {gt: $before.lines}}` on `loan.lines` and on the filtered `events` root, `containsWhere` on `loan.lines`; `key DUE`; `waitFor` with `{has: Key.DEPOSIT}`; a `mark` planting JSON text read back through `[json]`; `deposit`; `verify`: `{size: 0}` on `loan.authorisations[*kind=RETURN_OVERRIDE]`, `containsWhere` on `loan.deposits`, `anyOf` on `slip.last.text`, a regex on the event source, "no sign-off since" as `{size: $before.signOffs}`; the committed plan carries version 7's new forms | `PASS` in every load mode (its id added to the JUnit glue's list, §13.2 item 32) |
| `semantic-input-warnings.yaml` (§7.1) | `scan` of `basic-loan`'s barcode with surrounding spaces and `accept: [VALUE_COERCED]`, the loan line asserted; `enter: {value: "   "}` with `accept: [VALUE_DROPPED]`; cancel; sign off | `PASS` with two `warning` records `accepted: true` |
| `sign-on-any.yaml` (§7.5) | `signOnAny` with `$auto` (a fixture `{code: "103"}` without a credential → branch `auto`), sign off, then `$assistant` → branch `password`, sign off; `state.staff.code` asserted after each | `PASS` |
| `scan-checked.yaml` (§7.5) | `scanChecked` of the induction-required item `LAD-001` with `inducted: true`, then a plain item | `PASS`, the `prompt` record `expected: true, answeredBy: lending.scanChecked` |
| `device-scan.yaml` (§7.6) | `deviceScan` of a barcode on a desk without the driver | **`DEGRADED`** in every standard leg (rung `SEMANTIC`, one `fidelity` record), filtered out of the JUnit glue's `PASS`/full-fidelity loop and asserted so beside `loan-table-visual-check` (§13.2 item 32); `PASS` at `FULL` on the scanner leg |
| `scanner/device-scan-physical.yaml` (§7.6) | `deviceScan` with `minRung: PHYSICAL`; `devices[id=scanner].rungs.scan[0] == PHYSICAL`. A committed scenario in a sub-directory the JUnit glue's directory source does not descend into (`ScenarioSource.directory` is not recursive, as for `exported/`): `PlansUpToDateTest.committedScenarios()` gains `scenarios/scanner/*.yaml` (today `Files.list(scenarios)` without descent, `:60-64`), its plan is `plan/device-scan-physical.plan.json` beside the others (so `:109-110`'s one-plan-per-scenario count holds), and `ScenarioSchemaCommittedTest.everyScenario()` counts it through `committedScenarios()` (`:59-67`) | `ERROR / PRECONDITION_FAILED` on a standard desk (no step call sent); `PASS` with `rung: PHYSICAL` on the scanner leg |
| `return-over-limit-needs-supervisor.yaml` (scenario A, changed; §9.4) | `requires:` gains `lending.returns.ceiling`; its two plans (`plan/` and `variants/branch/plan/`) and three bindings files (`seed-a`, `seed-b`, `branch-a`) change their `planHash` in step R | `PASS` on both seeds and the branch, as today |
| the negatives (`src/test/resources/negative/`) | `vetoed-click.yaml` (§3.2: a `click` on `closeDesk` with `lending.tools: "true"`: `FAIL / ASSERTION` naming `INPUT_REJECTED` and `vetoed`, `recover` runs, `closeRequests == 0`); `warning-not-accepted.yaml` (§7.1: the trimmed scan without `accept`: `FAIL / ASSERTION` naming `VALUE_COERCED`); `accept-unknown-code.yaml`, `min-rung-without-ladder.yaml` (compile errors: `PlansUpToDateTest.theNegativeScenariosCompile`, `:248-262`, which today expects every file in `negative/` to compile, expects a `ScenarioException` naming the message for these two and a plan for the rest, D642); `sign-on-any-password-required.yaml` (`$assistant` without a credential: `FAIL / ASSERTION` naming `lending.PASSWORD_REQUIRED`; recovery signs off); `count-delta-fails.yaml` (§8.7: a count captured, no scan, `{size: {gt: $before.scans}}`: `FAIL / ASSERTION` with the captured operand in the message) | as named |
| `positional/positional-loan.yaml` (test-only, under `PositionalOverlayPack`; §4.4) | lives under `src/test/resources/positional/`, is compiled at test time by the positional headless test under `PositionalOverlayPack`, has no committed plan and is counted by no pinned test (nothing walks `scenarios/positional/`, so it is not put there); a `db.loan … rows: 1` check and a `rows: 0` absence check over the positional source | `PASS` (fails today with "returned 0 columns") |

Nothing in them names a person or an item beyond the seeds' fixtures. `sign-on-any`'s committed plan carries a call
argument **named** `password` (`"password": "$assistant.password"`: `signOn` compiles to `lending.input` calls keyed
`value`, `LendingSteps.java:147`, `signOnAny` does not), which `PlansUpToDateTest.noScenarioOrPlanHoldsAPassword`
(`:278-289`) refuses in any file under `plan/` today; the test allows a `"password"` key whose value is exactly a
`$<slot>.password` reference to a slot that declares a credential, and refuses every other `"password"` or `password:`
(the intent, no literal password, is kept; renaming the parameter is rejected because `password` is the name the
agent's secret-key rule masks, D642; Q63). The pinned lists move under the branch-truth rule (D641): each package adds
its list lines on its own branch and the integrator resolves the literals at merge to `PlansUpToDateTest` (the
committed list of 17 names with `scanner/`'s one, `SEED_A` with the new seed-A scenarios, 17 negatives),
`BindingsUpToDateTest` (its "needing bindings" list untouched: no new scenario needs bindings, `needsBindings()`
covers query slots and images only; scenario A's rehash is step R's) and `ScenarioSchemaCommittedTest` (**22 → 34**:
the eleven committed scenarios plus five and `scanner/`'s one, and the eleven negatives plus six).

**As built (Phase 8, EX).** `loan-counts-and-events` captures **after the first scan, on the open loan**, not right after
`signOn`: `lending.loan` answers the open loan, else the most recently recorded one (`LendingPrimitives.loan`), so on a
desk that recorded an earlier loan (every `LoadModeChecks` leg, the JUnit glue) a `lines` count taken after sign-on is that
loan's, and the delta `{size: {gt: $before.lines}}` would fail whenever it had one line. As built: `signOn`; `scan`
once; `captureFacts before {scans, signOffs, lines, qty}` (`scans` 1, `signOffs` 0, `lines` 1, `qty` "1" on every
desk); `scan` again; `expect` `[*code=$item.code]` `{size: 1}`, `loan.lines` `{size: $before.lines}` (the second scan
adds to the line), the line's `qty` `{gt: $before.qty}` (a delta of a value) and the Barcode acknowledgements `{size:
{gt: $before.scans}}`; the rest as the table says. The negative `count-delta-fails` is as specified (its step timeout
5 s). `PlansUpToDateTest.thePlansUseEventDrivenSyncOnly` admits a `mark` step's `trace.mark` (sync `NONE`: the mark
publishes its event before it answers). On EX's branch the lists read: the committed list of 12 names
(`loan-counts-and-events.yaml` added), `SEED_A` with `loan-counts-and-events`, 12 negatives (`count-delta-fails.yaml`),
`ScenarioSchemaCommittedTest` 24, the JUnit glue's 12 ids (D641; the integrator resolves the literals).

### 12.5 Regenerated committed artifacts

The skeleton commit regenerates the version alone (§2.2, §8.2: the 19 plans, `toy-loan.plan.json` among them, and the
17 bindings files' `planHash`, key-free, native). Step R (§2.8) regenerates, once more: every plan's `packVersion` (19
files; scenario A's `requires`; the six new plans), every bindings file's `planHash` and `packVersion` (17 files), the five capability goldens (four
new ids), the five session recordings and both exported YAML heads (pack 0.7.0 and the three new verbs' schemas),
`exported/session-vision-loan.*` in the image, the committed schema copy, the pinned lists and counts, the version
literals and their pins. ET regenerates in its own build the one inference fixture whose request carried the
catalogue's procedures line (`controller/skillroy-inference/src/test/resources/inference/fcbda729…json`:
`LendingFixture.allowList` drops `b.procedure("open_loan_count")`, the old file deleted, the new one made with
`-Dskillroy.inference.fixtures.update=true` on the scripted gateway, no key; `SchemaCatalogueTest` loses its
procedures assertion). Nothing else: no image baseline, no live recording, no web host plan content beyond its
version, no table or seed.

**As built (Phase 8, step R, 2026-10-19).** Scenario D (`supervisor-return-llm-sql`, scenario A with the supervisor
found by a model) gains the same `requires` entry `lending.returns.ceiling` as scenario A (D620, Q53 named A only):
`PlansUpToDateTest.scenarioDsPlanIsScenarioAsWithTheSupervisorFoundByAModel` pins D's plan to A's but for the
supervisor's strategy, so A's new entry made it red after the regeneration. D's tier (b) request does not carry the
scenario's `requires` (the model's request is the slot's query, the catalogue and the allow-list), so its two live
recordings are untouched; its plan and its two bindings files' `planHash` changed, `resolveHash` did not. The script
ran its steps in the order 1, 2, 3, 5 (native), 4, 5 (image), 6: the image run of step 4 executes the scenario
module's unit tests first, which need step 5's recordings. Results: the five goldens gain exactly the four ids of
§2.4 (`lending.engine.view`, `lending.signOn`, `lending.scanChecked` OK; `lending.scanner.physical` UNAVAILABLE), no
`allow-differences.json` change; `LendingEndToEndIT` green in the image on a Java 25 and a Java 8 target; the five
session recordings replaced (five files), both exported heads and `session-vision-loan.*` regenerated; the lists at
17 committed scenarios, 17 plans, 17 negatives.

---

## 13. Tests and acceptance

Every test below fails without its change where that is meaningful (each fragment's rows say which); no test needs a
model key; the display tests extend `DisplayTestBase` and skip when headless. The tag in the module cell names the
package that writes the test (§2.5).

### 13.1 Without a display (unit and module tests; the normal build; no key)

| # | Module | What is proven |
|---|---|---|
| 1 | `agent-api` (EA) | `spi/InputVetoShapeTest`: the eleven constants' values, `refuse` the only abstract method, an anonymous implementation compiles at `--release 8`; `package-info` names the SPI |
| 2 | `agent-swing` (headless) (EA) | `InputVetoesTest` on `FakeContext` and an unshown tree: the ancestor rule (nearest refusal wins, `vetoed` names it, ≤ 64 levels), memoisation (one ask per component and route), text sanitising (control characters, 200 cut, empty text), a throwing veto refuses with the fixed text and logs once, a `VirtualMachineError` rethrown; `VisibilityTest` (headless, so `area` and `collapsedBy` over a root container: `isShowing()` is false for every headless component and no window exists; `onScreen` and the outside-the-window case belong to `CollapsedPaneDisplayTest`): zero-width ancestor, zero-height self, outside the parent, a `JViewport` view scrolled out (a non-empty area), a nested viewport inside a collapsed panel (collapsed), `collapsedBy` names the culprit (AWT `Panel` and `Canvas` construct headless; `ScrollPane` is display-only); `PointerTest`: the `Plan.veto` seam: a refusal before the press dispatches nothing (`dispatched 0 of 4`, or 1 with the move), a refusal after the press withholds the rest, balances it (`MOUSE_DRAGGED` then `MOUSE_RELEASED` count 0) and reports `reason: VETOED` with `vetoed {ref, veto, route}`, `dispatched 2 of 4`; `force` does not skip it; `events()` with a non-window source (converted coordinates, `xAbs` unchanged), the extra `MOUSE_EXITED` on a source change, the balance into a leaf source (drag, exit, release count 0, no click); `HitTestLocatorsTest`: `sourceAt` on a fake tree whose root is a container (no `Window` can be built headless, which is why the signature takes a `Container`, §3.4 rule 1); the heavyweight stand-in is a test subclass overriding `isDisplayable()` (true) and `isLightweight()` (false), neither final; the claimed-surface exception keeps the root; `mouseTarget` below the stand-in over children overriding `isLightweight()` (true), since unshown Swing components report false headless and `HitTest.java:386`'s filter would find nothing; `RolesAndTextTest.nodeShape` unchanged (no `vetoed`/`collapsed` key without one), a vetoed node carries `vetoed: true` after `masked`, a collapsed one `collapsed: true`; `ClickFidelityAndDialogShapesTest`: `Clicks.semantic` returns null for a plain `JPanel` and `doClick` for an `AbstractButton` (the AWT cases are display tests) |
| 3 | `skillroy-vision` (EA) | `VisionPartsTest`/the recorder tests with a `FakeVisionAgent` hit `vetoed: true`: `pointer` on every gesture, `type` and `key` at a vetoed focus owner, V2's raw pointer, each `Recorded.refused(VETOED)` with nothing sent or journaled; a hit without the key (a 0.1.0 agent) is not refused for that reason |
| 4 | `skillroy-scenario` (EA) | `UiSnapshotTextTest`: the flag `vetoed` rendered after `selected`; absent key renders nothing |
| 5 | the sample (headless) (EA) | `LendingInputVetoTest`: `closeMenu` refused on every route and `ANY` with the text, a descendant allowed by the veto itself (the module's ancestor rule does the rest), anything else null; `LendingModuleTest`: `InputVeto` registered (its line in the type loop and the count, edited to EA's branch truth and resolved at merge, D641); `LendingOptionsTest`: `lending.tools` parsed, default false; `PlansUpToDateTest` (the negative list, likewise), `ScenarioSchemaCommittedTest` (likewise; 34 at integration), `OutputScan` (§5.1) over the new files |
| 6 | `agent-api` (ET) | `DefaultMethodsTest`: `quiescentPoint()` null, `lane()` null, `columns(t)` null, `enforcesTimeout()` false, the five-argument `query` delegating to the four-argument one, `callQuiescent`/`declareLane`/`callOnLane` defaults throwing; `ValueTypesTest`: the seven names in order; `PrimitiveSpecTest`: budget only with `APP_QUIESCENT` (1..1000, default 50), `lane` only with `LANE`, descriptor keys written only when set and read back leniently, equality; new `QuiescentPointTest`: FIFO; `runOne` runs at most one; budget against `availableMs` and against the caller's remaining time (withdrawn `NOT_READY` with the reasons of §4.1); the withdraw-or-run race over many threads (every task ran exactly once or its caller got `NOT_READY`, never both, never later); capacity -> `BUSY`; a throwing task's exception reaches the caller; `runOne` never throws or blocks; an `await` on the thread that runs `runOne`, and one from inside a task of the point, refused `FORBIDDEN_THREAD` at once with the engine thread not held (D639); `timeoutMs` above 120,000 refused; `close` withdraws and later `await` is `CAPABILITY_UNAVAILABLE`; `stats()` |
| 7 | `agent-core` (ET) | new `QuiescentPolicyTest` (`CoreTestModule`'s provider gains an "engine" thread named with the forbidden prefix that calls `runOne` on a pausable tick, and a `test.quiescent.view` primitive with a budget): the task runs on the forbidden engine thread and only there (`thread` in the result); correlation id and events bound; paused -> `NOT_READY` within the timeout; never runs after its caller gave up (pause past the timeout, resume, body count 0); budget above the allowance, no provider, null point -> `CAPABILITY_UNAVAILABLE` with the reasons; `available()` false -> `NOT_READY` for a read and a mutating spec alike; a nested `call` from inside -> `FORBIDDEN_THREAD`, a `post` and a `publish` allowed; an overrun counted in `agent.info.threads.quiescent`, a 2x overrun -> `CAPABILITY_UNAVAILABLE` until `reprobe`; a task running past its caller's deadline -> `TIMEOUT` then `BUSY` until it ends (S30); `post(APP_QUIESCENT)` runs nothing anywhere; disable and close withdraw queued tasks and call `close()`; the engine thread is never interrupted (`isInterrupted()` checked in the body). New `LaneTest`: declaration bounds and the grammar; thread names; FIFO; queue full -> `BUSY lane full`; queued at expiry never runs; stuck -> `abandoned` and `BUSY lane stuck`; retire on handle close, on `rollback()`, after `uninstall` (`ModuleContext`); close leaves no `skillroy-lane-*` thread (as `ConcurrencyTest.closingUnderLoadEndsEveryRequestAndLeavesNoThread`); an `APP` call answers while a lane is stuck; a `LANE` primitive through the dispatcher with the S30 gate and an undeclared lane's `CAPABILITY_UNAVAILABLE`; `post(LANE)` refused; `agent.info.threads.lanes`. `ThreadPolicyTest.eachPolicyRunsWhereItSays` and `ConcurrencyTest` keep their explicit lists (`test.where.appQuiescent` and `test.where.lane` are registered by the fixture's loop over `values()` and need a point and a lane in the fixture) |
| 8 | `protocol` (ET) | new `SelectLabelsTest` (Java 8): a corpus of accept forms (aliases with and without `AS`, quoted identifiers of every dialect, `DISTINCT`, `TOP`, `WITH`, nested parentheses and commas inside functions and `CASE`, comments between tokens, `SELECT 1`) and give-up forms (`UNION`, `*`, `t.*`, depth beyond 32, 1001 items); `starTable` forms (alias, schema part, a join -> null); never throws on any `StatementGuardTest` input; `StatementGuardTest` unchanged and green after the `SqlTokens` move; `ReadOnlyJdbc`'s sink overload (`JdbcSpy`: the statement handed over after `prepareStatement`, null after close; a cancel during `executeQuery` ends it with an `SQLException` and `restore` still runs; no cancel after close); `ProtocolConstantsTest` pins 0.2 |
| 9 | `agent-data` (ET) | `ProviderQueryFormTest`: derived names; the provider's names win; a derived list of the wrong size ignored with the log line; zero rows with a readable list give the width; types-only zero-row width; `maxRows: 0` returns the columns; masks see the derived name (a `PasswordMask`-shaped policy fires on `SELECT code, password FROM staff` over a nameless provider); **`noRowsAndNoNamesGiveNoColumns` becomes "a statement the reader gives up on gives no columns"** (`SELECT 1 UNION SELECT 2` is refused by nothing in `StatementGuard`; or rename to the one-column `c1` case); the control: `remainingMs` passed, a cancel action runs once at expiry on a nested worker task that is not the dispatching worker (thread names recorded), one registered after cancellation runs at once, a throwing action is logged by class name, a blocking action is abandoned on its own task and logged by class name while the `TIMEOUT` answer arrives inside the reserve with `cancelPending: true` and a `data.query` of another source answers meanwhile (D638), a fifth action refused, a four-argument-only provider still works, the `TIMEOUT` data keys, `stillRunning` and the per-source `BUSY` until a fake read ends, a source with a lane runs there (thread name recorded), a stuck read on source A leaves source B answering; the star form: `columns(t)` asked at most once and only for the star form (a counting provider), null and throwing providers as today plus the log line, a size mismatch ignored; `data.sources` shows `lane` and `enforcesTimeout`. `DataQueryJdbcTest`: `JdbcSpy` records `PreparedStatement.cancel` on expiry and never after close; a real slow statement on `TestDb` (the four-way cross join of `theQueryTimeoutStopsARunawayStatement`) with 1000 ms is answered `TIMEOUT {cancelled: true, stillRunning: false}` and the next query answers; `queryTimeoutComesFromTheRemainingTime` unchanged; `DataModuleTest`: version 0.2.0, the spec unchanged (four parameters, `WORKER`, 10000). `FakeAgentContext` (data tests) gains a broker with lanes and a worker (or uses the core's) |
| 10 | `skillroy-inference` (ET) | `SqlGuardRulesTest.anAllowListedProcedureIsCalledWithParametersOnly` becomes "every `CALL` is refused `PROCEDURE` naming gap 119"; `theOutputCheckRefusesWhatDoesNotReadBackAsItself` loses its `CALL` line; `SqlGuardCorpusTest` with the moved entries (its minimum counts hold); `AllowListCheckTest`: the hint over a fake channel answering no column and `c1..c3`, and the unchanged pass over the BS6 twin schema; `InferenceContributorTest`: a list with a procedure -> `ContributionException` naming gap 119; `SchemaCatalogueTest`: no procedures line; `LlmSqlResolverTest` over the regenerated fixture; `SqlAllowListTest` keeps the builder working under `@Deprecated` |
| 11 | `skillroy-mcp`, `skillroy-scenario` (ET) | an MCP start with a pack whose list names a procedure -> `ENVIRONMENT` naming gap 119 (`PartsTest`/`ToolCallTest` style, `Desk`); `DataQueryLists.forDataQuery` writes an empty list; `ExecutorPhase2Test` gains a `db.*` check whose fake `data.query` answers `c1..cn` names by position, and a zero-row one that holds (no controller code change for 12; the test documents the executor's position rule over a positional source) |
| 12 | the sample's desk (ET) | `EngineTest`/`EngineEdgeTest`: `idle` after `inputRequested` and per tick, not during a prompt or an input; `availableMs` counts down with a lock, `Long.MAX_VALUE` without; `idleLockAndUnlock` unchanged and green; `idleView` throws off-thread and outside the callback; `readRows` by position, `columnsOf`, `ReadHandle.cancel` ends a running statement; the `Recorder` test listener unaffected (default method) |
| 13 | the sample's agent module (ET) | `LendingModuleTest`: `DataSourceProvider` 2, the primitive and probe lists with `lending.engine.view` and the count, each edited to ET's branch truth and resolved at merge (D641), the lane declared and retired; `LendingPrimitivesTest` (in-JVM desk through `LendingFixture`): the view on the engine thread with `pendingQty` after `Key.QTY` + `Entry 3`; `NOT_READY` with a prompt open; **admission by `availableMs`**: with `idleLockSeconds 1` and the 20 ms budget, a call made when less than 20 ms remain before the lock is not run until the next point after `LOCKED` (the module-level home of this proof: it needs a desk with a 1 s lock, which no shared end-to-end desk can carry); inert after `uninstall`, the point closed; the planted-secret twin of `dataQueryNeverReturnsAStaffPassword` on `lending-report` (`SELECT code, password, name FROM staff ORDER BY code` and `SELECT s.password FROM staff s WHERE s.code = ?`: every password `<redacted>`, no seeded password in the result; fails today, the mask sees `c2`); a slow read on `lending-report` with a short `remainingMs` -> `TIMEOUT {cancelled: true, lane: "lending.reads"}` |
| 14 | the sample's scenarios (headless) (ET) | `PositionalOverlayPack` and the positional scenario compile (`PlanCompiler` on the overlay); the goldens' expected states reported for step R (`CapabilityGoldenTest` tests `CapabilityGolden.normalise`/`compare` on hand-built matrices and the committed files' well-formedness, so a new agent id changes nothing it asserts and it passes before and after; the live comparison is the image's `LoadModeChecks` check 1c, §13.2); `OutputScan` (§5.1) over every new file |
| 15 | `agent-core` (EW) | `EventBusTest`: a policy masks a top-level and a nested field by `event:<topic>` and dotted column; list elements add no segment; policies in registration order; a non-scalar return → `<redacted>`; a throwing policy → `<redacted>`, logged once, the seq returned, `maskFailures` 1; no policy and no secret key → the stored payload `equals` the published one; the stored, delivered (a sink), awaited, `since` and call-scope copies are the one masked event; `payloadMatches` on a masked field; the character bound honoured after masking (`x` → a long value); the 10,000-scalar budget (`<redacted>`, `truncated: true`, `overBudget`); `slowPasses` and `maxPassMs` pinned with a sleeping policy, during whose sleep a second publisher completes (the pass holds no ring lock); the key rule with the protocol's rule, the `redact` option and a `RedactionKeys` (a nested map under a secret key is one string); `SecretParamsTest` unchanged and green; `ConcurrencyTest`: publishers on several threads with a policy. A core end-to-end test with `AgentHarness` and a fixture module that publishes from `install`'s context and from a primitive's `inv.context()` (`Dispatcher.java:302` hands the core context): both masked; the SSE stream (`SseTest`) sees the masked payload; `CorePrimitivesTest`: `agent.info.events` |
| 16 | `agent-api` (EW) | `WebSpiShapeTest`: `BrowserMask` in `TYPES`, its one method, no AWT or vendor type; `DefaultMethodsTest` unchanged (no default added); the `MaskPolicy` Javadoc has no test |
| 17 | `agent-web` (EW) | `MaskedBrowserTest`, a view-less `ScriptedBrowser` (`component = null`) with a seed tag: (a) nothing registered → listed with URL and title, reads served, `masksRegistered: false`; (b) any `CaptureMask` registered → `masked: true`, `url: ""`, no `title`, frames blank, the four reads `INVALID_PARAMS masked`, `redact: false` reads, `walk.stateReads` unchanged, `masksRegistered: true`; (c) a `BrowserMask` FALSE by tag → released; (d) TRUE by tag on a browser with a showing component and no `CaptureMask` → masked on every route; (e) TRUE by vendor object for an untagged browser; (f) a throwing `BrowserMask` → masked; (g) the browser gains a component at the next rebuild → the component rule applies, a FALSE no longer releases it when a `CaptureMask` names it; (h) union: one TRUE over a FALSE; (i) a `web.pointer` on a masked browser carries no `hit` while a control browser without masks has one; `web.dom.click {selector: "*"}` on a masked browser answers `AMBIGUOUS` whose `candidates` carry `role` and `tag` only with `count` kept, and a successful `web.dom.click`/`web.dom.fill` answers `clicked`/`filled` without `name` or `id`; `redact: false` keeps them (§5.2 (iv)). `CallPolicyArgumentsTest` (`skillroy-inference`, the test that pins `MASKED_WEB_READS`, `:131`): `redact: false` on `web.pointer`, `web.dom.click` and `web.dom.fill` refused for a model or a client. `BrowserIndexTest`: `Entry.masked` per entry after a rebuild; one `SeedWalk.masked` read per rebuild only while masks are registered. `EventsAndTapTest`: a masked browser's `web.load`, `web.ready`, `web.console`, `web.tap` shapes of §5.2 (i); unmasked unchanged. `SelectionTest`: summaries withhold a masked entry's URL and title; `by: {url}` and `by: {title}` never match a masked entry (`NOT_FOUND` with the withheld summary); `by: {tag}` does. `WebModuleInstallTest`: the new SPI type loads no AWT or vendor class at install |
| 18 | `agent-hooks`, `agent-byteman` (EW) | `CaptureTest`, `BindingEncoderTest`: a `CAPTURE` / `rule.fired` payload published on a bus with an idempotent policy is unchanged by the second pass |
| 19 | `skillroy-cdp` (EW) | `ConnectSupportTest`: detection on the real 1.63.0 class finds all three; `of(…)` seams. `CdpAttachTest`: with all present the existing expectations (`:96-134`); with `noDefaults` absent and no opt-in `connect` is never reached (the path's refusal is `CdpWebPathOpenTest`'s), with the opt-in the fake factory's options have `noDefaults == null` and a WARN line; with `artifactsDir` absent a traced open passes no directory and starts no tracing; a fake factory whose `connectOverCDP` throws `NoSuchMethodError` → `CdpException(PROTOCOL)`, the fake Playwright closed once, no thread left (the leak fix; fails today). `CdpWebPathOpenTest`: the `openInfo` key list; `playwright: "1.63.0"` (the manifest) and `connectOptions`; the `noDefaults`-absent refusal reason (a reason string from `open`, no driver started) and the property read; `WebPathTest` (`skillroy-scenario`, `FakeCdpPath`): the reason's fallback under `auto` and `PRECONDITION_FAILED` under `cdp` (the existing reason route, `:246, :293`). `CdpWebPathCallTest`: a view-less browser the agent reports `masked` is handed off (new fixture); an unmapped page's four reads and a `by: {url}` selection handed off while `masksRegistered: true`, served while false, handed off when the field is absent; `web.pointer`, `web.dom.click` and `web.dom.fill` of a masked or unmapped page handed off while `masksRegistered: true` and served while false (§5.2 (iv)); `PathEvents` of a masked page (`url: ""`, no `detail`, `masked: true`) |
| 20 | `skillroy-junit` (EW) | `OutputScanTest` (the moved `SecretScanTest`, on the per-file forms `leaks(Path, Set)` and `leaksIn(String, Set)` it already uses): the three layers; the directory form's `Hit`s; `Scope.EVENTS` reads the four record kinds, `failure/events.json` and `*.xml` and nothing else; `Scope.ALL` every text file; PNGs named, not read |
| 21 | the sample (EW) | `LendingPrimitivesTest`: `stateChanged(PASSWORD, IDLE)` and `(SIGNED_OFF, IDLE)` with a signed-on staff publish `staff.signedOn {code, name}`; `(LOCKED, IDLE)` and `(IDLE, LOAN)` do not; `PasswordMaskTest` (new; today only `LendingCaptureMaskTest` exists): the event rule (`<personal>` for `name` under `event:staff.signedOn` only; `code` untouched; rows as before). `WebHostModuleTest`: the three option values register what §12.3 says, a list of two, an unknown value ignored |
| 22 | `protocol` (EH) | `HookWireTypesTest`: `target.codeSource` round trip; absent key not written and a pinned spec hash unchanged (fails without the change: the key would be refused); violations for an uncompilable regex and for 201 characters, field `target.codeSource`; `HookProbe.codeSources` round trip and lenient read. New `ClassPatternTest`: every form (exact with nested classes, `*`, `**`, a glob in the last segment, the default package), non-matches (`a.b.C` against `a.b.CD`, `a.b.*` against `a.b.c.D`), every violation text, 200/201 characters, `firstMatch` order |
| 23 | `agent-api` (EH) | New `CodeSourcesTest` (Java 8 leg too): every row of §6.2's table through `name(…)`; `file:/x/a%0Ab.jar` → `a b.jar` and a `%1B` likewise (control characters become spaces after decoding); `of(String.class)` is `(none)`; a class defined with no domain (`defineClass` in a test loader) is `(none)`; the 200 cut; a `URL` built with a `URLStreamHandler` whose `toExternalForm`, `hashCode`, `equals` and `openConnection` throw, set as a code source of a class a test loader defines: `of` answers the file name (proves the field-getter rule); `DefaultMethodsTest` (append): `HookEngine.deny()` is empty by default |
| 24 | `agent-hooks` (EH) | `ProbeItemsTest` (+): "a class from another code source": a copy of `Returns` defined by `ChildFirstLoader` from a temporary jar (the test-classes copy reports `test-classes`, the jar copy the jar's name), a qualifier on the jar's name keeps one copy and `codeSources` lists both, a qualifier matching neither is `UNAVAILABLE("class from another code source: test-classes, <jar>")`; "an unreadable code source" through the package-private reader seam of `Probes` (`UNAVAILABLE("the class's code source cannot be read…")` under a qualifier, `OK` with `codeSources: ["(unreadable)"]` without); `codeSources` on the existing OK case; "a pathological qualifier": a backtracking `codeSource` pattern against a 200-character name answers `UNAVAILABLE("codeSource regex too slow")` within `BoundedRegex.BUDGET_MS` (D640); "denied by name before loaded?": the option (`hooks.deny` on the fake context), a registered `ClassDeny`, a throwing `ClassDeny`, each refusing an unloaded fixture name with its reason text, and the option first when two apply. `InstallTest` (+): `touched[].codeSource` and `hook.health.classes` after an install; `catalogue.deny` validated (`INVALID_PARAMS catalogue.deny[1]`, the 65th pattern, the 257th held), merged before the conflict check (a `HOOK_CONFLICT` call still tightens), echoed in the install and `alreadyInstalled` answers, held after `hook.remove {}`, unioned under `replace: true`, held when nothing installs; a hook installed before a deny arrived keeps firing and reads `deniedNow: true`; `HookEngine.deny()`. `HooksModuleTest` (+): `hooks.deny` invalid → FAILED naming the key; the catalogue `ClassDeny` is registered at install |
| 25 | `agent-byteman` (EH) | `ClassBoundTest` (+): a denied `CLASS` name refused before the count (the fake JVM's class list never read), a denied `^` and `INTERFACE` name, a denied matched subtype of an allowed `^` rule; `CheckLoadsNothingTest` (+): `rule.check` lists the deny in `errors`, a confirmed `rule.submit` refused by the deny loads nothing, appends nothing and sets no property; `SettingsTest` (+): `hooks.deny` read, trimmed, 65 patterns and a bad pattern refused naming the key; a `ClassDeny` in `FakeAgentContext.Spi` seen by the engine |
| 26 | `skillroy-scenario` (EH) | `HookCataloguesTest` (+): the map form of `packages`, inheritance by the longest name, an entry's own qualifier winning, the plain form unchanged; `deny` read, `denied()`, `packageRules()`; corpus rows (a bad regex at the `codeSource` line, a `packages` map with an unknown key or without `name`, a bad deny pattern at its line, an enabled entry on a denied class at its `class` line, 65 patterns); the disabled-denied warning; overlay union, an overlay qualifier applying to its own entry only, an overlay deny over an enabled base entry refused at the `deny` line and accepted with `disable`; the sample-shaped corpus hash pinned unchanged without the new keys and changed by a qualifier and by a deny; the builder's `packageRules`, `deny`, `build()` baking and refusing. `HooksTest` (+, the installer on the fake engine): `catalogue.deny` sent; the echo missing → ENVIRONMENT on the install path and on the `alreadyInstalled` path (a scripted `hook.health` without `deny`); `denyNotDelivered` recorded on a skip with `agentOption` from a scripted `rule.list` (`[patterns]`; `unknown` when the answer has no `deny` key or no `rule.*` capability exists, and then no `rule.list` call); a deny-only catalogue sent as a zero-spec install under `AUTO` and `ON`; `deniedNow` recorded; the `codeSource` upgrade hint on an `INVALID_PARAMS specs[0]` |
| 27 | `skillroy-cli` (EH) | `HooksCommandTest` (+): `status` prints both deny lines, the `CLASS LOADER CODE SOURCE HOOKS` table and the `CODE SOURCE` column from a scripted `hook.health` with `deny` and `classes`; `--json` carries them; against a scripted answer without `deny` the "unknown" line |
| 28 | the sample (EH) | `LendingHooksAndSettingsTest` (`:53-58`): `version()` 2, `packages()` still the one name, `packageRules()` with the qualifier, every enabled spec's `target().codeSource()` equal to it, `deny()` the `Inbox`; `LendingBranchOverlayTest` (`theConstantTheResolverTheAllowListAndTheCatalogueAreTheBranchs`): the replaced entry inherits the qualifier and the overlay's `deny()` is the base's; `LendingModuleTest` (+): `lending.hooks.deny` registers `LendingClassDeny` answering the reason, an invalid value fails the install naming the key, and nothing is registered without the option; `ScenarioEHeadlessTest` still passes (the echo in `HeadlessDesk`) |
| 29 | `protocol` (ES) | `CallWarningTest`: code shapes, message truncation and control characters, data cap, `fromJson` of a bad element -> `INVALID_WARNING`, `readAll` cap -> `TOO_MANY_WARNINGS`, round trip; `RpcResult` 7-argument form, `warnings` absent from JSON when empty, read when present, a 0.1.0-shaped result reads with an empty list; `ResultConventionsTest`: `InputResult.inputId/consumed`, `RungResult` builder, `CompositeResult.problems` for each violation and `answeredSeqs`; `ProtocolConstantsTest` still pins `0.2` |
| 30 | `agent-api` (ES) | `DefaultMethodsTest`: `Invocation.warn` no-op, `EventBus` 4-argument default delegating, `DeviceAdapter.rungs` empty; `ValueTypesTest` unchanged |
| 31 | `agent-core` (ES) | `DispatchTest`: warnings collected in order into the result, 17th -> `TOO_MANY_WARNINGS`, a secret parameter's value redacted from message and data, a warning whose message places a 12-character secret parameter's value across characters 495-506 holding no 8-character prefix of it in the result (redacted before the cut, §7.1), a `warn` after return dropped; `EventBusTest`: the overload stamps the given correlation id; `CorePrimitivesTest`: `device.list` with and without `rungs` (an old adapter's entry byte-identical) |
| 32 | `skillroy-scenario` (ES) | `WarningChannelTest` (`ToyPack`, `FakeAgentConnection` returning results with warnings): unaccepted -> `FAIL / ASSERTION` with the message and detail after every warning was recorded; accepted -> `PASS` with `warning {accepted: true}` records and `RunResult.warnings`/`StepResult.warnings`; an undeclared pack code never acceptable; cleanup warnings logged only; `INPUT_QUEUED` derived for `NONE`, uncorrelated `READY` and `EVENT` without `inputId` in the match, not for correlated syncs; masked message; `StepOutcome.warnings` and the refusal of a raw call with modifiers (`ExecutorSessionTest`); `CorrelatedAckTest`: `ackByInputId` adds the match on `ACK` and `READY` (a canned ack of another id between the call and the wait is ignored), missing `inputId` -> `ERROR / ENVIRONMENT` with nothing awaited, cleanup correlates too, the 5-argument `SyncPolicy` keeps `false` and `PlanExecutorTest.syncAckAwaitsTheAckTopicSinceSeqBefore` holds; `CompositeRulesTest`: a prompt event with a listed `seq` inside the window expected with `answeredBy`, outside the window ignored, an unlisted one handed to the policy, `problems` violations -> `ERROR / ENVIRONMENT`, the secret-echo check (equality for a 4-character secret inside a longer string not flagged, substring flagged at `minLengthInText`, the records masked, warnings' text scanned, the bound), a result without `composite` untouched; `RungLadderTest`: missing / undeclared / below-minimum rung, missing echo, a lower rung -> one `DEGRADED` record even when the primitive also says `DEGRADED`, the `rung` record, the run-start pre-check `PRECONDITION_FAILED` with its `rungs` record and no step sent, `OK` through a capability-less rung, cleanup logged only; `StepModifierTest`: the loader (one verb plus modifiers, two verbs refused, bad shapes, a verb named `accept`), the compiler (`accept` ∪ pre-accepted, unknown code, `minRung` membership, `minRung` on a ladder-less step, `expect`/`aiAct`/`aiExpect`/`captureFacts` refusing both, `ui.strict` -> first rung with the step's own winning), the plan writer (`accept` sorted, `rungs`, `minRung`, absent when empty) and the reader (each refused below 7, read at 7: `Phase8CompileTest` with EX), `ScenarioSchemaTest` (the two properties on every verb), `PackOverlayTest` (`warningCodes` forwarded), `JunitXmlReporterTest` (`warning` lines, `--json`), `StepJournalTest` (call keys round trip) |
| 33 | `skillroy-cli`, `skillroy-junit`, `skillroy-mcp`, `skillroy-inference` (ES) | `RunCommandTest`: `warning:` lines and `--json`; `ScenarioTestsTest`: the summary; `ToolCallTest`: `warnings` in a step reply only when non-empty; `SessionServiceTest`: the model view gains `warnings` only when non-empty (the fixtures' step JSON unchanged otherwise) |
| 34 | the sample (app, agent, pack) (ES) | `ScannerDriverTest`: frames decoded and refused; `LendingPrimitivesTest`: `inputId` in result and payload, `consumed` both ways (`ackTimeoutMs: 0`), trim -> `VALUE_COERCED` with `taken`, blank -> `VALUE_DROPPED`, a secret value never in `taken` or a message (the fixture marks `value` secret), nested answer under a planted supervisor sub-step -> `ANSWERED_NESTED`, a late acknowledgement published once from the engine thread with the same `inputId` and the call's correlation id, the pending bound and clearing on `deactivate()`, a caller-supplied `inputId` echoed, `data.reason`; `aBasicLoanThroughLendingInputPublishesTheDomainEvents` updated (payload `{source, inputId}`, still no value); `LendingCompositesTest`: every branch of both composites, bounded waits, `problems` empty, no password anywhere in a result, `refused` with the prompt open, `passwordRequired` with the pack code; `ScannerDeviceTest`: both rungs, refusal below `minRung` without the driver, the echo, malformed frame, `rungs("scan")`; `LendingModuleTest`: `DeviceAdapter` 2, the three primitives and capabilities declared and the count, edited to ES's branch truth and resolved at merge (D641); `LendingPackTest`: `ackByInputId`, `warningCodes`; `LendingStepsTest` / `LendingStepSchemasTest`: the three verbs' exact calls (ladder, `minRung` forwarding under `strict`, `password` only with a credential); `PlansUpToDateTest` (`scanner/` in `committedScenarios()`; `theNegativeScenariosCompile` expecting a `ScenarioException` with the named message for `accept-unknown-code` and `min-rung-without-ladder` and a plan for every other negative; `noScenarioOrPlanHoldsAPassword` allowing `sign-on-any.plan.json`'s `"password": "$assistant.password"` and still refusing a literal password and a `password:` in any YAML, D642), `BindingsUpToDateTest` (its lists unchanged: no new scenario needs bindings), `ScenarioSchemaCommittedTest` (its branch's truth), the goldens' expected states reported for step R, `SessionExportTest` and `InferenceChecks` item 8 red on ES's branch until step R regenerates the recordings and the exports (the named window, D641), `OutputScan` (§5.1) over every new file, `ScenarioEHeadlessTest` still green on `HeadlessDesk` with correlated acks |
| 35 | `protocol` (EX) | `PayloadMatchTest` (Java 8): scalar text equality (`2` vs `2.0` unequal, booleans by text, null), `has` on lists of strings and numbers (a map element never), `has` on a scalar, null and missing value (false), `problem` on `{has: [x]}`, `{has: {}}`, `{has: null}`, two keys, an unknown key, a list value, a map value; `usesOperator`; `ProtocolConstantsTest` still pins `0.2` |
| 36 | `agent-core` (EX) | `EventBusTest`: `await` with `has` on an existing and on a future event, none on a non-list payload; `CorePrimitivesTest`: `sync.await` with `has` answers the event, with `{sources: {in: …}}` answers `INVALID_PARAMS {param: match}` before waiting |
| 37 | `skillroy-scenario` (`ExpectationsTest`) (EX) | list filters: `[*code~^YRD]` → 2 elements, `[*code=NONE]` → empty list resolved, on a scalar/map/null unresolved, chained `[*a=1][*b=2]`, `[*…][last].x`, `[*…][0]`, `.qty` after a list filter unresolved, dotted fields `[*nested.a=1]`, `[*lines.0.code=X]`, `[payload.source=X]` single form, a literal dotted key no longer matched, references bound with type, one regex budget across a long list (a pathological regex rejected once); grammar errors `[*]`, `[*=1]`, `[*a]`, `[*a~(]`; `minPlanVersion` 7 for each new form and 1 otherwise; `[json]`: object, array and scalar text, not JSON, non-string, over 1 Mi, two `[json]` in a path; `size` on a filtered list with a nested matcher and with a reference; `anyOf`: holds, fails with both reasons, unresolved path with `exists` inside, nested `not`, references, normalise errors (empty, 17, non-list, `capability` inside); `containsWhere`: partial match, dotted fields, a field missing on some elements, `{exists: false}` inside, non-list, empty list, nested `matches` sharing one budget (a second slow regex fails "budget spent"), `not` of it, normalise errors (empty, 33, bad key, non-map); `Matchers.minPlanVersion` recursive |
| 38 | `skillroy-scenario` (`Phase8CompileTest`, new) (EX) | `Plan.VERSION` 7, the message "expected 1, 2, 3, 4, 5, 6 or 7", `8` refused; a version 6 plan read as 7; each §8.2 row refused below 7 with its message; a step and a call with an unknown key refused at 7 and read at 6; the reserved modifier keys pass the key check at 7; `captureFacts` compiles to the `facts` member (sorted fields, `path`/`size` normal form, empty `calls`), its slot declared `{kind: capture, step, fields}`, round trip byte-identical; compile errors: slot reserved, in `resolve`, captured twice (by an event and by facts), 0 or 33 fields, a bad field name, a field with two keys, an unknown root, a `db.` path, a reference to the slot in the same or an earlier step or to an undeclared field; the verb refused for a pack that declares it; `waitFor` with a list, a map and `{has: [x]}` refused, `{has: x}` accepted; a pack `sync.await` call with a bad `match` refused (`ToyPack`); `containsWhere` in a `db` check refused, `anyOf` accepted; `ScenarioSchemaTest`: the two matchers, the verb, eight roots in the description |
| 39 | `skillroy-scenario` (`EventsRootTest`, new; `FakeAgentConnection`) (EX) | the `events` root: window from `since`, oldest first, entry shape without `traceparent`, paging (2,500 events → three calls, the cursor from each full page's last `seq`), loss on the first page (`oldestSeq > since + 1` → the read fails naming the evicted range and `eventRing`), loss while paging (a gap in `seq`), the tenth page full → fails, an `evt.query` error → fails, a pack root `events` hides it (`PlanExecutor` and `ExecutorSession`), a session's window from its start mark, `BaseFacts.all()` from 0, `WebEvidenceTest`'s eight roots |
| 40 | `skillroy-scenario` (`Phase8ExecutorTest`, new; `FakeAgentConnection` with scripted roots and a scripted ring) (EX) | `captureFacts`: value and size captured, `capture {source: facts}` record, `bindings.json` `captured`; an unresolved path → `FAIL / ASSERTION` naming slot, field and path; a fetch failure → `ERROR / ENVIRONMENT`; a reference that does not resolve → `RESOLUTION_FAILED`; with `expectationsOnMaskedValues` a masked value fails the step, without it is captured; the delta `{size: {gt: $before.n}}` holds after a new event and fails without one (the operand in the message); `{size: 0}` on a filtered `events` root holds, a stray event fails it with the matching entries as `actual`; the `actual` cut at 200 with `actualTruncated`; `FAIL / ASSERTION` for `{size: 2}` on a list of 3 names the size; `EventSyncTest`: an `await` with `has` completes, with a reference resolved, a reference resolving to a list refused `RESOLUTION_FAILED`; `WebPathTest`: a combined wait on path events with `has`; `ExecutorSessionTest`: `execute("captureFacts", …)` binds the slot and the step's `captured` detail carries it, the session's masked rule, `check` with the new matchers and paths, `fact` with `[*…]`; `JournalExportTest`: the verb exported as given |
| 41 | the sample (headless) (EX) | `LST/LoanCountsHeadlessTest` (the `ScenarioEHeadlessTest` pattern on `HeadlessDesk`): the committed plan PASS without sleeping, the `capture {source: facts}` record, every `expect` record `held`; the negative `count-delta-fails` compiled and `FAIL / ASSERTION`; `HeadlessDesk.matches` through `PayloadMatch` (a `{has: Key.RETURN}` wait in DEPOSIT → `SYNC_TIMEOUT`); `PlansUpToDateTest` (the committed list and the negative list, EX's branch truth, D641; EX compiles `loan-counts-and-events`'s plan on its own branch), `ScenarioSchemaCommittedTest` (likewise), `BindingsUpToDateTest` (unchanged by EX) |
| 42 | `skillroy-scenario` (EP) | `PackCapabilitiesTest` (new; `ToyPack` gains a `List<PackCapability> capabilities` field): an id evaluated after the contributors and before `requires` (the agent's `data.query` is the only call before `requires`); `OK` kept in `REPLAY` (contrast `Phase7ExecutorTest:311-336`); a guard on an `UNAVAILABLE` pack id skips the check → `DEGRADED` with the reason in the skipped line; `requires` on it → `PRECONDITION_FAILED` naming `<id>: UNAVAILABLE (<reason>)` with nothing sent after the reads; the five refusals of §9.1.2 → `ERROR / ENVIRONMENT` naming the pack and the id, nothing evaluated; an id equal to an agent id and to a contributor's id → the two conflict messages; `evaluate` throwing with a message that quotes a row value → `UNAVAILABLE("failed: <SimpleName>")` with the quoted text in the record's `pack[]` entry's `error` (masked) and in no matrix, `caps` root or `capabilities.json` reason, and the run goes on; null → `"no answer"`; a wrong id in the answer → refused; `InterruptedException` → `ERROR / ENVIRONMENT "interrupted"`; source `pack:toy` in the `caps` root and `capabilities.json`; the `capabilities` record's `pack` list `{id, state, reason, strategy, scope, cached}` and its absence for a pack without capabilities (the record byte-identical to today's); a `SUITE` capability with a cache and a session evaluated once over two runs (`cached` false then true), twice without a cache, twice with another session, never cached without a session (`FakeAgentConnection.session(null)`); `ExecutorSession.open` merging them (`capabilities()`, the journal's `pack`); `CapabilityMerge`'s contributor rules unchanged |
| 43 | `skillroy-scenario` (EP) | `PackOverlayTest`: the `capabilities` case of the forwarding check; `ScenarioSchemaTest`: `requires`' description lists the pack's ids and descriptions, `on_prompt.unexpected` is the enum `[fail, pack]` |
| 44 | `skillroy-controller` (EP) | `CapabilityMatrixTest`: `SOURCE_PACK_PREFIX`, `with(entries, "pack:toy")`, `source(id)`, `agentOnly()` dropping them, the wire shape without a source; the client tests: `AgentClient.agentSession()` null before the first ping, the verified id after, unchanged by later pings, never I/O |
| 45 | `skillroy-scenario` (EP) | gap 111: `ScenarioLoaderTest.onPromptAndListsAreChecked` gains `failWithBundle` refused naming the line and the rule, `FAIL` and `Pack` accepted and normalised; `RobustnessTest.onPromptFailIsCaseInsensitive`: the `lenient` case becomes a load error, `pack` → the pack's policy (PASS), absent → the pack's, `FAIL` → strict; a plan reader test (`Phase8CompileTest`) refusing `{"onPrompt": {"unexpected": "x"}}` and `{"onPrompt": {"other": "fail"}}` whatever the version, accepting `fail` and `pack`; `PlanCompilerTest`'s `toy-loan.plan.json` byte-identical |
| 46 | `skillroy-scenario` (EP) | `PreflightScopeTest` (new; a counting `SUITE` check on `ToyPack`): one cache and one session over two runs → one call, records `{scope: suite, cached: false}` then `{…, cached: true}`; another session → two calls; no cache → two calls; no session → two calls, `cached: false`; `SCENARIO` → two calls and records without `scope`/`cached` (byte-identical to today); a cached failure fails the second run `ERROR / ENVIRONMENT` with the "(checked once for this suite)" suffix and no call; an exception kept as a failure; `interrupted` not kept; `SuiteCache.create(1)` full → the second item runs per scenario; `PlanExecutorTest.preflightFailureIsEnvironment` unchanged (order, stop at the first failure, after `requires`); `ExecutorSessionTest`: `SessionOptions.suite` honoured by `open` (one evaluation over an open and a run sharing the cache); `ExecutorPhase2Test.approveWithoutABindingsRootSendsNothingAndCallsNoPackCode` unchanged (no capability evaluated before the bindings-root check) |
| 47 | `skillroy-junit` (EP) | `ScenarioTestsTest` with `MiniPack` (a `SUITE` check) and `MiniAgent` (a session): one `dynamicTests` stream of three scenarios → one evaluation; two streams → two; options given by the caller without a cache → still one per stream; a caller's cache shared by two streams → one |
| 48 | `skillroy-cli` (EP) | `RunCommandTest` (`CliTestPack` gains a `SUITE` check and a capability `cli.env.ready`): two scenarios in one `run` → one evaluation, the second run's record `cached: true`; `ResolveCommand` likewise; `AuthorCommandTest`: the answer shared between the up-front pre-flight and `--run`; `ReResolveCommandTest`: a plan requiring `inference.sql` with a contributor on the class path is no longer refused "missing", a plan requiring an `UNAVAILABLE` pack id is `FAILED` `REQUIRES` naming the reason, a `Refused` declaration is `Exit.FAILED`; `Phase8CommandsTest`: `caps --pack` shows `cli.env.ready` with `SOURCE pack:cli` (table and `--json`), `caps` without `--pack` shows no pack row |
| 49 | `skillroy-mcp` (EP) | `ScenarioRunPolicyTest` (or a new `SuiteCacheOverMcpTest` on the MCP `FakeAgent` with a session): two `skillroy_scenario_run` calls → one evaluation of the test pack's `SUITE` check, the second run's record `cached: true`, the session's open counted as the first; `skillroy_catalogue` lists the pack's id with `source: pack:<id>` |
| 50 | the sample (headless) (EP) | `LendingPackTest`: `capabilities()` = `[lending.returns.ceiling]` with its description and `SCENARIO` scope, `lending.database` `SUITE`, version `0.7.0` (step R); on `FakeDesk`: `OK` with the row, `UNAVAILABLE` naming the row's name without it, the `DataException` reason; `LendingBranchOverlayTest`: the branch pack reads `return_ceiling`; `PackCapabilityHeadlessTest` (new, on `HeadlessDesk`, both environments): scenario A's committed plan ends `PASS` in `live` with the `capabilities` record's `pack` entry `OK`, and with a test overlay (`OverlayPack`-style) whose `lending.maxReturnSetting` names a missing row ends `PRECONDITION_FAILED` naming `lending.returns.ceiling: UNAVAILABLE (no system maximum return configured (desk_setting <name>))` before bind, with `data.query` the only call after `agent.capabilities`, no `preflight`, `bind`, `cleanup` or `recover` record; `PreflightSuiteHeadlessTest` (new): a `ScenarioTests.dynamicTests` stream of three committed seed-A scenarios on one `HeadlessDesk` → `lending.database` evaluated once (`cached` false then true), a second desk (a new session) → evaluated again; `PlansUpToDateTest`, `BranchPlansUpToDateTest`, `BindingsUpToDateTest` (red on EP's branch for scenario A's two plans and three bindings files until step R, the one named window, D641), `ScenarioSchemaCommittedTest` (every committed and negative file validates against the enum) green after integration |

**As built (Phase 8, EH, 2026-10-19), rows 22-28.** Row 24's "class from another code source" uses a fixture of its
own, `fixture.hooks.TwoJars`, defined a second time by `ChildFirstLoader` from a temporary jar (`two-jars-1.4.jar`),
not `Returns`: other test classes of the shared JVM leave child-loader copies of `Returns` loaded, which would widen
the copy set (D-EH-7); its pathological qualifier is `(.*a){20}`, because the JDK 25 regex engine memoises the classic
`(a|aa)+` and `(a+)+` and answers them at once. Row 22 pins `LIMIT`'s v0.1.0 spec hash and row 26 the sample-shaped
corpus hash, each computed by the code before the change; row 28 pins `lending.yaml` version 2's hash. Row 27 asserts
the `CODE SOURCE` column with `-` (the CLI test pack's catalogue has no qualifier and stays unchanged). The agent
answers a spec whose `codeSource` is not a regular expression `INVALID_PARAMS` `specs[0].target.codeSource` (a
`ProbeItemsTest` case).

### 13.2 End to end (the Linux image; `ci/run-in-linux.sh … -Dskillroy.headless=false`; Java 8 and Java 25 targets; no key)

Where each item lives. `LoadModeChecks` (every load mode of `LendingEndToEndIT`, both target Javas through the matrix's
two legs, the desk signed off after each check) gains the slots **157** (EA: the `tools` key absent off its leg),
**158, 159, 171** (ET), **172** (EW), **173, 174, 175** (ES), **176** (EX) and **177** (EP), and EP extends the glue's
existing slot 160; the free slots 178, 179, 181-189 and 191-199 stay free for the build (190 is taken today, as are
10-156, 160, 170, 180, 200 and 210). `LendingEndToEndIT` gains three nested
classes with their own `LendingTarget.Mode`, ordered after the Robot leg: `@Order(18) JavaAgentTools extends
InputSafetyChecks` (EA; `JAVAAGENT_TOOLS`: `-Dlending.tools=true`, agent options `discoveryDir` and `robot=true`, no
golden compared), `@Order(19) JavaAgentScanner extends ScannerChecks` (ES; `JAVAAGENT_SCANNER`:
`-Dlending.scannerDriver=true`) and `@Order(20) JavaAgentSmallRing extends SmallRingChecks` (EX; `JAVAAGENT_SMALL_RING`:
the agent option `eventRing=200`); none of the three is a `LoadModeChecks` subclass (the goldens are per standard leg,
as the Robot leg). `HookChecks` (the `JavaAgentHooks` leg) gains orders 68 and 69 and extends 65, 165 and 166 (EH);
`ResolutionChecks` and `PerTargetJavaChecks` gain EP's assertions; `WebHostChecks` (`WebHostEndToEndIT`, profile
`jcef`; its orders run 1-15 today, `@Order(14)` being `gap95dAMaskedBrowserIsMaskedOnEveryRoute` and `@Order(15)`
`gap95eWhatAPersonsMiddleClickDoesToTheRealPageIsRecorded`) extends its gap-95d item (`@Order(14)`, item 16) and gains
`@Order(16)` (item 17) and `@Order(17)` (item 18) (EW); `BytemanIT` (`agent-dist`) gains one method (EH); the five
goldens are compared with the live matrix in the image by `LoadModeChecks` check 1c
(`theCapabilityMatrixIsTheGoldenOfTheMode`, `@Order(30)`, `:350-352`), in the four load modes and the hooks leg, and
that check is red on ET's and ES's branches until step R regenerates the goldens (natively nothing is red:
`CapabilityGoldenTest` passes before and after, §13.1 row 14; `PerTargetJavaChecks:567-580` compares two live
matrices and stays green) (ET, ES). Items 1-10 run in `InputSafetyChecks` unless said; the agent-swing display tests
named per item host the agent-level half of a proof.

1. **The tools window** (EA). `swing.windows` lists two frames, the desk's focused; `app.state.lending.tools` present with
   every counter 0 and `lidOpen: false`; on every other leg `app.state.lending` has no `tools` key (the `LoadModeChecks`
   check at `@Order(157)`, one assertion).
2. **Every route is refused, `force` or not** (EA). (agent half: new `InputVetoDisplayTest`, own frame, a registered test
   veto, routes R1-R4, R6-R13, a registered `ActionFirer` accepting the component never fires, a focused vetoed
   button gets no SPACE from a target-less `swing.key`, dismiss of a vetoed dialog's button and `close`, the in-task
   re-check with a veto switched on between the read and the task: nothing fires, one warning, `vetoWithheld` 1 in the
   module's `info()`; **fix wave of the Phase 8 review and security fixes (FA, 2026-10-19)**: COMPONENT's second read
   refuses a veto that switched on with the window's activation, the component never focused (L2-F5);
   `PopupVetoDisplayTest`, its own frames: a frame veto covers a heavyweight menu popup's item the application opened,
   a vetoed combo its open list (L2-F2); a routed `DRAG` from a menu, from a menu whose popup is heavyweight, and a
   right-button drag on a panel with a context menu, each released on a vetoed item, answers `VETOED` with the press
   balanced and nothing fired (S155; each fired the item on the old code); `InputVetoesTest`: a popup's item asked with
   the invoker's chain, a cycle refused at the bound, the throwing veto's log line naming the class only). End to end against
   `closeDesk` and `{action: CLOSE}`: `swing.click` ACTION, AUTO, COMPONENT with `activateWindow`, COMPONENT with
   `fallback: ACTION`, DISPATCH, each also with `force: true`; `swing.key SPACE` and `ENTER` with the target;
   `swing.pointer` CLICK by `{id: closeDesk}`, by window point, by `{captureId}` point of a capture of the tools
   window, a `DRAG` from `printTestSlip` ending on `closeDesk`, and `via: ROBOT` with `requireFocus: false`; each
   `INPUT_REJECTED` with `data.reason: "vetoed"`, `data.veto` the module's text, `data.vetoed` the `closeMenu` panel's
   ref, `data.route` the route; then `closeRequests == 0`.
3. **ENTER's default button** (EA). `swing.key {key: ENTER, target: {id: toolsNote}}` is refused naming `closeDesk` in
   `vetoed` (route `swing.key`); `swing.type {target: {id: toolsNote}, text: "x", enter: true}` likewise;
   `swing.type` without `enter` into `toolsNote` succeeds. **Fix wave (Phase 8 review, FA, 2026-10-19)**, agent half
   only: `InputVetoDisplayTest` refuses `swing.type {mode: DISPATCH}` with `"x\n"` into `toolsNote` and with `"\n"`
   into the non-text `printTestSlip`, `defaultButton` naming `closeDesk` (L1-F1, L2-F1); `PopupVetoDisplayTest`
   refuses ENTER and a DISPATCH `'\n'` into a field of a `JInternalFrame` without a default button, `defaultButton`
   naming the window's (L2-S1); `InputVetoesTest` asks the outer default button headless.
4. **Readers** (EA). `swing.hitTest {target: {id: closeDesk}}` and at the key's centre answer `vetoed: true`;
   `swing.snapshot {root: <tools frame>}` nodes of `closeMenu`, `closeDesk` and the icon key carry `vetoed: true`,
   `printTestSlip` carries no such key; `swing.find {by: {id: closeDesk}}` likewise; `swing.capture {target: <tools
   frame>, annotateRefs: true}` has no mark for the two keys and one for `printTestSlip`; `swing.hitTest` at
   `printTestSlip` answers `vetoed: false`.
5. **An allowed sibling** (EA). `swing.click {by: {id: printTestSlip}}` fires: `testSlips == 1`.
6. **The visible area** (EA). (agent half: new `CollapsedPaneDisplayTest`: every rung, type, key and dismiss refused
   `collapsed` with `by`; locators unique; snapshot pruning and `includeHidden`; the pointer's component form and the
   hit-test's target form refused; a scrolled-out button still clicked and matched; the component-form point check
   `not visible` on a scrolled-out row; `InWindowDialogDisplayTest`: a collapsed in-window dialog root no longer
   blocks, and the once-per-root-class log line of §3.3 is written). End to end: `swing.find {by: {text: "Print
   slip"}}` answers exactly `printSlipVisible`; `swing.snapshot`
   of the tools frame omits `printSlipHidden`, with `includeHidden: true` it is there with `collapsed: true`;
   `swing.click {target: <ref of printSlipHidden>}` is `INVALID_PARAMS` `reason: "collapsed"`, `by` the
   `collapsedPane`'s ref; the click by `{text: "Print slip"}` makes `slipsVisible == 1`, `slipsHidden == 0`;
   `swing.hitTest` at `printSlipVisible` proposes `{text: "Print slip", role: button}` among its locators;
   `swing.click {by: {id: reprintSlip}}` (scrolled out of the viewport) fires, `reprints == 1`, and
   `swing.pointer {by: {id: reprintSlip}, action: CLICK}` is `INPUT_REJECTED` `reason: "not visible"`;
   **regression guard**: `LoadModeChecks.keypadKeysAreNamedByTheirActionId` and the Phase 7 `VisionSwingChecks`
   items pass unchanged on the four load-mode legs (the desk's snapshots and locators unchanged).
7. **Heavyweight routing** (EA). (agent half: new `HeavyweightRoutingDisplayTest`: a frame with a `Panel` holding a
   `JButton`, an AWT `Button`, a `Checkbox`, a listening `Canvas`: a routed click fires each (the AWT ones through
   their peers on X11, asserted by `ActionEvent`/`ItemEvent` counts), `received`, `source`, hit-test `source` and
   `mouseTarget`; a cancelled press (blocked EDT, as `HostSafetyDisplayTest:192-273`) into the AWT `Button` fires
   nothing (the exit-then-release balance); `MODAL_OPEN` still applies; a `Canvas` claimed by a test `SurfaceSource`
   keeps the window as source; `VisionDisplayTest`, `ModalBlockingDisplayTest`, `RobotPathDisplayTest` unchanged).
   End to end: `swing.pointer CLICK` by `{id: scanButton}`, `{id: lidOpen}`, `{id: rereadCard}` and by the same
   window points: each counter +1 exactly (`scans`, `lidChanges` with `lidOpen` toggled, `rereads`), `received` the
   component, `source` the `scanner` panel's ref, `fired: true`; `swing.hitTest` there answers `mouseTarget`/`target`
   the component and `source` the panel; `swing.pointer CLICK` by `{id: openDepositBox}` answers `source ==
   window`; a `MOVE` onto `scanButton` then a `CLICK` on `openDepositBox` leaves `scans` unchanged and
   `dispatched.of` one larger (the exit).
8. **AWT controls on ACTION and COMPONENT** (EA). `swing.click {by: {id: scanButton}}` (AUTO) → `scans` +1 with exactly
   one `ActionEvent` whose command is "Scan"; `{id: lidOpen}` → `lidOpen` toggles with one `ItemEvent`, twice back;
   `rung: COMPONENT, activateWindow: true` on each likewise (run last: it activates the tools window);
   `rung: DISPATCH` on `scanButton` is reported but not counted (toolkit-dependent, X11 fires). Agent half: in
   `HeavyweightRoutingDisplayTest`: a selected radio in a `CheckboxGroup` stays selected with no event, a disabled
   AWT `Button` refused `disabled`, `swing.dialogs` of an AWT `Dialog` with AWT buttons lists them and
   `swing.dismiss {button}` presses one.
9. **The negative scenario** (EA). `Runs.negative("vetoed-click.yaml")` on this leg: `Status.FAIL`,
   `FailureKind.ASSERTION`, `failedStepId` `s2`, the message contains `INPUT_REJECTED` and `vetoed`, the detail
   carries `veto`, `recover` ran and succeeded (the run log), `closeRequests == 0`, the desk signed off.
10. **The Robot route refuses before anything moves** (EA). (agent half: `RobotPathDisplayTest` with `FakeOs`: a vetoed
    point, and a drag ending on one, refused with `os.calls` empty, as `:393-404`; **Phase 8 security fixes (FA)**: a
    vetoed owner under its active owned dialog's point refuses the Robot with and without `force`, nothing moved
    (S156); a press that shows a vetoed component under a drag's end sends the release to the press point and answers
    `VETOED` (S155); `RobotPathTest` the release question's three outcomes headless). End to end: item 2's
    `via: ROBOT` call, and `swing.pointer {by: {id: printTestSlip}, via: ROBOT, requireFocus: false}` fires on this
    leg (`testSlips` +1), proving the leg's Robot works.

    **As built (Phase 8, EA, 2026-10-19), items 1-10** (`InputSafetyChecks`, `LendingEndToEndIT$JavaAgentTools`,
    `@Order(18)`; green on Java 25 and Java 8 targets in the image, 11 checks each; slot 157 is
    `LoadModeChecks.theToolsKeyIsAbsentOffTheToolsLeg`). Read so: (i) **covering**: the desk frame spans the 1024 x 768
    display and is the active window, so the agent ranks it above the tools window (P7 §3.2) and every routed or Robot
    pointer call into the tools window is refused `covered` unless forced; the calls that must act (item 7's clicks and
    move, item 2's capture-point and drag calls, item 10's Robot click) pass `force: true`, which skips the covering
    and modal rules only; the veto refusals of item 2 are asserted with and without it. (ii) Item 3: `vetoed` names the
    `closeMenu` panel (the ancestor rule, as in item 2) and the added `data.defaultButton` names `closeDesk` (§3.2's
    as-built note (a)); `swing.key ENTER` with `CTRL` is refused too. (iii) Item 7: `source` is the scanner panel's ref
    for `rereadCard` and the control itself for `scanButton` and `lidOpen` (heavyweight leaves are their own source,
    §3.4's as-built note); the move-then-click check compares with a repeated click at the same point (`of` one
    larger). (iv) Item 8's COMPONENT half runs after the negative scenario, and item 10's Robot click after it (the
    tools window is then active and raised). (v) Item 9: the failure message is the agent's (D504), so it names
    `INPUT_REJECTED` and the veto's text; the token `vetoed` is the failure detail's `data.reason`, `data.vetoed` the
    panel; `OutputScan` finds the staff password in no output of the run.

11. **The quiescent view (gap 10; slot 158)** (ET). After sign-on, `Key.QTY` then `Entry 3`: `lending.engine.view` answers
   `pendingQty: "3"`, `step` the quantity sub-step, and the envelope's `thread` is `LendingEngine`; with the induction
   prompt open (an induction-required item scanned) it answers `NOT_READY` ("no quiescent point within …") within its
   5 s; after `lending.prompt.answer` it answers again; `agent.info.threads.quiescent.ran` grew by two and `notReady` by
   one; `swing.snapshot` and `app.state` answered during the prompt as before. `ATTACH` matters (the engine exists before
   the agent, the listener attaches late) and `EMBEDDED` (the plugin start).
12. **Lanes and cancellation (gaps 9, 11; slot 159)** (ET). The runaway statement of `DataQueryJdbcTest.theQueryTimeoutStopsARunawayStatement`
   over the desk's `item` table (accepted by `StatementGuard`) on `lending-report` with `_meta.timeoutMs: 1000`: while it
   runs (started on a test thread), `lending.state`, `app.state` and `data.query` on `lending` answer at once; the call
   ends `TIMEOUT {source: "lending-report", lane: "lending.reads", cancelled: true, stillRunning: false,
   enforcesTimeout: true}` within about 1.3 s; `agent.info.threads.lanes["lending.reads"]` shows `owner: lending,
   threads: 1, queue: 2` and, after the read ended, `active: 0, abandoned: 0`; a second read on `lending-report` answers.
   The same statement on `lending` (the JDBC form) with 1500 ms: `TIMEOUT {cancelled: true, stillRunning: false}` and an
   immediate second `data.query` on `lending` answers (no `BUSY`): HSQLDB 2.7.4 honours `Statement.cancel()` (map B's
   `javap` finding; the build confirms it here on both target Javas).
13. **A positional source by name, and the allow-list check (gaps 12, 117; slot 171)** (ET). `data.query` on `lending-report`:
   `SELECT code, password, name FROM staff ORDER BY code` answers columns named `code, password, name`, every password
   `<redacted>`, and no seeded password appears anywhere in the reply; `SELECT * FROM staff WHERE 1 = 0` answers the
   `staff` table's columns and no row. `AllowListCheck.check(new SqlAllowList("lending-report", HSQLDB, LIST.tables(),
   List.of(), LIST.functions()), DataChannels.of(agent, Credentials.empty(), masks), masks, 30 s)` passes over the live agent (fails
   today); the same with a provider answering no `columns()` (a test-only agent option is not planted: the negative is
   §13.1 row 9). The positional scenario under `PositionalOverlayPack` ends `PASS` with the `rows: 1` and the `rows: 0`
   checks both held (fails today with "returned 0 columns").
14. **Goldens** (ET). After step R the five goldens hold `lending.engine.view` `OK` in every leg; nothing else moved for ET.

   **As built (Phase 8, ET).** Items 11 to 13 are `LoadModeChecks` slots 158, 159 and 171. The runaway statement is a
   seven-way cross join over the desk's `item` table (the desk has 13 items, so `TestDb`'s four-way join would end in
   milliseconds; 13^7 combinations end only when stopped). Item 11 also reads the view after `Key.QTY` alone (step
   `QUANTITY`), so `ran` grows by three; the desk is left signed off by `Key.CANCEL` and `Key.SIGN_OFF`. Item 12 starts
   the report on a test thread and asks `lending.state`, `app.state` and `data.query` on `lending` once
   `agent.info.threads.lanes["lending.reads"].active` is 1; the measured times go to `timings.txt`
   (`laneTimeoutMs`, `othersMs`). The JDBC half on `lending` runs with 1000 ms, not 1500: the module waits the call's
   time less 250 ms and the JDBC read's query timeout is that budget in whole seconds (at least 1), so with 1500 ms
   HSQLDB's own 1 s timeout ends the statement first (`INTERNAL_ERROR`, SQL state 40502) and nothing is cancelled; with
   1000 ms the module cancels at 750 ms. During item 11's prompt the desk's state is still `IDLE` (the item is added
   once the question is answered). Item 13's allow-list check and the positional scenario's run use the pack's row
   masks and the seed A credentials.

15. **The staff name in an event reaches no reader** (EW). (`LoadModeChecks`, new check at `@Order(172)`, every load mode of
   `LendingEndToEndIT` (JavaAgent, JavaToolOptions, Attach, Embedded, JavaAgentHooks through `HookChecks`'s inheritance),
   both target Javas): `agent.info` → `events.maskPolicies >= 1`; sign on through raw `lending.input` calls (the
   check-50 pattern) and `sync.await {topics: [staff.signedOn], since}` → `payload.code == "100"`, `payload.name ==
   "<personal>"`; `evt.query` since the same seq shows the same; the SSE stream read through `AgentClient.events(since,
   topics, sink)` (what `skillroy events --json` prints) shows the same; then a run of the committed `basic-loan` with
   `--out`, and `OutputScan.leaks(runDir, Set.of("Alice Archer"), Scope.EVENTS)` is empty (the name is on the status
   line, so `Scope.ALL` would find the snapshot of a failing run, not a leak); signed off after. Fails today: the name
   is published as it is.
16. **A view-less browser is masked by default** (EW). (`WebHostChecks.gap95dAMaskedBrowserIsMaskedOnEveryRoute`, the
   off-screen launches of `WebHostEndToEndIT` with and without a port, both agent modes, both target Javas; the windowed
   launches have no `detached`): with `webhost.mask=front`, `detached` is `masked: true`, `url: ""`, no `title`, frames
   blank, and `masksRegistered: true`; its four reads are refused on the agent path and handed off on the CDP path;
   `redact: false` reads it (the control); the session snapshot shows `[web <ref> masked]`; after `web.reload {browser:
   detached}`, `evt.query` since shows `web.load {url: "", masked: true}` and `web.ready {url: "", masked: true}`. The web host's item
   2 (`item2TheBrowsersAreEnumerated`) is unchanged: no mask in that launch.
17. **A `BrowserMask` releases or names a browser** (EW). (`WebHostChecks`, new item `@Order(16)`, the same launches): attach
   with `webhost.mask=front,release-detached` → `detached` is listed with URL and title and its text is read, `front`
   stays masked; attach with `webhost.mask=tag-back` → `back` is `masked: true` on every route (the four reads refused
   on the agent path, handed off on the CDP path) while `front` and, off screen, `detached` are not; `by: {url:
   <back's marker>}` answers `NOT_FOUND` whose `data.browsers` lists `back` with `url: ""` and `masked: true` (the
   control: the same `by.url` finds it before the mask); on the CDP path the selection is handed to the agent and
   answers the same. **As built (Phase 8, EW):** under `webhost.mask=tag-back` alone, `detached` (off screen) **is**
   masked: any registered `BrowserMask` puts the view-less rule in force (§5.2, D545), so "and, off screen, detached are
   not" is corrected and the check asserts it masked (D-EW-16); the control `by.url` runs under
   `front,release-detached`; the item ends on a fresh agent without a mask, and `gap95e…` moves from `@Order(15)` to
   `@Order(18)` so that it stays the launch's last act (it may leave the page unresponsive) (D-EW-17).
18. **The Playwright line recorded** (EW). (`WebHostChecks`, new item `@Order(17)`, the launches with a port; the first
   half may instead extend `item3BorrowInPagePassesWithoutSleeping`, `@Order(3)`, which already reads that run's
   `web.path` record, leaving only the traced run at `@Order(17)`): a run of `borrow-in-page` over the CDP
   path has a `web.path` record with `playwright: "1.63.0"` and `connectOptions: {noDefaults: true, isLocal: true,
   artifactsDir: false}`; a run with `web.trace: true` records `artifactsDir: true`.

19. **Order 65 (extended), `thePacksCatalogueIsProbedInstalledAndFires`** (EH). each catalogue probe answer carries
   `codeSources: ["skillroy-sample-lending-app.jar"]`; the install answer's `catalogue.deny` equals
   `["ai.skillroy.testjava.samples.lending.Inbox"]`; every `touched` line's `codeSource` is the app jar's name.
20. **Order 68 (new), `aClassIsOwnedByItsCodeSource`** (EH). `hook.probe` of `limit.applied` with `codeSource: 'hsqldb\.jar'`
   answers `UNAVAILABLE("class from another code source: skillroy-sample-lending-app.jar")`; a spec on a loaded HSQLDB
   class (`org.hsqldb.jdbc.JDBCDriver`, any declared method) reports `codeSources: ["hsqldb.jar"]` whatever its state;
   `hook.health.classes` lists `AccessControl`, `Deposits`, `WaiverEngine` with the app jar and their hook ids.
21. **Order 69 (new), `theCatalogueDeniesTheInbox`** (EH). `hook.probe` of a spec on `Inbox.offer`
   (`(Lai/skillroy/testjava/samples/lending/LendingInput;)Z`) answers `UNAVAILABLE("denied by the catalogue lending:
   ai.skillroy.testjava.samples.lending.Inbox")`; `hook.health.deny.catalogue` holds it, `from` is `["lending"]`.
22. **Order 165 (extended) and 166 (extended), `bytemanChecksARuleBeforeItIsLoaded…`** (EH). after `hook.remove {}` the deny
   is still held (`hook.health.deny`); then, before Byteman is loaded, `rule.check` of a rule on
   `ai.skillroy.testjava.samples.lending.Inbox` is `ok: false` with the deny in `errors`, a confirmed `rule.submit` of
   it is `RULE_REJECTED` naming it, and `rule.list.loaded` stays `false` (nothing appended, no `premain`); then the
   existing rule on `AccessControl.returnLimit` goes on as today (167, 168). Proves the stickiness across a remove and
   the before-the-load order on both target Javas.
23. **`BytemanIT` (`agent-dist`, failsafe, child JVM on the target Java), new method** (EH). launched with
   `hooks.deny=ai.skillroy.testjava.fixture.byteman.LendingToyMain` and no hooks module: `rule.check` lists the deny,
   a confirmed `rule.submit` is `RULE_REJECTED`, `rule.list` answers `loaded: false` and `deny.option` with the pattern;
   a second child with `hooks.deny=a..b` reports the module FAILED naming the key (`agent.info`).
24. **`BranchEndToEndIT`** (EH). unchanged assertions; it now runs with the inherited qualifier on `ReturnCeilings`.
25. **The per-target-Java CLI check** (EH). (`PerTargetJavaChecks`, the assembled launcher) may add `skillroy hooks status` on the
   hooks target and assert the `CODE SOURCE` column; optional (the scripted `HooksCommandTest` proves the printing).
   **As built (Phase 8, EH, 2026-10-19):** not taken (optional); items 19-24 run in `HookChecks` (orders 65, 68, 69,
   165, 166), `BytemanIT` and `BranchEndToEndIT` on the Java 25 and the Java 8 target.

26. **Warnings and acceptance** (ES). (`@Order(173)`): `semantic-input-warnings` PASS in every mode with two `warning`
   records (`VALUE_COERCED` with `taken` the trimmed barcode, `VALUE_DROPPED`), `RunResult.warnings` of two lines, the
   JUnit XML's `warning` lines; the negative `warning-not-accepted` `FAIL / ASSERTION` naming `VALUE_COERCED` with the
   hint `accept: [VALUE_COERCED]`; `accept-unknown-code` and `min-rung-without-ladder` refused at compile with the named
   messages.
27. **Correlated acknowledgements** (ES). (173): every `ack` wait of every committed scenario's `run.jsonl` carries
   `match: {inputId: …}` (asserted on `basic-loan`'s run in each mode); a raw `lending.input {…, ackTimeoutMs: 0}` on
   a state that drops the input (sent through the session API) returns `consumed: QUEUED` and a following verb's
   correlated wait ends `SYNC_TIMEOUT` naming the `inputId` rather than passing on another input's acknowledgement;
   `input.acked` payloads never carry a value (the password check of slot 200 extended to `taken` and warnings).
28. **Composites** (ES). (`@Order(174)`): `sign-on-any` PASS (branches `auto` and `password` in the `call` records,
   `state.staff.code` held); `scan-checked` PASS with the `prompt` record `expected: true, answeredBy:
   lending.scanChecked` for `induction-check` and the loan line present; `sign-on-any-password-required` `FAIL /
   ASSERTION` naming `lending.PASSWORD_REQUIRED`, recovery signs off, the next scenario runs; no password in any output
   file (the existing slot-200 scan over the new runs).
29. **Ladders, driver off** (ES). (`@Order(175)`): `device-scan` DEGRADED with a `rung` record (`rung: SEMANTIC`), one
   `fidelity` record, `degradedFidelity` one line; `scanner/device-scan-physical` `ERROR / PRECONDITION_FAILED` naming
   `lending.scanner.physical` with its `rungs` record and **no step call sent** (the `call` records hold `trace.mark`
   only); `device.list` shows `rungs: {scan: [PHYSICAL, SEMANTIC]}` and `devices[id=scanner]` the same; the golden
   holds the three new ids with the stated states (check 1c unchanged in mechanism).
30. **Ladders, driver on** (ES). (`JavaAgentScanner`, both target Javas): `lending.scanner.physical OK`;
   `scanner/device-scan-physical` PASS with `rung: PHYSICAL`, no `fidelity` record, the `minRung` echo in the `call`
   record's value; `device-scan` PASS at `FULL`; a raw `device.act {scan, minRung: PHYSICAL}` with a malformed barcode
   `INPUT_REJECTED` reason `malformedFrame` and no input posted (`lending.loan` unchanged).
31. **The three load modes and the hooks leg** (ES). run items 26-29 unchanged (the hooks leg through `HookChecks`'s shared
   list); the Java 8 target leg proves the module's Java 8 code (late acknowledgement from the engine thread, the
   driver, the device).

32. **The cluster's scenario in every load mode on both target Javas** (EX). `LoadModeChecks` gains `@Order(176)
   countsAndEventsScenarioPasses`: `loan-counts-and-events` through `Runs.run` PASS without
   sleeping, signed off after; its `run.jsonl` holds one `capture {source: facts}` taken right after `signOn` and
   before the two scans (§12.4's order), with `scans: 0` (the sign-on's two `Entry` inputs are not `Barcode`, and no
   `Barcode` acknowledgement exists yet), `signOffs: 0`, `lines: 0` (no loan is open, `LendingJson.java:83-97`), and
   `held` for every expectation. Hosted by `LendingEndToEndIT`'s `JavaAgent`, `JavaToolOptions`, `Attach`, `Embedded`
   and the hooks leg (`HookChecks extends LoadModeChecks`), on the java25 and java8-targets legs; the Robot leg
   (`RobotChecks`, standalone) runs none of the slots 157-177. The JUnit glue (`LoadModeChecks.junitGlueRunsTheCommittedScenarios`,
   `:915-945`) pins the committed ids as a literal list `all` (`:923-925`), compared sorted with the stream's names and
   counted `all.size() + 1`, so a new committed YAML is **not** in it without a line: the list gains the five new
   top-level ids (ES four: `semantic-input-warnings`, `sign-on-any`, `scan-checked`, `device-scan`; EX one:
   `loan-counts-and-events`; `scanner/` is outside the glue, §12.4), each package on its own branch (D641), and
   `device-scan` is filtered out of the `PASS`/full-fidelity `ids` loop (it has a `fidelity` record) and asserted
   `DEGRADED` with one `fidelity` record beside `loan-table-visual-check` (`:926-927`) (ES). The `{has: Key.DEPOSIT}`
   wait is the agent-side half (item 33), the correlation-id and source-regex filters prove the real agent's stamping
   (`HeadlessDesk` stamps neither).
33. **The agent-side rule on both Javas** (EX). The same item: the `waitFor` with `has` ends on the `input.requested` event the
   DUE key produced (its `sync` record `ok: true`, `match` as sent); and, in the same check, a raw `sync.await
   {topics: input.requested, since, match: {validInputs: {has: Key.RETURN}}, timeoutMs: 500}` through the client answers
   `TIMEOUT`, and one with `match: {validInputs: {in: [x]}}` answers `INVALID_PARAMS {param: match}` (the agent validates
   before it waits).
34. **Loss is a refusal, not a wrong pass: the small-ring launch** (EX). A new `LendingTarget.Mode.JAVAAGENT_SMALL_RING`
   (§12's planting rule: the `JAVAAGENT` launch with the extra agent option `eventRing=200`, as `JAVAAGENT_ROBOT` adds
   `robot=true`, `LST/LendingTarget.java:52-78`, `:203-206`) with its own nested class `JavaAgentSmallRing`
   (`SmallRingChecks`, ordered after the Robot leg) in `LendingEndToEndIT`, on both target Javas: (a)
   `loan-counts-and-events` is PASS there (its window holds far fewer than 200 events); (b) a scenario the test compiles
   from a string (`PlanCompiler.compile(bytes, name, pack)`: `signOn`, 300 `mark` steps, then `expect: {"events": {size:
   {gte: 0}}}`) ends `ERROR / ENVIRONMENT` "fact 'events' could not be read: the ring evicted events N..M since the run's
   start; raise the agent option eventRing", the step named, nothing passed; (c) the desk is signed off after each. The
   ten-full-pages rule (more than 10,000 events) is unit-tested only (§13.1 row 39): the default ring cannot be filled from a test
   in reasonable time, and the two loss conditions share one code path.
35. **Plan version 7 in the image** (EX). `ResolutionChecks` items 1-7 replay scenario A from its regenerated plan and bindings
   (`planHash` rehashed, `resolveHash` unchanged, approve byte-identical): no new item; the matrix proves the regeneration.

   **As built (Phase 8, EX), items 32-34.** Slot 176 (`countsAndEventsScenarioPasses`) asserts the capture of §12.4's
   As built note (`{lines: 1, qty: "1", scans: 1, signOffs: 0}`, step `s3`), eleven `expect` records held, the waitFor's
   `call` record of `sync.await` (a `waitFor` is sync `NONE`, so its record is the call's) `ok: true` with the match as
   sent, the Barcode acknowledgement of step `s2` carrying the correlation id `loan-counts-and-events/s2/1` (read with
   `evt.query`), and the raw `sync.await` checks from the DEPOSIT event's sequence: `{state: DEPOSIT, validInputs: {has:
   Key.DEPOSIT}}` answers that event, `{has: Key.RETURN}` is `TIMEOUT`, `{in: [x]}` is `INVALID_PARAMS {param: match}`.
   `SmallRingChecks` (`@Order(20) JavaAgentSmallRing`) checks `agent.info.options.eventRing` is `200`, item 34 (a) and
   (b) (the 300-mark scenario with a 3 s step timeout ends `ERROR / ENVIRONMENT` at `s302`, its one `expect` record
   `failed`), and the sign-off after each. The §13.1 row 38 part that names `ScenarioSchemaTest` is in
   `Phase8CompileTest` (`ScenarioSchemaTest` is EP's file); the headless test (row 41) runs each plan without its launch
   property (the headless desk is seed A's and answers no `jvm.properties`).

36. **`LoadModeChecks`, new slot 177** (EP). (every mode, the seed-A desk): a `basic-loan` run's `capabilities.json` holds
   `lending.returns.ceiling OK`, its `capabilities` record lists it under `pack` with `scope: scenario, cached: false`
   and `contributed: []`; an in-memory scenario asserting `caps[id=lending.returns.ceiling].source: pack:lending`
   passes; the run's one `preflight` record is `{id: lending.database, ok: true, scope: suite, cached: false}`
   (each `Runs.run` has its own options, no cache); the desk is signed off after.
37. **`ResolutionChecks`** (EP). (per mode and seed): scenario A replays `PASS` with its `requires` entry (items 1-7
   unchanged in outcome; the approve comparison byte-identical after step R); the precondition case (an unknown id
   added to `requires`) now sends `capabilities` then `data.query` before `requires` (`beforeRequires()`), still
   nothing after; the `FailingPreflight` case's record sequence `run-start, hooks, capabilities, requires, preflight,
   ledger, run-end` unchanged.
38. **`PerTargetJavaChecks`** (EP). (the JDBC leg, Java 25 and Java 8 targets): `lending.returns.ceiling` evaluated over the
   controller-side JDBC channel (`OK` in `capabilities.json`; no `data.query` reached the agent for it); the CLI `run`
   of scenario A and `basic-loan` in one invocation with the driver: the second scenario's `preflight` record
   `cached: true`; without the driver: `ok: false, cached: false` and `ENVIRONMENT` as today.
39. **`LoadModeChecks` slot 160** (EP). (the JUnit glue, every mode): across the stream's committed scenarios exactly one
   `preflight` record has `cached: false` and every later one `cached: true`.
40. **`BranchEndToEndIT`** (EP): `lending.returns.ceiling OK` read from `return_ceiling`; scenario A `PASS` on the branch.
41. **`HookChecks` / the `JavaAgentHooks` leg** (EP): the merge order holds (hooks → contributors → pack):
   `beforeRequires()` there is `capabilities, hook.health, capabilities, data.query`; scenario E unchanged.
42. **`InferenceChecks`' MCP subprocess item** (EP): `skillroy_catalogue` lists `lending.returns.ceiling` with
   `source: pack:lending`.

   As built (Phase 8, EP): items 36-42 and §13.1 rows 42-50 with the corrections of §9.7 (the calls before `requires`
   include `data.sources`; slot 177's `contributed`; item 38 without the driver; the CLI pack's id `clitest`).

### 13.3 Not verified in this phase, and why

No live item: nothing in Phase 8 needs a model key (common rule 7), so there is no `inference-live` row. Not verified
here, per section, each a line of `threat-model.md` §8 and of `SECURITY.md`:

- **§3 (EA).** The Windows and macOS peers' reaction to routed Java mouse events on AWT controls (§3.4; no such
  host); HW/LW mixing cut-outs in `mouseTarget` (gap 79); a veto in the second domain's own application (its layer is
  outside this repository; the lending veto is the stand-in); `allowFailedModules=true` with a failed layer module
  leaving its veto absent (asserted at unit level only: no e2e target has a failing module).
- **§4 (ET).** (a) A hook-entered idle callback (the second domain's runner behind an `OBSERVE` hook's `EventSink`):
  the sample's point is driven by an application listener; the hook path is stated (§4.1) and covered by P6's handler
  tests only. (b) `Statement.cancel()` on drivers other than HSQLDB (semantics vary: some block on the network, some
  cancel the connection's next statement): the contract bounds the action on its own task and calls it only while the
  statement is open; nothing else can be shown here. (c) A provider blocked in uninterruptible application code:
  proven with fakes (`stillRunning: true`, the per-source and lane `BUSY`), not on a target. (d) The MCP route of gap
  131 (not changed). (e) Windows: nothing in this section is platform-specific.
- **§5 (EW).** A Playwright line other than 1.63.0 (nothing offline resolves one; the gate is proved by the
  `ConnectSupport.of` seam, §13.1 row 19; an owner-run profile with one network fetch could run §13.2 item 18 on
  `-Dplaywright.version=1.60.0` and is left to the owner, no such profile being defined here); JxBrowser's view-less
  browsers and off-screen `bitmap` (no licence; gap 41); the CDP path's own events through a pack's row masks (not
  built, gap 138); the module-install window on attach (gap 139: masked by key only, stated).
- **§6 (EH).** An unreadable code source on a real `SecurityManager` target (Java 8 only; no such target in the
  matrix; the seam proves the mapping); a custom-protocol `URL` from a real application server or OSGi container (the
  throwing-handler test proves the rule, not a container); a Byteman trigger landing on a denied subtype loaded after
  the submit (the residual gap 140: its mechanism is BS14's, not reproduced here); two application jars sharing a
  package end to end (the unit tests prove it with `ChildFirstLoader`; a second sample module costs a reactor module,
  not taken).
- **§7 (ES).** The second domain's own composites, ladders and late acknowledgements (outside this repository; the
  rule and the checks are exercised on the sample's equivalents); a ladder whose physical rung is a real device (the
  sample's driver is the application's own code, which is what design §3.9 prefers in CI); a layer primitive that
  legitimately returns `warnings` objects in its value (unaffected by construction, not demonstrated); Windows (as
  every phase).
- **§8 (EX).** A CDP path's events in the root (not in Phase 8, gap 146); the `events` root on the web host (its pack
  has no scenario naming it); `[json]` on a target-published JSON string (the sample publishes none: the `mark` probe
  stands in, and the parser is unit-tested); more than 10,000 events in a run (§13.2 item 34's note); a target
  filling the 64 Mi character bound (the same `oldestSeq` path as the count bound, unit-tested with a scripted
  `oldestSeq`); Windows (no host).
- **§9 (EP).** The time a slow second-domain check saves (only the count of evaluations is asserted; no slow check
  exists in the sample); a suite that spans several test classes or JVMs (by design not cached: the suite is the
  stream, the invocation or the server); the second domain's configuration ids themselves (Q56, the owner's).

`threat-model.md` §8's Phase 8 line lists a subset of these and never more.

---

## 14. Exit criteria

Design §8's roadmap has no row for this phase yet (rows 0-7); the build's documentation pass adds **row 8** with
these criteria, item by item:

| Criterion | Met by |
|---|---|
| every high and medium row of wave 1 and the cheap lows (gaps 9-12, 18-22, 24, 105-122) are answered by a generic SPI, a result convention or a DSL form, each exercised on the sample lending desk | §16.2's table (28 rows taken, each with the subsection that takes it and what it closes; 34 applied, 6 deferred); §12; §13.1 rows 1-50 and §13.2 items 1-42 |
| nothing changes for a target or a pack that uses none of it, except the two privacy protections of §5.1 and §5.2 | the committed scenarios' outcomes unchanged in the matrix but for `planVersion` 7 and `packVersion` 0.7.0 (§13.2 items 6, 35, 36); `DefaultMethodsTest`, `SwingSpiDefaultsTest`, `WebSpiShapeTest`, `ValueTypesTest`, `PackOverlayTest` and the kept constructors (§2.3) green; every behaviour change of §11 marked where it stands |
| a component a layer vetoes is never acted on, on any route, `force` included | §13.2 items 2-4, 9, 10 on both target Javas; §13.1 rows 2-4 |
| every event payload is masked before it is stored, and a browser without a view is masked while any mask is registered | §13.2 items 15-17; §13.1 rows 15, 17 |
| a module's task runs on the application's thread only at its idle point, admitted by budget, and a slow provider read neither blocks the application lane nor outlives its caller | §13.2 items 11-13; §13.1 rows 6, 7, 9 |
| a hook binds the named jar's copy only, and a denied class is refused by both modules before anything reads it | §13.2 items 19-23; §13.1 rows 22-28 |
| a semantic input's caveats are warnings a step accepts or fails on, an acknowledgement is tied to its input, a composite's nested questions are reconciled, and a required rung is never acted through a shortcut | §13.2 items 26-31; §13.1 rows 29-34 |
| counts, deltas, membership and the agent's events are expectations, and a lost event window is a refusal, not a wrong pass | §13.2 items 32-34; §13.1 rows 35-41 |
| an environment fact only the pack knows guards a scenario in every mode, `replay` included, and a suite fact is checked once per suite | §13.2 items 36-42; §13.1 rows 42-50 |
| the regeneration once per cause (plan 7 at the skeleton; pack 0.7.0, module 0.3.0, the goldens, the recordings in step R) and the matrix green on all nine legs (arm64 twice, amd64 once, the `jcef` profile, the Robot, tools, scanner and small-ring legs once per Java) | step R (§2.8); `../TODO.md`'s Phase 8 matrix tables |
| the security delta review is run, everything medium or above fixed, and the owner signs it off | §15 (B40) |
| `replay` makes zero model calls, asserted, and nothing in the phase needed a key | unchanged; `InferenceChecks` and every `replay` run's ledger check |

---

## 15. The security delta review (B40)

**Decision (D632).** B31 covers the tree as of `223c5fb`, the last Phase 7 security fix; the 48 commits since it (the
B32-B35 follow-ups and `ListenerSoakIT`, 44 `.java` files) are in no signed record, and Phase 8 opens new surfaces on
every lens. Phase 8 is therefore reviewed as `docs/release-checklist.md` item 2 asks: a **delta review** over
everything changed since `223c5fb`, run after the build and the code review, in the method of P6 §12.3 and P7 §15
(D147, D338): the eight lenses L1-L8, each with one reviewer and one sceptic who tries to break the reviewer's claims,
the four-level scale and the counting rule of P6 §12.4 and `threat-model.md` §4 (a finding that needs the token counts
when the token's user is a model or an MCP client; the operator's environment is "operator-only"); findings numbered
**S149** onwards; every finding of medium or above **fixed** (contract first, a test that fails on the old code, then
the code), nothing of medium or above accepted as a known risk; a low may be accepted in writing with a release note;
the record (`record.json`, `dispositions.md`) in the session notes; the summary in `../TODO.md`; **the owner's
signature is B40**, and the orchestrator does not sign. What each lens looks at in this phase (from the fragments'
security notes):

| Lens | Phase 8 surface |
|---|---|
| L1 controller correctness and bounds | the three new parsers over target content (list filters, `containsWhere`, `[json]`) and their budgets; the `events` root's ten pages and its three loss conditions (an under-count would let a negative check pass); the `actual` cut; `anyOf`'s reasons; the declaration bounds of pack capabilities (64, 128), the cache bound (1,000), the refusal messages naming pack and id only, the stop-at-first-failure rule and the `InterruptedException` path (never cached); plan version 7's strict keys and gates; the warning channel's caps (16, 500, 2048) and the `INVALID_WARNING`/`TOO_MANY_WARNINGS` fail-closed codes; the ladder pre-check's matrix lookups; `OutputScan`'s three layers |
| L2 host safety (the agent against the host) | **the veto on every route that resolves a target** (twelve of the thirteen; the `ROBOT` click rung refused before resolution: walk `Clicks`, `Typing`, `Pointer.precheck`/`dispatch`, `RobotPath.check`, `SwingPrimitives.dismiss` and find no path to an action without the read-time and the in-task ask, `force` included; the fail-closed policy; the 64-level and 200-character bounds; the per-event check's component; the balance into a heavyweight leaf; no event ever sourced from a claimed surface; the semantic `ActionEvent`/`ItemEvent` inside the task after the in-task veto; `allowFailedModules=true` serving without a failed layer's veto, documented); **the one exemption of §4.1** (only `QuiescentPoint.runOne` reaches the wrapped task; `Guarded`'s refusal for every other path; a nested `call`, `callQuiescent` or `QuiescentPoint.await` `FORBIDDEN_THREAD`, the helper's own check on the application's thread and the 120 s cap, `EventBus.await` forbidden by rule; no `ThreadLocal` left on the application thread; never interrupted; `close()` before `uninstall` returns; the overrun penalty and the `agent.info` evidence; the sample's idle tick never shifting the idle lock); lanes (32 threads, retired with the module, S34 on their context loader); a cancel action as provider code on an agent thread bounded to 2 s; the ring pass on the publishing thread (the 10,000-scalar budget before any policy call; a throwing policy caught per scalar; `VirtualMachineError`s still propagating; no `toString()` of anything; the once-per-class log not floodable); `BrowserMask` called with the vendor object by identity only; `SeedWalk.masked` one bounded EDT read per rebuild; `CodeSources`' field-getter rule (no `URL` method but `getProtocol`, `getHost`, `getPath`; no `URL` as a key); `getProtectionDomain` under a Java 8 `SecurityManager`; `target.codeSource`'s regex on a worker over a 200-character name through `BoundedRegex` (a catastrophic pattern refuses the copy within the budget instead of stalling the worker under the engine's lock, D640); a decoded code-source name with its control characters replaced; `ClassDeny` under the engine's or the module's lock; item 2b reading nothing of a denied class; Byteman's deny before `premain` and before the class list is walked; the late acknowledgement published from the engine thread without blocking, the pending table bounded and cleared on detach; `Invocation.warn` non-blocking, capped; composites holding the `APP` worker with every wait under `remainingMs()`, never on the EDT; the scanner driver's 64-byte frame; `PayloadMatch` under the ring lock linear, no regex, no parsing, no allocation beyond `String.valueOf`; nothing of §9 running in the target JVM; §11's `SECURITY.md` sentences (a), (b), (d), (h) present |
| L3 the data channels | `SelectLabels` reading statement text and never logging it; a label never a value; a wrong label failing open exactly as a JDBC `AS` does (parity stated); `columns(table)` as reviewed module code that must name the relation `query()` reads (stated in the SPI); the `SqlTokens` move a pure refactor of a security component (S36, P2-L3-F1 cases unchanged); every `CALL`/`EXEC` refused on every path, the catalogue silent; `Statement.cancel()` only while the statement is open, never on a pooled connection's next statement; `TIMEOUT` and `BUSY` data carrying source and lane ids only; the per-source gate unusable to probe another source's statements; the `event:` prefix never a data source id and `DataQuery.mask` untouched; `PasswordMask`'s event rule keyed on `sourceId`; `containsWhere` refused in stored-row checks, `anyOf` per cell on masked rows; `captureFacts` never reading `db.*`; a pack capability's reason that may quote a row value masked like every reason, the `SuiteCache` in memory only, its key holding the agent session id and the env id, no credential; `taken` as application data under the run mask |
| L4 the web boundary (the page as an adversary) | the view-less rule failing closed only while a mask is registered (`masksRegistered` counting both SPIs; `Entry.masked` true after a failed or skipped EDT read; a `BrowserMask` FALSE never releasing a component a `CaptureMask` names; the four reads of a masked view-less browser refused before any page evaluation, JCEF's `CAPABILITY_UNAVAILABLE` path not reached first; `web.load`/`web.console`/`web.tap` of a masked browser carrying no page text, `error`, `source`, `detail` dropped not emptied; `by: {url\|title}` unable to probe a masked browser one regex at a time; `web.pointer`'s `hit`, `web.dom.click`/`fill`'s `clicked`/`filled` and the `AMBIGUOUS` candidates of a masked browser carrying no `name` or `id` on both paths, `web.dom.click {selector: "*"}` revealing nothing, D637); on the CDP path an absent `masksRegistered` counting as true, unmapped pages and url/title selections handed over while it is true, `PathEvents` withholding a masked page's URL and detail; `allowDefaultOverrides` read once in `open` from a system property, never from a scenario, a model or a client, recorded in `web.path`; every `Error` path of the attach closing the pending Playwright and ending the thread; the claimed-surface exception keeping page input on `web.pointer`'s checks (`SwingSpis.claiming` consulted on the path from the window down, not on the source alone); `WebRun.matches` through `PayloadMatch`; gap 126 (web input into a vetoed browser component) and gap 138 (the path's own events) as recorded residuals |
| L5 the model boundary (what leaves to the provider) | the veto's text as the layer's own one-line string of 200 characters reaching a model and the journal, never target content; a warning's text module-authored, redacted by the dispatcher for secret parameters and masked by the run mask, one printable line of 500; `inputId` opaque, never a value; a composite's `answered[].by` a parameter name, `choice` forbidden on a secret parameter; the executor's secret-echo check failing a step that returns a secret value before any sync, capture or model view; the `events` root exposing to a model through `skillroy_check`/`fact` whatever a module published, masked as §5.1 decides (confirm §5.1's masking at publish precedes any model-facing read of this root); `expectationsOnMaskedValues` extended to captured facts; a session's `captureFacts` under the session mask; `hook.health` and `rule.list` showing jar file names (never directories), patterns and deny reasons passed through `redactText`; the guard's `PROCEDURE` refusal the only new text a model sees from §4, the catalogue no longer advertising procedures; `agent.info.events` revealing counts only; pack capability descriptions as pack-authored text in the schema and the catalogue; `redact: false` on the new refusal keeping `CallPolicy`'s rule |
| L6 the model as an actor (MCP, session, planner, overlay) | nothing a model or a client passes (`force`, `expectTarget`, `via`, `redact: false`) skipping a veto or a mask; a 0.1.0 agent answering no `vetoed` key, so the controller does not fail closed on its absence (the agent is the enforcer; the recorder's refusal is a courtesy, D507); a model never setting `accept` or `minRung` (sessions refuse raw calls carrying them; a verb's pre-acceptance is the pack author's); a client's own scenario accepting warnings through `skillroy_scenario_run` (its scenario, its verdict); no model call anywhere in §9 (the evaluation context carries no gateway); `replay` honouring pack ids with the D19 ledger check standing; a model unable to set a capability's state (no primitive, no tool); `on_prompt` narrowing removing a silent policy choice from a model-written scenario; `hook.probe`, `hook.install` and `rule.*` still never offered (a model cannot write a qualifier or a deny); the session snapshot showing `[web <ref> masked]` for a view-less masked browser; the verb and the matchers of §8 giving a model no new write path |
| L7 Windows and file-system trust | no new writer, directory or file format: `run.jsonl`'s new records and keys, `capabilities.json`'s pack entries and `bindings.json`'s `captured` through the existing `OutputFiles` route; the `SuiteCache` never written; the ACL branch unverified as before (threat model §8) |
| L8 evidence at rest | `run.jsonl`'s `call`, `prompt`, `capture` and `step` records, `failure/events.json`, the JUnit XML, the MCP `events/recent` resource and the CLI's stream carrying masked payloads (check with `OutputScan` `Scope.EVENTS` on §13.2 item 15's run that the planted name is in none, and that `Scope.ALL` finds it only in a snapshot of the status line); `data.veto` and `data.vetoed` entering `run.jsonl` and the failure detail as any error data does (bounded, module-authored; no pixel change); `thread: LendingEngine` and the new `TIMEOUT` keys for a quiescent call; log lines naming sources, lanes, primitives and exception classes only; the regenerated inference fixture holding no procedure line and no value; the `hooks` record's `deny`, `denyNotDelivered`, `deniedNow` (patterns and hook ids; `touched` a count); the catalogue hash now covering `deny` so an archived record says which deny list a run ran under; the new records `warning`, `rung`, `rungs`, `prompt.answeredBy`, the result lists and a coerced `taken` masked as every record and line is; `capture {source: facts}` and `bindings.json`'s `captured` through the run's mask; `actual` of an `events` expectation writing module payloads into `run.jsonl` as published, bounded in number by the cut; `web.path`'s `playwright` and `connectOptions` as versions and booleans; `capabilities.json` and the `preflight` record gaining masked pack entries and two fields; nothing written outside the run directory; §11's `SECURITY.md` sentences (c), (e), (f), (g) present |

The delta review's "not verified" list (§13.3) joins `threat-model.md` §8, `README.md` and `SECURITY.md` by the
build's documentation package; the release checklist's item 6 repeats it. The build reserves B39 for its own, the code
review's and the fix wave's decisions, in P7's form (D359-D430, D431-D486).

---

## 16. Changes to the earlier contracts, the design overview, the guide, the threat model, `SECURITY.md` and `README.md`

### 16.1 Marks into the earlier contracts and the other documents

Written into the files named when the build starts, marked "(Phase 8, specified 2026-10-19, not yet built; P8 §n)" and
"(Phase 8, built …; P8 §n)" once built; the P1 passages carry their A-numbers (§10). Each package writes its notes under
its tag (in parentheses after each row) and never rewrites another's passage; the orchestrator merges by tag. One table
per document; where two packages mark one section, both rows stand and the build merges them into one passage.

**`phase1-contracts.md` (P1)**

| Section | Change |
|---|---|
| §4 SPI table | `InputVeto` (A67): the interface, the routes, the ancestor rule, fail closed, "(Phase 8, specified 2026-10-19, not yet built; P8 §3.2)"; `AgentModule.info()` default, shown in `agent.info.modules[]` (A68) (EA) |
| §6 Node | `vetoed: true` and `collapsed: true` only when true (A68, A69) (EA) |
| §6 Locator | "Only showing **and on screen** components match unless `includeHidden`" with the effective-area definition (A69) (EA) |
| §6 primitives table | `swing.click`/`swing.type`/`swing.key`/`swing.dismiss` rows: the veto and `collapsed`; `swing.hitTest` result `vetoed`, `source`; `swing.pointer` result `source`, `vetoed`, reason `VETOED`, the component form's `not visible` (A68, A69, A70) (EA) |
| §6 "Click rungs" | ACTION and COMPONENT fire `ActionEvent`/`ItemEvent` for AWT `Button`/`Checkbox`; DISPATCH's toolkit dependence; "Clicking a disabled, non-showing **or collapsed** component is `INVALID_PARAMS`" (A71, A69) (EA) |
| §6 "Modal blocking" | a new paragraph "Input vetoes" before it: every route, `force` never skips, the in-task re-ask, the pointer per event (A68) (EA) |
| §6 A27 | a collapsed in-window dialog root blocks nothing and is logged once per root class (A69; the `DialogLocator` Javadoc says the same where the layer author reads it); `swing.dismiss {button}` presses an AWT `Button` (A71) (EA) |
| §9.1 | `lending.tools` (the tools window); §9.2 the module's fourteenth SPI (step R writes the version) (EA) |
| §4 `skillroy-agent-api` (the `ThreadPolicy`/`ThreadBroker`/`ThreadPolicyProvider` block `P1:856-938`; the SPI table; `DataSourceProvider`/`QueryResult` `P1:1005-1020`) | `APP_QUIESCENT`, `LANE`; `QuiescentPoint`, `Lane`, `QueryControl`; `ThreadPolicyProvider.quiescentPoint`; `ThreadBroker.callQuiescent`, `declareLane`, `callOnLane`, the refused `post`s; `DataSourceProvider.query/5`, `enforcesTimeout`, `lane`, `columns`; the `QueryResult` names sentence (A72 to A75); the "never on a forbidden thread" sentence with its one exemption (ET; built 2026-10-19) |
| §5 dispatch, S30, `agent.info` (`P1:1355-1412`, `:1482`) | the `APP_QUIESCENT` gates, `LANE` dispatch, the S30 policy list, `threads.quiescent` and `threads.lanes` (ET; built 2026-10-19) |
| §6 `skillroy-agent-data` (`P1:1704-1727`, `:1764-1765`) | the hand-off and the module's own `TIMEOUT`, the per-source gate, `data.sources` keys, the naming and width rule in place of "reported as `c1..cn`", `columns(table)`, `maxRows: 0` true for the provider form (ET; built 2026-10-19) |
| §9.1 the desk (`P1:2792-2793`) | the engine polls with the idle tick; `EngineListener.idle`; `lending.idleTickMillis`; `IdleView`, `readRows`, `columnsOf` (ET; built 2026-10-19) |
| §9.2 the module (`P1:2936-2947`, `:3012`) | fourteen registrations (`lending-report`), the point, `lending.engine.view`, the lane; version 0.3.0 (step R) (ET; built 2026-10-19) |
| §4 `skillroy-agent-api` (the SPI table, `MaskPolicy`'s block, `:1034-1039`) | the events route and the thread rule (A77); `BrowserMask` in the web SPI (A79) (EW) |
| §5 dispatch, events and the ring (`:1385`, `:1414-1470`, A34's events bullet `:1458-1461`) | the ring pass at publish (A78): A34's "as published" becomes "as published, after the key rule and the registered policies, never by value"; `agent.info.events` (EW) |
| §2.4 options | `eventRing`/`eventRingChars` unchanged; a note that the bound runs after the pass (EW) |
| §8.5 failure evidence (`:2611`), §8.6 CLI `events` | the events written and printed are the masked ones (EW) |
| §2.4 options | the row `hooks.deny` (A81): comma-separated `ClassPattern`s, read at install by the hooks and the Byteman modules, FAILED naming the key when invalid; `lending.hooks.deny` is the sample's (P1 §9) (EH) |
| §4 `skillroy-agent-api` | the SPI table's hook comment (`phase1-contracts.md:1041-1044`) gains `ClassDeny` and `HookEngine.deny()`, and `…agent.api.instrument` gains `CodeSources` (A82) (EH) |
| §9 the sample | `lending.yaml` version 2 (qualifier, deny); `LendingClassDeny` behind `lending.hooks.deny` (EH) |
| §3 `skillroy-protocol` | `RpcResult.warnings` and `CallWarning` (A83); the conventions `InputResult`, `RungResult`, `CompositeResult` (A86); A33 unchanged, `INPUT_REJECTED data.reason` standard values; the version stays 0.2 (ES) |
| §4 SPI table | `Invocation.warn`, `EventBus.publish(…, correlationId)` (A84); `DeviceAdapter.rungs` (A85) (ES) |
| §5 catalogue, dispatch, events | `device.list` `rungs`; the dispatcher's redaction applied to warnings; a late event with a given correlation id (ES) |
| §8.1 YAML | the step modifiers `accept`, `minRung`; `ui.strict` for ladders (ES) |
| §8.2 plan | the call keys `accept`, `rungs`, `minRung` (version 7, EX §8) (ES) |
| §8.3 pack SPI | `SyncPolicy`'s sixth component; `PlannedCall`'s three; `CompileContext.minRung`; `DomainPack.warningCodes`; `Sync.ACK`/`READY` "correlated under `ackByInputId`"; `RESERVED_VERBS` with `accept`, `minRung` (A87) (ES) |
| §8.5 run sequence, error mapping, outputs | the result checks and their order (7.7), the records `warning`, `rung`, `rungs`, `prompt.answeredBy`; the rung pre-check after `requires`; unaccepted warning = `FAIL / ASSERTION`, secret echo and composite violation = `ERROR / ENVIRONMENT`; A29's fidelity record de-duplicated with the ladder's; `StepResult`/`RunResult.warnings`; JUnit and CLI lines (A88) (ES) |
| §9.2 sample primitives and events | `lending.input` row (fields, `inputId` parameter, `data.reason`), `lending.signOn`, `lending.scanChecked`, the device `scanner`; the sentence "`input.acked` is published by `lending.input` itself, on the calling thread" amended: in-call on the calling thread, late from the engine thread, payload `{source, inputId}`; capabilities `lending.signOn`, `lending.scanChecked`, `lending.scanner.physical` (A89) (ES) |
| §9.3 pack and verbs | `ackByInputId`, `warningCodes`, `signOnAny`, `scanChecked`, `deviceScan`; "Use an assistant without `auto_sign_on`" holds for `signOn`; `signOnAny` takes either (ES) |
| §10 acceptance | §13.2 items 1-5 (ES) |
| §2.4 options (`eventRing`, line 323) | note: the `events` fact root's window is bounded by the ring; the root refuses rather than under-count (P8 §8.6) (EX) |
| §4 `EventBus` (881-883) | `await`'s javadoc: a match entry is a scalar (text equality) or `{has: scalar}` (list membership by text), `PayloadMatch` (A90) (EX) |
| §5 table, `sync.await` row (1489) | `match` grammar; `INVALID_PARAMS {param: "match", reason}` for an invalid match (A90) (EX) |
| §8.2 Plan (2179-2232) | `planVersion` 7; strict step and call keys from 7 (A91) (EX) |
| §8.3 base verb table (`waitFor` row 2345, `expect` row) and base facts (2381-2397) | `waitFor`'s `match` validated at compile; the verb `captureFacts` (reserved); an eighth root `events` after `hooks` (A92) (EX) |
| §8.4 Expectations (2404-2462) | the grammar's list form, dotted filter fields, `[json]`; `size` with a nested matcher (built since Phase 1, documented now); `anyOf`, `containsWhere`; "one budget per matcher evaluation" now literal (A93) (EX) |
| §7 (the controller's client) | `AgentConnection.agentSession()` (A98): the verified `/ping` session, no I/O, null by default (EP) |
| §8.1 scenario YAML | `on_prompt`'s comment and prose: `fail \| pack`, any other value refused at load (A94) (EP) |
| §8.3 pack SPI | `DomainPack.capabilities()`, `PackCapability`, `CheckScope`, `PreflightCheck.scope()` (A95); `PromptPolicy`'s sentence on the key's values (EP) |
| §8.4 guarded expectations | a pack-contributed id may guard; the gap 24 decision sentence after "anything a scenario cannot do without belongs in `requires:`" (A97) (EP) |
| §8.5 run sequence and records | step 1: the pack's capabilities evaluated after the contributors' merge, reads allowed before `requires`; step 2: a `SUITE` check answered from the suite cache; `capabilities.pack`, `preflight.scope/cached`; the Phase 7 note extended (A96) (EP) |
| §9.2 the sample | pack 0.7.0: `lending.returns.ceiling` (pack side, source `pack:lending`, never in a golden), `lending.database` `SUITE`, scenario A's `requires` (A99) (EP) |

**`phase2-contracts.md` (P2)**

| Section | Change |
|---|---|
| §2.2 `ReadOnlyJdbc` row (`P2:154`) | the statement-sink overload (ET; built 2026-10-19) |
| §7.2 rows Execution and Timeout (`P2:1194-1196`), §7.4 closing sentence (`P2:1314-1316`), §15 rows 9-12 (`P2:2827-2830`) | the agent channel's `TIMEOUT` data; the zero-row sentence corrected ("a positional source can be checked, its width taken from the statement when no row comes back"); rows 9-12 "taken by Phase 8 (P8 §4)" (ET; built 2026-10-19) |
| §3.4 captured slots, §7.3 controller masks, §11.4 never written (`:1912`) | a slot captures the masked value; §11.4 gains "a personal value a registered `MaskPolicy` masks, in any event" and the `event:` route in the masks' list (EW) |
| §13.2 the sample's `PasswordMask` | the second rule (EW) |
| §3.4, §6.1 `PlannedCall.capture` | the three new components beside it; §13.2/§13.3 `interactiveSignOn` unchanged for `signOn`; the deferred rows 18, 19, 22 closed (P2 §15) (ES) |
| §3.3 reserved names (305-318) | `captureFacts` joins the reserved verb names (EX) |
| §3.3 reserved names | `accept` and `minRung` join the reserved verb names: a step holds one verb key plus the reserved modifiers, and a pack verb of either name is refused at compiler construction (P8 §7.3) (ES) |
| §3.4 a slot captured from an event (320-337) | a slot captured from facts (P8 §8.7) (EX) |
| §5 plan.json (496-594) | version 7: the step member `facts`; the version rule applied once more; strict keys (EX) |
| §15 rows 20, 21 (2823, 2834) | closed by P8 §8.7 and §8.6/§8.8 (EX) |
| §3.3 reserved names | the pack namespace `<packId>.`, the equality rule (§9.1.6) (EP) |
| §11.2 steps 1-2, §12.1 `resolve` row | step 1 names the pack evaluation; `resolve` "runs pre-flight, then binds each scenario" corrected to "per scenario, a `SUITE` check once per invocation" (EP) |
| §15 row 24 | closed by P8 §9.4 (EP) |

**`phase3-contracts.md` (P3)**

| Section | Change |
|---|---|
| §12 row 12 (`P3:979`) | taken (ET; built 2026-10-19) |
| §3.4 `devices` root | `rungs` mirrored (ES) |

**`phase4-contracts.md` (P4)**

| Section | Change |
|---|---|
| §3 the SPI (`:234-490`) | `BrowserMask` beside `BrowserRegistryAdapter` (A79) (EW) |
| §4.1 index, §4.2 `web.browsers`, the four reads and the three actions | `Entry.masked`, the view-less rule, `masksRegistered`, the selection rule (ii), the action answers without page data (iv) (A79) (EW) |
| §4.3 events (`:650-700`) | the masked browser's payloads (i) (EW) |
| §5.1 `CdpOptions`, §5.2 the CDP path (`:1160-1250`) | `allowDefaultOverrides`, `ConnectSupport`, the per-option table, the `web.path` fields (A80); the hand-offs (iii) (EW) |
| §6.4 evidence | `web.trace` under an `artifactsDir`-less line; `failure/web-browsers.json` holds the masked listing (EW) |
| §9.2 the web host module | the option values (EW) |
| §16 row 22 | closed; a web verb may declare a ladder the same way later (`path`/`fidelity: DOM` unchanged) (ES) |
| §7.3 (plan version) and gap row 21 (2354) | version 7; row 21 closed for the agent's bus; the CDP path's events gap 146 (EX) |

**`phase5-contracts.md` (P5)**

| Section | Change |
|---|---|
| §5.2 record comment (`P5:640-641`) and the D100 paragraph (`P5:669-686`); §5.3 rule 2 and the SQL Server sentence (`P5:707, 733-734`); §16.2 row 13 (`P5:2763`) | `procedures` reserved and required empty; the hint; "no `CALL` or `EXEC` on any channel (gap 119, P8 §4.5)"; the BS6 divergence; row 13 corrected (ET; built 2026-10-19) |
| §9.4 resources (`:1503-1523`) | `events/recent` is masked by the agent too (EW) |
| §12.1-12.3 sessions, journal, export | `StepOutcome.warnings`; a raw call with modifiers refused; journal call keys; the model view's `warnings` only when non-empty; §13.2 plan 7 (EX) (ES) |
| §12.2 (checks on secrets) | the captured-facts rule under `expectationsOnMaskedValues` (P8 §8.7) (EX) |
| §8.2 `re-resolve` step 1 | "checks `requires`" now against the merged matrix (contributors and pack); "runs the pre-flight once" stays true (through `Preflight`) (EP) |

**`phase6-contracts.md` (P6)**

| Section | Change |
|---|---|
| §4.3 the hook capture mask (`:1247-1259`) | "Events keep their payloads otherwise unredacted" becomes "pass the ring pass a second time under `event:hook.<id>`" (EW) |
| §3.3 | item 2b (the class deny, three sources, before "loaded?"); item 3's code-source filter and `codeSources`; the reasons (EH) |
| §3.6 | `HookEngine.deny()`; the engine's own `ClassDeny` registration (EH) |
| §3.7 | the options table row `hooks.deny` (EH) |
| §4.1 | `HookTarget.codeSource`, `HookSpec.violation()`'s field, `HookProbe.codeSources`, `ClassPattern` (EH) |
| §4.2 | `hook.install`'s `catalogue.deny` and the echo; `touched[].codeSource`; `hook.health`'s `classes` and `deny`, `deniedNow`; `hook.probe`'s `codeSources` (EH) |
| §5.1 | `deny:`, the `packages` map form, inheritance, the reader's new errors and warning, the hash with `deny`, `descriptor()`, the API additions (EH) |
| §5.3 | the echo requirement, the upgrade hint, `denyNotDelivered`, a deny-only catalogue, `deniedNow`, the record's new keys (EH) |
| §6.2 | `status`'s deny lines, the class table, the `CODE SOURCE` column, `--json` (EH) |
| §7.3 | the class bound's deny before the count and the load; matched subtypes; `rule.check`'s name-level check; the later-loaded-subtype residual (EH) |
| §7.5 | "Targets" gains the deny (EH) |
| §11.2 | the overlay's union of deny and package rules, the baked-base rule, the `disable` escape (EH) |
| §17 Q6 | the third-party warning stands; the qualifier is the mechanism that makes "our own classes only" sayable (EH) |
| §4.5 (`rule.check`) | its `warnings: [string]` stays information in the value, not the channel (ES) |
| §6.3 (`hooks` root, 1571-1600) | the `events` root beside it; the paging rule shared, the loss rule stricter here (EX) |
| §6.4 `capabilities.json` | holds the pack's entries too (EP) |

**`phase7-contracts.md` (P7)**

| Section | Change |
|---|---|
| §2.4 bounds | the rows of §3.7 (EA) |
| §3.2 | `vetoed` (always), `source`; the mouse-target search below the innermost heavyweight; `targetNode` unchanged (EA) |
| §3.3 | the decision text (source rule, the claimed-surface exception), steps 1 (the veto in the pre-check, the component form's point check), 2 (the exit on a source change), 7 (the per-event veto, `VETOED`), result `source` and `vetoed`; "Errors" gains `vetoed`, `not visible` (EA) |
| §3.4 | the veto in `check`, both reads, before the probe moves (EA) |
| §3.9 | the `INPUT_REJECTED` reasons `vetoed`, `not visible`; no new id (EA) |
| §6.5, §11 rule 6 | the recorder refuses a vetoed hit as a masked one, `Recorder.VETOED`; the raw pointer too (EA) |
| §12.2 | the sample module's `LendingInputVeto` (EA) |
| §16.2 gap 79 | the per-event veto uses the inferred mouse target, so `deepest` is checked too (EA) |
| §3.6 S111 (`:808-824`), §4.7 (b) (`:1163-1168`), §11 (b) | "every route" now includes the events, the selection error data, the action answers (D637) and a view-less browser; the CDP hand-off covers unmapped pages and the three actions (EW) |
| §8.2 plan version 6 | "applied once more: version 7" (EX) (ES) |
| §8.2 (plan version 6) | superseded by version 7 (P8 §8.2) (EX) |
| §8.5 contributors | a "pack capabilities" paragraph: evaluated after the merge, source `pack:`, honoured in `replay`, the record's `pack` (EP) |
| §9.2 `ExecutorSession`, §9.3 `caps` | `SessionOptions.suite` (fifteenth), `session-start.options.pack`; `caps --pack` rows with `SOURCE pack:<id>` (EP) |

**`base-framework-design.md` (design)**

| Section | Change |
|---|---|
| §2.2 | "No primitive calls application code known to end the JVM" gains "; a layer names such code's controls with `InputVeto`, and no route reaches them" (EA) |
| §2.6 SPI table | `InputVeto` row (EA) |
| §3.7 | the ladder's ACTION row (AWT controls), a paragraph "Input vetoes", "Locators" (on screen), the routing sentence of the pointer (innermost heavyweight) (EA) |
| §3.10 | the routing sentence (EA) |
| §2.6 SPI table (`ThreadPolicyProvider`, `DataSourceProvider` rows), §3.9 (`design:658-663`) | the new members; "SQL `SELECT` only; a procedure call is refused (P8 §4.5)"; lanes and the quiescent point in one sentence each (ET; built 2026-10-19) |
| §2.5 events, §2.6 SPI table, §3.8 attach block and version-pin bullet (`:599-606`), §1.3 table (`:117`), §6 provisioning (`:942`) | the ring pass; `BrowserMask`; the gate as built with the floor; "a pack pins older only when a spike shows the newest cannot attach, and then reads §10.3's floor" (EW) |
| §2.6 "Hook policy" | the bullet "third-party classes are off by default" gains: ownership by code source (`target.codeSource`, qualified `packages`), and a per-catalogue deny honoured by both modules (EH) |
| §2.5 | acknowledgements correlated by `inputId`; the warning channel in the step protocol (ES) |
| §3.7 | "Every input primitive reports the rung that fired" now a checked convention for pack ladders (`rungs`, `minRung`); the ladder table's note on `PHYSICAL`/`SEMANTIC` for devices (ES) |
| §3.9 devices | the rung in device action results; `rungs(action)` (ES) |
| §3.6 `DomainPack` | `warningCodes`; `SyncPolicy.ackByInputId` (ES) |
| §2.5 (events), §3.2 (DSL, line 401), §3.6 (`FactProvider`, line 519) | the `events` root; counts, deltas, `anyOf`/`containsWhere`, `captureFacts` in the DSL; eight base roots (EX) |
| §3.2 grammar table | `on_prompt`: "`fail`, or `pack` (the pack's policy, the default)" (EP) |
| §3.6 `DomainPack` listing and the extension-point table | `List<PackCapability> capabilities(); // Phase 8`; the pre-flight row gains the scope (EP) |
| §3.9 pre-flight paragraph (`:665`) | "They run before each scenario; a `SUITE`-scoped check once per suite, environment and agent start" (EP) |
| §6 junit.xml row (`:935`) | "one `environment` testcase for handshake, backend and credentials failures; a pre-flight failure is reported per scenario (a `SUITE` check's cached failure in every later scenario of the suite without re-running it)" (EP) |

**`../guides/integration-guide.md` (guide)**

| Section | Change |
|---|---|
| §6.2 | row `InputVeto` ("every control whose action ends the JVM or cannot be undone; EDT; fast, lock-free; `LendingInputVeto`; P8 §3.2") (EA) |
| §6.7 | the veto (route `ANY` rule; "veto the window or the bound control"; "a veto on a window or panel does not cover `web.*` input into a browser inside it; a layer that must keep page input out vetoes nothing more for that, gap 126"; "a veto that depends on application state and refuses inside the posted task leaves the step `fired: true`: the step's `sync` or an expectation on the effect fails it"), the visible-area rule, heavyweight containers, AWT controls on ACTION (EA) |
| §7 rule 4 | "register an `InputVeto` for every such control; key bindings are not followed: veto the window or the bound control; a window veto does not cover `web.*` input into a browser inside it (gap 126)" (EA) |
| §6.2 (`ThreadPolicyProvider`, `DataSourceProvider` rows), §6.3, §6.4, §6.9, §6.11 (parity), §11.3, §15.2 (rows 9-11, 13 kept) | the idle-point runner and its thread rules; lanes and "never `APP` for a slow read" with the `data.query`-on-`WORKER` correction; cancellation, `enforcesTimeout`, labels, `columns(table)`, the version-skew sentence; "procedures are not served"; the rows of 9, 10, 11 removed (ET; built 2026-10-19) |
| §6.2 SPI map, §6.8 browsers, §6.11 masking, §6.13 events, §7 rule 9, §10.3, §10.4, §15.2 | the rows and sentences of §5.1-§5.3; the `sourceId.startsWith("event:")` migration note; the view-less rule and the release form; the floor; gaps 109, 110, 121 removed from §15.2 (EW) |
| §6.2 | a row `ClassDeny` (what: refuse a class by name before any bytecode change; when: a hot path, a licensed or timing-sensitive class; thread: agent workers, cheap, never blocks; sample: `LendingClassDeny` behind an option; contract: P8 §6.5) (EH) |
| §9.2 | the `packages` map form and `target.codeSource` with the name forms of §6.2's table (the IT copy, a versioned jar, a class directory, a nested jar) and the anchored match; `deny:`; "bump `version` on every change" unchanged (EH) |
| §9.3 | the deny as a refusal, its three sources, the add-only rule ("loosening a deny needs a target restart; `hook.health.deny` shows the effective list"), `denyNotDelivered` and the remedy `hooks.deny`, a hook installed before its deny is flagged not removed (EH) |
| §9.6 | Byteman honours the deny before it loads; the later-loaded-subtype residual (EH) |
| §15.2 | rows 113 and 114 are not listed there today; nothing to remove; gap 140 added (EH) |
| §6.5 | the result convention, the codes, `inputId` and late acknowledgements, the EVENT pattern (ES) |
| §6.10 | `rungs(action)`, the rung in results, `minRung` refusal and echo (ES) |
| §6.13 | the four-argument `publish` for late events (ES) |
| §6.14 | `inv.warn`; the composite rule and `CompositeResult` (ES) |
| §8.2 | `ackByInputId`; `accept`/`minRung`; `warningCodes`; pre-accepting on a verb's call; ladders on `PlannedCall` (ES) |
| §8.3 | nested prompts a composite lists are expected (ES) |
| §8.6 | the step grammar with modifiers; the plan keys (ES) |
| §8.8 | warnings in the taxonomy; a lower ladder rung is `DEGRADED`; `PRECONDITION_FAILED` from `minRung` (ES) |
| §15.2 | rows 18, 19, 22 removed; gap 142 added (ES) |
| §4.4 (319-331) | "version 7 since Phase 8" (EX) |
| §6.13 Events (691-698) | the `events` fact root reads the ring for the run's window: publish small, structured payloads; a chatty module makes the root refuse (EX) |
| §8.2 Verbs and sync (784-806) | `EVENT` "with a payload match: scalars or `{has: scalar}`" (EX) |
| §8.3 Facts (808-819) | eight base roots; `events` (EX) |
| §8.6 YAML and plan (856-909) | examples: `"loan.lines[*code=$item.code]": {size: 2}`, `anyOf`, `containsWhere`, `captureFacts` and a delta, `{size: 0}` on `events`, `{has}`; the quoting note; plan 7 (EX) |
| §15.2 (1347-1365) | row 21 removed; nothing for 20/108/118/120 (never listed) (EX) |
| §6.12 | pack capabilities as the second source; the gap 24 decision pointer to §8.8 (EP) |
| §8.1 table | `promptPolicy()` row ("unless `on_prompt: {unexpected: fail}`"), `preflight()` row ("run before each scenario; scope `SUITE` once per suite and agent start"), a new `capabilities()` row; the contribution paragraph (`:778-779`) gains the pack prefix (EP) |
| §8.3 bullets | pre-flight: "a failure is `ENVIRONMENT` for the scenario; a `SUITE` check that failed fails every later scenario of the suite without running again; keep `SCENARIO` checks cheap"; prompts: the two accepted values (EP) |
| §8.6 (`:899`) | the example's comment (EP) |
| §8.8 | the gap 24 decision (§9.4), the `lending.returns.ceiling` example in `requires:` and in a guard, the namespace advice (EP) |
| §15.2 | rows 24 removed; 112's "once per scenario" sentence gone (EP) |

**`threat-model.md`** (unlike the other documents, **written with the contract on 2026-10-19 and revised with it after
the contract review**: every row below is already in the file, where the "written into" cell says; the build adds
only its "(Phase 8, built …)" marks and the delta review's findings)

| Section | Change | Written into |
|---|---|---|
| §5 | a vetoed component is never acted on, for any origin, on any route, `force` included (the recorder and the raw pointer refuse it as they refuse a masked one, the text snapshot flags it); §6 a row "the input veto" (the layer's code decides; the agent fails closed; twelve of the thirteen routes resolve a target); §8 the AWT peers on Windows/macOS; §7 a window veto not covering `web.*` input into a browser inside it (EA) | §5's Phase 8 paragraph, §6's "the input veto" row, §7's and §8's Phase 8 bullets |
| rows "the agent against the host application" and "the data channels" (`:211, :217`) | "a thread the application forbids is never used, **except a task the core submits through a `QuiescentPoint` at the point the provider itself runs it, admitted by budget and never interrupted**, a nested call refused"; "SELECT only (a procedure call is refused on every path, P8 §4.5); provider reads on bounded lanes, cancellable, the cancel actions on an agent task of their own" (ET; built 2026-10-19) | §6's two rows and the "lanes and cancellation" row |
| §6 row "the pack ↔ the base" (`:222`), the web row (`:218`), §7 (`:251-258`) | event payloads bounded and masked by the base from each policy's registration on; a view-less browser masked by default, its action answers without page data; the CDP path's own events as a residual (EW) | §6's "the pack ↔ the base" row (the event sentence merged into it) and "a browser without a view" row, §7's Phase 8 bullet |
| §2 actor A13 (`:49`) | "(Phase 8) the new records `warning`, `rung`, `rungs`, `capture {source: facts}`, `capabilities.pack` (with `error`), `preflight.cached`, the `hooks` record's `deny` keys and `web.path`'s `connectOptions`: patterns, ids, versions and masked values, never a directory or a credential" (EW) | §2, A13's row |
| §6 | the row "the hook engine against the application's bytecode" gains "ownership by code source; a per-catalogue deny by name, honoured by Byteman before it loads, add-only per agent instance"; the Byteman row gains the deny (EH) | §6's two rows |
| §8 | a denied subtype loaded after a `^`/`INTERFACE` submit; a custom-protocol code-source URL on a real container; a `SecurityManager` target (EH) | §8's Phase 8 lines |
| §3 | a module's result is trusted code's output; the executor's secret-echo check and composite validation are consistency controls at the model and evidence boundaries, not a trust boundary; warning text reaches a model masked (ES) | §3's "The policies a layer registers are reviewed code" bullet (not a §6 row: the judgement is about what is trusted) |
| §2 actor A4 (`:40`) | note: the `events` root and `[json]` bring more target content under the controller's parsers; each read bounded (10 pages, 1 Mi per JSON string, depth 200, the regex budgets) (EX) | §2, A4's row |
| §6 (trust boundaries) | pack capability evaluation is pack code on the controller at run start, reads only through the guarded channel, no model, reasons masked like every reason and a failure's reason the exception's class only; the suite cache is in-memory, per host, never written (EP) | §6's "the pack ↔ the base" row (merged, no row of its own) |

**`SECURITY.md`**

| Section | Change |
|---|---|
| "Known limitations" | the eight limitation sentences of §11, (a) to (h): the veto's uncovered key bindings, a window veto not covering `web.*` input into a browser inside it (gap 126) and the failed-module case (EA); the quiescent overrun and the uncancellable read (ET); events masked by key and policy at publish (on attach, the module-install window by key only), the CDP path's own events not, the view-less browser masked while any mask is registered (EW); the Byteman later-loaded subtype and the add-only deny (EH); the secret-echo check's equality for short secrets, an undetectable composite, `taken` as application data (ES); the `events` root's window (EX); pack code on the controller with no host timeout (EP); the Playwright gate proved through a seam (EW) |
| "Run outputs", "A page's pixels are masked as a whole" | events are masked by key and by the pack's policies; a browser without a view is masked while any mask is registered, released only by a `BrowserMask` (EW) |
| "Reviews" | the delta review's record (§15, B40), written by the build's documentation pass (EE) |

**`README.md`**

| Section | Change |
|---|---|
| the status paragraph | one Phase 8 sentence (EE): the input veto; lanes, a quiescent policy, cancellable provider reads and positional sources named; event payloads masked and the view-less browser; hook ownership by code source and a class deny-list; warnings, acknowledgements by input, composites and rung ladders; counts, captured facts, the `events` root and plan version 7; pack capabilities and suite-scoped pre-flight; B38-B40 as they are answered |
| "Where to look" | a row for `phase8-contracts.md` (EE) |
| "Embedded web views" | the Playwright floor sentence of §5.3 (EW) |

**`../licence-audit.md`**

| Section | Change |
|---|---|
| notes (`:456-467`) | "1.63.0 is the audited line; a layer that pins another audits it" (EW) |

### 16.2 Open SPI and DSL gaps: what Phase 8 takes, what it defers, what it opens

**Taken** (the row's number, what the wave asked, where it lands, what closes):

| # | Gap | In Phase 8 |
|---|---|---|
| 9 | one application lane | **closed** (§4.2): base-owned named lanes, `ThreadPolicy.LANE`, `DataSourceProvider.lane()`, `data.query`'s hand-off and per-source gate; `APP` stays unbounded by decision (D528, Q13; gap 136) |
| 10 | a thread policy for the engine thread at a quiescent point | **closed** (§4.1): `APP_QUIESCENT`, `QuiescentPoint`, admission by budget, the one exemption, the penalty, `agent.info.threads.quiescent`; the hook-entered runner stated, not proven (§13.3) |
| 11 | query timeout advisory, no cancellation | **closed** (§4.3) for provider reads and the JDBC form: `QueryControl`, `enforcesTimeout()`, the statement sink; `open()` stays unbounded (gap 135) |
| 12 | positional sources | **closed** (§4.4) for reading by name and the masks: labels derived from the statement inside the target; the zero-row width defect fixed (§4.8) |
| 13 | session-scoped data reads | **deferred** (§1, D533): the second domain's agent primitives cover its cases; admission of a procedure call is this gap's prepared read, not a lone `CALL`; a security pass owed when taken |
| 18 | the semantic-input result contract | **closed** (§7.1-§7.3): the warning channel in the envelope, `InputResult`, `accept`, `data.reason` |
| 19 | conditional or optional planned calls | **closed by the stated rule** (§7.5): one agent-side composite, `CompositeResult`, the prompt reconciliation and the secret-echo check; the conditional call form stays open (gap 142) |
| 20 | expectations relative to an earlier step | **closed** (§8.7, §8.8): `captureFacts` and deltas against a captured count |
| 21 | event expectations | **closed for the agent's bus** (§8.6, §8.5, §8.8, §8.3): the `events` root with a regex on the source, `[json]`, negation as a count, dotted filter fields; a CDP path's own events are gap 146; no negated-wait verb by decision (D607) |
| 22 | rung ladders for pack verbs | **closed** (§7.6, §7.3): `rungs`/`minRung` on the call, the rung chosen agent-side and enforced, the rung in device results, `DeviceAdapter.rungs` |
| 24 | scenario-level preconditions on application configuration | **closed as a decision** (§9.4): configuration is a capability, probed or pack-evaluated, in `requires:` or a guard; no `preconditions:` key; the sample proof is scenario A's `requires` |
| 28 | pack-defined artifact kinds | **deferred** (§1): low; the workaround stands; the seam named |
| 29 | a pack-contributed CLI subcommand | **deferred** (§1): low; the seam named |
| 105 | an agent module cannot veto input to a component | **closed** (§3.2) for the Swing routes; `web.*` input into a vetoed browser component stays open (gap 126) |
| 106 | a pack cannot contribute a capability | **closed** (§9.1): the SPI, the merge, the sources, the records, every host; `re-resolve` fixed |
| 107 | input-correlated acknowledgement | **closed** (§7.4): `inputId`, late acknowledgements, `ackByInputId`, the EVENT pattern in the guide |
| 108 | counting and list-valued filters | **closed** (§8.3, §8.4): the list form, dotted fields, `size` with a nested matcher documented |
| 109 | personal data in module event payloads | **closed** (§5.1), with the residual gap 138 (the CDP path's own events) |
| 110 | a browser that has no view is never masked | **closed** (§5.2): the view-less rule, `BrowserMask`, the events, the selection, the action answers (D637), the CDP hand-offs; `web.evaluate`/`web.waitFlag` on a masked browser stay S111's residual; JxBrowser unverified (gap 41) |
| 111 | a named `on_prompt` value silently means "pack policy" | **closed** (§9.2): `fail` or `pack`, anything else refused; named policies left for later without a break |
| 112 | pre-flight runs once per scenario; the guide says once per suite | **closed** (§9.3): the scope, the cache, one helper, the prose corrected |
| 113 | hook ownership by code source | **closed** (§6.2-§6.4, §6.8, §6.10): `target.codeSource`, qualified `packages`, the reporting; the deliberate limit is D556's file-name rule |
| 114 | a per-catalogue class deny-list | **closed** (§6.3, §6.5-§6.9) but one residual (gap 140) |
| 115 | routed pointer into heavyweight containers | **closed** (§3.4) on the Linux image; the Windows/macOS peers unverified |
| 116 | `isShowing()` is not "on screen" | **closed** (§3.3); the custom-scroller case documented (gap 130) |
| 117 | positional sources and the allow-list start check | **closed** (§4.4) for the start check's path; the metadata primitive is gap 133 |
| 118 | an "any of" matcher, partial-object list membership | **closed** (§8.4) as asked; quantifiers over scalar lists are gap 145 |
| 119 | `SqlAllowList.procedures` cannot be used | **closed by retirement** (§4.5): every `CALL`/`EXEC` refused with a reason, the field reserved and required empty, the catalogue silent; admission deferred with 13 |
| 120 | a list-valued `await` match | **closed** (§8.9): `{has: scalar}` through `PayloadMatch` |
| 121 | Playwright cannot be pinned below 1.60 | **closed as a gate** (§5.3): field-presence detection, the opt-in, the leak fixed, the floor stated; an older line unverified |
| 122 | AWT `Button` and `Checkbox` in the click ladder | **closed** (§3.5) for `Button`/`Checkbox`; other AWT controls are gap 129 |
| 123, 124, 125 | custom-painted text; a `DISPATCH` click at a region; a posted-key rung | **deferred** (§1): low; the seams named |

**New, numbered 126-150** (the TODO table's five columns; the tag names the section that opened the gap):

| # | Gap | Why it matters | Proposed shape | Phase |
|---|---|---|---|---|
| 126 | `web.*` input into a browser component an `InputVeto` names | the guide advises vetoing a window, and a window may hold a browser: `swing.pointer` into the canvas is then refused (§3.4 rule 2) while `web.pointer`, `web.dom.click`/`fill` and `web.dispatchEvent` act on the page inside it for any caller `CallPolicy` admits (a pack's verb; a person; a model or client a person opted in to the raw `web_*` tools); the recorder is not the route (D507 refuses the hit first) | the cheap half: `ComponentState` gains `vetoed` beside `masked`, filled by the same chain walk `web.pointer` already makes for the modal check (`ComponentReads.stateOf`) through `InputVeto.refuse(c, "web.pointer")` while an `InputVeto` is registered, `web.pointer` refusing `INPUT_REJECTED {reason: "vetoed", …}` on the agent path, `force` never skipping it; the rest (`web.dom.*`, `web.dispatchEvent`, which read no component, and the CDP path, handed to the agent while vetoes are registered through a `web.browsers.vetoesRegistered` field) is a new EDT read on three routes and a hand-off: the owner's scope decision (Q3) | later; **medium** (EA) |
| 127 | An option-configured veto | a layer without an agent module cannot veto | agent option `inputVeto=<id1,id2,…>` naming component ids, built in beside the SPI | later; low (EA) |
| 128 | Key bindings, mnemonics and accelerators | a key dispatched to one component may fire another's action | follow `WHEN_IN_FOCUSED_WINDOW` bindings and the menu bar's accelerators for the key before delivery; until then "veto the window" | later; low (EA) |
| 129 | AWT `List`, `Choice`, `TextField`, `Scrollbar`, `MenuItem` | no semantic rung for them | `Clicks.semantic` cases; `swing.type SET` on a `TextComponent` | later; low (EA) |
| 130 | A custom scroller counts its scrolled-out children as collapsed | an application that moves children without a `JViewport` | a layer hint (an SPI or a class list) naming viewport-like containers | later; low (EA) |
| 131 | MCP `data_query` always calls the agent's `data.query` while the start check reads through `DataChannels`, which prefers a `jdbc:` source (`ToolCalls.java:250-257`; `SkillroyMcpServer.java:444`) | a source served only over JDBC passes the start check and is then not served by `data_query` | route `data_query` through `DataChannels` with the same policy, or have the start check use the agent channel only | later (small) (ET) |
| 132 | no general cancellation signal for a primitive whose caller gave up (`lending.input`'s ack wait, `web.waitFlag`) | an abandoned call keeps its thread until its own wait ends; only `data.query` can now stop early | `Invocation.onCancel(Runnable)` fired by the dispatcher on `BrokerTimeout` and on close; `QueryControl` re-based on it | later (ET) |
| 133 | a data channel exposes no metadata, so D100 cannot check key nullability and a positional source needs `columns()` | the check trusts the list for nullability (P5 §5.2) | `data.columns {source, table}` -> `{columns:[{name, type, nullable}]}` with `DataChannel.columns`, JDBC `DatabaseMetaData.getColumns` | later (ET) |
| 134 | provider-backed lanes (application threads with their thread-locals) | a module whose reads need the application's thread context cannot use a base lane | a `backing: PROVIDER` declaration with `ThreadPolicyProvider.callOnLane` | later (ET) |
| 135 | `DataSourceProvider.open()` has no timeout and no cancel (a pool wait can block the lane) | the hand-off bounds the caller, the lane thread stays held | `open(QueryControl)` default overload | later (small) (ET) |
| 136 | `APP` is one unbounded lane (the sample's provider queue is unbounded) | a burst of `APP` calls queues without a bound the base sees | at most n `APP` calls in flight -> `BUSY`; `agent.info.threads.app` | later, Q13 (ET) |
| 137 | the controller's `JdbcDataChannel` does not cancel a statement on its timeout (it relies on `setQueryTimeout` and the per-source lock) | a driver that ignores `setQueryTimeout` keeps the source locked | adopt the `ReadOnlyJdbc` statement sink with a watchdog | later (small) (ET) |
| 138 | The CDP path's own events (`PathEvents`: `web.tap`, `web.ready`, `web.load` the path saw) never pass the agent's ring | a pack's `MaskPolicy` on `event:web.tap` masks `detail` on the agent path and not on the CDP path (a masked browser's are withheld on both, §5.2 (iii)) | `WebRunContext` carries the pack's `rowMasks()`; `PathEvents.publish` applies them as `event:<topic>` with §5.1's walk and budget | next phase; low (the detail is page content a pack chose to tap; the paths are otherwise at parity) (EW) |
| 139 | Events a module publishes before another module's `MaskPolicy` is registered are stored unmasked by that policy: the core's start-up events and, on an attach to a running application, what the base modules publish between their own install and the domain module's (install order) | a policy registered after a publisher's first events never sees them; §5.1, §11 rule 3 and the threat model promise the pass from the policy's registration on, and the key rule (D540) covers the window; the events stay readable while the ring holds them | none needed while the rule stands (modules install before `target.ready`, and application events flow after their listeners attach); a layer that needs it registers its policy in the module that publishes; to close the attach window: one pass over the ring at a registration, outside the ring lock, a slot replaced only when it still holds the same event (Q64) | later; low (EW) |
| 140 | A denied subtype loaded after a `^`/`INTERFACE` Byteman submit receives the trigger | the deny of gap 114 holds at submit time; a class the application loads later under a denied name is instrumented by Byteman's transformer, which the module does not sit behind (P6 §7.3's residual of the same kind) | a guard transformer registered behind Byteman's that answers the ahead recorder's bytes for a denied name; a per-load cost (BS3 fact 20) to measure first | later; **low** (EH) |
| 141 | A deny cannot be loosened without a target restart | D564's add-only rule is the safe direction, and an author who removes a pattern sees it still applied until the target restarts (`hook.health.deny`) | a confirmed `hook.deny {clear: true}` for a person (never a client), or a lift on `replace: true` with the owner's consent (Q28) | if asked; **low** (EH) |
| 142 | Conditional planned call (`when`) | a flow that cannot be one composite (two packs' primitives, a base verb in the branch) still has no conditional step | `when: {topics, match}` on a call, evaluated against the event that ended the previous call's wait; a skipped call recorded; plan key gated by version | later; **low** (the rule of 7.5 covers the second domain) (ES) |
| 143 | Session step modifiers | a client scenario through `skillroy_scenario_run` accepts warnings (its YAML is loaded), a session verb cannot | `accept` on the session's `execute(verb, args, modifiers, …)` behind the client origin, journaled and exported | later; **low** (ES) |
| 144 | A composite's branch as a fact | a scenario asserts a branch through application state; a `composite` event or fact root would let it assert the branch itself | an informational event `<domain>.composite {primitive, branch, answered: [{question, by}]}` (allowed now, 7.5; the base does not read it) or a base fact root over the last call's result | later; **low** (ES) |
| 145 | Quantifiers over list elements (`some: matcher`, `every: matcher`) | "no slip line names the item" on a list of strings (`slip.last.lines`) needs `containsWhere`'s idea without fields; today `slip.last.text: {not: {contains}}` stands in | two matchers in `Matchers`, the shared budget, the same unresolved rule as `containsWhere` | later; low (EX) |
| 146 | The `events` root on a non-agent web path | a CDP path's own events (`web.*` served by the path) have their own sequence and are not in the root; a scenario on that path cannot count them | merge with a `path` marker and the path's own loss rule, or a second root `webEvents` | later; low (EX) |
| 147 | Projection in expectation paths (`lines[*code=X].qty` as a list of values) | `containsWhere` and `size` cover membership and counts; "every qty of these lines is 2" needs `every` (gap 145) over a projection | a `.*field` or `[*].field` segment once `.field` on a list is disambiguated | later; low (EX) |
| 148 | `re-resolve` and `caps` do no step 0b | a scenario whose `requires:` names a `hook.<id>` that exists only after the install reads "missing" in `re-resolve`'s precondition check (as in 0.1.0); `caps` shows the pre-install matrix | `re-resolve` runs `HookInstaller` under a `--hooks` switch before its check, as a run does; or the guide says to run `skillroy run --hooks on` once before the job | later; low (EP) |
| 149 | `caps --pack` evaluates pack capabilities without credentials | a source served over JDBC only shows `UNAVAILABLE` there while a run reads it `OK` | `--env` and `--credentials` on `caps` through `BindArgs` | later; low (EP) |
| 150 | a `SUITE` answer has no time-to-live | a very long suite keeps a stale `OK`; the failure then shows at bind or a step | `SuiteCache.create(int, Duration maxAge)` | later; low (EP) |

**Defects found in passing and fixed by this contract** (each in its section; none left open): the pointer's component
form delivered to whatever lay at a point its component does not show, and `swing.hitTest`'s `deepest` descended
heavyweights while `mouseTarget` did not (§3.3, §3.4; the bare `isShowing()` check duplicated in three target forms is
one helper now); the seven of §4.8 (a positional provider's zero-row result without columns and `columnTypes` never
contributing to the width; guide §6.4's `data.query` "on `APP`"; the S30 gate keyed by primitive name making one stuck
source block every source; `CoreThreadBroker.post`'s silent `default:` worker branch; the `CALL` the contracts promised
and no channel ran; `DataModule.VERSION`'s stale literal); the Playwright driver and thread leak on a `LinkageError`,
the selection error data listing a masked browser's URL and title (an S111 residual), the CDP path serving a page with
no agent ref without any mask check, and a view-less JCEF browser's `web.bitmap` answering `CAPABILITY_UNAVAILABLE` where
the mask should answer first (§5); a prompt raised and answered during a marked call never covered by `handledUpTo`
(covered for composites by §7.5's reconciliation), P1 §9.2's sentence on where `input.acked` is published (true again),
`LendingModule`'s Javadoc bump rule restated (§7); `waitFor`'s `match` never validated, `[*…]` parsed as a single
filter on the field `*…` and silently never matching, step and call keys unchecked by the plan reader, `size` with a
nested matcher built since Phase 1 but documented as `{size: n}` only, nine copies of the payload-match rule (§8);
`ReResolveCommand`'s `requires` check against the agent-only matrix (§9.1.5). Observed and left as stated:
`HookInstaller` reads `hook.health`'s `catalogue` leniently, which is what lets the `alreadyInstalled` echo check sit
beside it without a wire change (§6.9).

---

## 17. Decisions taken in this contract (D501-D642)

D501-D623 are the seven fragments' decisions in section order (the tag and the fragment's own number in parentheses:
EA D501-D518, ET D519-D538, EW D539-D555, EH D556-D575, ES D576-D593, EX D594-D608, EP D609-D623); D624-D635 are the
editor's, taken while harmonising; D636-D642 are the reviser's, taken on 2026-10-19 after the contract review (the
finding in parentheses; rows amended by the review carry "(revised: <finding>)"). The build's decisions follow from
D643, numbered by the orchestrator at integration (B39), in a table of their own.

| # | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D501 | One SPI `InputVeto` (`String refuse(Component, String route)`, route constants, `ANY`), ancestor rule to the window, fail closed, memoised per call (§3.2) (EA 1) | a three-valued `ActionFirer`; `LocatorResolver` hiding; a pack-side deny-list; an option-configured veto; a `STUB` hook; reusing `masked` | only an agent-side check on every route covers refs, coordinates, raw calls and the routed pointer's real target; `CaptureMask` is the fail-closed precedent; the ancestor rule lets a layer veto a panel in one answer |
| D502 | Asked on every route after `requireClickable`, before the modal test; default type/key targets and ENTER's default button included; never skipped by `force` (EA 2) | explicit targets only; `force` skips | a focused vetoed key takes SPACE/ENTER like a click; ENTER fires the default button by Swing's own binding; `force` is a person's way past a dialog, never past a safety rule |
| D503 | Every posted task re-asks the veto before it acts; a refusal there is logged and counted (`vetoWithheld` in the Swing module's `AgentModule.info()`, a new additive default shown in `agent.info.modules[]`), not reported; the residual is stated where an author reads it: such a step passes with `fired: true` and only its `sync` or an expectation on the effect fails it (EA 3; revised: C2-F7) | report it in the result; no re-ask | the call answered "posted" and cannot be amended; a state-dependent veto must be atomic with the action; no run host reads `agent.info` after a step, so the counter is an operator's evidence (`skillroy info`) and the statement is what reaches the author |
| D504 | `reason: "vetoed"` token with the text in `veto`, plus `ref`, `vetoed`, `route`, `hit?` (EA 4) | the row's literal `"vetoed: <text>"` | P7 §3.9's reasons are tokens a consumer switches on; the text has its own key |
| D505 | The pointer checks per event the component AWT delivers to (the pressed one while a press is down); a refusal is `VETOED` with the press balanced; not skipped by `force` (EA 5) | pre-check only; check the component under every drag point | a drag's events go to the pressed component; the pre-check cannot see a veto that depends on state changed by the press |
| D506 | Readers flag with route `ANY`: Node `vetoed` only when true, hit-test `vetoed` always, no capture mark, snapshots not pruned (EA 6) | prune vetoed from snapshots; flag on every node | the pinned node shape and every committed golden stay; a model must see the component flagged, not absent |
| D507 | The recorder and V2's raw pointer refuse a vetoed hit with `Recorder.VETOED`; `UiSnapshotText` flags `vetoed` (EA 7) | rely on the agent's refusal alone | nothing vetoed should become a step, a journal line or an export (D358's reason) |
| D508 | No capability id, option, topic, code or plan change for Phase 8's Swing input (EA 8) | an id `swing.veto`; a 0.3 protocol | the goldens and matrix hashes stay; `/schema` shows the new keys (D299) |
| D509 | `web.*` input into a browser component a veto names is not refused (gap 126, medium); the contract, the guide, `SECURITY.md` and the threat model say that a window veto does not cover page input into a browser inside it (EA 9; revised: C2-F3) | check the browser component's chain in `web.pointer` (the cheap half, Q3) | the row is Swing; `web.dom.*`, `web.dispatchEvent` and the CDP path read no component, and `web.pointer`'s existing component read (`ComponentReads.stateOf`, the modal check) is the seam if the owner takes it; EW owns the web side; the earlier reason "the web routes never touch an AWT component" was false for `web.pointer` |
| D510 | "On screen" = showing and a non-empty effective area with the viewport exemption; `INVALID_PARAMS collapsed` with `by`; hereditary; locators and snapshots skip collapsed unless `includeHidden`; `collapsed` flag only when true (EA 10) | the strict (viewport-clipped) area everywhere; a flag only; a layer SPI | the strict rule breaks ACTION clicks and locators on scrolled-out components that work today; a flag alone does not fix AMBIGUOUS; the geometry is generic |
| D511 | A collapsed in-window dialog root blocks nothing and is not listed; a dropped root is logged once per root class, and the `DialogLocator` Javadoc says so where the layer author reads it (EA 11; revised: C2-F12) | keep blocking | a root with no area is not a dialog a person sees; consistent with the rule; the loosening of a host-safety rule must be visible to the author whose locator reports such a root |
| D512 | The pointer's component form refuses a computed point outside the component's strict visible rectangle, `INPUT_REJECTED not visible` (EA 12) | leave as today (the map's "optional") | today's events land on whatever lies there: a recorded step on a scrolled-out row clicks another row; honesty before delivery, cheap to check |
| D513 | The routed source is the innermost unclaimed heavyweight on the path at the point, point converted, drag keeps the press's source; a claimed (WEB) surface keeps the window as source (EA 13) | dispatch to the deepest; Robot; post with the heavyweight as source; refuse claimed surfaces | every heavyweight container has its own lightweight dispatcher; the exception keeps page input on `web.pointer`'s checks |
| D514 | `lastRouted` per source with one `MOUSE_EXITED` on a source change; `received` counts a heavyweight leaf or listening source; the balance into a leaf adds an exit; `source` in the hit-test and the pointer result (EA 14) | no exit; `received` unchanged | approximates the native leave; the X peers disarm on exit, so a cancelled press fires nothing |
| D515 | `Clicks.semantic`: AWT `Button` → `ActionEvent`, `Checkbox` → state and `ItemEvent` with the X peer's group semantics, on ACTION and COMPONENT after the firers; DISPATCH unchanged; `DialogReader` admits AWT `Button` (EA 15) | post to the system queue; each layer's firer; DISPATCH semantic too | one deterministic event per click on every toolkit; inside the task's checks; the rung's definition kept |
| D516 | The sample's tools window behind `lending.tools=true`, its own `Mode` and nested class, with `robot=true` on that leg; the veto by the panel's name (EA 16) | a `LendingPlugin`; the shared legs; no Robot | a launch property is nameable by a negative scenario; the shared legs assume one frame; the Robot refusal is then proven e2e without a golden |
| D517 | The order clickable → veto → modal on every route; the veto on `swing.type`/`swing.key` default targets and on `swing.dismiss` although those run no modal test (EA 17) | veto first | an argument error names what cannot take input at all; a veto outlives a dialog |
| D518 | Additive result keys placed after `masked` (`vetoed`) and after `surface` (`source`); `hitSubset` gains `vetoed` (EA 18) | – | lenient readers; the recorder reads by key |
| D519 | `APP_QUIESCENT` backed by a base-owned `QuiescentPoint`; admission by declared budget and the caller's remaining time, never pre-empted, overruns measured (ET 1) | a provider-owned `callAtQuiescentPoint` (the row's shape); a separate `QuiescentPointProvider` SPI; a lane with a budget (gap 9's vocabulary) | every module gets one semantics; the core can see, bound and report the queue; the exemption needs one provider; the lane and the point differ in admission, exemption and interruption |
| D520 | the forbidden-thread exemption is granted only to tasks the core submits through `QuiescentPoint`; nested `call` still `FORBIDDEN_THREAD`; never interrupted (ET 2) | a general "may run on a forbidden thread" flag | the exemption must be as narrow as the mechanism; the sample's engine dies on interrupt |
| D521 | a 2x overrun penalises the primitive until `reprobe` (ET 3) | log and count only | restrictive default: a module that cannot keep its budget is found at the first run |
| D522 | no `WORKER` fall-back for `APP_QUIESCENT` (reads included) (ET 4) | A19's read fall-back | the read is correct only at the point |
| D523 | no core capability id; a primitive names its own (ET 5) | `app.quiescent` | five goldens and every core capability test would move for an id nothing gates |
| D524 | enum order `…, APP, APP_QUIESCENT, LANE`; `post` refuses both and names every constant (ET 6) | `LANE` before `APP_QUIESCENT` | one decision for both packages' tests; `post`'s silent worker branch is a defect |
| D525 | base-owned agent lanes (ET 7) | provider-backed lanes; a per-provider thread policy for `data.query`; the per-source gate alone | the base bounds and reports its own threads; the policy moves reads onto `APP`; the gate alone leaves reads unbounded and unserialised |
| D526 | `ThreadPolicy.LANE` for a module's primitives now (ET 8) | `DataSourceProvider.lane()` only | a module's slow non-SQL reads need the same lane; the cost is one builder method and a dispatch case |
| D527 | `data.query` keeps `WORKER`; the read runs on the lane or a nested worker task; the module times out 250 ms before the dispatcher; a per-source gate (ET 9) | `data.query` on `CALLER` (the caller pool as the waiter); the read inline with a timer thread | `/schema` and `DataModuleTest` unchanged; the worker pool is sized for nested calls; the cancel must fire from another thread anyway |
| D528 | `APP` not bounded in Phase 8 (ET 10) | at most 8 `APP` calls in flight | a new `BUSY` path for every existing `APP` primitive for a case no target has shown; Q13 |
| D529 | a scoped `QueryControl` through a five-argument default `query` (ET 11) | `Invocation.onCancel` for every primitive; `cancel(id)` on the provider; interrupt only; `enforcesTimeout` only | Phase 8 needs the data path; the general form is gap 132; an id adds state and races; interrupts do not reach blocking JDBC |
| D530 | the JDBC form cancels through a `ReadOnlyJdbc` statement sink; seconds rounding unchanged (ET 12) | a watchdog thread in `JdbcExecutor`; rounding up | the sink cancels only an open statement; the cancel covers the rounding's remainder |
| D531 | labels from the statement inside the target, provider names win; the width from types, returned rows and the derived list (ET 13) | an explicit `columns:[…]` parameter; controller-side relabelling from `SqlGuard`'s parse; a helper only; a `synthetic` flag | parity with `getColumnLabel`; masks in the target see real names; no fifth parameter; the zero-row defect is fixed in the same rule |
| D532 | `columns(String table)` default null, asked for the star form only; the check unchanged but for a hint (ET 14) | a count fallback; a `data.columns` primitive (gap 133); a pack-declared full column order | the list has no total order; D100 trusts the source, not the pack; the primitive is a later phase's |
| D533 | 119 retired: the guard refuses every `CALL`, `procedures` reserved and required empty where used, the catalogue silent; admission is gap 13's (ET 15) | admit `CALL` on a channel that declares it (two keys, a structured `data.call`, a `ReadOnlyJdbc.call` envelope) | nothing ever ran it; a routine can write through a read-only account; a lone `CALL` serves none of the second domain's cases; a security pass would be owed |
| D534 | `DataModule.VERSION` literal `0.2.0`, bumped in step R (ET 16) | `${project.version}` as the other modules | the one bump of §2.4; the literal is the contract's own number |
| D535 | the desk's idle tick (default 100 ms), a dropped input keeps today's timer reset; `IdleView` is the plant; the 1 s lock proof lives in the module's unit test (ET 17) | `inputRequested` as the only point; a new `LendingTarget.Mode` for the lock proof | an idle desk would always be `NOT_READY`; a Mode per proof is heavy for a property that changes the shared desk's behaviour |
| D536 | `lending-report` always registered, lane `lending.reads` (1 thread, queue 2); the pack changes a mask lambda only (ET 18) | behind a launch property; a second allow-list in the pack | no new exposure; every end-to-end launch sees it without a flag; a second list would re-record scenario D |
| D537 | the tokenizer moves to `SqlTokens` as a pure refactor, landed first (ET 19) | a second tokenizer in `SelectLabels` | one lexical truth for a security component; `StatementGuardTest` is the proof |
| D538 | map C's O1 (MCP `data_query` to the agent while the start check prefers JDBC) is recorded as gap 131, not fixed (ET 20) | route `data_query` through `DataChannels` | MCP's route and policy are EP's and the editor's; a fix changes which channel serves a client |
| D539 | One pass at `RingEventBus.publish`, after the freeze and before the bound, outside the ring lock; the bus takes an `EventMasks` built over the SPI registry and the redactor, constructed before it (EW 1; revised: C2-F11) | a wrapper on `ModuleContext.events()`; masking at read time (five sites); a new `EventMask` SPI; controller-only `DomainPack.eventMasks()`; rule plus kit only | one choke point covers the ring, every subscription, SSE, `evt.query`, `sync.await`, the call's `events` and so every controller output and client; `inv.context()` is the core context (`Dispatcher.java:302`), so a wrapper is bypassed; a read-time mask leaves unmasked values in the ring and makes `await` ambiguous; a second SPI is not restrictive; the row's own complaint is that nothing checks |
| D540 | The key rule runs on every event, whether or not a policy is registered; A34's by-value rule stays out (EW 2) | the key rule only while a policy is registered; by-value masking in events | it is the hook encoder's rule on the one route that lacked it, cheap and by key only; tying it to a policy's presence would make a cheap protection depend on an unrelated registration; by value was removed by the review of 2026-10-03 (A34) for turning short values into `<redacted>` for every reader |
| D541 | 10,000 scalars per event, the rest `<redacted>` and `truncated: true`; mask before the character bound (EW 3) | bound first, then mask (policies see cut strings); no scalar budget (the character bound alone) | a policy must see whole values to recognise one; a 64 Mi character bound admits millions of short scalars, so the publishing thread needs a count bound of its own; `truncated` is the key the bound already uses |
| D542 | The thread rule of `MaskPolicy` is amended, not a second SPI (EW 4) | an `EventMask` with its own rule | the P6 precedent amended it for hooks; one SPI for "what masks a value" |
| D543 | `agent.info.events` counters, `slowPasses` and `maxPassMs` among them (EW 5; revised: C2-F11) | a new primitive; nothing | a reader of the matrix sees whether any policy is registered, whether one failed and whether one slows the host's thread; nothing on the wire of `Event` |
| D544 | `OutputScan` in `skillroy-junit` with the directory form and the per-file form the ten callers use, `SecretScan` deleted; `Scope.EVENTS` (EW 6; revised: C3-F4) | the scenario module's test-jar; keep the sample's copy; the directory form alone | the glue is the layer-facing module and the sample already depends on it; a test-jar is awkward for a layer's tests; a whole-run scan would find the status line's snapshot in a failing run; the callers walk their own directories and loop over `List<String>`, so the directory form alone would mean ten rewrites |
| D545 | A view-less browser is masked while any `CaptureMask` or `BrowserMask` is registered (EW 7) | fail closed always; `WebBrowser.viewComponent()` so a `CaptureMask` can name it | always-closed changes every web run without masks (the web host's item 2) and contradicts S111's "only while a mask is registered"; JxBrowser has no component at all for a browser found through an engine, and `CaptureMask.masks(Component)` cannot name a null |
| D546 | `BrowserMask {Boolean masks(String tag, Object vendorObject, boolean hasView)}` in `spi.web` (EW 8) | `default Boolean masksBrowser(tag, vendorObject)` on `CaptureMask`; V1 alone | one interface with two thread rules (EDT and worker) and a web concept in an AWT-typed SPI; V1 alone gives no way to release a page a scenario must read or to mask by tag |
| D547 | The flag is computed at rebuild (one EDT read for all components, no read for view-less ones) and refreshed per call; a FALSE never overrides a `CaptureMask` (EW 9) | compute only per call (events would need an EDT read on the adapter's thread); FALSE overrides everything | the events of §5.2 (i) need a flag that costs no EDT read on the adapter's thread; a release that could cancel a component mask would be the unsafe side |
| D548 | `web.browsers.masksRegistered`, absent counts as true on the CDP path (EW 10) | a capability id; a new primitive | an answer field is read where the path already reads the listing; a capability costs five goldens; fail closed for an older agent is §2.1's rule |
| D549 | A masked browser's events withhold `url`, `text`, `source`, `error` and `detail`, keep `name` and `signal` (EW 11) | let `detail` pass gap 109's policies; withhold `name` too | "no text, no tree" (S111) admits no page data in a payload; `name` and `signal` are what a step waits on, and a pattern name is the pack's, not the page's content |
| D550 | `by: {url\|title}` never match a masked entry; summaries withhold URL and title (EW 12) | match but withhold (the match itself is an oracle) | a regex on withheld text reveals it one bit at a time |
| D551 | The CDP path hands unmapped-page reads, `by: {url\|title}` selections and masked pages' own events to the agent or withholds them while `masksRegistered` (EW 13) | serve unmapped pages as today; apply `rowMasks()` to `PathEvents` | an unmapped page with masks registered is exactly the view-less case the agent now judges; `rowMasks()` on the path's events is a second masking route in Phase 8 for a path parity nobody has asked for (gap 138) |
| D552 | `ConnectSupport` by field presence, once per JVM, no reflective invocation (EW 14) | `Method.invoke`; a version-string gate; a stated minimum only; compile against an older line | field presence is the real fact and a shaded jar may carry no manifest version; the design already promised the gate; nothing offline resolves an older line |
| D553 | Without `noDefaults` the CDP path is unused unless the **operator** opts in with `skillroy.cdp.allowDefaultOverrides` (the one departure from the shared defaults: they said "unless the pack opts in") (EW 15) | a `web:` scenario key (`Plan` v7 top-level `web`, `ScenarioLoader`, `ScenarioSchema`, `WebSettings`: four shared files across EX/EP for a line nobody can run); a `DomainPack` default method; accept and log by default (design §3.8's wording) | the overrides change the application's own browser context, which is an operator's risk to accept, as `skillroy.robot` is; the restrictive default is §1's; a pack key would need plan gating and three other packages' files; the veto form is Q21 |
| D554 | `LinkageError` caught in `connect`: the pending Playwright closed, the thread shut down, `PROTOCOL` (EW 16) | leave `WebRun`'s catch as the only handling | a driver process and a thread leaked per attempt |
| D555 | The floor stated as "1.61 full, 1.60 untraced, 1.58-1.59 opt-in, below refused; only 1.63.0 run" (EW 17) | "1.58 (unverified)"; "1.60 or later" | say only what the gate guarantees and what was run |
| D556 | `CodeSources` in `agent-api` reads the location with the URL's field getters only and names it by the outer file name plus inner entry paths, directories never, control characters replaced after decoding, cut at 200; `(none)` and `(unreadable)` as sentinels (§6.2) (EH 1; revised: C2-F6) | the full location; `toExternalForm`/`toURI`; resolving the jar's manifest | a custom protocol's handler is application code and `equals`/`hashCode` may hit the network; a path may hold a user name (D154's precedent); the manifest would mean I/O on a worker for every probe |
| D557 | `target.codeSource` is a regex on the whole name, ≤ 200, compiled in `violation()` (§6.3) (EH 2) | a glob; a plain file name | the row asks for a regex; version suffixes and classifiers are regex-shaped; an anchored match keeps `lending-app.*` honest |
| D558 | A filter, like `loader`: a non-matching copy is left alone; inheritance from the longest qualified `packages` name, baked into the spec at read or build (§6.4, §6.8) (EH 3) | requirement semantics (every loaded copy must match); a catalogue-level default `codeSource` | a plugin loader's second copy from another jar is legitimate and must not fail the hook; one default cannot say "package A from jar X only"; baking keeps spec, entry hash and catalogue hash in one place |
| D559 | Report code sources in the probe, `touched`, a new `hook.health.classes` and the CLI; never the directory (§6.4, §6.10) (EH 4) | code sources in `hook.probe` only | `skillroy hooks status` reads `hook.health` only (D180) |
| D560 | An old agent refuses a qualified spec (`HookTarget` stays strict); the installer appends an upgrade hint (§6.3, §6.9) (EH 5) | a lenient `HookTarget` | leniency would let an old agent hook every copy while the catalogue said one jar: the loosening §2.1 forbids |
| D561 | The deny grammar is `ClassPattern` (exact with nested, `*`, `**`, a last-segment glob), 200 characters, 64 per source, in `skillroy-protocol` (§6.3) (EH 6) | regex patterns; package prefixes only | a deny list is reviewed by eye and matched on every probe and rule; no backtracking; a package and a single class are both needed |
| D562 | `ClassDeny.refuse(String className)` in `agent-api`, null or a reason, a throw refuses, name-only, workers only (§6.5) (EH 7) | a loader argument; a boolean | parallel to `InputVeto.refuse`; the Byteman check runs before any class is found; a reason is evidence |
| D563 | Three sources (option, catalogue, modules), the option first then registration order, first refusal wins, consulted at every probe, install, check and submit; item 2b before "loaded?" (§6.4, §6.5) (EH 8) | the catalogue only; a transform-time check | a Byteman-only target has no catalogue; a module knows its application; only ledger classes are transformed, so a denied class never reaches a transformer |
| D564 | The held catalogue deny is add-only for the engine instance, merged before the conflict check, bounded at 256; loosening needs a restart (§6.6) (EH 9) | replace on `replace: true`; clear on `hook.remove {}` | an engine must never be looser than an earlier reviewed statement it accepted; the bound keeps the union finite |
| D565 | The installer requires the echo (`catalogue.deny` in the install answer, `deny.catalogue` in health on the `alreadyInstalled` path) and fails closed without it (§6.9) (EH 10) | a capability id for the feature; a `/schema` check | an id costs five goldens for a gate; the echo proves the agent *applied* the list, which a schema cannot (§2.1, §2.4) |
| D566 | A deny the installer cannot deliver is recorded (`denyNotDelivered`, with `agentOption` read from `rule.list` when a `rule.*` capability exists) and the option `hooks.deny` is the remedy; no core primitive; a 0.1.0 agent's silent ignoring of the option is stated (§6.5, §6.9, §6.12) (EH 11; revised: C2-F10) | a core primitive `agent.deny` sent whatever the hooks mode | a hooks feature should not add a core primitive and a controller call; the option covers the Byteman-only target; the record makes the gap visible, and says whether the remedy is in force |
| D567 | A deny-only catalogue is sent as a zero-spec `hook.install` (§6.9) (EH 12) | treat it as "no catalogue" | its deny would otherwise never reach the target; D183 keeps it unrecorded as installed |
| D568 | A hook installed before its class was denied is flagged (`deniedNow`), not removed (§6.6) (EH 13) | removing it at the next install | a removal nobody asked for is a mutation; the flag and the record make it visible to a person |
| D569 | Byteman honours the deny in `ClassBound` before the count and the load, on matched subtypes too, and reports it in `rule.check`; the later-loaded-subtype residual is recorded (§6.7) (EH 14) | a guard transformer behind Byteman's | the guard costs every class load (BS3 fact 20) for a case P6 §7.3 already records as a residual |
| D570 | Overlay: deny is a union, package rules union by name with the overlay's qualifier for its own entries, base entries keep their baked value; an enabled denied base entry is an error unless disabled (§6.8) (EH 15) | an overlay lifting a base deny; re-baking base entries | an overlay narrows (P6 §11.2's spirit); re-baking would change base entries' hashes behind the base author's back |
| D571 | `deny` enters the catalogue hash only when non-empty; `descriptor()` carries it (§6.8) (EH 16) | always in the hash | every existing catalogue keeps its hash and its idempotent install |
| D572 | The deny is policy, not checksum protection; stated in the contract and the guide (§6.1) (EH 17) | promising the row's checksum rationale | retransformation changes neither the jar nor the resource bytes |
| D573 | The sample module registers `LendingClassDeny` behind `lending.hooks.deny` (§12.2) (EH 18) | no module source in the sample; a registration on every launch | CLAUDE.md asks that an SPI be exercised with the sample; §12's planting rule asks that it hide behind an option |
| D574 | 113 and 114 are one package (EH) with one catalogue bump to version 2 (EH 19) | two packages | the same files on both sides (`Probes`, `HE`, `HookOptions`, `HookCatalogue(s)`, `HooksCommand`, `lending.yaml`, `HookChecks`) |
| D575 | `HookEngine` gains only `default List<String> deny()`; the SPI install path carries no deny (§6.6) (EH 20) | a five-argument `install(…, deny)` default | a default that ignored a deny would be the one place a deny could be lost; a module's install has no catalogue to carry one |
| D576 | The warning channel is `RpcResult.warnings` filled through `Invocation.warn`, never a key inside a primitive's value (ES 1) | a reserved value key `warnings` (objects only; map D's recommendation); a fresh key `callWarnings` | a layer primitive that already returns `warnings` objects would start failing steps; the envelope cannot collide, the module cannot get the shape wrong, the dispatcher can redact it, the value stays the primitive's own; the cost is one optional wire field read leniently |
| D577 | An accepted warning leaves the verdict alone (`PASS`); an unaccepted one is `FAIL / ASSERTION` after all were recorded; cleanup warnings are logged only (ES 2) | accepted -> `DEGRADED`; `accept: [{code, as}]` | the author declared the outcome fine; `DEGRADED` means "not at the fidelity asked" (A29); the record and the result lists keep it visible |
| D578 | Codes are a closed base set plus pack codes the pack declares; an undeclared code is never acceptable; `INVALID_WARNING`/`TOO_MANY_WARNINGS` fail closed (ES 3) | free-form codes accepted by name | gap 111's lesson: an unknown name must be refused at load, not silently tolerated |
| D579 | `accept` and `minRung` are step modifiers; a verb may pre-accept on its own calls; sessions take no modifier and refuse raw calls that carry the three keys (ES 4) | a reserved verb argument; a scenario-level list; per-pack arguments; modifiers through the session API | works for every verb shape (`signOff`, `deposit` take no map); a session is interactive, its model or client sees the code; the export recompiles verbs |
| D580 | `INPUT_QUEUED` is derived by the executor only for an uncorrelated sync; every other code is the module's (ES 5) | the executor deriving every code from the fields | only the module knows what the application did, and it cannot be asked to see a secret sent value |
| D581 | `ui.strict` makes every ladder call's `minRung` its first rung; the step's own wins (ES 6) | `minRung` per step only | `strict` already means "no shortcut" (A29); one meaning across clicks and ladders |
| D582 | `inputId` is minted by the module (or echoed when supplied); `SyncPolicy.ackByInputId` is opt-in; a missing id under it is `ERROR / ENVIRONMENT` (ES 7) | opportunistic correlation; a controller-minted id; correlation by `Event.correlationId`; a `$result` reference root | a module that forgets the payload field would time every wait out silently under the opportunistic form; the flag is the restrictive one; the id must tie the application's own acknowledgement, which only the module holds |
| D583 | Late acknowledgements are published from the seam with a bounded pending table and the originating correlation id (ES 8) | no late acknowledgement (the first in-call ack is all) | gap 107's case is exactly the late one; the bound and the detach rule keep the host safe |
| D584 | The composite rule is stated and made checkable (`CompositeResult`, prompt reconciliation); the `when` form is gap 142 (ES 9) | `when` on a call now; the rule as guide text only | the second domain said the rule suffices if it names what to report; `when` is M-L across sessions, export and cleanup |
| D585 | The secret-echo check runs on every call with `secretParams`, equality for short secrets and substring from `minLengthInText` (ES 10) | composites only; substring for every length | the leak is the same wherever it comes from; 4-digit passwords occur inside barcodes |
| D586 | Nested prompts are expected only by listed `seq` inside the call's window (ES 11) | trusting any `answered` entry; extending `handledUpTo` to the call's `seq.after` for marked calls | a composite must not be able to hide a prompt it did not answer |
| D587 | `minRung` compiles into every ladder call of the step; forwarding it to the primitive is the verb's choice, with the echo required when forwarded (ES 12) | always forward (`_meta`); never forward | the plan shows the argument; a 0.1.0 module would ignore it, hence the echo (§2.1) |
| D588 | The ladder is call-level (`rungs`, `minRung`), the rung chosen agent-side, enforced by the executor, pre-checked at run start (ES 13) | a controller-side ladder of alternative calls resolved against the matrix | the primitive knows at the moment of acting whether the path is there; the controller form is L across every consumer of `Step.calls` and splits the second domain's existing ladder primitives per rung |
| D589 | A rung below the first is one `DEGRADED` fidelity record, de-duplicated with the primitive's own; below `minRung`, undeclared or missing is `FAIL / ASSERTION` (ES 14) | treating a lower rung as `FAIL` | a shortcut is lower fidelity, not a wrong outcome (A29) |
| D590 | `DeviceAdapter.rungs(action)` and `device.list` `rungs`; names `PHYSICAL`, `SEMANTIC` (ES 15) | a `DeviceRung` type; a `rung` parameter on `device.act` | a default method keeps every adapter compiling; the names are design §3.7's |
| D591 | The sample plants a scanner driver behind `lending.scannerDriver`, a `ScannerDevice`, two composites and three capability ids; the goldens are regenerated once in step R (ES 16) | a sample without a physical rung (convention only); an AWT simulator window | a headless physical path proves the ladder in every leg; a window needs a display and belongs to gap 122 |
| D592 | The fixed order of result checks (§7.7), the secret-echo check first (ES 17) | secret-echo last | nothing is evaluated on, or awaited for, a result that leaked a secret |
| D593 | Warnings are redacted by the dispatcher for secret parameters (inside the text from 8 characters, as whole tokens below; `SecretParams.redact`) **before** `CallWarning`'s bounds cut them, and masked by the run mask on every record and line (ES 18; revised: C2-S1) | run mask only; cut first, then redact | the agent's own logs and a raw caller's view are covered too; a cut before the redaction leaves a secret straddling the cut as a prefix no routine recognises (the console text's mask-then-cut precedent) |
| D594 | No new capability id, topic, error code, option or SPI in this section; the protocol stays 0.2 (EX 1) | a `0.3` minor to gate `{has}`; a capability id for the events root | every change is controller-side or an additive meaning of an existing parameter; a minor bump buys nothing (`/ping` compares majors) and an id would churn five goldens; a 0.1.0 agent fails closed (`SYNC_TIMEOUT`) |
| D595 | One plan version, 7, written always; the reader accepts 1-7 and gates each new item with a named message (EX 2) | the lowest version a plan needs (no churn for unchanged scenarios) | guide §4.4's rule and every earlier phase; `resolveHash` is untouched, so bindings survive; one version serves every Phase 8 package (regenerated once per cause, D626) |
| D596 | From 7 on, step and call objects are key-checked (an allow-list each, the §7 modifier keys reserved by name) (EX 3) | keep the leniency; check only EX's keys | an unknown key silently dropped is how a plan runs without its own rules; reserving §7's names lets EP add readers without touching the check |
| D597 | `[*…]` on a non-list is unresolved (so `{size: 0}` needs a real list); `exists` holds on an empty filtered list (EX 4) | resolve to an empty list on null; make the single filter return a list when several match | the first hides a missing fact behind a passing "none"; the second changes every committed path's meaning and `exists` |
| D598 | No projection (`lines[*…].qty` does not resolve); counting through the existing `size` (EX 5) | a `.count` pseudo-field or `[#]`; a `some`/`every` quantifier in Phase 8 | `.field` on a list means an index today; `.count` collides with real fields; `size` is built and tested; quantifiers are gap 145 |
| D599 | One regex budget per matcher evaluation threaded through nested matchers; the path's budget separate (EX 6) | a budget per `matches` as today | `containsWhere` over 10,000 entries would allow 10,000 s; S24 said "one per evaluation" |
| D600 | `containsWhere` refused in stored-row checks; `anyOf` allowed (EX 7) | let it fail at run | a cell is a scalar; refusing at compile is the restrictive and the clearer form |
| D601 | `[json]` as a path segment with a 1 Mi and depth-200 bound; no `json:` matcher (EX 8) | a `json: matcher` matcher | a segment can be followed by `.qty` and filters; a matcher cannot; both later if asked |
| D602 | The `events` root fails the read on any loss (first-page eviction, a gap while paging, ten full pages) (EX 9) | `truncated: true` and a lower bound as `hooks` does | `hooks` counts "at least"; a count that a `{size: 0}` or a delta rests on must be exact or absent |
| D603 | The root reads the agent's bus only; payloads as published (§5's masking at publish applies unchanged) (EX 10) | merge the CDP path's events with a `path` marker | two sequences that do not interleave; a session's `eventsSince` merges them for display, not for counting; gap 146 |
| D604 | `captureFacts` is a base verb, reserved; the step has no calls and no retry (EX 11) | `capture:` on `mark`; a `since: <step>` modifier on expectations; keep agent-published counts | `mark` mixes an agent call with a controller read; `since` needs a history the controller has not; the verb is explicit and reviewable; §2.2 reserves new base verb names |
| D605 | Under `expectationsOnMaskedValues` a masked captured value fails the step; sessions carry the verb (the session mask always) (EX 12) | drop or refuse the verb in sessions; capture masked text | a captured secret would become an operand or an argument; a dropped member is the defect map E found; a session export must replay it |
| D606 | `{has: scalar}` only, text equality, named `has`; validated at compile (`waitFor`, pack `sync.await` calls), at read (7), and by the agent (`INVALID_PARAMS`) (EX 13) | controller-side evaluation of operator entries (intercept every `sync.await`); nested key paths; `contains` | the interception would loop every wait on the controller; a dotted key is a literal key today; `contains` promises the expectation matcher's numeric and substring rules |
| D607 | Negation is `{size: 0}` / a delta on the `events` root; no `expectNoEvent` verb (EX 14) | `expectNoEvent {topic, match, since: <mark>, within}` | a second mechanism with its own plan shape, mark bookkeeping and step timing; the expectation form answers both stated needs exactly |
| D608 | `actual` in `run.jsonl` cut at 200 list elements with `actualTruncated: true` (EX 15) | no cap; a character cap | a filtered `events` list or the root itself would write up to the ring's 64 Mi; a count keeps the record readable and deterministic |
| D609 | Pack capabilities are an SPI on `DomainPack` (`capabilities()` → `PackCapability`), evaluated by the host (EP 1) | a guide rule only ("agent-declared from an owner-only agent option"); `inference.` contributors not forced in replay; a fact-based guard `when:` | the fact lives in the controller's environment (credentials, settings), not the target's launch configuration; a module per fact changes the goldens and cannot read JDBC; D326/P7 Q15 reserved `inference.` for model-backed ids; a fact guard is a grammar change decided from target facts while steps run |
| D610 | Honoured in `replay`: the context offers no mode, settings or gateway, so the answer cannot depend on the mode; the ledger check guards (EP 2) | force `UNAVAILABLE("replay")` as contributors | the point of 106 is a CI guard that is `OK` where the environment has the fact |
| D611 | An evaluation failure is `UNAVAILABLE("failed: <exception class>")`, not a refusal; the exception's masked text goes to the log and the record's `error`, never the matrix (EP 3; revised: C2-F9) | D367's refusal; the exception's `toString` as the reason | an evaluation reads the environment; a hiccup must degrade one expectation, not error every scenario; a driver's message can quote a row value, and the reason reaches the `caps` root a model reads (S22's rule on the agent side) |
| D612 | Every declared id is evaluated in every host, after the contributors and before `requires`; reads may precede `requires` (EP 4) | only the ids the plan names | a plan-independent `capabilities.json` and `caps` root; cost bounded (64, `SUITE` scope) |
| D613 | Source `pack:<packId>`; the `capabilities` record gains `pack` (absent when none); no new record type (EP 5) | a `capability` record per id | the sample's pinned record sequences stay; the reason is in the archive anyway |
| D614 | Id grammar `<packId>.<name>`; the conflict rule is equality with an agent or contributed id; no reserved sub-segment (EP 6) | a reserved `<packId>.env.` segment | the module already declares `<packId>.*`; a reserved segment would be a convention the base cannot enforce either side |
| D615 | `on_prompt.unexpected` ∈ {`fail`, `pack`}, refused at load and in the plan reader, no version bump; named pack policies deferred (EP 7) | warn; named `promptPolicies()` now | authoring safety is the point; nobody asked for a second policy; an unknown name is already refused, so names can come later without a break |
| D616 | `CheckScope` on `PreflightCheck` (default `SCENARIO`); a host-owned `SuiteCache` keyed by pack, version, id, env and agent session; both answers kept; a cached failure fails every later scenario `ENVIRONMENT` with a suffix (EP 8) | the guide corrected only; hosts run `SUITE` checks up front; cached failures as `<skipped>`; a JVM-wide cache; a TTL | the second domain's slow checks; the glue obtains the connection lazily per test; a status-mapping change is B14 territory; the suite is the unit the design names |
| D617 | `AgentConnection.agentSession()` default null, no I/O; `AgentClient` answers its verified session; null never caches (EP 9) | one `/ping` per run when a `SUITE` item exists | no new call on the wire; fakes and wrappers that do not answer are safe by construction |
| D618 | One helper `Preflight.run` for the four loops; the JUnit glue creates a cache per stream even when options are given (EP 10) | four loops kept in step; a cache only on the `options == null` path | the scope rule cannot drift; the stream is the suite whoever built the options |
| D619 | `SessionOptions.suite` so the MCP server's `open` and its run executors share one cache (EP 11) | the server's cache for runs only | a `SUITE` check would otherwise run at open and again at the first run |
| D620 | The sample: `lending.database` becomes `SUITE`; `lending.returns.ceiling` is `SCENARIO`; scenario A requires it (EP 12) | a new `SUITE` check (changes the pinned record count); a new scenario | one record per run stays; scenario A really needs the setting (it resolves `SystemLimit`); the negative lives in a headless test |
| D621 | Gap 24: configuration is a capability (probed or pack-evaluated), in `requires:` or a guard; no `preconditions:` key (EP 13) | a pack-evaluated `preconditions:` key with arguments; a per-scenario pre-flight | duplicates `requires:` plus 106 across a new key, plan field, SPI and failure kind; couples pre-flight to scenarios |
| D622 | `re-resolve` merges contributors and evaluates pack capabilities before its `requires` check (EP 14) | record only | 106 makes the agent-only check wrong for every pack id; the fix is one hunk |
| D623 | `caps --pack` evaluates through the agent's channel with empty credentials and env `default` (EP 15) | `UNAVAILABLE("not evaluated")`; `--env`/`--credentials` on `caps` | useful where the agent serves the source; the CLI surface stays; gap 149 for JDBC-only sources |
| D624 | The protocol stays `0.2` and the echo rule of §2.1 is applied to the three safety-relevant parameters (`catalogue.deny`, `target.codeSource` through the strict `HookTarget`, `minRung`) and to `masksRegistered` (absent counts as true); `{has}` needs no echo (EE) | a `0.3` minor; a capability id per gate; a `/schema` check per parameter | the major is in every bindings fingerprint; an echo proves the agent *applied* the parameter, which a schema cannot; a 0.1.0 agent ignores unknown parameters and strictness is the fail-closed form where the wire type already has it |
| D625 | One plan version 7 landed first by §8's package; the §7 modifiers are reserved as **call** keys in the skeleton (a YAML step modifier compiles into the step's calls); `captureFacts` takes no modifier; the `on_prompt` value check is versionless (EE) | a step key per modifier; a second bump for §7 | one regeneration serves every package; a modifier is a property of a call in the plan; the reader rule of §9.2 gates nothing new |
| D626 | Regeneration once per cause: the version alone at EX's skeleton commit (the 19 plans, `toy-loan.plan.json` among them, the 17 bindings hashes, key-free, native), and step R with the list of §2.8 after every package landed: module 0.3.0, pack 0.7.0, `DataModule` 0.2.0, web host module 0.3.0, plans, bindings, goldens, recordings, exported files; the hook catalogue's version 2 is EH's own; the lists and counts are the packages' under D641 (EE; revised: C3-F1) | each package bumping and regenerating as it lands; one regeneration only (the skeleton then red until step R, `Plan.fromJson` reading every plan back as version 7) | P7's step 2b: the recordings, the goldens and the pack-version content are regenerated exactly once in the phase; a per-package bump would rewrite 36 committed files several times; a skeleton that cannot be reactor-green blocks every package that rebases on it |
| D627 | The `LoadModeChecks` slots and the nested classes of §13.2: EA 157 and `@Order(18) JavaAgentTools`; ET 158, 159, 171; EW 172; ES 173-175 and `@Order(19) JavaAgentScanner`; EX 176 and `@Order(20) JavaAgentSmallRing`; EP 177 and the glue's 160 (EE) | the fragments' overlapping 157-159 and two classes at 18 | the orders are shared state of two files every package touches; fixed here so that no rebase moves another package's check |
| D628 | `OutputScan` (§5.1) replaces the sample's `SecretScan` for every package's scans; EW's `skillroy-junit` step lands before any package that adds a scan call (EE; revised: C3-F4) | each package keeping `SecretScan` until EW lands | one scan helper; the ten callers change `SecretScan.` to `OutputScan.` once |
| D629 | File ownership where two fragments claimed one file: `PasswordMask.java` is EW's with ET's one line; `EngineEvents.java` is ES's with ET's `idle` and EW's `staff.signedOn` as own branches; `CallRunner.java`, the step-modifier grammar in `ScenarioLoader`/`PlanCompiler`/`ScenarioSchema` and `HeadlessDesk`'s `lending.*` shapes are ES's; `Plan.java` is EX's with ES's and EP's hunks; `DomainPack`'s defaults, `warningCodes()` included, land in EP's step-1 commit (EE) | the map's five-package split, under which EP held ES's files | the seven-package split made the map's EP two packages; the owner is the package whose section specifies the file's rule |
| D630 | The sample module makes 16 registrations across 14 SPI types (17 across 15 with `lending.hooks.deny`), declares four new agent ids regenerated into the five goldens once, and `ScenarioSchemaCommittedTest` counts 34: the final values the integrator resolves the packages' branch edits to under D641 (EE; revised: C3-F3) | one edit of each count in step R (a test red on three branches for the whole phase, with no statement that it is) | a pinned assertion that three packages change cannot wait for step R and stay green on any branch; the final values are fixed here so that the merge is mechanical |
| D631 | Gaps 13, 28, 29, 123, 124 and 125 are deferred with the reason and the seam of §1; 13 is also the vehicle for admitting a procedure call (EE) | taking 13 with 119; taking 123 for the sample's custom components | the second domain's primitives cover 13 today and admission owes a security pass; the lows have workarounds the second domain uses |
| D632 | The security delta review's scope is everything since `223c5fb`, eight lenses with a sceptic each, findings from S149, everything medium or above fixed, the owner's signature B40 (EE) | since the release commit (leaving the B32-B35 follow-ups unreviewed); a full review | the release checklist's item 2; the follow-ups are in no signed record |
| D633 | B38 holds the contract's answers, B39 the build's and the reviews' decisions, B40 the delta review's sign-off; the marks read "(Phase 8, specified 2026-10-19, not yet built; P8 §n)"; the build numbers from D643 and A100 (EE) | one batch for everything | P7's three-batch precedent lets the build proceed on provisional answers and the owner confirm each in turn |
| D634 | The `INPUT_REJECTED` and `INVALID_PARAMS` reasons of §3 and §7 are tokens in one registry (§10) beside P7 §3.9's, each with its `data` keys; the sample's `malformedFrame` is the sample's (EE) | the row's literal `"vetoed: <text>"`; free-form reasons | a consumer switches on a token; the text has its own key (D504) |
| D635 | The core bus lands in the order EW (publish-side masking) → ES (the `publish` overload delegating to the masked path) → EX (`payloadMatches`); the `events` root reads masked payloads by construction and never unmasks (EE) | parallel edits to `RingEventBus` | the masked payload is the one object every reader sees; the order makes it so from the first commit that reads it |
| D636 | The `v0.1.0` compatibility rules of §2.3 are a decision of their own: the agent API grows by default methods, new types and appended enum constants; controller records grow by components with every older constructor kept; `DomainPack` by defaults forwarded by `DelegatingDomainPack`; the protocol's Java 8 classes by a constructor each, their keys written only when set and read leniently (`HookTarget` strict); reserved names stated (C1-F10) | leaving the rules to `settled` §4 and map I §2.6 | §17 is where the owner sees every choice (B38), and §2.3 was cited as "(D624)", a decision about the protocol and the echo rule that says nothing of the API |
| D637 | Acting on a masked browser returns no page data (§5.2 (iv)): `web.pointer` carries no `hit`, `web.dom.click`/`web.dom.fill` and the `AMBIGUOUS` candidates carry `role` and `tag` only, on both paths; `redact: false` a person's argument there too (`CallPolicy.MASKED_WEB_READS`) (C2-F1) | refusing the three actions on a masked browser; stripping on the agent path only; emptying the keys instead of dropping them | S111 allows acting on a masked browser (a pack's own flow); the hit summary and the candidates are page text a model or an opted-in client could read one point or one selector at a time (`selector: "*"` returns twenty accessible names), which breaks "masked on every route" in the mask's own terms; the CDP path serves the three itself, so an agent-only fix leaves the route open; dropped keys cannot be mistaken for empty text |
| D638 | A `data.query` cancel batch runs on a nested worker task of its own, never the dispatching worker; the dispatching worker waits `reserve - 50 ms` for the actions and the read together and answers inside the 250 ms reserve; `cancelled` stays a boolean and `cancelPending: true` is a new key (C2-F2) | the actions on the dispatching worker (the fragment's form); a reserve of 300 ms; `cancelled` as `true \| false \| "pending"` | up to four actions of 2 s plus 500 ms cannot fit a 250 ms reserve, so a slow `cancel()` would let the dispatcher abandon the call by primitive name and bring back §4.8 defect 4, and a synchronous action on the only thread cannot be "abandoned after 2 s"; §2.1 allows new `data` keys, not a changed type on an existing one |
| D639 | `QuiescentPoint.await` refuses `FORBIDDEN_THREAD` at once on the application's thread (`appThread` set by `runOne`) and from inside a task of the point; `timeoutMs` is capped at 120,000; the exemption's text says "a nested call is refused", not "can never wait"; `EventBus.await` stays unchecked and forbidden to a quiescent task by rule (C2-F4) | a forbidden-thread and EDT check inside `RingEventBus.await`; no guard (the Javadoc alone) | the broker's caller check cannot see a direct `await` from the provider's own idle callback, and that wait always runs to its timeout and ends `NOT_READY`; the guard is a few lines in a helper the base owns (D519's reason); a check in the bus changes an API every module uses for a route that is not new in Phase 8 |
| D640 | `target.codeSource` is matched through `BoundedRegex.find` on the anchored pattern under `BUDGET_MS`; a `TooSlow` refuses the copy `UNAVAILABLE("codeSource regex too slow")`, all copies or none (C2-F5) | "a self-inflicted stall of one worker, recorded" | the base already has the clocked matcher for a caller's pattern on text the target supplies (S46), the probe runs under the engine's lock that every `hook.*` call waits on, and a name is the target's text even when the pattern is a reviewed catalogue's |
| D641 | The branch-truth rule: a pinned assertion that several packages change (the SPI type list and count, the committed and negative scenario lists, the JUnit glue's id list) is edited by each package on its own branch to its own branch's truth and resolved to the final values by the integrator at merge; a package that adds a committed scenario compiles its plan on its own branch; the windows that stay red until step R are named (EP's scenario A files; ES's recordings and exports; the image's golden check on ET's and ES's branches) and nothing else is expected red (C3-F1, C3-F3, C3-F5, C3-F7) | the counts edited once in step R; no package compiling its plans before step R; "red until R" left unstated | a test red on three branches for the whole phase blocks every rebase and hides real failures; `PlansUpToDateTest.planFor` throws on a scenario without a plan; an integrator resolving a handful of literals is cheaper than a phase without green branches |
| D642 | Two test rules for the new negatives and plans: `PlansUpToDateTest.theNegativeScenariosCompile` expects a `ScenarioException` naming the message for `accept-unknown-code.yaml` and `min-rung-without-ladder.yaml` and a plan for every other negative; `noScenarioOrPlanHoldsAPassword` allows a `"password"` key whose value is exactly a `$<slot>.password` reference to a credential-declaring slot and refuses every other `"password"` or `password:` (C3-S1, C3-S2) | the two compile-error negatives held in memory in `Phase8CompileTest` (the count then 32); renaming `signOnAny`'s parameter | the negatives belong beside the others and the count stays 34; `password` is the name the agent's secret-key rule masks, which §12.2 relies on, and the test's intent (no literal password) is kept |

---

## 18. Questions for the owner: answered provisionally 2026-10-19 (B38)

Answered by the orchestrator on 2026-10-19 in the manner of P7 §18, so that the build can start; written into the
sections named; recorded as **B38** in `../DECISIONS.md` and provisional until the owner answers (`B38 ok`, or vetoes in
the forms of the last column; any id not mentioned keeps what is recorded). Q1-Q57 are the seven fragments' questions in
section order (EA Q1-Q9, ET Q10-Q17, EW Q18-Q24, EH Q25-Q32, ES Q33-Q41, EX Q42-Q48, EP Q49-Q57); Q58-Q61 are the
editor's; Q62-Q64 are the reviser's, opened by the contract review of 2026-10-19 and answered provisionally the same
way. Items that rest on knowledge only the owner has are marked **(owner's knowledge)**: Q9, Q16, Q23, Q31, Q41, Q45,
Q53 and Q56.

| # | Question | Answer | Written into | A veto means |
|---|---|---|---|---|
| Q1 | The veto refusal's shape: `reason: "vetoed"` with the text in `veto`, or the row's `reason: "vetoed: <text>"`? | the token and `veto` (D504) | §3.2 rule 5 | `B38 Q1 veto`: `reason` is `"vetoed: <text>"`, no `veto` key |
| Q2 | Are `swing.type`/`swing.key` default targets and ENTER's default button vetoed too? | yes (D502) | §3.2 rule 2 | `B38 Q2 veto`: explicit targets only; the default button not followed |
| Q3 | Does the veto extend to `web.*` input on a browser component a veto names? The premise, corrected by the review: `web.pointer` already reads the browser's AWT component on every call for the modal check (`ComponentReads.stateOf`, which walks the chain for `masked`), so asking the veto there is one more field of a read already made; `web.dom.*`, `web.dispatchEvent` and the CDP path read no component, so covering them is a new EDT read on three routes and a hand-off. The guide's "veto the window" advice leaves the page inside such a window open to `web.*` input | no, a gap (D509, gap 126, now **medium**); the contract, the guide, `SECURITY.md` (a) and the threat model say that a window veto does not cover page input into a browser inside it | §3.2 rule 10, §16.2, §11 | `B38 Q3 veto cheap`: `ComponentState.vetoed` filled by the same chain walk, refused on `web.pointer` (agent path) only, `force` never skipping; `web.dom.*`, `web.dispatchEvent` and the CDP path stay gap 126; `B38 Q3 veto full`: §5 checks the browser component's chain in `web.pointer` and `web.dom.*` under route `web.pointer`, and the CDP path hands the four over while an `InputVeto` is registered (`web.browsers.vetoesRegistered`, absent counting as true) |
| Q4 | The visible-area rule with the viewport exemption, refusing `INVALID_PARAMS collapsed`? | yes (D510) | §3.3 | `B38 Q4 veto strict`: the viewport-clipped area everywhere; `B38 Q4 veto code`: `INPUT_REJECTED` for `collapsed` |
| Q5 | The pointer's component form refuses a point its component does not show (`not visible`)? | yes (D512) | §3.3 | `B38 Q5 veto`: as today, the events land where the point is |
| Q6 | A claimed (WEB) heavyweight keeps the window as the routed source? | yes (D513) | §3.4 rule 2 | `B38 Q6 veto`: such a point is refused `INPUT_REJECTED "web surface"` |
| Q7 | AWT controls semantic on COMPONENT too, and `DialogReader` admitting AWT `Button`? | yes (D515) | §3.5 | `B38 Q7 veto`: ACTION only; the dialog reader unchanged |
| Q8 | `robot=true` on the tools leg so that the Robot refusal is proven end to end? | yes (D516) | §12, §13.2 item 10 | `B38 Q8 veto`: no Robot there; `RobotPathDisplayTest` is the proof |
| Q9 | **(owner's knowledge)** Does the second domain's application veto by component identity (a name, a class) or by application state (a key whose action ends the JVM only in some state)? The SPI covers both; the answer decides whether an option-configured veto (`inputVeto=<ids>`, gap 127) is worth building soon | identity, assumed; SPI only now | §16.2 gap 127 | `B38 Q9 state`: nothing changes in Phase 8 (the in-task re-ask covers state); gap 127 stays low |
| Q10 | Admission by budget **plus** a 2x-overrun penalty, or log and count only? | admission plus the penalty (D521) | §4.1 | `B38 Q10 veto`: log and count only; `penalised` stays empty |
| Q11 | A concrete base class `QuiescentPoint` in `agent-api`, or a provider-owned `callAtQuiescentPoint(task, budgetMs, timeoutMs)`? | the base class (D519) | §4.1 | `B38 Q11 veto`: `veto`: the provider method; the core keeps only the gates and the overrun count, the helper becomes a sample class |
| Q12 | `ThreadPolicy.LANE` for primitives now, or `DataSourceProvider.lane()` only? | now (D526) | §4.2 | `B38 Q12 veto`: `veto`: the enum ends at `APP_QUIESCENT`; `lane(String)` on the builder is dropped |
| Q13 | Bound `APP` (8 calls in flight -> `BUSY`) alongside the lanes? | no (D528) | §4.2 | `B38 Q13 veto`: `veto`: the bound, with `BUSY {reason: "application lane full"}` and a golden-free `agent.info.threads.app` |
| Q14 | A scoped `QueryControl`, or a general `Invocation.onCancel` fired by the dispatcher for every primitive? | scoped (D529) | §4.3 | `B38 Q14 veto`: `veto`: the general form (touches `Dispatcher`, `Invocation`, every `BrokerTimeout` path); `QueryControl` derived from it |
| Q15 | Derived labels automatic and public, or an explicit `columns:[string]` parameter on `data.query`? | automatic (D531) | §4.4 | `B38 Q15 veto`: `veto`: the parameter (a fifth param, `DataModuleTest` and every controller caller change); labels then caller-supplied |
| Q16 | **(owner's knowledge)** Does any pack outside this repository declare a procedure in `sqlAllowLists()`? Retire (refused at load with the message) or admit `CALL` through a declared channel? | retire (D533) | §4.5 | `B38 Q16 veto`: `veto`: admit through the two-key form and `data.call` (L, a security pass; the editor opens a package or defers to 8.x) |
| Q17 | The sample: idle tick default 100 ms with today's timer reset, and `lending-report` always on? | yes (D535, D536) | §12 | `B38 Q17 veto`: `veto tick`: 0 (one point per `inputRequested`; item 1 of §13.2 weakens to "after an input"); `veto source`: behind `lending.report=true` and a new `Mode` |
| Q18 | Should the secret-key rule run on every event payload even when no `MaskPolicy` is registered (a change for targets that publish under a key such as `token`)? | Yes (D540) | §5.1 | `B38 Q18 veto`: the key rule only while a policy is registered |
| Q19 | A new `BrowserMask` SPI (worker thread, tag and vendor object), or a default method on `CaptureMask`? | The new SPI (D546) | §5.2 | `B38 Q19 veto`: `CaptureMask.masksBrowser(String tag, Object vendorObject)` default null, the same union rule, called on a worker |
| Q20 | May a masked browser be selected by `by: {url\|title}` (matching on withheld text), or only by tag, native id or ref? | Only by tag, native id or ref (D550) | §5.2 (ii) | `B38 Q20 veto`: match, but withhold the URL and title in every answer |
| Q21 | Below Playwright 1.60: refuse the CDP path unless the operator sets `skillroy.cdp.allowDefaultOverrides`, or the pack's/scenario's `web: {allowDefaultOverrides: true}` (a plan 7 key), or accept and log as design §3.8 says? | The operator's property (D553) | §5.3 | `B38 Q21 veto key`: the `web:` key, gated "needs a version 7 plan", built with EX; `B38 Q21 veto accept`: design §3.8's accept-and-log |
| Q22 | Should the CDP path's own events pass the pack's `rowMasks()` as `event:<topic>` now (path parity), or stay a recorded residual? | Residual, gap 138 | §5.1, §16.2 | `B38 Q22 veto`: `WebRunContext` hands the path the pack's row masks; `PathEvents.publish` applies them with the §5.1 walk |
| Q23 | **(owner's knowledge)** Does any layer legitimately read a page in a browser that has no view while it masks another (the pre-loaded page of row 110's example)? If so it registers a `BrowserMask` FALSE for that browser; the sample shows the form | Assumed yes; the release form is specified and proved on the web host | §5.2, §12.2 | `B38 Q23 veto`: nothing to build; the guide drops the migration sentence |
| Q24 | The scalar budget of the ring pass: 10,000 per event, `<redacted>` beyond? | Yes (D541) | §5.1 | `B38 Q24 veto`: another figure, or the character bound alone (policies then see cut strings) |
| Q25 | **Filter or requirement?** Should `target.codeSource` select the copies to hook (a non-matching copy left alone) or require every loaded copy to match? | Filter (D558) | §6.4 | `B38 Q25 veto`: requirement semantics, `UNAVAILABLE("class from another code source")` when any copy does not match |
| Q26 | **The name rule.** File name plus inner entry paths, directories never (D154's rule), or the full location? | File name only (D556) | §6.2 | `B38 Q26 veto`: the full location, percent-decoded; the guide then warns about user names in paths |
| Q27 | **The deny grammar.** `ClassPattern` (no regex) for `deny`, while `codeSource` stays a regex? | Yes (D561) | §6.3 | `B38 Q27 veto`: regex patterns in `deny`, with the 200/64 bounds |
| Q28 | **Add-only.** A deny only tightens for the agent instance's life (a restart loosens), or does `replace: true` replace it? | Add-only (D564) | §6.6 | `B38 Q28 veto`: `replace: true` replaces the held list with the new catalogue's |
| Q29 | **The echo.** Fail the install when the agent does not echo the deny (an old agent), or warn and proceed? | Fail closed (D565) | §6.9 | `B38 Q29 veto`: a warning in the record (gap 18's channel) and the run proceeds |
| Q30 | **Delivery without the hooks module.** `denyNotDelivered` plus the option `hooks.deny`, or a core primitive the installer always sends? | The record and the option (D566) | §6.9 | `B38 Q30 veto`: a core primitive `agent.deny {patterns}` and a core-held registry, sent under every mode but `off` |
| Q31 | **(owner's knowledge) The sample's targets.** Qualifier `skillroy-sample-lending-app(-[^/]*)?\.jar` (a class-directory run of the desk with hooks is then refused, §6.12) and `deny: [….Inbox]`? | Yes (§12.3) | §12, P1 §9 | `B38 Q31 veto`: another qualifier form or deny target, named by the owner |
| Q32 | **A hook that is denied later.** Flag it in health and the record, or remove it at the next install? | Flag (D568) | §6.6 | `B38 Q32 veto`: remove it under the next `hook.install` that holds the deny, reported in `replaced` |
| Q33 | The warning channel in the envelope (`RpcResult.warnings`, `Invocation.warn`) rather than a value key? | Yes (D576) | §7.1, A83/2 | `B38 Q33 veto`: a reserved value key `warnings` (objects only, strings ignored); no `RpcResult`/`Invocation` change |
| Q34 | An accepted warning leaves `PASS`? | Yes (D577) | §7.1 | `B38 Q34 veto`: accepted -> `DEGRADED` (one line in `CallRunner`, the JUnit mapping of B14 applies) |
| Q35 | Correlated acknowledgements opt-in through `SyncPolicy.ackByInputId`, fail-closed without `inputId`? | Yes (D582) | §7.4 | `B38 Q35 veto`: opportunistic (match whenever the result has `inputId`), no flag |
| Q36 | Gap 19 by the stated rule with the two executor checks, the `when` form deferred? | Yes (D584) | §7.5, gap 142 | `B38 Q36 veto`: `when: {topics, match}` on a call, plan 7, a skipped call recorded `skipped: true` (M-L; sessions and export included) |
| Q37 | Call-level ladders with agent-side choice rather than a controller-side ladder of alternative calls? | Yes (D588) | §7.6 | `B38 Q37 veto`: `PlannedCall.ladder(List<Rung(name, capability, calls)>)` resolved once at run start, the plan flattened before steps run (L) |
| Q38 | `ui.strict` extends to pack ladders? | Yes (D581) | §7.3 | `B38 Q38 veto`: `minRung` per step only; `strict` unchanged |
| Q39 | The secret-echo check on every call with secret parameters, not only composites? | Yes (D585) | §7.5 | `B38 Q39 veto`: composites only (`composite` key present) |
| Q40 | Three new sample capability ids (five goldens regenerated once) rather than a sample without a physical rung? | Yes (D591) | §12.2, §12.4 | `B38 Q40 veto`: convention-only sample (no `ScannerDevice`, no driver); the ladder proven by `ToyPack` alone |
| Q41 | **(owner's knowledge)** Do the second domain's four ladder verbs fit 2..8 rungs with one capability condition per rung, and can its composites publish `prompt.waiting` (with a `seq`) for the nested questions they answer, or do they stay silent (then `answered` entries carry no `seq` and no reconciliation is needed)? | Assumed: yes to both; silence allowed | §7.5, §7.6 | `B38 Q41 veto`: the bounds move (rungs up to 16; `answered` up to 64) |
| Q42 | Plan version 7 once, always written, with strict step and call keys from 7? | yes (D595, D596) | §8.2, P1 §8.2, P2 §5, guide §4.4 | `B38 Q42 veto`: the lowest-needed version per plan, or lenient keys kept: `Plan.fromJson` only; the regeneration still happens once |
| Q43 | The `events` root refuses on loss rather than answering a lower bound? | refuse (D602) | §8.6 | `B38 Q43 veto`: `truncated: true` with a lower bound as `hooks`; `{size: 0}` then documented as "at least none seen" |
| Q44 | A `[json]` segment, or a `json:` matcher, or both? | the segment (D601) | §8.5 | `B38 Q44 veto`: add the matcher: `Matchers` only |
| Q45 | **(owner's knowledge)** May `captureFacts` be a reserved verb name, given that a layer's pack that already declares a verb of that name would be refused at compile? | yes, reserved (§2.2, D604); the name was chosen as unlikely to clash | §8.7, P2 §3.3 | `B38 Q45 veto`: not reserved: the pack verb wins (the base-verb rule), `RESERVED_VERBS` unchanged |
| Q46 | Under `expectationsOnMaskedValues`, fail a step whose captured value the mask changes? | yes (D605) | §8.7 | `B38 Q46 veto`: capture the masked text instead: the value is then useless as an operand but the step passes |
| Q47 | Cut `actual` in `run.jsonl` at 200 list elements? | yes (D608) | §8.10 | `B38 Q47 veto`: no cap (`RunLog` unchanged), or another number |
| Q48 | Quantifiers `some`/`every` over list elements in Phase 8? | no (gap 145) | §8.4, §16.2 gap 145 | `B38 Q48 veto`: add `some: matcher` and `every: matcher` to `Matchers` (small) |
| Q49 | Are pack capabilities honoured in `replay` (never forced `UNAVAILABLE`)? | yes (D610): the context carries no mode or gateway; the ledger check catches a model call | §9.1.3, P7 §8.5 mark | `B38 Q49 veto`: force `UNAVAILABLE("replay")` as contributors: every CI guard on one is `DEGRADED`, which defeats 106 |
| Q50 | Evaluate every declared id in every run, or only the ids the plan names? | every id (D612), with `SUITE` scope for slow ones | §9.1.3 | `B38 Q50 veto`: evaluate only named ids: `capabilities.json` and `caps` become plan-dependent |
| Q51 | Accept `pack` as the explicit spelling of the default? | yes (D615) | §9.2, schema enum | `B38 Q51 veto`: `fail` only; the enum shrinks to one value |
| Q52 | Named pack policies (`DomainPack.promptPolicies()`) in Phase 8? | no (D615): deferred; the loader's message leaves room | §9.2 | `B38 Q52 veto`: build `promptPolicies()` (S/M): compile-time name check, `DelegatingDomainPack`, schema per pack |
| Q53 | **(owner's knowledge)** May scenario A gain `requires: [… lending.returns.ceiling]` (its two plans, the standard and the branch variant, and its three bindings files' `planHash` change in step R) and `lending.database` become `SUITE`? | yes (D620): step R regenerates anyway for the pack bump | §12.3, A99 | `B38 Q53 veto`: leave scenario A: the gap 24 proof becomes an in-memory scenario in the headless test; keep `lending.database` `SCENARIO` and add a test-only `SUITE` check in `PackCapabilityHeadlessTest` |
| Q54 | A cached `SUITE` failure fails every later scenario of the suite as `ERROR / ENVIRONMENT`, with a suffix? | yes (D616): that is "one environment failure" as the design means it | §9.3.1, guide §8.3 | `B38 Q54 veto`: report later ones as skipped (`Assumptions.abort`, `<skipped>`): a status-mapping change (B14) |
| Q55 | The agent session through a no-I/O default `agentSession()` (null never cached) rather than one `/ping` per run? | the default method (D617) | §9.3.2, A98 | `B38 Q55 veto`: a `/ping` per run when the pack has a `SUITE` item; `Recording`'s `sent` lists then gain `ping` |
| Q56 | **(owner's knowledge)** Is "configuration is a capability, probed by the module or evaluated by the pack" the house rule for the second domain's settings (the return-limit check, the remote and delayed authorisation, the page settings), or does it need arguments? | the capability rule (D621); the second domain's facts are agent-probed today and work; a pack-evaluated id reads a setting the environment holds | §9.4, guide §8.8 | `B38 Q56 veto`: a `preconditions:` key with arguments (M): a top-level YAML key, a plan field (version 7), a pack SPI with arguments, schema, compiler checks |
| Q57 | Fix `re-resolve`'s agent-only `requires` check in this phase? | yes (D622) | §9.1.5 | `B38 Q57 veto`: record it as a gap and leave the check |
| Q58 | The protocol stays 0.2, with the echo rule of §2.1 for the safety-relevant parameters (the deny list, `codeSource`, `minRung`, `masksRegistered`) instead of a capability id or a `/schema` check per gate? | yes (D624) | §2.1, §10 | `B38 Q58 veto minor`: `0.3` as information only (`ProtocolConstantsTest`, the discovery file, P1 §3); `B38 Q58 veto schema`: a `/schema` presence check per new parameter on the controller, refusing an agent that lacks it |
| Q59 | Gaps 13, 28, 29, 123, 124 and 125 deferred with the reasons and seams of §1, and 13 named as the vehicle for a procedure call? | yes (D631) | §1, §16.2 | `B38 Q59 veto <n>`: naming the row to take; 13 then opens a package with a security pass (the two-key form of §4.5's rejected alternative), 123 an SPI for painted text consulted by snapshots, locators and marks |
| Q60 | The security delta review over everything since `223c5fb` (the B32-B35 follow-ups included), eight lenses with sceptics, S149 onwards, nothing medium or above accepted, the owner signs B40? | yes (D632) | §15 | `B38 Q60 veto release`: since the release commit only; `B38 Q60 veto full`: a full review of the tree; `B38 Q60 veto none`: no review before the next release (against the checklist) |
| Q61 | One regeneration step R after every package landed (plans 7, pack 0.7.0, module 0.3.0, `DataModule` 0.2.0, the web host module 0.3.0, four new agent ids into the five goldens, the recordings key-free, the counts once)? | yes (D626, D630), amended by Q62 and D641: the plan version is regenerated at the skeleton too, and the counts are the packages' branch edits resolved at merge | §2.4, §2.8, §12.5 | `B38 Q61 veto`: per-package regeneration as each lands (36 committed files rewritten several times); `B38 Q61 veto ids`: no new sample agent id (`lending.engine.view` becomes a sub-key of `app.state`; §7's three ids as Q40's veto), the goldens untouched |
| Q62 | Regenerate the committed plans and bindings hashes twice, the version alone at EX's skeleton commit and the rest in step R, rather than once in step R with the skeleton red until then? (`Plan.fromJson` reads every plan back as version 7, so every byte-for-byte and `planHash` comparison in `controller/skillroy-scenario`, the lending scenarios and the `jcef` profile is red from the skeleton on; the red starts upstream of most of the reactor) | twice, once per cause (D626 as revised, D641); each package compiles the plans of the scenarios it adds on its own branch; the named windows (EP's scenario A files, ES's recordings and exports, the image's golden check on ET's and ES's branches) stay red until step R and nothing else does | §2.2, §2.8, §8.2, `contract-notes.md` §3-§4 | `B38 Q62 veto`: one regeneration in step R, the skeleton and every branch carrying the named red tests until then, listed per step in `contract-notes.md` §4 |
| Q63 | `sign-on-any`'s composite call carries an argument named `password` (`"password": "$assistant.password"` in the committed plan), which `PlansUpToDateTest.noScenarioOrPlanHoldsAPassword` refuses in any file under `plan/` today: allow the reference form in the test, or rename the parameter? | allow the reference (D642): a `"password"` key whose value is exactly a `$<slot>.password` reference to a slot that declares a credential; a literal password and a `password:` in YAML stay refused | §12.4, §13.1 row 34 | `B38 Q63 veto`: rename the composite's parameter (`credential`), the secret-key rule then not masking it by name and §12.2's masking of the composite's argument resting on `_meta.secretParams` alone |
| Q64 | Close the attach window of gap 139 (the base modules' events between their own install and the domain module's `MaskPolicy` registration, masked by key only) by re-masking the ring at a registration? | no, recorded (gap 139; §5.1 and §11 rule 3 say "from the policy's registration on", `SECURITY.md` (c) says it in one clause): a short window on attach only, the key rule covers it, and a re-masking pass is a second masking route for a window no target has shown | §5.1, §11, §16.2 gap 139 | `B38 Q64 veto`: one pass over the ring at each `MaskPolicy` registration, outside the ring lock, a slot replaced only when it still holds the same event, counted in `agent.info.events` |
