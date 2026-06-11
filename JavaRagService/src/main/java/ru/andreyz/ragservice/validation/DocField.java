package ru.andreyz.ragservice.validation;

public enum DocField {
    TYPE("type"),
    SERVICE("service"),
    UPDATED("updated"),
    REVIEW_BY("review_by"),
    SOURCE("source");

    private final String key;

    DocField(String key) { this.key = key; }

    public String key() { return key; }
}
