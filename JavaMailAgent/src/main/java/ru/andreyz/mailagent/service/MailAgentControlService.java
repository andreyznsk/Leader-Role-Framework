package ru.andreyz.mailagent.service;

import org.springframework.stereotype.Service;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.model.ControlAuditEntry;
import ru.andreyz.mailagent.model.ControlSettingsResponse;
import ru.andreyz.mailagent.model.ControlSettingsStatusResponse;
import ru.andreyz.mailagent.model.MailEndpointDetectRequest;
import ru.andreyz.mailagent.model.MailEndpointDetectResult;
import ru.andreyz.mailagent.model.MailConnectionTestRequest;
import ru.andreyz.mailagent.model.MailConnectionTestResult;
import ru.andreyz.mailagent.model.ControlStatusResponse;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
@Slf4j
@RequiredArgsConstructor
@Service
public class MailAgentControlService {


    private final MailClient mailClient;
    private final MailRuntimeConfigService runtimeConfigService;
    private final MailConnectionTestService mailConnectionTestService;

    public void applyEnabled(Boolean enabled) {
        if (enabled != null) {
            runtimeConfigService.apply(Map.of("enabled", String.valueOf(enabled)));
        }
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

    public MailEndpointDetectResult detectEndpoint(MailEndpointDetectRequest request) {
        MailEndpointDetectResult result = mailConnectionTestService.detectEndpoint(request);
        if (result.success()) {
            log.info("Mail plugin endpoint detection succeeded: {} ({})", result.message(), result.endpoint());
        } else {
            log.warn("Mail plugin endpoint detection failed: {} ({})", result.message(), result.endpoint());
        }
        return result;
    }

    public MailConnectionTestResult testConnection(MailConnectionTestRequest request) {
        MailConnectionTestResult result = mailConnectionTestService.testConnection(request);
        if (result.success()) {
            log.info("Mail plugin connection test succeeded: {} ({})", result.message(), result.target());
        } else {
            log.warn("Mail plugin connection test failed: {} ({})", result.message(), result.target());
        }
        return result;
    }
}
