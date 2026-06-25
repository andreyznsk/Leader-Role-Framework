package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.integration.MemoryServiceClient;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.MailProcessingRoute;
import ru.andreyz.mailagent.model.PendingTaskRequest;
import ru.andreyz.mailagent.model.ProcessedEmail;
import ru.andreyz.mailagent.service.MailProcessingStateService;
import ru.andreyz.mailagent.service.MailRuntimeConfig;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final MemoryServiceClient memoryServiceClient;
    private final MailClient mailClient;
    private final MailConfig.PathProperties pathProperties;
    private final NoticeDocumentWriter noticeDocumentWriter;
    private final MailRuntimeConfigService runtimeConfigService;
    private final MailProcessingStateService processingStateService;
    private final ObjectMapper objectMapper;

    public ActionExecutor(MemoryServiceClient memoryServiceClient,
                          MailClient mailClient,
                          MailConfig.PathProperties pathProperties,
                          NoticeDocumentWriter noticeDocumentWriter,
                          MailRuntimeConfigService runtimeConfigService,
                          MailProcessingStateService processingStateService,
                          ObjectMapper objectMapper) {
        this.memoryServiceClient = memoryServiceClient;
        this.mailClient = mailClient;
        this.pathProperties = pathProperties;
        this.noticeDocumentWriter = noticeDocumentWriter;
        this.runtimeConfigService = runtimeConfigService;
        this.processingStateService = processingStateService;
        this.objectMapper = objectMapper;
    }

    public void execute(Email email, AgentResponse response) throws Exception {
        MailRuntimeConfig runtime = runtimeConfigService.snapshot();
        ProcessedEmail state = processingStateService.start(email, response.type().name());
        Object payload = buildPayload(response, runtime);
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
                StepResult result = switch (responseType) {
                    case REQUEST -> executeRequestRoute(route, (RequestActionPayload) routePayload);
                    case CAPTURE -> executeCaptureRoute(route, (CaptureActionPayload) routePayload);
                    case NOTICE -> executeNoticeRoute(route, email, (NoticeActionPayload) routePayload);
                    case NOISE, DRAFT -> executeMailSideEffectRoute(route, email, (MailSideEffectPayload) routePayload);
                };
                route = result.nextRoute();
                routePayload = result.nextPayload();
                if (result.outputPath() != null) {
                    outputPath = result.outputPath();
                }
                if (result.actionResultJson() != null) {
                    actionResultJson = result.actionResultJson();
                }
            } catch (Exception exception) {
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

    private StepResult executeRequestRoute(MailProcessingRoute route, RequestActionPayload payload) throws Exception {
        return switch (route) {
            case PLAN_APPEND -> {
                Path planFile = Path.of(payload.planPath());
                if (planFile.getParent() != null) {
                    Files.createDirectories(planFile.getParent());
                }
                Files.writeString(planFile, "\n" + payload.taskLine(),
                    StandardOpenOption.APPEND, StandardOpenOption.CREATE);
                yield new StepResult(MailProcessingRoute.MEMORY_PENDING_TASK, payload, null, null);
            }
            case MEMORY_PENDING_TASK -> {
                memoryServiceClient.createPendingTask(payload.pendingTaskRequest());
                log.info("REQUEST → plan + memory-service: {}", payload.taskLine());
                yield new StepResult(MailProcessingRoute.MOVE_TO_PROCESSED, payload, null, null);
            }
            case MOVE_TO_PROCESSED -> {
                moveToProcessedIfEnabled(resolveInbox(payload.pendingTaskRequest().emailId()),
                    resolveProcessed(payload.pendingTaskRequest().emailId(), payload.processedFolder()),
                    payload.moveEnabled());
                yield new StepResult(MailProcessingRoute.NONE, payload, null, null);
            }
            default -> throw new IllegalStateException("Unsupported REQUEST route: " + route);
        };
    }

    private StepResult executeCaptureRoute(MailProcessingRoute route, CaptureActionPayload payload) throws Exception {
        return switch (route) {
            case MEMORY_CAPTURE -> {
                memoryServiceClient.createCapture(payload.text(), payload.source(), payload.sourceId());
                log.info("CAPTURE → memory-service: {}", payload.text());
                yield new StepResult(MailProcessingRoute.MOVE_TO_PROCESSED, payload, null, null);
            }
            case MOVE_TO_PROCESSED -> {
                moveToProcessedIfEnabled(resolveInbox(payload.sourceId()),
                    resolveProcessed(payload.sourceId(), payload.processedFolder()),
                    payload.moveEnabled());
                yield new StepResult(MailProcessingRoute.NONE, payload, null, null);
            }
            default -> throw new IllegalStateException("Unsupported CAPTURE route: " + route);
        };
    }

    private StepResult executeNoticeRoute(MailProcessingRoute route,
                                          Email email,
                                          NoticeActionPayload payload) throws Exception {
        return switch (route) {
            case NOTICE_WRITE -> {
                Path noticePath = noticeDocumentWriter.write(email, payload.note());
                log.info("NOTICE → rag-inbox: {}", noticePath);
                yield new StepResult(
                    MailProcessingRoute.MOVE_TO_PROCESSED,
                    payload,
                    noticePath.toString(),
                    objectMapper.writeValueAsString(new NoticeActionResult(noticePath.toString()))
                );
            }
            case MOVE_TO_PROCESSED -> {
                moveToProcessedIfEnabled(resolveInbox(email.id()),
                    resolveProcessed(email.id(), payload.processedFolder()),
                    payload.moveEnabled());
                yield new StepResult(MailProcessingRoute.MARK_AS_READ, payload, null, null);
            }
            case MARK_AS_READ -> {
                if (payload.markAsRead()) {
                    mailClient.markAsRead(email.id(), email.folder());
                }
                yield new StepResult(MailProcessingRoute.NONE, payload, null, null);
            }
            default -> throw new IllegalStateException("Unsupported NOTICE route: " + route);
        };
    }

    private StepResult executeMailSideEffectRoute(MailProcessingRoute route,
                                                  Email email,
                                                  MailSideEffectPayload payload) throws Exception {
        return switch (route) {
            case MOVE_TO_PROCESSED -> {
                moveToProcessedIfEnabled(resolveInbox(email.id()),
                    resolveProcessed(email.id(), payload.processedFolder()),
                    payload.moveEnabled());
                MailProcessingRoute next = payload.markAsRead() ? MailProcessingRoute.MARK_AS_READ : MailProcessingRoute.NONE;
                yield new StepResult(next, payload, null, null);
            }
            case MARK_AS_READ -> {
                if (payload.markAsRead()) {
                    mailClient.markAsRead(email.id(), email.folder());
                }
                yield new StepResult(MailProcessingRoute.NONE, payload, null, null);
            }
            default -> throw new IllegalStateException("Unsupported mail side-effect route: " + route);
        };
    }

    private Object buildPayload(AgentResponse response, MailRuntimeConfig runtime) {
        return switch (response.type()) {
            case REQUEST -> new RequestActionPayload(
                response.taskLine(),
                pathProperties.getPlan(),
                new PendingTaskRequest(
                    response.taskTitle(),
                    response.note(),
                    response.emailId(),
                    response.sender(),
                    response.priority()
                ),
                runtime.processedFolder(),
                runtime.moveProcessedMail()
            );
            case CAPTURE -> new CaptureActionPayload(
                response.captureText() != null && !response.captureText().isBlank()
                    ? response.captureText()
                    : (response.note() != null ? response.note() : ""),
                "email",
                response.emailId(),
                runtime.processedFolder(),
                runtime.moveProcessedMail()
            );
            case NOTICE -> new NoticeActionPayload(
                response.note(),
                runtime.processedFolder(),
                runtime.moveProcessedMail(),
                true
            );
            case NOISE -> new MailSideEffectPayload(
                runtime.processedFolder(),
                runtime.moveProcessedMail(),
                runtime.markNoiseAsRead()
            );
            case DRAFT -> new MailSideEffectPayload(
                runtime.processedFolder(),
                runtime.moveProcessedMail(),
                false
            );
        };
    }

    private Object payloadFor(AgentResponseType responseType, String payloadJson) {
        return switch (responseType) {
            case REQUEST -> processingStateService.deserialize(payloadJson, RequestActionPayload.class);
            case CAPTURE -> processingStateService.deserialize(payloadJson, CaptureActionPayload.class);
            case NOTICE -> processingStateService.deserialize(payloadJson, NoticeActionPayload.class);
            case NOISE, DRAFT -> processingStateService.deserialize(payloadJson, MailSideEffectPayload.class);
        };
    }

    private MailProcessingRoute initialRoute(AgentResponseType responseType) {
        return switch (responseType) {
            case REQUEST -> MailProcessingRoute.PLAN_APPEND;
            case CAPTURE -> MailProcessingRoute.MEMORY_CAPTURE;
            case NOTICE -> MailProcessingRoute.NOTICE_WRITE;
            case NOISE, DRAFT -> MailProcessingRoute.MOVE_TO_PROCESSED;
        };
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
        if (payload instanceof RequestActionPayload requestPayload) {
            return requestPayload.processedFolder();
        }
        if (payload instanceof CaptureActionPayload capturePayload) {
            return capturePayload.processedFolder();
        }
        if (payload instanceof NoticeActionPayload noticePayload) {
            return noticePayload.processedFolder();
        }
        if (payload instanceof MailSideEffectPayload mailPayload) {
            return mailPayload.processedFolder();
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

    public record RequestActionPayload(
        String taskLine,
        String planPath,
        PendingTaskRequest pendingTaskRequest,
        String processedFolder,
        boolean moveEnabled
    ) {}

    public record CaptureActionPayload(
        String text,
        String source,
        String sourceId,
        String processedFolder,
        boolean moveEnabled
    ) {}

    public record NoticeActionPayload(
        String note,
        String processedFolder,
        boolean moveEnabled,
        boolean markAsRead
    ) {}

    public record MailSideEffectPayload(
        String processedFolder,
        boolean moveEnabled,
        boolean markAsRead
    ) {}

    public record NoticeActionResult(String outputPath) {}
}
