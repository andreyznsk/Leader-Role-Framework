package ru.andreyz.ragservice.validation;

public enum DocType {
    SERVICE_CARD,
    PROCESS,
    GLOSSARY,
    ADR,
    RAG;

    public static DocType fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Document type is blank");
        }
        return switch (raw.trim().toUpperCase().replace("-", "_")) {
            case "NOTICE", "KNOWLEDGE", "RAG" -> RAG;
            default -> valueOf(raw.trim().toUpperCase().replace("-", "_"));
        };
    }
}
