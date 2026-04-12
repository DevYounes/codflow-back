package com.codflow.backend.team.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {
    private String accessToken;
    private String tokenType;
    private Long expiresIn;
    /** Refresh token à utiliser sur POST /api/v1/auth/refresh pour obtenir un nouvel access token. */
    private String refreshToken;
    private UserDto user;
}
