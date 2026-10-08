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
-- BS6 accept corpus, Oracle (12c or newer: the guard writes FETCH FIRST). Every statement must be accepted and
-- rewritten byte-identically on two runs (into the "=>" text). Not executed: no Oracle server in the spike.
-- Format: see Corpus.java.

@@ FETCH FIRST written by the model is kept
SELECT i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code FETCH FIRST 20 ROWS ONLY
=> SELECT i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code FETCH FIRST 20 ROWS ONLY

@@ no ORDER BY: the key is appended and FETCH FIRST injected
SELECT i.code, i.title FROM item i WHERE i.active = 1
=> SELECT i.code, i.title FROM item i WHERE i.active = 1 ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ ORDER BY a non-key column: the key is appended
SELECT l.id, l.due FROM loan l ORDER BY l.due DESC NULLS LAST
=> SELECT l.id, l.due FROM loan l ORDER BY l.due DESC NULLS LAST, l.id FETCH FIRST 200 ROWS ONLY

@@ old-style outer join with (+)
SELECT l.id, x.item_code FROM loan l, loan_line x WHERE x.loan_id(+) = l.id
=> SELECT l.id, x.item_code FROM loan l, loan_line x WHERE x.loan_id(+) = l.id ORDER BY l.id, x.loan_id, x.line_no FETCH FIRST 200 ROWS ONLY

@@ comma join with the join condition in WHERE
SELECT s.code, r.role FROM staff s, staff_role r WHERE r.staff_code = s.code AND r.role = ?
=> SELECT s.code, r.role FROM staff s, staff_role r WHERE r.staff_code = s.code AND r.role = ? ORDER BY s.code, r.staff_code, r.role FETCH FIRST 200 ROWS ONLY

@@ ANSI join of three tables
SELECT s.code, r.role, p.permission FROM staff s
JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role
=> SELECT s.code, r.role, p.permission FROM staff s JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role ORDER BY s.code, r.staff_code, r.role, p.role, p.permission FETCH FIRST 200 ROWS ONLY

@@ join on USING: the merged column is appended unqualified, as Oracle requires
SELECT r.staff_code, p.permission FROM staff_role r JOIN role_permission p USING (role)
=> SELECT r.staff_code, p.permission FROM staff_role r JOIN role_permission p USING (role) ORDER BY r.staff_code, role, p.permission FETCH FIRST 200 ROWS ONLY

@@ CTE with GROUP BY
WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code)
SELECT i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC
=> WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code) SELECT i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC, i.code, lent.item_code FETCH FIRST 200 ROWS ONLY

@@ CTE with a column list
WITH late (loan_id, due_on) AS (SELECT l.id, l.due FROM loan l WHERE l.due < SYSDATE)
SELECT t.loan_id, t.due_on FROM late t
=> WITH late (loan_id, due_on) AS (SELECT l.id, l.due FROM loan l WHERE l.due < SYSDATE) SELECT t.loan_id, t.due_on FROM late t ORDER BY t.loan_id FETCH FIRST 200 ROWS ONLY

@@ subquery in WHERE with IN
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.returned IS NULL)
=> SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.returned IS NULL) ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ correlated EXISTS
SELECT s.code FROM staff s WHERE EXISTS (SELECT 1 FROM loan l WHERE l.staff_code = s.code)
=> SELECT s.code FROM staff s WHERE EXISTS (SELECT 1 FROM loan l WHERE l.staff_code = s.code) ORDER BY s.code FETCH FIRST 200 ROWS ONLY

@@ scalar subquery in the select list
SELECT l.id, (SELECT COUNT(*) FROM loan_line x WHERE x.loan_id = l.id) AS line_count FROM loan l
=> SELECT l.id, (SELECT COUNT(*) FROM loan_line x WHERE x.loan_id = l.id) AS line_count FROM loan l ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ a subquery on DUAL
SELECT l.id FROM loan l WHERE l.due < (SELECT SYSDATE - 7 FROM DUAL)
=> SELECT l.id FROM loan l WHERE l.due < (SELECT SYSDATE - 7 FROM DUAL) ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ SYSDATE from DUAL as the whole statement
SELECT SYSDATE AS now_on_server FROM DUAL
=> SELECT SYSDATE AS now_on_server FROM DUAL FETCH FIRST 200 ROWS ONLY

@@ derived table without AS, as Oracle writes it
SELECT d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status <> 'RETURNED') d ORDER BY d.due
=> SELECT d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status <> 'RETURNED') d ORDER BY d.due, d.id FETCH FIRST 200 ROWS ONLY

@@ GROUP BY with HAVING
SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0
=> SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0 ORDER BY x.item_code FETCH FIRST 200 ROWS ONLY

@@ single-row aggregate
SELECT COUNT(*) AS loans, MAX(l.due) AS last_due FROM loan l
=> SELECT COUNT(*) AS loans, MAX(l.due) AS last_due FROM loan l FETCH FIRST 200 ROWS ONLY

@@ DISTINCT
SELECT DISTINCT i.category FROM item i
=> SELECT DISTINCT i.category FROM item i ORDER BY i.category FETCH FIRST 200 ROWS ONLY

@@ CASE expression
SELECT i.code, CASE WHEN i.deposit >= 50 THEN 'HIGH' ELSE 'LOW' END AS band FROM item i
=> SELECT i.code, CASE WHEN i.deposit >= 50 THEN 'HIGH' ELSE 'LOW' END AS band FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ NVL, COALESCE and NULLIF
SELECT w.id, NVL(w.role, 'ANY') AS r1, COALESCE(w.role, 'ANY') AS r2, NULLIF(w.min_days, 0) AS d FROM waiver_rule w
=> SELECT w.id, NVL(w.role, 'ANY') AS r1, COALESCE(w.role, 'ANY') AS r2, NULLIF(w.min_days, 0) AS d FROM waiver_rule w ORDER BY w.id FETCH FIRST 200 ROWS ONLY

@@ CAST to NUMBER and VARCHAR2
SELECT i.code, CAST(i.deposit AS NUMBER(9, 2)) AS dep, CAST(i.code AS VARCHAR2(20)) AS c FROM item i
=> SELECT i.code, CAST(i.deposit AS NUMBER (9, 2)) AS dep, CAST(i.code AS VARCHAR2 (20)) AS c FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ SYSDATE arithmetic and TRUNC
SELECT l.id FROM loan l WHERE l.due < TRUNC(SYSDATE) - 7
=> SELECT l.id FROM loan l WHERE l.due < TRUNC(SYSDATE) - 7 ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ SYSTIMESTAMP minus an interval literal
SELECT l.id FROM loan l WHERE l.opened > SYSTIMESTAMP - INTERVAL '30' DAY
=> SELECT l.id FROM loan l WHERE l.opened > SYSTIMESTAMP - INTERVAL '30' DAY ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ ADD_MONTHS and MONTHS_BETWEEN
SELECT l.id, MONTHS_BETWEEN(SYSDATE, l.opened) AS months FROM loan l WHERE l.opened > ADD_MONTHS(SYSDATE, -1)
=> SELECT l.id, MONTHS_BETWEEN(SYSDATE, l.opened) AS months FROM loan l WHERE l.opened > ADD_MONTHS(SYSDATE, -1) ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ TO_CHAR and TO_DATE
SELECT l.id, TO_CHAR(l.due, 'YYYY-MM') AS due_month FROM loan l
WHERE l.opened >= TO_DATE('2026-09-01', 'YYYY-MM-DD')
=> SELECT l.id, TO_CHAR(l.due, 'YYYY-MM') AS due_month FROM loan l WHERE l.opened >= TO_DATE('2026-09-01', 'YYYY-MM-DD') ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ EXTRACT
SELECT l.id, EXTRACT(YEAR FROM l.due) AS y FROM loan l
=> SELECT l.id, EXTRACT(YEAR FROM l.due) AS y FROM loan l ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ quoted names in upper case
SELECT "L"."ID", "L"."DUE" FROM "LOAN" "L"
=> SELECT "L"."ID", "L"."DUE" FROM "LOAN" "L" ORDER BY "L".id FETCH FIRST 200 ROWS ONLY

@@ string functions and concatenation
SELECT i.code, UPPER(i.title) AS t, LOWER(i.category) AS c, i.code || '-' || i.title AS label, LENGTH(i.title) AS n
FROM item i
=> SELECT i.code, UPPER(i.title) AS t, LOWER(i.category) AS c, i.code || '-' || i.title AS label, LENGTH(i.title) AS n FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ LIMIT is rewritten as FETCH FIRST
SELECT i.code FROM item i ORDER BY i.code LIMIT 10
=> SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 10 ROWS ONLY

@@ FETCH FIRST over the cap is capped
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 250 ROWS ONLY
=> SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ personal column in WHERE and ORDER BY only
SELECT l.id FROM loan l WHERE UPPER(l.borrower) LIKE ? ORDER BY l.borrower
=> SELECT l.id FROM loan l WHERE UPPER(l.borrower) LIKE ? ORDER BY l.borrower, l.id FETCH FIRST 200 ROWS ONLY

@@ CALL of the allow-listed procedure
CALL open_loan_count(?)
=> CALL open_loan_count (?)
