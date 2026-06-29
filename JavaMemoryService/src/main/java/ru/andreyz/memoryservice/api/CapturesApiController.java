package ru.andreyz.memoryservice.api;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.CaptureRequest;
import ru.andreyz.memoryservice.dto.CaptureResponse;
import ru.andreyz.memoryservice.service.CaptureProcessingService;
import ru.andreyz.memoryservice.service.CaptureService;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/captures")
public class CapturesApiController {

    private final CaptureService captureService;
    private final CaptureProcessingService processingService;

    public CapturesApiController(CaptureService captureService,
                                  CaptureProcessingService processingService) {
        this.captureService = captureService;
        this.processingService = processingService;
    }

    @GetMapping
    public ResponseEntity<List<Capture>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String route,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(captureService.findWithFilters(status, date, source, route, q));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Capture> getById(@PathVariable Long id) {
        return captureService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CaptureResponse> create(@RequestBody CaptureRequest req) {
        CaptureService.CaptureSaveResult result = captureService.saveWithFile(req);
        Capture saved = result.capture();
        String savedAt = saved.capturedAt()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String filePath = result.file() != null ? result.file().toString() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CaptureResponse(filePath, true, saved.id(), savedAt));
    }

    @PostMapping("/{id}/process")
    public ResponseEntity<Map<String, Object>> process(@PathVariable Long id) {
        processingService.processSingle(id);
        Capture updated = captureService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Capture not found: " + id));
        return ResponseEntity.ok(Map.of(
                "id", id,
                "status", updated.status(),
                "route", updated.classified() != null ? updated.classified() : "",
                "routedTo", updated.routedTo() != null ? updated.routedTo() : "",
                "errorMessage", updated.errorMessage() != null ? updated.errorMessage() : ""
        ));
    }

    @PostMapping("/{id}/reprocess")
    public ResponseEntity<Map<String, Object>> reprocess(@PathVariable Long id) {
        captureService.reprocess(id);
        return ResponseEntity.ok(Map.of("id", id, "status", "NEW"));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Map<String, Object>> archive(@PathVariable Long id) {
        captureService.archive(id);
        return ResponseEntity.ok(Map.of("id", id, "status", "ARCHIVED"));
    }
}
