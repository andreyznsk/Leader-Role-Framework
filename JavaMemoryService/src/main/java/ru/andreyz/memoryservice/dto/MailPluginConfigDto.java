package ru.andreyz.memoryservice.dto;

import java.util.List;

public record MailPluginConfigDto(
        String protocol,
        String login,
        String passwordMasked,
        boolean passwordConfigured,
        String secretRef,
        String serverUrl,
        String host,
        Integer port,
        boolean ssl,
        Integer pollIntervalSeconds,
        List<String> foldersInclude,
        List<String> foldersExclude,
        boolean markNoiseAsRead,
        boolean moveProcessed,
        String processedFolder,
        String draftFolder
) {
}
