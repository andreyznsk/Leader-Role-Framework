package ru.andreyz.memoryservice.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Slf4j
@RequiredArgsConstructor
@Component
public class CaptureScheduler {


    private final CaptureProcessingService processingService;

    @Scheduled(cron = "${capture.scheduler.cron:0 0 * * * *}")
    public void processEndOfDay() {
        log.info("Starting scheduled capture processing");
        CaptureProcessingService.ProcessResult result = processingService.processToday();
        log.info("Capture processing done: {}/{} routed", result.routed(), result.total());
    }
}
