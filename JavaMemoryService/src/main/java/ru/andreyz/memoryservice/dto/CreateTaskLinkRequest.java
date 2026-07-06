package ru.andreyz.memoryservice.dto;

public record CreateTaskLinkRequest(Long toTaskId, String linkType) {}
