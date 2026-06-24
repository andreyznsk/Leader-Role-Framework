package ru.andreyz.mailagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.model.ControlAuditEntry;
import ru.andreyz.mailagent.model.ControlSettingsResponse;
import ru.andreyz.mailagent.model.ControlSettingsStatusResponse;
import ru.andreyz.mailagent.model.MailConnectionTestRequest;
import ru.andreyz.mailagent.model.MailConnectionTestResult;
import ru.andreyz.mailagent.model.ControlStatusResponse;

import java.util.List;
import java.util.Map;
@Service
public class MailAgentControlService {

    private static final Logger log = LoggerFactory.getLogger(MailAgentControlService.class);

    private final MailClient mailClient;
    private final MailRuntimeConfigService runtimeConfigService;
    private final MailConnectionTestService mailConnectionTestService;

    public MailAgentControlService(MailClient mailClient,
                                   MailRuntimeConfigService runtimeConfigService,
                                   MailConnectionTestService mailConnectionTestService) {
        this.mailClient = mailClient;
        this.runtimeConfigService = runtimeConfigService;
        this.mailConnectionTestService = mailConnectionTestService;
    }

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
