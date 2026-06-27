package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class KnowledgeTools {

    private final RestClient restClient;
    private final String ragBaseUrl;

    public KnowledgeTools(RestClient.Builder restClientBuilder,
                          @Value("${app.rag.base-url:http://localhost:8081}") String ragBaseUrl) {
        this.restClient = restClientBuilder.build();
        this.ragBaseUrl = ragBaseUrl;
    }

    @Tool(description = "Search the knowledge base using semantic search. Returns relevant documents from the RAG index. Use for questions about architecture, processes, incidents, decisions, and any stored knowledge.")
    public List<Map<String, Object>> searchKnowledge(
            @ToolParam(description = "Search query in natural language") String query,
            @ToolParam(description = "Maximum number of results to return (default 5)", required = false) Integer topK) {
        int limit = topK != null ? topK : 5;
        List<Map<String, Object>> results = restClient.post()
                .uri(ragBaseUrl + "/api/search")
                .body(Map.of("query", query, "top_k", limit))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return results != null ? results : List.of();
    }
}
