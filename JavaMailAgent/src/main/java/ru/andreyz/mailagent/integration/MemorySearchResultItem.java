package ru.andreyz.mailagent.integration;

import java.time.Instant;
import java.util.List;

public record MemorySearchResultItem(
        String layer,
        String title,
        String snippet,
        String url,
        String entityId,
        String entityType,
        double score,
        Instant updatedAt,
        List<String> matchedFields
) {
}
