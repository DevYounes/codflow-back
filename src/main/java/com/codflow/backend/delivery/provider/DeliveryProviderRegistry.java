package com.codflow.backend.delivery.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry that auto-discovers all DeliveryProviderAdapter implementations.
 * New providers are automatically registered when their @Component class is on the classpath.
 */
@Slf4j
@Component
public class DeliveryProviderRegistry {

    private final Map<String, DeliveryProviderAdapter> providers;

    public DeliveryProviderRegistry(List<DeliveryProviderAdapter> adapters) {
        this.providers = adapters.stream()
                .collect(Collectors.toMap(DeliveryProviderAdapter::getProviderCode, Function.identity()));
        log.info("Registered {} delivery providers: {}", providers.size(), providers.keySet());
    }

    public DeliveryProviderAdapter getProvider(String code) {
        DeliveryProviderAdapter adapter = providers.get(code);
        if (adapter == null) {
            throw new IllegalArgumentException("No delivery provider registered with code: " + code);
        }
        return adapter;
    }

    public boolean hasProvider(String code) {
        return providers.containsKey(code);
    }

    public List<String> getRegisteredProviderCodes() {
        return List.copyOf(providers.keySet());
    }
}
