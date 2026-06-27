package ru.andreyz.ragservice.control;

import java.time.LocalDateTime;

public record RagRuntimeConfig(
        boolean enabled,
        boolean schedulerEnabled,
        int scanIntervalSeconds,
        String ragInboxPath,
        String embeddingModel,
        int topK,
        String opensearchUrl,
        boolean validationEnabled,
        long version,
        LocalDateTime appliedAt
) {
}
