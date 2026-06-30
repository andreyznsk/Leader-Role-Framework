package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.service.MailProcessingStateService;
import ru.andreyz.mailagent.service.MailLinkingService;
import ru.andreyz.mailagent.service.MailRuntimeConfig;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;
import ru.andreyz.mailagent.model.ProcessedEmail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Slf4j
@RequiredArgsConstructor
@Component
public class MailAgentJob {

    private static final AgentResponseType[] RESPONSE_TYPES = AgentResponseType.values();

    private final MailClient mailClient;
    private final PromptBuilder promptBuilder;
    private final AgentClient agentClient;
    private final ActionExecutor actionExecutor;
    private final MailConfig.MailProperties mailProperties;
    private final MailConfig.PathProperties pathProperties;
    private final MailProcessingStateService processingStateService;
    private final ObjectMapper objectMapper;
    private final MailRuntimeConfigService runtimeConfigService;
    private final MailLinkingService mailLinkingService;

    @Scheduled(fixedDelayString = "#{${mail.poll-interval-seconds:60} * 1000}")
    public void poll() {
        MailRuntimeConfig runtime = runtimeConfigService.snapshot();
        if (!runtime.enabled()) {
            return;
        }
        int total = 0, errors = 0;
        int[] counts = emptyCounts();

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
                    log.error("Retry for email {} failed: {}", failedEmail.emailId(), e.getMessage());
                    log.error("", e);
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
            log.error("", e);
            runtimeConfigService.registerPollResult("Folder list failed: " + e.getMessage());
            return;
        }

        log.debug("Poll started — scanning {} folder(s): {}", folders.size(), folders);

        List<Email> emailsToProcess = new ArrayList<>();
        for (String folder : folders) {
            List<Email> emails;
            try {
                emails = mailClient.listUnread(folder, limit);
            } catch (Exception e) {
                log.error("Failed to fetch emails from folder {}: {}", folder, e.getMessage());
                log.error("", e);
                continue;
            }

            log.debug("Folder [{}]: {} unread email(s)", folder, emails.size());
            if (!emails.isEmpty()) {
                log.info("Folder [{}]: {} unread email(s)", folder, emails.size());
            }

            for (Email email : emails) {
                if (processingStateService.findByEmailId(email.id()).isPresent()) {
                    log.debug("Email {} already has processing state, skipping", email.id());
                    continue;
                }
                emailsToProcess.add(email);
            }
        }

        total += emailsToProcess.size();
        ProcessingBatchResult batchResult = processBatch(emailsToProcess);
        errors += batchResult.errors();
        mergeCounts(counts, batchResult.counts());

        finishPoll(total, errors, counts);
    }

    int resolveProcessingParallelism() {
        Integer configured = mailProperties.getProcessingParallelism();
        if (configured != null && configured > 0) {
            return configured;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private AgentResponse processEmail(Email email) throws Exception {
        log.info("Processing email {} from {}: \"{}\" [{}]",
            email.id(), email.from(), email.subject(), email.folder());
        saveToInbox(email);
        String prompt = promptBuilder.build(email);
        String raw = agentClient.complete(prompt);
        AgentResponse resp = mailLinkingService.apply(email, parseAgentResponse(raw));
        log.info("Classified as {}{}", resp.type(),
            resp.priority() != null ? ", priority " + resp.priority() : "");
        return resp;
    }

    private ProcessingBatchResult processBatch(List<Email> emails) {
        if (emails.isEmpty()) {
            return new ProcessingBatchResult(0, emptyCounts());
        }

        int parallelism = Math.min(emails.size(), resolveProcessingParallelism());
        log.info("Collected {} email(s) for processing with {} worker(s)", emails.size(), parallelism);

        try (ExecutorService executor = Executors.newFixedThreadPool(parallelism)) {
            List<Future<ProcessingOutcome>> futures = emails.stream()
                .map(email -> executor.submit(() -> processSingleEmail(email)))
                .toList();

            int errors = 0;
            int[] counts = emptyCounts();
            for (Future<ProcessingOutcome> future : futures) {
                try {
                    ProcessingOutcome outcome = future.get();
                    if (outcome.errorMessage() != null) {
                        errors++;
                        log.warn("Email {} failed: {}, will retry next poll", outcome.emailId(), outcome.errorMessage());
                        continue;
                    }
                    countByType(outcome.responseType(), counts);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors++;
                    log.warn("Mail processing interrupted: {}", e.getMessage());
                    log.error("", e);
                } catch (ExecutionException e) {
                    errors++;
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.warn("Unexpected mail processing failure: {}", cause.getMessage());
                    log.debug("", cause);
                    log.error("", e);
                }
            }
            return new ProcessingBatchResult(errors, counts);
        }
    }

    private ProcessingOutcome processSingleEmail(Email email) {
        try {
            AgentResponse resp = processEmail(email);
            actionExecutor.execute(email, resp);
            return new ProcessingOutcome(email.id(), resp.type().name(), null);
        } catch (Exception e) {
            log.error("", e);
            return new ProcessingOutcome(email.id(), null, e.getMessage());
        }
    }

    private void countByType(String responseType, int[] counts) {
        AgentResponseType type = AgentResponseType.valueOf(responseType);
        counts[type.ordinal()]++;
    }

    private void mergeCounts(int[] target, int[] source) {
        for (int i = 0; i < target.length; i++) {
            target[i] += source[i];
        }
    }

    private void finishPoll(int total, int errors, int[] counts) {
        String result = "%d processed (%d REQUEST, %d DRAFT, %d NOISE, %d CAPTURE, %d NOTICE, %d NOTE, %d errors)"
            .formatted(
                Math.max(0, total - errors),
                countFor(AgentResponseType.REQUEST, counts),
                countFor(AgentResponseType.DRAFT, counts),
                countFor(AgentResponseType.NOISE, counts),
                countFor(AgentResponseType.CAPTURE, counts),
                countFor(AgentResponseType.NOTICE, counts),
                countFor(AgentResponseType.NOTE, counts),
                errors
            );
        log.debug("Poll finished: {}", result);
        runtimeConfigService.registerPollResult(result);
    }

    private int[] emptyCounts() {
        return new int[RESPONSE_TYPES.length];
    }

    private int countFor(AgentResponseType type, int[] counts) {
        return counts[type.ordinal()];
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

    private record ProcessingOutcome(String emailId, String responseType, String errorMessage) {
    }

    private record ProcessingBatchResult(int errors, int[] counts) {
    }
}
