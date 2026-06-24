package ru.andreyz.memoryservice.dto;

public record MailAgentEndpointDetectResultDto(
        boolean success,
        String status,
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
}
