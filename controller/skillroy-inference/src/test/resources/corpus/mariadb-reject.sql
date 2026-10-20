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
-- BS6 reject corpus, MariaDB. Every statement must be refused with the reason code after "@@", the same way twice.
-- Format: see Corpus.java.

@@ PARSE two statements
SELECT l.id FROM loan l; DELETE FROM loan

@@ PARSE LIMIT … ROWS EXAMINED (the parser does not know it)
SELECT i.code FROM item i ORDER BY i.code LIMIT 10 ROWS EXAMINED 1000

@@ PARSE an unterminated quoted identifier
SELECT `code FROM item

@@ DML INSERT … SELECT
INSERT INTO waiver_rule (id, category, min_days, percent_off) SELECT 10, i.category, 1, 100 FROM item i

@@ DML REPLACE
REPLACE INTO role_cap (role, cap, max_amount) VALUES ('DESK', 'WAIVER', 1000)

@@ DML UPDATE
UPDATE item SET deposit = 0

@@ DML SELECT INTO OUTFILE
SELECT s.code FROM staff s INTO OUTFILE '/tmp/staff.txt'

@@ DDL CREATE TABLE AS
CREATE TABLE loan_copy AS SELECT l.id FROM loan l

@@ DDL DROP
DROP TABLE loan_line

@@ PROCEDURE CALL of a routine that is not allow-listed
CALL mysql.rds_kill(1)

@@ PROCEDURE CALL with a literal argument
CALL open_loan_count('S02')

@@ SET_OPERATION INTERSECT
SELECT i.code FROM item i INTERSECT SELECT x.item_code FROM loan_line x

@@ SET_OPERATION EXCEPT
SELECT i.code FROM item i EXCEPT SELECT x.item_code FROM loan_line x

@@ LOCKING FOR UPDATE SKIP LOCKED
SELECT l.id FROM loan l FOR UPDATE SKIP LOCKED

@@ LOCKING an index hint
SELECT l.id FROM loan l USE INDEX (PRIMARY)

@@ TABLE a table that is not allow-listed
SELECT a.actor FROM audit_log a

@@ TABLE a schema-qualified name
SELECT i.code FROM lending.item i

@@ TABLE a CTE that shadows an allow-listed table
WITH staff AS (SELECT a.actor AS code FROM audit_log a) SELECT s.code FROM staff s

@@ STAR SELECT *
SELECT * FROM loan

@@ STAR COUNT of a qualified star
SELECT COUNT(i.*) AS n FROM item i

@@ COLUMN a column that is not allow-listed
SELECT s.pin_hash FROM staff s WHERE s.code = 'S01'

@@ COLUMN an ambiguous unqualified column
SELECT code FROM item i JOIN staff s ON s.code = i.code

@@ PERSONAL a personal column in the select list
SELECT s.code, s.email FROM staff s

@@ PERSONAL a personal column through a derived table
SELECT d.n FROM (SELECT s.code, s.name AS n FROM staff s) d

@@ FUNCTION SLEEP
SELECT i.code FROM item i WHERE i.deposit > SLEEP(3)

@@ FUNCTION LOAD_FILE
SELECT LOAD_FILE('/var/lib/mysql/my.cnf') AS f FROM item i

@@ FUNCTION a sequence
SELECT NEXT VALUE FOR loan_seq AS n FROM item i

@@ FUNCTION a session variable
SELECT @@version AS v FROM item i

@@ LITERAL a string longer than 200 characters
SELECT i.code FROM item i WHERE i.title <> '{{repeat 220 z}}'

@@ LITERAL a hexadecimal literal
SELECT i.code FROM item i WHERE i.code = X'4452'

@@ LITERAL a binary literal
SELECT i.code FROM item i WHERE i.active = b'1'

@@ LITERAL a double-quoted string
SELECT i.code FROM item i WHERE i.category = "POWER"

@@ ORDER an ordinal outside the select list
SELECT i.code, i.title FROM item i ORDER BY 5

@@ ORDER ORDER BY inside a CTE
WITH c AS (SELECT i.code FROM item i ORDER BY i.code) SELECT c.code FROM c

@@ LIMIT LIMIT with an offset, MySQL style
SELECT i.code FROM item i ORDER BY i.code LIMIT 10, 10

@@ LIMIT OFFSET … FETCH
SELECT i.code FROM item i ORDER BY i.code OFFSET 2 ROWS FETCH FIRST 2 ROWS ONLY

@@ LIMIT FETCH … WITH TIES
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 2 ROWS WITH TIES

@@ SIZE four nested subqueries
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.loan_id IN (
  SELECT l.id FROM loan l WHERE l.staff_code IN (SELECT r.staff_code FROM staff_role r WHERE r.role IN (
    SELECT c.role FROM role_cap c))))

@@ SIZE 21 columns in the select list
SELECT l.id{{repeat 20 , l.status}} FROM loan l

@@ SIZE more than 4000 characters
SELECT l.id FROM loan l WHERE l.status IN ('OPEN'{{repeat 600 , 'OPEN'}})

@@ COMMENT an executable comment with a version
SELECT /*!50000 SLEEP(1), */ i.code FROM item i

@@ COMMENT an unterminated block comment
SELECT i.code FROM item i /*

@@ UNSUPPORTED WITH RECURSIVE
WITH RECURSIVE c (n) AS (SELECT l.id FROM loan l) SELECT c.n FROM c

@@ PROCEDURE CALL of a procedure: no data channel runs a procedure call (gap 119; BS6 admitted this one)
CALL open_loan_count(?)
