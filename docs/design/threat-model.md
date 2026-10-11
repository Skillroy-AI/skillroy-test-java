# Threat model of the base framework

**Status:** written 2026-10-09 with the Phase 6 contract (`phase6-contracts.md` §12.7, decision D148) and revised the
same day after the contract review and pass 1 of the repeat security review; cited by every lens of that review (§12.3
there) and by `SECURITY.md`; **extended 2026-10-10 for Phase 7** (`phase7-contracts.md` §11 and §15, decision D334: the
rows and lines marked "(Phase 7)" below, written with the contract and completed by its build and its security delta
review, B31; revised the same day after the contract review: the §3 bullet, the §5 paragraph, the §6 rows, the §7
line and the §8 lines; **completed 2026-10-11 by the delta review's fixes** (findings S101-S148, `phase7-contracts.md` §15,
marked "Phase 7 security fix" below): the delta review is run and fixed and its sign-off, B31, is the owner's and open). It says who the adversaries are, where the
trust boundaries run, what the framework trusts on purpose, and what is out of scope, so that a reviewer's "who can
reach it" and a release reader's "what does this protect me from" rest on one text. Where this document and a
contract disagree, the contract has the detail and this document the judgement; change this one first when the
judgement changes. References: `base-framework-design.md` (`design`), the phase contracts (`P1`-`P6`),
`../DECISIONS.md` (B13, B17, B23-B25), the security review of 2026-10-02 (`../TODO.md`).

## 1. What the system is, in one paragraph

An **agent** (Java 8) runs inside a target JVM, a desktop application under test, loaded at launch (`EMBEDDED`,
`JAVAAGENT`) or by attach (`ATTACH`). It serves JSON-RPC and SSE on a loopback port, authenticated in both directions
with HMAC-SHA256 over a 32-byte token that never travels (H2). Through it a **controller** (Java 25) drives the
application: its input seam, its decision code as verifiers, read-only data channels, embedded web views, and, in
Phase 6, catalogued bytecode hooks and ad-hoc rules. A **domain pack** (controller) and an **agent module** (target)
teach the framework one application; a **tenant overlay** varies a pack for one deployment. A **model** is used only
where decisions B6, B7 and B15 allow it: at authoring time, at bind time in `live` and `approve`, and between steps in
the interactive profile, never in CI (`replay` makes zero model calls, asserted). An **MCP server** projects the
primitives and the pack's verbs as tools for a client. Runs leave files behind (`run.jsonl`, bindings, ledgers,
evidence) and, in Phase 6, may export spans to a telemetry backend.

## 2. Actors

| Id | Actor | What it can do without the framework's help | Trusted? |
|---|---|---|---|
| A1 | **Another OS user on the same host** | see the loopback port and the process list; try to bind a freed port; plant files in shared directories (`/tmp`, a shared checkout); on a shared Windows host, inherit a permissive ACL from a parent directory | no |
| A2 | **A same-user process without the token** | everything A1 can, plus read files the user owns that are not owner-only, attach to the user's JVMs (the attach API's own trust boundary, B13), publish `assist.requested` if it runs inside the target | no; but see §4 (the same-user attach is the platform's boundary, not ours) |
| A3 | **A browser page or web content inside the target** (embedded web view): **an adversary of the controller as much as of the agent** | run script in its own page: read and replace the injected library's slot, forge result objects, flood the tap, keep a drain outstanding, echo typed values to its console; and feed the **controller** page-controlled data (URLs, titles, aria text, counts, result shapes) that its parsers, regexes and the planner read: every bound on that data must hold on the controller's side, not only in the page's own library; with a debug port open, anyone on the host can drive the browser | no |
| A4 | **The target application and its content** (its data, events, texts, pages) | answer with hostile values: driver error texts that echo parameters, rows and events crafted to confuse the controller's parsers, names and texts of any length, a modal dialog at any time; its content reaches the controller's expectations, reports and regexes, and the model's prompts and the planner's context (so it is also what steers A5); its own code runs in the same JVM as the agent | no: its values are data; its code is the thing under test |
| A5 | **The model** (and, through it, whatever text it read: screen content, application data, a page; since Phase 7 the pixels of a capture too) | propose actions, SQL, scenarios, labels; be steered by what it reads (prompt injection); **hold the token** through the session service and the planner's server, within the tools and gates offered to it (§4); (Phase 7) name a pixel to click, which becomes an action only through the hit-test and, in V1 and V2, the step that will be replayed; in V3 a Robot press at the hit-tested point under the double opt-in, journaled live-only (P7 §6.4, §6.5) | no: the model proposes, never confirms (§5) |
| A6 | **An MCP client** | call every tool the server offers, read every resource, send any `_meta` and any argument value (a `$reference`, a probe argument that differs from the real one), claim to be a person; **hold the token** through `skillroy mcp`, within the server's policy (§4) | no: a client is often a model |
| A7 | **The planner's CLI and what is on `PATH`** | the planner runs the first `claude` on `PATH` headless under `dontAsk` with a mutating server; the CLI keeps its own transcript | partly: the executable is the operator's (§3), its transcript at rest is a known limit |
| A8 | **A remote network party** | reach nothing: the agent binds loopback only; the controller opens connections to the model provider and, in Phase 6, to a telemetry backend | no |
| A9 | **A telemetry backend's operator** | read every span attribute the exporter sends | no: hence the allow-list, masking and TLS rules (P6 §10) |
| A10 | **The pack author** (a domain pack, an agent module, an overlay, a hook catalogue) | run code in the controller and in the target; name tables, columns, hook targets | **yes**: the pack is reviewed code (§3) |
| A11 | **The operator** (whoever runs the CLI, the tests or CI) | set environment variables, `PATH`, the agent home, the credentials file, the options; hold the token | **yes** (§3) |
| A12 | **Other code in the target JVM**: another bytecode agent, a profiler, an application plugin | retransform the same classes, redefine them, register transformers before or after ours; **call the public statics `Spy.enter`/`Spy.exit` and `RuleHelper.fired` and so forge a hook or rule event**; claim `Spy`'s slot if it were ever free; reach, by reflection, the package-private statics through which the Byteman module takes its `Retransformer` (§7); on Java 8, have its own redefinition reverted by the engine's restore (BS3, case BC4) | no: it is not hostile by assumption, but the engine must survive it and report what it cannot see (P6 §3.5, D154); hook and rule events are evidence against non-adversarial application code only (§3) |
| A13 | **A reader of what a run leaves behind**: whoever reads `run.jsonl`, `failure/`, `capabilities.json`, bindings, ledgers, `spans.jsonl`, a planner's envelope, an MCP `--out` directory, a CI artifact | read every value written there; on a shared host, read a run output tree that took the process umask (pass 1, S37) | no: hence P2 §11.4's "never written" list, the masks on every capture and attribute, and the `umask 077` rule for shared hosts |
| A14 | **The model provider**, or whatever `ANTHROPIC_BASE_URL` names | read every prompt and image sent; answer anything | no for its answers (A5); its handling of what it received is out of scope (§7); the masks run before a request is built, and the key goes to it alone (P5 §2.3, §6.2); (Phase 7) an image reaches it only as a redacted `PAINT` capture of the target's own windows, never a capture of the glass, and only under `images: true`; pixels carry no value mask, so what the application renders in a component no mask names reaches it as rendered (P7 §3.4, §3.6, §11) |
| A15 | **(Phase 7) Whatever else is on the desktop where the V3 tier runs**: another application's window, a notification, a screen lock, the person's own hand on the pointer | receive a Robot click aimed at the target, or take the pointer while the agent moves it; on macOS without the Accessibility permission swallow every event silently (BS9) | no: Robot acts on whatever is at a screen point, so V3 is behind a double opt-in (the agent option `robot` and a person's `--allow-robot`) and is never a per-call choice of a model or a client, refuses a point outside the target's windows, a point in a minimised frame or a window it owns, a point no screen shows (Phase 7 security fix, S104, S105), `modifiers`, and a point any window of the target JVM covers, requires the target JVM to hold the focused window at the press (`requireFocus`, read on the EDT immediately before the pointer moves), never leaves a button pressed (a press and its release in one task, the release in a `finally` before the pointer is restored), probes delivery, reports `NOT_DELIVERED` instead of a success it cannot see, and on a person's desktop the loop stops the turn when the target JVM loses the focus instead of clicking to win it back (P7 §3.4, §11) |

## 3. What is trusted, on purpose

- **The pack as reviewed code.** A `DomainPack`, an `AgentModule`, a `DomainPackOverlay`, a hook catalogue and a
  Byteman rule a person submits with `confirm` are code that a reviewer committed. A hostile pack is out of scope
  (§7); a **wrong** pack is a defect the framework must survive without harming the host: the engine probes every
  hook target, refuses protected packages and loaders, bounds class sizes, restores and verifies, and never lets an
  advice throw into the host (P6 §3). Oracles are pure; cross-checks and verifiers run the application's own decision
  code. **The committed scenarios, plans, bindings and recordings are reviewed code in the same sense**: a reviewer
  diffs them, what the planner exports stays `REQUIRED` until a person approves it (D97), and `replay` runs only what
  was committed. **(Phase 7) Image baselines and anchors are committed reviewed code in the same sense**: a baseline
  shows the application's data as rendered, masked per component only (the password rule and the pack's `CaptureMask`),
  so the author masks with `expectImage`'s `mask` what must not be committed, a reviewer looks at the PNG as at the YAML,
  and `approve` and the export write one only after the person saw the file (P7 §8.7); an images-only `approve` writes the bindings
  file only with a person's approval, at a step for a baseline or of the change for anything else, and a client's
  `skillroy_scenario_run` never writes into the bindings root (Phase 7 security fix, S115).
- **Hook and rule events as evidence.** An `OBSERVE`, `CAPTURE` or `rule.fired` event says that the hooked method ran
  with those values only if no code in the JVM called `Spy.enter`/`Spy.exit` or `RuleHelper.fired` itself (A12): the
  statics are public because an advice must reach them from every loader. A hook expectation is therefore evidence
  about non-adversarial application code, as every verifier that runs the application's own code already is; it is
  not a tamper-proof record, and no scenario may treat it as one.
- **The operator's environment.** The shell's environment variables (the model key, a telemetry header's variable,
  `PATH`, `JAVA_HOME`), the home directory, the agent home with its jars, the pack jars given on the command line,
  the Byteman jar the agent option `byteman.jar` names (P6 §7.4: an absolute path in a directory no other user may
  write to, never an environment variable), **the module jars the `modules` option names and a spy jar `hooks.spyJar`
  names, under the same rule** (Phase 6 security fix, pass 2, P2-L1-F5: one predicate, `JarTrust`, P6 §3.6; a jar
  another user could have replaced is refused, not loaded) and the credentials file are the operator's own and are
  trusted as given:
  the framework reads the model key from the environment only, never from a file, an option or a command line
  (P5 §2.3), reads a telemetry header's value from a variable the settings **name** and never from the key's variable
  (P6 §10.3), and executes the `claude` found on `PATH`. What the framework does with them is bounded (nothing of them
  is written, printed or sent anywhere but where the operator pointed it), but a compromised operator account is out
  of scope.
- **The JDK, the Maven cache, the CI image and its actions.** The build runs offline against the local repository;
  what is in it is trusted. The CI example's base image and actions are the operator's to pin in their copy (pass 1,
  S40). The audit (`../licence-audit.md`) reads licences, not malware.
- **The file systems' own guarantees.** Owner-only means POSIX `0600`/`0700` where the file system has POSIX
  attributes, an ACL with one `ALLOW` for the owner where it has ACLs (Windows; built and unit-tested on built ACL
  values, **not verified on a Windows host**), and a refusal where it has neither (a credentials file is the one
  exception, accepted with a warning, P2 §4). The kind is decided per volume, a FAT or exFAT volume is "neither"
  whatever its views say (a credentials file there is refused too, and a module, spy or Byteman jar there is
  "writable by others", `JarTrust`), and what the POSIX branch creates is read back exactly (pass 2, P2-L7-F1, P1
  §2.5); a volume mounted with ignore-ownership whose type keeps permissions is not detected. A directory another user
  owns, or that others may write to, is refused by the agent and ignored by the controller (S8, S19, H3). **The path to
  it counts too** (Phase 6 security fix, pass 2, P2-L1-F3): the controller refuses to write into a directory with a
  directory above it that others may rename entries of (group- or other-writable without the sticky bit; on ACL file
  systems a delete or take-over right; on a volume with neither, always), and the agent reads an options file only
  when it is a regular file of the current user that nobody else may write (nor, when it holds a token, read), so a
  directory or file another user put in place of the operator's is refused rather than trusted (P1 §2.4, §2.5, §7).
  **The operator's settings files and run output count too** (Phase 6 security fix, pass 2, P2-L8-F1, P2-L8-F3,
  P2-L8-S1): `telemetry.yaml` and `inference.yaml`, which the controller reads implicitly beside the credentials file,
  are trusted only when nobody but the current user could have written them (P6 §10.3, P5 §4.4); a planted one is a
  settings error, not a silent skip. A directory a run writes its output into must be the current user's and not
  writable by others without the sticky bit, and every output file is created exclusively, never through a link or a
  file already in its place (P2 §11.3), so `umask 077` keeps what a run writes from other users even in a directory
  another user prepared.

## 4. The token rule

The token (32 random bytes, in an owner-only file, the key of every request and reply MAC) **is code execution in the
target**: whoever holds it may do what the agent can do, within the agent's own gates (read-only mode, `confirm` for
destructive primitives, the production marker, the bounds). That is the design (B13, B17), and the controller is its
holder. Consequences for the review (P6 §12.4): a finding that **needs the token** to reach is recorded as
"token-holder only" and not counted, **unless the token's user is a model or an MCP client** (A5, A6), who hold it
through the session service, the planner's server or `skillroy mcp`: then the finding counts at the severity of what
the model or client gets. The first pass of the repeat review (2026-10-09, P6 §12.2) rated on exactly this rule: its
one high and six mediums are paths from a client's or a model's argument, or from a page's or the target's content, to
something the policy never offered or never bounded on the controller's side. The same for the **operator's
environment**: a finding that needs the operator to configure something against themselves is "operator-only".

What the token does not give: a way into another JVM (every start has a new token and session id; a captured MAC is
useless against a later instance, H2), a way to enable an agent that was not enabled (the enable flag is checked
before any thread or port exists), a way past the production marker.

**The same-user attach boundary.** Any process of the same OS user can attach to the user's JVMs and start the agent
in them with `enabled=true` in the attach options (B13: attaching is itself a deliberate act by a same-user process).
That is the attach API's trust boundary, not the framework's; `-XX:+DisableAttachMechanism` closes it, and
`SECURITY.md` says so.

## 5. The model rule

The model **proposes and never confirms**. A model's request is never a confirmation (a `confirm` from a `MODEL`
origin is stripped, P5 D87, D89, and so is one from a `CLIENT` origin, which is what the MCP server and the planner's
server act under, P6 D158); nothing a model produces reaches `data.query` except through the SQL guard and the
allow-list, applied to the statement as it will run (P5 §5; pass 1, S55), installs, arms or removes a hook, submits a
Byteman rule, runs a script primitive (P6 §4.2, §7.3; D96, D98, D156) or uploads a module; the tools it is offered are
the pack's verbs and, by policy, base primitives that are never destructive, and a verb's compiled calls are checked
with the real arguments at call time (D158); its budget is bounded, once per session for every call made on a
client's behalf (D157), and `replay` makes no call at all. **What the model reads from the target is untrusted
input** (screen content, application data, a page's text): the framework assumes it may be adversarial and keeps the
gates on the action side, where a person's flag is needed. **A value the model resolved is target content** (A4): it
is masked, bounded and guarded as one, never trusted because the model found it. A tier (b) binding needs a person's
approval; an exported scenario's model-found slots are `REQUIRED` until a person approves (D97). A prompt carries no
credential, personal value or key (P5 §6.2): the masks run before a request is built, and a recording is masked again
on its way to disk.

**(Phase 7) What the model sees on the screen is untrusted in the same way, and it is always the target's own
windows.** A capture is personal data until masked, so the agent redacts it before it leaves the JVM (`redact: true` by
default, the built-in rule for password fields and the pack's `CaptureMask`, which withholds the component's text as
well as its pixels), and nothing a model or an MCP client says can switch that off (P7 §3.6, §9.1). What a model
receives is a redacted `PAINT` capture of the target JVM's windows in every tier; a capture of the glass (`mode:
ROBOT`), which holds other processes' pixels and the desktop, is a person's evidence only and is refused for a model or
a client on every route (P7 §3.4, D341). Pixels carry no value mask: a component no mask names reaches the model as
rendered, which is why **no credential is ever a model's or a client's argument**: a `$slot.secret` reference, the
credential-joined slot as a whole (`$slot` in any spelling, whose value carries the joined fields) and a verb given a
credential-joined slot are refused for every origin but a person's before anything is journaled or sent, and such a step's
call arguments resolve against the slots' public values only; a client's scenario run meets the same rule in the server's
plan policy (a base verb or raw call naming a credential slot's field or the slot itself, and a pack verb's arguments as the
client wrote them, judged by step identity, with every caller passing the written scenario); nothing of a model's or a client's
changes the target while a credential is in flight; a slot the person bound is never rebound on their behalf; and sign-on
runs through the pack's verb by the person or the player before the loop (P7 §9.2, §9.4, D342, D425, D427; Phase 7 security
fix, S101, S102, S107, S113, S127, S128).
Text in a capture may carry instructions (vision §Risk7): the tools a vision session offers are the target's own
primitives through the pack's verbs and the module's own tools, each acted through the session service's facade under
the same `CallPolicy`, which the hosts' raw primitive paths run too (P7 §9.1). A pixel the model names becomes an
action only through `swing.hitTest` (grounding), the stale-capture check and, in V1 and V2, the step that will be
replayed, under `Origin.model`; in V3 a Robot press at the hit-tested point under the double opt-in, journaled
live-only; a locator the mask would change is never recorded; the vision module's tools never click, type or press a
key at a component a mask names, nor with V2's raw button halves or a move onto one (P7 §6.3, §6.4, §6.5, §11). The person's
clipboard and the X11 selection are not the target's: no paste, copy or cut chord and no middle button is ever sent for a model
or a client (`CallPolicy`, every route; P7 §11 rule 11), and a browser a pack's mask names is masked on every route: no
pixels, no text, no tree, in the loop's image, the session's snapshot and the raw `web_*` reads (P7 §3.6; Phase 7 security
fix, S110, S111, S112). A visual verdict (`aiExpect`, `skillroy_look`) is advisory
behind the capability `inference.vision`, which is `UNAVAILABLE` in `replay` and in every run that is not a vision
session, so it never turns a failing scenario green and never gates CI. The one path from a model to the operating
system's input queue, the V3 tier through `java.awt.Robot`, is a person's choice twice over (the agent option `robot`
on the host and `--allow-robot` on the command), never a tool argument and never a per-call choice of a model or a
client (P7 §3.4, §9.1).

## 6. Trust boundaries, and the mechanism at each

| Boundary | Mechanism | Where specified |
|---|---|---|
| controller ↔ agent (the control channel) | loopback only; HMAC of every request and reply with the token as key; a fresh nonce per request; the request MAC verified on the head alone, over the body hash the head declares, so that no body is read for a request without the key (Phase 6 security fix, pass 2, P2-L1-F1); bounds that apply before authentication (head size, deadlines, connection count, body budget); `Host`/`Origin` checks; the client's terminal *lost* state | P1 §5, §7 (H1, H2, H3) |
| the discovery directory and every owner-only file | per-user directory with a deterministic fall-back; owner-only files; refusal of a directory another user owns or others may write to; stale files removed; the token read only inside the directory under its default name | P1 §2.5, §7; P6 §9.3 |
| the agent against the host application | an isolated child-first loader for everything but the bootstrap jar and, in hooks mode, `Spy`; daemon `skillroy-*` threads; bounded waits; actions posted to the EDT, reads bounded; never AWT before the application; **a thread the application forbids is never used, and an advice body only publishes to the bus on the thread it fired on**; redaction of secret-looking keys; the enable flag and the production marker; read-only mode and `confirm` | P1 §2.2, §5; design §2.2; P6 §3.4, §4.3 |
| the bootstrap path (hooks mode) | one class, `Spy`, with a set-once, weakly held, identity-released handler slot that is **never free while any class carries an advice** (a detach that ran out of budget keeps its disarmed handler installed, and a later agent is refused); advice bodies that call nothing else and never throw; a visibility probe before any install; the jar's content checked before the one append; the `Nexus` measure so that Byte Buddy injects nothing else | P6 §3.1-§3.3, §3.5 (D116, D117) |
| the hook engine against the application's bytecode | a deny-list by package name and by loader; the class size limit (256 KiB or 2,000 methods); explicit discovery, never a scan of the host's classes; the ledger; restore and verification in one retransformation with a foreign-event count; `STUB` behind a destructive primitive; `CAPTURE` masked by index, policy and key, bounded on the input | P6 §3, §4 |
| Byteman (optional) | loaded in process, **no listener**, rules over the authenticated channel under the dispatcher's gates; the `Retransformer` handed to the module once and **never published** (package-private `take()`/`park()` reached by reflection; no public accessor on the system class path at any time); `rule.check` loads neither Byteman nor a class; a lint deny-list matched as identifiers (a tripwire for Byteman's built-ins and the entry points it names, not a sandbox: `confirm` on every submit is the gate; fix wave, FB, 2026-10-09); a bound on the classes a rule may retransform, checked before Byteman is loaded; the jar checked at its real path; a curated helper; unload by name under the module's prefix | P6 §7 |
| the data channels | SELECT only; a statement guard on the agent, `SqlGuard` on the controller; read-only roles and server-side timeouts; row caps; row masks; credentials from `credentials.yaml` only and never written as a value | P2 §4, §7, §11.4 |
| embedded web views | the base connects only to a debug port the application configured and checks the endpoint's ownership before reading its body; the attach dials the browser WebSocket at that port's own authority, never a host or socket an answer names (a peer holding the port can still redirect the driver's WebSocket handshake, §8); every library call is bounded in the page by a guard of the caller's own text, and the page's data is bounded and parsed again on the receiving side of both paths, never trusted; a page's console output is budgeted per browser on the technology's own thread (past the budget the adapter stops console capture until a new document); traces in an owner-only directory, never with a secret in flight; script primitives carry the `script` flag, which read-only mode refuses on both paths and the MCP policy gates | P4 §4-§6, §8.3; P6 D156; P4 §4.3, §4.6, §5.1 (pass 2, P2-L4-S1, F1, F2) |
| controller ↔ model | the key from the environment only, never written; masks before every prompt (**a secret-named property's secrecy inherited by everything nested under it and by a definition it references; a capture's bytes never as text**; security pass 2, SC); recordings for CI; budgets; the SQL guard on the statement as it will run; **what the provider returns scrubbed of the key and bounded before it is read** (pass 2, SC); a model's output acted on only through the gates above | P5 §2.3, §4-§7 |
| controller ↔ MCP client | read-only by default, mutating by flag, destructive never; **`hook.*` and `rule.*` primitives never offered, except the reads `hook.health`, `hook.events` and `rule.list`**; the server acts under a `CLIENT` origin that never confirms, and `confirm` and `_meta` are never taken from a client; a pack verb's compiled calls checked at call time against the server's policy; one session budget over every model call made for a client; **a client's scenario run under the server's hooks switch**; results cut and masked; a capture's bytes only under the inference settings' `images: true`, **as an image only, never as text on any route** (security pass 2, SC) | P5 §9 (D76, D96-D98); P6 §4.2, §6.1, D157-D159 |
| controller ↔ telemetry backend | off by default; explicit settings, no autoconfiguration from the environment; loopback-http or https only, **a header over https only** (pass 2, P2-L8-F2); **the settings file the current user's** (pass 2, P2-L8-F1); headers from named variables, never written and never the model key's variable; attributes from an allow-list, masked and bounded, **the messages without paths** (pass 2, P2-L8-F4); never parameters, values, payloads, prompts or credentials | P6 §10 |
| the pack ↔ the base | a pack is reviewed code (§3); the base bounds what a pack's code receives (masked rows, bounded reads) and what it may declare (allow-lists checked against the database, D100) | P2 §6, P5 §5.2 |
| **(Phase 7)** the agent against the host's desktop (the V3 tier) | `java.awt.Robot` constructed only behind the agent option `robot`, after the application started AWT (A4), on a worker and never the EDT; a `via: ROBOT` or `mode: ROBOT` call refused by the executor, the session and the raw `call` command without a person's `--allow-robot`, `mode: ROBOT` refused for every origin but a person's whatever the flag, and `via: ROBOT` never a model's or a client's per-call choice (`CallPolicy.arguments` on every route; the vision module's own V3 steps alone pass it); a point outside every showing window of the target JVM refused, any covering window of the JVM refused; the target JVM must hold a focused window at the press (`requireFocus`, default true, read on the EDT immediately before the pointer moves); a press always paired with its release in one task, the release in a `finally` before the pointer is restored (`PRESS`/`RELEASE` refused through Robot); a bounded delivery probe before the first action and `NOT_DELIVERED` when no press arrives, so a silent drop (macOS without the Accessibility permission, BS9) is never reported as a success; a press received by another component reported as a failure; on a person's desktop the loop stops the turn when the target JVM loses the focus | P7 §3.4, §9.1, §9.2, §11 |
| **(Phase 7)** controller ↔ model, pixels | a capture reaches a model only as a redacted `PAINT` capture of the target JVM's own windows (never a capture of the glass, D341), redacted by the agent before it left the JVM, only under the inference settings' `images: true`, only as an image part (never as text on any route, D244; its `base64` never masked and never on a text route), its hash in the journal's provenance and in a recording; a page's pixels composited only into a surface no mask names (masked as a whole component or not at all); the marks list and the hit-test's names as target text inside `<target-ui>`, the model's own question inside `<model-question>`, the pack's screen legend and a tool's instructions in the system parts masked as the vocabulary is; no credential offered (the `$slot.secret` refusal, the refusal of a model's or a client's verb with a credential-joined slot, the plan policy's form for a client's scenario and the in-flight rule, D342); the budget reserved with the image's tokens before the call; every vision call through the service's metered, ledgered `visionCall`, so `replay`'s zero-call assertion covers it | P7 §3.6, §4.8, §6.2, §7, §9.2, §10 |
| **(Phase 7)** the model's pixel ↔ the target | a coordinate acts only through `swing.hitTest` (grounding) and, when the window set changed since the capture, a fresh capture and a same-component check; the recorded step is then guarded by the verb's exact-match locator (`expectTarget` closes the window for the live-only raw calls and an MCP client's call; the residual is gap 87); the recorder refuses a masked target for every input and a screen-text locator the mask would change; the routed pointer keeps the modal check before the first event and before every further one, and the covering-window check, that AWT's modal loop and stacking would apply to a person, and acts on nothing after its call failed (a dispatched press is disarmed and released without an action); `web.pointer` refuses a browser whose window a modal dialog blocks on both paths, the CDP path through the agent's `web.browsers.blockedBy` (D404, D416), **and the recorder refuses a page hit under a modal dialog before `webClick`**; a `webClick` is recorded only for the frame the call started in and the browser the step resolves to; the CDP path dispatches nothing after the call's deadline (Phase 7 security fix, S109, S130, S132, S133); the stale-capture rule compares the hit with what the capture showed (V1's mark, the window at the pixel; S114); `force` on every input primitive, `via: ROBOT`, `mode: ROBOT` and `redact: false` are never a model's or a client's to give (`CallPolicy.arguments` at every host's raw primitive path and at the service's facade) | P7 §3.3, §6.4, §6.5, §9.1 |
| **(Phase 7)** evidence at rest, pixels | captures the loop acted on (`captures/<sha256>.png`, the raster as captured, never a capture of the glass), image baselines and anchors beside the bindings file (reviewed code, §3; the file's path derived from the label and the render key and resolved inside the bindings directory without links), and `failure/image-*.png` hold masked pixels only, are written through `OutputFiles`, never travel on a text route, take the process umask (`umask 077` on a shared host), and are served to an MCP client only under `images: true`; `failure/image-*.png` hold the compared rasters with the step's masks blanked, and `failure/capture.png` the window as the agent redacted it (Phase 7 security fix, S145); a session without `--out` writes into a new owner-only temporary directory (S148) | P7 §8.7, §9.6, §11 |

## 7. Out of scope

- A compromised operator account, shell, JDK or Maven cache; a hostile pack, agent module, overlay, catalogue or
  committed rule (they are reviewed code, §3).
- Physical access to the host; another user with administrative rights on it (they can read anything).
- Code already running in the target JVM that reflects on the agent's or the Byteman module's internals with
  `setAccessible` (A12 as an adversary): the gates hold against the channel, the page, the target's content, the model
  and the client, not against code with reflective access inside the same JVM (P6 §7.1).
- Denial of service **by the operator** against their own target (a hook on a hot method, a huge catalogue): the
  framework bounds what it can (sizes, budgets, timeouts) and documents the costs (P6 §2.4).
- A target's own listeners: a JDWP port is unauthenticated code execution and a larger exposure than the agent;
  bind it to loopback (design §6).
- The provider's handling of prompts once sent (retention is a model-choice matter, design §5), the telemetry
  backend's handling of spans once received.
- (Phase 7) The windows of other processes on the desktop where V3 runs: they are never targets and never driven;
  their pixels are in scope in one way only, that they never reach a model or a client (a glass capture is a person's
  evidence, blanked outside the target's windows, P7 §3.4), and the agent cannot see whether one covers the target
  (§8).
- (Phase 7) The person's clipboard and the X11 PRIMARY selection are a personal asset outside the target: the framework never
  reads them, and never sends a chord or a middle button that would paste them into the target for a model or a client (P7 §11
  rule 11). An application's own Paste item, context-menu command or button is an ordinary click that the framework cannot
  enumerate: a pack masks it, and rule 6 then refuses every input at it (§8).
- Correctness of the application under test: a wrong `PASS` from the application's own defect is a test problem, not
  a security one; a wrong `PASS` from **hostile input** is in scope (High).

## 8. Not verified, as of this writing

- The Windows path: the ACL branches of S7/S15 and of every owner-only file the controller writes, the `.bat`
  launcher and the planner's `cmd.exe /c` form, `JAVAAGENT` as the logged-on user, the rungs and paint capture
  (P6 §9, §13.3): no Windows host exists.
- The `jxbrowser` adapter against the real product (a licence holder's repository runs the kit).
- The live-only paths run only with the owner's key (P5 §15.3).
- The CDP attach's WebSocket handshake: Playwright's driver follows an HTTP redirect of it (Playwright 1.63 has no
  option to refuse one), so a peer that holds the configured debug port before the application's engine binds it,
  while the agent reports no CDP target id, can redirect the driver to another host or to a Unix socket the operator
  can open; it gains an outbound connection with Playwright's headers and Playwright's own commands, never the run's
  scripts (P4 §5.1, the residual of security fix P2-L4-F1, accepted by the owner, B28, 2026-10-10).
- The retransformation pause on a painting event-dispatch thread of a real application (BS3 measured a spinning
  thread and the safepoint logs).
- A detach that exhausted its budget: the inert advice, the disarmed handler kept in `Spy`'s slot and the refused
  re-attach are specified and unit-tested with a simulated slow retransformation (P6 §3.5, §13.1), not observed on a
  real application.
- A Byteman rule left installed by such a detach is not inert: its condition and `DO` clause keep running (only the
  agent's `rule.fired` stops) until the target restarts; the next agent instance reports it and loads no rule
  (P6 §7.5).
- The Byteman module (built 2026-10-09 behind BS14, B26 Q2) against a real application with its own agents: BS14 and
  `BytemanIT` ran on a toy, and the hooks leg's Byteman check on the sample desk.
- (Phase 7) Robot on macOS with the Accessibility permission granted (BS9 ran this Mac without it and Robot delivered
  nothing, silently; nothing was changed to find out), on Windows (UIPI, session 0, the popup trigger on the release),
  under Wayland or XWayland, and in a locked session (`spikes/BS9/RESULT.md`); the V3 tier is specified for a person's
  desktop and verified on the Linux image's WM-less display (and in BS9 under openbox) (P7 §13.4); the milliseconds
  between the agent's focus read on the EDT and the Robot press, in which another window may take the point, are A15's
  residual (`SECURITY.md`, P7 §11 (c)); a covering window of another process is invisible to the agent on both paths.
- (Phase 7) A person's `ROBOT` capture's two mask reads around the grab: a secret that appears between them is blanked
  by the union, a window that opens between them re-takes the capture, and a component that moves without a window
  event in that interval is the residual (P7 §3.4).
- (Phase 7) The Windows ACL branch of the new writers (`captures/`, `candidates/`, `<scenarioId>.images/`,
  `failure/image-*.png`): built on `OutputFiles`, not verified on a Windows host (the first line of this list).
- (Phase 7 security fixes) A window of the target JVM on another virtual desktop or workspace is undetectable through AWT, so
  `requireFocus` is the only guard there; the iconified refusal (S104) ran on the WM-less display and once under openbox, not
  on macOS or Windows (Windows parks a minimised frame at about (-32000, -32000), now left out as hidden); macOS's routing of a
  button event at an off-display point is undetermined (now refused before any move, S105); the first glass capture on macOS
  raises the screen-recording consent dialog in the target application's name; an in-window change between a V2 or V3 capture
  and the act (a glass-pane dialog that opens no window) is outside the stale rule's trigger (S114); a browser a pack's mask
  names is masked on every route, but that ran against fakes and over real Swing components, not a real masked JCEF browser
  (S111); the paste of S110 and the middle click on an X11 desktop are stated from the JDK's and Blink's behaviour, with the
  refusal tested at every gate; the Windows ACL judgement of the run-output directory (S141) is built and unit-tested on ACL
  values, not verified on a Windows host (a directory under `C:\` on a shared host is refused), and S142's directory junction
  and S144's inheritance of the pixel files are limits proposed for the owner's acceptance (B31; `SECURITY.md`).
- (Phase 7) HiDPI: every spike ran at scale 1; the Swing side reports user-space pixels and the web side an empirical
  factor flagged `scaleAssumed` (P7 §4.8, gap 78).
- (Phase 7) JxBrowser pointer input (`WebCapability.INPUT` behind descriptor roles, strategy "input unverified") against
  the real library: a licence holder's repository runs the kit (gap 41).
- (Phase 7) The vision loop against a live model beyond the `inference-live` profile's runs of P7 §13.3 (BS12 measured
  grounding and short sessions on the sample desk, USD 5.64); the session's fold under the provider's history rule on
  the 5.5 models (P7 §7.4, gap 85).

These are listed again, for a release reader, in `README.md`'s status paragraph and in `SECURITY.md`.
