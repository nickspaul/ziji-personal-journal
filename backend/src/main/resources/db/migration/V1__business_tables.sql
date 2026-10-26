CREATE TABLE zj_products (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `kind` VARCHAR(40) NULL,
  `brand` VARCHAR(500) NULL,
  `name` VARCHAR(500) NULL,
  `variant` VARCHAR(500) NULL,
  `aliases` VARCHAR(500) NULL,
  `barcode` VARCHAR(500) NULL,
  `unitCount` DECIMAL(20,6) NULL,
  `bottleMl` DECIMAL(20,6) NULL,
  `abv` DECIMAL(20,6) NULL,
  `tarMg` DECIMAL(20,6) NULL,
  `nicotineMg` DECIMAL(20,6) NULL,
  `coMg` DECIMAL(20,6) NULL,
  `constituentMethod` VARCHAR(500) NULL,
  `packagingVersion` VARCHAR(500) NULL,
  `region` VARCHAR(500) NULL,
  `sourceUrl` VARCHAR(2000) NULL,
  `sourceDate` DATE NULL,
  `verified` BOOLEAN NULL,
  `favorite` BOOLEAN NULL,
  `locked` BOOLEAN NULL,
  `active` BOOLEAN NULL,
  `notes` TEXT NULL,
  derivedJson JSON NULL,
  INDEX idx_products_deleted (deleted),
  INDEX idx_products_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_prices (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `productId` CHAR(36) NULL,
  `amount` DECIMAL(20,6) NULL,
  `priceType` VARCHAR(40) NULL,
  `effectiveDate` DATE NULL,
  `currency` VARCHAR(40) NULL,
  `channel` VARCHAR(500) NULL,
  `sourceUrl` VARCHAR(2000) NULL,
  `verified` BOOLEAN NULL,
  `notes` TEXT NULL,
  derivedJson JSON NULL,
  INDEX idx_prices_deleted (deleted),
  INDEX idx_prices_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_purchases (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `productId` CHAR(36) NULL,
  `quantity` DECIMAL(20,6) NULL,
  `amount` DECIMAL(20,6) NULL,
  `occurredAt` DATETIME NULL,
  `payer` VARCHAR(40) NULL,
  `notes` TEXT NULL,
  derivedJson JSON NULL,
  INDEX idx_purchases_deleted (deleted),
  INDEX idx_purchases_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_smoking (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `occurredAt` DATETIME NULL,
  `timePrecision` VARCHAR(40) NULL,
  `productId` CHAR(36) NULL,
  `count` DECIMAL(20,6) NULL,
  `source` VARCHAR(40) NULL,
  `purchaseId` CHAR(36) NULL,
  `unitPrice` DECIMAL(20,6) NULL,
  `mood` VARCHAR(500) NULL,
  `context` VARCHAR(500) NULL,
  `notes` TEXT NULL,
  derivedJson JSON NULL,
  INDEX idx_smoking_deleted (deleted),
  INDEX idx_smoking_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_sessions (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `startedAt` DATETIME NULL,
  `endedAt` DATETIME NULL,
  `timePrecision` VARCHAR(40) NULL,
  `context` VARCHAR(500) NULL,
  `notes` TEXT NULL,
  derivedJson JSON NULL,
  INDEX idx_sessions_deleted (deleted),
  INDEX idx_sessions_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_drinks (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `sessionId` CHAR(36) NULL,
  `productId` CHAR(36) NULL,
  `occurredAt` DATETIME NULL,
  `timePrecision` VARCHAR(40) NULL,
  `inputMode` VARCHAR(40) NULL,
  `bottles` DECIMAL(20,6) NULL,
  `bottleMl` DECIMAL(20,6) NULL,
  `share` DECIMAL(20,6) NULL,
  `volumeMl` DECIMAL(20,6) NULL,
  `abv` DECIMAL(20,6) NULL,
  `purchaseId` CHAR(36) NULL,
  `unitPrice` DECIMAL(20,6) NULL,
  `notes` TEXT NULL,
  derivedJson JSON NULL,
  INDEX idx_drinks_deleted (deleted),
  INDEX idx_drinks_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_intimacy (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `occurredAt` DATETIME NULL,
  `timePrecision` VARCHAR(40) NULL,
  `count` DECIMAL(20,6) NULL,
  `durationMinutes` DECIMAL(20,6) NULL,
  `moodBefore` VARCHAR(500) NULL,
  `moodAfter` VARCHAR(500) NULL,
  `impact` VARCHAR(500) NULL,
  `manualAlcohol` VARCHAR(40) NULL,
  `overrideReason` TEXT NULL,
  `notes` TEXT NULL,
  derivedJson JSON NULL,
  INDEX idx_intimacy_deleted (deleted),
  INDEX idx_intimacy_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_body (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `measuredAt` DATETIME NULL,
  `heightCm` DECIMAL(20,6) NULL,
  `weightKg` DECIMAL(20,6) NULL,
  `age` DECIMAL(20,6) NULL,
  `sex` VARCHAR(40) NULL,
  `notes` TEXT NULL,
  derivedJson JSON NULL,
  INDEX idx_body_deleted (deleted),
  INDEX idx_body_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_completeness (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `date` DATE NULL,
  `kind` VARCHAR(40) NULL,
  `complete` BOOLEAN NULL,
  derivedJson JSON NULL,
  INDEX idx_completeness_deleted (deleted),
  INDEX idx_completeness_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_goals (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `kind` VARCHAR(40) NULL,
  `period` VARCHAR(40) NULL,
  `limitValue` DECIMAL(20,6) NULL,
  `unit` VARCHAR(40) NULL,
  `enabled` BOOLEAN NULL,
  derivedJson JSON NULL,
  INDEX idx_goals_deleted (deleted),
  INDEX idx_goals_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_health (
  id CHAR(36) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 1,
  createdAt DATETIME(6) NOT NULL,
  updatedAt DATETIME(6) NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  `title` VARCHAR(500) NULL,
  `topic` VARCHAR(40) NULL,
  `body` TEXT NULL,
  `sourceUrl` VARCHAR(2000) NULL,
  `reviewedAt` DATE NULL,
  derivedJson JSON NULL,
  INDEX idx_health_deleted (deleted),
  INDEX idx_health_updated (updatedAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE zj_settings (
  id INT NOT NULL PRIMARY KEY,
  payload JSON NOT NULL,
  updatedAt DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO zj_settings(id,payload,updatedAt) VALUES (1,JSON_OBJECT(),NOW(6));

CREATE TABLE zj_auth (
  id INT NOT NULL PRIMARY KEY,
  passwordHash VARCHAR(255) NOT NULL,
  createdAt DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE zj_audit (
  id CHAR(36) NOT NULL PRIMARY KEY,
  occurredAt DATETIME(6) NOT NULL,
  action VARCHAR(40) NOT NULL,
  collectionName VARCHAR(80) NOT NULL,
  rowId VARCHAR(100) NULL,
  beforeJson JSON NULL,
  afterJson JSON NULL,
  INDEX idx_audit_occurred (occurredAt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE zj_backups (
  id CHAR(36) NOT NULL PRIMARY KEY,
  createdAt DATETIME(6) NOT NULL,
  reason VARCHAR(100) NOT NULL,
  fileName VARCHAR(300) NOT NULL,
  sizeBytes BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_smoking_time ON zj_smoking(occurredAt);
CREATE INDEX idx_sessions_time ON zj_sessions(startedAt);
CREATE INDEX idx_drinks_time ON zj_drinks(occurredAt);
CREATE INDEX idx_drinks_session ON zj_drinks(sessionId);
CREATE INDEX idx_intimacy_time ON zj_intimacy(occurredAt);
CREATE INDEX idx_body_time ON zj_body(measuredAt);
CREATE INDEX idx_prices_product ON zj_prices(productId,effectiveDate);
CREATE INDEX idx_complete_date_kind ON zj_completeness(date,kind);
