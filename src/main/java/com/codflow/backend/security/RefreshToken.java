package com.codflow.backend.security;

import com.codflow.backend.team.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "refresh_tokens")
@EntityListeners(AuditingEntityListener.class)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    /** Expiration absolue — 7 jours par défaut. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Dernière utilisation du token (login ou refresh).
     * Utilisé pour la fenêtre d'inactivité : si now - lastUsedAt > inactivityTimeout,
     * la session est considérée expirée et le token est révoqué.
     */
    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
