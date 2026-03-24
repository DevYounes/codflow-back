package com.codflow.backend.delivery.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProviderConfigRequest {
    private String apiBaseUrl;
    private String apiKey;
    private String apiToken;
    private Boolean active;
}
