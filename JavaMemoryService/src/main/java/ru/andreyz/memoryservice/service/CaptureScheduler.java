package ru.andreyz.memoryservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CaptureScheduler {

    private static final Logger log = LoggerFactory.getLogger(CaptureScheduler.class);

    private final CaptureProcessingService processingService;

    public CaptureScheduler(CaptureProcessingService processingService) {
        this.processingService = processingService;
    }

    @Scheduled(cron = "0 0 18 * * *")
    public void processEndOfDay() {
        log.info("18:00 — starting scheduled capture processing");
        CaptureProcessingService.ProcessResult result = processingService.processToday();
        log.info("Capture processing done: {}/{} routed", result.routed(), result.total());
    }
}
