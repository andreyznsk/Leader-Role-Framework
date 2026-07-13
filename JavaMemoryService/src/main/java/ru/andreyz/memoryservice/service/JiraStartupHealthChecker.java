package ru.andreyz.memoryservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.andreyz.common.jira.JiraClient;
import ru.andreyz.common.jira.JiraIntegrationProperties;
import ru.andreyz.common.jira.dto.JiraCurrentUser;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class JiraStartupHealthChecker implements ApplicationRunner {

    private final JiraIntegrationProperties properties;
    private final Optional<JiraClient> jiraClient;
    private final JiraIntegrationStateService stateService;

    public JiraStartupHealthChecker(JiraIntegrationProperties properties,
                                    Optional<JiraClient> jiraClient,
                                    JiraIntegrationStateService stateService) {
        this.properties = properties;
        this.jiraClient = jiraClient;
        this.stateService = stateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        refreshSnapshot();
    }

    public JiraIntegrationSnapshot refreshSnapshot() {
        if (!properties.isEnabled()) {
            log.info("Jira integration: disabled");
            JiraIntegrationSnapshot snapshot = JiraIntegrationSnapshot.disabled("Jira integration is disabled");
            stateService.update(snapshot);
            return snapshot;
        }
        if (!properties.isStartupCheckEnabled()) {
            log.info("Jira integration: enabled, startup check skipped");
            JiraIntegrationSnapshot snapshot = JiraIntegrationSnapshot.available("Jira startup check skipped", null);
            stateService.update(snapshot);
            return snapshot;
        }
        try {
            validateConfiguration();
            JiraClient client = jiraClient.orElseThrow(() ->
                    new IllegalStateException("Jira client is not configured"));
            JiraCurrentUser currentUser = client.getCurrentUser();
            client.getProjects(new LinkedHashSet<>(properties.getAllowedProjects()));
            client.getIssueTypes(properties.getDefaultProject());
            log.info("Jira integration: enabled, startup check passed successfully (user={}, baseUrl={})",
                    currentUser.displayName(), properties.getBaseUrl());
            JiraIntegrationSnapshot snapshot = JiraIntegrationSnapshot.available(
                    "Connected as " + currentUser.displayName(),
                    currentUser
            );
            stateService.update(snapshot);
            return snapshot;
        } catch (Exception e) {
            String safeMessage = sanitize(e.getMessage());
            log.warn("Jira integration: enabled, startup check failed: {}", safeMessage);
            JiraIntegrationSnapshot snapshot = JiraIntegrationSnapshot.unavailable(safeMessage);
            stateService.update(snapshot);
            return snapshot;
        }
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new IllegalStateException("jira.base-url must be set");
        }
        if (!StringUtils.hasText(properties.getToken())) {
            throw new IllegalStateException("jira.token must be set");
        }
        if (properties.getAuthType() == JiraIntegrationProperties.AuthType.BASIC
                && !StringUtils.hasText(properties.getUsername())) {
            throw new IllegalStateException("jira.username must be set for BASIC auth");
        }
        Set<String> allowedProjects = new LinkedHashSet<>();
        for (String value : properties.getAllowedProjects()) {
            if (StringUtils.hasText(value)) {
                allowedProjects.add(value.trim());
            }
        }
        if (allowedProjects.isEmpty()) {
            throw new IllegalStateException("jira.allowed-projects must contain at least one project");
        }
        if (!allowedProjects.contains(properties.getDefaultProject())) {
            throw new IllegalStateException("jira.default-project must belong to jira.allowed-projects");
        }
    }

    private String sanitize(String message) {
        if (!StringUtils.hasText(message)) {
            return "Jira integration unavailable";
        }
        return message.replaceAll("(?i)(authorization|bearer|basic)\\s+[^\\s,;]+", "$1 [redacted]");
    }
}
