package ru.andreyz.mailagent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.PendingTaskRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MemoryServiceClientTest {

    @Test
    void createPendingTaskSkippedWhenDisabled() {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(false);

        MemoryServiceClient client = new MemoryServiceClient(new ObjectMapper(), props);

        // Не должен выбрасывать даже при недоступном URL
        assertDoesNotThrow(() -> client.createPendingTask(
            new PendingTaskRequest("Test", "description", "email-001", "user@test.com", "NORMAL")
        ));
    }

    @Test
    void createCaptureSkippedWhenDisabled() {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(false);

        MemoryServiceClient client = new MemoryServiceClient(new ObjectMapper(), props);

        assertDoesNotThrow(() -> client.createCapture("FYI text", "email"));
    }

    @Test
    void isHealthyReturnsFalseWhenDisabled() {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(false);

        MemoryServiceClient client = new MemoryServiceClient(new ObjectMapper(), props);

        assertFalse(client.isHealthy());
    }

    @Test
    void isHealthyReturnsFalseWhenUnreachable() {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(true);

        MemoryServiceClient client = new MemoryServiceClient(new ObjectMapper(), props);

        assertFalse(client.isHealthy());
    }
}
