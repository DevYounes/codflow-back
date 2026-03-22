package com.codflow.backend.team.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "Le nom d'utilisateur est obligatoire")
    private String usernameOrEmail;

    @NotBlank(message = "Le mot de passe est obligatoire")
    private String password;
}
