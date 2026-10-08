# BS6: `SqlGuard` across SQL dialects

A standalone Maven project (not a module of the root reactor) that prototypes the Phase 5 `SqlGuard`
(`docs/design/phase5-contracts.md` §5.3) on JSqlParser 5.4 and runs an accept and a reject corpus per `SqlDialect` as
JUnit 5 tests. The findings are in `RESULT.md`.

## Run

JDK 25 or newer, Maven 3.9. The first run downloads JSqlParser 5.4 from Maven Central; HSQLDB 2.7.4 and JUnit 5.14.4
are the versions the root POM pins.

```
mvn -f spikes/BS6/pom.xml verify
```

No display, no server, no network after the first download. The run takes about 10 seconds and writes:

| File | What |
|---|---|
| `target/bs6/summary.md` | Per dialect: accepted, rejected with the named reason, executed on HSQLDB, parse and guard time |
| `target/bs6/<dialect>.txt` | Every corpus entry with its rewrite (and injected clauses, tables, columns) or its violation |
| `target/bs6/limits.txt` | The documented parser limits and what the guard does with each |
| `target/bs6/corpus/<dialect>-accept.sql` | The accept corpus with every `=>` line set to the current rewrite |

## Layout

| Path | What |
|---|---|
| `src/main/java/…/SqlGuard.java` | The guard: lexical pass, parse, statement kind, rewrite (ORDER BY, row limit), checks on the output |
| `src/main/java/…/Analyzer.java` | The walk: scopes, tables, columns, personal columns, functions, literals, sizes |
| `src/main/java/…/SqlText.java` | The guard's own lexical pass: comments, quoting, nesting |
| `src/main/java/…/DialectProfile.java` | Per dialect: JSqlParser preset, identifier folding, row-limit form |
| `src/main/java/…/SqlAllowList.java`, `SqlDialect.java` | The allow-list of §5.2 (shape kept, spike's own builder) |
| `src/test/resources/lending-schema.sql` | The spike's lending schema and rows (HSQLDB) |
| `src/test/resources/corpus/<dialect>-accept.sql`, `-reject.sql` | The corpora |
| `src/test/resources/corpus/parser-limits.sql` | What JSqlParser cannot parse or misreads, per dialect, asserted |
| `src/test/java/…/SqlGuardCorpusTest.java` | The gate: accept, reject, limits, sizes and per-rule coverage |
| `src/test/java/…/HsqldbExecutionTest.java` | Runs every rewritten HSQLDB accept statement on the embedded database |
| `src/test/java/…/Rule7AsWrittenTest.java` | Shows on HSQLDB why rule 7 cannot be applied as written |
| `src/test/java/…/Bs6ReportTest.java` | Writes `target/bs6/` |

## Corpus format

Everything before the first `@@` line is the file's header. An entry is `@@ <label>` (accept), `@@ <REASON> <label>`
(reject) or `@@ <DIALECT> <ACCEPT|REASON> <label>` (limits), then the statement on one or more lines (comments are part
of it), then optionally `?? v1 | v2` (parameter values for the HSQLDB run: `'text'`, integers, decimals,
`DATE yyyy-mm-dd`, `true`, `false`) and `=> <text>` (the expected rewrite). `{{repeat N TEXT}}` writes TEXT N times.

To refresh the expected rewrites after a deliberate change to the guard: run the build, review
`target/bs6/corpus/<dialect>-accept.sql` against `src/test/resources/corpus/`, and copy it over.
