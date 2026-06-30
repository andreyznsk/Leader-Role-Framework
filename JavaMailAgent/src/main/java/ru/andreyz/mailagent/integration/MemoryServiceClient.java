package ru.andreyz.mailagent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.integration.MemorySearchRequest;
import ru.andreyz.mailagent.integration.MemorySearchResponse;
import ru.andreyz.mailagent.model.PendingTaskRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MemoryServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final List<Duration> RETRY_DELAYS = List.of(
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10)
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final boolean enabled;
    private final RetrySleeper retrySleeper;

    @Autowired
    public MemoryServiceClient(ObjectMapper objectMapper, MailConfig.MemoryServiceProperties props) {
        this(objectMapper, props, HttpClient.newHttpClient(), duration -> Thread.sleep(duration.toMillis()));
    }

    MemoryServiceClient(ObjectMapper objectMapper, MailConfig.MemoryServiceProperties props, HttpClient httpClient) {
        this(objectMapper, props, httpClient, duration -> Thread.sleep(duration.toMillis()));
    }

    MemoryServiceClient(ObjectMapper objectMapper,
                        MailConfig.MemoryServiceProperties props,
                        HttpClient httpClient,
                        RetrySleeper retrySleeper) {
        this.objectMapper = objectMapper;
        this.baseUrl = props.getUrl();
        this.enabled = props.isEnabled();
        this.httpClient = httpClient;
        this.retrySleeper = retrySleeper;
    }

    public void createPendingTask(PendingTaskRequest request) {
        if (!enabled) {
            log.debug("memory-service disabled, skipping");
            return;
        }
        try {
            postJson("/api/tasks/pending", request, "Pending task created in memory-service");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create pending task in memory-service", e);
        }
    }

    public MemorySearchResponse search(MemorySearchRequest request) {
        if (!enabled) {
            return new MemorySearchResponse(request.query(), request.mode(), request.layers(), null, List.of());
        }
        try {
            return postJson("/api/search", request, "Mail linking search completed", MemorySearchResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to search in memory-service", e);
        }
    }

    public void createCapture(String text, String source, String sourceId) {
        if (!enabled) {
            log.debug("memory-service disabled, skipping capture");
            return;
        }
        try {
            postJson("/api/capture", Map.of(
                "text", text,
                "source", source,
                "sourceId", sourceId
            ), "Capture saved to memory-service");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save capture to memory-service", e);
        }
    }

    public void createNote(String title, String text, String tags, String source) {
        if (!enabled) {
            log.debug("memory-service disabled, skipping note");
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            payload.put("text", text);
            payload.put("tags", tags);
            payload.put("source", source);
            postJson("/api/notes", payload, "Note saved to memory-service");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save note to memory-service", e);
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
            log.error("", e);
            return false;
        }
    }

    private void postJson(String path, Object payload, String successMessage) throws Exception {
        postJson(path, payload, successMessage, Void.class);
    }

    private <T> T postJson(String path, Object payload, String successMessage, Class<T> responseType) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(REQUEST_TIMEOUT)
            .build();

        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info(successMessage);
                    if (responseType == Void.class) {
                        return null;
                    }
                    return objectMapper.readValue(response.body(), responseType);
                }
                throw new IllegalStateException("memory-service " + path + " returned " + response.statusCode()
                    + ": " + response.body());
            } catch (Exception exception) {
                if (attempt == RETRY_DELAYS.size()) {
                    throw exception;
                }
                Duration delay = RETRY_DELAYS.get(attempt);
                log.warn("memory-service call {} failed on attempt {}/{}: {}. Retrying in {}s",
                    path, attempt + 1, RETRY_DELAYS.size() + 1, exception.getMessage(), delay.toSeconds());
                log.warn("httpRequest: {}", httpRequest);
                log.error("", exception);
                retrySleeper.sleep(delay);
            }
        }
        return null;
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
