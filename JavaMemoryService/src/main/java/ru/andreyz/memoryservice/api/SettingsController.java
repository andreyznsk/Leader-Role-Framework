package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.memoryservice.dto.MailAgentConnectionTestResultDto;
import ru.andreyz.memoryservice.dto.PluginSettingsDto;
import ru.andreyz.memoryservice.dto.PluginSummaryDto;
import ru.andreyz.memoryservice.dto.SystemSettingsDto;
import ru.andreyz.memoryservice.dto.UpdateMailPluginSettingsRequest;
import ru.andreyz.memoryservice.service.PluginSettingsService;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final PluginSettingsService pluginSettingsService;

    public SettingsController(PluginSettingsService pluginSettingsService) {
        this.pluginSettingsService = pluginSettingsService;
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

    @PutMapping("/plugins/mail")
    public ResponseEntity<PluginSettingsDto> updateMail(@RequestBody UpdateMailPluginSettingsRequest request) {
        return ResponseEntity.ok(pluginSettingsService.updateMailSettings(request));
    }

    @PostMapping("/plugins/mail/test-connection")
    public ResponseEntity<MailAgentConnectionTestResultDto> testMailConnection() {
        return ResponseEntity.ok(pluginSettingsService.testMailAgentConnection());
    }
}
