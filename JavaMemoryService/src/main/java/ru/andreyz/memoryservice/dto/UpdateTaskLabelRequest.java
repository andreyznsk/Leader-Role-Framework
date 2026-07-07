package ru.andreyz.memoryservice.dto;

public record UpdateTaskLabelRequest(
        String name,
        String color,
        Boolean archived
) {}
