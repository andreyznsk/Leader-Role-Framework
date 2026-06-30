package ru.andreyz.common.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockAgentClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsConfiguredResponse() {
        MockAgentClient client = new MockAgentClient("{\"type\":\"NOTE\"}");

        String response = client.complete("ignored prompt");

        assertThat(response).isEqualTo("{\"type\":\"NOTE\"}");
    }

    @Test
    void classifiesMailPromptWithMockClaudeRunnerRules() throws Exception {
        MockAgentClient client = new MockAgentClient("");
        String prompt = """
                Ты - ассистент Tech Lead. Проанализируй входящее письмо и верни JSON.

                Письмо:
                От: team@company.ru
                Тема: FYI: переезд на новый кластер
                Текст:
                К сведению: с 1 июля переезжаем на новый Kubernetes кластер.

                Верни JSON строго в следующем формате:
                {"emailId": "email-capture-001"}
                """;

        JsonNode response = objectMapper.readTree(client.complete(prompt));

        assertThat(response.get("type").asText()).isEqualTo("CAPTURE");
        assertThat(response.get("emailId").asText()).isEqualTo("email-capture-001");
        assertThat(response.get("captureText").asText()).contains("Mock capture:");
    }

    @Test
    void classifiesCaptureFilePromptWithMockCaptureClassifierRules() throws Exception {
        MockAgentClient client = new MockAgentClient("");
        String prompt = """
                Классифицируй каждую заметку. Верни ТОЛЬКО JSON массив, без пояснений.

                Заметки:
                [{"file":"task.md","text":"TASK: E2E task | Нужно сделать действие HIGH"},
                 {"file":"note.md","text":"NOTE: E2E note | Информация к сведению"}]
                """;

        JsonNode response = objectMapper.readTree(client.complete(prompt));

        assertThat(response).hasSize(2);
        assertThat(response.get(0).get("file").asText()).isEqualTo("task.md");
        assertThat(response.get(0).get("type").asText()).isEqualTo("TASK");
        assertThat(response.get(0).get("title").asText()).isEqualTo("E2E task");
        assertThat(response.get(0).get("body").asText()).isEqualTo("Нужно сделать действие HIGH");
        assertThat(response.get(0).get("priority").asText()).isEqualTo("HIGH");
        assertThat(response.get(1).get("type").asText()).isEqualTo("NOTE");
        assertThat(response.get(1).get("tags").asText()).isEqualTo("capture,mock");
    }

    @Test
    void classifiesMailLinkingPromptAsUpdateTaskWhenExistingTaskAndDeadlineChangePresent() throws Exception {
        MockAgentClient client = new MockAgentClient("");
        String prompt = """
                Ты анализируешь новое входящее письмо и найденный контекст LeaderOS.
                Определи, это новая задача или продолжение существующей.

                Верни только JSON:
                {
                  "decision": "<NEW_TASK|LINK_TO_TASK|UPDATE_TASK|IGNORE|REQUEST_CONFIRMATION>"
                }

                Письмо и контекст:
                {
                  "email": {
                    "id": "mail-001",
                    "subject": "RE: Release deadline",
                    "from": "sender@test.com",
                    "body": "Новый срок по релизу: пятница"
                  },
                  "search": {
                    "results": [
                      {
                        "layer": "TASK",
                        "title": "Release tracking",
                        "url": "/ui/tasks/42/edit"
                      }
                    ]
                  }
                }
                """;

        JsonNode response = objectMapper.readTree(client.complete(prompt));

        assertThat(response.get("decision").asText()).isEqualTo("UPDATE_TASK");
        assertThat(response.get("targetTaskId").asLong()).isEqualTo(42L);
        assertThat(response.get("proposedDescriptionAppend").asText()).contains("Mock update from email");
    }

    @Test
    void classifiesMailLinkingPromptAsIgnoreWhenNoActionRequired() throws Exception {
        MockAgentClient client = new MockAgentClient("");
        String prompt = """
                Ты анализируешь новое входящее письмо и найденный контекст LeaderOS.
                Определи, это новая задача или продолжение существующей.

                Верни только JSON:
                {
                  "decision": "<NEW_TASK|LINK_TO_TASK|UPDATE_TASK|IGNORE|REQUEST_CONFIRMATION>"
                }

                Письмо и контекст:
                {
                  "email": {
                    "id": "mail-002",
                    "subject": "RE: Release closed",
                    "from": "sender@test.com",
                    "body": "Подтверждаю: действий не требуется, просто информация."
                  },
                  "search": {
                    "results": [
                      {
                        "layer": "TASK",
                        "title": "Release tracking",
                        "url": "/ui/tasks/42/edit"
                      }
                    ]
                  }
                }
                """;

        JsonNode response = objectMapper.readTree(client.complete(prompt));

        assertThat(response.get("decision").asText()).isEqualTo("IGNORE");
        assertThat(response.get("targetTaskId").isNull()).isTrue();
    }
}
