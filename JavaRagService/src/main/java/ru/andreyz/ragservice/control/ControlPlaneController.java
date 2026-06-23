package ru.andreyz.ragservice.control;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/control")
public class ControlPlaneController {

    private final RagControlService controlService;

    public ControlPlaneController(RagControlService controlService) {
        this.controlService = controlService;
    }

    @GetMapping("/settings")
    public ResponseEntity<ControlSettingsResponse> settings() {
        return ResponseEntity.ok(controlService.getSettings());
    }

    @PutMapping("/settings")
    public ResponseEntity<ControlSettingsStatusResponse> updateSettings(@RequestBody(required = false) ControlSettingsUpdateRequest request) {
        return ResponseEntity.ok(controlService.updateSettings(request != null ? request.settings() : null));
    }

    @GetMapping("/status")
    public ResponseEntity<ControlStatusResponse> status() {
        return ResponseEntity.ok(controlService.getStatus());
    }

    @GetMapping("/audit")
    public ResponseEntity<List<ControlAuditEntry>> audit() {
        return ResponseEntity.ok(controlService.getAudit());
    }
}
