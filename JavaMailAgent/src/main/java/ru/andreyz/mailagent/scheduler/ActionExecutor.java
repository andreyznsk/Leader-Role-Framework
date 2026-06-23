package ru.andreyz.mailagent.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.integration.MemoryServiceClient;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.PendingTaskRequest;
import ru.andreyz.mailagent.service.MailRuntimeConfig;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final MemoryServiceClient memoryServiceClient;
    private final MailConfig.PathProperties pathProperties;
    private final NoticeDocumentWriter noticeDocumentWriter;
    private final MailRuntimeConfigService runtimeConfigService;

    public ActionExecutor(MemoryServiceClient memoryServiceClient,
                          MailConfig.PathProperties pathProperties,
                          NoticeDocumentWriter noticeDocumentWriter,
                          MailRuntimeConfigService runtimeConfigService) {
        this.memoryServiceClient = memoryServiceClient;
        this.pathProperties = pathProperties;
        this.noticeDocumentWriter = noticeDocumentWriter;
        this.runtimeConfigService = runtimeConfigService;
    }

    public ActionResult execute(Email email, AgentResponse response) throws IOException {
        MailRuntimeConfig runtime = runtimeConfigService.snapshot();
        Path inbox = resolveInbox(response.emailId());
        Path processed = resolveProcessed(response.emailId(), runtime.processedFolder());

        switch (response.type()) {
            case REQUEST -> {
                Path planFile = Path.of(pathProperties.getPlan());
                Files.createDirectories(planFile.getParent());
                Files.writeString(planFile, "\n" + response.taskLine(),
                    StandardOpenOption.APPEND, StandardOpenOption.CREATE);

                memoryServiceClient.createPendingTask(new PendingTaskRequest(
                    response.taskTitle(),
                    response.note(),
                    response.emailId(),
                    response.sender(),
                    response.priority()
                ));

                moveToProcessedIfEnabled(inbox, processed, runtime.moveProcessedMail());
                log.info("REQUEST → plan + memory-service: {}", response.taskLine());
                return new ActionResult(null, false);
            }
            case DRAFT -> {
                moveToProcessedIfEnabled(inbox, processed, runtime.moveProcessedMail());
                log.info("DRAFT → {}: {}", response.draftPath(), response.note());
                return new ActionResult(null, false);
            }
            case NOISE -> {
                moveToProcessedIfEnabled(inbox, processed, runtime.moveProcessedMail());
                log.info("NOISE: {}", response.note());
                return new ActionResult(null, runtime.markNoiseAsRead());
            }
            case CAPTURE -> {
                String text = response.captureText() != null && !response.captureText().isBlank()
                    ? response.captureText()
                    : (response.note() != null ? response.note() : "");
                memoryServiceClient.createCapture(text, "email");
                moveToProcessedIfEnabled(inbox, processed, runtime.moveProcessedMail());
                log.info("CAPTURE → memory-service: {}", text);
                return new ActionResult(null, false);
            }
            case NOTICE -> {
                Path noticePath = noticeDocumentWriter.write(email, response.note());
                moveToProcessedIfEnabled(inbox, processed, runtime.moveProcessedMail());
                log.info("NOTICE → rag-inbox: {}", noticePath);
                return new ActionResult(noticePath.toString(), true);
            }
        }
        throw new IllegalStateException("Unsupported response type: " + response.type());
    }

    private Path resolveInbox(String emailId) {
        return Path.of(pathProperties.getInbox(), sanitize(emailId) + ".json");
    }

    private Path resolveProcessed(String emailId, String processedFolder) {
        String target = processedFolder != null && !processedFolder.isBlank()
                ? processedFolder
                : pathProperties.getProcessed();
        return Path.of(target, sanitize(emailId) + ".json");
    }

    private void moveToProcessedIfEnabled(Path inbox, Path processed, boolean enabled) throws IOException {
        if (enabled && Files.exists(inbox)) {
            Files.createDirectories(processed.getParent());
            Files.move(inbox, processed, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sanitize(String emailId) {
        return emailId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public record ActionResult(String outputPath, boolean markAsRead) {}
}
