package com.codflow.backend.config.service;

import com.codflow.backend.config.entity.SystemSetting;
import com.codflow.backend.config.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SystemSettingService {

    // Well-known keys
    public static final String KEY_SHEET_IMPORT_URL      = "googlesheet.import.url";
    public static final String KEY_ROUND_ROBIN_POINTER   = "assignment.round_robin.last_index";
    public static final String KEY_SHOPIFY_DOMAIN        = "shopify.store.domain";
    public static final String KEY_SHOPIFY_TOKEN         = "shopify.access.token";
    public static final String KEY_SHOPIFY_ENABLED       = "shopify.import.enabled";
    public static final String KEY_SHOPIFY_LAST_ORDER_ID = "shopify.import.last_order_id";

    private final SystemSettingRepository repository;

    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return repository.findById(key).map(SystemSetting::getValue);
    }

    @Transactional
    public void set(String key, String value) {
        SystemSetting setting = repository.findById(key)
                .orElse(new SystemSetting(key, null, null));
        setting.setValue(value);
        repository.save(setting);
    }

    @Transactional
    public void set(String key, String value, String description) {
        SystemSetting setting = repository.findById(key)
                .orElse(new SystemSetting(key, null, description));
        setting.setValue(value);
        if (description != null) setting.setDescription(description);
        repository.save(setting);
    }

    public int getInt(String key, int defaultValue) {
        return get(key).map(v -> {
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
        }).orElse(defaultValue);
    }
}
