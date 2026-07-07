package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.integration.MemoryServiceClient;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.MailProcessingRoute;
import ru.andreyz.mailagent.model.ProcessedEmail;
import ru.andreyz.mailagent.service.MailProcessingStateService;
import ru.andreyz.mailagent.service.MailRuntimeConfig;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Slf4j
@RequiredArgsConstructor
@Component
public class ActionExecutor {

    private static final int MAX_STORED_EMAIL_ID_LENGTH = 120;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern TICKET_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]+-\\d+\\b");
    private static final Pattern FILE_PATTERN = Pattern.compile("\\b\\S+\\.(png|jpe?g|gif|webp|pdf|docx?|xlsx?|pptx?|zip)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern LOCAL_DATE_PATTERN = Pattern.compile("\\b\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}\\b");
    private static final Pattern DEADLINE_LINE_PATTERN = Pattern.compile("(?i).*(deadline|due|before|until|by\\s+\\w+|срок|дедлайн|до\\s+\\S+|к\\s+\\S+).*");
    private static final Pattern EXPECTED_RESULT_LINE_PATTERN = Pattern.compile("(?i).*(expected|result|ready|done when|готов|результат|итог).*");

    private final MemoryServiceClient memoryServiceClient;
    private final MailClient mailClient;
    private final MailConfig.PathProperties pathProperties;
    private final NoticeDocumentWriter noticeDocumentWriter;
    private final MailRuntimeConfigService runtimeConfigService;
    private final MailProcessingStateService processingStateService;
    private final ObjectMapper objectMapper;
    @Value("${agent.provider:unknown}")
    private String agentProvider;

    public void execute(Email email, AgentResponse response) throws Exception {
        execute(email, response, null, null);
    }

    public void execute(Email email, AgentResponse response, String agentPrompt, String agentRawResult) throws Exception {
        MailRuntimeConfig runtime = runtimeConfigService.snapshot();
        ProcessedEmail state = processingStateService.start(email, response.type().name());
        Object payload = buildPayload(email, response, runtime, agentPrompt, agentRawResult);
        executeChain(state, email, response.type(), initialRoute(response.type()), payload);
    }

    public void retry(ProcessedEmail state) throws Exception {
        AgentResponseType responseType = AgentResponseType.from(state.responseType());
        Object payload = payloadFor(responseType, state.routePayloadJson());
        Email email = loadStoredEmail(state.emailId(), processedFolderFor(payload));
        MailProcessingRoute route = state.failedRoute() != null ? state.failedRoute() : initialRoute(responseType);
        executeChain(state, email, responseType, route, payload);
    }

    private void executeChain(ProcessedEmail state,
                              Email email,
                              AgentResponseType responseType,
                              MailProcessingRoute startingRoute,
                              Object payload) throws Exception {
        ProcessedEmail current = state;
        MailProcessingRoute route = startingRoute;
        Object routePayload = payload;
        String outputPath = state.outputPath();
        String actionResultJson = state.actionResultJson();

        while (route != MailProcessingRoute.NONE) {
            current = processingStateService.checkpoint(
                current,
                responseType.name(),
                route,
                routePayload,
                outputPath,
                actionResultJson
            );
            try {
                StepResult result = executeMailIntakeRoute(route, email, (IntakeMailActionPayload) routePayload);
                route = result.nextRoute();
                routePayload = result.nextPayload();
                if (result.outputPath() != null) {
                    outputPath = result.outputPath();
                }
                if (result.actionResultJson() != null) {
                    actionResultJson = result.actionResultJson();
                }
            } catch (Exception exception) {
                log.error("", exception);
                processingStateService.markError(current, exception);
                throw exception;
            }
        }
        processingStateService.markProcessed(current, outputPath, actionResultJson);
    }

    private Path resolveInbox(String emailId) {
        return Path.of(pathProperties.getInbox(), storageFileName(emailId));
    }

    private Path resolveProcessed(String emailId, String processedFolder) {
        String target = processedFolder != null && !processedFolder.isBlank()
                ? processedFolder
                : pathProperties.getProcessed();
        return Path.of(target, storageFileName(emailId));
    }

    private StepResult executeMailIntakeRoute(MailProcessingRoute route,
                                              Email email,
                                              IntakeMailActionPayload payload) throws Exception {
        return switch (route) {
            case INTAKE_WRITE -> {
                memoryServiceClient.createIntake(payload.intakeRequest());
                log.info("{} → memory-service intake: {}", payload.responseType(), email.id());
                yield new StepResult(MailProcessingRoute.MOVE_TO_PROCESSED, payload, null, null);
            }
            case MOVE_TO_PROCESSED -> {
                String sourceId = resolveStoredEmailId(email, payload.sourceId());
                moveToProcessedIfEnabled(resolveInbox(sourceId),
                    resolveProcessed(sourceId, payload.processedFolder()),
                    payload.moveEnabled());
                MailProcessingRoute next = payload.markAsRead() ? MailProcessingRoute.MARK_AS_READ : MailProcessingRoute.NONE;
                yield new StepResult(next, payload, null, null);
            }
            case MARK_AS_READ -> {
                markAsReadIfEnabled(email, payload.markAsRead(), payload.responseType());
                yield new StepResult(MailProcessingRoute.NONE, payload, null, null);
            }
            default -> throw new IllegalStateException("Unsupported intake mail route: " + route);
        };
    }

    private void markAsReadIfEnabled(Email email, boolean requested, String reason) throws Exception {
        if (!requested) {
            return;
        }
        MailRuntimeConfig runtime = runtimeConfigService.snapshot();
        if (!runtime.markAsReadEnabled()) {
            log.info("Email {} in folder [{}] could be marked as read for {} but markAsReadEnabled=false; leaving unread",
                email.id(), email.folder(), reason);
            return;
        }
        mailClient.markAsRead(email.id(), email.folder());
    }

    private Map<String, Object> buildIntakePayload(Email email,
                                                   AgentResponse response,
                                                   String agentPrompt,
                                                   String agentRawResult) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sourceType", "MAIL");
        request.put("sourceId", email.messageId() != null && !email.messageId().isBlank() ? email.messageId() : email.id());
        request.put("sourcePayload", buildMailSourcePayload(email));
        request.put("agentProvider", agentProvider);
        request.put("agentPrompt", agentPrompt);
        request.put("agentResult", agentRawResult);
        request.put("suggestedRoute", suggestedRoute(response.type()));
        request.put("suggestedPayload", buildSuggestedPayload(email, response));
        request.put("confidence", response.agentConfidence());
        request.put("createdBy", "mail-agent");
        return request;
    }

    private Map<String, Object> buildMailSourcePayload(Email email) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("emailId", email.id());
        payload.put("messageId", email.messageId());
        payload.put("conversationId", email.conversationId());
        payload.put("inReplyTo", email.inReplyTo());
        payload.put("folder", email.folder());
        payload.put("from", email.from());
        payload.put("recipients", email.recipients());
        payload.put("subject", email.subject());
        payload.put("body", email.body());
        payload.put("receivedAt", email.receivedAt() != null ? email.receivedAt().toString() : null);
        return payload;
    }

    private String suggestedRoute(AgentResponseType type) {
        return switch (type) {
            case REQUEST -> "TASK";
            case CAPTURE, NOTE -> "NOTE";
            case RAG -> "RAG";
            case NOISE, DRAFT -> "NOISE";
        };
    }

    private Map<String, Object> buildSuggestedPayload(Email email, AgentResponse response) {
        return switch (response.type()) {
            case REQUEST -> buildTaskSuggestedPayload(email, response);
            case CAPTURE -> buildCaptureSuggestedPayload(email, response);
            case RAG -> buildRagSuggestedPayload(email, response);
            case NOTE -> buildNoteSuggestedPayload(email, response);
            case NOISE, DRAFT -> buildNoiseSuggestedPayload(email, response);
        };
    }

    private Map<String, Object> buildTaskSuggestedPayload(Email email, AgentResponse response) {
        MailTaskSummary summary = buildMailTaskSummary(email, response);
        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("title", response.taskTitle());
        suggested.put("description", buildPendingTaskDescription(email, response, summary));
        suggested.put("emailId", response.emailId());
        suggested.put("sender", response.sender());
        suggested.put("priority", response.priority());
        suggested.put("subject", email.subject());
        suggested.put("sourceSummary", summary.toMap());
        return suggested;
    }

    private Map<String, Object> buildCaptureSuggestedPayload(Email email, AgentResponse response) {
        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("title", hasText(email.subject()) ? email.subject().trim() : "Capture from mail");
        suggested.put("text", hasText(response.captureText()) ? response.captureText() : fallbackMailSummary(email, response));
        suggested.put("tags", "mail,capture");
        return suggested;
    }

    private Map<String, Object> buildRagSuggestedPayload(Email email, AgentResponse response) {
        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("docType", "RAG");
        suggested.put("title", hasText(email.subject()) ? email.subject().trim() : "RAG document");
        suggested.put("body", fallbackMailSummary(email, response));
        suggested.put("subject", email.subject());
        suggested.put("sender", email.from());
        suggested.put("receivedAt", email.receivedAt() != null ? email.receivedAt().toString() : null);
        return suggested;
    }

    private Map<String, Object> buildNoteSuggestedPayload(Email email, AgentResponse response) {
        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("title", hasText(response.noteTitle()) ? response.noteTitle() : email.subject());
        suggested.put("text", hasText(response.noteText()) ? response.noteText() : fallbackMailSummary(email, response));
        suggested.put("tags", "mail,email");
        return suggested;
    }

    private Map<String, Object> buildNoiseSuggestedPayload(Email email, AgentResponse response) {
        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("title", hasText(email.subject()) ? email.subject().trim() : "Noise");
        suggested.put("text", fallbackMailSummary(email, response));
        return suggested;
    }

    private Object buildPayload(Email email, AgentResponse response, MailRuntimeConfig runtime, String agentPrompt, String agentRawResult) {
        boolean markAsRead = switch (response.type()) {
            case RAG, NOTE -> true;
            case NOISE -> runtime.markNoiseAsRead();
            case REQUEST, CAPTURE, DRAFT -> false;
        };
        return new IntakeMailActionPayload(
            response.type().name(),
            buildIntakePayload(email, response, agentPrompt, agentRawResult),
            resolveStoredEmailId(email, response.emailId()),
            runtime.processedFolder(),
            runtime.moveProcessedMail(),
            markAsRead
        );
    }

    private String buildPendingTaskDescription(Email email, AgentResponse response, MailTaskSummary summary) {
        List<String> lines = new ArrayList<>();
        lines.add("## Mail intake summary");
        addBullet(lines, "Initiator", summary.initiator());
        addBullet(lines, "Requested action", summary.requestedAction());
        addBullet(lines, "Context", summary.context());
        addBullet(lines, "Deadline/date", summary.deadline());
        addBullet(lines, "Links/tickets/artifacts", joinList(summary.artifacts()));
        addBullet(lines, "Expected result", summary.expectedResult());
        addBullet(lines, "Suggested route", summary.suggestedRoute());
        addBullet(lines, "Source subject", summary.subject());
        addBullet(lines, "Received at", summary.receivedAt());
        return String.join("\n", lines).trim();
    }

    private String fallbackMailSummary(Email email, AgentResponse response) {
        if (hasText(response.note())) {
            return response.note().strip();
        }
        if (hasText(response.noteText())) {
            return response.noteText().strip();
        }
        if (hasText(response.captureText())) {
            return response.captureText().strip();
        }
        return buildRawEmailBlock(email);
    }

    private String buildRawEmailBlock(Email email) {
        StringBuilder builder = new StringBuilder("## Сырой текст письма");
        if (hasText(email.subject())) {
            builder.append("\nТема: ").append(email.subject().trim());
        }
        if (hasText(email.from())) {
            builder.append("\nОт: ").append(email.from().trim());
        }
        if (email.recipients() != null && !email.recipients().isEmpty()) {
            builder.append("\nКому: ").append(String.join(", ", email.recipients()));
        }
        if (hasText(email.messageId())) {
            builder.append("\nMessage-Id: ").append(email.messageId().trim());
        }
        if (hasText(email.conversationId())) {
            builder.append("\nConversation-Id: ").append(email.conversationId().trim());
        }
        if (hasText(email.inReplyTo())) {
            builder.append("\nIn-Reply-To: ").append(email.inReplyTo().trim());
        }
        if (hasText(email.body())) {
            builder.append("\n\n").append(normalizeMultiline(email.body()));
        }
        return builder.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String resolveStoredEmailId(Email email, String candidate) {
        if (hasText(candidate)) {
            return candidate;
        }
        return email.id();
    }

    private String normalizeMultiline(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.strip();
    }

    private MailTaskSummary buildMailTaskSummary(Email email, AgentResponse response) {
        String body = normalizeMultiline(email.body());
        return new MailTaskSummary(
            firstNonBlank(response.sender(), email.from()),
            firstNonBlank(response.taskTitle(), email.subject(), "Mail task"),
            firstNonBlank(normalizeMultiline(response.note()), summarizeBody(body)),
            firstNonBlank(extractDeadline(body), email.receivedAt() != null ? email.receivedAt().toLocalDate().toString() : null),
            extractArtifacts(email),
            extractExpectedResult(body),
            suggestedRoute(response.type()),
            normalizeMultiline(email.subject()),
            email.receivedAt() != null ? email.receivedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null
        );
    }

    private String summarizeBody(String body) {
        if (!hasText(body)) {
            return null;
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 320) {
            return normalized;
        }
        return normalized.substring(0, 317) + "...";
    }

    private String extractDeadline(String body) {
        if (!hasText(body)) {
            return null;
        }
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (ISO_DATE_PATTERN.matcher(trimmed).find()
                || LOCAL_DATE_PATTERN.matcher(trimmed).find()
                || DEADLINE_LINE_PATTERN.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return null;
    }

    private List<String> extractArtifacts(Email email) {
        Set<String> artifacts = new LinkedHashSet<>();
        collectMatches(artifacts, URL_PATTERN, email.body());
        collectMatches(artifacts, TICKET_PATTERN, email.subject());
        collectMatches(artifacts, TICKET_PATTERN, email.body());
        collectMatches(artifacts, FILE_PATTERN, email.body());
        return new ArrayList<>(artifacts);
    }

    private String extractExpectedResult(String body) {
        if (!hasText(body)) {
            return null;
        }
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && EXPECTED_RESULT_LINE_PATTERN.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return null;
    }

    private void collectMatches(Set<String> target, Pattern pattern, String text) {
        if (!hasText(text)) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            target.add(matcher.group().trim());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void addBullet(List<String> lines, String label, String value) {
        if (hasText(value)) {
            lines.add("- " + label + ": " + value.trim());
        }
    }

    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(", ", values);
    }

    private Object payloadFor(AgentResponseType responseType, String payloadJson) {
        IntakeMailActionPayload payload = processingStateService.deserialize(payloadJson, IntakeMailActionPayload.class);
        if (payload == null) {
            return null;
        }
        if (!responseType.name().equals(payload.responseType())) {
            return new IntakeMailActionPayload(
                responseType.name(),
                payload.intakeRequest(),
                payload.sourceId(),
                payload.processedFolder(),
                payload.moveEnabled(),
                payload.markAsRead()
            );
        }
        return payload;
    }

    private MailProcessingRoute initialRoute(AgentResponseType responseType) {
        return MailProcessingRoute.INTAKE_WRITE;
    }

    private Email loadStoredEmail(String emailId, String processedFolder) throws IOException {
        Path inboxPath = resolveInbox(emailId);
        if (Files.exists(inboxPath)) {
            return objectMapper.readValue(Files.readString(inboxPath), Email.class);
        }
        Path processedPath = resolveProcessed(emailId, processedFolder);
        if (Files.exists(processedPath)) {
            return objectMapper.readValue(Files.readString(processedPath), Email.class);
        }
        throw new IOException("Stored email payload not found for " + emailId);
    }

    private String processedFolderFor(Object payload) {
        if (payload instanceof IntakeMailActionPayload intakePayload) {
            return intakePayload.processedFolder();
        }
        return null;
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

    static String storageFileName(String emailId) {
        String sanitized = sanitize(emailId);
        if (sanitized.length() <= MAX_STORED_EMAIL_ID_LENGTH) {
            return sanitized + ".json";
        }
        String hash = stableHash(emailId);
        int prefixLength = Math.max(1, MAX_STORED_EMAIL_ID_LENGTH - hash.length() - 1);
        return sanitized.substring(0, prefixLength) + "_" + hash + ".json";
    }

    private static String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record StepResult(
        MailProcessingRoute nextRoute,
        Object nextPayload,
        String outputPath,
        String actionResultJson
    ) {}

    public record IntakeMailActionPayload(
        String responseType,
        Map<String, Object> intakeRequest,
        String sourceId,
        String processedFolder,
        boolean moveEnabled,
        boolean markAsRead
    ) {}

    private record MailTaskSummary(
        String initiator,
        String requestedAction,
        String context,
        String deadline,
        List<String> artifacts,
        String expectedResult,
        String suggestedRoute,
        String subject,
        String receivedAt
    ) {
        private Map<String, Object> toMap() {
            Map<String, Object> summary = new LinkedHashMap<>();
            putIfPresent(summary, "initiator", initiator);
            putIfPresent(summary, "requestedAction", requestedAction);
            putIfPresent(summary, "context", context);
            putIfPresent(summary, "deadline", deadline);
            if (artifacts != null && !artifacts.isEmpty()) {
                summary.put("artifacts", artifacts);
            }
            putIfPresent(summary, "expectedResult", expectedResult);
            putIfPresent(summary, "suggestedRoute", suggestedRoute);
            putIfPresent(summary, "subject", subject);
            putIfPresent(summary, "receivedAt", receivedAt);
            return summary;
        }

        private void putIfPresent(Map<String, Object> target, String key, String value) {
            if (value != null && !value.isBlank()) {
                target.put(key, value);
            }
        }
    }
}
