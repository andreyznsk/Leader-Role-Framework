package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.domain.Risk;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.UsageEventType;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;
import ru.andreyz.memoryservice.dto.ContextDto;
import ru.andreyz.memoryservice.dto.UsageEventCommand;
import ru.andreyz.memoryservice.service.CaptureClassifierAgent.ClassificationBatch;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Slf4j
@RequiredArgsConstructor
@Service
public class CaptureProcessingService {


    private final CaptureService captureService;
    private final CaptureClassifierAgent classifierAgent;
    private final CaptureRouter captureRouter;
    private final ContextService contextService;
    private final UsageEventService usageEventService;

    public ProcessResult processToday() {
        List<CaptureService.CaptureFile> pending = captureService.findTodayFiles();
        if (pending.isEmpty()) {
            log.info("No capture files for today, skipping.");
            return new ProcessResult(0, 0);
        }

        log.info("Processing {} capture files via agent", pending.size());

        // Collect IDs of today's NEW captures to drive state machine
        List<Long> processingIds = captureService.findTodayNew().stream()
                .map(Capture::id)
                .toList();
        processingIds.forEach(id -> captureService.markProcessing(id));

        Map<String, String> pendingTextByFile = new HashMap<>();
        pending.forEach(file -> pendingTextByFile.put(file.file(), file.text()));

        ClassificationBatch batch;
        try {
            batch = classifierAgent.classifyFilesWithTrace(pending, buildDayContext());
        } catch (Exception e) {
            log.error("Classification failed: {}", e.getMessage(), e);
            processingIds.forEach(id -> captureService.markError(id, e.getMessage()));
            return new ProcessResult(pending.size(), 0);
        }

        int routed = 0;
        for (ClassifiedCapture c : batch.items()) {
            try {
                String routedTo = captureRouter.route(
                        c,
                        pendingTextByFile.get(c.file()),
                        batch.prompt(),
                        batch.rawResult(),
                        batch.agentProvider()
                );
                if (c.captureId() != null) {
                    captureService.markProcessed(c.captureId(), c.type(), routedTo);
                }
                if (c.file() != null && !c.file().isBlank()) {
                    captureService.moveToProcessed(c.file());
                }
                routed++;
                usageEventService.record(new UsageEventCommand(
                        UsageEventType.CAPTURE_PROCESSED,
                        "capture-bot",
                        "SUCCESS",
                        c.file(),
                        "capture",
                        c.captureId() != null ? String.valueOf(c.captureId()) : null,
                        null,
                        null,
                        java.util.Map.of("classification", c.type(), "routedTo", routedTo)
                ));
                log.info("Capture {} → {} ({})", captureRef(c), c.type(), routedTo);
            } catch (IOException e) {
                log.warn("Failed to move capture file {} after route: {}", c.file(), e.getMessage(), e);
            } catch (Exception e) {
                log.warn("Failed to route capture {}: {}", captureRef(c), e.getMessage(), e);
                if (c.captureId() != null) {
                    captureService.markError(c.captureId(), e.getMessage());
                }
            }
        }

        return new ProcessResult(pending.size(), routed);
    }

    public void processSingle(Long id) {
        Capture capture = captureService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Capture not found: " + id));

        captureService.markProcessing(id);

        try {
            CaptureService.CaptureFile file = new CaptureService.CaptureFile(
                    String.valueOf(id), capture.rawText());
            ClassificationBatch batch = classifierAgent.classifyFilesWithTrace(
                    List.of(file), buildDayContext());

            if (batch.items().isEmpty()) {
                captureService.markError(id, "No classification result returned");
                return;
            }

            ClassifiedCapture c = batch.items().get(0);
            String routedTo = captureRouter.route(
                    c,
                    capture.rawText(),
                    batch.prompt(),
                    batch.rawResult(),
                    batch.agentProvider()
            );
            captureService.markProcessed(id, c.type(), routedTo);

            usageEventService.record(new UsageEventCommand(
                    UsageEventType.CAPTURE_PROCESSED,
                    "capture-bot",
                    "SUCCESS",
                    null,
                    "capture",
                    String.valueOf(id),
                    null,
                    null,
                    java.util.Map.of("classification", c.type(), "routedTo", routedTo)
            ));
            log.info("Single capture {} → {} ({})", id, c.type(), routedTo);

        } catch (Exception e) {
            log.error("Failed to process capture {}: {}", id, e.getMessage(), e);
            captureService.markError(id, e.getMessage());
            usageEventService.record(new UsageEventCommand(
                    UsageEventType.CAPTURE_FAILED,
                    "capture-bot",
                    "FAILED",
                    null,
                    "capture",
                    String.valueOf(id),
                    null,
                    null,
                    java.util.Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown")
            ));
        }
    }

    private String buildDayContext() {
        ContextDto context = contextService.buildContext();
        List<String> taskTitles = context.todayPlan().tasks().stream()
                .map(Task::title)
                .toList();
        List<String> riskTitles = context.openRisks().stream()
                .map(Risk::title)
                .toList();
        return "Задачи: " + formatList(taskTitles) + "\n" +
                "Открытые риски: " + formatList(riskTitles);
    }

    private String formatList(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String captureRef(ClassifiedCapture c) {
        return c.file() != null ? c.file() : String.valueOf(c.captureId());
    }

    public record ProcessResult(int total, int routed) {}
}
