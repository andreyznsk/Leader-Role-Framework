package ru.andreyz.memoryservice.dto;

public record JiraAssignableUserDto(
        String accountId,
        String displayName,
        String email
) {
}
