package ru.andreyz.mailagent.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.mailagent.model.MailConnectionTestResult;
import ru.andreyz.mailagent.model.MailPluginStateRequest;
import ru.andreyz.mailagent.service.MailAgentControlService;

import java.util.Map;

@RestController
@RequestMapping("/api/control")
public class ControlPlaneController {

    private final MailAgentControlService controlService;

    public ControlPlaneController(MailAgentControlService controlService) {
        this.controlService = controlService;
    }

    @PostMapping("/plugin-state")
    public ResponseEntity<Map<String, Object>> pluginState(@RequestBody(required = false) MailPluginStateRequest request) {
        controlService.applyEnabled(request != null ? request.enabled() : null);
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    @PostMapping("/test-connection")
    public ResponseEntity<MailConnectionTestResult> testConnection() {
        return ResponseEntity.ok(controlService.testConnection());
    }
}
