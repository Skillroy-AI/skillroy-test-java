# Threat model of the base framework

**Status:** written 2026-10-09 with the Phase 6 contract (`phase6-contracts.md` §12.7, decision D148) and revised the
same day after the contract review and pass 1 of the repeat security review; cited by every lens of that review (§12.3
there) and by `SECURITY.md`. It says who the adversaries are, where the
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
| A5 | **The model** (and, through it, whatever text it read: screen content, application data, a page) | propose actions, SQL, scenarios, labels; be steered by what it reads (prompt injection); **hold the token** through the session service and the planner's server, within the tools and gates offered to it (§4) | no: the model proposes, never confirms (§5) |
| A6 | **An MCP client** | call every tool the server offers, read every resource, send any `_meta` and any argument value (a `$reference`, a probe argument that differs from the real one), claim to be a person; **hold the token** through `skillroy mcp`, within the server's policy (§4) | no: a client is often a model |
| A7 | **The planner's CLI and what is on `PATH`** | the planner runs the first `claude` on `PATH` headless under `dontAsk` with a mutating server; the CLI keeps its own transcript | partly: the executable is the operator's (§3), its transcript at rest is a known limit |
| A8 | **A remote network party** | reach nothing: the agent binds loopback only; the controller opens connections to the model provider and, in Phase 6, to a telemetry backend | no |
| A9 | **A telemetry backend's operator** | read every span attribute the exporter sends | no: hence the allow-list, masking and TLS rules (P6 §10) |
| A10 | **The pack author** (a domain pack, an agent module, an overlay, a hook catalogue) | run code in the controller and in the target; name tables, columns, hook targets | **yes**: the pack is reviewed code (§3) |
| A11 | **The operator** (whoever runs the CLI, the tests or CI) | set environment variables, `PATH`, the agent home, the credentials file, the options; hold the token | **yes** (§3) |
| A12 | **Other code in the target JVM**: another bytecode agent, a profiler, an application plugin | retransform the same classes, redefine them, register transformers before or after ours; **call the public statics `Spy.enter`/`Spy.exit` and `RuleHelper.fired` and so forge a hook or rule event**; claim `Spy`'s slot if it were ever free; reach, by reflection, the package-private statics through which the Byteman module takes its `Retransformer` (§7); on Java 8, have its own redefinition reverted by the engine's restore (BS3, case BC4) | no: it is not hostile by assumption, but the engine must survive it and report what it cannot see (P6 §3.5, D154); hook and rule events are evidence against non-adversarial application code only (§3) |
| A13 | **A reader of what a run leaves behind**: whoever reads `run.jsonl`, `failure/`, `capabilities.json`, bindings, ledgers, `spans.jsonl`, a planner's envelope, an MCP `--out` directory, a CI artifact | read every value written there; on a shared host, read a run output tree that took the process umask (pass 1, S37) | no: hence P2 §11.4's "never written" list, the masks on every capture and attribute, and the `umask 077` rule for shared hosts |
| A14 | **The model provider**, or whatever `ANTHROPIC_BASE_URL` names | read every prompt and image sent; answer anything | no for its answers (A5); its handling of what it received is out of scope (§7); the masks run before a request is built, and the key goes to it alone (P5 §2.3, §6.2) |

## 3. What is trusted, on purpose

- **The pack as reviewed code.** A `DomainPack`, an `AgentModule`, a `DomainPackOverlay`, a hook catalogue and a
  Byteman rule a person submits with `confirm` are code that a reviewer committed. A hostile pack is out of scope
  (§7); a **wrong** pack is a defect the framework must survive without harming the host: the engine probes every
  hook target, refuses protected packages and loaders, bounds class sizes, restores and verifies, and never lets an
  advice throw into the host (P6 §3). Oracles are pure; cross-checks and verifiers run the application's own decision
  code. **The committed scenarios, plans, bindings and recordings are reviewed code in the same sense**: a reviewer
  diffs them, what the planner exports stays `REQUIRED` until a person approves it (D97), and `replay` runs only what
  was committed.
- **Hook and rule events as evidence.** An `OBSERVE`, `CAPTURE` or `rule.fired` event says that the hooked method ran
  with those values only if no code in the JVM called `Spy.enter`/`Spy.exit` or `RuleHelper.fired` itself (A12): the
  statics are public because an advice must reach them from every loader. A hook expectation is therefore evidence
  about non-adversarial application code, as every verifier that runs the application's own code already is; it is
  not a tamper-proof record, and no scenario may treat it as one.
- **The operator's environment.** The shell's environment variables (the model key, a telemetry header's variable,
  `PATH`, `JAVA_HOME`), the home directory, the agent home with its jars, the pack jars given on the command line,
  the Byteman jar the agent option `byteman.jar` names (P6 §7.4: an absolute path in a directory no other user may
  write to, never an environment variable) and the credentials file are the operator's own and are trusted as given:
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
  whatever its views say (a credentials file there is refused too), and what the POSIX branch creates is read back
  exactly (pass 2, P2-L7-F1, P1 §2.5); a volume mounted with ignore-ownership whose type keeps permissions is not
  detected. A directory another user owns, or that others may write to, is refused
  by the agent and ignored by the controller (S8, S19, H3).

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

## 6. Trust boundaries, and the mechanism at each

| Boundary | Mechanism | Where specified |
|---|---|---|
| controller ↔ agent (the control channel) | loopback only; HMAC of every request and reply with the token as key; a fresh nonce per request; bounds that apply before authentication (head size, deadlines, connection count, body budget); `Host`/`Origin` checks; the client's terminal *lost* state | P1 §5, §7 (H1, H2, H3) |
| the discovery directory and every owner-only file | per-user directory with a deterministic fall-back; owner-only files; refusal of a directory another user owns or others may write to; stale files removed; the token read only inside the directory under its default name | P1 §2.5, §7; P6 §9.3 |
| the agent against the host application | an isolated child-first loader for everything but the bootstrap jar and, in hooks mode, `Spy`; daemon `skillroy-*` threads; bounded waits; actions posted to the EDT, reads bounded; never AWT before the application; **a thread the application forbids is never used, and an advice body only publishes to the bus on the thread it fired on**; redaction of secret-looking keys; the enable flag and the production marker; read-only mode and `confirm` | P1 §2.2, §5; design §2.2; P6 §3.4, §4.3 |
| the bootstrap path (hooks mode) | one class, `Spy`, with a set-once, weakly held, identity-released handler slot that is **never free while any class carries an advice** (a detach that ran out of budget keeps its disarmed handler installed, and a later agent is refused); advice bodies that call nothing else and never throw; a visibility probe before any install; the jar's content checked before the one append; the `Nexus` measure so that Byte Buddy injects nothing else | P6 §3.1-§3.3, §3.5 (D116, D117) |
| the hook engine against the application's bytecode | a deny-list by package name and by loader; the class size limit (256 KiB or 2,000 methods); explicit discovery, never a scan of the host's classes; the ledger; restore and verification in one retransformation with a foreign-event count; `STUB` behind a destructive primitive; `CAPTURE` masked by index, policy and key, bounded on the input | P6 §3, §4 |
| Byteman (optional) | loaded in process, **no listener**, rules over the authenticated channel under the dispatcher's gates; the `Retransformer` handed to the module once and **never published** (package-private `take()`/`park()` reached by reflection; no public accessor on the system class path at any time); `rule.check` loads neither Byteman nor a class; a lint deny-list matched as identifiers (a tripwire for Byteman's built-ins and the entry points it names, not a sandbox: `confirm` on every submit is the gate; fix wave, FB, 2026-10-09); a bound on the classes a rule may retransform, checked before Byteman is loaded; the jar checked at its real path; a curated helper; unload by name under the module's prefix | P6 §7 |
| the data channels | SELECT only; a statement guard on the agent, `SqlGuard` on the controller; read-only roles and server-side timeouts; row caps; row masks; credentials from `credentials.yaml` only and never written as a value | P2 §4, §7, §11.4 |
| embedded web views | the base connects only to a debug port the application configured and checks the endpoint's ownership before reading its body; the page's data is bounded and parsed on the controller's side, never trusted; traces in an owner-only directory, never with a secret in flight; script primitives carry the `script` flag, which read-only mode refuses on both paths and the MCP policy gates | P4 §4-§6, §8.3; P6 D156 |
| controller ↔ model | the key from the environment only, never written; masks before every prompt (**a secret-named property's secrecy inherited by everything nested under it and by a definition it references; a capture's bytes never as text**; security pass 2, SC); recordings for CI; budgets; the SQL guard on the statement as it will run; **what the provider returns scrubbed of the key and bounded before it is read** (pass 2, SC); a model's output acted on only through the gates above | P5 §2.3, §4-§7 |
| controller ↔ MCP client | read-only by default, mutating by flag, destructive never; **`hook.*` and `rule.*` primitives never offered, except the reads `hook.health`, `hook.events` and `rule.list`**; the server acts under a `CLIENT` origin that never confirms, and `confirm` and `_meta` are never taken from a client; a pack verb's compiled calls checked at call time against the server's policy; one session budget over every model call made for a client; **a client's scenario run under the server's hooks switch**; results cut and masked; a capture's bytes only under the inference settings' `images: true`, **as an image only, never as text on any route** (security pass 2, SC) | P5 §9 (D76, D96-D98); P6 §4.2, §6.1, D157-D159 |
| controller ↔ telemetry backend | off by default; explicit settings, no autoconfiguration from the environment; loopback-http or https only; headers from named variables, never written and never the model key's variable; attributes from an allow-list, masked and bounded; never parameters, values, payloads, prompts or credentials | P6 §10 |
| the pack ↔ the base | a pack is reviewed code (§3); the base bounds what a pack's code receives (masked rows, bounded reads) and what it may declare (allow-lists checked against the database, D100) | P2 §6, P5 §5.2 |

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
- Correctness of the application under test: a wrong `PASS` from the application's own defect is a test problem, not
  a security one; a wrong `PASS` from **hostile input** is in scope (High).

## 8. Not verified, as of this writing

- The Windows path: the ACL branches of S7/S15 and of every owner-only file the controller writes, the `.bat`
  launcher and the planner's `cmd.exe /c` form, `JAVAAGENT` as the logged-on user, the rungs and paint capture
  (P6 §9, §13.3): no Windows host exists.
- The `jxbrowser` adapter against the real product (a licence holder's repository runs the kit).
- The live-only paths run only with the owner's key (P5 §15.3).
- The retransformation pause on a painting event-dispatch thread of a real application (BS3 measured a spinning
  thread and the safepoint logs).
- A detach that exhausted its budget: the inert advice, the disarmed handler kept in `Spy`'s slot and the refused
  re-attach are specified and unit-tested with a simulated slow retransformation (P6 §3.5, §13.1), not observed on a
  real application.
- A Byteman rule left installed by such a detach is not inert: its condition and `DO` clause keep running (only the
  agent's `rule.fired` stops) until the target restarts; the next agent instance reports it and loads no rule
  (P6 §7.5).
- The Byteman module against a real application with its own agents, if it is built (spike BS14 runs on a toy).

These are listed again, for a release reader, in `README.md`'s status paragraph and in `SECURITY.md`.
