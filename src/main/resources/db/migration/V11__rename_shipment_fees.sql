-- Align column names with the actual Ozon Express API field names:
--   DELIVERED-PRICE → delivered_price
--   RETURNED-PRICE  → returned_price  (was return_fee)
--   REFUSED-PRICE   → refused_price   (new)
-- applied_fee / applied_fee_type stay as-is.

ALTER TABLE delivery_shipments
    RENAME COLUMN delivery_fee TO delivered_price;

ALTER TABLE delivery_shipments
    RENAME COLUMN return_fee TO returned_price;

ALTER TABLE delivery_shipments
    ADD COLUMN refused_price DECIMAL(10,2);
