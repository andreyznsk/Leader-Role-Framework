package ru.andreyz.memoryservice.search;

public record LayerInfo(
        String name,
        String title,
        boolean enabled,
        boolean available
) {}
