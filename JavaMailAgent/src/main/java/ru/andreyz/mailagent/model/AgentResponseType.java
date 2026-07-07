package ru.andreyz.mailagent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum AgentResponseType {
    DRAFT,
    REQUEST,
    NOISE,
    CAPTURE,
    RAG,
    NOTE;

    @JsonCreator
    public static AgentResponseType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Agent response type is blank");
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "NOTICE", "KNOWLEDGE", "RAG" -> RAG;
            default -> valueOf(raw.trim().toUpperCase(Locale.ROOT));
        };
    }

    @JsonValue
    public String jsonValue() {
        return name();
    }
}
