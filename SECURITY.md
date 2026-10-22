# Security policy

Skillroy Test (Java) puts an agent inside a running Java application and drives it from a controller, which may also
hand actions to a language model. Read this page before you run the agent on a host other people share.

## Reporting a vulnerability

Report it privately, never in a public issue:

- through the repository host's private vulnerability reporting for this repository, or
- by mail to the address in [`NOTICE`](NOTICE).

You will get a reply within seven days. There is no bounty. Please include the version or commit, the load mode
(`JAVAAGENT`, `ATTACH` or `EMBEDDED`), the Java versions of the target and the controller, and the steps or the code
path. (The channel and the support policy were confirmed by the owner on 2026-10-10, decision B26 Q13.)

## Supported versions

Only the latest minor release receives security fixes, until the project says otherwise. The first release is 0.1.0
(2026-10-12, the tag `v0.1.0`).

## What is in scope

The threat model, [`docs/design/threat-model.md`](docs/design/threat-model.md), names the actors the framework defends
against: another OS user on the host, a same-user process without the agent's token, a page inside an embedded web
view, the target application's content, the model, an MCP client, the planner's command-line tool and what is on
`PATH`, a remote party, a telemetry backend's operator, other code in the target JVM, a reader of what a run leaves
behind, the model provider, and, since Phase 7, whatever else is on the desktop where the Robot tier runs (its actors
A1-A9 and A12-A15). A path from any of them to code execution, to a
credential, the model key, a prompt or a personal value, to a confirmation no person typed, or to a wrong test
verdict from hostile input is in scope.

Out of scope (its §7): a compromised operator account, shell, JDK or Maven cache; a hostile domain pack, agent
module, overlay, hook catalogue or committed rule (they are reviewed code); physical or administrative access to the
host; code inside the target JVM that reflects on the agent's internals; a denial of service by the operator against
their own target; the target's own listeners (a JDWP port); the provider's and the telemetry backend's handling of
what they received.

## Known limitations

Read these before you run the agent on a shared host.

- **Attach is the platform's trust boundary.** Any process of the same OS user that can attach to a JVM can start the
  agent in it (decision B13). Start a target that must never be instrumented with `-XX:+DisableAttachMechanism`.
- **Java 21 and newer targets.** Loading the agent into a running JVM (`ATTACH`) prints the JEP 451 warning and will
  be refused by a future JDK; launch with `-javaagent` (`JAVAAGENT`), which prints nothing.
- **Hooks on Java 25.** With hooks installed, a Java 25 target prints one class-data-sharing warning line on its
  standard error when the agent's spy jar is appended to the bootstrap path (once per JVM; B26 Q14).
- **`StatementGuard` is a safety net, not the boundary.** A statement can pass its check and still have effects the check
  cannot see: give the data account a read-only role, and set `readOnlyMode: always` on PostgreSQL sources (a deny-list of
  side-effect function names is not built) (accepted by the owner, B28, 2026-10-10).
- **Run outputs take the process umask.** `run.jsonl`, captures, bindings, ledgers and span files hold UI snapshots,
  events and row samples. On a shared host run the controller under `umask 077`, and give `--out` a directory you own
  that other users cannot write to, never a path directly under `/tmp`: a run refuses an output directory another user
  owns or others may write to (without the sticky bit), and never writes through a file or link already in place of
  one of its files (security pass 2, P2-L8-F3, P2-L8-S1). That a private umask is the advice and not the default is
  accepted by the owner (B28, 2026-10-10). On Windows the output directory's access list is judged too: a directory that
  lets another user write to it, or to the files created in it (a folder under `C:\` inherits Modify for Authenticated
  Users), is refused with the `icacls` remedy (Phase 7 security fix, S141; not verified on a Windows host). Since Phase 8
  the event payloads in them are masked at publish, by key and by the pack's `MaskPolicy`s, and a browser without a view
  is masked while any mask is registered, released only by a `BrowserMask` (Phase 8, built 2026-10-19; P8 §5.1, §5.2,
  and the events bullet below); a session's journal cuts a fact's lists at 200 elements, and a captured value is at most
  64 KiB of JSON (Phase 8 security fix, S150).
- **Settings files must be your own.** `telemetry.yaml` and `inference.yaml` beside the credentials file (or in the
  bindings environment directory) are read only when no other user could have written them: a file others (or a
  shared group) may write to, or another user's, stops the command with a settings error naming it; on a Linux host
  with `umask 002` a file your own user-private group may write is accepted. A telemetry header is sent to an
  `https://` endpoint only, never over the default loopback `http://` (security pass 2, P2-L8-F1, P2-L8-F2).
- **Pin the CI example.** Pin the image and the actions in your copy of `ci/examples/nightly-re-resolve.yml`: the base image of
  `ci/Dockerfile` by digest and every action by its commit SHA; the example itself names floating tags, because a pinned
  example rots within weeks (accepted by the owner, B28, 2026-10-10).
- **The Playwright driver inherits the controller's environment.** `DEBUG=pw:*`, `NODE_OPTIONS`, `PLAYWRIGHT_NODEJS_PATH` and
  `PLAYWRIGHT_DRIVER_DIR` change what it runs or logs, so set them only on purpose (accepted by the owner, B28, 2026-10-10).
- **The planner's session transcript.** The planner's `claude` session transcript stays in the CLI's own data directory; on a
  shared host run `author` with a key and your own `CLAUDE_CONFIG_DIR` (accepted by the owner, B28, 2026-10-10).
- **A squatted debug port while the agent reports no target id.** When the CDP path attaches while the agent reports no CDP
  target id (no browser created yet, or an adapter without target ids), a process that holds the configured remote-debugging
  port before the application's browser engine binds it can answer the attach's WebSocket handshake with an HTTP redirect,
  which Playwright's driver follows: to another host, or to a Unix socket the operator can open. The process gains an
  outbound connection with Playwright's headers and Playwright's own DevTools commands, never the run's scripts or typed
  values. Bind the remote-debugging port in the application before any other process can, or run such scenarios with
  `web.path: agent` (security pass 2, P2-L4-F1) (accepted by the owner, B28, 2026-10-10).
- **A loopback `http` model base URL.** `ANTHROPIC_BASE_URL` may name plain `http` on `127.0.0.1`, `::1` or `localhost` (for a
  local proxy and the tests' fakes); if that proxy is not listening, another user of the host who binds its port receives the
  key in clear with the next request. Use a loopback `http` base URL only for a proxy you run, never on a shared host; prefer
  `https` on loopback, where a squatter fails the TLS check (security pass 2, P2-L5-F4) (accepted by the owner, B28, 2026-10-10).
- **A Windows whose built-in account names are not English.** The owner-only checks cannot recognise the system account and the
  administrators group by name, so a directory or file created under a directory that passes those entries on by inheritance
  (the user's `%TEMP%`, the profile) is refused: the planner's working directory, the CDP trace directory, the MCP server's
  output, the agent's token and the discovery directory, the credentials file and the Byteman jar's directory fail with "its ACL
  allows ...". Nothing is exposed; the framework refuses to run there. Remedy: create a directory, remove its inheritance and
  grant only yourself (`icacls <dir> /inheritance:r /grant:r "%USERNAME%":(OI)(CI)F`), and point `java.io.tmpdir`
  (`SKILLROY_JAVA_OPTS=-Djava.io.tmpdir=<dir>` and the test JVMs' `-Djava.io.tmpdir`), `discoveryDir` and the credentials file
  at it. Not verified on a Windows host (security pass 2, P2-L7-F4) (accepted by the owner, B28, 2026-10-10).
- **Redaction hides a rectangle, not a value** (Phase 7, built 2026-10-10; P7 §11 (a)). A capture blanks the rectangle of a
  component after it was painted, so the layout and a value's width stay visible; and pixels carry no value mask: a
  component no mask names reaches a model as rendered. A pack's `CaptureMask` must name every component that can show a
  secret, and any control that feeds a secret field as well (the sample's number pad is masked while its entry field is).
  A mask's `regions` hide both the pixels and the text they cover (a label or field they meet, a table's cells, a list's
  selected item); two masks combine by union; the accessible name of a masked label or button is its text and is withheld
  with it, while a name set apart from the text (an accessible name, a `labelFor` label, a border title, `setName`) stays and
  must not itself be a secret (Phase 7 security fix, S106, S122, S123).
- **A page's pixels are masked as a whole or not at all** (P7 §11 (b)). There is no mask for a DOM element: a pack whose
  page shows personal data masks the whole browser component (with a `CaptureMask`) while it does, and a masked browser is
  masked on every route: its page reaches no model and no client as pixels, text or tree (`web_bitmap`, `web_dom_text`,
  `web_aria_snapshot` and `web_hit_test` are refused, the snapshot shows `[web <ref> masked]`; Phase 7 security fix, S111).
  Since Phase 8 a browser the agent has no component for is masked while any `CaptureMask` or `BrowserMask` is
  registered, and a `BrowserMask` answering TRUE masks a browser with a view too: its view is grey in every capture and
  its hit-test reports it masked (P8 §5.2; Phase 8 security fix, S166). A masked browser's events withhold page data,
  `by: {url|title}` never matches it, and the actions that stay allowed on it answer without page data; the actions that
  evaluate a caller's locator in the page (`web.dom.click`, `web.dom.fill`, a selector `web.dispatchEvent`) are refused
  unless a person passes `redact: false` (S165). `web.evaluate` and `web.waitFlag` on a masked browser remain S111's
  residual: they are never offered to a model or a client, and read-only mode refuses them.
- **With vision on, pixels leave for the model provider** (P7 §11, rule 2). With `--vision` and the inference settings'
  `images: true`, redacted captures of the target's own windows go to the provider with every vision call; `images: false`
  (the default) or `--vision off` keeps every pixel at home. A capture of the glass (`mode: ROBOT`) never goes to a model or
  a client. A model or a client can no longer paste, copy or cut through the person's clipboard or the X11 selection, nor
  press the middle button, through any tool or raw primitive (S110), but an application's own Paste control is an ordinary
  click: mask it. Under the default `fallbacks: true` a refused request is re-run by the provider on a fallback model of the
  same provider, redacted captures included; whether the fallback targets' data handling suits you is yours to confirm.
  Two bounds were added: no token count is sent to the provider when the session's budget is exhausted or too small (S135),
  and a turn's tool reads (captures, waits, zooms) are bounded, to 20 or twice the action limit, and its waits together to
  three step timeouts (S138).
- **The Robot tier presses where the pointer lands** (P7 §11 (c), (d)). V3 refuses a point in a window of the target JVM
  that the toolkit reports minimised (or a window a minimised frame owns), a point no screen shows, a point under another
  window of the JVM, and `modifiers` (Phase 7 security fix, S104, S105, S124). It still presses whatever is at the screen
  point when the desktop changed in the milliseconds between the agent's focus read and the press, when a covering window of
  another process cannot be seen, and when a window of the JVM lies on another virtual desktop or workspace, which AWT cannot
  see (the focus rule is the only guard there). A press that is not received, or that another component received, makes the
  next call probe delivery again, and that probe degrades the capability when it fails (the press itself does not), so the
  damage is about one stray click per successful action in between. A person's `ROBOT` capture shows the glass inside the
  target's window rectangles (and blanks the masks of every window of the JVM that meets them), so a window of another
  process that covers the target is in it; it never goes to a model or a client.
- **Committed baselines and anchors are screenshots** (P7 §11 (e)). `<scenarioId>.images/` beside a bindings file holds the
  application's data as rendered, masked per component only: mask with `expectImage`'s `mask` what must not be committed, and
  review the PNGs as you review the YAML. `skillroy_scenario_run` over MCP refuses `approve` on a server without an approver and
  never writes into the bindings root, an images-only `approve` writes nothing at bind time, an unreadable bindings file stops
  `approve` instead of being rewritten, and `approve` without `--out` rejects a candidate it cannot show (only `--yes` approves
  unseen; S115, S116, S117). The image comparison blanks a focus owner's rectangle only up to a quarter of the compared raster
  (S103): a baseline approved while the compared component held the focus may need re-approval if the focus differs at replay;
  the message says it was compared, not masked.
- **`captures/` holds a screenshot of every acted-on screen** (P7 §11 (f)). Under the session's output directory, the raster
  as the agent captured it (redacted, never a glass capture), with the process umask: run a vision session on a shared host
  under `umask 077`; without `--out` the session writes into a new owner-only directory under the system's temporary
  directory, whose path it prints at the start and the end (it is kept: remove it when done), so on a shared host give
  `--out` a directory you own (Phase 7 security fix, S148).
- **The Robot opt-in can be committed** (P7 §11 (h)). The agent option `robot=true` in a launcher configuration and
  `skillroy.robot=true` in a build file are halves of the opt-in (the other is `--allow-robot` on the command): do not commit
  either. The JUnit glue prints a warning whenever the property is true.
- **The Robot tier is verified on the Linux image's display only** (P7 §11 (g), §13.4). macOS with the Accessibility
  permission granted (without it Robot delivers nothing, silently, and the delivery probe says so), Windows, Wayland, a locked
  session and a scale other than 1 are not verified, nor is a person's desktop with another process's window over the target.
- **A directory junction in a run's output tree** (S142; accepted by the owner, B31, 2026-10-11). On Windows a directory
  junction (`mklink /J`, which needs no privilege) in place of a run's `failure/`, `captures/` or `candidates/` directory, of
  the per-scenario output directory, or of a bindings environment's `<id>.images/` directory is taken for an ordinary
  directory and judged by its target's owner: the run then writes its PNG files into, and deletes files of its own names
  (`image-*`, `web-*`, `run.jsonl`, `bindings.json`, the ledgers, `capabilities.json`, `failure/*.json`, `capture.png`) from,
  the directory the junction points at, if the operator owns it. A symbolic link in the same place is refused unless the
  operator owns it. Nothing is read back through the junction except a baseline whose hash is still checked. Give `--out`
  and the working copy a directory under your profile whose ACL others cannot write (`icacls <dir> /inheritance:r /grant:r
  "%USERNAME%":(OI)(CI)F`). Not verified on a Windows host.
- **Windows run output inherits its parent's access list** (S144; accepted by the owner, B31, 2026-10-11). On Windows,
  run output, a session's `captures/` (a screenshot of every screen the model acted on), `candidates/`,
  `failure/image-*.png` and an uncommitted `<id>.images/` directory inherit the access list of the directory they are
  created in; the `umask 077` remedy of the POSIX hosts has no counterpart there, and a folder under `C:\` gives every local
  user read access by default. On a shared Windows host give `--out` and the working copy a directory under your profile, or
  remove inheritance from the directory you choose (`icacls <dir> /inheritance:r /grant:r "%USERNAME%":(OI)(CI)F`). Not
  verified on a Windows host.
- **The input veto does not follow key bindings** (Phase 8, built 2026-10-19, fixed 2026-10-20; P8 §11 (a)). A layer's
  `InputVeto` is asked on every input route that resolves a target, for every origin, `force` included; ENTER's default
  buttons (every root pane on the walk up to the window, a `'\n'` in `DISPATCH` text included) and a popup's opener are
  asked too (Phase 8 fix wave, FA). It does not follow Swing's key bindings, mnemonics and accelerators: veto the window
  or the bound control; a menu item's own veto does not cover the keyboard of an open menu, so veto the window. A veto on
  a window or panel does not cover `web.*` input into a browser inside it (gap 126), and a layer that must keep page input
  out vetoes nothing more for that. A failed layer module served under `allowFailedModules=true` serves without its veto.
  On the Robot route a drag's release is asked again just before it happens, but a popup or a drop zone the application
  shows after that question is not seen (gap 153); a `JRootPane` embedded off the key target's walk and outside a
  `JInternalFrame` has its default button pressed by ENTER without being asked (gap 154). The mask's walk does not follow
  popups as the veto's now does: a masked combo's or window's popups drawn as windows of their own are not masked (gap
  152).
- **A quiescent task holds the application's thread until it returns** (P8 §11 (b)). A module's task runs on the
  application's own thread only at the idle point the provider drives, admitted by its declared budget and never
  pre-empted: one that overruns holds the thread until it returns (an overrun of twice the budget penalises the primitive,
  or the module, until `reprobe`). A provider read that ignores cancellation keeps its lane or its source busy until it
  ends. A positional source's unnamed columns are masked under the names their items read, or withheld as `<redacted>`
  while any mask is registered (Phase 8 security fix, S160, FB); a rename (`password AS p`) defeats a name-keyed mask on
  any source, positional or not: a mask keyed by name sees the label the statement gives.
- **Events are masked at publish, by key and by the pack's policies** (P8 §11 (c), §5.1). Every event payload passes the
  registered `MaskPolicy`s (under `event:<topic>`) and the secret-key rule before it is stored, so a secret-looking map key
  is `<redacted>` whether or not a policy is registered; on attach, the events of the module-install window are masked by
  key only (gap 139). The CDP path's own events never pass the agent's ring (gap 138). A browser without a view is masked
  while any mask is registered and released only by a `BrowserMask`. A browser that becomes masked while it is open (its
  view moved into a masked container, a mask registered later) sends page data in its events for up to about 2 s after
  its next event (Phase 8 security fix, S172, FE; gap 155): a `CaptureMask` should answer by a stable container. The
  listener that keeps a person's middle click from pasting the X11 PRIMARY selection into a page is best effort: a page
  that interferes with it keeps the paste (S171).
- **A Byteman deny cannot see a subtype loaded later, and a deny only tightens** (P8 §11 (d)). A class the pack's
  catalogue, the operator's `hooks.deny` or a module denies is never read, probed, instrumented or handed to Byteman, but
  a denied subtype loaded after a `^` or `INTERFACE` Byteman submit receives Byteman's trigger (gap 140), and a deny list
  only tightens for the life of an agent instance: a restart loosens it.
- **The secret-echo check is a consistency control** (P8 §11 (e)). A step whose result echoes a secret parameter's value
  fails, but a secret shorter than `masking.minLengthInText` is compared by equality only; a composite that answers a
  question it does not own cannot be detected by the base; `taken` records application data, under the run's mask.
- **The `events` root sees the run's window only while the ring holds it** (P8 §11 (f)). A target that publishes more
  than `eventRing` events in a run makes such expectations `ERROR`, by design, and so does an event its module or the
  ring cut at publish (`truncated: true`, a long console line included; Phase 8 security fix, S151, FC); a regex or a
  `[json]` text that a bound stops makes its expectation `ERROR`, never a pass (S149). Publish small, structured payloads.
- **Pack code at run start has no host timeout** (P8 §11 (g)). A pack capability's evaluation and a pre-flight check run
  pack code on the controller, reading through the guarded read-only data channel; the channel's per-query bound is
  their bound.
- **The CDP path's gate for an older Playwright is proved through a seam** (P8 §11 (h), §5.3). Below Playwright 1.60 the
  CDP path is not used unless the operator sets `skillroy.cdp.allowDefaultOverrides` (never a scenario's, a pack's or a
  model's choice: the overrides change the application's own browser context); only 1.63.0 was run.
- **A client's raw `sync.await` can confirm a guess at a short unmasked secret** (S180; accepted by the owner, B40,
  2026-10-20; P8 §11 (i)). A client's raw `sync.await` (a base read every MCP server offers), or a pack verb whose
  argument flows into a `match`, compares an event's raw payload on the agent by equality: where a module publishes a short
  secret unmasked, a client can confirm guesses at it, one call per guess. Mask such a payload with a `MaskPolicy` or
  publish it under a secret-looking key. The fix, rewriting such a match where a session sends the call, is gap 151.
- **A caller's own arguments come back to it through the value mask** (S181; open, found 2026-10-22, not yet rated: the
  owner sets its severity; gap 156). The session journal, which an MCP client reads as `run/<id>/journal.jsonl`, and a
  failed call's reply detail record the caller's arguments masked by value. Any MCP client, or a model, can therefore
  test whether a value is a secret the controller holds (a `credentials.yaml` value, a joined credential field): a right
  guess reads back as `<redacted>` and a wrong one as sent, and one argument holding several candidates tests them all at
  once. It needs nothing from the target and works on a read-only server. It confirms whole guesses only, but as many in
  one argument as fit, so until it is fixed keep every secret in `credentials.yaml` long and random enough that no list
  of guesses covers it (letters, digits and symbols, not a numeric code); `masking.minLengthInText` does not limit the
  echo. Treat a short code the target requires, such as a four-digit staff code, as recoverable in a few calls by any
  client or model of a session whose mask holds it (the batch size by reading). Shown by a throwaway probe that sent
  model-origin calls straight to an executor session over a fake agent; the MCP server's and the session service's paths
  to a client or a model were read, not run.
- **A locator or a selection regex can test a secret pair in the target's text** (S182; open, found 2026-10-22, not yet
  rated: the owner sets its severity; gap 157). A Swing locator's `by.text` or `by.name` (exact, `contains` or `regex`,
  as `swing.find` and every verb with a locator take it) is compared with the component's raw text, and a web selection's
  `by.url` or `by.title` regex with an unmasked browser's raw URL and title (by reading, a web read's CSS `selector`, as
  `web.dom.text` takes it, tests the raw DOM the same way, for example an attribute selector on an `aria-label`), while
  every read shows a secret pair in that text (`password=…`, a secret-named key in JSON text) masked. A caller can
  confirm a guess at the pair's value and, with `contains` or a regex, recover it one character at a time (each call
  extends a known prefix), on a read-only server too; unlike S181, a long random value does not help. Until it is fixed,
  name a component that can show such text with a `CaptureMask`, which withholds its text, and a name that is only its
  text, from the built-in `text` and `name` strategies. A name set apart from the text (an accessible name, `setName`, a
  `NameEnricher`'s) is still matched by `by.name`, so keep such text out of names, and a module's own `LocatorResolver`
  sees the raw component. Also mask a browser whose page, URL or title can carry one. Found by reading the code; not run.
- **What Phase 8 changes by default** (P8 §11's behaviour changes, where the full list is). Two protections apply to
  every target and pack because that is their point: event payloads are masked at publish, and a browser without a view
  is masked while any mask is registered (above). Also: every `CALL`/`EXEC` is refused by the statement guard; the CDP
  path is unused below Playwright 1.60 without the operator's property; a regex or a `[json]` that a bound stops, an event
  cut at publish and a lost event window end an expectation `ERROR`, and a single filter that matches twice holds no
  `exists` form; a session's checks and a model's or a client's run follow fact paths on the masked view, so a person's
  `fact` and `observe` in a session show `<redacted>` under secret-named keys and pairs, and a reason names a value's
  kind, never the value; `DISPATCH` text holding a `'\n'` is refused while a default button on the target's walk is
  vetoed, a popup's items are refused under a vetoed opener, window or combo, a routed release over a vetoed component
  is refused, and on the Robot route a covered point whose covering window's component is vetoed answers `vetoed`, with
  or without `force`; on a masked browser a locator action is refused unless a person passes `redact: false`, the tap
  events carry the tap's pattern as their name, and the CDP path's frozen-target refusal names the page by its id only.
- **Not verified on Windows.** No Windows host has run this code: the access-control branches that make the token,
  credential and discovery files owner-only, the `.bat` launcher, the planner's `cmd.exe /c` form, `JAVAAGENT` as the
  logged-on user, the input rungs and the Robot tier (UIPI, session 0, the popup trigger on the release, a minimised frame's parking position and
  the cursor clamping of S104 and S105), and the ACL judgement of the run-output directory (S141, tested on built ACL values;
  it resolves CREATOR OWNER by its English name). Until the project
  says otherwise, treat Windows as unverified.
- **Not verified elsewhere.** The `jxbrowser` adapter beyond the one version and platform its kit ran on (JxBrowser 8.18.3 on macOS arm64 with a JDK 25 test JVM, 2026-10-11: 19 of 19 in three variants, its pointer input and a Retina display included), that is other JxBrowser lines, Linux, Windows and a Java 8 test JVM; the paths that need a live model key, among them the vision loop beyond the short sessions of
  the spikes and the Phase 7 live class's three short runs (the last on the final code, 7 of 7), a provider's handling of a
  toolset member whose `configs` disable it, and the planner's model choosing `approve`; a hook detach that ran out of its time budget on a real application
  (a Byteman rule left installed by such a detach keeps running until the target restarts). Phase 7 adds heavyweight popups
  and native drag and drop under the pointer primitives, windowed JCEF on a Java 8 target, a long vision session and the
  session's fold under the provider's history rule. The delta review's fixes add the paste and the middle click on an X11
  desktop with a headed JVM (a masked browser and a modal dialog over a page have run against real JCEF since, gap 95;
  there a person's synthetic middle click left an off-screen JCEF page unresponsive, a click a model or a client cannot
  send, S110: the cause was Chromium's paste of the X11 PRIMARY selection on the middle `mouseup`, which the agent's
  `web.pointer` has cancelled since 2026-10-12, gap 104, so no middle click through the agent pastes that selection
  into a page's main frame), a second OS user for the writers' link rule,
  an APFS case-insensitive volume, a real
  multi-screen desktop and HiDPI (the glass bound at scale 3 through a seam only). Phase 8 adds (P8 §13.3): the Windows
  and macOS peers' reaction to routed Java mouse events on AWT controls; a veto in a layer's own application (the
  sample's veto stands in); a hook-entered idle callback driving a quiescent point; `Statement.cancel()` on drivers
  other than HSQLDB; a provider blocked in uninterruptible application code (fakes only); a Playwright line other than
  1.63.0; JxBrowser's view-less browsers; a `SecurityManager` target and a custom-protocol code-source URL from a real
  application server; a Byteman trigger on a denied subtype loaded after the submit; more than 10,000 events in one
  run; and, from the fix wave, the Robot route's release question against a real `java.awt.Robot`, a Windows
  code-source location, and the late-acknowledgement race of P8 §13.2 item 27 end to end. The full list is §8 of the
  threat model.

## Reviews

The design was reviewed for security on 2026-10-02 (findings S1-S34, all fixed). The review was repeated in two passes on
2026-10-09 (findings S35-S62 and S63-S100: 66 findings, 2 high, 18 medium, 46 low; every finding of medium or above is fixed;
eight lows are accepted by the owner in the limitations above; `docs/TODO.md`, "Security review (repeat, pass 1)" and
"(repeat, pass 2)"). The owner signed the repeat review off on 2026-10-10 (B28 in `docs/DECISIONS.md`). No outside reader
has read the record yet; one is recommended, not a gate (B26 Q12). Phase 7 (the vision bolt-on, pointer input and the Robot
tier, built 2026-10-10) opened new boundaries (pixels leave the JVM, screen content steers a model, Robot acts outside the
application) and was code-reviewed and security-reviewed on 2026-10-10/11: a delta review over everything it changed since the
signed record (`docs/design/phase7-contracts.md` §15; eight lenses with a sceptic each; findings S101-S148: 48 confirmed, 2 high,
13 medium, 33 low). Every finding of medium or above and 31 of the 33 lows are fixed; the two others, S142 and S144, are
accepted by the owner with the release notes above. The owner signed the delta review off on 2026-10-11 (B31 in
`docs/DECISIONS.md`), so Phase 7's boundary is reviewed, fixed and signed off; the limitations above are the build's own
statement of what masking and the Robot tier cannot do. The clipboard route (S110) was found by this review in code that
predates Phase 7 (the planner's mutating server) and is fixed for every route. Phase 8 (the input veto, threads, lanes
and cancellable provider reads, event masking and the view-less browser, hook ownership by code source and the class
deny-list, semantic input, the `events` root and captured facts, and pack capabilities; built 2026-10-19) opened new
surfaces on every lens and was code-reviewed and security-reviewed on 2026-10-19/20: a delta review over everything
changed since the record B31 signed (`docs/design/phase8-contracts.md` §15; eight lenses with a sceptic each; findings
S149-S180: 32 confirmed, 3 high, 8 medium, 21 low). Every finding of medium or above and 20 of the 21 lows are fixed,
S157 (these Phase 8 sentences were missing) by the documentation pass; the other, S180, is accepted by the owner with
the release note above. The owner signed the delta review off on 2026-10-20 (B40 in `docs/DECISIONS.md`), so Phase 8's
boundary is reviewed, fixed and signed off; the limitations above are the build's own statement of what the veto, the
masks and the new readers cannot do. Two findings were opened after that sign-off, on 2026-10-22, while gap 151's fix
was being specified, outside a review lens as S180 was: S181 and S182, both open and not yet rated (above, and
`docs/TODO.md`, "Phase 8 security delta review"). B40 does not cover them, and `docs/release-checklist.md` item 2 holds the next release until each is fixed or, if the
owner rates it low, accepted with a release note.
