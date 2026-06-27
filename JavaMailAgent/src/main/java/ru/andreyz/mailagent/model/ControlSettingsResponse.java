package ru.andreyz.mailagent.model;

import java.util.Map;

public record ControlSettingsResponse(
        String pluginCode,
        String pluginName,
        long version,
        Map<String, ControlSettingsDescriptor> settings
) {
}
