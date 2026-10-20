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
-- BS6 accept corpus, PostgreSQL (13 or newer). Every statement must be accepted and rewritten byte-identically on two
-- runs (into the "=>" text). Not executed: no PostgreSQL server in the spike. Format: see Corpus.java.

@@ key already in ORDER BY, one parameter
SELECT i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code
=> SELECT i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code LIMIT 200

@@ quoted names in lower case (PostgreSQL folds unquoted names to lower case)
SELECT "i"."code", "i"."title" FROM "item" "i" WHERE "i"."active"
=> SELECT "i"."code", "i"."title" FROM "item" "i" WHERE "i"."active" ORDER BY "i".code LIMIT 200

@@ ORDER BY a non-key column: the key is appended
SELECT i.code, i.per_day FROM item i ORDER BY i.per_day DESC NULLS LAST
=> SELECT i.code, i.per_day FROM item i ORDER BY i.per_day DESC NULLS LAST, i.code LIMIT 200

@@ join of two tables: the keys of both are appended
SELECT l.id, x.line_no, x.item_code FROM loan l JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ?
=> SELECT l.id, x.line_no, x.item_code FROM loan l JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ? ORDER BY l.id, x.loan_id, x.line_no LIMIT 200

@@ join of three tables
SELECT s.code, r.role, p.permission FROM staff s
JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role
WHERE s.active IS TRUE
=> SELECT s.code, r.role, p.permission FROM staff s JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role WHERE s.active IS TRUE ORDER BY s.code, r.staff_code, r.role, p.role, p.permission LIMIT 200

@@ left join with IS NULL and a join on USING
SELECT r.role, p.permission FROM staff_role r LEFT JOIN role_permission p USING (role) WHERE p.permission IS NULL
=> SELECT r.role, p.permission FROM staff_role r LEFT JOIN role_permission p USING (role) WHERE p.permission IS NULL ORDER BY r.staff_code, role, p.permission LIMIT 200

@@ CTE with GROUP BY
WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code)
SELECT i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC
=> WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code) SELECT i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC, i.code, lent.item_code LIMIT 200

@@ two CTEs, the second reading the first
WITH open_loans AS (SELECT l.id, l.staff_code FROM loan l WHERE l.status = 'OPEN'),
     per_desk AS (SELECT o.staff_code, COUNT(*) AS n FROM open_loans o GROUP BY o.staff_code)
SELECT d.staff_code, d.n FROM per_desk d
=> WITH open_loans AS (SELECT l.id, l.staff_code FROM loan l WHERE l.status = 'OPEN'), per_desk AS (SELECT o.staff_code, COUNT(*) AS n FROM open_loans o GROUP BY o.staff_code) SELECT d.staff_code, d.n FROM per_desk d ORDER BY d.staff_code LIMIT 200

@@ subquery in WHERE with IN
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.returned IS NULL)
=> SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.returned IS NULL) ORDER BY i.code LIMIT 200

@@ correlated EXISTS
SELECT s.code FROM staff s WHERE EXISTS (SELECT 1 FROM loan l WHERE l.staff_code = s.code AND l.status = 'OVERDUE')
=> SELECT s.code FROM staff s WHERE EXISTS (SELECT 1 FROM loan l WHERE l.staff_code = s.code AND l.status = 'OVERDUE') ORDER BY s.code LIMIT 200

@@ scalar subquery in the select list
SELECT l.id, (SELECT SUM(x.deposit_paid) FROM loan_line x WHERE x.loan_id = l.id) AS held FROM loan l
=> SELECT l.id, (SELECT SUM(x.deposit_paid) FROM loan_line x WHERE x.loan_id = l.id) AS held FROM loan l ORDER BY l.id LIMIT 200

@@ derived table with an alias and its key selected
SELECT d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status <> 'RETURNED') AS d ORDER BY d.due
=> SELECT d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status <> 'RETURNED') AS d ORDER BY d.due, d.id LIMIT 200

@@ GROUP BY with HAVING and an aggregate in ORDER BY
SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0
ORDER BY SUM(x.qty) DESC
=> SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0 ORDER BY SUM(x.qty) DESC, x.item_code LIMIT 200

@@ single-row aggregate
SELECT COUNT(*) AS loans, MIN(l.opened) AS first_opened FROM loan l
=> SELECT COUNT(*) AS loans, MIN(l.opened) AS first_opened FROM loan l LIMIT 200

@@ DISTINCT
SELECT DISTINCT l.status FROM loan l
=> SELECT DISTINCT l.status FROM loan l ORDER BY l.status LIMIT 200

@@ CASE expression
SELECT i.code, CASE WHEN i.per_day > 5 THEN 'high' ELSE 'low' END AS fee_band FROM item i
=> SELECT i.code, CASE WHEN i.per_day > 5 THEN 'high' ELSE 'low' END AS fee_band FROM item i ORDER BY i.code LIMIT 200

@@ COALESCE, NULLIF and IS DISTINCT FROM
SELECT w.id, COALESCE(w.role, 'ANY') AS needs_role, NULLIF(w.min_days, 0) AS d FROM waiver_rule w
WHERE w.role IS DISTINCT FROM 'VOLUNTEER'
=> SELECT w.id, COALESCE(w.role, 'ANY') AS needs_role, NULLIF(w.min_days, 0) AS d FROM waiver_rule w WHERE w.role IS DISTINCT FROM 'VOLUNTEER' ORDER BY w.id LIMIT 200

@@ casts with :: and CAST
SELECT i.code::text AS code_text, i.deposit::numeric(10, 2) AS deposit, CAST(i.per_day AS integer) AS rate
FROM item i
=> SELECT i.code::text AS code_text, i.deposit::numeric (10, 2) AS deposit, CAST(i.per_day AS integer) AS rate FROM item i ORDER BY i.code LIMIT 200

@@ ILIKE
SELECT i.code FROM item i WHERE i.title ILIKE ?
=> SELECT i.code FROM item i WHERE i.title ILIKE ? ORDER BY i.code LIMIT 200

@@ date arithmetic: now() minus an interval
SELECT l.id FROM loan l WHERE l.due < now() - interval '3 days'
=> SELECT l.id FROM loan l WHERE l.due < now() - INTERVAL '3 days' ORDER BY l.id LIMIT 200

@@ date arithmetic: CURRENT_DATE minus an interval and a date cast
SELECT l.id FROM loan l WHERE l.due::date < CURRENT_DATE - INTERVAL '1 month'
=> SELECT l.id FROM loan l WHERE l.due::date < CURRENT_DATE - INTERVAL '1 month' ORDER BY l.id LIMIT 200

@@ date arithmetic: date plus an integer
SELECT l.id, l.due + 7 AS grace_end FROM loan l
=> SELECT l.id, l.due + 7 AS grace_end FROM loan l ORDER BY l.id LIMIT 200

@@ date_trunc and EXTRACT
SELECT l.id, date_trunc('month', l.opened) AS month_opened, EXTRACT(DOW FROM l.due) AS weekday FROM loan l
=> SELECT l.id, date_trunc('month', l.opened) AS month_opened, EXTRACT(DOW FROM l.due) AS weekday FROM loan l ORDER BY l.id LIMIT 200

@@ string functions and concatenation
SELECT i.code, i.code || ' ' || LOWER(i.title) AS label, CONCAT(i.category, '/', i.code) AS path,
       LENGTH(i.title) AS n, SUBSTRING(i.title FROM 1 FOR 4) AS t4, TRIM(BOTH ' ' FROM i.barcode) AS b
FROM item i
=> SELECT i.code, i.code || ' ' || LOWER(i.title) AS label, CONCAT(i.category, '/', i.code) AS path, LENGTH(i.title) AS n, SUBSTRING(i.title FROM 1 FOR 4) AS t4, Trim( BOTH ' ' FROM i.barcode ) AS b FROM item i ORDER BY i.code LIMIT 200

@@ string_agg with GROUP BY
SELECT x.loan_id, string_agg(x.item_code, ',') AS items FROM loan_line x GROUP BY x.loan_id
=> SELECT x.loan_id, string_agg(x.item_code, ',') AS items FROM loan_line x GROUP BY x.loan_id ORDER BY x.loan_id LIMIT 200

@@ LIMIT under the cap is kept
SELECT l.id FROM loan l ORDER BY l.id LIMIT 50
=> SELECT l.id FROM loan l ORDER BY l.id LIMIT 50

@@ LIMIT over the cap is capped
SELECT l.id FROM loan l ORDER BY l.id LIMIT 201
=> SELECT l.id FROM loan l ORDER BY l.id LIMIT 200

@@ FETCH FIRST is rewritten as LIMIT
SELECT l.id FROM loan l ORDER BY l.id FETCH FIRST 7 ROWS ONLY
=> SELECT l.id FROM loan l ORDER BY l.id LIMIT 7

@@ personal column in WHERE and ORDER BY only
SELECT s.code FROM staff s WHERE s.name ILIKE 'a%' ORDER BY s.name
=> SELECT s.code FROM staff s WHERE s.name ILIKE 'a%' ORDER BY s.name, s.code LIMIT 200

@@ two parameters
SELECT x.loan_id, x.line_no FROM loan_line x WHERE x.item_code = ? AND x.qty > ?
=> SELECT x.loan_id, x.line_no FROM loan_line x WHERE x.item_code = ? AND x.qty > ? ORDER BY x.loan_id, x.line_no LIMIT 200
