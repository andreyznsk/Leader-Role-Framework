package ru.andreyz.memoryservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Locale;

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
        String normalizedType = normalizeType(type);
        boolean ragAliasFilter = "RAG".equals(normalizedType);
        String uri = type == null || type.isBlank() || ragAliasFilter
                ? ragBaseUrl + "/api/rag/documents"
                : ragBaseUrl + "/api/rag/documents?type=" + normalizedType;
        List<KnowledgeDocumentSummary> documents = restClient.get()
                .uri(uri)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        List<KnowledgeDocumentSummary> baseDocuments = documents != null ? documents : List.of();
        return baseDocuments.stream()
                .map(this::normalizeSummary)
                .filter(document -> document != null)
                .filter(document -> normalizedType == null || normalizedType.equals(document.docType()))
                .toList();
    }

    public KnowledgeDocumentDetails get(Long id) {
        KnowledgeDocumentDetails details = restClient.get()
                .uri(ragBaseUrl + "/api/rag/documents/{id}", id)
                .retrieve()
                .body(KnowledgeDocumentDetails.class);
        return normalizeDetails(details);
    }

    public KnowledgeDocumentDetails update(Long id, String content) {
        KnowledgeDocumentDetails details = restClient.put()
                .uri(ragBaseUrl + "/api/rag/documents/{id}", id)
                .body(Map.of("content", content))
                .retrieve()
                .body(KnowledgeDocumentDetails.class);
        return normalizeDetails(details);
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

    private KnowledgeDocumentSummary normalizeSummary(KnowledgeDocumentSummary summary) {
        if (summary == null) {
            return null;
        }
        return new KnowledgeDocumentSummary(
                summary.id(),
                summary.filePath(),
                summary.fileName(),
                normalizeType(summary.docType()),
                summary.status(),
                summary.errorMessage(),
                summary.indexedAt(),
                summary.updated(),
                summary.title(),
                summary.sender(),
                summary.subject(),
                summary.receivedAt()
        );
    }

    private KnowledgeDocumentDetails normalizeDetails(KnowledgeDocumentDetails details) {
        if (details == null) {
            return null;
        }
        return new KnowledgeDocumentDetails(normalizeSummary(details.summary()), details.content());
    }

    private String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "NOTICE", "KNOWLEDGE", "RAG" -> "RAG";
            default -> raw.trim().toUpperCase(Locale.ROOT);
        };
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
