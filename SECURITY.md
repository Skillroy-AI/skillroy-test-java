# Security policy

Skillroy Test (Java) loads an agent inside a running Java application and drives it from a local controller, which
may also delegate exploration or resolution to a language model. Read this policy before running the agent on a
shared host.

## Reporting a vulnerability

Report security issues privately—never in a public issue:

- through the repository host's private vulnerability reporting for this repository, or
- by email to the address in [`NOTICE`](NOTICE).

You will receive a reply within seven days. There is no bug bounty. Please include the version or commit, the load
mode (`JAVAAGENT`, `ATTACH` or `EMBEDDED`), the Java versions of the target and the controller, and reproduction
steps or the relevant code path.

## Supported versions

Only the latest minor release receives security fixes.

## Scope and trust model

The full threat model is in [`docs/design/threat-model.md`](docs/design/threat-model.md).

In scope are paths from an untrusted actor—another OS user on a shared host, a same-user process without the
agent's token, untrusted web content inside an embedded browser view, hostile data rendered by the target
application, prompt injection steering a model or MCP client past confirmation/masking/veto gates, or a remote
network peer—to unauthorized code execution, credential or API-key disclosure, unconfirmed destructive actions, or
a falsified test pass from hostile input.

The local controller, CLI, MCP client and configured model session operate inside the test author's trusted
harness boundary for test inspection and authoring (while still enforcing read-only mode, `confirm` gates on
destructive primitives, credential redaction in outputs, `CaptureMask` / `MaskPolicy`, and `InputVeto`).

Out of scope: a compromised operator account, shell, JDK or Maven cache; hostile domain packs, agent modules,
overlays, hook catalogues or committed rules (these are reviewed code); physical or administrative access to the
host; code already running inside the target JVM reflecting on agent internals; target listeners outside Skillroy
(such as an exposed JDWP port); and external model or telemetry providers' own handling of data sent to them.

## Operational guidance and known limitations

- **Dynamic attach uses the JVM's same-user boundary.** Any process running as the same OS user that can attach to
  a JVM can load the agent into it. Start targets that must never be instrumented dynamically with
  `-XX:+DisableAttachMechanism`.
- **Java 21+ dynamic attach and Java 25 hooks.** Dynamic `ATTACH` on Java 21+ prints the JEP 451 warning and will
  be restricted by future JDKs; prefer `-javaagent` (`JAVAAGENT`) at launch. Appending the hooks spy jar to the
  bootstrap path on Java 25 prints a one-line CDS warning on stderr.
- **SQL guards complement—do not replace—database permissions.** `StatementGuard` and `SqlGuard` enforce
  read-only `SELECT` shapes, but SQL functions can still have side effects a parser cannot see. Always run test
  data channels with a read-only database role (and `readOnlyMode: always` on PostgreSQL).
- **Run outputs inherit the process umask.** `run.jsonl`, captures, bindings, ledgers and span files hold UI
  snapshots, events and row samples. The controller refuses output directories owned by another user or writable
  by others without the sticky bit and creates files exclusively, but new files still inherit the process umask
  (or parent ACL on Windows). On shared POSIX hosts, run with `umask 077` and pass `--out` a private directory you
  own; on shared Windows hosts, place `--out` and your working copy under a directory restricted to your user
  (`icacls <dir> /inheritance:r /grant:r "%USERNAME%":(OI)(CI)F`) and avoid directory junctions inside output
  trees.
- **Settings files must be owner-controlled.** `telemetry.yaml` and `inference.yaml` are accepted only when no
  other user could have written them. OTLP headers are sent over `https://` endpoints only, never plain loopback
  `http://`.
- **Pin CI containers and actions.** Pin base images by digest and GitHub Actions by commit SHA in production
  copies of `ci/examples/nightly-re-resolve.yml` and `ci/Dockerfile`.
- **Subprocess and proxy environment variables.** Playwright's driver inherits `DEBUG=pw:*`, `NODE_OPTIONS`,
  `PLAYWRIGHT_NODEJS_PATH` and `PLAYWRIGHT_DRIVER_DIR`; set them intentionally. On shared hosts, isolate
  `CLAUDE_CONFIG_DIR` when running `skillroy author`, and avoid plain `http://` loopback `ANTHROPIC_BASE_URL`
  proxies where another local user could bind an unused port. When using CDP without an agent-reported target ID,
  ensure the target application binds its remote-debugging port before untrusted local processes can squat it, or
  use `web.path: agent`.
- **Component and browser masking boundaries.** `CaptureMask` blanks component rectangles after painting and
  withholds component text, so layout and field widths remain visible; ensure packs mask both secret fields and
  any keypad/echo controls that feed them. Embedded web pages are masked at the whole-browser level (there is no
  per-DOM-element pixel mask); browsers without a Swing view are masked while any `CaptureMask` or `BrowserMask`
  is registered unless explicitly released by a `BrowserMask`. Commit image baselines (`<scenarioId>.images/`)
  only after reviewing that sensitive areas are masked.
- **Vision pixel transmission.** Screen captures leave the machine only when `--vision` is used alongside
  `images: true` in `inference.yaml` (`images: false` is the default). Glass captures (`mode: ROBOT`) are never
  sent to a model or MCP client.
- **Robot tier (`--vision v3`).** Native pointer injection via `java.awt.Robot` requires a double opt-in: both the
  agent option `robot=true` and `--allow-robot` on the command (never commit either to build configs). Because OS
  window changes between an EDT focus check and a native press cannot be prevented atomically, use V3 only in
  dedicated or supervised sessions.
- **Input vetoes and quiescent tasks.** `InputVeto` guards resolved Swing targets, ancestor chains, popups and
  root-pane default buttons on `ENTER`, but does not trace arbitrary Swing key bindings/accelerators or `web.*`
  actions inside an embedded browser—veto the enclosing window or bound control when needed. Tasks scheduled via
  `APP_QUIESCENT` run non-preemptively on the application thread at declared idle points; overruns are measured
  and penalized rather than interrupted.
- **Event ring window.** Event payloads are masked at publish by key and registered `MaskPolicy`s. Expectations
  over the `events` root fail closed as `ERROR` if the ring buffer overflows `eventRing` or an event was truncated
  at publish.
- **Unverified platforms and configurations.** Windows support (ACL checks on English and non-English localized
  built-in account names, `bin\skillroy.bat`, `cmd.exe` planner invocation, `JAVAAGENT` and `ATTACH`) is unit-tested
  via seams on Linux/macOS and has not yet been verified on a Windows host. `Robot` input outside the Linux Xvfb
  test image (macOS Accessibility, Windows UIPI/Session 0, Wayland, HiDPI scaling != 1) and `jxbrowser` beyond the
  single macOS JDK 25 verification run should likewise be verified in your target environment before relying on
  them.
