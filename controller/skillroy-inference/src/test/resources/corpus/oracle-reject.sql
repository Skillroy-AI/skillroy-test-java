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
-- BS6 reject corpus, Oracle. Every statement must be refused with the reason code after "@@", the same way twice.
-- Format: see Corpus.java.

@@ PARSE two statements
SELECT l.id FROM loan l; DROP TABLE loan

@@ PARSE an incomplete statement
SELECT l.id FROM loan l WHERE l.id BETWEEN 1 AND

@@ DML INSERT
INSERT INTO role_cap (role, cap, max_amount) VALUES ('VOLUNTEER', 'REFUND', 500)

@@ DML DELETE
DELETE FROM waiver_rule WHERE id = 1

@@ DML MERGE
MERGE INTO item i USING (SELECT 'DRL-01' AS code FROM DUAL) v ON (i.code = v.code)
WHEN MATCHED THEN UPDATE SET i.active = 0

@@ DDL TRUNCATE
TRUNCATE TABLE loan_line

@@ DDL GRANT
GRANT SELECT ON audit_log TO PUBLIC

@@ PROCEDURE an anonymous PL/SQL block
BEGIN DELETE FROM loan; END;

@@ PROCEDURE CALL of a routine that is not allow-listed
CALL DBMS_STATS.GATHER_SCHEMA_STATS('LENDING')

@@ SET_OPERATION MINUS
SELECT i.code FROM item i MINUS SELECT x.item_code FROM loan_line x

@@ SET_OPERATION UNION ALL
SELECT s.code FROM staff s UNION ALL SELECT l.staff_code FROM loan l

@@ LOCKING FOR UPDATE NOWAIT
SELECT l.id FROM loan l WHERE l.status = 'OPEN' FOR UPDATE NOWAIT

@@ LOCKING FOR UPDATE OF a column
SELECT l.id, l.status FROM loan l FOR UPDATE OF l.status

@@ TABLE a dictionary view
SELECT u.username FROM all_users u

@@ TABLE a schema-qualified dictionary view
SELECT t.table_name FROM SYS.ALL_TABLES t

@@ TABLE a CTE that shadows an allow-listed table
WITH role_cap AS (SELECT a.actor AS role FROM audit_log a) SELECT c.role FROM role_cap c

@@ STAR SELECT *
SELECT * FROM DUAL

@@ STAR a qualified star
SELECT w.* FROM waiver_rule w

@@ COLUMN a pseudo-column
SELECT l.id, l.ROWID FROM loan l

@@ COLUMN a column that is not allow-listed
SELECT s.code, s.pin_hash FROM staff s

@@ PERSONAL a personal column in the select list
SELECT l.id, l.borrower FROM loan l

@@ PERSONAL a personal column through NVL
SELECT s.code, NVL(s.email, 'none') AS mail FROM staff s

@@ FUNCTION UTL_HTTP
SELECT UTL_HTTP.REQUEST('http://example.org/') AS r FROM DUAL

@@ FUNCTION DBMS_PIPE
SELECT l.id FROM loan l WHERE DBMS_PIPE.RECEIVE_MESSAGE('x', 10) = 1

@@ FUNCTION USER
SELECT USER AS who FROM DUAL

@@ FUNCTION LISTAGG … WITHIN GROUP (a form outside the guard's function grammar)
SELECT x.loan_id, LISTAGG(x.item_code, ',') WITHIN GROUP (ORDER BY x.item_code) AS items FROM loan_line x
GROUP BY x.loan_id

@@ LITERAL a string longer than 200 characters
SELECT i.code FROM item i WHERE i.title = '{{repeat 205 o}}'

@@ LITERAL alternative quoting
SELECT i.code FROM item i WHERE i.title = q'[Tile saw]'

@@ LITERAL a named bind variable
SELECT l.id FROM loan l WHERE l.status = :status

@@ LITERAL a hexadecimal literal
SELECT i.code FROM item i WHERE i.barcode = X'41'

@@ ORDER ORDER BY a constant
SELECT l.id FROM loan l ORDER BY NULL

@@ ORDER ORDER BY inside a derived table (the ROWNUM pattern)
SELECT d.id FROM (SELECT l.id FROM loan l ORDER BY l.due) d WHERE ROWNUM <= 10

@@ LIMIT ROWNUM
SELECT l.id FROM loan l WHERE ROWNUM <= 10 ORDER BY l.id

@@ LIMIT OFFSET … FETCH
SELECT l.id FROM loan l ORDER BY l.id OFFSET 5 ROWS FETCH NEXT 5 ROWS ONLY

@@ LIMIT FETCH FIRST … PERCENT
SELECT l.id FROM loan l ORDER BY l.id FETCH FIRST 10 PERCENT ROWS ONLY

@@ SIZE four nested subqueries
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.loan_id IN (
  SELECT l.id FROM loan l WHERE l.staff_code IN (SELECT r.staff_code FROM staff_role r WHERE r.role IN (
    SELECT p.role FROM role_permission p))))

@@ SIZE nine table references
SELECT x1.loan_id FROM loan_line x1, loan_line x2, loan_line x3, loan_line x4, loan_line x5, loan_line x6,
  loan_line x7, loan_line x8, loan_line x9

@@ SIZE more than 4000 characters
SELECT i.code FROM item i WHERE i.code IN ('LAD-01'{{repeat 450 , 'LAD-01'}})

@@ COMMENT an optimizer hint
SELECT /*+ FULL(i) PARALLEL(i 8) */ i.code FROM item i

@@ COMMENT a hint in a line comment
SELECT --+ INDEX(i)
  i.code FROM item i

@@ UNSUPPORTED CONNECT BY
SELECT s.code FROM staff s START WITH s.code = 'S01' CONNECT BY PRIOR s.code = s.code

@@ TABLE a database link (JSqlParser's Table.getName() returns "item" for it)
SELECT i.code FROM item@remote_desk i
