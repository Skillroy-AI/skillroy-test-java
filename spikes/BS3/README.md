# BS3: Byte Buddy retransformation, inline Advice, a bootstrap `Spy`, restore on detach

A standalone Maven project (an aggregator of its own, not a module of the root reactor) that prototypes, small, the
shapes of the Phase 6 modules `skillroy-agent-spy`, `skillroy-agent-hooks` and the hook path of
`skillroy-agent-bootstrap` (`docs/design/base-framework-design.md` §2.1, §2.2 step 6, §2.6), and runs them against the
sample lending desk and a toy class on Java 8 and Java 25 targets in every load mode. It is the hook go/no-go of
Phase 6 (`docs/TODO.md`, "BS3 Retransformation, Advice and bootstrap `Spy`"). The findings are in `RESULT.md`.

## Run

JDK 25 or newer and Maven 3.9 to build; the Linux test image (OrbStack or Docker) to run. The first build downloads
Byte Buddy 1.18.14, maven-shade-plugin 3.6.2 and the old host libraries (Byte Buddy 1.12.19, ASM 5.0.3, JNA 4.5.2);
everything after that works offline (`-o`). The sample's jar must be built in the main tree first
(`mvn -o -q -pl samples/skillroy-sample-lending-app -am package -DskipTests`). Never `mvn install`.

```
mvn -f spikes/BS3/pom.xml package
SKILLROY_PLATFORM=linux/arm64 SKILLROY_CI_IMAGE=skillroy-test-java-ci:bs8-arm64 SKILLROY_NO_XVFB=1 \
  SKILLROY_JDK8_HOME=/path/to/linux-jdk8 spikes/BS3/run.sh [configuration ...]
```

`run.sh` starts one container per configuration (`scripts/run-config.sh` lists them: `JAVAAGENT` and `ATTACH` on
Java 8 and Java 25, `ATTACH` on Java 25 with `-XX:+EnableDynamicAgentLoading` and with `-XX:-EnableDynamicAgentLoading`,
the `java.*` Spy, a host that ships old libraries, the unrelocated control, Byte Buddy's default Nexus injection,
`-Xshare:off`, a system class path append) and prints one verdict line each. About two minutes for all 17 on the arm64
image. With `BS3_RUNS=<name>` the evidence goes to `target/<name>/` instead of `target/runs/`, so that a repeated
matrix stays beside the first.

A run on the development machine needs no image:

```
D=spikes/BS3/target/bs3
java -javaagent:$D/bs3-agent.jar=out=<dir>,mode=javaagent,expectEngine=relocated -cp "$D/bs3-host.jar:$D/lib/*" \
  ai.skillroy.testjava.spikes.bs3.host.LendingDeskHost --out <dir> --toy-jar $D/bs3-toy.jar
```

Each run leaves in `target/runs/<configuration>/`: `probe.log` (everything the probe did, in order),
`result.properties` (one key per fact; `check.*` keys are PASS or FAIL), `stdout.log` and `stderr.log` of the target
JVM, `safepoint.log` (Java 25, `-Xlog:safepoint`; on Java 8 the stop times are in `stdout.log`), `attach.log` for the
`ATTACH` configurations, `java-version.txt`, `exit.txt`, and `classes/` with the class files of the byte check.

## Layout

| Path | What it is |
|---|---|
| `pom.xml` | The aggregator: Java 8 for everything (all of it runs inside a target JVM), the versions, the plugin set |
| `spy/` → `target/bs3/bs3-spy.jar`, `bs3-spy-java.jar` | `Spy`: one `volatile` handler slot, static `enter`/`exit`/`stubbed`, counters, nothing but `java.*` references. The second jar is the same class relocated into a `java.*` package (item 1) |
| `engine/` → `target/bs3/bs3-engine.jar` | The hook engine and the probe script, with Byte Buddy shaded and relocated under `ai.skillroy.testjava.spikes.bs3.shaded`; `bs3-engine-javaspy.jar` is the same against the `java.*` Spy, `bs3-engine-unrelocated.jar` the control whose Byte Buddy keeps its own package names |
| `engine/…/HookEngine.java` | Install (visibility probe, a byte recorder ahead of the hook, one `AgentBuilder` per hook with `RETRANSFORMATION`, explicit discovery, `disableClassFormatChanges()`, `InitializationStrategy.NoOp`, inline Advice), the touched-class ledger with its count of foreign transform events, remove with the byte check, and the Nexus switch |
| `engine/…/HookAdvice.java`, `RawHookAdvice.java` | The one advice shape for all three modes (`skipOn` plus an exit advice that assigns the return value), with `suppress = Throwable.class`; the raw copy without `suppress` is used once, to show what a host would see |
| `engine/…/HookRegistry.java` | The handler behind `Spy`: per hook a mode (`OBSERVE`, `CAPTURE`, `STUB`, `OFF`), a stub value, counters and the captured copies; events into a bounded ring |
| `engine/…/SpyBoot.java`, `SpyAccess.java` | The append by name and reflection only, the per-loader visibility probe, and everything that touches `Spy` itself (loaded after the append) |
| `engine/…/Probe.java`, `Base.java`, `ByteChecks.java`, `InFlight.java`, `Latency.java`, `SecondInstall.java` | The probe script: base install and fire, reconfiguration, item 1, fire costs, remove; item 3; item 5; item 4's sweeps; item 6's second engine; item 2 at the end |
| `engine/…/ClassFacts.java`, `Canon.java`, `ClassGen.java`, `Injection.java` | A class-file reader for the byte diffs, a canonical text (sorted members, no debug attributes), the generated classes of the size sweep, the snapshots of the shared loaders |
| `agent/` → `target/bs3/bs3-agent.jar` | `ProbeAgent` (`Premain-Class`, `Agent-Class`, `Can-Retransform-Classes`, `Can-Redefine-Classes`, `Can-Set-Native-Method-Prefix`) and `EngineLoader`, child-first for the project prefix like the real `AgentClassLoader` |
| `toy/` → `target/bs3/bs3-toy.jar` | `DepositRule`, the toy target (never on the class path) |
| `host/` → `target/bs3/bs3-host.jar` | `LendingDeskHost` (the sample's `AccessControl` on seed A, the toy in a `ToolShedLoader`), `HostTargets` (what the probe reaches by name), `StrictDelegationLoader` (delegates only `java.*`), `Attach` (the JDK 25 attach client) |
| `hostlibs/` → `target/bs3/hostlibs/` | `OldLibraries`, host code that uses Byte Buddy 1.12.19, ASM 5.0.3 and JNA 4.5.2, and those three jars |
| `run.sh`, `scripts/run-config.sh` | The matrix: one container per configuration |
