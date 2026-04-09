# CLAUDE.md — codflow-back

Application de gestion COD (Cash On Delivery) pour e-commerce marocain.  
Stack : **Spring Boot 3.2.3 / Java 17 / PostgreSQL / Flyway / JWT / WebFlux**

---

## Build & run

```bash
# Compilation (nécessite réseau Maven)
mvn compile

# Build complet
mvn clean package -DskipTests

# La DB est gérée par Flyway — ddl-auto: validate (jamais create/update)
# Nouvelle colonne → créer V<N+1>__description.sql dans src/main/resources/db/migration/
```

Pas de mvnw dans ce repo — utiliser `mvn` directement.  
Maven Central peut être inaccessible dans certains environnements sandboxés.

---

## Architecture des packages

```
com.codflow.backend
├── analytics/       # KPIs, stats agents, performances produits
├── charges/         # Charges métier et frais de livraison
├── common/          # BaseEntity, ApiResponse, PageResponse, exceptions, PhoneNormalizer
├── config/          # Security, JPA Auditing, CORS, SystemSetting (clés config DB)
├── customer/        # Entité Customer, liaison aux commandes
├── delivery/        # Shipments Ozon Express, DeliveryNote, tracking
├── importer/        # Import Excel, Google Sheets (désactivé), Shopify (actif)
├── order/           # Entité Order, OrderItem, statuts, historique, round-robin
├── product/         # Product, ProductVariant, stock
├── security/        # JWT, UserPrincipal, filtre auth
├── stock/           # StockMovement, StockAlert, StockArrival, StockService
└── team/            # User, Role, auth
```

**Dépendances inter-modules critiques :**
- `OrderService` → `StockService` (même transaction `@Transactional(REQUIRED)`)
- `StockService` → `ProductRepository`, `ProductVariantRepository`
- `DeliveryService` → `OrderService` (mise à jour statut après livraison/retour)

---

## Entités principales

### Order
```
orders
├── status (OrderStatus enum)
├── stock_reserved  — true entre CONFIRME et LIVRE
├── stock_deducted  — true après LIVRE
├── confirmed_at, cancelled_at
├── shopify_order_id (déduplification Shopify)
├── customer_phone_normalized (déduplification doublons)
├── is_exchange, source_order_id
└── items → @OneToMany(cascade=ALL, orphanRemoval=true)
```

### OrderItem
```
order_items
├── product_id  → @ManyToOne(LAZY, NO cascade)
├── variant_id  → @ManyToOne(LAZY, NO cascade)
├── unit_cost   — snapshot du costPrice au moment de la confirmation
└── quantity, unit_price, total_price
```

### Product / ProductVariant
```
products / product_variants
├── current_stock   — stock physique
├── reserved_stock  — réservé par commandes confirmées
└── getAvailableStock() = currentStock - reservedStock  [transient]
```

### BaseEntity
Toutes les entités étendent `BaseEntity` : `id`, `createdAt`, `updatedAt` (`@LastModifiedDate`).

---

## Règles métier stock (CRITIQUE)

| Transition | currentStock | reservedStock | Méthode |
|---|---|---|---|
| → CONFIRME | inchangé | +qty | `reserveStockForOrder` |
| → LIVRE | -qty | -qty | `finalizeStockDeduction` |
| CONFIRME → ANNULE | inchangé | -qty | `releaseReservation` |
| CONFIRME → RETOURNE | inchangé | -qty | `releaseReservation` |
| LIVRE → RETOURNE | +qty | inchangé | `restoreStockForOrder` |
| PAS_SERIEUX / FAKE_ORDER / DOUBLON | **aucun impact** | **aucun impact** | — |

**Seul `ANNULE` (pas `PAS_SERIEUX`/`FAKE_ORDER`/`DOUBLON`) libère la réservation.**

`OrderStatus.isCancelled()` retourne true pour les 4 statuts — ne pas l'utiliser pour décider des opérations stock ; tester `newStatus == OrderStatus.ANNULE` explicitement.

---

## Piège Hibernate critique — saveAndFlush avant les opérations stock

`StockService` et `OrderService` partagent le **même `EntityManager`** (`@Transactional(REQUIRED)`).

Les méthodes `@Modifying` dans les repositories stock ont :
```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
```
- `flushAutomatically` : flush TOUTES les entités dirty AVANT le SQL UPDATE
- `clearAutomatically` : vide TOUT le L1 cache APRÈS le SQL UPDATE → toutes les entités deviennent **détachées**

**Conséquence :** si `orderRepository.save(order)` est appelé APRÈS une opération stock, `merge()` sur l'entité détachée peut réécrire un état périmé en DB (notamment `currentStock`).

**Pattern obligatoire dans `OrderService` :**
```java
// 1. Snapshot coûts (items encore MANAGED)
snapshotUnitCosts(order);
// 2. Modifier les flags de l'ordre
order.setStockReserved(true);
// 3. Persister pendant que l'entité est encore MANAGED
orderRepository.saveAndFlush(order);
// 4. Opérations stock (peuvent vider le L1 cache)
reserveStockForOrder(order);
// 5. Rechargement frais
return toDto(getOrderById(orderId), true);
```

Ce pattern est implémenté dans `updateStatus()` et `createExchangeOrder()`.

---

## Import Shopify

**Service :** `ShopifyImportService`  
**Scheduler :** toutes les 5 min (configurable `SHOPIFY_SYNC_INTERVAL`)  
**Mécanisme :** pagination `since_id` (Shopify retourne les IDs **strictement supérieurs** au curseur)

### Clés SystemSetting (stockées en DB)
| Clé | Description |
|---|---|
| `shopify.store.domain` | ex. `castello.myshopify.com` |
| `shopify.access.token` | token OAuth ou Personal Access Token |
| `shopify.last.order.id` | curseur de pagination (since_id) |
| `shopify.app.client_id` | OAuth app client ID |
| `shopify.app.client_secret` | OAuth app client secret |

### Endpoints import (ADMIN)
```
POST /api/v1/import/shopify/trigger              # import immédiat
POST /api/v1/import/shopify/reset-since-id?sinceId=<N>  # reset curseur
GET  /api/v1/import/shopify/status               # état de la config
GET  /api/v1/import/shopify/oauth/start          # démarrer OAuth
GET  /api/v1/import/shopify/oauth/callback       # callback OAuth (public)
POST /api/v1/import/shopify/historical/excel     # migration historique (one-shot)
POST /api/v1/import/backfill-order-costs         # relier items → produits via SKU
```

### Logique curseur since_id (bug corrigé)
Si un ordre échoue à l'import, le curseur **ne doit pas avancer au-delà** de cet ID pour permettre la retraite au prochain cycle.

```java
long maxSuccessId = sinceId;       // avance seulement pour les succès
long minFailedId = Long.MAX_VALUE; // plus petit ID en erreur

long newSinceId = minFailedId != Long.MAX_VALUE
    ? Math.min(maxSuccessId, minFailedId - 1)
    : maxOrderId;
```

### Champs optionnels resilients
`parseShopifyOrder` ne throw plus si téléphone/adresse/ville est absent — remplacés par des valeurs placeholder (`"0000000000"`, `"À compléter"`) avec log WARN.

### Gestion 429 Rate Limit
`fetchOrders()` : handler 429 + retry 5× backoff exponentiel (`Retry.backoff(5, 2s)`).

---

## Livraison (Ozon Express)

**Adaptateur :** `OzonExpressAdapter`  
**Config propriétés :** `OzonExpressProperties` (customer-id, api-key, api-base-url)  
**Sync automatique :** toutes les 5 min (`DELIVERY_SYNC_INTERVAL`)

La livraison déclenche des changements de statut commande :
- Shipment `DELIVERED` → commande `LIVRE` → `finalizeStockDeduction`
- Shipment `RETURNED` → commande `RETOURNE` → restore/release stock selon état

---

## Sécurité

- JWT stateless (jjwt 0.12.3), expiration 24h (configurable `JWT_EXPIRATION`)
- Rôles : `ADMIN`, `MANAGER`, `AGENT`
- Les agents ne voient que **leurs propres commandes** (`assignedTo`)
- Endpoint OAuth Shopify `/oauth/callback` est **public** (appelé par Shopify)

---

## Conventions de code

- **Pas de MapStruct** : les mappings DTO sont faits manuellement dans les services (`toDto(...)`)
- **Lombok** : `@Getter @Setter @NoArgsConstructor` sur les entités, `@RequiredArgsConstructor` sur les services
- **Transactions** : toujours `@Transactional` sur les méthodes de service qui écrivent ; `@Transactional(readOnly=true)` pour les lectures
- **Exceptions** : `BusinessException` (400), `ResourceNotFoundException` (404) — gérées par `GlobalExceptionHandler`
- **Réponses API** : toujours enveloppées dans `ApiResponse<T>` ; listes paginées dans `PageResponse<T>`
- **Numéros commande** : format `COD-YYYYMMDD-XXXXXX` (auto) ou fourni à la création ; Shopify : collision → suffixe UUID court

---

## Variables d'environnement

| Variable | Défaut | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/codflow` | URL PostgreSQL |
| `DB_USERNAME` / `DB_PASSWORD` | `codflow` | Credentials DB |
| `JWT_SECRET` | (valeur dev) | Clé HMAC-SHA256 (min 32 chars) |
| `CORS_ORIGINS` | `http://localhost:4200` | Frontend autorisé |
| `BACKEND_URL` | `http://localhost:8080` | Utilisé pour callback OAuth |
| `SHOPIFY_SYNC_INTERVAL` | `300000` (5 min) | Intervalle import Shopify (ms) |
| `DELIVERY_SYNC_INTERVAL` | `300000` (5 min) | Intervalle sync livraison (ms) |
| `STOCK_ALERT_CHECK_INTERVAL` | `3600000` (1h) | Intervalle check alertes stock |
| `OZON_CUSTOMER_ID` | `79869` | ID client Ozon Express |
| `OZON_API_KEY` | (valeur dev) | Clé API Ozon Express |

---

## Migrations Flyway

Dernière migration : `V20__add_order_exchange.sql`  
Prochaine : `V21__...`  
Ne jamais modifier une migration existante — toujours créer une nouvelle.

---

## Points d'attention pour les futures sessions

1. **Hibernate L1 cache** : voir section "Piège Hibernate" — toujours `saveAndFlush` avant les opérations stock dans `OrderService`
2. **since_id Shopify** : ne jamais avancer le curseur si un ordre a échoué (logique `minFailedId`)
3. **`isCancelled()` vs `== ANNULE`** : `isCancelled()` inclut PAS_SERIEUX/FAKE_ORDER/DOUBLON qui n'ont pas d'impact stock
4. **`snapshotUnitCosts`** : doit être appelé pendant que les items sont encore MANAGED (avant les opérations stock)
5. **Ozon city ID** : champ `deliveryCityId` sur Order — ID numérique requis par Ozon, distinct du champ texte `ville`
6. **Téléphone normalisé** : `PhoneNormalizer.normalize()` pour la déduplification (`0612345678` = `212612345678` = `+212612345678`)
