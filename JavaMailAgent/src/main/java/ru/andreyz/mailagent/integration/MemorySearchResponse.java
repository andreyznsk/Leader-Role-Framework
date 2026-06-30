package ru.andreyz.mailagent.integration;

import java.util.List;

public record MemorySearchResponse(
        String query,
        String mode,
        List<String> layers,
        String summary,
        List<MemorySearchResultItem> results
) {
}
