package ru.andreyz.memoryservice.search;

import java.util.List;

public record SearchResponse(
        String query,
        SearchMode mode,
        List<SearchLayer> layers,
        String summary,
        List<SearchResultItem> results
) {}
