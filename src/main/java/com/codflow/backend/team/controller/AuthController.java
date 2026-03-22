package com.codflow.backend.team.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.security.UserPrincipal;
import com.codflow.backend.team.dto.LoginRequest;
import com.codflow.backend.team.dto.LoginResponse;
import com.codflow.backend.team.dto.UserDto;
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

    @PostMapping("/login")
    @Operation(summary = "Connexion utilisateur")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.login(request)));
    }

    @GetMapping("/me")
    @Operation(summary = "Obtenir le profil de l'utilisateur connecté")
    public ResponseEntity<ApiResponse<UserDto>> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUser(principal.getId())));
    }
}
