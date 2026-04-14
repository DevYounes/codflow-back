package com.codflow.backend.publicapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Filtre d'authentification par API key pour les endpoints publics
 * ({@code /api/v1/public/**}) appelés par les sites vitrines (ex. CASTELLO).
 *
 * <p>Lit l'en-tête {@code X-API-Key} ou le paramètre de requête {@code apiKey}
 * et, si la valeur correspond à {@code app.castello.api-key}, injecte dans le
 * {@link SecurityContextHolder} une authentification avec l'autorité
 * {@code ROLE_SYSTEM} afin que les endpoints protégés par {@code @PreAuthorize}
 * puissent l'autoriser si nécessaire.</p>
 *
 * <p>Ne fait rien si la clé est manquante : la chaîne de sécurité normale
 * (JWT) prendra le relais, et les endpoints publics restent accessibles
 * car autorisés par {@code permitAll()} dans {@code SecurityConfig}.</p>
 */
@Slf4j
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-API-Key";
    public static final String QUERY_PARAM = "apiKey";
    public static final String SYSTEM_PRINCIPAL = "castello-system";
    public static final String ROLE_SYSTEM = "ROLE_SYSTEM";

    @Value("${app.castello.api-key:}")
    private String configuredKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean isPublicRoute = path != null && path.startsWith("/api/v1/public/");

        if (!isPublicRoute) {
            filterChain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER_NAME);
        if (!StringUtils.hasText(provided)) {
            provided = request.getParameter(QUERY_PARAM);
        }

        // Si aucune clé n'est configurée côté serveur, on refuse par défaut toute requête
        // pour éviter d'exposer l'API sans protection en cas d'oubli de variable d'env.
        if (!StringUtils.hasText(configuredKey)) {
            log.warn("Public API called but app.castello.api-key is not configured — denying request to {}", path);
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Public API disabled: missing server-side API key configuration");
            return;
        }

        if (!StringUtils.hasText(provided) || !constantTimeEquals(provided, configuredKey)) {
            log.warn("Public API rejected (invalid or missing API key) for {}", path);
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid or missing API key");
            return;
        }

        // Clé valide : authentification système
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                SYSTEM_PRINCIPAL, null,
                List.of(new SimpleGrantedAuthority(ROLE_SYSTEM)));
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Comparaison à temps constant pour éviter les timing attacks. */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
