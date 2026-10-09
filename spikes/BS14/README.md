# BS14: Byteman in process

A standalone Maven project (an aggregator of its own, not a module of the root reactor) that prototypes, small, the
shapes of the Phase 6 modules `skillroy-agent-byteman-sys` (the `manager:` class and the curated helper on the system
class path) and `skillroy-agent-byteman` (the module that loads a user-supplied Byteman jar through the agent's own
`Instrumentation`, by reflection only, with no listener), and runs them on Java 8 and Java 25 targets in the Linux
image. It is the gate of `docs/design/phase6-contracts.md` §7.2: the two modules are built only if it passes. The
findings are in `RESULT.md`.

## Run

JDK 25 or newer and Maven 3.9 to build; the Linux test image (OrbStack or Docker) to run. Everything is offline:
Byteman 4.0.27 (`org.jboss.byteman:byteman`, LGPL-2.1-or-later) comes from the local Maven repository, at `provided`
scope for the sys jar's compilation and as the run-time jar the probe appends (the container sees the repository at
`/root/.m2`). Never `mvn install`.

```
mvn -o -f spikes/BS14/pom.xml package
SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=skillroy-test-java-ci:bs8-arm64 SKILLROY_NO_XVFB=1 \
  SKILLROY_JDK8_HOME=/path/to/linux-jdk8 spikes/BS14/run.sh [configuration ...]
```

`run.sh` starts one container per configuration (`scripts/run-config.sh` lists them) and prints one verdict line each;
the five take about ten seconds together on the arm64 image. A run on the development machine needs no image (the
socket item then fails: macOS has no `/proc`):

```
D=spikes/BS14/target/bs14 BM=$HOME/.m2/repository/org/jboss/byteman/byteman/4.0.27/byteman-4.0.27.jar
java "-javaagent:$D/bs14-agent.jar=out=<dir>;probe=$D/bs14-probe.jar;sys=$D/bs14-sys.jar;byteman=$BM;bmopts=listener:false,manager:ai.skillroy.testjava.spikes.bs14.sys.RuleManager" \
  -cp $D/bs14-toy.jar ai.skillroy.testjava.spikes.bs14.toy.ToyHost
```

Each run leaves in `target/runs/<configuration>/`: `probe.log` (every fact in order), `result.properties` (one key per
fact; `check.*` keys are PASS or FAIL; `probe.verdict`), `stdout.log` and `stderr.log` of the target JVM (the probe
writes `BS14-MARK <step>` lines to both, so that a console line can be attributed to the step that caused it),
`console.txt` (every console line that is neither a marker nor the toy's own), `java-version.txt` and `exit.txt` (the
exit code, the console line count and the count of JEP 451 lines).

## Layout

| Path | What it is |
|---|---|
| `pom.xml` | The aggregator: Java 8 for everything (all of it runs inside a target JVM), Byteman's version, the plugin set |
| `sys/` → `target/bs14/bs14-sys.jar` | `RuleManager` (`initialize(Retransformer, String, Integer)`, the one public method; package-private `take()` and `park(Object)`) and `RuleHelper` (the `(Rule)` constructor, a no-arg one to see which Byteman uses, `fired(Object...)` and the fixed-arity `observe(Object)` to `observe(Object x 8)`, the set-once weak handler slot). Compiled against Byteman at `provided` scope |
| `probe/` → `target/bs14/bs14-probe.jar` | The probe script in the shape of the module: `Probe` (items 1-6), `Second` (a second module instance reusing the parked `Retransformer`), `FiredHandler` (the handler behind `RuleHelper`'s slot, loaded by name after the appends), `Recorder` (the two byte recorders, ahead of and behind Byteman's transformer), `ClassRefs` (a constant-pool reader), `ProcNet` (listening sockets from `/proc/self`), `Host`, `Report` |
| `agent/` → `target/bs14/bs14-agent.jar` | `Bs14Agent` (`Premain-Class`, `Can-Retransform-Classes`, `Can-Redefine-Classes`; options separated by `;`) and `ProbeLoader`, child-first for the probe's package like a module loader |
| `toy/` → `target/bs14/bs14-toy.jar` | `ToyHost` (the host's thread `lending-toy` serves the probe's calls through two queues) and `LendingToy` (`ceiling(String code)`, `deposit(String itemCode)`) |
| `run.sh`, `scripts/run-config.sh` | The configurations: one container each |
