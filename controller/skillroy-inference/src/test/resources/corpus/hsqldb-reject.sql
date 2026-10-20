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
-- BS6 reject corpus, HSQLDB. Every statement must be refused with the reason code after "@@", the same way twice.
-- Format: see Corpus.java.

@@ PARSE two statements
SELECT i.code FROM item i; DROP TABLE item

@@ PARSE an incomplete statement
SELECT i.code FROM item i WHERE

@@ PARSE an unterminated string literal
SELECT i.code FROM item i WHERE i.title = 'Cordless

@@ PARSE backtick quoting is not HSQLDB syntax
SELECT `code` FROM item

@@ DML INSERT
INSERT INTO waiver_rule (id, category, min_days, percent_off, role) VALUES (9, 'POWER', 1, 100, NULL)

@@ DML UPDATE
UPDATE loan SET status = 'RETURNED' WHERE id = 102

@@ DML DELETE
DELETE FROM loan_line WHERE loan_id = 101

@@ DML SELECT INTO a new table
SELECT i.code INTO item_copy FROM item i

@@ DDL DROP TABLE
DROP TABLE audit_log

@@ DDL CREATE TABLE
CREATE TABLE scratch (id INTEGER)

@@ DDL TRUNCATE
TRUNCATE TABLE loan_line

@@ PROCEDURE CALL of a routine that is not allow-listed
CALL reset_deposits(?)

@@ PROCEDURE CALL of the allow-listed routine with a literal argument
CALL open_loan_count('S01')

@@ SET_OPERATION UNION
SELECT i.code FROM item i UNION SELECT x.item_code FROM loan_line x

@@ SET_OPERATION UNION ALL inside a CTE
WITH codes AS (SELECT i.code FROM item i UNION ALL SELECT x.item_code FROM loan_line x)
SELECT c.code FROM codes c

@@ LOCKING FOR UPDATE
SELECT l.id FROM loan l WHERE l.status = 'OPEN' FOR UPDATE

@@ LOCKING a table hint
SELECT l.id FROM loan l WITH (NOLOCK)

@@ TABLE a table that is not allow-listed
SELECT a.id, a.detail FROM audit_log a

@@ TABLE a schema-qualified name
SELECT t.table_name FROM information_schema.tables t

@@ TABLE a CTE that shadows an allow-listed table
WITH item AS (SELECT a.id AS code FROM audit_log a) SELECT i.code FROM item i

@@ TABLE a quoted name in the wrong case (HSQLDB stores ITEM)
SELECT i.code FROM "item" i

@@ TABLE a table that is not allow-listed, in a subquery
SELECT i.code FROM item i WHERE i.code IN (SELECT a.detail FROM audit_log a)

@@ STAR SELECT *
SELECT * FROM item

@@ STAR qualified star
SELECT i.* FROM item i

@@ COLUMN a column that is not allow-listed, through an alias
SELECT s.code, s.pin_hash FROM staff s

@@ COLUMN a column that is not allow-listed, in WHERE
SELECT s.code FROM staff s WHERE s.pin_hash = ?

@@ COLUMN an alias that names nothing
SELECT z.code FROM item i

@@ COLUMN an unqualified correlated reference
SELECT i.code FROM item i WHERE EXISTS (SELECT 1 FROM loan_line x WHERE x.item_code = code)

@@ PERSONAL a personal column in the select list
SELECT l.id, l.borrower FROM loan l

@@ PERSONAL a personal column carried through a CTE
WITH b AS (SELECT l.id, l.borrower AS who FROM loan l) SELECT b.id, b.who FROM b

@@ PERSONAL a personal column inside a select-list expression
SELECT s.code, UPPER(s.name) AS shout FROM staff s

@@ FUNCTION LOAD_FILE reads a file on the database host
SELECT i.code, LOAD_FILE('/etc/hosts') AS f FROM item i

@@ FUNCTION a function that is not allow-listed
SELECT i.code FROM item i WHERE RAND() > 0.5

@@ FUNCTION a window function
SELECT i.code, ROW_NUMBER() OVER (ORDER BY i.code) AS rn FROM item i

@@ FUNCTION CAST to a large-object type
SELECT i.code, CAST(i.title AS BLOB) AS b FROM item i

@@ LITERAL a string longer than 200 characters
SELECT i.code FROM item i WHERE i.title = '{{repeat 201 x}}'

@@ LITERAL a hexadecimal literal
SELECT i.code FROM item i WHERE i.barcode = X'34303036'

@@ LITERAL a named parameter
SELECT i.code FROM item i WHERE i.code = :code

@@ LITERAL a backslash in a string literal
SELECT i.code FROM item i WHERE i.title LIKE 'Step\_%'

@@ ORDER ORDER BY a constant
SELECT i.code FROM item i ORDER BY 'x'

@@ ORDER an ordinal outside the select list
SELECT i.code FROM item i ORDER BY 3

@@ ORDER ORDER BY inside a derived table
SELECT d.code FROM (SELECT i.code FROM item i ORDER BY i.code) d

@@ ORDER a derived table whose key is not selected
SELECT d.title FROM (SELECT i.title FROM item i) d

@@ LIMIT LIMIT with OFFSET
SELECT i.code FROM item i ORDER BY i.code LIMIT 10 OFFSET 20

@@ LIMIT OFFSET with FETCH
SELECT i.code FROM item i ORDER BY i.code OFFSET 5 ROWS FETCH NEXT 5 ROWS ONLY

@@ LIMIT a parameter as the row limit
SELECT i.code FROM item i ORDER BY i.code LIMIT ?

@@ LIMIT zero rows
SELECT i.code FROM item i ORDER BY i.code LIMIT 0

@@ LIMIT a row limit inside a subquery
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x LIMIT 5)

@@ SIZE four nested subqueries
SELECT i.code FROM item i WHERE i.code IN (
  SELECT x.item_code FROM loan_line x WHERE x.loan_id IN (
    SELECT l.id FROM loan l WHERE l.staff_code IN (
      SELECT r.staff_code FROM staff_role r WHERE r.role IN (
        SELECT p.role FROM role_permission p))))

@@ SIZE nine table references
SELECT x1.loan_id FROM loan_line x1
JOIN loan_line x2 ON x2.loan_id = x1.loan_id JOIN loan_line x3 ON x3.loan_id = x1.loan_id
JOIN loan_line x4 ON x4.loan_id = x1.loan_id JOIN loan_line x5 ON x5.loan_id = x1.loan_id
JOIN loan_line x6 ON x6.loan_id = x1.loan_id JOIN loan_line x7 ON x7.loan_id = x1.loan_id
JOIN loan_line x8 ON x8.loan_id = x1.loan_id JOIN loan_line x9 ON x9.loan_id = x1.loan_id

@@ SIZE 21 columns in the select list
SELECT i.code{{repeat 20 , i.title}} FROM item i

@@ SIZE more than 4000 characters
SELECT i.code FROM item i WHERE i.code IN ('DRL-01'{{repeat 450 , 'DRL-01'}})

@@ SIZE parentheses nested 40 deep (parse time and stack depth grow with nesting)
SELECT i.code FROM item i WHERE {{repeat 40 (}}i.deposit > 1{{repeat 40 )}}

@@ COMMENT an optimizer hint comment
SELECT /*+ INDEX(i) */ i.code FROM item i

@@ COMMENT a nested block comment
SELECT i.code /* outer /* inner */ */ FROM item i

@@ COMMENT an unterminated block comment
SELECT i.code FROM item i /* trailing

@@ COMMENT a hint in a line comment
SELECT i.code FROM item i --+ FULL(i)

@@ UNSUPPORTED a recursive CTE
WITH RECURSIVE r (code) AS (SELECT i.code FROM item i) SELECT r.code FROM r

@@ UNSUPPORTED NATURAL JOIN
SELECT l.id FROM loan l NATURAL JOIN loan_line x

@@ PERSONAL a personal column renamed in a derived table
SELECT d.title FROM (SELECT s.code, s.name AS title FROM staff s) d

@@ PROCEDURE CALL of a procedure: no data channel runs a procedure call (gap 119; BS6 admitted this one)
CALL open_loan_count(?)
