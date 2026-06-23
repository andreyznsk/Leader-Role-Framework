package ru.andreyz.ragservice.control;

import java.time.LocalDateTime;

public record ControlStatusResponse(
        String pluginCode,
        String status,
        boolean enabled,
        boolean schedulerEnabled,
        boolean validationEnabled,
        String ragInboxPath,
        String embeddingModel,
        int topK,
        LocalDateTime lastScanAt,
        String lastScanResult,
        long configVersion
) {
}
