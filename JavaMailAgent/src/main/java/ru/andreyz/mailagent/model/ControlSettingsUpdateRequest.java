package ru.andreyz.mailagent.model;

import java.util.Map;

public record ControlSettingsUpdateRequest(
        Map<String, String> settings
) {
}
