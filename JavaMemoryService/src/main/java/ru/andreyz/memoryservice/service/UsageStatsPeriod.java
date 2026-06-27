package ru.andreyz.memoryservice.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public enum UsageStatsPeriod {
    TODAY("today"),
    SEVEN_DAYS("7d"),
    THIRTY_DAYS("30d"),
    ALL("all");

    private final String value;

    UsageStatsPeriod(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public Instant startInstant() {
        return switch (this) {
            case TODAY -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
            case SEVEN_DAYS -> Instant.now().minusSeconds(7L * 24 * 60 * 60);
            case THIRTY_DAYS -> Instant.now().minusSeconds(30L * 24 * 60 * 60);
            case ALL -> null;
        };
    }

    public static UsageStatsPeriod from(String raw) {
        if (raw == null || raw.isBlank()) {
            return SEVEN_DAYS;
        }
        for (UsageStatsPeriod period : values()) {
            if (period.value.equalsIgnoreCase(raw)) {
                return period;
            }
        }
        return SEVEN_DAYS;
    }
}
