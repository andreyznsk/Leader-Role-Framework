package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.memoryservice.dto.MailPluginConfigDto;
import ru.andreyz.memoryservice.dto.PluginHeartbeatRequest;
import ru.andreyz.memoryservice.dto.PluginSummaryDto;
import ru.andreyz.memoryservice.service.PluginSettingsService;

@RestController
@RequestMapping("/api/plugins")
public class PluginControlController {

    private final PluginSettingsService pluginSettingsService;

    public PluginControlController(PluginSettingsService pluginSettingsService) {
        this.pluginSettingsService = pluginSettingsService;
    }

    @GetMapping("/mail/config")
    public ResponseEntity<MailPluginConfigDto> mailConfig() {
        return ResponseEntity.ok(pluginSettingsService.getMailAgentConfig());
    }

    @PostMapping("/{code}/heartbeat")
    public ResponseEntity<PluginSummaryDto> heartbeat(@PathVariable String code,
                                                      @RequestBody(required = false) PluginHeartbeatRequest request) {
        return ResponseEntity.ok(pluginSettingsService.recordHeartbeat(code, request));
    }
}
