package com.codflow.backend.security;

import com.codflow.backend.common.exception.BusinessException;
import com.codflow.backend.team.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider tokenProvider;

    /** Expiration absolue du refresh token (défaut 7 jours). */
    @Value("${app.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    /**
     * Fenêtre d'inactivité : si le token n'est pas utilisé pendant cette durée,
     * la session est considérée expirée (défaut 8 heures).
     */
    @Value("${app.jwt.inactivity-timeout-ms:28800000}")
    private long inactivityTimeoutMs;

    /**
     * Crée un refresh token pour un utilisateur après un login réussi.
     * Un utilisateur peut avoir plusieurs refresh tokens (plusieurs appareils/navigateurs).
     */
    @Transactional
    public RefreshToken createRefreshToken(User user) {
        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setToken(UUID.randomUUID().toString());
        rt.setExpiresAt(LocalDateTime.now().plusNanos(refreshExpirationMs * 1_000_000L));
        rt.setLastUsedAt(LocalDateTime.now());
        return refreshTokenRepository.save(rt);
    }

    /**
     * Valide le refresh token et retourne un nouvel access token JWT.
     * Met à jour lastUsedAt (fenêtre glissante d'inactivité).
     *
     * @throws BusinessException si le token est inconnu, expiré ou inactif depuis trop longtemps
     */
    @Transactional
    public String refreshAccessToken(String tokenStr) {
        RefreshToken rt = refreshTokenRepository.findByToken(tokenStr)
                .orElseThrow(() -> new BusinessException("Session invalide. Veuillez vous reconnecter."));

        LocalDateTime now = LocalDateTime.now();

        // Vérification expiration absolue
        if (rt.getExpiresAt().isBefore(now)) {
            refreshTokenRepository.delete(rt);
            throw new BusinessException("Session expirée. Veuillez vous reconnecter.");
        }

        // Vérification inactivité (fenêtre glissante)
        LocalDateTime inactivityCutoff = now.minusNanos(inactivityTimeoutMs * 1_000_000L);
        if (rt.getLastUsedAt().isBefore(inactivityCutoff)) {
            refreshTokenRepository.delete(rt);
            log.info("[SESSION] Token révoqué pour inactivité — utilisateur id={}", rt.getUser().getId());
            throw new BusinessException(
                    "Session expirée pour inactivité. Veuillez vous reconnecter.");
        }

        // Mise à jour de la fenêtre d'inactivité
        rt.setLastUsedAt(now);
        refreshTokenRepository.save(rt);

        return tokenProvider.generateTokenFromUserId(rt.getUser().getId());
    }

    /**
     * Révoque un refresh token spécifique (logout depuis un appareil).
     * Silencieux si le token est déjà supprimé.
     */
    @Transactional
    public void revokeToken(String tokenStr) {
        refreshTokenRepository.findByToken(tokenStr).ifPresent(rt -> {
            refreshTokenRepository.delete(rt);
            log.info("[SESSION] Logout — token révoqué pour utilisateur id={}", rt.getUser().getId());
        });
    }

    /**
     * Révoque tous les refresh tokens d'un utilisateur (logout depuis tous les appareils).
     */
    @Transactional
    public void revokeAllForUser(User user) {
        refreshTokenRepository.deleteByUser(user);
        log.info("[SESSION] Tous les tokens révoqués pour utilisateur id={}", user.getId());
    }

    /**
     * Nettoyage quotidien à 3h du matin — supprime les tokens expirés ou inactifs.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanExpiredTokens() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime inactivityCutoff = now.minusNanos(inactivityTimeoutMs * 1_000_000L);
        refreshTokenRepository.deleteExpiredTokens(now, inactivityCutoff);
        log.info("[SESSION] Nettoyage des tokens expirés effectué");
    }
}
