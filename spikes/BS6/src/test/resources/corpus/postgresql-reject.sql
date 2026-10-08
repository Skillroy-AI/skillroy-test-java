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
-- BS6 reject corpus, PostgreSQL. Every statement must be refused with the reason code after "@@", the same way twice.
-- Format: see Corpus.java.

@@ PARSE two statements
SELECT l.id FROM loan l; UPDATE loan SET status = 'OPEN'

@@ PARSE adjacent string literals on one line
SELECT i.code FROM item i WHERE i.title = 'Tile' ' saw'

@@ DML INSERT … RETURNING
INSERT INTO waiver_rule (id, category, min_days, percent_off) VALUES (9, 'POWER', 1, 100) RETURNING id

@@ DML DELETE inside a CTE
WITH d AS (DELETE FROM loan_line WHERE loan_id = 101 RETURNING loan_id) SELECT d.loan_id FROM d

@@ DML UPDATE inside a CTE
WITH u AS (UPDATE item SET deposit = 0 RETURNING code) SELECT u.code FROM u

@@ DML SELECT INTO a new table
SELECT i.code INTO item_copy FROM item i

@@ DDL CREATE TABLE AS
CREATE TABLE loan_copy AS SELECT l.id FROM loan l

@@ DDL COMMENT ON
COMMENT ON TABLE item IS 'x'

@@ PROCEDURE CALL of a routine that is not allow-listed
CALL refresh_stats()

@@ PROCEDURE a DO block
DO 'BEGIN PERFORM pg_sleep(1); END'

@@ SET_OPERATION EXCEPT
SELECT i.code FROM item i EXCEPT SELECT x.item_code FROM loan_line x

@@ SET_OPERATION UNION in a subquery
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x UNION SELECT 'DRL-01')

@@ LOCKING FOR UPDATE SKIP LOCKED
SELECT l.id FROM loan l WHERE l.status = 'OPEN' FOR UPDATE SKIP LOCKED

@@ LOCKING FOR NO KEY UPDATE
SELECT l.id FROM loan l FOR NO KEY UPDATE

@@ LOCKING FOR SHARE
SELECT l.id FROM loan l FOR SHARE

@@ TABLE a system catalogue
SELECT u.usename FROM pg_catalog.pg_user u

@@ TABLE a table that is not allow-listed
SELECT a.detail FROM audit_log a

@@ TABLE a quoted name in the wrong case
SELECT i.code FROM "Item" i

@@ TABLE a CTE that shadows an allow-listed table
WITH loan_line AS (SELECT a.id AS loan_id FROM audit_log a) SELECT x.loan_id FROM loan_line x

@@ STAR SELECT *
SELECT * FROM staff

@@ STAR a qualified star in a CTE
WITH s AS (SELECT st.* FROM staff st) SELECT s.code FROM s

@@ COLUMN a column that is not allow-listed
SELECT s.code FROM staff s ORDER BY s.pin_hash

@@ COLUMN a column of the wrong table
SELECT l.id, l.title FROM loan l

@@ PERSONAL a personal column in the select list
SELECT l.borrower FROM loan l

@@ PERSONAL a personal column in a scalar subquery of the select list
SELECT l.id, (SELECT s.email FROM staff s WHERE s.code = l.staff_code) AS desk_mail FROM loan l

@@ FUNCTION pg_sleep
SELECT pg_sleep(5)

@@ FUNCTION pg_read_file
SELECT pg_read_file('/etc/passwd') AS f FROM item i

@@ FUNCTION current_setting
SELECT current_setting('data_directory') AS d FROM item i

@@ FUNCTION a cast to a catalogue type
SELECT i.code FROM item i WHERE 'item'::regclass IS NOT NULL

@@ LITERAL a string longer than 200 characters
SELECT i.code FROM item i WHERE i.title = '{{repeat 300 a}}'

@@ LITERAL an escape string
SELECT i.code FROM item i WHERE i.title = E'Tile saw'

@@ LITERAL a dollar-quoted string
SELECT i.code FROM item i WHERE i.title = $$Tile saw$$

@@ LITERAL a numbered parameter
SELECT i.code FROM item i WHERE i.code = $1

@@ ORDER ORDER BY a parameter
SELECT i.code FROM item i ORDER BY ?

@@ ORDER a derived table whose key is not selected
SELECT d.category FROM (SELECT i.category, i.title FROM item i) d

@@ LIMIT LIMIT ALL
SELECT i.code FROM item i ORDER BY i.code LIMIT ALL

@@ LIMIT LIMIT with OFFSET 0
SELECT i.code FROM item i ORDER BY i.code LIMIT 10 OFFSET 0

@@ LIMIT FETCH … WITH TIES
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 5 ROWS WITH TIES

@@ SIZE four nested subqueries
SELECT i.code FROM item i WHERE EXISTS (SELECT 1 FROM loan_line x WHERE x.item_code = i.code AND EXISTS (
  SELECT 1 FROM loan l WHERE l.id = x.loan_id AND EXISTS (SELECT 1 FROM staff s WHERE s.code = l.staff_code AND
  EXISTS (SELECT 1 FROM staff_role r WHERE r.staff_code = s.code))))

@@ SIZE nine table references
SELECT s.code FROM staff s JOIN staff_role r ON r.staff_code = s.code JOIN role_permission p ON p.role = r.role
JOIN role_cap c ON c.role = r.role JOIN loan l ON l.staff_code = s.code JOIN loan_line x ON x.loan_id = l.id
JOIN item i ON i.code = x.item_code JOIN waiver_rule w ON w.category = i.category
JOIN staff s2 ON s2.code = s.code

@@ SIZE more than 4000 characters
SELECT i.code FROM item i WHERE i.title NOT IN ('x'{{repeat 900 , 'x'}})

@@ COMMENT a nested block comment (PostgreSQL nests them, the guard refuses them)
SELECT i.code /* a /* b */ c */ FROM item i

@@ COMMENT a hint comment
SELECT /*+ SeqScan(i) */ i.code FROM item i

@@ UNSUPPORTED DISTINCT ON
SELECT DISTINCT ON (x.item_code) x.item_code, x.loan_id FROM loan_line x ORDER BY x.item_code, x.loan_id

@@ UNSUPPORTED a regular-expression operator
SELECT i.code FROM item i WHERE i.title ~ '^(a+)+$'

@@ UNSUPPORTED LATERAL
SELECT i.code, z.n FROM item i, LATERAL (SELECT COUNT(*) AS n FROM loan_line x WHERE x.item_code = i.code) z

@@ UNSUPPORTED TABLESAMPLE
SELECT l.id FROM loan l TABLESAMPLE SYSTEM (10)

@@ FUNCTION a window function
SELECT l.id, rank() OVER (PARTITION BY l.staff_code ORDER BY l.due) AS r FROM loan l
