# Phase 2 contracts: resolution, bindings, credentials, stored-row checks, sample scenario A

**Status:** as built. Specified 2026-10-02; built 2026-10-02 (four implementers, integrated and merged the same day),
stated here as built; reviewed 2026-10-03. The text was the specification the build followed; after the build it was
corrected to what the code does, from the implementers' deviation lists and a reading of the source. Where the build
differs from the specification, or decided what the specification left open, the passage says what the code does
and is marked "(as built 2026-10-02: …)" or carries a short "As built:" sentence. Signatures, names, defaults and
limits were checked against the source; what was run, on which JDKs and with what result is in `../TODO.md`.
**Scope:** Phase 2 of `base-framework-design.md` ("Roadmap": resolution and sample scenario A). This file is the
single source of truth for Phase 2 in the way `phase1-contracts.md` is for Phase 1: where it and the design overview
disagree on a Phase 2 detail, this file wins; where it and the code disagree, one of the two has a defect. Change the
contract first, then the code.
Passages that Phase 5 changes (`phase5-contracts.md` §16.1) were written in before its code and marked
"(Phase 5, built 2026-10-08; P5 §n)"; the build of 2026-10-08 marked each of them "(Phase 5, built 2026-10-08; P5 §n)"; its answers B23 and its
decisions B24 were confirmed by the owner on 2026-10-08.

**What it builds on.** The Phase 1 contract as built, plus the Phase 1.x items of the same day (amendments A17-A33
and hardening H1-H3), which are written into `phase1-contracts.md` and marked there "(1.x, built 2026-10-02)"; they
were built before Phase 2. The ones it leans on are named where they are used: `SecretKeys` (A17), fixture-secret
masking (A25), `MODAL_OPEN` as `FAIL` (A30), `AgentConnection.await` throwing `AgentRpcException` (A31),
`RunResult.degradedFidelity` (A29), the withheld driver message (S22, which replaced A26), the client's `lost` latch
(H2). References of the form "P1 §8.5" mean a section of `phase1-contracts.md`.

**Revision.** Third draft of 2026-10-02. The second draft followed three independent reviews (fit with the code as
built, implementability and tests, SPI cut and scope); what they changed is folded into the text, and the decisions that
moved are D1, D5, D7, D8, D16, D21, D22 and the new D23-D32 (§16). The third draft folds in the project lead's answers
to the questions of §17 (confirmed by the owner on 2026-10-06; Q10 and Q14 vetoed as their rows record), which add
amendment A34 (`secretParams`, §11.4), the sample's loopback database listener (§13.1, §14.2), the `autoSignOn` field of
`lending.access.check` (§13.2, §13.3), the rename of the sample's limit table to `role_cap` (§13.1) and the decisions
D33-D36; §17 keeps the questions with their answers. Phase 2 needs a handful of changes to the Phase 1 contract, listed
in §16.1; they were written into `phase1-contracts.md` before the code changed, marked "(Phase 2, specified 2026-10-02,
not yet built)" at the time and "(Phase 2, built 2026-10-02)" since. The as-built pass of 2026-10-03 brought this file
and those passages to what the code does; the passages of this file that it changed carry the marks described under
**Status**.

**Reading guide.** §1 scope; §2 modules; §3-§5 the file formats (scenario YAML, `credentials.yaml`, `plan.json`);
§6 the Java SPI; §7 data channels and stored-row checks; §8 the binder, step by step; §9 the bindings file; §10
failures; §11 the executor and what a run writes; §12 CLI and JUnit; §13 the sample; §14 acceptance; §15 which open
gaps are closed and which are deferred; §16 decisions taken here and the Phase 1 contract changes they need; §17
the questions put to the project lead and their answers.

---

## 1. Scope and non-goals

Phase 2 delivers:

- typed queries in a scenario's `resolve` section, an `oracle` section, and the resolution model: `Query`,
  `Resolution`, `Resolver`, `Verifier`, `Oracle`, `Strategy`, `Provenance`;
- tier (a) resolvers only: committed SQL or committed Java in the pack, run through the in-process `data.query`
  channel or a controller-side read-only JDBC channel;
- in-process verifiers that call the application's own decision code through a domain primitive, and oracles as
  committed Java with a bind-time `must_equal` guard;
- bindings files with the modes `replay`, `approve` and `live`; environment fingerprint v1; `REBIND_NEEDED` and
  `RESOLUTION_FAILED` raised by the binder;
- `credentials.yaml` as the only source of secrets for sign-on steps and JDBC;
- stored-row verification (`db.*` checks) over either channel; a slot captured from an event;
- the CLI commands `resolve` and `diff-bindings`, the `run` command's mode and environment options, and the JUnit
  glue's equivalents;
- a cost ledger that must be empty after a `replay` run;
- amendment A34 to the Phase 1 contract: a request's `_meta.secretParams`, which the agent core honours in its log,
  trace and error data, and which the executor sets for a secret argument (§11.4);
- sample scenario A, `return-over-limit-needs-supervisor`, on seeds A and B with one YAML file; the sample's
  loopback database listener (`lending.dbPort`, §13.1), so that the end-to-end acceptance covers the controller-side
  JDBC channel as well (§14.2).

**Non-goals.** Named so that their seams are respected, not built:

| Not in Phase 2 | Seam reserved here | Phase |
|---|---|---|
| Any model call; the provider gateway (`InferenceGateway`); model-written SQL; `SqlGuard`; budgets | `Strategy.LLM_SQL`, `LLM_REASON`, `LLM_VISION`; the strategy tokens `llm-sql`, `llm-reason`, `llm-vision` (parsed, rejected at compile time); the plan field `inference` (always `none`); the prefix `inference.` for controller-contributed capability ids and resolver ids (§3.3); `CostLedger` (exists, stays empty); `ResolverCandidate.confidence`, `Resolution.confidence`, `approval`, `usage`; the tier fall-through of §8.3 | 5 |
| Oracle cross-checks and `ORACLE_SUSPECT` | the `crossCheck` key of an oracle entry (parsed, rejected at compile time); binder step 8 (§8.3) | 3 (Phase 3, built 2026-10-03; `phase3-contracts.md` §3.1, §6) |
| Nightly `re-resolve` through a batch interface | the mode name `re-resolve` is reserved and rejected | 5 |
| Interactive sessions and run-time inference between steps (decision B15) | `live` mode; `ResolutionEngine.resolve` as a public single-query entry (§6.6); the names `ExecutorSession` and `StepJournal` (§11.5), not built | 5, 7 |
| The reserved scenario verbs `aiAct`, `aiExpect`, `pointer`, `expectImage` (decision B16) | a pack or base verb may not take these names (compile error from Phase 2 on) | 7 |
| Aggregates in stored-row checks (`sum(amount)`), `$env.now` | rejected with a message that names Phase 3 | 3 (Phase 3, built 2026-10-03: aggregates `sum`, `min`, `max` arrive, P3 §3.2, §7; `$env.now` is decided against, P3 §3.3, D39) |
| A fluent Java DSL, MCP, CDP/web, hooks | unchanged from the design | later |

Phase 2 adds **no new third-party artifact** (main scope). The YAML dependency that `skillroy-scenario` had in
Phase 1 moved one module down, to `skillroy-data` (§2.1). `java.sql` is part of the JDK; a JDBC driver for the
controller-side channel is supplied by whoever configures that channel (§7.2 says how it reaches the class path).

---

## 2. Module layout and build

### 2.1 Decision

- The resolution **SPI** and the **deterministic resolution engine** live in `skillroy-scenario`. Reason:
  `DomainPack` (package `…scenario.pack`) must reference `Query`, `Resolver`, `Verifier`, `Oracle` and
  `EnvFingerprinter`, so those types have to live where `DomainPack` lives or below it. The package split follows
  what is package-private in the code as built:
  - `ai.skillroy.testjava.scenario.resolve` (new): the SPI and its value types, that is everything a pack
    implements or constructs (`Query`, `QueryType`, `Codec`, `RecordCodec`, `NumberRange`, `Resolver`,
    `ResolverCandidate`, `ResolveContext`, `Verifier`, `VerifyContext`, `VerifierResult`, `Oracle`,
    `ResolutionCatalogue`, `EnvFingerprinter`, `FingerprintContext`, `Strategy`, `Tier`, `Resolution`, `Provenance`,
    `CredentialSpec`, `CredentialRef`, `Approval`, `LlmUsage`, `EnvFingerprint`, `BindMode`, `CostLedger`,
    `StoredTable`, `StoredColumn`). This package references only public types of `…scenario`.
  - `ai.skillroy.testjava.scenario` (existing): `Binder`, `ResolutionEngine`, `BindOptions`, `BindOutcome`,
    `BindException`, `BindListener`, `BindingsFile`, `BindingsDiff`, `Approver`, `OracleValue`. They need
    `References`, `Values`, `Matchers`, `ExpectationPath` and `Bindings.lookup(slot, fields, text)`, which are
    package-private there and stay so.
- One new controller-side module, **`controller/skillroy-data`** (package `ai.skillroy.testjava.data`, Java 25):
  the `DataChannel` abstraction with its two implementations, `Credentials`, `SecretMask`, `RowMask`, and the
  strict YAML reader. It sits *below* `skillroy-scenario`. Reason: it keeps `java.sql` code and its test database
  out of the scenario module, and gives `Credentials` a home that both the channels and the binder can reach.
- **The strict YAML reader moves down.** `YamlReader` (in Phase 1 package-private in `skillroy-scenario`, throwing
  `ScenarioException`) becomes the public class `ai.skillroy.testjava.data.yaml.StrictYaml` in `skillroy-data`,
  with the same reading rules and `YamlException(int line, String reason)`; `ScenarioLoader` wraps that in
  `ScenarioException`. The two Jackson artifacts move from `skillroy-scenario`'s pom to `skillroy-data`'s
  (`skillroy-scenario` keeps `jackson-databind` only if its own code still uses it). One reader, so
  `credentials.yaml` and scenarios are strict in the same way. As built: `YamlReader` is deleted;
  `StrictYaml.read(byte[])` returns a `StrictYaml.Doc` (`root()`, `lineOf(container)`, `keyLine(map, key)`,
  `MAX_DEPTH` 100); `YamlException` also has `YamlException(int line, String reason, boolean quotesInput, Throwable
  cause)` and `quotesInput()`, true when the reason could quote the input, so that the credentials reader can hold
  that text back (§4). `skillroy-scenario`'s pom names no Jackson artifact (its code uses none; it reaches Jackson
  only through `skillroy-data`), and Jackson is used by `StrictYaml` alone.
- **`skillroy-inference` is not created in Phase 2.** It arrives in Phase 5 and depends on `skillroy-scenario`: it
  implements the gateway, contributes tier (b) and (c) resolvers, `SqlGuard`, prompt masking, the budget meter and
  batch re-resolution. The design overview's module table (§3.1) listed the query/resolution model under
  `skillroy-inference`; that placement would make `skillroy-scenario` depend on `skillroy-inference` and
  `skillroy-inference` on `skillroy-scenario`. The overview was corrected to the layout below on 2026-10-02.
- The rules both data channels must share move into **`skillroy-protocol`** (Java 8), package
  `ai.skillroy.testjava.protocol.sql`, following the precedent of `SecretKeys` (A17) and `Auth` (H2): the statement
  guard, the column-value conversion and the JDBC read path. `skillroy-agent-data` keeps `data.query`, the provider
  lookup and `MaskPolicy`, and calls the moved classes. Behaviour of `data.query` does not change.

Dependency graph after Phase 2 (arrows = "depends on"; new edges in **bold**):

```
protocol   (gains …protocol.sql: StatementGuard, ValueConverter, ReadOnlyJdbc)
agent-data -> agent-api, protocol          controller -> protocol
**data -> controller, protocol**           scenario -> controller, **data**
junit -> scenario                          cli -> scenario, **data**, controller, protocol
lending-pack -> scenario                   lending-scenarios -> lending-pack, junit, dist, lending-app, lending-agent (test)
later: inference -> scenario               vision -> inference, scenario        mcp -> scenario
```

As built, the sample modules have four more edges: `lending-pack -> data` and `lending-scenarios -> data` (the pack's
resolvers, pre-flight and row mask use `DataChannel`, `DataException` and `RowMask`; the scenario tests build
`Credentials`), and in test scope `lending-scenarios -> cli` (the committed-files `diff-bindings` check of §14.1 item
9 calls the CLI) and `lending-agent -> agent-data` (the module's `MaskPolicy` test, §13.2). No cycle. Test-scope
additions: HSQLDB (the version already pinned in the root pom) in `skillroy-protocol` (the `jdk8` classifier, for the
moved tests), in `skillroy-data` and in `skillroy-scenario` (binder tests over a real JDBC channel).
`skillroy-sample-lending-scenarios` already has the sample application jar in test scope; that is where the pack's
SQL meets the real schema inside the test JVM (§14.1 item 8).

### 2.2 Classes moved to `skillroy-protocol`

| New class (`…protocol.sql`, Java 8, public final) | From | Contract |
|---|---|---|
| `StatementGuard` | `skillroy-agent-data` `StatementGuard` | unchanged rules (P1 §6, "Statement guard, as built"). `static String check(String sql)` returns the statement without its trailing `;` or throws `RpcException(INVALID_PARAMS, data {param:"sql", reason})` |
| `ValueConverter` | `skillroy-agent-data` `ValueConverter` | unchanged conversion (decimals as plain strings, ISO-8601 dates, 10000-character cap, `base64:` for binary, truncation marker) |
| `ReadOnlyJdbc` | the JDBC part of `JdbcExecutor` | `static Rows execute(Connection c, String sql, List<Object> params, int maxRows, int timeoutSeconds, Consumer<String> warn) throws SQLException, InterruptedException`: `setReadOnly(true)`, forward-only read-only `PreparedStatement`, parameters bound with `setObject` after the check of §7.1, `setQueryTimeout`, `setMaxRows(maxRows + 1)`, conversion through `ValueConverter`; restores the connection's state as in Phase 1. Failures while closing or restoring go to `warn` (the agent passes its `AgentLog`, the controller its logger), as `JdbcExecutor` logged them in Phase 1 |
| `Rows` | `skillroy-agent-data` `Table` | `columns:[{name,type}]`, `rows:[[…]]`, `rowCount`, `truncated`; `toJson()` / `fromJson(Map)` are the `data.query` result shape |

```java
public final class Rows {
    public static final class Column { public String name(); public String type();
        public Column(String name, String type); }           // as built 2026-10-02: public, so that code can build rows
    /** As built 2026-10-02: rows from their parts (lists copied; a row's width must equal the column count). */
    public static Rows of(List<Column> columns, List<? extends List<?>> rows, boolean truncated);
    public List<Column> columns();
    public List<List<Object>> rows();            // values in the forms ValueConverter produces
    public Object get(int row, int column);
    /** Index of the column with this name, compared without regard to case; -1 if absent or ambiguous. */
    public int index(String name);
    public int rowCount();  public boolean truncated();
    public Map<String,Object> toJson();  public static Rows fromJson(Map<String,Object> json);
    // as built: equals and hashCode by columns, rows and truncated; toString shows columns and counts, no cell value
}
```

**As built (2026-10-02), `ReadOnlyJdbc`.** It has two entry points with the signature above: `execute` applies the
parameter check of §7.1 (`checkParams(List<?>)`: an `IllegalArgumentException` that names the index and class of the
first other parameter, never its value) and then the read path; `executeLenient` skips that check and is used by the
agent's `data.query` alone, whose wire contract (P1 §6) accepts any JSON scalar, a `Double` included, and stays
unchanged; every controller-side caller uses `execute`. Neither closes the connection: the agent's `JdbcExecutor`
closes its own after each call, and the JDBC channel keeps its (§7.2). Two more public helpers: `describe(Throwable)`
(the class, and the SQL state and error code of the first `SQLException` in the cause chain, never a message) and
`sqlCause(Throwable)`. The agent's two log lines for a failure while restoring now read "could not end the read
transaction" and "could not restore the connection's read-only flag" (their endings about closing were dropped,
since the read path no longer closes); the texts the tests assert are unchanged. `ValueConverter` became public.

Notes:

- `Table`'s conversion from `QueryResult` (an agent-api type) stays in `skillroy-agent-data`, as a function that
  produces a `Rows`.
- No class of `skillroy-protocol` outside `…protocol.sql` references that package, so the wire types still load on a
  runtime without the `java.sql` module (as built, `JavaSqlBoundaryTest` scans the compiled classes for it).
- Column names are what the driver reports as labels (`ResultSetMetaData.getColumnLabel`, as built). HSQLDB reports
  them in upper case (`CODE` for `code AS code`), other databases do not. Code therefore reads columns **by
  position** or through `index(name)`; nothing in the base compares a reported name case-sensitively.

A statement accepted by one channel is accepted by the other, is bound with the same Java parameter types on both
(§7.1), and a value read through one channel has the same JSON form as through the other. That is what makes "over
either channel" true for resolvers and for `db.*` checks.

### 2.3 Build conventions

As P1 §1. `skillroy-data` compiles with `maven.compiler.release=25`. The root pom's module list gains
`controller/skillroy-data` before `controller/skillroy-scenario`. `.gitignore` gains `credentials.yaml` and
`*.credentials.yaml`.

---

## 3. Scenario YAML additions

The top-level keys of P1 §8.1 stay; `oracle` is added (after `resolve`). With A29's `ui` that makes thirteen. An
unknown key is still an error that names the key and its line. The strict YAML reading of P1 §8.1 applies to every
new section.

### 3.1 Grammar

```
resolve      := { <slotName>: slot, … }                      # slotName: [A-Za-z_][A-Za-z0-9_]*, not reserved (§3.3)
slot         := fixtureSlot | querySlot
fixtureSlot  := { fixture: <value>, credential?: credential }
querySlot    := { query: <QueryKind>,                        # a kind the pack declares (§6.2)
                  args?: { <arg>: <value>, … },              # default {}; may hold $references
                  strategy?: [ strategy, … ],                # default: every resolver the running pack has for the kind
                  verify?: <verifierId> | [ <verifierId>, … ],   # default: the pack's verifiers for the kind
                  credential?: credential }
strategy     := sql | java | llm-sql | llm-reason | llm-vision
credential   := { realm: <realm>, key: <field> }             # realm: [a-z0-9-]+; key: a field of the slot value

oracle       := { <oracleName>: oracleEntry, … }             # oracleName: as slotName
oracleEntry  := { fn: <OracleFn>,                            # a function the pack declares (§6.5)
                  args?: <map> | <list> | <scalar>,          # may hold $references
                  must_equal?: <value>,                      # bind-time guard (§8.3 step 9)
                  crossCheck?: … }                           # reserved for Phase 3; a compile error in Phase 2 (P3 §3.1 fills it)

verifyItem   := { <path>: matcher, … }                       # as Phase 1
              | { db.<table>: rowCheck }                     # a stored-row check (§7.4); exactly one key
rowCheck     := { where: { <column>: <scalar>, … },          # equality, AND; null means IS NULL; $references allowed
                  rows?: <count> | matcher,                  # default 1
                  capability?: <capabilityId>,               # a guarded check, as P1 §8.4
                  <column>: matcher, … }                     # at least one unless rows is given

capture      := { slot: <slotName>, fields: { <field>: <payloadPath>, … } }  # on waitFor and on await (§3.4)
timeouts     := { step?: <duration>, bind?: <duration> }     # bind: default 60s, at most 10m
```

Rules:

1. A slot has exactly one of `fixture` and `query`. `args`, `strategy` and `verify` are errors on a fixture slot.
2. `strategy` tokens map to `Strategy` values: `sql` -> `CANONICAL_SQL`, `java` -> `CANONICAL_JAVA`, `llm-sql` ->
   `LLM_SQL`, `llm-reason` -> `LLM_REASON`, `llm-vision` -> `LLM_VISION`. In Phase 2 an `llm-*` token is a compile
   error ("strategy llm-sql needs the inference module, which is not part of this version"). A token for which the
   compiling pack has no resolver of that query kind is a compile error that lists the strategies the pack offers.
   `strategy` is a *restriction*: without it, the resolvers are whatever the pack running at bind time has for the
   kind, in its order (§8.3). The plan does not name resolver ids (decision D24).
   (Phase 5, built 2026-10-08; P5 §13.1) `llm-sql` is accepted when the compiling pack declares a
   `SqlAllowList` for at least one source (`DomainPack.sqlAllowLists()`, §6.1): the compile error above is replaced by a
   check against the pack ("pack lending declares no SQL allow-list: llm-sql cannot be served"), and a slot may list
   `llm-sql` alone. `llm-reason` and `llm-vision` stay rejected, with "not in this version".
3. **Every query slot has at least one verifier.** If neither the scenario nor the pack names one for the kind,
   compilation fails: the verifier is the gate regardless of tier, and a slot without one would be unverified data.
   Fixture slots are literals written by the author and are not verified.
4. `credential` joins the slot to an entry of `credentials.yaml` (§4): the entry's id is the text of the slot
   value's field `key`. Its secret fields become fields of the slot value in memory only (`$assistant.password`).
   A secret field whose name is already a field of the value is a bind error (`ENVIRONMENT`). For a query slot, a
   candidate without an entry is dropped, which is how "must have a known password" is expressed. **A joined
   secret field resolves only in the arguments of step calls and cleanup calls** (§8.4): not in `resolve`, not in
   `oracle`, not in an expectation, not in a `db` check.
5. **A fixture may not hold a secret.** A fixture field whose key `SecretKeys.looksSecret` (A17) is a compile error
   that says to move the value to `credentials.yaml` and add `credential:`. This replaces the Phase 1 practice of a
   password in a fixture; A25's masking stays as the backstop for values that reach a run some other way. The plan
   reader enforces the same rule on a version 2 plan (a hand-edited plan cannot bring a secret back); a version 1
   plan is read as before and relies on A25. As built (2026-10-02), a fixture slot whose own name looks secret
   counts as a secret fixture too and is refused the same way, at compile time and by the plan reader.
6. `db.<table>` is accepted as an item of `verify` and as an entry of an `expect` step. `<table>` must be a name the
   pack declares (§7.4); a column that the pack does not list for it is a compile error. `db` is reserved as a root:
   a pack whose `FactProvider` claims the fact root `db` fails compilation. An `expect` step is a YAML mapping, so
   it can hold one check per table (duplicate keys are errors); `verify` is a list and can hold any number.

### 3.2 References, the resolution graph, and what replaces expressions

References keep the Phase 1 syntax and substitution rules (P1 §8.1). New:

- `$oracle.<name>` and `$oracle.<name>.field` refer to an oracle's output.
- `$env.id` is the environment id of the run (§8.1). No other field of `$env` exists in Phase 2 (Phase 3, built
  2026-10-03: nor in Phase 3; `$env.now` is decided against, P3 §3.3).
- A slot's `args` (or fixture value), an oracle's `args` and an oracle's `must_equal` may reference slots and
  oracles; each such reference is an edge. Slots and oracles together form **one directed graph**; a cycle is a
  compile error that names the cycle. The compiler writes the evaluation order into the plan (`resolutionGraph`,
  §5): a topological order in which, among the nodes that are ready, the one written first in the file comes
  first (`resolve` entries before `oracle` entries).
- A slot filled by `capture` (§3.4) may be referenced only in steps after the capturing step and in `verify`;
  a reference in an earlier step, in `resolve` or in `oracle` is a compile error.
- As built (2026-10-02): `$oracle.<name>` with a name the scenario does not declare, and a field of `$env` other than
  `id`, are compile errors (`$env.now` names Phase 3). A reference to an unknown slot inside `resolve` is left to
  bind time and fails there as `RESOLUTION_FAILED` / `REFERENCE`, as in Phase 1.

**There is no expression language** (gap 25 of `../TODO.md`, decided here). A value that has to be computed is the
output of an oracle (`Money.negate` in the sample); a margin or a range is an argument of the pack's query
(`deposit: {between: [20, 200]}`). Reason: an expression evaluated by the base would be a second, unreviewed place
where expected values are computed; oracles are committed, unit-tested Java. The cost is that each pack supplies
even trivial arithmetic as an oracle of its own (`Money.negate`); the base ships none, because rounding and scale
are the domain's business.

### 3.3 Reserved names

Slot and oracle names may not be `oracle`, `env`, `run`, `db`, `secret`, `credentials` or `inference`. Verb names may
not be `aiAct`, `aiExpect`, `pointer` or `expectImage`. A pack id, a capability id declared by an agent module, and
a resolver, verifier or oracle id of a pack may not be `inference` or start with `inference.`: that prefix belongs
to what the controller contributes from Phase 5 on (`ResolutionCatalogue.build()` and the pack loader enforce it;
as built 2026-10-02, the pack loader's test is the public `DomainPacks.reservedId(String)`).

(Phase 5, built 2026-10-08; P5 §13.1) `call` joins the reserved verb names: it is a base verb
(P1 §8.3), and a pack that declares a verb `call` is refused.

### 3.4 A slot captured from an event

The base verb `waitFor` and the `await` of `call`, `click`, `type` and `press` (A32) accept `capture`:

```yaml
- waitFor: { topic: loan.posted, within: 10s, capture: { slot: loan, fields: { id: loanId, total: total } } }
```

When the awaited event arrives, the executor binds the slot `loan` to `{id: <payload.loanId>, total:
<payload.total>}`. `fields` is required, so that every reference to the slot can be checked at compile time. The
right-hand side is a path into the event's payload with the dotted navigation references use (`loanId`,
`header.id`). A path that leads nowhere in the event fails the step as `FAIL` / `ASSERTION` ("event loan.posted has
no payload value at loanId"). A captured slot is never written to a bindings file: it belongs to the run. A slot
name may be captured once per scenario and may not also appear in `resolve`.

A pack verb captures through `PlannedCall.withCapture(CaptureSpec)` (§6.1) on a call whose sync is `EVENT` or whose
primitive is `sync.await`.

---

## 4. `credentials.yaml`

One file per environment, never committed (`.gitignore` names it), read by the controller only. It also names the
JDBC sources, so a little non-secret configuration (URL, timeouts) lives beside the secrets: one file, decided (Q7);
a split into an environment file and a credentials file is for the day environments need more non-secret settings.

```
file        := { version: 1, credentials?: realms, jdbc?: sources, masking?: masking }
realms      := { <realm>: { <id>: { <field>: <scalar>, … }, … }, … }   # every field value is a secret
sources     := { <sourceId>: jdbcSource, … }                           # sourceId: the id used in data.query
jdbcSource  := { url: <string>, user?: <string>, password?: <string>,
                 driver?: <class name>,                                # see §7.2 for how it is found
                 statementTimeout?: <duration>,                        # default 10s, at most 120s
                 loginTimeout?: <duration>,                            # default 10s (as built: at most 120s)
                 properties?: { <name>: <scalar>, … } }                # extra driver properties; values are secrets
masking     := { minLengthInText?: <int> }                             # default 8; owner's decision 2026-10-06 (Q14)
```

As built (2026-10-02), the details the grammar left open: `version` must be 1; a realm matches `[a-z0-9-]+`, a field
name `[A-Za-z_][A-Za-z0-9_]*`, a source id `[A-Za-z0-9][A-Za-z0-9_.-]*`, and an entry id is any non-empty text. A
field value, a `password` and a property value are text or a whole number (read as its text); a boolean or an empty
text is an error, except that a property may be `true` or `false`, which is then not a secret. A `url` must start
with `jdbc:`, a `driver` must be a Java class name, and a duration is a whole number with `ms`, `s` or `m`; both
timeouts are at most 120 s (a longer one is an error). A file larger than 1 MiB is refused. A YAML syntax error is
reported with its line, and the parser's own text is held back whenever it could quote the input
(`YamlException.quotesInput()`), since that text could hold a value.

```yaml
version: 1
credentials:
  staff:
    "100": { password: "…" }
    "200": { password: "…" }
jdbc:
  reporting:
    url: jdbc:postgresql://db.test.example:5432/app
    user: skillroy_readonly
    password: "…"
    statementTimeout: 10s
```

Rules:

- Read with `StrictYaml` (§2.1), the reader scenarios use; unknown keys are errors; ids are compared as text (quote
  codes).
- **Location.** `--credentials <file>` (CLI), else the system property `skillroy.credentials`, else the environment
  variable `SKILLROY_CREDENTIALS`, else `<bindingsRoot>/<envId>/credentials.yaml` when that file exists, else no
  credentials. The file is located and loaded **once per invocation**, before the agent is contacted (§12). A
  fixture slot, or a recorded binding in `replay`, that needs an entry the credentials do not have fails as
  `ENVIRONMENT` and names the realm and id it looked for, never a value. In `live` and `approve` a *candidate*
  without an entry is merely dropped (§8.3), so there a missing file shows as `RESOLUTION_FAILED` / `ALL_REJECTED`
  whose rejections say "no credential staff/<id>".
- **Permissions.** Where the file system has POSIX permissions, a file readable or writable by group or others is
  refused (`ENVIRONMENT`, "credentials file is accessible to other users"; as built 2026-10-02, any permission of
  group or others, execute included, and the message suggests mode 600). Where it has ACLs only, the rule of
  S7/S15 applies (owner-only). Where it has neither, the file is accepted and a warning is logged once.
- **What counts as a secret.** Every scalar under `credentials.<realm>.<id>`; a source's `password`; every value
  under `properties`; the user-info part of a `url` (S21). `Credentials.mask()` returns a `SecretMask` that knows
  all of them. As built (2026-10-02): also the value of a `key=value` pair inside a `url` whose key looks secret by
  `SecretKeys` (`?password=…`); a `true` or `false` property is not a secret, so that the text "true" is never
  masked.
- **Where a secret may go.** Into the params of a step call or cleanup call (a sign-on input) and into a JDBC
  connection. Nowhere else: §11.4 lists every output and the test that proves it.
- **`masking.minLengthInText`** (added 2026-10-06, the owner's decision on Q14, B19): the shortest secret that the
  controller's mask also replaces where it occurs inside a longer text (§7.3). Default 8; a whole number from 1 to
  1000. A shorter secret is masked only as a whole value or where it stands in double quotes. An environment whose
  test accounts have short credentials, as the environments of a private overlay may, lowers it and accepts the
  false redactions that follow (in that environment, every text that contains the digits of a four-character code
  is hidden). Nothing in the base refuses a credential for its length. The agent's own rule (A34, P1 §5) is not
  configured by this file: it replaces a secret of 8 or more characters anywhere in a log line and a shorter one
  where it stands as a token.
- A JDBC source named here is served by the controller-side channel; every other source id is served by the
  agent's `data.query` (§7.2). The account must be a read-only role; the base additionally sets the connection
  read-only and guards the statement, as a safety net and not as the boundary.

```java
package ai.skillroy.testjava.data;

public final class Credentials {
    public static final int VERSION = 1;                                     // as built 2026-10-02: the constants
    public static final String FILE_NAME = "credentials.yaml", PROPERTY = "skillroy.credentials",
            ENV = "SKILLROY_CREDENTIALS";
    public static Credentials load(Path file) throws CredentialsException;   // format, permissions
    /** From an already parsed document of the same grammar (tests, embedding). origin: a label for messages. */
    public static Credentials of(Map<String,Object> document, String origin) throws CredentialsException;
    public static Credentials empty();
    public static Path locate(Path explicit, Path bindingsRoot, String envId);   // the location rule; null = none
    public boolean has(String realm, String id);
    public Set<String> jdbcSources();
    public SecretMask mask();
    /** The secret fields of one entry. Only the binder and the JDBC channel call this.
     *  As built: null when the realm has no entry with this id. */
    public Map<String, String> secrets(String realm, String id);
    public String origin();                                                  // as built: the label messages use
    @Override public String toString();                                      // realm names and entry counts only
}
public final class CredentialsException extends Exception { }   // never a value; since 2026-10-03 never a key text
    public CredentialsException(String message);                             // as built: public
}
/** Replaces secrets in anything about to be written or printed. Thread-safe; immutable once built. */
public final class SecretMask {
    public static final String REDACTED = SecretKeys.REDACTED;               // "<redacted>"
    public static final int MIN_SUBSTRING = 8;                               // the default of minLengthInText()
    public static final int MAX_MIN_SUBSTRING = 1000;                        // 2026-10-06
    public static final SecretMask NONE;                                     // as built: knows no secret
    public static SecretMask of(Collection<String> secretValues);           // as built: nulls and "" ignored
    /** 2026-10-06: the same with the environment's threshold (§4 masking.minLengthInText), 1..1000; plus keeps it. */
    public static SecretMask of(Collection<String> secretValues, int minLengthInText);
    public int minLengthInText();
    public SecretMask plus(Collection<String> moreSecretValues);
    /** A deep copy of a JSON-like value in which a string value equal to a secret, and any occurrence of a secret
     *  of minLengthInText() (default 8) or more characters inside a longer string, becomes REDACTED. By value only:
     *  keys are not looked at.
     *  As built: a number whose text equals a secret is replaced too (booleans never), and a shorter secret is also
     *  replaced where it stands in double quotes inside a longer string ("1111"), A25's rule as built. */
    public Object apply(Object jsonLike);
    public String apply(String text);
    public boolean isEmpty();
}
```

`SecretMask` masks **by value only**. It is part (2) of A25's rule. Part (1) of A25, the key rule
(`SecretKeys.looksSecret`), stays where A25 puts it: it is applied by the code that writes a *fixture structure*,
not by the mask. A mask that also redacted by key would destroy this contract's own fields: `SecretKeys` matches
`credential` and `token`, so `"credential": {"realm": "staff", "id": "100"}` in a bindings file and `inputTokens` in
the cost ledger would be written as `<redacted>`. A test asserts that a bindings file and a ledger line keep those
fields. A25 was built first, and Phase 2 moved its by-value code here without changing the rule; one owner. As built
(2026-10-02), the scenario module keeps the key rule in its package-private class `FixtureSecrets` (Phase 1's
package-private `SecretMask` of that module, renamed) and uses this class for the value rule.

A secret shorter than `masking.minLengthInText` characters (default 8, §4) is masked only where it is a whole string
value (as built, also where it stands in double quotes inside a longer string, as A25 does it). That is A25's rule
and a deliberate limit: a four-digit code inside a longer text cannot be told from a number that happens to contain
the same digits, and lowering the threshold buys false redactions. Q14 kept the threshold fixed at 8; on 2026-10-06
the owner vetoed that in favour of a setting per environment (B19): the default stays 8, and `credentials.yaml` may
lower it for an environment whose existing test accounts have short credentials, which the environments of a
private overlay may have. The base never refuses a credential for its length. §11.4 words its guarantee
accordingly. **Documented rule for real environments:** test accounts should use credentials of 8 or more
characters, so that the substring rule protects them in free text; where that is not possible, lower
`masking.minLengthInText` and accept the false redactions. The sample's four-digit passwords are protected by
construction (§11.4), and `credentials.example.yaml` says so (the build of 2026-10-02 left the advice out; added on
2026-10-03, and a test reads the file through the real reader and checks for it; the setting was added to the
example on 2026-10-06).

---

## 5. `plan.json` additions

`planVersion` becomes **2**. Phase 1.x left `Plan.VERSION` at 1 (A29's `ui` key was added without a bump; checked
in `Plan` and `PlanCompiler` after the 1.x merge), so Phase 2 is the next number (Q1).

```json
{ "planVersion": 2, "scenarioId": "return-over-limit-needs-supervisor", "pack": "lending", "packVersion": "0.2.0",
  "sourceHash": "sha256:…", "inference": "none",
  "requires": ["lending.input", "…"],
  "slots": [
    {"name":"systemMax","kind":"query","query":"SystemLimit","args":{"type":"RETURN"},"verify":["lending.limits"]},
    {"name":"item","kind":"query","query":"LendableItem",
     "args":{"deposit":{"between":["20.00","200.00"],"lte":"$systemMax"}},
     "strategy":["sql"],"verify":["lending.deposit"]},
    {"name":"assistant","kind":"query","query":"StaffWithPermission","args":{"…":"…"},
     "verify":["lending.access.check"],"credential":{"realm":"staff","key":"code"}},
    {"name":"loan","kind":"capture","step":"s12","fields":["id","total"]} ],
  "oracles": [
    {"name":"auth","fn":"ExpectedAuthorisation",
     "args":{"amount":"$item.deposit","staffLimit":"$assistant.limits.RETURN","systemMax":"$systemMax"},
     "mustEqual":"SUPERVISOR_PROMPT"},
    {"name":"returnLine","fn":"Money.negate","args":["$item.deposit"]} ],
  "resolutionGraph": ["systemMax","item","assistant","supervisor","keys","oracle.auth","oracle.returnLine"],
  "steps": [ {"id":"s12","verb":"waitFor","source":{"line":54},
              "calls":[ {"primitive":"sync.await","args":{"timeoutMs":10000,"topics":"loan.posted"},
                         "sync":"NONE","timeoutMs":11000,
                         "capture":{"slot":"loan","fields":{"id":"loanId","total":"total"}}} ],
              "expect":[] } ],
  "verify": [
    {"db":"loan_line","source":"lending",
     "sql":"SELECT loan_id, line_no, item_code, qty, unit_deposit, amount, returned FROM loan_line WHERE loan_id = ? AND item_code = ? ORDER BY loan_id, line_no",
     "params":[{"kind":"INTEGER","value":"$loan.id"},{"kind":"TEXT","value":"$item.code"}],
     "rows":{"equals":1},
     "expect":{"amount":{"equals":"$oracle.returnLine"},"returned":{"equals":true}}},
    {"path":"slip.last.text","matcher":{"contains":"$item.title"}} ],
  "cleanup": ["…"], "launchProperties": {}, "artifacts": [], "onPrompt": {"unexpected":"fail"},
  "stepTimeoutMs": 30000, "bindTimeoutMs": 60000 }
```

| Key | Rule |
|---|---|
| `inference` | `none`, `bind` or `run`. Always `none` in Phase 2. From Phase 5: `bind` when a slot lists an `llm-*` strategy, `run` when a step needs a model while steps run. A plan with `run` is never executed in `replay`; it is reported as skipped (decision B16). An executor that reads a value it does not know refuses the plan |
| `inference` (Phase 5, built 2026-10-08; P5 §13.2) | version 5 writes `bind` when any slot's strategy list holds a token whose strategy `needsModel()` (`llm-sql`), and `none` otherwise; the reader accepts `none` and `bind` and refuses `run` with "not in this version" (B16). `resolveHash` covers what it covered, so a slot that gains `llm-sql` changes it |
| a `call` step (Phase 5, built 2026-10-08; P5 §13.1, §13.2) | compiles like any other step; the plan shows the primitive (P1 §8.3 for the verb) |
| `slots` | fixture and query slots in the order of the `resolve` section, then capture slots in step order |
| `slots[].kind` | `fixture` (as Phase 1, plus optional `credential`), `query`, `capture` |
| `slots[]` of kind `query` | `query`, `args` (keys sorted), `strategy` (**only when the scenario wrote it**), `verify` (verifier ids after defaulting), optional `credential`. Resolver ids are not written: resolvers are selected from the running pack at bind time, and the bindings file records which one answered (§9) |
| `slots[]` of kind `capture` | `step` (id of the capturing step), `fields` (the slot's field names, sorted) |
| `oracles[]` | `name`, `fn`, `args` (map keys sorted; a list or scalar as written), optional `mustEqual` |
| `resolutionGraph` | node names in evaluation order; an oracle is `oracle.<name>`; captured slots are not listed |
| a call's `capture` | `{slot, fields{field: payloadPath}}`, only on a call with sync `EVENT` or on `sync.await` |
| a `db` element (in `verify` and in a step's `expect`) | `db` (the pack's table name), `source`, `sql` (generated at compile time, §7.4), `params` (`[{kind, value}]`: the column kind and the template), `rows` (a matcher in normal form), `expect` (column -> matcher in normal form, keys sorted), optional `capability` (a guarded check, as P1 §8.4) |
| `requires` | the scenario's list, then the capability ids the slots' verifiers declare (§6.4) that are not already present, sorted |
| `bindTimeoutMs` | `timeouts.bind`, default 60000 |
| key order | the Phase 1 order; `inference` after `sourceHash`; `oracles` and `resolutionGraph` after `slots`; `bindTimeoutMs` last. A29's `ui` keeps the place the 1.x contract gives it |

**Determinism.** Unchanged in kind: the same YAML and pack version produce a byte-identical plan. Nothing from an
environment enters a plan: no envId, no fingerprint, no resolved value, no credential, no path. What the compiling
pack contributes (default verifiers, the SQL generated for `db` elements) is written out, so it is covered by the
committed-plan test.

**What makes a plan specific to a pack variant.** A plan names query kinds, verifier ids and oracle functions:
these are *roles*, and a variant pack (an overlay for one installation) may answer them with other code (§6.4).
The generated SQL of `db` elements, however, holds physical table and column names. A variant whose physical
schema differs compiles its own plans from the same YAML.

**Version rules.**

- The compiler always writes version 2 (Phase 3, built 2026-10-03: version 3, with `crossCheck` on
  an oracle entry and `aggregates` on a `db` element; the reader accepts 1, 2 and 3; P3 §4)
  (Phase 4, built 2026-10-07; P4 §7.3, D58: version 4, with the scenario key `web` and the web verbs' calls; the
  reader accepts 1 to 4, reads a version 3 plan as version 4 without `web` and refuses `web` below version 4). The
  sample's committed plans are regenerated.
  (Phase 5, built 2026-10-08; P5 §13.2) Version 5 (D81) supersedes 4 as the written version, with
  `inference: bind` when a slot lists `llm-sql` and the base verb `call`; the reader accepts 1 to 5. The sample's plans
  are regenerated once, which changes the `planHash` of every bindings file and no value.
- The reader accepts versions 1 and 2. A version 1 plan is read as version 2 with `inference: none`, no oracles, an
  empty graph and `bindTimeoutMs` 60000. Any other version is refused (`ScenarioException`, CLI exit 2). As built
  (2026-10-02), the reader also refuses a version 2 plan whose `inference` is anything but `none` ("needs the
  inference module, which is not part of this version") and a `bindTimeoutMs` above 600000 (10 minutes).
  (Phase 5, built 2026-10-08; P5 §13.2) The reader accepts `inference` of `none` and `bind`; `run`
  stays refused, with "not in this version" (B16).
- The version is raised whenever a plan written by the new compiler could be executed wrongly, or not at all, by an
  executor that knows only the previous version: a new key, a new value of an enumerated key, or a changed meaning.
  A plan is never silently down-converted.
- `Plan.hash()` (the canonical JSON of the whole plan) stays. New: **`Plan.resolveHash()`**, `"sha256:"` over the
  canonical JSON of `{slots (kinds fixture and query), oracles, resolutionGraph}`. Bindings are tied to
  `resolveHash`, not to `hash()` (§9, decision D7). The pack version is deliberately **not** part of it: P1 requires
  a new pack version whenever a verb compiles differently, and that must not invalidate the bindings of every
  scenario in every environment. What a pack change can really alter is caught where it matters: verifiers re-run
  in every mode, and an oracle whose output changed is `ORACLE_CHANGED` (§8.3).

### 5.1 Java shape of the changed Phase 1 types

`Plan`, `Scenario` and `PlannedCall` are public records as built; the additions keep every existing constructor, so
Phase 1 callers and tests compile unchanged.

```java
/** An element of verify and of a step's expect: a fact expectation (Phase 1) or a stored-row check. */
public sealed interface Check permits Expectation, RowCheck { Map<String,Object> toJson(); String describe();
    String capability(); }                                            // as built 2026-10-02: the guard, or null

public record Expectation(String path, Object matcher, String capability) implements Check { /* as built */ }

/** params: one per ? of sql. rows and expect hold matchers in normal form (P1 §8.4). */
public record RowCheck(String table, String source, String sql, List<Param> params, Object rows,
                       Map<String,Object> expect, String capability /*nullable*/) implements Check {
    public record Param(ColumnKind kind, Object value /*scalar or reference template*/) { }
}

public record Plan(/* … Phase 1 components …; verify becomes List<Check> */
                   String inference, List<Oracle> oracles, List<String> resolutionGraph, long bindTimeoutMs) {
    public static final int VERSION = 2;
    // (Phase 4, built 2026-10-07; P4 §7.3: VERSION is 4, after 3 in Phase 3, P3 §4.1)
    // (Phase 5, built 2026-10-08; P5 §13.2: VERSION is 5, after 4 in Phase 4, P4 §7.3)
    public record Slot(String name, String kind, Object value /*fixture*/, QuerySpec query /*query*/,
                       CredentialSpec credential /*nullable*/, CaptureDecl capture /*capture*/) {
        public Slot(String name, String kind, Object value);          // the Phase 1 constructor: a fixture
    }
    public record QuerySpec(String query, Map<String,Object> args, List<String> strategy /*empty = not restricted*/,
                            List<String> verify) { }
    public record CaptureDecl(String step, List<String> fields) { }
    public record Oracle(String name, String fn, Object args, Object mustEqual /*nullable*/, boolean hasMustEqual) { }
    public record Step(String id, String verb, int line, List<Call> calls, List<Check> expect) { }
    public record Call(/* … Phase 1 components … */ CaptureSpec capture /*nullable*/) {
        /* the Phase 1 constructors stay */
    }
    public boolean needsBindings();                                    // a query slot or an oracle (§8.1)
    public String resolveHash();
    // as built 2026-10-02: INFERENCE_NONE = "none", DEFAULT_BIND_TIMEOUT_MS = 60000, MAX_BIND_TIMEOUT_MS = 600000;
    // slot(String name) and oracle(String name) (null when absent), rowChecks() (every RowCheck, in plan order)
}
```

`Scenario` (the loaded YAML) gains the same content in source form: `Map<String,Object> oracle`, `Duration
bindTimeout`, and its `verify` and `expect` items may be `db` entries. The reader of a plan element decides by key:
an element with `db` is a `RowCheck`, one with `path` an `Expectation`; an element with both or neither is refused.

`ResolutionException` (as built: `(String reference, String message)`) gains a reason code:

```java
public ResolutionException(String reason, String reference, String message);
public String reason();          // "REFERENCE" for the Phase 1 constructor
// as built 2026-10-02: public static final String REFERENCE = "REFERENCE"; a null reason means REFERENCE;
public boolean hasReason();      // true when the exception was built with an explicit reason code
```

---

## 6. Java SPI

Packages as §2.1: `ai.skillroy.testjava.scenario.resolve` (resolution SPI and value types), `…scenario` (binder,
engine, bindings file), `…scenario.pack` (pack SPI, extended). Unless a rule below says otherwise, pack objects are
shared by every run in the JVM and must be stateless or thread-safe; the binder calls them on the executor's
thread, one at a time.

**Records a pack constructs** (`QueryType`, `ResolverCandidate`, `VerifierResult`, `StoredTable`, `StoredColumn`,
`CaptureSpec`) are built through their static factories. Their canonical constructors are not API: a later phase
may add a component without breaking packs that used the factories.

### 6.1 `DomainPack` and step SPI additions

```java
public interface DomainPack {
    // … Phase 1 members unchanged …
    /** Query kinds, resolvers, verifiers and oracles of this pack. Read once per compile and once per run. */
    default ResolutionCatalogue resolution() { return ResolutionCatalogue.EMPTY; }
    /** Supplies the pack's fingerprint components; null means the base components only. */
    default EnvFingerprinter fingerprinter() { return null; }
    /** Tables usable in db.<name> checks. */
    default List<StoredTable> storedTables() { return List.of(); }
    /** Applied on the controller to every row read over either channel before it is kept or shown (§7.3). */
    default List<RowMask> rowMasks() { return List.of(); }
    // (Phase 5, built 2026-10-08; P5 §5.2, §12.4) two more default members: sqlAllowLists(),
    // a List<SqlAllowList> with one entry per data source (default List.of(): tier (b) is unavailable for the
    // pack), and assistAnswerPrimitive(), by which a pack names the primitive that answers an in-application
    // overlay (default null: no overlay)
}

public record PlannedCall(String primitive, Map<String,Object> args, Sync sync, long timeoutMs, AwaitSpec await,
                          CaptureSpec capture /* nullable */) {
    // … Phase 1 constructors and factories unchanged (capture = null) …
    public PlannedCall withCapture(CaptureSpec capture);     // sync EVENT or primitive sync.await, else IllegalArgumentException
}
/** fields: slot field name -> path into the awaited event's payload. */
public record CaptureSpec(String slot, Map<String,String> fields) {
    public static CaptureSpec of(String slot, Map<String,String> fields);
}

public interface CompileContext {
    // … Phase 1 members unchanged …
    /** What the scenario declares for a slot, or null when there is no such slot. Never a value. */
    default SlotInfo slot(String name) { return null; }
    record SlotInfo(String name, String kind /*fixture|query|capture*/, String queryKind /*nullable*/,
                    CredentialSpec credential /*nullable*/, Set<String> captureFields /*empty unless capture*/) { }
}

public interface PreflightCheck {
    // … Phase 1 members unchanged: String id(); String check(AgentConnection agent) …
    /** Phase 2 entry; the executor calls this one. data() routes by source like every other read (§7.2). */
    default String check(PreflightContext ctx) throws Exception { return check(ctx.agent()); }
    interface PreflightContext { AgentConnection agent(); DataChannel data(); String envId(); }
}
// (Phase 5, built 2026-10-08; P5 §9.5)
public interface StepLibrary {
    // … Phase 1 members unchanged (P1 §8.3) …
    /** The JSON Schema of a verb's arguments, for MCP tools and the scenario schema; null = a free-form object.
     *  The verb's compile stays the authority: the compiler does not check arguments against the schema. */
    default Map<String,Object> argsSchema(String verb) { return null; }
}
```

`CompileContext.slot` is what lets a pack verb reject, at compile time, a `staff` argument whose slot has no
`credential` (§13.3); in Phase 1 a verb saw only its own arguments. `PreflightContext` lets a pre-flight that reads
the database work when the environment serves that source over JDBC. `mcpTools()` of the design overview is not
added in Phase 2.

### 6.2 Queries and values

```java
/** A typed question about an environment. Implemented by records of a pack; T is the type of the answer. */
public interface Query<T> { }

/** One query kind: the name scenarios write, the record that carries its arguments, and the type of its value. */
public record QueryType<Q extends Query<T>, T>(String kind, Class<Q> queryClass, Type valueType, Codec codec) {
    /** valueType is read from the class's own "implements Query<T>" declaration, so that a parameterised T
     *  (Map<String,String>) needs no type-token helper. codec = RecordCodec.DEFAULT. */
    public static <Q extends Query<T>, T> QueryType<Q,T> of(String kind, Class<Q> queryClass);
    public QueryType<Q,T> withCodec(Codec codec);
}

/** Between JSON-like data (scenario args, slot values, bindings files) and the pack's typed objects. */
public interface Codec {
    /** Compile time: checks the shape of args whose $references are still in place. Returns problems, empty = ok. */
    List<String> check(Type type, Object template);
    Object decode(Type type, Object jsonLike) throws CodecException;       // bind time, references resolved
    Object encode(Object typed);                                           // to JSON-like; total for supported types
    /** As built 2026-10-02: why this codec cannot handle the type at all, or null when it can; asked when a
     *  catalogue is built, so that an unsupported type is a compile error. The default accepts every type. */
    default String unsupported(Type type) { return null; }
}

/** A numeric condition usable as a query argument: {gt, gte, lt, lte, between:[lo, hi]} (between is inclusive).
 *  Every key that is present must hold (a conjunction). */
public record NumberRange(BigDecimal gt, BigDecimal gte, BigDecimal lt, BigDecimal lte, List<BigDecimal> between) {
    public boolean test(BigDecimal value);                    // null value: false
    /** The tightest bound over all keys, null = open; whether it is strict is test()'s matter. */
    public BigDecimal lowerBound();  public BigDecimal upperBound();
}
```

`RecordCodec.DEFAULT` (reflection over record components; no dependency):

| Java type | JSON-like form |
|---|---|
| a record | a map; keys are the component names. Decoding: an unknown key is an error; a missing key decodes as `null` (a missing primitive component is an error). Encoding writes the keys **sorted**, so that a bindings file does not depend on component order |
| `String`, `Boolean`/`boolean`, `Long`/`long`, `Integer`/`int` | the scalar; a whole number that does not fit is an error |
| `BigDecimal` | encoded as its plain string with its scale (`"34.99"`); decoded from a string or a number |
| an `enum` | its `name()` |
| `LocalDate`, `Instant` | ISO-8601 text (`2026-10-02`, `2026-10-02T09:30:00Z`) |
| `List<E>`, `Map<String,V>` | list (order kept); map, written with sorted keys (values may be null) |
| `Double`/`double`, `Float`/`float` (Phase 5, built 2026-10-08: `Double` and `Float` as JSON numbers; P5 §5.4) | a JSON number, decoded from any finite number (a whole one included; text is not a number), a `float` only when the number fits one; encoded as a `Double`. What a model's structured answer states as a number, such as `SqlProposal.confidence` |
| `Optional`, arrays, any other class | not supported: an error when the pack's catalogue is validated (compile time) |

A slot value is the encoded form of `T`: any JSON-like value, including a scalar (`$systemMax`) or a map
(`$assistant.code`). Money is a decimal string, as in Phase 1 facts, and compares numerically in matchers (P1 §8.4).
**A slot value is never null**: a resolver that finds nothing returns no candidate, and a candidate whose value is
null is a pack defect (`ENVIRONMENT`). Absence inside a value is modelled by the value type (a null map entry such
as `limits.RETURN`).

### 6.3 Resolution, strategy, provenance

```java
public enum Strategy { FIXTURE, CANONICAL_SQL, CANONICAL_JAVA, LLM_SQL, LLM_REASON, LLM_VISION, REPLAY }
    // as built 2026-10-02: token() ("sql", "java", "llm-sql", …; null for FIXTURE and REPLAY), ofToken(String),
    // tier(), needsModel(), and TOKENS, the five scenario tokens
public enum Tier { A, B, C }                 // CANONICAL_* and FIXTURE: A; LLM_SQL: B; LLM_REASON, LLM_VISION: C

/** The accepted answer to one query, with everything needed to review it. Built by the engine, never by a pack. */
public record Resolution(String slot, String query, Object args /*encoded*/, String argsHash,
                         Object value /*encoded, without secret fields*/, Strategy strategy, Provenance provenance,
                         List<VerifierResult> verified, double confidence /*1.0 for tier A*/,
                         CredentialRef credential /*nullable*/, Approval approval, LlmUsage usage /*null in Phase 2*/) {
    public Resolution withApproval(Approval approval);            // as built 2026-10-02: a copy, for the binder
}

/** resolverId: the resolver whose candidate was accepted. statements and calls: everything that resolver read,
 *  in order. candidates: how many reached the verifiers, over all resolvers asked. */
public record Provenance(String resolverId, List<Statement> statements, List<Call> calls, List<String> notes,
                         int candidates, boolean candidatesTruncated, List<Rejected> rejected) {
    /** channel: "agent" or "jdbc". params are the query's own arguments, masked. rowSample: masked, at most 3 rows. */
    public record Statement(String channel, String source, String sql, List<Object> params, int rowCount,
                            boolean truncated, List<List<Object>> rowSample) { }
    /** A read-only primitive the resolver called through ctx.agent(): the review trail of a non-SQL resolver. */
    public record Call(String primitive, Object params /*masked*/) { }
    /** A candidate that was not accepted: which resolver offered it, its label, and why. */
    public record Rejected(String resolverId, String label, String reason) { }
}
public record VerifierResult(String verifierId, boolean passed, String reason, Map<String,Object> evidence) {
    public static VerifierResult pass(String reason, Map<String,Object> evidence);   // the engine fills in the id
    public static VerifierResult fail(String reason, Map<String,Object> evidence);
    public VerifierResult withVerifierId(String id);                 // as built 2026-10-02: how the engine fills it in
}
public record CredentialSpec(String realm, String key) { }      // what a slot declares
public record CredentialRef(String realm, String id) { }
public record Approval(State state, String by) { public enum State { NOT_REQUIRED, APPROVED } }
// (Phase 5, built 2026-10-08; P5 §7.1) State gains REQUIRED, between NOT_REQUIRED and
// APPROVED; the record keeps its shape
public record LlmUsage(String model, String requestId, long inputTokens, long outputTokens, String costUsd) { }
public record EnvFingerprint(int version, String hash, Map<String,String> components) { }
```

The design overview sketched `Resolution<T>` holding a typed value and built by the resolver, and
`Optional<Resolution<T>> resolve(…)`. This contract deviates, with reasons; the overview's §3.3 was brought in line
on 2026-10-02:

- **A resolver returns candidates; the engine builds the `Resolution`.** A resolver that returned a finished
  resolution could skip the verifier. With candidates, the gate is in one place (§8.3), and "the first candidate
  the application itself accepts" has a defined meaning.
- **`Resolution` holds the encoded value**, because it is what is written to the bindings file, compared in
  `replay` and substituted for references. Packs still work with typed `T` (candidates, verifiers).
- `REPLAY` is the strategy a run's own `bindings.json` (§11.3) reports for a slot taken from a bindings file; the
  file itself keeps the strategy that produced the value.
- Smaller differences from the overview's sketch: a query's kind is declared by `QueryType`, not by a
  `Query.kind()` method; `Resolver.strategy()` replaces `Resolver.tier()` (the tier follows from the strategy); a
  verifier receives a `VerifyContext` instead of an `AgentSession`; `DomainPack` has one `resolution()` catalogue
  instead of four lists; the fingerprint holds neither the plan hash nor the pack version (D7, D8); the bindings
  file has no `env.profile`.

**Where the Phase 5 fields get their values.** `Resolution.confidence` is the accepted candidate's
`ResolverCandidate.confidence` (1.0 from every tier (a) resolver). `Resolution.usage` is filled by the engine from
the ledger entries recorded during that slot's `candidates` calls; a resolver never builds it. In Phase 2 both are
constant (1.0, null).

(Phase 5, built 2026-10-08; P5 §5.4, §7.3) Phase 5 builds the rule above: as built in Phase 2 the
engine passes `null` for `usage`. It then fills `usage` from the ledger entries of the slot's model call
(`LedgerWriter`, P5 §7.3), and `confidence` is the proposal's own, carried by the candidate
(`ResolverCandidate.confidence`), where tier (a) gives 1.0. The `Approval` of a tier (b) resolution is `REQUIRED` until
a person approves.

### 6.4 Resolvers and verifiers

```java
public interface Resolver<Q extends Query<T>, T> {
    /** Stable id written into provenance, by convention "<pack>.<sql|java>.<Kind>". The id is a role: a variant
     *  pack replaces a resolver by registering another under the same id. */
    String id();
    Class<Q> queryClass();
    Strategy strategy();                                   // Phase 2: CANONICAL_SQL or CANONICAL_JAVA
    /** Candidates, best first. Deterministic: the same data and query give the same list in the same order.
     *  An empty list means nothing in this environment satisfies the query. Never null, no null element. */
    List<ResolverCandidate<T>> candidates(Q query, ResolveContext ctx)
            throws ResolutionException, DataException, AgentRpcException, AgentLostException, InterruptedException;
}
/** label: a short identifying text for messages and provenance ("staff 100"); never a secret. */
public record ResolverCandidate<T>(T value, String label, double confidence) {
    public static <T> ResolverCandidate<T> of(T value, String label);      // confidence 1.0
}

public interface ResolveContext {
    AgentConnection agent();                 // read-only view, see below; calls are recorded into the provenance
    DataChannel data();                      // routes by source id; statements are recorded into the provenance
    CredentialSpec credential();             // the slot's credential declaration, or null
    CredentialIndex credentials();           // existence only
    String envId();
    BindMode mode();
    int maxCandidates();                     // 20: how many candidates the engine will hand to verifiers
    long remainingMs();                      // what is left of the bind budget
    CostLedger ledger();
    void note(String text);                  // added to Provenance.notes
    /** For a statement whose result the resolver needs whole: throws ResolutionException(reason TRUNCATED). */
    Rows requireComplete(Rows rows) throws ResolutionException;
}
public interface CredentialIndex { boolean has(String realm, String id); }

public interface Verifier<Q extends Query<T>, T> {
    /** The name scenarios write in verify:, by convention the primitive that is asked ("lending.access.check").
     *  It names the application's decision code, which several query kinds may share. */
    String id();
    Class<Q> queryClass();
    /** Capability ids the verifier needs; the compiler adds them to plan.requires. */
    default List<String> requires() { return List.of(); }
    /** Whether the application itself agrees that the candidate answers the query. Must not throw for "no":
     *  returns VerifierResult.fail with a reason. evidence: the calls made and what the application answered. */
    VerifierResult verify(Q query, T candidate, VerifyContext ctx)
            throws DataException, AgentRpcException, AgentLostException, InterruptedException;
}
public interface VerifyContext { AgentConnection agent(); DataChannel data(); String envId(); long remainingMs(); }

public final class ResolutionCatalogue {
    public static final ResolutionCatalogue EMPTY;
    public static Builder builder();
    /** Starts from another catalogue: how a variant pack overlays a base pack. */
    public static Builder builder(ResolutionCatalogue base);
    public static final class Builder {
        public <Q extends Query<T>, T> Builder query(QueryType<Q,T> type);
        public Builder resolver(Resolver<?,?> resolver);                 // order of calls = order tried
        public Builder verifier(Verifier<?,?> verifier);                 // order of calls = default order
        public Builder oracle(Oracle<?,?> oracle);
        /** Replaces the registered object with the same id (and, for a verifier, the same query class), keeping
         *  its position. IllegalStateException when there is nothing to replace. */
        public Builder replace(Resolver<?,?> resolver);
        public Builder replace(Verifier<?,?> verifier);
        public Builder replace(Oracle<?,?> oracle);
        public ResolutionCatalogue build();                              // validates: see below
    }
    public QueryType<?,?> query(String kind);  public Set<String> kinds();
    public List<Resolver<?,?>> resolvers(String kind);  public List<Verifier<?,?>> verifiers(String kind);
    public Verifier<?,?> verifier(String kind, String id);               // null when the kind has none of that id
    public Oracle<?,?> oracle(String fn);  public Set<String> oracleFns();
}
```

`build()` fails with `IllegalStateException` (reported as a compile error that names the pack) when: two query
types share a kind, or one query class is registered under two kinds; a resolver or verifier names a query class
that is not declared; two resolvers share an id, two oracles share a function name, or two verifiers share an id
**and** a query class (the same id for different query classes is allowed: one decision primitive may verify
several kinds); a resolver's strategy is an `LLM_*` value; an id is or starts with `inference`; a query class,
value type or oracle type is not supported by its codec.

**Rules for resolvers and verifiers.**

- *Deterministic and read-only.* The `AgentConnection` they receive is a view:
  - `call` of a primitive whose descriptor (from one `schema()` call) is `mutating` or `destructive` is refused
    with `IllegalStateException`, reported as `ENVIRONMENT` ("pack defect: resolver lending.sql.X called a
    mutating primitive"). A primitive the schema does not list is passed through and answered by the agent
    (`METHOD_NOT_FOUND`);
  - `await` and event streams throw `UnsupportedOperationException`; `close` does nothing;
  - every call's timeout is clamped to `remainingMs()`.

  It relies on modules declaring `mutating` correctly. Binding must never change the application's state: a bind,
  failed or not, leaves the target as it found it (§10).
- *Bounded, without a watchdog.* The whole bind has the budget `bindTimeoutMs`, measured on `BindOptions.clock`.
  The engine checks it before each pack call, and the context wrappers clamp every agent and data timeout to what
  is left (as built 2026-10-02: and to at most 120 s, the agent's cap on a call's timeout; since 2026-10-03 a data
  statement without a timeout of its own gets the source's default, `statementTimeout` for a JDBC source and the
  `data.query` descriptor's timeout for the agent's, clamped the same way, where the build of 2026-10-02 gave it the
  whole remaining budget, and a `TIMEOUT` of a call whose timeout the budget cut is reported as the budget's
  `ENVIRONMENT`); there is no extra thread. When the budget is spent the bind fails as `ENVIRONMENT` ("binding timed out
  in slot …"), not as `RESOLUTION_FAILED`: nothing is known about the environment's data. Time spent inside
  `Approver.review` is not counted.
- *SQL is committed text.* A `CANONICAL_SQL` resolver's statements are string constants in the pack with `?`
  parameters, in the forms of §7.1. Each ends in an `ORDER BY` whose columns determine the order completely (a
  unique key last). Conditions belong in the statement, not in Java over a whole table: a statement should return
  candidates, not the table.
- *Truncation.* With a total order, the first 200 rows are a deterministic prefix, so a truncated *candidate*
  statement is allowed; the engine sets `candidatesTruncated`. A statement the resolver needs whole (a lookup it
  joins against in Java) goes through `ctx.requireComplete(rows)`, which fails the slot as `RESOLUTION_FAILED`
  reason `TRUNCATED`.
- *No secrets.* A resolver never selects a secret column, and never sees a credential (only whether one exists).
- *Credentials early.* When `ctx.credential()` is not null, a resolver should skip rows without an entry
  (`ctx.credentials().has(realm, id)`) before doing further work on them. The engine drops such candidates anyway
  (§8.3); doing it early keeps an environment with thousands of rows and three test credentials cheap.
- *The verifier asks the application.* A verifier's evidence comes from the application's own decision code through
  a domain primitive. Re-running the resolver's SQL is not a verifier. Where a base primitive is the application's
  own answer (the UI tree for a key that must exist), a base primitive is acceptable.
- A verifier is selected for a slot by `(kind, id)`; a `verify:` id for which the kind has no verifier is a compile
  error ("no verifier lending.deposit for kind SystemLimit").
- *Before sign-on.* Binding happens before the first step, so a verifier must be callable in the state a scenario
  starts in. An application whose decision code needs a signed-on session cannot be served by a verifier in
  Phase 2; that limit belongs to gap 13 (session-scoped reads).
- *Exceptions.* `ResolutionException` from a resolver fails the slot (`RESOLUTION_FAILED`, the exception's reason,
  or `RESOLVER` for the two-argument constructor, with the text in `detail.message`). A `DataException`, an
  unhandled JSON-RPC error, a `RuntimeException`, a null list or a null candidate from a resolver or verifier is
  `ENVIRONMENT` (§10).
- (Phase 5, built 2026-10-08; P5 §8.3) *Batchable.* A resolver may implement `BatchableResolver`
  (`skillroy-scenario`): `prepare(query, ctx)` returns an opaque request where `candidates` would call a model, and
  `complete(query, ctx, answer)` returns the candidates once the batch has answered. Only `re-resolve` (§8.3) uses them;
  every other mode calls `candidates`.
- (Phase 5, built 2026-10-08; P5 §5.5) *Contributed resolvers.* Besides the pack's, the engine asks
  the resolvers that a `ResolverContributor` offers (§6.6), after the pack's and in `ServiceLoader` order, both
  restricted by the slot's `strategy` list. Their ids start with `inference.`; the one Phase 5 offers is `inference.sql`
  (P5 §5.4), with the strategy `LLM_SQL`.

### 6.5 Oracles

```java
/** Computes an expected outcome from bound values. Committed, reviewed, unit-tested Java. */
public interface Oracle<I, O> {
    String fn();                             // the name scenarios write: "ExpectedAuthorisation", "Money.negate"
    /** Codec-supported types. The defaults read them from the class's "implements Oracle<I, O>" declaration. */
    default Type inputType();  default Type outputType();
    /** PURE: no I/O, no agent, no clock, no randomness, no static state. Same input, same output, on any JVM. */
    O compute(I input);
}
```

`args` decoding: a map decodes as the input record; a list decodes positionally onto the record's components; when
the input type is itself a `List`, the list is the input; when it is neither a record nor a list, the list must have
exactly one element; a scalar decodes as the input itself. A null output is allowed (it encodes as JSON null). The
output is encoded with the codec and becomes `$oracle.<name>`. An exception from `compute` is `ENVIRONMENT` ("pack
defect: oracle …"), never a scenario failure.

(Phase 3, built 2026-10-03; P3 §5.1, §6.) A model never evaluates an oracle. `crossCheck` (Phase 3)
will compare an oracle's output with a dry run of the application's own code and raise `ORACLE_SUSPECT` on
disagreement; its place in the algorithm is §8.3 step 8. A cross-check is not a `Verifier<Q,T>` (an oracle has no
query); Phase 3 defines its own SPI type, and the reserved key's inner shape is deliberately left open here.

### 6.6 Fingerprinter, binder entry points, ledger, approval

```java
public interface EnvFingerprinter {
    /** Named components that identify the environment the bindings were made for. Keys: "<pack>.<name>".
     *  Values: short texts or "sha256:…" checksums; never secrets or personal data. Read-only and bounded. */
    Map<String,String> components(FingerprintContext ctx)
            throws DataException, AgentRpcException, AgentLostException, InterruptedException;
}
public interface FingerprintContext { AgentConnection agent(); DataChannel data(); String envId(); long remainingMs(); }

public enum BindMode { REPLAY, APPROVE, LIVE }   // as built 2026-10-02: label() ("replay", …) and parse(String),
                                                // which refuses re-resolve ("not part of this version")
// (Phase 5, built 2026-10-08; P5 §8.1) BindMode gains RE_RESOLVE, label "re-resolve"; parse
// accepts it, and run and the JUnit glue refuse it: re-resolve is a command, skillroy re-resolve (P5 §8.2)

public record BindOptions(BindMode mode, String envId, Path bindingsRoot, Credentials credentials,
                          Approver approver, boolean writeBindings, String approvedBy, CostLedger ledger,
                          Clock clock) {
    public static BindOptions defaults();    // REPLAY, "default", null, Credentials.empty(), Approver.REJECT_ALL,
                                             // false, null, new CostLedger(), system clock
    /** §12.2. Loads the credentials file once; a file that is refused is a CredentialsException. */
    public static BindOptions fromSystemProperties() throws CredentialsException;
    /** As built 2026-10-02: the same, with the bindings root to use when skillroy.bindings is not set (the glue
     *  passes the §9 default); fromSystemProperties() passes null. */
    public static BindOptions fromSystemProperties(Path defaultBindingsRoot) throws CredentialsException;
    /* with…() copies; as built also DEFAULT_ENV and the property names PROP_MODE, PROP_ENV, PROP_BINDINGS,
       PROP_APPROVE */
    // (Phase 5, built 2026-10-08; P5 §5.5) a last component InferenceSettings inference (default
    // InferenceSettings.none()), with withInference(…); fromSystemProperties(…) reads the properties of P5 §4.4
}

/** Asked once per bind in APPROVE mode, with every resolution and oracle of the scenario. */
public interface Approver {
    Approver APPROVE_ALL = …;  Approver REJECT_ALL = …;
    Decision review(ApprovalRequest request);
    record ApprovalRequest(String scenarioId, String envId, EnvFingerprint env, List<Resolution> resolutions,
                           List<OracleValue> oracles, BindingsDiff againstExisting /*null when no file exists*/) { }
    record Decision(boolean approved, String by, String reason) { }
}
public record OracleValue(String name, String fn, String inputsHash, Object value, Object mustEqual) { }

public final class Binder {                  // replaces the Phase 1 class; Binder.bind(Plan) and check stay public
static Bindings bind(Plan plan) throws ResolutionException;                  // fixtures only, as Phase 1 public
static void check(Plan plan, Bindings bindings) throws ResolutionException;  // as Phase 1 + captured slots /** The
full bind of §8. Never mutates the target. */ public static BindOutcome bind(Plan plan, DomainPack pack,
AgentConnection agent, BindOptions options, BindListener listener) throws BindException, AgentLostException,
InterruptedException; } public record BindOutcome(Bindings bindings, BindingsFile file /*what was read or written;
null if none*/, EnvFingerprint env, Path written /*null if nothing was written*/) { } // as built 2026-10-02: in live
without writing, file is the file that would be written (resolve --dry-run // prints it); written is also null when an
identical file was left untouched // (Phase 3, built 2026-10-03; P3 §5.3): BindOutcome gains a
fifth component, List<String> skipped (guarded // cross-checks that could not run); the Phase 2 constructor stays.
public final class BindException extends Exception {     // carries FailureKind, reason code, slot, detail (§10)
public FailureKind kind();  public String reason();  public String slot();  public Map<String,Object> detail(); } /**
run.jsonl records of the bind (§11.3). Types: fingerprint, resolver (one per Resolver.candidates call), *  verifier
(one per Verifier.verify call), resolve, oracle, guard, bind. */ public interface BindListener { void event(String
type, Map<String,Object> fields); BindListener NONE = (type, fields) -> { }; }                  // as built 2026-10-02

/** Resolves queries one at a time outside a plan: the hook for live and, later, interactive use (decision B15).
 *  The binder is built on it. Not thread-safe. */
public final class ResolutionEngine implements AutoCloseable {     // as built 2026-10-02: AutoCloseable
    public ResolutionEngine(DomainPack pack, AgentConnection agent, BindOptions options, Duration budget,
                            BindListener listener);
    public void close();                     // as built: closes the data channels it opened itself (JDBC connections)
    /** strategy: empty = not restricted. verify: empty = the pack's verifiers for the kind. The result is also
     *  kept under its slot name, with the credential join, for later calls and for bindings(). */
    public Resolution resolve(String slot, String kind, Map<String,Object> args, List<String> strategy,
                              List<String> verify, CredentialSpec credential)
            throws BindException, AgentLostException, InterruptedException;
    public Object evaluate(String fn, Object args) throws BindException;                // one oracle
    public EnvFingerprint fingerprint() throws BindException, AgentLostException, InterruptedException;
    /** Everything resolved so far, joined secrets and mask included; what PlanExecutor.run(plan, bindings) takes. */
    public Bindings bindings();
}

/** Every model call of a JVM-side run. Thread-safe. Phase 2 has no writer; the type and the assertion exist. */
public final class CostLedger {
    public record Entry(String scenarioId, String slot, Strategy strategy, String provider, String model,
                        String requestId, long inputTokens, long outputTokens, String costUsd) { }
    // (Phase 5, built 2026-10-08; P5 §7.3) Entry gains String purpose, long cacheReadTokens, long
    // cacheWriteTokens, boolean batch and String note; the nine-argument constructor stays, with purpose "resolve",
    // zeros, false and null. cost-ledger.jsonl writes the new fields after the old ones, and the ledger gains a
    // writer, LedgerWriter of …inference.budget
    public void record(Entry entry);
    public List<Entry> entries();  public boolean isEmpty();
    public int size();                       // as built 2026-10-02
}
```

`Bindings` (immutable, as built) gains: the strategy per slot (already there), `withCaptured(String slot, Object
value)`, `withCredential(String slot, Map<String,String> secretFields)` (the join of §8.4 for a caller that builds
bindings itself: adds the fields to the slot's value and their values to the mask), `secrets()` (a `SecretMask` of
every joined field), and `toJson(Plan)` in the shape of §11.3. `Bindings.substitute` is unchanged in what it
returns. As built (2026-10-02) it also has the accessors `strategy(slot)`, `credential(slot)`, `oracle(name)`,
`oracles()`, `resolutions()`, `captured()` and `envId()`, which the `$oracle` and `$env` lookups and the executor
use.

(Phase 5, built 2026-10-08; P5 §5.5) `ResolverContributor`, a `ServiceLoader` SPI of
`…scenario.resolve` found on the thread context class loader (as `WebPath` is, P4 §6.1), lets a controller-side module
offer resolvers to a bind without being a pack: `resolvers(pack, settings, ledger, clock)` returns them (ids start with
`inference.`), `capabilities(settings)` contributes capability ids (`inference.gateway`, `inference.sql`) to the matrix,
and `close()` ends the bind's use of it. It is consulted once per bind in `live`, `approve` and `re-resolve` and never
in `replay`, where `InferenceSettings.none()` is substituted. `InferenceSettings` holds strings, booleans and paths only
(gateway, recordings, models, effort, budgets, prices, fallbacks, images, timeouts, `logPrompts` and its file), so that
`skillroy-scenario` names no inference type; P5 §4.4 says how it is filled.

---

## 7. Data channels and stored-row checks (`skillroy-data`)

### 7.1 API

```java
package ai.skillroy.testjava.data;

public interface DataChannel {
    int MAX_ROWS = 200;                      // as built 2026-10-02
    /** True if this channel can serve the source id. */
    boolean serves(String source) throws AgentLostException;
    /** One guarded read-only statement. maxRows at most 200; timeout null = the source's default.
     *  params: each a String, Long, Boolean or null; anything else is IllegalArgumentException. */
    Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
            throws DataException, AgentLostException, InterruptedException;
    /** "agent" or "jdbc" for a concrete channel; the router answers for the source. */
    String kind(String source);
    /** 2026-10-03: the source's own statement timeout where the channel knows it (JDBC), else null. */
    default Duration defaultTimeout(String source) { return null; }
    /** 2026-10-03: serves(source) with a bound on the wait for the agent's source list. */
    default boolean serves(String source, Duration timeout) throws AgentLostException { return serves(source); }
}
public final class AgentDataChannel implements DataChannel { public AgentDataChannel(AgentConnection agent);
    public Set<String> servedSources() throws AgentLostException; }   // as built: the ids data.sources lists
public final class JdbcDataChannel implements DataChannel, AutoCloseable {
    public JdbcDataChannel(Credentials credentials, List<RowMask> masks);
}
/** JDBC for the sources credentials.yaml names, the agent for every other source. */
public final class DataChannels implements DataChannel, AutoCloseable {
    public static DataChannels of(AgentConnection agent, Credentials credentials, List<RowMask> masks);
}   // as built: agent may be null (only the JDBC sources are served then); JDBC wins when both serve a source
/** The controller-side counterpart of the agent's MaskPolicy; same signature and meaning. Thread-safe. */
@FunctionalInterface public interface RowMask { Object mask(String sourceId, String column, Object value); }

public final class DataException extends Exception {
    public enum Kind { GUARD, UNKNOWN_SOURCE, UNAVAILABLE, SQL, TIMEOUT, MASK, OTHER }
    public Kind kind();  public String source();  public String sqlState();  public int errorCode();
    public String exceptionClass();          // never the driver's message (S22)
    public String rpcCode();                 // the agent's error code for OTHER and for agent-side kinds, else null
    // as built 2026-10-02, public: DataException(Kind, String source, String message, String sqlState,
    //     int errorCode, String exceptionClass, String rpcCode) and DataException(Kind, String source, String message)
}
```

**Parameter forms.** A parameter is a `String`, a `Long`, a `Boolean` or null, on both channels. A decimal is
passed as its plain string, a date or time as ISO-8601 text, and the committed SQL casts where the column is not
of the parameter's kind (`deposit >= CAST(? AS DECIMAL(10,2))`). Reason: the wire codec parses any number with a
fraction as a `Double`, so a `BigDecimal` bound directly over JDBC and the same value sent through the agent would
be compared as different Java types (and `34.99` at a bound of `34.99` could differ), and a decimal written into a
bindings file as a JSON number would not read back as written. With these four forms both channels call
`setObject` with the same Java type, and the `params` recorded in provenance are identical whichever channel ran
the statement. `Double` is not accepted. `IllegalArgumentException` from a resolver's statement is a pack defect
(`ENVIRONMENT`).

**As built (2026-10-02), the other arguments.** A `maxRows` outside 0-200 is an `IllegalArgumentException` (it is
not capped), and so are a null or empty source, a null statement and a zero or negative timeout; both channels make
these checks before anything else. A timeout is capped at 120 s: the JDBC channel rounds it up to whole seconds (at
least 1) for `setQueryTimeout`, the agent channel passes it in milliseconds as the call's `timeoutMs`.

### 7.2 Behaviour

| | `AgentDataChannel` | `JdbcDataChannel` |
|---|---|---|
| Serves | the ids `data.sources` lists (asked once per channel instance; since 2026-10-03 with a timeout of 5 s, or the caller's shorter one). `METHOD_NOT_FOUND` from `data.sources` (no data module in the target) means "serves nothing", not an error | the ids under `jdbc:` in `credentials.yaml` |
| Guard | the agent's (`StatementGuard`, in the target) | the same `StatementGuard`, on the controller, before a connection is used |
| Execution | `data.query {source, sql, params, maxRows}` | one connection per source, opened at first use and kept for the life of the channel (closed by `close()`); before each statement `isValid` with a 2 s limit, and one reconnect if it is not. `ReadOnlyJdbc.execute`. No pool; calls are serialised per source. Since 2026-10-03, when the call carries a timeout: the per-source lock is taken with `tryLock(timeout)` and a longer wait is `TIMEOUT`; the connect waits at most `min(loginTimeout, timeout)`, a late connection still closed; `isValid` gets `min(2 s, the timeout rounded up to seconds)`, never 0 |
| Read-only | the provider's connection, set read-only by the agent | the account's role (the boundary), plus `setReadOnly(true)` |
| Timeout | the call's `timeoutMs` | `setQueryTimeout(statementTimeout)`, or the call's timeout when one is given; connecting is bounded by `loginTimeout` (see below) and, since 2026-10-03, by the call's timeout |
| Row cap | 200, `truncated` reported | 200, `truncated` reported |
| Values | `ValueConverter` in the agent | `ValueConverter` on the controller: identical forms |
| Masking | the agent's `MaskPolicy` list, in the target | the pack's `RowMask` list, on the controller |
| Errors | `INVALID_PARAMS` with `data.param = "sql"` -> `GUARD`; `NOT_FOUND` -> `UNKNOWN_SOURCE`; `CAPABILITY_UNAVAILABLE` -> `UNAVAILABLE`; `TIMEOUT` -> `TIMEOUT`; `INTERNAL_ERROR` with `data.sqlState` -> `SQL`; `INTERNAL_ERROR` with `data.maskPolicy` -> `MASK`; every other error (`INTERNAL_ERROR` without a state, `INVALID_PARAMS` on `maxRows` or `params[i]`, `BUSY`, `NOT_READY`, `METHOD_NOT_FOUND` on `data.query`) -> `OTHER` with `rpcCode` | guard rejection -> `GUARD`; no driver, or `Driver.connect` returns null -> `UNAVAILABLE`; `SQLTimeoutException` or an expired connect -> `TIMEOUT`; other `SQLException` -> `SQL` with state, code and class only; a `RowMask` that throws -> `MASK`, and the value appears nowhere |

**As built (2026-10-02).** `AgentDataChannel` does not run the statement guard itself: the agent's guard decides,
and its refusal maps to `GUARD`. It keeps the `data.sources` answer, a `METHOD_NOT_FOUND` included; after any other
error it keeps nothing, serves nothing for that question and asks again the next time. Every failure it reports
carries the agent's error code by name in `rpcCode` (for example `"INVALID_PARAMS"`), whatever the kind; a failure
of the JDBC channel carries none. `JdbcDataChannel` also counts an SQL state of class `HYT` as `TIMEOUT`, and after
`close()` its queries fail as `UNAVAILABLE`.

**Finding and using the driver.** `DriverManager` is not used: it returns only drivers visible to its caller's
class loader, so a driver that arrives in a `--pack` jar (a child class loader, as the CLI builds it) would never
be found, and its login timeout is JVM-wide. Instead:

- with `driver`: the class is loaded by name through the thread's context class loader and instantiated;
- without: `ServiceLoader<java.sql.Driver>` on the context class loader, and the first driver that `acceptsURL`
  (as built 2026-10-02: at most 100 providers are looked at);
- then `Driver.connect(url, properties)` is called directly, with `user`, `password` and the source's `properties`.
  A null result is `UNAVAILABLE`;
- the connect runs on a daemon thread and is awaited for at most `loginTimeout`; when the wait expires the error
  is `TIMEOUT`, and a connection that arrives later is closed.

How a driver jar reaches the CLI: from Phase 2 on `--pack <jar>` accepts any jar, with or without a `DomainPack` in
it. Every `--pack` jar is on the loader that the CLI sets as context class loader while scenarios run (that part
is as built). In a JUnit run the driver is
an ordinary test-scope dependency.

Routing (`DataChannels`): a source named under `jdbc:` goes to JDBC; any other to the agent. When neither serves
it the error is `UNKNOWN_SOURCE`, and the message lists the ids each channel serves. Before the first step the
executor checks that every source a plan's `db` elements name is served (`PRECONDITION_FAILED` otherwise). Because
routing is an environment fact, a scenario should not put a capability of the agent's data provider (the sample's
`lending.sql`) into `requires`: the served-source check covers it on either channel.

A statement run for a resolver is recorded in the slot's provenance with its channel. Parameters are the query's
own arguments; a parameter that is a secret (it cannot be, by §6.4 and §8.4, but the mask is applied anyway) is
written as `<redacted>`.

### 7.3 Masking on the controller (gap 27)

`DomainPack.rowMasks()` is applied by `JdbcDataChannel` to every value it returns, which closes the gap that rows
read over JDBC never pass an agent-side `MaskPolicy`. In addition the binder applies the same masks to every row
sample it puts into provenance, whichever channel the row came from: a pack whose agent module forgot a
`MaskPolicy` still cannot leak the column through a bindings file. A pack should declare the same rule on both
sides (the sample does, §13.2 and §13.3): the controller does not re-apply `rowMasks()` to the cells a `db` check reads
over the agent channel (clarified 2026-10-03 with Phase 3's aggregates, P3 §7); there the agent's `MaskPolicy` is the
mask.

### 7.4 Stored-row checks

```java
public enum ColumnKind { TEXT, INTEGER, DECIMAL, BOOLEAN, TIMESTAMP }

/** One column a scenario may name. name: what scenarios write. sql: the pack's committed SQL text for it
 *  (a physical name, quoted if the database needs it). paramSql: how a where-value is written, with exactly one
 *  "?" (for a decimal, typically "CAST(? AS DECIMAL(10,2))"). */
public record StoredColumn(String name, String sql, ColumnKind kind, String paramSql) {
    public static StoredColumn of(String name, ColumnKind kind);      // sql = name, paramSql = "?"
    public StoredColumn sql(String sql);  public StoredColumn param(String paramSql);
}
/** A table a scenario may check with db.<name>. tableSql: committed SQL text. orderBy: column names that
 *  determine the row order completely. */
public record StoredTable(String name, String source, String tableSql, List<StoredColumn> columns,
                          List<String> orderBy) {
    public static StoredTable of(String name, String source, String tableSql, List<StoredColumn> columns,
                                 List<String> orderBy);
}
```

Scenarios use the logical `name`s; the SQL holds the pack's `sql` texts. A variant pack with other physical names
keeps the scenarios and compiles its own plans (§5). `name`s match `[A-Za-z_][A-Za-z0-9_]*`; `sql`, `tableSql` and
`paramSql` are pack-committed text and are not restricted beyond what `StatementGuard` checks in the finished
statement (so an identifier that is one of the guard's forbidden words can be quoted).

Compilation of `db.<name>: {where, rows?, capability?, <column>: matcher…}`:

- `sql` = `SELECT <every column's sql, in the declared order> FROM <tableSql> WHERE <c1.sql> = <c1.paramSql> AND …
  ORDER BY <orderBy columns' sql>`. A `where` value of `null` gives `<c.sql> IS NULL` and no parameter. With an
  empty `where` the `WHERE` clause is omitted. All SQL text comes from the `StoredTable`; scenario text never
  becomes SQL text. `where` keys are written in the order the scenario gives them. The compiler runs the result
  through `StatementGuard`; a rejection is a compile error that names the pack's table.
- `params` = for each non-null `where` value in that order, `{kind, value}` with the column's kind and the scalar
  or reference.
- `rows`: a whole number `n` becomes `{equals: n}`; a matcher is normalised as P1 §8.4; the default is `{equals: 1}`.
- `expect`: each listed column with its matcher in normal form. The keys `where`, `rows` and `capability` are never
  columns.

Execution:

1. Substitute references in `params` and `expect`. Each parameter value is converted to the form of §7.1 by its
   kind: `TEXT` -> text; `INTEGER` -> `Long`; `BOOLEAN` -> `Boolean`; `DECIMAL` -> the plain decimal string;
   `TIMESTAMP` -> the SQL-standard literal form `yyyy-MM-dd HH:mm:ss[.fraction]` (changed 2026-10-03, see the
   as-built note). A reference that resolves to null, or a value that does not convert, is `ERROR` /
   `RESOLUTION_FAILED` reason `REFERENCE` (the statement was compiled for a value). As built (2026-10-02): `TEXT`
   takes the text of a scalar (a map or list does not convert); `INTEGER` a whole number that fits a `Long`; `BOOLEAN` a
   boolean or the text `true` or `false` (any case); `TIMESTAMP` an ISO-8601 local date-time (`T` or a space
   between date and time; seconds and fraction optional), converted to `yyyy-MM-dd HH:mm:ss[.fraction]` (the fraction
   only when it is not zero, without trailing zeros), which is bound as text and accepted with a plain `?` and with a
   `CAST(? AS TIMESTAMP)` on HSQLDB 2.7.4 over both channels; an offset, `Z`, a date alone, a time alone or other text
   is `RESOLUTION_FAILED` / `REFERENCE` with a message that names the form, because zone semantics differ between
   databases. (Reviewed and changed 2026-10-03: the build of 2026-10-02 passed any ISO text on as written, which no
   HSQLDB statement accepted, with or without a cast, SQL state 22007.) A pack gives a `TIMESTAMP` column a `paramSql`
   such as `CAST(? AS TIMESTAMP)` where its database wants one (the sample's `posted_at` has it). The table and its column positions
   come from the running pack's `StoredTable`; a table it does not declare is `ERROR` / `ENVIRONMENT`
   ("recompile").
2. Run the statement through the channel that serves `source` with `maxRows` 200.
3. **Columns are matched by position**: result column *i* is `columns[i]` of the `StoredTable`. The names the
   driver reports are ignored (§2.2). A result with another column count is `ERROR` / `ENVIRONMENT`.
4. The row count must satisfy `rows`; every returned row must satisfy every column matcher (matchers and numeric
   equality as P1 §8.4, so `"-34.99"` equals `-34.99`).

The check is retried like any expectation until it holds or the step timeout expires; a failure is `FAIL` /
`ASSERTION` whose detail holds the table, the `where` values, the row count and the first non-matching row
(masked). A `truncated` result fails the check ("more than 200 rows match; narrow the where"). A `DataException`
is `ERROR` / `ENVIRONMENT`. A check with `capability` is guarded as P1 §8.4 guards an expectation.

Not in Phase 2: aggregates, comparisons other than equality in `where`, joins. Because columns are matched by
position, a source that cannot name its columns can be checked; gap 12 remains open only for code that wants to
read such a source by name.

---

## 8. The binder

Binding is step 3 of the run sequence (P1 §8.5): after the `requires` and launch-property checks and the pack's
pre-flight, before the start mark and the first step. It happens once, at run start, in every mode. Nothing in
Phase 2 resolves while steps run. A consequence, and a known limit: resolvers and verifiers run in the state the
scenario starts in, before any sign-on (§6.4, "Before sign-on").

### 8.1 Inputs

`BindOptions` (§6.6): the mode; the environment id (`[a-z0-9-]+`, default `default`); the bindings root; the
credentials; the approver; the clock the budget is measured on.

A plan **needs bindings** when it has at least one slot of kind `query` or at least one
oracle. A plan that does not (every Phase 1 scenario) is bound as in Phase 1, plus the credential join of §8.4;
no bindings file is read or written, no fingerprint is computed, and the mode makes no difference.

### 8.2 Fingerprint v1

`EnvFingerprint.components` = the base components plus the pack's:

| Component | Value |
|---|---|
| `skillroy.fingerprint` | `1` |
| `skillroy.protocolMajor` | the agent protocol's major version, from `/ping` |
| `<pack>.<name>` … | what `DomainPack.fingerprinter()` returns (keys must start with the pack id and a dot) |

`hash` = `"sha256:"` + SHA-256 of `Json.writeCanonical(components)`. The components are kept beside the hash in
the bindings file, so that a mismatch can say *which* component changed. The fingerprint identifies the
environment (schema, application build, site, configuration version); it deliberately contains no transactional
data, not the plan and not the pack version (the pack is not a property of the environment, and a pack release
must not invalidate every bindings file: D7, D8). Data that changes under a stable identity is caught by the
verifiers, which re-run in every mode.

The fingerprint is computed **once per bind**, that is per scenario. There is no cache in Phase 2: a cache would
have to be keyed by the target's identity (a suite may relaunch the target between scenarios), and the sample's
fingerprint costs a dozen short reads. A cache keyed by the target's pid and start time can be added later without
a contract change.

A fingerprinter failure is `ENVIRONMENT`: a `DataException`, a JSON-RPC error, a key without the pack prefix, or
**any truncated statement** (a checksum over a cut result would be a wrong identity; the engine checks the
statements it recorded during `components`).

### 8.3 Algorithm

Common preparation, all modes:

1. Build the read-only agent view (one `schema()` call) and `DataChannels`. Read the pack's catalogue. A slot's
   query kind, verifier id or oracle function that the pack running now does not have is `ENVIRONMENT` ("the plan
   names verifier … which pack lending 0.3.0 does not have; recompile"). A pack version that merely differs from
   `plan.packVersion` is logged, as in Phase 1, and is not an error (Q13): what must match is the catalogue, so
   that a variant pack with its own version can run the base's plans (D24).
2. If the plan needs bindings, compute the fingerprint (§8.2).

Two definitions used below:

- **`argsHash`** of a query slot and **`inputsHash`** of an oracle = `"sha256:"` over `Json.writeCanonical` of the
  plan's `args` after reference substitution. No codec round trip is involved, so a null component the codec would
  add does not enter the hash.
- **Substitution during the graph walk uses values without joined secret fields.** The credential join (§8.4) is
  applied to the in-memory bindings only after step 9. A reference to a secret field inside `resolve` or `oracle`
  therefore does not resolve: `RESOLUTION_FAILED` reason `REFERENCE`. A secret can thus reach neither a resolver,
  nor an SQL parameter, nor a hash, nor an oracle value, nor the bindings file.

**`replay`**

3. Read `<bindingsRoot>/<envId>/<scenarioId>.bindings.json`. Missing root, directory or file: `REBIND_NEEDED`
   reason `NO_BINDINGS`. `REBIND_NEEDED` reason `UNREADABLE` when the file cannot be parsed; has a
   `bindingsVersion` this code does not know; names another scenario or environment id; lacks a query slot or
   oracle of the plan, or has one the plan does not; or when the hash of its `env.components` is not its
   `env.fingerprint` (the file was edited by hand).
4. `file.resolveHash` must equal `plan.resolveHash()`: else `REBIND_NEEDED` reason `PLAN_CHANGED`.
5. `file.env.fingerprint` must equal the computed hash: else `REBIND_NEEDED` reason `FINGERPRINT`, with every
   component whose value differs (name, recorded, actual). **No verifier runs after a fingerprint mismatch.**
6. Walk `resolutionGraph` in order. For a **fixture slot**: substitute references in its value from what is bound
   so far (as Phase 1). For a **query slot**:
   a. substitute references in `args`; compute `argsHash` and compare it with the file's: a difference is
      `REBIND_NEEDED` reason `ARGS_CHANGED` (an upstream value or the file was edited);
   b. decode the query (`Codec.decode`) and the recorded value to `T`; a value that does not decode is
      `REBIND_NEEDED` reason `UNREADABLE`;
   c. run every verifier of the slot, in plan order, on the recorded value. The first `passed = false` is
      `REBIND_NEEDED` reason `VERIFIER`, with the slot, the verifier id, its reason and its evidence. **A replay
      never calls a resolver** and never falls back to resolving the slot again;
   d. check that the credentials have the entry the recorded `credential` names. A missing entry is
      `ENVIRONMENT`: the binding is still right, the credentials file is incomplete. As built (2026-10-02), the
      binder first checks that the recorded `credential` has the slot's realm and, as its id, the text of the
      recorded value's key field; a mismatch, a missing `credential` on a slot that declares one, and a recorded
      `credential` on a slot that declares none are `REBIND_NEEDED` reason `UNREADABLE`.
   (Phase 5, built 2026-10-08; P5 §7.1, §5.5) In step 6 a recorded slot whose `strategy` is `LLM_SQL`
   and whose `approval.state` is not `APPROVED` is `REBIND_NEEDED` reason `NOT_APPROVED`, with the file and the slot in
   the detail; any other recorded slot is checked as items a to d say, with no model involved. `replay` loads no
   `ResolverContributor` and constructs no gateway, whatever the settings say.
7. For an **oracle** node: substitute references in `args`; compute `inputsHash` and compare it with the file's
   (a difference is `REBIND_NEEDED` reason `ARGS_CHANGED`, with `oracle` set); decode, `compute`, encode. Compare
   the output with the file's recorded value (JSON equality after encoding): a difference is `REBIND_NEEDED`
   reason `ORACLE_CHANGED` (the pack's oracle code changed, or the file was edited).
8. *(Phase 3: evaluate the oracle's `crossCheck`; a disagreement is `ORACLE_SUSPECT` in every mode. Not built.)*
   (Phase 3, built 2026-10-03; P3 §6.1: the check runs in every mode after the oracle is computed
   and before its guard; a guarded check whose capability is not `OK` is skipped and makes `DEGRADED` the best
   outcome, P3 §6.2.)
9. Guard: if the oracle has `mustEqual`, compare (matcher equality of P1 §8.4, references in `mustEqual`
   substituted). A failed guard in `replay` is `REBIND_NEEDED` reason `GUARD`.
10. Apply the credential join (§8.4) to every slot that declares one, then `Binder.check`: every reference in
    steps, expectations, `db` elements and cleanup calls resolves (`RESOLUTION_FAILED` reason `REFERENCE`, as
    Phase 1; a secret field referenced outside call arguments is the same failure). References to captured slots
    are checked against the slot's declared fields.
11. Nothing is written to the bindings directory. The cost ledger is checked after the run (§11.2).

**`live`**

3. No bindings file is read.
4. Walk `resolutionGraph`. Fixture slots as above. For a **query slot**:
   a. substitute references in `args`, compute `argsHash`, decode the query. Arguments that do not decode are
      `RESOLUTION_FAILED` reason `ARGS` (an upstream value has a shape the query does not accept);
   b. take the resolvers the running pack has for the kind, in catalogue order, restricted to the slot's
      `strategy` when the plan has one. None left: `ENVIRONMENT` ("pack … has no sql resolver for kind …");
   c. for each resolver in that order: call `candidates`. Drop every candidate for which the slot has a
      `credential` and the credentials have no entry (recorded as rejected, "no credential staff/<id>"; at most
      20 such records are kept). Hand the remaining candidates, at most `maxCandidates` (20; more sets
      `candidatesTruncated`), to the verifiers in order: every verifier of the slot runs on a candidate, and the
      first failure rejects it with the verifier's reason. **The first candidate that every verifier passes is
      the resolution**; the engine does not look for a "better" one. **If no candidate of this resolver passes,
      the next resolver is asked** (the tiers are a ladder gated on the verifier, as the design overview says);
   d. no resolver returned any candidate: `RESOLUTION_FAILED` reason `NO_CANDIDATE`. Candidates, none accepted:
      `RESOLUTION_FAILED` reason `ALL_REJECTED`, with up to 20 `{resolver, label, reason}`. A resolver's own
      `ResolutionException` ends the slot at once with its reason (`TRUNCATED`, a reason the resolver chose, or
      `RESOLVER`).
   (Phase 5, built 2026-10-08; P5 §5.5) In 4b the ladder is the pack's resolvers first and then the
   contributed ones (§6.4, §6.6), in `ServiceLoader` order, both restricted by the slot's `strategy` list. A slot whose
   list names `llm-sql` while no contributor offers it is `ENVIRONMENT` ("no resolver for strategy llm-sql: the gateway
   is none / skillroy-inference is not on the class path / the pack declares no allow-list").
5. Oracle nodes: compute (step 7 without the comparisons), then step 8 (reserved), then the guard. A failed guard
   in `live` or `approve` is `RESOLUTION_FAILED` reason `GUARD`: with the slots fixed at their first verified
   candidates this environment does not give a binding under which the scenario tests what it claims, so it is
   not run.
6. The credential join and `Binder.check` (step 10).
7. Nothing is written, unless `writeBindings` is set: then the file is written as in `approve` step 8 with
   `approval.state = NOT_REQUIRED`. (Tier (a) resolutions are accepted on the verifier alone; approval becomes
   mandatory for tiers (b) and (c) in Phase 5.)
   (Phase 5, built 2026-10-08; P5 §7.1) A tier (b) resolution in `live` is used by the run with
   `approval` `REQUIRED`, and with `writeBindings` the file is written with `approval.state = REQUIRED`, so that a later
   `replay` refuses it until a person approves.

**`approve`**

3.-6. As `live`.
7. Build the `ApprovalRequest`: every resolution (value, provenance with statements, calls and masked row
   samples, verifier evidence), every oracle value with its guard, the fingerprint, and the difference against an
   existing bindings file if there is one. Call the `Approver` once; the time it takes is outside the bind
   budget. Not approved: `RESOLUTION_FAILED` reason `NOT_APPROVED`; nothing is written.
   (Phase 5, built 2026-10-08; P5 §7.1) Before a tier (b) slot calls a model in `approve`, the binder
   reads the existing bindings file (`--reuse-bindings`, on by default, D82). When its `resolveHash` equals the plan's,
   the slot's `argsHash` equals the one just computed and the recorded value passes every verifier now, the recorded
   value is offered as the candidate, with no model call, and is written again with the new approval if approved; a
   model is called only for a slot that fails that check. Tier (a) slots are not reused, `--no-reuse-bindings` resolves
   every slot afresh, and the reuse is noted in the slot's provenance notes.
8. Write `<bindingsRoot>/<envId>/<scenarioId>.bindings.json` (§9): the `<envId>` directory is created if needed;
   the text goes to a temporary file in the same directory, then an atomic move. If the content is byte-identical
   to the existing file, the file is left untouched. **The file must hold no credential value**: if
   `mask.apply(text)` differs from `text`, nothing is written and the bind fails as `ENVIRONMENT` ("a recorded
   value equals a credential"). The file itself is never passed through the mask, because a masked value would
   later be read back as the value. An I/O failure is `ENVIRONMENT`. A missing `bindingsRoot` in `approve`, or in
   `live` with `writeBindings`, for a plan that needs bindings is refused before any pack call as `ENVIRONMENT`
   ("approve needs a bindings root …; nothing was resolved"), by `Binder.bind` and by `PlanExecutor` before the
   pre-flight (built 2026-10-03; the CLI refuses it as a usage error earlier still).
9. The run continues with these bindings (the `resolve` command stops here instead, §12.1).

**`re-resolve` (Phase 5, built 2026-10-08; P5 §8.1, §8.2).** `BindMode.RE_RESOLVE` binds as `live`
(steps 3-6) with one difference: a tier (b) call is not made inline. A resolver that is a `BatchableResolver` (§6.4)
parks the scenario at that slot, the job submits the parked requests of a round as one batch, and each scenario resumes
with its answer; the verifiers, oracles, cross-checks and guards then run as in `live`, against the live target, in the
round in which the scenario resumed. The file is written as in `approve` step 8, with
`approval: {state: APPROVED, by: "re-resolve:<jobId>"}` for every slot the job resolved, and an identical file is left
untouched. The mode belongs to the command `skillroy re-resolve` (§12.1); `run` and the JUnit glue refuse it.

**No backtracking across slots.** The engine fixes each slot in graph order and never revisits an earlier one
because a later slot or a guard failed. A scenario that needs a joint choice expresses it in its queries (the
sample's assistant is chosen *given* the item: `limit: {lt: $item.deposit}`), or as one query whose value holds
both. Reason: backtracking makes the chosen binding depend on search order in ways a reviewer cannot predict from
the YAML (decision D5).

What this costs, stated plainly: the engine can report `RESOLUTION_FAILED` in an environment where *another*
choice for an earlier slot would have worked (the cheapest item may exclude every assistant while a dearer one
would not). So `NO_CANDIDATE`, `ALL_REJECTED` and `GUARD` mean "no answer **with the earlier slots fixed at their
first verified candidates**", and the failure detail lists those slots (`fixedUpstream`, §10) so that the reader
sees what the choice depended on. Authoring rule: write the most constrained slot first, and let the less
constrained ones depend on it.

### 8.4 The credential join

For a slot with `credential: {realm, key}` and a bound value `v`: `id` = the text of `v.<key>` (the value must be
a map with that field: otherwise `ENVIRONMENT`, a scenario or pack defect). The entry's secret fields are added to
the slot's value **in the in-memory `Bindings` only**, after the graph walk and the guards (step 10), and their
values are added to the run's `SecretMask`. A secret field whose name the value already has is `ENVIRONMENT`. The
`Resolution`, the bindings file and every output carry `credential: {realm, id}` and never the fields.

A joined field resolves in the `args` of step calls and cleanup calls and nowhere else. `Binder.check` refuses a
reference to one in an expectation, a `db` element, or a matcher (`RESOLUTION_FAILED` / `REFERENCE`): those are
written to evidence and sent as SQL parameters.

### 8.5 Ordering and purity summary

| Call | When | May touch the target | May throw for "no" |
|---|---|---|---|
| `EnvFingerprinter.components` | once, before any slot | read-only primitives, `DataChannel` | no: a failure is `ENVIRONMENT` |
| `Resolver.candidates` | `live`/`approve` only, per query slot, graph order, catalogue order | read-only primitives, `DataChannel` | returns an empty list |
| `Verifier.verify` | every mode, per query slot, after the value is known | read-only primitives, `DataChannel` | returns `passed = false` |
| `Oracle.compute` | every mode, graph order | nothing | no: an exception is `ENVIRONMENT` |
| `Approver.review` | `approve`, once, after all slots and oracles | nothing | returns `approved = false` |
| `CrossCheck.check` (Phase 3, built 2026-10-03; P3 §6.3) | every mode, per oracle with a `crossCheck`, after `Oracle.compute`, before the guard | read-only primitives, `DataChannel` | returns `agreed = false` |

An `AgentLostException` anywhere in the bind is `AGENT_LOST` / `TARGET_RESTARTED` as in Phase 1. A JSON-RPC error
or a `DataException` from a resolver, verifier or fingerprinter call that the pack does not handle is
`ENVIRONMENT`, with the primitive or the source and the error in the detail.

---

## 9. The bindings file

**Location.** `<bindingsRoot>/<envId>/<scenarioId>.bindings.json`. There is **one bindings root per invocation**:
`--bindings <dir>` or the system property `skillroy.bindings`; without either, the directory `bindings` in the
*parent* of the directory that holds the first scenario or plan argument. For the sample that is the module's
`bindings/`, whether the argument is `scenarios/x.yaml` or `plan/x.plan.json`. The default need not exist: in
`replay` a missing root is `NO_BINDINGS`, in `approve` it is created. A source without a directory (a classpath
source) has no default; the root must then be given when a plan needs bindings. The file is meant to be committed
and reviewed as a diff, next to the scenario.

**Format** (`Json.writePretty`; keys in the order shown; `resolutions` and `oracles` in `resolutionGraph` order;
ends with a newline; no timestamps, no host names, no paths, no secrets):

```json
{ "bindingsVersion": 1,
  "scenarioId": "return-over-limit-needs-supervisor",
  "envId": "seed-a",
  "pack": "lending", "packVersion": "0.2.0",
  "planHash": "sha256:…",
  "resolveHash": "sha256:…",
  "env": { "fingerprint": "sha256:…",
           "components": { "lending.schema": "sha256:…", "lending.seed": "A",
                           "lending.site": "TOOL LIBRARY - MILL LANE", "skillroy.fingerprint": "1",
                           "skillroy.protocolMajor": "0" } },
  "resolutions": {
    "systemMax": {
      "query": "SystemLimit", "args": {"type": "RETURN"}, "argsHash": "sha256:…",
      "value": "500.00",
      "strategy": "CANONICAL_SQL",
      "provenance": { "resolver": "lending.sql.SystemLimit",
                      "statements": [ {"channel": "agent", "source": "lending",
                                       "sql": "SELECT value FROM desk_setting WHERE name = ? ORDER BY name",
                                       "params": ["max_return"], "rowCount": 1, "truncated": false} ],
                      "calls": [], "candidates": 1, "candidatesTruncated": false, "rejected": [] },
      "verifier": [ {"id": "lending.limits", "passed": true, "reason": "the desk reports maxReturn 500.00"} ],
      "confidence": 1.0,
      "approval": {"state": "APPROVED", "by": "approve"},
      "llm": null },
    "assistant": {
      "query": "StaffWithPermission",
      "args": {"interactiveSignOn": true, "limit": {"lt": "34.99", "type": "RETURN"}, "perform": ["SIGN_ON", "RETURN"]},
      "argsHash": "sha256:…",
      "value": {"code": "100", "limits": {"RETURN": "20.00"}, "permissions": ["RETURN", "SIGN_ON"]},
      "credential": {"realm": "staff", "id": "100"},
      "strategy": "CANONICAL_SQL",
      "provenance": { "resolver": "lending.sql.StaffWithPermission", "statements": ["…"],
                      "calls": [], "candidates": 1, "candidatesTruncated": false, "rejected": [] },
      "verifier": [ {"id": "lending.access.check", "passed": true, "reason":
          "SIGN_ON and RETURN permitted; RETURN of 34.99 refused; limit 20.00; asked for a password"} ],
      "confidence": 1.0, "approval": {"state": "APPROVED", "by": "approve"}, "llm": null } },
  "oracles": {
    "auth": {"fn": "ExpectedAuthorisation", "inputsHash": "sha256:…", "value": "SUPERVISOR_PROMPT",
             "mustEqual": "SUPERVISOR_PROMPT"},
    "returnLine": {"fn": "Money.negate", "inputsHash": "sha256:…", "value": "-34.99"} } }
```

Rules:

- **Only query slots and oracles are recorded.** Fixture slots are in the plan; captured slots belong to a run. (Phase
  3, built 2026-10-03; P3 §8.3) An oracle entry gains the informational key `crossCheck` when the plan has one;
  `bindingsVersion` stays 1.
- **Record to file.** The file is the JSON of `BindingsFile` with these names: `Resolution.provenance.resolverId`
  -> `provenance.resolver`; `Resolution.verified` -> `verifier`, each `VerifierResult.verifierId` -> `id`;
  `Rejected.resolverId` -> `resolver`; `Resolution.usage` -> `llm`; `Resolution.slot` is the map key. Every other
  field keeps its record name. Values are written as the codec encodes them (records and maps with sorted keys,
  decimals as strings), and statement `params` in the forms of §7.1, so the text reads back to equal objects. Call
  `params` of the provenance, verifier `evidence` and row-sample cells are frozen with their keys sorted at every
  depth (since 2026-10-03: a pack's `Map.of` iterates in a per-JVM order, which made two approvals of one environment
  differ as soon as a resolver recorded a call).
- **The provenance reflects the credentials that existed at approval**: candidates dropped for want of an entry
  are in `rejected`, and a resolver that skips them early runs fewer statements. Two approvals of one environment
  agree byte for byte when their credentials files name the same ids. Recorded on purpose (Q15): leaving the drops
  out would hide why a candidate was skipped.
- **What is deliberately left out of the file**: row samples and verifier `evidence` (they can hold personal data
  and change without the binding changing; they are shown at approval and written to the run's own outputs,
  masked); the time of approval (the commit has it; a timestamp would make every re-resolution a diff);
  `provenance.notes`.
- (Phase 5, built 2026-10-08; P5 §5.4, §7.3) For a tier (b) slot `llm` is filled, from the ledger
  entries of the slot's model call (model, request id, tokens, cost), where tier (a) slots keep `null`; and its
  `provenance.rationale` is written, the model's rationale, as the provenance's last key and only when there is one, so
  that a tier (a) file does not change (D93 of P5 §16; the refused proposals are `provenance.rejected` entries).
  `provenance.notes` stay out of the file like the rest of the list above. (Corrected in the Phase 5 fix wave of
  2026-10-08: this mark first said the notes were written.)
- `approval.by` is a free-text label, never a person's name by default (Q6): `--approved-by <text>`, else the
  `Approver`'s `Decision.by`, else the mode name. As built (2026-10-02): `live --write-bindings` writes
  `--approved-by`, else `live`; the CLI's approvers (`--yes` and the terminal) and `Approver.APPROVE_ALL` give no
  `by`, so a file they approve says `approve`, as the sample's committed files do. `approval.state` is `APPROVED`
  (written by `approve`) or
  `NOT_REQUIRED` (written by `live` with `--write-bindings`, tier (a) only). **`replay` accepts both states** (Q2):
  a tier (a) binding is accepted on the verifier alone, so a file written by `live --write-bindings` replays like
  an approved one. Phase 2 has no switch that insists on `APPROVED`; one can be added for CI if wanted.
- (Phase 5, built 2026-10-08; P5 §7.1) `approval.state` may also be `REQUIRED`: a tier (b) resolution
  that `live --write-bindings` wrote and no person has approved. `replay` refuses an `LLM_SQL` slot whose state is not
  `APPROVED` (`REBIND_NEEDED` reason `NOT_APPROVED`, §8.3), so the acceptance of both states above holds for tier (a)
  slots. A file written by `re-resolve` says `APPROVED` with `by: "re-resolve:<jobId>"`.
- `pack`, `packVersion` and `planHash` are informational (they tell a reviewer which pack and plan the file was
  made with); `resolveHash` is what `replay` compares.
- `value` holds semantic ids and facts of the environment, never a secret field. The base does not inspect what a
  pack puts into a value type beyond the mask. The sample's staff value has a code, not a name, so that no personal
  data reaches a committed bindings file; that is the recommended practice for a pack whose test accounts are
  people, and a scenario that needs a name on a slip can read it through a fact lookup at run time. A pack for an
  environment whose test accounts are not real people may carry their names in its value types (Q10 as the owner
  decided it on 2026-10-06, B19: the base stays restrictive in its sample and its advice, and a private overlay
  decides for its own environments).
- A file is valid for exactly one environment id and one scenario id; `replay` checks both against its own
  (`REBIND_NEEDED` reason `UNREADABLE` when they differ, since the file was copied to the wrong place).
- The file is never masked (§8.3 `approve` step 8): it is built only from data that never held a secret, and the
  writer proves that before writing.

```java
public record BindingsFile(int bindingsVersion, String scenarioId, String envId, String pack, String packVersion,
                           String planHash, String resolveHash, EnvFingerprint env,
                           Map<String,Resolution> resolutions, Map<String,OracleValue> oracles) {
    public static final int VERSION = 1;  public static final String SUFFIX = ".bindings.json";  // as built 2026-10-02
    public static BindingsFile read(Path file) throws IOException, BindingsFormatException;
    public static BindingsFile parse(String text) throws BindingsFormatException;             // as built
    public String toJsonText();                               // deterministic, as shown above
    public void write(Path file) throws IOException;          // temporary file + atomic move
    public static Path locate(Path bindingsRoot, String envId, String scenarioId);
    public BindingsDiff diff(BindingsFile other);
}
/** What differs between two bindings files: per slot (value, args, strategy, resolver, statements, calls,
 *  verifier outcome, credential), per oracle (fn, value), fingerprint components, hashes, pack version. */
public record BindingsDiff(List<Change> changes) {
    public record Change(String where /* "resolutions.assistant.value", "env.components.lending.seed" */,
                         Object before, Object after) { }
    public boolean isEmpty();
    public Set<String> slots();    // as built 2026-10-02: the slots and "oracle.<name>" entries that differ, in order
}
```

---

## 10. Failure taxonomy and status mapping

No value is added to `FailureKind`. Phase 2 starts raising `REBIND_NEEDED` and extends `RESOLUTION_FAILED`;
`ORACLE_SUSPECT` stays unraised until Phase 3. A model call in `replay` is `ENVIRONMENT`, not a kind of its own
(Q9): no taxonomy change for a condition Phase 2 cannot produce; a dedicated kind can be weighed when Phase 5 exists.

| Kind | Status | Raised by | When (reason code in `detail.reason`) |
|---|---|---|---|
| `REBIND_NEEDED` | `ERROR` | binder, `replay` only | `NO_BINDINGS`, `UNREADABLE`, `PLAN_CHANGED`, `FINGERPRINT`, `ARGS_CHANGED`, `VERIFIER`, `ORACLE_CHANGED`, `GUARD` |
| `RESOLUTION_FAILED` | `ERROR` | binder, `live` and `approve`; every mode for `REFERENCE`; executor for a `db` parameter | `NO_CANDIDATE`, `ALL_REJECTED`, `TRUNCATED`, `ARGS`, `GUARD`, `NOT_APPROVED`, `REFERENCE` (the Phase 1 case, a secret field referenced where it may not be, and a `db` parameter that is null or does not convert), `RESOLVER` or a reason the resolver chose |
| `ORACLE_SUSPECT` | `ERROR` | binder, every mode (Phase 3) | an oracle and its cross-check disagree (Phase 3, built 2026-10-03: reason `DISAGREED`, detail in P3 §6.1, §8.1) |
| `PRECONDITION_FAILED` | `ERROR` | executor | as Phase 1, plus: a `db` element's source is served by no channel |
| `ENVIRONMENT` | `ERROR` | binder and executor | credentials lacking an entry that a fixture slot or a recorded binding needs; a secret field colliding with a value field; the fingerprint could not be computed (including a truncated statement); the bind budget was spent; a pack defect (a mutating call from a resolver or verifier; a null list, null candidate or null value; a `RuntimeException` from a resolver, verifier or oracle; a parameter of a form §7.1 does not allow; a catalogue that does not match the plan); a `DataException` or an unhandled JSON-RPC error from a resolver, verifier or fingerprinter; a bindings file that could not be written, or that would hold a credential value; a `DataException` or a wrong column count in a `db` check; **a non-empty cost ledger after a `replay` run** |
| `ASSERTION` | `FAIL` | executor | as Phase 1, plus: a `db` check that does not hold; an awaited event without a captured payload value |

(Phase 5, built 2026-10-08; P5 §7.1, §7.2, §8.2) `REBIND_NEEDED` gains the reason `NOT_APPROVED`
(`replay` found a tier (b) slot whose approval is not `APPROVED`; the file and the slot are in the detail), and
`RESOLUTION_FAILED` gains `BUDGET` (a budget meter refused a model call; `{scope, id, limitUsd, spentUsd, estimateUsd}`
is in the detail). The nightly job's report lists a scenario whose batch did not end within `timeouts.batchWaitMs` as
`BATCH_TIMEOUT`.

A credentials file that is missing where one was named, unreadable, malformed or too open is not a per-scenario
failure: it is found before anything runs (§12).

The line between the two binder kinds: `REBIND_NEEDED` means "a recorded answer no longer fits; a person must look
and re-approve"; `RESOLUTION_FAILED` means "asked now, with the earlier slots fixed at their first verified
candidates, this environment has no answer" (§8.3, "No backtracking"). Neither is a verdict on the application,
so both are `ERROR`, not `FAIL`, and no step has run when either is reported.

`RunResult.detail` for both: `reason`, `slot` (or `oracle`), `mode`, `envId`, and by reason: `bindingsFile` (a path
relative to the bindings root), `expected`/`actual` hashes, `components` (the changed ones), `verifier`
(`{id, reason, evidence}`), `rejected` (`[{resolver, label, reason}]`), `guard` (`{mustEqual, actual}`),
`fixedUpstream` (`[{slot, label}]`: the already-bound query slots the failing slot or oracle references, for
`NO_CANDIDATE`, `ALL_REJECTED` and `GUARD`), `message` (a resolver's own text). All masked.

**Nothing is sent to the target for any failure before the start mark** (Q11): a failed `requires` or launch-property
check, a failed pre-flight, a failed bind, and a failed served-source check. No failure evidence is collected from
the UI, **no cleanup call is sent and `recover` is not called**: the start mark was never written, so the scenario
did nothing that could need undoing, and a bind is read-only. This needed a change to P1 §8.5, which in Phase 1 sent
cleanup calls "whatever the outcome and including failures before any step" (`runCleanup` even re-bound the fixtures
to do so); the rule there is now "cleanup calls and `recover` run only if the start mark was written" (§16.1). As
built (2026-10-02), `run.jsonl` of such a run has neither a `cleanup` nor a `recover` record. The argument that
`recover` after a failed pre-flight might un-stick a desk left dirty by an earlier scenario was weighed and set
aside: an earlier scenario that failed after its start mark has already had its own `recover`, and a pre-flight that
fails on a dirty desk should say so, not be papered over. The `resolve` command likewise never calls `recover`.

JUnit XML: both kinds are `<error type="REBIND_NEEDED">` / `<error type="RESOLUTION_FAILED">` on the scenario's
testcase; the message is the one-line reason, the body the masked detail. The `environment` testcase fails when
the credentials cannot be loaded, which is checked once, before the agent is contacted; no scenario runs then.
Fingerprint failures are reported **per scenario**, because the fingerprint is computed in each scenario's bind:
one that cannot be computed is `ENVIRONMENT`, a mismatch is `REBIND_NEEDED` (bindings files are per scenario and
some may be stale while others are not). This narrows the design overview's sentence that fingerprint failures
belong to the `environment` testcase.

---

## 11. Executor changes and run outputs

### 11.1 API

```java
public record RunOptions(/* … Phase 1 and 1.x components … */ BindOptions bind) {   // never null
    public RunOptions withBind(BindOptions bind);
}
public record RunResult(/* … Phase 1 and 1.x components … */ BindMode mode, String envId,
                        List<CostLedger.Entry> modelCalls) { }
```

`RunOptions.defaults()` carries `BindOptions.defaults()`; a null `bind` is refused by the record's constructor. The
executor never reads system properties; only the JUnit glue's overloads without `RunOptions` do (§12.2). The
executor passes `RunOptions.clock` on as the bind clock.

`PlanExecutor.run(plan)` binds with `options.bind()` through `Binder.bind` (§8). `PlanExecutor.run(plan, bindings)`
with caller-supplied bindings skips the binder as in Phase 1 (tests, and the hook for a caller that resolved through
`ResolutionEngine`, whose `bindings()` already carries the join and the mask). A caller that builds `Bindings` by
hand joins secrets with `Bindings.withCredential` (§6.6), which is also what puts them into the run's mask.

As built (2026-10-02), for the CLI's `resolve` (§12.1): `public PlanExecutor.Resolved resolve(Plan plan)` runs the
sequence of §11.2 up to and including the bind and, since the review of 2026-10-03, the served-source check, then
stops: no start mark, no step, no cleanup call, no `recover`. `public record Resolved(RunResult result, BindOutcome
outcome, List<CrossCheckOutcome> crossChecks)`: the result (`PASS` when bound, with no steps), the bind outcome (null
when the bind failed or did not run) and, from Phase 3 (P3 §9), every cross-check record the bind emitted, masked as
`run.jsonl` holds them, so that the CLI's `--json` can carry them for a failed bind too (the two-argument constructor
stays).

Consequence for existing callers: a scenario whose slot declares a `credential` and is run with
`BindOptions.defaults()` (empty credentials) ends `ERROR` / `ENVIRONMENT`. Every test that runs such a scenario
passes credentials (§13.4 lists them).

### 11.2 Run sequence (changes to P1 §8.5 in bold)

1. `requires` and `launchProperties`;
2. pre-flight;
3. **bind (§8); check that every `db` source is served;**
4. the start mark;
5. steps: **before a call is sent, the executor names its secret arguments in `_meta.secretParams` (A34, §11.4)**;
   **after a call's sync, if the call has `capture`, bind the captured slot from the event that ended the
   wait** (the event `sync.await` returned, or the event that satisfied an `EVENT` sync); **`db` elements of an
   `expect` step are evaluated with the step's expectations;**
6. `verify`, **including its `db` elements, in file order;**
7. **the ledger check: in `replay`, if `options.bind().ledger()` gained an entry during this run, the result
   becomes `ERROR` / `ENVIRONMENT` ("replay made N model calls"), whatever the steps did.** In Phase 2 nothing
   writes to the ledger, so the check can only fire in a test that injects a writer; it exists so that Phase 5
   cannot add a model call to `replay` without a red build;
8. evidence, cleanup, recover: as Phase 1, **except that cleanup and `recover` run only if the start mark of
   step 4 was written: a failure in steps 1-3 sends nothing to the target** (Q11; §10, §16.1). Cleanup calls carry
   `_meta.secretParams` like step calls.

The "no sleeps" rule is unchanged: the binder waits on nothing but call replies; `db` checks are retried through
the same poll as expectations and must hold at the first attempt in the committed scenarios (§14.2 item 4). The
bounded wait around a JDBC connect (§7.2) is a wait on a future, not a sleep.

### 11.3 What a run writes

In `RunOptions.outputDir` (one directory per scenario, as Phase 1):

| File | Content |
|---|---|
| `run.jsonl` | the Phase 1 record types, plus: `fingerprint` (hash, components, and in `replay` the recorded hash), `resolver` (one per `Resolver.candidates` call: slot, resolver id, candidate count; **never present in `replay`**), `verifier` (one per `Verifier.verify` call: slot, id, passed, reason), `resolve` (one per query slot: slot, query, masked args, mode, strategy, resolver, statements with masked row samples, calls, candidates tried, rejections, verifier results with evidence, credential ref), `oracle` (name, fn, value, recorded value in `replay`), `guard` (oracle, mustEqual, actual, ok), `bind` (as Phase 1, plus mode, envId, the bindings file used or written, or the failure reason), `capture` (step, slot, value), `db` (per attempt: table, source, channel (`agent` or `jdbc`), params, row count, ok; as built: `step`, `table`, `source`, `channel`, `params`, `rowCount`, `ok`; the record of a failed statement carries `error` with `source` and `channel`; a check skipped under its capability writes `table` and `source`; completed 2026-10-03, the build of 2026-10-02 having no `source`), `ledger` (the count at run end). The Phase 1 `call` record also lists the names the call carried in `_meta.secretParams` (`secretParams`, omitted when empty; A34). Every record passes the mask on its JSON form (maps and lists copied, any other object normalised first; 2026-10-03), and a record that cannot be written becomes `{type, t, unwritable: <masked reason>, fields: [names]}` |
| `bindings.json` | written when the bind ends and **rewritten at run end** (so that `captured` is complete). This run's bindings, for evidence: `{scenarioId, planHash, resolveHash, mode, envId, env, resolutions: {slot: {strategy, value, …}}, oracles, captured: {slot: value}}`. A slot taken from a bindings file has `strategy: REPLAY` and `recordedStrategy`. Fixture slots are included with `strategy: FIXTURE` (as Phase 1). Masked |
| `cost-ledger.jsonl` | one line per `CostLedger.Entry` of this run; always written; empty in Phase 2 |
| `failure/` | as Phase 1 (only for failures during steps or verify) |

In the bindings directory: the bindings file, only in `approve` and in `live` with `writeBindings` (§8.3).

(Phase 3, built 2026-10-03; P3 §8.2) `run.jsonl` gains a `crosscheck` record, the `bind` record a
`skipped` list, and the `db` record `aggregates`; the run's `bindings.json` records each oracle's cross-check outcome.

(Phase 4, built 2026-10-07; P4 §8.2: `run.jsonl` gains `web.path` (once per run with web calls: path, reason, CDP
attach, library hash, the scenario's policy), `web.fallback` (per fall-back to the agent path, naming the path that
refused the call), `web.recover` (per frozen-target recovery), `web.trace` (what became of the trace), `web.close` (a
path that failed to close), `fact` (a `text` the `web` root left out), `path` on every `call` record of a `web.*`
primitive, and `pathSince` and `source` on the `sync` record of a wait that read a path's events.)

### 11.4 What is never written

A value from `credentials.yaml` (a credential field, a JDBC password, a driver property, URL user-info) is never
**written as a value** to: `plan.json`; a bindings file; `run.jsonl`; `bindings.json`; `cost-ledger.jsonl`;
`failure/*`; `RunResult` (message, detail, step results); JUnit XML; anything the CLI prints; an exception message.
"As a value" is exact: no JSON string value, XML attribute value or text node, and no whitespace-delimited token of
printed text equals a secret. In addition, a secret of `masking.minLengthInText` or more characters (default 8, §4)
does not occur inside any longer text. A shorter secret inside a longer text is masked only where it stands in
double quotes: the guarantee for short secrets rests on the mechanisms below, which keep them out of those texts in
the first place, not on the mask; an environment may lower the threshold (§4) and accept false redactions.
Mechanism:

- compile time: fixtures cannot hold secrets (§3.1 rule 5), so compiled plans cannot; the version 2 plan reader
  enforces the same rule on what it reads;
- the credential join puts secret fields into the in-memory bindings only, after resolution (§8.4), and they
  resolve only in call arguments; resolvers, verifiers, oracles, hashes and SQL parameters never see one;
- every record, file and message listed above, **except the bindings file**, passes `SecretMask.apply` (the run's
  mask = the credentials' mask plus the secret values A25 collects from fixtures of version 1 plans) at the point
  where it is written or returned. The key rule of A25 is applied separately, by the writer of a fixture structure;
- the bindings file is built from data that never held a secret, and the writer refuses to write a text the mask
  would change (§8.3 `approve` step 8);
- the JDBC password goes from `Credentials` to `Driver.connect` inside `JdbcDataChannel` and is not held anywhere
  else.

(Phase 5, built 2026-10-08; P5 §7.4, §6.2) What is never written also includes a prompt that was not
masked, a model key, and a response to a request that named a secret. `PromptMask` applies the run's `SecretMask`, the
key rule and the column rule to every part before a request is built, and `RecordingGateway` applies the mask of the
gateway's opener once more on the way to disk (amended by the Phase 5 review fix wave, 2026-10-08, P5 §4.2: the second
pass applies the key rule and the bounds, and the opener's credential values where its opener passed them; the by-value
and column rules are the request builder's); the model key is read from the environment only, never from a file, a
property or an option;
`inference.jsonl` holds metadata and masked structured answers, and masked prompt text only under `--log-prompts`.

On the agent side a secret travels as a parameter of the primitive call that needs it (a sign-on input), under a
neutral key: the password is the `value` of a `lending.input` call, and the Phase 1 redaction of the agent (P1 §5
"Secret redaction") works by key name, so it would not recognise it. Amendment A34 closes that (Q3).

**A34 `secretParams` (an amendment to the Phase 1 contract, written into P1 §3, §5 and §7 and listed in §16.1;
specified 2026-10-02 and built 2026-10-02 in the Phase 2 wave: the agent core by its owner, the controller side at
integration).**

- *Protocol (P1 §3, §5 "Dispatch").* A request's `_meta` may carry `secretParams: ["<param name>", …]`, a list of
  names of **top-level** params of that request. It is optional; absent or empty means nothing. A value that is
  not a list of strings is `INVALID_PARAMS` ("_meta.secretParams must be a list of strings"). A name that is not a
  param of the request is ignored. Like the other `_meta` keys it is stripped before the primitive sees its
  arguments (`Invocation.args()`), and the primitive receives the values untouched: the rule covers what the core
  *writes about* a call, never what the call *does*. As built (2026-10-02): the key is `Protocol.META_SECRET_PARAMS`;
  a value that is not an array of at most 100 strings is `INVALID_PARAMS` with `data {primitive, param:
  "_meta.secretParams"}` and the message "_meta.secretParams must be an array of at most 100 parameter names"; the
  check runs after the method, `params` and `_meta` checks and before any gate, and the primitive does not run. The
  name `_meta` is ignored like any name that is not a param.
- *Core (P1 §5).* Wherever the core logs, traces or reports the call, the value of every named param is replaced by
  `SecretKeys.REDACTED` (`<redacted>`): in every `AgentLog` line the core writes about the call (the dispatch
  failure line, the timed-out and abandoned-call lines, and any line that would otherwise carry the params); in the
  payload of every event the core itself stamps with call data (none in Phase 1; the rule binds a later `trace`
  record of calls); and in the `data` of every error the core builds for the call (`INVALID_PARAMS` on a named
  param names the key and never its value; `INTERNAL_ERROR`'s `data.message` and stack frames, and `TIMEOUT`'s
  data, pass through the same replacement by value). A module's own message is outside the rule, as in Phase 1: the
  sample's `lending.input` does not echo `value`, and a module that echoes an argument it was told is secret has a
  defect. Replacement is by value, as A25 part (2) does it on the controller (a whole value, and a value of 8 or
  more characters inside a longer text), so that a password the core would otherwise write inside a message is
  caught too.

  As built (2026-10-02; reviewed and corrected 2026-10-03), in the class `SecretParams` used by `Dispatcher`,
  `CallScope` and `RingLog`. *Which values:* of each named param that is present, its text, a number's decimal text,
  and every scalar below it when it is an object or an array; booleans, nulls and empty strings are not secrets.
  *Where they are hidden:* the error the call answers with, its message and every string or number in its `data`
  however deep (an `RpcException` of the primitive, the `INTERNAL_ERROR` of an exception, any other error); every
  agent-log line written on a thread bound to the call's scope, the dispatcher's and a module's alike (message,
  exception message and cause message, hidden before they are cut), and the dispatcher's line for an unexpected
  failure, which is written without the `Throwable` so that no unredacted message slips in; the broker's line about a
  failed posted task, written with the task's scope bound; and the timed-out line, which is written outside the call's
  scope and is therefore passed through the replacement explicitly. The timed-out and abandoned-call lines name only
  the primitive and the thread. *Not hidden:* the payload of an event published under the call's scope, which reaches
  the ring, every subscription and the result's `events` as published (the build of 2026-10-02 rewrote those payloads
  too; the review of 2026-10-03 removed that: a payload value equal to a short secret, a year or an amount with a
  code's digits, would have become `<redacted>` for every reader, and what the call does is not what the core writes
  about it). So a module's message is covered where it reaches the error or a log line of the call's thread; not
  covered are a module's lines on other threads, the result value and the events. *How:* a string or number equal to a
  secret becomes `<redacted>`; inside a longer text a secret of 8 or more characters is replaced wherever it occurs,
  and a shorter one only where it stands as a token of its own (no letter or digit directly before or after it, so
  that `rejected 1111` is hidden and `11112` is not). Map keys are never changed. *Not changed:* the primitive's
  result value.
- *Controller (P1 §7).* `CallOptions` gains a fourth component, `List<String> secretParams` (default empty; the
  three-argument constructor stays), which `AgentClient` writes into `_meta.secretParams`; a `_meta.secretParams`
  already present in the params is merged with it as the other `_meta` keys are (the options win). The executor
  sets it for every step call and cleanup call: a top-level argument is named **by provenance**, when its template
  references a joined secret field of §8.4 or an A25 fixture secret of a version 1 plan (a reference whose slot name
  or a field on its path looks secret by `SecretKeys`, or a reference to a structure that holds such a key), whether
  the reference is the whole argument or lies inside a structure; a path that reaches null, a boolean or an empty
  text names nothing. It is never decided by value: a legitimate literal equal to some credential (a code equal to
  another staff member's password) is not declared, which the specification's by-value wording ("a string the run's
  `SecretMask` would change") would have done; built so on 2026-10-03 after the review, the build of 2026-10-02
  having named joined fields only. The `call` record of `run.jsonl` lists the names
  (§11.3), so a test can see that the sign-on's `value` was declared. As built (2026-10-02): `CallOptions` also has
  `withSecretParams(List<String>)` and `MAX_SECRET_PARAMS = 100` (more names, or a blank one, is an
  `IllegalArgumentException`); `AgentClient` writes the key only when the list is not empty, and then its list
  replaces one given in the params. The executor names a top-level argument when its template references a joined
  credential field (§8.4), or the whole value of a slot that has joined fields; an A25 fixture secret of a version 1
  plan is not announced to the agent (the controller still masks it in everything it writes). As built (2026-10-08,
  the Phase 5 fix wave; P5 §12.2): `AgentClient` drops a `_meta` the params carry, so nothing given in the params is
  merged into the request's `_meta` any more (such a `_meta` could confirm a destructive call).
- *What it proves.* With A34 the sample's end-to-end secret check (§14.2 item 10) extends to the agent's own log:
  no line of `agent.log` holds a password as a value. Without A34 that check could only be made by never making
  the core log a param, which no contract line guaranteed. (As built, that end-to-end proof is made by the Phase 1
  secret check of every load mode, P1 §10 check 18, see §14.2 item 10.)

### 11.5 Reserved for interactive use (not built)

The type names `ExecutorSession` (with `StepOutcome execute(PlannedCall call)`: one call with the step protocol,
that is sync, prompt check, events, snapshot) and `StepJournal` (exports ordinary scenario steps and bindings) are
reserved; nothing in Phase 2 may use them for something else. How a session is opened is left to Phase 5: an
interactive session has bindings but no plan, so no factory signature is fixed here. Phase 2 keeps the run loop
structured so that "execute one call with the step protocol" is one private method, which is what the session
entry will expose (as built 2026-10-02: `executeCall` of the executor's run; neither type exists).

(Phase 5, built 2026-10-08; P5 §12.1, §12.2, §12.3) Phase 5 builds both types in `skillroy-scenario`
(D79), by extracting `executeCall` into a package-private call runner that the plan run and the session share.
`ExecutorSession.open(pack, agent, options)` opens a session without a plan (the checks and pre-flight of a run, the web
path and the fingerprint); `execute` runs one call or one verb with the step protocol and returns a `StepOutcome`, never
throwing for a failed call. `StepJournal` is the session's masked, append-only `journal.jsonl` and exports ordinary
scenario steps and bindings that replay with zero model calls.

---

## 12. CLI and JUnit

### 12.1 `skillroy-cli`

New options of `run` (the Phase 1 options stay):

| Option | Meaning |
|---|---|
| `--mode replay\|approve\|live` | default `replay`. `re-resolve` is refused with "not part of this version" |
| `--env <id>` | environment id, `[a-z0-9-]+`, default `default` |
| `--bindings <dir>` | bindings root, one per invocation; default: §9 |
| `--credentials <file>` | see §4 for the default |
| `--yes` | `approve` only: approve everything without asking (`Approver.APPROVE_ALL`) |
| `--approved-by <label>` | written as `approval.by` |
| `--write-bindings` | `live` only: write the bindings file with `approval.state = NOT_REQUIRED` |
| `--mode re-resolve` (Phase 5, built 2026-10-08; P5 §8.1) | refused by `run` with "re-resolve is a command: skillroy re-resolve" (exit 64), in place of "not part of this version"; the mode belongs to the command of that name |
| `--inference claude\|recorded\|none`, `--recordings <dir>`, `--inference-file <path>`, `--budget-scenario <usd>`, `--log-prompts` (Phase 5, built 2026-10-08; P5 §13.4, §4.4) | the gateway, the recordings directory, the settings file, the per-scenario budget and the logging of masked prompts; also options of `resolve`. The summary prints `model cost: $x (budget $y)` after `model calls: N`, and `--json` carries `modelCost`, `budget` and a summary of the `inference` records |

`approve` without `--yes` asks on the terminal: per scenario it prints the fingerprint, each resolution (slot,
value, resolver, each statement with its parameters, the masked row sample, each verifier's reason), each oracle
with its guard, and the difference against the existing file, then reads `y` or `n`. The question goes through a
small seam, `Terminal { boolean interactive(); String readLine(String prompt); }`, so that tests can script it
and so that the terminal test does not depend on one JDK's behaviour of `System.console()`; the default
implementation reports `interactive` only when a console exists and reports itself as a terminal. End of input
counts as `n`. When the terminal is not interactive and `--yes` is absent, the command fails with exit code 64
before anything runs: `approve` never blocks an unattended job. The same holds for `resolve`. The human-readable
summary of every `run` ends with a line `model calls: 0`. (Phase 5, built 2026-10-08; P5 §13.4: a `model cost: $x (budget $y)` line follows it, and `resolve` prints both lines too.)

(Phase 5, built 2026-10-08; P5 §7.1) For a tier (b) resolution the screen also shows, after the value
and the verifier reasons: the strategy (`llm-sql`), the model, the request id and the cost; the model's rationale; the
guarded statement, with the clauses the guard injected marked `[injected]`; the parameters and the argument each binds;
the masked row sample; and the confidence and the row bounds the model stated, with the count found. On the JUnit path
nothing changes: `approve` in a test is scripted or fails.

**Before the agent is contacted**, `run` and `resolve` determine the bindings root and load the credentials, once.
A credentials file that was named and is missing, or is unreadable, malformed or too open: no scenario runs, the
JUnit XML's `environment` testcase fails with the reason (never a value), and the exit code is 1.

New commands:

| Command | Does |
|---|---|
| `resolve <yaml\|plan.json\|dir>… [--env <id>] [--bindings <dir>] [--credentials <file>] [--yes] [--approved-by <label>] [--dry-run] [--pack <jar>]… [--json]` | connects, checks `requires` and launch properties, runs pre-flight, then binds each scenario in `approve` mode, checks that every `db` source is served (since 2026-10-03) and stops: no step runs, no cleanup call is sent and `recover` is not called. `--dry-run` binds in `live` mode: it prints what would be written and writes nothing. Scenarios that need no bindings are listed as such and skipped |
| `diff-bindings <a.bindings.json> <b.bindings.json> [--json]` and `diff-bindings --bindings <dir> --env <a> --env <b> <scenario-id> [--json]` | offline (no agent): prints the `BindingsDiff` of two files, one line per change, grouped by slot. For reviewing a re-resolution, and for comparing two environments |
| `re-resolve`, `mcp`, `author`, `triage`, `session` (Phase 5, built 2026-10-08; P5 §13.4) | the commands of the AI layer, as P5 §8.2 (`re-resolve`, which refuses to start without a job budget), §9.2 (`mcp`, the MCP server over stdio), §10.2 (`author`, the planner), §11.2 (`triage`, over the output directory of a finished run) and §12.4 (`session`, an interactive session with a REPL or a script) state them. Each takes `--help`, `--json` and the exit codes below unless P5 states otherwise |

`resolve` and `diff-bindings` take `--help`; `resolve` takes the connection options of P1 §8.6. Reviewed and
corrected 2026-10-03: `--dry-run` printed the would-be bindings file with a text scrub only, so a recorded value that
equalled a credential containing `"` or `\` could print, escaped; the would-be file now goes through the same
refusal as `approve`'s writer (`Binder.requireNoCredential`: the credentials mask plus fixture secrets, applied to the
text and to the parsed values), ending the scenario as `ENVIRONMENT` "the bindings file was not written: a recorded
value equals a credential", exit 1, with no `wouldWrite` in `--json`; and the approval screen masks each value before
writing it as JSON, where it masked the text only.

As built (2026-10-02): `resolve --dry-run` prints the bindings file that would be written (`BindOutcome.file`,
§6.6); `resolve` without `--dry-run` and with no bindings root at all (no `--bindings`, no `skillroy.bindings` and no
default of §9) is a usage error; `diff-bindings --json` prints `{a, b, differ, slots, changes}`; with `--json`, `run`
puts `modelCalls` into its JSON and prints the summary and the `model calls: N` line on standard error. `--pack`
accepts any jar or class directory (§7.2). The help texts list `resolve` and `diff-bindings`.

| Exit code | `run` | `resolve` | `diff-bindings` |
|---|---|---|---|
| 0 | every scenario passed | every scenario that needs bindings was resolved (and, unless `--dry-run`, written or already identical) | the files do not differ |
| 1 | a scenario `FAIL` or `ERROR` (including `REBIND_NEEDED`, `RESOLUTION_FAILED`, and a non-empty ledger in `replay`); the credentials could not be loaded | a scenario could not be resolved, or was not approved; the credentials could not be loaded | the files differ |
| 2 | a scenario or plan file could not be loaded or compiled; nothing ran | the same | a file is missing or not a bindings file |
| 3 | the agent could not be found, reached, attached or kept | the same | – |
| 64 | usage error, including `approve` without a terminal and without `--yes`, `--write-bindings` outside `live`, a bad `--env` | usage error | usage error |
| 70 | internal error | internal error | internal error |

(Phase 3, built 2026-10-03; P3 §9) `resolve` prints each cross-check's outcome and exits 1 for
`ORACLE_SUSPECT`; the exit codes themselves are unchanged.

Everything the CLI prints passes the run's `SecretMask` and the control-character rule of S26.

### 12.2 `skillroy-junit`

`ScenarioTests.dynamicTests(source, connection, runOptions)` uses `runOptions.bind()` as it is. The overloads
without `RunOptions` build `RunOptions.defaults().withBind(BindOptions.fromSystemProperties())` (as built
2026-10-02: `fromSystemProperties(root)`, with `root` the default of the table below, which the glue reads from the
new `ScenarioSource.scenarioDirectory()`, a default method that answers null for a source without a directory):

| System property | Meaning | Default |
|---|---|---|
| `skillroy.mode` | `replay`, `approve`, `live` | `replay` |
| `skillroy.env` | environment id | `default` |
| `skillroy.bindings` | bindings root | §9: `bindings` in the parent of the scenario directory of a `ScenarioSource` that has one; for `ScenarioSource.classpath` there is no default, and the property is required when a scenario needs bindings |
| `skillroy.credentials` | credentials file | §4 |
| `skillroy.approve` | `all`: `Approver.APPROVE_ALL`. Any other value, or absent: `Approver.REJECT_ALL` | absent |
| `skillroy.inference`, `skillroy.inference.recordings`, `skillroy.inference.budget.scenario`, `skillroy.inference.file`, `skillroy.inference.logPrompts` (Phase 5, built 2026-10-08; P5 §13.5, §4.4) | the gateway `claude`, `recorded` or `none` and its settings, read when `skillroy-inference` is on the class path | `claude` when `ANTHROPIC_API_KEY` is set and the mode is `live`, `approve` or `re-resolve`, else `none`; `replay` is always `none` |

If `fromSystemProperties()` throws `CredentialsException`, the stream consists of one failing dynamic test named
`environment` with the reason, and no scenario runs. The same single test reports an `approve` stream whose scenario
needs bindings and has no bindings root (2026-10-03).

There is no interactive prompt on the JUnit path: `approve` in a test is either scripted (`skillroy.approve=all`, or
an `Approver` passed in code that asserts on the `ApprovalRequest`) or it fails as `RESOLUTION_FAILED` /
`NOT_APPROVED`.

In `replay`, **when at least one plan of the stream needs bindings**, the stream ends with one more dynamic test,
`skillroy: zero model calls`, which fails when the shared `CostLedger` is not empty after the scenarios of the
stream ran. A stream of Phase 1 scenarios only keeps its test count. Status mapping as Phase 1: `REBIND_NEEDED` and
`RESOLUTION_FAILED` are `ERROR` and throw `ScenarioRunException` carrying the `RunResult`.

(Phase 5, built 2026-10-08; P5 §13.5) `ScenarioTests` is unchanged in its contract. A `replay` stream
needs no gateway; an `approve` or `live` stream with `llm-sql` slots works when `skillroy-inference` is on the class
path and `skillroy.inference` names a gateway. `skillroy.mode=re-resolve` is the single failing `environment` test, with
the message of P5 §8.1. The trailing `skillroy: zero model calls` test is unchanged.

---

## 13. Sample additions

The application's behaviour gains nothing: the supervisor flow, `AccessControl`, `Catalogue` and the database's
content are as built. The application changes in two ways that do not touch its behaviour: its limit table is renamed
(`role_cap`, below), and it gets an optional loopback listener for its database (`lending.dbPort`, end of §13.1) so
that the acceptance can run over the controller-side JDBC channel. All other additions are in the agent module, the
pack and the scenarios module. `LendingPack.VERSION` becomes `0.2.0` (Phase 3, built 2026-10-03:
`0.3.0`, P3 §2). As built (2026-10-02), the listener brought one more change that leaves the desk's behaviour alone: a
database that is to be served is created with a random administrator and without `SA` (end of §13.1). The agent
module's `LendingModule.VERSION` stays `0.1.0`.

**The limit table is `role_cap`** (first step of Phase 2, decided 2026-10-02; D36). The sample table that holds a
role's limits, `role_cap (role_id, kind, amount)`, is renamed from its Phase 1 name, with its columns unchanged, in
the application, its seeds, the agent module, the pack, every test and every document. Reason: the pack's
fingerprinter and its tests need the table name as an upper-case literal for the database's catalogue (§13.3), and
the project's neutrality check rejects the upper-case form of the old name. After the rename nothing in the
repository contains the old name in any case, so this contract does not spell it either. The prose keeps saying
"limit" (a staff member's return limit); only the table is renamed.

### 13.1 The rule the scenario tests (as the application implements it)

From `Engine.addReturnLines` and `AccessControl`, in the order the application applies it, for a return line added
by the signed-on staff member `s`, with `value` = the sum of the open loan's return lines as a positive amount at
scale 2:

1. if `s` lacks the permission `RETURN`: prompt `return-not-permitted`; the line is not added;
2. else if a system maximum is configured (`desk_setting max_return`) and `value > max`: prompt
   `return-not-allowed`; the line is not added;
3. else if `s`'s return limit is unlimited or `value <= limit`: the line is added;
4. else if an authorisation already given in this loan covers `value`: the line is added;
5. else: prompt `supervisor-required` (`OK`, `Cancel`). After `OK` the desk asks for a supervisor code, then a
   password. The authorisation succeeds when the code is not `s`'s own, the password matches, and
   `AccessControl.check(code, RETURN_OVERRIDE, value)` allows it: the supervisor is active, holds
   `RETURN_OVERRIDE`, has no return limit or one of at least `value`, and `value` is within the system maximum.
   Then the line is added with a `loan_authorisation` of kind `RETURN_OVERRIDE`; otherwise the prompt
   `authorisation-failed` appears.

A staff member's return limit is the **maximum** `role_cap.amount` of kind `RETURN` over their roles; no row in
any of their roles means unlimited. Permissions are the union over roles; an inactive or unknown staff member has
none. A supervisor does not need `SIGN_ON` to authorise.

**The database listener (Q4; D34).** The application gets the system property `lending.dbPort=<port>` (1 to
65535; default: not set, no listener). When it is set, the application, after creating and seeding its in-memory
database and before showing its window, also serves that same database on `127.0.0.1:<port>` through the embedded
database's own server class (`org.hsqldb.server.Server`, database name `lending`, path `mem:lending`, silent, no
trace, no `System.exit`), and stops it when the application exits. The listener is the sample's, not the
framework's; it binds loopback only, and nothing in the framework knows it exists. For the controller-side channel
the seed creates a **read-only user**, `reader`, with `SELECT` on every table of the schema and nothing else; its
password is the value of the system property `lending.dbPassword`, which is required when `lending.dbPort` is set
(the application refuses to start without it, naming the property) and is secret by `SecretKeys` (the key contains
`password`), so `jvm.properties`, `jvm.info` and `agent.info.options` report it as `<redacted>`. The URL a
controller uses is `jdbc:hsqldb:hsql://127.0.0.1:<port>/lending`; the driver is the HSQLDB jar the sample already
depends on. The engine's own connection and the agent's `data.query` provider are unchanged: they stay in-process,
and the application's behaviour does not depend on the listener. A headless unit test of the application starts it
on a free port and shows that `reader` can `SELECT` from `loan` and is refused an `INSERT` by the database, that the
port answers on `127.0.0.1` only, and that a start with `lending.dbPort` but without `lending.dbPassword` fails.

**The listener as built (2026-10-02).** `LendingDbServer` runs HSQLDB's own `org.hsqldb.server.Server` bound to
`127.0.0.1` and nothing else, with at most 16 connections, daemon threads, no log, `remote_open=false` (a client
cannot open another database) and no `System.exit`. `LendingApp.dbServer()` returns it (null without a listener);
`LendingApp.shutdown()` stops it before it closes the database, and closing the listener leaves the database open.
The port may also be 0, for any free port (the chosen one is logged and returned by `dbServer().port()`); a value
that is not a port number from 0 to 65535 is an `IllegalArgumentException`, and a port in use fails the launch with
an `SQLException`. `lending.dbPassword` was not part of the build of 2026-10-02 (the account had a fixed
password); it is built since the review of 2026-10-03: `LendingDbServer.start(db, port, readerPassword)` and
`LendingDb.createReadOnlyUser(String)` (a blank password is refused; a `'` is doubled in the SQL literal; `ALTER
USER` when the account already exists; a failing `CREATE` or `ALTER` is rethrown with SQL state and code only, never
the driver's text), and `LendingApp.launch` refuses a set port without a password before the database, the listener
or the window exist, with a message that names the property and echoes no value (an empty or blank value counts as
missing; a password without a port is ignored). Building it found a weakness of HSQLDB 2.7.4: any connected user can
change the password of any user whose name it knows, and a connected administrator can be neither dropped nor renamed,
so with the default administrator `SA` any local process could have taken the database over through the port. A
database that is to be served is therefore created by `LendingDb.createServable`: one administrator whose name and
password are random and known only to the desk's JVM, and no `SA`; `LendingDbServer.start` refuses any other database.
The read-only account is `reader` (`LendingDb.READER_USER`, created by `createReadOnlyUser()`). A limit remains, found
in the build and not covered by a test: `reader` can change its own password, which changes no data but would lock
other clients out. `LendingDbServerTest` (14 tests, headless) shows among other things that every table reads alike
for `reader` and in process, that write and administration statements are refused for `reader`, that the administrator
can be neither seen nor taken over, that nothing answers on a non-loopback address, and that the listener's threads
are daemon threads; `LendingAppMainTest` starts `main` in a child JVM with `-Dlending.dbPort` and probes the port.

### 13.2 Agent module: new primitives

Same registration, thread policy and capability conventions as P1 §9.2 (one capability per primitive name, probed
against the running desk).

| Primitive | Params | Result `value` / behaviour |
|---|---|---|
| `lending.deposit` (`APP`, timeout 5000, read-only) | exactly one of `barcode:string`, `code:string` (both or neither: `INVALID_PARAMS`) | the application's own lookup: `barcode` -> `Catalogue.findByBarcode` (what a scan does), `code` -> `Catalogue.findByCode`. `{found:true, code, barcode, title, deposit, active, perDay, inductionRequired}` with `deposit` as a decimal string, or `{found:false}` |
| `lending.limits` (`APP`, timeout 5000, read-only) | – | `{maxReturn: string\|null}`: `AccessControl.systemMaxReturn()`, the value the desk loaded and enforces; `null` when no maximum is configured |

(Phase 3, built 2026-10-03; P3 §10.2) The module gains the read-only dry run
`lending.waiver.evaluate {lines, date?}` and the `app.state` field `today` (the desk's business date).

`lending.access.check` (P1 §9.2) keeps its params and gains one field in its answer (Q12; D35):
`{allowed, limit, reason, autoSignOn: bool}`, where `autoSignOn` is the application's own flag for the staff member
(`AccessControl.findStaff(code).autoSignOn()`: `true` when entering the code signs on without a password), `false`
for an unknown or inactive staff member. It lets the staff verifier check the "is asked for a password" condition
against the application instead of trusting the resolver's SQL (§13.3). A Phase 1 caller that ignores the field is
unaffected. The module also registers a `MaskPolicy` that replaces the value of a column named `password` (any
case) of source `lending` with `<redacted>`: the `staff` table holds the sample's passwords in clear text, and no
row sample may carry one. Both new primitives, the new field and the `MaskPolicy` have headless unit tests in the
agent module (Java 8), against the application's real `Catalogue`, `AccessControl` and database.

As built (2026-10-02): `lending.deposit` with both parameters answers `INVALID_PARAMS` with `data.param` `"code"`,
with neither `"barcode"`; a code is matched ignoring case, a barcode exactly. `autoSignOn` is `false` for an unknown
or inactive staff member and the member's own flag (`Staff.autoSignOn()`, which the engine reads at sign-on) otherwise
(the build of 2026-10-02 reported an inactive member's own flag; corrected on 2026-10-03, with a test). The
`MaskPolicy` is `PasswordMask`, registered in `install` and removed in `uninstall`: source exactly `lending` and a
column label equal to `password` ignoring case; a non-null value becomes `<redacted>`, null stays null, and a column
renamed in the statement (`password AS p`) is not masked, as for any rule by column name. The module's tests use
`skillroy-agent-data` in test scope to show that the real `data.query` path returns no staff password.
`LendingModule.VERSION` stays `0.1.0`.

### 13.3 Pack: queries, resolvers, verifiers, oracles, fingerprinter, tables

Query and value types (records in `…samples.lending.pack`; money is `BigDecimal` at scale 2):

```java
public record SystemLimit(String type) implements Query<BigDecimal> { }                      // type: RETURN
public record LendableItem(NumberRange deposit, Boolean perDay) implements Query<ItemRef> { } // perDay default false
public record ItemRef(String code, String barcode, String title, BigDecimal deposit) { }
public record StaffWithPermission(List<String> perform, LimitCondition limit, Boolean interactiveSignOn,
                                  List<String> distinctFrom) implements Query<StaffRef> { }
/** The staff member's limit of this kind must satisfy the bounds. Unlimited satisfies gt and gte, never lt or lte. */
public record LimitCondition(String type, BigDecimal lt, BigDecimal lte, BigDecimal gt, BigDecimal gte) { }
public record StaffRef(String code, List<String> permissions, Map<String,BigDecimal> limits) { }   // limits.RETURN: null = unlimited
public record ActionKey(List<String> actions) implements Query<Map<String,String>> { }       // logical action -> key action id
```

Registered with `QueryType.of("SystemLimit", SystemLimit.class)` and so on; the value types are read from the
declarations above. Candidates are built with `ResolverCandidate.of`. As built (2026-10-02), the records are public
and nested in the class `LendingQueries` (`LendingQueries.SystemLimit` and so on), and `LimitCondition` also has
`kind()` (its type, `RETURN` when absent) and `test(BigDecimal limit)` (every bound holds; null, unlimited, satisfies
`gt` and `gte` only).

| Query kind | Resolver (strategy) | Verifier |
|---|---|---|
| `SystemLimit` | `lending.sql.SystemLimit` (`sql`) | `lending.limits` |
| `LendableItem` | `lending.sql.LendableItem` (`sql`) | `lending.deposit` |
| `StaffWithPermission` | `lending.sql.StaffWithPermission` (`sql`) | `lending.access.check` |
| `ActionKey` | `lending.java.ActionKey` (`java`) | `swing.find` |

The names `ActiveWaiverRuleWithTriggers`, `ItemsTriggering`, `LoanFromTrigger`, `ExpectedWaiver`, `ExpectedTotals`
and `lending.waiver.evaluate` belong to scenario B and Phase 3; they are not declared in Phase 2.

**Committed SQL** (source `lending`, HSQLDB; constants in the resolver classes; parameters in the order of the `?`,
in the forms of §7.1: decimals as plain strings with a `CAST` in the statement). Result columns are read by
position.

`lending.sql.SystemLimit`: the parameter is `"max_" + type` in lower case (`max_return`). One row with a decimal
value gives one candidate; no row, a null value or a value that is not a decimal gives none.

```sql
SELECT value FROM desk_setting WHERE name = ? ORDER BY name
```

`lending.sql.LendableItem`: parameters `perDay` (a `Boolean`, default `FALSE`), the range's lower bound (default
`"0"`) and upper bound (default `"99999999.99"`), both as plain decimal strings. The statement narrows; each row is
then a candidate if `deposit` passes `NumberRange.test` (which applies the strict bounds); the order is cheapest
first.

```sql
SELECT code, barcode, title, deposit FROM item
WHERE active = TRUE AND barcode IS NOT NULL AND per_day = ?
  AND deposit >= CAST(? AS DECIMAL(10,2)) AND deposit <= CAST(? AS DECIMAL(10,2))
ORDER BY deposit, code
```

`lending.sql.StaffWithPermission`: one candidate statement, then one small statement per staff row that is still
in question. The first has one parameter, the limit kind (`limit.type`, default `RETURN`):

```sql
SELECT s.code AS code, s.auto_sign_on AS auto_sign_on,
       (SELECT MAX(rl.amount) FROM staff_role sr JOIN role_cap rl ON rl.role_id = sr.role_id
         WHERE sr.staff_code = s.code AND rl.kind = ?) AS limit_amount
FROM staff s WHERE s.active = TRUE ORDER BY s.code
```

(As built 2026-10-02 the alias of `role_cap` is `rl`; each committed statement is one line of text, and the
bindings files record that text.)

In `code` order, a row stays in question when: `limit`, if given, holds for `limit_amount` (SQL `NULL` =
unlimited); `interactiveSignOn: true` and `auto_sign_on` is false (the `signOn` verb enters a password, which an
auto-sign-on staff member is never asked for; the verifier confirms this reading through `autoSignOn`, §13.2); its
code is not in `distinctFrom`; and, when the slot declares a credential, `ctx.credentials()` has an entry for the
code. Only for such rows the permissions are read, with the
staff code as parameter:

```sql
SELECT DISTINCT rp.permission AS permission
FROM staff_role sr JOIN role_permission rp ON rp.role_id = sr.role_id
WHERE sr.staff_code = ? ORDER BY permission
```

The row is a candidate when its permissions contain every entry of `perform`. The resolver stops as soon as it has
`ctx.maxCandidates()` candidates. The value is `{code, permissions (sorted), limits: {<type>: limit_amount}}`. The
`MAX` mirrors the application's rule; whether it really does is what the verifier is for. The password column is
never selected. Both statements are bounded: the first returns one row per active staff member and may be
truncated (a prefix by code, recorded as `candidatesTruncated`); the second returns one staff member's
permissions and goes through `ctx.requireComplete`. The limit conditions are applied in Java so that "unlimited"
is decided in one reviewed place; a pack for a large staff table would push them into the first statement.

As built (2026-10-02), one rule was added so that a credential-less environment fails as §4 and §14.2 item 10 say
(`ALL_REJECTED`, "no credential staff/<id>") rather than as `NO_CANDIDATE`: the resolver skips staff without an
entry early only when the slot's credential is keyed by `code`, and when no staff member with an entry qualifies but
some were skipped only for want of one, it offers those after all (in code order, permissions checked), so that the
engine drops them with that reason. A bind that succeeds reads exactly what it read without the rule, so the
committed bindings files do not depend on it.

`lending.java.ActionKey` (committed Java, no SQL): one candidate, the map `{<action>: <action>}` for the requested
actions, provided each is one of the pack's key actions (`DUE`, `DEPOSIT`, `RETURN`, `CANCEL`, `SIGN_OFF`, `QTY`;
as built the public constant `LendingPack.KEY_ACTIONS`, which the `key` verb uses too); otherwise none. The knowledge
is the pack's; the verifier proves it against the running window.

**Verifiers.**

| Verifier id | Requires | Passes when |
|---|---|---|
| `lending.limits` | `lending.limits` | `lending.limits` answers a `maxReturn` equal (as a decimal) to the candidate |
| `lending.deposit` | `lending.deposit` | `lending.deposit {barcode: candidate.barcode}` answers `found`, `active`, the candidate's `code`, a `deposit` equal to the candidate's, and `perDay` equal to the query's |
| `lending.access.check` | `lending.access.check` | all of: (1) the candidate's code is not in `distinctFrom`; (2) for every permission `p` in `perform`, `lending.access.check {staff, permission: p}` is `allowed`; (3) the `limit` field of the answer for `RETURN` (or, when `perform` has no `RETURN`, for `RETURN_OVERRIDE`) equals the candidate's limit (both null, or equal decimals), and that reported limit satisfies **every** bound of the query's `limit`, strictly where the bound is strict (unlimited satisfies `gt` and `gte` only); (4) for each bound, with `p` = each of `RETURN`, `RETURN_OVERRIDE` present in `perform`: `gte: x` or `gt: x` needs `{staff, permission: p, amount: x}` to be `allowed`; `lt: x` needs it to be refused. For the supervisor the call of (4) is exactly the check the engine makes when it authorises; (5) when the query has `interactiveSignOn: true`, the answer's `autoSignOn` (§13.2) is `false`, with the reason "signs on with the code alone" otherwise (Q12: the "is asked for a password" condition is verified by the application, not only by the resolver's SQL; `interactiveSignOn` absent or `false` adds no condition). Every condition of the query is thereby verified |
| `swing.find` | `swing.tree` | for every action of the candidate, `swing.find {by: {action: <id>}, all: true}` returns exactly one match (with `all`, zero matches is an empty list and not an error; without it the primitive answers `NOT_FOUND`) |

As built (2026-10-02): the verifiers' reason texts are the implementer's, and they appear in the committed bindings
files ("SIGN_ON and RETURN permitted; RETURN of 34.99 refused; limit 20.00; asked for a password"). In
`lending.access.check`, condition (5) reads `autoSignOn` from the first answer of (2) (from a `SIGN_ON` check when
`perform` is empty) and fails with "the desk signs staff <code> on without a password"; a `limit` of another type
than `RETURN` is refused ("the desk reports RETURN limits only"); an `lte` bound is checked against the reported
limit in (3) and not probed in (4).

**Oracles.**

```java
/** What the desk does when a staff member with this return limit enters a return of this amount (§13.1). */
public final class ExpectedAuthorisation implements Oracle<ExpectedAuthorisation.Input, ExpectedAuthorisation.Outcome> {
    public record Input(BigDecimal staffLimit /*null = unlimited*/, BigDecimal systemMax /*null = none*/,
                        BigDecimal amount) { }
    public enum Outcome { NONE, SUPERVISOR_PROMPT, NOT_ALLOWED }
    // fn() = "ExpectedAuthorisation"
}
```

`compute`, with `a` = `|amount|` at scale 2 (`HALF_UP`), in this order: `systemMax != null && a > systemMax` ->
`NOT_ALLOWED`; `staffLimit == null || a <= staffLimit` -> `NONE`; otherwise `SUPERVISOR_PROMPT`. It assumes the staff
member holds `RETURN` (the scenario's query asks for it) and that no earlier authorisation exists in the loan (the
scenario returns one line). Unit tests cover each branch, both boundaries (`a == limit`, `a == systemMax`), null
inputs and a negative amount.

`Money.negate`: `Oracle<BigDecimal, BigDecimal>`, `fn()` = `"Money.negate"`, returns `amount.negate()` with the
scale kept (`"34.99"` -> `"-34.99"`). As built (2026-10-02) the class is `MoneyNegate` (constant `FN`).

**Fingerprinter** (`<pack>.<name>` components):

| Component | Source |
|---|---|
| `lending.schema` | `"sha256:"` over the canonical JSON of `{<table>: rows}` for the tables the pack reads (`staff`, `staff_role`, `role_permission`, `role_cap`, `desk_setting`, `item`, `loan`, `loan_line`, `loan_waiver`, `loan_authorisation`; a constant list in the pack, upper-cased for the lookup because the sample database stores unquoted names in upper case, which is why the limit table was renamed, §13), each read with `SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'PUBLIC' AND table_name = ? ORDER BY ordinal_position` (source `lending`). A table that returns no row is a fingerprinter failure. One short statement per table the pack depends on: the checksum does not grow with the schema, cannot be truncated, and ignores tables the pack never touches |
| `lending.site` | `SELECT value FROM desk_setting WHERE name = ? ORDER BY name` with `slip_header` |
| `lending.seed` | `app.state`, section `lending`, field `seed` |

Seeds A and B share the schema and differ in site and seed, so their fingerprints differ. (Phase 3, built 2026-10-03;
P3 §10.3, D45) The table list gains `WAIVER_RULE` and `WAIVER_RULE_ITEM`; scenario A's committed bindings are
regenerated once.

**Stored tables** (source `lending`; logical and physical names coincide; `tableSql` is the name). Decimal columns
carry `paramSql` `CAST(? AS DECIMAL(10,2))` (`qty`: `DECIMAL(10,3)`); the others `?`.

| `db.<name>` | Columns (kind) | Order |
|---|---|---|
| `loan` | `id` (INTEGER), `staff_code` (TEXT), `posted_at` (TIMESTAMP), `total` (DECIMAL) | `id` |
| `loan_line` | `loan_id` (INTEGER), `line_no` (INTEGER), `item_code` (TEXT), `qty` (DECIMAL), `unit_deposit` (DECIMAL), `amount` (DECIMAL), `returned` (BOOLEAN) | `loan_id, line_no` |
| `loan_waiver` | `loan_id` (INTEGER), `rule_code` (TEXT), `amount` (DECIMAL) | `loan_id, rule_code` |
| `loan_authorisation` | `loan_id` (INTEGER), `kind` (TEXT), `staff_code` (TEXT) | `loan_id, kind, staff_code` |

**Pre-flight.** The pack's `lending.database` check (in Phase 1 a `data.query` call on the agent) is implemented
through `check(PreflightContext)` and reads through `ctx.data()`, so it also passes in an environment that serves
`lending` over JDBC.

**Row masks**: `rowMasks()` returns the same rule as the module's `MaskPolicy` (column `password` of source
`lending`).

**Verbs.** One new verb; two changed.

| Verb | Compiles to |
|---|---|
| `authorise {staff}` | `lending.prompt.answer {choice: OK, id: supervisor-required}` with sync `EVENT` on the pack's ready topics, marked as handling the prompt; then `lending.input` Entry with `<staff>.code`, READY; then `lending.input` Entry with `<staff>.password`, READY. `staff` is a `$slot` reference whose slot has a `credential`, which the verb checks with `CompileContext.slot` (§6.1); a compile error otherwise: a literal mapping with a password is no longer accepted. If the desk refuses, its `authorisation-failed` prompt is an unexpected prompt and fails the step under `on_prompt: {unexpected: fail}` |
| `signOn {staff}` (changed) | as Phase 1, but `staff` must be a `$slot` reference whose slot has a `credential` (checked with `CompileContext.slot`); the mapping form `{code, password}` is removed |
| `key {action, value?}` (changed) | `action` may be a reference (`$keys.RETURN`); the check against the pack's key list then happens through the `ActionKey` slot instead of at compile time |

### 13.4 Scenarios module

Layout (new entries only):

```
scenarios/return-over-limit-needs-supervisor.yaml       plan/return-over-limit-needs-supervisor.plan.json
bindings/seed-a/return-over-limit-needs-supervisor.bindings.json
bindings/seed-b/return-over-limit-needs-supervisor.bindings.json
credentials.example.yaml                                 (placeholders; documents the format)
src/test/resources/negative/no-item-in-range.yaml        src/test/resources/negative/return-within-limit.yaml
```

Environment ids: `seed-a`, `seed-b`. With the default of §9, both `scenarios/x.yaml` and `plan/x.plan.json` find
the module's `bindings/` directory. The tests generate each environment's `credentials.yaml`, owner-only, into a
JUnit temporary directory under `java.io.tmpdir` (not into the build directory, which `ci/run-in-linux.sh`
bind-mounts and where owner-only permissions may not hold), from the same made-up staff data the seeds use (test
code, as `SeedData` is application code), with an entry for every staff member; no credentials file is committed.
For a target launched with the database listener (§13.1, §14.2) the generated file also has a `jdbc:` section with
the one source `lending` (`url: jdbc:hsqldb:hsql://127.0.0.1:<port>/lending`, `user: reader`, the generated
`lending.dbPassword` value as `password`), which is what routes that source to the controller-side channel (§7.2)
for that target and leaves every other target on the agent channel. `credentials.example.yaml` shows both
sections, with placeholders, and carries the 8-character advice of §4 (added on 2026-10-03; it also says how the
file is found, that the sample's tests generate theirs, and that the `jdbc` password is the value the desk was
launched with as `-Dlending.dbPassword`).
The `wrong-assistant` bindings are **generated by the test** from the committed file (the assistant's value and
credential replaced by the supervisor's, `argsHash` kept), so that an edit of the scenario's `resolve` section does
not leave a stale negative file behind.

**Existing material that changes**, because fixtures may no longer hold a password (§3.1 rule 5) and `signOn`
loses its mapping form. The contract text comes first (§16.1), then:

| Where | Change |
|---|---|
| `scenarios/basic-loan.yaml`, `scenarios/basic-loan-ui.yaml` and their two committed plans | the assistant becomes `{ fixture: { code: "100" }, credential: { realm: staff, key: code } }`; they keep `launchProperties: { lending.seed: A }` and need no bindings file; the plans are regenerated (version 2) |
| the four Phase 1 negatives under `src/test/resources/negative/` (`guarded-unavailable`, `missing-capability`, `prompt-left-open`, `wrong-expectation`), and the 1.x click-fidelity negatives when they exist | the same one-line change |
| `LendingStepsTest`, `LendingPackTest` | cases for the mapping form of `signOn` are replaced by: slot with credential compiles; slot without, and a literal mapping, are compile errors |
| `controller/skillroy-scenario` test material: `toy-loan.yaml`, its plan, `ToyPack`, `ScenarioLoaderTest`, `PlanExecutorTest` | the toy scenario's assistant gets a `credential`; the tests pass `BindOptions` with `Credentials.of(…)` built in code. One test keeps a version 1 plan with a fixture password, to prove that version 1 is still read and that A25 masks it |
| every caller that runs a sample scenario with `RunOptions.defaults()` (the end-to-end test, the JUnit glue tests) | passes the generated credentials through `RunOptions.withBind` |
| P1 §8.1, §8.2 (examples), §9.3 (`signOn` row) | show the credential form |

`scenarios/return-over-limit-needs-supervisor.yaml`, in full:

```yaml
# A deposit return above the signed-on assistant's return limit needs approval by a supervisor.
# Nothing here names a person or an item. The item is chosen first (the cheapest that fits), the assistant and the
# supervisor are chosen for that item: the file runs on a seeded desk in which some assistant's return limit is
# below that item's deposit and somebody else may authorise the amount.
scenario: return-over-limit-needs-supervisor
pack: lending
tags: [resolution, returns]
requires: [lending.input, lending.state, lending.loan, lending.loan.authorisations, lending.prompt,
           lending.prompt.answer, lending.slip]
resolve:
  systemMax:
    query: SystemLimit
    args: { type: RETURN }
  item:
    query: LendableItem
    args: { deposit: { between: ["20.00", "200.00"], lte: $systemMax } }
    strategy: [sql]
    verify: lending.deposit
  assistant:
    query: StaffWithPermission
    args: { perform: [SIGN_ON, RETURN], limit: { type: RETURN, lt: $item.deposit }, interactiveSignOn: true }
    verify: lending.access.check
    credential: { realm: staff, key: code }
  supervisor:
    query: StaffWithPermission
    args: { perform: [RETURN_OVERRIDE], limit: { type: RETURN, gte: $item.deposit },
            distinctFrom: [$assistant.code] }
    verify: lending.access.check
    credential: { realm: staff, key: code }
  keys:
    query: ActionKey
    args: { actions: [RETURN, DUE] }
oracle:
  auth:
    fn: ExpectedAuthorisation
    args: { staffLimit: $assistant.limits.RETURN, systemMax: $systemMax, amount: $item.deposit }
    must_equal: SUPERVISOR_PROMPT              # a binding under which no supervisor is needed is rejected, not run
  returnLine: { fn: Money.negate, args: [$item.deposit] }
steps:
  - signOn: { staff: $assistant }
  - expect: { "state.name": IDLE, "state.staff.code": $assistant.code }
  - key: { action: $keys.RETURN }
  - expect: { "state.name": RETURN, "state.returnMode": true }
  - scan: { item: $item }
  - expectPrompt: { id: supervisor-required, within: 10s }    # its text is captured, not asserted
  - authorise: { staff: $supervisor }
  - expect:
      "loan.lines[code=$item.code].returned": true
      "loan.lines[code=$item.code].amount": $oracle.returnLine
      "loan.authorisations[kind=RETURN_OVERRIDE].staffCode": $supervisor.code
  - key: { action: $keys.DUE }
  - expect: { "state.name": DEPOSIT, "loan.total": $oracle.returnLine }
  - deposit: {}                                               # a return hands the deposit back: no amount
  - waitFor: { topic: loan.posted, within: 10s, capture: { slot: loan, fields: { id: loanId, total: total } } }
verify:
  - { "state.name": IDLE }
  - db.loan: { where: { id: $loan.id }, staff_code: $assistant.code, total: $oracle.returnLine }
  - db.loan_line: { where: { loan_id: $loan.id, item_code: $item.code }, returned: true, amount: $oracle.returnLine }
  - db.loan_authorisation: { where: { loan_id: $loan.id }, kind: RETURN_OVERRIDE, staff_code: $supervisor.code }
  - { "slip.last.text": { contains: $item.title } }
on_prompt: { unexpected: fail }
timeouts: { step: 30s }
```

It has no `launchProperties`: that is the point. It does not require `lending.sql`: which channel serves the source
`lending` is the environment's business (§7.2), and the served-source check covers it. It does require
`lending.loan.authorisations`, because it asserts on that fact unguarded. As built (2026-10-02) the comment block
is four lines and the `requires` list is wrapped over two lines (the file keeps to 120 columns), so the `waitFor`
step is on line 54 (the plan example of §5). The committed bindings files were written by `approve` without a label,
so their `approval.by` is `approve` (§9). What it resolves to, by the seeds as built:

| Slot | Seed A (`seed-a`) | Seed B (`seed-b`) | Why this one |
|---|---|---|---|
| `systemMax` | `500.00` | `350.00` | `desk_setting max_return` |
| `item` | `PWR-001`, deposit `34.99` | `T1009`, deposit `27.50` | the cheapest active counted item between 20 and 200 |
| `assistant` | `100` (limit `20.00`) | `4711` (limit `15.00`) | first by code with `SIGN_ON` and `RETURN`, a limit below the deposit, a password step, and a credential. Seed B's `4715` holds two roles with limits 15 and 40; the application takes the maximum, 40, so that staff member is *not* below the deposit. Seed A's `103` and seed B's `4714` sign on without a password and are excluded |
| `supervisor` | `200` (limit `300.00`) | `4720` (limit `200.00`) | first by code with `RETURN_OVERRIDE` and a limit of at least the deposit, other than the assistant |
| `keys` | `{RETURN: RETURN, DUE: DUE}` | the same | |
| `$oracle.auth` | `SUPERVISOR_PROMPT` | `SUPERVISOR_PROMPT` | |
| `$oracle.returnLine` | `-34.99` | `-27.50` | |

Negative material:

| File | Run in | Expected |
|---|---|---|
| `negative/no-item-in-range.yaml`: scenario A with `deposit: { between: ["100000.00", "200000.00"] }` | `live` | `ERROR` / `RESOLUTION_FAILED`, reason `NO_CANDIDATE`, slot `item`; no step runs |
| `negative/return-within-limit.yaml`: scenario A with the assistant's condition turned round, `limit: { type: RETURN, gte: $item.deposit }` | `live` | every slot resolves and every verifier passes (seed A: assistant `200`, supervisor `300`; seed B: assistant `4715`, supervisor `4720`), but the guard computes `NONE`, not `SUPERVISOR_PROMPT`: `ERROR` / `RESOLUTION_FAILED`, reason `GUARD`; no step runs. The scenario would have run to a failed `expectPrompt`; the guard says why before the desk is touched |
| `wrong-assistant` (generated by the test, see above): the committed bindings file of that environment with the assistant's value and credential replaced by the supervisor's | `replay` | `ERROR` / `REBIND_NEEDED`, reason `VERIFIER`, slot `assistant` (the application refuses "limit below the deposit" for that staff member); no step runs |

---

## 14. Acceptance

### 14.1 Without a display (unit and module tests; run in the normal build)

1. `skillroy-protocol`: the moved `StatementGuard`, `ValueConverter` and `ReadOnlyJdbc` pass the tests they had in
   `skillroy-agent-data`, on a Java 8 test JVM too; `Rows` round-trips through `toJson`/`fromJson`; `index(name)`
   is case-insensitive; a parameter of a type §7.1 does not allow is refused.
2. `skillroy-data`, `StrictYaml`: the tests `YamlReader` had, moved with it.
   `JdbcDataChannel` against an HSQLDB database started inside the test: a guarded statement is refused before a
   connection is used; values have the forms of P1 §6; the 200-row cap and `truncated`; a `RowMask` is applied and
   a throwing one yields `MASK` without the value; an `SQLException` surfaces state, code and class but not the
   driver's message; the password and URL user-info appear in no exception or `toString`; the connection is
   reused, and reopened once after it was closed underneath. With a **fake `java.sql.Driver`** (registered through
   a test `ServiceLoader` entry, and once by class name from a child class loader that `DriverManager` cannot
   see): the driver is found both ways; a connect that never returns ends as `TIMEOUT` after `loginTimeout`; a
   null from `connect` is `UNAVAILABLE`.
3. `skillroy-data`, **channel parity**: one abstract contract test (`DataChannelContract`) runs the same statements
   **with parameters of every allowed form** (a decimal string compared at its exact bound included) against
   `JdbcDataChannel` and against `AgentDataChannel`. The latter runs over a fake connection that executes
   `data.query` with the real `ReadOnlyJdbc` and `StatementGuard` and passes request and response through
   `Json.write` and `Json.parse`, as the wire does. The two must return equal `Rows`. The agent channel's error
   mapping of §7.2 is tested row by row, `OTHER` included, and `serves` on a target without a data module.
4. `Credentials`: grammar, strictness, `of(Map)`, the location rule, the permission rule (POSIX; the ACL and the
   no-permission branches with a fake file system, marked "not verified on Windows"). `SecretMask`: equality;
   a 12-character secret inside an error message, an event payload and a snapshot text is masked; a 4-character
   secret inside a longer text is not, and as a whole value is; URL user-info; **keys are not looked at**: a map
   with the keys `credential`, `inputTokens` and `password` keeps all three values unless a value is a secret.
5. `skillroy-scenario`: grammar and compile errors of §3 (each rule, with file and line); `CompileContext.slot`;
   plan version 2 written, versions 1 and 2 read, others refused, a version 2 plan with a secret-looking fixture
   key refused; byte-identical recompilation; `resolveHash` is unchanged by an edit to a step **and by a pack
   version change**, and changed by an edit to a slot or an oracle; `RecordCodec` for every supported type, sorted
   keys, and each error; the graph order (edges from `must_equal` included) and the cycle error;
   `ResolutionCatalogue.build()` validations, `builder(base)` and `replace`; the generated `db` SQL for each
   `ColumnKind`, with quoted pack text, and its guard check.
6. `skillroy-scenario`, the binder against a toy pack and a fake connection, for each mode: every reason code of
   §10 is produced by exactly the situation §8.3 names; `replay` emits no `resolver` event; a fingerprint mismatch
   calls no verifier; the first verified candidate wins and the rejected ones are recorded with their resolver;
   **a second resolver is asked when every candidate of the first is rejected**, and not otherwise; candidates
   without a credential are dropped before the cap of 20; no backtracking, and `fixedUpstream` in the detail; the
   read-only view (mutating refused, `await` unsupported, unknown primitive passed through, calls recorded in
   provenance); the bind budget on a test clock, with timeouts clamped, and review time not counted; a reference
   to a joined secret field in `resolve`, `oracle`, an expectation or a `db` element is `REFERENCE`, and the
   secret reaches no resolver, hash or file; `approve` writes only on approval, atomically, creates the
   environment directory, leaves an identical file untouched, and refuses a text that contains a credential
   value; `live` writes nothing without `writeBindings`; the bindings file is byte-stable, keeps its `credential`
   objects, and holds no secret, timestamp or path; a pack built with `builder(base).replace(…)` binds a plan
   compiled with the base pack.
7. `skillroy-scenario`, executor: `capture` binds the slot (a nested payload path too) and a missing payload value
   fails the step; `db` elements (row count, column matchers **by position against a result whose reported names
   are upper case**, `IS NULL`, parameter conversion per kind, retry, truncation, wrong column count, source not
   served); after a failure before the start mark (each of: an unmet `requires`, a launch-property mismatch, a
   failed pre-flight, a failed bind, an unserved `db` source) no cleanup call and no `recover` go out, and after a
   failed start mark neither does (Q11); every step call and cleanup call whose substituted argument is a secret
   carries that argument's name in `_meta.secretParams`, a call without one carries no such key, and the `call`
   record lists the names (A34); the ledger check turns a passing `replay` run into `ERROR` / `ENVIRONMENT` when
   **a test verifier that holds the `BindOptions` ledger** records an entry (no resolver runs in `replay`, so a
   resolver cannot be the writer), and leaves `live` alone; `cost-ledger.jsonl` is always written;
   `bindings.json` is rewritten at run end with `captured`.
8. `skillroy-sample-lending-pack`: oracle unit tests (§13.3); verb compile tests (credential check, `$keys`
   reference); the catalogue builds, and builds the same on every call; the staff verifier rejects a candidate
   whose `autoSignOn` is `true` under `interactiveSignOn: true` and accepts it without that condition (Q12).
   `skillroy-sample-lending-agent`: headless tests of `lending.deposit`, `lending.limits`, the `autoSignOn` field
   of `lending.access.check` (an auto-sign-on staff member, an ordinary one, an unknown code) and the `MaskPolicy`
   (§13.2). `skillroy-sample-lending-app`: the headless listener test of §13.1 (Java 8 test JVM too). Every test
   of the application, the agent module and the pack that names the limit table uses `role_cap`, and the project's
   neutrality check finds the old name nowhere, in any case.
   `skillroy-sample-lending-scenarios`, **the pack's SQL on the real schema over the JDBC channel**, a test that
   needs no display and runs in the normal build (it lives here because it needs the committed YAML, plan and
   bindings as well as the application jar): it starts the sample's own database (`LendingDb`, seeds A and B)
   inside the test JVM and points `JdbcDataChannel` at it, with a test connection that answers `/ping`,
   `schema`, `lending.access.check`, `lending.deposit`, `lending.limits` and `app.state` from the application's
   real `AccessControl` and `Catalogue`, and `swing.find` from a fixed list of the key actions. On each seed:
   `live` resolves the table of §13.4; `approve` writes a file byte-identical to the committed one except for
   `"channel": "jdbc"` in the statements; `replay` of that file passes the bind; the generated `wrong-assistant`
   file is `REBIND_NEEDED`; the two negative scenarios fail as §13.4 says; the `db.*` statements of the plan run
   against the same database and their columns match by position. This is where the controller-side channel
   meets the sample's schema, since the sample's end-to-end database lives in the target's memory and is
   reachable there only through the agent.
9. `skillroy-cli`: option parsing and exit codes of §12.1 against the fake agent; the default bindings root for a
   YAML argument and for a plan argument is the same directory; `diff-bindings` on the committed `seed-a` and
   `seed-b` files reports the four differing slots and the fingerprint components and exits 1; on a file and
   itself exits 0; `approve` with a scripted non-interactive `Terminal` and without `--yes` exits 64 and makes no
   call; end of input answers `n`; a refused credentials file exits 1 with a failed `environment` testcase and
   makes no call. As built (2026-10-02): the check on the committed files is `DiffCommittedBindingsTest` in
   `skillroy-sample-lending-scenarios`, where the files are, calling the CLI's `Main.run` (it also finds
   `oracle.returnLine` among the differences); the CLI module's own tests cover the rest of the item with generated
   files.
10. `skillroy-junit`: the system properties of §12.2; the trailing `zero model calls` test is present for a stream
    with a plan that needs bindings and absent for a Phase 1 stream; a `CredentialsException` becomes the single
    test `environment`.
11. `PlansUpToDateTest` covers the new plan; a new `BindingsUpToDateTest` checks that each committed bindings file
    is in canonical form (read and written again, it is byte-identical) and that its `resolveHash` equals the
    committed plan's.
12. `skillroy-agent-core`, A34 (Java 8 test JVM too): a call with `_meta.secretParams: ["value"]` to a test
    primitive that throws reaches the primitive with its `value` intact and answers `INTERNAL_ERROR` whose
    `data.message` and frames show `<redacted>` where the value stood; the dispatch failure line and the timed-out
    and abandoned-call lines of the agent log show `<redacted>` and never the value; a name that is no param is
    ignored; a `secretParams` that is not a list of strings is `INVALID_PARAMS`; a request without it behaves as in
    Phase 1. `skillroy-controller`: `CallOptions.secretParams` is written into `_meta.secretParams`, merged with a
    `_meta` already in the params, and omitted when empty.

### 14.2 End to end (extends P1 §10; needs a display; `ci/run-in-linux.sh`)

As built: this acceptance ran on 2026-10-02 on the integration tree, in the Linux image, on Java 8 and Java 25
targets; the numbers are in `../TODO.md`. Where a check below was built differently, its item says so.

`LendingEndToEndIT` runs the Phase 1 and 1.x checks as before, on seed A, with the generated credentials file.
New: for **each load mode** (`JAVAAGENT`, `JAVAAGENT via JAVA_TOOL_OPTIONS`, `ATTACH`, `EMBEDDED`) and **each
seed** (A, B; one launched target per mode and seed), the checks of `ResolutionChecks`, through the real
`PlanExecutor`, `Binder` and `AgentClient` over HTTP, with the committed YAML, plan and bindings:

1. **Live resolution.** `Binder.bind` in `live` resolves the slots and oracles to the values of §13.4 for this
   seed; every verifier passed; the recorded statements are the committed SQL texts with channel `agent`; nothing
   was written to the bindings directory; the desk is untouched (signed off, no prompt, and no `input.acked` or
   `state.changed` event since the bind began).
2. **Approve is scriptable and deterministic.** `approve` with a scripted `Approver` (which asserts that it was
   shown value, statements and verifier evidence for every slot) writes into an empty temporary bindings root a
   file byte-identical to the committed `bindings/<env>/…` file.
3. **Same YAML, per-environment bindings.** The committed plan (one file for both seeds, no launch property) runs
   in `replay` with `--env seed-a` on seed A and `--env seed-b` on seed B: `PASS`.
4. **No sleeps, no resolution in replay.** In that run the executor's sleeper is never called, every expectation
   and every `db` check held at its first attempt, no resolver ran (`run.jsonl` has no `resolver` record, which
   also covers the Java resolver of `keys`, and every `resolve` record has `strategy: REPLAY`), and every slot's
   verifier ran once (one `verifier` record per slot).
5. **Zero model calls, asserted.** `RunResult.modelCalls` is empty, `cost-ledger.jsonl` exists and is empty, the
   ledger shared by the mode's runs is empty at the end, and the plan's `inference` is `none`.
6. **Stored rows over the agent channel.** The three `db` checks read the rows the application wrote; the captured
   `$loan.id` equals `lending.loan`'s `id` and the loan id of the newest slip.
7. **A wrong binding is `REBIND_NEEDED`.** `replay` with the generated `wrong-assistant` bindings: `ERROR` /
   `REBIND_NEEDED`, reason `VERIFIER`, slot `assistant`, the application's refusal in the evidence; no step ran,
   no cleanup call and no `recover` were sent (`run.jsonl` has neither record), and the desk is untouched. The
   same holds for a failure before the bind (Q11): scenario A with a `requires` entry no target has ends
   `PRECONDITION_FAILED` with nothing sent after the capability check, and a pack pre-flight scripted to fail ends
   `ENVIRONMENT` the same way. (Proven by `ResolutionChecks` since 2026-10-03: scenario A with an unknown
   capability added to `requires`, compiled in memory, ends `PRECONDITION_FAILED`; the committed plan run with a
   delegating pack whose pre-flight is scripted to fail ends `ENVIRONMENT`; a recording connection shows that only
   `agent.capabilities` was sent, and `run.jsonl` holds exactly `run-start`, `requires`, `preflight` in the second
   case, `ledger`, `run-end`. Before that the sentence rested on the Phase 1 check of a prompt left open and on the
   executor's headless tests.)
8. **A foreign environment is `REBIND_NEEDED`.** `replay` against this target with the *other* seed's environment
   id, and therefore its committed bindings: reason `FINGERPRINT`, naming `lending.seed` and `lending.site`; no
   verifier was called. With an environment id that has no bindings file: reason `NO_BINDINGS`.
9. **A query nothing satisfies is `RESOLUTION_FAILED`.** `no-item-in-range` in `live`: reason `NO_CANDIDATE`, slot
   `item`. `return-within-limit` in `live`: reason `GUARD`, with the oracle's `NONE`. No step ran in either.
10. **Secrets.** The sample's passwords are four digits, so the assertion is on values, not on substrings (a
    substring test would hit timestamps, durations and hashes): in every file under the output directories of
    checks 1-9, in the written bindings file, in the plan, in the `RunResult`s and in the JUnit XML rendered from
    them, no JSON string value, XML attribute value or XML text token equals the assistant's or the supervisor's
    password. **With A34** the same holds for the agent's own log (`agent.log`, every line, split on whitespace and
    quotes) after the runs of checks 1-9, and every `lending.input` call of a `signOn` or `authorise` step that
    entered a password carried `_meta.secretParams: ["value"]` (the `call` records of `run.jsonl`). (Proven by
    `ResolutionChecks` since 2026-10-03, per load mode and seed: every line of `agent.log`, split on whitespace and
    quotes, is scanned for both passwords of the seed, and in the replay run every `lending.input` call whose
    `value` is `<redacted>` carries `secretParams: ["value"]`, exactly the `signOn` and `authorise` steps, and no
    other `lending.input` call does. The Phase 1 check 18 keeps its own test on `basic-loan` and `basic-loan-ui`.)
    The substring
    rule for long secrets is proven headless (§14.1 item 4). A `replay` run without the credentials file is
    `ERROR` / `ENVIRONMENT` and names `staff/<code>`; a `live` run without it is `RESOLUTION_FAILED` /
    `ALL_REJECTED` whose rejections say "no credential".
11. **The next scenario still runs.** After checks 7-9, `basic-loan` (seed A) or scenario A again (seed B) passes
    without a restart.

Once per target Java (not per load mode), through the assembled CLI launcher against a running target:
`skillroy resolve --env seed-a --yes` writes the committed file's bytes and exits 0; `skillroy run --mode replay
--env seed-a` exits 0 and prints `model calls: 0`; the same with the `wrong-assistant` root exits 1 and prints
`REBIND_NEEDED`; no whitespace-delimited token of the output equals a password. The commands are given the
scenario file only: the default bindings root (§9) must find the committed files.

**Once per target Java, over the controller-side JDBC channel (Q4).** One more target (seed A, load mode `JAVAAGENT`)
is launched with `-Dlending.dbPort=0` (the desk picks a free port, which the test reads back through `app.state`,
field `dbPort`, so that no port is picked and released before the launch; as built 2026-10-03) and
`-Dlending.dbPassword=<a generated value of 12 characters>` (§13.1), and its generated `credentials.yaml` has the
`jdbc:` section of §13.4, so that the source `lending` is served by `JdbcDataChannel` and not by the agent. The HSQLDB
jar is on the test class path already (the application depends on it), so `ServiceLoader` finds the driver. Against
that target, through the real `PlanExecutor`, `Binder` and `AgentClient`:

1. the pack's pre-flight `lending.database` passes, through `PreflightContext.data()` over JDBC (asserted since
   2026-10-03: exactly one `preflight` record with `ok: true`, and no `resolver` record);
2. `live` resolves the slots and oracles to the seed A values of §13.4; every recorded statement has channel
   `jdbc` and otherwise equals the committed one, and `approve` into a temporary root writes the committed `seed-a`
   file's bytes with `"channel": "jdbc"` as the only difference (the fingerprint is the same: `reader` sees the same
   `information_schema` rows and the same `desk_setting`);
3. the committed plan runs in `replay` with `--env seed-a` and the committed bindings (`replay` does not compare
   provenance): `PASS`; every `db` record of `run.jsonl` has `channel: jdbc`; the three stored-row checks read the
   rows the application wrote, matched by position against the upper-case names the driver reports; no sleeper
   call, no `resolver` record;
4. the account is the boundary: an `INSERT` sent through the channel is refused by `StatementGuard` before a
   connection is used (`GUARD`), and an `UPDATE` run by the test on its own connection as `reader` is refused by the
   database with a privilege error, while the same statement as the application's own user would succeed (not run)
   (end to end since 2026-10-03: `GUARD` with the real credentials and with a wrong password, which shows the guard
   runs before any connection; the reader's `UPDATE` on the test's own connection ends with SQL state `42501` and
   the data is unchanged; headless, `LendingDbServerTest` has the account refused 17 write and administration
   statements that are valid for the administrator);
5. the listener answers on `127.0.0.1` only (a connect to the host's non-loopback address is refused) (end to end
   since 2026-10-03; the check skips with a reason on a host that has no non-loopback IPv4 address);
6. secrets: the database password is 12 characters, so the substring rule applies: it occurs in no file under the
   output directories, in no `RunResult`, in no exception message and in no line of `agent.log`; `jvm.properties`
   reports `lending.dbPassword` as `<redacted>` and `jvm.info` lists `-Dlending.dbPassword=<redacted>` (built
   2026-10-03; the scan also covers the target's own log, its discovery directory and the launcher's output, and a
   failure message names the place, never the value);
7. through the assembled CLI launcher, `skillroy run --mode replay --env seed-a --credentials <file> --pack <the
   HSQLDB jar>` against that target exits 0 and prints `model calls: 0`, which is the end-to-end proof that
   `--pack` accepts a jar without a `DomainPack` and that the driver is found through the context class loader
   (§7.2); the same command without the `--pack` jar on a launcher whose `lib/` has no driver ends `ERROR` /
   `ENVIRONMENT` with `UNAVAILABLE` for source `lending` and exits 1. (End to end since 2026-10-03, with two `--pack`
   jars, the lending pack and the HSQLDB jar, since the pack is needed to compile and the driver to connect; the
   test first asserts that the launcher's `lib/` provides no `java.sql.Driver`, so the second half runs on the same
   launcher, and ends in the pre-flight with `ENVIRONMENT … (UNAVAILABLE) … source 'lending'` and nothing sent.)

This block runs on the Java 8 target and on the Java 25 target, once each; the per-mode and per-seed matrix above
stays on the agent channel, which is what the sample's deployment has.

**Exit criteria of Phase 2:** §14.1 green in the normal build; §14.2 green in every load mode, on both seeds, on a
Java 8 target and a Java 25 target, the JDBC block included, twice in a row without a flake. **Met on 2026-10-02**
on the integration tree: green natively and in the Linux image, twice in a row on the Java 8 target and on the
Java 25 target, with the checks as built on that day (the JDBC block's items 4-7 and the Q11 and A34 sentences of
items 7 and 10 were then proven headless or by Phase 1 checks only; the review of 2026-10-03 added them end to end).
The matrix of the merged main tree, before and after the review's fixes, is recorded in `../TODO.md`.

---

## 15. Open SPI and DSL gaps: what Phase 2 takes

Numbers are those of the table "Open SPI and DSL gaps" in `../TODO.md`.

| # | Gap | In Phase 2 |
|---|---|---|
| 4 | Fixture secrets written in clear | **Closed for good**: fixtures cannot hold secrets; `credentials.yaml` and the credential join (§3.1, §4, §8.4) |
| 20 | Expectations relative to an earlier step | **Partly**: a slot captured from an event (§3.4). Capturing a *fact* and asserting a delta against it is deferred |
| 25 | Expressions over slot values | **Decided**: no expression form; computed values are oracles, margins are query arguments (§3.2) |
| 27 | Masking of rows read over the controller-side JDBC channel | **Closed**: `RowMask` (§7.3) |
| 30 | `AgentConnection.await` cannot report a JSON-RPC error | already amendment A31 (1.x, built 2026-10-02) |
| 9 | One application lane | deferred: scenario A's reads are short |
| 10 | A thread policy for the engine thread at a quiescent point | deferred |
| 11 | Query timeout is advisory, no cancellation | deferred; the JDBC channel does enforce `setQueryTimeout` |
| 12 | Positional sources | **partly**: `db.*` checks match columns by position and no longer need names (§7.4); reading such a source by name stays deferred |
| 13 | Session-scoped data reads | deferred; related limit recorded: verifiers run before sign-on (§6.4) |
| 18 | Semantic-input result contract | deferred |
| 19 | Conditional or optional planned calls | deferred; scenario A's prompt sequence is fixed |
| 21 | Event expectations | deferred |
| 22 | Rung ladders for pack verbs | deferred |
| 24 | Scenario-level preconditions on application configuration | deferred; a query that nothing satisfies already reports an unsuitable environment as `RESOLUTION_FAILED`; `PreflightContext` (§6.1) gives a pre-flight the data channel |
| 28 | Pack-defined artifact kinds | deferred |
| 26 | Oracle cross-check outcomes | Phase 3, with `crossCheck` (Phase 3, built 2026-10-03: a guarded cross-check that cannot run makes `DEGRADED` the best outcome, P3 §6.2, D38) |

---

## 16. Decisions taken in this contract

| # | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D1 | Resolution SPI (`…scenario.resolve`) and deterministic engine (`…scenario`) in `skillroy-scenario`; new `skillroy-data` below it, which also takes the strict YAML reader and the YAML dependency; `skillroy-inference` created in Phase 5 above it | the model in `skillroy-inference` now (as the overview's table says); one more module `skillroy-resolution`; a second YAML reader for credentials | `DomainPack` must reference the SPI and the inference module needs the scenario module: the first alternative is a cycle. A separate resolution module would hold types that only the scenario module uses. `skillroy-data` keeps `java.sql` code and its test database out of the scenario module; one YAML reader keeps credentials and scenarios strict in the same way |
| D2 | Guard, value conversion and JDBC read path move to `skillroy-protocol` | copy them into `skillroy-data`; make `skillroy-data` depend on the agent's data module | "either channel" is only true if both use the same code; a copy drifts; the agent module is not a controller dependency and is Java 8 target code |
| D3 | A resolver returns ordered candidates; the engine verifies and builds the `Resolution` | the overview's `Optional<Resolution<T>> resolve(…)` | the gate cannot be skipped by a resolver; "first candidate the application accepts" is defined and recorded with the rejected ones |
| D4 | Every query slot must have a verifier (compile error otherwise) | optional verification; `verify: none` | "the verifier is the gate regardless of tier" would otherwise be a convention |
| D5 | No backtracking across slots; first verified candidate wins. The cost is stated: a failure means "no answer with the earlier slots fixed", and the detail names those slots (`fixedUpstream`) | search for any consistent assignment | predictability for the reviewer; determinism of the bindings file; joint choices belong in the queries, most constrained slot first |
| D6 | No expression language; oracles and query arguments instead | a small arithmetic form (`abs(...)`, `* 1.1`) | keeps expected-value computation in committed, tested code |
| D7 | Bindings are tied to `resolveHash` (slots, oracles, graph; **not** the pack version), `planHash` and `packVersion` kept as information | compare the whole `planHash`, as the overview's fingerprint sentence implies | editing an expectation, adding a step or releasing a pack version must not invalidate every environment's bindings; too strict a key trains people to re-approve without reading. Real changes are caught by the verifiers and by `ORACLE_CHANGED` |
| D8 | The fingerprint has named components stored beside the hash, and contains no plan, no pack version and no transactional data | hash only; plan hash or pack version inside the fingerprint | a mismatch must say what changed; plan changes are D7's matter; the pack is not a property of the environment; data drift is the verifiers' matter |
| D9 | After a fingerprint mismatch no verifier runs | run verifiers anyway and report both | one clear reason; verifier evidence against a foreign environment is noise |
| D10 | Failure in `replay` is `REBIND_NEEDED`; the same situation in `live`/`approve` is `RESOLUTION_FAILED` (guards included) | a guard failure as `ASSERTION`; a third kind | neither is a verdict on the application; the kind tells the reader whether to re-approve or to change the environment or scenario |
| D11 | Secrets reach a slot by a `credential` join that exists only in memory; fixtures may not hold secrets | a `$secret.…` reference namespace; keeping fixture passwords with masking | pack verbs keep writing `<slot>.password`; a nested reference syntax is avoided; a plan can be committed without review for secrets |
| D12 | A missing credential in `replay` is `ENVIRONMENT`; in `live` it rejects the candidate | `REBIND_NEEDED` in `replay` | the binding is still correct; the credentials file is what has to be fixed |
| D13 | The bindings file holds no timestamp, row sample or verifier evidence | the overview's fuller shape | a stable, reviewable diff without personal data; the evidence is in the run's outputs and on the approval screen |
| D14 | `approve` asks once per scenario, through an `Approver`; the CLI needs a terminal or `--yes`; JUnit never prompts | a prompt per slot; a prompt read from standard input in any context | scriptable tests; an unattended job can never hang on a question |
| D15 | `live` writes nothing unless `--write-bindings`, and then marks `approval: NOT_REQUIRED` (tier (a) only) | `live` always writes; `live` never writes | "resolves on demand" for a developer, with an explicit way to keep the result |
| D16 | Routing by `credentials.yaml`: a source named under `jdbc:` is controller-side, every other is the agent's. Scenarios do not `require` the agent's data capability, and a pre-flight reads through `PreflightContext.data()` | the pack declares the channel per source; a scenario key | the same pack and scenario must work whether an environment's database is inside the target or beside it; that is an environment fact |
| D17 | `db.*` SQL is generated at compile time from pack-declared tables and written into the plan | free SQL in the scenario; SQL built at run time | scenario text never becomes SQL; the reviewer sees the statement in the plan diff |
| D18 | The compiler always writes plan version 2; the reader accepts 1 and 2 | write the lowest version that can express the plan | one writer, one format; nothing has been released on version 1 |
| D19 | A non-empty ledger in `replay` is `ERROR` / `ENVIRONMENT`; checked per run, by the JUnit stream and printed by the CLI | a new failure kind; a check in CI scripts only | no taxonomy change for a condition Phase 2 cannot even produce; the assertion lives in the framework, where Phase 5 cannot forget it |
| D20 | The sample gains two read-only primitives (`lending.deposit`, `lending.limits`) and no change to the application's behaviour; `ActionKey` is a Java resolver verified by `swing.find`. (The answers of 2026-10-02 add one field to an existing primitive, D35, and a listener and a table rename to the application, D34 and D36; none changes what the desk does) | verify `SystemLimit` by re-reading the table; skip `ActionKey` | a verifier asks the application, not the database again; `ActionKey` exercises the non-SQL tier (a) path |
| D21 | The interactive single-call entry is reserved by type name only (`ExecutorSession`, `StepJournal`), not built and without a factory signature; `ResolutionEngine` is public, with `resolve`, `evaluate`, `fingerprint` and `bindings()` | build `ExecutorSession` now; fix `session(Plan, Bindings)` | scenario A does not need it; an interactive session has no plan; the engine entry costs nothing because the binder is built on it |
| D22 | The fingerprint is computed per bind, without a cache; every fingerprint failure is reported per scenario. Only a credentials file that cannot be loaded fails the `environment` testcase, before the agent is contacted | all fingerprint failures on the `environment` testcase; a cache per `BindOptions` with a 60 s rule | bindings files are per scenario and go stale independently; a cache not keyed by the target's identity is wrong after a relaunch, and the reads are cheap |
| D23 | Statement parameters are `String`, `Long`, `Boolean` or null on both channels; decimals travel as plain strings and the committed SQL casts | bind whatever Java type the resolver has; allow `Double` | the wire turns a decimal into a `Double`, so the two channels would compare different types, and a bindings file would not read back as written |
| D24 | A plan names roles (query kind, verifier id, oracle function), never resolver ids; resolvers are chosen from the running pack at bind time; a catalogue can be built on another and replace by id | resolver ids and defaulted strategies written into the plan | a variant pack must be able to answer a kind with other code without recompiling the base's scenarios; the bindings file already records which resolver answered |
| D25 | Tiers are a ladder gated on the verifier: the next resolver is asked when no candidate of the previous one passes | stop at the first resolver that returns any candidate | it is what the design overview says, and deciding it in Phase 5 would change outcomes and the provenance format then |
| D26 | `SecretMask` masks by value only; the bindings file is never masked, and its writer refuses a text the mask would change | a mask that also redacts by key; masking the bindings file | the key rule matches this contract's own fields (`credential`, `inputTokens`); a masked value in a bindings file would be replayed as the value |
| D27 | Joined secret fields are added after the graph walk and resolve only in the arguments of step and cleanup calls | secrets usable anywhere a reference is | otherwise a password could reach a resolver, an SQL parameter, a hash (a short value under a hash is guessable) or an oracle value in a committed file |
| D28 | `db.*` columns are declared as `StoredColumn(name, sql, kind, paramSql)` and matched to the result by position | physical identifiers only, matched by reported name | drivers report names in their own case; a guard-forbidden identifier must be quotable; a variant schema should not change scenarios; typed parameters need a kind |
| D29 | The JDBC channel finds its driver through the context class loader, calls `Driver.connect` itself with a bounded wait, and keeps one connection per source | `DriverManager`; a connection per call | `DriverManager` cannot see a driver in a `--pack` jar and its login timeout is JVM-wide; a retried `db` check must not open a connection every poll |
| D30 | After a failed bind neither cleanup calls nor `recover` are sent; extended by Q11 to every failure before the start mark (`requires`, launch properties, pre-flight, bind, served sources) | Phase 1 behaviour (cleanup "whatever the outcome"); `recover` still after a failed pre-flight | "a bind leaves the target as it found it" must hold by construction, not because the sample happens to reject a sign-off when signed off; one rule for everything before the mark is simpler, and a dirty desk is the earlier scenario's `recover` to fix |
| D31 | One bindings root per invocation; default `bindings` in the parent of the first argument's directory | a root beside each scenario or plan file | YAML and plan live in sibling directories and must find the same files; credentials are located once from that root |
| D32 | A truncated candidate statement is allowed and recorded; a statement that must be whole goes through `requireComplete`; a truncated fingerprint statement is an error | fail every truncated statement | with a total order the first 200 rows are a deterministic prefix; failing them makes resolvers unusable on real tables |

Decisions from the project lead's answers of 2026-10-02 (§17; confirmed by the owner on 2026-10-06, with Q10 and Q14
vetoed as their rows record):

| # | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D33 | A34: a request's `_meta.secretParams` names the top-level params whose values the core replaces by `<redacted>` wherever it logs, traces or reports the call, never in what the primitive receives; the executor sets it for a secret argument (§11.4) | the Phase 1 key rule alone; a redaction of every `value` param; a redaction list in the options file | a secret under a neutral key (`lending.input`'s `value`) is invisible to the key rule; redacting every `value` would blind diagnostics of ordinary inputs; only the caller knows which argument is secret, per call |
| D34 | The sample serves its database on a loopback port when `lending.dbPort` is set, with a read-only user whose password comes from `lending.dbPassword`; the end-to-end acceptance runs scenario A over the controller-side JDBC channel once per target Java (§13.1, §14.2) | JDBC covered by headless tests only (§14.1 items 2, 3, 8); a listener owned by the framework | the stronger proof is wanted: the sample's deployment keeps its database in the target's memory, so without the listener the controller-side channel never meets a launched target; a listener in the framework would be a production surface the base must not have |
| D35 | `lending.access.check` answers `autoSignOn`, and the staff verifier checks it under `interactiveSignOn: true` (§13.2, §13.3) | leave `interactiveSignOn` unverified; a new primitive `lending.staff` | every condition of a query should be verified by the application; one field on the decision primitive the verifier already calls costs less than a primitive |
| D36 | The sample's limit table is `role_cap` from the start of Phase 2, in code, seeds, tests and documents; the old name appears nowhere in any case (§13) | quote the identifier in the fingerprinter's lookup; add the old name's upper-case form to the neutrality check's allow-list | the catalogue lookup needs the upper-case literal, which the project's neutrality check rejects for the old name; a sample must not need an exception in that check |

### 16.1 Changes to the Phase 1 contract that Phase 2 needs

Written into `phase1-contracts.md` on 2026-10-02 before the code, each passage marked "(Phase 2, specified
2026-10-02, not yet built)" at the time; since the as-built pass each is marked "(Phase 2, built 2026-10-02)" and
states what the code does:

| P1 section | Change |
|---|---|
| §8.5 run sequence | cleanup calls and `recover` run only if the start mark was written; **nothing is sent to the target for any failure before it**: an unmet `requires`, a launch-property mismatch, a failed pre-flight, a failed bind, an unserved `db` source (D30, Q11). In Phase 1, cleanup ran "whatever the outcome and including failures before any step" |
| §8.1, §8.2 examples; §9.3 `signOn` row | a fixture key that looks secret is a compile error; examples and the verb show the `credential` form; the mapping form `{code, password}` of `signOn` is removed |
| §8.3 pack SPI | `CompileContext.slot`, `PreflightCheck.check(PreflightContext)`, `PlannedCall.capture` (all additive, with defaults) |
| §8.4 | `ResolutionException` gains a reason code; `Expectation` implements `Check`; the fact root `db` is reserved |
| §8.6 CLI | `--pack <jar>` accepts a jar without a `DomainPack` (a JDBC driver) |
| §1 module table, §8.1 | the strict YAML reader lives in `skillroy-data` as `StrictYaml`; `skillroy-protocol` gains `…protocol.sql` |
| §3 protocol, §5 "Dispatch" and "Secret redaction", §7 `CallOptions`, §8.5 `run.jsonl` | A34 `secretParams` (D33, §11.4): the `_meta` field, the core's replacement rule in log, trace and error data, the controller's `CallOptions.secretParams`, the `call` record's `secretParams` |
| §9.1 database, §9.2 primitive table | the limit table is `role_cap` (D36); `lending.access.check` answers `autoSignOn` (D35) |

The design overview (`base-framework-design.md`) was corrected the same day, each change marked "(Phase 2
contract, specified 2026-10-02)", since the as-built pass "(Phase 2 contract, built 2026-10-02)": the module table
of §3.1 (D1), the resolver signature and `Resolution` shape of §3.3 (D3, and the list in §6.3 here), and the
wording of scenario A's portability claim in §4.1 (D5).

---

## 17. Questions put to the project lead: answered 2026-10-02

The questions of the second draft are kept for history, each with the answer the project lead gave on 2026-10-02
(confirmed by the owner on 2026-10-06; Q10 and Q14 vetoed as their rows record) and the section where the answer now
lives. Every answer is built (2026-10-02). Two additions came with the answers: the rename of the sample's limit table
(D36, §13) and the request to correct the design overview together with the Phase 2 code (§16.1, last paragraph).

| # | Question (second draft) | Answer (2026-10-02) | Written into |
|---|---|---|---|
| Q1 | **Plan version and 1.x.** A29 adds the key `ui` to `plan.json` without saying whether `planVersion` changes. If 1.x takes version 2 for it, Phase 2 is version 3 | 1.x did not bump it: `Plan.VERSION` is 1 in the code after the 1.x merge. Phase 2 plans are version 2 | §5 |
| Q2 | **Should `replay` insist on approved bindings?** A file written by `live --write-bindings` carries `NOT_REQUIRED` and is replayed like an approved one, because tier (a) is accepted on the verifier alone | Accepted as written: `replay` accepts tier (a) bindings marked `NOT_REQUIRED`. No approval switch in Phase 2 | §9 |
| Q3 | **A secret under a neutral key on the wire.** The sign-on password is the `value` of a `lending.input` call. The controller masks it everywhere it writes; the agent's own log and trace redact by key name only. Proposal: a request-level list `secretParams: ["value"]` (protocol 0.2 is not released, so it is cheap now) that the core honours in its log, trace and error data | **Yes, build it**, as amendment A34: `_meta.secretParams: ["<param name>", …]`; the core replaces the values of those top-level params by `<redacted>` wherever it logs, traces or reports the call (agent log, the `trace`/event payloads it stamps, error `data`), never in what the primitive receives. The executor sets it for arguments that came from a joined secret field. The agent-core owner implements it in the Phase 2 wave | §1, §11.2, §11.3, §11.4, §14.1 item 12, §14.2 item 10, D33, §16.1; P1 §3, §5, §7, §8.5 |
| Q4 | **End-to-end coverage of the JDBC channel.** The sample's database is in the target's memory, so §14.2 covers the agent channel only and §14.1 items 2, 3 and 8 cover JDBC. An option that makes the sample also serve its database on a loopback port would let §14.2 run scenario A's `db.*` checks over both channels, at the cost of a behaviour the sample does not have today | **Yes**: the sample gets `lending.dbPort=<port>` (default: not set, no listener). When set it also serves its database on `127.0.0.1:<port>` through the embedded database's own server class, with a read-only user for the controller-side channel. The end-to-end acceptance launches one target per JVM version with it and runs scenario A's stored-row checks over the controller-side JDBC channel as well. The listener is the sample's, not the framework's, and binds loopback only | §13 intro, §13.1, §13.4, §14.1 item 8, §14.2 (the JDBC block), D34 |
| Q5 | *Closed in the second draft (D25).* Tier order: the next resolver is asked when no candidate of the previous one passes | decided | §8.3 |
| Q6 | **Who is `approval.by`?** A free-text label avoids putting a person's name into a committed file by default | A free-text label, default the mode name | §9 |
| Q7 | **`credentials.yaml` also names JDBC sources**, which mixes secrets with a little non-secret configuration (URL, timeout) in one git-ignored file | One file (`credentials.yaml`) | §4 |
| Q8 | *Closed in the second draft.* Schema checksum: the sample hashes the columns of the tables the pack reads, one statement per table (§13.3). A pack for a real target should do the same over its own allow-list | decided | §13.3 |
| Q9 | **`ENVIRONMENT` for a model call in `replay`.** A dedicated kind (`POLICY_VIOLATION`) would read better in reports once Phase 5 exists | No new failure kind | §10 |
| Q10 | **Staff names in values.** The sample's staff value carries a code and no name, to keep personal data out of committed bindings. Scenario B's slip assertions may want the name | Staff names stay out of the sample's binding values; the base does not restrict a pack's value types. **Owner, 2026-10-06:** vetoed as a rule for every pack; kept as the sample's practice and as the recommendation for packs whose test accounts are people (§11.3) | §9, §11.3 |
| Q11 | **How far does "nothing is sent before the start mark" go?** D30 stops cleanup and `recover` after a failed bind. As built they also run after a failed `requires`, launch-property or pre-flight check. Applying the rule to every failure before the start mark is simpler and is what §16.1 writes; the argument against is that `recover` after a failed pre-flight may be what un-sticks a desk left dirty by an earlier scenario | Nothing is sent to the target for any failure before the start mark (`requires`, launch properties, pre-flight, bind) | §10, §11.2, §14.1 item 7, §14.2 item 7, D30, §16.1; P1 §8.5 |
| Q12 | **`interactiveSignOn` has no verifier.** The sample has no read-only primitive that says whether a staff member is asked for a password, so that one condition rests on the resolver's SQL. A small addition (a field `autoSignOn` in the answer of `lending.access.check`, or a primitive `lending.staff`) would close it, at the price of the "no application or primitive change beyond two" claim | **Yes**: the answer of `lending.access.check` gains `autoSignOn: bool`, and the staff verifier checks it, so the "is asked for a password" condition is verified by the application, not only by the resolver's SQL | §13.2, §13.3, §14.1 item 8, D20, D35; P1 §9.2 |
| Q13 | **Pack version difference between plan and running pack.** One review proposed a hard `ENVIRONMENT` ("recompile") whenever they differ and the plan needs bindings. This contract only logs it, as Phase 1 does, and fails on a missing kind, verifier or oracle, so that a variant pack with its own version can run the base's plans (D24) | Logged, not an error | §8.3 step 1 |
| Q14 | **Short secrets.** A secret under 8 characters is masked only as a whole value (A25's rule). The sample's four-digit passwords are therefore protected inside longer texts by construction (§11.4), not by the mask. Real environments should be told to use credentials of 8 or more characters for test accounts, or the threshold should be lowered at the cost of false redactions | As A25; documented: test accounts should use credentials of 8 or more characters. **Owner, 2026-10-06:** vetoed in favour of a threshold per environment, `masking.minLengthInText` in `credentials.yaml`, default 8 (§4, §7.3); the base never refuses a short credential | §4, §7.3, §11.4 |
| Q15 | **Provenance depends on the credentials file.** Candidates dropped for want of a credential are recorded, so two approvals agree byte for byte only when their credentials files name the same ids (§9). Leaving those drops out of the file would remove the dependency and hide why a candidate was skipped | Recorded, as the contract says | §9 |
