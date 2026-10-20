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
-- BS6 reject corpus, SQL Server. Every statement must be refused with the reason code after "@@", the same way twice.
-- Format: see Corpus.java.

@@ PARSE two statements
SELECT l.id FROM loan l; TRUNCATE TABLE loan

@@ PARSE WAITFOR (the parser does not know it)
WAITFOR DELAY '00:00:05'

@@ PARSE an UPDLOCK hint (the parser knows only some table hints; refused either way)
SELECT l.id FROM loan l WITH (UPDLOCK)

@@ DML INSERT
INSERT INTO item (code, barcode, title, category, deposit, per_day, active) VALUES ('X', '1', 't', 'POWER', 1, 1, 1)

@@ DML SELECT INTO a temporary table
SELECT i.code INTO #items FROM item i

@@ DML DELETE
DELETE FROM loan_line WHERE loan_id = 101

@@ DDL DROP TABLE
DROP TABLE loan

@@ DDL CREATE INDEX
CREATE INDEX ix_due ON loan (due)

@@ PROCEDURE xp_cmdshell
EXEC xp_cmdshell 'dir'

@@ PROCEDURE EXECUTE of a once allow-listed name (no data channel runs a procedure call, gap 119)
EXECUTE open_loan_count 'S01'

@@ SET_OPERATION UNION
SELECT i.code FROM item i UNION SELECT x.item_code FROM loan_line x

@@ SET_OPERATION EXCEPT
SELECT s.code FROM staff s EXCEPT SELECT l.staff_code FROM loan l

@@ LOCKING NOLOCK
SELECT TOP 10 l.id FROM loan l WITH (NOLOCK) ORDER BY l.id

@@ LOCKING NOLOCK on a joined table
SELECT l.id FROM loan l JOIN loan_line x WITH (NOLOCK) ON x.loan_id = l.id

@@ LOCKING FOR UPDATE
SELECT l.id FROM loan l FOR UPDATE

@@ TABLE a schema-qualified name
SELECT l.id FROM dbo.loan l

@@ TABLE a four-part name through a linked server
SELECT l.id FROM remote_srv.lending.dbo.loan l

@@ TABLE the system catalogue
SELECT o.name FROM sys.objects o

@@ TABLE a CTE that shadows an allow-listed table
WITH waiver_rule AS (SELECT a.id FROM audit_log a) SELECT w.id FROM waiver_rule w

@@ STAR SELECT *
SELECT * FROM item

@@ STAR TOP with a qualified star
SELECT TOP 1 i.* FROM item i

@@ COLUMN a column that is not allow-listed
SELECT s.code FROM staff s WHERE s.pin_hash = N'x'

@@ COLUMN a column of the wrong table
SELECT s.code, s.title FROM staff s

@@ PERSONAL a personal column in the select list
SELECT s.code, s.email FROM staff s

@@ PERSONAL a personal column through a CTE
WITH b AS (SELECT l.id, l.borrower FROM loan l) SELECT b.id, b.borrower FROM b

@@ FUNCTION a system variable
SELECT @@VERSION AS v

@@ FUNCTION OPENROWSET
SELECT r.x FROM OPENROWSET('SQLNCLI', 'Server=remote;Trusted_Connection=yes;', 'SELECT 1 AS x') AS r

@@ FUNCTION a function that is not allow-listed
SELECT i.code, HASHBYTES('SHA2_256', i.title) AS h FROM item i

@@ FUNCTION a window function
SELECT l.id, ROW_NUMBER() OVER (ORDER BY l.due) AS n FROM loan l

@@ LITERAL a string longer than 200 characters
SELECT i.code FROM item i WHERE i.title = N'{{repeat 201 w}}'

@@ LITERAL a binary literal
SELECT i.code FROM item i WHERE i.barcode = 0x3430

@@ LITERAL a variable
SELECT l.id FROM loan l WHERE l.id = @p1

@@ ORDER ORDER BY a constant
SELECT TOP 5 i.code FROM item i ORDER BY 'x'

@@ ORDER ORDER BY inside a derived table with TOP
SELECT d.id FROM (SELECT TOP 5 l.id FROM loan l ORDER BY l.due) d

@@ LIMIT TOP … PERCENT
SELECT TOP 10 PERCENT i.code FROM item i ORDER BY i.code

@@ LIMIT TOP … WITH TIES
SELECT TOP 5 WITH TIES i.code FROM item i ORDER BY i.code

@@ LIMIT OFFSET … FETCH
SELECT i.code FROM item i ORDER BY i.code OFFSET 10 ROWS FETCH NEXT 10 ROWS ONLY

@@ LIMIT TOP with a parameter
SELECT TOP (?) i.code FROM item i ORDER BY i.code

@@ SIZE four nested subqueries
SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x WHERE x.loan_id IN (
  SELECT l.id FROM loan l WHERE l.staff_code IN (SELECT r.staff_code FROM staff_role r WHERE r.role IN (
    SELECT p.role FROM role_permission p))))

@@ SIZE 21 columns in the select list
SELECT TOP 1 i.code{{repeat 20 , i.title}} FROM item i

@@ SIZE more than 4000 characters
SELECT i.code FROM item i WHERE i.code IN (N'DRL-01'{{repeat 420 , N'DRL-01'}})

@@ COMMENT a nested block comment (T-SQL nests them, the guard refuses them)
SELECT i.code /* a /* b */ c */ FROM item i

@@ COMMENT an unterminated block comment
SELECT i.code FROM item i /* open

@@ UNSUPPORTED CROSS APPLY
SELECT l.id, a.item_code FROM loan l CROSS APPLY (SELECT TOP 1 x.item_code FROM loan_line x WHERE x.loan_id = l.id) a

@@ UNSUPPORTED a query hint
SELECT i.code FROM item i OPTION (MAXDOP 1)

@@ PROCEDURE CALL of a procedure: no data channel runs a procedure call (gap 119; BS6 admitted this one)
CALL open_loan_count(?)
