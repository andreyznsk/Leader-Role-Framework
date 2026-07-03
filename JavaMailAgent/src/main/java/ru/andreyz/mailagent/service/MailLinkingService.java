package ru.andreyz.mailagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.mailagent.integration.MemorySearchRequest;
import ru.andreyz.mailagent.integration.MemorySearchResponse;
import ru.andreyz.mailagent.integration.MemoryServiceClient;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.MailLinkingDecision;
import ru.andreyz.mailagent.model.MailLinkingDecisionType;
import ru.andreyz.mailagent.scheduler.MailLinkingPromptBuilder;

import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MailLinkingService {


    private static final List<String> SEARCH_LAYERS = List.of("TASK", "NOTE", "PEOPLE", "RISK", "INCIDENT", "KNOWLEDGE");

    private final MemoryServiceClient memoryServiceClient;
    private final MailLinkingPromptBuilder promptBuilder;
    private final AgentClient agentClient;
    private final ObjectMapper objectMapper;

    public MailLinkingService(MemoryServiceClient memoryServiceClient,
                              MailLinkingPromptBuilder promptBuilder,
                              AgentClient agentClient,
                              ObjectMapper objectMapper) {
        this.memoryServiceClient = memoryServiceClient;
        this.promptBuilder = promptBuilder;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
    }

    public AgentResponse apply(Email email, AgentResponse classification) {
        if (classification.type() != AgentResponseType.REQUEST) {
            return classification;
        }

        try {
            MemorySearchResponse searchResponse = memoryServiceClient.search(buildSearchRequest(email));
            MailLinkingDecision decision = parse(agentClient.complete(promptBuilder.build(email, classification, searchResponse)));
            return merge(classification, email, decision);
        } catch (Exception exception) {
            log.error("", exception);
            return classification;
        }
    }

    private MemorySearchRequest buildSearchRequest(Email email) {
        return new MemorySearchRequest(
                buildQuery(email),
                SEARCH_LAYERS,
                "QUICK",
                8
        );
    }

    private String buildQuery(Email email) {
        String normalizedSubject = normalizeSubject(email.subject());
        String body = email.body() == null ? "" : email.body().replaceAll("\\s+", " ").trim();
        if (body.length() > 240) {
            body = body.substring(0, 240);
        }
        String recipients = email.recipients() == null ? "" : email.recipients().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        return String.join(" ",
                normalizedSubject,
                safe(email.from()),
                recipients,
                safe(email.conversationId()),
                body
        ).trim();
    }

    private String normalizeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        String normalized = subject.trim();
        while (normalized.matches("(?i)^(re|fw|fwd):\\s+.*")) {
            normalized = normalized.replaceFirst("(?i)^(re|fw|fwd):\\s+", "").trim();
        }
        return normalized;
    }

    private MailLinkingDecision parse(String raw) throws Exception {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("(?s)```[a-z]*\\n?", "").replaceAll("```", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("No JSON object in mail linking decision");
        }
        return objectMapper.readValue(trimmed.substring(start, end + 1), MailLinkingDecision.class);
    }

    private AgentResponse merge(AgentResponse classification, Email email, MailLinkingDecision decision) {
        if (decision == null || decision.decision() == null) {
            return classification;
        }
        if (decision.decision() == MailLinkingDecisionType.IGNORE) {
            return new AgentResponse(
                    AgentResponseType.NOISE,
                    classification.emailId(),
                    decision.reason(),
                    null, null, null, null, null, null, null, null,
                    null, null, decision.confidence(), decision.reason(), null
            );
        }

        String title = safe(decision.title()).isBlank() ? classification.taskTitle() : decision.title().trim();
        String summary = safe(decision.summary()).isBlank() ? classification.note() : decision.summary().trim();
        return new AgentResponse(
                classification.type(),
                classification.emailId(),
                summary,
                classification.taskLine(),
                title,
                classification.priority(),
                classification.sender(),
                classification.draftPath(),
                classification.captureText(),
                classification.noteText(),
                classification.noteTitle(),
                decision.decision().name(),
                decision.targetTaskId(),
                decision.confidence(),
                decision.reason(),
                decision.proposedDescriptionAppend()
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
