-- Backfill applied_fee and applied_fee_type for shipments that had their status
-- set before the charges feature was introduced (applied_fee_type is NULL).

UPDATE delivery_shipments
SET applied_fee      = COALESCE(delivered_price, 0),
    applied_fee_type = 'LIVRAISON'
WHERE status = 'DELIVERED'
  AND applied_fee_type IS NULL;

UPDATE delivery_shipments
SET applied_fee      = COALESCE(returned_price, 0),
    applied_fee_type = 'RETOUR'
WHERE status = 'RETURNED'
  AND applied_fee_type IS NULL;

UPDATE delivery_shipments
SET applied_fee      = COALESCE(refused_price, 0),
    applied_fee_type = 'REFUS'
WHERE status = 'FAILED_DELIVERY'
  AND applied_fee_type IS NULL;

UPDATE delivery_shipments
SET applied_fee      = 0,
    applied_fee_type = 'ANNULATION'
WHERE status = 'CANCELLED'
  AND applied_fee_type IS NULL;
