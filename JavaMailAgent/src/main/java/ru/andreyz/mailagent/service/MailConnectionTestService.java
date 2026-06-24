package ru.andreyz.mailagent.service;

import org.springframework.stereotype.Service;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.MailAuthType;
import ru.andreyz.mailagent.model.MailConnectionErrorType;
import ru.andreyz.mailagent.model.MailEndpointDetectRequest;
import ru.andreyz.mailagent.model.MailEndpointDetectResult;
import ru.andreyz.mailagent.model.MailConnectionTestRequest;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.util.List;

@Service
public class MailConnectionTestService {

    private final MailRuntimeConfigService runtimeConfigService;
    private final MailClient runtimeMailClient;
    private final MailConfig.EwsProperties baseEwsProperties;
    private final MailConfig.ImapProperties baseImapProperties;
    private final EwsConnectionTester ewsConnectionTester;
    private final EwsEndpointDetector ewsEndpointDetector;

    public MailConnectionTestService(MailRuntimeConfigService runtimeConfigService,
                                     MailClient runtimeMailClient,
                                     MailConfig.EwsProperties baseEwsProperties,
                                     MailConfig.ImapProperties baseImapProperties,
                                     EwsConnectionTester ewsConnectionTester,
                                     EwsEndpointDetector ewsEndpointDetector) {
        this.runtimeConfigService = runtimeConfigService;
        this.runtimeMailClient = runtimeMailClient;
        this.baseEwsProperties = baseEwsProperties;
        this.baseImapProperties = baseImapProperties;
        this.ewsConnectionTester = ewsConnectionTester;
        this.ewsEndpointDetector = ewsEndpointDetector;
    }

    public MailEndpointDetectResult detectEndpoint(MailEndpointDetectRequest request) {
        return ewsEndpointDetector.detect(request);
    }

    public MailConnectionTestResult testConnection(MailConnectionTestRequest request) {
        MailRuntimeConfig snapshot = runtimeConfigService.snapshot();
        String protocol = selectProtocol(request, snapshot);
        if ("ews".equals(protocol)) {
            MailEndpointDetectResult endpointDetect = detectEndpoint(new MailEndpointDetectRequest(
                    protocol,
                    request != null ? request.ewsUrl() : snapshot.serverUrl()
            ));
            if (!endpointDetect.success()) {
                return new MailConnectionTestResult(
                        false,
                        ru.andreyz.mailagent.model.MailConnectionStatus.FAILED,
                        "ews",
                        endpointDetect.endpointReachable(),
                        endpointDetect.httpsOk(),
                        endpointDetect.ewsDetected(),
                        false,
                        null,
                        selectAuthType(request, snapshot),
                        request != null ? request.username() : snapshot.login(),
                        null,
                        null,
                        null,
                        endpointDetect.message(),
                        endpointDetect.errorType(),
                        endpointDetect.details(),
                        endpointDetect.endpoint(),
                        endpointDetect.endpoint()
                );
            }
            MailConfig.MailProperties mailProperties = toMailProperties(snapshot, request);
            MailConfig.EwsProperties ewsProperties = toEwsProperties(snapshot, request);
            MailConnectionTestResult result = ewsConnectionTester.test(mailProperties, ewsProperties, selectExcludeFolders(request, snapshot));
            return new MailConnectionTestResult(
                    result.success(),
                    result.status(),
                    result.protocol(),
                    endpointDetect.endpointReachable(),
                    endpointDetect.httpsOk(),
                    endpointDetect.ewsDetected(),
                    result.authenticationOk(),
                    result.exchangeVersion(),
                    result.authType(),
                    mailProperties.getUsername(),
                    result.foldersFound(),
                    result.foldersScanLimited(),
                    result.inboxFound(),
                    result.message(),
                    result.errorType(),
                    result.details(),
                    result.endpoint(),
                    result.target()
            );
        }
        if ("imap".equals(protocol) || "maildev".equals(protocol)) {
            MailConnectionTestResult result = runtimeMailClient.testConnection();
            return result.success() ? result : MailConnectionTestResult.failed(
                    protocol,
                    null,
                    MailConnectionErrorType.UNKNOWN,
                    result.message(),
                    result.details(),
                    result.target()
            );
        }
        return MailConnectionTestResult.failed(protocol, null, MailConnectionErrorType.NOT_SUPPORTED,
                "Protocol is not supported", protocol, protocol);
    }

    private String selectProtocol(MailConnectionTestRequest request, MailRuntimeConfig snapshot) {
        if (request == null || request.protocol() == null || request.protocol().isBlank()) {
            return snapshot.protocol();
        }
        return request.protocol().trim().toLowerCase(java.util.Locale.ROOT);
    }

    private List<String> selectExcludeFolders(MailConnectionTestRequest request, MailRuntimeConfig snapshot) {
        if (request != null && request.folderExclude() != null) {
            return request.folderExclude().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
        }
        return snapshot.foldersExclude();
    }

    private MailConfig.MailProperties toMailProperties(MailRuntimeConfig snapshot, MailConnectionTestRequest request) {
        MailConfig.MailProperties properties = new MailConfig.MailProperties();
        properties.setProtocol("ews");
        properties.setUsername(selectString(request != null ? request.username() : null, snapshot.login()));
        properties.setPassword(selectSecret(request != null ? request.password() : null, snapshot.password()));
        properties.setPollIntervalSeconds(snapshot.pollIntervalSeconds());
        return properties;
    }

    private MailConfig.EwsProperties toEwsProperties(MailRuntimeConfig snapshot, MailConnectionTestRequest request) {
        MailConfig.EwsProperties properties = new MailConfig.EwsProperties();
        properties.setUrl(selectString(request != null ? request.ewsUrl() : null, snapshot.serverUrl()));
        properties.setAutodiscover(baseEwsProperties.isAutodiscover());
        properties.setDomain(baseEwsProperties.getDomain());
        properties.setAuthType(selectAuthType(request, snapshot));
        properties.setVersion(baseEwsProperties.getVersion());
        properties.setTimeoutSeconds(baseEwsProperties.getTimeoutSeconds());
        return properties;
    }

    private String selectAuthType(MailConnectionTestRequest request, MailRuntimeConfig snapshot) {
        MailAuthType fallback = snapshot.authType();
        if (request == null) {
            return fallback.name();
        }
        return MailAuthType.fromValue(request.authType(), fallback).name();
    }

    private String selectString(String incoming, String fallback) {
        if (incoming == null) {
            return fallback;
        }
        String trimmed = incoming.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private String selectSecret(String incoming, String fallback) {
        if (incoming == null) {
            return fallback;
        }
        String trimmed = incoming.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
