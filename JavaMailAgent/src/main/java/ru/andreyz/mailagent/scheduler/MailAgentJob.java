package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.ProcessedEmail;
import ru.andreyz.mailagent.repository.ProcessedEmailRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class MailAgentJob {

    private static final Logger log = LoggerFactory.getLogger(MailAgentJob.class);

    private final MailClient mailClient;
    private final PromptBuilder promptBuilder;
    private final AgentClient agentClient;
    private final ActionExecutor actionExecutor;
    private final MailConfig.MailProperties mailProperties;
    private final MailConfig.PathProperties pathProperties;
    private final MailConfig.FolderProperties folderProperties;
    private final ProcessedEmailRepository processedEmailRepository;
    private final ObjectMapper objectMapper;

    public MailAgentJob(
        MailClient mailClient,
        PromptBuilder promptBuilder,
        AgentClient agentClient,
        ActionExecutor actionExecutor,
        MailConfig.MailProperties mailProperties,
        MailConfig.PathProperties pathProperties,
        MailConfig.FolderProperties folderProperties,
        ProcessedEmailRepository processedEmailRepository,
        ObjectMapper objectMapper
    ) {
        this.mailClient = mailClient;
        this.promptBuilder = promptBuilder;
        this.agentClient = agentClient;
        this.actionExecutor = actionExecutor;
        this.mailProperties = mailProperties;
        this.pathProperties = pathProperties;
        this.folderProperties = folderProperties;
        this.processedEmailRepository = processedEmailRepository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${mail.poll-interval-seconds:60}000")
    public void poll() {
        int limit = mailProperties.getFetchLimit();
        List<String> excludeFolders = folderProperties.getExclude();

        List<String> folders;
        try {
            folders = mailClient.listFolders(excludeFolders);
        } catch (Exception e) {
            log.error("Failed to list folders: {}", e.getMessage());
            return;
        }

        log.info("Poll started — scanning {} folder(s): {}", folders.size(), folders);

        int total = 0, errors = 0;
        int[] counts = {0, 0, 0, 0, 0}; // REQUEST, DRAFT, NOISE, CAPTURE, NOTICE

        for (String folder : folders) {
            List<Email> emails;
            try {
                emails = mailClient.listUnread(folder, limit);
            } catch (Exception e) {
                log.error("Failed to fetch emails from folder {}: {}", folder, e.getMessage());
                continue;
            }

            log.info("Folder [{}]: {} unread email(s)", folder, emails.size());

            for (Email email : emails) {
                if (processedEmailRepository.existsByEmailId(email.id())) {
                    log.debug("Email {} already processed, skipping", email.id());
                    continue;
                }
                total++;
                try {
                    AgentResponse resp = processEmail(email);
                    switch (resp.type()) {
                        case REQUEST -> counts[0]++;
                        case DRAFT   -> counts[1]++;
                        case NOISE   -> counts[2]++;
                        case CAPTURE -> counts[3]++;
                        case NOTICE  -> counts[4]++;
                    }
                    ActionExecutor.ActionResult actionResult = actionExecutor.execute(email, resp);
                    if (actionResult.markAsRead()) {
                        mailClient.markAsRead(email.id(), email.folder());
                        log.info("Email {} marked as read ({})", email.id(), resp.type());
                    }
                    processedEmailRepository.save(ProcessedEmail.of(email, resp.type().name(), actionResult.outputPath()));
                } catch (Exception e) {
                    errors++;
                    log.warn("Email {} failed: {}, will retry next poll", email.id(), e.getMessage());
                }
            }
        }

        log.info("Poll finished: {} processed ({} REQUEST, {} DRAFT, {} NOISE, {} CAPTURE, {} NOTICE, {} errors)",
            total - errors, counts[0], counts[1], counts[2], counts[3], counts[4], errors);
    }

    private AgentResponse processEmail(Email email) throws Exception {
        log.info("Processing email {} from {}: \"{}\" [{}]",
            email.id(), email.from(), email.subject(), email.folder());
        saveToInbox(email);
        String prompt = promptBuilder.build(email);
        String raw = agentClient.complete(prompt);
        AgentResponse resp = parseAgentResponse(raw);
        log.info("Classified as {}{}", resp.type(),
            resp.priority() != null ? ", priority " + resp.priority() : "");
        return resp;
    }

    private AgentResponse parseAgentResponse(String raw) throws IOException {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("(?s)```[a-z]*\\n?", "").replaceAll("```", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("No JSON object in agent response: " + trimmed);
        }
        return objectMapper.readValue(trimmed.substring(start, end + 1), AgentResponse.class);
    }

    private void saveToInbox(Email email) throws IOException {
        Path inboxDir = Path.of(pathProperties.getInbox());
        Files.createDirectories(inboxDir);
        Path file = inboxDir.resolve(ActionExecutor.sanitize(email.id()) + ".json");
        Files.writeString(file, objectMapper.writeValueAsString(email));
    }
}
