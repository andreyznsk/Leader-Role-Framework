package ru.andreyz.mailagent.service;

import java.time.LocalDateTime;
import java.util.List;

public record MailRuntimeConfig(
        boolean enabled,
        String protocol,
        String login,
        String password,
        String serverUrl,
        String host,
        int port,
        boolean ssl,
        int pollIntervalSeconds,
        List<String> foldersInclude,
        List<String> foldersExclude,
        boolean markNoiseAsRead,
        boolean moveProcessedMail,
        String processedFolder,
        String draftFolder,
        long version,
        LocalDateTime appliedAt
) {
}
