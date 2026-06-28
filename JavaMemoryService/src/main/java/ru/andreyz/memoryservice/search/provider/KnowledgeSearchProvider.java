package ru.andreyz.memoryservice.search.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.andreyz.memoryservice.search.SearchLayer;
import ru.andreyz.memoryservice.search.SearchProvider;
import ru.andreyz.memoryservice.search.SearchResultItem;

import java.util.List;
import java.util.Map;

/**
 * Semantic search via JavaRagService /api/search through the Memory-owned proxy.
 * Never accesses the 'rag' schema directly.
 */
@Component
public class KnowledgeSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchProvider.class);

    private final RestClient restClient;
    private final String ragBaseUrl;

    public KnowledgeSearchProvider(RestClient.Builder restClientBuilder,
                                   @Value("${app.rag.base-url:http://localhost:8081}") String ragBaseUrl) {
        this.restClient = restClientBuilder.build();
        this.ragBaseUrl = ragBaseUrl;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.KNOWLEDGE;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        try {
            List<Map<String, Object>> rawResults = restClient.post()
                    .uri(ragBaseUrl + "/api/search")
                    .body(Map.of("query", query, "top_k", limit))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (rawResults == null) return List.of();

            return rawResults.stream()
                    .map(this::toSearchResultItem)
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            log.warn("Knowledge search unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    private SearchResultItem toSearchResultItem(Map<String, Object> raw) {
        String title = stringOf(raw, "title", "file_path", "source");
        String snippet = stringOf(raw, "content", "chunk", "text");
        if (snippet != null && snippet.length() > 300) snippet = snippet.substring(0, 300) + "...";
        double score = scoreOf(raw);
        String entityId = stringOf(raw, "id", "document_id");

        return new SearchResultItem(
                SearchLayer.KNOWLEDGE,
                title != null ? title : "Knowledge document",
                snippet,
                null,
                entityId,
                "KNOWLEDGE",
                score,
                null
        );
    }

    private String stringOf(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    private double scoreOf(Map<String, Object> map) {
        Object val = map.get("score");
        if (val instanceof Number n) {
            double d = n.doubleValue();
            return Math.max(0.0, Math.min(1.0, d));
        }
        return 0.5;
    }
}
