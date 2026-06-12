package ru.andreyz.mailagent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.PendingTaskRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class MemoryServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceClient.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final boolean enabled;

    public MemoryServiceClient(ObjectMapper objectMapper, MailConfig.MemoryServiceProperties props) {
        this.objectMapper = objectMapper;
        this.baseUrl = props.getUrl();
        this.enabled = props.isEnabled();
    }

    public void createPendingTask(PendingTaskRequest request) {
        if (!enabled) {
            log.debug("memory-service disabled, skipping");
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/pending"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                log.info("Pending task created in memory-service");
            } else {
                log.warn("memory-service returned {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            // Не останавливаем обработку почты если memory-service недоступен
            log.warn("Failed to reach memory-service: {}", e.getMessage());
        }
    }

    public void createCapture(String text, String source) {
        if (!enabled) {
            log.debug("memory-service disabled, skipping capture");
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "text", text,
                "source", source
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/capture"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("Capture saved to memory-service");
            } else {
                log.warn("memory-service /api/capture returned {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("Failed to save capture to memory-service: {}", e.getMessage());
        }
    }

    public boolean isHealthy() {
        if (!enabled) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/actuator/health"))
                .GET()
                .timeout(Duration.ofSeconds(2))
                .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
