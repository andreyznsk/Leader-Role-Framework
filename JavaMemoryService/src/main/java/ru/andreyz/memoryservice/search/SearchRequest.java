package ru.andreyz.memoryservice.search;

import java.util.List;

public record SearchRequest(
        String query,
        List<SearchLayer> layers,
        SearchMode mode,
        Integer limit
) {}
