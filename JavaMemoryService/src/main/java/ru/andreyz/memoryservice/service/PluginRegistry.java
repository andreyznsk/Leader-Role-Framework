package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class PluginRegistry {

    private final List<PluginDefinition> plugins = List.of(
            new PluginDefinition("mail", "Mail Plugin", "MAIL", true),
            new PluginDefinition("chat", "Chat Plugin", "CHAT", false)
    );

    public List<PluginDefinition> all() {
        return plugins;
    }

    public PluginDefinition getRequired(String code) {
        return plugins.stream()
                .filter(plugin -> plugin.code().equals(normalize(code)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown plugin: " + code));
    }

    public record PluginDefinition(String code, String name, String type, boolean editable) {
    }

    private String normalize(String code) {
        return code == null ? "" : code.toLowerCase(Locale.ROOT);
    }
}
