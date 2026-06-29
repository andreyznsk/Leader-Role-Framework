package ru.andreyz.memoryservice.search;

import java.util.List;

public record ParsedSearchQuery(
        String originalQuery,
        String normalizedQuery,
        List<String> keywords,
        String postgresTsQuery
) {}
