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
-- BS6 accept corpus, MySQL (8.0 or newer). Every statement must be accepted and rewritten byte-identically on two runs
-- (into the "=>" text). Not executed: no MySQL server in the spike. Format: see Corpus.java.

@@ backtick-quoted names, key already in ORDER BY, one parameter
SELECT `i`.`code`, `i`.`title` FROM `item` `i` WHERE `i`.`category` = ? ORDER BY `i`.`code`
=> SELECT `i`.`code`, `i`.`title` FROM `item` `i` WHERE `i`.`category` = ? ORDER BY `i`.`code` LIMIT 200

@@ no ORDER BY: the key is appended and the limit injected
SELECT i.code, i.title FROM item i WHERE i.active = TRUE
=> SELECT i.code, i.title FROM item i WHERE i.active = true ORDER BY i.code LIMIT 200

@@ ORDER BY a non-key column: the key is appended as the tie-breaker
SELECT i.code, i.deposit FROM item i ORDER BY i.deposit DESC
=> SELECT i.code, i.deposit FROM item i ORDER BY i.deposit DESC, i.code LIMIT 200

@@ join of two tables: the keys of both are appended
SELECT l.id, x.line_no, x.item_code FROM loan l INNER JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ?
=> SELECT l.id, x.line_no, x.item_code FROM loan l INNER JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ? ORDER BY l.id, x.loan_id, x.line_no LIMIT 200

@@ join of three tables over staff roles and permissions
SELECT s.code, r.role, p.permission FROM staff s
JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role
WHERE s.active = 1 ORDER BY s.code
=> SELECT s.code, r.role, p.permission FROM staff s JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role WHERE s.active = 1 ORDER BY s.code, r.staff_code, r.role, p.role, p.permission LIMIT 200

@@ left join with IS NULL
SELECT i.code, i.title FROM item i LEFT OUTER JOIN loan_line x ON x.item_code = i.code WHERE x.loan_id IS NULL
=> SELECT i.code, i.title FROM item i LEFT OUTER JOIN loan_line x ON x.item_code = i.code WHERE x.loan_id IS NULL ORDER BY i.code, x.loan_id, x.line_no LIMIT 200

@@ CTE with GROUP BY (MySQL 8)
WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code)
SELECT i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC
=> WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code) SELECT i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC, i.code, lent.item_code LIMIT 200

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
SELECT l.id, (SELECT COUNT(*) FROM loan_line x WHERE x.loan_id = l.id) AS line_count FROM loan l
=> SELECT l.id, (SELECT COUNT(*) FROM loan_line x WHERE x.loan_id = l.id) AS line_count FROM loan l ORDER BY l.id LIMIT 200

@@ derived table with an alias and its key selected
SELECT d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status != 'RETURNED') AS d ORDER BY d.due
=> SELECT d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status != 'RETURNED') AS d ORDER BY d.due, d.id LIMIT 200

@@ GROUP BY with HAVING
SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0
ORDER BY total DESC
=> SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0 ORDER BY total DESC, x.item_code LIMIT 200

@@ single-row aggregate
SELECT COUNT(*) AS open_loans, MAX(l.due) AS last_due FROM loan l WHERE l.status = 'OPEN'
=> SELECT COUNT(*) AS open_loans, MAX(l.due) AS last_due FROM loan l WHERE l.status = 'OPEN' LIMIT 200

@@ DISTINCT
SELECT DISTINCT i.category FROM item i
=> SELECT DISTINCT i.category FROM item i ORDER BY i.category LIMIT 200

@@ CASE expression
SELECT i.code, CASE WHEN i.deposit >= 50 THEN 'HIGH' ELSE 'LOW' END AS band FROM item i
=> SELECT i.code, CASE WHEN i.deposit >= 50 THEN 'HIGH' ELSE 'LOW' END AS band FROM item i ORDER BY i.code LIMIT 200

@@ COALESCE, NULLIF and IFNULL
SELECT w.id, COALESCE(w.role, 'ANY') AS needs_role, NULLIF(w.percent_off, 0) AS pct, IFNULL(w.role, '-') AS r
FROM waiver_rule w
=> SELECT w.id, COALESCE(w.role, 'ANY') AS needs_role, NULLIF(w.percent_off, 0) AS pct, IFNULL(w.role, '-') AS r FROM waiver_rule w ORDER BY w.id LIMIT 200

@@ CAST to SIGNED, DECIMAL and CHAR
SELECT i.code, CAST(i.deposit AS SIGNED) AS whole, CAST(i.per_day AS DECIMAL(10, 2)) AS rate,
       CAST(i.code AS CHAR(10)) AS c
FROM item i
=> SELECT i.code, CAST(i.deposit AS SIGNED) AS whole, CAST(i.per_day AS DECIMAL (10, 2)) AS rate, CAST(i.code AS CHAR (10)) AS c FROM item i ORDER BY i.code LIMIT 200

@@ date arithmetic: DATE_ADD with an INTERVAL
SELECT l.id FROM loan l WHERE l.due < DATE_ADD(CURDATE(), INTERVAL 7 DAY)
=> SELECT l.id FROM loan l WHERE l.due < DATE_ADD(CURDATE(), INTERVAL 7 DAY) ORDER BY l.id LIMIT 200

@@ date arithmetic: CURDATE() minus an INTERVAL
SELECT l.id FROM loan l WHERE l.due < CURDATE() - INTERVAL 3 DAY
=> SELECT l.id FROM loan l WHERE l.due < CURDATE() - INTERVAL 3 DAY ORDER BY l.id LIMIT 200

@@ date arithmetic: DATE_SUB on NOW()
SELECT l.id FROM loan l WHERE l.opened > DATE_SUB(NOW(), INTERVAL 1 MONTH)
=> SELECT l.id FROM loan l WHERE l.opened > DATE_SUB(NOW(), INTERVAL 1 MONTH) ORDER BY l.id LIMIT 200

@@ DATEDIFF and TIMESTAMPDIFF
SELECT l.id, DATEDIFF(CURDATE(), l.due) AS late_days, TIMESTAMPDIFF(DAY, l.opened, l.due) AS lent_days
FROM loan l WHERE DATEDIFF(CURDATE(), l.due) > 3
=> SELECT l.id, DATEDIFF(CURDATE(), l.due) AS late_days, TIMESTAMPDIFF(DAY, l.opened, l.due) AS lent_days FROM loan l WHERE DATEDIFF(CURDATE(), l.due) > 3 ORDER BY l.id LIMIT 200

@@ YEAR, MONTH and EXTRACT
SELECT l.id, YEAR(l.due) AS y, MONTH(l.due) AS m, EXTRACT(DAY FROM l.due) AS d FROM loan l
=> SELECT l.id, YEAR(l.due) AS y, MONTH(l.due) AS m, EXTRACT(DAY FROM l.due) AS d FROM loan l ORDER BY l.id LIMIT 200

@@ string functions
SELECT i.code, CONCAT(i.code, '-', i.title) AS label, CHAR_LENGTH(i.title) AS n, UPPER(i.category) AS c,
       SUBSTRING(i.title, 1, 4) AS t4, TRIM(i.barcode) AS b
FROM item i WHERE LOWER(i.title) LIKE ?
=> SELECT i.code, CONCAT(i.code, '-', i.title) AS label, CHAR_LENGTH(i.title) AS n, UPPER(i.category) AS c, SUBSTRING(i.title, 1, 4) AS t4, Trim( i.barcode ) AS b FROM item i WHERE LOWER(i.title) LIKE ? ORDER BY i.code LIMIT 200

@@ LIMIT under the cap is kept
SELECT l.id, l.due FROM loan l ORDER BY l.due LIMIT 10
=> SELECT l.id, l.due FROM loan l ORDER BY l.due, l.id LIMIT 10

@@ LIMIT over the cap is capped
SELECT i.code FROM item i ORDER BY i.code LIMIT 1000
=> SELECT i.code FROM item i ORDER BY i.code LIMIT 200

@@ FETCH FIRST (not MySQL syntax) is rewritten as LIMIT
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 3 ROWS ONLY
=> SELECT i.code FROM item i ORDER BY i.code LIMIT 3

@@ TOP (not MySQL syntax) is rewritten as LIMIT
SELECT TOP 5 i.code FROM item i ORDER BY i.code
=> SELECT i.code FROM item i ORDER BY i.code LIMIT 5

@@ personal column in WHERE and ORDER BY only
SELECT l.id, l.due FROM loan l WHERE l.borrower LIKE ? ORDER BY l.borrower
=> SELECT l.id, l.due FROM loan l WHERE l.borrower LIKE ? ORDER BY l.borrower, l.id LIMIT 200

@@ personal columns in JOIN ON and GROUP BY only
SELECT COUNT(*) AS loans FROM loan l JOIN staff s ON s.code = l.staff_code AND s.name IS NOT NULL GROUP BY s.email
=> SELECT COUNT(*) AS loans FROM loan l JOIN staff s ON s.code = l.staff_code AND s.name IS NOT NULL GROUP BY s.email ORDER BY s.email LIMIT 200

@@ two parameters, BETWEEN and an IN list
SELECT x.loan_id, x.line_no FROM loan_line x WHERE x.qty BETWEEN ? AND ? AND x.item_code IN ('DRL-01', 'SAW-01')
=> SELECT x.loan_id, x.line_no FROM loan_line x WHERE x.qty BETWEEN ? AND ? AND x.item_code IN ('DRL-01', 'SAW-01') ORDER BY x.loan_id, x.line_no LIMIT 200

@@ a subquery on DUAL
SELECT l.id FROM loan l WHERE l.due < (SELECT CURDATE() FROM DUAL)
=> SELECT l.id FROM loan l WHERE l.due < (SELECT CURDATE() FROM DUAL) ORDER BY l.id LIMIT 200

@@ line comments, including MySQL's #, are stripped
SELECT i.code, i.title -- the title
FROM item i # every item
WHERE i.active = TRUE
=> SELECT i.code, i.title FROM item i WHERE i.active = true ORDER BY i.code LIMIT 200
