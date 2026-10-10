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
behind, and the model provider (its actors A1-A9 and A12-A14). A path from any of them to code execution, to a
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
  accepted by the owner (B28, 2026-10-10).
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
- **Not verified on Windows.** No Windows host has run this code: the access-control branches that make the token,
  credential and discovery files owner-only, the `.bat` launcher, the planner's `cmd.exe /c` form, `JAVAAGENT` as the
  logged-on user and the input rungs. Until the project says otherwise, treat Windows as unverified.
- **Not verified elsewhere.** The `jxbrowser` adapter against the real product; the paths that need a live model key;
  a hook detach that ran out of its time budget on a real application (a Byteman rule left installed by such a detach
  keeps running until the target restarts). The full list is §8 of the threat model.

## Reviews

The design was reviewed for security on 2026-10-02 (findings S1-S34, all fixed). The review was repeated in two passes on
2026-10-09 (findings S35-S62 and S63-S100: 66 findings, 2 high, 18 medium, 46 low; every finding of medium or above is fixed;
eight lows are accepted by the owner in the limitations above; `docs/TODO.md`, "Security review (repeat, pass 1)" and
"(repeat, pass 2)"). The owner signed the repeat review off on 2026-10-10 (B28 in `docs/DECISIONS.md`). No outside reader
has read the record yet; one is recommended, not a gate (B26 Q12).
