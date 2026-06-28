package ru.andreyz.memoryservice.search;

import java.util.List;

public interface SearchProvider {
    SearchLayer layer();

    /**
     * Returns results with scores normalized to [0.0, 1.0].
     */
    List<SearchResultItem> search(String query, int limit);
}
