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
-- The small tool-library lending schema of spike BS6, lifted with its corpora: the HSQLDB accept corpus runs on it,
-- and so do the resolver's and the catalogue's tests (HSQLDB 2.7, default mode). Statements end with ";" at the end
-- of a line; "--" lines are skipped. The allow-list of the tests (LendingFixture) lists every table here except
-- audit_log, and every column except staff.pin_hash.

CREATE TABLE staff (
  code VARCHAR(16) NOT NULL PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  email VARCHAR(120),
  pin_hash VARCHAR(64) NOT NULL,
  active BOOLEAN NOT NULL,
  joined DATE NOT NULL
);
CREATE TABLE staff_role (
  staff_code VARCHAR(16) NOT NULL,
  role VARCHAR(16) NOT NULL,
  PRIMARY KEY (staff_code, role)
);
CREATE TABLE role_permission (
  role VARCHAR(16) NOT NULL,
  permission VARCHAR(32) NOT NULL,
  PRIMARY KEY (role, permission)
);
CREATE TABLE role_cap (
  role VARCHAR(16) NOT NULL,
  cap VARCHAR(24) NOT NULL,
  max_amount DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (role, cap)
);
CREATE TABLE item (
  code VARCHAR(16) NOT NULL PRIMARY KEY,
  barcode VARCHAR(32) NOT NULL,
  title VARCHAR(120) NOT NULL,
  category VARCHAR(24) NOT NULL,
  deposit DECIMAL(10,2) NOT NULL,
  per_day DECIMAL(10,2) NOT NULL,
  active BOOLEAN NOT NULL
);
CREATE TABLE waiver_rule (
  id INTEGER NOT NULL PRIMARY KEY,
  category VARCHAR(24) NOT NULL,
  min_days INTEGER NOT NULL,
  percent_off INTEGER NOT NULL,
  role VARCHAR(16)
);
CREATE TABLE loan (
  id INTEGER NOT NULL PRIMARY KEY,
  borrower VARCHAR(80) NOT NULL,
  staff_code VARCHAR(16) NOT NULL,
  opened DATE NOT NULL,
  due DATE NOT NULL,
  status VARCHAR(12) NOT NULL
);
CREATE TABLE loan_line (
  loan_id INTEGER NOT NULL,
  line_no INTEGER NOT NULL,
  item_code VARCHAR(16) NOT NULL,
  qty INTEGER NOT NULL,
  deposit_paid DECIMAL(10,2) NOT NULL,
  returned DATE,
  PRIMARY KEY (loan_id, line_no)
);
-- Present in the schema, absent from the allow-list.
CREATE TABLE audit_log (
  id INTEGER NOT NULL PRIMARY KEY,
  logged_at TIMESTAMP NOT NULL,
  actor VARCHAR(16) NOT NULL,
  detail VARCHAR(200) NOT NULL
);
-- The one allow-listed routine: open loans handled by a member of staff (a function, so that CALL returns a value).
CREATE FUNCTION open_loan_count(p_staff VARCHAR(16)) RETURNS INTEGER
  READS SQL DATA
  RETURN (SELECT COUNT(*) FROM loan WHERE staff_code = p_staff AND status = 'OPEN');

INSERT INTO staff VALUES ('S01', 'Ada Moss', 'ada@example.org', 'x1', TRUE, DATE '2024-03-01');
INSERT INTO staff VALUES ('S02', 'Ben Okafor', 'ben@example.org', 'x2', TRUE, DATE '2025-01-15');
INSERT INTO staff VALUES ('S03', 'Cleo Varga', NULL, 'x3', FALSE, DATE '2023-06-30');
INSERT INTO staff_role VALUES ('S01', 'DESK');
INSERT INTO staff_role VALUES ('S01', 'SUPERVISOR');
INSERT INTO staff_role VALUES ('S02', 'DESK');
INSERT INTO staff_role VALUES ('S03', 'VOLUNTEER');
INSERT INTO role_permission VALUES ('DESK', 'LOAN_OPEN');
INSERT INTO role_permission VALUES ('DESK', 'LOAN_RETURN');
INSERT INTO role_permission VALUES ('SUPERVISOR', 'DEPOSIT_REFUND');
INSERT INTO role_permission VALUES ('SUPERVISOR', 'WAIVER_APPLY');
INSERT INTO role_permission VALUES ('VOLUNTEER', 'LOAN_RETURN');
INSERT INTO role_cap VALUES ('DESK', 'WAIVER', 10.00);
INSERT INTO role_cap VALUES ('SUPERVISOR', 'WAIVER', 50.00);
INSERT INTO role_cap VALUES ('SUPERVISOR', 'REFUND', 200.00);
INSERT INTO item VALUES ('DRL-01', '4006381333931', 'Cordless drill', 'POWER', 40.00, 3.50, TRUE);
INSERT INTO item VALUES ('DRL-02', '4006381333948', 'Hammer drill', 'POWER', 60.00, 5.00, TRUE);
INSERT INTO item VALUES ('SAW-01', '4006381333955', 'Tile saw', 'POWER', 80.00, 7.50, TRUE);
INSERT INTO item VALUES ('LAD-01', '4006381333962', 'Step ladder 2 m', 'ACCESS', 25.00, 2.00, TRUE);
INSERT INTO item VALUES ('LAD-02', '4006381333979', 'Extension ladder 5 m', 'ACCESS', 45.00, 4.00, FALSE);
INSERT INTO item VALUES ('GAR-01', '4006381333986', 'Hedge trimmer', 'GARDEN', 35.00, 3.00, TRUE);
INSERT INTO item VALUES ('GAR-02', '4006381333993', 'Lawn aerator', 'GARDEN', 20.00, 1.50, TRUE);
INSERT INTO waiver_rule VALUES (1, 'POWER', 7, 10, 'DESK');
INSERT INTO waiver_rule VALUES (2, 'POWER', 14, 25, 'SUPERVISOR');
INSERT INTO waiver_rule VALUES (3, 'GARDEN', 3, 50, NULL);
INSERT INTO loan VALUES (101, 'Dana Reyes', 'S01', DATE '2026-09-01', DATE '2026-09-08', 'RETURNED');
INSERT INTO loan VALUES (102, 'Eli Novak', 'S02', DATE '2026-09-20', DATE '2026-09-27', 'OVERDUE');
INSERT INTO loan VALUES (103, 'Fay Lund', 'S01', DATE '2026-10-01', DATE '2026-10-15', 'OPEN');
INSERT INTO loan VALUES (104, 'Gus Patel', 'S02', DATE '2026-10-03', DATE '2026-10-10', 'OPEN');
INSERT INTO loan_line VALUES (101, 1, 'DRL-01', 1, 40.00, DATE '2026-09-07');
INSERT INTO loan_line VALUES (101, 2, 'LAD-01', 1, 25.00, DATE '2026-09-07');
INSERT INTO loan_line VALUES (102, 1, 'SAW-01', 1, 80.00, NULL);
INSERT INTO loan_line VALUES (103, 1, 'GAR-01', 1, 35.00, NULL);
INSERT INTO loan_line VALUES (103, 2, 'GAR-02', 2, 40.00, NULL);
INSERT INTO loan_line VALUES (104, 1, 'DRL-02', 1, 60.00, NULL);
INSERT INTO audit_log VALUES (1, TIMESTAMP '2026-10-01 09:00:00', 'S01', 'loan 103 opened');
