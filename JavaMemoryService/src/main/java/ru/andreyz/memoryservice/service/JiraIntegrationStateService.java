package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class JiraIntegrationStateService {

    private final AtomicReference<JiraIntegrationSnapshot> snapshot =
            new AtomicReference<>(JiraIntegrationSnapshot.disabled("Jira integration is disabled"));

    public JiraIntegrationSnapshot getSnapshot() {
        return snapshot.get();
    }

    public void update(JiraIntegrationSnapshot next) {
        snapshot.set(next);
    }
}
