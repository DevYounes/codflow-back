-- Track whether stock has been deducted for each order.
-- Back-fill: any order that passed through CONFIRME (has confirmed_at) is considered deducted,
-- unless it subsequently reached a terminal return/cancel state where stock was already restored.
ALTER TABLE orders ADD COLUMN stock_deducted BOOLEAN NOT NULL DEFAULT FALSE;

-- Mark as deducted all orders that are confirmed/in-delivery/delivered
-- (these are the orders whose stock is currently out)
UPDATE orders
SET stock_deducted = TRUE
WHERE status IN (
    'CONFIRME',
    'EN_PREPARATION',
    'ENVOYE',
    'EN_LIVRAISON',
    'LIVRE'
);
