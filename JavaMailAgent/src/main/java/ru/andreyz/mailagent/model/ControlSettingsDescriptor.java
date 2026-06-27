package ru.andreyz.mailagent.model;

import java.util.List;

public record ControlSettingsDescriptor(
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
