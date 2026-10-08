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
-- BS6 accept corpus, HSQLDB (2.7, default mode). Every statement must be accepted, rewritten byte-identically on two
-- runs (into the "=>" text) and, rewritten, run on the embedded lending schema with the "??" parameter values.
-- Format: see Corpus.java.

@@ key already in ORDER BY, one parameter
SELECT i.code, i.title, i.deposit FROM item i WHERE i.category = ? ORDER BY i.code
?? 'POWER'
=> SELECT i.code, i.title, i.deposit FROM item i WHERE i.category = ? ORDER BY i.code LIMIT 200

@@ no ORDER BY: the key is appended and the limit injected
SELECT i.code, i.title FROM item i WHERE i.active = TRUE
=> SELECT i.code, i.title FROM item i WHERE i.active = true ORDER BY i.code LIMIT 200

@@ ORDER BY a non-key column: the key is appended as the tie-breaker
SELECT i.code, i.deposit FROM item i ORDER BY i.deposit DESC
=> SELECT i.code, i.deposit FROM item i ORDER BY i.deposit DESC, i.code LIMIT 200

@@ join of two tables: the keys of both are appended
SELECT l.id, x.line_no, x.item_code FROM loan l JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ?
?? 'OPEN'
=> SELECT l.id, x.line_no, x.item_code FROM loan l JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ? ORDER BY l.id, x.loan_id, x.line_no LIMIT 200

@@ join of three tables over staff roles and permissions
SELECT s.code, r.role, p.permission
FROM staff s
JOIN staff_role r ON r.staff_code = s.code
JOIN role_permission p ON p.role = r.role
WHERE s.active = TRUE
ORDER BY s.code, p.permission
=> SELECT s.code, r.role, p.permission FROM staff s JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role WHERE s.active = true ORDER BY s.code, p.permission, r.staff_code, r.role, p.role LIMIT 200

@@ join on USING: the merged key column is appended unqualified
SELECT r.staff_code, p.permission FROM staff_role r JOIN role_permission p USING (role) WHERE r.staff_code = ?
?? 'S01'
=> SELECT r.staff_code, p.permission FROM staff_role r JOIN role_permission p USING (role) WHERE r.staff_code = ? ORDER BY r.staff_code, role, p.permission LIMIT 200

@@ left join with IS NULL: items never lent
SELECT i.code, i.title FROM item i LEFT JOIN loan_line x ON x.item_code = i.code WHERE x.loan_id IS NULL
=> SELECT i.code, i.title FROM item i LEFT JOIN loan_line x ON x.item_code = i.code WHERE x.loan_id IS NULL ORDER BY i.code, x.loan_id, x.line_no LIMIT 200

@@ CTE with GROUP BY read by the outer select
WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code)
SELECT i.code, i.title, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC
=> WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code) SELECT i.code, i.title, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC, i.code, lent.item_code LIMIT 200

@@ CTE with a column list
WITH overdue (loan_id, desk) AS (SELECT l.id, l.staff_code FROM loan l WHERE l.status = 'OVERDUE')
SELECT o.loan_id, o.desk FROM overdue o
=> WITH overdue (loan_id, desk) AS (SELECT l.id, l.staff_code FROM loan l WHERE l.status = 'OVERDUE') SELECT o.loan_id, o.desk FROM overdue o ORDER BY o.loan_id LIMIT 200

@@ subquery in WHERE with IN
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.returned IS NULL)
=> SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.returned IS NULL) ORDER BY i.code LIMIT 200

@@ correlated EXISTS
SELECT s.code FROM staff s
WHERE EXISTS (SELECT 1 FROM staff_role r WHERE r.staff_code = s.code AND r.role = 'SUPERVISOR')
=> SELECT s.code FROM staff s WHERE EXISTS (SELECT 1 FROM staff_role r WHERE r.staff_code = s.code AND r.role = 'SUPERVISOR') ORDER BY s.code LIMIT 200

@@ scalar subquery in the select list
SELECT l.id, (SELECT COUNT(*) FROM loan_line x WHERE x.loan_id = l.id) AS line_count FROM loan l ORDER BY l.id
=> SELECT l.id, (SELECT COUNT(*) FROM loan_line x WHERE x.loan_id = l.id) AS line_count FROM loan l ORDER BY l.id LIMIT 200

@@ derived table with an alias and its key selected
SELECT d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status <> 'RETURNED') d ORDER BY d.due
=> SELECT d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status <> 'RETURNED') d ORDER BY d.due, d.id LIMIT 200

@@ a quoted alias in a derived table keeps its quotes in the tie-breaker
SELECT d."Loan No" FROM (SELECT l.id AS "Loan No" FROM loan l WHERE l.status = 'OPEN') d
=> SELECT d."Loan No" FROM (SELECT l.id AS "Loan No" FROM loan l WHERE l.status = 'OPEN') d ORDER BY d."Loan No" LIMIT 200

@@ GROUP BY with HAVING: the grouping column is the tie-breaker
SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0
ORDER BY total DESC
=> SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0 ORDER BY total DESC, x.item_code LIMIT 200

@@ single-row aggregate: nothing to order
SELECT COUNT(*) AS open_loans, MAX(l.due) AS last_due FROM loan l WHERE l.status = 'OPEN'
=> SELECT COUNT(*) AS open_loans, MAX(l.due) AS last_due FROM loan l WHERE l.status = 'OPEN' LIMIT 200

@@ DISTINCT: the select list is the tie-breaker
SELECT DISTINCT i.category FROM item i
=> SELECT DISTINCT i.category FROM item i ORDER BY i.category LIMIT 200

@@ CASE expression
SELECT i.code, CASE WHEN i.deposit >= 50 THEN 'HIGH' WHEN i.deposit >= 30 THEN 'MID' ELSE 'LOW' END AS band
FROM item i
=> SELECT i.code, CASE WHEN i.deposit >= 50 THEN 'HIGH' WHEN i.deposit >= 30 THEN 'MID' ELSE 'LOW' END AS band FROM item i ORDER BY i.code LIMIT 200

@@ COALESCE and NULLIF
SELECT w.id, COALESCE(w.role, 'ANY') AS needs_role, NULLIF(w.percent_off, 0) AS pct FROM waiver_rule w
=> SELECT w.id, COALESCE(w.role, 'ANY') AS needs_role, NULLIF(w.percent_off, 0) AS pct FROM waiver_rule w ORDER BY w.id LIMIT 200

@@ CAST
SELECT i.code, CAST(i.deposit AS INTEGER) AS whole_deposit, CAST(i.per_day AS VARCHAR(10)) AS rate FROM item i
=> SELECT i.code, CAST(i.deposit AS INTEGER) AS whole_deposit, CAST(i.per_day AS VARCHAR (10)) AS rate FROM item i ORDER BY i.code LIMIT 200

@@ date arithmetic: CURRENT_DATE minus an interval literal
SELECT l.id, l.due FROM loan l WHERE l.due < CURRENT_DATE - INTERVAL '7' DAY
=> SELECT l.id, l.due FROM loan l WHERE l.due < CURRENT_DATE - INTERVAL '7' DAY ORDER BY l.id LIMIT 200

@@ date arithmetic: DATEADD with a quoted date part
SELECT l.id FROM loan l WHERE l.due BETWEEN DATEADD('day', -30, CURRENT_DATE) AND CURRENT_DATE
=> SELECT l.id FROM loan l WHERE l.due BETWEEN DATEADD('day', -30, CURRENT_DATE) AND CURRENT_DATE ORDER BY l.id LIMIT 200

@@ date arithmetic: TIMESTAMPADD with SQL_TSI_DAY (read as a column by the parser)
SELECT l.id, TIMESTAMPADD(SQL_TSI_DAY, 7, l.due) AS grace_end FROM loan l
=> SELECT l.id, TIMESTAMPADD(SQL_TSI_DAY, 7, l.due) AS grace_end FROM loan l ORDER BY l.id LIMIT 200

@@ date arithmetic: a duration without INTERVAL
SELECT l.id FROM loan l WHERE l.due < CURRENT_DATE - 7 DAY
=> SELECT l.id FROM loan l WHERE l.due < CURRENT_DATE - 7 DAY ORDER BY l.id LIMIT 200

@@ DATEDIFF and EXTRACT
SELECT l.id, DATEDIFF('day', l.opened, l.due) AS days_lent, EXTRACT(MONTH FROM l.opened) AS opened_month
FROM loan l
=> SELECT l.id, DATEDIFF('day', l.opened, l.due) AS days_lent, EXTRACT(MONTH FROM l.opened) AS opened_month FROM loan l ORDER BY l.id LIMIT 200

@@ string functions
SELECT i.code, UPPER(i.title) AS t1, LOWER(i.category) AS c1, TRIM(i.barcode) AS b1, SUBSTRING(i.title, 1, 4) AS t4,
       LENGTH(i.title) AS n
FROM item i WHERE LOWER(i.title) LIKE ?
?? '%drill%'
=> SELECT i.code, UPPER(i.title) AS t1, LOWER(i.category) AS c1, Trim( i.barcode ) AS b1, SUBSTRING(i.title, 1, 4) AS t4, LENGTH(i.title) AS n FROM item i WHERE LOWER(i.title) LIKE ? ORDER BY i.code LIMIT 200

@@ concatenation and LIKE with ESCAPE
SELECT i.code, i.code || ' ' || i.title AS label FROM item i WHERE i.title LIKE 'Step!_%' ESCAPE '!'
=> SELECT i.code, i.code || ' ' || i.title AS label FROM item i WHERE i.title LIKE 'Step!_%' ESCAPE '!' ORDER BY i.code LIMIT 200

@@ LIMIT under the cap is kept
SELECT l.id, l.due FROM loan l ORDER BY l.due LIMIT 10
=> SELECT l.id, l.due FROM loan l ORDER BY l.due, l.id LIMIT 10

@@ LIMIT over the cap is capped
SELECT i.code FROM item i ORDER BY i.code LIMIT 5000
=> SELECT i.code FROM item i ORDER BY i.code LIMIT 200

@@ FETCH FIRST is rewritten as LIMIT
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 3 ROWS ONLY
=> SELECT i.code FROM item i ORDER BY i.code LIMIT 3

@@ TOP is rewritten as LIMIT
SELECT TOP 5 i.code FROM item i ORDER BY i.code
=> SELECT i.code FROM item i ORDER BY i.code LIMIT 5

@@ quoted identifiers in upper case (HSQLDB folds unquoted names to upper case)
SELECT "I"."CODE", "I"."TITLE" FROM "ITEM" "I" WHERE "I"."ACTIVE" = TRUE
=> SELECT "I"."CODE", "I"."TITLE" FROM "ITEM" "I" WHERE "I"."ACTIVE" = true ORDER BY "I".code LIMIT 200

@@ personal column in WHERE and ORDER BY only
SELECT l.id, l.due FROM loan l WHERE l.borrower LIKE ? ORDER BY l.borrower
?? 'F%'
=> SELECT l.id, l.due FROM loan l WHERE l.borrower LIKE ? ORDER BY l.borrower, l.id LIMIT 200

@@ personal columns in JOIN ON and GROUP BY only
SELECT COUNT(*) AS loans FROM loan l JOIN staff s ON s.code = l.staff_code AND s.name IS NOT NULL GROUP BY s.email
=> SELECT COUNT(*) AS loans FROM loan l JOIN staff s ON s.code = l.staff_code AND s.name IS NOT NULL GROUP BY s.email ORDER BY s.email LIMIT 200

@@ two parameters, BETWEEN and an IN list
SELECT x.loan_id, x.line_no, x.qty FROM loan_line x
WHERE x.qty BETWEEN ? AND ? AND x.item_code IN ('DRL-01', 'GAR-02', 'SAW-01')
?? 1 | 2
=> SELECT x.loan_id, x.line_no, x.qty FROM loan_line x WHERE x.qty BETWEEN ? AND ? AND x.item_code IN ('DRL-01', 'GAR-02', 'SAW-01') ORDER BY x.loan_id, x.line_no LIMIT 200

@@ CALL of the allow-listed procedure, parameters only
CALL open_loan_count(?)
?? 'S01'
=> CALL open_loan_count (?)

@@ ORDER BY an ordinal and a select-list alias that already cover the grouping column
SELECT l.staff_code AS desk, COUNT(*) AS n FROM loan l GROUP BY l.staff_code ORDER BY 2 DESC, desk
=> SELECT l.staff_code AS desk, COUNT(*) AS n FROM loan l GROUP BY l.staff_code ORDER BY 2 DESC, desk LIMIT 200

@@ NOT EXISTS with a qualified correlation
SELECT i.code FROM item i
WHERE NOT EXISTS (SELECT 1 FROM loan_line x WHERE x.item_code = i.code AND x.returned IS NULL) AND i.active = TRUE
=> SELECT i.code FROM item i WHERE NOT EXISTS (SELECT 1 FROM loan_line x WHERE x.item_code = i.code AND x.returned IS NULL) AND i.active = true ORDER BY i.code LIMIT 200

@@ plain comments are stripped
-- items that a desk member may waive
SELECT w.id, w.category /* the rule's category */, w.percent_off FROM waiver_rule w WHERE w.role = 'DESK'
=> SELECT w.id, w.category, w.percent_off FROM waiver_rule w WHERE w.role = 'DESK' ORDER BY w.id LIMIT 200

@@ a personal column renamed in a derived table may still filter
SELECT d.code FROM (SELECT s.code, s.name AS title FROM staff s) d WHERE d.title IS NOT NULL
=> SELECT d.code FROM (SELECT s.code, s.name AS title FROM staff s) d WHERE d.title IS NOT NULL ORDER BY d.code LIMIT 200

@@ comment and statement markers inside string literals are text
SELECT i.code FROM item i WHERE i.title <> '--x' AND i.code <> '/*' AND i.barcode <> ';'
=> SELECT i.code FROM item i WHERE i.title <> '--x' AND i.code <> '/*' AND i.barcode <> ';' ORDER BY i.code LIMIT 200
