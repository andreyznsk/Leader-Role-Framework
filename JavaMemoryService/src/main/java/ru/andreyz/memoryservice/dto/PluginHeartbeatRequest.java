package ru.andreyz.memoryservice.dto;

public record PluginHeartbeatRequest(
        String status,
        String message
) {
}
