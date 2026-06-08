package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.Email;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class MailAgentJob {

    private static final Logger log = LoggerFactory.getLogger(MailAgentJob.class);

    private final MailClient mailClient;
    private final PromptBuilder promptBuilder;
    private final ClaudeRunner claudeRunner;
    private final ActionExecutor actionExecutor;
    private final MailConfig.MailProperties mailProperties;
    private final MailConfig.PathProperties pathProperties;
    private final ObjectMapper objectMapper;

    public MailAgentJob(
        MailClient mailClient,
        PromptBuilder promptBuilder,
        ClaudeRunner claudeRunner,
        ActionExecutor actionExecutor,
        MailConfig.MailProperties mailProperties,
        MailConfig.PathProperties pathProperties,
        ObjectMapper objectMapper
    ) {
        this.mailClient = mailClient;
        this.promptBuilder = promptBuilder;
        this.claudeRunner = claudeRunner;
        this.actionExecutor = actionExecutor;
        this.mailProperties = mailProperties;
        this.pathProperties = pathProperties;
        this.objectMapper = objectMapper;
    }

    // fixedDelay — следующий тик только после завершения предыдущего
    @Scheduled(fixedDelayString = "${mail.poll-interval-seconds:60}000")
    public void poll() {
        int limit = mailProperties.getFetchLimit();
        log.info("Poll started, fetching up to {} unread emails", limit);

        List<Email> emails;
        try {
            emails = mailClient.listUnread(limit);
        } catch (Exception e) {
            log.error("Failed to fetch emails: {}", e.getMessage());
            return;
        }

        log.info("Found {} unread emails", emails.size());

        int errors = 0;
        int[] counts = {0, 0, 0}; // REQUEST, DRAFT, NOISE

        for (Email email : emails) {
            try {
                AgentResponse resp = processEmail(email);
                switch (resp.type()) {
                    case REQUEST -> counts[0]++;
                    case DRAFT   -> counts[1]++;
                    case NOISE   -> counts[2]++;
                }
            } catch (Exception e) {
                errors++;
                log.warn("Email {} failed: {}, will retry", email.id(), e.getMessage());
            }
        }

        log.info("Poll finished: {} processed ({} REQUEST, {} DRAFT, {} NOISE, {} errors)",
            emails.size() - errors, counts[0], counts[1], counts[2], errors);
    }

    private AgentResponse processEmail(Email email) throws Exception {
        log.info("Processing email {} from {}: \"{}\"", email.id(), email.from(), email.subject());
        saveToInbox(email);
        String prompt = promptBuilder.build(email);
        AgentResponse resp = claudeRunner.run(prompt);
        log.info("Classified as {}{}", resp.type(),
            resp.priority() != null ? ", priority " + resp.priority() : "");
        actionExecutor.execute(resp);
        mailClient.markAsRead(email.id());
        log.info("Email {} marked as read, moved to processed/", email.id());
        return resp;
    }

    private void saveToInbox(Email email) throws IOException {
        Path inboxDir = Path.of(pathProperties.getInbox());
        Files.createDirectories(inboxDir);
        Path file = inboxDir.resolve(ActionExecutor.sanitize(email.id()) + ".json");
        Files.writeString(file, objectMapper.writeValueAsString(email));
    }
}
