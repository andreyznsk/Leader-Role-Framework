package ru.andreyz.mailagent.service;

import microsoft.exchange.webservices.data.core.ExchangeService;
import microsoft.exchange.webservices.data.core.PropertySet;
import microsoft.exchange.webservices.data.core.enumeration.property.BasePropertySet;
import microsoft.exchange.webservices.data.core.enumeration.property.WellKnownFolderName;
import microsoft.exchange.webservices.data.core.enumeration.search.FolderTraversal;
import microsoft.exchange.webservices.data.core.service.folder.Folder;
import microsoft.exchange.webservices.data.core.service.schema.FolderSchema;
import microsoft.exchange.webservices.data.search.FindFoldersResults;
import microsoft.exchange.webservices.data.search.FolderView;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.client.EwsSupport;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.MailAuthType;
import ru.andreyz.mailagent.model.MailConnectionErrorType;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Slf4j
@RequiredArgsConstructor
@Component
public class EwsConnectionTester {


    private final MailConfig.TestConnectionProperties testConnectionProperties;

    public MailConnectionTestResult test(MailConfig.MailProperties mailProperties,
                                         MailConfig.EwsProperties ewsProperties,
                                         List<String> excludeFolders) {
        MailAuthType authType = MailAuthType.fromValue(ewsProperties.getAuthType(), MailAuthType.BASIC);
        String endpoint = EwsSupport.connectionTarget(mailProperties, ewsProperties);
        if (endpoint == null || endpoint.isBlank()) {
            return MailConnectionTestResult.failed("ews", authType.name(), MailConnectionErrorType.INVALID_ENDPOINT,
                    "EWS endpoint is required", "Endpoint URL is blank", endpoint);
        }
        if (mailProperties.getUsername() == null || mailProperties.getUsername().isBlank()) {
            return MailConnectionTestResult.failed("ews", authType.name(), MailConnectionErrorType.INVALID_ENDPOINT,
                    "Username is required", "Username is blank", endpoint);
        }
        if (mailProperties.getPassword() == null || mailProperties.getPassword().isBlank()) {
            return MailConnectionTestResult.failed("ews", authType.name(), MailConnectionErrorType.UNAUTHORIZED,
                    "Password is required", "Password is blank", endpoint);
        }
        if (authType == MailAuthType.OAUTH2) {
            return MailConnectionTestResult.failed("ews", authType.name(), MailConnectionErrorType.NOT_SUPPORTED,
                    "OAUTH2 is planned but not implemented", "Use BASIC or NTLM for MVP", endpoint);
        }

        log.info("EWS test connection started: endpoint={}, authType={}, username={}",
                endpoint, authType.name(), mailProperties.getUsername());

        try {
            ExchangeService service = EwsSupport.createService(mailProperties, ewsProperties);
            try {
                Folder inbox = Folder.bind(service, WellKnownFolderName.Inbox, folderPropertySet());
                FolderScanResult folderScan = scanFolders(inbox, excludeFolders);
                log.info("EWS test connection success: exchangeVersion={}, foldersFound={}",
                        ewsProperties.getVersion(), folderScan.foldersFound());
                return MailConnectionTestResult.connected(
                        "ews",
                        normalizeExchangeVersion(ewsProperties.getVersion()),
                        authType.name(),
                        folderScan.foldersFound(),
                        folderScan.scanLimited(),
                        true,
                        "Connected",
                        endpoint
                );
            } finally {
                service.close();
            }
        } catch (Exception e) {
            MailConnectionErrorType errorType = mapError(e);
            String details = rootMessage(e);
            log.warn("EWS test connection failed: errorType={}, message={}", errorType.name(), details);
            log.error("", e);
            return MailConnectionTestResult.failed(
                    "ews",
                    authType.name(),
                    errorType,
                    messageFor(errorType),
                    details,
                    endpoint
            );
        }
    }

    private FolderScanResult scanFolders(Folder inbox, List<String> excludeFolders) throws Exception {
        int limit = Math.max(1, testConnectionProperties.getMaxFoldersToScan());
        int foldersFound = isExcluded("Inbox", excludeFolders) ? 0 : 1;
        boolean scanLimited = false;
        ArrayDeque<FolderPath> queue = new ArrayDeque<>();
        queue.add(new FolderPath(inbox, "Inbox"));

        while (!queue.isEmpty()) {
            FolderPath current = queue.removeFirst();
            FolderView view = new FolderView(100);
            view.setTraversal(FolderTraversal.Shallow);
            view.setPropertySet(folderPropertySet());

            int offset = 0;
            boolean more;
            do {
                view.setOffset(offset);
                FindFoldersResults children = current.folder().findFolders(view);
                for (Folder child : children.getFolders()) {
                    String path = current.path() + "/" + child.getDisplayName();
                    if (!isExcluded(path, excludeFolders)) {
                        foldersFound++;
                    }
                    if (foldersFound >= limit) {
                        scanLimited = true;
                        return new FolderScanResult(foldersFound, true);
                    }
                    queue.addLast(new FolderPath(child, path));
                }
                more = children.isMoreAvailable();
                offset = children.getNextPageOffset() != null ? children.getNextPageOffset() : 0;
            } while (more);
        }
        return new FolderScanResult(foldersFound, scanLimited);
    }

    private PropertySet folderPropertySet() {
        return new PropertySet(BasePropertySet.IdOnly,
                FolderSchema.DisplayName,
                FolderSchema.ChildFolderCount);
    }

    private MailConnectionErrorType mapError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("401") || normalized.contains("unauthorized")) {
                    return MailConnectionErrorType.UNAUTHORIZED;
                }
                if (normalized.contains("timed out") || normalized.contains("timeout")) {
                    return MailConnectionErrorType.TIMEOUT;
                }
                if (normalized.contains("ssl")) {
                    return MailConnectionErrorType.SSL_ERROR;
                }
                if (normalized.contains("malformed") || normalized.contains("illegal character")
                        || normalized.contains("uri is not absolute") || normalized.contains("invalid uri")) {
                    return MailConnectionErrorType.INVALID_ENDPOINT;
                }
                if (normalized.contains("connection refused") || normalized.contains("unknownhost")) {
                    return MailConnectionErrorType.ENDPOINT_UNREACHABLE;
                }
            }
            if (current instanceof SocketTimeoutException) {
                return MailConnectionErrorType.TIMEOUT;
            }
            if (current instanceof SSLException) {
                return MailConnectionErrorType.SSL_ERROR;
            }
            if (current instanceof IllegalArgumentException) {
                return MailConnectionErrorType.INVALID_ENDPOINT;
            }
            if (current instanceof ConnectException) {
                return MailConnectionErrorType.ENDPOINT_UNREACHABLE;
            }
            current = current.getCause();
        }
        return MailConnectionErrorType.UNKNOWN;
    }

    private String messageFor(MailConnectionErrorType errorType) {
        return switch (errorType) {
            case UNAUTHORIZED -> "EWS authentication failed";
            case TIMEOUT -> "EWS endpoint timed out";
            case SSL_ERROR -> "SSL handshake failed";
            case INVALID_ENDPOINT -> "EWS endpoint is invalid";
            case NOT_SUPPORTED -> "Authentication type is not supported";
            case ENDPOINT_UNREACHABLE -> "EWS endpoint is not reachable";
            case UNKNOWN -> "Connection failed";
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

    private String normalizeExchangeVersion(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private boolean isExcluded(String folderPath, List<String> excludeFolders) {
        Set<String> normalized = new HashSet<>();
        if (excludeFolders != null) {
            for (String raw : excludeFolders) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                for (String token : raw.split(",")) {
                    if (!token.isBlank()) {
                        normalized.add(normalize(token));
                    }
                }
            }
        }
        String path = normalize(folderPath);
        String leaf = normalize(leafName(folderPath));
        return normalized.contains(path) || normalized.contains(leaf);
    }

    private String leafName(String folderPath) {
        int slash = folderPath.lastIndexOf('/');
        return slash >= 0 ? folderPath.substring(slash + 1) : folderPath;
    }

    private String normalize(String value) {
        return value.trim()
                .replace('\\', '/')
                .replaceAll("/+", "/")
                .toLowerCase(Locale.ROOT);
    }

    private record FolderScanResult(int foldersFound, boolean scanLimited) {
    }

    private record FolderPath(Folder folder, String path) {
        private FolderPath {
            Objects.requireNonNull(folder);
            Objects.requireNonNull(path);
        }
    }
}
