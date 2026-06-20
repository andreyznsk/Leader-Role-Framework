package ru.andreyz.mailagent.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.integration.MemoryServiceClient;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.Email;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ActionExecutorTest {

    @TempDir
    Path tempDir;

    ActionExecutor executor;
    MemoryServiceClient memoryServiceClient;

    @BeforeEach
    void setUp() {
        MailConfig.PathProperties paths = new MailConfig.PathProperties();
        paths.setInbox(tempDir.resolve("inbox").toString());
        paths.setProcessed(tempDir.resolve("processed").toString());
        paths.setDrafts(tempDir.resolve("drafts").toString());
        paths.setPlan(tempDir.resolve("plans/today.md").toString());
        paths.setRagInbox(tempDir.resolve("rag-inbox").toString());

        memoryServiceClient = mock(MemoryServiceClient.class);
        executor = new ActionExecutor(memoryServiceClient, paths, new NoticeDocumentWriter(paths));
    }

    @Test
    void noiseMovesEmailToProcessed() throws IOException {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-noise-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
            AgentResponseType.NOISE, emailId, "CI notification", null, null, null, null, null, null
        );

        executor.execute(email(emailId), response);

        assertFalse(Files.exists(inbox.resolve(emailId + ".json")));
        assertTrue(Files.exists(tempDir.resolve("processed/" + emailId + ".json")));
    }

    @Test
    void requestAppendsToplan() throws IOException {
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
            null
        );

        executor.execute(email(emailId), response);

        Path planFile = tempDir.resolve("plans/today.md");
        assertTrue(Files.exists(planFile));
        String content = Files.readString(planFile);
        assertTrue(content.contains("Review PR #42"));
    }

    @Test
    void captureCreatesMemoryCaptureAndMovesEmailToProcessed() throws IOException {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-capture-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
            AgentResponseType.CAPTURE, emailId,
            "Useful FYI",
            null, null, null, null, null,
            "К сведению: переезд на новый кластер с 1 июля"
        );

        executor.execute(email(emailId), response);

        verify(memoryServiceClient).createCapture("К сведению: переезд на новый кластер с 1 июля", "email");
        assertFalse(Files.exists(inbox.resolve(emailId + ".json")));
        assertTrue(Files.exists(tempDir.resolve("processed/" + emailId + ".json")));
    }

    @Test
    void noticeCreatesRagDocumentAndMovesEmailToProcessed() throws IOException {
        Path inbox = tempDir.resolve("inbox");
        Files.createDirectories(inbox);
        String emailId = "test-notice-001";
        Files.writeString(inbox.resolve(emailId + ".json"), "{}");

        AgentResponse response = new AgentResponse(
                AgentResponseType.NOTICE, emailId,
                "Новая release-практика команды", null, null, null, null, null, null
        );

        ActionExecutor.ActionResult result = executor.execute(
                email(emailId, "NOTICE: Новый порядок релизов", "architect@example.com", "Согласование через release calendar"),
                response
        );

        assertTrue(result.markAsRead());
        assertNotNull(result.outputPath());
        Path noticeFile = Path.of(result.outputPath());
        assertTrue(Files.exists(noticeFile));
        String content = Files.readString(noticeFile);
        assertTrue(content.contains("type: NOTICE"));
        assertTrue(content.contains("## Контекст"));
        assertTrue(content.contains("release calendar"));
        assertFalse(Files.exists(inbox.resolve(emailId + ".json")));
        assertTrue(Files.exists(tempDir.resolve("processed/" + emailId + ".json")));
    }

    @Test
    void sanitizeReplacesSpecialChars() {
        assertEquals("AAMk-123__abc", ActionExecutor.sanitize("AAMk-123::abc"));
        assertEquals("user_test.com", ActionExecutor.sanitize("user@test.com"));
    }

    private Email email(String emailId) {
        return email(emailId, "Subject", "sender@example.com", "Body");
    }

    private Email email(String emailId, String subject, String from, String body) {
        return new Email(emailId, subject, from, body, LocalDateTime.of(2026, 6, 20, 10, 15), "INBOX");
    }
}
