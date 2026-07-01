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
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Slf4j
@RequiredArgsConstructor
@Component
public class ActionExecutor {


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
        AgentResponseType responseType = AgentResponseType.valueOf(state.responseType());
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
        return Path.of(pathProperties.getInbox(), sanitize(emailId) + ".json");
    }

    private Path resolveProcessed(String emailId, String processedFolder) {
        String target = processedFolder != null && !processedFolder.isBlank()
                ? processedFolder
                : pathProperties.getProcessed();
        return Path.of(target, sanitize(emailId) + ".json");
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
            case NOTICE -> "RAG";
            case NOISE, DRAFT -> "NOISE";
        };
    }

    private Map<String, Object> buildSuggestedPayload(Email email, AgentResponse response) {
        return switch (response.type()) {
            case REQUEST -> buildTaskSuggestedPayload(email, response);
            case CAPTURE -> buildCaptureSuggestedPayload(email, response);
            case NOTICE -> buildNoticeSuggestedPayload(email, response);
            case NOTE -> buildNoteSuggestedPayload(email, response);
            case NOISE, DRAFT -> buildNoiseSuggestedPayload(email, response);
        };
    }

    private Map<String, Object> buildTaskSuggestedPayload(Email email, AgentResponse response) {
        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("title", response.taskTitle());
        suggested.put("description", buildPendingTaskDescription(email, response));
        suggested.put("emailId", response.emailId());
        suggested.put("sender", response.sender());
        suggested.put("priority", response.priority());
        suggested.put("subject", email.subject());
        return suggested;
    }

    private Map<String, Object> buildCaptureSuggestedPayload(Email email, AgentResponse response) {
        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("title", hasText(email.subject()) ? email.subject().trim() : "Capture from mail");
        suggested.put("text", hasText(response.captureText()) ? response.captureText() : fallbackMailSummary(email, response));
        suggested.put("tags", "mail,capture");
        return suggested;
    }

    private Map<String, Object> buildNoticeSuggestedPayload(Email email, AgentResponse response) {
        Map<String, Object> suggested = new LinkedHashMap<>();
        suggested.put("docType", "NOTICE");
        suggested.put("title", hasText(email.subject()) ? email.subject().trim() : "Notice");
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
            case NOTICE, NOTE -> true;
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

    private String buildPendingTaskDescription(Email email, AgentResponse response) {
        String agentSummary = normalizeMultiline(response.note());
        String rawEmail = buildRawEmailBlock(email);
        if (agentSummary == null) {
            return rawEmail;
        }
        return agentSummary + "\n\n---\n\n" + rawEmail;
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

    private Object payloadFor(AgentResponseType responseType, String payloadJson) {
        return processingStateService.deserialize(payloadJson, IntakeMailActionPayload.class);
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
}
