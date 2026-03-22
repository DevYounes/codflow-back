package com.codflow.backend.common.util;

/**
 * Normalizes Moroccan phone numbers to a canonical 9-digit form.
 *
 * Examples (all → "612345678"):
 *   0612345678     (local format)
 *   212612345678   (international without +)
 *   +212612345678  (international with +)
 *   00212612345678 (international with 00)
 */
public final class PhoneNormalizer {

    private PhoneNormalizer() {}

    /**
     * Returns the normalized 9-digit phone number, or null if input is blank.
     */
    public static String normalize(String phone) {
        if (phone == null || phone.isBlank()) return null;

        // Strip everything except digits
        String digits = phone.replaceAll("[^0-9]", "");

        // 00212XXXXXXXXX → strip "00212"
        if (digits.startsWith("00212") && digits.length() >= 14) {
            digits = digits.substring(5);
        }
        // 212XXXXXXXXX → strip "212"
        else if (digits.startsWith("212") && digits.length() >= 12) {
            digits = digits.substring(3);
        }
        // 0XXXXXXXXX → strip leading "0"
        else if (digits.startsWith("0") && digits.length() >= 10) {
            digits = digits.substring(1);
        }

        return digits.isEmpty() ? null : digits;
    }
}
