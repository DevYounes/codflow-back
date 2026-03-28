#!/bin/bash
# deploy.sh — Script de déploiement CodFlow
# Usage: ./deploy.sh [--first-run]
set -e

DOMAIN="${DOMAIN:-YOUR_DOMAIN}"
FRONTEND_BUILD="${FRONTEND_BUILD:-../codflow-front/dist}"  # chemin vers le build React

echo "==> Déploiement CodFlow"

# ── Première installation uniquement ─────────────────────────────────────────
if [[ "$1" == "--first-run" ]]; then
    echo "==> Première installation..."

    # Vérifier que .env existe
    if [ ! -f .env ]; then
        echo "ERREUR: Le fichier .env n'existe pas. Copiez .env.example et remplissez les valeurs."
        echo "  cp .env.example .env && nano .env"
        exit 1
    fi

    # Démarrer Nginx en HTTP seulement pour obtenir le certificat SSL
    echo "==> Obtention du certificat SSL Let's Encrypt..."
    docker compose up -d nginx certbot
    sleep 5
    docker compose run --rm certbot certonly \
        --webroot \
        --webroot-path=/var/www/certbot \
        --email admin@${DOMAIN} \
        --agree-tos \
        --no-eff-email \
        -d ${DOMAIN}

    echo "==> SSL obtenu. Démarrage complet..."
fi

# ── Copier le build React ─────────────────────────────────────────────────────
if [ -d "$FRONTEND_BUILD" ]; then
    echo "==> Copie du build React..."
    rm -rf nginx/frontend
    cp -r "$FRONTEND_BUILD" nginx/frontend
else
    echo "AVERTISSEMENT: Build React non trouvé dans $FRONTEND_BUILD"
    echo "  Assurez-vous d'avoir lancé 'npm run build' dans le projet frontend"
    mkdir -p nginx/frontend
fi

# ── Pull dernières images et rebuild ─────────────────────────────────────────
echo "==> Build du backend..."
docker compose build --no-cache backend

# ── Démarrer tous les services ────────────────────────────────────────────────
echo "==> Démarrage des services..."
docker compose up -d

# ── Vérification ─────────────────────────────────────────────────────────────
echo "==> Attente du démarrage du backend..."
sleep 15
if docker compose exec -T backend wget -qO- http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "✓ Backend opérationnel"
else
    echo "⚠ Backend pas encore prêt — vérifiez avec: docker compose logs backend"
fi

echo ""
echo "==> Déploiement terminé!"
echo "    Frontend + API: https://${DOMAIN}"
echo "    Logs:           docker compose logs -f"
echo "    Arrêt:          docker compose down"
