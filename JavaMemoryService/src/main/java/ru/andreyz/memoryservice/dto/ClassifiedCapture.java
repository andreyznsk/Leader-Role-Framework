package ru.andreyz.memoryservice.dto;

public record ClassifiedCapture(
        Long captureId,
        String file,
        String type,
        String title,
        String body,
        String tags,
        String priority
) {}
