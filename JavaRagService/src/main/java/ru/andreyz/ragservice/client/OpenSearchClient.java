package ru.andreyz.ragservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.andreyz.ragservice.control.RagRuntimeConfig;
import ru.andreyz.ragservice.control.RagRuntimeConfigService;
import ru.andreyz.ragservice.search.SearchResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OpenSearchClient {


    private final String index;
    private final RagRuntimeConfigService runtimeConfigService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private volatile String ensuredBaseUrl;

    public OpenSearchClient(
            @Value("${opensearch.index}") String index,
            ObjectMapper mapper,
            RagRuntimeConfigService runtimeConfigService) {
        this.index = index;
        this.mapper = mapper;
        this.runtimeConfigService = runtimeConfigService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PostConstruct
    public void ensureIndexExists() {
        ensureConfiguredIndexExists();
    }

    public void ensureConfiguredIndexExists() {
        ensureIndexExists(runtimeConfigService.snapshot().opensearchUrl());
    }

    private synchronized void ensureIndexExists(String baseUrl) {
        if (Objects.equals(ensuredBaseUrl, baseUrl)) {
            return;
        }
        try {
            String indexUrl = baseUrl + "/" + index;
            HttpRequest head = HttpRequest.newBuilder()
                    .uri(URI.create(indexUrl))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<Void> check = httpClient.send(head, HttpResponse.BodyHandlers.discarding());
            if (check.statusCode() == 200) {
                log.info("OpenSearch index '{}' already exists", index);
                ensuredBaseUrl = baseUrl;
                return;
            }

            String mapping = mapper.writeValueAsString(Map.of(
                    "settings", Map.of("index", Map.of("knn", true)),
                    "mappings", Map.of("properties", Map.of(
                            "text", Map.of("type", "text"),
                            "vector", Map.of("type", "knn_vector", "dimension", 1024),
                            "source", Map.of("type", "keyword"),
                            "doc_id", Map.of("type", "keyword"),
                            "chunk_index", Map.of("type", "integer"),
                            "indexed_at", Map.of("type", "date")
                    ))
            ));

            HttpRequest create = HttpRequest.newBuilder()
                    .uri(URI.create(indexUrl))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapping))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = httpClient.send(create, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                log.info("Created OpenSearch index '{}'", index);
                ensuredBaseUrl = baseUrl;
            } else if (resp.body().contains("resource_already_exists_exception")) {
                log.info("OpenSearch index '{}' already exists (race condition, OK)", index);
                ensuredBaseUrl = baseUrl;
            } else {
                log.warn("Unexpected response creating index: {} {}", resp.statusCode(), resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Failed to ensure OpenSearch index exists: {}", e.getMessage());
            log.error("", e);
        }
    }

    public void indexDocument(String chunkId, String text, float[] vector, String source, String docId, int chunkIndex) {
        try {
            RagRuntimeConfig runtime = runtimeConfigService.snapshot();
            ensureIndexExists(runtime.opensearchUrl());
            ObjectNode doc = mapper.createObjectNode();
            doc.put("text", text);
            doc.put("source", source);
            doc.put("doc_id", docId);
            doc.put("chunk_index", chunkIndex);
            doc.put("indexed_at", Instant.now().toString());
            var arr = doc.putArray("vector");
            for (float v : vector) arr.add(v);

            String url = runtime.opensearchUrl() + "/" + index + "/_doc/" + chunkId + "?refresh=wait_for";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(doc)))
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new RuntimeException("Index failed: " + resp.statusCode() + " " + resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while indexing", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to index document chunk: " + chunkId, e);
        }
    }

    public void deleteBySource(String source) {
        try {
            RagRuntimeConfig runtime = runtimeConfigService.snapshot();
            ensureIndexExists(runtime.opensearchUrl());
            String body = mapper.writeValueAsString(Map.of(
                    "query", Map.of("term", Map.of("source", source))
            ));
            String url = runtime.opensearchUrl() + "/" + index + "/_delete_by_query?refresh=true";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Delete by source returned {}: {}", resp.statusCode(), resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Failed to delete chunks for source {}: {}", source, e.getMessage());
            log.error("", e);
        }
    }

    public List<SearchResult> knnSearch(float[] queryVector, int topK) {
        try {
            RagRuntimeConfig runtime = runtimeConfigService.snapshot();
            ensureIndexExists(runtime.opensearchUrl());
            ObjectNode vectorQuery = mapper.createObjectNode();
            var vectorArr = vectorQuery.putArray("vector");
            for (float v : queryVector) vectorArr.add(v);
            vectorQuery.put("k", topK);

            ObjectNode query = mapper.createObjectNode();
            query.set("knn", mapper.createObjectNode().set("vector", vectorQuery));

            ObjectNode body = mapper.createObjectNode();
            body.put("size", topK);
            body.set("query", query);

            String url = runtime.opensearchUrl() + "/" + index + "/_search";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Search failed: " + resp.statusCode() + " " + resp.body());
            }

            List<SearchResult> results = new ArrayList<>();
            JsonNode root = mapper.readTree(resp.body());
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode src = hit.path("_source");
                results.add(new SearchResult(
                        src.path("text").asText(),
                        src.path("source").asText(),
                        hit.path("_score").asDouble(),
                        src.path("chunk_index").asInt()
                ));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Search interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("kNN search failed", e);
        }
    }
}
