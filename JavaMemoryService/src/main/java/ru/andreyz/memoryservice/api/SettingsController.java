package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.memoryservice.dto.ControlPluginAuditDto;
import ru.andreyz.memoryservice.dto.ControlPluginDto;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsResponse;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsUpdateRequest;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsUpdateResponse;
import ru.andreyz.memoryservice.dto.MailAgentConnectionTestResultDto;
import ru.andreyz.memoryservice.dto.PluginSettingsDto;
import ru.andreyz.memoryservice.dto.PluginSummaryDto;
import ru.andreyz.memoryservice.dto.SystemSettingsDto;
import ru.andreyz.memoryservice.dto.UpdateMailPluginSettingsRequest;
import ru.andreyz.memoryservice.service.ControlPluginService;
import ru.andreyz.memoryservice.service.PluginSettingsService;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final PluginSettingsService pluginSettingsService;
    private final ControlPluginService controlPluginService;

    public SettingsController(PluginSettingsService pluginSettingsService,
                              ControlPluginService controlPluginService) {
        this.pluginSettingsService = pluginSettingsService;
        this.controlPluginService = controlPluginService;
    }

    @GetMapping("/system")
    public ResponseEntity<SystemSettingsDto> system() {
        return ResponseEntity.ok(pluginSettingsService.getSystemSettings());
    }

    @GetMapping("/plugins")
    public ResponseEntity<List<PluginSummaryDto>> plugins() {
        return ResponseEntity.ok(pluginSettingsService.getPlugins());
    }

    @GetMapping("/plugins/{code}")
    public ResponseEntity<PluginSettingsDto> plugin(@PathVariable String code) {
        return ResponseEntity.ok(pluginSettingsService.getPluginSettings(code));
    }

    @GetMapping("/control/plugins")
    public ResponseEntity<List<ControlPluginDto>> controlPlugins() {
        return ResponseEntity.ok(controlPluginService.listPlugins());
    }

    @GetMapping("/control/plugins/{code}/settings")
    public ResponseEntity<?> controlPluginSettings(@PathVariable String code) {
        try {
            return ResponseEntity.ok(controlPluginService.getPluginSettings(code));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(java.util.Map.of(
                    "pluginCode", code,
                    "status", "UNAVAILABLE",
                    "message", e.getMessage()
            ));
        }
    }

    @PutMapping("/control/plugins/{code}/settings")
    public ResponseEntity<?> updateControlPluginSettings(@PathVariable String code,
                                                         @RequestBody ControlPluginSettingsUpdateRequest request) {
        try {
            return ResponseEntity.ok(controlPluginService.updatePluginSettings(code, request));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(java.util.Map.of(
                    "pluginCode", code,
                    "status", "UNAVAILABLE",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/control/plugins/{code}/audit")
    public ResponseEntity<List<ControlPluginAuditDto>> controlPluginAudit(@PathVariable String code) {
        return ResponseEntity.ok(controlPluginService.getPluginAudit(code));
    }

    @PutMapping("/plugins/mail")
    public ResponseEntity<PluginSettingsDto> updateMail(@RequestBody UpdateMailPluginSettingsRequest request) {
        return ResponseEntity.ok(pluginSettingsService.updateMailSettings(request));
    }

    @PostMapping("/plugins/mail/test-connection")
    public ResponseEntity<MailAgentConnectionTestResultDto> testMailConnection() {
        MailAgentConnectionTestResultDto result = pluginSettingsService.testMailAgentConnection();
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(500).body(result);
    }
}
