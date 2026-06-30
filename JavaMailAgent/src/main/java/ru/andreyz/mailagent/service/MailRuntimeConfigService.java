package ru.andreyz.mailagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.ControlAuditEntry;
import ru.andreyz.mailagent.model.ControlSettingsDescriptor;
import ru.andreyz.mailagent.model.ControlSettingsResponse;
import ru.andreyz.mailagent.model.ControlSettingsStatusResponse;
import ru.andreyz.mailagent.model.MailAuthType;
import ru.andreyz.mailagent.model.ControlStatusResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MailRuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(MailRuntimeConfigService.class);

    public static final String SECRET_MASK = "*****";
    private static final List<String> PROTOCOL_OPTIONS = List.of("maildev", "imap", "ews");
    private static final List<String> AUTH_TYPE_OPTIONS = List.of("BASIC", "NTLM", "OAUTH2");
    private static final String PLUGIN_CODE = "mail";
    private static final String PLUGIN_NAME = "Mail Agent";

    private final AtomicReference<MailRuntimeConfig> current;
    private final MailControlAuditStore auditStore;
    private final MailPromptTemplateService promptTemplateService;

    private volatile LocalDateTime lastPollAt;
    private volatile String lastPollResult = "No polls yet";

    public MailRuntimeConfigService(MailConfig.MailProperties mailProperties,
                                    MailConfig.PathProperties pathProperties,
                                    MailConfig.ImapProperties imapProperties,
                                    MailConfig.EwsProperties ewsProperties,
                                    MailConfig.FolderProperties folderProperties,
                                    MailControlAuditStore auditStore,
                                    MailPromptTemplateService promptTemplateService) {
        this.auditStore = auditStore;
        this.promptTemplateService = promptTemplateService;
        String initialProtocol = normalizeProtocol(mailProperties.getProtocol());
        String classificationPrompt = promptTemplateService.loadClassificationPrompt();
        String linkingPrompt = promptTemplateService.loadLinkingPrompt();
        this.current = new AtomicReference<>(new MailRuntimeConfig(
                true,
                initialProtocol,
                blankToEmpty(mailProperties.getUsername()),
                blankToNull(mailProperties.getPassword()),
                blankToEmpty(ewsProperties.getUrl()),
                defaultAuthType(initialProtocol, ewsProperties.getAuthType()),
                blankToEmpty(imapProperties.getHost()),
                imapProperties.getPort(),
                imapProperties.isSsl(),
                Math.max(1, mailProperties.getPollIntervalSeconds()),
                sanitizeList(folderProperties.getInclude()),
                sanitizeList(folderProperties.getExclude()),
                mailProperties.isMarkAsReadEnabled(),
                true,
                true,
                pathProperties.getProcessed(),
                pathProperties.getDrafts(),
                classificationPrompt,
                linkingPrompt,
                1L,
                LocalDateTime.now()
        ));
    }

    public ControlSettingsResponse descriptor() {
        MailRuntimeConfig config = snapshot();
        Map<String, ControlSettingsDescriptor> settings = new LinkedHashMap<>();
        settings.put("enabled", descriptor(bool(config.enabled()), "boolean", "Enabled",
                "Enable or disable mail polling without stopping JVM process", true, false, true, null));
        settings.put("protocol", descriptor(config.protocol(), "select", "Protocol",
                "Mail protocol/client implementation", true, false, true, PROTOCOL_OPTIONS));
        settings.put("login", descriptor(config.login(), "string", "Login", null, true, false, false, null));
        settings.put("password", descriptor(hasPassword(config) ? SECRET_MASK : "", "secret",
                "Password / secret", null, true, true, false, null));
        settings.put("serverUrl", descriptor(config.serverUrl(), "string", "Server URL",
                "Used for Exchange EWS only. Example: https://outlook.domain.ru/EWS/Exchange.asmx", true, false, false, null));
        settings.put("authType", descriptor(config.authType().name(), "select", "Authentication Type",
                "Used for Exchange EWS authentication. BASIC and NTLM are supported. OAUTH2 is planned.", true, false, true, AUTH_TYPE_OPTIONS));
        settings.put("host", descriptor(config.host(), "string", "Host",
                "Used for IMAP only. Example: imap.company.com", true, false, false, null));
        settings.put("port", descriptor(String.valueOf(config.port()), "number", "Port",
                "Used for IMAP only. Typical value: 993 for IMAPS.", true, false, false, null));
        settings.put("ssl", descriptor(bool(config.ssl()), "boolean", "Use SSL / TLS",
                "Used for IMAP only. Enable for secure IMAPS connection.", true, false, false, null));
        settings.put("pollIntervalSeconds", descriptor(String.valueOf(config.pollIntervalSeconds()), "number",
                "Poll interval seconds", null, true, false, true, null));
        settings.put("foldersInclude", descriptor(joinLines(config.foldersInclude()), "list", "Folders include",
                "One folder per line", true, false, false, null));
        settings.put("foldersExclude", descriptor(joinLines(config.foldersExclude()), "list", "Folders exclude",
                "One folder per line. Example: Inbox/CI/CD", true, false, false, null));
        settings.put("markAsReadEnabled", descriptor(bool(config.markAsReadEnabled()), "boolean", "Mark emails as read",
                "Global switch for mailClient.markAsRead. When disabled, MailAgent only logs that the email could be marked as read.", true, false, false, null));
        settings.put("markNoiseAsRead", descriptor(bool(config.markNoiseAsRead()), "boolean", "Mark noise as read",
                null, true, false, false, null));
        settings.put("moveProcessedMail", descriptor(bool(config.moveProcessedMail()), "boolean", "Move processed mail",
                null, true, false, false, null));
        settings.put("processedFolder", descriptor(config.processedFolder(), "string", "Processed folder",
                null, true, false, false, null));
        settings.put("draftFolder", descriptor(config.draftFolder(), "string", "Draft folder",
                null, true, false, false, null));
        settings.put("classificationPrompt", descriptor(config.classificationPrompt(), "text", "Classification Prompt",
                "Runtime prompt template for email classification. Stored in MailAgent database and applied on next email.", true, false, true, null));
        settings.put("linkingPrompt", descriptor(config.linkingPrompt(), "text", "Mail Linking Prompt",
                "Runtime prompt template for request-to-task linking. Applied after REQUEST classification.", true, false, true, null));
        return new ControlSettingsResponse(PLUGIN_CODE, PLUGIN_NAME, config.version(), settings);
    }

    public synchronized ControlSettingsStatusResponse apply(Map<String, String> incomingSettings) {
        Map<String, String> incoming = incomingSettings != null ? incomingSettings : Map.of();
        MailRuntimeConfig previous = snapshot();
        LocalDateTime appliedAt = LocalDateTime.now();

        boolean enabled = parseBoolean(incoming, "enabled", previous.enabled());
        String protocol = parseProtocol(incoming.get("protocol"), previous.protocol());
        String login = parseString(incoming, "login", previous.login());
        String password = parseSecret(incoming, "password", previous.password());
        String serverUrl = parseString(incoming, "serverUrl", previous.serverUrl());
        MailAuthType authType = parseAuthType(incoming.get("authType"), defaultAuthType(protocol, previous.authType().name()));
        String host = parseString(incoming, "host", previous.host());
        int port = parseInt(incoming, "port", previous.port(), false);
        boolean ssl = parseBoolean(incoming, "ssl", previous.ssl());
        int pollIntervalSeconds = parseInt(incoming, "pollIntervalSeconds", previous.pollIntervalSeconds(), true);
        List<String> foldersInclude = parseLines(incoming, "foldersInclude", previous.foldersInclude(), false);
        List<String> foldersExclude = parseLines(incoming, "foldersExclude", previous.foldersExclude(), false);
        boolean markAsReadEnabled = parseBoolean(incoming, "markAsReadEnabled", previous.markAsReadEnabled());
        boolean markNoiseAsRead = parseBoolean(incoming, "markNoiseAsRead", previous.markNoiseAsRead());
        boolean moveProcessedMail = parseBoolean(incoming, "moveProcessedMail", previous.moveProcessedMail());
        String processedFolder = parseString(incoming, "processedFolder", previous.processedFolder());
        String draftFolder = parseString(incoming, "draftFolder", previous.draftFolder());
        String classificationPrompt = parseText(incoming, "classificationPrompt", previous.classificationPrompt(), true);
        String linkingPrompt = parseText(incoming, "linkingPrompt", previous.linkingPrompt(), true);

        MailRuntimeConfig updated = new MailRuntimeConfig(
                enabled,
                protocol,
                login,
                password,
                serverUrl,
                authType,
                host,
                port,
                ssl,
                pollIntervalSeconds,
                foldersInclude,
                foldersExclude,
                markAsReadEnabled,
                markNoiseAsRead,
                moveProcessedMail,
                processedFolder,
                draftFolder,
                classificationPrompt,
                linkingPrompt,
                previous.version() + 1,
                appliedAt
        );
        if (!Objects.equals(previous.classificationPrompt(), classificationPrompt)) {
            promptTemplateService.saveClassificationPrompt(classificationPrompt);
        }
        if (!Objects.equals(previous.linkingPrompt(), linkingPrompt)) {
            promptTemplateService.saveLinkingPrompt(linkingPrompt);
        }
        current.set(updated);

        List<String> changedKeys = changedKeys(previous, updated);
        Map<String, String> applied = sanitizedApplied(updated);
        Map<String, String> requestAudit = sanitizedRequest(incoming);
        Map<String, String> ignored = incoming.containsKey("password")
                ? Map.of("password", "secret value accepted but not returned")
                : Map.of();
        String message = "MailAgent settings applied. New values will be used by subsequent operations.";

        log.info("Received settings update: keys={}", changedKeys);
        for (String key : changedKeys) {
            if ("password".equals(key)) {
                log.info("Applied mail secret: password=<masked>");
            } else {
                log.info("Applied mail setting: {}={}", key, applied.get(key));
            }
        }
        if (!changedKeys.isEmpty()) {
            log.info("Applied runtime config version={}", updated.version());
        }

        auditStore.save(updated.version(), changedKeys, requestAudit, applied, "APPLIED", message);
        return new ControlSettingsStatusResponse(PLUGIN_CODE, "APPLIED", appliedAt, applied, ignored, message);
    }

    public ControlStatusResponse status() {
        MailRuntimeConfig config = snapshot();
        return new ControlStatusResponse(
                PLUGIN_CODE,
                "UP",
                config.enabled(),
                config.enabled(),
                config.protocol(),
                lastPollAt,
                lastPollResult,
                config.version()
        );
    }

    public List<ControlAuditEntry> audit() {
        return auditStore.findRecent();
    }

    public MailRuntimeConfig snapshot() {
        return current.get();
    }

    public void registerPollResult(String result) {
        lastPollAt = LocalDateTime.now();
        lastPollResult = result;
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

    private List<String> changedKeys(MailRuntimeConfig previous, MailRuntimeConfig updated) {
        List<String> changed = new ArrayList<>();
        maybeAdd(changed, "enabled", previous.enabled(), updated.enabled());
        maybeAdd(changed, "protocol", previous.protocol(), updated.protocol());
        maybeAdd(changed, "login", previous.login(), updated.login());
        maybeAdd(changed, "password", previous.password(), updated.password());
        maybeAdd(changed, "serverUrl", previous.serverUrl(), updated.serverUrl());
        maybeAdd(changed, "authType", previous.authType(), updated.authType());
        maybeAdd(changed, "host", previous.host(), updated.host());
        maybeAdd(changed, "port", previous.port(), updated.port());
        maybeAdd(changed, "ssl", previous.ssl(), updated.ssl());
        maybeAdd(changed, "pollIntervalSeconds", previous.pollIntervalSeconds(), updated.pollIntervalSeconds());
        maybeAdd(changed, "foldersInclude", previous.foldersInclude(), updated.foldersInclude());
        maybeAdd(changed, "foldersExclude", previous.foldersExclude(), updated.foldersExclude());
        maybeAdd(changed, "markAsReadEnabled", previous.markAsReadEnabled(), updated.markAsReadEnabled());
        maybeAdd(changed, "markNoiseAsRead", previous.markNoiseAsRead(), updated.markNoiseAsRead());
        maybeAdd(changed, "moveProcessedMail", previous.moveProcessedMail(), updated.moveProcessedMail());
        maybeAdd(changed, "processedFolder", previous.processedFolder(), updated.processedFolder());
        maybeAdd(changed, "draftFolder", previous.draftFolder(), updated.draftFolder());
        maybeAdd(changed, "classificationPrompt", previous.classificationPrompt(), updated.classificationPrompt());
        maybeAdd(changed, "linkingPrompt", previous.linkingPrompt(), updated.linkingPrompt());
        return changed;
    }

    private void maybeAdd(List<String> changed, String key, Object left, Object right) {
        if (!Objects.equals(left, right)) {
            changed.add(key);
        }
    }

    private Map<String, String> sanitizedApplied(MailRuntimeConfig config) {
        Map<String, String> applied = new LinkedHashMap<>();
        applied.put("enabled", bool(config.enabled()));
        applied.put("protocol", config.protocol());
        applied.put("login", config.login());
        applied.put("serverUrl", config.serverUrl());
        applied.put("authType", config.authType().name());
        applied.put("host", config.host());
        applied.put("port", String.valueOf(config.port()));
        applied.put("ssl", bool(config.ssl()));
        applied.put("pollIntervalSeconds", String.valueOf(config.pollIntervalSeconds()));
        applied.put("foldersInclude", joinLines(config.foldersInclude()));
        applied.put("foldersExclude", joinLines(config.foldersExclude()));
        applied.put("markAsReadEnabled", bool(config.markAsReadEnabled()));
        applied.put("markNoiseAsRead", bool(config.markNoiseAsRead()));
        applied.put("moveProcessedMail", bool(config.moveProcessedMail()));
        applied.put("processedFolder", config.processedFolder());
        applied.put("draftFolder", config.draftFolder());
        applied.put("classificationPrompt", config.classificationPrompt());
        applied.put("linkingPrompt", config.linkingPrompt());
        return applied;
    }

    private Map<String, String> sanitizedRequest(Map<String, String> incoming) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            sanitized.put(entry.getKey(), "password".equals(entry.getKey()) ? SECRET_MASK : entry.getValue());
        }
        return sanitized;
    }

    private boolean hasPassword(MailRuntimeConfig config) {
        return config.password() != null && !config.password().isBlank();
    }

    private MailAuthType parseAuthType(String value, MailAuthType fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return MailAuthType.fromValue(value, fallback);
        } catch (IllegalArgumentException e) {
            log.error("", e);
            throw new IllegalArgumentException("Unsupported authType: " + value);
        }
    }

    private MailAuthType defaultAuthType(String protocol, String configuredValue) {
        MailAuthType fallback = "ews".equals(protocol) ? MailAuthType.NTLM : MailAuthType.BASIC;
        return MailAuthType.fromValue(configuredValue, fallback);
    }

    private String parseProtocol(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = normalizeProtocol(value);
        if (!PROTOCOL_OPTIONS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported protocol: " + value);
        }
        return normalized;
    }

    private boolean parseBoolean(Map<String, String> incoming, String key, boolean fallback) {
        String value = incoming.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean for " + key + ": " + value);
    }

    private int parseInt(Map<String, String> incoming, String key, int fallback, boolean positive) {
        String value = incoming.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (positive && parsed <= 0) {
                throw new IllegalArgumentException("Invalid number for " + key + ": " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.error("", e);
            throw new IllegalArgumentException("Invalid number for " + key + ": " + value);
        }
    }

    private String parseString(Map<String, String> incoming, String key, String fallback) {
        return incoming.containsKey(key) ? blankToEmpty(incoming.get(key)) : fallback;
    }

    private String parseSecret(Map<String, String> incoming, String key, String fallback) {
        if (!incoming.containsKey(key)) {
            return fallback;
        }
        String value = incoming.get(key);
        if (value == null || value.isBlank() || SECRET_MASK.equals(value)) {
            return fallback;
        }
        return value;
    }

    private String parseText(Map<String, String> incoming, String key, String fallback, boolean required) {
        if (!incoming.containsKey(key)) {
            return fallback;
        }
        String value = incoming.get(key);
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException("Setting " + key + " must not be empty");
            }
            return "";
        }
        String normalized = value.replace("\r\n", "\n");
        if (required && normalized.isBlank()) {
            throw new IllegalArgumentException("Setting " + key + " must not be empty");
        }
        return normalized;
    }

    private List<String> parseLines(Map<String, String> incoming, String key, List<String> fallback, boolean required) {
        if (!incoming.containsKey(key)) {
            return fallback;
        }
        List<String> lines = sanitizeList(splitLines(incoming.get(key)));
        if (required && lines.isEmpty()) {
            throw new IllegalArgumentException("Setting " + key + " must not be empty");
        }
        return lines;
    }

    private List<String> splitLines(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return raw.lines().map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private List<String> sanitizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String normalizeProtocol(String value) {
        return value == null || value.isBlank()
                ? "maildev"
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String joinLines(List<String> values) {
        return String.join("\n", values);
    }

    private String bool(boolean value) {
        return value ? "true" : "false";
    }
}
