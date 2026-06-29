package ru.andreyz.memoryservice.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SearchSupport {

    private SearchSupport() {
    }

    public static boolean containsAnyKeyword(String value, List<String> keywords) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(normalized::contains);
    }

    public static List<String> matchedFields(List<String> keywords, Map<String, String> fields) {
        if (keywords.isEmpty()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        fields.forEach((field, value) -> {
            if (containsAnyKeyword(value, keywords)) {
                matched.add(field);
            }
        });
        return matched;
    }

    public static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
