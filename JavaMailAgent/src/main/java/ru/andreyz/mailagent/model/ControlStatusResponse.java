package ru.andreyz.mailagent.model;

import java.time.LocalDateTime;

public record ControlStatusResponse(
        String pluginCode,
        String status,
        boolean enabled,
        boolean polling,
        String protocol,
        LocalDateTime lastPollAt,
        String lastPollResult,
        long configVersion
) {
}
