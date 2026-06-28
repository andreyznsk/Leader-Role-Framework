package ru.andreyz.memoryservice.search;

import java.time.Instant;

public record SearchResultItem(
        SearchLayer layer,
        String title,
        String snippet,
        String url,
        String entityId,
        String entityType,
        double score,
        Instant updatedAt
) {}
