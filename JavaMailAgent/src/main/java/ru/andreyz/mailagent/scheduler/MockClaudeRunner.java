package ru.andreyz.mailagent.scheduler;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "mock.agent", havingValue = "true")
public class MockClaudeRunner implements ClaudeRunner {

    private static final Logger log = LoggerFactory.getLogger(MockClaudeRunner.class);

    // Matches "emailId": "actual-id" in the PromptBuilder JSON example
    private static final Pattern EMAIL_ID_PATTERN = Pattern.compile("\"emailId\":\\s*\"([^\"]+)\"");

    @PostConstruct
    public void init() {
        log.warn("⚠️  MOCK ClaudeRunner is active — real Claude agent will NOT be called");
        log.warn("⚠️  Set mock.agent=false to use real Claude agent");
    }

    @Override
    public AgentResponse run(String prompt) {
        String emailSection = extractEmailSection(prompt);
        AgentResponseType type = detectType(emailSection);
        String priority        = detectPriority(emailSection);
        String emailId         = extractEmailId(prompt);

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
                null,
                null
            );
            case DRAFT -> new AgentResponse(
                type,
                emailId,
                "Mock: classified as DRAFT by keyword",
                null, null, null, null,
                "drafts/" + emailId + "-draft.md",
                null
            );
            case NOISE -> new AgentResponse(
                type,
                emailId,
                "Mock: classified as NOISE (default or keyword)",
                null, null, null, null, null, null
            );
            case CAPTURE -> new AgentResponse(
                type,
                emailId,
                "Mock: classified as CAPTURE by keyword",
                null, null, null, null, null,
                "Mock capture: " + firstNonBlankLine(emailSection)
            );
        };
    }

    // Extract only the email header+body section, before the JSON template instructions.
    // Fixes: PromptBuilder always includes keywords REQUEST/DRAFT/NOISE/CAPTURE/CRITICAL/HIGH
    // in the template text, which polluted the full-prompt keyword search.
    private String extractEmailSection(String prompt) {
        int idx = prompt.indexOf("Верни JSON");
        return idx >= 0 ? prompt.substring(0, idx) : prompt;
    }

    private AgentResponseType detectType(String emailSection) {
        String upper = emailSection.toUpperCase();
        // DRAFT: requests for a response letter / draft
        if (upper.contains("ОТВЕТН") || upper.contains("ЧЕРНОВИК"))
            return AgentResponseType.DRAFT;
        // NOISE: CI/CD notifications and automated reports
        if (upper.contains("BUILD") || upper.contains("PIPELINE") ||
            upper.contains("PASSED") || upper.contains("SUCCESS") || upper.contains("DURATION:"))
            return AgentResponseType.NOISE;
        // CAPTURE: useful information without an immediate action
        if (upper.contains("FYI") || upper.contains("К СВЕДЕНИЮ") ||
            upper.contains("ИНФО:") || upper.contains("НАПОМИНАНИЕ:") ||
            upper.contains("CAPTURE"))
            return AgentResponseType.CAPTURE;
        return AgentResponseType.REQUEST;
    }

    private String detectPriority(String emailSection) {
        String upper = emailSection.toUpperCase();
        if (upper.contains("СРОЧНО") || upper.contains("ASAP") || upper.contains("P1 ИНЦИДЕНТ"))
            return "CRITICAL";
        if (upper.contains("ДО ЗАВТРА") || upper.contains("ВАЖНО") || upper.contains("ДЕДЛАЙН"))
            return "HIGH";
        if (upper.contains("КОГДА БУДЕТ ВРЕМЯ"))
            return "LOW";
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

    // Fixes: PromptBuilder formats emailId as `"emailId": "actualId"` with leading spaces/quotes,
    // so startsWith-based extraction always failed and returned a mock-id-<timestamp> fallback.
    private String extractEmailId(String prompt) {
        Matcher m = EMAIL_ID_PATTERN.matcher(prompt);
        return m.find() ? m.group(1) : "mock-id-" + System.currentTimeMillis();
    }

    private String extractSender(String prompt) {
        for (String line : prompt.lines().toList()) {
            if (line.toLowerCase().startsWith("от:")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) return parts[1].trim();
            }
        }
        return "unknown@mock.local";
    }

    private String firstNonBlankLine(String text) {
        return text.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse("(no text)");
    }
}
