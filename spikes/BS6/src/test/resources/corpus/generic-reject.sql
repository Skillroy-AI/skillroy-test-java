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
-- BS6 reject corpus, GENERIC. Every statement must be refused with the reason code after "@@", the same way twice.
-- Format: see Corpus.java.

@@ PARSE two statements
SELECT i.code FROM item i; SELECT s.code FROM staff s

@@ PARSE not SQL
SHOW ME THE LOANS

@@ PARSE an empty statement
;

@@ DML INSERT
INSERT INTO loan (id, borrower, staff_code, opened, due, status) VALUES (999, 'x', 'S01', CURRENT_DATE, CURRENT_DATE, 'OPEN')

@@ DML UPDATE
UPDATE staff SET active = FALSE

@@ DML MERGE
MERGE INTO item i USING item j ON (i.code = j.code) WHEN MATCHED THEN UPDATE SET deposit = 0

@@ DDL CREATE TABLE
CREATE TABLE note (id INTEGER)

@@ DDL ALTER TABLE
ALTER TABLE staff DROP COLUMN pin_hash

@@ PROCEDURE CALL of a routine that is not allow-listed
CALL purge_loans(?)

@@ PROCEDURE CALL with an expression argument
CALL open_loan_count(UPPER(?))

@@ SET_OPERATION INTERSECT
SELECT i.code FROM item i INTERSECT SELECT x.item_code FROM loan_line x

@@ SET_OPERATION UNION in a CTE
WITH a AS (SELECT s.code FROM staff s UNION SELECT l.staff_code FROM loan l) SELECT a.code FROM a

@@ LOCKING FOR UPDATE
SELECT l.id FROM loan l FOR UPDATE

@@ LOCKING FOR UPDATE OF
SELECT l.id FROM loan l FOR UPDATE OF l.status

@@ NOT_SELECT VALUES
VALUES (1, 2)

@@ TABLE information_schema
SELECT c.column_name FROM information_schema.columns c

@@ TABLE a quoted name in lower case
SELECT i.code FROM "item" i

@@ TABLE a table that is not allow-listed, joined
SELECT l.id FROM loan l JOIN audit_log a ON a.id = l.id

@@ TABLE a CTE that shadows an allow-listed table, in a subquery
SELECT i.code FROM item i WHERE i.code IN (WITH item AS (SELECT a.detail AS code FROM audit_log a)
SELECT k.code FROM item k)

@@ STAR SELECT *
SELECT * FROM item

@@ STAR a qualified star next to a column
SELECT l.id, x.* FROM loan l JOIN loan_line x ON x.loan_id = l.id

@@ COLUMN a column that is not allow-listed
SELECT s.pin_hash FROM staff s

@@ COLUMN an unknown column of a CTE
WITH c AS (SELECT i.code FROM item i) SELECT c.title FROM c

@@ PERSONAL a personal column in the select list
SELECT s.name FROM staff s

@@ PERSONAL a personal column inside COALESCE
SELECT l.id, COALESCE(l.borrower, 'unknown') AS who FROM loan l

@@ FUNCTION CURRENT_USER
SELECT CURRENT_USER AS who FROM item i

@@ FUNCTION a function that is not allow-listed
SELECT i.code FROM item i WHERE RANDOM() < 0.5

@@ FUNCTION a window function
SELECT i.code, RANK() OVER (ORDER BY i.deposit) AS r FROM item i

@@ LITERAL a string longer than 200 characters
SELECT i.code FROM item i WHERE i.title LIKE '{{repeat 210 %}}'

@@ LITERAL a hexadecimal literal
SELECT i.code FROM item i WHERE i.barcode = X'00'

@@ LITERAL a named parameter
SELECT i.code FROM item i WHERE i.code = :code

@@ LITERAL a numbered parameter
SELECT i.code FROM item i WHERE i.code = ?1

@@ ORDER an ordinal outside the select list
SELECT i.code FROM item i ORDER BY 0

@@ ORDER ORDER BY inside a CTE
WITH c AS (SELECT i.code FROM item i ORDER BY i.code) SELECT c.code FROM c

@@ LIMIT OFFSET
SELECT i.code FROM item i ORDER BY i.code OFFSET 3 ROWS

@@ LIMIT FETCH … WITH TIES
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 3 ROWS WITH TIES

@@ LIMIT LIMIT 0
SELECT i.code FROM item i ORDER BY i.code LIMIT 0

@@ SIZE four nested subqueries
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.loan_id IN (
  SELECT l.id FROM loan l WHERE l.staff_code IN (SELECT r.staff_code FROM staff_role r WHERE r.role IN (
    SELECT p.role FROM role_permission p))))

@@ SIZE a CTE with three nested subqueries
WITH c AS (SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.loan_id IN (
  SELECT l.id FROM loan l WHERE l.staff_code IN (SELECT r.staff_code FROM staff_role r))))
SELECT c.code FROM c

@@ SIZE nine table references
SELECT r1.role FROM role_cap r1, role_cap r2, role_cap r3, role_cap r4, role_cap r5, role_cap r6, role_cap r7,
  role_cap r8, role_cap r9

@@ SIZE more than 4000 characters
SELECT i.code FROM item i WHERE i.code IN ('X'{{repeat 900 , 'X'}})

@@ SIZE function calls nested 33 deep
SELECT i.code FROM item i WHERE i.deposit > {{repeat 33 ABS(}}1{{repeat 33 )}}

@@ COMMENT a hint comment
SELECT /*+ PARALLEL */ i.code FROM item i

@@ COMMENT a nested block comment
SELECT /* a /* b */ */ i.code FROM item i

@@ UNSUPPORTED NATURAL JOIN
SELECT x.loan_id FROM loan_line x NATURAL JOIN loan l

@@ UNSUPPORTED GROUPING SETS
SELECT l.status, l.staff_code, COUNT(*) AS n FROM loan l GROUP BY GROUPING SETS ((l.status), (l.staff_code))
