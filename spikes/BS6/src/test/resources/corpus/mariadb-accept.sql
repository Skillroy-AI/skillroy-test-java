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
-- BS6 accept corpus, MariaDB (10.6 or newer). Every statement must be accepted and rewritten byte-identically on two
-- runs (into the "=>" text). Not executed: no MariaDB server in the spike. Format: see Corpus.java.

@@ backtick-quoted names, key already in ORDER BY, one parameter
SELECT `l`.`id`, `l`.`due` FROM `loan` AS `l` WHERE `l`.`status` = ? ORDER BY `l`.`id`
=> SELECT `l`.`id`, `l`.`due` FROM `loan` AS `l` WHERE `l`.`status` = ? ORDER BY `l`.`id` LIMIT 200

@@ no ORDER BY: the key is appended and the limit injected
SELECT w.id, w.category, w.min_days FROM waiver_rule w WHERE w.percent_off >= 10
=> SELECT w.id, w.category, w.min_days FROM waiver_rule w WHERE w.percent_off >= 10 ORDER BY w.id LIMIT 200

@@ ORDER BY a non-key column: the key is appended as the tie-breaker
SELECT l.id, l.opened FROM loan l ORDER BY l.opened DESC
=> SELECT l.id, l.opened FROM loan l ORDER BY l.opened DESC, l.id LIMIT 200

@@ join of two tables: the keys of both are appended
SELECT i.code, x.loan_id, x.qty FROM item i JOIN loan_line x ON x.item_code = i.code WHERE i.category = ?
=> SELECT i.code, x.loan_id, x.qty FROM item i JOIN loan_line x ON x.item_code = i.code WHERE i.category = ? ORDER BY i.code, x.loan_id, x.line_no LIMIT 200

@@ join of three tables over staff roles and caps
SELECT s.code, r.role, c.cap, c.max_amount FROM staff s
JOIN staff_role r ON r.staff_code = s.code JOIN role_cap c ON c.role = r.role
ORDER BY c.max_amount DESC
=> SELECT s.code, r.role, c.cap, c.max_amount FROM staff s JOIN staff_role r ON r.staff_code = s.code JOIN role_cap c ON c.role = r.role ORDER BY c.max_amount DESC, s.code, r.staff_code, r.role, c.role, c.cap LIMIT 200

@@ left join with IS NULL: staff without a role
SELECT s.code FROM staff s LEFT JOIN staff_role r ON r.staff_code = s.code WHERE r.role IS NULL
=> SELECT s.code FROM staff s LEFT JOIN staff_role r ON r.staff_code = s.code WHERE r.role IS NULL ORDER BY s.code, r.staff_code, r.role LIMIT 200

@@ CTE with GROUP BY
WITH per_desk AS (SELECT l.staff_code, COUNT(*) AS loans FROM loan l GROUP BY l.staff_code)
SELECT s.code, d.loans FROM staff s JOIN per_desk d ON d.staff_code = s.code ORDER BY d.loans DESC
=> WITH per_desk AS (SELECT l.staff_code, COUNT(*) AS loans FROM loan l GROUP BY l.staff_code) SELECT s.code, d.loans FROM staff s JOIN per_desk d ON d.staff_code = s.code ORDER BY d.loans DESC, s.code, d.staff_code LIMIT 200

@@ CTE with a column list
WITH open_lines (loan_id, line_no, item) AS
  (SELECT x.loan_id, x.line_no, x.item_code FROM loan_line x WHERE x.returned IS NULL)
SELECT o.loan_id, o.line_no, o.item FROM open_lines o
=> WITH open_lines (loan_id, line_no, item) AS (SELECT x.loan_id, x.line_no, x.item_code FROM loan_line x WHERE x.returned IS NULL) SELECT o.loan_id, o.line_no, o.item FROM open_lines o ORDER BY o.loan_id, o.line_no LIMIT 200

@@ subquery in WHERE with IN
SELECT l.id FROM loan l WHERE l.staff_code IN (SELECT r.staff_code FROM staff_role r WHERE r.role = 'DESK')
=> SELECT l.id FROM loan l WHERE l.staff_code IN (SELECT r.staff_code FROM staff_role r WHERE r.role = 'DESK') ORDER BY l.id LIMIT 200

@@ correlated NOT EXISTS
SELECT i.code FROM item i WHERE NOT EXISTS (SELECT 1 FROM loan_line x WHERE x.item_code = i.code)
=> SELECT i.code FROM item i WHERE NOT EXISTS (SELECT 1 FROM loan_line x WHERE x.item_code = i.code) ORDER BY i.code LIMIT 200

@@ scalar subquery in the select list
SELECT i.code, (SELECT MAX(x.loan_id) FROM loan_line x WHERE x.item_code = i.code) AS last_loan FROM item i
=> SELECT i.code, (SELECT MAX(x.loan_id) FROM loan_line x WHERE x.item_code = i.code) AS last_loan FROM item i ORDER BY i.code LIMIT 200

@@ derived table with an alias and its key selected
SELECT d.code, d.deposit FROM (SELECT i.code, i.deposit FROM item i WHERE i.active = TRUE) d ORDER BY d.deposit
=> SELECT d.code, d.deposit FROM (SELECT i.code, i.deposit FROM item i WHERE i.active = true) d ORDER BY d.deposit, d.code LIMIT 200

@@ GROUP BY with HAVING
SELECT l.status, COUNT(*) AS n FROM loan l GROUP BY l.status HAVING COUNT(*) >= 1
=> SELECT l.status, COUNT(*) AS n FROM loan l GROUP BY l.status HAVING COUNT(*) >= 1 ORDER BY l.status LIMIT 200

@@ single-row aggregate
SELECT SUM(x.deposit_paid) AS held, AVG(x.qty) AS mean_qty FROM loan_line x WHERE x.returned IS NULL
=> SELECT SUM(x.deposit_paid) AS held, AVG(x.qty) AS mean_qty FROM loan_line x WHERE x.returned IS NULL LIMIT 200

@@ DISTINCT over two columns
SELECT DISTINCT w.category, w.role FROM waiver_rule w
=> SELECT DISTINCT w.category, w.role FROM waiver_rule w ORDER BY w.category, w.role LIMIT 200

@@ simple CASE expression
SELECT l.id, CASE l.status WHEN 'OPEN' THEN 1 WHEN 'OVERDUE' THEN 2 ELSE 0 END AS state FROM loan l
=> SELECT l.id, CASE l.status WHEN 'OPEN' THEN 1 WHEN 'OVERDUE' THEN 2 ELSE 0 END AS state FROM loan l ORDER BY l.id LIMIT 200

@@ COALESCE and IFNULL
SELECT x.loan_id, x.line_no, COALESCE(x.returned, CURDATE()) AS back, IFNULL(x.returned, '2099-01-01') AS b2
FROM loan_line x
=> SELECT x.loan_id, x.line_no, COALESCE(x.returned, CURDATE()) AS back, IFNULL(x.returned, '2099-01-01') AS b2 FROM loan_line x ORDER BY x.loan_id, x.line_no LIMIT 200

@@ CAST to DATE and to DECIMAL
SELECT l.id, CAST(l.opened AS DATE) AS opened_on, CAST(l.id AS DECIMAL(10, 0)) AS n FROM loan l
=> SELECT l.id, CAST(l.opened AS DATE) AS opened_on, CAST(l.id AS DECIMAL (10, 0)) AS n FROM loan l ORDER BY l.id LIMIT 200

@@ date arithmetic: NOW() minus an INTERVAL
SELECT l.id FROM loan l WHERE l.due < NOW() - INTERVAL 3 DAY
=> SELECT l.id FROM loan l WHERE l.due < NOW() - INTERVAL 3 DAY ORDER BY l.id LIMIT 200

@@ date arithmetic: DATE_ADD on a column
SELECT l.id, DATE_ADD(l.due, INTERVAL 14 DAY) AS grace FROM loan l
=> SELECT l.id, DATE_ADD(l.due, INTERVAL 14 DAY) AS grace FROM loan l ORDER BY l.id LIMIT 200

@@ TIMESTAMPDIFF and DATEDIFF
SELECT l.id FROM loan l WHERE TIMESTAMPDIFF(DAY, l.opened, l.due) > 7 OR DATEDIFF(l.due, l.opened) > 7
=> SELECT l.id FROM loan l WHERE TIMESTAMPDIFF(DAY, l.opened, l.due) > 7 OR DATEDIFF(l.due, l.opened) > 7 ORDER BY l.id LIMIT 200

@@ string functions
SELECT i.code, CONCAT(UPPER(SUBSTRING(i.title, 1, 1)), LOWER(SUBSTRING(i.title, 2))) AS nice,
       LENGTH(i.barcode) AS len
FROM item i
=> SELECT i.code, CONCAT(UPPER(SUBSTRING(i.title, 1, 1)), LOWER(SUBSTRING(i.title, 2))) AS nice, LENGTH(i.barcode) AS len FROM item i ORDER BY i.code LIMIT 200

@@ LIMIT under the cap is kept
SELECT i.code FROM item i ORDER BY i.code LIMIT 25
=> SELECT i.code FROM item i ORDER BY i.code LIMIT 25

@@ LIMIT over the cap is capped
SELECT x.loan_id, x.line_no FROM loan_line x ORDER BY x.loan_id, x.line_no LIMIT 999
=> SELECT x.loan_id, x.line_no FROM loan_line x ORDER BY x.loan_id, x.line_no LIMIT 200

@@ FETCH FIRST (MariaDB 10.6 syntax) is rewritten as LIMIT
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 4 ROWS ONLY
=> SELECT i.code FROM item i ORDER BY i.code LIMIT 4

@@ personal column in WHERE only
SELECT s.code FROM staff s WHERE s.email LIKE ?
=> SELECT s.code FROM staff s WHERE s.email LIKE ? ORDER BY s.code LIMIT 200

@@ personal column in ORDER BY only
SELECT l.id FROM loan l ORDER BY l.borrower DESC
=> SELECT l.id FROM loan l ORDER BY l.borrower DESC, l.id LIMIT 200

@@ three parameters
SELECT x.loan_id, x.line_no FROM loan_line x WHERE x.loan_id = ? AND x.qty >= ? AND x.item_code <> ?
=> SELECT x.loan_id, x.line_no FROM loan_line x WHERE x.loan_id = ? AND x.qty >= ? AND x.item_code <> ? ORDER BY x.loan_id, x.line_no LIMIT 200

@@ CALL of the allow-listed procedure
CALL open_loan_count(?)
=> CALL open_loan_count (?)

@@ a subquery on DUAL
SELECT i.code FROM item i WHERE i.deposit > (SELECT 30 FROM DUAL)
=> SELECT i.code FROM item i WHERE i.deposit > (SELECT 30 FROM DUAL) ORDER BY i.code LIMIT 200

@@ block and # comments are stripped
SELECT /* code only */ i.code FROM item i # active ones
WHERE i.active = TRUE
=> SELECT i.code FROM item i WHERE i.active = true ORDER BY i.code LIMIT 200
