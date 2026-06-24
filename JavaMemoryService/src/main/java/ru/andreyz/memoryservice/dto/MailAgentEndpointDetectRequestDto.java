package ru.andreyz.memoryservice.dto;

public record MailAgentEndpointDetectRequestDto(
        String protocol,
        String ewsUrl
) {
}
