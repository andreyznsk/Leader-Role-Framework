package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.ProcessedEmail;
import ru.andreyz.mailagent.service.MailControlAuditStore;
import ru.andreyz.mailagent.service.MailProcessingStateService;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;

class MailAgentJobTest {

    private MailClient mailClient;
    private PromptBuilder promptBuilder;
    private AgentClient agentClient;
    private ActionExecutor actionExecutor;
    private MailProcessingStateService processingStateService;
    private MailAgentJob job;

    @BeforeEach
    void setUp() {
        mailClient = mock(MailClient.class);
        promptBuilder = mock(PromptBuilder.class);
        agentClient = mock(AgentClient.class);
        actionExecutor = mock(ActionExecutor.class);
        processingStateService = mock(MailProcessingStateService.class);

        MailConfig.MailProperties mailProperties = new MailConfig.MailProperties();
        mailProperties.setFetchLimit(20);
        MailConfig.PathProperties pathProperties = new MailConfig.PathProperties();
        MailConfig.ImapProperties imap = new MailConfig.ImapProperties();
        MailConfig.EwsProperties ews = new MailConfig.EwsProperties();
        MailConfig.FolderProperties folders = new MailConfig.FolderProperties();
        MailControlAuditStore auditStore = mock(MailControlAuditStore.class);
        MailRuntimeConfigService runtimeConfigService = new MailRuntimeConfigService(
            mailProperties, pathProperties, imap, ews, folders, auditStore
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
            runtimeConfigService
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
            .withCheckpoint("REQUEST", ru.andreyz.mailagent.model.MailProcessingRoute.MEMORY_PENDING_TASK,
                "{\"taskLine\":\"- [ ] test\"}", null, null, LocalDateTime.now())
            .withError("memory down", LocalDateTime.now());
    }
}
