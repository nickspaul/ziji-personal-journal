ALTER TABLE zj_ledger
  ADD COLUMN debtKind ENUM('CREDIT','REPAYMENT','OPENING') NULL,
  ADD COLUMN debtPlatform VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  ADD COLUMN debtFee DECIMAL(11,2) NULL,
  ADD INDEX idx_ledger_debt (debtPlatform,deleted,`date`),
  ADD CONSTRAINT ck_ledger_debt CHECK (
    (debtKind IS NULL AND debtPlatform IS NULL AND debtFee IS NULL) OR
    (debtKind IS NOT NULL AND direction='EXPENSE' AND debtPlatform IS NOT NULL
     AND CHAR_LENGTH(TRIM(debtPlatform))>0
     AND (debtFee IS NULL OR (debtKind='REPAYMENT' AND debtFee>=0 AND debtFee<=amount))
     AND (tripId IS NULL OR debtKind='CREDIT'))
  );
