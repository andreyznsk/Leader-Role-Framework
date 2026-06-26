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
import ru.andreyz.mailagent.model.EmailProcessingStatus;
import ru.andreyz.mailagent.service.MailProcessingStateService;
import ru.andreyz.mailagent.service.MailRuntimeConfig;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Component
public class MailAgentJob {

    private static final Logger log = LoggerFactory.getLogger(MailAgentJob.class);

    private final MailClient mailClient;
    private final PromptBuilder promptBuilder;
    private final AgentClient agentClient;
    private final ActionExecutor actionExecutor;
    private final MailConfig.MailProperties mailProperties;
    private final MailConfig.PathProperties pathProperties;
    private final MailProcessingStateService processingStateService;
    private final ObjectMapper objectMapper;
    private final MailRuntimeConfigService runtimeConfigService;

    public MailAgentJob(
        MailClient mailClient,
        PromptBuilder promptBuilder,
        AgentClient agentClient,
        ActionExecutor actionExecutor,
        MailConfig.MailProperties mailProperties,
        MailConfig.PathProperties pathProperties,
        MailProcessingStateService processingStateService,
        ObjectMapper objectMapper,
        MailRuntimeConfigService runtimeConfigService
    ) {
        this.mailClient = mailClient;
        this.promptBuilder = promptBuilder;
        this.agentClient = agentClient;
        this.actionExecutor = actionExecutor;
        this.mailProperties = mailProperties;
        this.pathProperties = pathProperties;
        this.processingStateService = processingStateService;
        this.objectMapper = objectMapper;
        this.runtimeConfigService = runtimeConfigService;
    }

    @Scheduled(fixedDelayString = "#{${mail.poll-interval-seconds:60} * 1000}")
    public void poll() {
        MailRuntimeConfig runtime = runtimeConfigService.snapshot();
        if (!runtime.enabled()) {
            return;
        }
        int total = 0, errors = 0;
        int[] counts = {0, 0, 0, 0, 0, 0}; // REQUEST, DRAFT, NOISE, CAPTURE, NOTICE, NOTE

        List<ProcessedEmail> errorQueue = processingStateService.findErrorQueue();
        if (!errorQueue.isEmpty()) {
            log.info("Retrying {} email(s) from ERROR queue", errorQueue.size());
            for (ProcessedEmail failedEmail : errorQueue) {
                try {
                    actionExecutor.retry(failedEmail);
                    total++;
                    countByType(failedEmail.responseType(), counts);
                } catch (Exception e) {
                    errors++;
                    log.warn("Retry for email {} failed: {}", failedEmail.emailId(), e.getMessage());
                    finishPoll(total, errors, counts);
                    return;
                }
            }
        }

        int limit = mailProperties.getFetchLimit();
        List<String> excludeFolders = runtime.foldersExclude();

        List<String> folders;
        try {
            folders = mailClient.listFolders(excludeFolders);
            folders = filterIncludedFolders(folders, runtime.foldersInclude());
        } catch (Exception e) {
            log.error("Failed to list folders: {}", e.getMessage());
            log.debug("", e);
            runtimeConfigService.registerPollResult("Folder list failed: " + e.getMessage());
            return;
        }

        log.info("Poll started — scanning {} folder(s): {}", folders.size(), folders);

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
                if (processingStateService.findByEmailId(email.id()).isPresent()) {
                    log.debug("Email {} already has processing state, skipping", email.id());
                    continue;
                }
                total++;
                try {
                    AgentResponse resp = processEmail(email);
                    countByType(resp.type().name(), counts);
                    actionExecutor.execute(email, resp);
                } catch (Exception e) {
                    errors++;
                    log.warn("Email {} failed: {}, will retry next poll", email.id(), e.getMessage());
                    finishPoll(total, errors, counts);
                    return;
                }
            }
        }

        finishPoll(total, errors, counts);
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

    private void countByType(String responseType, int[] counts) {
        AgentResponseType type = AgentResponseType.valueOf(responseType);
        switch (type) {
            case REQUEST -> counts[0]++;
            case DRAFT -> counts[1]++;
            case NOISE -> counts[2]++;
            case CAPTURE -> counts[3]++;
            case NOTICE -> counts[4]++;
            case NOTE -> counts[5]++;
        }
    }

    private void finishPoll(int total, int errors, int[] counts) {
        String result = "%d processed (%d REQUEST, %d DRAFT, %d NOISE, %d CAPTURE, %d NOTICE, %d NOTE, %d errors)"
            .formatted(Math.max(0, total - errors), counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], errors);
        log.info("Poll finished: {}", result);
        runtimeConfigService.registerPollResult(result);
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

    private List<String> filterIncludedFolders(List<String> folders, List<String> includeFolders) {
        if (includeFolders == null || includeFolders.isEmpty()) {
            return folders;
        }
        List<String> normalizedIncludes = includeFolders.stream()
                .map(this::normalizeFolder)
                .toList();
        return folders.stream()
                .filter(folder -> normalizedIncludes.contains(normalizeFolder(folder)))
                .toList();
    }

    private String normalizeFolder(String folder) {
        return folder == null ? "" : folder.trim()
                .replace('\\', '/')
                .replaceAll("/+", "/")
                .toLowerCase(Locale.ROOT);
    }
}
