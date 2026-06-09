package ru.andreyz.ragservice.search;

public record SearchResult(
        String text,
        String source,
        double score,
        int chunkIndex
) {}
