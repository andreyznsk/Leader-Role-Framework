package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.dto.ControlPluginAuditDto;
import ru.andreyz.memoryservice.dto.ControlPluginDto;
import ru.andreyz.memoryservice.dto.ControlPluginRemoteAuditEntry;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsResponse;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsUpdateRequest;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsUpdateResponse;

import java.time.Instant;
import java.util.List;

@Service
public class ControlPluginService {

    private static final Logger log = LoggerFactory.getLogger(ControlPluginService.class);

    private final ControlPluginRegistry registry;
    private final ControlPluginStore store;
    private final ControlPluginClient client;
    private final ObjectMapper objectMapper;

    public ControlPluginService(ControlPluginRegistry registry,
                                ControlPluginStore store,
                                ControlPluginClient client,
                                ObjectMapper objectMapper) {
        this.registry = registry;
        this.store = store;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void registerDefaults() {
        for (ControlPluginRegistry.ControlPluginDefinition plugin : registry.all()) {
            store.upsertPlugin(plugin.code(), plugin.name(), plugin.baseUrl(), plugin.enabled());
        }
    }

    public List<ControlPluginDto> listPlugins() {
        return store.findAllPlugins().stream()
                .map(this::withLiveHealthStatus)
                .toList();
    }

    public ControlPluginSettingsResponse getPluginSettings(String code) {
        ControlPluginDto plugin = requirePlugin(code);
        try {
            ControlPluginSettingsResponse response = client.fetchSettings(plugin.baseUrl());
            store.saveSnapshot(code, response, writeJson(response));
            updateHealthStatus(code, plugin.baseUrl(), Instant.now());
            store.saveAudit(code, "FETCH_SETTINGS", "{}", writeJson(response), "UP", "Descriptor synced");
            return response;
        } catch (RuntimeException e) {
            updateHealthStatus(code, plugin.baseUrl(), Instant.now());
            store.saveAudit(code, "FETCH_SETTINGS", "{}", jsonMessage(e), "FAILED", rootMessage(e));
            throw e;
        }
    }

    public ControlPluginSettingsUpdateResponse updatePluginSettings(String code, ControlPluginSettingsUpdateRequest request) {
        ControlPluginDto plugin = requirePlugin(code);
        try {
            ControlPluginSettingsUpdateResponse response = client.updateSettings(plugin.baseUrl(), request);
            ControlPluginSettingsResponse refreshed = client.fetchSettings(plugin.baseUrl());
            store.saveSnapshot(code, refreshed, writeJson(refreshed));
            updateHealthStatus(code, plugin.baseUrl(), Instant.now());
            store.saveAudit(code, "APPLY_SETTINGS", writeJson(request), writeJson(response), response.status(), response.message());
            return response;
        } catch (RuntimeException e) {
            updateHealthStatus(code, plugin.baseUrl(), Instant.now());
            store.saveAudit(code, "APPLY_SETTINGS", writeJsonSafe(request), jsonMessage(e), "FAILED", rootMessage(e));
            throw e;
        }
    }

    public List<ControlPluginAuditDto> getPluginAudit(String code) {
        ControlPluginDto plugin = requirePlugin(code);
        try {
            List<ControlPluginAuditDto> audit = client.fetchAudit(plugin.baseUrl()).stream()
                    .map(item -> new ControlPluginAuditDto(
                            code,
                            "REMOTE_AUDIT",
                            item.status(),
                            item.message(),
                            "{}",
                            "{}",
                            item.appliedAt() != null ? item.appliedAt().atZone(java.time.ZoneId.systemDefault()).toInstant() : null
                    ))
                    .toList();
            updateHealthStatus(code, plugin.baseUrl(), Instant.now());
            return audit;
        } catch (RuntimeException e) {
            log.error("", e);
            updateHealthStatus(code, plugin.baseUrl(), Instant.now());
            return store.findAudit(code);
        }
    }

    public ControlPluginSettingsResponse getSnapshot(String code) {
        return store.findSnapshotJson(code)
                .map(this::readSettings)
                .orElse(null);
    }

    private ControlPluginDto requirePlugin(String code) {
        registry.getRequired(code);
        return store.findPlugin(code).orElseThrow(() -> new IllegalArgumentException("Unknown control plugin: " + code));
    }

    private ControlPluginDto withLiveHealthStatus(ControlPluginDto plugin) {
        String healthStatus = updateHealthStatus(plugin.code(), plugin.baseUrl(), plugin.lastSyncAt());
        return new ControlPluginDto(
                plugin.code(),
                plugin.name(),
                plugin.baseUrl(),
                plugin.enabled(),
                healthStatus,
                plugin.lastSyncAt()
        );
    }

    private String updateHealthStatus(String code, String baseUrl, Instant lastSyncAt) {
        String healthStatus = client.fetchHealthStatus(baseUrl);
        store.updatePluginStatus(code, healthStatus, lastSyncAt);
        return healthStatus;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize control plugin payload", e);
        }
    }

    private String writeJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("", e);
            return "{\"error\":\"serialization failed\"}";
        }
    }

    private ControlPluginSettingsResponse readSettings(String value) {
        try {
            return objectMapper.readValue(value, ControlPluginSettingsResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse plugin snapshot", e);
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        if (current.getMessage() != null && !current.getMessage().isBlank()) {
            return current.getMessage();
        }
        return current.getClass().getSimpleName();
    }

    private String jsonMessage(RuntimeException e) {
        return "{\"error\":\"" + rootMessage(e).replace("\"", "'") + "\"}";
    }
}
