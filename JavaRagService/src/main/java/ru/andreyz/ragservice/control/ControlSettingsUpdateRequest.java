package ru.andreyz.ragservice.control;

import java.util.Map;

public record ControlSettingsUpdateRequest(
        Map<String, String> settings
) {
}
