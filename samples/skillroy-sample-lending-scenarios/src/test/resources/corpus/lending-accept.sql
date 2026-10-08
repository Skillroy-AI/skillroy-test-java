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
-- The lending accept corpus (phase5-contracts.md 15.1 item 5): statements a model could write for a tier (b) slot of
-- the sample, over the tables of the lending pack's SQL allow-list and nothing else. Every statement must be accepted
-- by SqlGuard with that allow-list, rewritten byte-identically on two runs into the "=>" text and, rewritten, run on
-- the sample's own database (LendingDb, seed A, business date 2026-10-08, as the read-only account) with the "??"
-- parameter values, giving exactly the "<<" rows in order. Together the statements read every allow-listed table.
-- LendingAcceptCorpusTest runs it; spike BS6's corpora run on BS6's own schema in the inference module.
--
-- Format: BS6's (an entry starts with "@@ <label>"; the statement follows on one or more lines; "?? v1 | v2" gives the
-- parameter values: 'text', integers, decimals, DATE yyyy-mm-dd, true and false; "=> <text>" the expected rewrite),
-- with one line kind more: "<< c1 | c2" is one row read back, its cells as JDBC's getString gives them; an entry with
-- no "<<" line returns no row.
--
-- The rows: seed A, plus two loans the test posts as the desk records them: loan 1001 by staff member 100, three hand
-- tools with the B2W1-HAND waiver of 0.99 (total 2.55), and loan 1002 by staff member 100, the return of PWR-001
-- (-34.99) that supervisor 200 authorised with RETURN_OVERRIDE.

@@ who may authorise a return above a limit: correlated EXISTS over a staff member's roles and permissions
SELECT s.code FROM staff s
WHERE s.active = TRUE
  AND EXISTS (SELECT sr.staff_code FROM staff_role sr JOIN role_permission rp ON rp.role_id = sr.role_id
              WHERE sr.staff_code = s.code AND rp.permission = ?)
?? 'RETURN_OVERRIDE'
=> SELECT s.code FROM staff s WHERE s.active = true AND EXISTS (SELECT sr.staff_code FROM staff_role sr JOIN role_permission rp ON rp.role_id = sr.role_id WHERE sr.staff_code = s.code AND rp.permission = ?) ORDER BY s.code LIMIT 200
<< 200
<< 300

@@ each staff member's RETURN limit: an aggregate grouped by staff member, the group's column completing the order
SELECT sr.staff_code, MAX(rc.amount) AS return_limit
FROM staff_role sr JOIN role_cap rc ON rc.role_id = sr.role_id
WHERE rc.kind = ?
GROUP BY sr.staff_code
?? 'RETURN'
=> SELECT sr.staff_code, MAX(rc.amount) AS return_limit FROM staff_role sr JOIN role_cap rc ON rc.role_id = sr.role_id WHERE rc.kind = ? GROUP BY sr.staff_code ORDER BY sr.staff_code LIMIT 200
<< 100 | 20.00
<< 102 | 20.00
<< 103 | 20.00
<< 200 | 300.00

@@ active staff members with no RETURN limit (none of their roles has one): a CTE read through NOT IN
WITH capped AS (SELECT sr.staff_code FROM staff_role sr JOIN role_cap rc ON rc.role_id = sr.role_id WHERE rc.kind = ?)
SELECT s.code FROM staff s WHERE s.active = TRUE AND s.code NOT IN (SELECT c.staff_code FROM capped c)
?? 'RETURN'
=> WITH capped AS (SELECT sr.staff_code FROM staff_role sr JOIN role_cap rc ON rc.role_id = sr.role_id WHERE rc.kind = ?) SELECT s.code FROM staff s WHERE s.active = true AND s.code NOT IN (SELECT c.staff_code FROM capped c) ORDER BY s.code LIMIT 200
<< 101
<< 104
<< 300

@@ a staff member found by name: the personal column filters and is never selected
SELECT s.code FROM staff s WHERE s.name = ?
?? 'Carol Cooper'
=> SELECT s.code FROM staff s WHERE s.name = ? ORDER BY s.code LIMIT 200
<< 200

@@ items lent by the unit with a deposit in a range: BETWEEN two decimal parameters
SELECT i.code, i.title, i.deposit FROM item i
WHERE i.active = TRUE AND i.per_day = ? AND i.deposit BETWEEN CAST(? AS DECIMAL(10,2)) AND CAST(? AS DECIMAL(10,2))
?? false | 1.00 | 2.00
=> SELECT i.code, i.title, i.deposit FROM item i WHERE i.active = true AND i.per_day = ? AND i.deposit BETWEEN CAST(? AS DECIMAL (10, 2)) AND CAST(? AS DECIMAL (10, 2)) ORDER BY i.code LIMIT 200
<< HND-001 | Claw hammer | 1.10
<< HND-002 | Hand saw | 1.45
<< STK-001 | Stud finder | 1.35

@@ a desk setting by its key
SELECT d.name, d.value FROM desk_setting d WHERE d.name = ?
?? 'max_return'
=> SELECT d.name, d.value FROM desk_setting d WHERE d.name = ? ORDER BY d.name LIMIT 200
<< max_return | 500.00

@@ waiver rules in force on a day and the items they cover: dates as parameters, the keys of both tables appended
SELECT w.code, wi.item_code FROM waiver_rule w JOIN waiver_rule_item wi ON wi.rule_id = w.id
WHERE w.active = TRUE AND w.kind = ? AND w.starts_on <= ? AND w.ends_on >= ?
?? 'BORROW_N_WAIVE_M' | DATE 2026-10-08 | DATE 2026-10-08
=> SELECT w.code, wi.item_code FROM waiver_rule w JOIN waiver_rule_item wi ON wi.rule_id = w.id WHERE w.active = true AND w.kind = ? AND w.starts_on <= ? AND w.ends_on >= ? ORDER BY w.id, wi.rule_id, wi.item_code LIMIT 200
<< B2W1-HAND | HND-001
<< B2W1-HAND | HND-002
<< B2W1-HAND | HND-003

@@ the lines of the loans a staff member posted, returned items left out
SELECT l.id, x.line_no, x.item_code, x.amount FROM loan l JOIN loan_line x ON x.loan_id = l.id
WHERE l.staff_code = ? AND x.returned = FALSE
?? '100'
=> SELECT l.id, x.line_no, x.item_code, x.amount FROM loan l JOIN loan_line x ON x.loan_id = l.id WHERE l.staff_code = ? AND x.returned = false ORDER BY l.id, x.loan_id, x.line_no LIMIT 200
<< 1001 | 1 | HND-001 | 1.10
<< 1001 | 2 | HND-002 | 1.45
<< 1001 | 3 | HND-003 | 0.99

@@ the waivers of posted loans with their rule's description: a join on a column that is not a key
SELECT lw.loan_id, lw.rule_code, lw.amount, w.description
FROM loan_waiver lw JOIN waiver_rule w ON w.code = lw.rule_code
=> SELECT lw.loan_id, lw.rule_code, lw.amount, w.description FROM loan_waiver lw JOIN waiver_rule w ON w.code = lw.rule_code ORDER BY lw.loan_id, lw.rule_code, w.id LIMIT 200
<< 1001 | B2W1-HAND | 0.99 | Hand tools: borrow 2, third deposit waived

@@ the returns a supervisor authorised: a table without a primary key, its declared keys completing the order
SELECT a.loan_id, a.staff_code, l.total FROM loan_authorisation a JOIN loan l ON l.id = a.loan_id WHERE a.kind = ?
?? 'RETURN_OVERRIDE'
=> SELECT a.loan_id, a.staff_code, l.total FROM loan_authorisation a JOIN loan l ON l.id = a.loan_id WHERE a.kind = ? ORDER BY a.loan_id, a.kind, a.staff_code, l.id LIMIT 200
<< 1002 | 200 | -34.99
