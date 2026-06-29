package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.integration.MemoryServiceClient;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.MailProcessingRoute;
import ru.andreyz.mailagent.model.PendingTaskRequest;
import ru.andreyz.mailagent.model.ProcessedEmail;
import ru.andreyz.mailagent.service.MailControlAuditStore;
import ru.andreyz.mailagent.service.MailProcessingStateService;
import ru.andreyz.mailagent.service.MailPromptTemplateService;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;
import ru.andreyz.mailagent.repository.ProcessedEmailRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActionExecutorTest {

    @TempDir
    Path tempDir;

    ActionExecutor executor;
    MemoryServiceClient memoryServiceClient;
    MailClient mailClient;
    MailRuntimeConfigService runtimeConfigService;
    ProcessedEmailRepository processedEmailRepository;
    MailProcessingStateService processingStateService;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MailConfig.PathProperties paths = new MailConfig.PathProperties();
        paths.setInbox(tempDir.resolve("inbox").toString());
        paths.setProcessed(tempDir.resolve("processed").toString());
        paths.setDrafts(tempDir.resolve("drafts").toString());
        paths.setPlan(tempDir.resolve("plans/today.md").toString());
        paths.setRagInbox(tempDir.resolve("rag-inbox").toString());

        memoryServiceClient = mock(MemoryServiceClient.class);
        mailClient = mock(MailClient.class);
        MailConfig.MailProperties mail = new MailConfig.MailProperties();
        MailConfig.ImapProperties imap = new MailConfig.ImapProperties();
        MailConfig.EwsProperties ews = new MailConfig.EwsProperties();
        MailConfig.FolderProperties folders = new MailConfig.FolderProperties();
        MailControlAuditStore auditStore = mock(MailControlAuditStore.class);
        MailPromptTemplateService promptTemplateService = mock(MailPromptTemplateService.class);
        when(promptTemplateService.loadClassificationPrompt()).thenReturn("Prompt");
        when(promptTemplateService.saveClassificationPrompt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        runtimeConfigService = new MailRuntimeConfigService(mail, paths, imap, ews, folders, auditStore, promptTemplateService);
        processedEmailRepository = mock(ProcessedEmailRepository.class);
        when(processedEmailRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(processedEmailRepository.findByEmailId(any())).thenReturn(Optional.empty());
        when(processedEmailRepository.findByStatusOrderByLastAttemptAtAscCreatedAtAsc(any())).thenReturn(List.of());
        objectMapper = new ObjectMapper().findAndRegisterModules();
        processingStateService = new MailProcessingStateService(processedEmailRepository, objectMapper);
        executor = new ActionExecutor(
            memoryServiceClient,
            mailClient,
            paths,
            new NoticeDocumentWriter(paths),
            runtimeConfigService,
            processingStateService,
            objectMapper
        );
    }

    @Test
    void noiseMovesEmailToProcessed() throws Exception {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-noise-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
            AgentResponseType.NOISE, emailId, "CI notification", null, null, null, null, null, null, null, null
        );

        executor.execute(email(emailId), response);

        assertFalse(Files.exists(inbox.resolve(emailId + ".json")));
        assertTrue(Files.exists(tempDir.resolve("processed/" + emailId + ".json")));
    }

    @Test
    void requestAppendsToplan() throws Exception {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-request-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
            AgentResponseType.REQUEST, emailId,
            "requires review",
            "- [ ] [HIGH] Review PR #42 — от ivanov@test.com",
            "Review PR #42",
            "HIGH",
            "ivanov@test.com",
            null,
            null,
            null,
            null
        );

        executor.execute(email(emailId), response);

        Path planFile = tempDir.resolve("plans/today.md");
        assertTrue(Files.exists(planFile));
        String content = Files.readString(planFile);
        assertTrue(content.contains("Review PR #42"));
    }

    @Test
    void requestSendsAgentSummaryAndRawEmailToPendingTask() throws Exception {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-request-description-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
            AgentResponseType.REQUEST, emailId,
            "Нужно проверить обновление пайплайна и дать ответ.",
            "- [ ] [HIGH] Проверить обновление пайплайна — от ivanov@test.com",
            "Проверить обновление пайплайна",
            "HIGH",
            "ivanov@test.com",
            null,
            null,
            null,
            null
        );

        Email email = email(
            emailId,
            "Pipeline update",
            "ivanov@test.com",
            "Коллеги,\nнужно проверить новый pipeline до пятницы.\nСпасибо."
        );

        executor.execute(email, response);

        ArgumentCaptor<PendingTaskRequest> requestCaptor = ArgumentCaptor.forClass(PendingTaskRequest.class);
        verify(memoryServiceClient).createPendingTask(requestCaptor.capture());
        assertEquals(
            """
            Нужно проверить обновление пайплайна и дать ответ.

            ---

            ## Сырой текст письма
            Тема: Pipeline update
            От: ivanov@test.com

            Коллеги,
            нужно проверить новый pipeline до пятницы.
            Спасибо.
            """.strip(),
            requestCaptor.getValue().description()
        );
    }

    @Test
    void requestRetryFromMemoryCheckpointDoesNotDuplicatePlanLine() throws Exception {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-request-retry-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
            AgentResponseType.REQUEST, emailId,
            "requires review",
            "- [ ] [HIGH] Review PR #42 — от ivanov@test.com",
            "Review PR #42",
            "HIGH",
            "ivanov@test.com",
            null,
            null,
            null,
            null
        );

        doThrow(new IllegalStateException("memory down"))
            .doNothing()
            .when(memoryServiceClient)
            .createPendingTask(any());

        assertThrows(IllegalStateException.class, () -> executor.execute(email(emailId), response));

        ArgumentCaptor<ProcessedEmail> emailCaptor = ArgumentCaptor.forClass(ProcessedEmail.class);
        verify(processedEmailRepository, atLeastOnce()).save(emailCaptor.capture());
        ProcessedEmail errorRecord = emailCaptor.getAllValues().get(emailCaptor.getAllValues().size() - 1);
        assertEquals("REQUEST", errorRecord.responseType());
        assertEquals(MailProcessingRoute.MEMORY_PENDING_TASK, errorRecord.failedRoute());
        assertEquals("ERROR", errorRecord.status().name());

        executor.retry(errorRecord);

        Path planFile = tempDir.resolve("plans/today.md");
        String content = Files.readString(planFile);
        assertEquals(1, content.lines().filter(line -> line.contains("Review PR #42")).count());
        verify(memoryServiceClient, times(2)).createPendingTask(any());
    }

    @Test
    void captureCreatesMemoryCaptureAndMovesEmailToProcessed() throws Exception {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-capture-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
            AgentResponseType.CAPTURE, emailId,
            "Useful FYI",
            null, null, null, null, null,
            "К сведению: переезд на новый кластер с 1 июля",
            null,
            null
        );

        executor.execute(email(emailId), response);

        verify(memoryServiceClient).createCapture("К сведению: переезд на новый кластер с 1 июля", "email", emailId);
        assertFalse(Files.exists(inbox.resolve(emailId + ".json")));
        assertTrue(Files.exists(tempDir.resolve("processed/" + emailId + ".json")));
    }

    @Test
    void noticeCreatesRagDocumentAndMovesEmailToProcessed() throws Exception {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-notice-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
                AgentResponseType.NOTICE, emailId,
                "Новая release-практика команды", null, null, null, null, null, null, null, null
        );

        executor.execute(
            email(emailId, "NOTICE: Новый порядок релизов", "architect@example.com", "Согласование через release calendar"),
            response
        );

        ArgumentCaptor<ProcessedEmail> emailCaptor = ArgumentCaptor.forClass(ProcessedEmail.class);
        verify(processedEmailRepository, atLeastOnce()).save(emailCaptor.capture());
        ProcessedEmail processed = emailCaptor.getAllValues().get(emailCaptor.getAllValues().size() - 1);
        assertNotNull(processed.outputPath());
        Path noticeFile = Path.of(processed.outputPath());
        assertTrue(Files.exists(noticeFile));
        String content = Files.readString(noticeFile);
        assertTrue(content.contains("type: NOTICE"));
        assertTrue(content.contains("## Контекст"));
        assertTrue(content.contains("release calendar"));
        assertFalse(Files.exists(inbox.resolve(emailId + ".json")));
        assertTrue(Files.exists(tempDir.resolve("processed/" + emailId + ".json")));
        verify(mailClient).markAsRead(emailId, "INBOX");
    }

    @Test
    void sanitizeReplacesSpecialChars() {
        assertEquals("AAMk-123__abc", ActionExecutor.sanitize("AAMk-123::abc"));
        assertEquals("user_test.com", ActionExecutor.sanitize("user@test.com"));
    }

    @Test
    void noteCreatesMemoryNoteAndMovesEmailToProcessed() throws Exception {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-note-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
            AgentResponseType.NOTE, emailId,
            "Стоит сохранить в заметки",
            null, null, null, null, null, null,
            "Посмотреть практики blue-green rollout у соседней команды.",
            "Blue-green rollout"
        );

        executor.execute(email(emailId), response);

        verify(memoryServiceClient).createNote(
            "Blue-green rollout",
            "Посмотреть практики blue-green rollout у соседней команды.",
            "mail,email",
            "email"
        );
        assertFalse(Files.exists(inbox.resolve(emailId + ".json")));
        assertTrue(Files.exists(tempDir.resolve("processed/" + emailId + ".json")));
        verify(mailClient).markAsRead(emailId, "INBOX");
    }

    @Test
    void markAsReadDryRunLeavesNoticeUnread() throws Exception {
        runtimeConfigService.apply(Map.of("markAsReadEnabled", "false"));
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-notice-dry-run-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
            AgentResponseType.NOTICE, emailId,
            "Новая release-практика команды", null, null, null, null, null, null, null, null
        );

        executor.execute(email(emailId), response);

        verify(mailClient, never()).markAsRead(anyString(), anyString());
        assertTrue(Files.exists(tempDir.resolve("processed/" + emailId + ".json")));
    }

    private Email email(String emailId) {
        return email(emailId, "Subject", "sender@example.com", "Body");
    }

    private Email email(String emailId, String subject, String from, String body) {
        return new Email(emailId, subject, from, body, LocalDateTime.of(2026, 6, 20, 10, 15), "INBOX");
    }
}
