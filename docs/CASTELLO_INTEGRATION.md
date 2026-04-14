# Intégration CASTELLO → CODflow

Ce document décrit l'API publique de CODflow-back utilisée par le site vitrine
**CASTELLO** (Next.js) pour pousser ses commandes et lire son catalogue.

---

## Principe

```
  CASTELLO (Next.js)               CODflow-back (Spring Boot)
  ─────────────────                ──────────────────────────
                                   /api/v1/public/**
  Formulaire commande    ──POST──▶ ApiKeyAuthenticationFilter
                                     │
                                     ▼
                                   PublicOrderController
                                     │
                                     ▼
                                   OrderService.createOrder()
                                   source = CASTELLO_DIRECT
                                   status = NOUVEAU
                                   round-robin agent assignment
```

- Authentification : **en-tête `X-API-Key`** (pas JWT).
- Idempotence : le champ `externalRef` permet de retenter un POST sans
  créer de doublon en cas d'échec réseau.
- Les commandes CASTELLO suivent ensuite le même cycle de vie que les autres
  (confirmation téléphone agent, expédition Ozon, livraison, etc.).

---

## Configuration

### 1. Générer une clé API côté serveur

```bash
openssl rand -hex 32
# ex. 5f2d7a91b4c6e3f0a8d4e7b2c9f1a5d8e3b6c7f2a9d1e4b8c5f0a7e2d9b3c6f1
```

### 2. Variables d'environnement CODflow-back

Dans `.env` (ou `application.yml`) :

```
CASTELLO_API_KEY=5f2d7a91b4c6e3f0a8d4e7b2c9f1a5d8e3b6c7f2a9d1e4b8c5f0a7e2d9b3c6f1
CORS_ORIGINS=https://castello.ma,http://localhost:3000,http://localhost:4200
```

Sans `CASTELLO_API_KEY`, l'API publique renvoie **503** (par sécurité).

### 3. Variables d'environnement CASTELLO (Next.js)

Dans `.env.local` :

```
CODFLOW_API_URL=http://localhost:8080/api/v1
CODFLOW_API_KEY=5f2d7a91b4c6e3f0a8d4e7b2c9f1a5d8e3b6c7f2a9d1e4b8c5f0a7e2d9b3c6f1
```

---

## Endpoints

### `POST /api/v1/public/orders/castello`

Crée une commande dans CODflow (source `CASTELLO_DIRECT`, statut `NOUVEAU`).

**Headers**
```
Content-Type: application/json
X-API-Key: <CASTELLO_API_KEY>
```

**Body**
```json
{
  "externalRef": "CAT-20260414ABCD",
  "customerName": "Youssef El Amrani",
  "customerPhone": "0612345678",
  "customerPhone2": null,
  "address": "12 rue des Orangers, Hay Riad",
  "ville": "Rabat",
  "deliveryCityId": null,
  "notes": "Livraison après 18h",
  "items": [
    {
      "productSku": "CAT-001",
      "productId": null,
      "variantId": null,
      "productName": "Derby Classique",
      "quantity": 1,
      "unitPrice": 699
    }
  ]
}
```

**Réponse 201**
```json
{
  "success": true,
  "message": "Commande CASTELLO enregistrée avec succès",
  "data": {
    "id": 1234,
    "orderNumber": "COD-20260414-A1B2C3",
    "status": "NOUVEAU",
    "source": "CASTELLO_DIRECT",
    "customerName": "Youssef El Amrani",
    "customerPhone": "0612345678",
    "totalAmount": 699,
    "createdAt": "2026-04-14T15:42:03",
    "items": [ ... ]
  }
}
```

**Idempotence**

Si un `externalRef` identique a déjà été reçu, la commande existante est
retournée en **200 OK** avec `message: "Commande déjà enregistrée (retry idempotent)"`.
CASTELLO peut donc retenter un POST en toute sécurité.

**Erreurs**

| Code | Cas |
|---|---|
| `400` | Validation échouée (téléphone invalide, items vides, etc.) |
| `401` | `X-API-Key` absente ou incorrecte |
| `503` | `CASTELLO_API_KEY` non configurée côté serveur |

---

### `GET /api/v1/public/products`

Liste tous les produits actifs, avec leurs variantes actives.

**Headers** : `X-API-Key: <CASTELLO_API_KEY>`

**Réponse 200**
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "sku": "CAT-001",
      "name": "Derby Classique",
      "description": "Pièce intemporelle...",
      "price": 699.00,
      "imageUrl": "https://...",
      "availableStock": 42,
      "variants": [
        {
          "id": 11,
          "variantSku": "CAT-001-NOIR-42",
          "color": "Noir",
          "size": "42",
          "price": 699.00,
          "availableStock": 8
        }
      ]
    }
  ]
}
```

Les champs sensibles (`costPrice`, `reservedStock` brut, `minThreshold`) **ne
sont pas exposés**.

---

### `GET /api/v1/public/products/{sku}`

Obtient un produit par son SKU (actif uniquement). Même payload, mais pour un
seul produit. Retourne **404** si le SKU est inconnu ou inactif.

---

## Exemples d'appels cURL

### Créer une commande

```bash
curl -X POST http://localhost:8080/api/v1/public/orders/castello \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $CASTELLO_API_KEY" \
  -d '{
    "externalRef": "CAT-TEST-001",
    "customerName": "Test Client",
    "customerPhone": "0612345678",
    "address": "Test 123",
    "ville": "Casablanca",
    "items": [{
      "productSku": "CAT-001",
      "productName": "Derby Classique",
      "quantity": 1,
      "unitPrice": 699
    }]
  }'
```

### Lister le catalogue

```bash
curl http://localhost:8080/api/v1/public/products \
  -H "X-API-Key: $CASTELLO_API_KEY"
```

---

## Flux côté CASTELLO (Next.js)

Le formulaire `/commander` soumet vers `/api/commander` (route Next.js),
qui relaie vers CODflow :

```typescript
// castello/app/api/commander/route.ts
export async function POST(req: Request) {
  const body = await req.json();
  const res = await fetch(`${process.env.CODFLOW_API_URL}/public/orders/castello`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-API-Key": process.env.CODFLOW_API_KEY!,
    },
    body: JSON.stringify({
      externalRef: body.orderNumber,        // ex: CAT-20260414ABCD
      customerName: body.client.nom,
      customerPhone: body.client.telephone,
      address: body.client.adresse,
      ville: body.client.ville,
      notes: body.client.note,
      items: [{
        productSku: body.produit.sku,
        productName: body.produit.nom,
        quantity: 1,
        unitPrice: body.produit.prix,
      }],
    }),
  });
  return NextResponse.json(await res.json(), { status: res.status });
}
```

---

## Sécurité — check-list

- [ ] `CASTELLO_API_KEY` générée avec `openssl rand -hex 32` (≥ 32 octets)
- [ ] Stockée **uniquement** en variable d'environnement (jamais commitée)
- [ ] `CORS_ORIGINS` contient le domaine CASTELLO (`https://castello.ma`)
- [ ] HTTPS obligatoire en production (sinon la clé circule en clair)
- [ ] Rotation de la clé : changer la variable d'env des deux côtés simultanément
- [ ] Côté CASTELLO : l'appel à CODflow se fait **uniquement** dans un route handler
  Next.js (côté serveur), jamais dans un composant client — sinon la clé fuite.
