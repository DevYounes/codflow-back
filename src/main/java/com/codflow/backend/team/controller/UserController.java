package com.codflow.backend.team.controller;

import com.codflow.backend.common.dto.ApiResponse;
import com.codflow.backend.common.dto.PageResponse;
import com.codflow.backend.team.dto.CreateUserRequest;
import com.codflow.backend.team.dto.UpdateUserRequest;
import com.codflow.backend.team.dto.UserDto;
import com.codflow.backend.team.enums.Role;
import com.codflow.backend.team.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Gestion des membres de l'équipe")
public class UserController {

    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Créer un nouvel utilisateur")
    public ResponseEntity<ApiResponse<UserDto>> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Utilisateur créé avec succès", userService.createUser(request)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Lister les utilisateurs")
    public ResponseEntity<ApiResponse<PageResponse<UserDto>>> getUsers(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.success(userService.getUsers(role, search, pageable)));
    }

    @GetMapping("/agents")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Lister les agents disponibles")
    public ResponseEntity<ApiResponse<List<UserDto>>> getAgents() {
        return ResponseEntity.ok(ApiResponse.success(userService.getAgents()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Obtenir un utilisateur par ID")
    public ResponseEntity<ApiResponse<UserDto>> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUser(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mettre à jour un utilisateur")
    public ResponseEntity<ApiResponse<UserDto>> updateUser(@PathVariable Long id,
                                                           @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Utilisateur mis à jour", userService.updateUser(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Désactiver un utilisateur")
    public ResponseEntity<ApiResponse<Void>> deactivateUser(@PathVariable Long id) {
        userService.deactivateUser(id);
        return ResponseEntity.ok(ApiResponse.success("Utilisateur désactivé"));
    }
}
