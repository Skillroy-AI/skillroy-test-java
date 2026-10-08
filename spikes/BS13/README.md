# BS13: the Claude API facts the Phase 5 contract relies on

A standalone Maven project (not a module of the root reactor) that checks, once and cheaply, each API fact that
`docs/design/phase5-contracts.md` (§2.2, §3, §4.1, §4.4, §7.2, §8.3, §10.2, §15.3) relies on, through the Anthropic Java
SDK version the contract pins (`com.anthropic:anthropic-java` 2.34.0). The findings are in `RESULT.md`.

## Layout

| Path | What it is |
|---|---|
| `pom.xml` | the probe: Java 25, `anthropic-java` 2.34.0; `package` writes `target/bs13-probe.jar` and its class path `target/lib/` |
| `src/main/java/…/Bs13Probe.java` | one `main` with sub-commands; each writes `target/results/<command>[-variant].json` |
| `deps/jsqlparser/pom.xml`, `deps/mcp-jackson2/pom.xml`, `deps/mcp-jackson3/pom.xml` | resolve the other two Phase 5 sets (JSqlParser 5.4; the MCP Java SDK 2.0.1 as core plus its Jackson 2 module, and as the `mcp` convenience artifact, which brings Jackson 3) into the local Maven cache, for the licence audit |
| `deps/cli-classpath/pom.xml` | a simulation of the CLI distribution's class path after Phase 5 (the project's Jackson set, managed as the root POM manages it, plus the three sets), to see what Maven's mediation picks; `-P 'jackson-bom,!root-pom-management'` imports `jackson-bom` instead |

## Build

```bash
mvn -f spikes/BS13/pom.xml package          # online the first time: downloads the SDK set into the local Maven cache
for d in jsqlparser mcp-jackson2 mcp-jackson3 cli-classpath; do
  mvn -f spikes/BS13/deps/$d/pom.xml dependency:resolve dependency:tree
done
```

Never `mvn install` (nothing here is meant for the local repository).

## Run

The model key is read from the environment only: `ANTHROPIC_API_KEY` (what the SDK's `fromEnv()` reads), or the
variable named by `-Dbs13.keyVariable=<name>` when the key lives under another name (the probe then hands the value to
the SDK's `AnthropicBackend` builder). A key that is not scoped to a workspace also needs the workspace id, read from
`ANTHROPIC_WORKSPACE_ID` or from the variable named by `-Dbs13.workspaceVariable=<name>`, and sent as the
`anthropic-workspace-id` header. Neither value is ever printed, logged or written: the recorder never sees the key
(the SDK's transport adds it below the recorder), records the workspace header by name only, and every report is
checked for both values before it is written. Response headers other than the request id are not recorded.

```bash
J=spikes/BS13/target/bs13-probe.jar

# Offline: no key, no network beyond loopback, no spend.
java -jar $J client       # the client's retry and timeout defaults
java -jar $J shapes       # stop_details, refusal categories, a fallback-served response, parsed by SDK 2.34.0
java -jar $J transport    # retries, timeouts, cancellation and a per-request deadline, against local sockets

# Every live flow without the network: requests recorded as the SDK serialises them, canned responses.
java -Dbs13.dryRun=true -Dbs13.batchPollMs=50 -jar $J live     # results in target/results-dryrun/

# A wrong key (expects HTTP 401; refuses to run with any other value).
ANTHROPIC_API_KEY=invalid-key-for-the-spike java -jar $J auth

# All live items in one run (estimated well under USD 0.25; stops once USD 1.00 is passed, -Dbs13.maxSpendUsd to
# change), with the key and the workspace id under their standard names:
java -jar $J live
# or, from a login shell whose profile exports them under other names (names only on the command line):
zsh -lc 'java -Dbs13.keyVariable=<KEY_VARIABLE> -Dbs13.workspaceVariable=<WORKSPACE_VARIABLE> -jar spikes/BS13/target/bs13-probe.jar live'

java -jar $J spend        # sums target/results/*.json
```

Single live items: `models`, `count` and `errors` cost nothing (metadata, token counts, refused requests);
`structured`, `tools`, `thinking sonnet`, `thinking opus` (one Opus call), `fallbacks`, `cache` and `batch` spend a
little. Run `cache` before `batch` and within the hour: the batch requests reuse the one-hour cache entry `cache`
writes. `batch` polls every 15 s for at most 20 minutes (`-Dbs13.batchWaitMinutes`, `-Dbs13.batchPollMs`); a batch
that has not ended by then is collected later with `batch <msgbatch id>`. `count` reports the token sizes of the cache
prefixes; `-Dbs13.shortRules=<n>` changes the short one (default 9 rules).

## The Claude Code CLI (item 10)

Run by hand in a terminal of your own, in an empty directory, with the key (and, for a key that is not scoped to a
workspace, `ANTHROPIC_CUSTOM_HEADERS="anthropic-workspace-id: <id>"`) in the environment:

```bash
claude --version
claude -p --bare --output-format json \
  --json-schema '{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}' \
  --model claude-sonnet-5-5 --max-turns 4 --max-budget-usd 0.25 --tools "" --permission-mode dontAsk \
  "Reply with OK in the answer field." < /dev/null > cli-first.json; echo "exit=$?"
claude -p --bare --resume "$(python3 -c 'import json;print(json.load(open("cli-first.json"))["session_id"])')" \
  --output-format json --json-schema '<the same schema>' --model claude-sonnet-5-5 --max-turns 4 --tools "" \
  --permission-mode dontAsk "Now reply with YES in the answer field." < /dev/null > cli-resume.json; echo "exit=$?"
```

Copy the two envelopes into `target/results/` (as `cli-*.json`) so that `spend` counts them.
