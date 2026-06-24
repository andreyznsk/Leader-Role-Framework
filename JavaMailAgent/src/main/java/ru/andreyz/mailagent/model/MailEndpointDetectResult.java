package ru.andreyz.mailagent.model;

public record MailEndpointDetectResult(
        boolean success,
        MailEndpointDetectStatus status,
        String protocol,
        boolean endpointReachable,
        boolean httpsOk,
        boolean ewsDetected,
        Integer httpStatus,
        String recommendedAuthType,
        String message,
        String errorType,
        String details,
        String endpoint
) {

    public static MailEndpointDetectResult detected(String protocol,
                                                    boolean endpointReachable,
                                                    boolean httpsOk,
                                                    boolean ewsDetected,
                                                    Integer httpStatus,
                                                    String recommendedAuthType,
                                                    String message,
                                                    String endpoint) {
        return new MailEndpointDetectResult(
                true,
                MailEndpointDetectStatus.DETECTED,
                protocol,
                endpointReachable,
                httpsOk,
                ewsDetected,
                httpStatus,
                recommendedAuthType,
                message,
                null,
                null,
                endpoint
        );
    }

    public static MailEndpointDetectResult failed(String protocol,
                                                  boolean endpointReachable,
                                                  boolean httpsOk,
                                                  boolean ewsDetected,
                                                  Integer httpStatus,
                                                  MailConnectionErrorType errorType,
                                                  String message,
                                                  String details,
                                                  String endpoint) {
        return new MailEndpointDetectResult(
                false,
                MailEndpointDetectStatus.FAILED,
                protocol,
                endpointReachable,
                httpsOk,
                ewsDetected,
                httpStatus,
                MailAuthType.NTLM.name(),
                message,
                errorType != null ? errorType.name() : null,
                details,
                endpoint
        );
    }
}
