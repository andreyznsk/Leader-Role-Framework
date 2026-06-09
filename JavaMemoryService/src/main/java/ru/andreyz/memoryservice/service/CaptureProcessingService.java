package ru.andreyz.memoryservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;

import java.util.List;

@Service
public class CaptureProcessingService {

    private static final Logger log = LoggerFactory.getLogger(CaptureProcessingService.class);

    private final CaptureService captureService;
    private final CaptureClassifierAgent classifierAgent;
    private final CaptureRouter captureRouter;

    public CaptureProcessingService(CaptureService captureService,
                                     CaptureClassifierAgent classifierAgent,
                                     CaptureRouter captureRouter) {
        this.captureService = captureService;
        this.classifierAgent = classifierAgent;
        this.captureRouter = captureRouter;
    }

    public ProcessResult processToday() {
        List<Capture> pending = captureService.findTodayPending();
        if (pending.isEmpty()) {
            log.info("No pending captures for today, skipping.");
            return new ProcessResult(0, 0);
        }

        log.info("Processing {} pending captures via claude --print", pending.size());

        List<ClassifiedCapture> classified;
        try {
            classified = classifierAgent.classify(pending);
        } catch (Exception e) {
            log.error("Classification failed: {}", e.getMessage(), e);
            return new ProcessResult(pending.size(), 0);
        }

        int routed = 0;
        for (ClassifiedCapture c : classified) {
            try {
                String routedTo = captureRouter.route(c);
                captureService.markProcessed(c.captureId(), c.type(), routedTo);
                routed++;
                log.info("Capture {} → {} ({})", c.captureId(), c.type(), routedTo);
            } catch (Exception e) {
                log.error("Failed to route capture {}: {}", c.captureId(), e.getMessage(), e);
            }
        }

        return new ProcessResult(pending.size(), routed);
    }

    public record ProcessResult(int total, int routed) {}
}
