package com.codflow.backend.team.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.security.RefreshTokenService;
import com.codflow.backend.security.UserPrincipal;
import com.codflow.backend.team.dto.*;

import com.codflow.backend.team.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentification et gestion de session")
public class AuthController {

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/login")
    @Operation(summary = "Connexion utilisateur — retourne un access token (1h) et un refresh token (7j / 8h inactivité)")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.login(request)));
    }

    @PostMapping("/refresh")
    @Operation(
        summary = "Renouveler l'access token",
        description = """
            Échange un refresh token valide contre un nouvel access token JWT.
            Le refresh token est révoqué si :
            - il est inconnu ou déjà utilisé après expiration
            - plus de 8h se sont écoulées sans activité (inactivity-timeout)
            - son expiration absolue de 7 jours est atteinte
            """
    )
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        String newAccessToken = refreshTokenService.refreshAccessToken(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(
                LoginResponse.builder()
                        .accessToken(newAccessToken)
                        .tokenType("Bearer")
                        .refreshToken(request.getRefreshToken())
                        .build()));
    }

    @PostMapping("/logout")
    @Operation(
        summary = "Déconnexion — révoque le refresh token",
        description = "Supprime le refresh token en base. L'access token restera valide jusqu'à son expiration naturelle (1h max)."
    )
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revokeToken(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Déconnexion réussie"));
    }

    @GetMapping("/me")
    @Operation(summary = "Obtenir le profil de l'utilisateur connecté")
    public ResponseEntity<ApiResponse<UserDto>> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUser(principal.getId())));
    }

    @PatchMapping("/me")
    @Operation(
        summary = "Mettre à jour son propre profil",
        description = "Permet à tout utilisateur authentifié de modifier son prénom, nom, email et téléphone."
    )
    public ResponseEntity<ApiResponse<UserDto>> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Profil mis à jour", userService.updateProfile(principal.getId(), request)));
    }

    @PostMapping("/change-password")
    @Operation(
        summary = "Changer son mot de passe",
        description = "Nécessite le mot de passe actuel. Disponible pour tous les utilisateurs authentifiés."
    )
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.success("Mot de passe modifié avec succès"));
    }
}
