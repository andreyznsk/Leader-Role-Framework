package ru.andreyz.mailagent.scheduler;

import org.junit.jupiter.api.Test;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.Email;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockClaudeRunnerTest {

    @Test
    void runClassifiesFyiEmailAsCapture() {
        Email email = new Email(
            "email-capture-001",
            "FYI: переезд на новый кластер",
            "team@company.ru",
            "К сведению: с 1 июля переезжаем на новый Kubernetes кластер.",
            LocalDateTime.now(),
            "INBOX"
        );

        AgentResponse response = new MockClaudeRunner().run(new PromptBuilder().build(email));

        assertEquals(AgentResponseType.CAPTURE, response.type());
        assertEquals("email-capture-001", response.emailId());
        assertNotNull(response.captureText());
        assertTrue(response.captureText().contains("Mock capture:"));
    }
}
