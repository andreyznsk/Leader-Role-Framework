package ru.andreyz.mailagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.model.MailConnectionErrorType;
import ru.andreyz.mailagent.model.MailEndpointDetectRequest;
import ru.andreyz.mailagent.model.MailEndpointDetectResult;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

@Component
public class EwsEndpointDetector {

    private static final Logger log = LoggerFactory.getLogger(EwsEndpointDetector.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public MailEndpointDetectResult detect(MailEndpointDetectRequest request) {
        String endpoint = request != null ? blankToNull(request.ewsUrl()) : null;
        if (endpoint == null) {
            return MailEndpointDetectResult.failed("ews", false, false, false, null,
                    MailConnectionErrorType.INVALID_ENDPOINT, "EWS endpoint is required", "Endpoint URL is blank", null);
        }

        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            log.error("", e);
            return MailEndpointDetectResult.failed("ews", false, false, false, null,
                    MailConnectionErrorType.INVALID_ENDPOINT, "EWS endpoint is invalid", rootMessage(e), endpoint);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return MailEndpointDetectResult.failed("ews", false, false, false, null,
                    MailConnectionErrorType.INVALID_ENDPOINT, "EWS endpoint must use HTTPS", "Expected https:// URL", endpoint);
        }

        log.info("EWS endpoint detection started: endpoint={}", endpoint);

        try {
            HttpResponse<String> response = sendRequest(uri);
            int status = response.statusCode();
            String body = response.body() != null ? response.body() : "";
            boolean ewsDetected = looksLikeEws(body) || status == 401 || status == 403;
            if (status >= 200 && status < 500 && ewsDetected) {
                log.info("EWS endpoint detected: httpStatus={}, ewsDetected=true, recommendedAuthType=NTLM", status);
                return MailEndpointDetectResult.detected(
                        "ews",
                        true,
                        true,
                        true,
                        status,
                        "NTLM",
                        "EWS endpoint detected",
                        endpoint
                );
            }
            return MailEndpointDetectResult.failed(
                    "ews",
                    true,
                    true,
                    false,
                    status,
                    MailConnectionErrorType.INVALID_ENDPOINT,
                    "EWS response invalid",
                    "HTTP " + status,
                    endpoint
            );
        } catch (Exception e) {
            log.error("", e);
            MailConnectionErrorType errorType = mapError(e);
            return MailEndpointDetectResult.failed(
                    "ews",
                    false,
                    true,
                    false,
                    null,
                    errorType,
                    messageFor(errorType),
                    rootMessage(e),
                    endpoint
            );
        }
    }

    private HttpResponse<String> sendRequest(URI uri) throws Exception {
        HttpRequest head = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(5))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> response = httpClient.send(head, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 405 || response.statusCode() == 400) {
                return sendGet(uri);
            }
            return response;
        } catch (Exception e) {
            log.error("", e);
            return sendGet(uri);
        }
    }

    private HttpResponse<String> sendGet(URI uri) throws Exception {
        HttpRequest get = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return httpClient.send(get, HttpResponse.BodyHandlers.ofString());
    }

    private boolean looksLikeEws(String body) {
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("you have created a service")
                || normalized.contains("exchange web services")
                || normalized.contains("service")
                || normalized.contains("wsdl");
    }

    private MailConnectionErrorType mapError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SSLException) {
                return MailConnectionErrorType.SSL_ERROR;
            }
            if (current instanceof ConnectException || current instanceof UnknownHostException) {
                return MailConnectionErrorType.ENDPOINT_UNREACHABLE;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("timed out") || normalized.contains("timeout")) {
                    return MailConnectionErrorType.TIMEOUT;
                }
                if (normalized.contains("ssl")) {
                    return MailConnectionErrorType.SSL_ERROR;
                }
                if (normalized.contains("unknown host") || normalized.contains("connection refused")) {
                    return MailConnectionErrorType.ENDPOINT_UNREACHABLE;
                }
            }
            current = current.getCause();
        }
        return MailConnectionErrorType.UNKNOWN;
    }

    private String messageFor(MailConnectionErrorType errorType) {
        return switch (errorType) {
            case SSL_ERROR -> "SSL handshake failed";
            case TIMEOUT -> "EWS endpoint timed out";
            case ENDPOINT_UNREACHABLE -> "EWS endpoint is not reachable";
            case INVALID_ENDPOINT -> "EWS endpoint is invalid";
            default -> "EWS endpoint detection failed";
        };
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null && !current.getMessage().isBlank()
                ? current.getMessage()
                : current.getClass().getSimpleName();
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
