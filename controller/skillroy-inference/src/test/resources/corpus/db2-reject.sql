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
-- BS6 reject corpus, DB2. Every statement must be refused with the reason code after "@@", the same way twice.
-- Format: see Corpus.java.

@@ PARSE two statements
SELECT l.id FROM loan l; DELETE FROM loan

@@ PARSE a data-change table reference (the parser does not know it; it would write)
SELECT l.id FROM FINAL TABLE (INSERT INTO loan (id) VALUES (1)) l

@@ PARSE an incomplete statement
SELECT l.id FROM loan l WHERE l.id IN (

@@ DML INSERT
INSERT INTO staff_role (staff_code, role) VALUES ('S03', 'SUPERVISOR')

@@ DML UPDATE
UPDATE role_cap SET max_amount = 99999 WHERE role = 'DESK'

@@ DML MERGE
MERGE INTO role_cap c USING (SELECT 'DESK' AS role FROM staff s) v ON c.role = v.role
WHEN MATCHED THEN UPDATE SET c.max_amount = 0

@@ DML SELECT INTO
SELECT i.code INTO hv_code FROM item i

@@ DDL DROP TABLE
DROP TABLE loan_line

@@ DDL CREATE VIEW
CREATE VIEW all_staff AS SELECT s.code FROM staff s

@@ PROCEDURE an administrative routine
CALL SYSPROC.ADMIN_CMD('REORG TABLE loan')

@@ PROCEDURE CALL with a literal argument
CALL open_loan_count('S01')

@@ SET_OPERATION UNION
SELECT s.code FROM staff s UNION SELECT l.staff_code FROM loan l

@@ SET_OPERATION a recursive CTE (DB2 writes it without RECURSIVE)
WITH chain (code) AS (SELECT s.code FROM staff s UNION ALL SELECT c.code FROM chain c)
SELECT ch.code FROM chain ch

@@ LOCKING WITH UR
SELECT l.id FROM loan l WITH UR

@@ LOCKING WITH RS after FETCH FIRST
SELECT l.id FROM loan l ORDER BY l.id FETCH FIRST 10 ROWS ONLY WITH RS

@@ LOCKING FOR UPDATE
SELECT l.id FROM loan l FOR UPDATE

@@ LOCKING FOR READ ONLY
SELECT l.id FROM loan l FOR READ ONLY

@@ TABLE the dummy table
SELECT CURRENT DATE AS today FROM SYSIBM.SYSDUMMY1

@@ TABLE the catalogue
SELECT t.tabname FROM SYSCAT.TABLES t

@@ TABLE a table that is not allow-listed
SELECT a.actor FROM audit_log a

@@ TABLE a quoted name in lower case
SELECT l.id FROM "loan" l

@@ STAR SELECT *
SELECT * FROM role_cap

@@ STAR a qualified star
SELECT c.* FROM role_cap c

@@ COLUMN a column that is not allow-listed
SELECT s.code FROM staff s WHERE s.pin_hash IS NULL

@@ COLUMN an unknown alias
SELECT q.role FROM role_cap c

@@ PERSONAL a personal column in the select list
SELECT s.code, s.name FROM staff s

@@ PERSONAL a personal column inside CASE in the select list
SELECT l.id, CASE WHEN l.borrower LIKE 'A%' THEN 1 ELSE 0 END AS a FROM loan l

@@ FUNCTION RAISE_ERROR
SELECT l.id FROM loan l WHERE RAISE_ERROR('70001', 'stop') IS NULL

@@ FUNCTION a function that is not allow-listed
SELECT HEX(i.title) AS h FROM item i

@@ FUNCTION a window function
SELECT l.id, ROW_NUMBER() OVER () AS n FROM loan l

@@ FUNCTION CAST to CLOB
SELECT CAST(i.title AS CLOB) AS c FROM item i

@@ LITERAL a string longer than 200 characters
SELECT i.code FROM item i WHERE i.title = '{{repeat 201 q}}'

@@ LITERAL a hexadecimal literal
SELECT i.code FROM item i WHERE i.code = X'44524C'

@@ LITERAL a host variable
SELECT i.code FROM item i WHERE i.code = :hv

@@ ORDER ORDER BY a constant
SELECT i.code FROM item i ORDER BY 'code'

@@ ORDER ORDER BY inside a scalar subquery
SELECT s.code, (SELECT l.id FROM loan l WHERE l.staff_code = s.code ORDER BY l.id FETCH FIRST 1 ROW ONLY) AS f
FROM staff s

@@ LIMIT OFFSET … FETCH
SELECT i.code FROM item i ORDER BY i.code OFFSET 10 ROWS FETCH NEXT 10 ROWS ONLY

@@ LIMIT a parameter in FETCH FIRST
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST ? ROWS ONLY

@@ SIZE four nested subqueries
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.loan_id IN (
  SELECT l.id FROM loan l WHERE l.staff_code IN (SELECT r.staff_code FROM staff_role r WHERE r.role IN (
    SELECT p.role FROM role_permission p))))

@@ SIZE nine table references
SELECT s.code FROM staff s, staff s2, staff s3, staff s4, staff s5, staff s6, staff s7, staff s8, staff s9

@@ SIZE 21 columns in the select list
SELECT x.loan_id{{repeat 20 , x.qty}} FROM loan_line x

@@ SIZE more than 4000 characters
SELECT i.code FROM item i WHERE i.code NOT IN ('GAR-01'{{repeat 450 , 'GAR-01'}})

@@ COMMENT a nested block comment
SELECT i.code /* a /* b */ */ FROM item i

@@ COMMENT a hint comment
SELECT /*+ OPTIMIZE */ i.code FROM item i

@@ UNSUPPORTED OPTIMIZE FOR after FETCH FIRST
SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 10 ROWS ONLY OPTIMIZE FOR 10 ROWS

@@ PROCEDURE CALL of a procedure: no data channel runs a procedure call (gap 119; BS6 admitted this one)
CALL open_loan_count(?)
