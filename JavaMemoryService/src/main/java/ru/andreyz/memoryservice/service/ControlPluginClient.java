package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.andreyz.memoryservice.dto.ControlPluginRemoteAuditEntry;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsResponse;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsUpdateRequest;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsUpdateResponse;

import java.util.List;

@Component
public class ControlPluginClient {

    private static final Logger log = LoggerFactory.getLogger(ControlPluginClient.class);

    private final RestClient.Builder restClientBuilder;

    public ControlPluginClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public ControlPluginSettingsResponse fetchSettings(String baseUrl) {
        try {
            return client(baseUrl).get()
                    .uri("/api/control/settings")
                    .retrieve()
                    .body(ControlPluginSettingsResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("Plugin settings fetch failed: " + messageOf(e), e);
        }
    }

    public ControlPluginSettingsUpdateResponse updateSettings(String baseUrl, ControlPluginSettingsUpdateRequest request) {
        try {
            return client(baseUrl).put()
                    .uri("/api/control/settings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ControlPluginSettingsUpdateResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("Plugin settings update failed: " + messageOf(e), e);
        }
    }

    public List<ControlPluginRemoteAuditEntry> fetchAudit(String baseUrl) {
        try {
            ControlPluginRemoteAuditEntry[] body = client(baseUrl).get()
                    .uri("/api/control/audit")
                    .retrieve()
                    .body(ControlPluginRemoteAuditEntry[].class);
            return body != null ? List.of(body) : List.of();
        } catch (RestClientException e) {
            throw new IllegalStateException("Plugin audit fetch failed: " + messageOf(e), e);
        }
    }

    public String fetchHealthStatus(String baseUrl) {
        try {
            JsonNode body = client(baseUrl).get()
                    .uri("/actuator/health")
                    .retrieve()
                    .body(JsonNode.class);
            String status = body != null && body.hasNonNull("status")
                    ? body.get("status").asText()
                    : "UNKNOWN";
            return status == null || status.isBlank() ? "UNKNOWN" : status.toUpperCase();
        } catch (RestClientException e) {
            log.error("", e);
            return "DOWN";
        }
    }

    private RestClient client(String baseUrl) {
        return restClientBuilder.baseUrl(baseUrl).build();
    }

    private String messageOf(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "unknown error";
        }
        return e.getMessage();
    }
}
