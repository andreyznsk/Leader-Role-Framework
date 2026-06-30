package ru.andreyz.ragservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.andreyz.ragservice.control.RagRuntimeConfigService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OllamaClient {


    private final String ollamaUrl;
    private final RagRuntimeConfigService runtimeConfigService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaClient(
            @Value("${ollama.url}") String ollamaUrl,
            ObjectMapper mapper,
            RagRuntimeConfigService runtimeConfigService) {
        this.ollamaUrl = ollamaUrl;
        this.mapper = mapper;
        this.runtimeConfigService = runtimeConfigService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public float[] embed(String text) {
        try {
            String model = runtimeConfigService.snapshot().embeddingModel();
            String body = mapper.writeValueAsString(new EmbedRequest(model, text));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama error " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode embeddingNode = root.get("embedding");
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            return vector;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Ollama request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get embedding from Ollama", e);
        }
    }

    private record EmbedRequest(String model, String prompt) {}
}
