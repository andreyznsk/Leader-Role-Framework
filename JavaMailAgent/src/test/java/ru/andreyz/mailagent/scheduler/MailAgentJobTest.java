package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.ProcessedEmail;
import ru.andreyz.mailagent.service.MailControlAuditStore;
import ru.andreyz.mailagent.service.MailLinkingService;
import ru.andreyz.mailagent.service.MailProcessingStateService;
import ru.andreyz.mailagent.service.MailPromptTemplateService;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MailAgentJobTest {

    @TempDir
    Path tempDir;

    private MailClient mailClient;
    private PromptBuilder promptBuilder;
    private AgentClient agentClient;
    private ActionExecutor actionExecutor;
    private MailProcessingStateService processingStateService;
    private MailLinkingService mailLinkingService;
    private MailAgentJob job;

    @BeforeEach
    void setUp() {
        mailClient = mock(MailClient.class);
        promptBuilder = mock(PromptBuilder.class);
        agentClient = mock(AgentClient.class);
        actionExecutor = mock(ActionExecutor.class);
        processingStateService = mock(MailProcessingStateService.class);
        mailLinkingService = mock(MailLinkingService.class);
        when(mailLinkingService.apply(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        MailConfig.MailProperties mailProperties = new MailConfig.MailProperties();
        mailProperties.setFetchLimit(20);
        MailConfig.PathProperties pathProperties = new MailConfig.PathProperties();
        pathProperties.setInbox(tempDir.resolve("inbox").toString());
        pathProperties.setProcessed(tempDir.resolve("processed").toString());
        MailConfig.ImapProperties imap = new MailConfig.ImapProperties();
        MailConfig.EwsProperties ews = new MailConfig.EwsProperties();
        MailConfig.FolderProperties folders = new MailConfig.FolderProperties();
        MailControlAuditStore auditStore = mock(MailControlAuditStore.class);
        MailPromptTemplateService promptTemplateService = mock(MailPromptTemplateService.class);
        when(promptTemplateService.loadClassificationPrompt()).thenReturn("Prompt");
        when(promptTemplateService.loadLinkingPrompt()).thenReturn("Link prompt");
        MailRuntimeConfigService runtimeConfigService = new MailRuntimeConfigService(
            mailProperties, pathProperties, imap, ews, folders, auditStore, promptTemplateService
        );

        job = new MailAgentJob(
            mailClient,
            promptBuilder,
            agentClient,
            actionExecutor,
            mailProperties,
            pathProperties,
            processingStateService,
            new ObjectMapper().findAndRegisterModules(),
            runtimeConfigService,
            mailLinkingService
        );
    }

    @Test
    void pollRetriesErrorQueueBeforeScanningNewMail() throws Exception {
        ProcessedEmail failed = failedEmail("msg-001");
        when(processingStateService.findErrorQueue()).thenReturn(List.of(failed));
        when(mailClient.listFolders(any())).thenReturn(List.of("INBOX"));
        when(mailClient.listUnread("INBOX", 20)).thenReturn(List.of());

        job.poll();

        verify(actionExecutor).retry(failed);
        verify(agentClient, never()).complete(anyString());
    }

    @Test
    void pollStopsCurrentRunWhenRetryFails() throws Exception {
        ProcessedEmail failed = failedEmail("msg-002");
        when(processingStateService.findErrorQueue()).thenReturn(List.of(failed));
        doThrow(new IllegalStateException("still failing")).when(actionExecutor).retry(failed);

        job.poll();

        verify(actionExecutor).retry(failed);
        verify(mailClient, never()).listFolders(any());
        verify(agentClient, never()).complete(anyString());
    }

    @Test
    void pollCollectsSequentiallyAndProcessesCollectedEmails() throws Exception {
        Email first = email("msg-101", "INBOX");
        Email second = email("msg-102", "Team");
        when(processingStateService.findErrorQueue()).thenReturn(List.of());
        when(processingStateService.findByEmailId(anyString())).thenReturn(java.util.Optional.empty());
        when(mailClient.listFolders(any())).thenReturn(List.of("INBOX", "Team"));
        when(mailClient.listUnread("INBOX", 20)).thenReturn(List.of(first));
        when(mailClient.listUnread("Team", 20)).thenReturn(List.of(second));
        when(promptBuilder.build(any(Email.class))).thenReturn("prompt");
        when(agentClient.complete("prompt")).thenReturn("""
            {"type":"NOISE","emailId":"msg","sender":"sender@example.com","note":"ignored"}
            """);

        job.poll();

        verify(mailClient).listUnread("INBOX", 20);
        verify(mailClient).listUnread("Team", 20);
        verify(actionExecutor).execute(eq(first), any(), eq("prompt"), contains("NOISE"));
        verify(actionExecutor).execute(eq(second), any(), eq("prompt"), contains("NOISE"));
    }

    @Test
    void resolveProcessingParallelismUsesConfiguredValueWhenPresent() {
        MailConfig.MailProperties configured = new MailConfig.MailProperties();
        configured.setFetchLimit(20);
        configured.setProcessingParallelism(7);

        MailAgentJob configuredJob = new MailAgentJob(
            mailClient,
            promptBuilder,
            agentClient,
            actionExecutor,
            configured,
            new MailConfig.PathProperties(),
            processingStateService,
            new ObjectMapper().findAndRegisterModules(),
            new MailRuntimeConfigService(
                configured,
                new MailConfig.PathProperties(),
                new MailConfig.ImapProperties(),
                new MailConfig.EwsProperties(),
                new MailConfig.FolderProperties(),
                mock(MailControlAuditStore.class),
                promptTemplateService()
            ),
            mailLinkingService
        );

        assertThat(configuredJob.resolveProcessingParallelism()).isEqualTo(7);
    }

    @Test
    void resolveProcessingParallelismFallsBackToAvailableProcessorsWhenUnset() {
        assertThat(job.resolveProcessingParallelism())
            .isEqualTo(Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    @Test
    void pollStoresLongEmailIdsUsingShortenedFileName() throws Exception {
        String longEmailId = "ews-conv:" + "A".repeat(260);
        Email email = email(longEmailId, "INBOX");
        when(processingStateService.findErrorQueue()).thenReturn(List.of());
        when(processingStateService.findByEmailId(anyString())).thenReturn(java.util.Optional.empty());
        when(mailClient.listFolders(any())).thenReturn(List.of("INBOX"));
        when(mailClient.listUnread("INBOX", 20)).thenReturn(List.of(email));
        when(promptBuilder.build(any(Email.class))).thenReturn("prompt");
        when(agentClient.complete("prompt")).thenReturn("""
            {"type":"NOISE","emailId":"%s","sender":"sender@example.com","note":"ignored"}
            """.formatted(longEmailId));

        job.poll();

        verify(actionExecutor).execute(eq(email), any(AgentResponse.class), eq("prompt"), contains("NOISE"));
        assertThat(Files.exists(tempDir.resolve("inbox").resolve(ActionExecutor.storageFileName(longEmailId)))).isTrue();
    }

    private ProcessedEmail failedEmail(String emailId) {
        Email email = new Email(
            emailId,
            "Subject",
            "sender@example.com",
            "Body",
            LocalDateTime.of(2026, 6, 25, 10, 0),
            "INBOX"
        );
        return ProcessedEmail.newProcessing(email, "REQUEST", LocalDateTime.now())
            .withCheckpoint("REQUEST", ru.andreyz.mailagent.model.MailProcessingRoute.INTAKE_WRITE,
                "{\"responseType\":\"REQUEST\",\"intakeRequest\":{\"suggestedRoute\":\"TASK\"},\"sourceId\":\"" + emailId + "\",\"processedFolder\":null,\"moveEnabled\":true,\"markAsRead\":false}", null, null, LocalDateTime.now())
            .withError("memory down", LocalDateTime.now());
    }

    private Email email(String emailId, String folder) {
        return new Email(
            emailId,
            "Subject " + emailId,
            "sender@example.com",
            "Body",
            LocalDateTime.of(2026, 6, 25, 10, 0),
            folder
        );
    }

    private MailPromptTemplateService promptTemplateService() {
        MailPromptTemplateService promptTemplateService = mock(MailPromptTemplateService.class);
        when(promptTemplateService.loadClassificationPrompt()).thenReturn("Prompt");
        when(promptTemplateService.loadLinkingPrompt()).thenReturn("Link prompt");
        return promptTemplateService;
    }
}
