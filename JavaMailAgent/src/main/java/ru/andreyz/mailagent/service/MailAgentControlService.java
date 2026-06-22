package ru.andreyz.mailagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.mailagent.client.MailClient;
import ru.andreyz.mailagent.model.MailConnectionTestResult;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class MailAgentControlService {

    private static final Logger log = LoggerFactory.getLogger(MailAgentControlService.class);

    private final MailClient mailClient;
    private final AtomicReference<Boolean> currentEnabled = new AtomicReference<>();

    public MailAgentControlService(MailClient mailClient) {
        this.mailClient = mailClient;
    }

    public void applyEnabled(Boolean enabled) {
        if (enabled == null) {
            log.info("Mail plugin state sync received without enabled flag");
            return;
        }
        Boolean previous = currentEnabled.getAndSet(enabled);
        if (previous == null || !previous.equals(enabled)) {
            log.info("Mail plugin enabled updated from MemoryService control plane: {} -> {}", previous, enabled);
        } else {
            log.info("Mail plugin enabled state re-confirmed by MemoryService control plane: {}", enabled);
        }
    }

    public MailConnectionTestResult testConnection() {
        MailConnectionTestResult result = mailClient.testConnection();
        if (result.success()) {
            log.info("Mail plugin connection test succeeded: {} ({})", result.message(), result.target());
        } else {
            log.warn("Mail plugin connection test failed: {} ({})", result.message(), result.target());
        }
        return result;
    }
}
