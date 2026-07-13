package ru.andreyz.common.jira;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.andreyz.common.jira.dto.JiraAssignableUser;
import ru.andreyz.common.jira.dto.JiraConnectionResult;
import ru.andreyz.common.jira.dto.JiraCreateIssueRequest;
import ru.andreyz.common.jira.dto.JiraCreateIssueResult;
import ru.andreyz.common.jira.dto.JiraCurrentUser;
import ru.andreyz.common.jira.dto.JiraIssueType;
import ru.andreyz.common.jira.dto.JiraProject;
import ru.andreyz.common.jira.exception.JiraAuthenticationException;
import ru.andreyz.common.jira.exception.JiraClientException;
import ru.andreyz.common.jira.exception.JiraPermissionException;
import ru.andreyz.common.jira.exception.JiraUnavailableException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class AtlassianJiraClient implements JiraClient {

    private final RestClient client;
    private final JiraIntegrationProperties properties;

    public AtlassianJiraClient(RestClient.Builder restClientBuilder,
                               JiraIntegrationProperties properties) {
        this.properties = properties;
        this.client = restClientBuilder
                .baseUrl(normalizeBaseUrl(properties.getBaseUrl()))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeaders(this::applyAuthorization)
                .requestFactory(requestFactory(settingsTimeout(properties)))
                .build();
    }

    @Override
    public JiraConnectionResult testConnection() {
        JiraCurrentUser user = getCurrentUser();
        return new JiraConnectionResult(true, "Connected as " + user.displayName(), user);
    }

    @Override
    public JiraCurrentUser getCurrentUser() {
        JsonNode body = get("/rest/api/2/myself");
        return toCurrentUser(body);
    }

    @Override
    public List<JiraProject> getProjects(Set<String> allowedProjectKeys) {
        if (allowedProjectKeys == null || allowedProjectKeys.isEmpty()) {
            return List.of();
        }
        return allowedProjectKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .map(this::getProject)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(JiraProject::key))
                .toList();
    }

    @Override
    public List<JiraIssueType> getIssueTypes(String projectKey) {
        JsonNode project = getProjectNode(projectKey);
        JsonNode issueTypes = project.path("issueTypes");
        if (!issueTypes.isArray()) {
            return List.of();
        }
        List<JiraIssueType> result = new ArrayList<>();
        for (JsonNode item : issueTypes) {
            result.add(new JiraIssueType(
                    text(item, "id"),
                    text(item, "name"),
                    item.path("subtask").asBoolean(false)
            ));
        }
        return result.stream()
                .filter(type -> !type.subtask())
                .sorted(Comparator.comparing(JiraIssueType::name))
                .toList();
    }

    @Override
    public List<JiraAssignableUser> getAssignableUsers(String projectKey, String query) {
        String normalizedProject = requireText(projectKey, "projectKey");
        String normalizedQuery = query == null ? "" : query.trim();
        String path = "/rest/api/2/user/assignable/search?project=%s&query=%s".formatted(
                url(normalizedProject),
                url(normalizedQuery)
        );
        JsonNode body = get(path);
        if (!body.isArray()) {
            return List.of();
        }
        Map<String, JiraAssignableUser> unique = new LinkedHashMap<>();
        for (JsonNode item : body) {
            JiraAssignableUser user = new JiraAssignableUser(
                    text(item, "accountId"),
                    text(item, "displayName"),
                    firstNonBlank(text(item, "emailAddress"), text(item, "name"), text(item, "key")),
                    item.path("active").asBoolean(true)
            );
            if (StringUtils.hasText(user.accountId())) {
                unique.putIfAbsent(user.accountId(), user);
            }
        }
        return unique.values().stream()
                .filter(JiraAssignableUser::active)
                .sorted(Comparator.comparing(JiraAssignableUser::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public JiraCreateIssueResult createIssue(JiraCreateIssueRequest request) {
        requireText(request.projectKey(), "projectKey");
        requireText(request.issueTypeId(), "issueTypeId");
        requireText(request.summary(), "summary");

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", request.projectKey().trim()));
        fields.put("issuetype", Map.of("id", request.issueTypeId().trim()));
        fields.put("summary", request.summary().trim());
        fields.put("description", request.description() == null ? "" : request.description());
        if (StringUtils.hasText(request.assigneeAccountId())) {
            fields.put("assignee", Map.of("accountId", request.assigneeAccountId().trim()));
        }

        JsonNode body = post("/rest/api/2/issue", Map.of("fields", fields));
        String key = text(body, "key");
        return new JiraCreateIssueResult(
                text(body, "id"),
                key,
                issueBrowseUrl(key)
        );
    }

    private JiraProject getProject(String projectKey) {
        JsonNode project = getProjectNode(projectKey);
        return new JiraProject(
                text(project, "id"),
                text(project, "key"),
                text(project, "name")
        );
    }

    private JsonNode getProjectNode(String projectKey) {
        return get("/rest/api/2/project/" + url(requireText(projectKey, "projectKey")));
    }

    private JsonNode get(String path) {
        try {
            return client.get().uri(path).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw translateException(e);
        } catch (RestClientException e) {
            throw new JiraUnavailableException("Jira request failed: " + safeMessage(e), e);
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            return client.post().uri(path).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw translateException(e);
        } catch (RestClientException e) {
            throw new JiraUnavailableException("Jira request failed: " + safeMessage(e), e);
        }
    }

    private JiraClientException translateException(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String details = extractErrorMessage(e);
        if (status == 401) {
            return new JiraAuthenticationException(details, e);
        }
        if (status == 403) {
            return new JiraPermissionException(details, e);
        }
        if (status >= 500) {
            return new JiraUnavailableException(details, e);
        }
        return new JiraClientException(details, e);
    }

    private String extractErrorMessage(RestClientResponseException e) {
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            if (body != null) {
                List<String> messages = new ArrayList<>();
                JsonNode errorMessages = body.path("errorMessages");
                if (errorMessages.isArray()) {
                    errorMessages.forEach(item -> {
                        if (item.isTextual() && StringUtils.hasText(item.asText())) {
                            messages.add(item.asText().trim());
                        }
                    });
                }
                JsonNode errors = body.path("errors");
                if (errors.isObject()) {
                    errors.fields().forEachRemaining(entry -> {
                        if (entry.getValue().isTextual() && StringUtils.hasText(entry.getValue().asText())) {
                            messages.add(entry.getValue().asText().trim());
                        }
                    });
                }
                if (!messages.isEmpty()) {
                    return sanitize(String.join("; ", messages));
                }
            }
        } catch (Exception ignored) {
            // fallback below
        }
        return sanitize("Jira returned HTTP " + e.getStatusCode().value());
    }

    private JiraCurrentUser toCurrentUser(JsonNode body) {
        return new JiraCurrentUser(
                text(body, "accountId"),
                text(body, "displayName"),
                firstNonBlank(text(body, "emailAddress"), text(body, "name"), text(body, "key"))
        );
    }

    private void applyAuthorization(HttpHeaders headers) {
        String token = requireText(properties.getToken(), "jira.token");
        if (properties.getAuthType() == JiraIntegrationProperties.AuthType.BASIC) {
            String username = requireText(properties.getUsername(), "jira.username");
            String raw = username + ":" + token;
            headers.set(HttpHeaders.AUTHORIZATION,
                    "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            return;
        }
        headers.setBearerAuth(token);
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(Duration timeout) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        int millis = Math.toIntExact(timeout.toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    private static Duration settingsTimeout(JiraIntegrationProperties properties) {
        Duration connect = properties.getTimeout().getConnect();
        Duration read = properties.getTimeout().getRead();
        return connect.compareTo(read) > 0 ? connect : read;
    }

    private String issueBrowseUrl(String key) {
        return normalizeBaseUrl(properties.getBaseUrl()) + "/browse/" + key;
    }

    private static String normalizeBaseUrl(String value) {
        String baseUrl = requireText(value, "jira.base-url");
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " must be set");
        }
        return value.trim();
    }

    private static String text(JsonNode node, String fieldName) {
        if (node == null || node.path(fieldName).isMissingNode() || node.path(fieldName).isNull()) {
            return null;
        }
        String value = node.path(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String safeMessage(Exception e) {
        return sanitize(e == null ? "unknown error" : e.getMessage());
    }

    private static String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown error";
        }
        String sanitized = value.replaceAll("(?i)(authorization|bearer|basic)\\s+[^\\s,;]+", "$1 [redacted]");
        sanitized = sanitized.replaceAll("(?i)(token|password)=([^\\s,;]+)", "$1=[redacted]");
        return sanitized;
    }
}
