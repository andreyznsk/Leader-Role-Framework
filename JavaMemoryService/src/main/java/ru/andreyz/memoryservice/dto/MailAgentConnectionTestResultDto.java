package ru.andreyz.memoryservice.dto;

public record MailAgentConnectionTestResultDto(
        boolean success,
        String status,
        String protocol,
        String exchangeVersion,
        String authType,
        Integer foldersFound,
        Boolean foldersScanLimited,
        Boolean inboxFound,
        String message,
        String errorType,
        String details,
        String endpoint,
        String target
) {
}
