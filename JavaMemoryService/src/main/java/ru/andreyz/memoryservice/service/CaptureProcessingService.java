package ru.andreyz.memoryservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Risk;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.UsageEventType;
import ru.andreyz.memoryservice.dto.ContextDto;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;
import ru.andreyz.memoryservice.dto.UsageEventCommand;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CaptureProcessingService {

    private static final Logger log = LoggerFactory.getLogger(CaptureProcessingService.class);

    private final CaptureService captureService;
    private final CaptureClassifierAgent classifierAgent;
    private final CaptureRouter captureRouter;
    private final ContextService contextService;
    private final UsageEventService usageEventService;

    public CaptureProcessingService(CaptureService captureService,
                                     CaptureClassifierAgent classifierAgent,
                                     CaptureRouter captureRouter,
                                     ContextService contextService,
                                     UsageEventService usageEventService) {
        this.captureService = captureService;
        this.classifierAgent = classifierAgent;
        this.captureRouter = captureRouter;
        this.contextService = contextService;
        this.usageEventService = usageEventService;
    }

    public ProcessResult processToday() {
        List<CaptureService.CaptureFile> pending = captureService.findTodayFiles();
        if (pending.isEmpty()) {
            log.info("No capture files for today, skipping.");
            return new ProcessResult(0, 0);
        }

        log.info("Processing {} capture files via claude --print", pending.size());

        List<ClassifiedCapture> classified;
        try {
            classified = classifierAgent.classifyFiles(pending, buildDayContext());
        } catch (Exception e) {
            log.error("Classification failed: {}", e.getMessage(), e);
            return new ProcessResult(pending.size(), 0);
        }

        int routed = 0;
        for (ClassifiedCapture c : classified) {
            try {
                String routedTo = captureRouter.route(c);
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
            }
        }

        return new ProcessResult(pending.size(), routed);
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
