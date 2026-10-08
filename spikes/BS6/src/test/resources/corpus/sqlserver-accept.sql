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
-- BS6 accept corpus, SQL Server (2016 or newer). Every statement must be accepted and rewritten byte-identically on
-- two runs (into the "=>" text). Not executed: no SQL Server in the spike. Format: see Corpus.java.

@@ TOP written by the model is kept
SELECT TOP 10 i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code
=> SELECT TOP 10 i.code, i.title FROM item i WHERE i.category = ? ORDER BY i.code

@@ bracket-quoted names
SELECT [i].[code], [i].[title] FROM [item] AS [i] WHERE [i].[active] = 1
=> SELECT TOP 200 [i].[code], [i].[title] FROM [item] AS [i] WHERE [i].[active] = 1 ORDER BY [i].code

@@ no ORDER BY: the key is appended and TOP injected
SELECT l.id, l.due FROM loan l WHERE l.status = 'OPEN'
=> SELECT TOP 200 l.id, l.due FROM loan l WHERE l.status = 'OPEN' ORDER BY l.id

@@ ORDER BY a non-key column: the key is appended
SELECT i.code, i.deposit FROM item i ORDER BY i.deposit DESC
=> SELECT TOP 200 i.code, i.deposit FROM item i ORDER BY i.deposit DESC, i.code

@@ join of two tables: the keys of both are appended
SELECT l.id, x.line_no, x.item_code FROM loan l INNER JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ?
=> SELECT TOP 200 l.id, x.line_no, x.item_code FROM loan l INNER JOIN loan_line x ON x.loan_id = l.id WHERE l.status = ? ORDER BY l.id, x.loan_id, x.line_no

@@ join of three tables
SELECT s.code, r.role, p.permission FROM staff s
JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role
=> SELECT TOP 200 s.code, r.role, p.permission FROM staff s JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role ORDER BY s.code, r.staff_code, r.role, p.role, p.permission

@@ left join with IS NULL
SELECT i.code FROM item i LEFT JOIN loan_line x ON x.item_code = i.code WHERE x.loan_id IS NULL
=> SELECT TOP 200 i.code FROM item i LEFT JOIN loan_line x ON x.item_code = i.code WHERE x.loan_id IS NULL ORDER BY i.code, x.loan_id, x.line_no

@@ CTE with GROUP BY
WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code)
SELECT i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC
=> WITH lent AS (SELECT x.item_code, COUNT(*) AS times FROM loan_line x GROUP BY x.item_code) SELECT TOP 200 i.code, lent.times FROM item i JOIN lent ON lent.item_code = i.code ORDER BY lent.times DESC, i.code, lent.item_code

@@ CTE with a column list
WITH overdue (loan_id, desk) AS (SELECT l.id, l.staff_code FROM loan l WHERE l.status = 'OVERDUE')
SELECT o.loan_id, o.desk FROM overdue o
=> WITH overdue (loan_id, desk) AS (SELECT l.id, l.staff_code FROM loan l WHERE l.status = 'OVERDUE') SELECT TOP 200 o.loan_id, o.desk FROM overdue o ORDER BY o.loan_id

@@ subquery in WHERE with IN
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.returned IS NULL)
=> SELECT TOP 200 i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.returned IS NULL) ORDER BY i.code

@@ correlated EXISTS
SELECT s.code FROM staff s WHERE EXISTS (SELECT 1 FROM staff_role r WHERE r.staff_code = s.code)
=> SELECT TOP 200 s.code FROM staff s WHERE EXISTS (SELECT 1 FROM staff_role r WHERE r.staff_code = s.code) ORDER BY s.code

@@ scalar subquery in the select list
SELECT l.id, (SELECT COUNT(*) FROM loan_line x WHERE x.loan_id = l.id) AS line_count FROM loan l
=> SELECT TOP 200 l.id, (SELECT COUNT(*) FROM loan_line x WHERE x.loan_id = l.id) AS line_count FROM loan l ORDER BY l.id

@@ derived table with an alias and its key selected
SELECT d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status <> 'RETURNED') AS d ORDER BY d.due
=> SELECT TOP 200 d.id, d.due FROM (SELECT l.id, l.due FROM loan l WHERE l.status <> 'RETURNED') AS d ORDER BY d.due, d.id

@@ GROUP BY with HAVING
SELECT x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0
=> SELECT TOP 200 x.item_code, SUM(x.qty) AS total FROM loan_line x GROUP BY x.item_code HAVING SUM(x.qty) > 0 ORDER BY x.item_code

@@ single-row aggregate
SELECT COUNT(*) AS loans FROM loan l WHERE l.status = 'OPEN'
=> SELECT TOP 200 COUNT(*) AS loans FROM loan l WHERE l.status = 'OPEN'

@@ DISTINCT with TOP
SELECT DISTINCT TOP 50 i.category FROM item i
=> SELECT DISTINCT TOP 50 i.category FROM item i ORDER BY i.category

@@ CASE and IIF
SELECT i.code, CASE WHEN i.deposit >= 50 THEN 'HIGH' ELSE 'LOW' END AS band, IIF(i.active = 1, 'Y', 'N') AS on_shelf
FROM item i
=> SELECT TOP 200 i.code, CASE WHEN i.deposit >= 50 THEN 'HIGH' ELSE 'LOW' END AS band, IIF(i.active = 1, 'Y', 'N') AS on_shelf FROM item i ORDER BY i.code

@@ ISNULL, COALESCE and NULLIF
SELECT w.id, ISNULL(w.role, 'ANY') AS r1, COALESCE(w.role, 'ANY') AS r2, NULLIF(w.min_days, 0) AS d FROM waiver_rule w
=> SELECT TOP 200 w.id, ISNULL(w.role, 'ANY') AS r1, COALESCE(w.role, 'ANY') AS r2, NULLIF(w.min_days, 0) AS d FROM waiver_rule w ORDER BY w.id

@@ CAST and CONVERT with a style
SELECT l.id, CAST(l.due AS DATE) AS due_on, CONVERT(VARCHAR(10), l.opened, 120) AS opened_text FROM loan l
=> SELECT TOP 200 l.id, CAST(l.due AS DATE) AS due_on, CONVERT( VARCHAR (10), l.opened, 120 ) AS opened_text FROM loan l ORDER BY l.id

@@ DATEADD with a full date part and GETDATE()
SELECT l.id FROM loan l WHERE l.due < DATEADD(day, -7, GETDATE())
=> SELECT TOP 200 l.id FROM loan l WHERE l.due < DATEADD(DAY, -7, GETDATE()) ORDER BY l.id

@@ DATEADD with an abbreviated date part (read as a column by the parser)
SELECT l.id FROM loan l WHERE l.due < DATEADD(dd, -7, CAST(GETDATE() AS DATE))
=> SELECT TOP 200 l.id FROM loan l WHERE l.due < DATEADD(dd, -7, CAST(GETDATE() AS DATE)) ORDER BY l.id

@@ DATEDIFF
SELECT l.id, DATEDIFF(day, l.opened, l.due) AS lent_days FROM loan l WHERE DATEDIFF(day, l.due, GETDATE()) > 3
=> SELECT TOP 200 l.id, DATEDIFF(DAY, l.opened, l.due) AS lent_days FROM loan l WHERE DATEDIFF(DAY, l.due, GETDATE()) > 3 ORDER BY l.id

@@ YEAR, MONTH and CURRENT_TIMESTAMP
SELECT l.id, YEAR(l.due) AS y, MONTH(l.due) AS m FROM loan l WHERE l.opened < CURRENT_TIMESTAMP
=> SELECT TOP 200 l.id, YEAR(l.due) AS y, MONTH(l.due) AS m FROM loan l WHERE l.opened < CURRENT_TIMESTAMP ORDER BY l.id

@@ string functions and + concatenation
SELECT i.code, UPPER(i.title) AS t, LEN(i.title) AS n, SUBSTRING(i.title, 1, 4) AS t4, i.code + '-' + i.category AS p,
       CONCAT(i.code, '/', i.category) AS q
FROM item i
=> SELECT TOP 200 i.code, UPPER(i.title) AS t, LEN(i.title) AS n, SUBSTRING(i.title, 1, 4) AS t4, i.code + '-' + i.category AS p, CONCAT(i.code, '/', i.category) AS q FROM item i ORDER BY i.code

@@ a national string literal
SELECT i.code FROM item i WHERE i.title = N'Tile saw'
=> SELECT TOP 200 i.code FROM item i WHERE i.title = N'Tile saw' ORDER BY i.code

@@ TOP in parentheses becomes plain TOP
SELECT TOP (5) i.code FROM item i ORDER BY i.code
=> SELECT TOP 5 i.code FROM item i ORDER BY i.code

@@ TOP over the cap is capped
SELECT TOP 1000 i.code FROM item i ORDER BY i.code
=> SELECT TOP 200 i.code FROM item i ORDER BY i.code

@@ LIMIT is rewritten as TOP
SELECT i.code FROM item i ORDER BY i.code LIMIT 10
=> SELECT TOP 10 i.code FROM item i ORDER BY i.code

@@ FETCH FIRST without OFFSET is rewritten as TOP
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 3 ROWS ONLY
=> SELECT TOP 3 i.code FROM item i ORDER BY i.code

@@ personal column in WHERE and ORDER BY only
SELECT l.id FROM loan l WHERE l.borrower LIKE ? ORDER BY l.borrower
=> SELECT TOP 200 l.id FROM loan l WHERE l.borrower LIKE ? ORDER BY l.borrower, l.id

@@ CALL of the allow-listed procedure (not T-SQL: the channel must run it as a JDBC {call …} escape)
CALL open_loan_count(?)
=> CALL open_loan_count (?)
