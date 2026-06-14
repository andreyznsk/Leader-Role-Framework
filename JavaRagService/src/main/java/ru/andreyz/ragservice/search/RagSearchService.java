package ru.andreyz.ragservice.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.ragservice.client.OllamaClient;
import ru.andreyz.ragservice.client.OpenSearchClient;

import java.util.List;

@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    private final OllamaClient ollama;
    private final OpenSearchClient openSearch;

    public RagSearchService(OllamaClient ollama, OpenSearchClient openSearch) {
        this.ollama = ollama;
        this.openSearch = openSearch;
    }

    public List<SearchResult> search(String query, int topK) {
        log.info("[SEARCH] → query='{}' topK={}", query, topK);
        float[] queryVector = ollama.embed(query);
        List<SearchResult> results = openSearch.knnSearch(queryVector, topK);
        double topScore = results.isEmpty() ? 0.0 : results.get(0).score();
        log.info("[SEARCH] ✓ query='{}' results={} topScore={}", query, results.size(), String.format("%.4f", topScore));
        return results;
    }
}
