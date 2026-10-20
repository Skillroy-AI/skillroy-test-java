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
-- BS6 reject corpus, MySQL. Every statement must be refused with the reason code after "@@", the same way twice.
-- Format: see Corpus.java.

@@ PARSE two statements
SELECT i.code FROM item i; DROP TABLE item

@@ PARSE LOCK IN SHARE MODE (the parser does not know it; refused either way)
SELECT i.code FROM item i LOCK IN SHARE MODE

@@ PARSE an incomplete statement
SELECT i.code FROM item i WHERE i.code =

@@ DML SELECT INTO a user variable
SELECT i.code INTO @c FROM item i LIMIT 1

@@ DML INSERT
INSERT INTO waiver_rule (id, category, min_days, percent_off) VALUES (9, 'POWER', 1, 100)

@@ DML UPDATE with a join
UPDATE loan l JOIN staff s ON s.code = l.staff_code SET l.status = 'RETURNED' WHERE s.code = 'S01'

@@ DML DELETE
DELETE FROM loan_line WHERE loan_id = 101

@@ DML SELECT INTO OUTFILE
SELECT i.code FROM item i INTO OUTFILE '/tmp/items.csv'

@@ DML SELECT INTO DUMPFILE
SELECT i.title FROM item i INTO DUMPFILE '/tmp/title.bin'

@@ DDL DROP TABLE
DROP TABLE audit_log

@@ DDL ALTER TABLE
ALTER TABLE item ADD COLUMN note VARCHAR(10)

@@ DDL TRUNCATE
TRUNCATE TABLE loan_line

@@ PROCEDURE CALL of a routine that is not allow-listed
CALL reset_deposits()

@@ PROCEDURE CALL with a literal argument
CALL open_loan_count('S01')

@@ SET_OPERATION UNION
SELECT i.code FROM item i UNION SELECT x.item_code FROM loan_line x

@@ SET_OPERATION UNION ALL in a derived table
SELECT d.code FROM (SELECT i.code FROM item i UNION ALL SELECT x.item_code FROM loan_line x) d

@@ LOCKING FOR UPDATE
SELECT l.id FROM loan l WHERE l.status = 'OPEN' FOR UPDATE

@@ LOCKING FOR SHARE
SELECT l.id FROM loan l FOR SHARE

@@ LOCKING an index hint
SELECT i.code FROM item i FORCE INDEX (PRIMARY) WHERE i.code > 'A'

@@ TABLE a table that is not allow-listed
SELECT a.id, a.detail FROM audit_log a

@@ TABLE a schema-qualified name
SELECT u.user FROM mysql.user u

@@ TABLE information_schema
SELECT t.table_name FROM information_schema.tables t

@@ TABLE a CTE that shadows an allow-listed table
WITH loan AS (SELECT a.id FROM audit_log a) SELECT l.id FROM loan l

@@ STAR SELECT *
SELECT * FROM item

@@ STAR qualified star
SELECT l.id, x.* FROM loan l JOIN loan_line x ON x.loan_id = l.id

@@ COLUMN a column that is not allow-listed, through an alias
SELECT s.code, s.pin_hash FROM staff s

@@ COLUMN an unknown column
SELECT i.code, i.weight FROM item i

@@ PERSONAL a personal column in the select list
SELECT l.id, l.borrower FROM loan l

@@ PERSONAL a personal column inside CONCAT
SELECT s.code, CONCAT(s.name, ' <', s.email, '>') AS contact FROM staff s

@@ FUNCTION SLEEP
SELECT i.code FROM item i WHERE SLEEP(5) = 0

@@ FUNCTION BENCHMARK
SELECT BENCHMARK(1000000, MD5(i.title)) AS b FROM item i

@@ FUNCTION LOAD_FILE
SELECT i.code, LOAD_FILE('/etc/passwd') AS f FROM item i

@@ FUNCTION a system variable
SELECT @@datadir AS d FROM item i

@@ LITERAL a string longer than 200 characters
SELECT i.code FROM item i WHERE i.title = '{{repeat 250 y}}'

@@ LITERAL a hexadecimal literal
SELECT i.code FROM item i WHERE i.barcode = 0x34303036

@@ LITERAL a double-quoted string (a string or an identifier depending on ANSI_QUOTES)
SELECT i.code FROM item i WHERE i.title = "Tile saw"

@@ LITERAL a backslash escape
SELECT i.code FROM item i WHERE i.title = 'it\'s'

@@ LITERAL a character-set introducer
SELECT i.code FROM item i WHERE i.title = _utf8mb4'Tile saw'

@@ ORDER ORDER BY a constant
SELECT i.code FROM item i ORDER BY 'x'

@@ ORDER ORDER BY inside a derived table
SELECT d.code FROM (SELECT i.code FROM item i ORDER BY i.code) d

@@ LIMIT LIMIT with an offset, MySQL style
SELECT i.code FROM item i ORDER BY i.code LIMIT 5, 10

@@ LIMIT LIMIT with OFFSET
SELECT i.code FROM item i ORDER BY i.code LIMIT 10 OFFSET 5

@@ LIMIT a parameter as the row limit
SELECT i.code FROM item i ORDER BY i.code LIMIT ?

@@ SIZE four nested subqueries
SELECT i.code FROM item i WHERE i.code IN (
  SELECT x.item_code FROM loan_line x WHERE x.loan_id IN (
    SELECT l.id FROM loan l WHERE l.staff_code IN (
      SELECT r.staff_code FROM staff_role r WHERE r.role IN (
        SELECT p.role FROM role_permission p))))

@@ SIZE nine table references
SELECT x1.loan_id FROM loan_line x1, loan_line x2, loan_line x3, loan_line x4, loan_line x5, loan_line x6,
  loan_line x7, loan_line x8, loan_line x9 WHERE x1.loan_id = x9.loan_id

@@ SIZE 21 columns in the select list
SELECT i.code{{repeat 20 , i.title}} FROM item i

@@ SIZE more than 4000 characters
SELECT i.code FROM item i WHERE i.code IN ('SAW-01'{{repeat 450 , 'SAW-01'}})

@@ COMMENT an executable comment
SELECT /*! SLEEP(1), */ i.code FROM item i

@@ COMMENT an optimizer hint comment
SELECT /*+ MAX_EXECUTION_TIME(1) */ i.code FROM item i

@@ COMMENT a nested block comment
SELECT i.code /* a /* b */ c */ FROM item i

@@ UNSUPPORTED STRAIGHT_JOIN
SELECT i.code FROM item i STRAIGHT_JOIN loan_line x ON x.item_code = i.code

@@ UNSUPPORTED SQL_CALC_FOUND_ROWS
SELECT SQL_CALC_FOUND_ROWS i.code FROM item i

@@ FUNCTION a regular-expression match
SELECT i.code FROM item i WHERE i.title REGEXP '^(a+)+$'

@@ PROCEDURE CALL of a procedure: no data channel runs a procedure call (gap 119; BS6 admitted this one)
CALL open_loan_count(?)
