package ru.andreyz.ragservice.search;

import org.springframework.stereotype.Service;
import ru.andreyz.ragservice.client.OllamaClient;
import ru.andreyz.ragservice.client.OpenSearchClient;

import java.util.List;

@Service
public class RagSearchService {

    private final OllamaClient ollama;
    private final OpenSearchClient openSearch;

    public RagSearchService(OllamaClient ollama, OpenSearchClient openSearch) {
        this.ollama = ollama;
        this.openSearch = openSearch;
    }

    public List<SearchResult> search(String query, int topK) {
        float[] queryVector = ollama.embed(query);
        return openSearch.knnSearch(queryVector, topK);
    }
}
