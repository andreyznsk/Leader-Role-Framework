package ru.andreyz.memoryservice.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import ru.andreyz.memoryservice.domain.UsageEventType;
import ru.andreyz.memoryservice.dto.UsageEventCommand;
import ru.andreyz.memoryservice.service.UsageEventService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeSearchController {

    private final RestClient restClient;
    private final UsageEventService usageEventService;
    private final String ragBaseUrl;

    public KnowledgeSearchController(RestClient.Builder restClientBuilder,
                                     UsageEventService usageEventService,
                                     @Value("${app.rag.base-url:http://localhost:8081}") String ragBaseUrl) {
        this.restClient = restClientBuilder.build();
        this.usageEventService = usageEventService;
        this.ragBaseUrl = ragBaseUrl;
    }

    @PostMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> search(@RequestBody KnowledgeSearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        int topK = request.topK() != null ? request.topK() : 5;
        long started = System.currentTimeMillis();
        try {
            List<Map<String, Object>> results = restClient.post()
                    .uri(ragBaseUrl + "/api/search")
                    .body(Map.of("query", request.query(), "top_k", topK))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            List<Map<String, Object>> safeResults = results != null ? results : List.of();
            long durationMs = System.currentTimeMillis() - started;
            recordSearchEvents(request.query(), topK, safeResults.size(), durationMs, "SUCCESS");
            return ResponseEntity.ok(safeResults);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - started;
            usageEventService.record(new UsageEventCommand(
                    UsageEventType.KNOWLEDGE_SEARCH,
                    "memory-service",
                    "FAILED",
                    null,
                    null,
                    null,
                    durationMs,
                    0,
                    Map.of("query", request.query(), "topK", topK, "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
            ));
            throw e;
        }
    }

    private void recordSearchEvents(String query, int topK, int resultsCount, long durationMs, String status) {
        Map<String, Object> metadata = Map.of("query", query, "topK", topK, "resultsCount", resultsCount);
        usageEventService.record(new UsageEventCommand(
                UsageEventType.KNOWLEDGE_SEARCH,
                "memory-service",
                status,
                null,
                null,
                null,
                durationMs,
                null,
                metadata
        ));
        usageEventService.record(new UsageEventCommand(
                UsageEventType.RAG_SEARCH,
                "rag-service",
                status,
                null,
                null,
                null,
                durationMs,
                null,
                metadata
        ));
        if (resultsCount > 0) {
            usageEventService.record(new UsageEventCommand(
                    UsageEventType.RAG_RESULT_USED,
                    "rag-service",
                    status,
                    null,
                    null,
                    null,
                    durationMs,
                    null,
                    metadata
            ));
        }
    }

    public record KnowledgeSearchRequest(String query, Integer topK) {}
}
