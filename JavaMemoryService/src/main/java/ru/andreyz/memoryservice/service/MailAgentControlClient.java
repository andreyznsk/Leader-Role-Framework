package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.dto.MailAgentConnectionTestRequestDto;
import ru.andreyz.memoryservice.dto.MailAgentConnectionTestResultDto;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class MailAgentControlClient {

    private static final Logger log = LoggerFactory.getLogger(MailAgentControlClient.class);

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public MailAgentControlClient(ObjectMapper objectMapper,
                                  @Value("${app.plugins.mail.base-url:http://localhost:8080}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public void notifyEnabledChanged(boolean enabled) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("enabled", enabled));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/control/plugin-state"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("MailAgent notified about enabled={}", enabled);
            } else {
                log.warn("MailAgent plugin-state endpoint returned HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Failed to notify MailAgent about enabled state change: {}", fallbackMessage(e));
        }
    }

    public MailAgentConnectionTestResultDto testConnection(MailAgentConnectionTestRequestDto requestDto) {
        try {
            String body = requestDto != null ? objectMapper.writeValueAsString(requestDto) : "";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/control/test-connection"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readValue(response.body(), MailAgentConnectionTestResultDto.class);
            }
            return objectMapper.readValue(response.body(), MailAgentConnectionTestResultDto.class);
        } catch (Exception e) {
            return new MailAgentConnectionTestResultDto(false, "FAILED", null, null, null, null, null, null,
                    fallbackMessage(e), "UNKNOWN", fallbackMessage(e), baseUrl, baseUrl);
        }
    }

    private String fallbackMessage(Exception e) {
        if (e == null) {
            return "unknown error";
        }
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return e.getClass().getSimpleName();
    }
}
