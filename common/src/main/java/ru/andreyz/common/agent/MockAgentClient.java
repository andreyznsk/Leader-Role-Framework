package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MockAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(MockAgentClient.class);

    private static final Pattern EMAIL_ID_PATTERN = Pattern.compile("\"emailId\":\\s*\"([^\"]+)\"");
    private static final Pattern TASK_URL_PATTERN = Pattern.compile("/ui/tasks/(\\d+)/edit");
    private static final Pattern CAPTURE_FILE_PATTERN = Pattern.compile(
            "\\{\\s*\"file\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"\\s*,\\s*\"text\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"\\s*}",
            Pattern.DOTALL);
    private static final Pattern CAPTURE_ID_PATTERN = Pattern.compile(
            "captureId=(\\d+):\\s*(.*?)(?=\\RcaptureId=\\d+:|\\z)",
            Pattern.DOTALL);

    private final String fixedResponse;

    public MockAgentClient(String fixedResponse) {
        this.fixedResponse = fixedResponse == null ? "" : fixedResponse;
    }

    @PostConstruct
    public void init() {
        log.warn("AgentClient: MOCK - real LLM will not be called");
        if (!fixedResponse.isBlank()) {
            log.warn("agent.mock.response = {}", fixedResponse);
        }
    }

    @Override
    public String complete(String prompt) {
        if (!fixedResponse.isBlank()) {
            log.debug("MockAgentClient returning fixed response");
            return fixedResponse;
        }
        if (isMailLinkingPrompt(prompt)) {
            return completeMailLinkingPrompt(prompt);
        }
        if (isMailPrompt(prompt)) {
            return completeMailPrompt(prompt);
        }
        if (isCapturePrompt(prompt)) {
            return completeCapturePrompt(prompt);
        }
        log.debug("MockAgentClient returning default NOISE response for unknown prompt");
        return """
                {"type":"NOISE","emailId":"mock-id","note":"Mock: unknown prompt","taskLine":null,"taskTitle":null,"priority":null,"sender":null,"draftPath":null,"captureText":null}
                """.trim();
    }

    private boolean isMailPrompt(String prompt) {
        return prompt.contains("Письмо:") && prompt.contains("\"emailId\"");
    }

    private boolean isMailLinkingPrompt(String prompt) {
        return prompt.contains("Определи, это новая задача или продолжение существующей.")
                && prompt.contains("\"decision\": \"<NEW_TASK|LINK_TO_TASK|UPDATE_TASK|IGNORE|REQUEST_CONFIRMATION>\"");
    }

    private boolean isCapturePrompt(String prompt) {
        return prompt.contains("Заметки:") && (prompt.contains("\"file\"") || prompt.contains("captureId="));
    }

    private String completeMailPrompt(String prompt) {
        String emailSection = extractEmailSection(prompt);
        MailType type = detectMailType(emailSection);
        String priority = detectMailPriority(emailSection);
        String emailId = extractEmailId(prompt);
        String sender = extractSender(prompt);

        log.debug("MockAgentClient mail: detected type={}, priority={}", type, priority);

        return switch (type) {
            case REQUEST -> """
                    {"type":"REQUEST","emailId":"%s","note":"Mock: classified as REQUEST by keyword","taskLine":"%s","taskTitle":"Mock task title","priority":"%s","sender":"%s","draftPath":null,"captureText":null}
                    """.formatted(
                    json(emailId),
                    json("- [ ] [" + priorityLabel(priority) + "] Mock task from email " + emailId),
                    json(priority),
                    json(sender)).trim();
            case DRAFT -> """
                    {"type":"DRAFT","emailId":"%s","note":"Mock: classified as DRAFT by keyword","taskLine":null,"taskTitle":null,"priority":null,"sender":null,"draftPath":"%s","captureText":null}
                    """.formatted(json(emailId), json("drafts/" + emailId + "-draft.md")).trim();
            case CAPTURE -> """
                    {"type":"CAPTURE","emailId":"%s","note":"Mock: classified as CAPTURE by keyword","taskLine":null,"taskTitle":null,"priority":null,"sender":null,"draftPath":null,"captureText":"%s"}
                    """.formatted(json(emailId), json("Mock capture: " + firstNonBlankLine(emailSection))).trim();
            case NOISE -> """
                    {"type":"NOISE","emailId":"%s","note":"Mock: classified as NOISE (default or keyword)","taskLine":null,"taskTitle":null,"priority":null,"sender":null,"draftPath":null,"captureText":null}
                    """.formatted(json(emailId)).trim();
        };
    }

    private String completeCapturePrompt(String prompt) {
        List<CaptureInput> captures = extractCaptureInputs(prompt);
        String response = captures.stream()
                .map(this::classifyCapture)
                .toList()
                .toString();
        log.debug("MockAgentClient capture: classified {} item(s)", captures.size());
        return response;
    }

    private String completeMailLinkingPrompt(String prompt) {
        String upper = prompt.toUpperCase(Locale.ROOT);
        Long targetTaskId = extractLinkedTaskId(prompt);
        String summary = "Mock mail linking decision";
        String reason = "Mock mail linking rule";
        String append = null;
        String decision = "NEW_TASK";

        if (upper.contains("ДЕЙСТВИЙ НЕ ТРЕБУЕТСЯ")
                || upper.contains("NO ACTION REQUIRED")
                || upper.contains("ПРОСТО ИНФОРМАЦИЯ")
                || upper.contains("INFO ONLY")) {
            decision = "IGNORE";
            summary = "Mock ignore";
            reason = "Mock: informational reply without action";
            targetTaskId = null;
        } else if (targetTaskId != null && looksLikeUpdate(upper)) {
            decision = "UPDATE_TASK";
            summary = "Mock update to existing task";
            reason = "Mock: matched task plus update keywords";
            append = "Mock update from email: " + firstNonBlankLine(extractLinkingEmailSection(prompt));
        } else if (targetTaskId != null) {
            decision = "LINK_TO_TASK";
            summary = "Mock link to existing task";
            reason = "Mock: matched existing task in search results";
        }

        return """
                {
                  "decision": "%s",
                  "confidence": 0.91,
                  "targetTaskId": %s,
                  "title": %s,
                  "summary": "%s",
                  "reason": "%s",
                  "proposedDescriptionAppend": %s,
                  "matchedSources": %s
                }
                """.formatted(
                decision,
                targetTaskId == null ? "null" : targetTaskId,
                targetTaskId == null ? "\"Mock task title\"" : "null",
                json(summary),
                json(reason),
                append == null ? "null" : "\"" + json(append) + "\"",
                targetTaskId == null ? "[]" : "[\"TASK-" + targetTaskId + "\"]"
        ).trim();
    }

    private List<CaptureInput> extractCaptureInputs(String prompt) {
        List<CaptureInput> captures = new ArrayList<>();

        Matcher fileMatcher = CAPTURE_FILE_PATTERN.matcher(prompt);
        while (fileMatcher.find()) {
            captures.add(new CaptureInput(null, unescapeJson(fileMatcher.group(1)), unescapeJson(fileMatcher.group(2))));
        }
        if (!captures.isEmpty()) {
            return captures;
        }

        Matcher idMatcher = CAPTURE_ID_PATTERN.matcher(prompt);
        while (idMatcher.find()) {
            captures.add(new CaptureInput(Long.valueOf(idMatcher.group(1)), null, idMatcher.group(2).trim()));
        }
        return captures;
    }

    private String classifyCapture(CaptureInput input) {
        CaptureType type = detectCaptureType(input.text());
        ParsedText parsed = parseCaptureText(type, input.text());
        String priority = detectCapturePriority(input.text());
        String tags = type == CaptureType.NOTE ? "capture,mock" : null;

        List<String> fields = new ArrayList<>();
        if (input.captureId() != null) {
            fields.add("\"captureId\":" + input.captureId());
        }
        if (input.file() != null) {
            fields.add("\"file\":\"" + json(input.file()) + "\"");
        }
        fields.add("\"type\":\"" + type.name() + "\"");
        fields.add("\"title\":\"" + json(parsed.title()) + "\"");
        fields.add("\"body\":\"" + json(parsed.body()) + "\"");
        if (tags != null) {
            fields.add("\"tags\":\"" + json(tags) + "\"");
        }
        fields.add("\"priority\":\"" + priority + "\"");
        return "{" + String.join(",", fields) + "}";
    }

    private String extractEmailSection(String prompt) {
        int idx = prompt.indexOf("Верни JSON");
        return idx >= 0 ? prompt.substring(0, idx) : prompt;
    }

    private String extractLinkingEmailSection(String prompt) {
        int idx = prompt.indexOf("\"email\"");
        return idx >= 0 ? prompt.substring(idx) : prompt;
    }

    private MailType detectMailType(String emailSection) {
        String upper = emailSection.toUpperCase(Locale.ROOT);
        if (upper.contains("ОТВЕТН") || upper.contains("ЧЕРНОВИК")
                || upper.contains("DRAFT:") || upper.contains("REPLY:")) {
            return MailType.DRAFT;
        }
        if (upper.contains("BUILD") || upper.contains("PIPELINE")
                || upper.contains("PASSED") || upper.contains("SUCCESS") || upper.contains("DURATION:")) {
            return MailType.NOISE;
        }
        if (upper.contains("FYI") || upper.contains("К СВЕДЕНИЮ")
                || upper.contains("ИНФО:") || upper.contains("НАПОМИНАНИЕ:")
                || upper.contains("CAPTURE")) {
            return MailType.CAPTURE;
        }
        return MailType.REQUEST;
    }

    private String detectMailPriority(String emailSection) {
        String upper = emailSection.toUpperCase(Locale.ROOT);
        if (upper.contains("СРОЧНО") || upper.contains("ASAP") || upper.contains("P1 ИНЦИДЕНТ")) {
            return "CRITICAL";
        }
        if (upper.contains("ДО ЗАВТРА") || upper.contains("ВАЖНО") || upper.contains("ДЕДЛАЙН")) {
            return "HIGH";
        }
        if (upper.contains("КОГДА БУДЕТ ВРЕМЯ")) {
            return "LOW";
        }
        return "NORMAL";
    }

    private String priorityLabel(String priority) {
        return switch (priority) {
            case "CRITICAL" -> "P0";
            case "HIGH" -> "P1";
            case "LOW" -> "P3";
            default -> "P2";
        };
    }

    private String extractEmailId(String prompt) {
        Matcher m = EMAIL_ID_PATTERN.matcher(prompt);
        return m.find() ? m.group(1) : "mock-id";
    }

    private String extractSender(String prompt) {
        for (String line : prompt.lines().toList()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("от:")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    return parts[1].trim();
                }
            }
        }
        return "unknown@mock.local";
    }

    private Long extractLinkedTaskId(String prompt) {
        Matcher matcher = TASK_URL_PATTERN.matcher(prompt);
        if (matcher.find()) {
            return Long.valueOf(matcher.group(1));
        }
        return null;
    }

    private boolean looksLikeUpdate(String upper) {
        return upper.contains("ДЕДЛАЙН")
                || upper.contains("НОВЫЙ СРОК")
                || upper.contains("ПЕРЕНОС")
                || upper.contains("СДВИГАЕМ")
                || upper.contains("ПЯТНИЦ")
                || upper.contains("FRIDAY")
                || upper.contains("UPDATE");
    }

    private String firstNonBlankLine(String text) {
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("(no text)");
    }

    private CaptureType detectCaptureType(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (CaptureType type : CaptureType.values()) {
            if (upper.startsWith(type.name() + ":")
                    || upper.startsWith("TYPE=" + type.name())
                    || upper.contains("[" + type.name() + "]")) {
                return type;
            }
        }
        if (upper.contains("РИСК") || upper.contains("RISK")) {
            return CaptureType.RISK;
        }
        if (upper.contains("ВОПРОС") || upper.contains("QUESTION")) {
            return CaptureType.QUESTION;
        }
        if (upper.contains("ЗАМЕТКА") || upper.contains("NOTE")) {
            return CaptureType.NOTE;
        }
        return CaptureType.TASK;
    }

    private ParsedText parseCaptureText(CaptureType type, String text) {
        String cleaned = stripCaptureTypeMarker(type, text).trim();
        String title = cleaned;
        String body = cleaned;

        int separator = cleaned.indexOf('|');
        if (separator >= 0) {
            title = cleaned.substring(0, separator).trim();
            body = cleaned.substring(separator + 1).trim();
        }

        if (title.isBlank()) {
            title = type.name() + " capture";
        }
        if (body.isBlank()) {
            body = title;
        }

        return new ParsedText(title, body);
    }

    private String stripCaptureTypeMarker(CaptureType type, String text) {
        String trimmed = text.trim();
        String prefix = type.name() + ":";
        if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return trimmed.substring(prefix.length());
        }

        prefix = "TYPE=" + type.name();
        if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String rest = trimmed.substring(prefix.length()).trim();
            if (rest.startsWith(":")) {
                return rest.substring(1);
            }
            return rest;
        }

        String bracket = "[" + type.name() + "]";
        if (trimmed.regionMatches(true, 0, bracket, 0, bracket.length())) {
            return trimmed.substring(bracket.length());
        }

        return trimmed;
    }

    private String detectCapturePriority(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("CRITICAL") || upper.contains("P0") || upper.contains("СРОЧНО")) {
            return "CRITICAL";
        }
        if (upper.contains("HIGH") || upper.contains("P1") || upper.contains("ВАЖНО")) {
            return "HIGH";
        }
        if (upper.contains("LOW") || upper.contains("P3")) {
            return "LOW";
        }
        return "NORMAL";
    }

    private String json(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!escaped) {
                if (ch == '\\') {
                    escaped = true;
                } else {
                    result.append(ch);
                }
                continue;
            }
            result.append(switch (ch) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '"' -> '"';
                case '\\' -> '\\';
                default -> ch;
            });
            escaped = false;
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }

    private enum MailType {
        REQUEST,
        DRAFT,
        NOISE,
        CAPTURE
    }

    private enum CaptureType {
        TASK,
        RISK,
        NOTE,
        QUESTION,
        PERSON_NOTE,
        KNOWLEDGE,
        JOURNAL
    }

    private record CaptureInput(Long captureId, String file, String text) {}

    private record ParsedText(String title, String body) {}
}
