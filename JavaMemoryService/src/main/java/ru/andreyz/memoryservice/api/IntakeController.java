package ru.andreyz.memoryservice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.dto.IntakeApplyRequest;
import ru.andreyz.memoryservice.dto.IntakeCreateRequest;
import ru.andreyz.memoryservice.dto.IntakeItemDto;
import ru.andreyz.memoryservice.dto.IntakeRejectRequest;
import ru.andreyz.memoryservice.dto.IntakeUpdateRequest;
import ru.andreyz.memoryservice.service.IntakeService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/intake")
public class IntakeController {

    private final IntakeService intakeService;

    public IntakeController(IntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping
    public ResponseEntity<IntakeItemDto> create(@RequestBody IntakeCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(intakeService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<IntakeItemDto>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String sourceType,
                                                    @RequestParam(required = false) String suggestedRoute) {
        return ResponseEntity.ok(intakeService.list(status, sourceType, suggestedRoute));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IntakeItemDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(intakeService.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<IntakeItemDto> update(@PathVariable UUID id,
                                                @RequestBody IntakeUpdateRequest request) {
        return ResponseEntity.ok(intakeService.update(id, request));
    }

    @PostMapping("/{id}/apply")
    public ResponseEntity<IntakeItemDto> apply(@PathVariable UUID id,
                                               @RequestBody IntakeApplyRequest request) {
        return ResponseEntity.ok(intakeService.apply(id, request));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<IntakeItemDto> reject(@PathVariable UUID id,
                                                @RequestBody IntakeRejectRequest request) {
        return ResponseEntity.ok(intakeService.reject(id, request));
    }
}
