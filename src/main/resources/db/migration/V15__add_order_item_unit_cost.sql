-- Snapshot du coût de revient au moment de la confirmation de la commande
ALTER TABLE order_items ADD COLUMN unit_cost DECIMAL(10,2);
