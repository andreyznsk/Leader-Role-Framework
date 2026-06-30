package ru.andreyz.mailagent.integration;

import java.util.List;

public record MemorySearchRequest(
        String query,
        List<String> layers,
        String mode,
        Integer limit
) {
}
