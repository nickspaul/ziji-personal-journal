CREATE TABLE zj_ledger (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `date` DATE NOT NULL,
  direction ENUM('EXPENSE', 'INCOME') NOT NULL,
  amount DECIMAL(11,2) NOT NULL,
  category VARCHAR(40) NOT NULL,
  notes TEXT NULL,
  derivedJson JSON NULL,
  CHECK (amount > 0 AND amount <= 999999999),
  INDEX idx_ledger_deleted_created (deleted, createdAt, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
