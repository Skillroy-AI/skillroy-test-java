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
-- BS6 accept corpus, DB2 (LUW 11.5 and z/OS 12 syntax). Every statement must be accepted and rewritten
-- byte-identically on two runs (into the "=>" text). Not executed: no DB2 server in the spike. Format: see Corpus.java.

@@ FETCH FIRST written by the model is kept
SELECT i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code FETCH FIRST 10 ROWS ONLY
=> SELECT i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code FETCH FIRST 10 ROWS ONLY

@@ no ORDER BY: the key is appended and FETCH FIRST injected
SELECT i.code, i.title FROM item i WHERE i.active = TRUE
=> SELECT i.code, i.title FROM item i WHERE i.active = true ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ ORDER BY a non-key column: the key is appended
SELECT l.id, l.due FROM loan l ORDER BY l.due DESC
=> SELECT l.id, l.due FROM loan l ORDER BY l.due DESC, l.id FETCH FIRST 200 ROWS ONLY

@@ join of two tables: the keys of both are appended
SELECT l.id, x.line_no FROM loan l JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ?
=> SELECT l.id, x.line_no FROM loan l JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ? ORDER BY l.id, x.loan_id, x.line_no FETCH FIRST 200 ROWS ONLY

@@ join of three tables
SELECT s.code, r.role, c.cap FROM staff s
INNER JOIN staff_role r ON r.staff_code = s.code INNER JOIN role_cap c ON c.role = r.role
=> SELECT s.code, r.role, c.cap FROM staff s INNER JOIN staff_role r ON r.staff_code = s.code INNER JOIN role_cap c ON c.role = r.role ORDER BY s.code, r.staff_code, r.role, c.role, c.cap FETCH FIRST 200 ROWS ONLY

@@ left join with IS NULL
SELECT i.code FROM item i LEFT OUTER JOIN loan_line x ON x.item_code = i.code WHERE x.loan_id IS NULL
=> SELECT i.code FROM item i LEFT OUTER JOIN loan_line x ON x.item_code = i.code WHERE x.loan_id IS NULL ORDER BY i.code, x.loan_id, x.line_no FETCH FIRST 200 ROWS ONLY

@@ CTE with GROUP BY
WITH lent AS (SELECT x.item_code, SUM(x.qty) AS qty FROM loan_line x GROUP BY x.item_code)
SELECT i.code, lent.qty FROM item i JOIN lent ON lent.item_code = i.code
=> WITH lent AS (SELECT x.item_code, SUM(x.qty) AS qty FROM loan_line x GROUP BY x.item_code) SELECT i.code, lent.qty FROM item i JOIN lent ON lent.item_code = i.code ORDER BY i.code, lent.item_code FETCH FIRST 200 ROWS ONLY

@@ CTE with a column list
WITH late (loan_id, due_on) AS (SELECT l.id, l.due FROM loan l WHERE l.due < CURRENT DATE)
SELECT t.loan_id, t.due_on FROM late t ORDER BY t.due_on
=> WITH late (loan_id, due_on) AS (SELECT l.id, l.due FROM loan l WHERE l.due < CURRENT DATE) SELECT t.loan_id, t.due_on FROM late t ORDER BY t.due_on, t.loan_id FETCH FIRST 200 ROWS ONLY

@@ subquery in WHERE with IN
SELECT s.code FROM staff s WHERE s.code IN (SELECT l.staff_code FROM loan l WHERE l.status = 'OPEN')
=> SELECT s.code FROM staff s WHERE s.code IN (SELECT l.staff_code FROM loan l WHERE l.status = 'OPEN') ORDER BY s.code FETCH FIRST 200 ROWS ONLY

@@ correlated EXISTS
SELECT r.staff_code, r.role FROM staff_role r
WHERE EXISTS (SELECT 1 FROM role_permission p WHERE p.role = r.role AND p.permission = 'WAIVER_APPLY')
=> SELECT r.staff_code, r.role FROM staff_role r WHERE EXISTS (SELECT 1 FROM role_permission p WHERE p.role = r.role AND p.permission = 'WAIVER_APPLY') ORDER BY r.staff_code, r.role FETCH FIRST 200 ROWS ONLY

@@ scalar subquery in the select list
SELECT s.code, (SELECT COUNT(*) FROM loan l WHERE l.staff_code = s.code) AS loans FROM staff s
=> SELECT s.code, (SELECT COUNT(*) FROM loan l WHERE l.staff_code = s.code) AS loans FROM staff s ORDER BY s.code FETCH FIRST 200 ROWS ONLY

@@ derived table with an alias and its key selected
SELECT d.code, d.per_day FROM (SELECT i.code, i.per_day FROM item i WHERE i.category = 'POWER') AS d
=> SELECT d.code, d.per_day FROM (SELECT i.code, i.per_day FROM item i WHERE i.category = 'POWER') AS d ORDER BY d.code FETCH FIRST 200 ROWS ONLY

@@ GROUP BY with HAVING
SELECT w.category, MAX(w.percent_off) AS best FROM waiver_rule w GROUP BY w.category HAVING COUNT(*) > 0
=> SELECT w.category, MAX(w.percent_off) AS best FROM waiver_rule w GROUP BY w.category HAVING COUNT(*) > 0 ORDER BY w.category FETCH FIRST 200 ROWS ONLY

@@ single-row aggregate
SELECT COUNT(*) AS lines, SUM(x.deposit_paid) AS held FROM loan_line x
=> SELECT COUNT(*) AS lines, SUM(x.deposit_paid) AS held FROM loan_line x FETCH FIRST 200 ROWS ONLY

@@ DISTINCT
SELECT DISTINCT r.role FROM staff_role r
=> SELECT DISTINCT r.role FROM staff_role r ORDER BY r.role FETCH FIRST 200 ROWS ONLY

@@ CASE expression
SELECT x.loan_id, x.line_no, CASE WHEN x.returned IS NULL THEN 'OUT' ELSE 'BACK' END AS state FROM loan_line x
=> SELECT x.loan_id, x.line_no, CASE WHEN x.returned IS NULL THEN 'OUT' ELSE 'BACK' END AS state FROM loan_line x ORDER BY x.loan_id, x.line_no FETCH FIRST 200 ROWS ONLY

@@ COALESCE, VALUE and NULLIF
SELECT w.id, COALESCE(w.role, 'ANY') AS r1, VALUE(w.role, 'ANY') AS r2, NULLIF(w.min_days, 0) AS d
FROM waiver_rule w
=> SELECT w.id, COALESCE(w.role, 'ANY') AS r1, VALUE(w.role, 'ANY') AS r2, NULLIF(w.min_days, 0) AS d FROM waiver_rule w ORDER BY w.id FETCH FIRST 200 ROWS ONLY

@@ CAST
SELECT i.code, CAST(i.deposit AS DECIMAL(9, 2)) AS dep, CAST(i.per_day AS INTEGER) AS rate FROM item i
=> SELECT i.code, CAST(i.deposit AS DECIMAL (9, 2)) AS dep, CAST(i.per_day AS INTEGER) AS rate FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ CURRENT DATE as DB2 spells it
SELECT l.id FROM loan l WHERE l.due < CURRENT DATE
=> SELECT l.id FROM loan l WHERE l.due < CURRENT DATE ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ a labeled duration in the singular
SELECT l.id FROM loan l WHERE l.due < CURRENT DATE - 7 DAY
=> SELECT l.id FROM loan l WHERE l.due < CURRENT DATE - 7 DAY ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ ADD_DAYS
SELECT l.id FROM loan l WHERE l.due BETWEEN ADD_DAYS(CURRENT DATE, -30) AND CURRENT DATE
=> SELECT l.id FROM loan l WHERE l.due BETWEEN ADD_DAYS(CURRENT DATE, -30) AND CURRENT DATE ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ DAYS difference
SELECT l.id, DAYS(CURRENT DATE) - DAYS(l.due) AS late_days FROM loan l WHERE DAYS(CURRENT DATE) - DAYS(l.due) > 7
=> SELECT l.id, DAYS(CURRENT DATE) - DAYS(l.due) AS late_days FROM loan l WHERE DAYS(CURRENT DATE) - DAYS(l.due) > 7 ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ YEAR and MONTH
SELECT l.id, YEAR(l.opened) AS y, MONTH(l.opened) AS m FROM loan l
=> SELECT l.id, YEAR(l.opened) AS y, MONTH(l.opened) AS m FROM loan l ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ CURRENT TIMESTAMP as DB2 spells it
SELECT l.id FROM loan l WHERE l.opened < CURRENT TIMESTAMP
=> SELECT l.id FROM loan l WHERE l.opened < CURRENT TIMESTAMP ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ quoted names in upper case
SELECT "L"."ID", "L"."STATUS" FROM "LOAN" "L" WHERE "L"."STATUS" <> 'RETURNED'
=> SELECT "L"."ID", "L"."STATUS" FROM "LOAN" "L" WHERE "L"."STATUS" <> 'RETURNED' ORDER BY "L".id FETCH FIRST 200 ROWS ONLY

@@ string functions
SELECT i.code, UPPER(i.title) AS t, SUBSTR(i.title, 1, 4) AS t4, LENGTH(i.barcode) AS n, i.code || '/' || i.category AS p,
       CONCAT(i.code, i.category) AS q
FROM item i
=> SELECT i.code, UPPER(i.title) AS t, SUBSTR(i.title, 1, 4) AS t4, LENGTH(i.barcode) AS n, i.code || '/' || i.category AS p, CONCAT(i.code, i.category) AS q FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ FETCH FIRST over the cap is capped
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 500 ROWS ONLY
=> SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ FETCH FIRST ROW ONLY becomes an explicit count
SELECT l.id FROM loan l ORDER BY l.due DESC FETCH FIRST ROW ONLY
=> SELECT l.id FROM loan l ORDER BY l.due DESC, l.id FETCH FIRST 1 ROWS ONLY

@@ LIMIT is rewritten as FETCH FIRST
SELECT i.code FROM item i ORDER BY i.code LIMIT 5
=> SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 5 ROWS ONLY

@@ TOP is rewritten as FETCH FIRST
SELECT TOP 5 i.code FROM item i ORDER BY i.code
=> SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 5 ROWS ONLY

@@ personal column in WHERE and ORDER BY only
SELECT l.id FROM loan l WHERE l.borrower <> '' ORDER BY l.borrower
=> SELECT l.id FROM loan l WHERE l.borrower <> '' ORDER BY l.borrower, l.id FETCH FIRST 200 ROWS ONLY

@@ two parameters
SELECT l.id FROM loan l WHERE l.opened >= ? AND l.staff_code = ?
=> SELECT l.id FROM loan l WHERE l.opened >= ? AND l.staff_code = ? ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ CALL of the allow-listed procedure
CALL open_loan_count(?)
=> CALL open_loan_count (?)
