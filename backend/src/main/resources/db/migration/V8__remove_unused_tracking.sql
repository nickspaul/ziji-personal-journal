-- Back up the local database before upgrading. These retired features have no
-- references from behavior records, journal entries, tasks or body measurements.
-- Keep earlier migrations unchanged so existing Flyway checksums remain valid.
DROP TABLE IF EXISTS zj_completeness, zj_goals;
