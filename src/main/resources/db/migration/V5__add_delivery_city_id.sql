-- V5: Add delivery city ID to orders
-- delivery_city_id: Ozon Express city ID chosen by the agent during confirmation
--   (customer types free-text city, agent maps it to the official Ozon city ID)

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS delivery_city_id VARCHAR(20);
