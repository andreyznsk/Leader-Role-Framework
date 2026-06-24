package ru.andreyz.mailagent.model;

public record MailConnectionTestResult(
        boolean success,
        MailConnectionStatus status,
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
                exchangeVersion,
                authType,
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
                null,
                authType,
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
