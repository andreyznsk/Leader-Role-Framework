package ru.andreyz.ragservice.control;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RagControlService {

    private final RagRuntimeConfigService runtimeConfigService;

    public RagControlService(RagRuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    public ControlSettingsResponse getSettings() {
        return runtimeConfigService.descriptor();
    }

    public ControlSettingsStatusResponse updateSettings(Map<String, String> settings) {
        return runtimeConfigService.apply(settings);
    }

    public ControlStatusResponse getStatus() {
        return runtimeConfigService.status();
    }

    public List<ControlAuditEntry> getAudit() {
        return runtimeConfigService.audit();
    }
}
