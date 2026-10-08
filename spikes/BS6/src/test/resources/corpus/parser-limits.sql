-- Copyright 2026 Mike Mills.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- BS6: what JSqlParser 5.4 cannot parse, or reads differently from the database, per dialect, and what the guard then
-- does. "@@ <DIALECT> <OUTCOME> <label>": OUTCOME is ACCEPT (with the rewrite after "=>" when it shows the point) or
-- the reason code the guard refuses with (PARSE: the parser itself fails). The test asserts each outcome, so that a
-- JSqlParser upgrade that changes one shows up. Format: see Corpus.java.

@@ HSQLDB ACCEPT TIMESTAMPADD's SQL_TSI_DAY is parsed as a column reference; the guard treats it as a date part
SELECT l.id, TIMESTAMPADD(SQL_TSI_DAY, 7, l.due) AS grace FROM loan l

@@ HSQLDB ACCEPT a duration without INTERVAL is parsed as an interval and written back without the keyword
SELECT l.id FROM loan l WHERE l.due < CURRENT_DATE - 7 DAY
=> SELECT l.id FROM loan l WHERE l.due < CURRENT_DATE - 7 DAY ORDER BY l.id LIMIT 200

@@ HSQLDB PARSE HSQLDB's own SELECT LIMIT <offset> <count> form
SELECT LIMIT 0 5 i.code FROM item i ORDER BY i.code

@@ MYSQL ACCEPT "--" without a following space is a comment to the parser (and to the guard), an operator to MySQL
SELECT i.code FROM item i WHERE i.deposit > 5--1
=> SELECT i.code FROM item i WHERE i.deposit > 5 ORDER BY i.code LIMIT 200

@@ MYSQL COMMENT an executable comment is dropped silently by the parser; the guard's own pass refuses it
SELECT i.code /*!, SLEEP(5) */ FROM item i

@@ MYSQL PARSE LOCK IN SHARE MODE
SELECT i.code FROM item i LOCK IN SHARE MODE

@@ MYSQL PARSE SELECT INTO @v fails while INTO @c parses (a lexer token for "@v")
SELECT i.code INTO @v FROM item i LIMIT 1

@@ MYSQL FUNCTION GROUP_CONCAT with ORDER BY and SEPARATOR (not on this allow-list; its inner ORDER BY is outside the guard's function grammar)
SELECT x.loan_id, GROUP_CONCAT(x.item_code ORDER BY x.item_code SEPARATOR ',') AS items FROM loan_line x
GROUP BY x.loan_id

@@ MARIADB PARSE LIMIT … ROWS EXAMINED
SELECT i.code FROM item i ORDER BY i.code LIMIT 10 ROWS EXAMINED 1000

@@ POSTGRESQL ACCEPT a typed cast is written back with a space before the type modifier (still valid)
SELECT i.code, i.deposit::numeric(10,2) AS d FROM item i
=> SELECT i.code, i.deposit::numeric (10, 2) AS d FROM item i ORDER BY i.code LIMIT 200

@@ POSTGRESQL UNSUPPORTED DISTINCT ON
SELECT DISTINCT ON (x.item_code) x.item_code, x.loan_id FROM loan_line x ORDER BY x.item_code, x.loan_id

@@ POSTGRESQL FUNCTION an ordered aggregate
SELECT x.loan_id, string_agg(x.item_code, ',' ORDER BY x.item_code) AS items FROM loan_line x GROUP BY x.loan_id

@@ POSTGRESQL FUNCTION an aggregate with FILTER
SELECT COUNT(*) FILTER (WHERE x.qty > 1) AS multi FROM loan_line x

@@ POSTGRESQL FUNCTION = ANY (ARRAY[…]) is parsed as a call of a function named ANY
SELECT l.id FROM loan l WHERE l.id = ANY(ARRAY[101, 102])

@@ POSTGRESQL PARSE SELECT INTO TEMP
SELECT i.code INTO TEMP item_copy FROM item i

@@ DB2 PARSE a labeled duration in the plural (DB2 accepts DAYS, MONTHS, YEARS)
SELECT l.id FROM loan l WHERE l.due < CURRENT DATE - 7 DAYS

@@ DB2 PARSE a labeled duration in hours
SELECT l.id FROM loan l WHERE l.opened < CURRENT TIMESTAMP - 3 HOURS

@@ DB2 ACCEPT a labeled duration in the singular parses as an interval
SELECT l.id FROM loan l WHERE l.due < CURRENT DATE - 1 MONTH
=> SELECT l.id FROM loan l WHERE l.due < CURRENT DATE - 1 MONTH ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ DB2 PARSE OPTIMIZE FOR after an unaliased table (OPTIMIZE is read as the table alias)
SELECT code FROM item OPTIMIZE FOR 10 ROWS

@@ DB2 PARSE WITH RR USE AND KEEP EXCLUSIVE LOCKS
SELECT l.id FROM loan l WITH RR USE AND KEEP EXCLUSIVE LOCKS

@@ DB2 ACCEPT FETCH FIRST ROW ONLY (the deparser alone would write "FETCH FIRST  ROW ONLY"; the guard writes a count)
SELECT l.id FROM loan l ORDER BY l.id FETCH FIRST ROW ONLY
=> SELECT l.id FROM loan l ORDER BY l.id FETCH FIRST 1 ROWS ONLY

@@ ORACLE ACCEPT SYSDATE is parsed as a column reference; the guard reads it as a function when it is allow-listed
SELECT l.id FROM loan l WHERE l.due < SYSDATE

@@ ORACLE TABLE a database link: Table.getName() returns the local name (the guard checks the raw name)
SELECT i.code FROM item@remote_desk i

@@ ORACLE FUNCTION LISTAGG … WITHIN GROUP is an analytic node
SELECT LISTAGG(x.item_code, ',') WITHIN GROUP (ORDER BY x.item_code) AS items FROM loan_line x

@@ ORACLE FUNCTION an aggregate with KEEP
SELECT MAX(l.id) KEEP (DENSE_RANK FIRST ORDER BY l.due) AS first_id FROM loan l

@@ ORACLE UNSUPPORTED PIVOT
SELECT p.* FROM (SELECT l.status FROM loan l) PIVOT (COUNT(*) FOR status IN ('OPEN' AS o)) p

@@ SQLSERVER PARSE WAITFOR
WAITFOR DELAY '00:00:05'

@@ SQLSERVER PARSE most table hints (only NOLOCK and INDEX are known)
SELECT l.id FROM loan l WITH (READPAST)

@@ SQLSERVER PARSE DELETE TOP
DELETE TOP (10) FROM loan_line

@@ SQLSERVER ACCEPT DATEADD's abbreviated date part is parsed as a column reference; the guard treats it as a date part
SELECT l.id FROM loan l WHERE l.due < DATEADD(dd, -7, GETDATE())

@@ SQLSERVER ACCEPT CONVERT with a style is its own node and is written back with inner spaces
SELECT l.id, CONVERT(varchar(10), l.opened, 120) AS d FROM loan l
=> SELECT TOP 200 l.id, CONVERT( varchar (10), l.opened, 120 ) AS d FROM loan l ORDER BY l.id

@@ SQLSERVER UNSUPPORTED FOR XML PATH
SELECT i.code FROM item i FOR XML PATH('')

@@ SQLSERVER FUNCTION STRING_AGG … WITHIN GROUP
SELECT STRING_AGG(x.item_code, ',') WITHIN GROUP (ORDER BY x.item_code) AS items FROM loan_line x

@@ SQLSERVER UNSUPPORTED OUTER APPLY
SELECT l.id, a.qty FROM loan l OUTER APPLY (SELECT TOP 1 x.qty FROM loan_line x WHERE x.loan_id = l.id) a

@@ GENERIC NOT_SELECT TABLE as a statement
TABLE item

@@ GENERIC UNSUPPORTED WITH RECURSIVE
WITH RECURSIVE n (k) AS (SELECT i.code FROM item i) SELECT n.k FROM n

@@ GENERIC ACCEPT an interval with a field range
SELECT l.id FROM loan l WHERE l.opened > CURRENT_TIMESTAMP - INTERVAL '1 2' DAY TO HOUR

@@ GENERIC ACCEPT adjacent string literals across a line break
SELECT i.code FROM item i WHERE i.title = 'Tile'
' saw'

@@ POSTGRESQL TABLE a quoted name containing a dot is split into schema and table ("lending.item" is one name to PostgreSQL)
SELECT x.code FROM "lending.item" x

@@ POSTGRESQL UNSUPPORTED a JSON operator
SELECT i.code FROM item i WHERE i.title::json->>'a' = 'b'

@@ SQLSERVER PARSE the JDBC escape for a procedure call
{call open_loan_count(?)}
