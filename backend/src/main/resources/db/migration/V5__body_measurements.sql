ALTER TABLE zj_body
  ADD COLUMN `bodyFatPct` DECIMAL(20,6) NULL AFTER `weightKg`,
  ADD COLUMN `waistCm` DECIMAL(20,6) NULL AFTER `bodyFatPct`;
