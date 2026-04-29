package com.codflow.backend.importer.service;

import com.codflow.backend.order.entity.Order;
import com.codflow.backend.common.util.PhoneNormalizer;
import com.codflow.backend.config.service.SystemSettingService;
import com.codflow.backend.importer.dto.ImportResultDto;
import com.codflow.backend.order.dto.CreateOrderRequest;
import com.codflow.backend.order.enums.OrderSource;
import com.codflow.backend.order.repository.OrderRepository;
import com.codflow.backend.order.service.OrderService;
import com.codflow.backend.product.entity.ProductVariant;
import com.codflow.backend.product.repository.ProductRepository;
import com.codflow.backend.product.repository.ProductVariantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Imports orders from Shopify REST API using incremental sync (since_id).
 *
 * Configuration (via PUT /api/v1/settings/{key}):
 *   shopify.store.domain   → mon-shop.myshopify.com
 *   shopify.access.token   → shpat_xxxxxxxxxxxx
 *   shopify.import.enabled → true
 *
 * The last imported order ID is tracked automatically in shopify.import.last_order_id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopifyImportService {

    private static final String SHOPIFY_API_VERSION    = "2024-01";
    private static final int    PAGE_LIMIT             = 250;
    private static final String OAUTH_CALLBACK_PATH    = "/api/v1/import/shopify/oauth/callback";

    @Value("${app.backend.url:http://localhost:8080}")
    private String backendUrl;

    private final SystemSettingService      settingService;
    private final OrderService              orderService;
    private final OrderRepository           orderRepository;
    private final ProductRepository         productRepository;
    private final ProductVariantRepository  variantRepository;
    private final WebClient.Builder         webClientBuilder;
    private final ObjectMapper              objectMapper;

    /**
     * Scheduled sync — runs every 5 minutes by default.
     * Override with app.shopify.sync-interval-ms in application.yml.
     */
    @Scheduled(fixedDelayString = "${app.shopify.sync-interval-ms:300000}")
    public void scheduledSync() {
        if (!"true".equalsIgnoreCase(settingService.get(SystemSettingService.KEY_SHOPIFY_ENABLED).orElse("false"))) {
            return;
        }
        try {
            ImportResultDto result = doImport();
            log.info("Shopify scheduled sync: {} importées, {} ignorées, {} erreurs (total={})",
                    result.getImported(), result.getSkipped(), result.getErrors(), result.getTotalRows());
            if (!result.getErrorMessages().isEmpty()) {
                result.getErrorMessages().forEach(msg -> log.warn("  [SHOPIFY-ERR] {}", msg));
            }
        } catch (Exception e) {
            log.error("Shopify scheduled sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Resets the since_id cursor to 0 (full re-import) or to a specific value.
     * Useful when the cursor is stuck or when you need to re-import orders.
     */
    public void resetSinceId(long sinceId) {
        settingService.set(SystemSettingService.KEY_SHOPIFY_LAST_ORDER_ID, String.valueOf(sinceId));
        log.warn("Shopify import: since_id reset to {}", sinceId);
    }

    /**
     * Manual trigger — called from the controller.
     */
    @Transactional
    public ImportResultDto triggerImport() {
        String domain = settingService.get(SystemSettingService.KEY_SHOPIFY_DOMAIN).orElse(null);
        String token  = settingService.get(SystemSettingService.KEY_SHOPIFY_TOKEN).orElse(null);
        if (domain == null || domain.isBlank()) {
            throw new IllegalStateException(
                    "Domaine Shopify non configuré. Définissez: " + SystemSettingService.KEY_SHOPIFY_DOMAIN);
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Token Shopify non configuré. Définissez: " + SystemSettingService.KEY_SHOPIFY_TOKEN);
        }
        return doImport();
    }

    /**
     * Returns current Shopify configuration status (without exposing the token).
     */
    public java.util.Map<String, Object> getStatus() {
        String domain  = settingService.get(SystemSettingService.KEY_SHOPIFY_DOMAIN).orElse(null);
        String token   = settingService.get(SystemSettingService.KEY_SHOPIFY_TOKEN).orElse(null);
        String enabled = settingService.get(SystemSettingService.KEY_SHOPIFY_ENABLED).orElse("false");
        String lastId  = settingService.get(SystemSettingService.KEY_SHOPIFY_LAST_ORDER_ID).orElse("0");
        return java.util.Map.of(
                "configured", domain != null && token != null,
                "enabled",    "true".equalsIgnoreCase(enabled),
                "domain",     domain != null ? domain : "",
                "lastSyncedOrderId", lastId
        );
    }

    // -----------------------------------------------------------------------
    // OAuth flow
    // -----------------------------------------------------------------------

    /**
     * Step 1: Generates the Shopify OAuth authorization URL.
     * Requires shopify.store.domain and shopify.app.client_id to be configured.
     */
    @Transactional
    public String startOAuth() {
        String clientId = settingService.get(SystemSettingService.KEY_SHOPIFY_CLIENT_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "shopify.app.client_id non configuré. Définissez-le via PUT /api/v1/settings/shopify.app.client_id"));
        String domain = settingService.get(SystemSettingService.KEY_SHOPIFY_DOMAIN)
                .orElseThrow(() -> new IllegalStateException(
                        "shopify.store.domain non configuré. Définissez-le via PUT /api/v1/settings/shopify.store.domain"));

        String state = UUID.randomUUID().toString().replace("-", "");
        settingService.set(SystemSettingService.KEY_SHOPIFY_OAUTH_STATE, state);

        // Le callback pointe toujours vers le backend (jamais vers le frontend)
        String callbackUrl = backendUrl + OAUTH_CALLBACK_PATH;
        String encodedRedirect = URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);
        log.info("Shopify OAuth start — redirect_uri={}", callbackUrl);

        return "https://" + domain + "/admin/oauth/authorize"
                + "?client_id=" + clientId
                + "&scope=read_orders,write_orders"
                + "&redirect_uri=" + encodedRedirect
                + "&state=" + state;
    }

    /**
     * Step 2: Exchanges the OAuth code for an access token and stores it.
     * Called from the OAuth callback endpoint after Shopify redirects back.
     */
    @Transactional
    public void completeOAuth(String shop, String code, String state) {
        String expectedState = settingService.get(SystemSettingService.KEY_SHOPIFY_OAUTH_STATE).orElse(null);
        if (expectedState == null || expectedState.isBlank() || !expectedState.equals(state)) {
            throw new IllegalStateException("State OAuth invalide — lien expiré ou attaque CSRF détectée");
        }

        String clientId = settingService.get(SystemSettingService.KEY_SHOPIFY_CLIENT_ID)
                .orElseThrow(() -> new IllegalStateException("shopify.app.client_id non configuré"));
        String clientSecret = settingService.get(SystemSettingService.KEY_SHOPIFY_CLIENT_SECRET)
                .orElseThrow(() -> new IllegalStateException("shopify.app.client_secret non configuré"));

        try {
            String responseBody = webClientBuilder.build()
                    .post()
                    .uri("https://" + shop + "/admin/oauth/access_token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("client_id", clientId, "client_secret", clientSecret, "code", code))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = objectMapper.readTree(responseBody);
            String accessToken = json.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("Token vide dans la réponse Shopify");
            }

            settingService.set(SystemSettingService.KEY_SHOPIFY_TOKEN, accessToken);
            settingService.set(SystemSettingService.KEY_SHOPIFY_DOMAIN, shop);
            settingService.set(SystemSettingService.KEY_SHOPIFY_ENABLED, "true");
            settingService.set(SystemSettingService.KEY_SHOPIFY_OAUTH_STATE, ""); // invalidate state

            log.info("Shopify OAuth terminé avec succès pour la boutique: {}", shop);
        } catch (WebClientResponseException e) {
            throw new IllegalStateException(
                    "Erreur Shopify OAuth (HTTP " + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Erreur lors de l'échange OAuth: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------

    private ImportResultDto doImport() {
        String domain = settingService.get(SystemSettingService.KEY_SHOPIFY_DOMAIN)
                .orElseThrow(() -> new IllegalStateException("shopify.store.domain non configuré"));
        String token  = settingService.get(SystemSettingService.KEY_SHOPIFY_TOKEN)
                .orElseThrow(() -> new IllegalStateException("shopify.access.token non configuré"));

        long sinceId = settingService.get(SystemSettingService.KEY_SHOPIFY_LAST_ORDER_ID)
                .map(s -> { try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; } })
                .orElse(0L);

        log.info("Shopify import: démarrage depuis since_id={} (domaine={})", sinceId, domain);

        List<String> errors  = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int imported  = 0;
        int totalRows = 0;
        // maxOrderId is used only for pagination within this run
        long maxOrderId = sinceId;
        // maxSuccessId tracks the highest ID that was successfully imported or skipped —
        // this is the cursor we save to DB so failed orders are retried next run
        long maxSuccessId = sinceId;
        // minFailedId tracks the lowest ID that errored — we must not advance past it
        long minFailedId = Long.MAX_VALUE;

        // Shopify paginates via since_id — fetch all pages
        long pageSinceId = sinceId;
        while (true) {
            String url = buildOrdersUrl(domain, pageSinceId);
            JsonNode ordersNode = fetchOrders(url, token);
            if (ordersNode == null || !ordersNode.isArray() || ordersNode.isEmpty()) {
                log.info("Shopify import: 0 commande retournée pour since_id={} (fin de pagination ou API vide)", pageSinceId);
                break;
            }
            log.info("Shopify import: {} commandes reçues pour since_id={}", ordersNode.size(), pageSinceId);

            for (JsonNode order : ordersNode) {
                totalRows++;
                long shopifyId = order.path("id").asLong();
                if (shopifyId > maxOrderId) maxOrderId = shopifyId;

                try {
                    String shopifyOrderName = order.path("name").asText(); // e.g. "#1001"
                    if (orderRepository.existsByShopifyOrderId(String.valueOf(shopifyId))) {
                        skipped.add(shopifyOrderName + " déjà importée");
                        // Already in DB — safe to advance cursor past it
                        if (shopifyId > maxSuccessId) maxSuccessId = shopifyId;
                        continue;
                    }
                    CreateOrderRequest request = parseShopifyOrder(order);
                    orderService.createOrder(request, null);
                    imported++;
                    if (shopifyId > maxSuccessId) maxSuccessId = shopifyId;
                } catch (Exception e) {
                    errors.add("Commande Shopify #" + shopifyId + ": " + e.getMessage());
                    log.warn("Shopify import error for order {}: {}", shopifyId, e.getMessage());
                    // Track lowest failed ID — we must not advance the cursor past it
                    if (shopifyId < minFailedId) minFailedId = shopifyId;
                }
            }

            // If we got fewer than PAGE_LIMIT, no more pages
            if (ordersNode.size() < PAGE_LIMIT) break;

            // Next page starts after the last order in this batch (pagination only)
            pageSinceId = ordersNode.get(ordersNode.size() - 1).path("id").asLong();
        }

        // Advance the cursor only to the last successfully processed order.
        // If there were errors, we cap at (minFailedId - 1) so failed orders are retried next run.
        long newSinceId = minFailedId != Long.MAX_VALUE
                ? Math.min(maxSuccessId, minFailedId - 1)
                : maxOrderId;
        if (newSinceId > sinceId) {
            settingService.set(SystemSettingService.KEY_SHOPIFY_LAST_ORDER_ID, String.valueOf(newSinceId));
            log.info("Shopify import: curseur since_id sauvegardé → {}", newSinceId);
        } else if (minFailedId != Long.MAX_VALUE) {
            log.warn("Shopify import: curseur non avancé à cause d'erreurs (minFailedId={}, maxSuccessId={}). " +
                    "Les commandes en erreur seront réessayées au prochain cycle.", minFailedId, maxSuccessId);
        }

        return ImportResultDto.builder()
                .totalRows(totalRows)
                .imported(imported)
                .skipped(skipped.size())
                .errors(errors.size())
                .errorMessages(errors)
                .skippedMessages(skipped)
                .build();
    }

    private String buildOrdersUrl(String domain, long sinceId) {
        // since_id est TOUJOURS passé (même =0) : c'est lui qui force le tri id ASC
        // documenté par Shopify et garantit la pagination des plus anciennes vers
        // les plus récentes. Sans since_id, l'API trie par id DESC et un store de
        // plus de 250 commandes verrait ses plus anciennes silencieusement ignorées.
        return "https://" + domain + "/admin/api/" + SHOPIFY_API_VERSION
                + "/orders.json?status=any&limit=" + PAGE_LIMIT + "&since_id=" + sinceId;
    }

    private JsonNode fetchOrders(String url, String token) {
        try {
            // 10 MB buffer — Shopify responses can be large (200+ orders with all fields)
            ExchangeStrategies strategies = ExchangeStrategies.builder()
                    .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();

            String body = webClientBuilder
                    .exchangeStrategies(strategies)
                    .build()
                    .get()
                    .uri(url)
                    .header("X-Shopify-Access-Token", token)
                    .retrieve()
                    // Handle 429 Too Many Requests — Shopify rate limit
                    .onStatus(status -> status.value() == 429, response -> {
                        String retryAfter = response.headers().asHttpHeaders()
                                .getFirst("Retry-After");
                        long waitSeconds = 10; // default if header absent
                        if (retryAfter != null) {
                            try { waitSeconds = Long.parseLong(retryAfter.trim()); } catch (NumberFormatException ignored) {}
                        }
                        log.warn("Shopify rate limit (429) — attente {}s avant de réessayer.", waitSeconds);
                        long waitMs = waitSeconds * 1000L;
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        return reactor.core.publisher.Mono.error(
                                new WebClientResponseException(429, "Too Many Requests", null, null, null));
                    })
                    .bodyToMono(String.class)
                    // Retry jusqu'à 5 fois sur erreurs réseau transitoires (Connection reset, timeout, 429…)
                    // avec backoff exponentiel : 2s, 4s, 8s, 16s, 32s
                    .retryWhen(Retry.backoff(5, Duration.ofSeconds(2))
                            .filter(ex -> !(ex instanceof WebClientResponseException wce)
                                    || wce.getStatusCode().value() == 429)
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .block();

            if (body == null || body.isBlank()) {
                log.warn("Shopify returned empty body for: {}", url);
                return null;
            }

            JsonNode root = objectMapper.readTree(body);

            // Shopify sometimes returns 200 with an error in the body
            if (root.has("errors")) {
                log.error("Shopify API error in body: {}", root.path("errors").asText());
                return null;
            }

            return root.path("orders");
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw new IllegalStateException(
                        "Accès refusé à Shopify (HTTP " + e.getStatusCode().value()
                        + "). Vérifiez le token d'accès.");
            }
            log.error("Shopify API HTTP error: {} — body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            // Connection reset / timeout — erreur réseau transitoire
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Shopify sync: erreur réseau transitoire (sera retenté au prochain cycle) — {}", msg);
            return null;
        }
    }

    private CreateOrderRequest parseShopifyOrder(JsonNode order) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setSource(OrderSource.SHOPIFY);
        req.setShopifyOrderId(order.path("id").asText());

        // Order number — use Shopify's "#1001" name, fallback to order_number
        String orderName = order.path("name").asText(null);
        req.setOrderNumber(orderName != null ? orderName.replace("#", "SHOP-") : null);

        // Customer info — prefer shipping_address
        JsonNode shipping = order.path("shipping_address");
        JsonNode customer = order.path("customer");

        String firstName = cleanName(shipping.path("first_name").asText(
                customer.path("first_name").asText("")));
        String lastName  = cleanName(shipping.path("last_name").asText(
                customer.path("last_name").asText("")));
        String fullName  = (firstName + " " + lastName).trim();
        if (fullName.isEmpty()) fullName = order.path("email").asText("Client Shopify");
        req.setCustomerName(fullName);

        // Phone — shipping_address.phone or customer.phone or order.phone
        // Use a placeholder if missing so the order is imported and an agent can complete it later.
        String phone = shipping.path("phone").asText(null);
        if (blank(phone)) phone = customer.path("phone").asText(null);
        if (blank(phone)) phone = order.path("phone").asText(null);
        if (blank(phone)) {
            phone = "0000000000";
            log.warn("[SHOPIFY-IMPORT] Commande {} : téléphone manquant — importée avec '0000000000', à compléter manuellement.",
                    order.path("name").asText(req.getShopifyOrderId()));
        }
        req.setCustomerPhone(PhoneNormalizer.toLocalFormat(phone));

        // Address — use placeholder if missing
        String address = shipping.path("address1").asText(null);
        String address2 = shipping.path("address2").asText(null);
        if (!blank(address2)) address = address + ", " + address2;
        if (blank(address)) {
            address = "À compléter";
            log.warn("[SHOPIFY-IMPORT] Commande {} : adresse manquante — importée avec placeholder, à compléter manuellement.",
                    order.path("name").asText(req.getShopifyOrderId()));
        }
        req.setAddress(address);

        // City — use placeholder if missing
        String city = shipping.path("city").asText(null);
        if (blank(city)) {
            city = "À compléter";
            log.warn("[SHOPIFY-IMPORT] Commande {} : ville manquante — importée avec placeholder, à compléter manuellement.",
                    order.path("name").asText(req.getShopifyOrderId()));
        }
        req.setVille(city);
        req.setCity(city);
        req.setZipCode(shipping.path("zip").asText(null));

        // Notes — append import warnings if any fields were missing
        req.setNotes(order.path("note").asText(null));

        // Shipping cost from first shipping line
        BigDecimal shippingCost = BigDecimal.ZERO;
        JsonNode shippingLines = order.path("shipping_lines");
        if (shippingLines.isArray() && !shippingLines.isEmpty()) {
            String shippingPrice = shippingLines.get(0).path("price").asText("0");
            shippingCost = parseBigDecimal(shippingPrice);
        }
        req.setShippingCost(shippingCost);

        // Line items
        JsonNode lineItems = order.path("line_items");
        if (!lineItems.isArray() || lineItems.isEmpty()) {
            throw new IllegalArgumentException("Aucun article dans la commande");
        }

        List<CreateOrderRequest.OrderItemRequest> items = new ArrayList<>();
        for (JsonNode li : lineItems) {
            CreateOrderRequest.OrderItemRequest item = new CreateOrderRequest.OrderItemRequest();

            String title   = li.path("title").asText(null);
            String variant = li.path("variant_title").asText(null);
            String productName = blank(variant) || "Default Title".equals(variant)
                    ? title
                    : title + " - " + variant;
            item.setProductName(productName);
            item.setQuantity(li.path("quantity").asInt(1));
            item.setUnitPrice(parseBigDecimal(li.path("price").asText("0")));

            String sku = li.path("sku").asText(null);
            if (!blank(sku)) {
                item.setProductSku(sku);
                // 1. Essayer SKU produit exact
                var product = productRepository.findBySku(sku);
                if (product.isPresent()) {
                    item.setProductId(product.get().getId());
                    log.info("[SKU-MATCH] SKU '{}' → produit id={} '{}'", sku, product.get().getId(), product.get().getName());
                } else {
                    // 2. Essayer SKU variante (ex: "VM-42" → variante pointure 42 du produit VM)
                    var variantOpt = variantRepository.findByVariantSku(sku);
                    if (variantOpt.isPresent()) {
                        ProductVariant v = variantOpt.get();
                        item.setProductId(v.getProduct().getId());
                        item.setVariantId(v.getId());
                        log.info("[SKU-MATCH] SKU '{}' → variante id={} (produit id={} '{}')", sku, v.getId(), v.getProduct().getId(), v.getProduct().getName());
                    } else {
                        log.warn("[SKU-MATCH] SKU '{}' introuvable — ni produit ni variante. Stock et coût ignorés.", sku);
                    }
                }
            } else {
                log.warn("[SKU-MATCH] Article '{}' sans SKU — impossible de lier au produit.", item.getProductName());
            }

            items.add(item);
        }

        // Offres "achetez 2 produits" : Shopify peut envoyer une variante par défaut
        // (ex: pointure 39) pour les articles supplémentaires au lieu de la variante
        // choisie par le client. On aligne donc la pointure des articles suivants sur
        // celle du premier article.
        alignSizesOnFirstItem(items);

        req.setItems(items);
        return req;
    }

    /**
     * Aligne la pointure des articles 2+ sur celle du premier article.
     * Si le premier article a une variante avec une pointure, les articles
     * suivants sont réassociés à la variante du même produit qui possède
     * cette pointure (si elle existe).
     */
    private void alignSizesOnFirstItem(List<CreateOrderRequest.OrderItemRequest> items) {
        if (items == null || items.size() < 2) return;

        CreateOrderRequest.OrderItemRequest first = items.get(0);
        if (first.getVariantId() == null) return;

        ProductVariant firstVariant = variantRepository.findById(first.getVariantId()).orElse(null);
        if (firstVariant == null || blank(firstVariant.getSize())) return;

        String referenceSize = firstVariant.getSize();

        for (int i = 1; i < items.size(); i++) {
            CreateOrderRequest.OrderItemRequest it = items.get(i);
            if (it.getProductId() == null) continue;

            // Déjà sur la bonne pointure → rien à faire
            if (it.getVariantId() != null) {
                ProductVariant current = variantRepository.findById(it.getVariantId()).orElse(null);
                if (current != null && referenceSize.equalsIgnoreCase(current.getSize())) continue;
            }

            var match = variantRepository.findFirstByProductIdAndSizeIgnoreCase(it.getProductId(), referenceSize);
            if (match.isEmpty()) {
                log.warn("[SIZE-ALIGN] Article #{} '{}' : variante pointure '{}' introuvable pour produit id={} — aucun réalignement",
                        i + 1, it.getProductName(), referenceSize, it.getProductId());
                continue;
            }

            ProductVariant target = match.get();
            Long previousVariantId = it.getVariantId();
            it.setVariantId(target.getId());
            if (target.getVariantSku() != null) it.setProductSku(target.getVariantSku());

            // Recompose le nom affiché avec la nouvelle variante.
            // NB: target.getProduct() est LAZY — on ne peut pas appeler .getName() dessus
            // hors session Hibernate. On relit le product via son repo (session dédiée).
            String baseName = productRepository.findById(it.getProductId())
                    .map(p -> p.getName())
                    .orElse(it.getProductName());
            String suffix = blank(target.getColor())
                    ? target.getSize()
                    : target.getColor() + " / " + target.getSize();
            it.setProductName(baseName + " - " + suffix);

            log.info("[SIZE-ALIGN] Article #{} aligné sur pointure '{}' : variante {} → {} ({})",
                    i + 1, referenceSize, previousVariantId, target.getId(), it.getProductName());
        }
    }

    private BigDecimal parseBigDecimal(String val) {
        if (blank(val)) return BigDecimal.ZERO;
        try { return new BigDecimal(val.replace(",", ".").trim()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Supprime les tirets et espaces parasites en début/fin de nom (ex: "- Ahmed" → "Ahmed"). */
    private String cleanName(String s) {
        if (s == null) return "";
        return s.replaceAll("^[\\s\\-]+|[\\s\\-]+$", "").replaceAll("\\s{2,}", " ").trim();
    }
}
