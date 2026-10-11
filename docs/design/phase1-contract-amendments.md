# Phase 1 contract amendments: changelog

Amendments A1-A16 were decided after the first module reviews (2026-10-01). All of them are implemented and, since
2026-10-02, merged into `phase1-contracts.md`, which is again the only place to read the contract. Amendments
A17-A33 and the hardening items H1-H3 were decided on 2026-10-02 (Phase 1.x), written into the contract as the
specification, and built, integrated and verified the same day; the contract now states what the code does, each
passage marked "(1.x, built 2026-10-02)". This file keeps the list of what changed, where it now lives and where the
build differs from the amendment's wording. It overrides nothing.

## Amendments A1-A16 (built)

| Id | Change | Now in the contract | Proven by |
|---|---|---|---|
| A1 | Per-call completion event: `Sync.EVENT`, `AwaitSpec`, a five-component `PlannedCall`, `await` in `plan.json`, `sync: EVENT` on the base verb `call` | §8.2, §8.3 | `EventSyncTest` (scenario); the sample pack's `answerPrompt`, end to end |
| A2 | Bounded HTTP pool: option `httpThreads`, no queue, saturated connections closed, at most 8 `/events` streams, then 503 | §2.4, §5 | `HttpLimitsTest` (core), Java 8 and 21 |
| A3 | Per-user discovery directory with an ownership and permission check, on the agent and the controller | §2.4, §2.5, §7 | `DiscoveryDirTest` (core), `HostSafetyIT` (dist), `DiscoveryDirectoryTest` (controller), `AttachIT` (cli: real agent, no directory named on either side) |
| A4 | No AWT initialisation by the agent before the application uses AWT | §5 (core), §6 (swing) | `AwtIsolationTest` (core), `HostSafetyIT` (dist), `AwtStartTest` (swing) |
| A5 | Secret redaction in `jvm.info`, `jvm.properties`, `agent.info.options`; option `redact` | §2.4, §5 | `RedactorTest`, `RedactionPrimitivesTest` (core), `HostSafetyIT` (dist) |
| A6 | `EmbeddedAgent.start` idempotent, re-entrant, bounded, callable by name | §2.3 | `EmbeddedStartTest` (bootstrap), `EmbeddedStartIT` (dist) |
| A7 | Node `id` and locator `{id}` | §6 | `LocatorTest`, `SwingDisplayTest` |
| A8 | `MODAL_OPEN` for input into a window a modal dialog blocks, unless `force` | §6 | `ModalBlockingDisplayTest`; §10 check 9 |
| A9 | Re-shown and hidden windows publish `window.opened {reshown:true}` / `window.closed` | §6 (AWT tap) | `ReshownWindowDisplayTest` |
| A10 | `activateWindow` for the COMPONENT click rung | §6 (click rungs) | `WindowActivationDisplayTest`; §10 check 9 |
| A11 | `swing.snapshot` caps `maxDepth` at 89 | §6 | swing tests |
| A12 | Data statement guard stricter than first specified; booleans stay booleans; `data.sources` on `WORKER` with 5 s; driver messages verbatim (the last point is replaced by S22 under H3, below) | §6 | data module tests (232) |
| A13 | Scenario details: numeric equality, the YAML number rule, the start `trace.mark`, `handlesPrompt`, the `waitFor` limit | §8.1-§8.5 | scenario tests (201) |
| A14 | Javadoc of `AgentModule.install` and `ActionFirer.fire` | §4 | read in the source; behaviour covered by `EmbeddedStartTest` and the swing click tests |
| A15 | Core details: `agent.disable` gates, readiness timeout, asynchronous close, re-attach keeps token and nonce, gap frame id, `sync.await` cap, a throwing `ProductionMarker`, module states | §5, §7 | core and controller tests |
| A16 | Controller details: 5 s start-up margin before attach, `awaitPid` never attaches, no request timeout on streams, the Java 8 proof for controller-side modules | §1, §7 | controller tests; BS1 measurements in `../TODO.md` |

## Where the build differs from the amendment's wording

The contract text states what was built. These are the points where that is not literally what the amendment said.

| Id | Amendment said | Built, and now in the contract |
|---|---|---|
| A1 | `timeoutMs: 0` = the step timeout | the compiler resolves 0 to the step timeout, so `plan.json` always holds a positive value; an await lasts at most 600 s; an `EVENT` wait on a prompt topic does not count as handling the prompt |
| A3 | user name "reduced to" `[A-Za-z0-9._-]` | every other character is dropped (not replaced); a symbolic link to the directory must belong to the current user as well |
| A8 | `data.dialog`, `data.title` | `data.ref` (the target) as well; the check applies only to an explicit target, and `swing.dismiss` is not checked |
| A9 | the tap diffs at the start of `swing.settle` | in every settle marker and once more after the quiet period, because hiding a window raises no AWT event that could end the quiet wait |
| A10 | activation took 35-408 ms | 2 to about 400 ms over all runs |
| A13 | unquoted YAML scalars that look like codes keep their text | they are errors ("write it in quotes if it is a code"): YAML would otherwise read `0100` as 64 |
| A16 | attach signal kills a Java 8 JVM up to about 130 ms after spawn | confirmed with a raw signal; through the JDK 21 Linux attach client the hazard did not occur, so the 5 s margin is a safety net |

## Recorded after the end-to-end review (2026-10-02)

Not amendments: behaviour that the reviews found or fixed and that the contract now states.

| Subject | Contract section |
|---|---|
| The attach argument limit is a refusal at 1025 characters, on Java 21 targets as on Java 8, not a truncation | §2.4, §7 |
| The discovery user-name rule, identical on both sides | §2.5 |
| `PromptDecision`, the `CompileContext` defaults, base fact shapes, status per failure kind, run outputs | §8.3, §8.5 |
| CLI commands, options and exit codes | §8.6 |
| The sample lending module is ready while a prompt is open; `lending.prompt.answer` reports `listening`; listener changes are serialised JVM-wide | §9.2 |
| Sample application clarifications (sub-steps, empty `validInputs` while busy, the sign of waivers, the cumulative return limit) | §9.1 |
| The JSON codec reads numbers the same on every runtime | `phase1-implementation-notes.md` |

## Changes of 2026-10-02 that are built

Not amendments either: the tree changed and the contract follows it.

| Subject | Contract section |
|---|---|
| The controller side is Java 25 (`--release 25`, build JDK >= 25, the CLI launcher refuses an older Java, the Linux image is JDK 25); the gates are a Java 8 target and a Java 25 target (decisions B3, B4). Measurements taken on Java 21 stay as measurements | §1, §7, §8.6, §10 |
| The bundled sample is the lending desk (`samples/skillroy-sample-lending-*`, domain id `lending`), unrelated to the first sample's domain (decision B10). Every example in the contract uses it; the one behavioural simplification is that a deposit has no kind (the `deposit` verb takes only `amount?`, `loan_deposit` has no such column) | §1, §3, §8.1-§8.3, §9, §10 |
| Apache-2.0 licence headers on every source file (decision B1) | §1 |
| Names are final (decision B2) | status paragraph |

## Phase 1.x amendments A17-A33 (2026-10-02)

Sources: review items R1-R4, the SPI gaps recorded in `../TODO.md`, and decision B12 (the COMPONENT rung is part of
the base). Decisions B17 and B18 in `../DECISIONS.md` record the provisional choices.

| Id | Change | In the contract | Status |
|---|---|---|---|
| A17 | The secret-key rule is shared: `SecretKeys` (`DEFAULT_KEYS`, `looksSecret`, `REDACTED`) in `skillroy-protocol`; the core's redaction uses it | §3, §5 (secret redaction) | built 2026-10-02 |
| A18 | Capabilities can be withdrawn: `declare` returns `AutoCloseable`, `undeclare(id)`; a module's capabilities go with its `uninstall()`; a withdrawn capability answers `CAPABILITY_UNAVAILABLE` ("capability withdrawn") | §4, §5 (modules, dispatch step 3) | built 2026-10-02 |
| A19 | `APP` before the application thread exists: `ThreadPolicyProvider.available()`; non-mutating calls run on `WORKER`, mutating ones answer `NOT_READY` | §4, §5 (dispatch step 5), §9.2 | built 2026-10-02 |
| A20 | Live options: `AgentContext.option(key)` | §2.4, §4 | built 2026-10-02 |
| A21 | Module-supplied redaction keys: SPI `RedactionKeys`; the sample module registers one | §4, §5 (secret redaction), §9.2 | built 2026-10-02 |
| A22 | Non-blocking embedded start: `EmbeddedAgent.startAsync` | §2.3 | built 2026-10-02 |
| A23 | Stale discovery files are removed: the agent removes files of its own pid from an earlier JVM at start; the controller's listing removes entries whose process is gone, with a flag to switch that off (review item R3, decision B18) | §2.5 (agent), §7 (controller), §8.6 (`jvms`) | built 2026-10-02 |
| A24 | An `httpNoDelay` option and a launcher flag for the JDK HTTP server | §2.4 (note) | **withdrawn**: review item R1 is decided as its option (c), the agent's own listener (H1) |
| A25 | Fixture secrets are masked in everything a run writes or returns, by key and by value (review item R4, decision B18) | §8.5 | built 2026-10-02 |
| A26 | Driver messages of `data.query` withheld | §6 (as S22) | **superseded** by security finding S22 under H3, which states the rule; S22 built 2026-10-02 |
| A27 | Dialogs that are not windows: SPI `DialogLocator`, `DialogTextExtractor.extract(Component)`, `swing.dialogs`, `swing.dismiss` and `MODAL_OPEN` for in-window dialogs | §4, §6 | built 2026-10-02 |
| A28 | COMPONENT with fall-back: `swing.click` parameter `fallback: "ACTION"`, result fields `requestedRung` and `fidelity` (decision B12, agent part) | §6 (click rungs) | built 2026-10-02 |
| A29 | Click fidelity as a scenario setting: the twelfth key `ui: {clickRung, strict}`, `click` arguments `fallback` and `strict`, `fired: false` fails the step, degraded fidelity makes the run `DEGRADED` (`RunResult.degradedFidelity`, `run.jsonl` type `fidelity`) (decision B12, controller part) | §8.1, §8.2, §8.3, §8.5, §9.3, §10 checks 5, 10, 11, 13 | built 2026-10-02 |
| A30 | `MODAL_OPEN` from a step call is `FAIL` / `ASSERTION` (review item R2, decision B18) | §8.5, §10 check 12 | built 2026-10-02 |
| A31 | `AgentConnection.await` declares and throws `AgentRpcException` | §7 | built 2026-10-02 |
| A32 | Event sync for the base UI verbs: `await` on `click`, `type` and `press` | §8.3, §9.3 | built 2026-10-02 |
| A33 | Documentation only: for an input sent through a primitive the `INPUT_REJECTED` error is the contract; the topic `input.rejected` is for inputs the application rejects on its own | §3, §9.2 | written into the contract; nothing to build |

## Hardening H1-H3 (2026-10-02)

Source: the security review of 2026-10-02 (34 confirmed findings, S1-S34). The wire protocol version becomes 0.2
(`Protocol.VERSION`, §3); the major stays 0.

| Id | Change | Closes | In the contract | Status |
|---|---|---|---|---|
| H1 | The agent's own minimal HTTP/1.1 listener on a loopback `ServerSocket` replaces the JDK `HttpServer`: bounded request head under an absolute deadline, body rules, one thread per connection from the bounded pool, eviction of the oldest unauthenticated connection, `TCP_NODELAY` and single-write replies. It also decides review item R1 as option (c) and removes the 45 ms per call that was measured with the JDK server (decision B17) | S3, S4, S10, S11, S12, S16, part of S29 | §5 (control server, bounded pool, host safety), §2.4 (`httpThreads`, `httpIdleTimeoutMs`), §1 (logging) | built 2026-10-02 |
| H2 | The token never travels: HMAC-SHA256 authentication of every request and every reply (`Auth` in `skillroy-protocol`), a session id per agent start, `/ping` with a client nonce, the client's terminal `lost` state, test fakes on `Auth`; protocol 0.2 (decision B17) | S1; narrows S5, S6, S28 | §3 (`Auth`, `Protocol.VERSION`), §5 (authentication, `/ping`), §7 (client authentication), §8.5 (cleanup), §8.6 (direct mode), §2.4, §2.5, §10 checks 2 and 16 | built 2026-10-02 |
| H3 | The verified fix of each remaining finding, as listed below; a JSON-RPC batch has at most 100 elements | the findings below | see below | built 2026-10-02 (S7/S15 not verified on Windows) |

H3 by finding:

| Finding | Change | In the contract |
|---|---|---|
| S2 | The agent log bounds every line; nothing from a request is logged before authentication | §5 (agent log) |
| S5 | The launch options file is deleted once `awaitAgent` has verified the agent | §7 (`LaunchedTarget`, options file) |
| S6, S28 | A token file is read only inside the discovery directory being read; a start time of 0 or less is skipped; a launch connects with the launcher's own token | §2.4 (`tokenFile`), §7 (token files) |
| S7, S15 | Owner-only files and the directory trust check through ACLs where a file system has no POSIX attributes; refusal where it has neither. Not verified on Windows | §2.4 (`tokenFile`), §2.5 |
| S8, S19 | One deterministic fall-back discovery directory, `<user.home>/.skillroy/run`, by the same rule on both sides | §2.4 (`discoveryDir`), §2.5, §7 |
| S9 | Operator-set `port`, `nonce` and `tokenFile` system properties are restored on close | §2.5, §5 (`agent.disable`) |
| S13 | Numeric text longer than 64 characters is not a number | §3 |
| S14 | The production gate fails closed when a module failed and no marker is registered; options `allowFailedModules`, `requireProductionMarker`; loading problems are `FAILED` modules | §2.4, §5 (modules) |
| S17 | `mainClassGuard` matches the whole launch target | §2.4 |
| S18 | Attach refuses a pid that is not listed as a JVM unless its executable is `java`/`javaw`; override `skillroy.attach.allowUnlisted` | §7 (attach), §8.6 |
| S20, S29 | No host-name lookup, no `java.util.logging` and no network work on the host's calling thread; with H1 the JDK HTTP server is gone | §5 (host safety, start sequence), §1 |
| S21 | Passwords in URL user information are redacted | §5 (secret redaction) |
| S22 | Driver error text of `data.query` is withheld and never logged, unless the provider opts in and no `MaskPolicy` is registered (replaces A26) | §4 (`DataSourceProvider`), §6 |
| S23, S25 | The client caps reply size and puts a deadline on the body; an event stream caps line and event size | §5 (`/events`), §7 (reply limits) |
| S24 | Regular expressions in matchers and paths run under a budget; a `StackOverflowError` cannot escape a run | §8.4, §8.5, §8.6 |
| S26 | The CLI neutralises control characters where it prints | §8.6 |
| S27 | JUnit XML values are scrubbed before they are escaped | §8.5 |
| S30 | A primitive with an abandoned call is refused (`BUSY`, -32033); bounded `CALLER` and `WORKER` pools; lifecycle work on threads of its own | §3 (`ErrorCodes`), §5 (bounded dispatch, dispatch steps 3 and 5, `agent.info`) |
| S31, S32, S33 | The bootstrap forgets a closed runtime; a close that overlaps a start leaves nothing behind; `stop()` never waits on the event-dispatch thread or a forbidden thread | §2.3, §5 (closing and re-attach) |
| S34 | Agent threads carry the host loader as context class loader; SPI implementations must not start host threads on agent threads | §1 (agent threads), §4 |
| (no number) | A JSON-RPC batch has at most 100 elements; a longer one is `INVALID_REQUEST` | §5 (`POST /rpc`) |

## Where the build differs from the amendment's wording (Phase 1.x, 2026-10-02)

As for A1-A16: the contract text states what was built. These are the points where that is not literally what the
amendment, the hardening item or the specification text written into the contract that afternoon said; the
implementers' reasons are kept in one line each.

| Id | Specification said | Built, and now in the contract |
|---|---|---|
| H2 | the body hash travels in a request header `X-Skillroy-Body-Sha256`, so that the MAC is verified on the head alone | no such header: a request with a body is authenticated once the body is in, over the SHA-256 of exactly those bytes; a shared 16 MB budget for the bodies of connections that have not authenticated yet, then 503 with `Retry-After: 1` (§5). The wire carries one authenticator; the header remains the way to close the gap fully |
| H2 | the client reads the session id from the 200 `/ping` body; a non-200 first `/ping` is reported by its status alone | a reply header `X-Skillroy-Session` on every authenticated reply; the client verifies the `/ping` MAC with it before parsing the body, then requires the body's `sessionId` (and the discovery file's) to match; a `/ping` reply without a valid MAC and session header latches (§3, §5, §7) |
| H2 | a 401 carries no MAC | also `WWW-Authenticate: Skillroy-HMAC` (replacing the former `Bearer`), and no session header (§5) |
| H2 | every reply after authentication carries the MAC, "error statuses included (400, 403, 404, 405, 411, 413, 501, 503)" | 413 and 501 (and the budget's 503, 431 and a malformed-head 400) are sent before authentication and carry no MAC; a client fails such a call without latching, and the event stream treats one as a failed attempt under its retry policy; a wrong MAC or an unsigned 200 still latches (§5, §7) |
| H2 | the latch is set by "a refused or reset connection" | a reset or closed connection latches only when a 1 s probe finds the port refusing connections; otherwise the call fails plainly, or, for a `POST` whose pooled connection died before any response byte while the port still accepts, is retried exactly once on a fresh connection (§7) |
| H2 | on a lost client `events()` fails with `AgentLostException` | it returns a stream that ends at once without connecting, so the CLI's `events` reports "lost" as before (§7) |
| H1 | statuses: 400, 401, 403, 404, 405, 411, 413, 431, 501, 503 | the same, plus 500 after authentication; the accept back-off is 50 ms doubling to 1 s (not 100 ms); the head deadline of a pipelined request counts from now; `/events` is close-delimited; after an error reply the agent drains input for at most 2 s or 16 MB; HEAD replies carry the body's `Content-Length` and a MAC over an empty body; paths are compared without percent-decoding; `Expect: 100-continue` is ignored; HTTP/1.0 is never kept alive (§5) |
| H1 | idle keep-alive connections are closed after 30 s | the option `httpIdleTimeoutMs`, default 120000, deliberately longer than the JDK client's 30 s keep-alive, so that the client retires an idle connection first (§2.4, §5) |
| H1 | `stop()` closes the listener and every open socket, then waits the grace period | idle and half-read connections are closed before the grace period; only requests in progress get it (§5) |
| H1, S12/S16 | the host-safety test asserts that `java.util.logging` is not loaded before `main` | it asserts that the application's own `LogManager` and logging configuration take effect: on JDK 25 `ManagementFactory` loads the class without initialising it (§5) |
| H3 (batch) | a batch over 100 elements is one `INVALID_REQUEST` | with `id` null and `data {elements, maxBatch}` (§5) |
| S2 | message about 1,000, exception message about 300, line 2,000 | exact: message 1,000, exception and cause messages 300, thread name 100, stack frame 200, line 2,000; control characters become spaces; worst case about 8 MB (§5) |
| S8/S19 | the fall-back is reported in the agent log only | also recorded in `skillroy.agent.error`; the agent serves normally (§2.5) |
| S8/S19 | the user-name rule, "reduced to" `[A-Za-z0-9._-]` | characters are dropped, as for A3; S19's injective `_xxxx` encoding was not adopted, so that both sides stay identical (§2.5) |
| S6/S28 | the token file's name is free | it must be exactly `skillroy-<pid>-<startMs>.token`, taken from the discovery file's name; on re-attach `skillroy-<pid>-<digits>.token` (§7) |
| S14 | loading problems become `FAILED` records | also: `ModuleLoader` reads the service files itself instead of `ServiceLoader`, and skips a class-path copy of a provider a module jar already named (the EMBEDDED layout has the module jar on both), so that copy is neither loaded twice nor failed (§5) |
| S20 | `RuntimeMXBean.getName()` as a last resort on a separate 2 s thread | on `skillroy-start`, inside the 60 s bound; a pid of -1 fails the start (§5) |
| S29 | the verified fix's deferred listener start (`serverStart=deferred`) | not built; the agent records `java.net.preferIPv4Stack` and `preferIPv6Addresses` before it binds and warns at `target.ready` if the application changed them (§5) |
| S30 | probes run on threads of their own; a JDBC watchdog | probes stay on the worker pool (a full pool makes the capability `UNAVAILABLE` with the `BUSY` reason); the watchdog is not built; `agent.info.threads` was added (§5) |
| S33 | "while a start is in progress" concerns the EDT and forbidden threads | it applies to every caller: a host-thread `stop()` during another thread's start returns at once and the stop runs afterwards (§2.3) |
| S22 | the verified fix returned the driver text whenever no `MaskPolicy` was registered | the provider's opt-in `exposesErrorText()` **and** no `MaskPolicy`, both read per call; `sqlState` and `errorCode` are null for a provider failure without an `SQLException` (§6) |
| A17 | `SecretKeys.DEFAULT_KEYS` is a `Pattern` | a `String` regex, compiled case-insensitively by its users (§3) |
| A21 | the `redact` option and the `RedactionKeys` are read at call time | the `redact` option is validated and read at start; only the `RedactionKeys` are read per call (§5) |
| A23 (controller) | a live process whose start time differs by more than 2 s is stale | stale means exactly "not live by the listing's rule" (the 60 s tolerance), so no file is both listed and removed and no live agent's files are deleted on a system whose boot time is recorded to the second (§7) |
| A25 | a secret shorter than 8 characters is masked only where it stands alone | also inside double quotes (`"1111"`), the way JSON and the executor's messages write a string value; a number equal to a secret is masked, booleans never; unquoted short secrets in free text are not (§8.5) |
| A27/A28 | open details | `requestedRung` is the rung as asked (an `AUTO` click reports `AUTO`); a fall-back reserves 400 ms of the call budget; `swing.dismiss` without `dialog` takes the topmost modal window, else the only in-window dialog, else `AMBIGUOUS` / `NOT_FOUND`; the innermost blocking in-window dialog is named; `edtState.modalDialogs` lists dialog windows only; the fall-back covers all three focus failures and re-checks the component (§6) |
| A29 | `degradedFidelity` directly after `skippedExpectations` in `RunResult` and `StepResult` | the last component of both (and `ui` the last of `Scenario` and `Plan`), so that the old constructors stay as prefixes (§8.5) |
| A29 | degraded fidelity is recorded for the base verbs' calls | for any step call, so that a pack verb's fall-back is never hidden; `fired: false` is checked for the base verbs only (§8.5) |
| A30 | the detail carries the dialog's title and text | the agent's `MODAL_OPEN` data has no text, so the executor makes one bounded `swing.dialogs` call for it (§8.5) |
| A32 | the settle call has no arguments | `{timeoutMs: min(step timeout, 118000)}`, call timeout plus 2000 ms; and no `swing.settle` call moves `waitFor`'s `since` (§8.3) |
| §9.3, §10 | the negative scenarios `click-fallback` and `click-strict`, forcing the fall-back after a dialog closed | `click-fallback-degraded`, `click-strict-fails` and a third, `modal-open-step`, targeting the prompt's painted OK button, which can never take the focus on any display; the UI scenario's clicks carry `focusTimeoutMs: 5000` (§9.3, §10) |
| §10 | "13 per mode" | 19 checks, 21 tests per mode (the JUnit glue is three dynamic tests), 84 in all; the old 13 are strengthened, none weakened (§10) |
| (packaging) | `LICENSE` and `NOTICE` in jars and zips, attribution in the CLI distribution | built: `META-INF/LICENSE` and `NOTICE` in every jar, both at the top level of the agent home and the CLI distribution, `THIRD-PARTY.txt` in the CLI distribution kept in step with `lib/` by a test, `<licenses>` and `<inceptionYear>` in the root pom (§2.1, §8.6) |

## Details the contract fixed where the specifications left them open (2026-10-02)

The specifications of A17-A33 and H1-H3 left these points open or stated them in two ways. The contract fixed each
with the simplest consistent choice before the build; where the build then chose otherwise, the table above has the
last word.

| Item | Left open or contradictory | Fixed in the contract as |
|---|---|---|
| H1 + H2 | Authentication is checked "on the head alone, before any body byte is read", but the request MAC covers the body's hash | the client sends the hash in the head (`X-Skillroy-Body-Sha256`); the agent verifies the MAC with the declared hash, then checks the body against it (401 on a difference) |
| H2 | "the fields it has today plus `sessionId` and `clientNonce`": today's `nonce` already is the client's nonce | `/ping` returns both `nonce` and `clientNonce`, with the same value |
| H2 | the exact header syntax, nonce generation and the `Auth` API | `Skillroy-HMAC`, one space, `name=value` pairs separated by a comma and optional spaces, no quotes; nonces from `Auth.newNonce()` (16 random bytes, 32 hex characters); the `Auth` signatures of §3 |
| H2 | which replies carry the MAC, and how a client verifies the first `/ping` | every reply after successful authentication, error statuses included; the client reads `sessionId` from the 200 `/ping` body and verifies with it; a non-200 first `/ping` is reported by its status alone |
| H2 | a `/ping` without a usable nonce | 401 |
| H1 | statuses the specification does not name; the accept back-off; where the head deadline starts on a kept-alive connection; the `/events` framing | malformed head 400, `POST` without `Content-Length` 411; 100 ms; from the first byte of a later request; a body that ends with the connection (no chunking) |
| H1 | when a connection may be kept alive | only after an authenticated request that passed the `Host` and `Origin` checks and whose body was read completely |
| S8 / S19 | two different rules for the default directory in the two verified fixes | the rule the hardening item names: the temporary directory first, `<user.home>/.skillroy/run` only when the default is occupied by another user; the user-name rule is unchanged, since the fall-back also resolves two names that reduce to the same string; the fall-back is reported in the agent log only, because a value in `skillroy.agent.error` reads as a failed start |
| S6 / S28 | whether a token file must also have the default name | no: inside the directory being read, by real path, with the file checks of §7 |
| S7 / S15 | which principals an existing directory's ACL may name | the creating principal ("me" = the owner of a file just created), the system account and the administrators group; refusal if those cannot be resolved while such entries exist |
| S22 / A26 | "unless the provider opts in": no mechanism was named, and the verified fix keys on `MaskPolicy` instead | `DataSourceProvider.exposesErrorText()` (default false); the text is returned only when it is true **and** no `MaskPolicy` is registered; it is never logged |
| S29 | the verified fix's deferred listener start is not in the hardening item | not specified; the remaining effect (the network classes are initialised before `main` in JAVAAGENT mode) is stated in §5 |
| S30 | error code and pool sizes are given as examples | `BUSY` = -32033; `CALLER` at most `httpThreads + 8`, `WORKER` at most `2 * httpThreads + 8` |
| A23 | the controller's staleness test (start time off by more than 2 s) is tighter than the liveness rule of the listing (60 s) | one rule: an entry is removed exactly when the listing skips it as not live (60 s tolerance), so that no file is both listed and removed; API `list(dir, removeStale)` and `entries(dir, removeStale)` |
| A25 | the sample's password has four characters, below the 8 characters at which values are masked inside longer text | the end-to-end check is that the password is written as a value nowhere in the run outputs |
| A29 | what `strict` on one step means, and how the plan records `ui` | the step's `strict` overrides `ui.strict`; `strict: true` on a step means no fall-back even when the same step names one; `strict` is never sent to the agent; `plan.json` has `"ui"` only when the scenario has the key; `degradedFidelity` follows `skippedExpectations` in `RunResult` and `StepResult` |
| A32 | the arguments of the `swing.settle` call | none, sync `NONE` |
| A21 / A19 (sample) | which key the sample module registers; whether it shows `available()` | a pattern matching `lending.pin`; it keeps the default `available()` |
| §10 (new checks) | the names of the new negative scenarios | `click-fallback`, `click-strict` (built as `click-fallback-degraded`, `click-strict-fails` and `modal-open-step`, see above) |

## A34 onwards: where the later amendments live (index, Phase 7, built 2026-10-10, VC; P7 §5, D301)

The amendments after A33 change the frozen Phase 1 contract through the later contracts, which carry the full text and
mark the Phase 1 passages in place; this index only says where each lives, so that the sequence has its second half
here as well.

| Id | Change | Where it lives |
|---|---|---|
| A34 | `secretParams`: the `_meta` field of a call announcing which parameters hold a secret, and the core's replacement rule in logs, traces and error data | `phase2-contracts.md` §11.4, §16.1 |
| A35 | `web.waitFlag` answers an expression that throws the same way on both paths | `phase4-contracts.md` §16.1 |
| A36 | a call the CDP path serves reads the agent's sequence once | `phase4-contracts.md` §16.2 |
| A37 | the web SPI gains `reload` and the bitmap's source as default methods (gap 40) | `phase4-contracts.md` §16.3 |
| A38 | the re-probe after `window.opened` runs at most once a second (gap 42) | `phase4-contracts.md` §16.4 |
| A39 | the COMPONENT rung's window activation is re-issued while it waits | `phase1-contracts.md` §6 (marked "4.x, A39") |
| A40-A60 | the Phase 6 security fixes S35-S62 whose fix changed a contract passage, one id per finding in S order; each passage is marked "(Phase 6, …, A<n>)" in P1, P2, P4 or P5 | `phase6-contracts.md` §12.2 (the list of the pass's dispositions) |
| A61 | `swing.capture` extended: `by`, `mode: ROBOT`, `scope`, `region`, `scale`, `redact` (default true), `annotateRefs`; the result's geometry, marks, carets, focus, redactions and raster hash; a window painted from its root pane (gap 76) | `phase7-contracts.md` §3.1, §3.4, §3.6; `phase1-contracts.md` §6 |
| A62 | `swing.type` without a target types at the focus owner; gap 60 stated | `phase7-contracts.md` §3.5; `phase1-contracts.md` §6 |
| A63 | `swing.hitTest`, `swing.pointer`, `swing.rendering`; the five capability ids; "Modal blocking" per pointer event | `phase7-contracts.md` §3.2-§3.4, §3.8, §3.9; `phase1-contracts.md` §5, §6 |
| A64 | the SPIs `RegionResolver`, `CaptureMask`, `SurfaceSource`; `LocatorResolver.describe` | `phase7-contracts.md` §3.6, §3.7; `phase1-contracts.md` §4 |
| A65 | the agent option `robot` | `phase7-contracts.md` §3.4, §3.9; `phase1-contracts.md` §2.4 |
| A66 | `WebBrowser.dispatchPointer`, `WebPointerEvent`, `WebCapability.INPUT` built; `web.hitTest`, `web.pointer`; the script library's version 2 | `phase7-contracts.md` §4; `phase4-contracts.md` §3, §4 |

From A67 the orchestrator assigns any further id at integration.
