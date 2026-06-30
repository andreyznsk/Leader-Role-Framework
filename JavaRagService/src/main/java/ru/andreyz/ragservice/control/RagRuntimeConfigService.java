package ru.andreyz.ragservice.control;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RagRuntimeConfigService {


    private static final String PLUGIN_CODE = "rag";
    private static final String PLUGIN_NAME = "RAG Service";

    private final AtomicReference<RagRuntimeConfig> current;
    private final RagControlAuditStore auditStore;

    private volatile LocalDateTime lastScanAt;
    private volatile String lastScanResult = "No scans yet";

    public RagRuntimeConfigService(@Value("${rag.control.enabled:true}") boolean enabled,
                                   @Value("${rag.control.scheduler-enabled:true}") boolean schedulerEnabled,
                                   @Value("${rag.scheduler.interval-ms:60000}") int scanIntervalMs,
                                   @Value("${rag.inbox.path}") String ragInboxPath,
                                   @Value("${ollama.model}") String embeddingModel,
                                   @Value("${rag.control.top-k:10}") int topK,
                                   @Value("${opensearch.url}") String opensearchUrl,
                                   @Value("${rag.control.validation-enabled:true}") boolean validationEnabled,
                                   RagControlAuditStore auditStore) {
        this.auditStore = auditStore;
        this.current = new AtomicReference<>(new RagRuntimeConfig(
                enabled,
                schedulerEnabled,
                Math.max(1, scanIntervalMs / 1000),
                blankToEmpty(ragInboxPath),
                blankToEmpty(embeddingModel),
                Math.max(1, topK),
                blankToEmpty(opensearchUrl),
                validationEnabled,
                1L,
                LocalDateTime.now()
        ));
    }

    public ControlSettingsResponse descriptor() {
        RagRuntimeConfig config = snapshot();
        Map<String, ControlSettingsDescriptor> settings = new LinkedHashMap<>();
        settings.put("enabled", descriptor(bool(config.enabled()), "boolean", "Enabled",
                "Enable or disable indexing without stopping the JVM", true, false, true, null));
        settings.put("schedulerEnabled", descriptor(bool(config.schedulerEnabled()), "boolean", "Scheduler enabled",
                "Run background inbox scan on schedule", true, false, true, null));
        settings.put("scanIntervalSeconds", descriptor(String.valueOf(config.scanIntervalSeconds()), "number", "Scan interval seconds",
                "Delay between background scan attempts", true, false, true, null));
        settings.put("ragInboxPath", descriptor(config.ragInboxPath(), "string", "RAG inbox path",
                "Directory scanned by the background indexer", true, false, true, null));
        settings.put("embeddingModel", descriptor(config.embeddingModel(), "string", "Embedding model",
                "Ollama embedding model used for indexing and search", true, false, true, null));
        settings.put("topK", descriptor(String.valueOf(config.topK()), "number", "Default top K",
                "Default number of semantic search results", true, false, true, null));
        settings.put("opensearchUrl", descriptor(config.opensearchUrl(), "string", "OpenSearch URL",
                "Base URL for vector index operations", true, false, true, null));
        settings.put("validationEnabled", descriptor(bool(config.validationEnabled()), "boolean", "Validation enabled",
                "Validate markdown structure before indexing", true, false, true, null));
        return new ControlSettingsResponse(PLUGIN_CODE, PLUGIN_NAME, config.version(), settings);
    }

    public synchronized ControlSettingsStatusResponse apply(Map<String, String> incomingSettings) {
        Map<String, String> incoming = incomingSettings != null ? incomingSettings : Map.of();
        RagRuntimeConfig previous = snapshot();
        LocalDateTime appliedAt = LocalDateTime.now();

        RagRuntimeConfig updated = new RagRuntimeConfig(
                parseBoolean(incoming, "enabled", previous.enabled()),
                parseBoolean(incoming, "schedulerEnabled", previous.schedulerEnabled()),
                parseInt(incoming, "scanIntervalSeconds", previous.scanIntervalSeconds()),
                parseString(incoming, "ragInboxPath", previous.ragInboxPath()),
                parseString(incoming, "embeddingModel", previous.embeddingModel()),
                parseInt(incoming, "topK", previous.topK()),
                parseString(incoming, "opensearchUrl", previous.opensearchUrl()),
                parseBoolean(incoming, "validationEnabled", previous.validationEnabled()),
                previous.version() + 1,
                appliedAt
        );
        current.set(updated);

        List<String> changedKeys = changedKeys(previous, updated);
        Map<String, String> applied = applied(updated);
        String message = "RAG settings applied. Runtime config updated without JVM restart.";

        log.info("Received RAG settings update: keys={}", changedKeys);
        for (String key : changedKeys) {
            log.info("Applied RAG setting: {}={}", key, applied.get(key));
        }

        auditStore.save(updated.version(), changedKeys, new LinkedHashMap<>(incoming), applied, "APPLIED", message);
        return new ControlSettingsStatusResponse(PLUGIN_CODE, "APPLIED", appliedAt, applied, Map.of(), message);
    }

    public ControlStatusResponse status() {
        RagRuntimeConfig config = snapshot();
        return new ControlStatusResponse(
                PLUGIN_CODE,
                config.enabled() ? "UP" : "DISABLED",
                config.enabled(),
                config.schedulerEnabled(),
                config.validationEnabled(),
                config.ragInboxPath(),
                config.embeddingModel(),
                config.topK(),
                lastScanAt,
                lastScanResult,
                config.version()
        );
    }

    public List<ControlAuditEntry> audit() {
        return auditStore.findRecent();
    }

    public RagRuntimeConfig snapshot() {
        return current.get();
    }

    public boolean shouldScan(LocalDateTime now, LocalDateTime lastFinishedAt) {
        RagRuntimeConfig config = snapshot();
        if (!config.enabled() || !config.schedulerEnabled()) {
            return false;
        }
        if (lastFinishedAt == null) {
            return true;
        }
        return !lastFinishedAt.plusSeconds(config.scanIntervalSeconds()).isAfter(now);
    }

    public void registerScanResult(String result) {
        lastScanAt = LocalDateTime.now();
        lastScanResult = result;
    }

    private ControlSettingsDescriptor descriptor(String value,
                                                 String type,
                                                 String label,
                                                 String description,
                                                 boolean editable,
                                                 boolean secret,
                                                 boolean required,
                                                 List<String> options) {
        return new ControlSettingsDescriptor(value, type, label, description, editable, secret, required, options);
    }

    private List<String> changedKeys(RagRuntimeConfig previous, RagRuntimeConfig updated) {
        List<String> changed = new ArrayList<>();
        maybeAdd(changed, "enabled", previous.enabled(), updated.enabled());
        maybeAdd(changed, "schedulerEnabled", previous.schedulerEnabled(), updated.schedulerEnabled());
        maybeAdd(changed, "scanIntervalSeconds", previous.scanIntervalSeconds(), updated.scanIntervalSeconds());
        maybeAdd(changed, "ragInboxPath", previous.ragInboxPath(), updated.ragInboxPath());
        maybeAdd(changed, "embeddingModel", previous.embeddingModel(), updated.embeddingModel());
        maybeAdd(changed, "topK", previous.topK(), updated.topK());
        maybeAdd(changed, "opensearchUrl", previous.opensearchUrl(), updated.opensearchUrl());
        maybeAdd(changed, "validationEnabled", previous.validationEnabled(), updated.validationEnabled());
        return changed;
    }

    private void maybeAdd(List<String> changed, String key, Object left, Object right) {
        if (!Objects.equals(left, right)) {
            changed.add(key);
        }
    }

    private Map<String, String> applied(RagRuntimeConfig config) {
        Map<String, String> applied = new LinkedHashMap<>();
        applied.put("enabled", bool(config.enabled()));
        applied.put("schedulerEnabled", bool(config.schedulerEnabled()));
        applied.put("scanIntervalSeconds", String.valueOf(config.scanIntervalSeconds()));
        applied.put("ragInboxPath", config.ragInboxPath());
        applied.put("embeddingModel", config.embeddingModel());
        applied.put("topK", String.valueOf(config.topK()));
        applied.put("opensearchUrl", config.opensearchUrl());
        applied.put("validationEnabled", bool(config.validationEnabled()));
        return applied;
    }

    private boolean parseBoolean(Map<String, String> incoming, String key, boolean fallback) {
        String value = incoming.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private int parseInt(Map<String, String> incoming, String key, int fallback) {
        String value = incoming.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            log.error("", e);
            return fallback;
        }
    }

    private String parseString(Map<String, String> incoming, String key, String fallback) {
        String value = incoming.get(key);
        return value == null ? fallback : value.trim();
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String bool(boolean value) {
        return String.valueOf(value);
    }
}
