package ru.andreyz.memoryservice.dto;

public record IntakeRejectRequest(
        String reason,
        String reviewedBy
) {}
