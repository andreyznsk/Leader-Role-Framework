package ru.andreyz.memoryservice.dto;

import java.util.List;
import java.util.Map;

public record ControlPluginSettingsResponse(
        String pluginCode,
        String pluginName,
        long version,
        Map<String, ControlPluginSettingFieldDto> settings
) {
    public record ControlPluginSettingFieldDto(
            String value,
            String type,
            String label,
            String description,
            boolean editable,
            boolean secret,
            boolean required,
            List<String> options
    ) {
    }
}
