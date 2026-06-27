package ru.andreyz.memoryservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SystemSettingsDto(
        String application,
        List<String> activeProfiles,
        String agentProvider,
        String javaVersion,
        String status,
        Instant lastConfigurationUpdateAt,
        List<PluginSummaryDto> registeredPlugins,
        Map<String, String> routingDefaults
) {
}
