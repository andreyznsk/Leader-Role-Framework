package ru.andreyz.mailagent.scheduler;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;

@Component
@ConditionalOnProperty(name = "mock.agent", havingValue = "true")
public class MockClaudeRunner implements ClaudeRunner {

    private static final Logger log = LoggerFactory.getLogger(MockClaudeRunner.class);

    @PostConstruct
    public void init() {
        log.warn("⚠️  MOCK ClaudeRunner is active — real Claude agent will NOT be called");
        log.warn("⚠️  Set mock.agent=false to use real Claude agent");
    }

    @Override
    public AgentResponse run(String prompt) {
        AgentResponseType type = detectType(prompt);
        String priority       = detectPriority(prompt);
        String emailId        = extractEmailId(prompt);

        log.debug("MockClaudeRunner: detected type={}, priority={}", type, priority);

        return switch (type) {
            case REQUEST -> new AgentResponse(
                type,
                emailId,
                "Mock: classified as REQUEST by keyword",
                "- [ ] [" + priorityLabel(priority) + "] Mock task from email " + emailId,
                "Mock task title",
                priority,
                extractSender(prompt),
                null
            );
            case DRAFT -> new AgentResponse(
                type,
                emailId,
                "Mock: classified as DRAFT by keyword",
                null, null, null, null,
                "drafts/" + emailId + "-draft.md"
            );
            case NOISE -> new AgentResponse(
                type,
                emailId,
                "Mock: classified as NOISE (default or keyword)",
                null, null, null, null, null
            );
        };
    }

    private AgentResponseType detectType(String prompt) {
        String upper = prompt.toUpperCase();
        if (upper.contains("REQUEST")) return AgentResponseType.REQUEST;
        if (upper.contains("DRAFT"))   return AgentResponseType.DRAFT;
        return AgentResponseType.NOISE;
    }

    private String detectPriority(String prompt) {
        String upper = prompt.toUpperCase();
        if (upper.contains("CRITICAL")) return "CRITICAL";
        if (upper.contains("HIGH"))     return "HIGH";
        if (upper.contains("LOW"))      return "LOW";
        return "NORMAL";
    }

    private String priorityLabel(String priority) {
        return switch (priority) {
            case "CRITICAL" -> "P0";
            case "HIGH"     -> "P1";
            case "LOW"      -> "P3";
            default         -> "P2";
        };
    }

    private String extractEmailId(String prompt) {
        return extractField(prompt, "emailId", "mock-id-" + System.currentTimeMillis());
    }

    private String extractSender(String prompt) {
        return extractField(prompt, "От", "unknown@mock.local");
    }

    private String extractField(String prompt, String field, String defaultValue) {
        for (String line : prompt.lines().toList()) {
            if (line.toLowerCase().startsWith(field.toLowerCase())) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) return parts[1].trim();
            }
        }
        return defaultValue;
    }
}
