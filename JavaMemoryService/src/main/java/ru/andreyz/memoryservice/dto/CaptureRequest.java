package ru.andreyz.memoryservice.dto;

public record CaptureRequest(String text, String source, String sourceId) {
    public CaptureRequest(String text, String source) {
        this(text, source, null);
    }
}
