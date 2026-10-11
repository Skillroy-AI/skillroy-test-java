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

Only the latest minor release receives security fixes, until the project says otherwise. Nothing is released yet.

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
  Users), is refused with the `icacls` remedy (Phase 7 security fix, S141; not verified on a Windows host).
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
- **Not verified on Windows.** No Windows host has run this code: the access-control branches that make the token,
  credential and discovery files owner-only, the `.bat` launcher, the planner's `cmd.exe /c` form, `JAVAAGENT` as the
  logged-on user, the input rungs and the Robot tier (UIPI, session 0, the popup trigger on the release, a minimised frame's parking position and
  the cursor clamping of S104 and S105), and the ACL judgement of the run-output directory (S141, tested on built ACL values;
  it resolves CREATOR OWNER by its English name). Until the project
  says otherwise, treat Windows as unverified.
- **Not verified elsewhere.** The `jxbrowser` adapter against the real library, its pointer input included (the roles are
  named from the public API); the paths that need a live model key, among them the vision loop beyond the short sessions of
  the spikes and the Phase 7 live class's three short runs (the last on the final code, 7 of 7), a provider's handling of a
  toolset member whose `configs` disable it, and the planner's model choosing `approve`; a hook detach that ran out of its time budget on a real application
  (a Byteman rule left installed by such a detach keeps running until the target restarts). Phase 7 adds heavyweight popups
  and native drag and drop under the pointer primitives, windowed JCEF on a Java 8 target, a long vision session and the
  session's fold under the provider's history rule, and the committed image baselines on the amd64 image (a key that differs
  there is settled by an `approve` run on that leg). The delta review's fixes add a masked browser on real JCEF (the fixes ran
  against fakes and over real Swing components), a real JCEF frame under a `JOptionPane`, the paste and the middle click on an
  X11 desktop with a headed JVM, a second OS user for the writers' link rule, an APFS case-insensitive volume, a real
  multi-screen desktop and HiDPI (the glass bound at scale 3 through a seam only). The full list is §8 of the threat model.

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
predates Phase 7 (the planner's mutating server) and is fixed for every route.
