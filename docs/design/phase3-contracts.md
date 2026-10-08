# Phase 3 contracts: waiver rules, oracle cross-checks, stored-row aggregates, sample scenario B

**Status:** as built. Specified 2026-10-03 and built the same day (three implementers in isolated copies: the base,
the sample's agent module, the sample's pack and scenarios; integrated and verified natively and in the Linux image),
then reviewed through three lenses with adversarial verification and corrected; the passages this changed carry "(as
built 2026-10-03: …)" or a short "As built:" sentence, and the review's corrections say "reviewed and corrected".
Where the text and the code disagree, one of the two has a defect; change the contract first, then the code. What was
run, on which JDKs and with what result is in `../TODO.md`. **Scope:** Phase 3 of `base-framework-design.md`
("Roadmap": waiver rules and oracles, sample scenario B). This file is the single source of truth for Phase 3 in the
way `phase2-contracts.md` is for Phase 2: where it and the design overview disagree on a Phase 3 detail, this file
wins; change the contract first, then the code.
Passages that Phase 5 changes (`phase5-contracts.md` §16.1) were written in before its code and marked
"(Phase 5, built 2026-10-08 on provisional B23; P5 §n)"; the build of 2026-10-08 (on the provisional B23 answers, with its
own decisions B24) marked each of them "(Phase 5, built 2026-10-08 on provisional B23; P5 §n)".

**What it builds on.** The Phase 1 contract as built (with Phase 1.x) and the Phase 2 contract as built, both stated
in their own files. Phase 2 reserved the seams this phase fills: the `crossCheck` key of an oracle entry (P2 §3.1,
§6.5), binder step 8 (P2 §8.3), the failure kind `ORACLE_SUSPECT` (P2 §10), aggregates in stored-row checks and
`$env.now` (P2 §1, both "rejected with a message that names Phase 3"), and gap 26 of `../TODO.md` (oracle cross-check
outcomes). References of the form "P1 §8.5" and "P2 §8.3" mean sections of `phase1-contracts.md` and
`phase2-contracts.md`.

**Reading guide.** §1 scope; §2 modules and versions; §3 the scenario YAML additions; §4 `plan.json` version 3; §5
the Java SPI additions; §6 the binder's cross-check step and `ORACLE_SUSPECT`; §7 stored-row aggregates; §8 failures
and run outputs; §9 CLI and JUnit; §10 the sample; §11 acceptance; §12 which open gaps are taken; §13 decisions,
with the changes to the Phase 1 and Phase 2 contracts in §13.1; §14 the questions put to the project lead, answered
provisionally and confirmed by the owner on 2026-10-06.

---

## 1. Scope and non-goals

Phase 3 delivers:

- **oracle cross-checks**: the `crossCheck` key of an oracle entry, a pack-supplied `CrossCheck` that compares an
  oracle's output with the application's own computation at bind time (a dry run through a read-only domain
  primitive), run in every mode; a disagreement is `ORACLE_SUSPECT`, an `ERROR` that says the test is suspect, never
  a verdict on the application;
- **gap 26**: a cross-check guarded by a capability that the target does not have is skipped, recorded, and makes
  `DEGRADED` the best outcome of the run, as a skipped guarded expectation does (decision B14);
- **aggregates in stored-row checks**: `sum(<column>)`, `min(<column>)` and `max(<column>)` over the rows a `db.*`
  check read, computed on the controller, so that they mean the same over either channel and in every SQL dialect;
- **the base fact root `devices`**: every `DeviceAdapter` of the target with its state, so that a scenario can assert
  on a peripheral (the sample's printed slip) through the base and not only through a pack fact;
- **the decision on `$env.now`**: not provided; a date-dependent query reads the target's business date at bind time;
- **plan version 3** (the new keys) and the sample pack version `0.3.0`;
- the sample: the dry-run primitive `lending.waiver.evaluate`, the desk's business date in `app.state`, the waiver
  queries, resolvers, verifiers, oracles and cross-check of the design overview's scenario B, scenario B itself
  (`borrow-2-waive-1-slip`) on seeds A and B with one YAML file, and the demonstration that a deliberately wrong
  oracle is reported as `ORACLE_SUSPECT`.

**Non-goals.** Named so that their seams are respected, not built:

| Not in Phase 3 | Seam reserved here | Phase |
|---|---|---|
| Any model call; model-drafted oracles and their approval | `Strategy.LLM_*`, the plan field `inference`, `CostLedger` (unchanged from Phase 2: parsed, rejected, empty) | 5 |
| A cross-check that needs a model (`inference.*` as a check id) | the `inference.` prefix rule of P2 §3.3 covers cross-check ids | 5 |
| `count()` and `avg()` aggregates, aggregates in `where`, comparisons other than equality in `where`, joins | the aggregate grammar of §3.2 is a closed list; a key that looks like a function call and is not in it is a compile error that names the list | later |
| Capturing a fact into a slot and asserting a delta (the rest of gap 20) | unchanged | later |
| A `DeviceAdapter` action from a scenario (`device.act`) | the `devices` root is read-only; a pack verb may call `device.act` | later |
| Interactive sessions (`ExecutorSession`, `StepJournal`) | reserved as in P2 §11.5 | 5, 7 |

Phase 3 adds **no new module and no new third-party artifact**. Everything lands in `skillroy-scenario`,
`skillroy-cli`, `skillroy-junit` (a message), the sample's agent module, pack and scenarios module.

---

## 2. Module layout, versions and build

- No new module. The dependency graph of P2 §2.1 is unchanged.
- `Plan.VERSION` becomes **3** (§4). `BindingsFile.VERSION` stays 1 (§8.3 says why).
- `LendingPack.VERSION` becomes `0.3.0`; `LendingModule.VERSION` stays `0.1.0` (a module version is the agent
  jar's, and the sample agent jar gains a primitive without a behaviour change of the existing ones; it is listed in
  `agent.info` either way).
- The sample's fingerprinter reads two more tables (§10.3), so the committed bindings files of scenario A are
  regenerated once (their `lending.schema` component changes; §10.4). This is the one-time cost P2 D8 accepts for a
  pack that starts reading a table it did not read before; it is not a schema change of the environment.
- Build conventions as P1 §1 and P2 §2.3.

---

## 3. Scenario YAML additions

The top-level keys of P2 §3 stay (thirteen). The strict YAML reading applies to every new key.

### 3.1 Cross-checks

```
oracleEntry  := { fn: <OracleFn>, args?: …, must_equal?: <value>,
                  crossCheck?: crossCheck }
crossCheck   := <checkId>                                        # shorthand for { check: <checkId> }
              | { check: <checkId>,                              # a CrossCheck the pack declares for this fn (§5.1)
                  tolerance?: <decimal>,                         # passed to the check; default none (exact)
                  capability?: <capabilityId> }                  # a guarded check (§6.2); default: unguarded
```

Rules:

1. `check` names a `CrossCheck` the compiling pack declares **for the oracle's `fn`**
   (`ResolutionCatalogue.crossCheck(id)` whose `oracleFn()` is that fn). Otherwise a compile error: "no cross-check
   lending.waiver.evaluate for oracle ExpectedWaiver; the pack offers: …".
2. `tolerance` is a decimal scalar, zero or more, written as a string or a number and normalised to its plain decimal
   string in the plan (`"0.00"`; as built the YAML reader turns an unquoted `0.00` into `0.0`, so a scenario quotes
   the tolerance to keep its scale; the plan reader requires the form `\d+(\.\d+)?`). Its meaning belongs to the check
   (§5.1); the engine passes it through unchanged. A check that is given a tolerance it cannot use fails the compile
   through `CrossCheck.acceptsTolerance()` (default `false`): "cross-check … takes no tolerance".
3. **Unguarded** (no `capability`): the check's `requires()` are added to `plan.requires` (sorted, deduplicated), as a
   verifier's are (P2 §5). A target without them is `PRECONDITION_FAILED` before the bind.
4. **Guarded** (`capability: <id>`): nothing is added to `plan.requires`. At bind time the check runs only when that
   capability's state is `OK` on the target; otherwise it is skipped and recorded, and `DEGRADED` becomes the best
   outcome of the run (§6.2). Use this only for a dry run whose availability genuinely differs between targets or
   builds (B14's rule); the sample's cross-check is unguarded.
5. An oracle may have at most one cross-check. A cross-check is not a verifier: it has no query and no candidate; it
   compares one computed value with one computation of the application (P2 §6.5).
6. `crossCheck` is an edge-free key: it adds nothing to the resolution graph. The check runs after the oracle is
   computed and before its guard (`must_equal`), in every mode (§6.1).

### 3.2 Aggregates in stored-row checks

```
rowCheck     := { where: { <column>: <scalar>, … },
                  rows?: <count> | matcher,                       # default 1, as Phase 2
                  capability?: <capabilityId>,
                  <column>: matcher, …,                           # per-row matchers, as Phase 2
                  <aggregate>: matcher, … }                       # new: over all rows read
aggregate    := sum(<column>) | min(<column>) | max(<column>)     # column: INTEGER or DECIMAL; no spaces inside
```

Rules:

1. An aggregate key is recognised by its form `<fn>(<column>)`. `fn` must be one of `sum`, `min`, `max`; anything
   else that has the form of a function call (`count()`, `avg(amount)`, `SUM(amount)`) is a compile error that
   lists the three. The column must be one the pack lists for the table, of kind `INTEGER` or `DECIMAL`; a `TEXT`,
   `BOOLEAN` or `TIMESTAMP` column is a compile error ("sum(rule_code): rule_code is TEXT; aggregates take INTEGER
   or DECIMAL columns").
2. Aggregates are computed **on the controller over the rows the check's statement returned**, after the row cap:
   a `truncated` result fails the check as in Phase 2 ("more than 200 rows match; narrow the where"), so an
   aggregate never silently covers a part of the table. `null` cells are ignored, as SQL aggregates ignore them;
   over zero rows, or when every cell is null, an aggregate is `null`. `sum` of `DECIMAL` is exact (`BigDecimal`);
   `sum` of `INTEGER` is a whole number.
3. The matcher is normalised and compared as P1 §8.4 (numeric equality, so `"0.99"` equals `0.99`). References are
   substituted as for per-row matchers, and checked at bind time like them (`Binder.check`, P2 §8.3 step 10; reviewed
   and corrected 2026-10-03: the build of the day checked a `db` element's params, `expect` and `rows` but not its
   aggregates, so a bad reference there passed the bind and failed after the steps ran, or ended `DEGRADED` under a
   guard on an absent capability, and `approve` wrote a bindings file for such a plan).
4. `rows`, `where`, `capability` and the per-row matchers keep their Phase 2 meaning; the default of `rows` stays
   `{equals: 1}` whether or not aggregates are present.
5. Reason for computing on the controller rather than in SQL: both channels return the same rows (P2 §2.2), so the
   same arithmetic over them means the same on either channel and in every dialect; the statement in the plan stays
   the one reviewers already read; and the row cap already bounds the work. A pack that needs a database-side
   aggregate over an unbounded table writes a query kind for it.

### 3.3 `$env.now`: not provided (decision D39)

`$env` keeps the one field `id`. `$env.now` stays a compile error; its message no longer names a later phase:
"$env has the field id only; a query that depends on the date reads the target's business date at bind time (see the
pack's documentation)". Reason: a date in a query's `args` would enter `argsHash` and change every day, so every
`replay` would report `ARGS_CHANGED`; and the controller's clock is not the target's business date. A pack whose
query depends on the date reads that date from the target inside its resolver (the sample reads `app.state`, §10.3),
records the read in provenance (`Provenance.Call`), and its verifier re-checks the condition in every mode, so that a
binding that has fallen out of its date window is reported as `REBIND_NEEDED` / `VERIFIER` with the application's
own reason.

### 3.4 The base fact root `devices`

The base fact roots of P1 §8.3 (`app`, `ui`, `dialogs`, `caps`) gain **`devices`**: a list, sorted by device id, of
`{id, kind, actions: [string], state: {…}}`, built from `device.list` and one `device.state {id}` per device (both
core primitives, `APP` thread for the state, P1 §5). An expectation path selects a device with a filter:
`"devices[id=printer].state.last.text": { contains: $rule.code }`. A pack whose own fact root is named `devices`
takes precedence, as for every base root (P1 §8.3). Reading the root costs one call per device plus one; a scenario
that does not name it pays nothing. `device.list` exists on every agent, so the root needs no guard; a device the
target lacks is an empty filter match and fails the expectation as any other.

### 3.5 Captures from print events

Nothing new in the grammar: the Phase 2 `capture` (P2 §3.4) on `waitFor` applies to a print event. The pattern,
written here because scenario B is its first use: `waitFor: { topic: slip.printed, within: 10s, capture: { slot:
slip, fields: { loanId: loanId } } }` ties the run to the printed slip, and `verify` asserts the rendered text
through the pack fact (`slip.last.text`) and through the base root (`devices[id=printer].state.last.text`) against
the same loan id (`slip.last.loanId: $slip.loanId`). Two `waitFor` steps that follow one input both observe events
published since that input: the executor's `since` is the `seq.before` of the last call that was not a wait (P1
§8.3, A32), so `loan.posted` and `slip.printed` can be captured one after the other.

---

## 4. `plan.json` version 3

`planVersion` becomes **3**: two new keys appear in a plan (`crossCheck` on an oracle, `aggregates` on a `db`
element), and an executor that knows only version 2 would run such a plan without the cross-check and without the
aggregate, which is "executed wrongly" by P2 §5's version rule.

```json
{ "planVersion": 3, "scenarioId": "borrow-2-waive-1-slip", "pack": "lending", "packVersion": "0.3.0", "…": "…",
  "oracles": [
    {"name":"needed","fn":"LoanFromTrigger","args":{"item":"$item","rule":"$rule"}},
    {"name":"waiver","fn":"ExpectedWaiver","args":{"lines":["$oracle.needed"],"rule":"$rule"},
     "crossCheck":{"check":"lending.waiver.evaluate","tolerance":"0.00"}},
    {"name":"due","fn":"ExpectedTotals","args":{"lines":["$oracle.needed"],"waivers":["$oracle.waiver.amount"]}} ],
  "verify": [
    {"db":"loan_waiver","source":"lending",
     "sql":"SELECT loan_id, rule_code, amount FROM loan_waiver WHERE loan_id = ? ORDER BY loan_id, rule_code",
     "params":[{"kind":"INTEGER","value":"$loan.id"}],
     "rows":{"equals":1},
     "expect":{"rule_code":{"equals":"$rule.code"}},
     "aggregates":{"sum(amount)":{"equals":"$oracle.waiver.amount"}}} ] }
```

| Key | Rule |
|---|---|
| `oracles[].crossCheck` | `{check, tolerance?, capability?}`; present **only when the scenario wrote it**; `tolerance` as a plain decimal string; key order `check`, `tolerance`, `capability` |
| a `db` element's `aggregates` | aggregate key -> matcher in normal form, keys sorted; present only when the scenario wrote at least one aggregate; written after `expect` and before `capability` |
| `requires` | as Phase 2, plus the `requires()` of every **unguarded** cross-check |
| key order | the Phase 2 order; `crossCheck` is the last key of an oracle entry |

**Version rules.** The compiler always writes version 3 (Phase 4, built 2026-10-07; P4 §7.3, D58: version 4
supersedes 3 as the written version; the reader accepts 1 to 4, reads a version 3 plan as version 4 without `web`
and refuses `web` below version 4). The reader accepts versions 1, 2 and 3; a version 1 or 2
plan is read as version 3 without cross-checks and without aggregates (as built a version 2 plan that carries one of
the new keys is refused: "'crossCheck' needs a version 3 plan", "'aggregates' needs a version 3 plan"); any other
version is refused ("planVersion N is not supported (expected 1, 2 or 3)"). A test pins the `resolveHash` of a
version 2 plan as the Phase 2 code computed it. The rule "a
fixture field whose key looks secret is refused" (P2 §3.1 rule 5) applies to version 2 **and later**; version 1
relies on A25. The sample's committed plans are regenerated (all of them: `planVersion` and `packVersion` change).

(Phase 5, built 2026-10-08 on provisional B23; P5 §13.2) Version 5 (D81) supersedes 4 as the written version, as 4
superseded 3; the reader accepts 1 to 5.

**`resolveHash`** covers `{slots (fixture and query), oracles, resolutionGraph}` as P2 §5. An oracle entry's
`crossCheck` is part of `oracles` and therefore of the hash **when it is written**; an entry without one is written
exactly as in Phase 2, so the `resolveHash` of every Phase 2 scenario is unchanged by this version. `aggregates` are
part of `verify`, not of the hash. Reason for including the cross-check in the hash: adding a cross-check to an
oracle changes what a binding was accepted under, and a reviewer should re-approve.

### 4.1 Java shapes

```java
public record Plan(/* … Phase 2 components … */) {
    public static final int VERSION = 3;                        // reads 1, 2 and 3
    // (Phase 4, built 2026-10-07; P4 §7.3: VERSION 4, reads 1 to 4; Plan and Scenario gain a last component
    // WebSettings web, null when the YAML has no web key (effectiveWeb() then answers WebSettings.DEFAULT), and keep
    // the Phase 3 constructors)
    // (Phase 5, built 2026-10-08 on provisional B23; P5 §13.2: VERSION 5, reads 1 to 5)
    public record Oracle(String name, String fn, Object args, Object mustEqual, boolean hasMustEqual,
                         CrossCheckSpec crossCheck /*nullable*/) {
        public Oracle(String name, String fn, Object args, Object mustEqual, boolean hasMustEqual); // Phase 2: null
    }
    /** check: the CrossCheck id; tolerance: plain decimal string or null; capability: null = unguarded. */
    public record CrossCheckSpec(String check, String tolerance, String capability) { }
}
public record RowCheck(String table, String source, String sql, List<Param> params, Object rows,
                       Map<String,Object> expect, Map<String,Object> aggregates /*never null; may be empty*/,
                       String capability) implements Check {
    public RowCheck(String table, String source, String sql, List<Param> params, Object rows,
                    Map<String,Object> expect, String capability);          // Phase 2: no aggregates
    /** An aggregate key, parsed: fn is sum, min or max. */
    public record Aggregate(String fn, String column) {
        public static Aggregate parse(String key);     // null when key has not the form fn(column); lower case only
        public String key();                            // "sum(amount)"
        // as built 2026-10-03: FUNCTIONS, FORMS and looksLikeCall(key), used for the compile error that lists the three
    }
}
```

`Scenario` (the loaded YAML) carries the `crossCheck` value of each oracle entry (as built: `Scenario.OracleEntry`
gains a trailing component `crossCheck`, the Phase 2 constructor kept) and the aggregate keys of each `db` item in
source form; the loader checks shapes (the mapping form's keys, the shorthand), the compiler checks meaning
(the check exists for the fn, the aggregate's column and kind).

---

## 5. Java SPI additions

Packages as P2 §2.1: the SPI in `ai.skillroy.testjava.scenario.resolve`, the engine in `…scenario`. Pack objects
stay stateless or thread-safe; the binder calls them on the executor's thread, one at a time.

### 5.1 `CrossCheck`

```java
/** Compares an oracle's output with the application's own computation of the same thing, at bind time.
 *  Committed Java in the pack; asks the application through read-only primitives or the data channel. */
public interface CrossCheck<I, O> {
    /** The name scenarios write in crossCheck.check, by convention the primitive asked ("lending.waiver.evaluate"). */
    String id();
    /** The oracle this check is for (Oracle.fn()). The check's I and O must equal that oracle's. */
    String oracleFn();
    /** Capability ids the check needs; the compiler adds them to plan.requires for an unguarded check (§3.1). */
    default List<String> requires() { return List.of(); }
    /** Whether the check gives a meaning to crossCheck.tolerance; false: a scenario writing one does not compile. */
    default boolean acceptsTolerance() { return false; }
    /** Codec-supported types; the defaults read them from the class's "implements CrossCheck<I, O>" declaration. */
    default Type inputType();  default Type outputType();
    /** Must not throw for "disagree": returns CrossCheckResult.disagree with a reason and the application's answer.
     *  input: the oracle's decoded input; output: the oracle's output (decoded O); tolerance: the plan's, or null. */
    CrossCheckResult check(I input, O output, BigDecimal tolerance, VerifyContext ctx)
            throws DataException, AgentRpcException, AgentLostException, InterruptedException;
}
/** actual: what the application computed, encoded (JSON-like) for the report; evidence: the calls made. */
public record CrossCheckResult(boolean agreed, String reason, Object actual, Map<String,Object> evidence) {
    public static CrossCheckResult agree(String reason, Object actual, Map<String,Object> evidence);
    public static CrossCheckResult disagree(String reason, Object actual, Map<String,Object> evidence);
    // as built 2026-10-03: the factories freeze actual and evidence with keys sorted at every depth (and, after the
    // review, in JSON-like form), so that every consumer sees the same canonical value
}
```

`VerifyContext` (P2 §6.4) is reused unchanged: a cross-check has the same rights as a verifier (read-only agent view,
the data channel, the environment id, the remaining budget). It is not a `Verifier<Q,T>`: it has no query and no
candidate, and it is selected by oracle function, not by query kind.

**Rules.**

- *Read-only and bounded*, as for verifiers (P2 §6.4): the agent view refuses mutating calls; every timeout is
  clamped to the remaining budget; the budget is checked before the call. The check runs once per oracle per bind.
- *The application's own computation.* A cross-check asks the application to compute what the oracle computed (a dry
  run, a quote, a preview), through a read-only domain primitive that calls the application's own code. Re-running
  the oracle, or re-reading the rows the oracle's inputs came from, is not a cross-check.
- *Tolerance.* When the check accepts one, `tolerance` bounds the allowed difference in the check's own terms (the
  sample: an absolute difference of money); `null` means exact. A check that accepts none receives `null` always.
- *Exceptions.* A `RuntimeException`, a `DataException`, an unhandled JSON-RPC error, or a null result from the check
  is `ENVIRONMENT` ("pack defect: cross-check lending.waiver.evaluate failed: …"), as for a verifier. A disagreement
  is never an exception. As built (reviewed and corrected 2026-10-03): the engine brings `actual` and `evidence` to
  JSON form and masks them, together with the oracle's `expected` value and a skipped check's capability reason, at its
  two recording points, so that every consumer (the `crosscheck` record, the `ORACLE_SUSPECT` detail, the approval
  screen, `bindings.json`, the CLI's `--json`, `Bindings.crossChecks()`) sees masked JSON-like values; a value that
  cannot be written as JSON is `ENVIRONMENT`.
- *Purity of the oracle is unchanged*: the oracle still computes from bound values alone; only the check touches the
  target.

### 5.2 `ResolutionCatalogue`

```java
public final class ResolutionCatalogue {
    public static final class Builder {
        // … Phase 2 members …
        public Builder crossCheck(CrossCheck<?,?> check);           // registration order is irrelevant: one per id
        public Builder replace(CrossCheck<?,?> check);              // same id; IllegalStateException when none
    }
    public CrossCheck<?,?> crossCheck(String id);                   // null when none
    public List<CrossCheck<?,?>> crossChecks(String oracleFn);       // every check declared for the oracle, by id
}
```

`build()` additionally fails (reported as a compile error that names the pack) when: two cross-checks share an id;
a check's `oracleFn()` names no registered oracle; the check's `inputType()`/`outputType()` do not equal the oracle's
(compared as `java.lang.reflect.Type`s after `GenericTypes` resolution); an id is or starts with `inference`; a
check's types are not supported by the codec. A variant pack overlays a base pack's cross-check with
`builder(base).replace(check)` (P2 D24).

### 5.3 Binder, outcome and run result

```java
public record BindOutcome(Bindings bindings, BindingsFile file, EnvFingerprint env, Path written,
                          List<String> skipped /*never null; cross-checks skipped under a guard, §6.2*/) {
    public BindOutcome(Bindings bindings, BindingsFile file, EnvFingerprint env, Path written);   // Phase 2: empty
}
/** run.jsonl record types of the bind (P2 §6.6) gain: crosscheck (one per oracle with a crossCheck). */
public interface BindListener { void event(String type, Map<String,Object> fields); }
```

`RunResult` and `StepResult` are unchanged in shape: a skipped cross-check is one more line of
`RunResult.skippedExpectations` (as built the line reads "oracle waiver: cross-check lending.waiver.evaluate skipped:
capability lending.waiver.evaluate is UNAVAILABLE (reason)", the parenthesis only when the capability has a reason;
`RunResult` recognises such lines through `skippedCrossChecks()` and `skippedChecks()`, and `degradedSummary()` reads
"skipped expectations: …; skipped cross-checks: …; degraded fidelity: …"), and the run's best outcome is `DEGRADED`
under the mapping of B14. As built, the outcomes travel as `CrossCheckOutcome` records (`skippedLine`, `summary`,
`toJson`): `Bindings.crossChecks()`, a seventh component `Approver.ApprovalRequest.crossChecks` (the six-argument
constructor stays) and `PlanExecutor.runWithOutcome(Plan)` carry them to the approval screen, `bindings.json`, the
CLI and the JUnit glue. The capability states a guarded check needs come from the executor's single
`agent.capabilities` read; a `Binder.bind` called directly reads them itself, once, and only when it reaches a
guarded check.
`ResolutionEngine` (P2 §6.6) gains `public CrossCheckResult crossCheck(String oracleName)`: runs the oracle's check
on the values resolved so far (the hook for interactive use, as `evaluate` is); the binder is built on it. So that
the hook is usable through public members alone, `evaluate` gains an overload that keeps the oracle under its name:
`public Object evaluate(String name, String fn, Object args, Plan.CrossCheckSpec crossCheck)` (the two-argument
`evaluate` of Phase 2 keeps nothing by name, and `crossCheck(name)` of an oracle it does not know is an
`IllegalArgumentException`; added 2026-10-03 after the review). As built: arguments that cannot be written as JSON are
`RESOLUTION_FAILED` / `ARGS`; evaluating a name again replaces the earlier oracle and drops its cross-check outcome;
the check itself is looked up when `crossCheck(name)` runs.

### 5.4 `BaseFacts`

`BaseFacts.ROOTS` gains `devices` (§3.4): `List.of("app", "ui", "dialogs", "caps", "devices")`. The provider calls
`device.list`, then `device.state {id}` for every listed id in id order, and returns the list of `{id, kind, actions,
state}`; a `device.state` that fails with a JSON-RPC error makes the fact read fail as any fact read does (P1 §8.4).

---

## 6. The binder: step 8, `ORACLE_SUSPECT`, guarded cross-checks

### 6.1 Step 8 (every mode)

P2 §8.3 reserved step 8 between the oracle's computation (step 7 in `replay`, step 5 in `live` and `approve`) and
its guard (`must_equal`). Phase 3 fills it, **in every mode**, for an oracle whose plan entry has a `crossCheck`:

8. a. If the check is guarded (`capability`) and that capability's state on the target is not `OK` (read from the
      one `agent.capabilities` call the bind makes for `requires`; a capability the target does not declare counts
      as `UNAVAILABLE`): the check is **skipped**. The listener gets `crosscheck {oracle, check, skipped: true,
      capability, state, reason}`, `BindOutcome.skipped` gains one line, and the run's best outcome becomes
      `DEGRADED` (§6.2). Nothing else changes: the oracle's value stands.
   b. Otherwise decode the oracle's input (already decoded for `compute`) and output, and call
      `check.check(input, output, tolerance, ctx)`; the call is recorded as `crosscheck {oracle, check, agreed,
      reason, actual, evidence (masked), tolerance}`.
   c. `agreed = false` is **`ORACLE_SUSPECT`**, reason `DISAGREED`, in every mode (P2 D10 does not apply: the kind
      says "the test is suspect", and that is as true in `replay` as in `live`). Detail: `oracle`, `fn`, `check`,
      `expected` (the oracle's output, encoded), `actual` (the check's `actual`), `tolerance`, `message` (the check's
      reason), `evidence`, `inputsHash`, `mode`, `envId`, `fixedUpstream` (the query slots the oracle's args
      reference). All masked. No step runs; nothing is sent to the target afterwards (P2 §10: a failure before the
      start mark); in `approve` nothing is written; in `live --write-bindings` nothing is written.
   d. An exception from the check is `ENVIRONMENT` (§5.1).

9. The guard, as Phase 2.

As built (2026-10-03): step 1 of the bind also refuses, as `ENVIRONMENT` "… recompile" and before any pack call that
reads, a plan whose cross-check the running pack does not have ("the plan names cross-check C for oracle F, which
pack P V does not have") or whose tolerance the check no longer takes. The `crosscheck` record holds `oracle, fn,
check, tolerance`, then either `skipped: true, capability, state, reason` (the capability's reason, or "the target
does not declare it") or `agreed, reason, expected, actual, evidence`; the engine masks reason, actual and evidence
with the credentials mask before recording and `RunLog` masks again with the run's mask. `fixedUpstream` lists the
query slots the oracle's own arguments reference. The `ORACLE_SUSPECT` message reads "oracle <name> (<fn>) disagrees
with <check>: expected <expected>, the application computes <actual> (<the check's reason>)", values as compact JSON
of at most 300 characters each.

Order matters: the cross-check runs **before** `must_equal`, so a scenario whose oracle is wrong is reported as
`ORACLE_SUSPECT` and not as `GUARD` ("this environment gives no binding under which the scenario tests what it
claims"): with a wrong oracle, the guard's verdict would be meaningless. In `replay`, step 7's `ORACLE_CHANGED`
comes first: an oracle whose code changed since approval is reported as such, and a file approved with a wrong
oracle is caught by step 8 (§11.1 item 6 tests exactly that).

### 6.2 Guarded cross-checks and `DEGRADED` (gap 26)

Decision B14's rule for guarded expectations extends to cross-checks: a check that could not be made is not a
`PASS`. The executor adds every line of `BindOutcome.skipped` to `RunResult.skippedExpectations` before the start
mark, so that a run whose steps all pass ends `DEGRADED` ("every evaluated expectation held, but …", P1 §8.5 as
amended by A29), with the same mapping (`RunOptions.degradedAs`, the JUnit `<skipped>`, the CLI's `degraded:` lines
and exit code 0). The `bind` record of `run.jsonl` lists `skipped`. An unguarded cross-check cannot be skipped: its
`requires()` are in `plan.requires`, so a target without them fails before the bind.

What is deliberately **not** degraded: an oracle without a cross-check. Oracles are committed, unit-tested Java
(P2 §6.5); the cross-check is extra evidence a pack adds where the application can compute the same thing. Gap
26's other case, an oracle calibrated from a first real run, belongs to Phase 5, where a model may draft one (Phase 5, built 2026-10-08 on provisional B23; P5 §12.3, D86: a model drafts no oracle in Phase 5; an observed value exported from a session is an ordinary expectation marked in the YAML and listed in the export report for the reviewer, with no run-time status); a
model-drafted oracle will carry an approval state, not a cross-check.

### 6.3 Ordering and purity summary (P2 §8.5, one row added)

| Call | When | May touch the target | May throw for "no" |
|---|---|---|---|
| `CrossCheck.check` | every mode, per oracle with a `crossCheck`, after `Oracle.compute`, before the guard | read-only primitives, `DataChannel` | returns `agreed = false` |

---

## 7. Stored-row aggregates

Compilation (adds to P2 §7.4): for every aggregate key of a `db.<name>` item the compiler checks the form, the
function, the column's existence and kind (§3.2), normalises the matcher, and writes `aggregates` into the `db`
element with sorted keys. The generated `sql` is unchanged: aggregates add no column and no clause.

Execution (adds to P2 §7.4's steps):

5. After the row count and the per-row matchers hold (steps 3 and 4), compute each aggregate over the returned rows
   from the column's position: `sum` as the exact sum of the non-null cells (`BigDecimal` for `DECIMAL`, a whole
   number for `INTEGER`), `min`/`max` by numeric comparison over the non-null cells; `null` over no cells. Compare
   each with its matcher as P1 §8.4.

The check is retried with the step's expectations as before; the failure detail of a `db` check gains `aggregates`
(`{"sum(amount)": {"expected": …, "actual": …}}` for the ones that did not hold; `expected` is the substituted matcher
in normal form). As built (2026-10-03): a `DECIMAL` aggregate is the plain decimal string and an `INTEGER` one a
`Long` when it is whole, else the plain string; a non-null cell that is not a number fails the check ("row N holds a
value in column C that is not a number"); the `db` record carries `aggregates` only when they were computed; an
aggregate alone satisfies the rule that a check has at least a column matcher, an aggregate or `rows`. A `truncated`
result still fails the whole check before any aggregate is computed. Masking is as for every row read (P2 §7.2, §7.3):
over the JDBC channel the pack's `RowMask`s apply before aggregation; over the agent channel the agent's `MaskPolicy`
applies in the target, and the controller re-applies the pack's masks to provenance row samples only, not to
`db`-check cells (the review of 2026-10-03 made this explicit: a pack whose agent module lacks the twin mask
aggregates that column in clear, which P2 §7.3 asks packs to prevent by declaring the same rule on both sides). A
masked column cannot be aggregated meaningfully, which is the pack's affair (the sample masks `password`, a `TEXT`
column that cannot be aggregated anyway).

---

## 8. Failure taxonomy, status mapping and run outputs

### 8.1 Taxonomy (changes to P2 §10)

| Kind | Status | Raised by | When (reason code in `detail.reason`) |
|---|---|---|---|
| `ORACLE_SUSPECT` | `ERROR` | binder, every mode | `DISAGREED`: an oracle and its cross-check disagree (§6.1) |
| `ENVIRONMENT` | `ERROR` | binder | as Phase 2, plus: a cross-check that throws or returns null; the plan names a cross-check the running pack does not have ("the plan names cross-check … which pack lending 0.4.0 does not have; recompile") |
| `ASSERTION` | `FAIL` | executor | as Phase 2, plus: an aggregate of a `db` check that does not hold |

`ORACLE_SUSPECT` is a verdict on the **test**: either the committed oracle or the application's dry run is wrong,
and a person must look. It is not a verdict on the application and not a reason to re-approve, so it is neither
`FAIL` nor `REBIND_NEEDED`. No step has run when it is reported. JUnit XML: `<error type="ORACLE_SUSPECT">` on the
scenario's testcase, message "oracle waiver (ExpectedWaiver) disagrees with lending.waiver.evaluate: expected …,
the application computes … (<the check's reason>)" (as built: the base's wording is neutral; the pack's reason may say
"the desk"), the body the masked detail.

### 8.2 `run.jsonl`

| Record | Fields |
|---|---|
| `crosscheck` (new; one per oracle with a `crossCheck`, every mode) | `oracle`, `fn`, `check`, `tolerance`, then either `skipped: true, capability, state, reason` or `agreed, reason, expected, actual, evidence` (masked) |
| `bind` | as Phase 2, plus `skipped` (the lines of `BindOutcome.skipped`, omitted when empty) and, on failure, the `ORACLE_SUSPECT` detail |
| `db` | as Phase 2, plus `aggregates` (`{key: value}` as computed, omitted when the check has none) |

### 8.3 The bindings file

`bindingsVersion` stays **1**. The oracle entry gains one optional, informational key when the plan's entry has a
cross-check: `"crossCheck": {"check": "lending.waiver.evaluate", "tolerance": "0.00"}` (`tolerance` omitted when none;
`capability` omitted when unguarded, else written). `replay` does not compare it; it recomputes the oracle and re-runs
the check. Reason to write it at all: the file is the reviewed record of what a binding was accepted under, and "this
oracle was cross-checked against the application" is part of that. Reason not to raise the version: nothing is
released, the key is optional, and a reader that does not know it refuses the file as `UNREADABLE` (P2 §8.3), which is
safe. `BindingsFile.read` accepts the key (and refuses unknown keys inside it) and `toJsonText` writes it after
`mustEqual` (as built on `OracleValue.crossCheck`, the Phase 2 constructor kept); `BindingsDiff` reports it under
`oracles.<name>.crossCheck`, with the `{check, tolerance?, capability?}` map before and after (null when absent). The
approval screen (`approve` without `--yes`, P2 §12.1) prints each cross-check's outcome with its oracle.

`bindings.json` (the run's own, P2 §11.3): each oracle gains `crossCheck: {check, agreed}` or `{check, skipped}`.

---

## 9. CLI and JUnit

- `run`, `resolve`: no new option. `resolve` prints each oracle's cross-check line (as built "<scenario id>:
  cross-check lending.waiver.evaluate: agrees (the desk waives 0.99 for 3 units of HND-003)", before the "resolved"
  line) and, for a guarded check that was skipped, a `degraded:` line; `resolve` still exits 0 after a bind that was
  merely degraded, and writes the file. Exit code 1 of `resolve` gains "or its oracle is suspect"; exit code 1 of
  `run` already covers every `ERROR`. `run` prints skipped cross-checks as `degraded:` lines and keeps `skipped:` for
  skipped expectations.
- `--json` output of `run` and `resolve` carries the `crosscheck` records (key `crossChecks`, in the §8.2 form) and
  the `skipped` list, both keys always present (reviewed and corrected 2026-10-03: the build of the morning wrote them
  for a successful bind only, so a failed bind's records were lost).
- JUnit glue: `ORACLE_SUSPECT` maps as every `ERROR` (a `ScenarioRunException` carrying the `RunResult`); a degraded
  run aborts the dynamic test with the B14 message, which now lists skipped cross-checks beside skipped
  expectations.
- The `devices` root needs nothing in the CLI.

---

## 10. Sample additions

The application's behaviour gains nothing. The agent module gains one read-only primitive and one `app.state`
field; the pack gains the waiver queries, resolvers, verifiers, oracles and the cross-check; the scenarios module
gains scenario B, its plan and bindings, and the negative material. `LendingPack.VERSION` becomes `0.3.0`.

### 10.1 The rule the scenario tests (as the application implements it)

From `WaiverEngine` and `Engine.computeDue`, for the `DUE` key on a loan (not a return), with the business date
`d` = the desk's clock date:

1. The waiver rules effective on `d` are the active ones whose date window contains `d` (`starts_on <= d <= ends_on`,
   a null bound open), in rule-id order.
2. For each such rule of kind `BORROW_N_WAIVE_M`: the *units* are the loan's non-return lines of counted (not
   per-day) items whose code the rule covers, one unit per whole quantity. With `n` units and `group = borrow_qty +
   waived_qty`, `waived = (n / group) * waived_qty` units have their deposit waived: the units sorted by unit deposit
   ascending, then item code, then line number, and the first `waived` taken. The waiver is the sum of their unit
   deposits, at scale 2; a rule that waives no unit yields no row.
3. The loan's `total` is its subtotal (the sum of its line amounts) minus the waivers; the `loan_waiver` rows are
   written with the loan; the slip prints `SUBTOTAL`, one `WAIVER <rule code>` line per waiver and `DEPOSIT DUE`.
4. A unit covered by several effective rules counts in each of them; the seeds avoid such overlaps.

`WaiverEngine.evaluate(lines, date)` is a pure function of its arguments, and `WaiverEngine.dryRun(unitsByCode,
catalogue, date)` builds synthetic counted lines and evaluates them: that is the application's own computation the
cross-check asks for.

### 10.2 Agent module

| Primitive | Params | Result `value` / behaviour |
|---|---|---|
| `lending.waiver.evaluate` (`APP`, timeout 5000, read-only, capability of the same name probed against the running desk) | `lines: [{code: string \| barcode: string, qty: int}]` (1 to 50 lines; each exactly one of `code`, `barcode`; `qty` a whole number 1 to 999; anything else `INVALID_PARAMS` with `data.param` naming `lines[i].<field>`), `date?: string` (ISO-8601 local date; default the desk's business date) | the desk's own dry run, `WaiverEngine.dryRun` with the items looked up through `Catalogue` (a line whose item is unknown is reported, not ignored: `{found:false}` for it would hide a typo, so the whole call is `INVALID_PARAMS` with `data.param = "lines[i].code"` and `data.reason = "unknown item"`), on `date`: `{date: "2026-10-03", waivers: [{ruleCode, amount}]}` with `amount` a decimal string at scale 2 and the waivers in rule-id order; an empty list when nothing is waived |

`app.state` section `lending` gains **`today`** (beside the Phase 1 fields and Phase 2's `dbPort`): the desk's
business date, `LocalDate.now(engine.clock())` as ISO-8601 text. It is the date `DUE` evaluates the waiver rules on; a
resolver that depends on the date reads it from here (§3.3). It is **not** a fingerprint component (it changes daily;
the fingerprint identifies the environment).

Both have headless unit tests in the agent module (Java 8), against the application's real `WaiverEngine`,
`Catalogue` and seeds.

As built (2026-10-03). The dry run lives in the public class `LendingWaivers`, which refers to no class of the agent
API, so that a controller-side test connection can answer the primitive from the real `WaiverEngine` without an agent:
`static Map<String,Object> evaluate(WaiverEngine, Catalogue, Map<String,Object> rawParams, LocalDate businessDate)`
throws the primitive's `RpcException` and produces its exact answers; `MAX_LINES = 50`, `MAX_QTY = 999`. Lines of one
item add up however the item is named (a code is trimmed and matched ignoring case, a barcode trimmed and matched
exactly; a barcode given as `code` is unknown); retired items are known and counted, as `dryRun` counts them; totals
of one item may exceed 999 across lines (the work is bounded by 50 x 999 units). `INVALID_PARAMS` carries
`{param, reason}` and the message `param + " " + reason`, never a value: `lines` missing, not an array or not 1 to 50
long; `lines[i]` not an object; a field other than `code`, `barcode`, `qty` ("a line is one counted loan line …, so a
return or a number of days cannot be expressed"); both `code` and `barcode`, or neither; `code`/`barcode` not a string;
`qty` missing, not whole or outside 1 to 999 (a `Long`, an integral double or numeric text is accepted); an unknown item
(`lines[i].code` or `.barcode`, reason "unknown item"); `date` not a string or not an ISO-8601 local date (parsed
strictly, never echoed); and, beyond the specification, any other top-level key except `_meta` ("is not a parameter of
lending.waiver.evaluate: give lines and, optionally, date"), so that a misspelt `date` does not silently use the
desk's date. Check order: unknown keys; `lines` line by line (fields, code/barcode, qty, lookup); `date`. `today` is
`LocalDate.now(engine.clock())` in the clock's zone, placed after `dbPort`; the module's test pins the section's
sixteen fields. Tests: `LendingWaiversTest` (25 cases on both seeds, the dry run equal to what `DUE` waives at the
desk), `LendingModuleTest`.

### 10.3 Pack: queries, resolvers, verifiers, oracles, cross-check, fingerprinter

Query and value types (records in `LendingQueries`; money `BigDecimal` at scale 2):

```java
/** An effective waiver rule of a kind, with the items it covers. */
public record ActiveWaiverRuleWithTriggers(String kind /*default BORROW_N_WAIVE_M*/, Integer borrowQty /*null = any*/,
        Integer waivedQty /*null = any*/, Boolean exclusiveItems /*default false*/) implements Query<WaiverRuleRef> { }
public record WaiverRuleRef(String code, String kind, int borrowQty, int waivedQty, List<WaiverItem> items) { }
/** An item a rule covers, with what a scan needs and the flags that decide whether a scenario can lend it plainly. */
public record WaiverItem(String code, String barcode, String title, BigDecimal deposit, boolean perDay,
                         boolean inductionRequired) { }
/** The items of a resolved rule that a scenario can scan without a further prompt; cheapest first. */
public record ItemsTriggering(WaiverRuleRef rule, Boolean perDay /*default false*/,
                              Boolean inductionRequired /*default false*/) implements Query<ItemRef> { }
/** One planned loan line, as the oracles and the dry run read it. */
public record LoanLineRef(String code, long qty, BigDecimal unitDeposit) { }
```

`WaiverRuleRef.items` holds every item the rule covers that is active and scannable (`barcode IS NOT NULL`), with
its flags, sorted by deposit then code; an item the rule covers that is inactive or has no barcode is left out (the
desk cannot lend it). `ItemRef` is the Phase 2 record.

| Query kind | Resolver (strategy) | Verifier |
|---|---|---|
| `ActiveWaiverRuleWithTriggers` | `lending.sql.ActiveWaiverRuleWithTriggers` (`sql`) | `lending.waiver.evaluate` |
| `ItemsTriggering` | `lending.java.ItemsTriggering` (`java`) | `lending.deposit` (a second verifier under that id, for this query class; P2 §6.4 allows one id for several kinds) |

**`lending.sql.ActiveWaiverRuleWithTriggers`.** First the business date: `ctx.agent().call("app.state")` and the
field `lending.today` (a read-only call, recorded in provenance as `Provenance.Call`, which holds no value; a desk
that reports none, or not an ISO-8601 date, is a `ResolutionException` reason `NO_DATE`). Then one statement with the
single parameter `kind`:

```sql
SELECT r.id, r.code, r.kind, r.borrow_qty, r.waived_qty, r.starts_on, r.ends_on,
       i.code, i.barcode, i.title, i.deposit, i.per_day, i.induction_required
FROM waiver_rule r JOIN waiver_rule_item ri ON ri.rule_id = r.id JOIN item i ON i.code = ri.item_code
WHERE r.active = TRUE AND r.kind = ? AND i.active = TRUE AND i.barcode IS NOT NULL
ORDER BY r.id, i.deposit, i.code
```

The date window is applied in Java, with exactly the semantics of the application's `WaiverRule.effectiveOn`
(`starts_on <= today <= ends_on`, a null bound open; the cells arrive as ISO-8601 date text). **The date is not a
statement parameter and appears in no recorded text**, so that the bindings file is the same on every day the rule
is effective (decision D47): a re-approval must not show a date diff, and the window is decided in one reviewed
place, as the staff resolver decides "unlimited". Rows are grouped by `r.id` (contiguous, in id order) into one
candidate per rule; `borrowQty` and `waivedQty`, when given, must equal the rule's; a rule with no row (no scannable
item) is no candidate. With `exclusiveItems: true`, a
rule that shares an item code with another rule of the result (another effective rule of the same kind; other kinds
do not exist in the sample) is dropped, as is the other. The statement is bounded by the number of rule-item pairs
and goes through `ctx.requireComplete` (a truncated prefix would cut a rule's items). The value is
`{code, kind, borrowQty, waivedQty, items}`; the rule's id is not in it (it is the order, not the identity a
scenario needs). Candidates in rule-id order.

**`lending.java.ItemsTriggering`** (committed Java, no SQL): the candidates are `rule.items` filtered by the query's
`perDay` and `inductionRequired` flags (default both false, so a plain counted tool), in the order they have
(cheapest first). No rows are read: the rule's value already holds everything.

**Verifiers.**

| Verifier id | Query | Requires | Passes when |
|---|---|---|---|
| `lending.waiver.evaluate` | `ActiveWaiverRuleWithTriggers` | `lending.waiver.evaluate` | the dry run of `borrowQty + waivedQty` units of the candidate's cheapest **counted** item (the first of `items` with `perDay` false), on the desk's date (no `date` parameter), answers **a** waiver with `ruleCode` equal to the candidate's `code` and `amount` equal to `waivedQty × deposit` of that item; with `exclusiveItems: true` it must be the **only** waiver. Reason text: "the desk waives 0.99 for 3 units of HND-003 under B2W1-HAND" (no date in the reason, which is recorded in the bindings file; the date goes into the evidence, D47). A candidate with no counted item fails ("the rule covers no counted item the desk can lend") |
| `lending.deposit` | `ItemsTriggering` | `lending.deposit` | as the Phase 2 verifier of `LendableItem`: a scan of the candidate's barcode finds it, active, at its deposit, lent counted or per day as the query asks; and `inductionRequired` as the query asks |

**Oracles** (pure; unit-tested against hand-computed cases):

```java
/** The loan that triggers exactly one waiver of the rule with one item: borrowQty + waivedQty units of it. */
public final class LoanFromTrigger implements Oracle<LoanFromTrigger.Input, LoanLineRef> {
    public record Input(WaiverRuleRef rule, ItemRef item) { }          // fn() = "LoanFromTrigger"
}
/** What the desk waives under one rule for these lines (§10.1 step 2), assuming the rule is effective. */
public final class ExpectedWaiver implements Oracle<ExpectedWaiver.Input, ExpectedWaiver.Waiver> {
    public record Input(WaiverRuleRef rule, List<LoanLineRef> lines) { }
    public record Waiver(String ruleCode, BigDecimal amount, long waivedUnits) { }   // fn() = "ExpectedWaiver"
}
/** Subtotal, waiver total and total of a loan (§10.1 step 3). */
public final class ExpectedTotals implements Oracle<ExpectedTotals.Input, ExpectedTotals.Totals> {
    public record Input(List<LoanLineRef> lines, List<BigDecimal> waivers) { }
    public record Totals(BigDecimal subtotal, BigDecimal waiverTotal, BigDecimal total) { }   // fn() = "ExpectedTotals"
}
```

`LoanFromTrigger.compute`: `{code: item.code, qty: rule.borrowQty + rule.waivedQty, unitDeposit: item.deposit}`; a
rule whose `waivedQty` is 0 or whose `borrowQty` is negative is a pack defect (`IllegalArgumentException`,
reported `ENVIRONMENT`). `ExpectedWaiver.compute` mirrors §10.1 step 2 exactly over `lines` (membership by
`rule.items` codes; a line whose item `rule.items` marks `perDay` is ignored, as the desk ignores it; one unit per
whole `qty`; sorted by `unitDeposit`, then code, then position; `amount` at scale 2; `waivedUnits` the count); a rule
that waives nothing gives `{ruleCode, "0.00", 0}`. `ExpectedTotals.compute`: subtotal
= Σ `qty × unitDeposit`, waiverTotal = Σ `waivers`, total = subtotal − waiverTotal, all at scale 2.

**Cross-check `lending.waiver.evaluate`** (`CrossCheck<ExpectedWaiver.Input, ExpectedWaiver.Waiver>`, `oracleFn()`
`ExpectedWaiver`, requires `lending.waiver.evaluate`, accepts a tolerance): calls `lending.waiver.evaluate {lines:
[{code, qty} per input line]}` (no `date`: the desk's date) and takes the amount the desk reports for
`output.ruleCode` (`0.00` when the rule is absent from the answer). Agrees when `|desk − output.amount| <= tolerance`
(`tolerance` null = `0`); `actual` is `{ruleCode, amount: <desk>, date: <the desk's date>, waivers: <the whole
answer>}`; the reason names both amounts and the date. Disagrees otherwise, with the same `actual`. Why the date in
`actual`: a disagreement caused by the date (the rule left its window between resolution and this bind) reads at
once in the report, although the verifier normally catches that first.

**Fingerprinter.** `TABLES` gains `WAIVER_RULE` and `WAIVER_RULE_ITEM` (the pack now reads them), so
`lending.schema` changes once (§2). `lending.site` and `lending.seed` unchanged; `today` is **not** added.

As built (2026-10-03). `ActiveWaiverRuleWithTriggers.kindOrDefault()`; candidate labels `rule <code>` and
`item <code>`; `NO_DATE` also for a `today` that is not an ISO-8601 date; a rule whose window bound is not an ISO-8601
date is no candidate; `exclusiveItems` is judged among the rules effective today only (a rule outside its window
excludes nothing). The rule verifier checks, before the dry run, that the candidate's kind and quantities match the
query ("rule X is of kind K, not Q"; "rule X is 2 + 1, not what the query asks"), fails a rule that waives no unit
("…, which waives no unit"), turns the primitive's "unknown item" error into a failure ("the desk does not know item
X") and lets any other error propagate; its failure reasons are "the desk waives nothing under R for 3 units of I[; it
waives X 0.50]", "the desk waives 1.98 for … under R, not 0.99" and "3 units of I trigger other rules too: …"; the
date goes into the evidence map (`date`), never into a reason (D47). The item verifier first checks that the item is
one the query's rule covers ("item X is not one that rule R covers"; "the query names no rule"), then makes the
Phase 2 scan (its reasons byte-identical, so scenario A's bindings are unchanged by it) and adds ", no induction
check" or ", induction check". `ExpectedWaiver` mirrors `WaiverEngine.benefit` for odd rules (a foreign kind, a
`waivedQty` below 1 or a negative `borrowQty` give `0.00` and 0 units, not an exception). The cross-check's reason reads
"the desk waives A for N units of I under R on D; the oracle expects B" with " (tolerance T)" when the tolerance is not
zero (lines read "1 unit of A, 2 units of B and 3 units of C"); `actual.waivers` is the answer's list. The catalogue
registers two query kinds, two resolvers, two verifiers, three oracles and the cross-check, and builds the same on
every call; the waiver tables follow `ITEM` in the fingerprinter's list (the checksum does not depend on the order).

**Stored tables, row masks, verbs, pre-flight, catalogue.** The stored tables of Phase 2 already include
`loan_waiver` (`loan_id`, `rule_code`, `amount` DECIMAL); nothing changes there, and the aggregate `sum(amount)` is
compiled by the base. Row masks unchanged. Verbs unchanged: `quantity {n}` and `scan {item}` enter the trigger
quantity (the desk's `QTY` key sets the quantity of the next item), `deposit {amount}` takes an oracle value. The
catalogue registers the two query types, the two resolvers, the two verifiers, the three oracles and the
cross-check, and still builds the same on every call.

### 10.4 Scenarios module

Layout (new entries only):

```
scenarios/borrow-2-waive-1-slip.yaml                   plan/borrow-2-waive-1-slip.plan.json
bindings/seed-a/borrow-2-waive-1-slip.bindings.json    bindings/seed-b/borrow-2-waive-1-slip.bindings.json
src/test/resources/negative/no-rule-of-kind.yaml       (a kind nobody has: RESOLUTION_FAILED / NO_CANDIDATE)
src/test/resources/negative/waiver-guard-fails.yaml    (must_equal on the waiver amount that cannot hold: GUARD)
```

The committed plans of `basic-loan`, `basic-loan-ui` and `return-over-limit-needs-supervisor` are regenerated
(version 3, pack `0.3.0`); the committed bindings of scenario A are regenerated on both seeds (the `lending.schema`
component). The wrong-oracle demonstration is **not** a YAML file: it is a pack overlay built in the test
(`ResolutionCatalogue.builder(base).replace(new WrongExpectedWaiver())`, a test class whose `compute` returns
`borrowQty × unitDeposit` instead of the waived units' deposits: `1.98` where the desk waives `0.99`), wrapped in a
`DomainPack` that delegates everything else to the real pack, so that nothing wrong is ever committed under
`scenarios/`.

`scenarios/borrow-2-waive-1-slip.yaml`, in full:

```yaml
# A loan of enough units of one item to trigger a borrow-N-waive-M waiver rule once: the desk waives the cheapest
# deposits, the total is reduced, the stored rows and the printed slip show the waiver. Nothing here names a rule, an
# item or a person: the rule is the first effective one of its kind on the desk's own date, the item its cheapest
# plain tool, the assistant anyone who signs on with a password. The expected waiver is computed by a committed
# oracle and cross-checked against the desk's own dry run before a single input is sent.
scenario: borrow-2-waive-1-slip
pack: lending
tags: [resolution, waivers, slip]
requires: [lending.input, lending.state, lending.loan, lending.slip]
resolve:
  assistant:
    query: StaffWithPermission
    args: { perform: [SIGN_ON], interactiveSignOn: true }
    verify: lending.access.check
    credential: { realm: staff, key: code }
  rule:
    query: ActiveWaiverRuleWithTriggers
    args: { kind: BORROW_N_WAIVE_M, exclusiveItems: true }
    strategy: [sql]
    verify: lending.waiver.evaluate
  item:
    query: ItemsTriggering
    args: { rule: $rule }
    verify: lending.deposit
  keys:
    query: ActionKey
    args: { actions: [DUE] }
oracle:
  needed: { fn: LoanFromTrigger, args: { rule: $rule, item: $item } }
  waiver:
    fn: ExpectedWaiver
    args: { rule: $rule, lines: [$oracle.needed] }
    crossCheck: { check: lending.waiver.evaluate, tolerance: "0.00" }   # a disagreement is ORACLE_SUSPECT
  due: { fn: ExpectedTotals, args: { lines: [$oracle.needed], waivers: [$oracle.waiver.amount] } }
steps:
  - signOn: { staff: $assistant }
  - expect: { "state.name": IDLE, "state.staff.code": $assistant.code }
  - quantity: { n: $oracle.needed.qty }
  - scan: { item: $item }
  - expect: { "state.name": LOAN, "loan.lines[code=$item.code].qty": $oracle.needed.qty }
  - key: { action: $keys.DUE }
  - expect:
      "state.name": DEPOSIT
      "loan.waivers[ruleCode=$rule.code].amount": $oracle.waiver.amount
      "loan.waiverTotal": $oracle.waiver.amount
      "loan.total": $oracle.due.total
  - deposit: { amount: $oracle.due.total }
  - waitFor: { topic: loan.posted, within: 10s, capture: { slot: loan, fields: { id: loanId, total: total } } }
  - waitFor: { topic: slip.printed, within: 10s, capture: { slot: slip, fields: { loanId: loanId } } }
verify:
  - { "state.name": IDLE }
  - db.loan: { where: { id: $loan.id }, staff_code: $assistant.code, total: $oracle.due.total }
  - db.loan_line: { where: { loan_id: $loan.id }, item_code: $item.code, qty: $oracle.needed.qty,
                    amount: $oracle.due.subtotal }
  - db.loan_waiver: { where: { loan_id: $loan.id }, rule_code: $rule.code, "sum(amount)": $oracle.waiver.amount }
  - { "slip.last.loanId": $slip.loanId }
  - { "slip.last.text": { contains: $rule.code } }
  - { "slip.last.text": { contains: $item.title } }
  - { "devices[id=printer].state.last.loanId": $slip.loanId }
  - { "devices[id=printer].state.last.text": { contains: $rule.code } }
on_prompt: { unexpected: fail }
timeouts: { step: 30s }
```

It has no `launchProperties` and does not require `lending.sql` (P2 §7.2). It requires nothing for the printer: the
`devices` root is read through core primitives every agent has. What it resolves to, by the seeds as built (the
desk's date is the seeding date, so every "effective" window below holds):

| Slot or oracle | Seed A (`seed-a`) | Seed B (`seed-b`) | Why this one |
|---|---|---|---|
| `assistant` | `100` | `4711` | first by code with `SIGN_ON`, a password step and a credential |
| `rule` | `B2W1-HAND` (2 + 1; items `HND-003` 0.99, `HND-001` 1.10, `HND-002` 1.45) | `B2W1-CYCLE` (2 + 1; items `T1002` 0.79, `T1001` 0.89, `T1013` 1.15) | the first rule by id that is active and inside its window on the desk's date; `B2W1-GARDEN` and `B2W1-SEALING` are inactive, `B1W1-PAINT` ended yesterday, `B3W1-WRENCH` starts in ten days |
| `item` | `HND-003` "Screwdriver set", 0.99 | `T1002` "Tyre levers", 0.79 | the rule's cheapest plain tool |
| `keys` | `{DUE: DUE}` | the same | |
| `$oracle.needed` | `{code: HND-003, qty: 3, unitDeposit: "0.99"}` | `{code: T1002, qty: 3, unitDeposit: "0.79"}` | |
| `$oracle.waiver` | `{ruleCode: B2W1-HAND, amount: "0.99", waivedUnits: 1}` | `{ruleCode: B2W1-CYCLE, amount: "0.79", waivedUnits: 1}` | the cross-check agrees with the desk |
| `$oracle.due` | `{subtotal: "2.97", waiverTotal: "0.99", total: "1.98"}` | `{subtotal: "2.37", waiverTotal: "0.79", total: "1.58"}` | |

As built (2026-10-03). The committed bindings of scenario B record the rule statement with the parameter
`["BORROW_N_WAIVE_M"]` and a `rowCount` of 4 (seed A: the two active rules of the kind with their items) or 5 (seed B);
no date appears anywhere in any committed bindings file (`BindingsUpToDateTest` asserts it). The files were produced
headless by `PackOnRealSchemaTest`'s approve against the sample's own database (`-Dskillroy.bindings.update=true`,
which writes `"channel": "agent"` in place of `jdbc`); the end-to-end approve check proves the agent-channel bytes.
The test connection `DeskConnection` answers `lending.waiver.evaluate` through `LendingWaivers.evaluate` and
`app.state` with `{lending: {seed, today}}`, and lets a test script its capability matrix; the test helpers are
`ScenarioB`, `WrongExpectedWaiver` (`borrowQty x unitDeposit` of the first line) and `OverlayPack` (a delegating pack
whose catalogue is `builder(base).replace(…)`). `fixedUpstream` of the `ORACLE_SUSPECT` detail is `[rule]` (the
query slots the oracle's own arguments reference). The committed plans of Phase 2 carry `packVersion` `0.3.0` and
scenario A's bindings their new `packVersion`, `planHash` and `lending.schema`; `resolveHash` is unchanged.

Negative material:

| Material | Run in | Expected |
|---|---|---|
| `negative/no-rule-of-kind.yaml`: scenario B with `kind: BORROW_N_WAIVE_M_NEVER` | `live` | `ERROR` / `RESOLUTION_FAILED`, reason `NO_CANDIDATE`, slot `rule`; no step runs |
| `negative/waiver-guard-fails.yaml`: scenario B with `must_equal: { ruleCode: $rule.code, amount: "0.00", waivedUnits: 0 }` on `waiver` | `live` | every slot resolves and the cross-check agrees, but the guard fails: `ERROR` / `RESOLUTION_FAILED`, reason `GUARD`; no step runs. It shows that the cross-check (step 8) comes before the guard (step 9) |
| the wrong-oracle overlay (test code, §10.4 above) | `live` and `approve` against the real desk; `replay` against a bindings file the test writes with the wrong oracle's value | `ERROR` / `ORACLE_SUSPECT`, reason `DISAGREED`, oracle `waiver`, `expected.amount` `1.98` (`expected` is the oracle's whole encoded output, §6.1), `actual.amount` `0.99`; no step runs, nothing is written, the desk is untouched. In `replay` with the **committed** file the same overlay is `REBIND_NEEDED` / `ORACLE_CHANGED` (its value differs from the recorded one), which is also right |
| the guarded form: scenario B with `crossCheck: { check: lending.waiver.evaluate, capability: lending.waiver.evaluate }` against a target whose `lending.waiver.evaluate` capability is scripted as not `OK` (a fake connection in the scenario module's tests, `UNAVAILABLE`, `DEGRADED` or undeclared; end to end the capability is scripted `DEGRADED`, because the rule's verifier puts the same id into `plan.requires`, so `UNAVAILABLE` would end the run as `PRECONDITION_FAILED` before the bind; the real desk always has it) | `replay` | the check is skipped, the bind succeeds, the run ends `DEGRADED` with the skipped line |

---

## 11. Acceptance

### 11.1 Without a display (unit and module tests; run in the normal build)

1. `skillroy-scenario`, grammar and compile: the `crossCheck` shorthand and mapping forms; an unknown key inside it;
   a check the pack lacks for the fn (the message lists what the pack offers); a tolerance on a check that accepts
   none; `tolerance` normalised to a plain decimal string; unguarded checks add their `requires()` to
   `plan.requires` and guarded ones do not; an aggregate key of each form, a `TEXT`/`BOOLEAN`/`TIMESTAMP` column
   refused, `count()`/`avg(x)`/`SUM(x)` refused with the list of three; `$env.now` refused with the §3.3 message;
   plan version 3 written, versions 1, 2 and 3 read, others refused; a version 2 plan reads as version 3 without
   cross-checks and aggregates; the secret-looking fixture rule applies to versions 2 and 3; byte-identical
   recompilation; `resolveHash` unchanged by adding an aggregate and changed by adding a cross-check;
   `ResolutionCatalogue.build()` refuses a duplicate check id, an unknown `oracleFn`, mismatched types and an
   `inference.` id; `builder(base).replace(check)` overlays.
2. `skillroy-scenario`, the binder against a toy pack and a fake connection, in **each mode**: the check runs once per
   oracle after `compute` and before the guard, with the decoded input and output and the plan's tolerance; `agreed
   = false` is `ORACLE_SUSPECT` / `DISAGREED` with every detail field of §6.1, nothing written in `approve` and in
   `live --write-bindings`, no cleanup call and no `recover`; a wrong oracle against a file approved with the right
   one is `ORACLE_CHANGED` in `replay`, and against a file approved with the wrong one is `ORACLE_SUSPECT`; a check
   that throws, or returns null, is `ENVIRONMENT` naming the check; a guarded check whose capability is `UNAVAILABLE`,
   `DEGRADED` or undeclared is skipped with the `crosscheck` record and the `BindOutcome.skipped` line, and an `OK`
   capability runs it; an unguarded check never consults the capability; the check's timeouts are clamped to the
   remaining budget and the budget is checked before the call; the `crosscheck` record's `evidence` passes the mask
   (a secret of 8 or more characters in the check's evidence is `<redacted>`); the bindings file written by
   `approve` carries the oracle's `crossCheck` key exactly as §8.3 says, reads back equal, is byte-stable, and a
   Phase 2 file without the key still reads; `BindingsDiff` reports a changed check under `oracles.<name>.crossCheck`;
   `ResolutionEngine.crossCheck(name)` returns the same result the binder recorded.
3. `skillroy-scenario`, executor: a run whose bind skipped a guarded check ends `DEGRADED` with the line in
   `skippedExpectations`, the B14 mapping (`degradedAs`), the JUnit `<skipped>` message and the `bind` record's
   `skipped`; aggregates: `sum`, `min`, `max` over returned rows for `DECIMAL` and `INTEGER` columns, `null` cells
   ignored, `null` over no cells, numeric matcher equality (`"0.99"` against `0.99`), a failing aggregate is `FAIL` /
   `ASSERTION` with `aggregates` in the detail, a truncated result fails before aggregation, aggregates retried with
   the expectations and the `db` record carries them; the `devices` root: built from `device.list` and one
   `device.state` per id in id order, a filter path selects a device, an empty list fails the expectation, a
   `device.state` error fails the fact read, and a pack root named `devices` wins.
4. `skillroy-cli`: `resolve` prints the cross-check lines and `degraded:` lines; `ORACLE_SUSPECT` gives exit 1 and
   `<error type="ORACLE_SUSPECT">` in the JUnit XML with the §8.1 message; `--json` carries the `crosscheck` records.
   `skillroy-junit`: the degraded message lists a skipped cross-check.
5. `skillroy-sample-lending-agent` (Java 8 test JVM too): `lending.waiver.evaluate` against the real `WaiverEngine`
   and both seeds (one group of the cheapest item waives one deposit; one unit short waives nothing; two groups waive
   two; mixed items waive the lowest deposits; a return line or a number of days cannot be expressed (the grammar has
   counted loan lines only, so a `returned` field, a negative or fractional `qty` is `INVALID_PARAMS`) and a per-day
   item is ignored; an inactive rule and a rule outside its window on the given `date` waive nothing; an unknown item
   is `INVALID_PARAMS`; the parameter rules); `app.state` `lending.today` equals the desk clock's date and changes
   with a fixed test clock. `skillroy-sample-lending-pack`: oracle unit tests against hand-computed cases for every
   branch of §10.1 step 2 (the sorted-units rule with mixed deposits, ties by code and position, whole quantities,
   zero waived, two groups); `ExpectedTotals` and `LoanFromTrigger` likewise; the catalogue builds the same on every
   call and holds the cross-check for `ExpectedWaiver` only; the verifiers against a fake connection (every pass and
   every fail reason of §10.3); the resolver's grouping, `exclusiveItems`, `borrowQty`/`waivedQty` filters, the
   `NO_DATE` reason and the provenance call.
6. `skillroy-sample-lending-scenarios`, headless, **the pack on the real schema** (the Phase 2 `PackOnRealSchemaTest`
   extended; the test connection answers `app.state` with `today`, and `lending.waiver.evaluate` from the real
   `WaiverEngine`): on each seed `live` resolves scenario B to the table of §10.4; `approve` writes a file
   byte-identical to the committed one except for `"channel": "jdbc"`; `replay` of the committed file passes the
   bind and the cross-check agrees; the wrong-oracle overlay is `ORACLE_SUSPECT` in `live` and `approve`, and
   `ORACLE_CHANGED` in `replay` against the committed file; a file the test writes with the wrong oracle's value is
   `ORACLE_SUSPECT` in `replay`; the two negative scenarios fail as §10.4 says; the `db.loan_waiver` statement with
   its aggregate runs against rows the test inserts. **The oracle equals the dry run for every seeded waiver rule**
   (the roadmap's exit criterion): for every `waiver_rule` row of both seeds, on a date inside its window and with
   the rule made effective for the purpose (a test copy of the rule with `active` true), for loans of one, two and
   three groups, one unit short of a group, and mixed items of the rule in every order, `ExpectedWaiver.compute`
   equals `WaiverEngine.evaluate` on the same lines, amount and rule code; and on a date outside the window the dry
   run waives nothing, which is what the verifier reports as a failure.
   `PlansUpToDateTest` and `BindingsUpToDateTest` cover the new plan and both new bindings files; the regenerated
   Phase 2 files are byte-identical to a fresh compile and rewrite.

As built (2026-10-03), the tests of §11.1: `Phase3CompileTest` (15), `CrossCheckSpiTest` (5),
`CrossCheckBinderTest` (13), `ExecutorPhase3Test` (14), `CrossCheckCommandsTest` (5), `CrossCheckGlueTest` (3),
`LendingWaiversTest` (25), `LendingWaiverTest` (22), `LendingResolutionTest` (additions), `PackOnRealSchemaTest`
(scenario B on both seeds, the wrong-oracle overlay, the guarded form), `WaiverOracleAgreementTest` (every seeded
rule of both seeds, 552 loans), `PlansUpToDateTest` and `BindingsUpToDateTest`. Determinism was checked by the review
in fresh JVMs under other locales, time zones, charsets and business dates: the committed files are byte-identical.

### 11.2 End to end (extends P2 §14.2; needs a display; `ci/run-in-linux.sh`)

For **each load mode** and **each seed**, through the real `PlanExecutor`, `Binder` and `AgentClient`, with the
committed YAML, plan and bindings of scenario B:

1. **Live resolution.** `live` resolves the slots and oracles to the values of §10.4 for this seed; every verifier
   passed; the cross-check agreed (one `crosscheck` record, `agreed: true`, `actual.date` equal to the desk's
   `today`); the recorded statements are the committed SQL with channel `agent`; the provenance of `rule` holds the
   `app.state` call; nothing written; the desk untouched.
2. **Approve is deterministic.** A scripted `approve` writes a file byte-identical to the committed one.
3. **Same YAML, per-environment bindings.** The committed plan runs in `replay` on seed A with `--env seed-a` and on
   seed B with `--env seed-b`: `PASS`.
4. **No sleeps; the check ran once.** No sleeper call; every expectation, `db` check and aggregate held at the first
   attempt; no `resolver` record; one `verifier` record per slot and one `crosscheck` record, `agreed: true`.
5. **The waiver is real.** `db.loan_waiver`'s `sum(amount)` equals the oracle's amount and the row the desk wrote;
   the loan's `total` equals the oracle's; the slip text (through the pack fact and through the `devices` root)
   names the rule and the item; `slip.last.loanId` equals the captured `slip.printed` payload and `$loan.id`.
6. **A wrong oracle is `ORACLE_SUSPECT`.** The wrong-oracle overlay in `live`: `ERROR` / `ORACLE_SUSPECT`, reason
   `DISAGREED`, oracle `waiver`, `expected.amount` `1.98` (seed A) or `1.58` (seed B), `actual.amount` the desk's; no
   step ran; no cleanup call and no `recover`; the desk untouched; nothing written to the bindings directory.
7. **The next scenario still runs.** After check 6, scenario B passes without a restart.
8. **Secrets.** As P2 §14.2 item 10 over the outputs of checks 1-7.

Once per target Java, through the assembled CLI launcher: `skillroy resolve --env seed-a --yes` on scenario B
leaves the committed bytes unchanged and exits 0 and prints the cross-check line; `skillroy run --mode replay --env
seed-a` exits 0 and prints `model calls: 0`. The per-target JDBC block of P2 §14.2 runs scenario B as well as A
(`live` resolves with channel `jdbc`, the cross-check agrees, `replay` passes with every `db` record over `jdbc`).

As built (2026-10-03): `ResolutionChecks` checks 8-13 cover items 1-7 and the guarded `DEGRADED` run (the capability
scripted `DEGRADED`, see §10.4), check 14 the secrets of both scenarios (both plans scanned; the A34 `secretParams`
assertion runs over scenario B's replay as well, expecting the `signOn` inputs); `PerTargetJavaChecks` runs scenario B
in the JDBC block and a CLI check for it; the JUnit glue check expects five dynamic tests. The two assertions that
compare the cross-check's date with the desk's `today` read the date before and after the run and accept either, so a
leg that crosses the target's midnight does not fail (reviewed and corrected 2026-10-03). The matrix ran twice on
2026-10-03 (numbers in `../TODO.md`).

**Exit criteria of Phase 3** (the roadmap's, made precise): §11.1 green in the normal build; §11.2 green in every
load mode, on both seeds, on a Java 8 target and a Java 25 target, twice in a row without a flake; the oracle equals
the dry run for every seeded waiver rule (§11.1 item 6); `ORACLE_SUSPECT` is demonstrated with a deliberately wrong
oracle headless (§11.1 items 2 and 6) and end to end (§11.2 item 6).

---

## 12. Open SPI and DSL gaps: what Phase 3 takes

Numbers are those of `../TODO.md`, "Open SPI and DSL gaps".

| # | Gap | In Phase 3 |
|---|---|---|
| 26 | Oracle cross-check outcomes | **Closed**: a guarded cross-check that cannot run makes the outcome `DEGRADED` (§6.2); the other case (a calibrated oracle) is Phase 5's (Phase 5, built 2026-10-08 on provisional B23; P5 §12.3, D86: answered by the journal export, which marks observed expectations for the reviewer) |
| 20 | Expectations relative to an earlier step | unchanged (partly closed in Phase 2); a captured print event is the pattern of §3.5 |
| 12 | Positional sources | unchanged; aggregates also read columns by position |
| 21 | Event expectations | unchanged; the two consecutive `waitFor`s of scenario B work without it |
| 28 | Pack-defined artifact kinds | unchanged; the slip is read as a fact, not captured as an artifact |

New, recorded for the owner of the gaps table: **an aggregate over an unbounded table** (more than 200 matching
rows) is not expressible; a pack writes a query kind for it (§3.2 rule 5). **`device.act` from a scenario** is not
offered (§1).

---

## 13. Decisions taken in this contract

| # | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| D37 | A cross-check is its own SPI type, `CrossCheck<I,O>`, selected by oracle function, run by the engine in every mode after `compute` and before the guard | reuse `Verifier<Q,T>`; a cross-check inside `Oracle` | an oracle has no query and no candidate (P2 §6.5); the oracle stays pure and the check is the only thing that touches the target; the position before the guard makes a wrong oracle `ORACLE_SUSPECT` and not `GUARD` |
| D38 | A guarded cross-check (`capability:`) is skipped when the capability is not `OK`, and the run is `DEGRADED` at best; an unguarded one puts its `requires()` into `plan.requires` | always `PRECONDITION_FAILED`; skip silently; a new outcome | B14's rule, extended as gap 26 asks; the scenario says which it wants |
| D39 | `$env.now` is not provided; a date-dependent query reads the target's business date inside its resolver and records the read in provenance; its verifier re-checks the condition in every mode | `$env.now` as a query argument; the controller's clock; a bind-time constant excluded from the hash | a date in `args` makes `argsHash` change daily; the controller's clock is not the target's business date; the verifier catches a binding that fell out of its window with the application's own reason |
| D40 | Aggregates (`sum`, `min`, `max`) are computed on the controller over the rows the check read; a truncated result fails first; `count()` and `avg()` are not offered | SQL aggregates generated into the statement; a second statement per check | the same rows on both channels give the same result in every dialect; the reviewed statement is unchanged; the row cap bounds the work; `count()` is `rows` and `avg()` has rounding the domain should own |
| D41 | `planVersion` 3 for the two new keys; the reader accepts 1, 2 and 3; `bindingsVersion` stays 1 with an optional informational `crossCheck` key | leave the plan at version 2; raise the bindings version | P2 §5's rule: an older executor would run such a plan without the check and the aggregate; a bindings reader that does not know the key refuses the file safely |
| D42 | The cross-check is part of `resolveHash` when written; aggregates are not | hash neither; hash both | adding a cross-check changes what a binding was accepted under; an aggregate is an assertion on a run, like an expectation |
| D43 | The base fact root `devices` (read-only, from `device.list` and `device.state`) | a `device` verb; a pack-only fact | a peripheral's state is observable on every target through core primitives; the sample's slip is asserted through the base and through the pack to prove both |
| D44 | The sample's dry-run primitive takes `lines` of counted items and an optional `date`, and the desk's `today` is an `app.state` field, not a fingerprint component | a primitive per rule; `today` in the fingerprint | the dry run mirrors `WaiverEngine.dryRun`; a fingerprint that changes daily would make every bindings file stale every day |
| D45 | The sample's fingerprint reads the two waiver tables from Phase 3 on, and scenario A's bindings are regenerated once | leave the list; a separate component | the pack now reads those tables (P2 §13.3's rule: the tables the pack reads); the one-time regeneration is P2 D8's accepted cost |
| D46 | The wrong-oracle demonstration is a test-time pack overlay, never a committed file | a committed negative YAML with a wrong `must_equal`; a committed wrong oracle class in the pack | nothing wrong may be committed under `scenarios/`; `builder(base).replace` is the variant-pack mechanism P2 D24 built for exactly this |
| D47 | The sample's rule statement takes the kind only; the date window is applied in Java from the desk's `today`, and no recorded text (statement params, verifier reasons) carries the date | the date as a `CAST(? AS DATE)` parameter; the date in the verifier's reason | a bindings file must not change from one day to the next; the cross-check's evidence, which is not recorded, may name the date |

### 13.1 Changes to the Phase 1 and Phase 2 contracts that Phase 3 needs

Written into `phase1-contracts.md` and `phase2-contracts.md` on 2026-10-03 before the code, each passage marked
"(Phase 3, specified 2026-10-03, not yet built)" at the time and "(Phase 3, built 2026-10-03)" since the build:

| Contract and section | Change |
|---|---|
| P1 §8.3 base fact roots; §8.5 `Status.DEGRADED` | the root `devices`; a skipped cross-check as a cause of `DEGRADED` |
| P1 §8.4 (A29 passage) | `skippedExpectations` may list a skipped cross-check |
| P1 §9.2 primitive table and `app.state` list | `lending.waiver.evaluate`; the `today` field |
| P2 §1 non-goals; §3.1 `oracleEntry`; §3.2; §5 version rules and `Plan.VERSION`; §6.5; §6.6 `BindOutcome`, `BindListener`; §8.3 step 8; §8.5; §9 format; §10 taxonomy; §11.3 records; §12.1 exit codes; §13 pack version; §13.2 primitives; §13.3 fingerprinter; §15 gap 26 | as this file states them (§3-§10); the reserved `crossCheck` key, `ORACLE_SUSPECT`, aggregates and `$env.now` lose their "Phase 3" placeholders |

The design overview's §4.2 sketch of scenario B is superseded by §10.4 of this file (it assumed `$env.now`,
`strategy: [sql, llm-sql]`, a `count` argument, `times: $oracle.needed.qty` on `scan`, a `sum(amount)` with a
database-side meaning, and `lending.sql` in `requires`); the overview is corrected together with the Phase 3 code,
marked "(Phase 3 contract, specified 2026-10-03)".

---

## 14. Questions put to the project lead: answered 2026-10-03

Answered provisionally by the orchestrator on 2026-10-03, in the manner of P2 §17, and confirmed by the owner on
2026-10-06 (B20), every one of the eight.

| # | Question | Answer | Written into |
|---|---|---|---|
| Q1 | Should `$env.now` exist at all, as a bind-time constant excluded from the hash? | No. A date the controller supplies is not the target's business date, and a constant excluded from the hash hides a dependency the bindings file should show; the resolver reads the target's date and records the read | §3.3, D39 |
| Q2 | Database-side aggregates (`SELECT SUM(…)`) or controller-side over the rows read? | Controller-side, with the 200-row cap as the bound | §3.2, §7, D40 |
| Q3 | Should `ORACLE_SUSPECT` be `ERROR` in `replay` too, or `REBIND_NEEDED`? | `ERROR` / `ORACLE_SUSPECT` in every mode: a disagreement is a problem with the test, not a stale binding | §6.1, §8.1 |
| Q4 | Does a cross-check belong in the bindings file? | Yes, as an informational key (`check`, `tolerance`, `capability`); `replay` recomputes | §8.3, D41 |
| Q5 | `planVersion` 3, or keep 2 and tolerate the new keys? | 3 | §4, D41 |
| Q6 | Should the sample's `today` join the fingerprint so that a stale date window is a `FINGERPRINT` mismatch? | No: it would stale every file daily; the verifier reports the window with the application's own reason | §10.2, D44 |
| Q7 | Regenerate scenario A's bindings (fingerprint change) or keep the Phase 2 table list and add a component? | Regenerate once; the list follows the rule "the tables the pack reads" | §2, D45 |
| Q8 | Is a `devices` base fact root wanted now, or only the pack fact? | Now: it is small and proves `DeviceAdapter` through the base | §3.4, D43 |
