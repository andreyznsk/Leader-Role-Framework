package ru.andreyz.mailagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.client.EwsMailClient;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.client.MailException;
import ru.andreyz.mailagent.client.MaildevClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.util.List;

@Component
public class RuntimeMailClient implements MailClient {

    private final ObjectMapper objectMapper;
    private final MailRuntimeConfigService runtimeConfigService;
    private final MailConfig.MaildevProperties maildevProperties;
    private final MailConfig.EwsProperties baseEwsProperties;

    public RuntimeMailClient(ObjectMapper objectMapper,
                             MailRuntimeConfigService runtimeConfigService,
                             MailConfig.MaildevProperties maildevProperties,
                             MailConfig.EwsProperties baseEwsProperties) {
        this.objectMapper = objectMapper;
        this.runtimeConfigService = runtimeConfigService;
        this.maildevProperties = maildevProperties;
        this.baseEwsProperties = baseEwsProperties;
    }

    @Override
    public List<String> listFolders(List<String> excludeFolders) throws MailException {
        return delegateChecked().listFolders(excludeFolders);
    }

    @Override
    public List<Email> listUnread(String folder, int limit) throws MailException {
        return delegateChecked().listUnread(folder, limit);
    }

    @Override
    public void markAsRead(String emailId, String folder) throws MailException {
        delegateChecked().markAsRead(emailId, folder);
    }

    @Override
    public MailConnectionTestResult testConnection() {
        try {
            return delegateChecked().testConnection();
        } catch (Exception e) {
            return new MailConnectionTestResult(false, e.getMessage(), runtimeConfigService.snapshot().protocol());
        }
    }

    @Override
    public void close() {
    }

    private MailClient delegateChecked() throws MailException {
        MailRuntimeConfig config = runtimeConfigService.snapshot();
        return switch (config.protocol()) {
            case "maildev" -> new MaildevClient(objectMapper, maildevProperties);
            case "ews" -> new EwsMailClient(toMailProperties(config), toEwsProperties(config));
            case "imap" -> throw new MailException("IMAP protocol is not implemented yet");
            default -> throw new MailException("Unsupported protocol: " + config.protocol());
        };
    }

    private MailConfig.MailProperties toMailProperties(MailRuntimeConfig config) {
        MailConfig.MailProperties properties = new MailConfig.MailProperties();
        properties.setProtocol(config.protocol());
        properties.setUsername(config.login());
        properties.setPassword(config.password());
        properties.setPollIntervalSeconds(config.pollIntervalSeconds());
        return properties;
    }

    private MailConfig.EwsProperties toEwsProperties(MailRuntimeConfig config) {
        MailConfig.EwsProperties properties = new MailConfig.EwsProperties();
        properties.setUrl(config.serverUrl());
        properties.setAutodiscover(baseEwsProperties.isAutodiscover());
        properties.setDomain(baseEwsProperties.getDomain());
        properties.setVersion(baseEwsProperties.getVersion());
        properties.setTimeoutSeconds(baseEwsProperties.getTimeoutSeconds());
        return properties;
    }
}
