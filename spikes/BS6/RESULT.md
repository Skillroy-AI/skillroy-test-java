# BS6: `SqlGuard` across SQL dialects

Run on 2026-10-07. **Proves** (`docs/TODO.md`, "Base spikes"; `docs/design/phase5-contracts.md` §5.3, which names
this spike as the gate of the tier (b) resolver): the JSqlParser 5.4 allow-list (exactly one SELECT, allow-listed
tables and columns with alias resolution, no DDL or DML, `CALL` only for allow-listed procedures, the row limit
injected and capped at 200, a deterministic `ORDER BY`) accepts and rejects correctly in every `SqlDialect`.

**Verdict: passed, with rule changes.** A prototype guard on JSqlParser 5.4 accepts all 264 accept-corpus statements
of the eight dialects, rewrites each byte-identically on two runs and in the same text on every build (the corpora
carry the expected rewrites), runs all 41 rewritten HSQLDB statements on the embedded lending schema, and refuses all
382 reject-corpus statements with the reason the corpus names; every rule of §5.3 is violated at least twice per
dialect. No dialect is "a dialect without a passing corpus". But two rules cannot be applied as written, two need a
bound or a mechanism the contract does not name, and most need additions (§4): rule 7's "key of the driving table"
produces invalid SQL for `GROUP BY`, `DISTINCT` and single-row aggregates and leaves ties under a one-to-many join;
rule 6 refuses the date and interval literals that date arithmetic needs; rule 9 has no nesting bound, and
JSqlParser's parse time and stack depth grow with nesting; rule 10 can only be met by the guard's own lexical pass,
because JSqlParser drops every comment silently, MySQL's executable comments included. The corpora also found one
bypass in a naive use of the parser's API
(an Oracle database link reads as an allow-listed table name, F-B), fixed in the prototype. DB2's everyday date
arithmetic (labeled durations in the plural) does not parse (F-E): DB2 passes, with a constraint the model must be
told about. Oracle needs 12c or newer for the injected `FETCH FIRST`.

## 1. What was run

- **The prototype** (`src/main/java/…/bs6/`, Java 25, about 2,000 lines with comments): `SqlGuard` (lexical pass,
  parse, statement kind, `CALL`, rewrite, checks on the output), `Analyzer` (scopes, tables, columns, personal
  columns, functions, literals, sizes), `SqlText` (the lexical pass), `DialectProfile` (per dialect: parser preset,
  identifier folding, row-limit form), and the §5.2 shapes `SqlAllowList`, `SqlDialect`, `GuardedStatement`,
  `GuardViolation`. Deny by
  default twice over: an AST node type the walk does not know is refused, and a select, join, table, group-by,
  order-by item or function whose text differs from that of a copy carrying only the parts the walk checked is
  refused, so that a clause JSqlParser adds in a later release cannot pass unseen. A guard defect fails closed.
- **The lending schema** (`src/test/resources/lending-schema.sql`): `staff(code, name, email, pin_hash, active,
  joined)`, `staff_role`, `role_permission(role, permission)`, `role_cap(role, cap, max_amount)`, `item(code, barcode,
  title, category, deposit, per_day, active)`, `waiver_rule`, `loan(id, borrower, staff_code, opened, due, status)`,
  `loan_line(loan_id, line_no, item_code, qty, deposit_paid, returned)`, `audit_log` and the function
  `open_loan_count`, with a few rows each. The allow-list (`LendingFixture`) lists every table but `audit_log` and
  every column but `staff.pin_hash`; `staff.name`, `staff.email` and `loan.borrower` are `personal`; the procedure
  `open_loan_count`; per dialect the extra function names its corpus uses (date functions, `NVL`, `ISNULL`, …).
- **The corpora** (`src/test/resources/corpus/`): per dialect an accept list (31-41 statements, each with its
  expected rewrite) and a reject list (42-60, each with its expected reason code), covering joins (inner, outer,
  full, cross, comma, `USING`, Oracle `(+)`), CTEs (with column lists, chained), subqueries in `WHERE`, `FROM` and
  the select list, aggregates with `GROUP BY`/`HAVING`, `DISTINCT`, `CASE`, `COALESCE`, `NULLIF`, `CAST`,
  each dialect's date arithmetic (`CURRENT_DATE - INTERVAL`, `DATEADD`, `DATE_ADD`, `TIMESTAMPADD`, `SYSDATE`,
  `GETDATE()`, `CURRENT DATE`, `ADD_DAYS`, `now() - interval`), string functions, the four row-limit forms, quoted
  identifiers per dialect, backticks, `::`, `ILIKE`, `DUAL`, `TOP`, `WITH (NOLOCK)`, `FETCH FIRST`, and in the reject
  lists DML, DDL, `CALL`, set operations, `SELECT *`, unlisted tables and columns, personal columns, forbidden
  functions (`SLEEP`, `BENCHMARK`, `LOAD_FILE`, `pg_sleep`, `pg_read_file`, `UTL_HTTP`, `DBMS_PIPE`, `xp_cmdshell`,
  `OPENROWSET`, `RAISE_ERROR`), long, hexadecimal and binary literals, `OFFSET`, `FOR UPDATE`, locking hints, hint
  comments, two statements, `INTO`, depth and size limits, CTEs shadowing allow-listed tables. Plus
  `parser-limits.sql`: 41 statements a dialect's targets would write that JSqlParser cannot parse or reads
  differently, each with the guard's outcome asserted, so that an upgrade that changes one shows up.
- **The tests** (741, all passing): `SqlGuardCorpusTest` (each accept statement through two independent guards,
  equal results, the expected text, `?` count unchanged; each reject statement refused twice with the same reason and
  fragment; the limits; at least 25 of each per dialect and every rule violated at least twice),
  `HsqldbExecutionTest` (each rewritten HSQLDB statement runs with its parameters, at most 200 rows),
  `Rule7AsWrittenTest` (§4, rule 7), `Bs6ReportTest` (writes `target/bs6/`).
- **Environment**: macOS on Apple silicon (aarch64), Maven 3.9.16 running on JDK 26.0.2 (compiled with `--release
  25`), JSqlParser 5.4 from Maven Central (the only download), HSQLDB 2.7.4 (`jdk8` classifier) and JUnit 5.14.4 from
  the local cache. No database server: only the HSQLDB corpus executes; the other seven are checked at the parse and
  rewrite level, which is BS6's pass criterion ("Parsing needs no database").

## 2. Summary per dialect

| Dialect | Parser preset | Accepted | Rejected (named reason) | Executed | What JSqlParser 5.4 cannot parse, or reads wrongly (F-E, F-F) |
|---|---|---|---|---|---|
| HSQLDB | none (defaults) | 41/41 | 60/60 | 41/41 | Cannot: HSQLDB's `SELECT LIMIT <offset> <count>`. Wrongly: `TIMESTAMPADD(SQL_TSI_DAY, …)` reads `SQL_TSI_DAY` as a column (the guard compensates) |
| MySQL | `MYSQL` | 34/34 | 53/53 | – | Cannot: `LOCK IN SHARE MODE`; `SELECT … INTO @v` (that one name). Wrongly: drops `/*! … */` executable comments (MySQL runs them); reads `5--1` as `5` plus a comment (MySQL: `5 - -1`); the preset makes `"x"` a string and `\` an escape (both depend on `sql_mode`; the guard refuses both) |
| MariaDB | `MARIADB` | 31/31 | 43/43 | – | Cannot: `LIMIT … ROWS EXAMINED`. Wrongly: as MySQL |
| PostgreSQL | `POSTGRESQL` | 31/31 | 48/48 | – | Cannot: `SELECT … INTO TEMP`. Wrongly: `= ANY(ARRAY[…])` is a call of a function named `ANY`. Outside the guard's grammar: `DISTINCT ON`, ordered and filtered aggregates, `~`, `LATERAL`, JSON operators (`->>`) |
| DB2 | none (defaults) | 33/33 | 45/45 | – | Cannot: **labeled durations in the plural** (`CURRENT DATE - 7 DAYS`, `- 3 HOURS`; the singular parses), `WITH RR USE AND KEEP … LOCKS`, `FINAL TABLE (INSERT …)`. Wrongly: `OPTIMIZE` after an unaliased table is read as its alias; the deparser writes `FETCH FIRST  ROW ONLY` with two spaces |
| Oracle | `ORACLE` | 32/32 | 42/42 | – | Wrongly: `SYSDATE`, `SYSTIMESTAMP` are columns in the tree; `Table.getName()` drops a database link (`item@remote` is "item", F-B). Outside the guard's grammar: `LISTAGG … WITHIN GROUP`, `KEEP (DENSE_RANK …)`, `CONNECT BY`, `PIVOT` |
| SQL Server | `SQLSERVER` | 31/31 | 45/45 | – | Cannot: `WAITFOR`; every table hint but `NOLOCK` and `INDEX` (`UPDLOCK`, `READPAST`, …); `DELETE TOP (n)`; the JDBC escape `{call …}`. Wrongly: `DATEADD(dd, …)` reads the date part as a column (the guard compensates). Outside: `APPLY`, `FOR XML`, `OPTION (…)` |
| GENERIC | `ANSI_SQL` | 31/31 | 46/46 | – | Merges adjacent literals across a line break into one (valid SQL). Outside the guard's grammar: `TABLE t`, `WITH RECURSIVE`, `GROUPING SETS`, `NATURAL JOIN` |
| **All** | | **264/264** | **382/382** | **41/41** | Every dialect: comments are dropped silently (F-A); unknown `SHOW`/`CREATE`/`ALTER`/`REPLACE` become an `UnsupportedStatement` even with that feature off (F-C); parse time and stack depth grow with parenthesis nesting (F-D) |

## 3. JSqlParser: version, parser features, licence

- **Version 5.4** (`com.github.jsqlparser:jsqlparser:5.4`, released 2026-09-13, the latest 5.4.x on Maven Central on
  2026-10-07; jar SHA-1 `4ce1ae8c94f2b64797c1fda0e916a4ce6eb83933`). Java 11 bytecode (class version 55), an explicit
  JPMS module `net.sf.jsqlparser`, **no run-time dependencies** (every dependency in its POM has test scope).
- **Parsing**: `CCJSqlParserUtil.parseStatements(text, executor, consumer)` with the guard's own single daemon thread
  (the convenience overloads create an executor per call), so that "exactly one statement" is counted rather than
  inferred from a parse error. JSqlParser parses with "simple" lookahead first and retries with "complex" parsing on
  failure (a refused text is parsed twice); `Feature.timeOut` bounds each attempt (the guard sets 2,000 ms).
- **Dialects**: JSqlParser has one grammar for every database. `CCJSqlParser.withDialect(Dialect)` switches lexer
  features and a handful of grammar rules: `MYSQL` and `MARIADB` turn on
  `allowBackslashEscapeCharacter`, `allowHashLineComments`, `allowDoubleQuotedStrings` and MySQL's `INTO` variables;
  `POSTGRESQL` turns on `allowDollarQuotedStringTags`, adjacent literals across a line break and a few PostgreSQL
  statement forms (`DO`, roles, triggers);
  `SQLSERVER` turns on `allowSquareBracketQuotation` and T-SQL routine and `SET` forms; `ORACLE` turns on PL/SQL
  blocks; `ANSI_SQL` (used for GENERIC) turns on adjacent literals. **There is no preset for DB2 or HSQLDB**; both
  parse with the defaults. The guard also sets `allowUnsupportedStatements` off (the default). JSqlParser's
  validation framework (`DatabaseType`: ANSI_SQL, ORACLE, MYSQL, SQLSERVER, MARIADB, POSTGRESQL, H2; no DB2, no
  HSQLDB) is not used: the guard's own rules decide.
- **Licence**: the POM names two licences, **"GNU Library or Lesser General Public License (LGPL) V2.1"** and **"The
  Apache Software License, Version 2.0"**; the manifest's `Bundle-License` lists both URLs; every source file states
  "Dual licensed under GNU LGPL 2.1 or Apache License 2.0". The choice is disjunctive, so **the project may take the
  Apache-2.0 option**, as phase5-contracts.md §2.2 assumes. The jar carries no LICENSE or NOTICE file: the audit
  (`docs/licence-audit.md` §5, still "not audited") should cite the POM, the manifest and the source headers, record
  the Apache-2.0 election, and the CLI's `THIRD-PARTY.txt` should say so.

## 4. The rules of §5.3: what could not be implemented as written, and the fix

The prototype implements the fix in each case; the corpora exercise it.

| Rule | As written | What the spike found | Proposed wording (implemented) |
|---|---|---|---|
| 1 Parse | One statement in the dialect; a failure is a violation | Implementable. JSqlParser turns an unknown `SHOW`, `CREATE`, `ALTER`, `REPLACE` or `REFRESH` into an `UnsupportedStatement` even with `allowUnsupportedStatements` off (F-C) | Add: "a statement the parser only captured (`UnsupportedStatement`) is a parse failure" |
| 2 SELECT only | No `INTO`, `FOR UPDATE`, locking hints, set operations; `CALL p(?, …)` when allow-listed | (a) `INTO` has no reason code; (b) a CTE body may be DML (PostgreSQL `WITH d AS (DELETE … RETURNING …)`); (c) "locking hints" leaves out index hints, isolation clauses and query hints; (d) T-SQL has no `CALL`, and JSqlParser cannot parse the JDBC escape `{call …}`; (e) constructs outside the guard's grammar (`CONNECT BY`, `PIVOT`, `LATERAL`, `APPLY`, `DISTINCT ON`, recursive CTEs, `GROUPING SETS`) need a code | (a) `INTO` → `DML`; (b) rule 2 applies inside `WITH` → `DML`; (c) `LOCKING` covers every table hint (`NOLOCK`, index hints), isolation clause (`WITH UR`/`RS`/`CS`/`RR`) and `FOR` clause (`UPDATE`, `SHARE`, `READ ONLY`); query hints `OPTION (…)` → `UNSUPPORTED`; (d) the guard accepts `CALL p(?, …)` in every dialect and the channel runs it with `Connection.prepareCall("{call p(?, …)}")`; (e) a new reason code **`UNSUPPORTED`** |
| 3 Tables | Allow-listed; aliases resolve; a CTE shadows nothing | Implementable, with gaps the wording should close: schema-, database- and server-qualified names; Oracle database links, which `Table.getName()` hides (F-B); quoted names that differ only in case (PostgreSQL may hold both `item` and `"Item"`); `DUAL` | Refuse any qualified name, any `@link`; a quoted name must spell the declared name as the dialect folds it (upper for Oracle, DB2, HSQLDB, standard; lower for PostgreSQL; case-insensitive for MySQL, MariaDB, SQL Server); `DUAL` is a keyless one-row table in Oracle, MySQL and MariaDB |
| 4 Columns | Every reference resolves through aliases, CTE and subquery columns; `SELECT *` refused; `personal` not in the select list | (a) **Unqualified names cannot be resolved exactly from the allow-list**: the database looks among *all* the columns of the inner tables first, the guard sees only the allowed ones, so a correlated fallback could bind, in the guard's view, to an allowed outer column while the database reads a hidden inner one; (b) personal values flow through CTEs, derived tables and scalar subqueries; (c) `HAVING` is a filter too | (a) The allow-list carries every column *name* of each allow-listed table (the catalogue builder reads the schema anyway) so unqualified names resolve as the database resolves them; until then (the prototype) an unqualified name resolves in the innermost `FROM` only and a correlated reference must be qualified; (b) a derived column is personal if its expression reads a personal one, and the outermost select list (with its scalar subqueries) may read none; (c) `HAVING` joins the permitted places; `COUNT(t.*)` is `STAR` |
| 5 Functions | Aggregates, `COALESCE`, `NULLIF`, `CASE`, `CAST`, `LOWER`, `UPPER`, `TRIM`, `LENGTH`, `SUBSTRING`, `ABS`, `ROUND`, `CURRENT_DATE` and the allow-list's | (a) Not enough for "date arithmetic as each dialect writes it": every dialect needs `CURRENT_TIMESTAMP`/`EXTRACT` and its own date functions, and the tree spells them five ways (`Function`, `TimeKeyExpression` with `CURRENT DATE`, a `Column` for `SYSDATE`, `DateUnitExpression` or a `Column` for date parts, dedicated `Cast`, `Extract`, `Trim`, `Transcoding` nodes); (b) an allowed name can carry `OVER`, `WITHIN GROUP`, `FILTER`, `KEEP` or an inner `ORDER BY`; (c) `CAST` can name catalogue types (PostgreSQL's `::regclass` and kin, which read the catalogue), LOBs, XML | (a) A default extension per dialect shipped with the guard (date functions, `CURRENT_TIMESTAMP`, `EXTRACT`, `NVL`/`ISNULL`/`IFNULL`, `CONCAT`) so that a pack lists only the unusual; bare words only from a fixed niladic list (`SYSDATE`, …), never any allowed name, so a hidden column cannot be read under a function's name; date-part keywords per dialect; (b) analytic and ordered forms refused (`FUNCTION`); (c) `CAST`/`CONVERT` only to scalar types (character, numeric, date and time, boolean) |
| 6 Literals | Numbers, strings, `NULL`; no string over 200; no hex or binary; bound values as `?` | (a) Refuses `DATE '…'`, `TIMESTAMP '…'`, `INTERVAL '7' DAY`, `TRUE`, all needed; (b) where a string literal ends depends on server settings the guard cannot see: backslash escapes (MySQL `NO_BACKSLASH_ESCAPES`, PostgreSQL `standard_conforming_strings`), `"…"` (MySQL `ANSI_QUOTES`: with the MySQL preset the parser reads `"pin_hash"` as a string, the server can read it as a column); (c) `?1`, `$1`, `:name`, `@p` | (a) Allow typed date/time literals, interval literals and booleans; (b) refuse a backslash in any string literal, double-quoted text in MySQL and MariaDB, dollar-quoted, `E'…'`, `q'…'`, `U&'…'` and introducer (`_utf8mb4'…'`) strings, and backticks outside MySQL and MariaDB; (c) only `?` |
| 7 ORDER BY | Ends with every key column of the driving table; appended when missing | **Invalid SQL** after `GROUP BY`, after `DISTINCT` (unless the key is selected) and after a single-row aggregate (HSQLDB: "invalid ORDER BY expression"); **not a total order** under a one-to-many join (the lines of one loan tie); `ORDER BY` in a subquery or CTE is undefined; a key column merged by `JOIN … USING` must be unqualified (Oracle refuses a qualifier). `Rule7AsWrittenTest` shows the three invalid forms and the ties on HSQLDB | The tie-breaker is: the `GROUP BY` expressions of a grouped query; the select list of a `DISTINCT` query; nothing for a single-row aggregate; else the key columns of **every** table in `FROM` in order, a CTE or derived table contributing the key it derives (grouping columns, distinct columns, or its tables' keys, which it must select) or the statement is refused (`ORDER`). The `ORDER BY` must *contain* the tie-breaker (any position, any direction); missing parts are appended; `ORDER BY` inside subqueries and CTEs is refused; ordinals and select-list aliases count |
| 8 Row limit | `LIMIT` / `FETCH FIRST n ROWS ONLY` / `TOP n` at most 200, else injected; no `OFFSET` | Implementable. Each dialect accepts only some forms, and SQL Server's `FETCH` needs `OFFSET` | The guard reads any of the three forms, caps it and writes **one form per dialect**: `LIMIT n` (HSQLDB, MySQL, MariaDB, PostgreSQL), `FETCH FIRST n ROWS ONLY` (DB2, Oracle 12c+, GENERIC), `TOP n` (SQL Server). Refused (`LIMIT`): a parameter, `0`, `PERCENT`, `WITH TIES`, `LIMIT ALL`, MySQL's `LIMIT a, b`, `ROWNUM`, a limit inside a subquery or CTE, two forms at once |
| 9 Size | 3 nested subqueries, 8 tables, 20 select-list columns, 4,000 characters | **No nesting bound**: parse time grows faster than linearly with parenthesis nesting and JSqlParser's recursion overflows a 1 MB thread stack near 640 levels, all within 4,000 characters (F-D) | Add "parentheses nested at most 32 deep", checked in the lexical pass before parsing; count CTE bodies as subquery levels and table *references* (not distinct tables) |
| 10 Comments | Stripped before parsing, refused if they survive (no `/*+ hints */`) | JSqlParser drops every comment silently, including MySQL's `/*! … */` (which MySQL executes) and keeps only Oracle-style `/*+ */` after `SELECT` (F-A) | The guard's own lexical pass, aware of literals and quoted identifiers per dialect: a plain comment becomes one space; a hint (`/*+`, `--+`), an executable comment (`/*!`), a nested or an unterminated comment is refused |
| (new) Output | – | The deparser is what runs, so it must be checked | The rewritten text carries no comment marker, parses and deparses to itself, and passes the walk again with the same parameters and tables (the tie-breaker may add key columns) |

## 5. The rewrite through the deparser, per dialect

The guard deparses with `StatementDeParser` (single-line output). For every one of the 264 accepted statements the
output parses and deparses to itself, and the `?` parameters keep their number and order (a limit given as `?` is
refused, so nothing with a parameter moves).

- **Injected or rewritten row limit.** HSQLDB, MySQL, MariaDB, PostgreSQL: `… ORDER BY i.code LIMIT 200` (`TOP 5` and
  `FETCH FIRST 3 ROWS ONLY` become `LIMIT 5` and `LIMIT 3`). DB2, Oracle, GENERIC: `… ORDER BY i.code FETCH FIRST 200
  ROWS ONLY` (`LIMIT 5` becomes `FETCH FIRST 5 ROWS ONLY`; `FETCH NEXT` becomes `FETCH FIRST`; `FETCH FIRST ROW
  ONLY` becomes `FETCH FIRST 1 ROWS ONLY`). SQL Server: `SELECT TOP 200 …`, after `DISTINCT`
  (`SELECT DISTINCT TOP 50 i.category …`); `TOP (5)` becomes `TOP 5`; `LIMIT 10` and `FETCH FIRST 3 ROWS ONLY` become
  `TOP 10` and `TOP 3`.
- **Appended tie-breakers.** `ORDER BY i.deposit DESC, i.code`; `ORDER BY l.id, x.loan_id, x.line_no` (join);
  `ORDER BY total DESC, x.item_code` (grouped); `SELECT DISTINCT i.category … ORDER BY i.category`; nothing for
  `COUNT(*)`; `ORDER BY r.staff_code, role, p.permission` (`USING (role)`); `ORDER BY "I".code`, `d."Loan No"`
  (quoting kept).
- **The dialect's own syntax is kept**: backticks, `[brackets]`, double quotes, `::` casts, `ILIKE`, `(+)`, `N'…'`,
  `CURRENT DATE`, `CURRENT TIMESTAMP`, `7 DAY` without `INTERVAL`, MySQL's `INTERVAL 7 DAY`, `DATEADD(dd, …)`,
  `SYSDATE`, `TOP` placement.
- **Normalised, all still valid**: `TRUE` → `true`; `interval` → `INTERVAL`; `DATEADD(day, …)` → `DATEADD(DAY, …)`;
  `TRIM(x)` → `Trim( x )`; `DECIMAL(10,2)` → `DECIMAL (10, 2)`; `::numeric(10,2)` → `::numeric (10, 2)`;
  `CONVERT(varchar(10), x, 120)` → `CONVERT( varchar (10), x, 120 )`; `CALL p(?)` → `CALL p (?)`; comments and line
  breaks → single spaces; adjacent literals across a line break merged (`'Tile'` / `' saw'` → `'Tile saw'`, ANSI and
  PostgreSQL presets).
- `toString()` and the deparser differ cosmetically (`WITH x(a,b)` against `WITH x (a, b)`; `FETCH FIRST  ROW ONLY`
  from the deparser); the guard uses the deparser throughout, as §5.3 says.

## 6. Parse time

Warm, on this machine (Apple silicon, JDK 26.0.2), over the 643-646 corpus statements, the median of 25 runs per
statement after five warm-up passes over every corpus, three runs of the report: **parsing a statement once takes
about 0.07 ms on average** (mean of the medians 62, 72 and 84 µs; 95th percentile 104-138 µs; per dialect 53-133 µs).
The whole guard (lexical pass, parse, walk, rewrite, then the re-parse and re-walk of its own output) averages 75-103
µs over the same statements; a refused statement usually stops early, an accepted one is parsed twice. The slowest
parse was a 4.5 KB `IN` list (2.4-4.5 ms), which the guard refuses by size before parsing. Plain means are noisier
(one GC or JIT pause lifts a 25-run mean to a few milliseconds); no corpus statement needed JSqlParser's slower
"complex" retry. A cold JVM's first parses take a few milliseconds.

**Nesting** (F-D) is the exception: `WHERE ((…(x > 1)…))` parses in 1 ms at 80 levels, 15 ms at 160, 29 ms at 320 and
160 ms at 640, where it overflowed a 1 MB thread stack; at 1,950 levels (3.9 KB) 1.4 s. At the guard's bound of 32
levels it takes under 1 ms.

## 7. Findings about JSqlParser

- **F-A Comments are dropped silently.** `SELECT /*! SLEEP(5), */ code FROM item` parses as `SELECT code FROM item`:
  run as written, MySQL would execute the comment. `--1` is a comment to the parser even in MySQL, where `5--1` is
  `5 - -1`. Only Oracle-style `/*+ … */` after `SELECT` survives (as `OracleHint`). Hence rule 10's own lexical pass,
  and running the deparsed text, never the model's.
- **F-B `Table.getName()` drops an Oracle database link** (`item@remote_desk` → `item`, name parts
  `[item@remote_desk]`), and `getDBLinkName()` returns the table's own name when there is no link. A check of
  `getName()` against the allow-list accepted `SELECT i.code FROM item@remote_desk i` (the Oracle reject corpus caught
  it); the prototype checks the raw name part. A quoted name containing a dot is split into parts (`"lending.item"`
  becomes `"lending"."item"`, though to PostgreSQL it is one table name) unless the JVM-wide system property
  `SPLIT_NAMES_ON_DELIMITER` says otherwise; the guard refuses it as qualified either way.
- **F-C `UnsupportedStatement` without asking.** An unknown `SHOW …`, `CREATE …`, `ALTER …`, `REPLACE …` or
  `REFRESH …` comes back as an `UnsupportedStatement` (`SHOW ME THE LOANS` "parses") even with
  `allowUnsupportedStatements` off.
- **F-D Nesting.** Parse time grows faster than linearly with parenthesis depth, and the recursive-descent parser
  overflows its thread's stack near 640 levels at `-Xss1m`; JSqlParser's own `getNestingDepth` counts parentheses
  inside string literals too, so the guard counts in its lexical pass.
- **F-E Not parsed** (the guard refuses them as `PARSE`, which is safe but a loss): DB2 labeled durations in the
  plural (`- 7 DAYS`, `- 3 HOURS`) and `WITH RR USE AND KEEP … LOCKS`, `FINAL TABLE (…)`; MySQL `LOCK IN SHARE MODE`,
  `INTO @v` (only that variable name: the lexer has a token for it); MariaDB `LIMIT … ROWS EXAMINED`; PostgreSQL
  `SELECT … INTO TEMP`; SQL Server `WAITFOR`, table hints other than `NOLOCK` and `INDEX`, `DELETE TOP (n)`, `{call …}`;
  HSQLDB `SELECT LIMIT 0 5 …`.
- **F-F Read differently from the database** (the guard compensates or refuses): `SYSDATE`, `SYSTIMESTAMP`,
  `SQL_TSI_DAY` and SQL Server's date-part abbreviations are column references in the tree; PostgreSQL's
  `= ANY(ARRAY[…])` is a function named `ANY`; DB2's `FROM item OPTIMIZE FOR 10 ROWS` takes `OPTIMIZE` for an alias;
  the MySQL preset reads `"x"` as a string and `\'` as an escape whatever the server's `sql_mode`.
- **F-G The tree is wide.** `PlainSelect` and its base class carry some forty optional clauses (`QUALIFY`,
  `PREWHERE`, KSQL windows, `INTO TEMP`, `FOR XML`, `SETTINGS`, …) for a dozen dialects, and `Select` has further
  subclasses (pipe syntax, `VALUES`, `TABLE`); a guard that checks named fields one by one will miss the next one.
  The prototype's copy-and-compare check (§1) refused every unknown clause the corpora tried (`OPTIMIZE FOR`,
  `OPTION (MAXDOP 1)`, `SQL_CALC_FOUND_ROWS`, `CONNECT BY`, `TABLESAMPLE`, `FOR XML PATH`).

## 8. Per dialect: served or unavailable

Every dialect has a passing corpus under BS6's criteria, so none is "a dialect without a passing corpus" that the
allow-list builder must refuse with "no BS6 corpus". Each can be served with §4's rules and these conditions:

- **HSQLDB: served.** The corpus also ran on the embedded database (41/41).
- **MySQL, MariaDB: served.** The guard refuses the forms whose meaning depends on `sql_mode` (backslash escapes,
  double-quoted text), so no server setting has to be known.
- **PostgreSQL: served.**
- **DB2: served, with a known gap.** Plural labeled durations, DB2's everyday date arithmetic, do not parse; the
  prompt catalogue must tell the model to write `ADD_DAYS`, `DAYS()` or a singular unit (`- 7 DAY`), or such
  statements are refused as `PARSE` (fail-closed, at the cost of more "no candidates").
- **Oracle: served for Oracle 12c or newer.** The guard writes `FETCH FIRST`; an Oracle 11g or older target has no
  such clause and `ROWNUM` is refused, so for it tier (b) must stay unavailable.
- **SQL Server: served**, with `CALL p(?, …)` run by the channel as the JDBC escape `{call p(?, …)}`.
- **GENERIC: served only where the target accepts `FETCH FIRST n ROWS ONLY`** (SQL:2008); a database that does not
  should be named as its own dialect rather than served as GENERIC.

## 9. Left open

- **No real servers.** Only HSQLDB executed. The seven other corpora show that the guard accepts, refuses and
  rewrites as intended; that each rewritten statement runs on its database is reasoned, not run. Before the resolver
  serves a dialect in production, its accept corpus should run on that database (Testcontainers images exist for
  PostgreSQL, MySQL, MariaDB, SQL Server, Oracle Free and DB2 Community), preferably in the Linux test image.
- **The full column list** (rule 4, fix (a)) is not built: the prototype refuses unqualified correlated references
  instead.
- **Function sets per dialect** (rule 5, fix (a)): the fixture's lists show what the corpora needed; the default
  extension the guard should ship is a design decision for Phase 5.
- **Window functions and set operations** stay refused (Phase 5 scope); a later phase that wants them needs new
  corpora.
- Parse-time numbers come from one laptop; the Linux image was not used (nothing here needs a display).
- `docs/licence-audit.md` §5 and `docs/TODO.md` (BS6) are not updated (this spike edits only `spikes/BS6/`).

## 10. How to reproduce

`mvn -f spikes/BS6/pom.xml verify` (about 10 seconds; JDK 25+; the first run downloads JSqlParser 5.4). The findings
are written to `spikes/BS6/target/bs6/`; `README.md` describes the layout and the corpus format.
