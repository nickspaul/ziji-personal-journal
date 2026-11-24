ALTER TABLE zj_ledger
  ADD COLUMN tripId CHAR(36) NULL,
  ADD COLUMN reimbursement ENUM('SELF','PENDING','REIMBURSED') NULL,
  ADD CONSTRAINT fk_ledger_trip FOREIGN KEY (tripId) REFERENCES zj_journal(id),
  ADD CONSTRAINT ck_ledger_trip CHECK (
    (tripId IS NULL AND reimbursement IS NULL) OR
    (tripId IS NOT NULL AND direction='EXPENSE' AND reimbursement IS NOT NULL)
  ),
  ADD INDEX idx_ledger_period (deleted,`date`,id);

CREATE TABLE zj_bills (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  title VARCHAR(100) NOT NULL,
  category VARCHAR(40) NOT NULL,
  amount DECIMAL(11,2) NOT NULL,
  nextDue DATE NOT NULL,
  monthDay TINYINT UNSIGNED NOT NULL,
  derivedJson JSON NULL,
  CHECK (monthDay BETWEEN 1 AND 31),
  CHECK (amount > 0 AND amount <= 999999999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
