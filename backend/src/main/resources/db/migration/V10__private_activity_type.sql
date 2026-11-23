-- The original private activity form recorded masturbation only.
ALTER TABLE zj_intimacy
  ADD COLUMN activityType ENUM('MASTURBATION', 'SEX') NOT NULL DEFAULT 'MASTURBATION';
