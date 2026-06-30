package ru.andreyz.mailagent.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.MailConnectionErrorType;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MaildevClient implements MailClient {

    private static final Logger log = LoggerFactory.getLogger(MaildevClient.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper;
    private final String apiUrl;

    public MaildevClient(ObjectMapper objectMapper, MailConfig.MaildevProperties props) {
        this.objectMapper = objectMapper;
        this.apiUrl = props.getApiUrl();
    }

    @Override
    public List<String> listFolders(List<String> excludeFolders) throws MailException {
        return List.of("INBOX");
    }

    @Override
    public List<Email> listUnread(String folder, int limit) throws MailException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/email"))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            List<Map<String, Object>> all = objectMapper.readValue(
                response.body(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );

            return all.stream()
                .filter(e -> !Boolean.TRUE.equals(e.get("read")))
                .limit(limit)
                .map(e -> toEmail(e, folder))
                .toList();

        } catch (Exception e) {
            throw new MailException("Failed to list emails from Maildev: " + e.getMessage(), e);
        }
    }

    @Override
    public void markAsRead(String emailId, String folder) throws MailException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/email/" + emailId + "/read"))
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new MailException("Failed to mark email " + emailId + " as read", e);
        }
    }

    @Override
    public MailConnectionTestResult testConnection() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/email"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return MailConnectionTestResult.connected("maildev", null, null, null, false, false, "HTTP 200", apiUrl);
            }
            return MailConnectionTestResult.failed("maildev", null, MailConnectionErrorType.UNKNOWN,
                    "HTTP " + response.statusCode(), "Maildev returned HTTP " + response.statusCode(), apiUrl);
        } catch (Exception e) {
            log.error("", e);
            return MailConnectionTestResult.failed("maildev", null, MailConnectionErrorType.UNKNOWN,
                    "Connection failed", e.getMessage(), apiUrl);
        }
    }

    @Override
    public void close() {}

    private Email toEmail(Map<String, Object> raw, String folder) {
        String id = String.valueOf(raw.get("id"));
        String subject = String.valueOf(raw.getOrDefault("subject", "(no subject)"));
        String from = extractFrom(raw);
        List<String> recipients = extractRecipients(raw);
        String body = String.valueOf(raw.getOrDefault("text", ""));
        String messageId = header(raw, "message-id");
        String conversationId = firstNonBlank(
                header(raw, "thread-index"),
                header(raw, "references"),
                header(raw, "thread-topic")
        );
        String inReplyTo = header(raw, "in-reply-to");
        return new Email(id, subject, from, recipients, body, messageId, conversationId, inReplyTo, LocalDateTime.now(), folder);
    }

    @SuppressWarnings("unchecked")
    private String extractFrom(Map<String, Object> raw) {
        Object fromObj = raw.get("from");
        if (fromObj instanceof List<?> fromList && !fromList.isEmpty()) {
            Object first = fromList.get(0);
            if (first instanceof Map<?, ?> firstMap) {
                Object address = firstMap.get("address");
                if (address != null) return address.toString();
            }
        }
        return "unknown";
    }

    private List<String> extractRecipients(Map<String, Object> raw) {
        List<String> recipients = new ArrayList<>();
        collectAddresses(raw.get("to"), recipients);
        collectAddresses(raw.get("cc"), recipients);
        collectAddresses(raw.get("bcc"), recipients);
        return recipients.stream().distinct().toList();
    }

    private void collectAddresses(Object source, List<String> recipients) {
        if (!(source instanceof List<?> values)) {
            return;
        }
        for (Object value : values) {
            if (value instanceof Map<?, ?> addressMap) {
                Object address = addressMap.get("address");
                if (address != null && !address.toString().isBlank()) {
                    recipients.add(address.toString());
                }
            }
        }
    }

    private String header(Map<String, Object> raw, String name) {
        Object headers = raw.get("headers");
        if (headers instanceof Map<?, ?> headerMap) {
            Object value = headerMap.get(name);
            if (value == null) {
                value = headerMap.get(name.toLowerCase());
            }
            if (value instanceof List<?> values && !values.isEmpty()) {
                Object first = values.get(0);
                return first != null ? first.toString() : null;
            }
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
