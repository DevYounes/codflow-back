package com.codflow.backend.delivery.provider.ozon;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ozon")
public class OzonExpressProperties {
    private String customerId;
    private String apiKey;
    private String apiBaseUrl = "https://api.ozonexpress.ma";
}
