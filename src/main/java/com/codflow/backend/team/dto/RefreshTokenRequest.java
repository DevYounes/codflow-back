package com.codflow.backend.team.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefreshTokenRequest {

    @NotBlank(message = "Le refresh token est obligatoire")
    private String refreshToken;
}
