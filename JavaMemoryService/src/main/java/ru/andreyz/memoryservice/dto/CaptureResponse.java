package ru.andreyz.memoryservice.dto;

public record CaptureResponse(String file, boolean saved, Long captureId, String savedAt) {}
