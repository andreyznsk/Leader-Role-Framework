package ru.andreyz.memoryservice.dto;

public record ClassifiedCapture(
        Long captureId,
        String type,
        String title,
        String body,
        String priority
) {}
