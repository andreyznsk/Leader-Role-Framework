package ru.andreyz.memoryservice.dto;

public record CreateTaskLabelRequest(
        String name,
        String color
) {}
