package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ru.andreyz.common.jira.JiraClient;
import ru.andreyz.common.jira.JiraIntegrationProperties;
import ru.andreyz.common.jira.dto.JiraCurrentUser;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class JiraStartupHealthCheckerTest {

    @Test
    void disabledConfigurationSetsDisabledSnapshot(CapturedOutput output) throws Exception {
        JiraIntegrationProperties properties = new JiraIntegrationProperties();
        properties.setEnabled(false);
        JiraIntegrationStateService stateService = new JiraIntegrationStateService();
        JiraStartupHealthChecker checker = new JiraStartupHealthChecker(properties, Optional.empty(), stateService);

        checker.run(null);

        assertThat(stateService.getSnapshot().status()).isEqualTo(JiraIntegrationStatus.DISABLED);
        assertThat(output.getOut()).contains("Jira integration: disabled");
    }

    @Test
    void failedValidationSetsUnavailableSnapshot(CapturedOutput output) throws Exception {
        JiraIntegrationProperties properties = new JiraIntegrationProperties();
        properties.setEnabled(true);
        properties.setDefaultProject("ENG");
        JiraIntegrationStateService stateService = new JiraIntegrationStateService();
        JiraStartupHealthChecker checker = new JiraStartupHealthChecker(properties, Optional.empty(), stateService);

        checker.run(null);

        assertThat(stateService.getSnapshot().status()).isEqualTo(JiraIntegrationStatus.UNAVAILABLE);
        assertThat(output.getOut()).contains("Jira integration: enabled, startup check failed: jira.base-url must be set");
    }

    @Test
    void successfulCheckSetsAvailableSnapshot(CapturedOutput output) throws Exception {
        JiraIntegrationProperties properties = new JiraIntegrationProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost");
        properties.setToken("token");
        properties.setDefaultProject("ENG");
        properties.setAllowedProjects(java.util.List.of("ENG"));
        JiraClient client = mock(JiraClient.class);
        when(client.getCurrentUser()).thenReturn(new JiraCurrentUser("acc", "Leader User", "leader@example.com"));
        when(client.getProjects(java.util.Set.of("ENG"))).thenReturn(java.util.List.of());
        when(client.getIssueTypes("ENG")).thenReturn(java.util.List.of());
        JiraIntegrationStateService stateService = new JiraIntegrationStateService();
        JiraStartupHealthChecker checker = new JiraStartupHealthChecker(properties, Optional.of(client), stateService);

        checker.run(null);

        assertThat(stateService.getSnapshot().status()).isEqualTo(JiraIntegrationStatus.AVAILABLE);
        assertThat(output.getOut()).contains("Jira integration: enabled, startup check passed successfully");
    }

    @Test
    void refreshSnapshotRecoversAfterTemporaryFailure() throws Exception {
        JiraIntegrationProperties properties = new JiraIntegrationProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost");
        properties.setToken("token");
        properties.setDefaultProject("ENG");
        properties.setAllowedProjects(java.util.List.of("ENG"));
        JiraClient client = mock(JiraClient.class);
        doThrow(new IllegalStateException("Connection refused"))
                .doReturn(new JiraCurrentUser("acc", "Leader User", "leader@example.com"))
                .when(client).getCurrentUser();
        when(client.getProjects(java.util.Set.of("ENG"))).thenReturn(java.util.List.of());
        when(client.getIssueTypes("ENG")).thenReturn(java.util.List.of());
        JiraIntegrationStateService stateService = new JiraIntegrationStateService();
        JiraStartupHealthChecker checker = new JiraStartupHealthChecker(properties, Optional.of(client), stateService);

        checker.refreshSnapshot();
        assertThat(stateService.getSnapshot().status()).isEqualTo(JiraIntegrationStatus.UNAVAILABLE);

        checker.refreshSnapshot();
        assertThat(stateService.getSnapshot().status()).isEqualTo(JiraIntegrationStatus.AVAILABLE);
    }
}
