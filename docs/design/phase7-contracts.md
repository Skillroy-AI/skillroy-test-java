# Phase 7 contracts: the vision bolt-on and the vision loop of interactive sessions (the extended `swing.capture`, `swing.hitTest`, `swing.pointer` routed and through Robot, their `web.*` counterparts, `skillroy-vision` with the tiers V1-V3, the recorder, pixel-anchored replay, `expectImage`, the capability `inference.vision`, plans skipped in `replay`)

**Status: specified 2026-10-10, revised the same day after the contract review (three lenses, each with a sceptic; the
session notes' `contract-review/revision-log.md` says what each finding changed), and built the same day** in four
packages (VS, VW, VC and VM) integrated in the order VC, VM's step 1b, VS with the regeneration step 2b, VW, VM; every
section a package built or deviated from carries an "As built (Phase 7, <TAG>)" note. **Reviewed and fixed 2026-10-10:**
the code review (three lenses with a sceptic each: 30 distinct findings, 2 high, 9 medium, 19 low) was fixed by FA, FB and
FC, marked "Fix wave (Phase 7 review, <TAG>, 2026-10-10)", and a race in the hooks engine that the matrix found by FX
(marked "As built (Phase 7, FX, 2026-10-11)" in P6 §4.6). **Security-reviewed (delta) and fixed 2026-10-10/11:** the delta
review of §15 (eight lenses with a sceptic each: 48 standing findings S101-S148, 2 high, 13 medium, 33 low) was fixed by SG,
SH, SI and SJ, marked "(Phase 7 security fix, S<nnn>, built 2026-10-11, <TAG>)"; S142 and S144 are accepted by the owner
(B31, 2026-10-11). The build's decisions are D359-D430, those of the fix wave, the race fix and the security fixes D431-D486
(§17); built on the then provisional B29 and B30, **the answers of §18 (B29) and all these decisions (B30) were confirmed by
the owner on 2026-10-11, every row and every decision, no veto, and the delta review was signed off by the owner the same
day (B31)**. Written after the seven reader maps
of 2026-10-10 (kept in the session notes, not in this repository; `map n §m` below) and after the four spikes of design
§8 reported the same day: **BS9** passed with conditions (`../../spikes/BS9/RESULT.md`: Robot input on the WM-less Xvfb,
with openbox and on a macOS desktop, Java 8 and Java 25 targets; `ROBOT` against `PAINT` capture; swing-driving F13
settled), **BS10** passed with conditions (`../../spikes/BS10/RESULT.md`: routed in-JVM dispatch against Robot, 16
cases), **BS11** passed with conditions (`../../spikes/BS11/RESULT.md`: `PAINT` determinism within and across
environments, the tolerance study) and **BS12** passed, live (`../../spikes/BS12/RESULT.md`: grounding accuracy,
latency, tokens and cost of V1 and V2 on Sonnet 5.5, Opus 5.5 and Haiku 5.5, USD 5.64). Every statement that rests on a
spike cites it as `BS<n>` with the section of its result. The questions of §18 were answered by the orchestrator on
2026-10-10 and recorded as decision **B29** (`../DECISIONS.md`), confirmed by the owner on 2026-10-11; the build's and the
reviews' decisions are **B30**, confirmed by the owner the same day; the security delta review's sign-off is **B31** (§15),
given by the owner on 2026-10-11. Written in the manner of `phase5-contracts.md` and
`phase6-contracts.md`: the contract is changed first, then the code, and the build marks every passage it builds or
deviates from with an "As built" note under a two-letter implementer tag (§2.3). Section references `P1 §n` to `P6 §n`
are to the six earlier contracts, `design §n` is the design overview `base-framework-design.md`, `vision §Fn/Rn/Cn/Riskn`
is the research note `../research/vision-input.md`, `swing §Fn` is `../research/swing-driving.md`, `browser-spi §Fn` is
`../research/embedded-browser-spi.md`. Decisions taken here are **D287 to D358** (§17; D341-D358 were added by the
revision after the review); the build's decisions are D359-D430 (§17, numbered by the orchestrator at integration; they
are B30), and the decisions of the code review's fix wave, the race fix and the security fixes are D431-D486 (§17; they
join B30);
amendments to the frozen Phase 1 contract are **A61 to A66** (§5), the next free number after P6's reservation (none of
A61 onwards was used); gaps continue from **77** (gap 76, the capture defect BS9 found, is closed by this contract,
§16.2); the security delta review's findings start at **S101** (§15); the plan version becomes **6** (§8.2), the
bindings file version **2** (§8.7), the script library's version **2** (§4.6); the protocol stays **0.2** (D299).

What Phase 7 is for, in one paragraph. Phases 1 to 6 built a framework in which every step addresses a component, a
fact or a row, and a model is never in the CI loop. Phase 7 adds the one thing the design kept out of the base and
reserved names for (B16, design §3.10): a model may look at the screen and act on it like a person, in interactive use
and at authoring time, and what it does is recorded once into ordinary steps that replay with zero model calls. The
agent gains three small additions on JDK 8 API: a capture that says where its pixels lie and what is in them, a read-only
hit-test from a point to a component with its locators, and a pointer that acts at coordinates, routed through the
window by AWT's own dispatcher or, behind a double opt-in, through `java.awt.Robot`; the web module gains the same two
operations for an embedded page. The controller gains the optional module `skillroy-vision`: the loop in three tiers,
the adapters for the Claude computer toolset and for a provider-neutral set of custom tools, the recorder that turns a
pixel into a locator before anything is clicked, the export of such steps as ordinary scenario steps, pixel-anchored
replay behind an exact image guard, and `expectImage`. Where a pixel cannot be mapped to a component the order is a
pack's `RegionResolver`, then an image-anchored `pointer` step, then a plan that `replay` reports as **skipped**, which
is neither a failure nor a pass. **What a model sees is always a redacted `PAINT` capture of the target's own windows**,
in every tier: V3 means "the model sees `PAINT`, Robot acts" (D341), and a capture of the glass is a person's evidence
only. **Nothing of Phase 7 changes the default path**: without the agent option `robot` no Robot is ever constructed,
without `inference.yaml`'s `images: true` no pixel reaches a model, without the vision module on the class path every
scenario of Phases 1 to 6 compiles and runs as before (plan version 6 is one regeneration of the committed plans), and
`replay` still makes zero model calls, asserted.

---

## 1. Scope and non-goals

Phase 7 delivers:

- **the agent's Swing side** (Java 8, `skillroy-agent-swing`, §3): `swing.capture` extended additively (A61) with the
  reserved parameters `scope`, `region`, `scale`, `redact`, `mode: ROBOT`, `annotateRefs` and the reserved result
  fields `captureId`, `seq`, `space`, `origin`, `scale`, `windows`, `surfaces`, `marks`, plus what BS9, BS11 and BS12
  showed is needed: the window painted from its root pane (BS9 RESULT "The capture comparison"; BS12 RESULT finding 1),
  the caret rectangles and the focus state (BS11 RESULT conditions 1 and 2), the redacted rectangles, and a hash of the
  decoded pixels (BS11 condition 4); `swing.hitTest` (read-only; A63); `swing.pointer` with `via: ROUTED` by default
  and `via: ROBOT` behind the double opt-in (A63, A65); `swing.type` with an omitted target (A62); the read
  `swing.rendering` (the rendering inputs a baseline is keyed by, §3.8; A63); the agent SPIs `RegionResolver`,
  `CaptureMask`, `SurfaceSource` and the default method `LocatorResolver.describe` (A64); the five reserved capability
  ids; the agent option `robot`;
- **the web side** (§4): `web.hitTest` and `web.pointer` on the in-process path (JCEF verified in the image, JxBrowser
  behind descriptor roles and marked unverified) and on the CDP path; `WebCapability.INPUT` with the SPI method
  `WebBrowser.dispatchPointer` (A66); the web module as a `SurfaceSource` for the Swing capture; the script library's
  `hit` function and its versioning rule;
- **`controller/skillroy-vision`** (Java 25, optional, §6): the loop for V1 (the default: marks on a `PAINT` capture at
  the window's own size, answered by mark; BS12 RESULT "Accuracy": 100 % on Sonnet 5.5 and Opus 5.5), V2 (the Claude
  computer toolset, or the provider-neutral custom tools with `click_at`; BS12: 100 % through the toolset) and V3 (V2
  acting through Robot, opt-in; the model still sees `PAINT`); the tool adapters; grounding and visual questions; the
  recorder (hit-test before acting, act through the step that will be replayed, the stale-capture rule as BS12 found it
  workable); the export of such steps as ordinary steps with provenance; the scenario player that runs a scenario with
  `aiAct` and `aiExpect` steps over `ExecutorSession` in interactive use; the contributor of `inference.vision`;
- **the gateway changes** (§7): provider-defined tool entries, `toolset_name`, image parts inside a tool result, the
  automatic cache hint, `IMAGES` on the recorded and the scripted gateway, the `claude-haiku-5-5` price row, image
  tokens in the budget estimate, the `effort.vision` key;
- **the scenario side** (§8): the verbs `aiAct`, `aiExpect`, `pointer`, `expectImage`; the plan field `inference: run`
  and plan version 6; the step locator `region` beside `by`; the checks `VisualCheck` and `ImageCheck`; controller-side
  capabilities merged into the matrix through the SPI `CapabilityContributor`, `inference.vision` among them,
  `UNAVAILABLE(reason="replay")` in `replay`; the fifth status `SKIPPED` for a plan `replay` does not execute; image
  baselines and anchors in the bindings file (version 2) keyed by a rendering key, with masked PNGs beside it, and the
  bind lifecycle they need; the exact image comparison in `skillroy-scenario`, so that an anchored step replays without
  the vision module;
- **interactive sessions** (§9): the `SessionTools` seam of the session service, through which the vision module adds
  its tools to the one loop, meter and journal and acts only through the service's facade `SessionActions`; the
  unjournaled read `ExecutorSession.read`; the provenance object on a journal step, handed in by the acting call; the
  opt-ins `RunOptions.robot` and `SessionOptions.robot`; `SessionOptions.host`; the CLI options `--vision` and
  `--allow-robot`, `session --play`; the MCP server's `--vision` and `--allow-robot`;
- **budgets and the ledger** (§10): USD 1.00, 30 UI actions and 20 model turns per session (BS12 RESULT "Proposal");
  the purpose `vision`; every vision call through the service's metered, ledgered call, so that the zero-LLM assertion
  covers it;
- **safety and data** (§11, and `threat-model.md` extended): captures for a model always redacted `PAINT` captures of
  the target's own windows; pixels leaving only under `images: true`; screen text as untrusted input; no credential ever
  a model's or a client's argument (D342); `confirm` and read-only mode unchanged; V3 behind the double opt-in, refusing
  a point outside the target's windows and a point while the target JVM holds no focused window, and never leaving a
  button pressed (D343, D344); captures the loop acted on kept masked under the output directory; the limitation
  sentences the build writes into `SECURITY.md`;
- **the sample** (§12): a one-component number pad on the lending desk with its `RegionResolver`, a capture mask for
  the entry field and the number pad while a secret is expected, the keypad legend, and five Phase 7 scenarios, among
  them the exported vision session that replays with zero model calls;
- **tests and acceptance** (§13), **exit criteria** (§14: the four of design §8 row 7, made precise), and **the
  security delta review** (§15, B31).

**Non-goals.** Named so that their seams are respected, not built:

| Not in Phase 7 | Seam reserved or rule stated here | Phase |
|---|---|---|
| A capture of the glass (`swing.capture {mode: ROBOT}`) for a model or an MCP client | never (D341): a `ROBOT` capture shows other processes' pixels and the desktop, which no mask can name; it is refused for every origin but a person's by `CallPolicy` and by `ExecutorSession`, the V3 `capture` tool has no mode, and design §3.10's V3 row ("a `ROBOT` screen capture") is amended to "a `PAINT` capture; Robot acts" (§16.1); a later glass check (a `ROBOT` raster compared with a root-pane `PAINT` of each window, which BS9 showed pixel-identical when nothing covers it) is the alternative the owner may ask for (§18 Q35) | later, if the owner wants it |
| Robot key input (`swing.type` mode `ROBOT`, a Robot `swing.key`), gap 31's `ROBOT` type mode | not built (D345): V3's keyboard goes through the routed `swing.type {mode: SET}` by locator and `swing.key`, after the agent-side focus rule of §3.4; the mode value stays refused "not in this version"; the reason is BS9's key conditions (RESULT "What a `ROBOT` rung and the V3 tier need": keys land in the AWT focus owner if and only if the target JVM has a focused window, and on a WM-less display nothing restores the focus after a modal dialog closes) | later, if a target needs the OS's key path proven (§18 Q6) |
| A Robot button held across calls (`PRESS`, `RELEASE` through Robot; the toolset's `left_mouse_down`, `left_mouse_up` in V3) | never (D343): a Robot press is always paired with its release inside one worker task, released in a `finally` before the pointer is restored; the halves are refused with the reason | none |
| `swing.click {rung: ROBOT}` as a click | stays the sentinel it is (`UNAVAILABLE("opt-in rung, not implemented")`, P1 §6); a click at a component's centre through Robot is `swing.pointer {target, via: ROBOT}` (D291) | none |
| `swing.events` (a key, mouse and focus tap; gap 31), `OPTION_VALUE` dismissal | unchanged; "did my input arrive" is answered by the pointer's `received` field and by the pack's events through the step protocol (D293) | later |
| `Strategy.LLM_VISION` as a bind-time strategy, the query kind `UiTarget` | both stay reserved: the token is still refused "not in this version" by the loader, the compiler and the plan reader; plain recorded steps are the one shape (vision §R3's default, §Risk1; D316) | 7.x, if a target's locators legitimately differ between environments |
| An `aiAct` or `aiExpect` step executed by `PlanExecutor` | a plan with `inference: run` is `SKIPPED` in `replay` and refused `ERROR / ENVIRONMENT` in every other mode by `PlanExecutor`; the vision module's `ScenarioPlayer` runs such a scenario over `ExecutorSession` (§6.8, D311); `PlanExecutor` skips every `VisualCheck` unconditionally, whatever the capability's state (D312, D354) | none planned |
| A base web verb for pointer input (`webPointer`), `pointer` on a web surface | a pixel action on a web surface is recorded as `webClick` (by role and name) through `web.hitTest`'s locators, or kept live-only; a raw `call: {primitive: web.pointer}` remains possible (§6.5) | later, if a page needs a position-dependent step |
| Perceptual image metrics, OCR, video, image comparison beyond what `expectImage` and the image guard need | the comparison is exact on decoded rasters after masks, with `maxDiffPixels` as the one explicit margin (BS11 RESULT "Tolerance study"; D314) | none (vision §R9) |
| OS input tools outside the JVM (xdotool, `SendInput`), a window manager in the CI image, driving windows of other processes | never (vision §R9); the CI image stays WM-less (BS9 RESULT: routed input and `PAINT` capture need none) | none |
| Robot as a default anywhere; V3 over the overlay without a person's flag | never (B12, design §3.10); the double opt-in of §3.4 | none |
| Server-side tool-result clearing, client-side pruning of screenshots, mid-conversation `system` messages, `Effort` values beyond `HIGH` | not in the SPI; a session's history is append-only within a turn and the Phase 5 fold applies between turns (§7.4, D323) | later, if a long session shows the need |
| The presentation of an in-application overlay (a drawn pointer, highlights) | the overlay stays a tenant layer's (design §5); `OverlayBridge` is unchanged (§9.5) | none |
| HiDPI scale factors other than 1, Windows, Wayland, a locked session, macOS with the Accessibility permission granted | specified where the spikes reached (§3.4, §13.4); verified nowhere (BS9 RESULT "Not verified"; BS11 RESULT "Not verified") | when a host exists (`../TODO.md`, Walls) |
| Vendor-specific embedded-browser code; JxBrowser input verified against the real library | behind descriptor roles, strategy "input unverified" (§4.5); the kit against the real library in a licence holder's repository | wall (gap 41) |
| Hidden JCEF browsers in a vision loop | a hidden browser has no bitmap (gap 39), so the tiers never target one; `web.pointer` into one is allowed and recorded `visibleInSwing: false` (§4.3) | none |
| A self-healing locator, a model call at replay time | never (C0, design §10) | none |

Phase 7 adds **one controller-side module** (`skillroy-vision`), **no agent-side module** (the Robot implementation
lives inside `skillroy-agent-swing`, D290), **no sample module** (the lending desk, its agent module, its pack and its
scenarios grow), and **no third-party dependency** anywhere: the toolset's typed classes are in the audited SDK
(`../licence-audit.md` §4 rows for `anthropic-java` 2.70.0; map 4 §1.9), image work on both sides is
`java.awt.image` and `javax.imageio`, and the agent's additions are AWT and Java2D (vision §C5).

---

## 2. Module layout, dependencies, versions, build and distribution

### 2.1 Decision

| Module | Change in Phase 7 | Depends on |
|---|---|---|
| `agent/skillroy-protocol` | nothing on the wire types; the script library gains `hit` and version 2 (§4.6) | – |
| `agent/skillroy-agent-api` | the SPIs `RegionResolver`, `CaptureMask`, `SurfaceSource` in `…agent.api.spi`; `LocatorResolver.describe` (default); in `…agent.api.spi.web` the value type `WebPointerEvent` and the default method `WebBrowser.dispatchPointer`; Javadoc of `WebCapability.INPUT` (A64, A66). The three Swing SPIs and `describe` land as VS's first commit, which VW rebases on; the two web SPI files are VW's | – |
| `agent/skillroy-agent-core` | the option `robot` in `AgentRuntime.resolveOptions` (a boolean with the default `false`, validated like `readOnly`, reported by `agent.info.options`); the Swing module reads it from `ctx.options()`, never through `ctx.option(…)`'s live fallback (A65) | unchanged |
| `agent/skillroy-agent-swing` | `swing.capture` extended, `swing.hitTest`, `swing.pointer`, `swing.rendering`, `swing.type`'s default target; the classes `HitTest`, `Pointer`, `RobotInput` behind the seam `RobotFactory`, `Marks` (geometry only), `Redaction`, `Rendering` beside `Capture`, `Clicks`, `Typing`; the capability ids of §3.9; the masked-text rule in `ComponentView` and `ComponentText` (§3.6) | `agent-api`, `protocol` |
| `agent/skillroy-agent-web` | `web.hitTest`, `web.pointer`; `web.hittest` and `web.pointer` derived capabilities; the `SurfaceSource` registration; the `jcef` adapter's `dispatchPointer`; the `jxbrowser` descriptor roles under `for: "INPUT"` (§4) | unchanged |
| `agent/skillroy-agent-dist` | the home unchanged; `HostSafetyIT` gains the no-Robot case of §13.2 item 11 (a full home, an AWT fixture, the display) | unchanged |
| `controller/skillroy-controller` | `CapabilityMatrix.with(Map<String, Capability> contributed, String source)` and `source(id)` (`agent`, or `controller:<id>`), kept outside the wire shape; nothing else (§8.5) | unchanged |
| `controller/skillroy-scenario` | `Status.SKIPPED`; plan version 6 with `inference: run`, the four verbs, `region`, `row`, `VisualCheck`, `ImageCheck`, `Plan.Call.guard`, `Plan.needsBindings()` over `images`; the SPI `CapabilityContributor` and the merge; `BaseFacts.caps(Supplier)`; `…scenario.image.ImageCompare`; bindings file version 2 (`images`) with `Approver.reviewImage` and the carry-forward rule; `REBIND_NEEDED` reason `IMAGE`; `RunOptions.robot`, `SessionOptions.robot` and `SessionOptions.host`; `ExecutorSession.read`, `capabilities()`, the four-argument `execute` forms, the origin-aware refusals of §9.2; the journal step's `provenance`; the export's comment, report lines, `images` section and `images()`; `DomainPack.screenLegend()`; `InferenceSettings.effort.vision` (VM's one file in this module) (§8, §9) | unchanged |
| `controller/skillroy-inference` | the SPI changes of §7; `SessionTools`, `SessionActions`, `SessionToolContext`, `SessionExtensions` in `…inference.session`; the service's `visionCall`, its limits and the facade; `CallPolicy.arguments(primitive, args, robotAllowed)` with the refusals of §9.1 at every raw-tool path; `PromptMask.LABELS` gains `model-question`, `PromptMask.holdsSecret(text)`; the one-sentence change of `SessionService.vocabulary()` (§9.2), **VM's first commit, landed before VS regenerates the recordings** (§12.5); `InferenceContributor` implements `CapabilityContributor` and stops answering `inference.vision` (this one class and its test are landed by VC in step 1 as a shared file in VM's module, so that two `inference.` ids are contributed before VM lands, §13.2 item 9) (§7, §9.1) | unchanged |
| **`controller/skillroy-vision`** (new; `ai.skillroy.testjava.vision`) | the loop, the tiers, the two tool adapters, grounding and visual questions, the recorder, the scenario player, the captures directory, the contributor of `inference.vision` (§6); its tests carry their own fake agent with PNG fixtures and take `ToyPack` and `FakeAgentConnection` from the `skillroy-scenario` test-jar | `skillroy-inference`, `skillroy-scenario`, `skillroy-controller`, `skillroy-protocol`; **optional at run time**: `skillroy-scenario`, `skillroy-inference`, `skillroy-junit` and `skillroy-mcp` do not depend on it; found through `ServiceLoader` (`SessionTools`, `CapabilityContributor`); the CLI depends on it and the CLI zip bundles it in `lib/` (as `skillroy-telemetry`, P6 §2.1); test scope: the scenario test-jar |
| `controller/skillroy-mcp` | `--vision`, `--allow-robot` through `ServerOptions` (`vision`, `robot`); `ToolCalls.primitive` calls `CallPolicy.arguments`; the captures directory's resources; `source` in `skillroy_catalogue` (§9.4) | unchanged: the server loads the session tools through `ServiceLoader` from the pack loader and the CLI's class path, so it does not depend on `skillroy-vision` |
| `controller/skillroy-junit` | `ScenarioTests.report` maps `SKIPPED` to `Assumptions.abort`; the property `skillroy.robot` with its warning line (§8.6, §9.3) | unchanged |
| `controller/skillroy-cli` | `session --vision* --allow-robot --play`, `run --allow-robot`, `call --allow-robot` (`CallCommand` refuses `via: ROBOT` and `mode: ROBOT` in its raw arguments without it, §9.3), `mcp --vision --allow-robot`, `caps` with `source`, the `skipped` count (§9.3); `WebCommandsTest`'s stale "reserved" string (§4.4) | + `skillroy-vision` |
| `controller/skillroy-cdp` | `web.hitTest` and `web.pointer` served (§4.7) | unchanged |
| `samples/skillroy-sample-lending-app` | `NumberPad` (§12.1) | unchanged |
| `samples/skillroy-sample-lending-agent` | `LendingRegions`, `LendingCaptureMask`, `LendingWidgets.describe`; module version `0.2.0` (§12.2) | unchanged |
| `samples/skillroy-sample-lending-pack` | `screenLegend()`; `LendableItem.inductionRequired` (gap 49); pack version `0.6.0` (§12.3) | unchanged |
| `samples/skillroy-sample-lending-scenarios` | the five scenarios of §12.4, their plans, bindings and baselines; the regenerated plans and bindings of the earlier scenarios (plan version 6); the regenerated session recordings (§12.5); the new checks of §13 | + `skillroy-vision` (test scope) |
| `samples/skillroy-sample-web-host*` (profile `jcef`) | the pointer-event counter on the host's page and the kit page; kit items 15-18; `WebHostChecks` item 1 flipped and a new item over both paths (§4.9); its `plan/*.plan.json` regenerated for version 6 (VC's script, nothing else in that tree) | unchanged |

Maven coordinates `ai.skillroy:skillroy-vision`, the reactor's version (B2). Root `pom.xml`: `controller/skillroy-vision`
after `controller/skillroy-telemetry` in the module list. The dependency direction `skillroy-vision -> skillroy-inference
-> skillroy-scenario` is the design's (§3.1, §3.10); nothing below `skillroy-vision` names a vision type.

### 2.2 Versions

Nothing new. The Anthropic Java SDK stays at 2.70.0 (P5 §2.2, D88): its non-beta `com.anthropic.models.messages`
package carries the typed toolset classes (`ComputerToolset20260801`, `ComputerToolsetConfigs`,
`ToolUseBlockParam.Builder.toolsetName`, `ToolResultBlockParam.Builder.toolsetName`, `contentOfBlocks` with
`Content.Block.ofImage`, `ImageBlockParam.Builder.cacheControl`, `MessageCreateParams.Builder.cacheControl`,
`MessageCountTokensParams.Builder.addTool(ComputerToolset20260801)`; map 4 §1.9, BS12 RESULT "How it was run"), so no
raw JSON and no beta header is needed for the toolset. The JDK stays the controller's image library. Plan version 6,
bindings file version 2, script library version 2, fingerprint format 1, recording format 1, protocol 0.2.

### 2.3 Build conventions

As P6 §2.3: Java 8 language and API with no third-party class in `agent/**` and the two Java 8 sample modules, Java 25
in `controller/**` and the two Java 25 sample modules; the Apache-2.0 header on every new file (`ci/license-headers.sh`);
no `mvn install`; every display test skips when headless and runs with `ci/run-in-linux.sh`; the lending vocabulary
only. Four implementers build in parallel worktrees under the tags **VS** (the agent's Swing side, `agent-core`'s
option, `agent-dist`'s test, the sample's desk, agent module and pack), **VW** (the web side, the CDP path, the web
host), **VC** (the scenario, controller, JUnit and CLI side: `run`, `caps`, the glue) and **VM** (the gateway, the
session seam, `skillroy-vision`, the hosts' session options, the MCP options, the sample's vision scenario); the work
packages, the file ownership, the merge rules and the integration order are in the session notes' `contract-notes.md`.
Each writes "As built" notes under its tag and never rewrites another's passage; the orchestrator merges by tag and
numbers the decisions (a tag and a number in each report) from D359 at integration (done on 2026-10-10: D359-D430, §17),
and assigns any further amendment id from A67 (never an implementer).

### 2.4 Threads and bounds

The agent's rules are unchanged (P1 §5, §6; design §3.7): every read is one bounded `EDT_SYNC` task with the call's
remaining time, every action is posted and the call returns once it is queued, no wait lacks a deadline, nothing runs
on a thread the application forbids, and the dispatcher's timeout (120 s cap) is the backstop. Phase 7's additions
and their bounds:

| Where | Bound |
|---|---|
| `swing.capture` | one EDT read for the paint, the masks, the carets, the marks' geometry and the window geometry; PNG encoding off the EDT; one raster per call, at most 4096 pixels a side after `scale`, and never larger than an eighth of the JVM's heap headroom (`maxMemory() - (totalMemory() - freeMemory())`, read before the allocation, so that a heap that may still grow towards `-Xmx` is not refused an ordinary capture; a larger one is `INVALID_PARAMS` naming the needed and the free megabytes); an `OutOfMemoryError` around the allocation or the paint is caught and answered `INTERNAL_ERROR`, never left on the EDT; a `SCREEN` composite of at most 32 windows; at most 200 marks; at most 64 redacted rectangles reported (more are blanked but counted in `redactedTruncated`); `focus.ownerLocator` is the first candidate of §3.2's order that matches exactly one component, found under the locator regex budget, never all six; (Phase 7 security fix, S125, built 2026-10-11, SG) **the encoding is counted too**: after the off-EDT encoding, the PNG is refused when six times its size (what the base64 text, the reply's JSON text and its bytes allocate beside it; two bytes a character on a Java 8 target) is more than half the heap headroom read then (`INVALID_PARAMS` with `neededMb`, `freeMb` and `reason: "encoding"`, "lower 'scale' or capture a smaller scope"), and an `OutOfMemoryError` of the encoding is answered `INTERNAL_ERROR` (the raster's bound counted the raster alone, and a high-entropy image encodes to about the raster's size: about ten times the raster was allocated beside it for a 4096² capture, L2-S4); a `ROBOT` capture's PNG meets the same bound; at most 32 `covered` rectangles per surface (§3.1; more become one, the whole surface) |
| `swing.hitTest` | one EDT read; at most 6 locator candidates, each checked with the engine under the locator regex budget (P1 §6); covering windows: at most 32 |
| `swing.pointer` ROUTED | one EDT read (the pre-check), then one `invokeLater` task per event, at most 100 events per call (a drag of at most 50 steps, a wheel of at most 50 notches), each task checking the call's `cancelled` flag and the modal state first (§3.3), then one bounded EDT read for the result; a press that opens a modal dialog is never awaited beyond that read's deadline |
| `swing.pointer` ROBOT, `swing.capture` ROBOT | Robot on `WORKER`, never the EDT (`waitForIdle` throws there, vision §F6); the delivery probe waits at most 1000 ms for the native `MOUSE_MOVED` (BS9 RESULT "What a `ROBOT` rung and the V3 tier need"); a press and its release in one worker task with no wait between them, the release in a `finally` that runs on a timeout or an interruption too, before the pointer is restored (D343); the press observation at most the call's remaining time; a screen capture at most 4096 pixels a side, and under `swing.capture`'s heap bound: the grab and, when the toolkit's image needs one, its conversion copy are each refused beyond an eighth of the heap headroom read before it (`INVALID_PARAMS` with `neededMb` and `freeMb`), an `OutOfMemoryError` of either is answered `INTERNAL_ERROR`, and the outside is blanked by filled rectangles, not a pixel walk (fix wave (Phase 7 review, FA, 2026-10-10): the glass capture had neither bound, L2-S3); (Phase 7 security fix, S126, built 2026-10-11, SG) the grab's bound counts what `Robot` allocates at the screen's UI scale `s` (read, as `Robot` reads it, from the default configuration of the screen whose bounds hold the rectangle's centre): `4 w h` bytes at scale 1, and `4 (ceil(w sx) ceil(h sy) + w h)` bytes, about (s² + 1) times the raster, at another scale (the device-space pixels and the user-space image beside them), checked before the port is looked up; a non-default screen's Robot is constructed on an agent worker within the call's remaining time (S121, §3.4) |
| `swing.rendering` | one EDT read; the probe render is a fixed 320 x 120 off-screen panel |
| `CaptureMask`, `RegionResolver`, `SurfaceSource`, `LocatorResolver.describe` | called on the EDT inside the capture's, the snapshot's or the hit-test's read; must be fast and lock-free; a throwing implementation is logged once and ignored (as `NameEnricher` is, P1 §4), a throwing `CaptureMask` blanks the component |
| `web.hitTest`, `web.pointer` | `WORKER`, 10 s; a pointer `CLICK` is move, press and release awaited in sequence under the call's budget; iframes descended to depth 3; a mutating call is never retried and never re-sent on the shared JCEF client (P4 §4.2, D62) |
| The loop (controller) | one model call per iteration under `timeouts.callMs`; the budget reserved before the call (D74); the step protocol's waits (`sync.await`, `swing.settle`) with the step timeout; a model's `wait` is a settle, never a sleep (vision §R1; BS12 RESULT "Sessions": `wait` was answered by the settle 97 times); at most 30 UI actions and 20 model turns per session, enforced by the service (§10); the marks drawn on the controller's copy of the raster (Java 25 `java.awt.image`) |
| Image comparison (controller) | decoded rasters of at most 4096 x 4096; the exact hash first, a pixel walk only on a mismatch, for the report |
| `InputVeto` (Phase 8, built 2026-10-19, EA; P8 §3.7) | EDT only, inside the call's read or posted task; at most 64 ancestors per ask (deeper refused), memoised per call and route; a throwing veto refuses; the text one line of 200 characters; no new timeout |
| `Visibility` (Phase 8, built 2026-10-19, EA; P8 §3.7) | EDT only; O(depth) per component, O(nodes) per walk (each area computed from the parent's, memoised for the call) |
| `HitTest.sourceAt` (Phase 8, built 2026-10-19, EA; P8 §3.7) | one `getDeepestComponentAt` and one walk up, inside the existing pre-check read |
| `swing.pointer` ROUTED (Phase 8, built 2026-10-19, EA; P8 §3.7) | at most **101** events per call (the 100 above plus the exit to the previous source); each task checks `cancelled`, the modal state and the per-event veto (asked outside the state's lock); the balance into a heavyweight leaf adds at most one `MOUSE_EXITED` |
| `Clicks.semantic` (Phase 8, built 2026-10-19, EA; P8 §3.7) | one dispatched `ActionEvent`/`ItemEvent` inside the posted task |

### 2.5 What a target JVM sees

Unchanged in kind (P6 §2.5): the agent home's `lib/` and `modules/` jars hold project classes only; `optional/` holds
the hooks and Byteman modules and is never scanned. Phase 7 adds no jar and no third-party class (D290: the Robot
implementation is JDK API inside `skillroy-agent-swing`). What a target JVM may newly do, each behind a gate:
construct a `java.awt.Robot` (only when the agent option `robot=true` is set and AWT is confirmed started by the
application, A4; never at install, never at probe time before `checkAwt`; `RobotInput` is loaded lazily behind
`RobotFactory`, so without the option the class is never loaded), move the real pointer (only under a `via: ROBOT` call
that passed the double opt-in), dispatch window-sourced mouse events on the EDT (only under `swing.pointer`, posted,
bounded). `HomeConstantPoolIT` and `DistributionContentIT` keep guarding the home.

### 2.6 What the CLI distribution carries

`lib/skillroy-vision.jar` beside `skillroy-telemetry.jar`; `THIRD-PARTY.txt` unchanged (no new dependency);
`bin/skillroy` and `bin/skillroy.bat` unchanged; the help texts of `session`, `run`, `mcp` and `caps` gain the options
of §9.3.

---
## 3. The agent's Swing side (`skillroy-agent-swing`, Java 8)

Every primitive of this section is registered in `SwingPrimitives.all()` in name order, declared with
`PrimitiveSpec.builder`, runs on `ThreadPolicy.CALLER` and hops to the EDT itself as the module's other primitives do
(P1 §6; map 1 §1.2). Parameters are read through `JsonView`; an unknown parameter is ignored, as everywhere; a known
one with a wrong value is `INVALID_PARAMS` with `data.param`. Coordinates are user-space pixels, as every `bounds` of
`swing.snapshot` is; the three coordinate spaces are named in §3.1. Nothing here names a model.

### 3.1 `swing.capture`, extended (A61; built since Phase 1, guarded since Phase 4)

**Decision (D287, D288).** `swing.capture` keeps its name, its capability `swing.capture.paint`, its default mode and
its four result fields, and gains the reserved parameters and result fields additively. Two things about the built
behaviour change, both as amendment A61: **a window is painted from its root pane** (BS9 RESULT "The capture
comparison": `XFramePeer.print` draws imitation Motif decorations, a 17-pixel title bar from y = 6 and frame lines, at
fixed positions into every `printAll` of a frame on the X toolkit, which with the WM-less display's insets of 0 lie on
the content; BS12 RESULT finding 1: the desk's prompt and status lines were half covered in every frame capture so far;
a root-pane `PAINT` and a `ROBOT` capture are pixel-identical on Linux, 0 differing pixels in 23 of 24 comparisons;
this closes gap 76 of `../TODO.md`), and **the capture is redacted by default** (`redact: true`; §3.6). What depended on
the old pixels: nothing committed (no baseline existed before this phase), and nothing compares them; what shows them:
the failure evidence `failure/capture.png`, the artifact `artifacts/capture.png`, the MCP server's `mcp/<n>.png` and the
planner's envelope (P1 §8.5, P5 §9.4). From A61 on those show the content without the imitation decorations, with the
image's size the root pane's (equal to the window's on the WM-less display; 1022 x 743 instead of 1024 x 768 under
openbox, whose insets are 20, 1, 5, 1: BS10 RESULT "What was run", legs `wm-*`), and with a password field or a
pack-named component blanked. P4 §2.5's part-by-part guard and the `skipped` field are unchanged.

**Parameters.**

| Parameter | Type | Default | Meaning |
|---|---|---|---|
| `target` | ref, or a locator object | the active window, else the topmost modal dialog, else the first showing window (`Windows.defaultWindow`, unchanged) | what to capture; a locator object is accepted beside a ref from Phase 7 on (`TargetSpec.parse(a, false)`), as `by` is |
| `by` | locator object | – | the target by locator (instead of `target`) |
| `mode` | `PAINT` (default), `ROBOT` | `PAINT` | `PAINT` is `printAll` into an image on the EDT, as built; `ROBOT` is `Robot.createScreenCapture` of the target's screen bounds on a worker, **a person's evidence only** (§3.4; capability `swing.capture.robot`; refused for every other origin, §9.1, §9.2) |
| `scope` | `WINDOW` (default), `COMPONENT`, `SCREEN`, `REGION` | `WINDOW` when the target is a window, `COMPONENT` when it is not | what the image covers (below) |
| `region` | `{x, y, w, h}` in the target's own coordinate space, or a string: a region id a `RegionResolver` resolves on the target (§3.7) | – | with `scope: REGION`: the rectangle to capture; refused with another scope (`INVALID_PARAMS`); a rectangle that lies outside the target is clipped, an empty intersection is `INVALID_PARAMS`; an unknown region id is `NOT_FOUND` with `data.region` |
| `scale` | number, 0.25 to 4 | 1 | image pixels per user-space pixel: below 1 a downscale, above 1 a zoom (`Graphics2D.scale`, the one place device pixels enter); the 4096-pixel cap of each side and the heap bound of §2.4 still apply and lower the effective value, which the result reports |
| `redact` | boolean | **true** | blank the components the built-in rule and the registered `CaptureMask`s name (§3.6); `false` is an explicit opt-out, which a model or an MCP client never gets to give (§9.1, §11) |
| `annotateRefs` | boolean | false | answer `marks`: the geometry and the labels of the components a mark would stand on (below). **The agent draws nothing** (D346): the controller draws the marks on its own copy of the raster before an image goes to a model, so one paint, one raster, one `hash` of the content |

**What each scope paints**, all in `PAINT` mode (for `ROBOT` see §3.4):

- `WINDOW`: a `RootPaneContainer` (every `JFrame`, `JDialog`, `JWindow`) is painted from its root pane
  (`getRootPane().printAll`), so the image is the root pane's size and holds no decoration, real or imitated; a plain
  `java.awt.Window` or `Frame` without a root pane is painted as before (`printAll` of the window). `space: WINDOW`,
  `window: <ref>`, `origin: {x, y}` = the root pane's location in the window's coordinate space (the insets; `{0, 0}`
  on a display without a window manager and for a dialog without decorations).
- `COMPONENT`: the component painted as before (`printAll`, its own size); when the component is a `RootPaneContainer`
  the root pane is painted (as `WINDOW`). `space: WINDOW`, `window` = its window's ref, `origin` =
  `ComponentView.windowOffset(component)`: the image's pixel (0, 0) lies at that point of the window's space, which is
  the space of every non-window `bounds` in `swing.snapshot` (P1 §6).
- `REGION`: the rectangle painted through a clipped and translated graphics of the target's paint (`Graphics2D.create(x,
  y, w, h)`); for a `RootPaneContainer` target the root pane's paint, translated by `origin`, so that no imitation
  decoration enters a region either; `space: WINDOW`, `origin` = the rectangle's origin in window space; with a region
  id, the rectangle is `RegionResolver.rectOf(target, id)` and the result adds `region: {id, rect}`.
- `SCREEN` (capability `swing.capture.screen`; the composite of vision §F11 and research R2): every showing window of
  the JVM, each painted from its root pane at its content's screen position onto one canvas whose bounds are the union
  of the windows' screen bounds (not the whole screen), in the module's stacking order from bottom to top: windows
  neither active nor owned nor modal in creation order, then the active window, then owned windows above their owners,
  then the modal dialogs as `Windows.topmostModal` ranks them, last on top. Decoration bands keep the canvas's
  background. `space: SCREEN`, `origin` = the union's top-left in screen coordinates, `stackingAssumed: true` (AWT has
  no public z-order, vision §F8; another process's windows are never in it). This is the capture the vision loop sends
  (§6.2); BS12 composited the desk's frame and its prompt dialogs in this way (BS12 RESULT "How it was run").

**The mapping rule**, stated once: an image pixel `(px, py)` lies at the point `(origin.x + px / scale, origin.y + py /
scale)` of the result's `space`, which is the window named by `window` (`WINDOW` space) or the screen (`SCREEN` space).
`swing.hitTest {space: {captureId}}` applies it (§3.2).

**Result** (additive; `format`, `width`, `height`, `base64`, `skipped?` as built, `width` and `height` the image's own
pixels after `scale` as before):

| Field | Meaning |
|---|---|
| `captureId` | an opaque id unique per agent start (`"cap-<n>"`); the agent keeps the geometry (space, window, origin, scale, windows) of its last 32 captures for at most 10 minutes, so that a hit-test may name a capture instead of repeating its geometry; never the pixels |
| `seq` | the event bus position read on the EDT in the same task as the paint (`ctx.events().lastSeq()` after `tap.sync()`, as `swing.snapshot` does) |
| `mode`, `scope` | as used |
| `space` | `WINDOW` or `SCREEN` |
| `window` | the window's ref (`WINDOW`, `COMPONENT`, `REGION` scopes); absent for `SCREEN` |
| `origin` | `{x, y}`, where pixel (0, 0) lies in `space` |
| `scale` | the effective factor (the requested one, lowered by the 4096 cap and the heap bound) |
| `windows` | `[{ref, rect}]`: every window in the image, `rect` in image coordinates (one entry for the three single-window scopes, several for `SCREEN`) |
| `surfaces` | `[{kind: WEB \| HEAVYWEIGHT, rect, ref, browser?, redacted}]`: areas that are not Swing-painted, `rect` in image coordinates; `WEB` entries come from the registered `SurfaceSource`s (§3.7, the web module's index: `browser` is the browser's ref), `HEAVYWEIGHT` from the capture's own heavyweight walk (P4 §2.5) for components nobody claims; `redacted: true` when the surface's component is one a mask names or its rectangle intersects a `redacted` rectangle (the loop then never paints a page's pixels into it, §4.8); a part that threw is in `skipped` as before and in `surfaces` too (`skipped` is kept as built, D289); (Phase 7 security fix, S108, S129, built 2026-10-11, SG) a `WEB` entry also carries **`covered: [{x, y, w, h}]`**, its `rect` is the component's **visible part**, and it is listed only for a component that is **showing** (below); (Phase 7 security fix, S108 follow-up, built 2026-10-11, SG) a `WEB` entry also carries **`bounds: {x, y, w, h}`**, the component's whole rectangle in image coordinates, not clipped (it may extend beyond the image) |
| `marks` | with `annotateRefs`: `[{mark, ref, rect, label, role, row?, region?}]`: `mark` the number a drawn mark would carry (1 to n in reading order), `rect` the rectangle a mark stands on, in image coordinates (the component's, a table row's, a region's), `label` the text the marks list gives the model (role, name, text; nothing for a masked component, §3.6), `row` the model row index for a table row, `region` the region id for a `RegionResolver` region; the pixels are untouched |
| `carets` | `[{ref, rect}]`: for every showing, focused `JTextComponent` in the image whose caret is visible, the caret's rectangle (`modelToView` of the dot, widened to the caret's width; a selection has no caret entry), in image coordinates, computed in the same EDT read; the host's caret is never hidden (BS11 RESULT condition 1 and "Proposal" 2) |
| `focus` | `{owner: ref \| null, ownerLocator: <locator> \| null, ownerRect: {x, y, w, h} \| null, window: ref \| null, active: boolean}`: the focus owner, the first locator of §3.2's order that matches it alone (so that a baseline can record it across agent starts; bounded, §2.4), its rectangle in image coordinates (what a comparison blanks on a focus mismatch, §6.7), the focused window and whether the captured window (or, for `SCREEN`, the active window) is active (BS11 condition 2: under Aqua losing the focus repaints the focus ring, 9,625 pixels); (Phase 7 security fix, S146, built 2026-10-11, SG) `ownerLocator` is never a `{text, role}` candidate: the first unique candidate of §3.2's order without it (a nameless text field is named by `{class, index}` or `{path}`), since a text field's text is its content, which a baseline's or an anchor's `focus.owner` would carry into a committed bindings file, past the step's mask and even when the owner lies outside the image (the hit-test's own `locators` keep the candidate) |
| `redacted` | `[{ref, rect, rule}]`: the rectangles blanked, `rule` `password-field` or `capture-mask` (§3.6), or (Phase 8 security fix, S166, FE) `browser-mask`: the component of a surface its `SurfaceSource` reports `masked` (a masked embedded browser's view), blanked whole; `redactedTruncated: true` when more than 64 were blanked |
| `hash` | `sha256:<hex>` over the decoded raster the image in `base64` holds, the content: the width and the height as 4-byte big-endian integers, then every pixel's `getRGB` value (alpha included, `0xFF` for an opaque image) as a 4-byte big-endian integer, rows top to bottom (never the PNG bytes: the same raster encodes to different bytes on Java 8 and Java 25, BS11 RESULT condition 4); the controller's `ImageCompare.rasterHash` computes the same bytes and both modules' tests share one vector |
| `stackingAssumed` | `SCREEN` only: true |
| `region` | `REGION` with a region id: `{id, rect}` |

**The marks rule** (`annotateRefs`): one mark per component that would take a click or a key, in reading order (top to
bottom, left to right, by the component's image rectangle): every `AbstractButton`, `JTextComponent`, `JComboBox`,
`JList`, `JTree`, `JSlider`, `JSpinner`, `JTabbedPane` tab, `JMenuItem`, every `JTableHeader` and every visible row of a
`JTable` (one mark per row, `rect` the row's rectangle, `row` the model index), every custom component that has a
registered `ActionFirer` accepting it or a `NameEnricher` name, every `JLabel` with text, and every region of a
component a `RegionResolver` supports (one mark per region, `rect` the region's, `region` its id); **never a masked
component** (§3.6: it is grey and carries no mark); at most 200 marks, the rest left out and `marksTruncated: true` set.
The controller draws a mark (the vision module's `Marks`, §6.2) as a filled dark-blue box (RGB 31, 95, 168) with the
number in white, 12-pixel sans, inside the rectangle at its top-left corner; the capture's `hash` is of the content and
a comparison never sees a drawn mark (BS12 RESULT "Accuracy": marks gave 100 % on Sonnet 5.5 and Opus 5.5 where the text
snapshot gave 96 %).

**Errors.** As built, plus: `INVALID_PARAMS` for an unknown `mode`, `scope`, a `scale` outside 0.25-4, a `region` with
another scope than `REGION`, a raster beyond the heap bound; `NOT_FOUND` for an unknown region id;
`CAPABILITY_UNAVAILABLE` for `mode: ROBOT` without `swing.capture.robot` and for `scope: SCREEN` without
`swing.capture.screen`, checked by the body as `swing.click` checks a rung's capability (P1 §6).

**The pixels a model sees** (the rule the vision module follows, §6.2): `scope: SCREEN`, `mode: PAINT`, `redact: true`,
at the window's own size (`scale: 1`; a 1024 x 768 capture is 1,039 tokens, BS12 RESULT "Cost and tokens"), scaled
down only when the long edge exceeds 1920 pixels and never below 0.75 (BS12 RESULT "Failures": icons failed first at
0.5), `annotateRefs: true` for V1 and false for V2 and V3. Never `mode: ROBOT` (D341).

**As built (Phase 7, VS).** (a) The 4096 cap lowers the effective `scale`; the heap bound refuses
(`INVALID_PARAMS` with `data.neededMb` and `data.freeMb`) rather than lowering it, as §2.4 and §13.1 item 1 say (D384).
(b) The root pane replaces the painted component for a `RootPaneContainer` that is a **window** (`JFrame`, `JDialog`,
`JWindow`); a `JInternalFrame`, also a `RootPaneContainer`, keeps its own (real, Swing-painted) title bar in a
`COMPONENT` capture (D385). (c) A lightweight paint root (a root pane) has the heavyweights below it printed on top at
their place, since a Swing container's printing does not reach them. (d) The `SCREEN` canvas outside the windows'
content (decoration bands, gaps between windows) is RGB 64, 64, 64, distinct from the mask grey (D391); a mark whose
centre a window higher in the stack covers is left out. (e) Marks: the children of a `JComboBox` and a `JScrollBar`
(their arrow buttons) carry none, a `JTabbedPane` carries one per tab, a region resolver's regions replace the
component's own mark, and a label reads `role 'name': text`, one line, at most 120 characters (D390). (f) `carets` are
reported for every focused text component without a selection whatever the caret's blink phase, masked or not (the
rectangle a comparison blanks), widened by 2 pixels either side of `Caret.width` (D389). (g) For a target in no window
(the headless tests' trees) `origin` is relative to the topmost ancestor and `window` is absent.

**Phase 7 security fix, S108 and S129 (built 2026-10-11, SG): a surface is the showing component's visible part, with
what is painted above it.** A `WEB` surface (a registered `SurfaceSource`'s component) is listed only when the component
is **showing**: it and every ancestor up to its window are visible (`isShowing()` relative to the painted root, which
the window-list seam's stand-ins answer too), not when its own `isVisible()` is true alone, so a browser in a
`CardLayout` card that is not shown is no surface (L4-F1: it was listed at the rectangle its card would have, and the
loop asked for its bitmap; on JCEF the adapter refused it, a vendor or third-party adapter need not). Its `rect` is the
component's **visible part** in image coordinates: its bounds clipped by every ancestor's up to its window (what
`JComponent.getVisibleRect` computes, computed the same way for a heavyweight), so a browser a viewport scrolled partly
out is clipped to the viewport, and one scrolled wholly out (an empty visible part) is no surface at all (before, its
whole bounds lay over whatever the paint put below the viewport). It carries **`covered`**, a list of `{x, y, w, h}`
in image coordinates, each inside `rect`: the part of `rect` that every window **above** the surface's own in the
composite covers (the windows painted after it in a `SCREEN` capture: a modal dialog, an owned window), and, in its own
root, the part that every visible component **Swing paints above** a component of the surface's ancestry covers (a
sibling with a lower child index, which Swing paints later: the layered pane's popup, modal and drag layers above the
content layer, an in-window dialog in a higher layer, a visible glass pane), up to the painted root; more than 32 such
rectangles are answered as one, the whole `rect`. `covered` is additive: an older agent sends none, which the
controller reads as empty (§4.8). (Phase 7 security fix, S108 follow-up, built 2026-10-11, SG) Since `rect` is only the
visible part, a `WEB` entry also carries **`bounds`**, `{x, y, w, h}` in image coordinates: the component's whole
rectangle, clipped neither to the image nor to the visible part (its `x` and `y` may be negative, its far edges beyond
the image), which is where the page's bitmap lies; for a browser nothing clips, `bounds` equals `rect`. It is additive
too: without it the controller cannot place the bitmap of a browser a viewport clips (D475). A registered surface that is not listed (not showing, or scrolled out) is still
claimed, so the heavyweight walk never lists it as a `HEAVYWEIGHT` entry. `HEAVYWEIGHT` entries are unchanged (their
walk already tests visibility at every level, and nothing is drawn into them). Before, the surface's rectangle was the
component's whole bounds clipped to the image only, while the same class's marks took the visible part and dropped what
a higher window covers, so the composite painted the page over a modal dialog, an owned window, a popup or an in-window
dialog above the browser, and the model's click there landed on the hidden target control (L4-S1).

### 3.2 `swing.hitTest` (A63; read-only; capability `swing.hittest`)

**Decision (D292).** A point to the component under it, with every candidate locator checked to match exactly that
component, the point inside it, what AWT would deliver a mouse event to, the windows of the JVM that cover the point,
and the modal blocker; one bounded EDT read; nothing is changed. Not mutating, so read-only mode serves it. It also
describes a component named by ref or locator the same way (the point being the component's centre), which the recorder
needs for the focus owner (§6.5).

**Parameters.** Either a point or a target:

| Parameter | Type | Meaning |
|---|---|---|
| `point` | `{x, y}` | the point, in `space` |
| `space` | `{captureId: "cap-<n>"}`, `{window: ref}`, or the string `SCREEN` | the space `point` is in: a capture's image coordinates (the mapping rule of §3.1 applied by the agent; an id the agent no longer holds is `INVALID_PARAMS` with `data.captureId`), a window's own coordinates, or screen coordinates (`stackingAssumed: true`) |
| `target` / `by` | ref, or a locator object | instead of a point: describe this component; `point` is then its centre in its window's space |

**Result.**

| Field | Meaning |
|---|---|
| `window`, `windowRect` | the window the point is in (for `SCREEN`: the topmost window of the JVM at the point by the stacking order of §3.1) and its screen bounds |
| `covering` | `[{ref, rect, ranked}]`: every other showing window of the JVM whose screen bounds contain the point, with `ranked: true` when the module can place it above `window` with confidence (a modal dialog, a window owned by `window`, or the active window; vision §F8), else false (BS10 RESULT "What `swing.hitTest` must report") |
| `deepest` | `Node` (the shape of `swing.snapshot`, with Phase 7's `masked` flag, §3.6): `SwingUtilities.getDeepestComponentAt`, what is drawn there |
| `mouseTarget` | `Node` or null: what AWT's lightweight dispatcher would deliver a mouse event to, re-implemented with public API: the deepest visible component containing the point that has a mouse, mouse-motion or mouse-wheel listener, or is a `JComponent` with `getComponentPopupMenu() != null` or `getAutoscrolls()` (both call `enableEvents`, BS10 RESULT condition 3), searching siblings and then the container when a child does not qualify (vision §F7); `mouseTargetInferred: true` always, since the enabled-event-mask half of AWT's filter is not readable |
| `wheelTarget` | `Node` or null: the nearest ancestor of `mouseTarget` (itself included) with a mouse-wheel listener (BS10 RESULT: the list's scroll pane) |
| `target`, `targetKind` | the ref the rest of the result is about: `mouseTarget` when found (`MOUSE_TARGET`), else `deepest` (`DEEPEST`) |
| `local`, `relative` | the point inside `target`: `{x, y}` in its own pixels and `{fx, fy}` as fractions of its bounds (0 to 1) |
| `row` | for a `JTable` target the model row index under the point (`rowAtPoint`, then `convertRowIndexToModel`), for a `JList` the model index (`locationToIndex`), else absent; what a table-row mark and the recorder's `pointer {row}` use (§6.5) |
| `locators` | candidates for `target` in authoring preference (design §3.7), each checked with `LocatorEngine.find` over every showing window to match exactly this component, at most 6: first every entry of every registered `LocatorResolver.describe(target)` (strategy to value; §3.7), then `{id}`, `{name, role}`, `{text, role}` (never for a masked component), `{class, index}`, `{path}` last; an empty list when nothing matches exactly one |
| `fires` | true when a registered `ActionFirer.canFire(target)` accepts it (the recorder's "a custom component the pack can fire", §6.5) |
| `region` | `{id, rect, resolver}` when a registered `RegionResolver` supports `target` and `regionAt(target, local)` answers; `resolver` the implementation's class name |
| `surface` | `SWING`, `WEB`, `HEAVYWEIGHT` or `NONE`: `WEB` when a `SurfaceSource` claims `target` or an ancestor (with `browser`, the browser's ref, and `surfaceLocal: {x, y}`, the point in the claimed component's own pixels, for `web.hitTest {space: COMPONENT}`, §4.8), `HEAVYWEIGHT` for an unclaimed heavyweight, `NONE` when the point is in no component |
| `enabled`, `listening` | `target.isEnabled()` (a disabled component receives a press and does nothing, BS10 RESULT case "disabled button"), and whether `mouseTarget` was found |
| `masked` | true when `target` is a `JPasswordField` or a registered `CaptureMask` names it (§3.6): the recorder never types into, presses a key at, or clicks such a component (§6.5, §11) |
| `blockedBy` | `{ref, title, inWindow?}` when a modal dialog, or an in-window dialog a `DialogLocator` reports, blocks `target` (`Windows.requireNotBlocked`'s rule, P1 §6 A8/A27) |
| `stackingAssumed` | true for `SCREEN` space and for every `covering` entry with `ranked: false` |
| `seq` | the bus position at the read |

**Errors.** `NOT_FOUND` with `data.reason: "outside"` when the point lies in no showing window of the JVM (`SCREEN`) or
outside the named window or capture; `INVALID_PARAMS` for a malformed point, an unknown space form or capture id;
`STALE_HANDLE`, `NOT_FOUND`, `AMBIGUOUS` for the target form as for every locator (P1 §6).

**As built (Phase 7, VS).** The target form answers the named component as `target` with `targetKind: "TARGET"`
(`deepest` and `mouseTarget` at its centre are reported beside it), so that the recorder's description of the focus
owner is about that component (D388). `ranked` places `other` above `window` when `other` is owned by `window`, a modal
dialog over a window that is not modal (or ranked higher among modal dialogs by the stacking order), or the active
window, and never when `window` is owned by `other`. A `{text, role}` candidate is proposed for a text of at most 100
characters on one line. The capture geometry a `captureId` names is kept by `RecentCaptures`, the window held weakly.

**Fix wave (Phase 7 review, FC, 2026-10-10):** the target form also answers **`targetNode`**, the named component's own
`Node` (additive; the point form answers none), so that a reader of the target form (the recorder, for `click_ref` and
the focus owner, §6.5) takes the component's role, class and name from the component itself and not from what is drawn
at its centre (a label inside a composite button; review finding L1-F5, D446). `deepest` and `mouseTarget` keep their
meaning: what lies at the centre.

**Phase 8 (built 2026-10-19, EA; P8 §3.2 rule 7, §3.4 rule 6, A68, A70).** The result gains **`vetoed`** (always,
right after `masked`: true when a registered `InputVeto` refuses input on some route, asked with route `ANY`, to the
target or the deepest component, either chain) and **`source`** (right after `surface`: the ref of the routed event's
source, the window or the innermost showing heavyweight on the containment path at the point that no `SurfaceSource`
claims). The mouse target is searched below that heavyweight as its own lightweight dispatcher would: its lightweight
children, the heavyweight itself when it listens; a heavyweight leaf (an AWT `Button`, `Checkbox`, `Canvas`) is its
own mouse target when it listens or is an AWT control whose peer acts on a press (`Button`, `Checkbox`, `Choice`,
`List`, `Scrollbar`, `TextComponent`). `surface` keeps its meaning (`HEAVYWEIGHT` still anchors the recorder's step,
whose replay now reaches inside); `targetNode` is unchanged; HW/LW mixing cut-outs are not modelled. The target form
refuses a collapsed component (`INVALID_PARAMS collapsed`, P8 §3.3).

### 3.3 `swing.pointer` with `via: ROUTED` (A63; mutating; capability `swing.pointer.routed`)

**Decision (D293, D294, D352).** A pointer action at coordinates, delivered as a `MouseEvent` whose source is the
heavyweight window, in window coordinates, dispatched non-posted on the EDT from one `invokeLater` task per event, so
that AWT's own `LightweightDispatcher` picks the target, tracks enter and exit and retargets (vision §F7). BS10 RESULT:
this gave Robot's outcome in 14 of 16 cases on Java 8 and Java 25, WM-less and under openbox, arm64 and amd64, and the
same 16 outcomes on macOS where Robot delivered nothing; the two cases that differ, differ by construction and are the
two checks below. The built `DISPATCH` rung is unchanged and stays what it is (a click at a component's centre, blind to
position, glass panes and hover; BS10 RESULT "Per case").

**Addressing**, one of two forms:

| Form | Parameters | Meaning |
|---|---|---|
| by point | `point: {x, y}`, `space` as in §3.2 (`{captureId}`, `{window: ref}`, `SCREEN`) | the point in that space; `to: {x, y}` for `DRAG`'s end |
| by component | `target` or `by` (ref or locator), `region?` (a region id), `row?` (a table's model row or a list's model index), `at?: {fx, fy}` (fractions of the component's, the region's or the row's bounds; default `{0.5, 0.5}`), `to?: {fx, fy}` for `DRAG` | the agent resolves the component on the EDT, the region's rectangle through the `RegionResolver` (§3.7) or the row's through `getCellRect`/`getCellBounds` (`convertRowIndexToView` first), and the point from the fractions; this is the replay form of a recorded step (`pointer`, §8.1), so that no pixel is stored |

**Action parameters.**

| Parameter | Values | Default | Meaning |
|---|---|---|---|
| `action` | `MOVE`, `PRESS`, `RELEASE`, `CLICK`, `DOUBLE_CLICK`, `DRAG`, `WHEEL` | required | what to deliver (`PRESS` and `RELEASE` are routed only: `via: ROBOT` refuses them, §3.4) |
| `button` | `LEFT`, `MIDDLE`, `RIGHT` | `LEFT` | the button (`BUTTON1`, `BUTTON2`, `BUTTON3`) |
| `modifiers` | any of `SHIFT`, `CTRL`, `ALT`, `META` | none | extended modifier masks on every event |
| `clickCount` | 1 to 3 | 1 (`DOUBLE_CLICK`: 2) | the click count of press, release and click |
| `wheel` | `{notches: int}` | – | `WHEEL`: notches, positive towards the user (down); at most 50 |
| `steps` | 1 to 50 | 6 | `DRAG`: the number of `MOUSE_DRAGGED` samples between press and release |
| `via` | `ROUTED`, `ROBOT` | `ROUTED` | the path (§3.4 for `ROBOT`) |
| `force` | boolean | false | act although a modal dialog blocks the window or a ranked window covers the point; a person's argument only (§9.1) |
| `expectTarget` | ref | – | refuse (`INPUT_REJECTED`, `data.reason: "target changed"`, with the hit-test result) when the pre-check's `target` is not this component; for a raw call (the loop's live-only halves, an MCP client's call): a recorded step cannot carry a per-start ref (§6.4, D352) |

**The routed sequence** (BS10 RESULT "What `swing.pointer` ROUTED must do"), each event its own `invokeLater` task so
that the EDT runs what the previous event queued (a popup shown by a press) before the next:

1. a pre-check in one bounded EDT read: the hit-test of §3.2 at the point (its result is in the pointer's result as
   `hit`); `MODAL_OPEN` when `blockedBy` is set, unless `force` (AWT's modal filter stops posted events only; a
   window-sourced event dispatched non-posted reaches a blocked window, BS10 RESULT condition 1, swing §F14);
   `INPUT_REJECTED` with `data.reason: "covered"` and `data.covering` when a `covering` entry is `ranked: true`, unless
   `force` (routing cannot see a window on top, BS10 RESULT condition 2); an unranked covering window is reported, not
   refused; `expectTarget` checked;
2. `MOUSE_MOVED` to the point first when the window's last routed position differs (it drives enter, exit, rollover and
   tool tips through the lightweight dispatcher; the module keeps the last routed position per window, weakly);
3. for `PRESS`, `CLICK`, `DOUBLE_CLICK`, `DRAG`: `MOUSE_PRESSED` with the button's down mask, the click count and
   `popupTrigger` set on the press for button 3 on X11 and macOS; then for `RELEASE`, `CLICK`, `DOUBLE_CLICK`:
   `MOUSE_RELEASED` with the remaining buttons' mask and, for button 3 on Windows, `popupTrigger` on the release; then
   `MOUSE_CLICKED` with the same count, which `DRAG` never sends (the native release after a drag has click count 0 and
   no click follows, BS10 RESULT "What the event logs show");
4. `DRAG`: the press, `steps` `MOUSE_DRAGGED` events with the button's down mask along the straight line to `to`, the
   release at `to` with click count 0;
5. `WHEEL`: one `MouseWheelEvent` per notch, `wheelRotation` 1 or -1, `WHEEL_UNIT_SCROLL`, `scrollAmount` 3 (X11's
   value; the Mac's native amount was not observed, BS10 RESULT);
6. `MOVE`: the move alone;
7. **every task checks two things before it dispatches** (D352): the call's `cancelled` flag, set when the call's
   deadline passes (the built `Clicks` pattern: no event of a call that has failed is delivered later, as
   `HostSafetyDisplayTest` asserts), and `Windows.blockingModal(window)` again (a press handler that shows an
   application-modal dialog runs the dialog's secondary loop inside the press's own task, and that loop would pump the
   remaining non-posted events into the now-blocked window); a task that finds either set does nothing, **with one
   exception that cannot act**: a press already dispatched is balanced by one routed `MOUSE_DRAGGED` to the window
   point (-1, -1), outside every component, which makes the lightweight dispatcher deliver `MOUSE_EXITED` to the
   pressed component (`BasicButtonListener` disarms the model on exit; the desk's keys check `contains(point)` on the
   release), then a `MOUSE_RELEASED` at that point with click count 0 and no `MOUSE_CLICKED`; Swing acts on the
   release, not on the click, so a bare release would have fired the action late: this one fires no `ActionEvent` and
   leaves no model pressed; the result says `balanced: true` (D352; the built `Clicks` has no press to balance);
8. afterwards one bounded EDT read collects the result; an `AWTEventListener` registered for the call's duration (mouse
   events, the window's own) records the component that received `MOUSE_PRESSED`.

The real pointer is never moved (components that read `MouseInfo` see the person's pointer). A retargeted button-3 event
carries AWT's `META` bit in its extended modifiers natively and routed alike (BS10 RESULT); a primitive or a pack that
compares modifiers expects it.

**Result.**

| Field | Meaning |
|---|---|
| `via` | `ROUTED` |
| `window` | the window the events were sourced from |
| `hit` | the pre-check's `target`, `targetKind`, `deepest`, `enabled`, `masked`, `covering` (§3.2) |
| `fired` | true when every event of the sequence was dispatched on the EDT within the call's budget; false otherwise, with `reason` (`EDT_BUSY`: the deadline passed, the remaining tasks are cancelled and nothing of this call acts later, a dispatched press being disarmed and released as step 7 says; `MODAL_OPENED`: a modal dialog opened mid-sequence, the remaining events were withheld and the press was balanced the same way into the now-blocked window, where the disarmed release fires nothing) |
| `dispatched`, `balanced` | `{sent, of}`: how many events of the sequence were dispatched, of how many; `balanced: true` when a cancelled or withheld sequence's press was disarmed and released (step 7) |
| `received` | the ref of the component that received `MOUSE_PRESSED`, or null when nothing did (the point lies in no listening component, or a disabled component's delegate took it: `received` is then that component and `hit.enabled` false); for `MOVE` and `WHEEL`: the component that received the move or the wheel |
| `focusOwner`, `activated` | the focus owner after the sequence, and whether the window became the focused window because of it (BS9 K6: Swing's press handlers ask for the focus and the toolkit activates the window) |
| `seq` | the bus position after the result read |

"Fired" is honest in the same way the rungs are (P1 §6, "fired means delivered"): a routed press on the sample's
`IconKey` in an unfocused window is received and dropped by its debounce (map 1 §5.4), so the recorder reads the
application's events, not `fired`, for the consequence (design §2.5; §6.5). The executor's A29 rule applies to the
`pointer` verb (`fired: false` fails the step).

**Errors.** `MODAL_OPEN`, `INPUT_REJECTED` (reasons `covered`, `target changed`), `NOT_FOUND` (`outside`, an unknown
region or row), `INVALID_PARAMS` (a malformed point, an unknown action or button, more than 50 steps or notches, `to`
without `DRAG`, `region` or `row` with a point form), `STALE_HANDLE`/`AMBIGUOUS` for the component form,
`CAPABILITY_UNAVAILABLE` for `swing.pointer.routed` (AWT not started, headless) and for `via: ROBOT` without
`swing.pointer.robot`.

**As built (Phase 7, VS).** (a) `DOUBLE_CLICK` is two press-release-click triples with click counts 1 and 2, BS10's
native sequence; `CLICK` with `clickCount: n` is one triple of count n (D386). (b) `dispatched.sent` counts the events
whose dispatch **returned**: a press whose handler blocks the EDT or runs a modal dialog's loop is not counted
(§13.2 item 11's "dispatched 1 of 4"); on an `EDT_BUSY` result `balanced: true` is a promise, kept by the first task the
EDT runs after the cancellation, since the call cannot wait for a blocked EDT (D387). (c) The call's
`AWTEventListener` recognises its own events, retargeted ones included, by a `when` unique to the call; the window
itself counts as `received` only when it listens (D400). (d) The per-event modal re-read is skipped under `force`.

**Phase 8 (built 2026-10-19, EA; P8 §3.2-§3.4, A68-A70, D505, D512-D514).** (a) *Source.* The events' source is the
innermost unclaimed heavyweight at the point (§3.2's `source`), else the window; the point is converted to it, `xAbs`,
`yAbs` and the call's `when` unchanged; a drag keeps the press's source (the implicit grab), `to` converted into it;
a heavyweight a `SurfaceSource` claims (a browser's surface), or any component on the path to it, keeps the window as
the source, so page input stays on `web.pointer`'s checks. The result gains `source` after `window`. (b) *Step 1, the
pre-check,* asks the input veto first (route `swing.pointer.routed`, never skipped by `force`) for the hit's target and
deepest component, the component form's component and, for a drag, the target and deepest component at `to`:
`INPUT_REJECTED {reason: "vetoed", veto, ref, vetoed, route, hit}`; the component form refuses a collapsed component
(`INVALID_PARAMS collapsed`) and a point its component does not show, outside its strict visible rectangle
(`JComponent.getVisibleRect()`, else the effective area): `INPUT_REJECTED {reason: "not visible", ref, point, param:
at|to}`, the point in the component's own coordinates. (c) *Step 2, enter and exit.* The last routed position is kept
per source and the last source per window (both weakly); a call whose source differs from the window's last routed
source, which is still showing, starts with one `MOUSE_EXITED` to that source at its last point (one more event in
`dispatched.of`), then the move when the point differs from the new source's last position. (d) *Step 7, per event.*
After the `cancelled` and modal tests every task asks the veto for the component AWT will deliver the event to (while
a press of the call is down, the pressed one; otherwise the event source's mouse target at the event's point, else the
deepest component there), with a fresh evaluator, outside the call's state lock; a refusal stops the sequence
(`fired: false, reason: VETOED, vetoed: {ref, veto, route}`), the dispatched press balanced, `force` or not. Every event
is dispatched to its own source. (e) *Received* counts the source itself when it is a heavyweight leaf or listens.
(f) *The balance* into a heavyweight leaf dispatches the drag to (-1, -1), then one `MOUSE_EXITED` there (the X peers
disarm on exit), then the release with click count 0; into a container none is needed (its dispatcher delivers the
exit). Verified on the Linux image, Java 8 and Java 25 targets (`HeavyweightRoutingDisplayTest`, the tools leg).
**Errors** gain `INPUT_REJECTED` `vetoed` and `not visible`, and `INVALID_PARAMS` `collapsed`.

### 3.4 `via: ROBOT` and `mode: ROBOT`: the V3 tier's agent half (A63, A65)

**Decision (D290, D291, D341, D343, D344).** The Robot implementation lives inside `skillroy-agent-swing`
(`RobotInput`, package-private, loaded lazily behind the seam `RobotFactory`), JDK API only, constructed on an agent
worker after `checkAwt` has confirmed that the application started AWT (A4), one per `GraphicsDevice`, with
`setAutoWaitForIdle(false)` and `setAutoDelay(0)`, never on the EDT; the alternative, an optional module behind a new
agent-api SPI, is rejected in §17 (D290). It is reachable only behind a **double opt-in**:

1. **the agent option `robot`** (`robot=true`; default false; P1 §2.4): the operator's consent on the host that the
   agent may take the real pointer. Without it `swing.pointer.robot` and `swing.capture.robot` are set
   `UNAVAILABLE("robot: not enabled (agent option robot=true)")` at install and never probed (as `swing.click.robot` is
   today). With it, after `checkAwt`, both are declared with one probe (§3.9): `UNAVAILABLE("headless")` in a headless
   JVM, `UNAVAILABLE("robot: <the AWTException's message>")` when `new Robot(device)` throws (no XTEST, vision §F6),
   else `OK(strategy = "robot")`. The probe constructs the Robot and moves nothing.
2. **the controller's opt-in** (§9.2): `RunOptions.robot` and `SessionOptions.robot`, set only by a person's flag on
   the command (`--allow-robot` on `run`, `session`, `mcp` and on the raw `call` command, which bypasses `ExecutorSession` and
   `CallPolicy` and therefore refuses `via: ROBOT` and `mode: ROBOT` in its arguments without the flag, §9.3; the JUnit
   property `skillroy.robot`, which can sit in a committed build file, so the glue prints a one-line warning naming both
   halves whenever it is true), never a
   scenario key (D127's rule for `hooks`), never a tool argument a model chooses, never an MCP client's choice. Without
   it the executor and the session refuse a call that carries `via: ROBOT` or `mode: ROBOT` before it is sent (`ERROR /
   ENVIRONMENT`, "robot: not allowed; a person passes --allow-robot"), and a plan whose calls carry them is refused the
   same way before step 1 and before the agent is asked anything (its `requires` also names `swing.pointer.robot`,
   §8.1, which a CI target would fail next). With it, **a model or an MCP client still never chooses Robot**: `via:
   ROBOT` is refused for every raw tool call and every client's plan (`CallPolicy.arguments`, §9.1), and only the
   vision module's own V3 tools set it (D347); `mode: ROBOT` is refused for every origin but a person's by `CallPolicy`
   and by `ExecutorSession` (D341).

**The delivery probe** (BS9 RESULT "What a `ROBOT` rung and the V3 tier need": on this Mac, without the Accessibility
permission, Robot was constructed without error and delivered nothing, pointer or key). Before the first `via: ROBOT`
action after agent start, and again after any action whose press was not received, the pointer moves the real pointer
to the target point and waits, bounded at 1000 ms, for the native `MOUSE_MOVED` to arrive at a component of the target
window (an `AWTEventListener`); nothing arriving is `CAPABILITY_UNAVAILABLE(swing.pointer.robot, "robot: native input
is not delivered (no event within 1000 ms)")`, and the capability is set `DEGRADED` with that reason until the next
reprobe, so that the matrix shows it. A probe that passes is remembered while actions keep being received. **The probe runs before the action's pre-check**,
never between it and the press: the focus and covering read of the pre-check is the last thing before `mousePress`, so
that it is never a second old. **Fix wave (Phase 7 review, FA, 2026-10-10; L2-F4):** the probe's own EDT read (the read
that finds its point) applies the pre-check's refusals first, so that nothing moves for a call they refuse: a point
outside the window (`NOT_FOUND outside`; a `DRAG`'s end point likewise), `requireFocus` (`INPUT_REJECTED not focused`),
the covering rule (`INPUT_REJECTED covered` unless `force`) and `expectTarget`; and when a modal dialog blocks the target
window, or under `force` another window of the JVM covers the point, the probe is skipped, since AWT drops its move into
a blocked window as it drops the press and a covering window takes it: the action's own observation then reports what
arrived (`NOT_DELIVERED`, `received elsewhere`) and the capability is not degraded. The pre-check read is repeated
unchanged right before the press. Before, a point outside the window, an unfocused JVM or a modal dialog made the probe
move the real pointer and fail by construction, setting `DEGRADED` and answering `CAPABILITY_UNAVAILABLE` instead of the
refusal. A probe that passes, like one that fails, puts the pointer back where it was (L2-F6), so that the action's own
save and restore return it to the person's location, not to the probed point.

**`swing.pointer {via: ROBOT}`.** The parameters of §3.3, with these differences:

- `action` is `MOVE`, `CLICK`, `DOUBLE_CLICK`, `DRAG` or `WHEEL`; **`PRESS` and `RELEASE` are refused**
  (`INVALID_PARAMS`, "a Robot press is never left held: use CLICK or DRAG"; D343). **`modifiers` are refused**
  (`INVALID_PARAMS`, `data.param: "modifiers"`; Phase 7 security fix, S124, built 2026-10-11, SG): the Robot path
  holds no key (V3 has no Robot keyboard in Phase 7, D345), so a modified click would be pressed plain and reported as
  the modified one; an empty list is no modifier. A press and its release run in one
  worker task with no wait between them; a `finally` releases every button the call pressed before anything else, on a
  timeout or an interruption too; **the pointer is never restored while a button is down**.
- `requireFocus` (boolean, default **true**; D344): the pre-check's EDT read also requires that the target JVM holds a
  focused window (`KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() != null`), and the worker's
  `mouseMove` and `mousePress` follow that read at once (a delivery probe, when one is due, runs before the pre-check,
  never between the read and the press); a JVM without one is `INPUT_REJECTED` with `data.reason: "not focused"` and
  nothing moves (BS9 README: the spike's own strict mode read the focus on the EDT before every press). The WM-less
  `RobotChecks` of §13.2 pass `requireFocus: false` explicitly (BS9 K7: a WM-less frame may legitimately hold no focus).
  The window of a few milliseconds between that read and the press is the residual A15 of `threat-model.md` records.
- The point is converted to screen coordinates (the window's `getLocationOnScreen` for `WINDOW` space; the component's
  for the component form); **a point outside every showing window of the target JVM is refused** (`NOT_FOUND`,
  `data.reason: "outside"`), and a point that **any** covering window of the JVM covers, ranked or not, is refused
  (`INPUT_REJECTED`, `covered`) unless `force`, since Robot presses whatever is really on top (BS9 RESULT case
  "covered": the covering window got it); other processes' windows cannot be seen, which the result says
  (`stackingAssumed: true`). A `DRAG`'s end point (`to`) is held to the same two rules in the same read
  (`NOT_FOUND outside` and `INPUT_REJECTED covered` with `data.param: "to"`), since the Robot releases the button
  there (fix wave (Phase 7 review, FA, 2026-10-10; L2-S2): it was checked only to lie in the same window).
  **(Phase 7 follow-up, gap 101, built 2026-10-11)** One window is left out of the Robot's covering rule, for the point
  and a drag's end point alike: a window that owns the target's window, while the target's window is the active window.
  An owner lies below the windows it owns, and the active window was raised when it was activated (on a display without
  a window manager nothing else keeps an owned window above its owner, hence the second condition); before, a prompt
  dialog over its own frame could not be clicked through the Robot without `force`. The hit-test still reports the owner
  under `covering` (`ranked: false`); every other covering window still refuses the point. No modal check: input into a blocked window is dropped by AWT as for a person (BS9 case
  "modal"), and the result reports that nothing arrived. (Phase 7 security fix, S104 and S105, built 2026-10-11, SG)
  "Showing" means **on the glass**: a point in a window the toolkit reports **iconified** (`Frame.getExtendedState() &
  Frame.ICONIFIED`, read on the EDT in the pre-check), or in a window such a frame owns directly or transitively, is
  refused `NOT_FOUND` with `data.reason: "iconified"`, and such a window is never a covering window; a screen point, and
  a `DRAG`'s end point, that **no screen device's bounds** hold (the default configuration's bounds of every
  `GraphicsDevice`, read in the same EDT task) is refused `NOT_FOUND` with `data.reason: "off-screen"` (`data.param:
  "to"` for the end point), since the platform moves the pointer to the nearest screen edge instead and the press would
  land there (below).
- On the worker: the pointer's location is saved (`MouseInfo`), `mouseMove` to the point, the native sequence
  (`mousePress`/`mouseRelease` with the button masks, a drag as `steps` moves, `mouseWheel` per notch), the `finally`'s
  release, then the pointer restored to the saved location; the result's `received` is the component that received
  `MOUSE_PRESSED` (the `AWTEventListener` for the call's duration), and **nothing received within the call's deadline
  is `fired: false, reason: "NOT_DELIVERED"`**, never a success that cannot be seen (BS9: macOS drops silently); a
  `received` that is neither `hit.target` nor inside it is `fired: false, reason: "received elsewhere"`, and (Phase 7
  security fix, S105) it clears the passed delivery probe as `NOT_DELIVERED` does, so the next action is probed again
  rather than pressed at once. `activated`
  reports a window the press activated.

**`swing.capture {mode: ROBOT}`** (capability `swing.capture.robot`; **a person's evidence**, D341): refused for every
origin but `PERSON` by `ExecutorSession` and for every model's or client's raw call by `CallPolicy` (§9.1, §9.2), never
offered by a vision tool, never written to the captures directory, never served over MCP, never an anchor or a baseline
(vision §F11). For a person: `Robot.createScreenCapture` of the target window's screen bounds (`scope: WINDOW`,
decorations included, `space: SCREEN`, `origin` = the window's screen location, `windows: [{ref, rect}]`), of a
component's or a region's screen rectangle (`COMPONENT`, `REGION`), or of the union of the JVM's showing windows
(`SCREEN`, the real stacking, so `stackingAssumed: false`); user-space size (on a Retina display the 2x capture scaled
to user space, so text differs from a `PAINT` by antialiasing, BS9 RESULT leg `mac-j25`: 8,718 pixels); **every pixel
outside the screen rectangles of the JVM's showing windows is blanked grey** (the desktop, the bands of a `SCREEN`
union), and the result carries `glass: true` with the sentence "pixels inside a window's rectangle may belong to a
window of another process that covers it"; `redact` applies by rectangle: the mask rectangles of every JVM window whose
screen rectangle meets the captured one (fix wave (Phase 7 review, FA, 2026-10-10; L2-S1): not only the target
window's, so that a sign-on dialog over the captured frame is blanked too; `windows`, `surfaces` and `carets` then list
what the image holds of those windows, `focus.active` stays the captured window's) are read on the EDT
before and after the grab and the union is blanked, and when the bus `seq` shows a `window.opened` or `window.closed`
between the two reads the capture is taken once more, and a second attempt that again sees a window event is answered
with `unstable: true`; `carets`, `focus`, `hash` as
in §3.1; `marks` never (`annotateRefs` is refused with `ROBOT`, `INVALID_PARAMS`).

**V3's keyboard** (D344, D345). Not through Robot in Phase 7 (§1). The loop reads, before every `type` or `key`, through
`swing.edtState` (`focusOwner`) and the hit-test's `target` form, whether the target JVM holds a focused window and
which component owns the focus:

- (a) the JVM holds a focused window and the focus owner is the intended field: `swing.type {mode: SET}` by the field's
  locator (which needs no OS focus), or `swing.key` at the focus owner;
- (b) the JVM holds a focused window and another component owns the focus: one `swing.pointer {via: ROBOT, action:
  CLICK}` into the field (safe: the JVM's windows are in front, BS9 K6, K8), then (a);
- (c) the JVM holds no focused window: **the loop stops the turn** (`QUESTION`, "the target lost the focus; bring it to
  the front and ask again", §9.1's `endTurn`); it never clicks to activate a window it cannot know to be uncovered
  (BS9 README's strict mode refused exactly this; K6 on the Mac "not run"). On a display without a window manager
  nothing restores a frame's focus after a modal dialog closes (BS9 K7; BS4); case (c) is then the normal case after a
  dialog, and the person brings the window to the front.

**As built (Phase 7, VS).** (a) The Robot code is `RobotInput` (the only class naming `java.awt.Robot`) behind the
interfaces `RobotPort` and `RobotFactory`; `RobotFactory.DEFAULT` loads `RobotInput` by name, and `RobotPath` holds the
logic. (b) The capability probe runs on an agent worker posted from `checkAwt`; in a headless JVM both ids are set
`UNAVAILABLE("headless")` there and then. (c) The delivery probe moves the pointer one pixel aside first when it already
sits on the point (a move to where it is produces no native event) and also accepts the `MOUSE_ENTERED` it causes;
it restores the pointer when it fails (D393), and since the fix wave (FA, L2-F6) when it passes too. (d) The press observation waits at most 3 s within the call's
remaining time (D392). (e) A glass capture ignores `scale` (user-space size, `scale: 1` reported), refuses a screen
rectangle over 4096 pixels a side, carries the limitation sentence as `glassNote`, and reports `surfaces` beside
`windows` (D396).

**Phase 7 security fix (built 2026-10-11, SG): the glass, the screens and the probe's order.**

- **S104, iconified.** `Window.isShowing()` is `visible` alone and does not change when a frame is iconified, so AWT's
  "showing" is not "on the glass". One predicate, `Windows.hidden(window)` (the window is a frame the toolkit reports
  `ICONIFIED`, or a frame so reported owns it, directly or transitively), refuses a Robot point in such a window
  (`NOT_FOUND iconified`, in the delivery probe's read too, so nothing moves), leaves it out of the covering windows of
  the hit-test and of a Robot point, and leaves it out of the module's stacking, so of the `SCREEN` composite (the model
  is never shown a minimised frame painted at its last position, nor a window it owns painted over the focused frame),
  of a `SCREEN` point, and of the glass capture's window rectangles (whose pixels inside a minimised frame's rectangle
  are another application's, now blanked grey as the desktop is). Snapshots, locators and the routed pointer are
  unchanged (a minimised frame's tree is still read and its events still dispatched). The undetectable neighbour, a
  window of the JVM on another virtual desktop or workspace, cannot be seen through AWT; `requireFocus` is the only
  guard there (§13.4).
- **S105, off-screen.** X11 keeps the pointer on the screen, so a Robot press at a point no screen shows landed at the
  nearest edge: on another component of the same window when the window spans the edge (and the delivery probe passed
  there), on whatever lies at the edge otherwise. The pre-check now requires the screen point, and a drag's end point, to
  lie in some screen device's default-configuration bounds (`NOT_FOUND off-screen`), and a press `received elsewhere`
  is re-probed before the next one. macOS (`CRobot` posts the button event at the requested point while the cursor sits
  at the clamped one) is undetermined and Windows unverified (§13.4); the refusal covers both.
- **S121, the probe's port.** The delivery probe obtains its port before it adds its listener, so a port that cannot
  be had leaves no listener on the toolkit (before, one leaked per failed call, holding the target window for the JVM's
  life). As built (a) amended: the default screen's Robot is constructed by the capability probe on its worker; a Robot
  for another `GraphicsDevice` is constructed on demand **on an agent worker** (`ThreadPolicy.WORKER`, the call's
  remaining time), never on the call's thread, and cached per device.
- **S124, modifiers** (the differences list above) and **S126, the grab's bound at a UI scale** (§2.4's Robot row).

**Phase 8 (built 2026-10-19, EA; P8 §3.2 rule 2, A68).** `check` asks the input veto (route `swing.pointer.robot`,
never skipped by `force`) for the hit's target and deepest component, the component form's component and a drag's end
point, after the on-a-screen tests and before `requireFocus`; it runs in the delivery probe's read and again right
before the press, so a vetoed point, and a drag ending on one, are refused before anything moves
(`RobotPathDisplayTest`, `os.calls` empty). Delivery is unchanged; the hit-test's heavyweight search makes `target` the
component inside a heavyweight `Panel`, which `received elsewhere` compares against.

### 3.5 `swing.type` with an omitted target (A62)

`TargetSpec.parse(a, false)`: without `target` and `by` the text goes to `Typing.defaultKeyTarget()`, the rule
`swing.key` has (the focus owner, else the active window, else the topmost modal dialog, else the first showing window;
a focus owner in a window a showing modal dialog blocks is replaced by that dialog's focus component; P1 §6). A
`Window` so found becomes its root pane. `SET` on a target that is not a `JTextComponent` stays `INVALID_PARAMS`
("mode SET needs a text component", as built); `DISPATCH` types into whatever has the focus. The modal check runs for
an explicit target only, as for `swing.key`. Gap 60 is documented in the same passage of P1 §6: a dispatched `ENTER`
submits the text field that most recently had the focus, so `enter: true` with `DISPATCH` and an omitted target may
submit another field or none; `SET` is unaffected. The schema text of the primitive already says the target is
optional (map 2 §2 row 7); A62 makes the body agree.

### 3.6 Redaction: the built-in rule, the `CaptureMask` SPI and the masked-text rule (A61, A64)

**Decision (D295, D296, D349).** Pixels are personal data until masked (design §3.10, vision §C6, P5 §6.2), and the base
is restrictive by default (B19's rule): `swing.capture` blanks, in the same EDT read after `printAll` and before the
image leaves the task, the rectangle of every component the built-in rule or a registered `CaptureMask` names, with the
solid grey RGB 128, 128, 128, and reports them in `redacted`. The built-in rule: every `javax.swing.JPasswordField`
(whatever its echo character). The SPI, in the family of `NameEnricher` and `MaskPolicy` and separate from `MaskPolicy`
so that `MaskPolicy` stays row-shaped, AWT-free and callable on application threads (P6 §4.3):

```java
package ai.skillroy.testjava.agent.api.spi;
/** Which components show a secret: a capture blanks their pixels before the image leaves the JVM, and every reader of
 *  the tree (the snapshot, the hit-test, the marks, the dialog reader) withholds their text. Called on the EDT inside
 *  the capture's, the snapshot's or the hit-test's read, for every component in the tree; must be fast, lock-free and
 *  must not change the component. */
public interface CaptureMask {
    /** True to blank the component's whole rectangle and withhold its text. */
    boolean masks(java.awt.Component component);
    /** Rectangles inside the component to blank instead of the whole (a table's cells, a row's column); null or empty
     *  means the whole rectangle when {@link #masks} is true, nothing when it is false. With masks false the text under
     *  them is withheld (Phase 7 security fix, S122); with masks true all of the component's text is. */
    default java.util.List<java.awt.Rectangle> regions(java.awt.Component component) { return null; }
}
```

Registered through `ctx.spi().register(CaptureMask.class, …)`, looked up per read with `spi().all`. A mask that throws
is logged once per agent start and ignored for that component (the component is then **blanked and its text
withheld**, the safe side). **One rule for pixels and text** (D349; the text walker withheld a text component's content
for `JPasswordField` only, `ComponentText`): a component the built-in rule or a `CaptureMask` names has no `text` and no
`value` in any output of the module (`swing.snapshot`, `swing.find`'s nodes, `swing.dialogs`' texts, `swing.hitTest`'s
nodes, a mark's `label`), its `Node` carries `masked: true` (the role is unchanged: `Roles.ALL` is a closed set and a
role change would alter every committed snapshot fixture), the hit-test proposes no `{text, role}` locator for it, and
the marks rule skips it. The sample's `EntryField` masks itself only while a secret is expected, so its module's mask
names it, and the number pad that feeds it, while `isMasked()` (§12.2). What blanking cannot hide: the layout and the
text's width (R-a of map 7), stated in `SECURITY.md`'s limitations (§11). A `ROBOT` capture is blanked by the same
components' screen rectangles after the capture (§3.4). The hit-test's `masked` field uses the same rule (§3.2).
`redact: false` leaves everything in; who may say so is §9.1 and §11 (a person at the CLI; never a model, never an MCP
client).

**As built (Phase 7, VS).** A component that lies **inside** a masked component is treated as masked too: no text, no
value, `masked: true`, no mark, `masked` in the hit-test (its pixels lie inside the blanked rectangle, and a pack that
masks a container expects its content hidden); the safe side (D383). A mask's `regions` blank those rectangles whether
or not `masks` is true, as the Javadoc says; the text rule follows `masks` alone. The rule lives in `Redaction` and is
memoised per call in `ComponentView.masked`.

**Fix wave (Phase 7 review, FA, 2026-10-10; L2-F1).** The pixels follow the rule when the component a mask names lies
**outside** the paint root, too: a capture whose paint root lies inside it (a `COMPONENT` or `REGION` capture of a child
of a masked container; any capture of a window a mask names, whose root pane is the paint root of every scope, A61)
blanks, for every ancestor of the paint root up to and including its window, that ancestor's `regions` where they meet
the image, and for an ancestor masked whole its rectangle together with the paint root's own (a `COMPONENT` capture
paints a child whole even where it overhangs its parent), reported in `redacted` under the ancestor's ref with the
ancestor's rule; below an ancestor masked whole nothing is marked or walked (the paint root's caret is still reported).
Before, only components inside the paint root were blanked, so such a capture left the JVM with the masked content as
rendered and `redacted: []` while the snapshot and the hit-test said `masked: true` (D383's premise did not hold there).
A glass capture inherits the rule through `Capture.facts`.

**Phase 7 security fix (built 2026-10-11, SG): the name, how masks combine, and the text under `regions`.** D349 is
widened, not contradicted ("one rule for pixels and text"):

- **S106, the masked name rule.** Swing's accessible name of a `JLabel` and of every `AbstractButton` falls back to the
  component's text, so withholding `text` and `value` left a masked label's or button's content in `name`. For a masked
  component (named by a mask, inside one, or under a blanked region) **a name equal to the component's own text is
  withheld** (`name: null`), and so no `{name, role}` candidate of the hit-test, no first unique locator
  (`focus.ownerLocator`), no `name` match of the locator engine (no oracle through `{regex}` or `{contains}`), no
  description in an error message, no dialog button label and no node carries it. A name set apart from the text stays
  (the accessible name, the client property, a border title, a `labelFor` label, `setName`, a `NameEnricher`), so a
  committed `{name: …}` locator of a masked entry field keeps resolving; one that happens to equal the text is withheld
  too (the safe side). The sample is unaffected (its masked components take their names from the client property, a
  border title or a `labelFor` label, or carry an id).
- **S123, masks combine by union.** Each registered mask contributes, for a component, its `regions` when it gives
  some, else the whole rectangle when its `masks` is true, else nothing; the union is blanked, so one mask's `regions`
  never cancel another mask's whole-component blanking (before, `regions` of any mask replaced the whole rectangle, and
  the component's other pixels and its children's left the JVM while every text route said `masked: true`). A mask that
  names the component **and** gives `regions` still blanks only those (the conservative per-column form: its pixels by
  the rectangles, all of its text withheld).
- **S122, a blanked region withholds the text under it.** A rectangle a mask's `regions` blank, with `masks` false (the
  SPI's form for "a table's cells, a row's column"), withholds the text of what it covers on every text route, as the
  pixels are withheld: a component whose whole rectangle such a rectangle (of its own masks or of an ancestor's, up to
  its window) holds, or a component that shows a text or a value (a label, a button, a field; not a plain container,
  whose children are judged one by one) that such a rectangle meets, is **masked** (no `text`, no `value`, no name from
  its text, `masked: true`, no mark, the hit-test's `masked`); a `JTable`'s cell that such a rectangle meets is left out
  of the row mark's label and nulled in the node's `value.cells`, and a `JList`'s selected item that one meets is left
  out of its `text`; the table's or the list's other text stays. Before, the text rule followed `masks` alone, so the
  marks list, the node's text, a table's value, the hit-test's nodes and a recorded `{name, role}` locator carried the
  content the pixels hid (L2-F4). Chosen over the alternative of a disclosure sentence (the owner's choice; D454).

**Phase 7 security fix, S111 (built 2026-10-11, SI): a masked browser is masked on every route.** D349's one rule
for pixels and text reaches the web module and the snapshot's web views: a browser whose Swing component a registered
`CaptureMask` names, itself or through an ancestor up to its window (`masks` true, or a mask that throws for one of
them: the safe side; a mask's `regions` alone do not make a browser masked, they make its surface `redacted`, §4.8),
is masked on every route: no pixels, no text, no tree. `web.bitmap`, `web.dom.text`, `web.ariaSnapshot` and
`web.hitTest` of a masked browser answer `INVALID_PARAMS` with `data {param: "redact", reason: "masked", browser}`
before the page is evaluated, unless the call says `redact: false` (a person's argument, which `CallPolicy` refuses for
a model or a client, §9.1); `web.browsers` lists it with `masked: true`, `url: ""`, no `title`, and its frames' `name`
and `url` empty (the frame keys stay: handles, not page data); the session's text snapshot (`ExecutorSession.snapshotText`,
what `skillroy_snapshot` and the MCP server's snapshot read) shows it as the one line `[web <ref> masked]` and never
reads its tree, and also treats as masked an older agent's browser whose `component` ref the `swing.snapshot` marks
`masked: true`; the CDP path hands the four reads of a browser the agent reports masked to the agent (§4.7). The check
is one bounded EDT read of the component and its ancestors (`SeedWalk.state`, at most 2 s), made only while a pack has
registered a `CaptureMask`; a read that fails counts as masked. A run's web failure evidence (P4 §6.4) of a masked
browser is therefore its `web-<ref>-error.txt` with the refusal, as the Swing failure capture is redacted. Before, the
page's text reached every session model through the snapshot and its pixels, text and tree reached a read-only MCP
client through the raw reads, while the capture kept the surface grey (L5-S1). D471.

**(Phase 8, built 2026-10-19, EW; P8 §5.2, D545 to D550, D637.)** "Every route" now also covers the events (a masked
browser's `web.load`, `web.ready`, `web.console` and `web.tap` withhold page data and say `masked: true`), the
selection (`by: {url|title}` never matches a masked browser; the error data's summaries withhold its URL and title) and
the answers of the actions that stay allowed on it (`web.pointer` without `hit`; `web.dom.click`, `web.dom.fill` and an
`AMBIGUOUS` answer's candidates with `role` and `tag` only; `redact: false` a person's argument there too); and a
browser is masked by more than a `CaptureMask`: a `BrowserMask` (P4 §3, A79) names one by its tag or vendor object,
and while any `CaptureMask` or `BrowserMask` is registered a browser the adapter reports no component for is masked
unless a `BrowserMask` releases it. `SeedWalk.masksRegistered()` counts both SPIs.

### 3.7 `RegionResolver`, `SurfaceSource`, `LocatorResolver.describe` (A64)

Three additions to `…agent.api.spi`, all called on the EDT, all fast and change nothing (the family's rule, P1 §4):

```java
/** A custom-painted component's stable regions: a point to a region id and back (vision R4 option 1). */
public interface RegionResolver {
    boolean supports(java.awt.Component component);
    /** The region under a point of the component's own coordinates, or null. */
    Region regionAt(java.awt.Component component, java.awt.Point point);
    /** The rectangle of a region id in the component's own coordinates, or null when the id is unknown. */
    java.awt.Rectangle rectOf(java.awt.Component component, String id);
    /** Every region, for the marks of a capture; empty by default. */
    default java.util.List<Region> regions(java.awt.Component component) { return java.util.Collections.emptyList(); }
    final class Region { public final String id; public final java.awt.Rectangle rect;
                         public Region(String id, java.awt.Rectangle rect) { … } /* equals, hashCode, toString */ }
}
/** Areas of the tree that are not Swing-painted, as another module knows them (the web module's browser index):
 *  answered from what the module already holds, never by asking a browser; the list is a snapshot. */
public interface SurfaceSource {
    java.util.List<Surface> surfaces();
    final class Surface { public final java.awt.Component component; public final String kind /* "WEB" */;
                          public final String browserRef;
                          public Surface(java.awt.Component component, String kind, String browserRef) { … } }
}
// LocatorResolver gains:
/** The strategy values that name this component in the resolver's own vocabulary (for example {@code {action: "DUE"}}),
 *  so that a hit-test can propose a domain locator first (design 3.7); empty by default. */
default java.util.Map<String, String> describe(java.awt.Component component) { return java.util.Collections.emptyMap(); }
```

A region id is `[A-Za-z0-9][A-Za-z0-9:._-]{0,63}`; the agent refuses another (`INVALID_PARAMS`). The web module
registers one `SurfaceSource` built from its browser index (§4.8); a pack may register another for a technology of its
own. (Phase 8 security fix, S166, FE; P8 §5.2.) `Surface` gains `public final boolean masked` and a four-argument
constructor (the three-argument one kept: not masked): the web module reports a masked browser's surface masked, and the
Swing module then blanks the surface's component whole (§3.6's rule `browser-mask`) and its hit-test reports a target
on or below it `masked`, as for a component a `CaptureMask` names. The resolver precedence when several `RegionResolver`s support a component: registration order, the first that
answers. These three files land as VS's first commit (§2.1), so that VW's `SurfaceSource` registration builds against them; `WebPointerEvent` is VW's.

### 3.8 `swing.rendering` (read; capability `swing.capture.paint`; A63)

What a pixel baseline is keyed by (BS11 RESULT "Rendering inputs"; §8.7), read in one bounded EDT task, no change to
the host:

```
swing.rendering {} -> { jdk: {vendor, version, specification},  // java.vendor, java.runtime.version, java.specification.version
                        os: {name, version, arch},               // for the reviewer only; never in the key
                        lookAndFeel,                             // UIManager.getLookAndFeel().getClass().getName()
                        antialiasing: {desktopHints: [...],      // Toolkit.getDesktopProperty("awt.font.desktophints") as
                                                                 // sorted "key=value" strings (the hints' map order is not
                                                                 // stable), or []
                                       property},                // the system property awt.useSystemAAFontSettings, or null
                        screenScale,                             // GraphicsConfiguration.getDefaultTransform().getScaleX()
                        probeHash,                               // sha256 of the raster of the probe render
                        keyed: ["jdk.specification", "lookAndFeel", "antialiasing", "screenScale", "probeHash"],
                        renderKey }                              // sha256 over the canonical JSON of the keyed fields, 16 hex digits
```

**The key hashes what renders** (D350): the Java specification version (the line: BS11 RESULT "Across environments"
measured the line, 8 against 25, as a pixel change), the look and feel, the effective antialiasing, the screen scale
and the probe render's hash. It does **not** hash the vendor, the runtime's full version, the OS name, version or
architecture (BS11: the CPU architecture and a JDK 8 patch build, 1.8.0_504 against 1.8.0_492, left the raster
identical; `os.version` inside a container is the Docker host's kernel release, which would turn every committed
baseline stale with a host update) and no font names (`Font.getFamily()` of a logical font answers the logical name, so
such a field would be four constants; the probe render, which paints text, is the font tripwire: installing a font
package changed 3.3-4.7 % of the desk's pixels and changes the probe's). The probe render is a fixed 320 x 120
off-screen `JPanel` with the application's look and feel: a plain and a bold label, a text field, a `JTableHeader` of
two columns, a button, and an antialiased filled ellipse, painted with `printAll` into an RGB image (BS11 RESULT
"Proposal" 4: the toy alone renders identically on Java 8 and Java 25 while the desk does not, so the probe has a table
header and an antialiased shape). The window's size and insets are recorded per baseline, not here. `renderKey` is what
the bindings file's `images` entries are keyed by (§8.7); the comparison, `approve` and the recorder read it (§6.7,
§6.5), and a pack may read it in its `EnvFingerprinter` if it wants a rendering input fingerprinted (§8.8).

### 3.9 Capabilities, the agent option, errors and events

| Capability id | Set or declared | State | Guards |
|---|---|---|---|
| `swing.hittest` | joins `DISPLAY_CAPABILITIES` (`SwingModule`'s array): follows AWT's state as `swing.tree` does (`UNAVAILABLE("AWT not started…")`, `UNAVAILABLE("headless")`, `OK`) | – | `swing.hitTest` |
| `swing.pointer.routed` | likewise | – | `swing.pointer` with `via: ROUTED` |
| `swing.capture.screen` | likewise | – | `swing.capture {scope: SCREEN}` in `PAINT` mode (the composite; what the loop and `inference.vision` gate on, §8.5) |
| `swing.pointer.robot` | without the option `robot`: `set` `UNAVAILABLE("robot: not enabled (agent option robot=true)")` at install; with it: declared after `checkAwt` with the Robot probe of §3.4, posted to an agent worker; from the moment `checkAwt` confirms AWT until that probe has run both ids read `UNAVAILABLE("robot: probe not run")`, which a refused post leaves in place, so that a Robot call is refused rather than constructing the Robot on its own thread (fix wave (Phase 7 review, FA, 2026-10-10; L2-F8): an agent attached to a running application left both ids absent, an agent loaded before AWT left "AWT not started") | `OK("robot")`, `UNAVAILABLE(reason)`, `DEGRADED("robot: native input is not delivered…")` after a failed delivery probe | `swing.pointer {via: ROBOT}` |
| `swing.capture.robot` | the same probe | the same | `swing.capture {mode: ROBOT}` (a person's) |
| `swing.click.robot` | unchanged: `UNAVAILABLE("opt-in rung, not implemented")` | – | the rung stays refused (D291) |

The ids are lower-case as reserved (`swing.hittest`, like `web.hittest`; D298). Every new id enters the five committed
goldens with the state its load mode gives it (the hooks leg's too), identical across load modes and Javas; the matrix
hash of every leg changes once (`CapabilityGoldenTest`, `LoadModeChecks`, `AwtStartTest.DISPLAY_CAPS`,
`SwingModuleHeadlessTest`; map 1 §1.13). The agent option **`robot`** (boolean, a default of `false` in
`AgentRuntime.resolveOptions`, validated like `readOnly`, read by the Swing module from `ctx.options()`;
`agent.info.options` reports it) joins P1 §2.4's table (A65). No new error code (`NOT_FOUND` outside, `INVALID_PARAMS`,
`INPUT_REJECTED` with the reasons `covered`, `target changed` and `not focused`, `CAPABILITY_UNAVAILABLE`, `MODAL_OPEN`
cover every case; D299) and no new topic: the pointer publishes nothing, and `swing.events` stays gap 31. (Phase 7
security fix, S104, S105, S124, S125, built 2026-10-11, SG) Still no new code: a Robot point in an iconified frame or a
window it owns is `NOT_FOUND` with `data.reason: "iconified"`, a screen point no screen shows `NOT_FOUND` with
`data.reason: "off-screen"` (`data.param: "to"` for a drag's end point), `modifiers` with `via: ROBOT` are
`INVALID_PARAMS` with `data.param: "modifiers"`, and a capture whose PNG exceeds the encoding bound is `INVALID_PARAMS`
with `data.reason: "encoding"` (§2.4).

**Phase 8 (built 2026-10-19, EA; P8 §3.6, A68, A69).** Still no new id and no new code: `INPUT_REJECTED` gains the
`data.reason` values `vetoed` (an `InputVeto` refused the input) and `not visible` (the pointer's component form at a
point its component does not show), `INVALID_PARAMS` gains `collapsed`, and the pointer's `fired: false` reason gains
`VETOED`.

---
## 4. The web side (`skillroy-agent-web`, `skillroy-cdp`, the script library)

The two reserved primitives and the reserved `WebCapability.INPUT` are built; `RAW_PROTOCOL` stays reserved without a
method. Both paths answer byte-identical shapes for the same page state (D48). Coordinates are CSS pixels relative to
the main frame's layout viewport, the space `web.resize`'s override defines while one is active (vision §F12; BS8:
`Input.dispatchMouseEvent` at the button's centre incremented the page's counter, "trusted input works without a port").

### 4.1 The SPI (`…agent.api.spi.web`; A66, the A37 precedent of a default method)

```java
/** One synthetic pointer event for a page, in CSS pixels of the main frame's viewport (Phase 7). Immutable. */
public final class WebPointerEvent {
    public enum Action { MOVE, PRESS, RELEASE, WHEEL }
    public enum Button { NONE, LEFT, MIDDLE, RIGHT }
    public enum Modifier { ALT, CTRL, META, SHIFT }
    // builder: of(Action, int x, int y).button(Button).clickCount(int).modifiers(Set<Modifier>).delta(int dx, int dy).build()
}
// WebBrowser gains:
/** Dispatches one pointer event into the page (WebCapability.INPUT); completes once the technology has handled it.
 *  The default has no INPUT and completes exceptionally with UnsupportedOperationException. */
default java.util.concurrent.CompletableFuture<Void> dispatchPointer(WebPointerEvent event) { … }
```

`WebCapability.INPUT`'s Javadoc becomes "synthetic pointer input into a page: `WebBrowser.dispatchPointer`"; the enum's
order is frozen (`WebSpiShapeTest`). No key input in Phase 7 (`web.dom.fill` types; `Input.dispatchKeyEvent` and
`Input.insertText` are the later keys of the same flag, browser-spi §F16). Rules as P4 §3: no vendor type in a
signature, a future with the caller's bounded wait, never a wait on the EDT. These two files are VW's (§2.1); the three
Swing SPIs of §3.7 are VS's and land first.

**As built (Phase 7, VW, 2026-10-10):** as specified, with the bounds the sketch left open (D419): `x` and `y` lie
in `0..16384` (`WebPointerEvent.MAX_COORDINATE`, the largest viewport `web.resize` accepts), `clickCount` in `0..3`,
each wheel delta in `-16384..16384`; the defaults are `LEFT` and a click count of 1 for `PRESS` and `RELEASE`, `NONE`
and 0 for `MOVE` and `WHEEL`; `build()` refuses a press or a release with `NONE` or a click count of 0 and a delta on
anything but `WHEEL` (`IllegalStateException`); a `MOVE` may carry a held button. The enums are nested
(`WebPointerEvent.Action`, `.Button`, `.Modifier`) in the order above, which `WebSpiShapeTest` pins; the class keeps
three public constants, which that test's value-type rule now allows (static fields final, instance fields private and
final). The default `dispatchPointer` completes exceptionally with `UnsupportedOperationException("the adapter has no
INPUT: …")` and calls nothing of the browser; the built-in adapters answer a null event with
`IllegalArgumentException`.

### 4.2 `web.hitTest` (read; capability `web.hittest`; `WORKER`, 10 s; not `script`)

| Parameter | Meaning |
|---|---|
| `browser`, `by`, `frame` | the browser selection of every `web.*` primitive (P4 §4.2; `frame` default the main frame) |
| `point: {x, y}` | the point |
| `space` | `VIEWPORT` (default; CSS pixels of the main frame's viewport) or `COMPONENT` (pixels of the browser's Swing component, `WebBrowser.component()`: the agent converts, §4.8) |

Result: `{browser, frame, viewportPoint: {x, y}, element: {role, name, tag, id?, rect: {x, y, w, h}}, locators: [{role,
name} | {selector}], unique: boolean, iframe?: {key, rect}, scaleAssumed?: boolean}`. The library function `hit(x, y)`
(§4.6) does `document.elementFromPoint` (and `elementsFromPoint` through open shadow roots), takes the aria walker's role
and name (`roleOf`, `nameOf`), the tag, the id and the client rectangle, and proposes locators in the order `{role, name}`
then `{selector}` (an id selector, else a short unique path), each checked with the library's `find` to match exactly
one element (`unique`). When the element is an `iframe`, the point is translated into the child frame and the
function is evaluated there, to depth 3; the result's `frame` is the frame the element was found in and `iframe` the
frame element's key and rectangle in the parent (W8 of map 6). `NOT_FOUND` with `data.reason: "outside"` when the point
lies outside the viewport or hits nothing. The CDP path serves it on the `Place`'s frame through `frame.evaluate` with
the library (the `web.dom.text` pattern), the agent path through `evaluate` with the same expression.

**As built (Phase 7, VW, 2026-10-10):** the shape is as specified; what the sketch left open (D408, D410, D411):
**The descent** is the caller's, not the library's (a `data:` iframe is another origin, whose document the library
cannot reach): for a frame element `hit` also answers `child: {x, y, name, url}`, the point inside the element's border
and padding, and both paths find the child frame by one rule over the same `Page.getFrameTree` (`WebInput.childFrame`:
among the frame's children the one with the element's name when exactly one has it, else the one with its URL when
exactly one has it, else the only child) and evaluate `hit` there, at most three times; a child that answers `NOT_FOUND`
or a script error (the point on the frame's border or padding, a frame that went) leaves the frame element as the
answer. **Every rectangle** (`element.rect`, `iframe.rect`) is in the viewport of the frame the call started in, §4's
one coordinate space (for a frame of the main frame that is "in the parent"); `iframe` is the innermost frame element.
**`frame`**, when given, is the frame the call starts in and `point` is then in that frame's viewport; the result's
`frame` is the frame the element was found in, omitted for a browser without frame keys (no `FRAMES`). **The locators**
are those the library's `find` answers with exactly this element: `{role, name}` first (never for a `generic`, `none`
or `presentation` role, an empty name or one over 1000 characters), then `{selector}` (an id selector when the id is a
plain identifier and unique, else the shortest path of `tag.class:nth-of-type(n)` steps up from the element, at most
eight, anchored at an ancestor's unique id when one is met); `unique` is true when there is at least one. Names and ids
are cut at 1000 in the page and again on the receiving side, role and tag at 64, a selector at 4096 (`WebInput.hit`,
both paths, S43); rectangles are whole CSS pixels, a fractional point is rounded. A point outside the viewport is
`NOT_FOUND {reason: "outside", x, y}`, message "the point x,y lies outside the viewport or hits no element"
(`WebLib.errorOf`). A component point needs `EVALUATE` (the page's `innerWidth`); a browser without a component is
`INVALID_PARAMS` with `data.reason: "no component"`, a component without a width `"no component size"`.

**Phase 7 security fix, S130 (built 2026-10-11, SI):** `unique` is **the library's word**: the library in the frame
that answered says that `find` matched exactly that element there; it is page-chosen data like every other field of
the answer (threat model A3), and a child frame's answer says nothing about the frame the call started in. The vision
recorder therefore records a `webClick {role, name}` only for a hit found in the frame the call started in (§6.5 step 4).

### 4.3 `web.pointer` (mutating; capability `web.pointer`; `WORKER`, 10 s)

| Parameter | Values | Default |
|---|---|---|
| `browser`, `by` | selection | – |
| `action` | `MOVE`, `PRESS`, `RELEASE`, `CLICK`, `DOUBLE_CLICK`, `WHEEL` | required |
| `point`, `space` | as §4.2 | – |
| `button` | `LEFT`, `MIDDLE`, `RIGHT` | `LEFT` |
| `modifiers` | `ALT`, `CTRL`, `META`, `SHIFT` | none |
| `clickCount` | 1 to 3 | 1 (`DOUBLE_CLICK`: 2) |
| `wheel` | `{dx, dy}` in CSS pixels | – |
| `force` | boolean | false; a person's argument only (refused for a `MODEL` or `CLIENT` origin as on every input primitive, §9.1, D294) |

`CLICK` is one primitive call: a move, a press and a release awaited in sequence under the call's budget (a loop that
sent them as separate model actions would leave a page half-pressed for seconds, map 6 §7). Before acting the
primitive reads `hit` at the point (§4.2's function, so the recorder can cross-check it); the result is `{via: PROTOCOL
| VENDOR, viewportPoint, dispatched: [types], hit?: {role, name, tag, id?}}`, `via` mirroring `BitmapSource`
(`PROTOCOL` on JCEF and the CDP path, `VENDOR` on JxBrowser). **The modal check** (W5): the web module checks, in one
bounded EDT read, whether a showing `java.awt.Dialog` whose modality is not `MODELESS` and that is neither the browser
component's window nor owned by it blocks that window (the same heuristic as the Swing module's, re-stated here because
the two modules do not depend on each other), and answers `MODAL_OPEN` unless `force`; a browser without a component
(view-less) is not blocked. A browser whose component is not showing is served (a protocol call, like `webDispatch` on
the hidden card) and the result adds `visibleInSwing: false`; the tiers never target one (its bitmap is refused, gap
39). A mutating call is never retried (P4 §4.2). The JCEF adapter sends `Input.dispatchMouseEvent {type: mouseMoved |
mousePressed | mouseReleased | mouseWheel, x, y, button, clickCount, modifiers, deltaX?, deltaY?}` through
`JcefBrowser.call` (never a `READS` method, D62); the CDP path sends the same method with the same parameters on the
path's own session (`CdpAttach.send(sessionOnPw(page), …)`, D64's precedent; W6: Playwright's `page.mouse()` is not
used, so that both paths send byte-identical parameters and no actionability semantics hide a difference).

**As built (Phase 7, VW, 2026-10-10):** both paths read the parameters, plan the events, build the protocol's parameters
and shape both results with one class of the protocol module, `ai.skillroy.testjava.protocol.web.WebInput` (D409), so
that a wrong parameter is the same `INVALID_PARAMS` (`data.param`) on either path and the parameters are byte-identical:
`{type, x, y, button, clickCount, modifiers}`, plus `deltaX, deltaY` for `mouseWheel` (modifier bits ALT 1, CTRL 2, META
4, SHIFT 8; buttons `none`, `left`, `middle`, `right`). **The plan** (D406): every action starts with a `mouseMoved`
to the point (hover and pointer-enter run first, as a person's pointer arrives before it acts); then `PRESS` sends one
`mousePressed`, `RELEASE` one `mouseReleased`, `CLICK` with click count `n` the pairs `mousePressed`/`mouseReleased`
with click counts 1 to `n` (what Chromium needs to raise `dblclick`), `DOUBLE_CLICK` the same with `n` 2 by default,
`WHEEL` one `mouseWheel`; `dispatched` lists the types sent. **The `hit` read** before acting: a point outside the
viewport is `NOT_FOUND` and nothing is sent; a hit that cannot be read (a busy page, no `EVALUATE`) is left out of the
result and the action goes on (on the agent it takes at most half the budget and 5 s). **After a failure that follows a
press** one `mouseReleased` of that button is sent (at most 1 s, its outcome not awaited further), so that no button
stays held in the page, and the call answers the failure (D407). **The modal check** applies the Swing module's
heuristic as that module states it (D414; "neither the browser component's window nor owned by it" above is read as:
the dialog is not the browser's window and the browser's window is not owned by the dialog): when several dialogs block
the window, the active one, else the last created; `MODAL_OPEN` carries `data {dialog, title, browser}`. It runs in the
module's own bounded EDT read of the component (`SeedWalk.state`, at most 2 s), which also gives `visibleInSwing`. **On
the CDP path** (D404), which cannot see AWT, the path reads the agent's `web.browsers` once more before every pointer
call; a browser the agent reports blocked (the entry's new optional `blockedBy: {dialog, title}`, written by the same
check, P4 §4.2) is handed to the agent (`UNSUPPORTED`, a `web.fallback` record), which answers `MODAL_OPEN` itself,
unless `force`; so no pointer of a model or a client acts past a modal dialog on either path. `via` is the browser's
`bitmapSource()` on the agent (`PROTOCOL` for JCEF, `VENDOR` for JxBrowser and the SPI's default) and `PROTOCOL` on the
CDP path. A browser that is not showing, or has no component, is served with `visibleInSwing: false`.

**(Phase 7 follow-up, gap 104, built 2026-10-12.) No paste of the X11 PRIMARY selection.** A sequence that releases the
middle button (only a person's: a model's or a client's middle button is refused, S110) first evaluates, within the
call's bound (at most the hit's share), a one-shot capture listener on the main frame's window that cancels the default
action of the next middle `mouseup` and removes itself after it or after 5 s. On Linux that default is Blink's paste of
the X11 PRIMARY selection, which in an off-screen Chromium page blocked the page's renderer for 20 s to over a minute,
whether or not the selection had an owner (measured on JCEF 2026-10-12: the click `TIMEOUT` at 5 s; with the listener it
answered in 22 ms on a Java 25 target and 95 ms on a Java 8 one, the page answering at once and counting `pointerdown`,
`mousedown` and `mouseup`, trusted). The page's own handlers still see the event (with `defaultPrevented`); a page that
cannot take the listener (no `EVALUATE`, or the evaluation fails) gets the click as before; an iframe's own window is not
reached, so a middle click inside an iframe may still paste. The CDP path's own pointer is unchanged. (Phase 8 security
fix, S171, FE.) **Best effort**: the listener is added through the page's own `window.addEventListener`, so a page that
replaced it, or registered an earlier capture listener of its own that stops the event's propagation, keeps the paste
(an evaluation made after the page ran cannot reach the pristine built-ins); the listener removes itself on a
**trusted** middle `mouseup` only (`isTrusted` cannot be forged), so a page's own synthetic middle `mouseup` no longer
consumes it; and the expression is self-bounding as S131 requires of every raw page evaluation (one `try` around it,
the constant `1` its only answer), so a page that makes `addEventListener` throw no longer chooses the size of what
crosses the channel.

### 4.4 Capabilities

`web.hittest` and `web.pointer` join `WebCapabilities.GENERIC`: derived in every cycle, `web.hittest` `OK(<adapters>)`
when an open indexed browser has `EVALUATE`, `web.pointer` `OK(<adapters>)` when one has `INPUT`, else `UNAVAILABLE`
with the `flag()` reasons (`no browser found`, `no indexed browser has INPUT`); the install default stays `UNAVAILABLE`
with the reason `no embedded browser technology found` instead of `reserved for the vision bolt-on`. The three tests
and the acceptance that pin the reserved text flip (`WebModuleInstallTest`, `WebCapabilitiesTest`, `CdpWebPathServesTest`,
`WebHostChecks` item 1; map 6 §7); `WebCommandsTest` in the CLI (`:71`, `:110`) carries the same text in its fake's data,
which goes stale but not red: a note for VC, which owns the CLI's tests. The goldens count the reason by presence, so
they need no text change, but their `web.hittest` state becomes `OK` where a browser is indexed (the web host's goldens
only; the lending desk has none).

**As built (Phase 7, VW, 2026-10-10):** as specified: both ids are in `WebCapabilities.GENERIC`, set
`UNAVAILABLE("no embedded browser technology found")` at install and declared with a probe at readiness like the other
generic ids, `web.hittest` from `EVALUATE`, `web.pointer` from `INPUT` (an index rebuild outside a cycle may improve
them, as it may the others); the reserved text is gone from the module. The web host has no committed capability
goldens (only the lending scenarios module has goldens), so nothing was regenerated there (D418).

### 4.5 The adapters

- **`jcef`**: `JcefBrowser.dispatchPointer` sends the protocol method of §4.3; `ALL` gains `INPUT` (any JCEF whose
  DevTools channel exists carries it; nothing in the descriptor changes). Verified in the image (§13.2).
- **`jxbrowser`**: descriptor roles under a new group `for: "INPUT"` in `jxbrowser.json` for `Browser.dispatch` and
  the event builders (`MousePressed.newBuilder(Point)`, `MouseReleased`, `MouseMoved`, `MouseWheel`, `.button`,
  `.clickCount`, `.build`), resolved per vendor loader; the flag is absent where they do not resolve; the handle's
  override runs on the adapter's threads with a bound (the `resize` pattern); the strategy string says `input
  unverified` (W2); `fake-jxbrowser.json` mirrors the roles and `FakeBrowser.dispatch` records the event instead of a
  violation. Whether the vendor's `Point` is CSS or device pixels on a HiDPI view is not known (map 6 §9) and is a line
  of the strategy string until a licence holder's run says.

**As built (Phase 7, VW, 2026-10-10):** `jcef`: `dispatchPointer` sends `Input.dispatchMouseEvent` through the handle's
channel (the raw channel where its roles resolve, else the shared client), never one of the handle's reads, so it is
never sent again on the shared client; `ALL` gains `INPUT`; a browser without a native browser yet has no `INPUT` (it
has no flags at all). `jxbrowser` (D413): the group `for: "INPUT"` holds `browser.dispatch` (arity 1), `point.of`
(`com.teamdev.jxbrowser.ui.Point.of(int, int)`), the buttons `button.primary`, `button.middle`, `button.secondary`
(static fields of `com.teamdev.jxbrowser.ui.MouseButton`), and per event class of `com.teamdev.jxbrowser.ui.event`
(`MousePressed`, `MouseReleased`, `MouseMoved`, `MouseWheel`) its `newBuilder(Point)` and its builder's `button`,
`clickCount`, `deltaX`, `deltaY` (by arity, so that the vendor's numeric types need not be known) and `build`; every
name is unverified against the vendor's library, as the strategy says: `", input unverified (HiDPI: CSS or device pixels
unknown)"` is appended to the probe's strategy when the group resolves (`JxBrowserAdapter.INPUT_UNVERIFIED`). The event
is built and dispatched on the adapter's threads, the caller bounding the wait; modifier keys are not mapped, so an
event that names one is refused (`UnsupportedOperationException`, `CAPABILITY_UNAVAILABLE`). The fake mirrors the
roles with `FakePoint`, `FakeMouseButton` and `FakeMouse{Pressed,Released,Moved,Wheel}` (each with its builder);
`FakeBrowser.dispatch` hands the event to the fake page; `fake-jxbrowser-noinput.json` is a line without the roles.
(JxBrowser kit run against 8.18.3, built 2026-10-11; P4 §4.5 *Against the product*.) The run settled D413: the names
resolve, but `Browser.dispatch` has one overload per event type, so `browser.dispatch` (arity 1) is replaced by four
roles with their parameter types (`browser.dispatchMousePressed`, `browser.dispatchMouseReleased`,
`browser.dispatchMouseMoved`, `browser.dispatchMouseWheel`); the product's wheel deltas have the opposite sign of the
DOM's, so the handle negates `deltaX` and `deltaY`; `Point` is in CSS pixels on a HiDPI view (Retina,
`devicePixelRatio` 2); kit items 16 and 17 pass against the product. The strategy's note is dropped on line 8 and reads
`", input unverified on this line (verified on line 8)"` on any other line (`JxBrowserAdapter.INPUT_UNVERIFIED`);
modifier keys stay refused. The fake mirrors the overloads (`FakeBrowser.dispatch` per event type) and the sign.

### 4.6 The script library: `hit`, version 2 (`skillroy-protocol`, `web-lib.js`, `WebLib`)

`WebLib.FUNCTIONS` gains `hit`; `L.version` becomes `"2"`. **The versioning rule** (D300): a document whose slot
(`Symbol.for("skillroy")`) already holds a library is never replaced (P4 §4.6 stands: a replacement would lose a tap's
buffer), but a library whose `version` is lower than the caller's has the missing functions installed into the
existing object (`hit` into a version-1 slot), one install per document, and the slot's `version` is raised; a call of
a function the slot still lacks is `SCRIPT_ERROR` naming it. `WebLib.hash()` changes once and with it the `run.jsonl`
record's library hash. Every library answer stays bounded by the guard (`boundedCallExpression`, `WebLib.Bounded`) and
is cut again on the receiving side (S43): a hit's role, name, tag, id and rectangle are page-chosen data (threat model
A3).

**As built (Phase 7, VW, 2026-10-10):** `WebLib.VERSION` is `"2"` and `FUNCTIONS` holds `hit`. The install rule as built
(D405): a slot that already holds a library is never replaced; the text checks the functions version 2 added
(`ADDED_IN_2 = ["hit"]`) and leaves at once (`"present"`) when the slot has them; otherwise, after defining everything,
it installs each missing function into the slot's object (non-enumerable, non-writable, non-configurable) and raises the
slot's `version` where it can (`"upgraded"`). A version-1 slot's `version` was exposed non-configurable, so it stays
`"1"` and the functions it now has mark the upgrade; from version 2 on, `version` is configurable, so that a later
library can raise it. A slot that refuses the definition (a page froze it, or put something else there) is left as it
is, and a call of the missing function then fails as a script error whose message names it (Chromium's "… .hit is not
a function"). The library's text gains no division and no regular-expression literal (`WebLibTest`'s lexer). `hit`
answers `{element, locators, unique, child?}` (§4.2's note); the receiving side's reading is `WebInput.hit`.

### 4.7 The CDP path (`skillroy-cdp`)

`CdpWebPath.SERVED` gains `web.hitTest` and `web.pointer`, `MUTATING` gains `web.pointer`; `web.hitTest` runs the
library's `hit` on the `Place`'s frame, `web.pointer` sends `Input.dispatchMouseEvent` on the page's session (§4.3);
`budgetMs` unchanged (10 s). The path takes viewport points only: `space: COMPONENT` is handed to the agent path
(`UNSUPPORTED`, the agent fall-back of P4 §6.1), since the path has no component bounds (`AgentBrowser`, map 6 §5.3).
`CdpWebPathServesTest`'s "not served" list loses the two names.

**As built (Phase 7, VW, 2026-10-10):** as specified, with §4.2's descent (the child frame found in the page's
`Page.getFrameTree` by `WebInput.childFrame` and mapped to its Playwright frame as a `frame` key is) and §4.3's notes:
the pointer reads the agent's `web.browsers` once more and hands a browser the agent reports blocked by a modal dialog
to the agent (D404); `visibleInSwing` comes from that read; a component point, a secret parameter or a read-only
target hands the call to the agent before anything reached the page.

**Phase 7 security fixes, S133, S111 and S131 (built 2026-10-11, SI):** (a) **Nothing is dispatched after the call's
deadline** (S133): `web.pointer` on the CDP path reads the page's `hit` first, then the agent's `web.browsers` for the
modal check immediately before the first event (before, the modal check came first, so a page that stalled its own hit
made it stale), and checks the deadline before every `Input.dispatchMouseEvent`; once the caller's budget has passed
(it answered `TIMEOUT` already, and the abandoned operation still runs on the Playwright thread) nothing more is sent,
and a press already sent is balanced by one release, as after a failure. The hit read gets no bound of its own:
Playwright's `evaluate` has none, and an in-page timer cannot interrupt a page that stalls synchronously; the deadline
check is what keeps the late click out (D478). (b) **A masked browser's reads are the agent's** (S111, D404's method):
before `web.bitmap`, `web.dom.text`, `web.ariaSnapshot` and `web.hitTest` the path reads the agent's `web.browsers`
once more and hands a browser the entry marks `masked: true` to the agent (`UNSUPPORTED`), which refuses the read
(§3.6), unless `redact: false`; a `web.browsers` that fails hands the call to the agent too ((Phase 8, built 2026-10-19,
EW; P8 §5.2 (iii), (iv)) and so are, while the agent reports masks registered or says nothing about them, the reads of
a page the agent has no entry for, a `by: {url|title}` selection, and `web.pointer`, `web.dom.click` and `web.dom.fill`
of a masked or unmapped page; the path's own events of a masked page withhold its URL and detail). (c) The inner size the
path reads for `web.resize`'s answer is built in the page from `typeof` and two numbers (`{w, h}`, else `null`), so a
page that redefines `innerWidth` chooses no long answer (S131). (d) The path's PNG scaling decodes and encodes through
memory-cached image streams (S143, P2 §11.3).

### 4.8 Web surfaces in a Swing capture, and a Swing point in a page

- **Surfaces** (W4 (a)): the web module registers a `SurfaceSource` (§3.7) answered from its browser index: one
  `Surface(component, "WEB", browserRef)` per indexed browser whose `component()` is in a window, no vendor call. The
  Swing capture emits `surfaces: [{kind: WEB, rect, ref, browser, redacted}]` with the component's rectangle in image
  coordinates (for windowed JCEF the lightweight panel, which prints blank, BS8 F-E; for an off-screen canvas the
  heavyweight, which the guard lists in `skipped` as well), `redacted: true` when a mask names the browser component or
  a redacted rectangle intersects the surface (§3.1), and `HEAVYWEIGHT` for heavyweights nobody claims.
- **A Swing point in a page** (W3, two steps on the agent, nothing cross-module): `swing.hitTest` answers `surface: WEB`,
  `browser: <ref>` and `surfaceLocal: {x, y}` (the point in the browser component's own pixels); `web.hitTest {browser,
  point: surfaceLocal, space: COMPONENT}` converts on the agent: with no viewport override and `innerWidth` equal to the
  component's width, 1 CSS pixel = 1 component pixel; with a difference the factor is `innerWidth / component width`
  (both known; `scaleAssumed: false`); while a `web.resize` override is active the conversion is refused
  (`INVALID_PARAMS`, `data.reason: "viewport override"`, map 6 §5.6); on a HiDPI view the factor is computed the same
  way and `scaleAssumed: true` says that CEF's device scale was not consulted (none of this runs at scale 1 in the image
  anyway).
- **The composite** (D349's rule applied to pages): the vision loop (§6.2) composites `web.bitmap` into each `WEB` surface of a
  capture **whose `redacted` is false** before the image goes to a model (a windowed JCEF prints blank, an off-screen
  one is a placeholder); a redacted surface stays grey and carries no mark. A page's pixels are therefore masked as a
  whole component or not at all: there is no `CaptureMask` for a DOM element, and a pack whose page shows personal data
  masks the browser component while it does (§11; a limitation sentence of `SECURITY.md`). The loop acts on a `WEB` hit
  through `web.hitTest` and `webClick {role, name}` (recorded) or `web.pointer` (live-only).

**As built (Phase 7, VW, 2026-10-10):** the module registers its `SurfaceSource` (`WebSurfaces`) at install through
`SpiRegistry` and withdraws it at uninstall; it answers from the index alone: the component each entry's adapter
reported at the last rebuild (kept in the entry, typed `Object`), one `Surface(component, "WEB", ref)` per entry whose
component lies in a window (its parents walked on the calling EDT), a browser the module saw close left out; no browser
or adapter is asked (D415). The class that walks the parents is loaded at the first call, so that install still
requests no AWT class (`WebModuleInstallTest`). The conversion of a component point uses the component's width from
the module's own bounded EDT read and the page's `innerWidth`; the override that refuses it is the one this module's
`web.resize` set (an override the CDP path set on its own session is not seen by the agent, and the CDP path refuses a
component point anyway). `scaleAssumed` is `true` when the component's screen transform is not 1, else `false`.

**Phase 7 security fix, S108 and S129, the agent's half (built 2026-10-11, SG):** the Swing capture lists a `WEB`
surface only for a showing component, with `rect` its visible part and `covered` the rectangles painted above it (a
window above in the composite, a popup, modal or drag layer or a visible glass pane above it in its root), §3.1;
(S108 follow-up, built 2026-10-11, SG) and with `bounds`, the component's whole rectangle in image coordinates, not
clipped, so that the composite places the page's bitmap at `bounds` and clips it to `rect` minus `covered` (the
"proposed" field of SI's note below, now sent). What
the composite does with `covered` (the page drawn into `rect` minus `covered`; an agent without `covered` read as
empty) is the controller's half (the vision module's `Capture.Surface` and `Grounding.composite`), built with the
security fix's SI package.

**Phase 7 security fixes, S108 (the controller's half), S111 and S131 (built 2026-10-11, SI):** (a) **The composite
draws only where the page is seen** (S108): the vision module's `Capture.Surface` reads `covered` (absent: none) and
the composite draws the page's bitmap clipped to `rect` minus every `covered` rectangle, so a modal dialog, an owned
window, a popup or an in-window dialog above the browser keeps its own pixels in the model's picture. The bitmap is
drawn at the component's whole rectangle: the additive `bounds` when the agent sends one (no agent did when SI built;
SG's follow-up above sends it on every `WEB` surface, D462), else `rect`; since §3.1's `rect` is the **visible** part, a
`rect` whose shape is not the bitmap's (a browser a viewport cuts) is not drawn at all without `bounds` (where the
visible part lies in the page is not known), and the area keeps what the capture painted (D475; with an older agent that
sends no `bounds`). (b) **A masked browser is masked on every route** (S111, §3.6): the web module asks
the registered `CaptureMask`s for the browser's component and its ancestors in its own bounded EDT read; the composite
already never drew a `redacted` surface. (c) **Every page evaluation of the module is guarded** (S131, P4 §4.6): the
component-point conversion reads the inner size with an expression built from `typeof` and two numbers (`[w, h]`, else
`null`, which converts nothing), and `web.browsers`' title read cuts the title at 4096 characters in the page and checks
its primitive `length` afterwards (a page may replace `slice`); both answers pass the channel check (`WebPrimitives.parse`)
before they are parsed. Before, the inner size was a raw `evaluate` whose answer a page sized up to the channel bound,
and the title was parsed with no channel check at all (L4-F3; D480).

### 4.9 The web host, the kit and the acceptance (profile `jcef`)

`front.html` and the kit's `main.html` gain a pointer-event counter (`pointerdown`, `mousedown`, `mouseup`, `click`,
with `isTrusted`) on their button, so that the difference between `el.click()` and a pointer click is visible (gap 36).
Kit items 15-18: a hit-test at the block's centre answers the block, its rectangle and a unique selector; a pointer
`CLICK` on the counting element records the four events with `isTrusted: true`; a pointer into the hidden browser (what
Chromium does with it is recorded, not asserted); no enumerable global after a hit-test (item 11's rule). `WebHostChecks`
item 1 asserts `web.hittest` `OK` and `web.pointer` `OK` (JCEF has `INPUT`) in every launch, and a new item runs
`web.hitTest` and `web.pointer` over both paths and compares the shapes (D48). Gap 36 closes with this section. The
host's committed `plan/*.plan.json` are regenerated for plan version 6 by VC's script, which touches nothing else in
that tree (§12.5).

**As built (Phase 7, VW, 2026-10-10):** the counter is on the host pages' Borrow button (all three pages share one
script, so all three have it): `webhostPointer()` answers the `type:isTrusted` entries recorded so far; the kit page
gains a counting button `#count` whose script records the same into its `data-events` attribute (no global). Kit
items 15-18 are `WebEngineAdapterContract.item15…` to `item18…`: item 15 calls `hit` through
`WebLib.boundedCallExpression` at the centre of the block read from the page; item 16 dispatches `MOVE`, `PRESS` and
`RELEASE` through the adapter and polls the counter (bounded); item 17 publishes its record as a report entry `item17`
and prints it, asserting only that no event is waited for beyond the bound (the rest are not sent after one that is not
answered); item 18 is item 11's check around two `hit` calls. `WebHostChecks` gains
`item10HitTestAndPointerOnBothPathsAnswerTheSameShapes` (the agent's answers, and with a port the CDP path's, opened in
the check, compared for equality) and `item10TheSwingCaptureListsTheBrowserAndASwingPointReachesThePage` (the Swing
half, on VS's `swing.capture` surfaces and `swing.hitTest`; it would skip while the matrix had no `swing.hittest`, and
runs in every launch since VS landed).
The fakes serve `hit` and the counter over a fixed layout of the kit page (`FakeDocument.BLOCK_RECT`,
`COUNT_RECT`).

---

## 5. Protocol amendments, capabilities and events

**The wire stays 0.2** (D299): every change is an added primitive, an optional parameter, an optional result field, a
capability id or an SPI method; `/schema` and `/capabilities` tell a controller what the agent has; `fromJson` of every
answer type stays lenient; no new error code and no new topic. The additive changes to the frozen Phase 1 contract go
through its amendment process as marked passages (P1 header; P6 §15.1) with these ids, written into P1 as "(Phase 7,
specified 2026-10-10, not yet built; P7 §n)" when the build starts and corrected to "(Phase 7, built …; P7 §n)" after
it; the build appends an index table "A34 onwards" to `phase1-contract-amendments.md` (id, change, where it lives: A34
P2 §11.4, A35-A38 P4 §16, A39 P1 §6, A40-A60 P6 §12.2, A61-A66 here), since the design and this contract call that
file the amendment process and it ends at A33 (D301, map 2 §1.4).

| Id | Change | P1 passage | P7 |
|---|---|---|---|
| **A61** | `swing.capture`: the parameters `by`, `mode: ROBOT`, `scope`, `region`, `scale`, `redact` (default true), `annotateRefs`; the result fields `captureId`, `seq`, `mode`, `scope`, `space`, `window`, `origin`, `scale`, `windows`, `surfaces`, `marks` (geometry and labels; nothing drawn), `marksTruncated`, `carets`, `focus`, `redacted`, `redactedTruncated`, `hash`, `stackingAssumed`, `region`, and for a person's `ROBOT` capture `glass` and `unstable`; a window painted from its root pane (the built pixels on the X toolkit change: the imitation decorations go; gap 76 closed); the redaction default; the masked-text rule of §3.6 in every reader of the tree | §6 (the `swing.capture` row of the primitive table; the capture passage; the text walker's password sentence; P4 §2.5 is unchanged) | §3.1, §3.4, §3.6 |
| **A62** | `swing.type` without `target` and `by` types at the focus owner by `swing.key`'s rule; gap 60 stated | §6 (the `swing.type` row; "Type modes") | §3.5 |
| **A63** | the primitives `swing.hitTest`, `swing.pointer` and the read `swing.rendering`; the capability ids `swing.hittest`, `swing.pointer.routed`, `swing.pointer.robot`, `swing.capture.robot`, `swing.capture.screen`; "Modal blocking" covers the pointer (per event, §3.3); "Click rungs": the `ROBOT` sentence names `swing.pointer` | §5 (the catalogue), §6 (three rows; "Click rungs"; "Modal blocking"; "Capabilities") | §3.2-§3.4, §3.8, §3.9 |
| **A64** | the SPIs `RegionResolver`, `CaptureMask`, `SurfaceSource`; `LocatorResolver.describe` | §4 (the SPI table) | §3.6, §3.7 |
| **A65** | the agent option `robot` | §2.4 (the options table) | §3.4, §3.9 |
| **A66** | `WebBrowser.dispatchPointer`, `WebPointerEvent`, `WebCapability.INPUT` built; `web.hitTest`, `web.pointer` as built; the library's version 2 and its install rule | P4 §3, §4.2, §4.4, §4.6, §5.2 (P4 is marked, as A37 was) | §4 |

**Capabilities Phase 7 adds or changes**, for the goldens and `requires:`:

| Id | Side | Phase 7 state |
|---|---|---|
| `swing.hittest`, `swing.pointer.routed`, `swing.capture.screen` | agent, Swing | follow AWT's state (`OK` on the desk in every load mode) |
| `swing.pointer.robot`, `swing.capture.robot` | agent, Swing | `UNAVAILABLE("robot: not enabled (agent option robot=true)")` in every committed golden (the matrix legs do not set the option); probed only with it |
| `swing.click.robot` | agent, Swing | unchanged sentinel |
| `web.hittest`, `web.pointer` | agent, web | derived: `OK(jcef)` on the web host, `UNAVAILABLE("no embedded browser technology found")` on the lending desk |
| `inference.gateway`, `inference.sql`, `inference.vision` | controller (§8.5) | merged into the run's matrix by the contributors (`inference.vision` by the vision module's contributor alone); never in a golden (the goldens are the agent's matrix) |

No new event topic. The pointer and the hit-test publish nothing; the capture publishes nothing; the step protocol's
consequence of a pointer action is the application's own events (design §2.5).

---
## 6. The controller: `skillroy-vision`

### 6.1 Decision and shape (D302-D304, D353)

`controller/skillroy-vision`, package `ai.skillroy.testjava.vision`, Java 25, Apache-2.0 headers, no third-party
dependency. It holds the loop, the tiers, the two tool adapters, grounding and visual questions, the recorder, the
scenario player, the captures directory and the contributor of `inference.vision`. It plugs into the session service through
the `SessionTools` seam of §9.1 (one loop, one meter, one journal, one mask, one history: a second loop in this module
is rejected, D302, for D79's reason against two implementations), into the matrix through `CapabilityContributor`
(§8.5), and **acts only through the service's facade `SessionActions`** (D353): `execute(verb, args, provenance)` for
the step that will be replayed, `execute(PlannedCall, provenance)` for a live-only raw call, `read` for a capture, a
hit-test or `swing.rendering`, `complete` for a visual question, `capabilities()` for the merged matrix. The facade
fixes the origin (`Origin.model` of the turn), runs `CallPolicy` on every call and meters and ledgers every model call;
the module holds no `ExecutorSession`, no `AgentConnection` and no gateway. Types the build fixes:

| Type | Role |
|---|---|
| `VisionTier { V1, V2, V3 }` | the tier of a session (§6.2) |
| `VisionTools { TOOLSET, CUSTOM }` | the tool vocabulary of V2 and V3 (§6.3) |
| `VisionOptions(VisionTier tier, ModelClass model, VisionTools tools, int maxActions, int maxTurns, boolean zoom)` | from the host's tool options `vision.tier`, `vision.model`, `vision.tools`, `vision.maxActions`, `vision.maxTurns`, `vision.zoom` (§9.1); defaults `V1`, `SONNET_CLASS`, `TOOLSET`, 30, 20, false; whether a capture is annotated follows the tier (V1 only, §3.1) and is no option |
| `VisionSessionTools implements SessionTools` | the seam's implementation: tools, provider tools, instructions, `atTurnStart`, `act`, `afterBatch` |
| `ToolAdapter` (sealed), `CustomToolAdapter`, `ComputerToolsetAdapter` | the two tool vocabularies (§6.3) |
| `Marks` | draws the marks of a capture's `marks` geometry on the controller's copy of the raster (§3.1's style; D346) |
| `Grounding` | capture, hit-test and the stale-capture rule (§6.4) |
| `VisualQuestion` | one question on one capture with structured output (§6.4) |
| `Recorder` | a model's action to the step that will be replayed (§6.5) |
| `ScenarioPlayer` | runs a scenario with `aiAct`/`aiExpect` steps over a session (§6.8) |
| `Captures` | the on-disk directory `captures/<sha256>.png` through `OutputFiles` (§9.6) |
| `KeyNames` | X keysym names of the toolset (`Return`, `ctrl+s`, `alt+Tab`) to `KeyEvent.VK_` names and modifiers |
| `VisionCapabilities implements CapabilityContributor` | `inference.vision` (§8.5) |

The module's tests carry their own fake agent (`FakeVisionAgent`, canned captures from PNG fixtures with their geometry,
canned hit-tests; the PNG fixtures are this module's) and take `ToyPack` and `FakeAgentConnection` from the
`skillroy-scenario` test-jar (§2.1); the inference module's `FakeAgent` is not reachable and is not needed. The
player's `ExecutorSession` and `SessionService` are opened by its host (`SessionCommand --play`, or the IT of §13.2
item 8) and handed to it for the run; the module constructs neither (§6.8).

**As built (Phase 7, VM, 2026-10-10):** the module is as specified, plus a public record `Capture` (one capture: the PNG
as the agent sent it and its geometry, `captureId`, `seq`, `hash`, `windows`, `surfaces`, `marks`, `redacted`,
`carets`, `focus`), and `VisionOptions.narrow` for the player. The facade the contract fixed could not reach three things
the module needs, so `SessionActions` gains `outputDir()` (where `captures/` goes, §9.6), `screenLegend()` (the pack's
legend through the session's mask, for a visual question, §6.4) and `note(text)` (`skillroy_look`'s journal note under
the turn's origin): D420. The module's tests reach the scenario test-jar's package-private fixtures through a public
bridge in the scenario package (`VisionBridge`, test scope only), as `skillroy-telemetry`'s `TelemetryFixture` does;
`FakeVisionAgent` keeps components in window coordinates and hit-tests in screen coordinates through each capture's
own origin, so that a dialog that grows the composite makes the same image point name another component.

### 6.2 The loop in interactive sessions: V1, V2, V3

The loop is the session service's loop (P5 §12.4) with the vision tools added: the person's request (or the player's
`aiAct` intent) is the turn, the model answers with tool calls, each is acted through the facade and journaled under
`Origin.model(model, intent, requestId)`, the results go back in one `USER` message, and the turn ends with `done`,
`ask_person`, the action limit, the budget, a tool's `endTurn` or a lost agent. What Phase 7 adds, per iteration:

1. **Observe.** At the turn's start (`SessionTools.atTurnStart`, appended to the turn's first `USER` message) and
   after every batch of actions (`SessionTools.afterBatch`, appended after the batch's results as its own blocks: the
   image, and for V1 the marks list) the loop takes one capture: `swing.capture {scope: SCREEN, mode: PAINT, redact:
   true, annotateRefs: <V1>}` through `actions.read`, at the size rule of §3.1, **in every tier** (never `mode: ROBOT`,
   D341). It keeps the raster in memory with its `captureId`, `seq`, geometry and `hash` (written to the captures directory
   as captured when a step's provenance first names it, §9.6); composites `web.bitmap` into each `WEB` surface whose `redacted` is false (§4.8);
   and, for V1, draws the marks on its own copy (`Marks`, §3.1's style) before the image goes into the request, so that
   `hash` names the content and the file holds no mark. V1 also reads `snapshotText` (the text snapshot of P5, which
   BS12 RESULT finding 2 found to drop table rows and unnamed components: the marks list is what carries them) and gives
   the model the marks list as text in a `<target-ui>` block: `mark 7: button DUE`, `mark 12: table row 3 "Garden spade
   1 3.50"`. The capture is never attached to a tool result (`act` cannot know which call is the batch's last, and an
   error result carries text only, BS12 RESULT "Sessions": a 400 otherwise); the toolset's own `screenshot` returns an
   image result and the batch's `afterBatch` capture is then skipped as redundant (the loop compares `seq`). **Settle
   before every capture**: the recorded step's own sync (`SETTLE`, or `EVENT` then settle) has run; a model's `wait` is
   answered by `swing.settle` with a bound and never a sleep (BS12: 97 `wait` calls, none paused).
2. **Model.** One `ModelRequest` through the service's loop (`SessionService.complete`, metered and ledgered; §10):
   purpose `vision`, the class of `VisionOptions.model` (default `SONNET_CLASS`, `claude-sonnet-5-5`; BS12 RESULT: Opus
   5.5 added no accuracy at twice the price; **the class `haiku`**, whatever id the settings map it to, is allowed for V1
   only and refused for V2 and V3 at the session's start), effort `effort.vision` (default `low`; BS12: effort moved
   neither accuracy nor output tokens), `maxTokens` 4096, `CacheHint.AUTO` (§7.4), the system parts (the service's
   instructions, the tier's instructions from `SessionTools.instructions`, the pack's `screenLegend()` when it has one
   (§12.3; BS12: the legend halved V2's actions), the vocabulary; the tool's instructions and the legend pass
   `ctx.mask().text` as the vocabulary does), the tools of the tier (§6.3), the `providerTools` for V2/V3 with the
   toolset; the meter reserved before, the ledger written after (§10).
3. **Act.** Every tool call in order through `VisionSessionTools.act`: the adapter maps it to a grounding (§6.4) and the
   recorder (§6.5) to a step, which `actions.execute(verb, args, provenance)` runs with the step protocol; the
   `StepOutcome` masked and bounded is the tool result (text), an error result for a failed step. A batch stops at the
   first failure; the skipped calls get `is_error: true` with "Not executed: an earlier action in this turn failed"
   (vision §F1).
4. **Tiers.** `V1`: marks on the capture, the tools `click_mark`, `type`, `key`, `wait`, `capture`; the model answers
   with a mark, which the loop grounds by a hit-test at the mark's centre (§6.4), which the recorder turns into a
   locator; the existing rungs act. `V2`: the toolset (or the custom tools with `click_at`); a pixel, hit-tested and
   recorded. `V3`: `V2` acting through Robot: every click, drag and wheel runs as `pointer {…, via: ROBOT}` with
   `requireFocus: true` (§3.4); **the model sees `PAINT` in V3 as in every tier** (D341); the keyboard goes the routed way
   after the focus rule of §3.4, and when the target JVM holds no focused window the tool answers with `endTurn:
   QUESTION` ("the target lost the focus; bring it to the front and ask again"); allowed only with `SessionOptions.robot`,
   which the host validates against the tier **before it constructs the service** (a usage error at start, never a
   silent downgrade; `applies` never sees that case).
5. **Limits.** `maxActions` UI actions and `maxTurns` model turns per session (defaults 30 and 20; §10), **counted and
   enforced by the service** while a tool applies (`Outcome.step` for actions; every model call, the loop's own and a
   tool's `complete`, for turns), the service's `maxActionsPerTurn` (10) per turn, the session budget; at a limit the
   service ends the turn `LIMIT` and closes the vision tools for the session (`tools()` lists none afterwards, the
   model is told why), as the budget does.

What the loop never does: run in `replay` (the tools are not loaded there: `SessionTools.applies` is false without a
gateway, and a `replay` run has none); sleep; act around the facade; send a capture of the glass or an unredacted
capture; offer a model a credential (D342: no secret slot reference resolves from a model's call, and no `type` runs
while a secret is in flight, §6.5, §9.2); type into, press a key at or click a masked component (D358); let the model
confirm anything (`confirm` is stripped from a `MODEL` origin, D158); offer `data_query`, `hook.*`, `rule.*`, `agent.*`
or a destructive primitive (`CallPolicy`, unchanged); offer an acting tool under a read-only policy (§6.3).

**As built (Phase 7, VM, 2026-10-10):** while a tool set applies, the service's own loop call is purpose `vision`, the
class of the first applying tool set's `modelClass(ctx)` (a default method `SessionTools` gains: the contract's
interface had no way to name it; D421), effort `effort.vision`, `CacheHint.AUTO`, with the tool sets' provider tools;
the pack's legend is a system part the service adds after the tool sets' instructions (the context carries no pack).
V1's capture is followed by the marks list alone in its `<target-ui>` block (with the image's size); the text snapshot
is not repeated there, since the model has `skillroy_snapshot` and BS12 found the marks list to carry what the snapshot
drops (D422). A capture in a model's view is the agent's PNG unchanged unless marks are drawn or a page composited
(the re-encoding is `javax.imageio`'s). `afterBatch` is skipped when the batch's last view was the model's own capture
and no event arrived since, and when the turn ended in the batch.

**Phase 7 security fix, S134 (built 2026-10-11, SI):** step 3's tool results put **the target's text inside a
`<target-state>` block**, as the service's own step results are (P5 §6.2 "By origin", §11 rule 3): a step's message (a
`MODAL_OPEN` with the dialog's title and text, a `NOT_FOUND` quoting a locator's text), an agent's error on the vision
route (the capture that could not be taken, the page's hit-test, the focus read, the window or the anchor), each after
the module's own words, through the session's mask with a label tag in it neutralised; the module's own refusal texts
(`MASKED`, the credential and in-flight refusals, "the screen changed …", the clipboard, read-only and limit answers)
stay bare.

### 6.3 The tool adapters

**The provider-neutral custom tools** (strict `ToolSpec`s through `ToolProjection.strict`, names `[a-z0-9_]`; B7's
neutrality; the only form that can offer a click by ref):

| Tool | Input | Maps to |
|---|---|---|
| `capture` | `{}` | a fresh `PAINT` capture (§6.2 step 1), annotated in V1 (the tier's rule, no argument); there is no mode (D341) |
| `click_mark` | `{mark: integer, button?, count?}` | V1: a hit-test at the centre of the mark's rectangle in the capture's space (`swing.hitTest {point, space: {captureId}}`, which yields the component, a table's row or a region, §3.2); the recorder's locator path (§6.5) |
| `click_ref` | `{ref: string, button?, count?}` | a ref from the text snapshot: `swing.hitTest {target: ref}` (its centre); the same path |
| `click_at` | `{x: integer, y: integer, button?: left \| middle \| right, count?: 1 \| 2 \| 3}` | V2/V3: a pixel of the last capture; grounding, then the recorder |
| `drag` | `{x1, y1, x2, y2}` | `pointer {action: DRAG}` with fractions of the hit component |
| `scroll` | `{x, y, direction: up \| down \| left \| right, amount: integer}` | `pointer {action: WHEEL}` at the point (left/right as a horizontal wheel is not supported by `swing.pointer`: refused as text) |
| `type` | `{text: string, enter?: boolean}` | `type {by: <focus owner's locator>, text, enter}` (§6.5); refused when the focus owner is masked, when the text names or contains a secret, or while a secret is in flight (D342, D358) |
| `key` | `{key: string, modifiers?: [string]}` | `press {key, modifiers}` at the focus owner; refused when the focus owner is masked (D358) |
| `wait` | `{}` | `swing.settle` with the step timeout; the result says settled or not |
| `skillroy_look` | `{question: string}` | a visual question on the last capture (§6.4): a read, no step, journaled as a `note` with the verdict |

V1 offers `capture`, `click_mark`, `type`, `key`, `wait` and the session's `skillroy_*`, `done`, `ask_person`; V2 with
the custom adapter adds `click_at`, `drag`, `scroll`, `click_ref`. **Under a read-only policy, or one that offers no
verbs** (`SessionPolicy.readOnly()`, `offersVerbs()`), the module offers `capture`, `wait` and `skillroy_look` only
(and the toolset's `screenshot`, `zoom`, `wait`); the acting tools are listed as withheld in the instructions, so that
the model is not left to find the read-only refusal one call at a time. The marks list and the text snapshot tell the
model what a mark is; it is asked to answer with a mark wherever one exists (BS12: Haiku 5.5 wrote `click(x, y)` as
text in 20 of 144 custom-tool calls and never with a mark tool beside an image, which is one reason Haiku is V1-only).

**The Claude computer toolset** (`ComputerToolsetAdapter`; V2 and V3 by default, the custom adapter by the option
`vision.tools: custom`): one `ProviderTool("claude", "computer_toolset_20260801", {configs: {zoom: {enabled:
<vision.zoom>}}})` in the request's `providerTools` (§7.1); `zoom` off by default (never called in 828 toolset calls,
411 tokens a request; BS12 RESULT "Sessions"). Member calls arrive as `ToolUse` parts named by the member with
`toolsetName: "computer"`; every result echoes it (§7.2). The mapping (vision §R1):

| Member | Action |
|---|---|
| `screenshot` | a capture (§6.2), returned as an image result |
| `zoom {region}` | the rectangle, in the capture's space, is mapped through the capture's geometry to the topmost window of the JVM at the rectangle's centre (the stacking order of §3.1), clipped to that window, and captured as `swing.capture {target: <that window>, scope: REGION, region: <rect in the window's space>, scale: <fit the usual size>}`, an image result; a rectangle outside every window is refused as text; coordinates stay the full capture's (vision §F2) |
| `left_click`, `right_click`, `middle_click`, `double_click`, `triple_click {coordinate?, text?}` | `click_at` with the button and count; without a coordinate the adapter's cursor position; `text` (modifier keys) to `modifiers` |
| `left_click_drag {start_coordinate, coordinate}` | `drag` |
| `mouse_move {coordinate}` | V2: `pointer {action: MOVE}` through the routed path (live-only; never recorded as a step, §6.5) and the adapter's cursor; V3: the adapter's cursor only (a Robot `MOVE` would move the person's pointer for nothing) |
| `left_mouse_down`, `left_mouse_up` | V2: `pointer {action: PRESS \| RELEASE}` at the cursor through the routed path (live-only); **V3: answered as text "not supported in V3; use left_click_drag"** (D343: a Robot press is never left held; a `mouse_move` with a button held cannot arise) |
| `scroll {scroll_direction, scroll_amount, coordinate?}` | `scroll` |
| `type {text}` | `type` at the focus owner, with `type`'s refusals |
| `key {text, repeat?}` | `press`, the name translated by `KeyNames` (X keysyms: `Return`, `Escape`, `Tab`, `BackSpace`, `Delete`, `Up`…, `F1`…, letters and digits; combinations `ctrl+s`, `alt+Tab`, `shift+Tab`), repeated `repeat` times (at most 100), with `key`'s refusal |
| `hold_key`, `cursor_position` | `hold_key` is refused as text ("not supported"); `cursor_position` answered from the adapter's state |
| `wait {duration}` | `wait` (the settle; the duration is ignored and said so in the result) |

Coordinates are pixels of the capture the model last saw (vision §F2), which the adapter maps back through the
capture's `captureId` in `swing.hitTest {space: {captureId}}`. An image in a toolset result must fit the model's
limits unscaled (the size rule of §3.1 keeps it at or under 1920 pixels, below the high-resolution tier's 2576; vision
§F2).

**As built (Phase 7, VM, 2026-10-10):** V2 and V3 with the custom tools offer `capture`, `click_at`, `drag`, `scroll`,
`click_ref`, `type`, `key`, `wait`, `skillroy_look`, without `click_mark` (their captures carry no marks); with the
toolset, `skillroy_look` is the one custom tool beside the provider entry. Under a read-only policy (or one without
verbs) the toolset entry's `configs` disable every acting member (`enabled: false`), leaving `screenshot`, `zoom` (when
on), `wait` and `cursor_position`. A toolset click's `text` (modifier keys) maps to `modifiers`; `zoom` maps the
rectangle to the topmost window at its centre in the capture's `windows` order, assuming no insets (the WM-less image),
at a scale that fits 1024 pixels on the long edge (at most 4).

**Fix wave (Phase 7 review, FC, 2026-10-10):** `wait` settles for the session's step timeout, as the table says, and
no longer for a fixed 5 s (L1-F6): the facade gains the default method `SessionActions.stepTimeout()`, which the service
answers from `SessionOptions.stepTimeout` (§9.1); the settle's `timeoutMs` is that value, at least 1 s and at most 110 s
(below `ExecutorSession.read`'s 120 s bound for one read, §9.2, so that the settle's own answer ends a slow wait), and
the result names it ("did not settle within 30 s").

**Phase 7 security fixes, S110, S112, S124, S137 and S138 (built 2026-10-11, SI):** (a) **No clipboard chord, no
middle click** (S110, §11 rule 11): a `key` (custom tool, toolset member) that is a paste, copy or cut chord in any
spelling the agent reads (`CallPolicy.clipboardKey`: `V` with `CTRL` or `META`, `INSERT` with `SHIFT`, `PASTE`; `C`,
`X` or `INSERT` with `CTRL` or `META`, `DELETE` with `SHIFT`, `COPY`, `CUT`), a click with `button: middle`
(`click_mark`, `click_ref`, `click_at`) and the toolset's `middle_click` are answered as text ("that would paste, copy
or cut through the person's clipboard …") before anything is grounded, read or sent; the toolset entry's `configs`
disable `middle_click` always. (b) **V2's raw halves refuse a masked target** (S112): `left_mouse_down`,
`left_mouse_up` and `mouse_move` are grounded with the stale-capture rule (§6.4) as every pointer action is, and
answered `MASKED` before anything is sent when the hit is masked or lies on a page the capture kept grey (the test
`Recorder.web` applies). (c) **A V3 click with modifier keys is refused** with a sentence the model reads ("V3 cannot
hold a key while it clicks …"), as the agent refuses `modifiers` with `via: ROBOT` (§3.4; S124). (d) **The module
refuses an acting call it did not offer** (S137): under a read-only policy, or one that offers no verbs, every action
but a capture, a `wait`, a `zoom`, `cursor_position`, `skillroy_look` and a refusal is answered "this session does not
act (read-only) …" before anything is grounded, whatever the provider sent (a member its `configs` disabled). (e) **A
turn's waits are bounded** (S138): the `wait` calls of one turn together settle for at most three step timeouts (30 s by
default, at most 330 s), each counted as what it waited (the settle's reported `ms` or the time it took, at most its
timeout), each settling for at most what is left; at the bound the call answers "not run: the wait limit of N s per
turn was reached" and ends the turn `LIMIT` (D474).

### 6.4 Grounding, the stale-capture rule, visual questions

**Grounding** of a pixel `(x, y)` of capture `C`: `swing.hitTest {point, space: {captureId: C}}` (§3.2) before anything
acts; a V1 mark is grounded the same way at the centre of its `rect` (so a table-row mark yields the table **and its
`row`**, a region mark the pad **and its `region`**; the target form of the hit-test is used for the focus owner and
for `click_ref` only). **The stale-capture rule** (D305; design §3.10 step 1 as BS12 RESULT finding 4 refined it): when
the events since `C.seq` hold a `window.opened` or `window.closed` (the set of windows changed; read through
`eventsSince`), the loop takes a fresh capture `C'` and hit-tests the same point on `C'`; it acts only when `C'`'s
`target` is the same ref (and, for a table, the same row) as `C`'s, else it answers the model with an error result "the
screen changed since that capture" and attaches `C'`. **What closes the window between the hit-test and the act**: for
the recorded step it is the verb's own exact-match locator (a `click` or `pointer` by a locator that no longer matches
exactly one component fails `NOT_FOUND`/`AMBIGUOUS`; a locator that matches exactly one *other* component after a
window change is the residual, gap 87 of §16.2); for the live-only raw `swing.pointer` calls (`mouse_move`, the V2
button halves) and for an MCP client's call it is `expectTarget: <ref>` (§3.3), which no recorded step can carry
(a ref is a per-start handle). A `surface: WEB` hit goes to `web.hitTest {space: COMPONENT}` (§4.8); `HEAVYWEIGHT` or
`NONE` cannot be grounded and is answered as text to the model.

**Visual questions** (`VisualQuestion`; vision §R5): one request through `actions.complete` (purpose `vision`, metered
and ledgered, §10) with the capture as an `Image` part, the question in a provenance block, the pack's legend, and the
structured output schema `{verdict: "YES" | "NO" | "UNSURE", evidence: [{mark?: integer, box?: [x0, y0, x1, y1]}],
explanation: string}` (no numeric bounds in the schema, vision §F5); `UNSURE` is a result. The question's block says
whose words they are: `<person>` for an `aiExpect` (the scenario author's) and **`<model-question>`** for the session
tool `skillroy_look {question}` (the model's own text, which screen content may have steered; `PromptMask.LABELS` gains
the label and it is declared data like the others, P5 §6.2). `skillroy_look` is a read with no step, journaled as a
`note` with the verdict and the capture's hash, never a check that holds. The verdict is advisory: in a scenario it
stands behind the guard `inference.vision` (§8.4), and it never turns a failing scenario green.

**As built (Phase 7, VM, 2026-10-10):** the stale-capture rule reads `eventsSince(C.seq, "window.opened,window.closed",
1)` through the facade; the fresh capture keeps the tier's annotation; "the same target" is the same `target` ref and
the same `row`. A hit-test of a `NONE` surface is answered as text, a `HEAVYWEIGHT` one is recorded as an anchored step
(§6.5's rule wins over this section's sentence for `HEAVYWEIGHT`; D423). A visual question is one request of
`maxTokens` 1024 with the schema strict (`mark` and `box` nullable), the question in its labelled block and the legend as
a system part; an answer with no structured verdict is `UNSURE` with the stop reason.

**Phase 7 security fix, S114 (built 2026-10-11, SI): "C's target" is what the capture showed.** Both hit-tests of the
rule are live (the agent resolves a capture's pixel to the topmost window **now**, through the capture's geometry), so
comparing them caught only a window that moved the composite's origin, and a dialog that opened over the point inside
the composite's bounds was named by both and clicked (L6-S3). The rule now also compares the hit with the capture's own
view: **V1**: the hit's `target` is the mark's own `ref` (`Capture.Mark.ref`), **always**, not only after a window
event; or, for a composite whose centre is a child (an editable combo box's editor, a spinner's field), the component
the mark's component draws at its centre now (`swing.hitTest {target: <mark ref>}`'s `mouseTarget` or `deepest`) in the
window the capture shows at the pixel. **V2 and V3**: when the window set changed since the capture, the hit's `window`
is the window the capture shows at that pixel (`Capture.windowAt`, topmost first). Otherwise the model is answered "the
screen changed since that capture" with a fresh capture, nothing acted. The fresh capture and the second hit-test of the
same point are kept after a window event, as before (D473). An in-window change (a glass-pane dialog, no
`window.opened`) stays outside the rule's trigger for V2 and V3, the residual; V1's mark rule catches it unless the new
content is the mark's own component.

### 6.5 The recorder: from a model's action to the step that will be replayed (D306-D308, D342, D357, D358)

For one grounded action on `target` with hit-test result `H` (vision §R3; design §3.10 "Record once"):

1. **Choose the locator**: the first of `H.locators` (domain strategy first, then `id`, `name`, `text`, `class`, `path`;
   each already checked to match exactly one component, §3.2). A locator whose value is text the session's mask would
   change (`by: {text: …}` equal to a joined credential) is never chosen (the export would refuse it, P5 §12.3).
2. **Refuse what a model never does** (checked before any step is built):
   - a target the hit-test reports **`masked`** (a password field, a component a `CaptureMask` names, §3.2): no `click`,
     no `pointer`, no `type`, no `press` and no toolset `key` at it, with the text "the component takes a secret; ask the
     person to sign on" (D358). The sample's mask names the number pad while the entry field is
     masked (§12.2), so digits cannot be spelled into a secret by region clicks either;
   - **(Phase 8, built 2026-10-19, EA; P8 §3.2, D507)** a target the hit-test reports **`vetoed`** (an `InputVeto`
     of the application's module refuses input to it): refused as a masked one, on every gesture, for a vetoed focus
     owner (`type`, `press`) and in V2's raw pointer, with `Recorder.VETOED`;
   - a `type` whose text names a secret slot reference (`$<slot>.<key>` where the session's credential joins hold
     `key`), or **any step, a pack verb's included, whose compiled calls would reference a credential join's secret
     field** (`ExecutorSession` refuses both for every origin but a person's before anything is journaled or sent, §9.2:
     a model never signs on with a person's credential), or a `type` whose text contains a secret value the session's
     mask knows (`ctx.mask().holdsSecret(text)`), or any `type`, printable `press` or raw `swing.type` **while the
     session holds a secret in flight** (`actions.secretsInFlight()`, §9.2: a credential a person resolved in the
     session and no person's step has consumed yet): "a credential is never used on a model's behalf; the person signs
     on first" (D342). Sign-on is a person's or the player's `signOn` step before the loop (§6.8, §11).
     **(Phase 7 security fix, S101, S127, built 2026-10-11, SH)** A secret slot reference includes the joined slot as a
     whole (`$<slot>`, alone or embedded), and while a credential is in flight `ExecutorSession` refuses every call of
     the model's that changes the target, not only typing (§9.2 rules 1 and 2).
     **(Phase 7 follow-up, gap 94, built 2026-10-11)** The recorder reads a `type`'s text with the substitution's own
     tokenizer (`References.references`: `$$` a literal dollar, a field ending at the first character outside
     `[A-Za-z0-9_]`) and refuses it with the text above, before anything reaches the session, when a reference's first
     field looks secret by the mask's key rule or when the facade's `namesSecret(text)` answers true (§9.1: rule 1 of
     §9.2 over the session's credential joins, the joined slot as a whole in every spelling); nothing is journaled, and
     `ExecutorSession` stays the gate.
3. **Choose the step** (the roles are `Roles.ALL`'s, the closed set the agent reports: `window, dialog, panel, button,
   toggle, checkbox, radio, textbox, password, label, combobox, list, table, tree, tab, menu, menuitem, scrollpane, text,
   unknown`):
   - a click on a component whose position does not matter: `H.target`'s role is `button`, `toggle`, `checkbox`,
     `radio` or `menuitem`, or `H.fires` is true (a registered `ActionFirer` accepts it, §3.2), or the chosen locator is
     a `describe` strategy → the base verb `click {by: <locator>, rung?}`: the session's `ui.clickRung` (ACTION unless the
     session's `UiSettings` say COMPONENT, then with `fallback: ACTION` as `basic-loan-ui.yaml` does; map 7 Q5), `await`
     none;
   - a click where the position inside the component matters: the role is `textbox`, `text`, `table` (with `H.row`),
     `list` (with `H.row`), `tree`, `combobox`, `scrollpane`, `tab` (which tab depends on the point), `menu`, `label`,
     `panel`, `window`, `dialog` or `unknown` (every role of the set but the five above and `password`, which is always
     masked), or `H.target`'s class is `javax.swing.JSlider`, `JSpinner` or `JScrollBar`, or `H.region` is set →
     `pointer {by: <locator>, region?: <H.region.id>, row?: <H.row>, at: {fx, fy}}` (the point as fractions of the
     component's, the row's or the region's bounds; no pixel stored); a `double_click`, `drag`, `scroll` likewise with
     their action;
   - a click on a component with no locator and no region, or on a `HEAVYWEIGHT` surface → `pointer {by: <the window's
     locator>, at: {fx, fy}, guard: <label>}`: an **anchored** step (D357). The recorder takes the anchor **with the
     guard's own call** through `actions.read`: `swing.capture {target: <the window>, scope: REGION, region: <the 64 x 64
     rectangle around the point, clipped to the window>, redact: true}` (the root pane's paint, §3.1, so the anchor and
     the guard's capture at replay are the same paint), writes the raster as `captures/<hash>.png` (§9.6), reads
     `swing.rendering` once per session (cached; `renderKey`, the `rendering` object), takes the crop's `redacted`
     rectangles, `carets` and `focus` from that capture's result and the window's size and insets, and keeps all of it
     under the label `anchor-s<n>` for the provenance and the export (§6.6, §8.7);
     the step replays only when the crop still matches (§6.7);
   - `type` → `type {by: <the focus owner's locator>, text, enter?}` with the focus owner described through
     `swing.hitTest {target: <focus owner ref>}` (§3.2); no focus owner → an error result "nothing has the focus; click
     into a field first";
   - `key` → `press {key, modifiers?}` (the focus owner, by `swing.key`'s rule);
   - `wait`, `screenshot`, `zoom`, `cursor_position`, `skillroy_look` → no step; in V2 `mouse_move`, `left_mouse_down`
     and `left_mouse_up` run as raw routed `swing.pointer` calls through `actions.execute(PlannedCall, null)`
     (live-only, so the model can hover or drag by halves) and are journaled as raw steps the export leaves out; in V3
     the two button halves are refused as text (D343) and `mouse_move` moves the adapter's cursor only;
   - V3 → every click, drag and scroll runs as `pointer {…, via: ROBOT}` (the facade sets `via`, never the model, D347):
     journaled with `via: ROBOT`, **listed as live-only in the export and never exported as a step** (its `requires`
     would name `swing.pointer.robot`, which no CI target has).
4. **Act through that step**: `actions.execute(verb, args, provenance)` (the facade passes `Origin.model(model, intent,
   requestId)` and the provenance into `ExecutorSession.execute(verb, args, origin, provenance)`, §9.2), the verb's own
   sync (`click`, `type`, `press`, `pointer` compile to `SETTLE`); the consequence observed while recording is the
   consequence of the recorded step, so a difference between "clicked the pixel" and "fired the component" shows in
   front of a person (design §3.10 step 3). A `web` hit → `webClick {browser?, role, name}` when `web.hitTest`'s `{role,
   name}` locator is unique, else a raw `web.pointer` call (live-only).
5. **The provenance** handed in with the act (§9.2): tier, via, the intent (masked, bounded), the model and request id,
   the capture's hash, size, `seq`, `origin`, `windows` and file, the point, the hit-test's ref, name, class, locator,
   fractions, region and row, the mark, and for an anchored step the `anchor` object (label, rectangle, hash, file,
   `renderKey`, `rendering`, window, and the crop's `masks`, `carets` and `focus`, which the export's entry needs, §6.6).
6. **Export** (§6.6).

**As built (Phase 7, VM, 2026-10-10):** the recorder also refuses a `type` whose text holds a `$slot.key` reference whose
key the mask's key rule calls secret (`$staff.password`), before the facade and `ExecutorSession`, which hold the exact
rule over the session's joins. A locator is "a `describe` strategy" when it has a key outside the base grammar
(`id`, `name`, `role`, `text`, `class`, `index`, `path`, `regex`, `ignoreCase`, `within`). `click` is chosen for a left
single click without modifiers on a position-insensitive target outside V3; every other click is a `pointer` with the
action (`DOUBLE_CLICK` for a count of 2 or more, `clickCount` for 3, `button` and `modifiers` when not the defaults);
the fractions are rounded to three decimals; a row's `fy` is 0.5. The anchor's label is `anchor-s<n>`, n counting the
session's anchors from 1; the `anchor` object carries `label`, `rect` and its `x`, `y` (the crop's origin in the window's
space), `sha256`, `width`, `height`, `file`, `renderKey`, `rendering` (without `renderKey` and `keyed`), `window`
(`size`, and `insets` derived from the composite: the root pane's screen position minus the window's), `masks`,
`carets` and `focus {owner, ownerRect?, active}`. A `webClick` is recorded without `browser` (a ref is a per-start
handle; the single showing browser is the agent's default). In V3 a focus loss reported by the agent (`not focused`)
ends the turn `QUESTION` as the focus rule does.

**Fix wave (Phase 7 review, FC, 2026-10-10):** (a) Step 2's masked refusal comes **first, for every surface** (L2-F2):
a hit whose `masked` is true is refused before the `WEB` branch, and a `WEB` hit whose surface the capture reports
`redacted` (§3.1, §4.8) is refused the same, with the same text; so a page a pack masked is never hit-tested
(`web.hitTest` is not read) and none of its text (the element's role and name) enters a step's arguments, the journal,
the export or a tool result. (b) An anchored `DRAG` records `to` as its end point's fractions of the window, mapped as
`at` is (`(origin + to / scale) - window`), not `at` again (L1-F3). (c) The anchor's `window.insets` are the root pane's
position in the window: a `WINDOW` capture's `origin` (§3.1), read once per window per session through `actions.read`
(`scope: WINDOW`, `scale: 0.25`, `redact: true`; the pixels are not used), `{0, 0}` when that read fails (the insets are
the reviewer's information; the guard does not depend on them, §6.7). The build subtracted the window's position from
the composite's `windows[].rect`, which is the window's own bounds, so its insets were zero by construction (L1-F4).
(d) The node whose `role`, `class` and `name` choose between `click` and `pointer` and fill the provenance's `hitTest`
and the export's comment is, for a target-form hit (`click_ref`, the focus owner), the hit-test's `targetNode` (§3.2);
from an agent without it, the `mouseTarget` or the `deepest` node that is the target itself, else the `mouseTarget`,
else the `deepest`; the point form keeps its rule (the `mouseTarget`, else the `deepest`) (L1-F5, D446).

**Phase 7 security fixes, S109, S130, S132 and the recorder's half of S146 (built 2026-10-11, SI):** (a) **A page
under a modal dialog takes no click** (S109, step 4): a `WEB` hit whose window a modal dialog blocks (the hit-test's
`blockedBy`, in-window dialogs included) is refused ("a modal dialog blocks the page; answer the dialog first") before
the page's hit-test is read or anything is sent, as the routed pointer refuses it (D294, D335); before, the click was
acted as `webClick` (`web.dom.click`, which has no modal check) whenever the page proposed a unique `{role, name}`
(L4-S2; D472). (b) **A `webClick` only where a recorded step would act** (S130, S132, step 4): the recorder records
`webClick {role, name}` only for a hit found in the frame the call started in (no `iframe` in `web.hitTest`'s answer:
`unique` is that frame's library's word, §4.2) **and** in the browser the step without `browser` resolves to: with a
default browser in the session's web settings (the facade's new default method `SessionActions.webDefaultBrowser()`),
the browser that selection names now (`web.hitTest {by: <default>, point: <the hit's viewportPoint>}`, the agent's own
selection rule), else the only showing browser (`web.browsers`); a read that fails counts as another browser. Otherwise
the click is the live-only raw `web.pointer {browser, point, space: COMPONENT}` with the reason in the provenance's
`liveOnly` ("the element lies in a child frame; a frame key is a per-load handle …", "the page lies in a browser a
recorded webClick would not reach …"). No uniqueness read beyond the starting frame's own `find` is made (D477). (c)
**A text field is never named by its content** (step 1, S146's rule on the recorder's side, SG's handover): a
`{text, …}` candidate of a component whose role is `textbox`, `text` or `password` is never chosen (its text is what a
person or a model typed, which the step would carry into the journal, the export and a committed scenario, and which
the next `type` changes); the `{class, index}` or `{path}` candidate names such a field (D479). (d) Step 2 also
refuses a middle click (S110) and, in V3, a click with modifier keys (S124), before anything is built (§6.3).

### 6.6 Export into ordinary steps (D309, D355, D356)

A step with provenance exports as the verb step it was journaled as (P5 §12.3 rule 1 unchanged), followed on its line
by one masked comment through `YamlStep.comment` (`JournalExport`'s private record): `# aiAct "<intent, cut at 80>" by
<model>: <role> "<name>" (<class>)`. **The comment carries no capture hash, no handle and no mark number** (D356): the
hash differs between the Java lines (BS11: the JDK line changes the desk's pixels) and between two runs on one Java (the
caret's blink phase), and a handle is a per-start counter, so a committed export that carried them could never be
compared byte for byte (§13.2 item 8). Those go into the report, which gains the section **"Vision-derived steps: review
the locator and the rung"** with one line per such step (as D86's observed expectations: the step, the capture's hash
and file, the hit-test's ref, the mark, the point). V3 steps, raw `swing.pointer`/`web.pointer` calls and the loop's
reads are left out of the steps and listed under **"Live-only"** in the report with the reason. `requires` follows the
exported calls' primitives as today (`click` by locator requires nothing new; `pointer` requires `swing.pointer.routed`).

**Anchors.** The export writes the `images` section of an anchored step into its own `BindingsFile` (version 2, §8.7:
the entry from the provenance's `anchor` object: `sha256`, `width`, `height`, `masks`, `carets`, `focus`, `rendering`,
`window`, `anchor {x, y}`, keyed by the provenance's `renderKey`), and `ExportResult.images()` (`Map<String, byte[]>`,
label to the masked PNG read from `captures/<hash>.png`) hands the pixels to the export's caller to place under
`<scenarioId>.images/` beside the file; a `JournalExport` reads the file through `ExportOptions.captures` (the session's
output directory; the journal read back from its file exports the same, P5 §12.3's rule). An anchored step whose
capture file is absent (a session without `--out`) is an `ExportException` naming the step, listed in the report. The
export's PNGs are reviewed like its YAML (threat model §3). The committed example is the sample's
`exported/session-vision-loan.yaml` (§12.4), generated by §13.2 item 8 under `-Dskillroy.inference.fixtures.update=true`
on one named leg (VM's report says which; the Java 25 arm64 leg by default), as `SessionExportTest` generates the Phase 5
export; `HeadlessDesk` cannot produce it (it answers no `swing.*` method).

**(Phase 7 security fix, S146, S147, S120, built 2026-10-11, SJ.)** Three rules of the export's `images` section. (a)
**The export's bindings file, images included, is refused when its text holds a masked value** (P2 §11.4): the
`<redacted>` check runs again after the anchors' entries were added (it ran on the slots and oracles only), and an
anchor entry the journal masked (its `focus.owner`, its `window`) is an `ExportException` naming the anchor and its
step, never an entry that says `<redacted>` (S146). (b) **An anchor whose label is not a label is an `ExportException`**
naming the step (`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, checked where the journal's step is read, before any path is built
from it), and the `BindingsFile` constructor refuses a label or a render key its reader would refuse and two labels
that differ only in case, so that a file that cannot be read back is never written (S147; a pack's own session tool is
the actor that could write such a label into a provenance). (c) **The export writes its own bindings file with the
renderings this session recorded only**; the report's **Notes** carry one line per anchor entry to merge, `to merge:
images.<label>.<renderKey> (<scenarioId>.images/<label>.<renderKey>.png) into the committed bindings file beside its
other renderings' entries; the export's bindings file holds this session's renderings only`, and the person merges the
entry into the committed file (S120, option (a): the export stays a pure function of the journal; option (b), the CLI
merging into an existing file at the destination, is not built; D483). The lines are notes rather than a new report
section so that a played session's export, whose report `ScenarioPlayer.annotate` rebuilds, keeps them.

### 6.7 Pixel-anchored replay, the image guard, `expectImage` and the comparison (D313-D315, D357)

**The comparison** lives in `skillroy-scenario` (`…scenario.image.ImageCompare`), not in the vision module, because
`replay` must run an anchored step and an `expectImage` without the module (D313; design §3.10 placed "image comparison"
in `skillroy-vision`, which this contract amends: the module keeps grounding, marks and visual questions, the base
keeps the deterministic compare). It compares decoded ARGB rasters **exactly** (BS11 RESULT "Tolerance study": within
one rendering key the measured noise is zero and no tolerance separates environment noise from a one-character change,
28 to 50 pixels): both images must have the same size; the masks (the baseline's mask rectangles, the union of the
baseline's recorded `carets` and the current capture's `carets`, and, when the baseline's recorded `focus.owner`
locator and the current capture's `focus.ownerLocator` differ, both focus owners' rectangles (the entry's
`focus.ownerRect` and the capture's `focus.ownerRect`, image coordinates), said so in the result) are blanked on both
sides; the raster hashes (`ImageCompare.rasterHash`, the byte layout of §3.1, one test vector shared with
the agent's tests) are compared; equal hashes hold; otherwise the differing pixels are counted, and the check holds only
when the count is at most `maxDiffPixels` (default 0, the one explicit margin). The result `{equal, differing,
maxChannelDelta, box, maskedPixels, focusMasked}` goes into the run log and the detail; on a mismatch both PNGs go to
`failure/image-<label>-expected.png` and `…-actual.png`, and a diff image `…-diff.png` (red above 32 in a channel,
orange below, grey equal), bytes never in a text route.

**One code path for both hosts** (`…scenario.image.ImageSteps`, package-level): the capture call, the lookup of the
label's entry by the current `renderKey` (`swing.rendering` read once per run, cached, a read allowed after the start
mark), the compare, the files and the run-log record. `PlanExecutor` runs it for an `ImageCheck` **with the step's own
capture call result handed to the check** (checks today read facts and rows; this one reads the step's call), and for a
guarded `pointer` call before the pointer call; `ExecutorSession` exposes it to the player as `expectImage(Plan.Step
step, Origin origin)` and runs a guarded `pointer` step through it too; the session's `expectNow` is unchanged (it still
evaluates `Expectation`s only).

**An anchored `pointer` step** (`Plan.Call.guard {label}`, §8.2): before the pointer call the executor reads the entry
of the current `renderKey` for the label (`anchor {x, y}`, `width`, `height`), takes `swing.capture {target: <the step's
window>, scope: REGION, region: <that rectangle in the window's space>, redact: true}` (the root pane's paint, as the
recorder did) and compares it with the entry; a match lets the pointer call run; a mismatch, or no entry for this
rendering, is `REBIND_NEEDED` with reason `IMAGE` **in every mode, `approve` included** (an anchor is recorded by a
session and never approved at a step: without an entry for the current key the guard's rectangle is unknown, so the
person re-records the step through `session --play`, whose export writes the new key's entry into the export's own
bindings file; the person merges it into the committed one, and the report names the anchors and keys to merge, §6.6
(Phase 7 security fix, S120, built 2026-10-11, SJ: this sentence said "writes the new key's entry beside the old ones",
which nothing built)) (the detail: `label`, both hashes, the counts, the files; the
"recorded answer no longer fits" semantics of P2 §10, never a verdict on the application). Compared before the start
mark? No: the capture is a read sent to the target, which D30/Q11 allows only after the start mark; an anchored step's
guard therefore runs at its step.

**`expectImage`** (§8.1): the step's one call is `swing.capture {target|by?, scope: WINDOW | COMPONENT | REGION,
region?, redact: true}` (`clip` → `scope: REGION` with the rectangle, `region: <id>` → `scope: REGION` with the id) and
its one check an `ImageCheck(label, masks, maxDiffPixels)` compared with the baseline of the current `renderKey`: a
mismatch is `FAIL / ASSERTION` (the step's detail as above); no baseline for this rendering is `REBIND_NEEDED(IMAGE)`
in `replay`; in `approve` the capture is the candidate baseline: **the person sees the capture's file** (the CLI prints
its path and size, writing it under `candidates/` in the run's output directory first) and approves it through
`Approver.reviewImage(ImageApproval)` (whose default delegates to the approver's `review`, so `--yes`'s `APPROVE_ALL`
approves and a terminal-less `REJECT_ALL` rejects, the step then `REBIND_NEEDED(IMAGE, "candidate not approved")`; the
CLI's terminal approver asks; §8.7), and the bindings file is rewritten after the steps with the new entry; in `live` a missing baseline is recorded and the check is skipped with the
reason "no baseline for rendering <key>" (the run at best `DEGRADED`), so that a first live run can be followed by
`approve`.

**(Phase 7 security fix, S103, built 2026-10-11, SJ.) The focus mask has a bound** (D481). When the recorded
`focus.owner` and the capture's `focus.ownerLocator` differ, the two owners' rectangles are blanked on both sides only
as far as **they cover at most a quarter of the compared raster together** (the baseline's width times height,
divided by 4, the union counted once): a rectangle that alone covers more is not blanked, and when the rest still
covers more together none is; what is not blanked is **compared**. So a focus owner whose rectangle covers the compared
region (the compared component itself holding the focus at approval, the focus elsewhere or nowhere at replay, which a
session recorded from a terminal and the WM-less display give) no longer blanks it and makes the check hold whatever the
pixels show; a component that draws no focus indication still holds, and one that does shows a mismatch whose message
ends `the focus owner changed (<recorded> to <current>) and covers N % of the compared raster; it was compared, not
masked`. The record and the detail gain `focusMaskedPixels` (the pixels the focus rectangles blanked) and, when a
rectangle was compared instead, `focusMaskSkipped: [{side: recorded | current, owner, rect, pixels}]`; `focusMasked` is
true when the owners differ and no rectangle was skipped. The anchored step's guard takes the same path, so a guard
whose anchor's owner covers the crop is `REBIND_NEEDED`, never a press at the recorded point over pixels that differ.
§3.1's `ownerRect` and §8.7's `focus` are used only within this bound. A baseline approved while the compared component
held the focus may need re-approval if the focus differs at replay (the release note).

**(Phase 7 security fix, S145, built 2026-10-11, SJ.) The mismatch's PNGs are written as compared:**
`failure/image-<label>-expected.png` and `-actual.png` hold both rasters with the comparison's masks (the entry's masks,
the step's `mask`, both sides' carets, the focus rectangles that were blanked) blanked on both sides, as the diff
already did and as the baseline holds them, so the rectangle a step's `mask` hides never reaches run output.

**(Phase 7 security fix, S117, built 2026-10-11, SJ.) The person sees the candidate before approving it.** A
candidate that cannot be written under `candidates/` (a directory `OutputFiles` refuses, an I/O error) ends the step
`ERROR / ENVIRONMENT` naming the directory, instead of asking about no file; and a run without an output directory has
no candidate file, so the CLI's terminal approver rejects it without asking (`candidate not approved (no candidate file
to review: run with --out)`, the step `REBIND_NEEDED(IMAGE)`); only `--yes` (`APPROVE_ALL`, the contract's exception,
and the JUnit glue's `skillroy.approve=all`) approves a baseline unseen.

### 6.8 The scenario player (D311)

`ScenarioPlayer` runs a committed scenario whose plan is `inference: run` (it has `aiAct` steps) or that has `aiExpect`
steps, over one `ExecutorSession` in `live` mode and one `SessionService` with the vision tools, **both opened by its
host** (`SessionCommand --play`, or the IT of §13.2 item 8) with `SessionOptions.host = {"vision-session",
"vision-tier:<tier>"}` and the person's `robot` flag and handed to the player for the run (the module constructs
neither, §6.1): it compiles the scenario, binds its slots through
`ExecutorSession.resolve` (the session's live engine), then for every step in order: a deterministic step (`signOn`,
`click`, `pointer`, …) through `execute(verb, args, Origin.person(by))` with the plan's own expectations evaluated
through `check` after it, an `expectImage` or an anchored `pointer` through the session's `expectImage` route (§6.7);
an `aiAct {intent}` as one vision turn (§6.2) under `Origin.model`, ended by the model's `done` (the step passes), by
`ask_person` (the player stops and asks the person at the terminal; in a non-interactive run the step fails `ASSERTION`
"the model asked a question"), or by a limit, the budget or a tool's `endTurn` (the step fails `ENVIRONMENT`); an
`aiExpect {question, expect}` as one visual question (§6.4) through `SessionService.visionCall` (metered, ledgered,
§10) whose `YES`/`NO` against `expect` holds or fails `ASSERTION`, and whose `UNSURE` skips the check with the reason
(the run at best `DEGRADED`; D317). The guard admits the check because the session's merged matrix
(`ExecutorSession.capabilities()`) has `inference.vision OK` through its host feature (§8.5). **A step's `tier`,
`model` and `maxActions` narrow the session's `VisionOptions` and never widen them**: a step cannot raise `maxActions`
above the session's, pick a model class the session refuses, or ask for `V3` without `--allow-robot` on the `--play`
command (refused at compile of the scenario's run, a usage error). Sign-on comes first: the scenario's `signOn` step
runs under the person's origin before the first `aiAct`, so no credential is in flight when the loop starts (D342).
The player ends with the session's export offered as the scenario's deterministic form (the `aiAct` steps replaced by
what the model did, the `aiExpect` steps dropped and listed in the report, the anchors in the export's bindings file and
PNGs), which is how an authored live-only scenario becomes a CI one. Reachable as `skillroy session --play
<scenario.yaml> [--vision …] [--export <id>]` (§9.3) and as a Java API; never from `PlanExecutor`, never over MCP (a
client authors through the planner, P5 §10, whose server has no gateway).

**As built (Phase 7, VM, 2026-10-10):** the player takes the host's `VisionSessionTools` instance (or null) so that a
step can narrow the model class, the tier between V2 and V3 and `maxActions` (a narrowed turn ends `LIMIT` at the
step's limit); a step's tier that would change to or from V1 changes the tool vocabulary the service fixed at its start
and is a usage error like a widening one (D424). A fixture slot is bound through `ExecutorSession.fixture(slot, value,
credential, origin)` as the person (§9.2's note, D425); a host may pass its own `FixtureBinder`. An `aiExpect`'s capture is a `swing.capture {scope: SCREEN, mode: PAINT, redact: true,
annotateRefs: <V1>}` through `ExecutorSession.read` with the marks drawn, its verdict journaled as a note. The player hands every `expectImage` and anchored `pointer` the scenario's baselines, `ExecutorSession.baselines(root, env, scenarioId)` read once per play, when its host gave a bindings root and an environment (`withBaselines`; D359); without them a session holds none (an `expectImage` is recorded and skipped, an anchored step `REBIND_NEEDED`).

**Fix wave (Phase 7 review, FC, 2026-10-10):** **`verify` is evaluated** (L1-F2, D443). After the last step the
player evaluates the plan's `verify` in file order through `ExecutorSession.check(path, matcher, Origin.person)`: one
read each, as the session's `expect` steps take it (the last step's own sync has run), each journaled as a `check`, so
that the session's export carries the post-conditions as expectations after the last step; when one does not hold the
play is `FAIL / ASSERTION` with the first that did not, after all were evaluated; a guarded expectation whose
capability the session's merged matrix does not have `OK`, and a stored-row (`db`) check, which a session does not
evaluate, are skipped with the reason (the play at best `DEGRADED`). The block is reported as one step `verify` after the
scenario's steps; a play that failed at a step does not reach it. Without this a played scenario was `PASS` whatever its
post-conditions said, where a plan run of the same file checks them (P2's run order). **(Phase 7 security fix, S113,
built 2026-10-11, SH)** The scenario's slots are the person's for the whole play: the player binds them as the person,
and a model's `skillroy_resolve` of one of them in an `aiAct` turn is refused before anything is resolved or journaled
(§9.2, D425's note), so the person's later steps act on, and the `verify` judges, the scenario's values; the model may
resolve a slot under another name, which the export marks `REQUIRED` (D97). **The dropped `aiExpect` steps
are listed** (L1-S1): `ScenarioPlayer.Result` carries one line per `aiExpect` step of the scenario (its line, its
question masked and cut at 200 characters, its expectation, and its status in the play or "not reached"), and
`Result.annotate(ExportResult)` adds them to the export's report under "Live-only" (a visual question needs a model;
no new report section), which `session --play --export` applies. A step whose outcome is `DEGRADED` without a message
(an `expectImage` without a baseline) reports its skipped line as its message.

---
## 7. The gateway changes (`skillroy-inference`, the SPI of P5 §3)

All additive, so that the committed recordings keep their fingerprints (P5 §4.2 "Determinism"): a new field enters the
fingerprint only when it is non-null or non-empty; every older constructor is kept.

### 7.1 Provider-defined tool entries (D318)

```java
/** A tool entry a provider defines and the gateway passes through unread (the Claude computer toolset); another
 *  provider's gateway refuses it (INVALID_REQUEST). Mirrors Content.Opaque. */
public record ProviderTool(String provider, String type, Map<String, Object> raw) { … }   // raw copied, JSON-like
// ModelRequest gains the eleventh component List<ProviderTool> providerTools (the ten-argument constructor keeps it
// empty); Builder.providerTool(ProviderTool); fingerprintJson adds "providerTools" when non-empty.
// GatewayCapability gains PROVIDER_TOOLS: ClaudeGateway declares it; RecordedGateway declares it; ScriptedGateway too.
```

`ClaudeMapping.params` adds, for each `ProviderTool` of provider `claude` and type `computer_toolset_20260801`, an
`addTool(ComputerToolset20260801)` built from `raw.configs` (per member `enabled`, `deferLoading`); another type of
provider `claude` is passed as a raw tool union through the SDK's JSON mapper; another provider's entry is
`INVALID_REQUEST`. `tool_choice: auto` stays (a forced choice is a 400 on the 5.5 models). The fallback header
(`fallbacks: true`, P5 §4.1) is sent as today; whether it coexists with a toolset entry is the first live check's job
(§13.3; map 4 T9), and the gateway drops the header for a request with a provider tool only if that check says it must
(an "As built" note then records it).

**As built (Phase 7, VM, 2026-10-10):** `ClaudeMapping.providerTool` builds `ToolUnion.ofComputerToolset20260801` with
`configs` read by the SDK's own mapper (snake-case member keys: `zoom`, `left_click`, …); another `claude` type goes out
as a raw union of its `type` and fields; a request with provider tools and no own tools still sends `tool_choice: auto`;
the token count's body carries the entry. The fallback header is sent with a toolset as today (no live check ran in the
build; §13.3 item 3 is the orchestrator's).

### 7.2 `toolset_name` and image parts in a tool result (D319, D320)

`Content.ToolUse(id, name, input, toolsetName)` and `Content.ToolResult(toolUseId, content, error, toolsetName)` gain a
nullable fourth component (the three-argument constructors keep it null; `Prompts.part` writes `toolsetName` only when
non-null, so existing fingerprints stand). `ClaudeMapping.part` reads `toolset_name` from a `tool_use` block instead of
dropping it; `block` sets `toolsetName` on both param builders ("a result that omits it is rejected", map 4 §3 item 9).
`ToolResult.content` may be a `List<Content>` of `Text` and `Image` parts beside a text or a JSON-like value: the
compact constructor keeps such a list as parts (every element `Text` or `Image`, else `IllegalArgumentException`), and
**an error result with an image is refused at construction** ("an error result carries text only", BS12 RESULT
"Sessions": a 400 otherwise). `ClaudeMapping.block` renders a parts list with `contentOfBlocks` (`Block.ofText`,
`Block.ofImage`), an image only while `images` is on (`UNSUPPORTED` otherwise, as for a message's image part);
`Prompts.part` renders nested parts (an image by its hash); `PromptMask.part` masks the nested texts and passes an
image under `withImages(true)`.

**As built (Phase 7, VM, 2026-10-10):** a `tool_use` block's `toolset_name` is read back bounded like a provider word; a
parts list is any non-empty list whose first element is a `Content` (every element `Text` or `Image`, else
`IllegalArgumentException`); `ToolResult.parts()` answers it; `Prompts.partFromJson` reads a result's parts back as JSON
(an image cannot be). `Recording.json` and `LedgerWriter`'s logged prompt pass parts through `mask.withImages(true)`,
since `Prompts.part` writes an image by its hash only.

### 7.3 `IMAGES` on the recorded and the scripted gateway, the price row, the `vision` purpose and effort (D321, D322)

`RecordedGateway.capabilities()` declares `IMAGES` and `PROVIDER_TOOLS` (a recording is keyed by a fingerprint that
hashes the image, so it can answer; the mask still never writes bytes); **`ScriptedGateway` declares `IMAGES` too**
(the CI route of §12.4 runs the vision loop on it, and the loop checks the gateway's capabilities before it sends an
image); `Recording.json`/`read` round-trip the new fields; every opener that may see an image passes
`mask.withImages(settings.images())` (the session hosts of §9 do; the contributor and the job already do). `PriceTable`
gains `claude-haiku-5-5` at USD 0.10 / 0.50 per million tokens, cache read 0.01, with the note that the prompt-size tier
above 100K tokens (0.50 / 2.50) is not modelled; the Haiku **class** default stays `claude-haiku-4-5` (D84 stands): the
V1-only rule of §6.2 is stated on the class `haiku`, whatever id the settings map it to, and the `inference-live`
profile gains a settings file (`src/test/resources/inference/inference-live.yaml`, which VM adds: `InferenceLiveIT`
builds its settings in code today and the profile sets a system property only) that maps `models.haiku` to
`claude-haiku-5-5`; `VisionLiveIT` reads it for §13.3 item (4). `CostLedger.Entry.purpose` gains the value `vision`;
`InferenceSettings.DEFAULT_EFFORT` gains `vision: low` and the reader accepts the key (P5 §4.4's effort list grows by
one; no other new key: `images` stays the one switch, still file-only). `Effort` keeps `LOW`, `MEDIUM`, `HIGH`.

**As built (Phase 7, VM, 2026-10-10):** both gateways declare every capability; `ScriptedGateway.countTokens` adds an
image's tokens (§7.5's formula). The Haiku 5.5 row's note is `PriceTable.tierNote(model, inputTokens)`, added to a
call's usage note when the input (cache reads and writes included) exceeds 100,000 tokens. `CostLedger.Entry` gains the
constant `PURPOSE_VISION`. The live profile's settings file is `samples/skillroy-sample-lending-scenarios/src/test/
resources/inference/inference-live.yaml`.

### 7.4 The automatic cache hint (D323)

`CacheHint.AUTO`: the request's system prefix keeps its explicit breakpoint and the Claude mapping adds the top-level
`cache_control {type: ephemeral}` the provider advances to the last cacheable block (the vendor's robust combination
for agent loops; map 4 Q4). The loop uses it; the other purposes keep `SHORT`. Within a turn the history is append-only
(no client-side pruning of screenshots: it invalidates later thinking blocks on the 5.5 models, vision §F4); between
turns the Phase 5 fold applies (P5 §12.4); a 400 the fold provokes on an account under the vendor's history rule is
recorded as a finding of §13.3 and ends the turn `QUESTION` with the provider's text, never a retry.

### 7.5 Image tokens in the budget estimate (D324)

`SessionService.estimate`'s fallback (when `countTokens` is not available or failed) adds `ceil(w / 28) * ceil(h / 28)`
per `Image` part of the request, the size read from the PNG's IHDR (`Prompts.imageSize(bytes)`; a JPEG's SOF likewise);
BS12 RESULT "Cost and tokens": a 1024 x 768 capture counts 1,039 tokens on all three models (the formula gives 1,036).
The primary path (`countTokens`) already counts them.

**As built (Phase 7, VM, 2026-10-10):** `Prompts.imageSize` reads a PNG's `IHDR` and a JPEG's first start-of-frame
marker (within 64 KiB); `Prompts.imageTokens` gives the formula's count (0 for an unreadable header). The fallback counts
images in messages and inside a tool result's parts, and a provider tool's raw JSON as text.

---

## 8. The scenario side (`skillroy-scenario`, `skillroy-controller`, `skillroy-junit`, `skillroy-cli`)

### 8.1 The four verbs (D310)

`BaseSteps.VERBS` grows to eighteen: `call, click, type, press, dismiss, waitFor, expect, mark, webClick, webFill,
webDispatch, webWaitFlag, webResize, webTap, aiAct, aiExpect, pointer, expectImage`; the four leave
`PlanCompiler.LATER_VERBS` (deleted) and stay in `RESERVED_VERBS` (a pack may not take them; P2 §1). `UI_VERBS` gains
`pointer` (`fired: false` fails the step, A29). The planner's schema (`ScenarioSchema.baseVerbs`) lists them; the
committed-schema tests regenerate once.

| Verb | Arguments (the allow-list of `BaseSteps.map`) | Compiles to | Notes |
|---|---|---|---|
| `aiAct` | `intent` (string, required), `tier?` (`V1 \| V2 \| V3`), `model?` (`opus \| sonnet \| haiku`), `maxActions?` (1-30) | **no call**; the plan's `inference` becomes `run` (§8.2) | in `replay` the plan is `SKIPPED` (§8.6); in every other mode `PlanExecutor` refuses it `ERROR / ENVIRONMENT` ("inference: run: run it with skillroy session --play"); the `ScenarioPlayer` runs it (§6.8), where the step's values narrow the session's and never widen them. `tier: V3` adds `swing.pointer.robot` to `requires` with a compiler note |
| `aiExpect` | `question` (string, required), `expect?` (`YES` (default) \| `NO`) | a step with one `VisualCheck(question, expect, "inference.vision")` and no call (§8.4) | the guard is fixed: a `capability` argument is refused ("aiExpect is always guarded by inference.vision"); skipped unconditionally under `PlanExecutor` (D354), so the best outcome there is `DEGRADED` (B14) and the run is never red for it; evaluated by the player only |
| `pointer` | exactly one of `target` (a ref, as `click` takes one) / `by` (a locator); `region?` (a region id); `row?` (a table's model row or a list's model index); `at?: {fx, fy}` (default centre); `action?` (`CLICK` default, `DOUBLE_CLICK`, `PRESS`, `RELEASE`, `MOVE`, `DRAG`, `WHEEL`); `to?: {fx, fy}`; `button?`; `modifiers?`; `clickCount?`; `wheel?: {notches}`; `steps?`; `via?` (`ROUTED` default, `ROBOT`); `guard?` (a label); `force?`; `await?`; `strict?` | `swing.pointer` with the component form of §3.3, `SETTLE`, or with `await` `EVENT` then `swing.settle` as `uiCalls` does; `via: ROBOT` adds `swing.pointer.robot` to `requires` (a compiler note) and the executor refuses the plan without `RunOptions.robot` **before `checkRequires`** (§9.2: a person's flag is checked before the target is asked, so a CI run without the flag shows "robot: not allowed; a person passes --allow-robot" and never `PRECONDITION_FAILED`; with the flag and without the agent option `checkRequires` ends `PRECONDITION_FAILED` naming `swing.pointer.robot`); `PRESS` or `RELEASE` with `via: ROBOT` is a compile error ("a Robot press is never left held", D343); `expectTarget` is no argument (a ref cannot be committed, §6.4) | a deterministic coordinate step; `guard` makes it anchored (§6.7): the call carries `guard: {label}` (§8.2) and the plan's `images` list names the label; `fired: false` fails the step |
| `expectImage` | `label` (string `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, required, unique in the scenario); exactly one of `target` / `by`, or neither (the default window); `region?` (a region id) or `clip?: {x, y, w, h}` (not both); `mask?: [ {by: <locator>} \| {x, y, w, h} ]`; `maxDiffPixels?` (0, default, to 10000) | one `swing.capture {target|by, scope, region, redact: true}` call (sync `NONE`) and one `ImageCheck(label, masks, maxDiffPixels)` (§8.4), the check evaluated from that call's result | compared with the baseline of the current `renderKey` (§6.7, §8.7); the plan's `images` list names the label |

`region` is a string everywhere in the DSL (a region id, resolved by the agent's `RegionResolver`); a rectangle in the
DSL is `clip` or a `mask` entry (D297: on the wire `swing.capture`'s reserved `region` takes a rectangle or an id, §3.1;
the one word with two homes, stated here once). `region` and `row` are accepted on `pointer` only, `region` on
`expectImage` too (`click`'s rungs address a component, not a point; a region click is a `pointer`).

**As built (Phase 7, VC):** `BaseSteps` holds the four verbs with the allow-lists of this table (`VISION_VERBS`,
`POINTER_ACTIONS`, `REGION_ID`); `PlanCompiler.visionStep` compiles them (the compiler, not `BaseSteps`, attaches the
`VisualCheck` and the `ImageCheck`, since a `StepLibrary` returns calls only). Three choices the table left open
(D363, D364): `expectImage` without `region` or `clip` writes no `scope` and lets the agent's default answer
(`WINDOW` for a window and for no target, `COMPONENT` for a component; the compiler cannot tell which a locator names);
`pointer` always writes `action` (default `CLICK`: the primitive requires it) and checks `strict` as a boolean without
sending it (the primitive has no fall-back to restrict); an `await` capture on `pointer` declares its slot as `uiCalls`
does. One label per baseline or anchor in a scenario: an `expectImage` label and a `guard` label share one namespace
("used twice"). The scenario schema's `baseVerbs` lists the four (`ScenarioSchemaTest`); no committed schema copy exists
to regenerate.

**(Phase 7 security fix, S118, built 2026-10-11, SJ.)** Labels are **unique ignoring case** in a scenario: a label names
a file (`<label>.<renderKey>.png`), and two labels equal ignoring case share one on a case-insensitive volume (macOS's
default APFS, NTFS), where approve wrote one file for two entries and every later run ended `ERROR / ENVIRONMENT`. The
compiler refuses the second ("the image label 'pad' differs from 'Pad' only in case; a label names a file").

### 8.2 `plan.json` version 6 (D325)

*(Phase 8, built 2026-10-19, EX; P8 §8.2.)* Superseded by version 7: the version rule of this section applied once
more (a version 6 plan is read as version 7 without the Phase 8 content, which a version below 7 may not hold), and
from version 7 on an unknown step or call key is refused. The text below describes version 6 as it was built.

`Plan.VERSION = 6`; the reader accepts 1 to 6 and refuses version-6 content below 6 ("'images' needs a version 6
plan", "inference 'run' needs a version 6 plan", a `visual` or `image` check likewise, a call's `guard` likewise, the
verbs `aiAct`, `aiExpect`, `pointer`, `expectImage` likewise). New top-level key `images: [<label>…]` (sorted; the labels
of every `expectImage` and of every anchored `pointer`; absent when empty, as `web` is). **`Plan.Call` gains the optional
component `guard`** (`Plan.Guard(String label)`, written as `"guard": {"label": "…"}` on the `swing.pointer` call; null
otherwise, so no older plan changes): the executor takes the guard capture itself with the rectangle from the bindings
entry of the current render key (§6.7). **`Plan.needsBindings()` is true when `images` is non-empty** as well as for an
oracle or a query slot, so that a plan with a baseline alone reads, checks and writes its bindings file (§8.7).
`inference` is derived by `Plan.inferenceOf(slots, steps)`: `run` when any step's verb is `aiAct`, else `bind` when a
query slot's strategy names a model token, else `none`; the reader's consistency check uses the two-argument form;
`INFERENCE_RUN` becomes public. `Check`'s JSON gains two shapes (§8.4). One regeneration of every committed plan
(`planVersion`) and bindings file (`planHash`) in both sample scenario modules (`PlansUpToDateTest`,
`BranchPlansUpToDateTest`, `BindingsUpToDateTest`; the web host's `plan/*.plan.json` is VC's one touch in that tree), as
D81 did. `resolveHash` is unchanged in content, so no bindings file changes its `resolveHash`.

**As built (Phase 7, VC):** `Plan.VERSION` 6, the record's twenty-first component `images` (sorted; the older
constructors keep it empty), `Plan.Guard` and `Plan.Call.guard` (the eighth component; refused on any primitive but
`swing.pointer`, at construction and in the reader), `Plan.inferenceOf(slots, steps)`, `Plan.imagesOf(steps)` (what the
reader requires `images` to equal), `Plan.usesRobot()` (the steps' and the cleanup's calls) and `INFERENCE_RUN` public;
`images` is written after `web` and before `bindTimeoutMs`. The reader names each version-6 item it refuses below 6
(`'images'`, `inference 'run'`, a call's `guard`, the `visual` and `image` checks, the verb). VC's step-1 script
regenerated every committed plan: `-Dskillroy.plans.update=true` in `PlansUpToDateTest` (it now regenerates
`scenarios/exported/plan/` from `scenarios/exported/*.yaml` too), `BranchPlansUpToDateTest` and the web host's
`PlansUpToDateTest` (profile `jcef`), then `-Dskillroy.bindings.rehash=true` in `BindingsUpToDateTest`, which rewrites the
`planHash` of every committed bindings file (the standard ones, the branch variant's, the session export's) from its
YAML's compiled plan and nothing else (D373); step 2b reruns both after the pack's bump.

**(Phase 7 security fix, S118, S119, built 2026-10-11, SJ.)** The reader is as strict as the compiler: it refuses an
`images` list with two labels equal ignoring case (S118), a `guard` on a **cleanup** call ("cleanup: a guard is allowed
on a step's swing.pointer only": a cleanup call is sent without looking at its guard, so a guard there would be shown
and never evaluated), and an image check with **more than 64 masks** (`ImageMasks.MAX_MASKS`, the compiler's bound);
the `ImageCheck` constructor enforces the 64 too, so every construction path has the bound (S119). A plan the compiler
wrote holds none of these, so no committed plan changes.

*(Phase 8, built 2026-10-19, ES; P8 §2.2, §7.3.)* The rule of this section is applied once more for plan version 7 (§8's,
EX): among its new call keys are `accept`, `rungs` and `minRung`, each refused below 7.

### 8.3 `region` beside `by`

Stated in §8.1: a sibling argument of `by`/`target` on `pointer` and `expectImage`, passed to the primitive as `region`
(a string), resolved by the agent (`NOT_FOUND` with `data.region` when the component has no such region or no
`RegionResolver` supports it, which the executor maps to `FAIL / ASSERTION` as every `NOT_FOUND` of a step call).
P1 §6's locator grammar is unchanged (no new key inside `by`).

### 8.4 Two new checks: `VisualCheck`, `ImageCheck`

`sealed interface Check permits Expectation, RowCheck, VisualCheck, ImageCheck`:

| Check | Record | `plan.json` | Evaluated |
|---|---|---|---|
| `VisualCheck` | `(String question, String expect, String capability)`, `capability` always `inference.vision` | `{visual: {question, expect}, capability: "inference.vision"}` | by the `ScenarioPlayer` (§6.8) when the merged matrix has `inference.vision OK`; **under `PlanExecutor` skipped unconditionally** ("no visual evaluator in this host", D354): the capability's state is only the reason text printed (`… capability inference.vision is UNAVAILABLE (replay)` in `replay`, `… (no vision session)` elsewhere), and a contributor that answered `OK` in a `live` run changes nothing |
| `ImageCheck` | `(String label, List<Object> masks, int maxDiffPixels)`; the masks as written (`{by}` locators resolved at the step through `swing.find`, rectangles as given) | `{image: {label, masks, maxDiffPixels}}` | by `PlanExecutor` and the player alike through `ImageSteps` and `ImageCompare` (§6.7), **from the step's own capture call result**, against the baseline of the current `renderKey`; the `expect`-style retry does not apply (one capture, one compare) |

The run log gains the record `image {step, label, renderKey, result: held | failed | missing | skipped, differing?,
maxChannelDelta?, expected?, actual?, files?}`; the detail of a failed check carries the same fields; bytes never.

**As built (Phase 7, VC):** `VisualCheck` and `ImageCheck` in `…scenario`; `ImageCheck.LABEL` is the label pattern
(also `Plan.Guard`'s); a mask entry is checked by `ImageMasks` (at most 64 per step). Under `PlanExecutor` a visual check's
skipped line reads `<step>: aiExpect "<question>" <expect> [capability inference.vision] skipped: no visual evaluator in
this host; capability inference.vision is <state>`, its `expect` record `{visual, capability, result: skipped, state,
reason}`. The `image` record's `result` adds `approved` and `rejected` (an `approve` candidate) to the four values, and a
guard's record carries `guard: true` (D365). A `{by}` mask is resolved through `swing.find` at the step: the match's
`bounds` (window space) mapped through the capture's `origin` and `scale`; a locator that matches no single component
fails the step `FAIL / ASSERTION` (D375).

### 8.5 Controller-side capabilities in the matrix: `CapabilityContributor` (D326, D327, D348, D354)

```java
package ai.skillroy.testjava.scenario;
/** A controller-side module's capabilities, merged into the run's matrix beside the agent's (design 2.4, 3.10): found
 *  through ServiceLoader on the pack class loader; a pure function of its context; never a model call. */
public interface CapabilityContributor {
    String id();                                           // "inference", "vision"
    Map<String, Capability> capabilities(CapabilityContext ctx);
    record CapabilityContext(InferenceSettings settings, BindMode mode, CapabilityMatrix agent, Set<String> host,
                             DomainPack pack) {}
}
```

Rules: every contributed id starts with `inference.` (another is `ERROR / ENVIRONMENT` naming the contributor); an
agent that declares an id with that prefix is refused the same way (the prefix is the controller's, P2 §3.3; today
nothing enforces it agent-side); a contributed id that an agent also declares, **or that two contributors answer**, is a
conflict, refused likewise naming both; in `replay` every contributed id's state is forced to
**`UNAVAILABLE(reason="replay")`** whatever the contributor answered (the reserved behaviour, design §3.10); the merged
matrix is what `requires` is checked against, what guards read, what `capabilities.json` archives, what the base fact
root `caps` reads (**`BaseFacts.caps(Supplier<CapabilityMatrix>)`**, the `hooks(LongSupplier)` precedent: the executor and
the session pass their merged matrix, with a `source` per entry) and what `skillroy caps` and `skillroy_catalogue` show
(with a `source` per entry: `agent` or `controller:<contributor id>`; `capabilities.json` keeps the wire shape of
`/capabilities` and carries no `source`, which the matrix remembers controller-side, `CapabilityMatrix.with(contributed,
source)`); the `capabilities` record of `run.jsonl` gains `agentHash` (**`CapabilityMatrix.hash()` of the agent's matrix
read after step 0b**, what the "one matrix per target" check compares, D130 kept; the goldens are compared normalised as
today, never by hash) and `contributed: [ids]`. **The merge happens once per host**: in `PlanExecutor.checkRequires`
after step 0b and before `requires` (`host` empty); in `ExecutorSession.open` after the matrix is read, with `host` =
**`SessionOptions.host`** (D348: a `Set<String>`, the fourteenth component, default empty, `withHost`), which the
player's host, `SessionCommand` and `McpCommand` set to `{"vision-session", "vision-tier:<v1|v2|v3>"}` **before `open`**
when the vision tools will apply (the conditions mirror `SessionTools.applies` without the agent half: the module is present, the tier is not
`off`, the gateway is not `none`, `images: true`, and `v3` only with the robot flag); and in the MCP server's start for
`skillroy_catalogue` (the same `host`). **`ExecutorSession.capabilities()`** (public) answers the merged matrix: the
player's guard reads it, `SessionTools.applies` reads it through the facade, and `capabilities.json` is written once.

| Contributor | Ids | States |
|---|---|---|
| `InferenceContributor` (moves its `ResolverContributor.capabilities`, which becomes a deprecated no-op default; **stops answering `inference.vision`**, which `InferenceContributorTest` pins today and the vision module's contributor owns alone; VC lands this class's `implements CapabilityContributor` and its test in step 1 as a shared file in VM's module, so that the two ids are contributed before VM lands, §13.2 item 9) | `inference.gateway`, `inference.sql` | `OK` when the settings name a gateway other than `none`, else `UNAVAILABLE("gateway none")`; `inference.sql` also `UNAVAILABLE("no allow-list")` when `ctx.pack()` declares none |
| `VisionCapabilities` (`skillroy-vision`) | `inference.vision` | `OK(strategy = "v1" \| "v2" \| "v3", the tier read from the host's `vision-tier:` feature)` when `host` holds `vision-session`, the settings name a gateway other than `none` and `images: true`, and the agent's **`swing.capture.screen`** is `OK` (the loop's capture is the `SCREEN` composite, which implies `paint`); else `UNAVAILABLE` with the first failing reason: `"replay"`, `"no vision session"`, `"gateway none"`, `"images: false"`, `"swing.capture.screen is <state>"`; absent from the matrix when the module is not on the class path |

So `requires: [inference.vision]` ends `PRECONDITION_FAILED` with the reason in every `PlanExecutor` run and a guard
on it reads "`UNAVAILABLE (replay)`" in CI (today both read "missing", map 5 §1.7).

**As built (Phase 7, VC):** `CapabilityContributor` with the nested `CapabilityContext` and a static `load(ClassLoader)`;
the merge is `…scenario.CapabilityMerge` (public, so that the MCP server's start uses the same rules: `load`, `merge`,
`Refused`, `Result{merged, agent, contributed}`, `PREFIX`, `REPLAY_REASON`). A contributor that throws is refused naming
it, as one that answers an id without the prefix is (D367). `PlanExecutor` finds the contributors through its web path
loader (the pack loader; the context class loader without one) and `ExecutorSession` through
`SessionOptions.webPathLoader`, the same loader the hosts already set for the packs. `CapabilityMatrix.with(contributed,
source)`, `source(id)` and `agentOnly()` (D380). The `capabilities` record of `run.jsonl` is `{hash, ids, file,
agentHash, contributed}` (`hash` and the file the merged matrix's); the session journals `contributed` in
`session-start.options` when it is not empty. `InferenceContributor` implements both SPIs (one `META-INF/services` entry
each); `inference.gateway` and `inference.sql` are `OK` with the gateway's name as the strategy.

**Pack capabilities (Phase 8, built 2026-10-19, EP; P8 §9.1).** After the contributors' merge every host also evaluates
the running pack's own capabilities (`DomainPack.capabilities()`, `PackCapability`, through the one helper
`PackCapabilities`): ids `<packId>.<name>`, source **`pack:<packId>`** (`CapabilityMatrix.SOURCE_PACK_PREFIX`; `agentOnly()`
drops them, the wire shape carries no source), every declared id, before `capabilities.json` and `requires`. Unlike a
contributed `inference.*` id they are **honoured in `replay`**: the evaluation's context (`PreflightContext`) carries no
bind mode, no inference settings and no gateway, so the answer cannot depend on the mode, and the D19 ledger check still
catches a model call. An evaluation that throws makes its id `UNAVAILABLE("failed: <SimpleName>")` instead of refusing
the host (unlike D367: an evaluation reads the environment and may fail for environmental reasons); a declaration
error refuses it. The `capabilities` record gains `pack` (absent when the pack declares none), the session journals
`session-start.options.pack` beside `contributed`, and `BaseFacts.caps` shows `source: pack:<packId>` unchanged in code.

### 8.6 The fifth status: `SKIPPED` (D328)

`Status { PASS, DEGRADED, FAIL, ERROR, SKIPPED }`. A run is `SKIPPED` when its plan's `inference` is `run` and the
mode is `replay`: decided after the plan is read and before anything is sent to the target (right after `run-start`,
before `checkRequires`; `requireBindingsRoot` does nothing in `replay`, so no bindings file is needed for such a plan
and `vision-live-only` has none committed, §12.4; the agent is not contacted), `failureKind` null, `message` = "not
executed: the plan needs a model while steps run (inference: run)", `steps` empty, `run.jsonl` holding `run-start`, one
`skipped {reason}` record, the `ledger {count: 0}` record every run writes (the zero-model-call evidence) and `run-end`
(`status: SKIPPED`). JUnit: `<skipped message="SKIPPED: …"/>` whatever
`degradedAs` says (`JunitXmlReporter.outcome` gains the case); the glue `Assumptions.abort(message)`
(`ScenarioTests.report` gains the case; its `default` throw stays for any later value); the CLI counts "N skipped" in
its summary line, `--json`'s summary gains `skipped`, and the exit code is `OK` for a skipped run (a skipped plan is not
a failure of the build); `RunResult.status()` and `StepOutcome.status` reuse the enum (a session never produces
`SKIPPED`). A plan compiled before version 6 can never be `run`, so no older plan changes its outcome. `FailureKind` is
unchanged (design §3.5: no new kind).

**As built (Phase 7, VC):** the decision point is right after `run-start`: a `run` plan in `replay` writes `skipped
{reason}`, then the `ledger` and `run-end` records; in every other mode it is refused `ERROR / ENVIRONMENT` there too,
before the agent is contacted. `run-start` records `robot: true` when `RunOptions.robot` is set. `JunitXmlReporter`
writes `<skipped message="SKIPPED: <message>"/>`, `ScenarioTests.report` aborts with `<id> SKIPPED: <message>`. The CLI's
`--json` result carries `skippedReason` (the result's `skipped` list is P3 9's skipped cross-checks and keeps its
meaning; D366); the summary line ends `…, N errors, N skipped`.

### 8.7 Baselines and anchors: the bindings file, version 2 (D329, D330, D350, D355)

Image baselines (`expectImage`) and anchors (`pointer … guard`) are bindings: a recorded answer the environment
gives, reviewed and committed beside the scenario, never in the plan (environment data), never masked after the fact
(they hold masked pixels only). **They are reviewed code in the threat model's sense (its §3)**: a baseline shows the
application's data as rendered, masked per component only (the password rule and the pack's `CaptureMask`), so the
author masks with `expectImage`'s `mask` what must not be committed, and a reviewer looks at the PNG as at the YAML. The
bindings file grows to **version 2** (`BindingsFile.VERSION = 2`; the reader accepts 1 and 2; a file without images is
still written as version 1, so nothing committed changes until an image enters it):

```json
"images": {
  "<label>": {
    "<renderKey>": { "sha256": "…", "width": 420, "height": 110,
                     "masks": [{"x":0,"y":0,"w":10,"h":10}],
                     "carets": [{"x":12,"y":40,"w":1,"h":24}],
                     "focus": { "owner": {"id": "entry"}, "active": true },
                     "file": "<scenarioId>.images/<label>.<renderKey>.png",
                     "rendering": { "jdk": {"vendor": "…", "version": "…", "specification": "25"},
                                    "os": {"name": "Linux", "version": "…", "arch": "aarch64"},
                                    "lookAndFeel": "…", "antialiasing": {"desktopHints": [], "property": null},
                                    "screenScale": 1.0, "probeHash": "sha256:…" },
                     "window": { "size": {"w": 1024, "h": 768}, "insets": {"top": 0, "left": 0} },
                     "anchor": { "x": 412, "y": 288 } }
  }
}
```

`carets` and `focus` are the capture's own (`carets` as rectangles in the image's coordinates; `focus.owner` the focus
owner's locator from the capture's `focus.ownerLocator`, null when nothing had the focus, since a ref cannot be compared
across agent starts; `active` whether the captured window was active), which the comparison needs on the baseline's
side (§6.7; BS11 RESULT "Proposal" 3). `rendering` is `swing.rendering`'s answer without `renderKey`, nested as §3.8
gives it, for the reviewer only. `anchor` (anchors only) is the crop's origin in the step's target's coordinate space, so
that the guard captures the same rectangle at replay; a baseline has none. **`file` is informative**: the reader derives
the path from the label and the render key, requires it to resolve inside `<bindingsRoot>/<envId>/` without following a
link, and refuses a mismatch with `ERROR / ENVIRONMENT` (a hand-edited or planted file never names a path to open). The
PNG is written beside the file under `<bindingsRoot>/<envId>/<scenarioId>.images/`, through `OutputFiles`, with the
masks and the carets of its capture already blanked (so the committed pixels show nothing a mask hides). **The render
key** (D330, D350) is `swing.rendering`'s `renderKey` (§3.8): a baseline is bound to the rendering that made it, not to
the environment's fingerprint. The fingerprint and `EnvFingerprint.VERSION` are unchanged, and no bindings file of a
scenario without images changes when a font package or a JDK update changes pixels (the trap of vision §Risk3 and map 5
Q6: a fingerprint that carried the rendering inputs would split every environment into one per target Java, since the
JDK line changes 0.30 % of the desk's pixels, BS11 RESULT "Across environments"). A comparison first reads the target's
`swing.rendering` (once per run, cached; a read, allowed after the start mark), then looks the label's entry up by
`renderKey`: a missing entry is `REBIND_NEEDED(IMAGE, "no baseline <label> for rendering <key>")` in `replay`; a
present one is compared exactly (§6.7). `REBIND_NEEDED`'s reasons (P2 §10) gain `IMAGE`.

**The bind lifecycle** (D355), because a baseline is taken at its step, after the start mark, while the binder asks and
writes at bind time:

- `Plan.needsBindings()` is true for a plan whose `images` is non-empty (§8.2), so `Binder.start` reads the file,
  `Binder.check` verifies **in `replay`** that every label of the plan's `images` has at least one entry (any key) and
  reports the keys present (in `approve` and `live` the entries come at the step, so the check reports and refuses
  nothing there), and `requireBindingsRoot` applies to `approve`;
- a baseline is approved **at its step**: `Approver` gains `default Decision reviewImage(ImageApproval request)`
  (`ImageApproval(scenarioId, envId, label, renderKey, Path candidate, int width, int height, ImageEntry existing)`);
  the default delegates to `review(…)` over an `ApprovalRequest` built from the image, so the lambda constants keep
  their stance (`APPROVE_ALL` under `--yes` approves, `REJECT_ALL` without a terminal rejects, and a rejected candidate
  leaves the step `REBIND_NEEDED(IMAGE, "candidate not approved")`), and the CLI's terminal approver overrides it to
  print the candidate's path and size and ask; **an anchor is never approved**: it is recorded by a session (§6.5), and
  an anchored step without an entry for the current render key is `REBIND_NEEDED(IMAGE)` in every mode (§6.7);
- **after the steps**, `approve` rewrites the bindings file with the new entries beside the old ones (one baseline per
  rendering the project runs in, Playwright's practice) through the binder's `write` and `requireNoCredential`;
- `Binder.finish` and the re-resolve job carry an existing file's `images` section forward unchanged (a slot approval or
  a re-resolve never drops a baseline); `Binder.file(env)` takes the section from the file it read;
- the export writes the `images` section of its anchors into its own `BindingsFile` (§6.6) and `ExportResult.images()`
  (`Map<String, byte[]>`, label to masked PNG) hands the pixels to the caller to place beside it.

**As built (Phase 7, VC):** `BindingsFile` gains the eleventh component `images` (label to render key to
`BindingsFile.ImageEntry`; `Focus`, `Anchor` beside it); `VERSION` 2, `VERSION_WITHOUT_IMAGES` 1, and the constructor
writes 1 for a file without images whatever version it is given. A render key in a file is `[A-Za-z0-9]{8,64}` (it names
a file; D368). The entry's `window` is optional; an approved baseline records `{size}` from the capture's one window
rectangle and `{insets}` from a `WINDOW` capture's origin (D381). The derived-path rule: the binder refuses an entry
whose `file` is not `<scenarioId>.images/<label>.<renderKey>.png` (`ERROR / ENVIRONMENT`, in `replay`), and
`ImageBaselines` reads a PNG from the derived path only, inside `<bindingsRoot>/<envId>/`, never through a link, at most
64 MB, and only when it decodes to the entry's `sha256`. The lifecycle: `Binder.approve` asks nothing at bind time for a
plan whose bindings hold images only (no resolution, no oracle: its baselines are approved at their steps; D362);
`Binder.file(env)` takes the existing file's `images` (a live write, `approve`, a re-resolve); the second write is
`Binder.writeApprovedImages` after the steps, run also when a later step failed, since each baseline was approved by a
person at its step (D379). `ImageSteps.Outcome` has a fifth kind, `ERROR` (an unusable capture or baseline file, a
target that cannot answer `swing.rendering`: `ERROR / ENVIRONMENT`; D376).

**(Phase 7 security fix, S115, built 2026-10-11, SJ.) D362 as amended: `approve` of a plan whose bindings hold images
only asks nothing and writes nothing at bind time; after the steps, a file that would change for another reason than
an approved baseline is written only when the approver approves it** (D482). The bind-time write went to the
operator's bindings root with no approver asked, so a client's (or the planner's) `skillroy_scenario_run {mode:
approve}` replaced a committed scenario's file (its resolutions and oracles gone) or planted one, and a `REJECT_ALL`
approve after an environment change rewrote the file's `env`, clearing the fingerprint gate. As built
(`Binder.writeImagesOnly`, called by `PlanExecutor` after the steps, and after a bind alone for `skillroy resolve`):
with a baseline a person approved at its step the file is written with it beside the old entries, as before (D355,
D379); when an existing file would also change for another reason (its `env`, plan hash, resolve hash, pack,
resolutions or oracles) that change needs one `Approver.review` with no resolution and no oracle and the `BindingsDiff`
against the existing file, and refused, the approved baselines go into the existing file as it is and the run ends
`RESOLUTION_FAILED / NOT_APPROVED`; without an approved baseline, after steps that did not fail, a file that is absent
or would change is written only on the approver's yes (refused: `NOT_APPROVED`, nothing written), and after a failed
step nothing is written. So `REJECT_ALL` writes nothing, `--yes` keeps the operator's refresh of an environment whose
baselines still hold, and the terminal approver asks once at the end. D355 (a baseline approved at its step is kept)
and S61 (a client never replaces a scenario) stand. A plan with a slot or an oracle is approved and written at bind
time as before.

**(Phase 7 security fix, S116, built 2026-10-11, SJ.) An unusable bindings file is never taken for an absent one.** A
file at the derived path that cannot be read (a `bindingsVersion` this code does not know, a merge conflict left in
its JSON, one refused entry) or that names another scenario or environment ends a bind that would write the file
(`approve`, `live` or `re-resolve` with `writeBindings`), or whose plan has images (their baselines come from the file),
`ERROR / ENVIRONMENT` naming the file and the reason, as `replay` refuses it (`UNREADABLE`); before, such a bind
rewrote it without its images (every committed baseline and anchor of the scenario in that environment dropped). A
`live` run of a plan without images reads nothing from the file and is unchanged. P2 §8.3's approve step 8 says the
same.

**(Phase 7 security fix, S140, built 2026-10-11, SJ.) The writers follow the reader's link rule.** The bindings file's
writer (`BindingsFile.writeText`: the binder's, the export's) refuses an environment directory that is a symbolic link,
and every writer of a baseline's or an anchor's PNG (the binder's approve, the export's caller,
`SessionCommand.writeImages`) derives its path through `BindingsFile.imageFile`, the reader's one walk: inside
`<bindingsRoot>/<envId>/`, no symbolic link from that directory down, the directory itself included; the message names
the link (the directory by its path, an entry below it relative to it). So approve never writes where replay refuses
to read. A link above `<envId>` is P2 §11.3's documented non-coverage, unchanged.
**(Phase 7 follow-up, gap 99, built 2026-10-11.) The JSON reader follows the same rule.**
`BindingsFile.read(bindingsRoot, envId, scenarioId)` walks with `requireNoLink` (the message naming the link) and opens
the file without following one; it is what the binder (replay's read, a writing bind's read of the existing file,
approve's diff), the session's baselines and the executor's caller-supplied file read. A link at the file's position,
dangling or not, is a file that cannot be read: replay ends `UNREADABLE` and a writing bind refuses as for S116 rather
than replacing the link. `BindingsFile.read(Path)` stays for a file a person names (`diff-bindings`).

**(Phase 7 security fix, S146, built 2026-10-11, SJ, the controller's half.) A `{text}` locator is never recorded as
the owner: target text never enters the file.** `ImageSteps` drops a `text` key from the capture's
`focus.ownerLocator` before the entry is built (an older agent may answer a nameless text field's content there;
the agent no longer does, SG's half), and `JournalExport` drops it from an exported anchor's owner, keeping what is left (`{role}`), null when nothing is; the owner comparison of
§6.7 uses the same value. A `{text}` is no stable identity of a field anyway (typing changes it), and a hash of a short
text could be confirmed by guessing (D484). The export's `<redacted>` check covers its `images` section (§6.6).

**(Phase 7 security fix, S118, S147, built 2026-10-11, SJ.)** The bindings file's reader refuses two labels that
differ only in case, and its constructor refuses a label or a render key the reader would refuse and such a pair
(`IllegalArgumentException`): a file that cannot be read back is never written.

### 8.8 The fingerprint and `swing.rendering`

Unchanged in content (format 1; the pack's `<pack>.*` components, the protocol major, the format): the rendering
inputs are read by `swing.rendering` and keyed per baseline (§8.7), not fingerprinted (D330). A pack that wants a
rendering input in its fingerprint anyway may read `swing.rendering` in its `EnvFingerprinter` (a read-only
primitive, allowed there).

### 8.9 Reserved, still: `Strategy.LLM_VISION`, `UiTarget`

The token `llm-vision` is parsed and refused "not in this version" by the loader, the compiler and the plan reader, as
today; `UiTarget` is not built; `Approval`'s "tier (b) or (c)" wording stands for a later phase (D316, §1).

---
## 9. Interactive sessions

### 9.1 The `SessionTools` seam of the session service and the facade `SessionActions` (D302, D347, D353)

```java
package ai.skillroy.testjava.inference.session;
/** Tools a controller-side module adds to an interactive session's model (Phase 7: the vision module). Found through
 *  ServiceLoader; one instance per session; called on the session's thread; reaches the session, the agent and the
 *  model only through the facade in its context, never through an ExecutorSession or a gateway of its own. */
public interface SessionTools extends AutoCloseable {
    static List<SessionTools> load(ClassLoader loader) { … }           // ServiceLoader on the pack loader (Contributors.load's shape)
    String id();                                                       // "vision"
    boolean applies(SessionToolContext ctx);                           // false: the module stays silent in this session
    List<ToolSpec> tools(SessionToolContext ctx);                      // custom strict tools, projected through ctx.mask()
    List<ProviderTool> providerTools(SessionToolContext ctx);          // provider entries (the toolset), may be empty
    String instructions(SessionToolContext ctx);                       // extra system text; null for none; masked by the service
    boolean handles(String toolName);                                  // the names act() answers (custom and toolset members)
    List<Content> atTurnStart(SessionToolContext ctx)
            throws AgentLostException, InterruptedException;           // parts appended to the turn's first USER message
    Outcome act(Content.ToolUse use, SessionToolContext ctx)
            throws AgentLostException, InterruptedException;           // one tool call; the step it ran, if any
    List<Content> afterBatch(SessionToolContext ctx)
            throws AgentLostException, InterruptedException;           // parts after the batch's results (a fresh capture)
    @Override default void close() {}
    enum EndTurn { NONE, QUESTION, LIMIT }
    record Outcome(Content.ToolResult result, StepOutcome step, EndTurn endTurn, String reason) {}
    record SessionToolContext(SessionActions actions, SessionPolicy policy, PromptMask mask, InferenceSettings settings,
                              Map<String, String> options, boolean robotAllowed, Clock clock) {}
}
/** The service's facade: the one way a tool reaches the session. The origin of every call is the turn's Origin.model;
 *  CallPolicy runs on every call; every model call is metered on the session's meter and written to the ledger. */
public interface SessionActions {
    StepOutcome execute(String verb, Object args, Map<String, Object> provenance)
            throws AgentLostException, InterruptedException;           // the step that will be replayed (§9.2)
    StepOutcome execute(PlannedCall call, Map<String, Object> provenance)
            throws AgentLostException, InterruptedException;           // a live-only raw call
    Object read(String primitive, Map<String, Object> args)
            throws AgentRpcException, AgentLostException, InterruptedException;   // a capture, a hit-test, swing.rendering
    ModelResponse complete(ModelRequest request)
            throws BudgetException, InferenceException, InterruptedException;      // SessionService.visionCall (§10)
    CapabilityMatrix capabilities();                                   // the session's merged matrix (§8.5)
    String snapshotText(int maxChars) throws AgentLostException, InterruptedException;
    List<Event> eventsSince(long seq, String topics, int max);
    boolean secretsInFlight();                                         // §9.2, D342
    default boolean namesSecret(String text) { return false; }         // §9.2 rule 1 over the joins (gap 94, built 2026-10-11)
}
public record SessionExtensions(List<SessionTools> tools, Map<String, String> options) { public static SessionExtensions none() … }
```

`SessionService` gains the eighth constructor parameter `SessionExtensions` (the seven-argument one passes `none()`)
and the public method **`visionCall(ModelRequest)`** (purpose forced to `vision`, the estimate of §7.5, the `SESSION`
meter reserved before, the `LedgerWriter` after, a failed call recorded too, D102), which the facade's `complete` and the
player's `aiExpect` use. At construction it asks each tool `applies(ctx)` and keeps those that do; `tools()` lists their
tools after the projection's and before `done`/`ask_person`; the request carries their `providerTools`, their
`instructions` as system text after the service's own (**both through `ctx.mask().text` before they enter the request**,
as the vocabulary is, S49), and `CacheHint.AUTO` when any tool applies; the turn's first `USER` message carries every
tool's `atTurnStart` parts; `act()` consults `handles(name)` before "there is no tool"; a tool's `Outcome.step` counts
against `maxActionsPerTurn` when non-null; an `Outcome.endTurn` other than `NONE` ends the turn with the reason as the
model's last result text (`QUESTION` as `ask_person` does, `LIMIT` as the action limit does); after the batch's results
the service appends every tool's `afterBatch` parts to the same `USER` message; **the service counts and enforces
`vision.maxActions` and `vision.maxTurns`** (§10) and at a limit ends the turn `LIMIT` and closes the vision tools for the
rest of the session (`tools()` lists none afterwards, the model is told why); the service closes the tools with itself.

**`CallPolicy` on every route** (D347, D353; D244's method: one list, every route). `CallPolicy.arguments(String
primitive, Map<String, ?> args, boolean robotAllowed)` (the two-argument form delegates with `false`; `refusals(…)` takes
the flag too) refuses, for a `MODEL` or `CLIENT` origin: `redact: false` on `swing.capture` ("a capture is redacted for
a client"); `force` on `swing.pointer`, `swing.click`, `swing.type`, `swing.key` and `web.pointer` ("acting past a modal
dialog or a covering window is a person's choice"); **`mode: ROBOT` on `swing.capture` whatever the flag** ("a capture
of the glass is a person's evidence"); and `via: ROBOT` on `swing.pointer` unless `robotAllowed` ("robot input is a
person's opt-in"). Who calls it, and with which flag:

| Route | Call | Flag |
|---|---|---|
| a model's verb call in a session | `SessionService.act` through `refusals` (as today) | `false` |
| a model's raw primitive call in a session (`allowBasePrimitives`) | **`SessionService.act`'s primitive branch** (new: it refuses, never strips; a tool error naming the primitive and the argument, never a value; nothing journaled, nothing sent) | `false` |
| an MCP client's raw tool | **`ToolCalls.primitive`** (new; the same refusal before `execute`) | `false` |
| an MCP client's plan (`skillroy_scenario_run`), a planner's scenario | `PlanPolicy.refusals(plan)` / `PlanPolicy.arguments` (a plan with `pointer {via: ROBOT}` or `force: true` is a person's run, as one with `confirm` is today), **and the credential rule's plan form** (D342, §9.2): a step whose arguments as written (a base verb's or a raw `call`'s, not a pack verb's own compiled references) name a field of a slot the plan declares with `credential:`, unless the slot's own `fixture` value provides that field, is refused naming the step and the slot, never a value, and nothing runs | `false` |
| a `SessionTools`' step or raw call | **the facade `SessionActions.execute`**, before `ExecutorSession.execute`, with the turn's `Origin.model`; a refusal is the tool result's error | the host's `robotAllowed`: **the one route on which `via: ROBOT` passes**, for the vision module's V3 steps (§6.5); `mode: ROBOT` never |

So `robotAllowed` lets the vision module's own V3 tools set `via` and nothing else: a model's or a client's `via: ROBOT`
is refused on every route whatever the flags, and a glass capture is refused for them everywhere. A reviewed pack verb
whose compiled call carries `force: true` or `redact: false` is refused for a model or a client in the same way (such a
verb is a person's). `ExecutorSession` keeps its own origin-free gate (no `via: ROBOT` or `mode: ROBOT` without
`SessionOptions.robot`) and adds the person-only gate on `mode: ROBOT` in `execute` (§9.2), so that the rules hold even on
a route that forgot `CallPolicy`. The facade's `read` refuses, before `ExecutorSession.read`, everything `CallPolicy.never`
names, `data.query`, a script primitive, `agent.*`, `hook.*`/`rule.*` beyond the three reads (`hook.health`,
`hook.events`, `rule.list`), every mutating or destructive primitive, and a `swing.capture` with `mode: ROBOT` or
`redact: false`; `ExecutorSession.read` holds the same list locally (§9.2: `read` has no origin, so a person's glass or
unredacted capture is an `execute` under a `PERSON` origin or the CLI's `call`, never a `read`), one rule for later
callers.

**Fix wave (Phase 7 review, FB, 2026-10-10):** wherever this contract says `via: ROBOT` or `mode: ROBOT`, it means the
value in **any spelling the agent reads as `ROBOT`**: the agent upper-cases the trimmed value (§3.3, §3.4), so a gate that
compared the exact string let `via: robot` on a raw `call:` step, `" Robot"` or `ROBOT` behind a control character
through to an agent that acted on it. Every controller gate now tests one predicate, `Plan.robotValue(Object)` in
`skillroy-scenario`: both ends stripped of what `String.trim()` strips (code points up to U+0020) and of what
`String.strip()` strips, then `ROBOT` ignoring case (a superset of the agent's reading). Its users: `Plan.usesRobot`
(`PlanExecutor`'s refusal before `checkRequires`, §8.1), `PlanCompiler`'s `requires` rule (a raw `swing.pointer` call so
spelled names `swing.pointer.robot`), `ExecutorSession`'s origin-free gate and its person-only glass gate (§9.2),
`ExecutorSession.read`'s exclusion list, `CallCommand` (§9.3), `JournalExport`'s live-only rule (§6.6: a person's `call`
step spelled `via: robot` is live-only, never exported), and `CallPolicy` and the facade's `read` (above), which
delegate to it. `read`'s exclusion list refuses `redact` whenever it is present and not the boolean `true`
(`Plan.unredacted`: a string `"false"`, which the agent coerces, included), as the facade does. A plan's arguments are
not normalised, so no committed plan changes (D440). Since every gate reads the arguments as written, **`via` and
`mode` take a literal value**: a reference (`via: $r.v`) that resolves to `ROBOT` in any spelling is refused by the
call itself after substitution and before anything is sent (`CallRunner`, every route, every origin, whatever the flags;
`ERROR / ENVIRONMENT`, "robot: via and mode take a literal value; a reference that resolves to ROBOT is refused"),
D441.

The hosts load the tools with `SessionTools.load(ClassLoader)` (`ServiceLoader`, the pack loader) and pass the options
from their flags: `vision.tier` (`v1 | v2 | v3 | off`), `vision.model`, `vision.tools` (`toolset | custom`),
`vision.zoom`, `vision.maxActions`, `vision.maxTurns`. `applies` is false when `vision.tier` is `off`, when the
settings' gateway is `none` or `images` is false (the host reports why in its start-up line), or when the agent's
`swing.capture.screen` is not `OK` (`ctx.actions().capabilities()`); **`v3` without the robot flag is a usage error of
the host before it constructs the service** (never a silent downgrade, which a false `applies` would be). The default
tier when the flag is absent: `v1` when the module is present, the gateway named and `images: true`; `off` otherwise.
The host sets `SessionOptions.host` from the same conditions before it opens the session (§8.5). Under a read-only
policy, or one that offers no verbs, the module offers reads only (§6.3); a step a tool ran anyway meets
`ExecutorSession`'s read-only refusal.

**As built (Phase 7, VM, 2026-10-10):** a facade refusal is an `IllegalArgumentException` naming the verb or primitive
and the reason (nothing journaled or sent), which a tool answers as an error result; the facade acts only within a tool
call of a turn (its origin), and refuses otherwise. The service counts a UI action per facade `execute` (a tool's act
that ran two steps counts two; D426) and a model turn per call while a tool set applies (its loop's and every
`visionCall`, the player's included); at a limit the facade's or `visionCall`'s refusal ends the turn `LIMIT` after the
tool call and closes the tool sets; the vocabulary then says why. A tool set's parts and results pass the session's mask
(images as the settings say; an image while images are off ends the turn `QUESTION`); a result is rebuilt with the call's
id and toolset name. `maxActionsPerTurn` counts every action but a tool set's read (an outcome without a step).
`SessionService` gains `toolSets()` and `toolSetsClosed()` for the hosts' start-up line, and `PURPOSE_VISION`,
`OPTION_MAX_ACTIONS`/`OPTION_MAX_TURNS` (1 to 1000) with their defaults.

**Fix wave (Phase 7 review, FC, 2026-10-10):** (a) **The turn names the slots bound in the session** (the live run's
finding LV-1, D444). The turn's first `USER` message carries, after the `<person>` block and before the tool sets'
`atTurnStart` parts, one `<target-state>` block listing the slots bound in the session when at least one is (nothing
otherwise, so that a session without bindings sends the message it sent before): a heading that says a slot's
`$reference` may be written in a tool's arguments, a text to type included, where its value belongs, and that a
credential's secret is never the model's; then one line per slot in binding order (the last `resolve` or `fixture`
record of each in the session's journal, at most 40): its `$reference`, its query kind (`query LendableItem`) or
`fixture`, whether it is a credential, and its fields as references (`$item.code`: the keys of the journaled public
value without a key the mask's key rule calls secret, at most 30 a slot). **Never a value, never a secret and never a
secret field's name** (D342: the journal holds the public value only, the join's secret fields never), and the block
passes the session's mask as every labelled block does. Without it a model had no way to tell that `$item` in a
person's request names a slot the person bound before the turn (it learnt a reference only from its own
`skillroy_resolve` result), and the live run's models read it as an unfilled placeholder and asked. Every session gets
the block (the CLI's `ask`, the MCP server's `skillroy_session_ask`, the overlay, the player's `aiAct` turns); the Phase
5 session's five scripted stand-in recordings, whose person resolved the assistant before asking, changed fingerprint
and were regenerated on the key-free fixture route (`-Dskillroy.inference.fixtures.update=true`, `SessionExportTest`).
(b) `SessionActions` gains the default method `stepTimeout()`, which the facade answers from `SessionOptions.stepTimeout`
(for `wait`, §6.3; L1-F6).

**Phase 7 security fixes, S110, S111, S135, S136, S137 and S138 (built 2026-10-11, SI):** (a) **`CallPolicy.arguments`'
list gains** (S110, S111), for a `MODEL` or `CLIENT` origin on every route of the table above: a clipboard chord on
`swing.key` (`CallPolicy.clipboardKey`, read as the agent reads keys and modifiers: trimmed and stripped, upper-cased,
an optional `VK_`, `CONTROL` for `CTRL`, a single modifier text as a list of one; the `press` verb compiles to it, so
the verb route meets it too), `button: MIDDLE` on `swing.pointer`, `swing.click` and `web.pointer` ("the person's
clipboard and the system selection are never read into the target or written on a client's or a model's behalf"; the
web pointer's middle button pastes the X11 selection in a Linux browser as Swing's does), and `redact` other than
`true` on `web.bitmap`, `web.dom.text`, `web.ariaSnapshot` and `web.hitTest` ("a masked browser's page is a person's to
read"); refused, never stripped, no value in the reason (D470, D471). (b) **`visionCall` masks a tool set's tools,
output schema and provider tools** as its parts (S136): each tool through `ToolProjection.masked`, the schema through
`mask.schema`, each provider tool's `raw` through `mask.jsonLike`; a recording masks `outputSchema` too (unchanged
for a schema without a secret-named key or a known secret value, so no committed recording changes). (c) **The service
acts only a custom tool it listed** (S137): a custom tool's owner is the tool set that listed it in `tools()`; the
`handles` fallback serves only a provider toolset's member (the call carries its `toolsetName`), so a tool a read-only
policy withheld is "there is no tool …"; the module's own refusal (§6.3) comes before the executor's read-only refusal.
(d) **A tool set's reads count against `maxReadsPerTurn`** (S138): `SessionPolicy.maxReadsPerTurn()` is 20 or twice
`maxActionsPerTurn`, whichever is larger (a method, not a new component of the record, so no construction site
changes); a tool-set call made once that many reads were acted in the turn is answered "not run: the read limit of N
tool reads per turn was reached" and the turn ends `LIMIT`, as at the action limit; "`maxActionsPerTurn` counts every
action but a tool set's read" above now reads "… a tool set's read, which counts against `maxReadsPerTurn`" (D474).
(e) **The budget before the count** (S135, §10). (f) `SessionActions` gains the default method `webDefaultBrowser()`
(null by default; the facade answers the session's web settings' default browser), which §6.5's `webClick` rule reads.

### 9.2 `ExecutorSession`: `read`, the four-argument `execute`, the credential rule, the opt-ins, `host` (D308, D331, D332, D341, D342, D348, D351)

- **`public Object read(String primitive, Map<String, Object> args) throws AgentRpcException, AgentLostException,
  InterruptedException`**: one bounded read (`READ_TIMEOUT_MS`, 10 s, or the primitive's own timeout when longer,
  capped at 120 s) of a primitive the catalogue lists as not mutating and not `script` and that is not on the session's
  own exclusion list (`data.query`, `agent.*`, `hook.*`/`rule.*` beyond the three reads, anything destructive, and a
  `swing.capture` with `mode: ROBOT` or `redact: false`, since `read` has no origin; `IllegalArgumentException`); **masked** (every text value through the session's secret mask, **except an image
  result's `base64` entry**, the `Images` shape rule: its pixels the agent redacted, its `hash` the loop verifies, and
  the entry never reaches a text route; a mask applied to the base64 would corrupt the image where a short secret
  happens to occur in it, B19's lowered `masking.minLengthInText`); **not journaled** (as `snapshotText`, `eventsSince`
  and `fact` are not): the loop's captures and hit-tests are observations whose hashes the acted step's provenance
  records; **the agent's error is thrown**, not swallowed: `AgentRpcException` with the code, the message and `data`
  (the loop needs `NOT_FOUND` with `reason: outside` and `INVALID_PARAMS` for an expired `captureId`), `AgentLostException`
  when the agent is lost (the private `read` of today answers null on an error; the public one does not). The MCP server
  and the CLI keep their own routes for a client's reads.
- **`public StepOutcome execute(String verb, Object args, Origin origin, Map<String, Object> provenance)`** and
  **`execute(PlannedCall call, Origin origin, Map<String, Object> provenance)`** (D351): the three-argument forms pass
  null; the private `run` both forms share writes the provenance (masked, `intent` cut at 500 characters) into the
  step's `StepEntry` when it is non-null, so that the journal holds it on the step's own line (the journal is
  append-only and `finish` writes the entry inside `execute`; nothing can be added to it afterwards). What a tool passed
  in is what the journal holds; `SessionTools.Outcome` carries no provenance of its own.
- **`StepJournal.StepEntry`** gains the eighth component `Map<String, Object> provenance` (null for a step no vision
  tool chose; the seven-argument constructor keeps it null; `toJson` writes `provenance` only when present; `fromJson`
  reads it back, so the read-back equality test covers it). The keys, all optional but `tier`: `tier` (`V1 | V2 |
  V3`), `via` (`ROUTED | ROBOT`), `intent` (masked, at most 500 characters), `model`, `requestId`, `capture {hash,
  width, height, seq, space, origin, windows, file?}`, `point {x, y}` (image coordinates), `hitTest {ref, name?,
  className?, role?, locator, relative {fx, fy}, region?, row?, mark?}`, `anchor {label, rect, sha256, file, renderKey,
  rendering, window}` (an anchored step, §6.5), `verdict {verdict, explanation}` (for a visual question's note). No
  bytes (the appender elides them anyway), no secret beyond what `origin.intent` already carries. The model id and the
  request id stay in `origin` as well (one source, D80); **cost stays in the ledger**, found by `requestId`
  (`cost-ledger.jsonl`, `inference.jsonl`; P5 §12.3 as built); design §3.4's "(intent, model, request id, tokens,
  cost)" is amended to say so (D331).
- **The credential rule** (D342): the mechanisms, the first two in `ExecutorSession` and the plan form in `PlanPolicy`,
  so that every route meets them:
  1. both `execute` forms refuse, **for every origin but `PERSON`**, a step whose arguments as given name a secret field
     of a credential join (`$<slot>.<key>` where `joins.get(slot)` holds `key`), **and a step whose compiled calls would
     reference such a field** (a pack verb given a credential-joined slot: `signOn {staff: $assistant}` from a model or a
     client), the first before anything is compiled, the second on the compiled calls, both before anything is journaled
     or sent (`ERROR / ENVIRONMENT` in the read-only refusal's shape: "a credential is never used on a model's or a
     client's behalf; the person signs on first"); a person's verb joins the password inside the verb as today (D94), so
     a model never signs on with a person's credential and no dead end follows (only a person's step consumes a secret,
     and the person is at hand); the session's vocabulary (`SessionService.vocabulary()`, VM's first commit, §12.5) stops
     telling the model that a `$reference` names a resolved slot's secret field (it names the non-secret fields only).
     **(Phase 7 follow-up, gap 94, built 2026-10-11)** The sentence names the whole-slot rule too
     (`SessionService.REFERENCE_SENTENCE`: "… names a non-secret field of a resolved slot, never a credential's secret
     nor a credential slot as a whole: the person signs on"), and the five Phase 5 session recordings were regenerated on
     the key-free route. The earlier wording reached the model as "never a credential's secret: <redacted> person signs
     on" (the mask's `key: value` rule read "secret: the" as a secret's value); the new wording passes the mask whole.
     Rule 1's predicate is public as `ExecutorSession.namesSecret(value)`, which the facade's `namesSecret` answers
     with (§9.1, §6.5).
     **(Phase 7 security fix, S101, built 2026-10-11, SH)** The slot as a whole is such a reference: `$<slot>` of a
     credential-joined slot, alone or embedded (`x$<slot>`, `x$<slot>.`, `x$<slot>..password`: every spelling the
     substitution reads as the slot), resolves to the slot's value with its joined fields (A34: `Bindings.isSecretField`
     treats the empty field list as secret) and is refused exactly as the field form is, on both checks; a person's call
     that references the slot as a whole consumes it (rule 2) as the field form does. And for every origin but `PERSON`
     the session resolves a step's call arguments against the slots' public values (`Bindings.substitutePublic`; P2 §8.4
     as amended), so that no spelling of a reference reaches a joined secret on a model's or a client's behalf; the
     refusal stays the early, well-worded answer, and a value nested too deep to be read counts as a secret reference
     while a join exists.
     **The plan form of the same rule**: `PlanPolicy.refusals(plan)` refuses, for a client's scenario
     (`skillroy_scenario_run` under the mutating server, the planner's server), a step whose arguments as written (a base
     verb's or a raw `call`'s, not a pack verb's own compiled references) name a field of a slot the plan declares with
     `credential:`, unless the slot's own `fixture` value provides that field; the refusal names the step and the slot,
     never a value, and nothing runs (§9.1, §9.4): such a run goes through `PlanExecutor` with the operator's
     credentials and meets none of the session-side mechanisms, and a `swing_capture` read would otherwise carry what the
     step typed as pixels. **(Phase 7 security fix, S101, S102, S107, S128, built 2026-10-11, SH)** The plan form
     refuses a credential slot's field its `fixture` does not provide **or the slot itself**, reads the references with
     the executor's own tokenizer, judges a pack verb's written arguments by step identity, fails closed past the depth
     bound and needs the written scenario from every caller: §9.4's note;
  2. **`secretsInFlight()`**: true from the moment a credential join resolves a secret into the session's bindings (a
     person's `resolve` of a credential slot) until a step of a `PERSON` origin has consumed every such reference (its
     compiled calls referenced the slot's secret field); while it is true, `execute` refuses, for every origin but
     `PERSON`, every `type`, every printable `press` and every raw `swing.type`/`swing.key` ("a credential is in flight;
     the person signs on first"), and the facade and the recorder refuse the same before the call is built (§6.5).
     **(Phase 7 security fix, S127, built 2026-10-11, SH)** Widened (D342): while it is true `execute` refuses, for every
     origin but `PERSON`, every step one of whose compiled calls may change the target (a primitive the catalogue lists
     as mutating or destructive, one it does not list, and every typing call as above), a pack verb over the
     application's input seam (`enter`, `scan`, `key`, `quantity` over `lending.input`) and its raw primitive included,
     with the same text and `{refused: credential}`; reads still run (the model has nothing to change while the person
     is mid-sign-on); the facade's and the recorder's earlier refusals stay as they are;
  3. a `type` whose text contains a secret value the session's mask knows (`PromptMask.holdsSecret(text)`) is refused by
     the facade and the recorder (§6.5).
  Sign-on in a vision session therefore runs through the `signOn` verb's credential join **by the person** (the REPL
  step before `ask`, under `Origin.person`) **or by the player** (the scenario's `signOn` step before the first `aiAct`,
  §6.8), never on a model's or a client's call; §11 rule 6 and the threat model's §5 state it.
  **Fix wave (Phase 7 review, FC, 2026-10-10):** the turn's list of the slots bound in the session (§9.1, LV-1) follows
  the vocabulary's rule: it names a slot's non-secret fields only, never a value, a secret or a secret field's name.
- **`SessionOptions.robot`** and **`RunOptions.robot`** (boolean, default false; the thirteenth component of each
  record, after `observer`; the older constructors and `withRobot(boolean)` keep every caller): the controller half of
  the double opt-in (§3.4), set by a person's flag only; journaled in `session-start.options` (beside the host's
  `vision.*` tool options and `host`) and recorded in `run-start`. The private `run` both `execute` forms share refuses a
  call with `via: ROBOT` or `mode: ROBOT` before it is sent when the option is false (`ERROR / ENVIRONMENT`, the
  read-only refusal's shape), **and refuses `mode: ROBOT` for every origin but `PERSON` whatever the option** (D341);
  `PlanExecutor` refuses a plan with such calls before `checkRequires` (§8.1).
- **`SessionOptions.host`** (`Set<String>`, default empty; the fourteenth component, `withHost`; D348): the features the
  host declares to the capability merge of `open` (§8.5), `"vision-session"` among them; journaled in
  `session-start.options`. **`public CapabilityMatrix capabilities()`** answers the merged matrix after `open`.
- *(Phase 8, built 2026-10-19, EP; P8 §9.3.4, §9.1.5.)* **`SessionOptions.suite`** (`SuiteCache`, default none; the
  fifteenth component, `withSuite`; the fourteen-argument constructor kept): `open` answers a `SUITE`-scoped pre-flight
  check and pack capability from it under the connection's agent session; `open` also evaluates the pack's capabilities
  after the contributors' merge (with the session's data channels, environment id and the pack's settings), so
  `capabilities()` holds them, and journals their ids in `session-start.options.pack` when there are any.

**As built (Phase 7, VC):** the `read` exclusion list also refuses a primitive the catalogue does not list and a
`script` primitive; the timeout is 10 s or the descriptor's own, at most 120 s. The credential rule: `inFlight` is set by
any `resolve` that joins a secret, whatever its origin, and a person's step consumes a slot when one of its compiled
calls references the slot's secret field; while a credential is in flight a non-person's step is refused when one of
its compiled calls is `swing.type`, or `swing.key` with a key outside a fixed set of keys that type nothing (`ENTER`,
`TAB`, `ESCAPE`, the arrows, `F1`-`F24` and the like: deny by default), which covers a pack verb that types (D369). The
refusals' detail is `{refused: robot | glass | credential}`, never a value. `expectImage(Plan.Step, Origin)` has an
overload with `ImageSteps.Baselines` and the static `ExecutorSession.baselines(bindingsRoot, envId, scenarioId)`: a
session holds no bindings file, so the two-argument form records and skips an `expectImage` (the session is `live`;
the skipped line is in the outcome's `degradedFidelity`, the session having no skipped-expectations list) and answers
an anchored step `REBIND_NEEDED`; the scenario player hands in the scenario's baselines (D359, D377). The step is
journaled with its verb and the arguments reconstructed from the compiled step (D360). A guarded `pointer` executed
with a provenance whose `anchor` carries the guard's label is not compared: the recorder took that anchor with the
guard's own call an instant before, and the comparison starts with the export's replay (D361). `session-start.options`
holds `robot` only when true, `host` only when not empty (D374).

**As built (Phase 7, VM, D425):** `ExecutorSession.fixture(slot, value, credential, origin)` binds a fixture slot into
the session, as a plan's fixture is bound: the value's `$references` to earlier slots are substituted (public values),
the slot is declared `fixture` so that a later verb compiles against it, its strategy is `FIXTURE` (it has no
resolution and no bindings-file entry), and with a credential the entry's id is the value's `key` field and the
secret is joined (an absent credential entry or a secret field that is already a field of the value is
`ENVIRONMENT`, as in a bind). **Origin rule:** a slot that declares a credential is bound only for a person's origin
(the scenario player binds as the person); for a model's or a client's origin it is refused before anything is joined
or journaled ("a credential slot is bound by a person, never on a model's or a client's behalf"), consistent with D342.
Joining a secret puts the slot in flight exactly as a `resolve` does.
**(Phase 7 security fix, S113, S139, built 2026-10-11, SH)** The origin rule extends to every slot and to `resolve`: a
slot whose current binding a person made (a person's `resolve` or `fixture`, the scenario player's binding of a
scenario's slots) is rebound for a person's origin only; for any other origin `resolve` and `fixture` refuse it before
anything is resolved, joined or journaled (`BindException` `ENVIRONMENT`, "slot $<slot> was bound by the person;
resolve under another name", `detail.refused: slot`; `ExecutorSession.personSlotRefusal`), so a model or a client can
neither move what a person's later step acts on nor drop a credential slot's join and in-flight state; it binds new
slots and rebinds those a model or a client bound (either), and the person rebinds any. The `resolve` record carries
`origin` (`StepJournal.ResolveEntry`'s eighth component; the seven-argument constructor and a journal written before
the fix have none). The export marks a slot whose `resolve` record names an origin other than `PERSON` `REQUIRED` by
nobody until a person approves it, whatever its strategy (D97), or `APPROVED` by the exporter's own label when one is
named (the CLI's `--approved-by`, a person typing it); a record without an origin exports as before. Replay refuses
an unapproved value for a model's strategy only (D72), so for a tier (a) slot the mark is the reviewer's, in the
export's bindings file. **Journal:** one `fixture` record
`{type: fixture, slot, value, credential?: {realm, key}, origin}`, the value masked like every record; the export
writes the slot back as `resolve: <slot>: {fixture: <value>, credential?: …}` in the order the journal bound it, and a
later `resolve` or `fixture` of the same slot replaces it. The scenario player's default `FixtureBinder` is this
method; a host may still pass its own.

### 9.3 The CLI (`skillroy session`, `run`, `mcp`, `caps`)

| Command | Addition |
|---|---|
| `session` | `--vision v1 \| v2 \| v3 \| off` (the tier; default as §9.1), `--vision-model opus \| sonnet \| haiku`, `--vision-tools toolset \| custom`, `--allow-robot` (sets `SessionOptions.robot`; required for `--vision v3`, else a usage error before the session opens), `--play <scenario.yaml>` (the `ScenarioPlayer`, §6.8; with `--export <id>` the result is written as `export` does, PNGs beside; without `--play` the REPL as today); the command `ask` is the one interactive form (no separate `act`, D333); `SessionOptions.host` set from the flags and the module's presence (§8.5); the start-up line names the vision tier and why it is off when it is |
| `run` | `--allow-robot` (sets `RunOptions.robot`); in `--mode approve` the image approvals of §8.7 (the candidate's path and size printed, `--yes` approves); the summary line counts `skipped`; `--json` gains `skipped` and, per result, `skipped: true` with the reason |
| `call` | `--allow-robot` beside `--confirm` (VC): the raw command sends its arguments to the agent through the client with no `ExecutorSession` and no `CallPolicy`, so without the flag `CallCommand` refuses `via: ROBOT` and `mode: ROBOT` in the arguments before anything is sent; with it the person's typed argument is the second half of the opt-in (§3.4, §11) |
| `mcp` | `--vision v1 \| v2 \| v3` (the tier of `skillroy_session_ask`'s actor; default `off` unless given), `--allow-robot` (`ServerOptions.robot`; required for `v3`; enables V3 inside `skillroy_session_ask` and nothing else, §9.4); the help says captures reach the client only under `images: true` (unchanged, D159) |
| `caps` | a `source` column (`agent`, `controller:inference`, `controller:vision`) and the merged matrix when a pack and settings are given (`--pack`, `--inference`), the agent's alone otherwise. *(Phase 8, built 2026-10-19, EP; P8 §9.1.5)* with `--pack` and a single pack also the pack's own capabilities, a row each with `SOURCE pack:<packId>` (table and `--json`), evaluated with no credentials (every source through the agent's `data.query`), the environment `default`, the pack's settings and no cache; a source served over JDBC only then shows `UNAVAILABLE` (gap 149) |
| JUnit glue | the property `skillroy.robot` (`true` sets `RunOptions.robot`; the glue prints one warning line naming both halves of the opt-in whenever it is true, since a property can sit in a committed build file); `SKIPPED` as `Assumptions.abort` (§8.6) |

Exit codes unchanged (`Exit`); a skipped run is `OK`.

**As built (Phase 7, VM, 2026-10-10) for `session` and `mcp`:** the options are parsed by `VisionArgs`: `--vision v3`
without `--allow-robot`, `--vision-model haiku` with `v2`/`v3`, an unknown value, `--export` without `--play`, `--play`
with `--script` and `--play` without a gateway are usage errors before the agent is asked; the start-up line ends with
`vision v1 (…)` or `vision off (<why>)` (`--vision off`, the module absent, no model gateway, `images: false`, or the
target's `swing.capture.screen` not `OK`); `--play` runs the player instead of the REPL, prints each step and the
status, and with `--export <id>` writes the session as `export` does (exit 0 for `PASS` or `DEGRADED`); `mcp --vision`
prints its line on stderr. `export` (and `--play --export`) reads the anchors' PNGs from the session's output directory (`ExportOptions.withCaptures`) and writes each of `ExportResult.images()` where its bindings entry says (`<scenarioId>.images/<label>.<renderKey>.png` beside the bindings file, created exclusively); the player gets the session's bindings root and environment (`ScenarioPlayer.withBaselines`).

**As built (Phase 7, VC):** `run --allow-robot`; the terminal approver of `approve` is wrapped by
`RunCommand.imageApprover`, which prints `image baseline <label> of <scenario> in <env> for rendering <key>: <w> x <h>
pixels`, the candidate's path (or that the run has no `--out`), another rendering's baseline when there is one, and asks
`approve the baseline <label>? [y/N]`. `call --allow-robot`: without it `via: ROBOT` or `mode: ROBOT` at the arguments'
top level is a usage error (exit 64) before anything is sent. `caps` takes `--pack` and the gateway options; with either it
merges the contributors of the packs' loader in mode `live` without host features (`inference.vision` then answers `no
vision session`), and both the table (`SOURCE`) and `--json` carry the source (D372). The JUnit glue reads
`skillroy.robot` (`true`, `false` or unset; another value is the `environment` failure) and prints its warning once per
stream whenever the effective options allow Robot; a scenario with an `expectImage` or an anchored `pointer` counts as
needing bindings.

**(Phase 7 security fix, S117, S148, built 2026-10-11, SJ.)** `run --mode approve`: without an output directory there is
no candidate file, so the terminal approver rejects each baseline without asking (`no candidate file to review: run
with --out`) and `--yes` approves (§6.7). `session` without `--out` writes into a **new owner-only directory under the
system's temporary directory** (`SecureFiles.createOwnerOnlyTempDirectory(java.io.tmpdir, "skillroy-session-")`, the
MCP server's rule, P6 §9.3, S60), printed on stderr at the start (`no --out given: writing into <dir> (owner-only; kept
after the session)`) and at the end (`the session's files are in <dir>`), never `./run-output/<session id>/` with the
process umask: the session writes its journal, its ledgers, its telemetry and a screenshot of every acted-on screen
there (§9.6), and `SECURITY.md` (f) advised running "without an output directory, which keeps nothing", which the CLI
did not offer (D485). `export` without its own `--out` writes into that directory too. A system temporary directory
on a volume with neither POSIX permissions nor ACLs ends the command before the agent is contacted; give `--out`.

### 9.4 The MCP server

`ServerOptions` gains `vision` (`Map<String, String>` tool options) and `robot` (boolean); the actor factory of
`skillroy_session_ask` opens the session with `SessionOptions.host` from `--vision` (§8.5) and the service with
`SessionExtensions` from `SessionTools.load(loader)` and the options; **`--allow-robot` enables V3 inside
`skillroy_session_ask` and nothing else**: a client's raw `swing_pointer {via: ROBOT}` and `swing_capture {mode:
ROBOT}` are refused whatever the flag (D347, D341). **`ToolCalls.primitive` calls `CallPolicy.arguments(primitive, args,
false)`** and refuses, never strips, `redact: false`, `force`, `via: ROBOT` and `mode: ROBOT` (a tool error naming the
primitive and the argument, before `execute`). The raw tool names follow the server's snake-case rule:
`swing_hit_test`, `swing_rendering` and `web_hit_test` are reads (offered under the default read-only server, as
`swing_capture` is), `swing_pointer` and `web_pointer` are mutating (under `--mutating`); `ToolListTest`'s pinned lists
come from a fake catalogue and do not change. `skillroy_catalogue` shows the merged matrix with `source`; the captures
directory's files are listed as resources `skillroy://captures/<sha256>.png` only under `images: true` (else nothing
is listed: the journal names the file, D159), and a `ROBOT` capture is never among them; `PlanPolicy` admits `pointer`
and `expectImage` steps in a client's plan (their primitives are offered under `--mutating`; `via: ROBOT` and `force`
refused as a client's) and refuses `aiAct`/`aiExpect` ("run through skillroy session --play"). **`PlanPolicy.refusals`
also applies the credential rule's plan form** (§9.2): a client's scenario whose base verb or raw `call` names a
credential slot's field that the slot's `fixture` does not provide is refused before `PlanExecutor` runs anything
(`skillroy_scenario_run` runs with the operator's credentials, and the client's next `swing_capture` would otherwise
carry what such a step typed as pixels under `images: true`).

**As built (Phase 7, VM, 2026-10-10):** `ServerOptions` refuses `vision.tier: v3` without `robot`; the server declares
the host features only when `--vision` names a tier, a `SessionTools` of id `vision` is on the class path, a gateway is
named and `images: true` (the server never names a vision type); the captures are listed after each
`skillroy_session_ask`. `PlanPolicy.refusals(plan, scenario)` (the one `skillroy_scenario_run` calls) applies the
credential rule's plan form to a pack verb's arguments as the client wrote them too (a pass-through verb given
`$staff.password`), never to the verb's own compiled references (D427); `refusals(plan)` keeps the base-verb form.

**Phase 7 security fix (S101, S102, S107, S128, built 2026-10-11, SH):** `PlanPolicy.refusals(plan, scenario)` is the one
form: the scenario as written is required (`NullPointerException` otherwise) and every caller passes it,
`skillroy_scenario_run` and `skillroy author --run`, which reads the scenario back from the file the planner wrote and
refuses the run, nothing sent, when it cannot or when the file no longer compiles to the planner's plan; the
one-argument form, which judged a pack verb on its compiled calls only, is removed (S107). Plan step `s<i>` is judged
with the scenario's step `i`, the compiler's one-to-one numbering, never by source line, which two flow-style steps or a
scenario written as one line of JSON share (S102); in a plan that declares a credential slot, a plan that does not
number the scenario's steps so is refused. The references are read with the executor's own tokenizer
(`References.references`, public for this), so that every spelling the substitution reads as a slot is the slot
(S101). Refused, naming the step and the slot (and the field), never a value: in a base verb's or a raw `call`'s
compiled arguments, a field of a credential slot its `fixture` does not provide and the slot as a whole; in a pack
verb's arguments as written, such a field and the slot as a whole embedded in a longer text, while a string that is
exactly `$<slot>` hands the slot to the verb (the verb's own join, `signOn {staff: $staff}`, D427); in a pack verb's
compiled calls and in the cleanup calls, the slot as a whole, which would reach the agent with its joined secret
whatever the verb meant (a verb that joins names the field it puts where, as the sample's `signOn` does); the verb's
own field references stay unjudged (D427). A value nested deeper than 64 levels (`References.MAX_DEPTH`) in a plan
that declares a credential slot is refused rather than searched (S128), as `References.anyReference` does.

**(Phase 7 security fix, S115, built 2026-10-11, SJ.) A client's `skillroy_scenario_run` writes nothing in the
operator's bindings root, in either mode.** `live` never wrote (`withWriteBindings(false)`); `approve` is now refused,
before the scenario is compiled and before any model budget is reserved, while the session's approver is
`REJECT_ALL` (the CLI's server always: `this server has no approver (a person approves bindings and baselines); run
approve at the CLI`), since nothing can be approved there, and an images-only plan in `approve` writes nothing without
an approver anyway (§8.7). A server a Java host builds with another approver runs `approve` under that approver's
decisions. The tool's schema still lists both modes (a pinned list).

### 9.5 The overlay

`OverlayBridge` is unchanged: `assist.requested` carries the person's request, `ask` is the one entry, the tier is the
host's option, the answer goes back through the pack's `assistAnswerPrimitive`; V3 over the overlay needs the host's
`--allow-robot` like any other session.

### 9.6 Captures on disk

Every capture the loop acted on (the one a recorded step's provenance names) and every anchor crop (§6.5) is written
once as `captures/<sha256 hex>.png` under the session's output directory through `OutputFiles` (exclusive create; a
second write of the same hash is skipped): **the raster as captured**, redacted by the agent and without marks (the
controller draws marks on its own copy, D346), named by its own `hash`, so that the file is the pixels the model's view
was made from; the provenance's `capture.file` and `anchor.file` name it; a session without an output directory keeps
nothing (and cannot export an anchored step, §6.6). A `ROBOT` capture is never written here (D341). Served over MCP
under `images: true` only (§9.4); listed in P2 §11.4's rule as "masked pixels, never a text route". The directory holds
one screenshot of every acted-on screen and takes the process umask: on a shared host run the session under `umask 077`
or without an output directory (`SECURITY.md`, S37's advice; threat model A13). The anchors an export writes go beside
the bindings file, not here (§8.7).

**As built (Phase 7, VM, 2026-10-10):** `Captures.write` skips a file that exists (a regular file, links not followed)
and otherwise writes through `OutputFiles.write`; the name is checked to be `sha256:` and 64 hex digits.

**(Phase 7 security fix, S148, built 2026-10-11, SJ.)** "Without an output directory" exists for the Java API
(`SessionOptions.defaults(null)`) only: `skillroy session` always has one, and without `--out` it is a new owner-only
directory under the system's temporary directory, printed at the start and the end (§9.3), not `./run-output/<session
id>/` with the process umask. The advice reads: without `--out` the session writes under an owner-only temporary
directory; on a shared host give `--out` a directory you own, under `umask 077` (the text for `SECURITY.md` (f) and
`README.md` is the orchestrator's).

---

## 10. Budgets and the cost ledger

| Limit | Value | Where |
|---|---|---|
| per session | USD 1.00 (`budget.sessionUsd`, unchanged; BS12 RESULT "Proposal": a 30-turn session extrapolates to about USD 0.30 on Sonnet 5.5, 0.5 to 0.7 on Opus 5.5, so the action limit binds first) | the one `SESSION` meter of the host (D157), reserved before every vision call (D74) with the estimate of §7.5 |
| UI actions per session | 30 (`vision.maxActions`) | **the service**, counting every `Outcome.step` a tool's `act` ran while a tool applies (reads do not count) |
| model turns per session | 20 (`vision.maxTurns`) | **the service**, counting every model call made while a tool applies (its own loop calls and a tool's `complete`); at a limit it ends the turn `LIMIT`, closes the vision tools for the session and tells the model why |
| actions per turn | 10 (`SessionPolicy.maxActionsPerTurn`, unchanged) | the service |
| per scenario (the player) | the scenario budget applies to the player's binds (tier (b)); its `aiAct` turns and `aiExpect` questions are session calls on the session meter | `BudgetMeter` scopes unchanged |

Every vision call is a `ModelRequest` with purpose `vision` through **`SessionService.visionCall`** (the facade's
`complete` and the player's `aiExpect` alike; the module never opens a gateway): the entry's `purpose` `vision`,
`strategy` null, `slot` null, `details` the masked `inference.jsonl` record with the image parts as hashes; so
`replay`'s ledger check (D19) and the JUnit stream's `skillroy: zero model calls` cover the bolt-on by construction; a
failed call is a ledger entry too (D102). The `modelCall` span of the telemetry observer is built from the entry as
today and carries no image, prompt or intent (P6 §10.2).

**Phase 7 security fixes, S135 and S138 (built 2026-10-11, SI):** the table's "actions per turn … unchanged" row is
corrected and joined by two rows: **tool-set reads per turn**, `SessionPolicy.maxReadsPerTurn()` (20, or twice the
action limit when that is larger), the service, the turn ending `LIMIT` at the bound; **the waits of a turn**, at most
three step timeouts together (30 s with the default 10 s, at most 330 s), the vision module, the turn ending `LIMIT`
(§6.3). Since Phase 7 the action row counts every action but a tool set's read (§9.1). **A token count transmits the
request** (images included) to the provider, so the service refuses before anything is counted or sent when the meter
is exhausted or its limit is zero (in `complete` and at the top of `visionCall`), and, when the gateway has
`TOKEN_COUNT`, checks the local estimate (text length / 4 plus each image's tokens, §7.5, plus the maximum answer)
against the remaining budget first and asks the provider's count only when even that fits; a call refused so is a
`BudgetException` as a refused reservation is (S135). The local estimate overcounts as a rule, so a call close to the
remaining budget may be refused that the provider's count would have admitted (D476). P5 §7.2 as built reads the same.

---
## 11. Safety and data

The rules, each with where it is enforced; `threat-model.md` carries the judgement (its §2, §3, §5, §6, §7 and §8 are
extended with this phase, D334):

1. **Pixels are personal data until masked, and what leaves the JVM for a model is a redacted `PAINT` capture of the
   target's own windows.** `swing.capture` redacts by default (§3.6); the vision module never passes `redact: false`; a
   model or an MCP client cannot (`CallPolicy.arguments` on every route, §9.1); a person at the CLI may (`call
   swing.capture {redact: false}`), and that capture reaches no model. **A capture of the glass (`mode: ROBOT`) is a
   person's evidence only** (D341): refused for every other origin by `CallPolicy` and by `ExecutorSession`, never a
   vision tool's, never written to `captures/`, never served over MCP, and even a person's blanks every pixel outside
   the JVM's windows and says `glass: true`. **Pixels carry no value mask**: what the application renders in a component
   no mask names reaches a model as rendered, so a pack's `CaptureMask` must name every component that can show a
   secret, and a page's pixels are masked as a whole browser component or not at all (§4.8). What masking cannot hide:
   the layout and a value's width (R-a of map 7).
2. **Pixels leave the controller only as an image and only under `images: true`** (D159, D244 unchanged): an `Image`
   part to the model, a PNG resource to an MCP client; on every text route the bytes are elided (`Images`); the
   captures directory, the failure files and the baselines are files on disk through `OutputFiles`, never a text route.
3. **Screen content is untrusted input** (threat model §5; vision §R7, §Risk7). The tools a vision session offers are
   the target's own primitives through the pack's verbs and the module's custom tools, each acted through the service's
   facade under `CallPolicy` with the turn's `Origin.model` (§9.1); a model's or a client's raw primitive call meets the
   same `CallPolicy.arguments` at its host (`SessionService.act`, `ToolCalls.primitive`, `PlanPolicy`); nothing a model
   produces reaches `data.query` (the facade's `read` and `ExecutorSession.read` refuse it), `hook.*`, `rule.*`,
   `agent.*` or a module upload; a verdict (`aiExpect`, `skillroy_look`) is advisory and never turns a failing
   scenario green (§6.4); a locator taken from screen text that the mask would change is never recorded (§6.5); the
   marks list and the hit-test's names enter a prompt only inside `<target-ui>` blocks, the model's own question inside
   `<model-question>`, and the pack's legend in the system parts masked like the vocabulary (P5 §6.2).
4. **Read-only mode and `confirm` stand.** `swing.pointer` and `web.pointer` are mutating (refused `READ_ONLY` by the
   dispatcher and by the session's pre-check); `swing.hitTest`, `web.hitTest`, `swing.capture` (every mode) and
   `swing.rendering` are reads; nothing new is `destructive` (a `confirm` per pointer action would make a person confirm
   every click; the Robot gate is the double opt-in instead, D294); a `MODEL` or `CLIENT` origin still confirms nothing
   (D158); under a read-only policy the vision module offers reads only (§6.3).
5. **V3 is a person's choice twice over** (§3.4): the agent option `robot` on the host and `--allow-robot` on the
   command (`run`, `session`, `mcp`, and the raw `call`, which refuses `via: ROBOT` and `mode: ROBOT` in its arguments
   without it, §9.3); never a tool argument, never a scenario key, never an MCP client's `_meta`, **and never a per-call choice of
   a model or a client even with both flags** (`via: ROBOT` passes on the facade's route for the module's V3 steps and
   nowhere else, D347). Both halves can sit in committed files (the agent option in a launcher, `skillroy.robot` in a
   build file), so the JUnit glue warns whenever the property is true and `SECURITY.md` says not to commit it. A point
   outside every window of the target JVM is refused, **any** covering window of the JVM refuses the point (but an owner
   of the active target window, gap 101, §3.4), the target
   JVM must hold a focused window at the press (`requireFocus`, default true), a press is always paired with its release
   before the pointer moves back (D343), the delivery probe and `NOT_DELIVERED` make a silent drop visible (BS9: macOS),
   Robot never runs on the EDT, and the loop stops the turn when the JVM loses the focus instead of clicking to win it
   back (D344).
6. **No credential to a model, and no input into a masked component** (D342, D358). No secret slot reference resolves
   from a model's or a client's call, and no verb of theirs uses a credential-joined slot (`ExecutorSession` refuses
   `$<slot>.<secret>` and a step whose compiled calls would reference one for every origin but a person's before anything
   is journaled or sent; `PlanPolicy` refuses a client scenario's base verb or raw `call` that names a credential slot's
   field its `fixture` does not provide, §9.2); no `type`, printable `press` or raw `swing.type` runs for them while a credential is in
   flight (`secretsInFlight`); **(Phase 7 security fix, S101, S127, built 2026-10-11, SH)** the joined slot as a whole
   (`$<slot>`, in every spelling) is such a reference, on every route and in the plan form too (§9.4), a model's or a
   client's step resolves its call arguments against public values only, and while a credential is in flight nothing
   of theirs changes the target; a `type` whose text holds a known secret value is refused; sign-on runs through the
   `signOn` verb's credential join by the person or the player before the loop (§9.2, §6.8). The recorder refuses a
   `click`, `pointer`, `type`, `press` or toolset `key` at a component the hit-test reports `masked` (§3.2, §6.5) with the
   text "the component takes a secret; ask the person to sign on"; a pack's mask names what
   feeds a secret field too (the sample's number pad while the entry field is masked, §12.2). **(Phase 8, built
   2026-10-19, EA; P8 §3.2, D507)** It refuses a component the hit-test reports `vetoed` the same way, on every gesture,
   for a vetoed focus owner and in V2's raw pointer, with `Recorder.VETOED` ("the application's agent module refuses
   input to that component"): nothing vetoed becomes a step, a journal line or an export; a hit without the key (a
   0.1.0 agent) is not refused for it, since the agent is the enforcer.
7. **Captures the loop acted on are kept masked** under the session's output directory, referenced by hash (§9.6; a
   screenshot per acted-on screen, under the process umask); a baseline or an anchor holds masked pixels only and is
   reviewed code (§8.7; `approve` and the export write one only after the person saw the file, `--yes` aside); the
   journal, `run.jsonl` and the export hold hashes and file names, never bytes; a `ROBOT` capture is kept nowhere.
8. **Routed input respects what AWT would** (BS10 RESULT conditions 1 and 2): the modal check before the first event and
   before every further event of the sequence (a dialog the sequence itself opens stops it, D352), the covering-window
   check, and a call that failed delivers nothing later (the `cancelled` flag); a person may pass `force` (never a
   model or a client: `force` on `swing.pointer`, `swing.click`, `swing.type`, `swing.key` and `web.pointer` joins
   `CallPolicy.arguments`' refusals for a `MODEL` or `CLIENT` origin, D294).
9. **No model call in `replay`**, asserted (C0): the vision tools are never loaded there, `inference.vision` is
   `UNAVAILABLE("replay")`, a `run` plan is `SKIPPED` before the agent is contacted, and the ledger check covers every
   vision call of every other mode (every one goes through `visionCall`, §10).
10. **Budgets** bound every vision call before it is made (§10).
11. **The person's clipboard and the system selection are the desktop's** (Phase 7 security fix, S110, built
    2026-10-11, SI): never read into the target or written on a model's or a client's behalf. `CallPolicy.arguments`
    refuses a paste, copy or cut chord on `swing.key` and the middle button on `swing.pointer`, `swing.click` and
    `web.pointer` for a `MODEL` or `CLIENT` origin on every route (§9.1), and the vision module answers the same as text
    before anything is grounded (§6.3, §6.5). Before, a model (V1 by default, the planner's mutating server since Phase
    5) or a client could paste whatever the person's clipboard or the X11 selection held into an unmasked field, and the
    next capture sent it to the provider (L5-F1, L6-F1; D470). The residual: an application's own Paste item, context
    menu or button is an ordinary click the framework cannot enumerate; a pack masks such a control, and rule 6 then
    refuses every input at it. Rule 6's recorder refusal also covers V2's button halves and `mouse_move` (S112, §6.3).

**The limitation sentences the build writes into `SECURITY.md`'s "Known limitations"** (§15's L2 and L8 check that
they are there; the threat model's §8 carries the same facts):

- (a) redaction blanks a component's rectangle after it was painted: the layout and a value's width stay visible; and
  pixels carry no value mask: a component no mask names reaches a model as rendered, so a pack's `CaptureMask` must name
  every component that can show a secret;
- (b) a page's pixels are masked as a whole browser component or not at all;
- (c) V3 presses whatever is at the screen point when the desktop changed in the milliseconds between the agent's focus
  read and the press, and a covering window of another process cannot be seen; the first press that is not received
  degrades the capability, so the damage is bounded to about one stray click per focus loss;
- (d) a person's `ROBOT` capture shows the glass inside the target's window rectangles (another process's window that
  covers the target is in it); it never goes to a model or a client;
- (e) committed baselines and anchors are screenshots of the application's data as rendered, masked per component: the
  author masks what must not be committed;
- (f) `captures/` holds a screenshot of every acted-on screen under the session's output directory and takes the process
  umask;
- (g) the V3 tier is verified on the Linux image's display only; macOS with the Accessibility permission, Windows,
  Wayland, a locked session and HiDPI are not (§13.4);
- (h) the double opt-in can be committed (the agent option in a launcher configuration, `skillroy.robot` in a build
  file); do not commit it.

**Corrected by the security fixes (2026-10-11; `SECURITY.md` carries the corrected forms, written by the closing documentation
pass):** (a) also: a mask's `regions` hide the text they cover, two masks combine by union, and a masked label's or button's
name is withheld with its text (S106, S122, S123); (b) also: a masked browser is masked on every route (S111; (Phase 8,
built 2026-10-19, EW; P8 §5.2) its events, its selection error data and its action answers included, a browser without a
view masked while any mask is registered, the CDP path handing unmapped pages and the three actions to the agent); (c) reads: V3
refuses a point in a minimised frame or a window it owns, a point no screen shows, a point under another window of the JVM
and `modifiers` (S104, S105, S124), and still presses whatever is at the screen point when the desktop changed in the
milliseconds between the agent's focus read and the press, when a covering window of another process cannot be seen, and
when a window of the JVM lies on another virtual desktop or workspace; a press that is not received, or that another
component received, makes the next call probe delivery again, and that probe, not the press, degrades the capability when it
fails, so the damage is about one stray click per successful action in between (not 'per focus loss'; the first sentence's
"the first press that is not received degrades the capability" is withdrawn); (f) also: without `--out` the session writes
into a new owner-only temporary directory (S148), not nothing; and two sentences more: (i) a model or a client can no longer
paste, copy or cut through the person's clipboard or the X11 selection (rule 11, S110), and an application's own Paste
control is an ordinary click, to be masked; (j) S142 and S144 are limitations accepted by the owner (B31, 2026-10-11).

---
## 12. The sample

### 12.1 The desk (`skillroy-sample-lending-app`, Java 8)

A **number pad** (`NumberPad extends JComponent`, component name `numberPad`, `AccessibleRole.PANEL`, 3 columns by 4
rows of 48 x 40 keys: `7 8 9 / 4 5 6 / 1 2 3 / . 0 CLEAR`, custom-painted with `drawString`, one component with twelve
regions, region ids `key:0` to `key:9`, `key:.`, `key:clear`), placed in the bottom panel's `EAST` beside the keypad. A
press and release inside a key appends the digit (or the point) to the entry field's document through the field's own
`Document` (so `EntryField`'s mask filter applies) or clears it; it never posts to the engine itself (the entry field's
Enter does). It is the desk's one component with several meanings per component, the case `RegionResolver` exists for
(vision §R4 "keys of an on-screen keypad"; map 7 §1.9: nothing else on the desk has regions). The frame's other
components are unchanged in kind; the bottom panel grows to the pad's height (about 160 px beside the 64 px keypad
row), so the loan table above it shrinks and the snapshot bounds of the earlier components change, which nothing
committed depends on (the session recordings are made on `HeadlessDesk`, which has no Swing tree, §12.5).
`LendingUiDisplayTest` gains a test that a press on `key:7` appends `7`.

**As built (Phase 7, VS).** The keypad sits in a panel anchored `NORTH` inside the bottom panel's centre, so that its
keys keep their 64 px beside the 160 px number pad (the keys' tree paths change; nothing committed depends on them)
(D397).

### 12.2 The agent module (`skillroy-sample-lending-agent`, Java 8; version `0.2.0`)

- `LendingRegions implements RegionResolver`: `supports` a `NumberPad`; `regionAt` the key under the point;
  `rectOf` the key's rectangle; `regions` all twelve (so a capture with marks shows one mark per key).
- `LendingCaptureMask implements CaptureMask`: `masks` an `EntryField` while `isMasked()` (the `PASSWORD` state and the
  supervisor password sub-step, the two places where the frame sets the flag; the field paints asterisks, but the rule
  is the policy's, not the paint's)
  **and the `NumberPad` while the entry field is masked** (D358: the pad feeds the field, so its keys draw grey, carry
  no mark and take no model's click while a secret is expected); nothing else.
- `LendingWidgets.describe`: `{action: <actionId>}` for an `IconKey`, so that the hit-test's first locator is the
  domain one; `{name: <label>}` for a `PromptButton`.
- Registered in `LendingModule.install` beside the eleven SPIs of today (thirteen); `LendingModule.VERSION` becomes
  `0.2.0` with the pack's bump rule in its Javadoc ("change it whenever the module's primitives or SPIs change"; it has
  not changed since Phase 1 although its surface grew, map 7 T8).
- **(Phase 8, built 2026-10-19, EA; P8 §12.2)** `LendingInputVeto implements InputVeto`, the fourteenth SPI type:
  refuses the "Desk tools" window's panel `closeMenu` by name on every route and `ANY` ("closes the desk (the
  application would end)"); the version is written in step R.

### 12.3 The pack (`skillroy-sample-lending-pack`; version `0.6.0`)

`DomainPack` gains `default String screenLegend() { return null; }` (**VC's one default method in `skillroy-scenario`**,
landing in step 1 with the verbs, §2.1): a short plain-text legend of the application's icon-only or custom-painted
controls for a model that looks at the screen (BS12 RESULT "Sessions": the legend brought V2 from 10.3 to 4.5 actions).
The lending pack (VS) returns one paragraph in the lending vocabulary: the keypad's six keys left to right with their
meanings (`QTY` the quantity key, `CANCEL`, `RETURN` the return-mode key, `DUE` computes the deposit due, `DEPOSIT`
records the loan, `SIGN_OFF`), the entry field, the number pad, the prompt dialog's buttons. It is masked like the
vocabulary (S49) and enters the system parts, never a `<target-ui>` block (§6.2). `LendingPack.VERSION` becomes `0.6.0`
(the surface grew); every committed plan's `packVersion` changes with it in the same regeneration as the plan version.
The same pack change takes gap 49: the `LendableItem` query gains the argument `inductionRequired` (as `ItemsTriggering`
has), so that a scenario can ask for an item that needs no induction; the committed scenarios are unchanged by it.

**As built (Phase 7, VS).** `inductionRequired` null means either kind of item, so the committed queries keep their
meaning; a query that names it reads `LENDABLE_ITEM_BY_INDUCTION_SQL` (one more condition) and the verifier checks the
desk's `inductionRequired`, while a query without it reads exactly the old `LENDABLE_ITEM_SQL`, so the committed
bindings' evidence is unchanged; the two-argument constructor stays (D394).

**As built (Phase 7, VC):** `DomainPack.screenLegend()` (default null) and `DelegatingDomainPack` forwarding it, in step 1.

### 12.4 The Phase 7 scenarios (`skillroy-sample-lending-scenarios/scenarios/`)

| Scenario | What it shows | Replay outcome |
|---|---|---|
| `exported/session-vision-loan.yaml` (+ `plan/`, and bindings as the Phase 5 export has them, for the session's query-resolved item slot; compared by §13.2 item 8) | the exit criterion: a V1 vision session on the real desk (seed A) with the **scripted** gateway (`ScriptedAnswers.visionScript()`: answers by position that name marks by their labels, so that pixel differences between targets do not matter), run by `VisionSessionScript`, which opens its session with constructed `InferenceSettings` (gateway `recorded`, `images: true`) beside the `ScriptedGateway` (which declares `IMAGES`) and `SessionOptions.host = {"vision-session"}`, recorded into ordinary steps (`signOn` by the person's step first, then the model's `click {by: {action: DUE}}`, `type {by: {id: entry}, text, enter}` (the hit-test's first built-in candidate, §3.2: `id` is the component's name), `click {by: {action: DEPOSIT}}` and the person's `expect`), exported with the vision comments (no hash, handle or mark in them, D356) and the report's "Vision-derived steps", compared byte for byte with the committed copies in the image on both Javas (§13.2 item 8, which also generates them, on the Java 25 arm64 leg), and replayed with zero model calls | `PASS` |
| `sign-on-by-number-pad.yaml` (`launchProperties: {lending.seed: A}`, the shape of `basic-loan-ui.yaml`: staff 100 is a seed-A number, `SampleCredentials`) | `pointer {by: {id: numberPad}, region: "key:1"}` three times (`1`, `0`, `0`), `press ENTER` on the entry field, the password through the person's `type` with the credential reference (the committed shape of `basic-loan-ui.yaml`), `expect state IDLE`: region-addressed steps, deterministic, no pixel stored; a fixture credential slot and no query slot, so no bindings file (`Plan.needsBindings()` is false) | `PASS` on the seed-A target |
| `loan-table-visual-check.yaml` (`launchProperties: {lending.seed: A}` and the fixture slots, as `basic-loan`) | `basic-loan`'s steps, then `aiExpect: {question: "the loan table shows two lines for the scanned item"}`; no bindings file | `DEGRADED` on the seed-A target (the guard `inference.vision` is `UNAVAILABLE (replay)`; the exit criterion 3), `PASS` when the player runs it live |
| `vision-live-only.yaml` (tag `live`) | `signOn`, then `aiAct: {intent: "lend one plain item to the walk-in borrower and settle the deposit"}`, `expect state IDLE` | `SKIPPED` (`inference: run`; the exit criterion 2); run by `skillroy session --play` live; **no committed bindings file** (it never binds in `replay`) |
| `keypad-image.yaml` | `expectImage: {label: keypad, by: {id: numberPad}}` on the signed-off desk (the number pad is static and identical on both seeds), with baselines for the render keys the image runs, **one per Java line** (the Java 8 and the Java 25 target; the amd64 leg shares them: neither the architecture nor the vendor is keyed, and BS11 found the rasters identical across both), under `bindings/seed-a/keypad-image.images/` and `bindings/seed-b/…` (the one Phase 7 scenario with a bindings file, per seed, for its `images`); a second `expectImage: {label: keypad-frame, mask: [{by: {id: entry}}]}` captures the frame (no `by`; the entry field spans the bottom panel's `NORTH` and lies outside the number pad) with the entry field masked by locator, which exercises the locator form of `mask` | `PASS` on both targets and on the amd64 leg; a third render key (a desktop) is `REBIND_NEEDED(IMAGE)` by design |

Nothing in them names a person or an item. `keypad-image` runs on both seeds; `sign-on-by-number-pad`,
`loan-table-visual-check` and `vision-live-only` are seed-A scenarios (their staff number and fixtures are seed A's),
run from the seed-A target as `basic-loan` and `basic-loan-ui` are today; only `keypad-image` has a bindings file.

**As built (Phase 7, VC):** the four YAML files and their plans are committed in step 1, `keypad-image`'s plan included (it
compiles without VS); only its bindings files, baselines and replay wait behind the test-side marker `Phase7Pending`
(`NEEDS_BASELINES`), and `sign-on-by-number-pad`'s run behind `NEEDS_DESK` (D370). `sign-on-by-number-pad` ends with
`signOff: {}` and `verify: state.name SIGNED_OFF`, so that it leaves the desk signed off as every committed scenario does.
`loan-table-visual-check`'s question reads "the loan table shows one line for the scanned item, with a quantity of 2"
and stands after the scans, before `DUE` (D371): two scans of one item are one line of quantity 2 on the desk, and
after the deposit the loan is posted, so "two lines … " after `basic-loan`'s last step would be false for a live player;
the rest of `basic-loan`'s steps follow it. `vision-live-only` and `loan-table-visual-check` carry `launchProperties:
{lending.seed: A}` with `basic-loan`'s fixtures; none of the three seed-A scenarios has a bindings file.

**As built (Phase 7, VS, step 2b):** `keypad-frame` masks three locators, not one: `{by: {id: entry}}`,
`{by: {id: totalsLine}}` and `{by: {name: Printer, role: scrollpane}}` (D403). The frame is approved on a fresh
desk (`ResolutionChecks`, per seed) but also replays in the glue after earlier scenarios on a used desk, where the totals
line and the printer's slips differ (about 11 700 pixels on both Javas); masking them keeps the frame's check on the
desk's layout and static parts on any signed-off desk. The locator form of `mask` is exercised as before; the `keypad`
baselines are unchanged, and `keypad-frame`'s were approved afresh on both Javas and seeds, so each entry records the
three masks' rectangles (a compare masks the union of an entry's recorded masks and the step's resolved ones, so an
entry approved under one mask would still have passed, but would have recorded less than its plan says).

### 12.5 Regenerated committed artifacts

One regeneration, by the build, of: every plan (`planVersion` 6, `packVersion` 0.6.0) and bindings file (`planHash`)
of both scenario modules (in the web host's module the `plan/*.plan.json` only); the capability goldens of the five
load modes (the three new `OK` Swing ids, the two `UNAVAILABLE` Robot ids); the committed session recordings under
`src/test/resources/inference/`, **because the pack version stands in every session's system text ("The pack: lending
0.6.0"), gap 49's argument changes the vocabulary and so does D342's `$reference` sentence (§9.2)**, not because of the
desk's tree (the recordings are made on
`HeadlessDesk`, which answers `swing.snapshot` with `METHOD_NOT_FOUND`, so the number pad changes none of them;
scenario D's live recordings hold neither and stay valid), regenerated on the scripted stand-in route
(`-Dskillroy.inference.fixtures.update=true`, `SessionExportTest`, no key) **by VS together with its pack bump, after
VM's first commit landed the one-sentence vocabulary change of §9.2** (the sentence is a system part of every recorded
request and so in every recording's fingerprint; VM's first commit is that sentence and its `SessionServiceTest` pin
alone, before VS), so that the recordings are regenerated exactly once in the phase and no integration step is red for
it; the scenario JSON schema committed copy; `exported/session-basic-loan.*` (the pack
version in its head comment; the same VS run).

(Phase 7 security fix, S113, built 2026-10-11, SH) `exported/bindings/seed-a/session-basic-loan.bindings.json`
regenerated on the same key-free route (`-Dskillroy.inference.fixtures.update=true`, `SessionExportTest`): its `item`
slot, which the session's model resolves, is now `approval: {state: REQUIRED, by: null}` (§9.2, D425's note); the
YAML, the plan and the session recordings came out byte-identical.

---

## 13. Tests and acceptance

### 13.1 Without a display (unit and module tests; the normal build; no key)

| # | Module | What is proven |
|---|---|---|
| 1 | `agent-swing` | `Capture` on headless fakes (`CaptureGuardTest`'s style, lightweight `JComponent` fakes; the `WINDOW` and `SCREEN` scopes, `covering` and the modal rules need real windows and run in the display tests `SwingDisplayTest` and `ModalBlockingDisplayTest`, while the headless tests cover the geometry arithmetic through a package-private window-list seam `Windows.Source` the tests replace): the mapping rule of §3.1 for every scope, `scale` and the 4096 cap, the heap bound with a fake free-heap figure (`INVALID_PARAMS` naming both figures) and an `OutOfMemoryError` answered `INTERNAL_ERROR`, the marks geometry and counts (`region`, `row`, reading order, 200) with the pixels untouched (the hash equals an unannotated paint's), the redaction fill and `redacted`, a `CaptureMask` on a fake `JTextField` (grey pixels, no `text` in the snapshot or the hit-test's nodes, `masked: true`, no mark, no `{text, role}` candidate), the carets' rectangles and `focus.ownerLocator`, `hash` over the raster against the one test vector shared with `ImageCompareTest`; `HitTest`'s locator candidates and uniqueness check on a fake tree through `LocatorEngine`, `row`, `fires`; `Pointer`'s event sequence per action (the events a fake window records: masks, counts, popup trigger per `os.name`, the drag's release count 0 and no click, the wheel's rotation and amount), the `cancelled` flag (a task after the deadline dispatches nothing; a dispatched press is disarmed by a drag to (-1, -1) and released there, `balanced: true`, and the fake button's `ActionListener` counts no event), the per-event modal re-read (`MODAL_OPENED`, `dispatched n of m`), `expectTarget`; `RobotInput` behind the `RobotFactory` seam with a fake that delivers or drops (the delivery probe ordered before the pre-check, `NOT_DELIVERED`, `received elsewhere`, `PRESS` and `RELEASE` refused, a timeout between press and release still releases before the pointer is restored, `requireFocus` against a fake focus state, any covering window refuses, a `ROBOT` capture's masks read before and after the grab, `unstable`, `glass` with the outside pixels blanked); `Rendering`'s key over fixed inputs (the keyed set only: a changed vendor, full version or OS leaves it); `SwingModuleHeadlessTest`: the primitive list (fourteen), every new id's install state with and without the option `robot`, the Robot ids never probed without it; `AwtStartTest.DISPLAY_CAPS` with the three new ids; two pins of `SwingDisplayTest` change with A61: `:422` (`mode: ROBOT` becomes `CAPABILITY_UNAVAILABLE(swing.capture.robot)`) and `:408-409` (a window capture is the root pane's size, 640 x 480 only where the insets are 0) |
| 2 | `agent-api` | the three SPIs' default methods; `WebPointerEvent`'s builder and bounds; `WebBrowser.dispatchPointer`'s default refusal; `WebSpiShapeTest` (no vendor type; the enum's order) |
| 3 | `agent-web` | `web.hitTest` and `web.pointer` through `Harness`, `ScriptedAdapter`, `ScriptedBrowser` with a `Responder` for `hit`: the `COMPONENT` conversion and its refusal under an override, the modal check on a fake dialog (a display test where a real dialog is needed), `MOVE`/`PRESS`/`RELEASE` for `CLICK`, read-only mode refusing the pointer and serving the hit-test; `WebCapabilitiesTest`: the two ids derived; the fake JCEF's `Input.dispatchMouseEvent` recorded by `FakeDocument` (`JcefRawChannelFakeTest`, `JcefAdapterFakeTest`); the JxBrowser fake with and without the `INPUT` roles; the `SurfaceSource` from the index; `WebLibTest`: `hit` in `FUNCTIONS`, version 2, the install-into-an-older-slot rule's text |
| 4 | `protocol` | nothing new on the wire (`ProtocolConstantsTest` pins 0.2); `WebLib`'s hash changes once |
| 5 | `skillroy-scenario` | the four verbs compile (`BaseStepsTest`, `PlanCompilerTest`): `aiAct` → `inference: run`, `aiExpect` → `VisualCheck` with the fixed guard (a `capability` argument refused), `pointer` → `swing.pointer`'s component form with `SETTLE`/`EVENT`, `row`, `via: ROBOT` → `requires`, `PRESS` with `via: ROBOT` a compile error, `guard` → `Plan.Call.guard` and the `images` key, `expectImage` → one capture call and an `ImageCheck`, `region` and `clip` rules; plan version 6 reader and writer (`Phase7CompileTest`: a version-5 plan with `run`, `images` or `guard` refused; version 6 accepted; `inferenceOf(slots, steps)`; `needsBindings()` true for images alone); `Status.SKIPPED` in `PlanExecutorTest` (a `run` plan in `replay`: no agent call, `run.jsonl`'s four records (`run-start`, `skipped`, `ledger {count: 0}`, `run-end`), `RunResult`), refused in `live`; the robot plan refused `ERROR / ENVIRONMENT` before `checkRequires` without `RunOptions.robot`; `CapabilityContributor` merge (`FakeContributor`): `replay` forces `UNAVAILABLE("replay")`, an agent `inference.` id refused, two contributors answering one id refused naming both, `capabilities.json` merged once, `agentHash` the agent's hash, `SessionOptions.host` seen by the merge in `open` and `ExecutorSession.capabilities()` answering it, `BaseFacts.caps` reading the merged supplier, a `FakeContributor` answering `inference.vision OK` in `live` with the `VisualCheck` still skipped; `ImageCompareTest`: equal, one pixel, masks, the carets union, the focus mask, `maxDiffPixels`, the diff image, the raster hash vector; `ImageSteps` in `PlanExecutorTest` with `FakeAgentConnection`'s canned capture results: an `expectImage` held, failed (three files), missing (`REBIND_NEEDED(IMAGE, "no baseline <label> for rendering <key>")` in `replay`, recorded and skipped in `live`), an anchored `pointer` with a matching anchor running the pointer call, a mismatching one `REBIND_NEEDED(IMAGE)` with the three files, no entry for the key likewise in every mode, `approve` included; `BindingsFileTest`: version 2 read and write, version 1 kept without images, the `images` round trip with `carets`, `focus` (`owner`, `ownerRect`, `active`) and the nested `rendering`, the derived path (a `file` that does not match, or resolves through a link or outside the directory, refused); `BinderTest`: `needsBindings` with images, `reviewImage` asked at the step (the default delegating to `review`: `APPROVE_ALL` approves, `REJECT_ALL` leaves `REBIND_NEEDED(IMAGE)`) and the file rewritten after the steps, the label check in `replay` only, `finish` and the re-resolve carrying `images` forward; `ExecutorSessionTest`: `read` unjournaled, throwing the agent's error as `AgentRpcException`, never masking an image's `base64`, refusing `data.query`, a script and a mutating primitive; `SessionOptions.robot` refusing `via: ROBOT` and `mode: ROBOT`; `mode: ROBOT` refused for `MODEL` and `CLIENT` with the flag set; for `MODEL` and `CLIENT` a verb and a raw call naming `$assistant.password` refused with nothing sent, a `type` refused while a secret is in flight and accepted after the person's `signOn` consumed it, a model's `signOn {staff: $assistant}` refused whatever the flag; the four-argument `execute` writing the provenance on the step and its read-back equality (`StepJournalTest`); `JournalExportTest`: the comment without hash, handle or mark, the report's two sections, V3 steps left out, the `images` section in the export's bindings file and `images()`, an absent anchor file an `ExportException` naming the step; `JunitXmlReporterTest`: `SKIPPED` whatever `degradedAs`; `ScenarioSchemaTest`: the verbs |
| 6 | `skillroy-junit`, `skillroy-cli` (VC's half) | `ScenarioTestsTest`: `SKIPPED` aborts, the `skillroy.robot` warning line; `RunCommand`'s `skipped` count and exit 0, `--allow-robot`, the image approval prompt and `--yes` in `approve`; `caps` with `source` on `CliTestPack`; `WebCommandsTest`'s stale "reserved" string updated (the `session` and `mcp` options are item 8's) |
| 7 | `skillroy-inference` | `ClaudeGatewayTest` with `FakeAnthropic`: a `ProviderTool` becomes the toolset entry with `configs`, another provider's is `INVALID_REQUEST`, `toolset_name` sent on tool use and tool result and read back from a `tool_use` block, a parts-list result as `contentOfBlocks`, an image result refused while images are off, an error result with an image refused at construction, `CacheHint.AUTO`'s top-level `cache_control`; `ModelRequestTest`: fingerprints unchanged for a request without the new fields, changed by a provider tool; `RecordedGatewayTest`: `IMAGES` declared, a recording with an image part and a toolset request round-tripped; `ScriptedGatewayTest`: `IMAGES` and `PROVIDER_TOOLS` declared; `PriceTableTest`: the Haiku 5.5 row and note; `BudgetAndLedgerTest`: purpose `vision`; `SessionServiceTest` with a `FakeSessionTools`: tools listed, `atTurnStart` parts in the turn's first message, `act` consulted, `afterBatch` appended, provider tools and instructions in the request and masked, `endTurn` `QUESTION` and `LIMIT` ending the turn, `vision.maxActions` and `vision.maxTurns` enforced and the tools closed, `visionCall` one ledger entry with purpose `vision` on the session meter; **the raw primitive branch**: `swing_pointer {via: ROBOT}` with and without `robotAllowed`, `{force: true}` on `swing_click`, `swing_type`, `swing_key` and `swing_pointer`, `swing_capture {redact: false}` and `{mode: ROBOT}`, each a tool error with nothing reaching the fake agent and nothing journaled; **the facade**: a `FakeSessionTools` trying `read("data.query", …)`, an `execute` with `confirm: true`, a step with `via: ROBOT` under `robotAllowed` (passes) and without (refused), a read with `mode: ROBOT` (refused); a read-only policy with the tools' lists; `InferenceContributorTest`: `inference.vision` no longer answered; `SessionServiceTest`: a session without an applying tool builds the same system text as before but for the vocabulary sentence of §9.2 (so the recordings are regenerated once, §12.5); `estimate` with an image part |
| 8 | `skillroy-vision` (and VM's CLI and MCP options) | the loop on `FakeVisionAgent` (canned captures from the module's PNG fixtures with their geometry, canned hit-tests) and `ScriptedGateway`: a V1 turn (a mark grounded by a hit-test at its centre: a button → `click` by locator, a table row → `pointer {row}`, a region mark → `pointer {region}`; the provenance; the export's comment), a V2 turn through the custom tools and through the toolset (the member mapping of §6.3, `KeyNames`, `zoom` across two windows mapped to the topmost one), the stale-capture rule (a `window.opened` event between capture and act: a fresh capture and the same-component check), the refusals (a masked focus owner for `type` and `key`, a masked target for `click` and `pointer`, a `type` naming `$assistant.password`, a `type` while a secret is in flight, a `type` holding a known secret value, a `signOn {staff: $assistant}` from the model), Haiku refused for V2, the V3 rules (`via: ROBOT` set by the facade with `requireFocus: true`, a canned `focus.window` null giving no Robot call and `endTurn: QUESTION`, `left_mouse_down` answered as text, no mode on `capture`), the marks drawn on the controller's copy (the captures file equals the fixture), a redacted `WEB` surface staying grey after the composite, the capture always after the batch and skipped after the model's own `screenshot`, the toolset's skipped calls after a failure, the limits (30, 20) through the service; `VisualQuestion`'s schema, `UNSURE`, the `<model-question>` block; `VisionCapabilities`' states (gated on `swing.capture.screen`); `ScenarioPlayer` on the toy pack with a scripted model (`aiAct` done, `ask_person` in a non-interactive run, `aiExpect` YES/NO/UNSURE through `visionCall`, a step's `tier: V3` without the flag refused, `signOn` before the first `aiAct`, an `expectImage` through the session's route); `Captures`: one file per hash, the raster as captured; the CLI's `SessionCommand` (`--vision*`, `--allow-robot`, `v3` without it a usage error, `host` set, `--play` on `CliTestPack` with a scripted gateway) and `McpCommand` (`--vision`, `--allow-robot`) parsing; `ToolCalls` (MCP): raw `swing_capture {redact: false}` and `{mode: ROBOT}`, `swing_pointer {via: ROBOT}` with `--allow-robot` set, `swing_type` with a `$assistant.password` argument, each refused with nothing sent; `skillroy_scenario_run` with a client scenario whose `type` names `$staff.password` refused by `PlanPolicy` with nothing run, and one whose `signOn {staff: $staff}` passes (a pack verb's own join) |
| 9 | the sample | `LendingUiDisplayTest` is a display test; headless: `LendingModuleTest`'s registration list (thirteen SPIs), `LendingRegionsTest` on an unshown `NumberPad` (regions need no display), `LendingCaptureMaskTest` (the entry field, and the number pad while the entry field is masked), `LendingPackTest` (`screenLegend`, version 0.6.0), `PlansUpToDateTest`, `BindingsUpToDateTest` (its expected list gains `keypad-image`'s file per seed, the one Phase 7 scenario with bindings; `sign-on-by-number-pad`, `loan-table-visual-check` and `vision-live-only` have none, and the vision export's files are compared by §13.2 item 8 as the Phase 5 export's are; the `.images/` directories are walked separately and every PNG must be named by an entry of its bindings file and match that entry's `sha256`), `ScenarioSchemaCommittedTest`, `CapabilityGoldenTest`'s fixtures, `SecretScan` over every new file (the baselines' PNGs included: bytes, so the scan checks their names and that no `credentials.yaml` value appears in any text file) |

**As built (Phase 7, VW, 2026-10-10), items 2 (the web part), 3 and 4:** item 2 is `WebPointerEventTest`,
`WebBrowserDefaultsTest` (the default refusal) and `WebSpiShapeTest` (members, the frozen enum orders, the defaults);
item 3 is `PointingTest` (the two primitives through `Harness` over the fake page's layout of the kit page and a
scripted descent; the conversion and its refusals; the modal check on a fake dialog; the plan; the balancing release;
read-only mode; the `SurfaceSource`), `ComponentStateDisplayTest` (the module's modal check and component read against
a real `JDialog`; a display test), `WebCapabilitiesTest` (the two ids derived), `JcefAdapterFakeTest` and
`JcefRawChannelFakeTest` (`Input.dispatchMouseEvent` recorded by `FakeDocument`, on the shared client and on the raw
channel), `JxBrowserAdapterFakeTest` (with the `INPUT` roles, and `fake-jxbrowser-noinput.json` without them), the kit's
items 15-18 against the four fakes, `WebModuleInstallTest` (fifteen primitives, the install states, no AWT class
requested with the `SurfaceSource` registered); item 4 is `WebLibTest` (`hit` in `FUNCTIONS`, version 2, the install
rule's text, the `outside` message) and `WebInputTest` (the shared parameters, plan, protocol parameters, bounds,
child-frame rule and shapes); the CDP side is `CdpWebPathServesTest` and `CdpWebPathCallTest` (both primitives, the
descent through the frame tree, the hand-over of a blocked browser, the balancing release, read-only mode).

**Fix wave (Phase 7 review, FA, 2026-10-10; L3-F7), item 1:** as built, the Robot items of the row that need real
windows (the delivery probe and its order, `NOT_DELIVERED`, `received elsewhere`, `requireFocus`, the covering rule, a
`ROBOT` capture's masks before and after the grab, `unstable`, `glass`, and since the fix wave the probe's own refusals,
the modal skip, the drag's end point, the glass capture's heap bound and the masks of a dialog over the frame) run in the
display test `RobotPathDisplayTest`, with a fake OS port behind `RobotFactory` that delivers like the OS (and drops into a
modal-blocked window as AWT does), in the image and not headless; `RobotPathTest` keeps, headless, the paired press, the
release in the `finally` (a press that throws included), `PRESS`/`RELEASE` refused, the capability probe of a headless
JVM and the outside blanking (checked pixel for pixel against the per-pixel rule). `expectTarget` and the covering rule's
ranking have a headless case in `PointerTest` on a hand-built hit; their real-window cases are `VisionDisplayTest`'s. A
capture inside a masked container, an ancestor's mask regions and a mask that names the window are `CaptureTest` cases
(the window through the window-list seam), a mask that names a real `JDialog` a `VisionDisplayTest` case.

**Phase 7 security fix (built 2026-10-11, SG), item 1.** Headless: `WindowsTest` (new; S104: the hidden predicate
through the window-list seam and the state seam `Windows.State`, whose stand-ins say which window is an iconified frame
and who owns whom: the iconified frame and the windows it owns transitively left out of the stacking and the `SCREEN`
composite), `CaptureTest` (S129: a browser in a hidden card is no surface; S108: `covered` holds the dialog above in
the composite and a popup-layer component above in the root, and `rect` is the visible part, none for a browser scrolled
wholly out of a viewport; S122: a regions-only mask's label masked and the table's covered cells withheld from the
marks and the value; S123: two masks by union; S125: the encoding bound with a noise raster through the heap seam;
S146: the focus owner's locator without `{text}`), `HitTestLocatorsTest` (S106: a masked label, a button inside a
masked container and a directly masked label have no name, candidate, first unique locator, description or `name`
match from their text; an accessible name set apart from the text survives), `RobotPathTest` (S121: a probe whose port
cannot be had leaves no toolkit listener; S124: `modifiers` refused with nothing moved; S126: the grab's bytes per UI
scale, and a glass capture at scale 3 refused by the bound through the screens seam `RobotPath.Screens`). On the
display (the image): `RobotPathDisplayTest` (S104: a real frame the toolkit reports `ICONIFIED`, which the WM-less
display keeps as the frame's state although it cannot unmap it (the case also ran under openbox, which unmaps it, §13.4),
and a dialog it owns refused `NOT_FOUND iconified` with
nothing moved and left out of the composite; the same through the state seam, which also shows it is no covering
window; S105: a frame at x = 1024 - 124, 300 wide: a point at window x = 250 and a drag's end point there refused
`NOT_FOUND off-screen` with nothing moved and the capability `OK`; a press `received elsewhere` probed again before the
next press), `VisionDisplayTest` (S146: a focused nameless text field's `focus.ownerLocator` holds no text; S125: a
512 x 512 noise component refused by the encoding bound with an 8 MB headroom, captured with the JVM's own).

**As built (Phase 7, VC):** item 5 is `Phase7CompileTest`, `Phase7ExecutorTest`, `Phase7SessionTest`, `Phase7ExportTest`,
`Phase7BindingsTest`, `ImageCompareTest` (`…scenario.image`; the shared vector, the same six pixels and the same hash
literal in `ImageCompareTest` and the agent's `CaptureTest`: the 3 x 2 raster `0xFF000000 0xFFFFFFFF 0xFF808080 /
0xFF102030 0xFFFF0000 0xFF00FF00` hashes to `sha256:6be118c38e93cd2b790c98897d10e69afa4ea5b1248c59203b1c2633a884c4ba`;
fix wave (Phase 7 review, FA, 2026-10-10; L3-F1): the two tests had held two different vectors, this note the
controller's `… / 0xFFFF0000 0xFF00FF00 0xFF0000FF` → `sha256:74cb35da…6eec`, so that neither proved the two layouts
agree), the extended `JunitXmlReporterTest`,
`ScenarioSchemaTest`, `CapabilityMatrixTest` and `InferenceContributorTest`; the contributors of the executor and
session tests are found through a class loader built in the test (a `META-INF/services` file in a temporary directory),
so that no test-scope registration changes every other run. Item 6 is `ScenarioTestsTest`'s two tests and the CLI's
`Phase7CommandsTest` (`WebCommandsTest`'s reason updated).

### 13.2 End to end (the Linux image; `ci/run-in-linux.sh … -Dskillroy.headless=false`; Java 8 and Java 25 targets; no key)

Where each item lives: items 1-6 in a new `VisionSwingChecks` (VS, every load mode; registered in `LendingEndToEndIT`
by VS), item 7's `keypad-image` in `ResolutionChecks` (VC; the per-seed class, where the earlier scenarios replay) and its three seed-A scenarios in `LoadModeChecks` (VC; the seed-A target, where `basic-loan` and `basic-loan-ui` run today), item 8 in
`InferenceChecks` (VM; registered in `InferenceEndToEndIT`), item 9 in `LoadModeChecks` (VC), item 10 in the web host's
checks (VW), item 11 in the host-safety tests (VS).

1. **Capture.** `swing.capture {}` of the desk's frame: `width` and `height` the root pane's, no imitation decorations
   (the top 24 rows of the image hold the prompt line's background, not a title bar; compared with a `swing.capture
   {target: <root pane ref>}` of today's form: identical rasters), `space: WINDOW`, `origin {0, 0}` (WM-less), `seq`,
   `hash` equal across two captures of the unfocused desk (BS11); `scope: COMPONENT` of the loan table with its
   `origin` equal to the node's `bounds`; `scope: SCREEN` with the prompt dialog open: two `windows` entries, the
   dialog's root pane on top, `stackingAssumed: true`; `annotateRefs: true`: `marks` holds one entry per keypad key
   with its `NameEnricher` name, per number-pad region (`region`), per table row (`row`) and the header, and the pixels
   are untouched (`hash` equal to an unannotated capture's, both taken on the unfocused desk, since the caret's blink
   phase would differ otherwise, BS11); in the `PASSWORD` state the entry field **and the number
   pad** are in `redacted` (`capture-mask`), their pixels grey, the entry field's text absent from the snapshot and the
   hit-test's node, no mark on either; with `redact: false` (a person's call) the asterisks; `carets` lists the focused
   entry field's caret and `focus.ownerLocator` is `{id: entry}`.
2. **Hit-test.** The centre of every keypad key in `WINDOW` and in `{captureId}` space: `target` the key, `locators`
   starting with `{action: <id>}`, `relative {0.5, 0.5}`, `fires: true`; a table row: `target` the table, `row` the
   model index; the number pad's `key:7`: `region {id: "key:7"}`; the prompt dialog open: the frame's `DUE` key reports
   `blockedBy` the dialog and `covering` with `ranked: true` for the dialog where it overlaps; `target: <ref>` form for
   the entry field; `masked: true` for it in the `PASSWORD` state.
3. **Routed pointer.** `swing.pointer {by: {action: DUE}, action: CLICK}` from the signed-on desk fires the key
   (`received` the key, `fired: true`, `dispatched 4 of 4`, the desk's `input.requested` event follows: the event, not
   `fired`, is the evidence, since the desk's `IconKey` debounces, §3.3; BS10 verified the mechanism on the toy, no
   spike ran a routed press on the desk); a `pointer` on `key:1`, `key:0`, `key:0` of the number pad (`by: {id:
   numberPad}, region`) types `100` into the entry field; `MODAL_OPEN` for a pointer into the frame while the prompt
   dialog is open, `force: true` delivers (BS10 RESULT case "modal"); the `MODAL_OPENED` case runs in
   `ModalBlockingDisplayTest` on a fixture whose `mousePressed` shows a modal dialog (the desk has no such press: its keys
   only arm on the press and prompts open through the engine's `invokeLater` after the debounced action): `fired: false,
   reason: MODAL_OPENED, balanced: true`, the press disarmed and released into the blocked window, no `ActionEvent`; `expectTarget` with
   another ref is `INPUT_REJECTED`; `DRAG` on the loan table selects rows; `WHEEL` on the slip panel scrolls it.
4. **`swing.type` without a target** types into the focus owner (the entry field) in `SET` mode; `DISPATCH` with
   `enter` documents gap 60's behaviour in the check's name.
5. **`swing.rendering`** answers every field, `renderKey` stable across two calls, different between the Java 8 and
   the Java 25 leg (the specification version; BS11: the JDK line changes pixels) and equal between the arm64 and the
   amd64 leg of one Java (neither the architecture nor the vendor is keyed; BS11 found their rasters identical).
6. **Robot** (`RobotChecks`, run once per Java with the agent option `robot=true` on a target started for it, a
   `LendingTarget.Mode` beside the hooks leg, WM-less; every call passes `requireFocus: false` explicitly, since a WM-less
   frame may hold no focus, BS9 K7): `swing.pointer.robot` `OK("robot")`; `swing.pointer {by: {action: DUE}, via:
   ROBOT}` fires the key with `received` set (BS9 RESULT, the table's row "Sample: keypad keys": 3 of 3);
   `requireFocus: true` (the default) is `INPUT_REJECTED("not focused")` while the frame holds no focus and passes after
   a routed click gave it the focus; a point outside every window `NOT_FOUND`; `PRESS` refused; a person's
   `swing.capture {mode: ROBOT}` of the frame equals the root-pane `PAINT` raster inside the root pane (BS9: 0 differing
   pixels, the caret aside), carries `glass: true` and holds grey outside the frame; the controller's opt-in refusal
   comes first: a `pointer {via: ROBOT}` plan without `RunOptions.robot` is `ERROR / ENVIRONMENT` before the agent is
   asked, and with the flag on a target without the option `PRECONDITION_FAILED` naming `swing.pointer.robot`; without
   the option the ids are `UNAVAILABLE("robot: not enabled…")` in every other leg (the goldens).
7. **Scenarios** (both Javas): on the seed-A target (`LoadModeChecks`): `sign-on-by-number-pad` `PASS`;
   `loan-table-visual-check` `DEGRADED` with the skipped line naming `inference.vision` (its state reads `missing` until
   VM's contributor is on the class path and `UNAVAILABLE (replay)` after; the final matrix asserts the latter);
   `vision-live-only` `SKIPPED` with no agent call recorded; none of the three has a bindings file; on both seeds
   (`ResolutionChecks`): `keypad-image` `PASS` on both Javas and on the amd64 leg with the committed baselines (one key
   per Java line, per seed), and a deliberately changed baseline
   `FAIL / ASSERTION` with the three files under `failure/` (the missing-key case is §13.1 item 5's); every earlier
   scenario `PASS` unchanged with the regenerated plans (A to E, `basic-loan-ui`, scenario C's replay, the branch
   edition).
8. **The vision session** (`InferenceChecks`, seed A, both Javas): `VisionSessionScript` with the scripted gateway on
   the real desk (§12.4's settings and host feature): the person's `signOn` step, then the V1 turn records the three
   model steps; the export equals the committed `exported/session-vision-loan.*` byte for byte (the YAML, the plan and,
   as the Phase 5 export has it, the bindings file apart from the channel; nothing in them is target-dependent, D356), the journal read back exports the same,
   the replay of the committed plan passes with zero model calls and an empty ledger (the exit criterion 1 in CI); this
   item generates the committed copies under `-Dskillroy.inference.fixtures.update=true` on the Java 25 arm64 leg (VM's
   report names the leg); the player runs `vision-live-only.yaml` with the scripted model to `PASS` and
   `loan-table-visual-check.yaml`'s `aiExpect` with a scripted `YES`.
9. **Capabilities** (`LoadModeChecks`). The goldens of the five load modes equal the matrices after `target.ready`
   (the new ids; the normalised comparison as today); the run's `capabilities` record has `agentHash` equal to
   `CapabilityMatrix.hash()` of the agent's matrix read after step 0b and `contributed` the ids the class path contributes (`inference.gateway` and `inference.sql` from VC's step 1,
   `inference.vision` once VM's module is on the class path) with `UNAVAILABLE (replay)` in `capabilities.json`.
10. **The web host** (profile `jcef`, both rendering modes on Java 25, off screen on Java 8): kit items 15-18;
    `WebHostChecks` item 1 (`web.hittest`, `web.pointer` `OK(jcef)`) and the new item: `web.hitTest` at the button's
    centre on both paths answers the button with a unique selector; `web.pointer {action: CLICK}` on both paths
    increments the page's pointer counter with `isTrusted: true`; `swing.capture` of the host's window lists the browser
    as a `WEB` surface with its ref and `redacted: false`; `swing.hitTest` at a point in the browser answers `surface:
    WEB` with `surfaceLocal`, and `web.hitTest {space: COMPONENT}` with that point answers the element under it.
    **As built (Phase 7, VW, 2026-10-10):** `WebHostChecks.item10HitTestAndPointerOnBothPathsAnswerTheSameShapes` and
    `item10TheSwingCaptureListsTheBrowserAndASwingPointReachesThePage` (the second skips only while `swing.hittest` is
    absent from the matrix; on the head with VS's Swing side it ran and passed in all eight Java 25 launches, both JCEF
    modes, and the four Java 8 off-screen launches); the kit's items run in `JcefKitWindowedDisplayTest` and
    `JcefKitOffScreenDisplayTest`. **Fix wave (Phase 7 review, FB, 2026-10-10):** the second check no longer skips: it
    asserts `swing.hittest` `OK` first, so that a web host target that lost the Swing side's ids fails item 10's Swing
    half instead of skipping it (L3-S2).
    **(Phase 7 follow-up, gap 95, built 2026-10-11.)** Five more `WebHostChecks` items run in every launch of
    `WebHostEndToEndIT` (both renderings on a Java 25 target, off screen on a Java 8 target), after item 8, so that the
    security fixes S108, S109, S110, S111 and S133, whose tests ran against fakes, also run against real JCEF. The host
    gains the button `notice` (P4 §9.1) and its agent module an optional `CaptureMask` (P4 §9.2) for them. The
    controller's halves are the vision module's own classes (`Grounding.capture`, `Grounding.modelImage`,
    `Grounding.ground`, `Recorder.pointer`) over a facade (`SessionActions`) that passes the check's reads to the agent,
    and `CallPolicy.arguments`. Within an item every route is asserted in one `assertAll`, so that a run with several
    protections off reports each; nothing sleeps (events, `window.opened` and `window.closed` among them, and a bounded
    poll in (b) and (e)).
    (a) **A modal dialog over the page** (S108 on one desktop, S109, the modal check of §4.3 and D404):
    `swing.click {by: {id: notice}}` opens the host's modal `JOptionPane` over the page (`window.opened {modal: true}`
    awaited). While it shows: `web.browsers` gives the front entry `blockedBy.dialog`, the dialog's ref; the agent's
    `web.pointer {action: CLICK}` at the Borrow button is `MODAL_OPEN` with `data {dialog, browser}`, and with a port
    the CDP path hands the same call to the agent (`UNSUPPORTED`); the page's pointer counter counts nothing; the vision
    capture (`SCREEN`) has the dialog's window on top and the front surface's `covered` is exactly the dialog's
    rectangle within the surface's `rect`; the model image keeps the capture's own pixels (the dialog) in every pixel of
    `covered` and shows the page's `web.bitmap` in the rest of `rect` (at least 99 % of those pixels equal, and the
    dialog's area differs from the page's pixels there, so the comparison says something); `Grounding.ground` at the
    Borrow button's pixel answers `surface: WEB` with `blockedBy` the dialog, and `Recorder.pointer` (a left click)
    refuses it with `Recorder.PAGE_BLOCKED` before anything is read or sent. Then `swing.dismiss` answers the dialog
    (`window.closed` awaited), `blockedBy` is gone and the agent's pointer click counts four trusted events again (the
    control). (b) **No late click on the CDP path** (S133, the sceptic's probe on a real page; launches with a port):
    through `web.evaluate` the front page replaces its own `document.elementFromPoint`, once, by one that is busy for
    4 s, so that the page stalls the pointer's own hit read; the CDP path's `web.pointer {browser: <front ref>, action:
    CLICK}` (the selection by ref, so that the path asks the agent nothing before the hit) with a 1 s budget answers
    `TIMEOUT`; once the path's Playwright thread has returned (a bounded poll: a call while it is busy is `UNSUPPORTED`)
    the page's counter shows no event, and the same call with the default budget then clicks (the control). (c) **A
    middle click is a person's only** (S110 on a real page): `CallPolicy.arguments` refuses `web.pointer` with `button:
    MIDDLE` (a model's or a client's call, every route; a left click passes, the control), and `Recorder.pointer`
    refuses a middle click on the page (`Recorder.CLIPBOARD`) before anything is read or sent, with the counter
    unchanged. (d) **A masked browser on every route** (S111, S108's grey surface, D358 in the recorder; the last
    item but (e)): the check disables the
    agent and attaches a fresh one with the agent option `webhost.mask=front` (item 8's mechanism), so that the
    sample's module registers a `CaptureMask` naming the front card; then `web.browsers` lists the front browser with
    `masked: true`, `url: ""`, no `title`, its frames' `name` and `url` empty (their keys kept) and the back browser
    unmasked; `web.bitmap`, `web.dom.text`, `web.ariaSnapshot` and `web.hitTest` of the front browser are
    `INVALID_PARAMS {param: redact, reason: masked, browser}` on the agent's path, and with a port the CDP path hands
    them to the agent (`UNSUPPORTED`); a person's `redact: false` reads the page on both paths (the control), and
    `CallPolicy.arguments` refuses that argument on the four for a model or a client; the vision capture's front
    surface is `redacted: true` and its `rect` is solid grey (RGB 128, 128, 128) in the model image (nothing
    composited); `Grounding.ground` at the Borrow button's pixel answers `masked: true` and `Recorder.pointer` refuses
    it (`Recorder.MASKED`); a session's text snapshot (`ExecutorSession.snapshotText`) holds the line `[web <front ref>
    masked]` and nothing of the front page (its title, its URL's marker, its text), where a snapshot taken before the
    re-attach showed the page (the control). (e) **What a person's middle click does to a real page** (S110's reason,
    which SI stated from Blink's behaviour; recorded in `timings.txt`, not asserted; the launch's very last act, because
    it may leave the page unresponsive): a person's `web.pointer {action: CLICK, button: MIDDLE}` at the Borrow button
    through the agent with a 5 s budget, what it answered, how long the page then took to answer an evaluation again
    (re-asked, at most 20 s), which pointer events the page counted, and whether the host's event dispatch thread
    (`swing.snapshot`) and the back page (`web.evaluate`) still answer within 2 s. **As run (2026-10-11, the arm64
    image):** green in all eight launches on a Java 25 target and the four off-screen launches on a Java 8 target ((b)
    in the launches with a port); `covered` was the notice's 358 x 90 part of the page, all its 32,220 pixels kept and
    the page shown around it; the late call answered `TIMEOUT` after 1.0 s and the path's thread returned after about
    4.0 s with no event sent. Each protection neutralised in place made its check fail (the late click's four events
    arrived after the caller's `TIMEOUT` under the pre-S133 order). (e) found that a person's synthetic middle click
    leaves an **off-screen** JCEF page unresponsive (the call `TIMEOUT` at 5 s, no evaluation answered for 20 s, the event
    dispatch thread and the back page answering, on both target Javas), while a windowed page takes it as `pointerdown`,
    `mousedown` and `mouseup`, trusted, and no `click`. `../TODO.md`, gap 95. That was gap 104, fixed agent-side on 2026-10-12
(§4.3: the middle `mouseup`'s default, the X11 PRIMARY paste, cancelled); (e) still records rather than asserts.
11. **Host safety** (`HostSafetyDisplayTest`, `HostSafetyIT`): a blocked EDT makes `swing.capture`, `swing.hitTest`
    and `swing.pointer` time out cleanly and nothing runs late, **including an EDT blocked from inside the
    `MOUSE_PRESSED` handler of the sequence's own press**: the call returns `fired: false, EDT_BUSY, dispatched 1 of 4, balanced: true`,
    the disarming drag and the release are the only events delivered afterwards, the fixture's `ActionListener` (and on
    the desk the key's application event) counts nothing, and no click arrives; no listener the pointer adds
    survives the call; `HostSafetyIT` gains a case with the full home (`modules/` on), an AWT application fixture and
    the display (so it runs in the image): the `-verbose:class` scan shows no `java.awt.Robot` loaded without the option
    `robot` (the scan sees loading, not initialisation, which is why `RobotInput` is loaded lazily behind `RobotFactory`
    and nothing else in the module names `Robot` in a signature), and, with it, loaded only after `checkAwt` confirmed AWT (the capability probe constructs the Robot, §3.4), never at
    install and never in a headless JVM.

**Fix wave (Phase 7 review, FA, 2026-10-10), items 1, 3 and 6.** Item 1 (`VisionSwingChecks`, L3-F4, L3-F1): the
check runs on the focused desk, so its two hash equalities (two captures; an annotated against a plain capture, which
the check had not compared at all) are asserted on the rasters with the reported carets blanked, not on `hash`; it also
asserts that the agent's `hash` equals `ImageCompare.rasterHash` of the decoded image, on both target Javas, the
agreement on which the export's anchor check rests. Item 3 (L3-F5): the wheel check asserts first that the slip's text is
taller than its view (the six loans guarantee it), then that the wheel moved it; it no longer accepts an unmoved slip
that happened to fit. Item 6 (`RobotChecks`, L3-F2, L3-S3): the `requireFocus` refusal is its own test
(`requireFocusRefusesAnUnfocusedFrame`, `@Order(30)`), which begins with an assumption on the focus observed after the
prompt, so that a leg on which the frame keeps the focus reports it skipped instead of passing without the refusal (the
deterministic refusal is `RobotPathDisplayTest.requireFocusRefusesAJvmWithoutAFocusedWindow`); the pass is
`requireFocusPassesOnceTheFrameHasTheFocus` (`@Order(31)`). The glass capture's grey outside the frame lives in
`RobotPathDisplayTest.aGlassCaptureIsMaskedAndBlankedOutsideTheWindows`: a glass capture of the WM-less frame lies wholly
inside it, so the end-to-end check asserts the raster equality and `glass` only.

**Phase 7 security fix (built 2026-10-11, SG), items 6, 10 and 11.** Item 6: the WM-less off-screen case (a frame
across the 1024-pixel display's right edge, `NOT_FOUND off-screen` for the point and a drag's end point, nothing moved)
and the iconified case (the frame state the WM-less toolkit keeps) are `RobotPathDisplayTest` cases on the module's
fake OS port (§13.1 item 1), not `RobotChecks` steps: the desk's frame is not moved off the screen or iconified in the
end-to-end target. Item 10: the web host's back browser is a `CardLayout` card of its own, so `WebHostChecks` item 10
asserts that it is absent from `surfaces` (it was by its own `isVisible()` before the fix too); a browser wrapped in a
hidden card's panel, the case S129 is about, is `CaptureTest`'s, since wrapping the host's back browser would change
the web host application (another package's files and the BS8 sentinels). Item 11: `HostSafetyIT` and
`HostSafetyDisplayTest` run unchanged.

**As built (Phase 7, VC):** item 7's seed-A part and item 9 are `LoadModeChecks.thePhase7SeedAScenariosRunAsDesigned`
(`@Order(155)`) and `theCapabilitiesRecordCarriesTheAgentsHashAndTheContributedIds` (`@Order(156)`), in every load mode;
the expected reason and contributed ids followed whether the vision module's contributor was on the class path
(`LoadModeChecks.visionContributor()`), so that they tightened by themselves when VM landed. **Fix wave (Phase 7 review,
FB, 2026-10-10):** VM has landed and `skillroy-vision` is a test dependency of the module, so both checks assert the
tightened form unconditionally (`inference.vision is UNAVAILABLE (replay)`; `contributed` is `inference.gateway`,
`inference.sql`, `inference.vision`) and `visionContributor()` is gone: a dropped dependency or a renamed contributor
now fails items 7 and 9 instead of weakening them (L3-F6). The glue over the committed
directory (`junitGlueRunsTheCommittedScenarios`) runs the two new runnable scenarios (DEGRADED and SKIPPED, both aborted)
and leaves out what `Phase7Pending` holds back. `keypad-image` is `ResolutionChecks.keypadImageHoldsAgainstItsBaselinesAnd
AChangedOneFails` (`@Order(19)`, both seeds; the changed baseline is a copy of the bindings root with one pixel of the
`keypad` PNG flipped and its entry's `sha256` following it), assumed away until step 2b. **Fix wave (Phase 7 review,
FB, 2026-10-10):** the `approve` that `-Dskillroy.bindings.update=true` runs first brings the desk to the front with the
entry field focused (`ResolutionChecks.activateDesk`, a `swing.click {by: {id: entry}, rung: COMPONENT, activateWindow:
true}` outside the run, as `VisionSwingChecks` and `VisionSessionScript` do), so that an entry's `focus` and `carets`
record that known state and not the focus the WM-less display happened to leave (L3-F9; the replay is unchanged and
holds across focus states through §6.7's focus rule).

**As built (Phase 7, VS, step 2b).** Every plan of both scenario modules and the branch variant carries `packVersion`
0.6.0 (the export's at plan version 6), every committed bindings file the same pack version (`BindingsUpToDateTest`'s
rehash now carries the compiled plan's `packVersion` too, D401; an `approve` writes the same bytes), the five goldens hold the
five Swing ids; `keypad-image` is approved in the arm64 image on the Java 25 and the Java 8 target, both seeds: per seed a
bindings file with one baseline per render key (`d0bd5ce68e2f111c`, Java 25; `6ccffcf8f0c8db46`, Java 8) for `keypad`
and `keypad-frame` under `bindings/seed-<a|b>/keypad-image.images/`. `Phase7Pending` is deleted: `sign-on-by-number-pad`
runs in `LoadModeChecks` and the glue, `keypad-image` in `ResolutionChecks`, `BindingsUpToDateTest` and the glue.
`Runs.assertPassedWithoutSleeping` counts an `image` record as a check, and a scenario whose only steps are image checks
needs no wait (D402).

**As built (Phase 7, VM):** item 8 is `InferenceChecks#theVisionSessionExportsTheCommittedScenarioWhichReplaysWithZeroModelCalls`
(seed A, the agent on the command line). The scripted model names a mark by the control's quoted name in the marks list
(`button 'DUE'`), so that the prompt line, which holds the word too, is never chosen. Before the model's turn the harness
brings the desk to the front outside the session (`swing.click {by: {id: entry}, rung: COMPONENT, activateWindow: true}`,
not journaled, as `VisionSwingChecks` does): a WM-less display gives the focus to no window by itself, and one run
without it ended with the model's `type` refused ("nothing has the focus"). The session recovers the desk when it
closes, so that a failure does not leave the next check a signed-on desk. The committed
`exported/session-vision-loan.*` were generated on the arm64 image with a Java 25 controller and a Java 25 target and
held byte for byte with a Java 8 target. The export's YAML records `click {by: {action: DUE}}`, `type {by: {id: entry},
text: "10.00"}` (the focus owner's locator) and `click {by: {action: DEPOSIT}}`, each with its provenance comment, and
`requires` gains `swing.tree`. The player half is
`InferenceChecks#thePlayerPlaysTheTwoVisionScenariosWithAScriptedModel` (seed A, both Javas): the player binds both
scenarios' fixture slots through `ExecutorSession.fixture` as the person (§9.2's note, D425). `vision-live-only.yaml`
passes with the scripted model resolving a plain item, scanning it, clicking the `DUE` key's mark and taking the deposit
due, five vision calls. `loan-table-visual-check.yaml` passes with its `aiExpect` answered `YES` in one vision call, the
verdict journaled as a note.

### 13.3 Live (profile `inference-live`; needs `ANTHROPIC_API_KEY`; skipped without it; never in CI)

`VisionLiveIT` in the sample scenarios module, on the desk in the image, budget USD 1.00 per session and USD 3.00 for
the class: (1) a V1 session on Sonnet 5.5 at `low` lends one item and settles the deposit (`done`, the desk's facts
agree, at most 6 UI actions, at most USD 0.10; BS12: 4-5 actions, USD 0.03); (2) a V2 session through the toolset with
the pack's legend (at most 8 actions, USD 0.10; BS12: 4.5 with the legend); (3) one toolset request with `fallbacks:
true` (the header coexists, or the finding of §7.1 is recorded); (4) a V1 session on the class `haiku`, mapped to
`claude-haiku-5-5` by the settings file VM adds for the profile (§7.3) (done, USD 0.01); (5) a `skillroy_look` question and an
`aiExpect` through the player (YES on a true statement, NO on a false one); (6) the export of (1) replays with zero
model calls. Every file written is checked against the key's value before writing (common rule 6). The results are
recorded in `../TODO.md` under "Phase 7, the live items" with the spend.

**As built (Phase 7, VM; written, never run):** `VisionLiveIT` begins with `Live.assumeLive()` and a display check and
reads the profile's settings file. Item 3 runs second, so that a fallback header the provider refuses is recorded
before item 2 needs it: an answer means the header coexists, and `INVALID_REQUEST` is printed as the finding, not
failed. Items 1, 2 and 4 bound the turn's `vision` calls (a resolution the person asked for is not counted) and every
vision call's model. Item 5 is a read-only V1 turn asked to use `skillroy_look`, then the player on the committed
`loan-table-visual-check.yaml` (its true statement, `YES`) and on the same scenario with the question "the loan table is
empty" and `expect: NO`. An item whose bound would pass the class budget is skipped. The files the
class writes itself are checked for the key's value before they are written; the framework's files (journals,
`inference.jsonl`, captures, run directories) at the end of each item and again by the last check.

### 13.4 Not verified in this phase, and why

Windows (no host: the popup trigger on the release, UIPI, session 0), Wayland and XWayland, a locked session, macOS
with the Accessibility permission granted (not changed, by rule; BS9), HiDPI (the image is scale 1; `scaleAssumed` and
the Retina difference of BS9 are recorded), heavyweight popups, native drag and drop, JxBrowser input (the wall, gap
41), windowed JCEF on Java 8 (gap 44), a long session (BS12's longest task was 14 actions), the fold under the vendor's
history rule (§7.4), V3 on a person's desktop with another process's window covering the target (the residual of §11
(c); BS9's strict mode refused to run the case). The amd64 image runs the vision session and the scenarios as every leg
does; `keypad-image`'s committed keys are expected to hold there (BS11), and a key that differs there is a finding for
the orchestrator, settled by an `approve` on that leg, not a failure of the matrix. Each is a line of §15's "not
verified" list and of `threat-model.md` §8.

**Phase 7 security fix (2026-10-11, SG):** the iconified refusal ran on the WM-less display (where the toolkit keeps the
frame's `ICONIFIED` state while the frame stays mapped), through the state seam, and once under openbox in BS9's derived
image (`skillroy-spike-bs9:wm-arm64`, the two iconified cases of `RobotPathDisplayTest` on Java 25, green), not as a
matrix leg; also not verified: macOS's and Windows' minimised windows; a window of the JVM on another virtual
desktop or workspace (AWT cannot see it; `requireFocus` is the only guard); Windows' parking of a minimised frame at
about (-32000, -32000) (now left out of the composite as hidden, so its union no longer scales the picture down) and
`SetCursorPos`' clamping; macOS's routing of a button event at an off-display point (now refused before any move); a
real multi-screen desktop (the off-screen rule reads every device's bounds; the second device's Robot on a worker);
HiDPI (the grab's bound at scale 2 or 3 computed from the configuration's transform, not measured); the encoding bound's
Java 8 allocation (the factor six assumes two-byte characters there, not measured); on macOS, the screen-recording
consent dialog the first glass capture raises in the target application's name.

**Phase 7 security fixes (2026-10-11, SH, SI, SJ) and the delta review, also not verified:** a real masked browser (the web
host registers no `CaptureMask`, so S111's agent half ran against fakes and, for the module's own bounded EDT read, over real
Swing components; real JCEF ran only unmasked, and a web-host item with a mask on the front browser's panel would close it,
gap 95); one desktop holding a real JCEF frame and a `JOptionPane` (S108); that a synthetic middle click pastes the X11
PRIMARY selection in JCEF on Linux (stated from Blink's behaviour; the refusal is tested at every gate) and the paste of S110
on a headed JVM with the real clipboard; the late click of S133 on real Chromium (a fake world); a provider's handling of a
toolset member whose `configs` disable it (S137, made irrelevant by the module's own refusal); the Windows ACL judgement of
S141 (unit-tested on built ACL values; the CREATOR OWNER entry is resolved by its English name), S142's junction and S144's
inheritance of the pixel files, on no Windows host; a second OS user for the writers' link rule (S140: the links were the
probing user's); an APFS case-insensitive volume for S118's labels (a string comparison, tested in the compiler, the reader
and the constructor); the amd64 image, which no fix round used. The delta review's own lenses ran with no display and no
image run: S104 and S105 were shown from the code and the JDK's sources and only then run by SG, and the paste of S110 from
the agent's dispatch probed headless with Swing's sandbox clipboard. (Phase 7 follow-up, gap 95, built 2026-10-11: the
real masked browser, one desktop holding a real JCEF frame and a `JOptionPane` (S108), and S133's late click on real
Chromium have run since, §13.2 item 10's gap-95 checks, on both target Javas in the arm64 image; whether Chromium pastes
the PRIMARY selection on a synthetic middle click and the paste of S110 on a headed JVM stay not verified, and a person's
synthetic middle click was found to leave an off-screen JCEF page unresponsive.)

---

## 14. Exit criteria

The roadmap's row 7 (design §8), item by item:

| Criterion | Met by | This phase |
|---|---|---|
| a vision-driven session on the sample is recorded into ordinary steps that replay with zero model calls | §13.2 item 8 in the image with the scripted gateway on both Javas (the committed `exported/session-vision-loan.*`, which carries nothing target-dependent, D356), and once live (§13.3 items 1 and 6) | **can be met** in CI (the scripted form), and live once |
| a plan compiled with `inference: run` is reported as skipped in `replay` | `vision-live-only.yaml`: `Status.SKIPPED`, JUnit `<skipped>`, exit 0, no agent call (§8.6, §13.2 item 7) | **can be met** |
| a guarded visual expectation yields `DEGRADED` in `replay` | `loan-table-visual-check.yaml`: `inference.vision` `UNAVAILABLE (replay)` in the merged matrix, the check skipped, the run `DEGRADED` (§8.5, §13.2 item 7) | **can be met** |
| V3 runs only with its explicit opt-in | §13.2 item 6: `swing.pointer.robot` `UNAVAILABLE` without the agent option in every golden; with it, a `via: ROBOT` call is refused by the executor and the session without `--allow-robot`; with both it fires on the WM-less display (BS9); §13.1 items 7 and 8: never a per-call choice of a model or a client even with both flags, and never a glass capture for one | **can be met** on Linux; the person's-desktop half (macOS, Windows) is specified (§3.4) and not verified |
| *(added)* the pixels a model sees are redacted `PAINT` captures of the target's own windows, no credential reaches a model, and every vision call is in the ledger | §13.2 items 1 and 8, §13.1 items 5, 7 and 8 | **can be met** |
| *(added)* `web.hitTest` and `web.pointer` on both paths against real JCEF | §13.2 item 10 | **can be met** under the profile `jcef` |
| *(added)* an `expectImage` baseline holds on the target it was made on and is `REBIND_NEEDED` elsewhere | §13.2 item 7 (`keypad-image`) for the hold on every leg; §13.1 item 5's `PlanExecutorTest` case for the missing key | **can be met** |
| *(added)* the security delta review is signed off | §15 (B31) | **met** (2026-10-11): run and fixed 2026-10-10/11 (S101-S148; S142 and S144 accepted by the owner), signed off by the owner, B31 |

---
## 15. The security delta review (B31)

**Decision (D338).** B28 covers the tree as of `e3ceece`. Phase 7 opens a new boundary (pixels leave the JVM, screen
content steers a model, Robot acts outside the application) and is reviewed as `docs/release-checklist.md` item 2 asks:
a **delta review** over everything Phase 7 changed since `e3ceece`, run after the build and the code review, in the
method of P6 §12.3 (D147): the eight lenses L1-L8, each with one reviewer and one sceptic who tries to break the
reviewer's claims, the four-level scale and the counting rule of P6 §12.4 and `threat-model.md` §4 (a finding that
needs the token counts when the token's user is a model or an MCP client; the operator's environment is
"operator-only"); findings numbered **S101** onwards; every finding of medium or above **fixed** (contract first, a test
that fails on the old code, then the code), nothing of medium or above accepted as a known risk; a low may be accepted
in writing with a release note as P6 §12.5 allows; the record (`record.json` in the 2026-10-02 shape, `dispositions.md`)
in the session notes; the summary in `../TODO.md`; **the owner's signature is B31**, and the orchestrator does not sign (the
owner signed on 2026-10-11).
What each lens looks at in this phase:

| Lens | Phase 7 surface |
|---|---|
| L1 controller correctness and bounds | the merge of contributed capabilities and `SessionOptions.host`, `SKIPPED`'s decision point, the image comparison's bounds and files, the bindings file's `images` and its derived paths, the bind lifecycle's second write, plan version 6's reader (`guard`), the export's comment and `images` section |
| L2 host safety (the agent against the host) | `swing.pointer`'s posted sequence with its per-event checks and the `cancelled` flag, the listener's removal, the Robot's construction, worker thread, paired press and release, pointer restore and delivery probe, `requireFocus`, the capture's redaction, masks and heap bound on the EDT, the SPIs' call rule, the covering and modal checks, A4 (no Robot before AWT), bounds of §2.4; §11's `SECURITY.md` sentences (c), (d), (g) and (h) present |
| L3 the data channels | nothing new (no new SQL path); `data.query` never reachable from the facade's `read` or `ExecutorSession.read` |
| L4 the web boundary (the page as an adversary) | `hit`'s page-chosen answers bounded on the receiving side, `web.pointer`'s parameters, the library's version rule (an older slot a page controls), the CDP path's new sends, `SurfaceSource` from the index only, the composite into non-redacted surfaces only |
| L5 the model boundary (what leaves to the provider) | redaction before the image leaves, never a glass capture (D341), pixels without a value mask (§11 rule 1), `images: true` as the one gate, the mask on every text route with images (`Prompts.part` hashes; `base64` never masked, never a text route), recordings with images, the legend, a tool's instructions and the marks list masked, `<model-question>`, the key never in a request, the fallback header with a toolset |
| L6 the model as an actor (MCP, session, planner, overlay) | the tool vocabulary of V1-V3, `CallPolicy.arguments` on every route including the raw primitive paths (`redact: false`, `force` on five primitives, `via: ROBOT`, `mode: ROBOT`; `robotAllowed` on the facade's route only), the credential rule (the reference refusal, `secretsInFlight`, the value check), the recorder's refusals (masked target, screen-text locator), the stale rule and `expectTarget`'s scope, V3's double opt-in and `requireFocus`, the budget and the service-enforced limits, `confirm` still a person's; `--vision`/`--allow-robot` as the person's flags (on the raw `call` too, where the person's argument is the second half), the captures resources under `images: true` only, `PlanPolicy` on `pointer`/`expectImage`/`aiAct`/`aiExpect` and on a credential slot's field in a client's scenario (the credential rule's plan form), `skillroy_catalogue`'s `source`, a read-only policy's tool list |
| L7 Windows and file-system trust | the new writers and directories through `OutputFiles` and `SecureFiles`: `captures/` and `candidates/` under the session's or the run's output directory, `<scenarioId>.images/` in the source tree beside the bindings file (written by `approve` and the export's caller), `failure/image-*.png`, the MCP server's capture resources; a bindings-side `.images/` directory in a working copy another user prepared; `file` derived and resolved inside the bindings directory without links; the ACL branch unverified (threat model §8) |
| L8 evidence at rest | `captures/<hash>.png` (masked, as captured, never a `ROBOT` capture), baselines and anchors beside bindings (masked, reviewed code), `failure/image-*.png`, the journal's provenance (no bytes, bounded intent, the anchor object), `run.jsonl`'s `image` and `skipped` records, `OutputFiles` for every writer; §11's `SECURITY.md` sentences (a), (b), (e) and (f) present |

The delta review's "not verified" list (§13.4) joins `threat-model.md` §8, `README.md` and `SECURITY.md` by the
build's documentation package; the release checklist's item 6 repeats it.

**The run (2026-10-10/11).** One reviewer and one sceptic per lens; L1, L3,
L4, L7 and L8 on `fbef000` (before the code review's fix wave was integrated), L2, L5 and L6 on `2633eec` (after it); probes
in scratch copies, no display, no image run, no live model call. 52 findings were raised (29 by the reviewers, 23 by the
sceptics), 1 was refuted (L8-F4, no number) and 3 pairs were merged into one finding each (S110 = L5-F1 = L6-F1, S115 =
L7-F1 = L8-S1, S117 = L1-F3 = L8-F5): **48 standing findings, S101-S148, none critical, 2 high (S101 the credential-joined
slot as a whole passing the credential rule, S102 the plan form keyed by source line), 13 medium (S103-S115) and 33 low;
42 counted, 6 operator-only (S117, S119, S126, S136, S143, S147), none token-holder-only**; 22 were raised by the sceptics.
The code review's fix wave closed none of them. The record (`record.json`, `dispositions.md`, the lens files and four fix
reports) is in the session notes; its summary is in `../TODO.md`, "Security review (Phase 7 delta, 2026-10-10/11)". The fix
round ran from `03c8da9` in four packages with disjoint files, SG (the agent's Swing side), SH (credentials, slots and the
client's plan form), SI (the vision module, the session service, the web reads and the CDP path) and SJ (the image
baselines, the bindings files and the run output), and landed on `main` as SG `703cc9b`, SH `ed397f0`, SJ `dd2f1d6`, SI
`a1af6c8` and SG's follow-up for S108 `223c5fb`. **Every finding of medium or above and 31 of the 33 lows are fixed**
(contract first, marked "(Phase 7 security fix, S<nnn>, built 2026-10-11, <TAG>)", a test that fails on the old code);
S142 (a Windows directory junction taken for an ordinary directory) and S144 (the Windows ACL inheritance of the pixel
files) are accepted by the owner (B31, 2026-10-11) with the release notes of `../../SECURITY.md` (P6 §12.5). The 37 decisions of
the four packages are D450-D486 (§17). The clipboard route of S110 predates Phase 7 for the planner, whose server is always
`mcp --mutating` and has offered `swing_key` with modifiers since Phase 5; B28 did not see it. The owner signed the review
off on 2026-10-11 (B31 in `../DECISIONS.md`), with S142 and S144 accepted as proposed.

---
## 16. Changes to the earlier contracts and the design overview

### 16.1 Marks into the earlier contracts and the overview

Written into the files named when the build starts, marked "(Phase 7, specified 2026-10-10, not yet built; P7 §n)" and
"(Phase 7, built …; P7 §n)" once built; the P1 passages carry their A-numbers (§5). Each package writes its notes under
its tag and never rewrites another's passage; the orchestrator merges by tag.

| Contract and section | Change |
|---|---|
| P1 §2.4 options | `robot` (A65), a default of `false` in `AgentRuntime.resolveOptions` |
| P1 §3 `skillroy-protocol` | the version stays 0.2 (D299); the script library's `hit` and version 2 (A66) |
| P1 §4 `skillroy-agent-api` | the SPI table gains `RegionResolver`, `CaptureMask`, `SurfaceSource`; `LocatorResolver.describe`; the web SPI's `WebPointerEvent` and `WebBrowser.dispatchPointer` (A64, A66) |
| P1 §5 catalogue and dispatch | `swing.hitTest`, `swing.pointer`, `swing.rendering` in the catalogue (A63); no new gate |
| P1 §6 swing primitives | the `swing.capture` row and passage (A61), the text walker's sentence "Password fields never …" extended to every component a `CaptureMask` names (A61, §3.6), the `swing.type` row and "Type modes" with gap 60 (A62), the rows `swing.hitTest`, `swing.pointer` and `swing.rendering`, "Click rungs"' `ROBOT` sentence, "Modal blocking" covering the pointer per event, "Capabilities" with the five ids (A63) |
| P1 §8.1 scenario YAML | the keys unchanged; `region`, `row`, `clip`, `guard`, `via`, `at` as verb arguments (§8.1) |
| P1 §8.3 base verbs | `aiAct`, `aiExpect`, `pointer`, `expectImage`; `UI_VERBS` with `pointer` |
| P1 §8.4 expectations | `VisualCheck` (skipped unconditionally under the executor), `ImageCheck` (from the step's capture result); the `image` record |
| P1 §8.5 run sequence and outputs | `SKIPPED` right after `run-start`; the robot refusal before `checkRequires`; the records `skipped`, `image`; `capabilities` with `agentHash` and `contributed`; the files `failure/image-*.png`, `candidates/image-*.png` (approve), `captures/`; the second write of the bindings file after the steps in `approve` |
| P1 §8.6 CLI and JUnit | `--allow-robot`, `--vision*`, `session --play`, `caps`' `source`, the `skipped` count, `--yes` for image approvals; `skillroy.robot` with its warning; `SKIPPED` as skipped |
| P1 §9 the sample | the number pad, `LendingRegions`, `LendingCaptureMask` (the field and the pad), `describe`, module 0.2.0 |
| P1 §10 acceptance | the checks of §13.2 items 1-6, 9 and 11 |
| P2 §3.1 strategies | `llm-vision` still refused (D316) |
| P2 §3.3 the `inference.` prefix | enforced against the agent's matrix too; one contributor per id (§8.5) |
| P2 §8.2 `plan.json` | version 6: `inference: run`, `images`, `Plan.Call.guard`, the two check shapes, `inferenceOf(slots, steps)`, `needsBindings()` over `images`; the version rule applied once more |
| P2 §9 bindings file | version 2: `images` keyed by render key with `carets`, `focus`, the nested `rendering`, `file` informative and derived; the PNGs beside; the bind lifecycle (`Approver.reviewImage` at the step, the rewrite after the steps, `images` carried forward); the export's own `images` section and `ExportResult.images()` |
| P2 §10 `REBIND_NEEDED` | reason `IMAGE` |
| P2 §11.3 records | `skipped`, `image`, `capabilities.agentHash` |
| P2 §11.4 never written | an unredacted capture's bytes to a model or a client; a glass capture to a model or a client at all; a capture's bytes on any text route (unchanged); baselines and the captures directory hold masked pixels only |
| P2 §13 the sample pack | version 0.6.0, `screenLegend()`, gap 49's `inductionRequired` argument (§16.2) |
| P4 §1 non-goals | `INPUT` and the two primitives built (A66) |
| P4 §3 the SPI | `dispatchPointer`, `WebPointerEvent`, `INPUT`'s Javadoc |
| P4 §4.2, §4.4, §4.5, §4.6, §5.2 | the two primitives, the two capabilities derived, the adapters' input, the library's version 2 and `hit`, the CDP path's served set |
| P4 §7.1 verbs | no new web verb (§1) |
| P4 §12.2 acceptance | `WebHostChecks` item 1 flipped, the new item, kit items 15-18 |
| P5 §3.2 types | `ProviderTool`, `ModelRequest.providerTools`, `PROVIDER_TOOLS`, `toolsetName`, image parts in a tool result, `CacheHint.AUTO` |
| P5 §4.1 Claude gateway | the toolset mapping, `toolset_name`, `contentOfBlocks`, the top-level cache marker |
| P5 §4.2 recorded gateway | `IMAGES` and `PROVIDER_TOOLS` declared by the recorded and the scripted gateway; recordings with the new fields |
| P5 §4.4 settings | `effort.vision`; `images` "Phase 7 turns it on" becomes "the one switch for pixels"; the live profile's `models.haiku` mapping |
| P5 §6.2 masking | an image part passes under `images: true`, redacted by the agent before (§3.6), its `base64` never masked and never a text route; the legend and a tool's instructions masked like the vocabulary; the label `<model-question>`; the vocabulary names no secret field of a slot |
| P5 §7.2, §7.3, §7.4 | the Haiku 5.5 row; purpose `vision`; the estimate with images |
| P5 §9.2, §9.3, §9.4 MCP | `--vision`, `--allow-robot` (for `skillroy_session_ask` only), `ServerOptions.vision/robot`, `SessionOptions.host`; the captures resources (never a `ROBOT` capture); `PlanPolicy` on the four verbs; `ToolCalls.primitive` under `CallPolicy.arguments` (refuses, never strips); the raw tool names `swing_hit_test`, `swing_rendering`, `web_hit_test`, `swing_pointer`, `web_pointer` |
| P5 §12.2 `ExecutorSession` | `read` (throws, `base64` exempt, the exclusion list); the four-argument `execute` forms; `capabilities()`; `SessionOptions.robot` and `host`; the robot gates (origin-free for the flag, person-only for `mode: ROBOT`); the credential rule (the `$slot.secret` refusal for every origin but a person's, `secretsInFlight`) |
| P5 §12.3 journal and export | `provenance` on `step`, handed in by the acting call; the comment without hash, handle or mark; the report's two sections; the `images` section of an export's bindings file; cost stays in the ledger (and design §3.4's wording follows) |
| P5 §12.4 session service | `SessionTools`, `SessionActions`, `SessionExtensions`, the eighth constructor parameter, `visionCall`, `atTurnStart`, `afterBatch`, `endTurn`, the vision limits enforced by the service, `CacheHint.AUTO`, the raw primitive branch under `CallPolicy.arguments`, a tool's instructions masked |
| P5 §13.1, §13.2, §13.4, §13.5 | the verbs and `region`; plan version 6; the CLI options; `SKIPPED` in JUnit |
| P5 §14.1, §14.4 | the pack's version and legend; the vision session's export beside the Phase 5 one |
| P5 §15.3 live | `VisionLiveIT` (§13.3) |
| P6 §6.4 archived matrix | the merged matrix, `agentHash` (D130 kept on the agent's), `host` |
| P6 §10.2, §10.4 observer | no new attribute; `modelCall` spans with purpose `vision` carry no image |
| P6 §12 security | the delta review (§15, B31) |
| P6 §15.2 gaps | 31, 36, 49, 60 and 76 as §16.2 says |
| design §1.1 stack | the Vision row: built, `skillroy-vision` and the three agent additions |
| design §2.1 modules | `skillroy-agent-swing`'s row: hit-test, pointer, Robot behind the option; `skillroy-agent-web`: input |
| design §2.4 catalogue | the reserved rows become the built signatures; `swing.rendering` |
| design §2.6 SPI table | `RegionResolver` built; `CaptureMask`, `SurfaceSource`, `LocatorResolver.describe`; `MaskPolicy`'s row loses "a component-level method is reserved" (it is `CaptureMask`) |
| design §3.1 modules | `skillroy-vision` built; image comparison in `skillroy-scenario` (D313) |
| design §3.2 DSL | the four verbs, `region`; the reserved paragraph becomes the built one |
| design §3.4 record and replay | the journal's provenance "(intent, model, request id; the cost in the ledger by request id)" |
| design §3.5 failure taxonomy | `SKIPPED` as the fifth status, no new kind |
| design §3.7 capture | root-pane painting, redaction, the Robot rung's facts (BS9) |
| design §3.8 | `INPUT` built |
| design §3.10 | status "built" per section; **the tier table's V3 row (design line 643: "a `ROBOT` screen capture") becomes "a `PAINT` capture of the target's windows; the pointer through Robot" (D341)**; "image comparison" in the base (D313); **the two sentences D314 and D330 overturn: "Where a pixel cannot be mapped" item 2 (line 668: "a `PAINT` crop … and a tolerance. The fingerprint of such a scenario includes what changes rendering") and the visual-assertions table (line 676: "with a mask and a tolerance, and the rendering inputs are part of the fingerprint") read "exact after masks, `maxDiffPixels` 0 (D314); keyed by the render key, the fingerprint unchanged (D330)"**; the loop's rules as BS12 measured them |
| design §5 | the vision loop in the session service through `SessionTools` and its facade |
| design §6 CI | `SKIPPED`; `inference.vision UNAVAILABLE (replay)` |
| design §7 layout | `controller/skillroy-vision/` built |
| design §8 roadmap | row 7's status; BS9-BS12 passed; **the BS11 summary (line 953: "baselines bound to a fingerprint of rendering inputs") reads "baselines keyed by a render key" (D330)** |
| design §9 risks | **the recorded-vs-replayed row (line 986: "rendering inputs in the fingerprint, so the failure is one `REBIND_NEEDED` per scenario") reads "baselines keyed by the render key; `REBIND_NEEDED(IMAGE)` per baseline"**; the prompt-injection row gains the mechanisms built (the credential rule, the masked-target rule, `CallPolicy` on every route); a new row for the root-pane finding |
| design §11 | B29, B30, B31 |
| `threat-model.md` | §2, §3, §5, §6, §7, §8 (D334; written now, extended by the build) |
| `phase1-contract-amendments.md` | the "A34 onwards" index (D301) |

### 16.2 Open SPI and DSL gaps: what Phase 7 takes

| # | Gap | In Phase 7 |
|---|---|---|
| 31 | `ROBOT` type mode, `OPTION_VALUE` dismissal, `swing.events` | not taken: Robot keys stay out (§1, D345, §18 Q6; gap 77 below), `swing.events` is not needed (`received` and the pack's events answer "did my input arrive", D293); `OPTION_VALUE` unchanged |
| 34 | the `PAINT` guard | unchanged; `skipped` kept beside `surfaces` (D289) |
| 36 | `web.dom.click` is a DOM click | **closed**: `web.pointer` and `INPUT` (§4) |
| 37, 39, 44 | `web.resize` and the component's bounds; a hidden JCEF browser has no bitmap; windowed JCEF on Java 8 | unchanged; stated where they touch the vision loop (§1, §4.3) |
| 49 | `LendableItem` cannot say "an item that needs no induction" | **taken**: the pack changes anyway (0.6.0): `inductionRequired` argument on `LendableItem`, as `ItemsTriggering` has |
| 55 | `RpcResult.startedAt` | not taken (no protocol round is opened: 0.2 stays) |
| 60 | `swing.type` DISPATCH + `enter` acts on the last-focused field | **documented** in A62's passage; the fix stays later |
| 76 | `swing.capture` in `PAINT` mode, given a frame on Linux, includes imitation window decorations (BS9) | **closed**: A61 paints a window from its root pane (§3.1, D288); `REGION` and `COMPONENT` of a `RootPaneContainer` likewise |

New, numbered **77-87**: **(77)** Robot key input (a V3 keyboard through the OS) is not built; V3 types through the
routed primitives after a focus check, and stops the turn when the target JVM holds no focused window (§3.4, D345).
**(78)** HiDPI: the Swing side reports user-space pixels and the web side computes an empirical factor with
`scaleAssumed`; nothing runs at another scale than 1 here (BS9 leg `mac-j25`: a `ROBOT` capture on a Retina display
differs from a `PAINT` by antialiasing). **(79)** `swing.hitTest`'s mouse target is inferred with public API plus two
rules (`componentPopupMenu`, `autoscrolls`); a component that calls `enableEvents` otherwise is invisible to it (BS10
condition 3); `swing.pointer`'s `received` reports the real one. **(Phase 8, built 2026-10-19, EA; P8 §3.2 rule 6)**
The routed pointer's per-event veto asks for that inferred mouse target, so the deepest component at the point is
asked too, before the sequence (the pre-check's two chains) and per event when no mouse target is found. **(80)** The covering-window rule of the routed path
ranks only a modal dialog, an owned window and the active window; two unrelated frames of the JVM are reported, not
ranked (§3.2, §3.3); the Robot path refuses every covering window instead (§3.4), and a window of another process is
invisible to both. **(81)** `web.pointer` has no key input (`Input.dispatchKeyEvent`, `Input.insertText`), the later
keys of `INPUT`. **(82)** The toolset's `hold_key`, a horizontal `scroll`, and in V3 `left_mouse_down`/`left_mouse_up`
are refused as text (§6.3). **(83)** A baseline exists per rendering the project runs in; a new JDK line or font set
needs an `approve` run per environment to add its key (§8.7). **(84)** Redaction blanks a component's rectangle after
it was painted: the layout and a value's width stay visible, and pixels carry no value mask, so a component no mask
names reaches a model as rendered (§3.6, §11). **(85)** The session's fold and the vendor's history rule on the 5.5
models are not exercised live beyond one turn (§7.4). **(86)** `web.pointer` into a hidden JCEF browser is served but
what Chromium does with it is recorded, not asserted (§4.9). **(87)** The window between the recorder's hit-test and
the recorded step's act is closed by the stale-capture check and the verb's exact-match locator, not atomically: a
locator that matches exactly one *other* component after a window change acts on it (§6.4); `expectTarget` closes it
for the live-only raw calls only.

Numbered **88-90** by the build (2026-10-10; `../TODO.md`): **(88)** the routed `MODAL_OPENED` rule reports `fired: false`
also when a release, not the press, opens the dialog and only the trailing `MOUSE_CLICKED` is withheld although the button's
action fired (§3.3, VS); the executor's A29 then fails such a `pointer` step. **(89)** The MCP server's
`skillroy_session_export` writes no anchor PNGs: only `session` (the REPL's `export` and `--play --export`) does (§9.3, D429,
VM). **(90)** `web.pointer`'s `hit` is the main frame's, a frame element when the point lies in an iframe; only `web.hitTest`
descends (§4.2, §4.3, VW).

Numbered **91-102** by the code review's fix wave, the race fix and the security fix round (2026-10-11; `../TODO.md`): **(91)**
the committed image entries carry `rendering.os.version` and `jdk.version`, the container host's kernel release among them:
reviewer-only text that is not part of the key (D350) and that every `approve` on another host rewrites (code review L1-F7,
refuted as a churn problem; FB; a contract change, not a fix). **(92)** Closed 2026-10-11 for its reason text (a follow-up after Phase 7): a capability declared after the target is
ready reads `UNAVAILABLE("not probed yet: declared after the target was ready, its probe is queued")` until its posted
worker probe has run (`CoreCapabilityRegistry.LATE_REASON`, the `not probed yet` prefix kept); the state and the timing
are unchanged, and the synchronous first probe and an agent-api `declare(id, probe, initial)` stay rejected (D449). **(93)** Replay
refuses an unapproved value for a model's strategy only (D72): the export's `REQUIRED` mark on a model's or a client's
tier (a) binding (D468) is the reviewer's mark and no replay gate; refusing `NOT_APPROVED` for a recorded `REQUIRED`
whatever the strategy is one line in `Binder`, the owner's call (SH). *Closed 2026-10-11: the owner's answer is not to refuse (B33).* **(94)** Closed 2026-10-11 (a follow-up after Phase 7): the recorder reads a `type`'s text with
`References.references` and asks the facade's `namesSecret` (§6.5, §9.1), and the vocabulary's sentence names the
whole-slot rule (§9.2 rule 1); the five Phase 5 session recordings regenerated key-free. **(95)** S111's agent half and the composite's clip ran against fakes:
a web-host check with a mask on the front browser's panel, and one desktop with a real JCEF frame and a `JOptionPane`, would
close them (SI). **(96)** A modal check in `web.dom.click` and `web.dom.fill` for MODEL and CLIENT origins, the broader
alternative of S109, a P4 amendment; the recorder's path is closed by D472 (SI, optional). *Closed 2026-10-11: the owner's answer is not to refuse (B33).* **(97)** Two residuals of the
model's pixel: an in-window change between a V2 or V3 capture and the act (a glass-pane dialog that opens no window) is
outside the stale rule's trigger, and an application's own Paste item or context-menu command is an ordinary click that no
chord rule can see; mask it (SI). **(98)** A window of the target JVM on another virtual desktop or workspace is invisible
to AWT, so `requireFocus` is the only guard there; the iconified refusal ran on the WM-less display and once under openbox,
not on macOS or Windows (SG; documented limit). **(99)** The bindings JSON reader still follows a link at the bindings
file's own position (the writer replaces such a link by the atomic rename; the PNG reader's no-link rule is the one the
writers adopted; SJ). **(100)** The Windows ACL judgement of `OutputFiles` resolves CREATOR OWNER by its English name, so on
a Windows with localised built-in names it is not exempt (a false refusal, the limitation of S94 extended; SJ). **(101)**
The Robot covering rule refuses every point of a dialog that lies over its own owner (`HitTest.covering` lists the owner
frame below it), so V3 cannot click a prompt dialog of the desk without `force`; a fix must leave out only windows that own
the target and stay on the safe side for every other unranked window (code review's security lens L2, a functional note). *Closed 2026-10-11
(a follow-up after Phase 7): an owner of the target's window is left out while that window is the active one (§3.4).*
**(102)** The `<target-state>` block of the bound slots is capped at 40 slots and 30 fields a slot (counted beyond) and was
exercised with a few slots only (FC, D444).

---
## 17. Decisions taken in this contract (D287-D358), by its build (D359-D430), and by its fix wave and security fixes (D431-D486)

D287-D340 were taken with the contract on 2026-10-10; D341-D358 were added by the revision after the contract review
the same day (the session notes' `contract-review/revision-log.md` names the finding behind each, and the rows of
D287-D340 whose text the revision changed: D294, D302, D304, D305, D306, D308, D309, D312, D326, D327, D329, D330,
D331, D332, D334, D337, D338, D339, D340; the last round after the recheck changed D332, D337, D339, D342, D348, D352,
D355 and D357 once more, `revision-log.md` "Last round"). The build's decisions, D359-D430, follow the table below, in a
table of their own, and the decisions of the code review's fix wave, the race fix and the security fixes, D431-D486, in a
third. The owner confirmed all of them on 2026-10-11 without a veto: D287-D358 as the answers of §18 (B29), D359-D486 as
B30.

| # | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D287 | `swing.capture` is extended additively: name, capability `swing.capture.paint`, default mode and the four built fields unchanged; the reserved parameters and result fields added; `target` also takes a locator object and `by` is accepted (§3.1) | a second primitive (`swing.capture2`); a wire-type sub-package for the result | the reserved names exist for exactly this; the result stays a plain map every consumer reads by key (`writePng` reads `base64` only, `Images` keys on `format` and `base64`); nothing needs typed parsing beyond `JsonView` |
| D288 | A window is painted from its root pane (and so are a `REGION` and a `COMPONENT` of a `RootPaneContainer`) and the capture is redacted by default, both as amendment A61, although the built pixels change; gap 76 closes (§3.1, §3.6) | keep the frame painting and add `scope: CONTENT`; `redact` default false with the vision module always passing true | BS9 and BS12 found imitation decorations drawn over the content in every frame capture on the CI display: the built pixels were wrong, not a baseline anyone holds; nothing committed compares them; B19's rule makes the base restrictive by default, and a default of false would leave a person's evidence images and the MCP's unmasked while a model's are not |
| D289 | `skipped` stays as built; `surfaces` lists heavyweights and web surfaces beside it, each with `redacted` | fold `skipped` into `surfaces` | P4 §2.5 is built and tested; the overlap is one sentence; the controller never reads `skipped` |
| D290 | The Robot implementation lives inside `skillroy-agent-swing` (`RobotInput`, lazily behind `RobotFactory`), JDK API only, constructed on a worker after `checkAwt`, behind the agent option `robot` (§3.4) | an optional module `optional/skillroy-agent-robot.jar` behind a new agent-api SPI, named by `optionalModules` | `java.awt.Robot` is JDK API, so B4's rule is untouched; A4 is enforced in one place (`SwingModule.checkAwt`); the option is the host's gate exactly as `optionalModules` would be; an optional module costs an SPI, a second module, a second golden state per leg and a loader boundary for about two hundred lines; vision §R9 permits either |
| D291 | `swing.click {rung: ROBOT}` stays the sentinel; a Robot click on a component is `swing.pointer {target, via: ROBOT}` (§3.4) | map the rung to a pointer click at the centre | a third way to click by ref, with the rungs' `fired` semantics it cannot keep (a Robot click is observed, not posted); the pointer's component form is the same call with an honest result |
| D292 | `swing.hitTest`'s shape: a point in a capture's, a window's or the screen's space, or a component by ref; `deepest` and the inferred `mouseTarget` with the two public rules, `wheelTarget`, `covering` with `ranked`, up to six verified locators, `row`, `fires`, `region`, `surface`, `enabled`, `listening`, `masked`, `blockedBy` (§3.2) | the research's smaller shape; AWT's package-private target search by reflection | BS10 RESULT "What `swing.hitTest` must report": the glass pane, the disabled button, the covering window, the wheel target and the `setComponentPopupMenu` case each needed a field; the recorder needs `row` and `fires` (a table-row mark and a custom component the pack can fire are undecidable without them); reflection into `Container` would break the no-`sun.*`/no-internals rule and vary by JDK |
| D293 | `swing.pointer` ROUTED: window-sourced events dispatched non-posted from one `invokeLater` task per event in BS10's native sequence; the result reports `received`, `dispatched`, `focusOwner`, `activated`; no `swing.events` tap (§3.3) | the built `DISPATCH` rung with a point; posting the events to the system queue; building `swing.events` | BS10: `DISPATCH` is position-blind, bypasses glass panes and sends no enter or move; posted events would be filtered by the modal loop and retargeted; one `AWTEventListener` for the call's duration answers "what received the press" without a general tap |
| D294 | Two checks before a routed action: `MODAL_OPEN` for a blocked window and `INPUT_REJECTED(covered)` for a ranked covering window, both skipped by `force`, which a model or a client never gets to give **on any input primitive** (`swing.pointer`, `swing.click`, `swing.type`, `swing.key`, `web.pointer`); nothing new is `destructive` (§3.3, §9.1) | always refuse any covering window on the routed path; never refuse; a `confirm` per pointer action; `force` refused on the pointer alone | BS10 conditions 1 and 2 by construction; AWT's z-order is a guess outside the ranked cases (vision §F8), so refusing on a guess would refuse rightly placed clicks that routing delivers to the chosen window anyway (the Robot path, which presses the real top, refuses every covering window, D344); a per-click `confirm` would make a person confirm every action of a session and so train them to confirm anything; the judgement that acting past a modal dialog is a person's choice holds for every primitive that takes `force`, so one list on every route (D244) |
| D295 | `redact` defaults to true everywhere; the built-in rule blanks every `JPasswordField`; the fill is solid grey; `redacted` reports the rectangles (§3.6) | default false; the component's background as fill | B19 (the base restrictive by default); the grey is deterministic for baselines and visibly a mask; a background fill would make the mask invisible and leak whether the field was empty |
| D296 | `CaptureMask` is a separate SPI with `masks(Component)` and `regions(Component)`, not a default method on `MaskPolicy` (§3.6) | `MaskPolicy.masksPixels(Component)` | `MaskPolicy` is row-shaped, AWT-free and called on application threads for hooks and Byteman (P6 §4.3); an AWT type in its signature would load AWT where no AWT is wanted and tie two unrelated call rules together; the research's "component-level method on the masking policy" is honoured in the family, not the interface |
| D297 | `region` keeps both reserved meanings: on the wire `swing.capture.region` takes a rectangle or a region id; in the DSL `region` is always an id and a rectangle is `clip` (or a `mask` entry) (§3.1, §8.1) | rename the wire parameter `clip`; forbid the id form on the wire | the name is reserved for the capture rectangle and the step locator alike (map 2 §8); one wire parameter with two typed forms is the module's existing pattern (`name`: a string or `{contains}`), and the DSL stays unambiguous |
| D298 | The capability ids stay lower-case as reserved (`swing.hittest` beside the primitive `swing.hitTest`) (§3.9) | camel-case ids | `web.hittest` is in five committed goldens and the web host's acceptance; two conventions already coexist (`web.initScript`); the reservation is the written one |
| D299 | The protocol stays 0.2; no new error code, no new topic (§5) | `0.3` as a marker; a `COVERED` or `NOT_DELIVERED` code | every change is additive and discoverable through `/schema` and `/capabilities`; `INPUT_REJECTED` with a reason and a `fired: false` result carry the new outcomes; a code is a wire change for a consumer that reads by name |
| D300 | The script library's version becomes 2; an older slot gets the missing functions installed, is never replaced (§4.6) | replace an older library; make `hit` self-contained | replacing loses a tap's buffer (P4 §4.6's reason stands); a self-contained `hit` would duplicate the aria walker's role and name rules in a second copy that drifts |
| D301 | The build appends an "A34 onwards" index to `phase1-contract-amendments.md` (§5) | leave the file at A33 | the design and this contract call the file the amendment process; a reader who follows the pointer finds the sequence's second half nowhere |
| D302 | The vision module plugs into the session service through the `SessionTools` seam (`ServiceLoader`) and acts only through the service's facade `SessionActions`: one loop, one meter, one journal, one mask, one history, one policy (§9.1) | a second loop in `skillroy-vision` over `ExecutorSession`; vision verbs run by the executor only; a context that hands the tool the raw `ExecutorSession` | D79's reason against two implementations of one protocol; the CLI and MCP hosts would need a second actor; the executor cannot host a model loop without becoming the session service; a tool holding the raw session could pick its origin and bypass `CallPolicy` (D353) |
| D303 | `skillroy-vision` is optional at run time (`ServiceLoader` from the pack loader and the CLI's class path), bundled in the CLI zip, depended on by the CLI and the sample's scenario tests only (§2.1) | a required module of `skillroy-scenario` | `replay` must run anchored steps and `expectImage` without a model module (D313); the base's required set stays what it was |
| D304 | The tiers' defaults: V1 with marks, Sonnet 5.5 (`SONNET_CLASS`), effort `low`, captures at the window's own size (down only above 1920 pixels, never below 0.75), the toolset for V2/V3 with `zoom` off, the class `haiku` (whatever id it maps to) for V1 only (§3.1, §6.2, §6.3) | V2 by default; Opus by default; effort `medium`; Haiku everywhere; zoom on | BS12 RESULT: V1 marks 100 % and the cheapest form that answers with a ref; Opus added no accuracy at twice the price; effort moved nothing on single-element questions; Haiku wrote calls as text with a pixel tool and failed both V2 sessions; zoom was never called and costs 411 tokens a request; icons failed first at half size; the rule is on the class because the class default (`claude-haiku-4-5`, D84) is a settings matter |
| D305 | The stale-capture rule: a changed window set since the capture's `seq` triggers a fresh capture and a same-component (and same-row) check at the same point before acting; the recorded step's act is then guarded by the verb's exact-match locator, and `expectTarget` closes the remaining window for the live-only raw calls and an MCP client's call (§3.3, §6.4; gap 87) | the design's strict rule (refuse and recapture); no rule; `expectTarget` on the recorded step | BS12 RESULT finding 4: the strict rule refused a click after a dialog-closing click in the same batch; the refined rule let 16 such clicks through and refused none; a recorded step cannot carry a per-start ref, so the atomic form is the raw calls' alone and the residual is named |
| D306 | The recorder's locator and step choice: the first verified locator (domain strategy first); `click` by locator where position does not matter (the roles `button`, `toggle`, `checkbox`, `radio`, `menuitem`, a `fires` target or a `describe` locator), `pointer` by locator with fractions, a row or a region where it does (the other roles of `Roles.ALL`, the class rule for `JSlider`/`JSpinner`/`JScrollBar`), an anchored `pointer` on the window where no locator exists; `type` at the focus owner's locator; the session's `ui.clickRung` (§6.5) | always `pointer` at fractions; always `click`; new roles `slider`, `spinner`, `scrollbar` in `Roles.ALL` | design §3.7's authoring preference and §3.10 step 2; a `pointer` on a button records a position that does not matter and would break on a resize; a `click` on a table row loses the row; the UI scenario's rung convention (map 7 Q5); the role set is closed and a new role would change every committed snapshot fixture, so the rule is written in its words |
| D307 | Every recorded action runs through the step that will be replayed, under `Origin.model`; V3 actions and raw pointer halves are journaled and listed live-only, never exported as steps (§6.5, §6.6) | export V3 steps with `requires: swing.pointer.robot` | design §3.10 step 3; no CI target has Robot, and an exported step that can only fail there is noise; a person who wants it writes `pointer {via: ROBOT}` by hand and runs it with `--allow-robot` |
| D308 | The loop's captures, hit-tests and `swing.rendering` are unjournaled reads through the new `ExecutorSession.read` (masked except an image's `base64`, bounded, non-mutating only, with a local exclusion list, throwing the agent's error); their hashes live in the acted step's provenance (§9.2) | journal every capture and hit-test as raw read steps; a `read` that swallows errors and masks whole values | `snapshotText`, `eventsSince` and `fact` are already unjournaled observations; two or three read steps per action would swamp the journal and the export's report; the provenance keeps the evidence; the loop needs the agent's error codes to answer the model, and a mask over base64 would corrupt the image under a lowered `minLengthInText` |
| D309 | The export writes an ordinary verb step plus one masked comment (role, name, class, model; no hash, handle or mark) and a report section for every vision-derived step (§6.6) | a `provenance:` key in the YAML; no comment; the hash in the comment | the loader is strict and has no such key; D86's observed-expectation comment is the precedent a reviewer knows; the hash and the handle vary by Java line, by caret phase and by call order, so the committed export must not carry them (D356) |
| D310 | The four verbs' shapes of §8.1: `aiAct` compiles to no call and makes the plan `run`; `aiExpect` to a `VisualCheck` with a fixed guard; `pointer` to `swing.pointer`'s component form (`guard` on the call); `expectImage` to one capture call and an `ImageCheck` evaluated from it | `aiAct` as a `call` of a controller primitive; `aiExpect` with an overridable `capability` | a plan holds no model call by rule (B6); an overridable guard would let a scenario drop the one guard that keeps a verdict advisory (vision §R5) |
| D311 | `aiAct` and `aiExpect` steps run only in the vision module's `ScenarioPlayer` over a session; `PlanExecutor` skips a `run` plan in `replay` and refuses it in every other mode (§6.8) | a `ServiceLoader` seam in `PlanExecutor` for model steps | the executor would host a gateway, a meter, a ledger writer and a history: a second session service inside the run; the player is the session service with a script, one way in (B15) |
| D312 | `inference.vision` is `OK` only in a vision session (the player, or a service with the vision tools, through the host feature `vision-session`); under `PlanExecutor` it is `UNAVAILABLE` in every mode with the reason, and a `VisualCheck` is skipped there unconditionally (D354) (§8.5) | `OK` under `PlanExecutor` in `live` with an evaluator seam | an evaluator in the executor needs a gateway there (D311's reason); the capability then says exactly what it means: a vision loop is attached to this run |
| D313 | The exact image comparison and the shared step code path live in `skillroy-scenario` (`…scenario.image.ImageCompare`, `ImageSteps`); design §3.10's placement in `skillroy-vision` is amended (§6.7) | in `skillroy-vision` | `replay` runs anchored steps and `expectImage` in CI without the vision module (C0, D303) |
| D314 | The comparison is exact on decoded rasters after masks (the baseline's masks, both captures' carets, the focus owner's rectangle on a focus mismatch, the baseline's side recorded in its entry); `maxDiffPixels` defaults to 0 and is the one explicit margin; the hash is of the raster with a pinned byte layout, never the PNG (§3.1, §6.7) | a per-channel tolerance with a pixel budget by default | BS11 RESULT "Tolerance study": within one rendering the noise is zero, and no tolerance separates cross-environment noise (1,128 to 68,479 pixels) from a one-character change (28 to 50 pixels); the caret is as large as a real change and must be masked, not tolerated; identical rasters encode to different PNG bytes on Java 8 and 25; two modules compute the hash, so its bytes are pinned and one vector shared |
| D315 | An anchor mismatch is `REBIND_NEEDED(IMAGE)` with both images; an `expectImage` mismatch is `FAIL / ASSERTION`; a missing baseline is `REBIND_NEEDED` in `replay`, approved at the step in `approve`, skipped in `live` (§6.7) | one outcome for both | an anchor is a recorded answer about where to click (P2 §10's semantics); a baseline is an expectation about the application |
| D316 | `Strategy.LLM_VISION` and `UiTarget` stay reserved (§8.9) | build the binding-shaped recording | vision §Risk1: a vanished control would read as a binding problem; the binder runs before sign-on and cannot verify a UI target of a later screen (map 5 trap 5) |
| D317 | An `aiExpect` verdict `UNSURE` skips the check with the reason (the run at best `DEGRADED`); `NO` against `expect: YES` fails (§6.8) | `UNSURE` fails; `UNSURE` holds | vision §R5: `UNSURE` is a result, not an error; a verdict never turns a failing scenario green and should not turn a passing one red on an unsure answer; a `NO` is the model's statement and a person looks at a live failure |
| D318 | Provider-defined tool entries travel as `ModelRequest.providerTools` (`ProviderTool(provider, type, raw)`), fingerprinted when non-empty, declared by `GatewayCapability.PROVIDER_TOOLS` (§7.1) | widen `tools` to a sealed `ToolEntry`; the entry in `metadata` | every caller of `tools` stays untouched; `metadata` is outside the fingerprint, so a recording of a toolset request would answer a custom-tool request |
| D319 | `toolsetName` is a nullable fourth component of `ToolUse` and `ToolResult`, in the fingerprint when set (§7.2) | a wrapper record per toolset call; reading it from `input` | the provider requires it on every result and puts it beside the block, not inside the input |
| D320 | `ToolResult.content` may be a list of `Text` and `Image` parts; an error result with an image is refused at construction (§7.2) | a sixth `Content` variant; an `images` component | the sealed set and `Prompts.part` already render nested parts naturally; BS12: the provider rejects an image in an error result with a 400 |
| D321 | The recorded and the scripted gateway declare `IMAGES` and `PROVIDER_TOOLS`; the price table gains `claude-haiku-5-5` (0.10 / 0.50 / 0.01, the >100K tier noted); the Haiku class default stays `claude-haiku-4-5`, the live profile's settings file maps it to 5.5 (§7.3) | keep `IMAGES` off the recorded and scripted gateways; move the class default | a recording keyed by a fingerprint that hashes the image can answer; the loop checks the gateway's capabilities before it sends an image, and the CI route of §12.4 runs on the scripted gateway; D84 stands and the loop never needs the Haiku class for the toolset |
| D322 | The ledger purpose `vision`; `effort.vision` with default `low`; no other new settings key (§7.3) | reuse `effort.session`; a `vision:` block | one call's purpose is its own line in the ledger and the report; BS12 measured `low`; the module's switches are the host's options, not the settings file's |
| D323 | `CacheHint.AUTO` adds the top-level `cache_control` the provider advances; the history is append-only within a turn, folded between turns (§7.4) | a breakpoint the loop moves on the latest tool result; no tail caching | the vendor's robust combination for agent loops; four slots of bookkeeping in the loop for the same effect; without it a 20-action session costs about four times as much (vision §F3) |
| D324 | Image tokens enter the estimate's fallback as `ceil(w/28) * ceil(h/28)` per image (§7.5) | leave the fallback | BS12: 1,039 tokens per 1024 x 768 capture; the meter would under-reserve by that per call wherever `countTokens` fails |
| D325 | Plan version 6: `inference: run`, the `images` key, `Plan.Call.guard`, the two check shapes, the verbs and their arguments; `inferenceOf(slots, steps)`; `needsBindings()` over `images`; one regeneration of every committed plan and bindings file (§8.2) | a version-5 plan with `run`; the guard's region in the plan | P2 §8.2's version rule: a new enumerated value and new keys; the reader's consistency check must see the steps; the guard's rectangle is environment data and lives in the bindings entry, so the plan names the label only |
| D326 | `CapabilityContributor` (`ServiceLoader`; `Map<String, Capability>`; a `CapabilityContext` with the settings, the mode, the agent's matrix, the host features and the pack): the merge once per host, in the executor after step 0b, in `ExecutorSession.open` (reading `SessionOptions.host`, D348) and the MCP server; `replay` forces `UNAVAILABLE("replay")`; the `inference.` prefix refused agent-side; one contributor per id; `BaseFacts.caps(Supplier)`; `agentHash` (the agent's matrix after step 0b) and `contributed` in the `capabilities` record (§8.5) | widen `ResolverContributor.capabilities` to `Capability`; hard-code `inference.vision` in the executor; keep "missing"; a re-merge after the service is built | the method today carries states only and is instantiated never in `replay` (D72); the design's "guard or require a model without a new mechanism" needs this one small mechanism; `agentHash` keeps the "one matrix per target" check on the agent's matrix (D130) while the goldens stay normalised; the fact root reads the agent directly today, so the supplier is the seam; a context without the pack cannot answer the allow-list reason |
| D327 | `inference.vision` states: `OK(strategy = tier)` when the host declares `vision-session`, the gateway is not `none`, `images: true` and `swing.capture.screen` is `OK`; else `UNAVAILABLE` with the first failing reason; contributed by the vision module alone (§8.5) | `OK` whenever the module is present; gate on `swing.capture.paint`; `InferenceContributor` keeps answering the id | the capability says a vision loop can act in this run, which needs all four; the loop's capture is the `SCREEN` composite; two contributors answering one id is a conflict the merge refuses |
| D328 | `Status.SKIPPED`, decided right after `run-start` before the agent is contacted, JUnit `<skipped>` whatever `degradedAs` says, `Assumptions.abort`, the CLI's `skipped` count and exit 0 (§8.6) | `DEGRADED` with a skipped line; `ERROR / PRECONDITION_FAILED`; a `skippedAs` option | `DEGRADED` means every evaluated expectation held, which is false when nothing ran, and `--degraded-as failure` would redden a designed skip; an `ERROR` is an environment failure it is not; B16's "neither a failure nor a pass" needs its own word |
| D329 | Baselines and anchors live in the bindings file (version 2, `images`, with the capture's `carets`, `focus` and nested `rendering`, `file` informative and derived) with masked PNGs beside it under `<scenarioId>.images/`, reviewed code in the threat model's sense; the bind lifecycle of D355 makes the binder read, approve and write them (§8.7) | a separate `baselines/` tree with a manifest; anchors inline in the plan; `file` as a path to open | they are recorded answers reviewed per environment, which is what a bindings file is; a plan holds no environment data; the reader of version 1 is kept and a file without images stays version 1; a stored path a planted file could point anywhere is L7's subject |
| D330 | A baseline is keyed by the target's `renderKey` (`swing.rendering`, the keyed set of D350), not by the environment's fingerprint, which is unchanged (§3.8, §8.7) | the fingerprint carries the rendering inputs for scenarios with images | BS11: the JDK line changes 0.30 % of the desk's pixels, so a fingerprint with rendering inputs splits every environment into one per target Java and every such scenario's bindings file per leg; a key per baseline keeps one bindings file per environment, holds one PNG per rendering (Playwright's practice), and leaves every other scenario's bindings untouched by a font or JDK change (vision §Risk3) |
| D331 | The journal step's optional `provenance` object with the keys of §9.2, handed in by the acting call (D351); cost stays in the ledger by `requestId`; design §3.4's "tokens, cost" wording is amended (§9.2) | tokens and cost copied into the journal; provenance in `Origin.note` | P5 §12.3 as built and D80: one source per fact; the ledger entry is found by the request id; `note` is text |
| D332 | `SessionOptions.robot` and `RunOptions.robot` as the controller half of the double opt-in, set by a person's flag only (`--allow-robot` on `run`, `session`, `mcp` and the raw `call`, which otherwise refuses `via: ROBOT` and `mode: ROBOT` in its arguments since it bypasses `ExecutorSession`), enforced by `ExecutorSession` before a call is sent (origin-free for the flag; `mode: ROBOT` a person's whatever the flag, D341) and by `PlanExecutor` before `checkRequires` (§9.2, §9.3) | the capability gate alone; a per-step argument; `requires` first; `call` left outside the opt-in | the agent option is the host's consent, not the session's; a flag a model can set is not a person's choice (threat model §5); a person's flag is checked before the target is asked, so a CI run shows the flag's message and not a capability's |
| D333 | The CLI keeps `ask` as the one interactive form; the tier is the session's option; `--play` runs a scenario (§9.3) | a separate `act <intent>` command and `skillroy_session_act` | one entry per host (B15); a second command would duplicate `ask` with a flag |
| D334 | `threat-model.md` is extended now (§2 a row and two cells, §3 the baselines, §5 a paragraph, §6 four rows, §7 a line, §8 lines) and completed by the build and the delta review (§11, §15) | wait for the review | the review cites the model (P6 §12.3); the judgement comes first |
| D335 | `web.pointer`'s actions are `MOVE`, `PRESS`, `RELEASE`, `CLICK`, `DOUBLE_CLICK`, `WHEEL`, no key input; `CLICK` is one call; an AWT-only modal check; a hidden browser served; the CDP path sends raw `Input.dispatchMouseEvent` on its own session and takes viewport points only (§4.3, §4.7) | `CLICK` only; key input under `INPUT`; Playwright's `page.mouse()`; no modal check | BS8 proved the three events; `web.dom.fill` types; `page.mouse()` hides actionability semantics and differs from the agent's parameters (D48); a page under a modal dialog is one no person can reach |
| D336 | JxBrowser input behind descriptor roles under `for: "INPUT"`, strategy "input unverified"; the web module is a `SurfaceSource` from its index; a Swing point becomes a viewport point in two agent calls (§4.5, §4.8) | JCEF only; a controller-side join of refs; one step through a cross-module SPI | the mechanics are testable against the fake and a licence holder's run settles the library (the existing wall); the index knows the browser a blank panel belongs to and the controller does not; the two-step form needs nothing cross-module beyond the one SPI |
| D337 | The sample: a number pad on the desk, `LendingRegions`, `LendingCaptureMask` (the field and the pad while masked), `describe`, the keypad legend, module 0.2.0, pack 0.6.0 with gap 49's argument, five scenarios (three of them seed-A scenarios without a bindings file, `keypad-image` on both seeds with one), one regeneration of plans, bindings, goldens and session recordings, the recordings by VS with its pack bump after VM's first commit (D342's vocabulary sentence) landed (§12) | a shelf map; a toy outside the desk; leave the desk and skip `RegionResolver` on the sample | the research's own example and the one component with several meanings the desk lacks; a toy would not meet "on the sample"; the recordings change because the pack version stands in every session's system text and gap 49 changes the vocabulary (not because of the desk's tree: `HeadlessDesk` has none), which the scripted stand-in route regenerates without a key; D342's vocabulary sentence is in every recording's fingerprint too, so VM lands it first and the recordings are regenerated exactly once; staff 100 and `basic-loan`'s fixtures exist in seed A only, and a fixture credential slot needs no bindings file |
| D338 | The security delta review's scope, lenses (L7 Windows and file-system trust, as P6 §12.3 has it), numbering from S101, the no-acceptance rule for medium and above, the owner's signature as B31 (§15) | a full review of the tree; no review; L7 as "the client" | the release checklist's item 2 asks for a delta; B28 covers the rest; a new boundary needs the lenses that see it, and the new writers in the source tree are exactly L7's subject |
| D339 | The hosts' options: `session --vision/--vision-model/--vision-tools/--allow-robot/--play`, `run --allow-robot`, `call --allow-robot`, `mcp --vision/--allow-robot`, `caps` with `source`, the JUnit property `skillroy.robot` with its warning (§9.3, §9.4) | settings-file keys for the tier; a scenario key for Robot; `call` without a flag | a run-level switch is an option, never a scenario key (D127); the tier is a session's choice, not an installation's; a property can be committed, so the glue says so; the raw `call` bypasses `ExecutorSession` and `CallPolicy`, so its own flag is the person's second half there |
| D340 | The CI image stays WM-less; the Robot checks run WM-less with the agent option on a target started for them (a `LendingTarget.Mode`), passing `requireFocus: false`; no openbox image joins the matrix (§13.2) | an openbox leg | BS9: Robot pointer input lands WM-less and routed input needs no window manager; keys through Robot are not built (D345); a WM-less frame may hold no focus (BS9 K7), so the checks say so explicitly; the derived image's recipe stays under `spikes/BS9/docker/` for a later keyboard tier |
| D341 | **A capture of the glass is a person's evidence only.** `swing.capture {mode: ROBOT}` is refused for every origin but `PERSON` by `ExecutorSession` and for every model's or client's call by `CallPolicy.arguments` whatever the flags; the V3 `capture` tool has no mode and the loop observes through a redacted `PAINT` composite in every tier ("the model sees `PAINT`, Robot acts"); a person's `ROBOT` capture blanks every pixel outside the JVM's windows' screen rectangles, says `glass: true`, is never written to `captures/` and never served over MCP; design §3.10's V3 row is amended (§3.4, §6.2, §9.1, §11, §16.1) | the V3 tool `capture {mode: ROBOT}` "to look at the glass"; a glass check in the agent (a `ROBOT` raster compared with a root-pane `PAINT` of each window, the differing regions blanked) | a `ROBOT` capture holds other processes' pixels and the desktop (the bands of a `SCREEN` union, a covering window), which no mask can name, so under V3 and `images: true` a model would have received what §11 rule 1 promises it never sees; BS9 showed a root-pane `PAINT` pixel-identical to the glass when nothing covers the target, so the model loses nothing it may see; the glass check is more code than Phase 7 needs and BS12 never ran V3 (§18 Q35) |
| D342 | **No credential is ever a model's or a client's argument.** `ExecutorSession.execute` (both forms) refuses, for every origin but `PERSON`, a step whose arguments name a secret field of a credential join (`$<slot>.<key>`) and a step whose compiled calls would reference one (a pack verb given a credential-joined slot), before anything is journaled or sent; `PlanPolicy.refusals` refuses a client scenario's base verb or raw `call` that names a credential slot's field its `fixture` does not provide (`skillroy_scenario_run`, the planner's server); `secretsInFlight()` is true from a credential's join until a person's step consumed it and, while it is, every `type`, printable `press` and raw `swing.type`/`swing.key` of a `MODEL` or `CLIENT` origin is refused; a `type` whose text holds a known secret value is refused by the facade and the recorder; sign-on runs through `signOn` by the person or the player before the loop; the vocabulary names no secret field (§6.5, §6.8, §9.2, §11). **(Phase 7 security fix, S101, S127, built 2026-10-11, SH)** The joined slot as a whole (`$<slot>`, in every spelling the substitution reads) is a secret reference (A34) on every route and in the plan form; a non-person's session step resolves its call arguments against public values; while a credential is in flight every call of a non-person that changes the target is refused, not only typing (rule 2 widened) | the recorder's masked-field rule alone; a per-type `confirm`; the mask on the way out only | a model's `type {text: "$assistant.password"}` into an unmasked field (the desk's entry field outside the `PASSWORD` state) would render the credential, and the next capture would carry it to the provider as pixels, which no value mask can reach; the session's vocabulary told the model the reference form; screen text can steer it (vision §Risk7), and it works in the default tier; the mechanisms at the executor, the facade, the recorder and `PlanPolicy` keep every route (the session's verb and raw tool, an MCP client's raw tool and scenario run, the planner) under one rule (D244): a client's scenario run goes through `PlanExecutor` with the operator's credentials and met none of the session-side mechanisms, so the plan form closes it; a model's `signOn` with a person's credential is refused rather than allowed to consume the in-flight flag, since only a person's step clears it and the person is at hand; a pack verb's own join in a client's committed scenario stays what Phase 5 allowed (the reviewed verb puts the credential where the pack's author said) |
| D343 | **A Robot press is always paired with its release.** `swing.pointer {via: ROBOT}` refuses `PRESS` and `RELEASE` (`INVALID_PARAMS`, "a Robot press is never left held: use CLICK or DRAG"); a press and its release run in one worker task with no wait between them; a `finally` releases every button the call pressed before anything else, on a timeout or an interruption too; the pointer is never restored while a button is down; the toolset's `left_mouse_down`/`left_mouse_up` are answered as text in V3 and `pointer {action: PRESS}` with `via: ROBOT` is a compile error (§3.4, §6.3, §8.1) | the halves through Robot with the release on the next call or at the turn's end; the pointer not restored at all | a held Robot press followed by the pointer restore is an OS-level drag from inside the target to wherever the person left the pointer, across whatever windows lie between, left held across the model's next request and beyond a turn that ends at a limit; the web side already made `CLICK` one call for the same reason (§4.3); a cut-short `DRAG` or `CLICK` must release too, which only a `finally` guarantees |
| D344 | **V3 acts only on a focused target, checked at the press.** `swing.pointer {via: ROBOT}` gains `requireFocus` (default **true**): the pre-check's EDT read requires the target JVM to hold a focused window and the worker moves at once, else `INPUT_REJECTED("not focused")` and nothing moves; every covering window of the JVM refuses the point (ranked or not); a `received` outside `hit.target` is `fired: false, "received elsewhere"`; the WM-less `RobotChecks` pass `false` explicitly; the V3 loop stops the turn (`endTurn: QUESTION`) when the JVM holds no focused window and never clicks to activate a window it cannot know to be uncovered; a Robot click into a field is allowed only while the JVM holds the focused window and another component owns the focus (§3.4) | the check from the last capture on the controller (one model call old); `requireFocus` default false with the loop passing true; a Robot click to re-activate the window after a dialog | a model call is 1-2 s and up to 12 s (BS12), in which the person switches windows or a notification appears, and Robot presses whatever is on top; BS9's own strict mode read the focus on the EDT before every press and refused to click an inactive application (K6 on the Mac "not run"); the executor's `run --allow-robot` path needs the rule as much as the loop, so the default is the safe one; Robot goes to the real top, so an unranked covering window is a wrong press there, not a reported guess as on the routed path; the residual window of milliseconds is A15's and `SECURITY.md`'s |
| D345 | Robot key input is not built in Phase 7: V3's keyboard is the routed `swing.type {mode: SET}` by locator and `swing.key`, after the focus rule of D344; `swing.type {mode: ROBOT}` stays refused "not in this version"; gap 77 (§1, §3.4, §16.2) | `swing.type {mode: ROBOT}` and `swing.key {via: ROBOT}` with X-keysym translation and the re-activation after a dialog | BS9's key conditions: keys land in the AWT focus owner if and only if the target JVM has a focused window, and on a WM-less display nothing restores a frame's focus after a modal dialog closes; the routed `SET` needs no OS focus and types what the recorded step will type; the OS key path is a later tier if a target needs it proven (§18 Q6) |
| D346 | **The agent never draws marks.** `annotateRefs` answers the `marks` geometry and labels (with `region` and `row`); the controller's `Marks` draws the boxes on its own copy of the raster before the image goes into the request; `unmarkedHash` is dropped; `captures/<hash>.png` is the raster as captured and `hash` names the content (§3.1, §6.2, §9.6) | the agent returns both rasters (`base64` and `markedBase64`); the agent draws and the controller keeps the hash only | under V1 the controller held only the marked raster, so it could not write the unmarked file the provenance named, and a second paint may differ by a caret blink; one paint, one raster on the wire and one hash is the smaller form, and nothing of the model's view needs agent pixels |
| D347 | **Robot is never a per-call choice of a model or a client.** `CallPolicy.arguments(primitive, args, robotAllowed)` refuses `via: ROBOT` for a `MODEL` or `CLIENT` origin on every route but the facade's, where `robotAllowed` (the host's flag) lets the vision module's own V3 steps set it; `mode: ROBOT` is refused on every route whatever the flag; `--allow-robot` on `mcp` enables V3 inside `skillroy_session_ask` and nothing else; the raw primitive paths (`SessionService.act`'s primitive branch, `ToolCalls.primitive`) call `arguments` and refuse, never strip (§9.1, §9.4) | `via: ROBOT` and `mode: ROBOT` allowed for a model or a client "when `robotAllowed`"; stripping the keys as `confirm` is stripped | under `mcp --allow-robot` a client would otherwise hold Robot input and glass captures with its own choice of point and moment, which the threat model's §6 row says can never happen; the four new refusals had no call site on either host's raw-primitive path, so built as first written they would not have held; stripping would silently downgrade a `via: ROBOT` call to a `ROUTED` one that reports `via: ROUTED` |
| D348 | **The host feature reaches the merge through `SessionOptions.host`** (`Set<String>`, the fourteenth component, `withHost`), set before `open` by the player's host, `SessionCommand` and `McpCommand` under the conditions that mirror `applies`, carrying `vision-tier:<v1|v2|v3>` beside `vision-session` so that `VisionCapabilities` can answer the tier as the strategy; `ExecutorSession.capabilities()` answers the merged matrix; `capabilities.json` is written once (§8.5, §9.2) | `ExecutorSession.remergeCapabilities(host)` after the service kept its tools; the feature set by the service | the service is constructed on an already open session and `open` reads, merges and archives the matrix, so at the one merge point the feature was always empty and `inference.vision` read `UNAVAILABLE("no vision session")` in every session; a re-merge would rewrite `capabilities.json` and journal a second matrix for one run |
| D349 | **One rule for pixels and text.** A component the built-in rule or a `CaptureMask` names has no `text` and no `value` in any output of the Swing module (the snapshot, `swing.find`, the dialogs' texts, the hit-test's nodes, a mark's label), carries `masked: true`, gets no `{text, role}` candidate and no mark; the role is unchanged (§3.6). **(Phase 7 security fix, S106, S122, built 2026-10-11, SG)** The rule also withholds a masked label's or button's name when it is the component's own text (D452) and the text under a mask's `regions` (D454), and a masked browser is masked on every route (D471) | pixels masked, text unchanged (the built text walker); the role changed to `password` | the text walker withheld content for `JPasswordField` only, so a pack-masked `JTextField`'s secret would have reached a model as text beside its grey pixels (the snapshot, the marks list, the hit-test's nodes, an exported `{text, role}` locator); a pack author who writes a `CaptureMask` expects the content hidden; `Roles.ALL` is closed and a role change would alter every committed snapshot fixture |
| D350 | **The render key hashes what renders**: `java.specification.version`, the look and feel, the effective antialiasing (`desktopHints` as sorted `key=value` strings), the screen scale and the probe render's hash; not the vendor, the full runtime version, the OS name, version or architecture, and no font names; the probe render has a table header and an antialiased shape; `rendering` keeps the rest for the reviewer, nested (§3.8, §8.7) | the vendor, the full version, the OS and the architecture in the key; `fonts` from `Font.getFamily()`; the hints' text as `toString()` | BS11: the architecture and a JDK 8 patch build (1.8.0_504 against 1.8.0_492) changed no pixel while the JDK line did, so a key with them would turn `keypad-image` `REBIND_NEEDED` on the amd64 leg against identical rasters, and `os.version` inside a container is the Docker host's kernel release, which would turn every committed baseline stale with a host update; the amd64 leg's JDK 8 is another vendor's build with identical pixels; `Font.getFamily()` of a logical font answers the logical name (four constants), and the probe, which paints text, is the font tripwire (BS11: a font package changed 3.3-4.7 % of the desk's pixels); `RenderingHints.Key` hashes by identity, so the map's text order can change between starts |
| D351 | **Provenance is handed in by the acting call**: `ExecutorSession.execute(verb, args, origin, provenance)` and `execute(PlannedCall, origin, provenance)`; the three-argument forms pass null; the private `run` writes it into the step's `StepEntry`; `SessionTools.Outcome` carries no provenance (§9.2) | the service writing the provenance onto the journal step after `act` returned; `Outcome.provenance` kept as informational | both `execute` forms end in a private `finish` that appends the `StepEntry` to an append-only journal before they return, so nothing can be written onto the entry afterwards; the provenance is known before the act, and VC lands the signature first (the seam crosses two parallel packages) |
| D352 | **Every event task of a routed sequence checks before it dispatches**: the call's `cancelled` flag (set when the deadline passes; nothing of a failed call runs later, the built `Clicks` pattern) and `Windows.blockingModal` again (`MODAL_OPENED`); a dispatched press is balanced by a disarming `MOUSE_DRAGGED` to the window point (-1, -1) and a release there, which fires no `ActionEvent` (every Swing button model disarms on `MOUSE_EXITED` and the desk's keys check `contains` on the release); the result reports `dispatched: n of m` and `balanced` (§3.3) | "the events may still run later"; the modal check as a one-time pre-check; a bare balancing release; no balance at all (a model left pressed) | a release or a drag that runs after the call reported failure acts on a changed screen (a model that saw the failure may already have retried: a double action), and the built rule ("no click was delivered after its call had failed", `HostSafetyDisplayTest`) and §13.2 item 11 forbid it; a press handler that shows an application-modal dialog runs the dialog's secondary loop inside the press's own task, and that loop would pump the remaining non-posted events into the now-blocked window, which AWT would drop for a person (BS10 condition 1); Swing acts on the release, not on `MOUSE_CLICKED` (`BasicButtonListener.mouseReleased` fires while armed, the desk's `IconKey` and `PromptButton` act on a release inside their bounds), so a bare balancing release would have delivered the very late action, and a press left pressed would hold the button model until the person's next click |
| D353 | **The seam's context is a facade, and the service enforces what the seam relies on**: `SessionToolContext` carries `SessionActions` (no `ExecutorSession`), which fixes the origin, runs `CallPolicy.refusals` with the host's `robotAllowed`, refuses `read` of anything `never` names and `data.query`, and meters and ledgers `complete` through `SessionService.visionCall` (reachable by the player too); `atTurnStart` and `Outcome.endTurn (QUESTION | LIMIT)`; the service counts `vision.maxActions`/`maxTurns`, ends the turn `LIMIT` and closes the tools; a tool's instructions and provider tools pass the mask; a read-only or verb-less policy gets reads only (§6.3, §9.1, §10) | the raw `ExecutorSession` in the context with `CallPolicy` left to the tool; a gateway opened by the module; `act` told which call is the batch's last; a false `applies` for `v3` without the flag | a tool holding the raw session could pick any origin and bypass `CallPolicy` and `never` (`data.query` is neither mutating nor script); a module that opened its own gateway would bypass the one session meter (D157) and the ledger the zero-LLM assertion reads, and the seam gave a tool no way to end a turn or to make the nested call `skillroy_look` and the player's `aiExpect` need; the service builds the batch's results after the per-call loop, so a tool cannot know the last call and the capture always goes after the batch; a false `applies` is the silent downgrade the same sentence forbade, so the host validates `v3` first |
| D354 | **`PlanExecutor` skips every `VisualCheck` unconditionally** ("no visual evaluator in this host"), the capability's state being only the reason text; a contributed id that two contributors answer is `ERROR / ENVIRONMENT` naming both; `InferenceContributor` stops answering `inference.vision`; `CapabilityContext` carries the pack (§8.4, §8.5) | the skip as a consequence of the capability's state; two contributors tolerated | a `CapabilityContributor` loaded from a pack jar could answer `inference.vision: OK` in `live` and the executor would then meet a check it cannot evaluate with no stated outcome; `InferenceContributor` already answers the id today (`InferenceContributorTest` pins it), so without the rule two contributors would answer it once the module is present; the allow-list reason needs the pack |
| D355 | **The bind lifecycle learns images**: `Plan.needsBindings()` is true for a plan with `images`; a baseline is approved at its step through `Approver.reviewImage(ImageApproval)` (a default method delegating to `review`, so the lambda constants keep their stance; the CLI's terminal approver asks with the candidate's path and size) and the bindings file is rewritten after the steps through the binder's `write` and `requireNoCredential`; an anchor is never approved (recorded by a session; `REBIND_NEEDED(IMAGE)` in every mode without an entry for the key); `Binder.check`'s label check runs in `replay` only; `Binder.finish` and the re-resolve carry an existing `images` section forward; the export writes its anchors' `images` into its own `BindingsFile` and hands the PNGs out (§6.6, §8.7) | one batched image approval at the run's end; the approval at bind time; `ExportResult.images()` as the only carrier of an anchor | `needsBindings()` was true for an oracle or a query slot only, so `keypad-image`'s committed entries would never have been read, checked or written; `Approver.review` is called once in `Binder.finish`, at bind time before the start mark, while a baseline is captured at its step after it; `Binder.file(env)` rebuilds the file from the engine's resolutions and every slot approval or re-resolve would have dropped the `images`; a version-2 entry needs the render key, the masks, the window and the anchor origin, which bytes alone cannot carry; a batched approval at the end would approve pixels the person did not see at the step |
| D356 | **The exported comment carries no hash, handle or mark**; the report does; `captures/<hash>.png` is the raster as captured, named by its content hash; the committed vision export is generated by §13.2 item 8 on one named leg (§6.6, §9.6, §12.4) | the hash and the handle in the comment; the comparison modulo comments; the export generated by `SessionExportTest` | the capture's hash differs between the Java lines (BS11: the JDK line changes the desk's pixels) and between two runs on one Java (the caret's blink phase), and a handle is a per-start counter, so exit criterion 1's byte-for-byte comparison on both Javas could not have held; `HeadlessDesk` answers no `swing.*` method and cannot produce a capture |
| D357 | **An anchored step is `Plan.Call.guard {label}`**, the anchor taken at record time with the guard's own call (`swing.capture {scope: REGION}` of the window's root pane through `read`), written as `captures/<hash>.png`, `swing.rendering` read once per session, the window's size and insets kept, all in the provenance's `anchor` object with the crop's `masks`, `carets` and `focus`; the executor takes the guard's rectangle from the bindings entry of the current render key (§6.5, §6.7, §8.2) | the crop cut from the `SCREEN` composite at export time; the guard as a separate `swing.capture` call and check compiled into the plan with the rectangle | the controller never holds the composite's unmarked pixels in the form a crop needs, a crop of a frame's `SCREEN` paint may differ from a root-pane `REGION` paint, and the entry's render key, rendering, window and origin were in no provenance; `Plan.Call` had nothing that marked a call as guarded and the guard's rectangle is environment data that must stay out of the plan |
| D358 | **The recorder refuses a masked target for every input**: no `click`, `pointer`, `type`, `press` or toolset `key` at a component the hit-test reports `masked`; the threat model's sentence is scoped to the vision module's tools; the sample's `LendingCaptureMask` names the number pad while the entry field is masked (§6.5, §11, §12.2) | the `type` refusal alone; an agent-side rule for every typing primitive | the `key` tool spelled a secret digit by digit (`1`, `0`, `0`, `Return`) past a rule that covered `type` only, and region clicks on the sample's own number pad typed it through the pad; a model with `allowBasePrimitives`, an MCP client under `--mutating` and a pack verb never pass through the recorder, so the threat model's "never typed into on a model's behalf" is scoped to what the mechanism covers, with the credential rule (D342) and `CallPolicy` on the raw paths beside it |

### Decisions taken by the build, D359-D430

The four packages took 72 decisions while they built Phase 7 on 2026-10-10 and recorded them in their reports as a tag
and a number (VC 1 to 24, VS 1 to 21, VW 1 to 16, VM 1 to 11, each package's numbers running from 1 without a gap). The
orchestrator numbered them at integration on 2026-10-10, in the order VC, VS, VW, VM and by that number within a tag: **VC
D359-D382, VS D383-D403, VW D404-D419, VM D420-D430**; every "As built" note of this contract and every Javadoc or code
comment that cited a decision by its tag now cites the number, and the last column keeps the tag and the package's own
number for the reader of the reports. D401-D403 are VS's three decisions of the regeneration step 2b. The section in
parentheses after each decision says where it is built. Together they are decision **B30** (`../DECISIONS.md`, provisional while the build
ran on them, as B29 was, and confirmed by the owner on 2026-10-11); the decisions of the code review's fix wave and of the security delta review's fixes
(§15) are D431-D486, in the table after this one, and join B30. No decision of B1-B28 or of B29 is reversed by any of
them. Where a decision differs from the contract's own sentence, the passage carries an "As built" note: D371 (the visual check's question,
§12.4), D383 and D384 (§3.6, §3.1), D403 (§12.4), D404 (a field added to P4 §4.2's `web.browsers`) and D420-D421 (the
facade and the tool-set interface of §9.1 gain a few methods).

| # | Decision | Alternatives rejected | Reason | Tag |
|---|---|---|---|---|
| D359 | **The session's `expectImage` takes its baselines from the caller**: `ExecutorSession.expectImage(step, origin, baselines)` and the static `ExecutorSession.baselines(root, env, scenarioId)`; the contract's two-argument form holds none (live: the check is recorded and skipped; an anchor: `REBIND_NEEDED`) (§9.2, §6.8) | a baselines setter on the session; the session reading a bindings file itself | a session has neither a scenario nor a bindings file; the player (§6.8) knows both and hands them in | VC 1 |
| D360 | **`expectImage(Plan.Step)` journals the verb's arguments reconstructed from the compiled step** (§9.2) | journal no arguments | the journal and the export need the step as written | VC 2 |
| D361 | **A guarded `pointer` executed with a provenance whose `anchor` carries the guard's label is not compared** (§9.2) | compare against the just-taken crop; refuse | the recorder took the anchor with the guard's own call an instant before; without this rule the recording of an anchored step was always `REBIND_NEEDED` | VC 3 |
| D362 | **No bind-time approval for a plan whose bindings hold only images** (§8.7) (amended by the Phase 7 security fix, S115, built 2026-10-11, SJ: nor a bind-time write; after the steps a change other than an approved baseline is written only on the approver's yes, §8.7, D482) | ask the approver with empty resolutions | `REJECT_ALL` would refuse the run before its baseline steps; there is nothing to approve at bind time, each baseline being approved at its step | VC 4 |
| D363 | **`expectImage` writes `scope` only for `REGION`**; otherwise the agent's default applies (§8.1) | `WINDOW` or `COMPONENT` decided at compile | the compiler cannot tell what a locator names | VC 5 |
| D364 | **`pointer` always writes `action` (default `CLICK`)**; `strict` is checked by the compiler and not sent (§8.1) | omit the default; send `strict` | the primitive requires `action` and has no fall-back | VC 6 |
| D365 | **The `image` record's results add `approved` and `rejected`**, and a guard's record carries `guard: true` (§8.4) | reuse `held` and `missing` | approve's outcome is neither | VC 7 |
| D366 | **`run --json` gives each result a `skippedReason`** (§8.6) | `skipped: true` | a result's `skipped` already means P3 §9's list of skipped cross-checks | VC 8 |
| D367 | **A contributor that throws is refused `ERROR` / `ENVIRONMENT`, naming it** (§8.5) | skip it | a pure function that throws is a bug, as is a contributor that answers an id without the prefix | VC 9 |
| D368 | **A render key in a bindings file is `[A-Za-z0-9]{8,64}`** (§8.7) | exactly 16 lower-case hex digits | it names a file, so the form is bounded; VS fixes the exact form the agent writes (D350) | VC 10 |
| D369 | **"A credential is in flight" is set by any secret-joining resolve, and typing is `swing.type`, or `swing.key` outside a fixed set of non-printable keys, on the compiled calls (pack verbs included)** (§9.2) | base verbs only; a printable whitelist | deny by default; a pack verb that types is typing | VC 11 |
| D370 | **`keypad-image`'s plan was committed in step 1**; only its bindings, baselines and replay waited behind `Phase7Pending`; `sign-on-by-number-pad` ends with `signOff` (§12.4) | keep it out of `PlansUpToDateTest` | it compiles without VS's desk; every committed scenario leaves the desk signed off | VC 12 |
| D371 | **`loan-table-visual-check`'s question reads "…one line for the scanned item, with a quantity of 2" and stands after the scans** (§12.4) | the contract's "two lines" after the deposit | false on the desk: two scans of one item are one line of quantity 2, and the loan is posted after the deposit | VC 13 |
| D372 | **`caps` merges in mode `live`, with no host features, when `--pack` or an inference option is given**; `--json` carries `source` (§9.3) | `replay`; merge only when both are given | it shows what a live run would see; the source is the point of the column | VC 14 |
| D373 | **The regeneration scripts**: `skillroy.plans.update` also covers `exported/plan`, and the new `skillroy.bindings.rehash` rewrites every committed bindings file's `planHash` from its YAML's compiled plan, and nothing else (§8.2, §12.5) | a standalone script | the update-flag pattern the module already uses | VC 15 |
| D374 | **`session-start.options` records `robot` only when true, and `host` and `contributed` only when not empty** (§9.2) | always | Phase 5's journals stay unchanged | VC 16 |
| D375 | **A `{by}` mask is resolved by `swing.find` and its bounds mapped through origin and scale; no single match is `FAIL` / `ASSERTION`** (§8.4) | `ERROR` / `ENVIRONMENT` | a step's NOT_FOUND is an assertion everywhere else | VC 17 |
| D376 | **`ImageSteps.Outcome.Kind.ERROR`** answers an unusable capture or baseline file and a target without `swing.rendering` (§8.7) | throw | one mapping in `ImageRuns` serves both hosts | VC 18 |
| D377 | **A session's skipped image check goes to the outcome's `degradedFidelity`** (§9.2) | a new field | the session has no skipped-expectations list | VC 19 |
| D378 | **The web host scenarios' `PlansUpToDateTest` pin (plan version 5 to 6) is edited**, one line outside VC's files (§12.5) | leave it red under `-Pjcef` | VC's regeneration made it stale | VC 20 |
| D379 | **Approve's second write of the bindings file also happens after a later step failed** (§8.7) | only after a passing run | each baseline was approved by a person at its step | VC 21 |
| D380 | **`CapabilityMatrix.agentOnly()`** beside `with` and `source` (§8.5) | none | the `capabilities` record's `agentHash` and the tests need the agent's part back | VC 22 |
| D381 | **`ImageEntry`, `Focus` and `Anchor` are nested in `BindingsFile`; `window` is optional**; a baseline's window comes from the capture (§8.7) | top-level types | they are the file's records | VC 23 |
| D382 | **`ExportResult.images` is a record component, and the export's `Report` gains `visionDerived` and `liveOnly`** (§6.6, §8.7) | side channels | one result, as the export's other parts | VC 24 |
| D383 | **A component inside a masked component is masked too** (no text, value or mark; `masked` in the hit-test) (§3.6). **(Phase 7 review fix wave, FA, 2026-10-10)** A capture whose paint root lies inside a masked component blanks the ancestor's rectangle too (D431) | only the named component (the contract's wording) | its pixels are inside the blanked rectangle; a pack that masks a container expects its content hidden; the safe side | VS 1 |
| D384 | **The heap bound refuses** (`INVALID_PARAMS`, `neededMb`, `freeMb`); only the 4096 cap lowers the scale (§3.1) | lower the scale under the heap bound too (§3.1's sentence) | §2.4 and §13.1 item 1 say refuse; a silently tiny image would mislead a model | VS 2 |
| D385 | **The root pane replaces the painted component for a `RootPaneContainer` that is a window**; a `JInternalFrame` keeps its title bar (§3.1) | every `RootPaneContainer` | the imitation decorations belong to a heavyweight window's peer; an internal frame's title bar is real paint | VS 3 |
| D386 | **`DOUBLE_CLICK` is two triples with counts 1 and 2; `CLICK` with `clickCount: n` is one triple of count n** (§3.3) | one triple of count 2 | BS10's native sequence; lists and tables see the first click too | VS 4 |
| D387 | **`dispatched.sent` counts dispatches that returned; on `EDT_BUSY`, `balanced: true` is a promise** kept by the next task the EDT runs (§3.3) | count at the start of a dispatch; report the balance only once it is done | it matches §13.2 item 11's "1 of 4"; a call cannot wait for a blocked EDT | VS 5 |
| D388 | **The hit-test's target form answers the named component as `target` with `targetKind: "TARGET"`** (§3.2) | `MOUSE_TARGET` or `DEEPEST` at the component's centre | the recorder's focus-owner description must be about that component | VS 6 |
| D389 | **Carets are reported for every focused text component without a selection, whatever the blink phase, masked or not**, widened by 2 px either side (§3.1) | only while the caret is drawn (`Caret.isVisible`) | the rectangle a comparison blanks must not depend on the blink phase; §13.2 item 1 wants the masked field's caret | VS 7 |
| D390 | **Marks**: none for the children of `JComboBox` and `JScrollBar`; one per tab; regions replace the component's mark; labels read `role 'name': text`, at most 120 characters; covered `SCREEN` marks are left out (§3.1) | marks for every button, arrow buttons included | the marks list a model reads stays the controls a person would use | VS 8 |
| D391 | **The `SCREEN` canvas outside the windows' content is RGB 64, 64, 64** (§3.1) | the mask grey; white | distinct from a mask, deterministic | VS 9 |
| D392 | **The Robot press observation waits at most 3 s within the call's remaining time** (§3.4) | the whole remaining time (up to the 30 s default) | a dropped press is answered in seconds; the call's budget still bounds it | VS 10 |
| D393 | **The delivery probe moves one pixel aside first when the pointer already sits on the point, accepts `MOUSE_ENTERED`, and restores the pointer when it fails** (§3.4) | none | a move to the current position produces no native event | VS 11 |
| D394 | **`LendableItem.inductionRequired`: null means either kind**; a separate `LENDABLE_ITEM_BY_INDUCTION_SQL` exists only when the argument is named; the two-argument constructor stays (§12.3) | null means false (as `ItemsTriggering`); one SQL with a nullable parameter | committed scenarios keep their meaning and their bindings' evidence (the SQL text) unchanged | VS 12 |
| D395 | **The holder of the capture geometry a `captureId` names is `RecentCaptures`** (32 geometries for ten minutes, the window held weakly) (§3.1) | none | the repository's vocabulary rule for names | VS 13 |
| D396 | **A glass capture ignores `scale` (it reports 1), refuses a rectangle over 4096 a side, carries `glassNote` and reports `surfaces`** (§3.4) | none | user-space size as specified; the bound of §2.4 | VS 14 |
| D397 | **The keypad row stays 64 px high (wrapped in `NORTH`) beside the 160 px number pad** (§12.1) | let the keys stretch | §12.1: "about 160 px beside the 64 px keypad row" | VS 15 |
| D398 | **`VisionSwingChecks` extends `LoadModeChecks` (orders 52-58) instead of launching targets of its own; `RobotChecks` launches its own** (§13.2) | a separate class per mode with its own launch | items 1-5 run on the same target per mode with no extra launches; every check recovers the desk in a `finally` | VS 16 |
| D399 | **`swing.capture` of a target in no window**: `origin` is relative to the topmost ancestor and there is no `window` (§3.1) | refuse | the headless tests' trees; production targets are in windows | VS 17 |
| D400 | **The routed `received` counts the window itself only when it listens**; the call recognises its own events by a unique `when` (§3.3) | identity of event objects | retargeted events are new objects; the window gets the event only when nothing lightweight takes it | VS 18 |
| D401 | **The rehash of committed bindings files carries the compiled plan's `packVersion` too** (one hunk in VC's `BindingsUpToDateTest`); the field is information and neither hash covers it (§12.5, §13.2) | none recorded in the package's report | the rehash as VC wrote it rewrote `planHash` only, so `BindingsUpToDateTest` and `PackOnRealSchemaTest` (approve's bytes) still failed on the files' `packVersion` 0.5.0 | VS 19 |
| D402 | **`Runs.assertPassedWithoutSleeping` counts an `image` record as a check, and an images-only scenario needs no wait** (a VC helper) (§13.2) | none recorded in the package's report | the ten resolution classes failed one assertion each: an images-only scenario has no `expect` record and no wait, while its replay was `PASS` with both images `held` | VS 20 |
| D403 | **`keypad-frame` masks three locators**, `{by: {id: entry}}`, `{by: {id: totalsLine}}` and `{by: {name: Printer, role: scrollpane}}`, instead of §12.4's single mask; its baselines were approved afresh on both Javas and seeds (§12.4) | running `keypad-image` only in `ResolutionChecks`; a `maxDiffPixels` budget; a recovery that clears the slips first | the baselines are approved on a fresh desk but the glue replays after the earlier scenarios, where the totals line shows the last loan and the printer's scroll pane holds slips (11,724 differing pixels); with the three masks the frame checks the layout and the static parts on any signed-off desk and still exercises the locator form of `mask`; the first alternative would leave the glue unable to run every committed scenario, the second would hide real layout drift of that size, and the third needs a desk action that does not exist and makes the scenario depend on what ran before | VS 21 |
| D404 | **The CDP path hands a browser the agent reports blocked by a modal dialog to the agent**: before every `web.pointer` it re-reads the agent's `web.browsers`, which gains the optional entry field `blockedBy: {dialog, title}` (an additive change of P4 §4.2); the agent answers `MODAL_OPEN` unless `force` (§4.3, §4.7) | no modal check on the CDP path; never serve `web.pointer` on the CDP path; a new agent primitive for the modal state | the contract's modal rule (W5, D335) would otherwise hold on one path only: a model's or a client's pointer would act past a modal dialog whenever the run uses CDP; the agent already owns the check; an additive optional field changes no shape for an unblocked browser; the error shapes stay identical because the agent produces them | VW 1 |
| D405 | **The script library's version rule**: a slot that already holds a library is never replaced; the functions added in version 2 are installed into an older slot once (non-enumerable, non-writable, non-configurable); `version` is raised where it is configurable (a version 1 slot keeps `"1"`); install answers `"upgraded"`; a slot that refuses is left and the call fails naming the function (§4.6) | replace the slot; a second version marker property | D300's reason stands; version 1 made `version` non-configurable, so raising it is impossible there, and the presence of the function is the check that matters | VW 2 |
| D406 | **Every pointer action starts with a `mouseMoved`**; `CLICK` with count n sends press/release pairs with counts 1..n; `DOUBLE_CLICK` is `CLICK` with n 2 (§4.3) | no move for `PRESS` and `RELEASE`; one pair with count n | a person's pointer arrives before it acts (hover, pointer-enter); Chromium raises `dblclick` from the second pair | VW 3 |
| D407 | **After a failure that follows a press, one release of that button (at most 1 s), then the failure** (§4.3) | leave the button held | a held button in a page outlives the call (the same reason as D343 and §4.3's one-call `CLICK`) | VW 4 |
| D408 | **`web.hitTest` rectangles are in the starting frame's viewport**; `iframe` names the innermost frame element; the `frame` parameter is the starting frame (§4.2) | rectangles in each frame's own viewport | §4's one coordinate space; a nested rectangle would be unusable otherwise | VW 5 |
| D409 | **One shared class, `protocol.web.WebInput`**, holds the parameters, the plan, the protocol parameters, the hit bounds, the child-frame rule and the result shapes of both paths (§4.3, §4.7) | each path with its own copy | D48 and D335 want byte identity of the two paths, and CLAUDE.md's rule "never duplicate" covers shared wire-side code | VW 6 |
| D410 | **Child frame by name, then URL, then the only child**: among the frame's children by the element's name (exactly one), else by URL (exactly one), else the only child; read over `Page.getFrameTree` on both paths (§4.2) | descend in the page (same origin only); give up at every iframe | `data:` frames are cross-origin; both paths read the same tree | VW 7 |
| D411 | **Hit-test locators are only those `find` answers with exactly the element**: `{role, name}` not for generic, none or presentation roles, empty names or names over 1000 characters; `{selector}` is a unique plain id, else the shortest `tag.class:nth-of-type(n)` path (8 steps) anchored at an ancestor's unique id; `unique` means at least one (§4.2) | report non-unique candidates | the recorder needs exact-match locators only | VW 8 |
| D412 | **`web.pointer`'s hit read**: `NOT_FOUND` refuses before anything is sent, any other failure of the read omits `hit`; without `EVALUATE` only negative coordinates are refused as outside (§4.3) | fail the pointer on any hit failure | a busy page should not block a person's pointer; a point outside must never act | VW 9 |
| D413 | **JxBrowser `INPUT` roles are named from the public 7.x API as best known** (`com.teamdev.jxbrowser.ui.Point`, `ui.MouseButton`, the `ui.event.Mouse{Pressed,Released,Moved,Wheel}` builders, setters by arity); modifier keys are refused; the strategy note "input unverified" appears only when the roles resolve (§4.5) | map modifiers by a guessed `KeyModifiers` type; always print the note | unverified anyway (wall, gap 41); refusing is honest; the note means nothing without the roles | VW 10 |
| D414 | **The modal heuristic is the Swing module's** (the dialog is not the browser's window, and the window is not owned by the dialog); with several blockers, the active one, else the last created (§4.3) | the contract's literal phrase; the first created | the contract says "the same heuristic as the Swing module's" | VW 11 |
| D415 | **The web module's `SurfaceSource` is registered at install and answered from the component recorded per index entry at rebuild**, closed browsers left out; the class that walks the parents is loaded at the first call (§4.8) | install it at readiness; ask the adapter's `component()` | "answered from what the module already holds"; install must request no AWT class (the recording-loader test caught a verification-time load) | VW 12 |
| D416 | **`web.browsers.blockedBy` is read per open browser with a component, by one bounded EDT read, while the call has time** (the field of D404) (§4.7) | always; never | bounded and cheap; an absent field keeps today's shape | VW 13 |
| D417 | **A `COMPONENT` point needs `EVALUATE`** (`innerWidth` read in the main frame); `INVALID_PARAMS` reasons `no component`, `no component size`, `viewport override`; the override flag is the module's own (§4.2) | read the adapter's `viewport()` | `viewport()` is null or stale on JCEF; the CDP path refuses component points anyway | VW 14 |
| D418 | **The web host has no capability goldens**; nothing was regenerated there (§4.4) | add goldens | the contract's "(if present)"; out of scope | VW 15 |
| D419 | **`WebPointerEvent` bounds**: coordinates 0..16384, click count 0..3, deltas within 16384; defaults per action; press and release need a button and a count of at least 1; a delta for `WHEEL` only; the shape test allows public static final constants (§4.1) | unbounded ints | 16384 is the largest viewport `web.resize` accepts; fail fast at the SPI | VW 16 |
| D420 | **`SessionActions` gains `outputDir()`, `screenLegend()` (masked) and `note(text)`** (§6.1, §9.1) | let the module reach the session; drop `skillroy_look`'s note and the captures | the facade the contract fixed could not reach where `captures/` goes, the pack's legend for a visual question, or the look's journal note | VM 1 |
| D421 | **`SessionTools.modelClass(ctx)`, a default method** (Sonnet) (§6.2, §9.1) | a service option | the contract's interface could not name the loop's model class per tool set | VM 2 |
| D422 | **V1's `<target-ui>` block carries the marks list alone** (and the image's size), not the text snapshot again (§6.2) | repeat the snapshot | the model has `skillroy_snapshot`, and BS12 found the marks list carries what the snapshot drops | VM 3 |
| D423 | **A hit-test on a `HEAVYWEIGHT` surface is recorded as an anchored step; on a `NONE` surface it is answered as text** (§6.4) | refuse both | §6.5's rule for `HEAVYWEIGHT` wins over §6.4's sentence | VM 4 |
| D424 | **The player narrows the tier only between V2 and V3**; a step's tier to or from V1 is a usage error, like a widening (§6.8) | allow any narrowing | the service fixes the tool vocabulary at its start, and V1's tools differ from V2's | VM 5 |
| D425 | **`ExecutorSession.fixture(slot, value, credential, origin)` binds a fixture slot as a plan binds one**: the value's references are substituted with public values, the slot is declared `fixture` with strategy `FIXTURE` and no bindings-file entry, and with a credential the secret is joined and in flight; a credential slot is bound for a person's origin only (`FIXTURE_CREDENTIAL_REFUSED` otherwise, before anything is joined or journaled); one `fixture` journal record; the export writes the slot back as `resolve: <slot>: {fixture: …}`; the player's default `FixtureBinder.SESSION` calls it as the person. First built as a `FixtureBinder` the player's host supplies, because `ExecutorSession` was VC's file; the method followed at the orchestrator's request (`5faacb4`) (§6.8, §9.2). **(Phase 7 security fix, S113, S139, built 2026-10-11, SH)** For every slot, `resolve` and `fixture` refuse for any origin but a person's a slot whose current binding a person made; the `resolve` record carries `origin`; the export marks a non-person's binding `REQUIRED` (D97) | editing VC's file during the build (the shared-file rule forbade it); rewriting fixture slots as queries | `ExecutorSession` could not bind a fixture slot, so a scenario with one (`vision-live-only`, `loan-table-visual-check`) could not be played; the origin rule keeps D342 whole: no credential is bound on a model's or a client's behalf | VM 6 |
| D426 | **A UI action counts toward `vision.maxActions` per facade `execute`** (an act that ran two steps counts two) (§9.1) | per tool call | the limit protects the target, not the model | VM 7 |
| D427 | **`PlanPolicy.refusals(plan, scenario)` applies the credential rule's plan form to a pack verb's arguments as the client wrote them**, never to the verb's own compiled references (§9.4, §11). **(Phase 7 security fix, S101, S102, S107, S128, built 2026-10-11, SH)** Every caller passes the written scenario (the one-argument form is removed; `author --run` reads the file back); the written arguments are found by step identity; the executor's tokenizer reads them, the slot as a whole refused where it would reach a call (a pack verb's compiled calls, a base verb's, an embedded written reference) and admitted only as a slot handed whole to a pack verb; past the depth bound the form refuses | judge compiled calls only (a pass-through verb would slip through); judge every reference (the pack's own `signOn` join would be refused) | a client must not hand a secret field to a verb; the verb's own join is the pack's business | VM 8 |
| D428 | **`ScenarioPlayer.withBaselines(root, env)`** reads the scenario's baselines once per play and hands them to every `expectImage` and anchored `pointer` (D359's consumer); the CLI passes the session's bindings root and environment (§6.8) | a tenth constructor parameter | the baselines are optional (null for a host without bindings) | VM 9 |
| D429 | **`export` (the REPL's and `--play --export`) writes each anchor PNG where its bindings entry says**, created exclusively; the JSON form lists them under `images` (§9.3) | leave them to the person | §9.3: "PNGs beside" | VM 10 |
| D430 | **`VisionLiveIT` runs item 3 second and records an `INVALID_REQUEST` as the finding instead of failing**; the bounds of items 1, 2 and 4 count the turn's `vision` calls only; the framework's own files are checked for the key after each item (§13.3) | fail on a 400; count every ledger entry | the item exists to record the finding; a resolution the person asked for is not part of the turn; the framework writes its files itself | VM 11 |

### Decisions taken by the code review's fix wave, the race fix and the security fixes, D431-D486

The code review's three fixers (FA the agent's Swing side, FB the scenario, controller and CLI side, FC the vision module and
the session), the fixer of the hooks engine's race (FX) and the four packages of the security fix round (SG the agent's Swing
side, SH credentials, slots and the client's plan form, SI the vision module, the session service, the web reads and the CDP
path, SJ the image baselines, the bindings files and the run output) took 56 decisions and recorded them in their reports as
a tag and a number (FA 1 to 9, FB 1 to 3, FC 1 to 6, FX 1, SG 1 to 13, SH 1 to 7, SI 1 to 11, SJ 1 to 6, each tag's numbers
running from 1 without a gap; SG 13 was added by SG's follow-up after SI had landed). The orchestrator numbered them at the
closing documentation pass of 2026-10-11, in the order FA, FB, FC, FX, SG, SH, SI, SJ and by that number within a tag (the
alphabetical order of the tags, not the order they landed on `main`, which was FA, FC, FB, FX, SG, SH, SJ, SI): **FA
D431-D439, FB D440-D442, FC D443-D448, FX D449, SG D450-D462, SH D463-D469, SI D470-D480, SJ D481-D486**. Every
`D-<tag>-<n>` of this contract, of P6 §4.6 and of the code's comments now cites the number, and the last column keeps the tag,
the package's own number and the review's finding. They join decision **B30** (`../DECISIONS.md`, confirmed by the owner on 2026-10-11). No decision of
B1-B29 or of D287-D430 is reversed by any of them. Several amend or widen an earlier row, and that row carries a mark: D452
and D454 widen D349 and D383 (the masked-text rule withholds a masked label's name and the text under a region), D463, D467
and D468 widen D342 (the credential rule), D464, D465, D466 and D469 amend D427 (the plan form), D468 amends D425 (a
person's slot is not rebound), D482 amends D362 (no bind-time write), and D470 adds rule 11 to §11 (the person's clipboard).

| # | Decision | Alternatives rejected | Reason | Tag |
|---|---|---|---|---|
| D431 | **A paint root's ancestors up to its window are walked for masks**: an ancestor's `regions` are blanked where they meet the image; an ancestor masked whole blanks its rectangle united with the paint root's own, reported once under the ancestor's ref with its rule, and the paint root is then treated as blanked whole (nothing marked or walked below it, its caret still reported as for any whole-blanked component) (§3.6) | the reviewer's 'ancestors with `ownRule != null`' (misses the `regions` form); the ancestor's rectangle alone (leaks the part of a child that overhangs its parent, which a `COMPONENT` capture paints whole); refusing a capture inside a masked component (breaks the evidence of legitimate children) | the contract's guarantee that a component a mask names is blanked before the image leaves the JVM, for every scope; D383's premise made true | FA 1 (L2-F1) |
| D432 | **A person's glass capture applies the masks of every JVM window whose screen rectangle meets the captured rectangle**, in any stacking position; `windows`, `surfaces` and `carets` list what the image holds of them; `focus.active` stays the captured window's (`Capture.facts` gains the captured window, null for `SCREEN`) (§3.4) | only the windows ranked above the target (AWT has no z-order); a second, mask-only walk keeping `windows` to the target (two walks, and a `windows` list that hides windows whose pixels are in the image) | the safe side: a window below the target over-blanks, never under-blanks; the fields tell the truth about the image | FA 2 (L2-S1) |
| D433 | **One pre-check method serves the delivery probe's read and the read before the press** (bounds, the drag's end point, `requireFocus`, covering, `expectTarget`); the probe is skipped for a modal-blocked window and for a covered point under `force`; the drag's end-point refusals carry `data.param: "to"` (§3.4) | a lighter read for the probe (two code paths that can drift); accepting the probe's move at any window of the JVM (does not help the modal case, where AWT drops everything); probing anyway under `force` (degrades the capability for a window that legitimately took the move) | nothing moves for a refused call; the capability degrades only for real non-delivery | FA 3 (L2-F4, L2-S2) |
| D434 | **The probe restores the pointer whether it passed or failed**, rather than handing its pre-probe location to `act` (§3.4) | handing the location to `act` (leaves the pointer on the probed point when the pre-check then refuses) | the person's pointer is back in every outcome; one extra move per probe | FA 4 (L2-F6) |
| D435 | **After `cancel`, when a dispatched press is still unbalanced, one balancing task is posted** (best effort; `needsBalance` under the state's lock makes it idempotent with a queued task that balances first) (§3.3) | `cancel` alone, as proposed (no task may follow the failed post, so the press would stay unbalanced) | §3.3's 'a dispatched press of a stopped sequence is balanced' holds on this path too | FA 5 (L2-F7) |
| D436 | **Both Robot ids are set `UNAVAILABLE("robot: probe not run")` when `checkAwt` confirms AWT, before the probe is posted**; no retry; a Robot call on such a target is refused instead of constructing the Robot on the call thread (§3.9) | retrying from the watcher (it ends once AWT is confirmed; a retry needs a new timer thread) | a truthful matrix and no lazy Robot construction on a call thread; a refused post is rare (a full pool) | FA 6 (L2-F8) |
| D437 | **The heap rule is applied per allocation of the glass path** (the grab, then the conversion copy, the headroom re-read before each), refused before the grab with `param: scope` and the hint 'capture a smaller scope'; an `OutOfMemoryError` of either is `INTERNAL_ERROR`; the outside is filled band by band (at most 2n + 1 bands for n windows) (§2.4) | one up-front check of twice the raster (refuses grabs that would fit when no conversion is needed) | §2.4's bound as written for `swing.capture`; the per-pixel walk outside the call's budget is gone | FA 7 (L2-S3) |
| D438 | **The shared raster-hash vector is `CaptureTest`'s** (a 3 x 2 raster with `0xFF102030`, `sha256:6be118c3…c4ba`), held by `ImageCompareTest` too; `VisionSwingChecks` item 1 asserts the agent's `hash` equals `ImageCompare.rasterHash` of the decoded desk raster (§13.1) | the controller's vector | its `0xFF102030` has a distinct byte per channel, so a channel-order slip in either implementation changes the hash; VS's own request named it | FA 8 (L3-F1) |
| D439 | **The Robot leg's `requireFocus` refusal becomes its own test** that assumes the observed unfocused state (a visible skip when the frame kept the focus); the passing half is a second test (§13.2 item 6) | a fixture action that takes the focus away (the desk has none; it would add an RPC to the sample for a test) | the sceptic's endorsed fix; the deterministic refusal exists in `RobotPathDisplayTest` | FA 9 (L3-F2) |
| D440 | **One Robot predicate, `Plan.robotValue`, a superset of the agent's reading, at every controller site** (`Plan.robot`, `PlanCompiler.visionRequires`, `ExecutorSession.gate` and `readRefusal`, `CallCommand.robotArgument`, `JournalExport.liveOnly`; `CallPolicy.robot` and the facade's `read` delegate); its strip set is the union of `trim()`'s and `strip()`'s, so `\u0001ROBOT` is Robot; `via` and `mode` in a plan are not normalised; `redact` stays strict (present and not the boolean `true` is refused) (§9.1) | normalising `via` and `mode` in `BaseSteps.call` at compile (misses the REPL's raw call, `skillroy call` and the Java `execute(PlannedCall, …)` and changes committed plan bytes); keeping `CallPolicy`'s `strip()` rule as the definition (misses the control characters `trim()` drops: FB-N1); making the agent strict (the agent's parameter rule is shared by every enumerated argument) | a controller superset closes the gap without changing the wire | FB 1 (L1-F1 = L2-F3, FB-N1) |
| D441 | **`via` and `mode` take a literal value; a reference that resolves to Robot is refused at the call**: `CallRunner` refuses, after substitution and before sending, a `via` or `mode` that names Robot when the written argument did not (`ERROR / ENVIRONMENT`, run-log fields `refused`, `argument`, `primitive`) (§9.1) | re-running the gates after substitution with the origin and flags (the plan-level gate, `requires` and the export would still disagree with what is sent); resolving references before the gates (changes when resolution errors surface for every call) | the literal rule keeps `usesRobot`, `requires`, the export's live-only rule and the call in agreement; no committed scenario uses a reference there | FB 2 (FB-N2) |
| D442 | **Seed B's `keypad-frame` entries are regenerated** (deleted and approved again on both Javas, with the desk activated first) rather than hand-edited or left; the approve activates the desk before the baseline is taken (`-Dskillroy.bindings.update`) (§13.2) | hand-editing the Java 8 entry; leaving it until someone deletes it (`ImageSteps` approves only a missing key) | regenerating also tests the fix: the Java 25 entry came back byte-identical, the Java 8 entry now equals seed A's | FB 3 (L3-F9) |
| D443 | **The player's `verify`**: after the last step the plan's `verify` is evaluated in file order through `ExecutorSession.check`, one read per check, each journaled; all are evaluated and the first failure fails the play (`FAIL / ASSERTION`); a capability-guarded expectation whose capability is not `OK` and a `db` check are skipped with the reason (`DEGRADED`); one result step `verify` (§6.8) | polling until the step timeout as `PlanExecutor` does (needs a sleep or an event wait in the player); stopping at the first failure; amending §6.8 to say the player ignores `verify` (a wrong `PASS`) | the session's own `expect` step evaluates once and the last step's sync has run; all journaled helps the reviewer | FC 1 (L1-F2) |
| D444 | **The slots bound in the session are listed in the turn's first `USER` message** as a `<target-state>` block (`$reference`, query kind or `fixture`, credential or not, fields as `$slot.field`, keys the mask's key rule calls secret left out, never a value), in every session, only when at least one slot is bound, built from the journal's last `resolve` or `fixture` record per slot (§9.1, §9.2) | a system part (cached per session while bindings change between turns); a new label `<bound-slots>` (changes `INSTRUCTIONS` and `PromptMask.LABELS`, and so every recording); values too (D342's spirit, personal data, and a literal would replace the reference in the export); vision sessions only; a new `ExecutorSession.boundSlots()` (another package's file) | the journal holds exactly the public values, never the join's secret fields; the same gap existed in Phase 5 sessions, the overlay and MCP; the five session recordings were regenerated key-free | FC 2 (LV-1) |
| D445 | **The anchor's window insets are a `WINDOW` capture's `origin`** (`scale: 0.25`, `redact: true`), read once per window per session and cached, `{0, 0}` when the read fails (§6.5) | an additive `content` rectangle in the capture's `windows[]` (`Capture.java` was under FA's changes, and a wire change for reviewer information); amending §6.5 to say `{0, 0}` (misreports under a window manager) | no wire change; correct under a window manager | FC 3 (L1-F4) |
| D446 | **`targetNode` is added to the hit-test's target form** (the named component's own node; additive), and `Recorder.node` falls back, for an agent without it, to the `mouseTarget` or `deepest` that is the target, else `mouseTarget`, else `deepest` (§3.2, §6.5) | controller only, `mouseTarget` first (kept as the fallback); a snapshot read per `click_ref` (heavy) | the recorder must describe the named component, not the label at its centre | FC 4 (L1-F5) |
| D447 | **`wait` settles for the session's step timeout**: `SessionActions.stepTimeout()`, a default method (the facade answers `SessionOptions.stepTimeout`), bounded to 1 s at least and 110 s at most (§6.3, §9.1) | a `SessionToolContext` component (breaks every constructor caller); amending §6.3 to a fixed 5 s | the contract says the step timeout; a default method breaks no implementer | FC 5 (L1-F6) |
| D448 | **A played session's export lists the dropped `aiExpect` steps** under the report's existing 'Live-only' section (`ScenarioPlayer.Result.annotate`; `SessionCommand --play --export` applies it), and the scenario's `verify` reaches the export as expectations after the last step through the checks D443 journals (§6.8) | a new 'Dropped visual checks' section or passing the scenario into `JournalExport` (changes `ExportResult.Report` in another package's module and every caller); writing a `verify:` block into the export (needs `JournalExport` changes; a trailing `expect` step is equivalent in a plan run) | no change to another package's types; the lines survive the played export | FC 6 (L1-S1) |
| D449 | **The hooks engine sets each `hook.<id>` to its probe's first answer at declaration**: `HookEngineImpl.declare` computes `capabilityState(hook)` on the install's thread under the engine's lock, right after declaring the probe and only while no detach has begun, so `hook.install` answers only after every declared `hook.<id>` holds a state of P6 §4.6; the declared probe stays for `reprobe`. Agent-side, in the hooks module. The check (`ResolutionChecks`) stays and also compares the records' `agentHash` and names every differing entry on a failure (P6 §4.6, §5.3) | (a) the core runs a late declaration's probe synchronously (changes every module's `declare` and may block whatever thread declares); (b) the controller polls the matrix after an install until no `hook.<id>` is pending (a poll loop in the executor, keyed on a reason text, repeated in the session, the MCP server and the CLI); (c) relaxing the check (hides a real defect: a guard on a hook in the installing run); (d) an agent-api `declare(id, probe, initial)` (a public Java 8 SPI change) | the contract does not allow the difference: P6 §4.6, §5.3 step 6, §13.2 items 1 and 4 and D130 (one hash per target); the engine was wrong, not the check. Before: 22 of 30 hooks targets failed under load (Java 25), 4 of 6 on Java 8; after: 0 of 66 | FX 1 (capability-matrix hash flake) |
| D450 | **One predicate, `Windows.hidden`**: a frame the toolkit reports `ICONIFIED`, or a window such a frame owns transitively; it refuses a Robot point (`NOT_FOUND`, `data.reason: "iconified"`), removes the window from the covering windows and from the module's stacking (so the `SCREEN` composite, a `SCREEN` point and the glass capture's window rectangles), and leaves snapshots, locators and the routed pointer alone. The test seam is a package-private `Windows.State` (§3.4) | the Robot check alone (the composite would still paint the minimised frame and the model would click it); an `iconified: true` flag in `windows[]` or the hit-test (not built: the window is no longer in the composite) | a minimised frame is not on the glass; the agent refuses anyway | SG 1 (S104) |
| D451 | **A screen point is on a screen when it lies inside some device's default-configuration bounds** (not the union rectangle); a point on none, and a `DRAG`'s end point on none, is `NOT_FOUND` with `data.reason: "off-screen"` before anything moves; a press `received elsewhere` clears `deliveryOk`, so the next action is probed again (§3.4) | the union rectangle (accepts the gap between screens of different sizes); `offScreen` flags in the capture (the refusal is the guarantee) | the platform clamps an off-screen point to the nearest edge and the press lands there | SG 2 (S105) |
| D452 | **The safe name lives in `ComponentView.name` itself**: for a masked component (named by a mask, inside one, or under a blanked region) a name equal to the component's own text (trimmed) is withheld on every route (`node`, `describe`, the hit-test's `{name, role}`, `focus.ownerLocator`, the `name` matcher, the dialog reader's labels); a name set apart from the text stays (§3.6) | withholding every name of a masked component except an explicit accessible name (border titles and `labelFor` names, which committed locators of masked entry fields rely on, would go too) | every route that reads a name is covered at once | SG 3 (S106) |
| D453 | **Masks combine by union**: per mask, its `regions` when it gives some, else the whole rectangle when that mask names the component; the union is blanked (a whole contribution answers the whole rectangle); a password field and a throwing mask stay whole (§3.6) | the first mask's answer deciding (a mask giving `regions` for a component cancelled another mask's whole blanking) | a second mask can only add blanking | SG 4 (S123) |
| D454 | **A blanked region withholds the text under it** (the rule is built, not the disclosure sentence): a component a region holds whole, or a text- or value-bearing component (not a plain container, not a table or list) a region meets, is masked; a table's covered cells are null in `value.cells` and out of a row mark's label; a list's selected item under a region is left out of its `text` (§3.6) | the disclosure sentence instead (the owner's choice) | 'meets' rather than 'lies inside' for text-bearing leaves, because a region drawn around the visible text of a wider label would otherwise leave its text on the routes | SG 5 (S122) |
| D455 | **`covered` by Swing's paint order**: for a `WEB` surface, the windows painted later in the composite and, in its own root, every visible sibling with a lower child index along the surface's ancestry up to the paint root, each clipped to `rect`; more than 32 rectangles become one, the whole `rect`; `HEAVYWEIGHT` entries keep their rectangles; a surface is listed only for a showing component, with `rect` its visible part (§3.1, §4.8) | naming the layered pane's layers (popup, modal, drag) one by one | one rule covers the popup, modal and drag layers, in-window dialogs and the glass pane; the safe side beyond 32 | SG 6 (S108, S129) |
| D456 | **The encoding is counted in the capture's bound**: after the PNG is made, 6 x its size against half the headroom read after the encoding (`INVALID_PARAMS`, `param: scale`, `neededMb`, `freeMb`, `reason: "encoding"`); an `OutOfMemoryError` of the encoding is `INTERNAL_ERROR` (§2.4) | streaming the base64 into the reply (changes agent-core's control server and the protocol's JSON writer) | the endorsed check; other packages' modules stay untouched | SG 7 (S125) |
| D457 | **The glass grab's bound follows the screen's scale**: `4 w h` at scale 1 and `4 (ceil(w sx) ceil(h sy) + w h)` otherwise, the scale read through `RobotPath.Screens.scaleAt` at the rectangle's centre, checked before the port is looked up (§2.4) | (s^2 + 1) times the raster at every scale | Robot wraps the pixel array without a copy at scale 1 | SG 8 (S126) |
| D458 | **A non-default device's Robot is built on demand on an agent worker and cached**; the probe obtains its port before it adds its listener (§3.4) | one Robot per device at the capability probe (a native Robot per screen at start whether or not one is used) | no listener is left by a failed probe; no native object unless a screen is used | SG 9 (S121) |
| D459 | **`modifiers` are refused with the Robot**: `INVALID_PARAMS`, `data.param: "modifiers"`, in `SwingPrimitives.pointerCall` before the capability check (as `PRESS` is) and at the top of `RobotPath.pointer`; an empty list is no modifier (§3.4) | ignoring them (the old behaviour: a Shift-click was acted as a plain click) | a V3 click the model asked with a key held must not become a different click | SG 10 (S124) |
| D460 | **Only `focus.ownerLocator` drops the `{text}` candidate**: the first unique candidate of §3.2's order without `{text, role}` (a nameless field is named by `{class, index}` or `{path}`); the hit-test's own `locators` keep it (§3.1) | changing the hit-test's locators (every recorder choice and committed export would change, beyond the finding) | a field's content is not an identity and must not enter a baseline | SG 11 (S146) |
| D461 | **`WebHostChecks` item 10 asserts the back browser absent from `surfaces` and the front browser's `covered` empty**, without wrapping the back browser in a panel; the wrapped (hidden card) case is `CaptureTest`'s (§13.2 item 10) | wrapping the back browser in the web host (changes the host application and BS8's sentinels) | the host's back browser is a direct `CardLayout` card, absent by its own `isVisible()` | SG 12 (S129) |
| D462 | **Every `WEB` surface carries `bounds`**, the browser's whole rectangle in image coordinates, clipped neither to the image nor to the visible part (`x` and `y` may be negative), on every entry (equal to `rect` for an unclipped browser); `Capture.Walk.unclipped` is `toImage` without the image clip (§3.1, §4.8) | clipping `bounds` to the image (a bitmap placed at a clipped rectangle is scaled wrongly); sending it only when it differs from `rect` | SI's composite could not place the bitmap of a browser a viewport clips (D475); a reader needs no fallback beyond an older agent's | SG 13 (S108 follow-up) |
| D463 | **A non-person step resolves its call arguments against the slots' public values**: an interactive session's step of a model, a client or the system runs with `CallRunner.publicArguments` (`Bindings.substitutePublic`); the plan executor keeps the join (a client's plan may run a pack verb's own join, D427). Rule 1 treats the credential-joined slot as a whole as a secret reference in every spelling (`$x`, `x$x`, `x$x.`, `x$x..password`), a person's whole-slot call consumes it, and a value too deep to read counts as one while a join exists (P2 §8.4, §9.2) | rule 1 alone (a missed spelling would reopen the disclosure); swapping the runner's bindings for a public view during the step (the hosts read `session.bindings().secrets()` for their masks, which would miss the join mid-step) | the endorsed structural fix, with the early refusal kept for the wording | SH 1 (S101) |
| D464 | **The plan form judges three ways**: a base verb's or a raw call's compiled arguments (the slot as a whole and an unprovided field), a pack verb's written arguments (an unprovided field, and the slot embedded in a longer text; a string that is exactly `$<slot>` hands the slot to the verb, so `signOn {staff: $staff}` keeps running), and a pack verb's compiled and cleanup calls (the slot as a whole only; the verb's own field references stay unjudged). The tokenizer is the executor's own (`References.references`) (§9.4, D427) | refusing every whole-slot reference in written arguments (refuses `signOn {staff: $staff}`, the pack's own join D427 keeps); judging only compiled calls for the whole slot (a verb that transforms the text would slip) | a joining verb names the fields it puts where (the sample's `signOn` does); a pack verb that compiles a credential slot as a whole into a call is refused in a client's plan | SH 2 (S101) |
| D465 | **A pack verb's written arguments are found by step identity**: `s<i>` is the scenario's step `i`, the compiler's numbering, never the source line; a plan that does not number the scenario's steps so is refused when it declares a credential slot (§9.4) | `Plan.Step` carrying its written arguments (a `plan.json` change in another package's files) | the compiler's numbering is one to one already | SH 3 (S102) |
| D466 | **`PlanPolicy.refusals(plan)` is removed and the two-argument form requires the scenario**; `author --run` reads the written scenario back from the planner's file, checks that it compiles to the planner's plan, and passes it (a refusal line when it cannot or when it differs) (§9.4, D427) | keeping the one-argument form failing closed for any pack verb in a plan with a credential slot (it would refuse `signOn` in an authored scenario) | every caller has the scenario | SH 4 (S107) |
| D467 | **While a credential is in flight every call of a non-person that may change the target is refused**: a mutating, destructive or uncatalogued primitive, and every typing call as before; reads run (§9.2 rule 2, §11 rule 6) | a value check (`holdsSecret`) on every string argument only (needs the value to be known); naming the pack-verb input seam per pack | the model has nothing to change while the person is mid-sign-on; deny by default, as D369 does for keys | SH 5 (S127) |
| D468 | **A slot whose current binding a person made is rebound for a person only**: `resolve` and `fixture` refuse for any other origin before anything is resolved, joined or journaled (`BindException`, `ENVIRONMENT`, `detail.refused: slot`); a model or a client may rebind a slot either of them bound; `ResolveEntry` carries `origin` (nullable for an older journal); the export writes a non-person's binding `REQUIRED` by nobody or `APPROVED` by the exporter's own label. Limit: replay refuses an unapproved value for a model's strategy only (D72), so for a tier (a) slot the mark is the reviewer's (§6.8, §9.2, D425) | the credential-only rule (would not close S113); refusing a model's rebind of a client's slot (no boundary between two non-person origins of one session) | a played scenario's person-bound slot must not take the model's value | SH 6 (S113, S139) |
| D469 | **The plan form's depth bound is `References.MAX_DEPTH` = 64**, shared with `anyReference`; past it a plan that declares a credential slot is refused, and the session's rule 1 counts a too-deep value as a secret reference while a join exists (§9.4) | failing open past the bound (the old behaviour); applying the bound to every plan | there is nothing to protect in a plan without a credential slot | SH 7 (S128) |
| D470 | **The person's clipboard and the system selection are the desktop's**: for a MODEL or CLIENT origin on every route `CallPolicy.arguments` refuses `swing.key` with `V` and `CTRL` or `META`, `INSERT` and `SHIFT`, `PASTE`, the copy and cut chords and keys, and `button: MIDDLE` on `swing.pointer`, `swing.click` and `web.pointer` (read in every spelling the agent reads); the vision adapters answer the same as text before grounding; the toolset's `middle_click` is disabled in `configs` (§11 rule 11, §9.1, §6.3) | allowing and documenting (a path to a personal value no gate covers); stripping the modifier (§9.1: refused, never stripped) | a synthetic middle click pastes the X11 PRIMARY selection in a Linux browser as in Swing (`web.pointer` widens the dispositions' list); an application's own Paste control stays an ordinary click, to be masked | SI 1 (S110) |
| D471 | **A masked browser is masked on every route**: the web module asks the registered `CaptureMask`s for the browser's component and each ancestor in its own bounded EDT read (a throw or no time counts as masked); `web.bitmap`, `web.dom.text`, `web.ariaSnapshot` and `web.hitTest` answer `INVALID_PARAMS` (`param: redact`, `reason: masked`) unless `redact: false`, which `CallPolicy` refuses for a model or a client; `web.browsers` keeps the entry with `masked: true` and no URL, title or frame names; the snapshot text writes `[web <ref> masked]`; the CDP path hands a masked browser's four reads to the agent (§3.6, §4.8, P4 §4.2) | withholding only the pixels (the SPI's and the threat model's 'withholds the component's text as well') | a page's text and tree are as personal as its pixels | SI 2 (S111) |
| D472 | **A `WEB` hit under a modal dialog is refused by the recorder** (in-window dialogs included) before the page is read: 'a modal dialog blocks the page; answer the dialog first' (§6.5) | a modal check in `web.dom.click` and `web.dom.fill` for MODEL and CLIENT origins (a P4 amendment; not needed to close the recorder's path, left to the owner) | the routed pointer and `web.pointer` already refuse a blocked window (D294, D335) | SI 3 (S109) |
| D473 | **The stale rule compares the capture's own view**: in V1 the hit's target must be the mark's ref, or the component the mark's component draws at its centre now in the window the capture shows at the pixel; in V2 and V3 after a window event the hit's window must be the capture's window at the pixel; the older fresh-capture comparison is kept on top (§6.4) | dropping the second live hit-test (no reason to change a behaviour no finding faults) | a dialog opening over the point must not be clicked; an editable combo box's mark (whose centre is its editor) must not be refused for ever | SI 4 (S114) |
| D474 | **A turn's reads and waits are bounded**: `SessionPolicy.maxReadsPerTurn()` = max(20, 2 x `maxActionsPerTurn`), a method not a seventh component (an embedder who wants another bound sets the action limit); a turn's waits sum to at most three step timeouts (each counted as max(its elapsed time, the settle's `ms`) capped at its timeout); both answer 'not run: …' and end the turn `LIMIT`; the bound is not added to the system instructions (§9.1, §10, §6.3) | a seventh record component (every construction site and host flag changes); the read bound's text in the instructions (changes every session's prompt and the Phase 5 recordings' fingerprints) | a model's reads were exempt from every limit | SI 5 (S138) |
| D475 | **The composite clips the page to `rect` minus `covered`** and draws the bitmap at `bounds` when the agent sends it; without `bounds`, a `rect` whose shape differs from the bitmap's (a browser a viewport cuts) is not drawn at all and the area keeps the capture's own paint, because where the visible part lies in the page is unknown. SG's follow-up (D462) made the agent send `bounds` (§4.8) | scaling the whole page into the visible part (the model's pixels would not match where the click lands) | the model's picture shows what a person would see above the page | SI 6 (S108 controller half) |
| D476 | **The local estimate is checked against the remaining budget before the provider's token count** (`SessionService.refuseWithoutBudget` at the top of `complete` and `visionCall`; an exhausted or zero budget sends nothing) (§10, P5 §7.2) | sending the count anyway (the finding) | the local estimate overcounts as a rule: a call close to the remaining budget may be refused that the provider's count would have admitted | SI 7 (S135) |
| D477 | **A `webClick` is recorded only for a hit with no `iframe` in the browser the step resolves to**: the default browser's selection read through `web.hitTest {by: <default>, point}`, else the only showing browser of `web.browsers`; a failing read means live-only (`web.pointer` with `liveOnly` in the provenance) (§4.2, §6.5) | a further uniqueness read (a main frame that forges its own answer names one of its own elements, and `webClick` fails `NOT_FOUND` or `AMBIGUOUS` when the locator is not unique there) | the agent's own selection rule is used, not a copy of its regular expressions | SI 8 (S130, S132) |
| D478 | **The CDP `web.pointer` takes no bound of its own for the hit read** (Playwright's `evaluate` has no timeout and an in-page timer cannot interrupt a synchronous stall); the modal re-check moves after the hit and every dispatch checks the deadline; a press sent before the deadline is balanced by one release (§4.7) | an in-page timer; a bound around the hit read | nothing is dispatched after the caller timed out | SI 9 (S133) |
| D479 | **The recorder never chooses a `{text, …}` locator for a `textbox`, `text` or `password` role**; the hit-test keeps proposing it; `{class, index}` or `{path}` names a nameless field (§6.5 step 1) | changing the hit-test's candidates (another package's file, serving other readers) | a field's content is no identity and would enter a committed export | SI 10 (SG's handover) |
| D480 | **The inner-size and title reads are guarded expressions** built from `typeof`, two numbers and a primitive `length`, both answers through `WebPrimitives.parse` (the CDP path's `Scripts.innerSize` likewise), rather than the `web.evaluate` wrapper whose cut relies on a library slot a page may replace (P4 §4.6) | `WebBrowser.viewport()` (null before the first size is seen, and not the page's `innerWidth` the contract names) | their answers are bounded whatever the page did | SI 11 (S131) |
| D481 | **The focus mask is bounded at a quarter of the compared raster**: the union of the two owners' rectangles is blanked only when it covers at most a quarter of the baseline's area; a rectangle alone above it is compared, and when the rest's union is still above it none is blanked; `focusMasked` is true when the owners differ and none was skipped; `focusMaskedPixels` and `focusMaskSkipped` (`{side, owner, rect, pixels}`) are recorded; the guard takes the same path (§6.7) | each rectangle bounded separately (two rectangles of 24 % each blank half the raster); a margin around the focus ring (the agent does not report the ring); no focus rule (every baseline approved with the focus elsewhere would fail on a focus ring) | the total blanked by the focus rule is never above a quarter, and the 16.7 % case of the focus-mismatch test still holds | SJ 1 (S103) |
| D482 | **An images-only plan in `approve` asks nothing and writes nothing at bind time**; after the steps an approved baseline is written as before, and any other change of an existing file (environment, plan hash, resolutions, oracles, pack) only on the approver's yes (refused: the approved baselines go into the existing file as it is and the run ends `RESOLUTION_FAILED / NOT_APPROVED`); without an approved baseline the file is written only after steps that did not fail and on the approver's yes; `skillroy_scenario_run` refuses `mode: approve` while the session's approver is `REJECT_ALL` (§8.7, §9.4, P5 §9.3 (amends D362)) | the reviewer's fix 1 alone (breaks the operator's refresh); asking at bind time (a `REJECT_ALL` run would end at the bind, and the terminal would ask twice); letting a header change ride on an approved baseline (the person approves pixels, not an environment); asking after a failed step | the sceptic's amended fix, with the contract sentence taken literally for the header change; a client's run never writes into the bindings root | SJ 2 (S115) |
| D483 | **The export names what to merge**: option (a), one `to merge: images.<label>.<key> (<file>)` note per anchor entry in the report's Notes, rather than a new `Report` component; §6.7's re-record sentence is corrected (§6.6, §6.7) | option (b), the CLI merging into an existing bindings file at the destination; a new report section 'To merge' (an eleventh component of `ExportResult.Report`, which `ScenarioPlayer.annotate` rebuilds from ten) | the smaller change, no cross-package hunk, the lines survive the played export | SJ 3 (S120) |
| D484 | **The controller drops a `text` key from a focus owner's locator** in an approved entry, a comparison and an exported anchor (`ImageSteps.ownerWithoutText`; `{role}` is left, null when nothing is); the export's `<redacted>` check runs again over its `images` section after the anchors' entries are added, naming the anchor and the step (§8.7) | `{role, textHash}` (a hash of a short target text can be confirmed by guessing, and a field's text is no stable identity: typing changes it) | two nameless text fields then compare as one owner, which fails closed (their focus rings differ) | SJ 4 (S146) |
| D485 | **`skillroy session` without `--out` writes into a new owner-only directory under the system's temporary directory** (`SecureFiles.createOwnerOnlyTempDirectory`, `skillroy-session-<hex>`), kept, its path printed on stderr at the start and the end; `export` without its own `--out` writes there too (§9.3, §9.6) | the documentation-only correction (an acceptance under P6 §12.5; not taken, a fix was cheap); `--no-out`, keeping nothing (cannot export an anchored step) | the contract and `SECURITY.md` promised a session without `--out` kept nothing; it wrote `./run-output/<id>` | SJ 5 (S148) |
| D486 | **An unusable bindings file is `ERROR / ENVIRONMENT`** in a bind that writes the file or whose plan has images (`Binder.existing()` tells absent from unusable); a non-writing `live` or `re-resolve` of a plan without images reads nothing from the file and is unchanged (§8.7, P2 §8.3) | failing every bind that finds an unusable file at the derived path (a live run that never reads it would fail on a file left by a newer version) | a writing bind never replaces a file it cannot read | SJ 6 (S116) |

---

## 18. Questions for the owner: answered provisionally 2026-10-10, confirmed 2026-10-11 (B29)

Answered by the orchestrator on 2026-10-10 in the manner of P5 §17 and P6 §17, so that the build can start; written into
the sections named; **confirmed by the owner on 2026-10-11**, every row (`B29 ok`, no veto), the three that rested on the
owner's knowledge or taste among them (Q25 the sample's number pad, Q27 the JxBrowser input behind descriptor roles, which
stays unverified, and Q34 V3 on a person's desktop acting only while the target holds the focus). The column "A veto means"
records the form a veto would have taken. Items that rest on knowledge only the owner has were marked
**(owner's knowledge)**. Q35-Q39 were added by the revision after the contract review; the answers of Q2, Q6,
Q11, Q14, Q16, Q20, Q22, Q32 and Q34 changed with it; the last round after the recheck changed Q2, Q36 and Q39 once
more.

| # | Question | Answer (provisional 2026-10-10, confirmed 2026-10-11) | Written into | A veto means |
|---|---|---|---|---|
| Q1 | **Where the Robot code lives.** Inside `skillroy-agent-swing` (JDK API, lazily on a worker, behind the option), or an optional agent module behind a new SPI? | Inside the Swing module (D290) | §2.1, §3.4 | `B29 Q1 veto`: `optional/skillroy-agent-robot.jar` behind a `RobotInput` SPI in `agent-api`, named by `optionalModules`; a sixth golden state per leg |
| Q2 | **The double opt-in for V3.** An agent option (`robot=true`, the host's consent) **and** a person's controller flag (`--allow-robot`, per run, session or raw `call`), both required, and never a per-call choice of a model or a client even with both? | Yes (D290, D332, D347) | §3.4, §9.1, §9.2 | `B29 Q2 veto option`: the agent option alone; `B29 Q2 veto flag`: the controller flag alone (the agent then exposes Robot to any token holder); `B29 Q2 veto percall`: a model or a client may pass `via: ROBOT` when both flags are set |
| Q3 | **`redact` defaults to true**, so failure evidence, artifacts and MCP images are redacted too (a password field, a pack-named component)? | Yes (D288, D295) | §3.1, §3.6 | `B29 Q3 veto`: default false; the vision module always passes true; evidence images unmasked as today |
| Q4 | **The capture mask's shape.** A separate `CaptureMask` SPI, keeping `MaskPolicy` AWT-free, and one rule for pixels and text? | Yes (D296, D349) | §3.6 | `B29 Q4 veto`: a default method `MaskPolicy.masksPixels(Component)`; `B29 Q4 veto text`: pixels only, the text walker unchanged |
| Q5 | **Root-pane painting as an amendment to the built `PAINT`**: the pixels of every frame capture on Linux change (the imitation decorations go; the image is the content's size)? | Yes (D288) | §3.1 | `B29 Q5 veto`: the frame stays as built and `scope: CONTENT` is added for the root pane; the vision module uses `CONTENT` |
| Q6 | **Robot keys.** Not built: V3 types through the routed primitives after a focus check and stops the turn when the target JVM holds no focused window; BS9's key conditions are the reason. Right for Phase 7? | Yes (D345, D344) | §1, §3.4, §16.2 gap 77 | `B29 Q6 veto`: `swing.type {mode: ROBOT}` and `swing.key {via: ROBOT}` under `swing.pointer.robot`, with the focus check, the re-activation after a dialog and X-keysym translation in the agent |
| Q7 | **`swing.click {rung: ROBOT}`** stays the sentinel? | Yes (D291) | §3.4, §3.9 | `B29 Q7 veto`: the rung maps to `swing.pointer {target, via: ROBOT}` |
| Q8 | **`region`'s two homes**: on the wire a rectangle or an id, in the DSL always an id with `clip` for a rectangle? | Yes (D297) | §3.1, §8.1 | `B29 Q8 veto`: the wire parameter is renamed `clip` for the rectangle and `region` takes an id only |
| Q9 | **Lower-case capability ids** (`swing.hittest`) kept as reserved? | Yes (D298) | §3.9 | `B29 Q9 veto`: camel case, with `web.hittest` renamed in five goldens and the web host's acceptance |
| Q10 | **`LocatorResolver.describe`** as the reverse direction for domain locators? | Yes (A64) | §3.7 | `B29 Q10 veto`: no reverse method; the hit-test proposes built-in strategies only |
| Q11 | **The covering-window rule**: on the routed path refuse for a ranked window (modal, owned, active) and report otherwise; on the Robot path refuse for every covering window; `force` for a person only? | Yes (D294, D344) | §3.2, §3.3, §3.4 | `B29 Q11 veto refuse`: refuse for every covering window on both paths; `B29 Q11 veto report`: never refuse, always report, on both |
| Q12 | **`Status.SKIPPED`** as the fifth status for a `run` plan in `replay`? | Yes (D328) | §8.6 | `B29 Q12 veto degraded`: `DEGRADED` with a skipped line; `B29 Q12 veto error`: `ERROR / PRECONDITION_FAILED` |
| Q13 | **Who runs `aiAct` and `aiExpect`**: only the vision module's player over a session, never `PlanExecutor` (which skips or refuses a `run` plan)? | Yes (D311) | §6.8, §8.1 | `B29 Q13 veto`: a `ServiceLoader` step seam in `PlanExecutor` for model steps in `live` |
| Q14 | **`inference.vision` is `OK` only in a vision session**, and a visual check under `PlanExecutor` is skipped unconditionally? | Yes (D312, D327, D354) | §8.4, §8.5 | `B29 Q14 veto`: `OK` under `PlanExecutor` in `live` with an evaluator seam (Q13's veto implied) |
| Q15 | **Controller-side capabilities merged into the matrix** through `CapabilityContributor`, `UNAVAILABLE("replay")` in replay, `agentHash` kept for the goldens, the host feature through `SessionOptions.host`? | Yes (D326, D348) | §8.5, §9.2 | `B29 Q15 veto`: keep the agent-only matrix; `inference.*` guards stay on the "missing" path and the design's §3.10 sentence is withdrawn |
| Q16 | **Baselines keyed by a rendering key** in the bindings file (version 2), the fingerprint unchanged, the key hashing the Java line, the look and feel, antialiasing, the screen scale and a probe render (not the OS, the full version, the architecture or font names)? | Yes (D329, D330, D350) | §8.7, §3.8 | `B29 Q16 veto`: the fingerprint carries `skillroy.render.*` for a plan with images (one bindings file per target Java for such scenarios); the `images` section stays; `B29 Q16 veto vendor`: the vendor and the full version join the key (the amd64 leg then needs its own baselines) |
| Q17 | **Exact comparison** with `maxDiffPixels` 0 by default? | Yes (D314) | §6.7 | `B29 Q17 veto <n>`: a default margin of n pixels |
| Q18 | **The image comparison in `skillroy-scenario`**, not in `skillroy-vision`? | Yes (D313) | §6.7 | `B29 Q18 veto`: in `skillroy-vision`, which CI then needs for anchored steps and `expectImage` |
| Q19 | **The `SessionTools` seam** (one loop, the service's facade) rather than a second loop in the vision module? | Yes (D302, D353) | §9.1 | `B29 Q19 veto`: a second loop with its own actor in the CLI and the MCP server |
| Q20 | **Provenance** as an optional object on the journal step, handed in by the acting call, cost staying in the ledger by request id? | Yes (D331, D351) | §9.2 | `B29 Q20 veto`: tokens and cost copied into the step record (P5 §12.3 amended) |
| Q21 | **The gateway's additive changes**: `providerTools` + `PROVIDER_TOOLS`, `toolsetName` on both parts, image parts in a tool result (text-only when `error`), `CacheHint.AUTO`, `IMAGES` on the recorded and the scripted gateway, the Haiku 5.5 row with the class default unchanged, `effort.vision` `low`? | Yes (D318-D324) | §7 | `B29 Q21 veto <item>`: naming the item (`providerTools`, `toolsetName`, `imageResult`, `auto`, `recordedImages`, `haiku`, `effort`) and the alternative of §17's row |
| Q22 | **The defaults and limits**: V1 with marks, Sonnet 5.5 at `low`, the class `haiku` for V1 only, the toolset for V2/V3 with `zoom` off, captures unscaled up to 1920 and never below 0.75, USD 1.00 / 30 actions / 20 turns per session enforced by the service? | Yes (D304, D353, §10) | §6.2, §6.3, §10 | `B29 Q22 veto <item>`: the item and its value |
| Q23 | **`LLM_VISION` and `UiTarget`** stay reserved? | Yes (D316) | §8.9 | `B29 Q23 veto`: the binding-shaped recording with a step-time verifier is built |
| Q24 | **Plan version 6** with one regeneration of every committed plan and bindings file, and the session recordings regenerated on the scripted route (the pack version in their system text)? | Yes (D325, D337) | §8.2, §12.5 | `B29 Q24 veto`: no regeneration; then no pack bump (Q25's veto) and the vision export alone |
| Q25 | **The sample's number pad** on the desk (with the mask on the field and the pad, the legend, module 0.2.0, pack 0.6.0, gap 49's argument)? | Yes (D337, D358) | §12 | `B29 Q25 veto shelf`: a shelf map instead; `B29 Q25 veto toy`: a region component in tests only (the sample then shows V1/V2 without regions) |
| Q26 | **`aiExpect`'s `UNSURE`** skips the check (`DEGRADED` at best); `NO` fails? | Yes (D317) | §6.8 | `B29 Q26 veto`: `UNSURE` fails |
| Q27 | **JxBrowser input** behind descriptor roles, unverified, with the kit against the fake **(owner's knowledge: is a licence holder's run near?)** | Yes (D336) | §4.5 | `B29 Q27 veto`: JCEF only; the `jxbrowser` adapter never reports `INPUT` until verified |
| Q28 | **`web.pointer` honours a blocking modal dialog** (an AWT-only check in the web module)? | Yes (D335) | §4.3 | `B29 Q28 veto`: no check |
| Q29 | **The protocol stays 0.2**? | Yes (D299) | §5 | `B29 Q29 veto`: `0.3` (`ProtocolConstantsTest` and the discovery file's `protocol` change) |
| Q30 | **The security delta review** (eight lenses with sceptics over the diff since `e3ceece`, S101 onwards, nothing medium or above accepted, the owner signs B31)? | Yes (D338) | §15 | `B29 Q30 veto full`: a full review of the tree; `B29 Q30 veto none`: no review before the next release (against the checklist) |
| Q31 | **No separate `act` command**: `ask` with `--vision` is the interactive form; `--play` runs a scenario? | Yes (D333, D339) | §9.3 | `B29 Q31 veto`: `act <intent>` and `skillroy_session_act` beside `ask` |
| Q32 | **The loop's reads unjournaled** through `ExecutorSession.read` (which throws the agent's error and never masks an image's `base64`), their hashes in the provenance? | Yes (D308) | §9.2 | `B29 Q32 veto`: every capture and hit-test a raw read step in the journal, left out of the export by `reads` |
| Q33 | **The CI image stays WM-less**; Robot checks run WM-less with the option (and `requireFocus: false`); no openbox leg? | Yes (D340) | §13.2 | `B29 Q33 veto`: an openbox leg from `spikes/BS9/docker/` in the matrix |
| Q34 | **V3 on a person's desktop acts only while the target holds the focus**, checked by the agent at the press (`requireFocus`, default true), and the loop stops the turn when the target loses it instead of clicking to win it back; V1 and V2 never move the pointer. Right default? **(owner's knowledge: how the overlay sessions will be run)** | Yes (D344) | §3.4, §11 | `B29 Q34 veto`: V3 keeps acting after a focus loss (the loop passes `requireFocus: false`), reporting `NOT_DELIVERED` per action |
| Q35 | **A capture of the glass never reaches a model or a client**: V3 observes through a redacted `PAINT` composite ("the model sees `PAINT`, Robot acts"), and `swing.capture {mode: ROBOT}` is a person's evidence with everything outside the JVM's windows blanked? | Yes (D341) | §3.4, §6.2, §9.1, §11, §16.1 | `B29 Q35 veto`: a glass check built in the agent (a `ROBOT` raster compared with a root-pane `PAINT` of each window, the differing regions blanked, `covered: [rects]` reported) and `capture {mode: ROBOT}` offered to the model in V3 |
| Q36 | **No credential is ever used on a model's or a client's behalf**: a `$slot.secret` reference and a verb given a credential-joined slot refused for every origin but a person's, no `type` while a credential is in flight, a `type` holding a known secret value refused, a client scenario's base verb that names a credential field refused by `PlanPolicy`, sign-on by the person or the player before the loop? | Yes (D342) | §6.5, §9.1, §9.2, §9.4, §11 | `B29 Q36 veto`: the reference refusal for raw calls only, the recorder allowed to pass a secret reference into a masked field (the pixel path then rests on the pack's mask alone); `B29 Q36 veto verb`: a model or a client may run a pack verb with a credential-joined slot (a model's `signOn`), the verb's own join consuming the in-flight flag |
| Q37 | **A Robot press is always paired with its release**: `PRESS`/`RELEASE` refused through Robot, the toolset's `left_mouse_down`/`left_mouse_up` answered as text in V3, the release in a `finally` before the pointer is restored? | Yes (D343) | §3.4, §6.3 | `B29 Q37 veto`: the halves allowed through Robot with the release on the next call or at the turn's end (the pointer not restored while a button is down) |
| Q38 | **The controller draws the marks** on its own copy; the agent returns geometry only, one raster and one hash? | Yes (D346) | §3.1, §6.2, §9.6 | `B29 Q38 veto`: the agent draws and returns both rasters (`base64` and `markedBase64`), the 4096-pixel cap bounding each |
| Q39 | **Image baselines are approved at their step** (`Approver.reviewImage`, the candidate shown first, `--yes` approving) and the bindings file is rewritten after the steps, `images` carried forward on every rebind; anchors never approved but re-recorded by a session? | Yes (D355) | §6.7, §8.7 | `B29 Q39 veto batch`: one batched image approval at the run's end; `B29 Q39 veto bind`: baselines approved at bind time from the previous run's candidates |
