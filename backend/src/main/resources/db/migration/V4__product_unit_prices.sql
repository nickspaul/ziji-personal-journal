ALTER TABLE zj_products
  ADD COLUMN packagePrice DECIMAL(20,6) NULL,
  ADD COLUMN priceConfirmed BOOLEAN NULL DEFAULT FALSE,
  ADD COLUMN priceType VARCHAR(40) NULL DEFAULT 'PERSONAL';

UPDATE zj_products AS product
JOIN (
  SELECT productId, amount, priceType,
         ROW_NUMBER() OVER (
           PARTITION BY productId
           ORDER BY CASE priceType WHEN 'ACTUAL' THEN 0 WHEN 'PERSONAL' THEN 1 ELSE 2 END,
                    effectiveDate DESC, updatedAt DESC, id DESC
         ) AS priorityOrder
  FROM zj_prices
  WHERE deleted = FALSE AND verified = TRUE AND currency = 'CNY'
    AND effectiveDate <= CURRENT_DATE()
) AS existingPrice ON existingPrice.productId = product.id AND existingPrice.priorityOrder = 1
SET product.packagePrice = existingPrice.amount,
    product.priceConfirmed = TRUE,
    product.priceType = existingPrice.priceType
WHERE product.deleted = FALSE;
