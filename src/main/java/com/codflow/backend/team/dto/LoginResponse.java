package com.codflow.backend.team.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {
    private String accessToken;
    private String tokenType = "Bearer";
    private Long expiresIn;
    private UserDto user;
}
