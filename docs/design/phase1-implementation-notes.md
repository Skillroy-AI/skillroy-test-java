# Phase 1 implementation notes

Behaviour of the foundation (`skillroy-protocol`, `skillroy-agent-api`, the controller seam types and the build)
that `phase1-contracts.md` leaves open. Read this before writing code against them. Updated 2026-10-02 after the
Phase 1 build was verified and again after Phase 1.x was built; behaviour of the modules themselves is in the
contract. The same day the controller side moved to Java 25 and the sample became the lending desk; the names and
the baseline below follow that, and the measured facts keep the Java version they were measured on.

## Build

- Every module sets `maven.compiler.release` itself (8 or 25). Test code compiles at the same level as main code.
- Test JVM arguments: the shared `argLine` is `-Djava.awt.headless=${skillroy.headless} ${skillroy.test.extraArgLine}`.
  To add arguments set the property `skillroy.test.extraArgLine` in the module pom; never override `argLine`.
- `skillroy.it.targetJava` and `skillroy.agent.home` are passed through to test JVMs when set.
- `-Djvm=<java executable>` (the Surefire/Failsafe user property) runs the test JVMs themselves on that Java. Use it
  with a Java 8 executable for the agent-side modules only (`-pl` them): a controller-side test JVM needs Java 25.
- Never run with `-Dskillroy.headless=false` outside `ci/run-in-linux.sh`: display tests open real windows.
- Failsafe executions are defined once in the root `pluginManagement`; a module that declares
  `maven-failsafe-plugin` in its build plugins runs `*IT` classes.
- Root packages are `ai.skillroy.testjava.` + `agent.bootstrap`, `agent.core`, `agent.swing`,
  `agent.data`, `controller`, `scenario`, `junit`, `cli`, `samples.lending`, `samples.lending.agent`,
  `samples.lending.pack`, `samples.lending.scenarios`.
- Agent home: `agent/skillroy-agent-dist/target/skillroy-agent/` and `target/skillroy-agent.zip` (top-level folder
  `skillroy-agent/`, versionless jar names). The zip is installed as `ai.skillroy:skillroy-agent-dist:zip`; it is
  never on a class path. `skillroy-agent-dist` has packaging `pom`.
- The bootstrap jar manifest already carries the entries of contract §2.3.
- Jackson is declared only in `skillroy-scenario`; `skillroy-junit` has `junit-jupiter-api` at compile scope.
- CLI: `controller/skillroy-cli/target/skillroy-cli/` (`bin/skillroy`, `lib/*.jar`, `LICENSE`, `NOTICE`,
  `THIRD-PARTY.txt`) and `target/skillroy-cli.zip`. Its integration tests unpack the installed `skillroy-agent-dist`
  zip, so `install` the agent modules first when building the CLI on its own.
- Legal files (1.x): the root pom's `copy-legal-files` execution of `maven-resources-plugin` (in `pluginManagement`)
  copies the root `LICENSE` and `NOTICE` into `META-INF/` of every module's classes, through the property
  `skillroy.root.dir` (`${project.basedir}/../..`: every module sits two levels below the root, as its
  `relativePath` says; `${project.parent.basedir}` evaluated to null). The agent home and the CLI distribution carry
  both at the top level; `DistributionContentIT` (agent-dist) and `DistributionNoticesIT` (cli) check the exact
  file lists, and the latter keeps `THIRD-PARTY.txt` in step with `lib/`. Jar and zip entries stay stamped
  2026-10-01 00:00, and a rebuilt jar is byte-identical.
- `samples/skillroy-sample-lending-scenarios` prepares, in `pre-integration-test`, `target/skillroy-agent/` (the
  agent home with the lending module in `modules/`) and `target/it-target/` (the lending desk's class path:
  `skillroy-sample-lending-app.jar`, `hsqldb.jar`, `skillroy-sample-lending-agent.jar`). `LendingEndToEndIT` and a
  manual CLI session use both.
- The committed plans are checked byte for byte by `PlansUpToDateTest`; regenerate them with
  `-Dskillroy.plans.update=true`.

## Test totals (measured 2026-10-02)

Measured before the move to Java 25 and to the lending sample, with the controller side on Java 21 (the Linux image
of that day was JDK 21). The current numbers and configurations are in `../TODO.md`; these stay as the record of
what was measured.

| Configuration | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| Native, `mvn -o -q install`, headless | 1303 | 0 | 0 | 110 (display tests, the end-to-end checks, one root-only test) |
| Linux image, whole reactor, display, Java 21 targets | 1315 | 0 | 0 | 2 (the two headless-only tests) |
| Linux image, whole reactor, display, Java 8 targets | 1315 | 0 | 0 | 2 |
| Linux image, agent-side modules, test JVMs and targets on Java 8 | 803 | 0 | 0 | 2 |

Each container configuration was run twice with identical counts at the time. The totals of the latest runs are in
`../TODO.md`; per-module counts are not kept there any more.

## Phase 1.x (2026-10-02)

The amendments A17-A33 and the hardening H1-H3 were built in separate working copies, merged into an integration
tree and verified there before that tree was merged into the main tree (`../TODO.md` records the merged tree's
verification once it is done). The integration tree's numbers, before the merge:

| Configuration | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| Native, `mvn -o -q verify`, headless | 1535 | 0 | 0 | 126 (display and end-to-end tests) |
| Linux image, whole reactor, display, Java 25 targets | 1547 | 0 | 0 | 4 |
| Linux image, whole reactor, display, Java 8 targets | 1547 | 0 | 0 | 4 |
| Linux image, agent-side modules and sample, test JVMs and targets on Java 8 | 944 | 0 | 0 | 4 |

The four skips in the image are the two headless-only Swing tests, the S8 fall-back test that needs a directory
owned by another OS user, and one more: a core test that needs a Java 9+ JVM where Java 8 is in use (the Java 8
target and Java 8 test JVM runs), else one sample-agent test (the Java 25 run). The sample's own later runs, with the eight new end-to-end checks in place
(contract §10: 21 tests per load mode, 84 in all), ended at 1570 / 0 / 0 / 158 natively and 1582 / 0 / 0 / 4 in the
image on both targets, with no flake over two Java 25 runs and one Java 8 run. The Java 8 test JVM reports
`java.version=1.8.0_492`.

**Per-call cost with the agent's own listener (H1).** Measured by the end-to-end test in the Linux image (x86-64
under emulation; the median and mean of 40 `agent.info` calls on a kept-alive connection, and of 20 `/ping`s, after
warm-up): about 1.4 to 5 ms per call and 0.5 to 3 ms per ping on a Java 8 target (the javaagent mode, the first of
each run, includes warm-up), and about the same on a Java 25 target. Before 1.x a call cost about 45 ms on a Java 8
target (the delayed-ACK stall of the JDK server), so `basic-loan` now takes about 0.2 to 0.35 s instead of about
2.0 s and `basic-loan-ui` about 0.6 s instead of 2.9 to 3.0 s: a scenario takes about a tenth of its old time on
Java 8. On Java 25 the times are about what they were. With `await:` and COMPONENT clicks (A29, A32) `basic-loan-ui`
takes about 0.8 to 1.0 s: each awaited step adds a `swing.settle` call and each COMPONENT click waits for the focus.

**Seams found at integration**, both fixed before the merge and covered by tests: the controller treated every
unsigned refusal (400, 413, 431, 501, 503) as an impostor and latched itself lost, where the agent sends those
before it could authenticate the request (contract §7); and in the EMBEDDED layout the lending module jar sits on
the application's class path as well as in the agent home, so the class-path pass of `ModuleLoader` through
`ServiceLoader` failed on it and, under S14, the target never became ready (contract §5, "Module discovery").

## JSON codec (`Json`)

- Strict RFC 8259. A byte-order mark and form feed are rejected. Numbers outside the range of a double are rejected.
- Both sides of the wire read the same text as the same number on every runtime. Java 8's `Double.parseDouble`
  reports some in-range numbers as infinite when they are written with many zeros after the decimal point and a
  large exponent (`0.000000000000000001e326` is `1e308`); `Json` re-parses such a token in normalised form, so it
  is accepted on Java 8 exactly as on newer runtimes.
- Duplicate object keys: the last value wins and the key keeps its first position.
- Integral numbers that fit a `long` parse to `Long` (`-0` -> `Long 0`); everything else to `Double`.
- Nesting limit 200 on parse **and** on write (cycles fail the same way).
- The writer writes any `Iterable` as an array, except `java.nio.file.Path` and `Throwable`, which are written as
  their `toString()`. A Jackson `JsonNode` is an `Iterable` and **not** a `Map`: convert YAML/Jackson trees to plain
  `Map`/`List` before handing them to `Json.write` or `AgentConnection.call`.
- `writePretty` ends with a newline.

## Wire types (`Event`, `RpcResult`, `RpcError`, `RpcException`, `PrimitiveDescriptor`, `Capability`)

- Immutable. They deep-copy maps and values into a normal form at construction: `Integer`/`Short`/`Byte` -> `Long`,
  `Float` -> `Double`, enums -> names, arrays -> lists, any other non-JSON object (a `Date`, a bean) -> its
  `toString()`. A value therefore equals its own JSON round trip. Convert dates and decimals to the string form you
  want **before** building a result.
- Constructors throw the unchecked `JsonException` for NaN/Infinity and for nesting deeper than 184 levels.
  Consequences: `EventBus.publish` must catch it and log rather than let it escape into an application thread; the
  dispatcher must build the `RpcResult` inside its try block so that a bad primitive result becomes
  `INTERNAL_ERROR`; `swing.snapshot` costs two JSON levels per tree level and caps `maxDepth` at 89 (a table at
  the last level nests three JSON levels deeper than a panel, so 90 would not fit).
- `RpcError` omits `data` when null; the others always write every key (null values as `null`).

## `JsonView`

- `str()` returns the decimal/boolean text of a number or boolean; it fails only for objects and arrays.
- `num()`/`requireNum()` accept integral doubles and numeric strings; `bool()` accepts `"true"`/`"false"` in any case.
- `has()` is false for an explicit JSON null. `raw()` and `list()` are read-only views.

## `TopicFilter`

- A null topic matches only a match-all filter. Extra accessors: `globs()`, `matchesEverything()`.

## `PrimitiveSpec`

- Getters: `name()`, `description()`, `paramsSchema()`, `resultSchema()`, `thread()`, `timeoutMs()`, `capabilityId()`,
  `mutating()`, `destructive()`, `describe()`.
- `Builder.param(name, jsonType, required, description)`: `jsonType` must be a JSON Schema type name (`string`,
  `number`, `integer`, `boolean`, `object`, `array`, `null`), optionally joined with `|` (`"string|array"`). The
  shorthand used in the contract tables (`bool`, `int`, `long`) throws `IllegalArgumentException`.
- The parameter name `_meta` is reserved. A non-positive timeout throws. Using both `param` and `paramsSchema` throws.

## Agent SPI Javadoc decisions

- An unknown id in `AgentModule.requires()` blocks that module from installing (logged, agent continues).
- `EventBus.lastSeq()` is 0 before any event; sequence numbers start at 1.
- `HandleRegistry.resolve(handle, type)` fails with `INVALID_PARAMS` on a type mismatch, `STALE_HANDLE` when unknown
  or collected.
- `ThreadBroker.post` logs failures rather than throwing.
- `CapabilityRegistry.set` declares the id if it is new.
- `DialogInfo` getters: `extractorId()`, `title()`, `text()`, `buttons()`.
- `QueryResult` getters: `columnNames()` (may be null), `columnTypes()` (may be null), `rows()`, `truncated()`.

## Controller seam types

- `AgentConnection` implementations must be thread-safe; `close()` does not throw. Every method throws
  `AgentLostException`; both `call` methods also throw `AgentRpcException`.
- `CallOptions(String correlationId, Long timeoutMs, boolean confirm)` with `defaults()`, `withCorrelationId`,
  `withTimeoutMs`, `withConfirm`.
- `CapabilityMatrix(Map<String,Capability>)` is public; `CapabilityMatrix.fromJson(Map)` reads the
  `{"capabilities":{…}}` shape.
- `EventStream` is an interface (`isOpen()`, `lastSeq()`, `close()`); `AgentClient.events` returns an implementation.
- `AgentRpcException(String method, RpcError error)`; `AgentLostException` and `AttachFailedException` have
  `(String)` and `(String, Throwable)` constructors. `Endpoint.toString()` hides the token.
- `AgentConnection.await` throws `AgentRpcException` for any JSON-RPC error other than `TIMEOUT` (amendment A31,
  built 2026-10-02; before, `AgentClient` wrapped it in an `IllegalStateException`). `PlanExecutor` reports it as
  `ERROR` / `ENVIRONMENT`. `AgentConnection.isLost()` (default false) reports the client's lost latch (H2).
- A test double for `AgentConnection` that is used with `PlanExecutor` must answer `trace.mark` (the run's start
  mark; a failure there ends the run as `ERROR` / `ENVIRONMENT`). `evt.query` failures are tolerated.
- `PlanExecutor` removes its own files of an earlier run from `RunOptions.outputDir`: give every scenario its own
  directory.
