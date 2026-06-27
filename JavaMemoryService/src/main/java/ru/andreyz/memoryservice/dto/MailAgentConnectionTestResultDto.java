package ru.andreyz.memoryservice.dto;

public record MailAgentConnectionTestResultDto(
        boolean success,
        String status,
        String protocol,
        Boolean endpointReachable,
        Boolean httpsOk,
        Boolean ewsDetected,
        Boolean authenticationOk,
        String exchangeVersion,
        String authType,
        String mailbox,
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
