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
-- BS6 accept corpus, GENERIC (standard SQL; parsed with JSqlParser's ANSI_SQL preset). Every statement must be
-- accepted and rewritten byte-identically on two runs (into the "=>" text). Format: see Corpus.java.

@@ FETCH FIRST written by the model is kept
SELECT i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code FETCH FIRST 10 ROWS ONLY
=> SELECT i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code FETCH FIRST 10 ROWS ONLY

@@ FETCH NEXT is the same limit
SELECT i.code FROM item i ORDER BY i.code FETCH NEXT 5 ROWS ONLY
=> SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 5 ROWS ONLY

@@ no ORDER BY: the key is appended and FETCH FIRST injected
SELECT l.id, l.status FROM loan l WHERE l.status IN ('OPEN', 'OVERDUE')
=> SELECT l.id, l.status FROM loan l WHERE l.status IN ('OPEN', 'OVERDUE') ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ ORDER BY a non-key column: the key is appended
SELECT i.code, i.per_day FROM item i ORDER BY i.per_day DESC
=> SELECT i.code, i.per_day FROM item i ORDER BY i.per_day DESC, i.code FETCH FIRST 200 ROWS ONLY

@@ join of two tables
SELECT l.id, x.line_no FROM loan l JOIN loan_line x ON x.loan_id = l.id WHERE l.due < CURRENT_DATE
=> SELECT l.id, x.line_no FROM loan l JOIN loan_line x ON x.loan_id = l.id WHERE l.due < CURRENT_DATE ORDER BY l.id, x.loan_id, x.line_no FETCH FIRST 200 ROWS ONLY

@@ right outer join
SELECT x.loan_id, x.line_no, i.code FROM loan_line x RIGHT OUTER JOIN item i ON i.code = x.item_code
=> SELECT x.loan_id, x.line_no, i.code FROM loan_line x RIGHT OUTER JOIN item i ON i.code = x.item_code ORDER BY x.loan_id, x.line_no, i.code FETCH FIRST 200 ROWS ONLY

@@ full outer join
SELECT s.code, r.role FROM staff s FULL OUTER JOIN staff_role r ON r.staff_code = s.code
=> SELECT s.code, r.role FROM staff s FULL OUTER JOIN staff_role r ON r.staff_code = s.code ORDER BY s.code, r.staff_code, r.role FETCH FIRST 200 ROWS ONLY

@@ cross join
SELECT r.role, c.cap FROM staff_role r CROSS JOIN role_cap c WHERE c.role = r.role
=> SELECT r.role, c.cap FROM staff_role r CROSS JOIN role_cap c WHERE c.role = r.role ORDER BY r.staff_code, r.role, c.role, c.cap FETCH FIRST 200 ROWS ONLY

@@ CTE with GROUP BY
WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code)
SELECT i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code
=> WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code) SELECT i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY i.code, lent.item_code FETCH FIRST 200 ROWS ONLY

@@ subquery in WHERE with a comparison
SELECT i.code FROM item i WHERE i.deposit > (SELECT AVG(j.deposit) FROM item j)
=> SELECT i.code FROM item i WHERE i.deposit > (SELECT AVG(j.deposit) FROM item j) ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ correlated NOT EXISTS
SELECT s.code FROM staff s WHERE NOT EXISTS (SELECT 1 FROM loan l WHERE l.staff_code = s.code)
=> SELECT s.code FROM staff s WHERE NOT EXISTS (SELECT 1 FROM loan l WHERE l.staff_code = s.code) ORDER BY s.code FETCH FIRST 200 ROWS ONLY

@@ scalar subquery in the select list
SELECT r.role, (SELECT COUNT(*) FROM role_permission p WHERE p.role = r.role) AS n FROM staff_role r
=> SELECT r.role, (SELECT COUNT(*) FROM role_permission p WHERE p.role = r.role) AS n FROM staff_role r ORDER BY r.staff_code, r.role FETCH FIRST 200 ROWS ONLY

@@ derived table with an alias and its key selected
SELECT d.id FROM (SELECT l.id FROM loan l WHERE l.status = 'OPEN') AS d
=> SELECT d.id FROM (SELECT l.id FROM loan l WHERE l.status = 'OPEN') AS d ORDER BY d.id FETCH FIRST 200 ROWS ONLY

@@ GROUP BY with HAVING
SELECT l.staff_code, COUNT(*) AS n FROM loan l GROUP BY l.staff_code HAVING COUNT(*) > 1
=> SELECT l.staff_code, COUNT(*) AS n FROM loan l GROUP BY l.staff_code HAVING COUNT(*) > 1 ORDER BY l.staff_code FETCH FIRST 200 ROWS ONLY

@@ single-row aggregate
SELECT MIN(i.deposit) AS lo, MAX(i.deposit) AS hi, AVG(i.deposit) AS mean FROM item i
=> SELECT MIN(i.deposit) AS lo, MAX(i.deposit) AS hi, AVG(i.deposit) AS mean FROM item i FETCH FIRST 200 ROWS ONLY

@@ DISTINCT
SELECT DISTINCT x.item_code FROM loan_line x
=> SELECT DISTINCT x.item_code FROM loan_line x ORDER BY x.item_code FETCH FIRST 200 ROWS ONLY

@@ searched CASE
SELECT w.id, CASE WHEN w.percent_off >= 50 THEN 'half' WHEN w.percent_off >= 25 THEN 'quarter' END AS size
FROM waiver_rule w
=> SELECT w.id, CASE WHEN w.percent_off >= 50 THEN 'half' WHEN w.percent_off >= 25 THEN 'quarter' END AS size FROM waiver_rule w ORDER BY w.id FETCH FIRST 200 ROWS ONLY

@@ COALESCE and NULLIF
SELECT x.loan_id, x.line_no, COALESCE(x.returned, CURRENT_DATE) AS back, NULLIF(x.qty, 1) AS extra FROM loan_line x
=> SELECT x.loan_id, x.line_no, COALESCE(x.returned, CURRENT_DATE) AS back, NULLIF(x.qty, 1) AS extra FROM loan_line x ORDER BY x.loan_id, x.line_no FETCH FIRST 200 ROWS ONLY

@@ CAST
SELECT i.code, CAST(i.deposit AS INTEGER) AS whole, CAST(i.code AS VARCHAR(32)) AS c FROM item i
=> SELECT i.code, CAST(i.deposit AS INTEGER) AS whole, CAST(i.code AS VARCHAR (32)) AS c FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ CURRENT_DATE minus an interval
SELECT l.id FROM loan l WHERE l.due < CURRENT_DATE - INTERVAL '7' DAY
=> SELECT l.id FROM loan l WHERE l.due < CURRENT_DATE - INTERVAL '7' DAY ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ DATE and TIMESTAMP literals
SELECT l.id FROM loan l WHERE l.opened >= DATE '2026-09-01' AND l.opened < TIMESTAMP '2026-10-01 00:00:00'
=> SELECT l.id FROM loan l WHERE l.opened >= DATE '2026-09-01' AND l.opened < TIMESTAMP '2026-10-01 00:00:00' ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ EXTRACT and CURRENT_TIMESTAMP
SELECT l.id, EXTRACT(MONTH FROM l.due) AS m FROM loan l WHERE l.opened < CURRENT_TIMESTAMP
=> SELECT l.id, EXTRACT(MONTH FROM l.due) AS m FROM loan l WHERE l.opened < CURRENT_TIMESTAMP ORDER BY l.id FETCH FIRST 200 ROWS ONLY

@@ string functions
SELECT i.code, UPPER(i.title) AS t, CHAR_LENGTH(i.title) AS n, POSITION('drill' IN LOWER(i.title)) AS p,
       SUBSTRING(i.title FROM 1 FOR 4) AS t4, i.code || ':' || i.category AS k
FROM item i
=> SELECT i.code, UPPER(i.title) AS t, CHAR_LENGTH(i.title) AS n, POSITION('drill' IN LOWER(i.title)) AS p, SUBSTRING(i.title FROM 1 FOR 4) AS t4, i.code || ':' || i.category AS k FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ boolean literal and IS DISTINCT FROM
SELECT w.id FROM waiver_rule w WHERE w.role IS DISTINCT FROM 'DESK' AND TRUE
=> SELECT w.id FROM waiver_rule w WHERE w.role IS DISTINCT FROM 'DESK' AND true ORDER BY w.id FETCH FIRST 200 ROWS ONLY

@@ LIKE with ESCAPE and BETWEEN
SELECT i.code FROM item i WHERE i.title LIKE '%!_%' ESCAPE '!' OR i.deposit BETWEEN 20 AND 40
=> SELECT i.code FROM item i WHERE i.title LIKE '%!_%' ESCAPE '!' OR i.deposit BETWEEN 20 AND 40 ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ quoted names in upper case
SELECT "R"."ROLE", "R"."PERMISSION" FROM "ROLE_PERMISSION" "R"
=> SELECT "R"."ROLE", "R"."PERMISSION" FROM "ROLE_PERMISSION" "R" ORDER BY "R".role, "R".permission FETCH FIRST 200 ROWS ONLY

@@ LIMIT is rewritten as FETCH FIRST
SELECT i.code FROM item i ORDER BY i.code LIMIT 3
=> SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 3 ROWS ONLY

@@ TOP is rewritten as FETCH FIRST
SELECT TOP 3 i.code FROM item i ORDER BY i.code
=> SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 3 ROWS ONLY

@@ FETCH FIRST over the cap is capped
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 300 ROWS ONLY
=> SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 200 ROWS ONLY

@@ personal columns in WHERE, JOIN ON and ORDER BY only
SELECT l.id FROM loan l JOIN staff s ON s.code = l.staff_code AND s.email IS NOT NULL WHERE l.borrower <> s.name
ORDER BY l.borrower
=> SELECT l.id FROM loan l JOIN staff s ON s.code = l.staff_code AND s.email IS NOT NULL WHERE l.borrower <> s.name ORDER BY l.borrower, l.id, s.code FETCH FIRST 200 ROWS ONLY
