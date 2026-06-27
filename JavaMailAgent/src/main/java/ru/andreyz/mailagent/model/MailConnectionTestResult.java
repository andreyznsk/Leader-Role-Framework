package ru.andreyz.mailagent.model;

public record MailConnectionTestResult(
        boolean success,
        MailConnectionStatus status,
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

    public static MailConnectionTestResult connected(String protocol,
                                                     String exchangeVersion,
                                                     String authType,
                                                     Integer foldersFound,
                                                     boolean foldersScanLimited,
                                                     boolean inboxFound,
                                                     String message,
                                                     String endpoint) {
        return new MailConnectionTestResult(
                true,
                MailConnectionStatus.CONNECTED,
                protocol,
                true,
                true,
                true,
                true,
                exchangeVersion,
                authType,
                null,
                foldersFound,
                foldersScanLimited,
                inboxFound,
                message,
                null,
                null,
                endpoint,
                endpoint
        );
    }

    public static MailConnectionTestResult failed(String protocol,
                                                  String authType,
                                                  MailConnectionErrorType errorType,
                                                  String message,
                                                  String details,
                                                  String endpoint) {
        return new MailConnectionTestResult(
                false,
                MailConnectionStatus.FAILED,
                protocol,
                endpoint != null && !endpoint.isBlank(),
                endpoint != null && endpoint.startsWith("https://"),
                null,
                false,
                null,
                authType,
                null,
                null,
                null,
                null,
                message,
                errorType != null ? errorType.name() : null,
                details,
                endpoint,
                endpoint
        );
    }
}
