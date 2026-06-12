package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.CaptureRequest;
import ru.andreyz.memoryservice.dto.CaptureResponse;
import ru.andreyz.memoryservice.service.CaptureProcessingService;
import ru.andreyz.memoryservice.service.CaptureService;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/capture")
public class CaptureController {

    private final CaptureService captureService;
    private final CaptureProcessingService processingService;

    public CaptureController(CaptureService captureService,
                              CaptureProcessingService processingService) {
        this.captureService = captureService;
        this.processingService = processingService;
    }

    @PostMapping
    public ResponseEntity<CaptureResponse> capture(@RequestBody CaptureRequest req) {
        CaptureService.CaptureSaveResult result = captureService.saveWithFile(req);
        Capture saved = result.capture();
        String savedAt = saved.capturedAt()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return ResponseEntity.ok(new CaptureResponse(result.file().toString(), true, saved.id(), savedAt));
    }

    @GetMapping("/today")
    public ResponseEntity<List<Capture>> today() {
        return ResponseEntity.ok(captureService.findToday());
    }

    @GetMapping("/recent")
    public ResponseEntity<List<Capture>> recent() {
        return ResponseEntity.ok(captureService.findRecent());
    }

    @PostMapping("/process-today")
    public ResponseEntity<Map<String, Object>> processToday() {
        CaptureProcessingService.ProcessResult result = processingService.processToday();
        return ResponseEntity.ok(Map.of(
                "total", result.total(),
                "routed", result.routed()
        ));
    }

    @PostMapping("/process-now")
    public ResponseEntity<Map<String, Object>> processNow() {
        return processToday();
    }
}
