package ru.andreyz.memoryservice.dto;

import java.util.List;

public record UpdateMailPluginSettingsRequest(
        Boolean enabled,
        MailPluginConfigRequest config
) {
    public record MailPluginConfigRequest(
            String protocol,
            String login,
            String password,
            String secretRef,
            String serverUrl,
            String host,
            Integer port,
            Boolean ssl,
            Integer pollIntervalSeconds,
            List<String> foldersInclude,
            List<String> foldersExclude,
            Boolean markNoiseAsRead,
            Boolean moveProcessed,
            String processedFolder,
            String draftFolder
    ) {
    }
}
