ALTER TABLE zj_prices ADD CONSTRAINT fk_prices_product FOREIGN KEY(productId) REFERENCES zj_products(id);
ALTER TABLE zj_purchases ADD CONSTRAINT fk_purchases_product FOREIGN KEY(productId) REFERENCES zj_products(id);
ALTER TABLE zj_smoking ADD CONSTRAINT fk_smoking_product FOREIGN KEY(productId) REFERENCES zj_products(id), ADD CONSTRAINT fk_smoking_purchase FOREIGN KEY(purchaseId) REFERENCES zj_purchases(id);
ALTER TABLE zj_drinks ADD CONSTRAINT fk_drinks_product FOREIGN KEY(productId) REFERENCES zj_products(id), ADD CONSTRAINT fk_drinks_purchase FOREIGN KEY(purchaseId) REFERENCES zj_purchases(id), ADD CONSTRAINT fk_drinks_session FOREIGN KEY(sessionId) REFERENCES zj_sessions(id);
ALTER TABLE zj_completeness ADD COLUMN activeKey VARCHAR(80) GENERATED ALWAYS AS (IF(deleted,NULL,CONCAT(`date`,':',`kind`))) STORED, ADD UNIQUE INDEX uk_completeness_active (activeKey);
