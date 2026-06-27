package ru.andreyz.memoryservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeService {

    private final RestClient restClient;
    private final String ragBaseUrl;

    public KnowledgeService(RestClient.Builder restClientBuilder,
                            @Value("${app.rag.base-url:http://localhost:8081}") String ragBaseUrl) {
        this.restClient = restClientBuilder.build();
        this.ragBaseUrl = ragBaseUrl;
    }

    public List<KnowledgeDocumentSummary> list(String type) {
        String uri = type == null || type.isBlank()
                ? ragBaseUrl + "/api/rag/documents"
                : ragBaseUrl + "/api/rag/documents?type=" + type.trim().toUpperCase();
        List<KnowledgeDocumentSummary> documents = restClient.get()
                .uri(uri)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return documents != null ? documents : List.of();
    }

    public KnowledgeDocumentDetails get(Long id) {
        return restClient.get()
                .uri(ragBaseUrl + "/api/rag/documents/{id}", id)
                .retrieve()
                .body(KnowledgeDocumentDetails.class);
    }

    public KnowledgeDocumentDetails update(Long id, String content) {
        return restClient.put()
                .uri(ragBaseUrl + "/api/rag/documents/{id}", id)
                .body(Map.of("content", content))
                .retrieve()
                .body(KnowledgeDocumentDetails.class);
    }

    public ReindexResult reindex(Long id) {
        return restClient.post()
                .uri(ragBaseUrl + "/api/rag/documents/{id}/reindex", id)
                .retrieve()
                .body(ReindexResult.class);
    }

    public DeleteResult delete(Long id) {
        return restClient.delete()
                .uri(ragBaseUrl + "/api/rag/documents/{id}", id)
                .retrieve()
                .body(DeleteResult.class);
    }

    public record KnowledgeDocumentSummary(
            Long id,
            String filePath,
            String fileName,
            String docType,
            String status,
            String errorMessage,
            String indexedAt,
            String updated,
            String title,
            String sender,
            String subject,
            String receivedAt
    ) {}

    public record KnowledgeDocumentDetails(KnowledgeDocumentSummary summary, String content) {}

    public record ReindexResult(int chunksAdded, String status, String filePath) {}

    public record DeleteResult(Long id, String filePath, boolean fileDeleted, String status) {}
}
