package ru.andreyz.memoryservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.dto.MailPluginConfigDto;
import ru.andreyz.memoryservice.dto.MailAgentConnectionTestRequestDto;
import ru.andreyz.memoryservice.dto.PluginHeartbeatRequest;
import ru.andreyz.memoryservice.dto.PluginSettingsDto;
import ru.andreyz.memoryservice.dto.PluginSummaryDto;
import ru.andreyz.memoryservice.dto.SystemSettingsDto;
import ru.andreyz.memoryservice.dto.UpdateMailPluginSettingsRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PluginSettingsService {

    private static final String SECRET_MASK = "*****";

    private final PluginRegistry pluginRegistry;
    private final PluginSettingsStore store;
    private final MailAgentControlClient mailAgentControlClient;
    private final Environment environment;
    private final String applicationName;
    private final String agentProvider;

    public PluginSettingsService(PluginRegistry pluginRegistry,
                                 PluginSettingsStore store,
                                 MailAgentControlClient mailAgentControlClient,
                                 Environment environment,
                                 @Value("${spring.application.name:JavaMemoryService}") String applicationName,
                                 @Value("${agent.provider:claude}") String agentProvider) {
        this.pluginRegistry = pluginRegistry;
        this.store = store;
        this.mailAgentControlClient = mailAgentControlClient;
        this.environment = environment;
        this.applicationName = applicationName;
        this.agentProvider = agentProvider;
    }

    public SystemSettingsDto getSystemSettings() {
        List<PluginSummaryDto> plugins = getPlugins();
        Instant lastUpdated = plugins.stream()
                .map(PluginSummaryDto::updatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        return new SystemSettingsDto(
                applicationName,
                activeProfiles(),
                agentProvider,
                System.getProperty("java.version"),
                "UP",
                lastUpdated,
                plugins,
                defaultRouting()
        );
    }

    public List<PluginSummaryDto> getPlugins() {
        Map<String, PluginSettingsStore.StoredPluginSettings> settingsByCode = store.findAllSettings().stream()
                .collect(java.util.stream.Collectors.toMap(PluginSettingsStore.StoredPluginSettings::pluginCode, value -> value));
        Map<String, PluginSettingsStore.StoredPluginHeartbeat> heartbeatsByCode = store.findAllHeartbeats().stream()
                .collect(java.util.stream.Collectors.toMap(PluginSettingsStore.StoredPluginHeartbeat::pluginCode, value -> value));

        List<PluginSummaryDto> result = new ArrayList<>();
        for (PluginRegistry.PluginDefinition plugin : pluginRegistry.all()) {
            var settings = settingsByCode.get(plugin.code());
            var heartbeat = heartbeatsByCode.get(plugin.code());
            result.add(new PluginSummaryDto(
                    plugin.code(),
                    plugin.name(),
                    plugin.type(),
                    settings != null && settings.enabled(),
                    resolveStatus(settings, heartbeat),
                    heartbeat != null ? heartbeat.lastHeartbeatAt() : null,
                    settings != null ? settings.updatedAt() : null
            ));
        }
        return result;
    }

    public PluginSettingsDto getPluginSettings(String code) {
        PluginRegistry.PluginDefinition plugin = pluginRegistry.getRequired(code);
        PluginSettingsStore.StoredPluginSettings settings = store.findSettings(plugin.code()).orElse(null);
        PluginSettingsStore.StoredPluginHeartbeat heartbeat = store.findHeartbeat(plugin.code()).orElse(null);

        MailPluginConfigDto config = "mail".equals(plugin.code())
                ? toMailConfig(settings)
                : new MailPluginConfigDto(
                null, null, null, false, null, null, null, null, false,
                null, List.of(), List.of(), false, false, null, null
        );

        return new PluginSettingsDto(
                plugin.code(),
                plugin.name(),
                plugin.type(),
                settings != null && settings.enabled(),
                resolveStatus(settings, heartbeat),
                heartbeat != null ? heartbeat.lastHeartbeatAt() : null,
                settings != null ? settings.updatedAt() : null,
                config
        );
    }

    public PluginSettingsDto updateMailSettings(UpdateMailPluginSettingsRequest request) {
        PluginRegistry.PluginDefinition plugin = pluginRegistry.getRequired("mail");
        PluginSettingsStore.StoredPluginSettings existing = store.findSettings(plugin.code()).orElse(null);

        Map<String, Object> merged = existing != null ? new LinkedHashMap<>(existing.config()) : defaultMailConfigMap();
        UpdateMailPluginSettingsRequest.MailPluginConfigRequest incoming = request != null ? request.config() : null;

        putIfPresent(merged, "protocol", incoming != null ? incoming.protocol() : null);
        putIfPresent(merged, "login", incoming != null ? incoming.login() : null);
        putIfPresent(merged, "serverUrl", incoming != null ? incoming.serverUrl() : null);
        putIfPresent(merged, "host", incoming != null ? incoming.host() : null);
        putIfPresent(merged, "port", incoming != null ? incoming.port() : null);
        putIfPresent(merged, "ssl", incoming != null ? incoming.ssl() : null);
        putIfPresent(merged, "pollIntervalSeconds", incoming != null ? incoming.pollIntervalSeconds() : null);
        putIfPresent(merged, "foldersInclude", sanitizeList(incoming != null ? incoming.foldersInclude() : null));
        putIfPresent(merged, "foldersExclude", sanitizeList(incoming != null ? incoming.foldersExclude() : null));
        putIfPresent(merged, "markNoiseAsRead", incoming != null ? incoming.markNoiseAsRead() : null);
        putIfPresent(merged, "moveProcessed", incoming != null ? incoming.moveProcessed() : null);
        putIfPresent(merged, "processedFolder", incoming != null ? incoming.processedFolder() : null);
        putIfPresent(merged, "draftFolder", incoming != null ? incoming.draftFolder() : null);

        String existingSecret = existing != null ? asString(existing.config().get("password")) : null;
        String incomingPassword = incoming != null ? blankToNull(incoming.password()) : null;
        if (incomingPassword != null) {
            merged.put("password", incomingPassword);
        } else if (existingSecret == null) {
            merged.remove("password");
        }

        String secretRef = incoming != null && incoming.secretRef() != null
                ? blankToNull(incoming.secretRef())
                : existing != null ? existing.secretRef() : null;

        boolean enabled = request != null && request.enabled() != null
                ? request.enabled()
                : existing != null && existing.enabled();
        store.saveSettings(plugin.code(), plugin.type(), enabled, merged, secretRef);
        mailAgentControlClient.notifyEnabledChanged(enabled);
        return getPluginSettings(plugin.code());
    }

    public ru.andreyz.memoryservice.dto.MailAgentConnectionTestResultDto testMailAgentConnection(MailAgentConnectionTestRequestDto request) {
        return mailAgentControlClient.testConnection(request);
    }

    public MailPluginConfigDto getMailAgentConfig() {
        return toMailConfig(store.findSettings("mail").orElse(null));
    }

    public PluginSummaryDto recordHeartbeat(String code, PluginHeartbeatRequest request) {
        PluginRegistry.PluginDefinition plugin = pluginRegistry.getRequired(code);
        PluginSettingsStore.StoredPluginHeartbeat heartbeat = store.saveHeartbeat(
                plugin.code(),
                request != null ? request.status() : null,
                request != null ? request.message() : null
        );
        PluginSettingsStore.StoredPluginSettings settings = store.findSettings(plugin.code()).orElse(null);
        return new PluginSummaryDto(
                plugin.code(),
                plugin.name(),
                plugin.type(),
                settings != null && settings.enabled(),
                heartbeat.status(),
                heartbeat.lastHeartbeatAt(),
                settings != null ? settings.updatedAt() : null
        );
    }

    private MailPluginConfigDto toMailConfig(PluginSettingsStore.StoredPluginSettings settings) {
        Map<String, Object> config = settings != null ? settings.config() : defaultMailConfigMap();
        String password = blankToNull(asString(config.get("password")));
        return new MailPluginConfigDto(
                defaultString(asString(config.get("protocol")), "maildev"),
                asString(config.get("login")),
                password != null ? SECRET_MASK : null,
                password != null,
                settings != null ? settings.secretRef() : null,
                asString(config.get("serverUrl")),
                asString(config.get("host")),
                asInteger(config.get("port")),
                asBoolean(config.get("ssl"), true),
                asInteger(config.get("pollIntervalSeconds"), 60),
                asStringList(config.get("foldersInclude")),
                asStringList(config.get("foldersExclude")),
                asBoolean(config.get("markNoiseAsRead"), false),
                asBoolean(config.get("moveProcessed"), false),
                asString(config.get("processedFolder")),
                asString(config.get("draftFolder"))
        );
    }

    private String resolveStatus(PluginSettingsStore.StoredPluginSettings settings,
                                 PluginSettingsStore.StoredPluginHeartbeat heartbeat) {
        if (heartbeat != null && heartbeat.status() != null && !heartbeat.status().isBlank()) {
            return heartbeat.status();
        }
        if (settings != null && isConfigured(settings.config(), settings.secretRef())) {
            return "UNKNOWN";
        }
        return "NOT_CONFIGURED";
    }

    private boolean isConfigured(Map<String, Object> config, String secretRef) {
        if (secretRef != null && !secretRef.isBlank()) {
            return true;
        }
        if (config == null || config.isEmpty()) {
            return false;
        }
        return config.values().stream().anyMatch(value -> value != null && !(value instanceof List<?> list && list.isEmpty()));
    }

    private List<String> activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length > 0) {
            return List.of(profiles);
        }
        return List.of(environment.getDefaultProfiles());
    }

    private Map<String, String> defaultRouting() {
        return Map.of(
                "REQUEST", "create pending task",
                "DRAFT", "create draft / save proposal",
                "NOISE", "ignore / archive",
                "CAPTURE", "create raw capture",
                "KNOWLEDGE", "send to RAG inbox",
                "RISK", "create risk",
                "NOTE", "create note",
                "QUESTION", "create question"
        );
    }

    private Map<String, Object> defaultMailConfigMap() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("protocol", "maildev");
        defaults.put("ssl", true);
        defaults.put("pollIntervalSeconds", 60);
        defaults.put("foldersInclude", List.of());
        defaults.put("foldersExclude", List.of());
        defaults.put("markNoiseAsRead", false);
        defaults.put("moveProcessed", false);
        defaults.put("processedFolder", "processed");
        defaults.put("draftFolder", "drafts");
        return defaults;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String stringValue) {
            target.put(key, stringValue.isBlank() ? null : stringValue);
            return;
        }
        target.put(key, value);
    }

    private List<String> sanitizeList(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Integer asInteger(Object value) {
        return asInteger(value, null);
    }

    private Integer asInteger(Object value, Integer fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean asBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? null : String.valueOf(item))
                    .filter(item -> item != null && !item.isBlank())
                    .toList();
        }
        return List.of(String.valueOf(value));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
