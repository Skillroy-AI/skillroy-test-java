# BS11: `PAINT` capture determinism

A standalone Maven project (an aggregator of its own, not a module of the root reactor) that measures how deterministic
the built `swing.capture` (`PAINT`: `printAll` into an image on the EDT) is: the same window captured many times, after
restarts, after a state change and its reversal, while covered and after a move; then the same states across JDK lines,
architectures, the macOS desktop, font sets, antialiasing switches and Xvfb depths; then a tolerance study against
three real changes. It captures through the built primitive: the sample lending desk (seed A) is launched with the
built agent home (`agent/skillroy-agent-dist/target/skillroy-agent.zip`) plus the sample's agent module, through the
real `TargetLauncher`, and `swing.capture` is called over the control channel with the real `AgentClient` from the
built CLI distribution (`controller/skillroy-cli/target/skillroy-cli/lib/`), as the scenario tests do. The findings
are in `RESULT.md`.

## Run

JDK 25 or newer and Maven 3.9 to build; the Linux test image (OrbStack or Docker) to run. Offline: the controller and
protocol jars come from the CLI distribution on disk (system scope; never installed), HSQLDB 2.7.4 from the local
Maven repository. Never `mvn install`.

```
mvn -o -f spikes/BS11/pom.xml package
docker build --platform linux/arm64 -t skillroy-spike-bs11:tools    -f spikes/BS11/images/Dockerfile.tools    spikes/BS11/images
docker build --platform linux/arm64 -t skillroy-spike-bs11:fonts    -f spikes/BS11/images/Dockerfile.fonts    spikes/BS11/images
docker build --platform linux/arm64 -t skillroy-spike-bs11:fontpref -f spikes/BS11/images/Dockerfile.fontpref spikes/BS11/images
BS11_JDK8_ARM64=/path/to/linux-arm64-jdk8 BS11_JDK8_AMD64=/path/to/linux-x64-jdk8 spikes/BS11/run.sh [env ...]
BS11_DISPLAY_LOCK=/path/to/lock-dir spikes/BS11/run.sh mac-j25     # shows windows on this machine's desktop
spikes/BS11/run.sh compare
```

The derived images add packages from the base distribution's own apt repositories only (`xdotool`; then
`fonts-liberation2` and `fonts-noto-core`; then a `local.conf` that prefers Liberation for the generic families); the
base images are never modified. `run.sh` with no environment runs every Linux environment (about 25 s each on the
arm64 image, a few minutes each on the emulated amd64 image); `mac-j25` runs natively and is never part of the
default. `run.sh compare` compares every run with `arm64-j25` (and a few other pairs), compares the toy's changes and
the desk's one-character change within a run, and writes `target/compare/tables.md`.

## What one run does (`Runner`)

| Step | States (number of captures in the full run) |
|---|---|
| Desk, launch 1 | `desk-signed-off` (100); typed `12345` (10) and `12346` (10) into the entry field and cleared it again (10); the unknown-staff dialog (20) and the window after it closed (10); covered by a second process's window (10) and uncovered (10); moved with `xdotool` (10) and moved back (5) where `xdotool` exists; signed on (staff 103 signs on with the code alone) with one item on loan (20); the induction dialog of an induction-required item (20) and the loan after answering No (10) |
| Desk, launches 2 to 5 | `desk-signed-off@launchN` (10) and `desk-loan@launchN` (5): fresh processes |
| Toy | `toy-base` (20), then each change (5) and its reset (3): `label` (3.50 to 3.60, one character), `disable` (the button), `colour` (the swatch green to red), `tint` (the swatch's blue channel +6) |

Each capture is decoded (`ImageIO`) and hashed: SHA-256 over width, height and the ARGB of every pixel, and SHA-256
of the PNG bytes as sent. A state whose captures hash differently keeps every distinct variant (`png/<state>.vN.png`)
with its component tree (`swing.snapshot`, `tree/`). The state changes go through the agent's own primitives
(`swing.type`, `swing.dismiss`) and wait on the desk's events (`input.requested`, `window.opened`, `window.closed`) and
`swing.settle`; the harness never sleeps. Every wait is bounded; every process it starts is ended (`run-env.sh` also
ends a harness after 20 minutes).

## Evidence

`target/runs/<env>/`: `summary.json` (per state: captures, distinct raster and PNG hashes, the count per hash, mean
capture time; the restore relations), `captures.csv` (every capture), `env.json` (target Java, image, Xvfb screen,
`fc-match`, the font and freetype packages), `agent-info.json`, `png/`, `tree/`, `logs/` (the targets' consoles),
`harness.log`. `target/compare/`: one JSON and text file per pair of runs, the change pairs, and `tables.md`.

## Layout

| Path | What it is |
|---|---|
| `pom.xml` | The aggregator: the toy at `--release 8`, the harness at `--release 25` |
| `toy/` → `target/bs11/bs11-toy.jar` | `CaptureToy`: the toy window (state changes only through its own command field; nothing focusable, so no caret) and, with `-Dtoy.role=cover`, the covering window |
| `harness/` → `target/bs11/bs11-harness.jar` | `Bs11` (entry point), `Runner` (one environment's run), `Pixels` (decode, hash, diff, regions), `Compare` (pairs of runs, pairs of states, the tables) |
| `images/` | `Dockerfile.tools`, `Dockerfile.fonts`, `Dockerfile.fontpref`, `local.conf` |
| `run.sh`, `scripts/run-env.sh`, `scripts/run-mac.sh` | The environments, one container each; one environment's run; the macOS leg under the display lock |
