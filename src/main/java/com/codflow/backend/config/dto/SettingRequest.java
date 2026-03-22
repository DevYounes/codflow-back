package com.codflow.backend.config.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SettingRequest {
    private String value;
    private String description;
}
