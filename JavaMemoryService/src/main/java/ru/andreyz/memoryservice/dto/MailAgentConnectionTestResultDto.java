package ru.andreyz.memoryservice.dto;

public record MailAgentConnectionTestResultDto(
        boolean success,
        String message,
        String target
) {
}
