package com.codflow.backend.common.util;

/**
 * Normalizes Moroccan phone numbers.
 *
 * All formats → "612345678" (9-digit canonical) or "0612345678" (local):
 *   0612345678      (local format)
 *   663XXXXXX       (missing leading 0)
 *   212612345678    (international without +)
 *   +212612345678   (international with +)
 *   00212612345678  (international with 00)
 */
public final class PhoneNormalizer {

    private PhoneNormalizer() {}

    /**
     * Returns the 9-digit canonical form (e.g. "612345678"), or null if blank/invalid.
     */
    public static String normalize(String phone) {
        if (phone == null || phone.isBlank()) return null;

        String digits = phone.replaceAll("[^0-9]", "");

        if (digits.startsWith("00212") && digits.length() >= 14) {
            digits = digits.substring(5);
        } else if (digits.startsWith("212") && digits.length() >= 12) {
            digits = digits.substring(3);
        }
        // Second pass: strip leading 0 left after country-code removal (e.g. +2120661… → 0661…)
        if (digits.startsWith("0") && digits.length() >= 10) {
            digits = digits.substring(1);
        }

        return digits.isEmpty() ? null : digits;
    }

    /**
     * Returns the local 10-digit format starting with 0 (e.g. "0612345678"),
     * or the original trimmed value if normalization fails.
     */
    public static String toLocalFormat(String phone) {
        if (phone == null || phone.isBlank()) return phone;
        String normalized = normalize(phone);
        if (normalized == null) return phone.trim();
        return "0" + normalized;
    }
}
