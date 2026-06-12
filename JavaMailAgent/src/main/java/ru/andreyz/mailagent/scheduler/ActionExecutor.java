package ru.andreyz.mailagent.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.integration.MemoryServiceClient;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.PendingTaskRequest;

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

    public ActionExecutor(MemoryServiceClient memoryServiceClient, MailConfig.PathProperties pathProperties) {
        this.memoryServiceClient = memoryServiceClient;
        this.pathProperties = pathProperties;
    }

    public void execute(AgentResponse response) throws IOException {
        Path inbox = resolveInbox(response.emailId());
        Path processed = resolveProcessed(response.emailId());

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

                moveToProcessed(inbox, processed);
                log.info("REQUEST → plan + memory-service: {}", response.taskLine());
            }
            case DRAFT -> {
                moveToProcessed(inbox, processed);
                log.info("DRAFT → {}: {}", response.draftPath(), response.note());
            }
            case NOISE -> {
                moveToProcessed(inbox, processed);
                log.info("NOISE: {}", response.note());
            }
            case CAPTURE -> {
                String text = response.captureText() != null && !response.captureText().isBlank()
                    ? response.captureText()
                    : (response.note() != null ? response.note() : "");
                memoryServiceClient.createCapture(text, "email");
                moveToProcessed(inbox, processed);
                log.info("CAPTURE → memory-service: {}", text);
            }
        }
    }

    private Path resolveInbox(String emailId) {
        return Path.of(pathProperties.getInbox(), sanitize(emailId) + ".json");
    }

    private Path resolveProcessed(String emailId) {
        return Path.of(pathProperties.getProcessed(), sanitize(emailId) + ".json");
    }

    private void moveToProcessed(Path inbox, Path processed) throws IOException {
        if (Files.exists(inbox)) {
            Files.createDirectories(processed.getParent());
            Files.move(inbox, processed, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sanitize(String emailId) {
        return emailId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
