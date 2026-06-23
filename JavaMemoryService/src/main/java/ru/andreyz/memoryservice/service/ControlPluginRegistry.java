package ru.andreyz.memoryservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class ControlPluginRegistry {

    private final List<ControlPluginDefinition> plugins;

    public ControlPluginRegistry(@Value("${app.plugins.mail.base-url:http://localhost:8080}") String mailBaseUrl,
                                 @Value("${app.rag.base-url:http://localhost:8081}") String ragBaseUrl) {
        this.plugins = List.of(
                new ControlPluginDefinition("mail", "Mail Agent", mailBaseUrl, true),
                new ControlPluginDefinition("rag", "RAG Service", ragBaseUrl, true)
        );
    }

    public List<ControlPluginDefinition> all() {
        return plugins;
    }

    public ControlPluginDefinition getRequired(String code) {
        return plugins.stream()
                .filter(plugin -> plugin.code().equals(normalize(code)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown control plugin: " + code));
    }

    private String normalize(String code) {
        return code == null ? "" : code.toLowerCase(Locale.ROOT);
    }

    public record ControlPluginDefinition(
            String code,
            String name,
            String baseUrl,
            boolean enabled
    ) {
    }
}
