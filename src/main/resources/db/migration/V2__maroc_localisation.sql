-- Renommage wilaya -> ville (terminologie marocaine)
ALTER TABLE orders RENAME COLUMN wilaya TO ville;

-- Mise à jour URL Ozon Express vers le domaine marocain
UPDATE delivery_providers
SET api_base_url = 'https://api.ozonexpress.ma'
WHERE code = 'OZON_EXPRESS';
