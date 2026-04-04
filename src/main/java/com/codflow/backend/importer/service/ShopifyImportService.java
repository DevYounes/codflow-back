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
            if (result.getImported() > 0 || result.getErrors() > 0) {
                log.info("Shopify scheduled sync: {} imported, {} skipped, {} errors",
                        result.getImported(), result.getSkipped(), result.getErrors());
            }
        } catch (Exception e) {
            log.error("Shopify scheduled sync failed: {}", e.getMessage(), e);
        }
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

        List<String> errors  = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int imported  = 0;
        int totalRows = 0;
        long maxOrderId = sinceId;

        // Shopify paginates via since_id — fetch all pages
        long pageSinceId = sinceId;
        while (true) {
            String url = buildOrdersUrl(domain, pageSinceId);
            JsonNode ordersNode = fetchOrders(url, token);
            if (ordersNode == null || !ordersNode.isArray() || ordersNode.isEmpty()) break;

            for (JsonNode order : ordersNode) {
                totalRows++;
                long shopifyId = order.path("id").asLong();
                if (shopifyId > maxOrderId) maxOrderId = shopifyId;

                try {
                    String shopifyOrderName = order.path("name").asText(); // e.g. "#1001"
                    if (orderRepository.existsByShopifyOrderId(String.valueOf(shopifyId))) {
                        skipped.add(shopifyOrderName + " déjà importée");
                        continue;
                    }
                    CreateOrderRequest request = parseShopifyOrder(order);
                    orderService.createOrder(request, null);
                    imported++;
                } catch (Exception e) {
                    errors.add("Commande Shopify #" + shopifyId + ": " + e.getMessage());
                    log.warn("Shopify import error for order {}: {}", shopifyId, e.getMessage());
                }
            }

            // If we got fewer than PAGE_LIMIT, no more pages
            if (ordersNode.size() < PAGE_LIMIT) break;

            // Next page starts after the last order in this batch
            pageSinceId = ordersNode.get(ordersNode.size() - 1).path("id").asLong();
        }

        if (maxOrderId > sinceId) {
            settingService.set(SystemSettingService.KEY_SHOPIFY_LAST_ORDER_ID, String.valueOf(maxOrderId));
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
        String base = "https://" + domain + "/admin/api/" + SHOPIFY_API_VERSION
                + "/orders.json?status=any&limit=" + PAGE_LIMIT + "&order=id+asc";
        if (sinceId > 0) base += "&since_id=" + sinceId;
        return base;
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
                    .bodyToMono(String.class)
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
            log.error("Failed to fetch Shopify orders: {} — {}", e.getClass().getSimpleName(), e.getMessage());
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
        String phone = shipping.path("phone").asText(null);
        if (blank(phone)) phone = customer.path("phone").asText(null);
        if (blank(phone)) phone = order.path("phone").asText(null);
        if (blank(phone)) throw new IllegalArgumentException("Téléphone manquant");
        req.setCustomerPhone(PhoneNormalizer.toLocalFormat(phone));

        // Address
        String address = shipping.path("address1").asText(null);
        String address2 = shipping.path("address2").asText(null);
        if (!blank(address2)) address = address + ", " + address2;
        if (blank(address)) throw new IllegalArgumentException("Adresse manquante");
        req.setAddress(address);

        String city = shipping.path("city").asText(null);
        if (blank(city)) throw new IllegalArgumentException("Ville manquante");
        req.setVille(city);
        req.setCity(city);
        req.setZipCode(shipping.path("zip").asText(null));

        // Notes
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
        req.setItems(items);
        return req;
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
