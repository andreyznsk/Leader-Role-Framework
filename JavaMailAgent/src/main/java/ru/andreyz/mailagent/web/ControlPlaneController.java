package ru.andreyz.mailagent.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.mailagent.model.ControlAuditEntry;
import ru.andreyz.mailagent.model.ControlSettingsResponse;
import ru.andreyz.mailagent.model.ControlSettingsStatusResponse;
import ru.andreyz.mailagent.model.ControlSettingsUpdateRequest;
import ru.andreyz.mailagent.model.ControlStatusResponse;
import ru.andreyz.mailagent.model.MailConnectionTestResult;
import ru.andreyz.mailagent.model.MailPluginStateRequest;
import ru.andreyz.mailagent.service.MailAgentControlService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/control")
public class ControlPlaneController {

    private final MailAgentControlService controlService;

    public ControlPlaneController(MailAgentControlService controlService) {
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

    @PostMapping("/plugin-state")
    public ResponseEntity<Map<String, Object>> pluginState(@RequestBody(required = false) MailPluginStateRequest request) {
        controlService.applyEnabled(request != null ? request.enabled() : null);
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    @PostMapping("/test-connection")
    public ResponseEntity<MailConnectionTestResult> testConnection() {
        MailConnectionTestResult result = controlService.testConnection();
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(500).body(result);
    }
}
