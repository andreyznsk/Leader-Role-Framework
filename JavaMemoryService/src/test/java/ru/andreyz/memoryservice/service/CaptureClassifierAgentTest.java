package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptureClassifierAgentTest {

    private final AgentClient agentClient = prompt -> "[]";
    private final CaptureClassifierAgent agent = new CaptureClassifierAgent(agentClient, new ObjectMapper());

    @Test
    void parseResponse_plainJsonArray() throws IOException {
        String response = """
                [{"captureId":1,"type":"TASK","title":"Do ADR","body":"details","priority":"NORMAL"}]
                """;

        List<ClassifiedCapture> result = agent.parseResponse(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).captureId()).isEqualTo(1L);
        assertThat(result.get(0).type()).isEqualTo("TASK");
        assertThat(result.get(0).title()).isEqualTo("Do ADR");
        assertThat(result.get(0).priority()).isEqualTo("NORMAL");
    }

    @Test
    void parseResponse_withMarkdownJsonFence() throws IOException {
        String response = """
                ```json
                [{"captureId":2,"type":"RISK","title":"Single point","body":"only one person knows deploy","priority":"HIGH"}]
                ```
                """;

        List<ClassifiedCapture> result = agent.parseResponse(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("RISK");
        assertThat(result.get(0).priority()).isEqualTo("HIGH");
    }

    @Test
    void parseResponse_withMarkdownFenceNoLanguage() throws IOException {
        String response = "```\n[{\"captureId\":3,\"type\":\"QUESTION\",\"title\":\"How?\",\"body\":\"context\",\"priority\":\"LOW\"}]\n```";

        List<ClassifiedCapture> result = agent.parseResponse(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("QUESTION");
    }

    @Test
    void parseResponse_multipleItems() throws IOException {
        String response = """
                [
                  {"captureId":10,"type":"TASK","title":"T1","body":"b1","priority":"NORMAL"},
                  {"captureId":11,"type":"RISK","title":"R1","body":"b2","priority":"HIGH"},
                  {"captureId":12,"type":"KNOWLEDGE","title":"K1","body":"b3","priority":"LOW"}
                ]
                """;

        List<ClassifiedCapture> result = agent.parseResponse(response);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(ClassifiedCapture::type)
                .containsExactly("TASK", "RISK", "KNOWLEDGE");
    }

    @Test
    void parseResponse_withLeadingText() throws IOException {
        // Agent sometimes adds a preamble before the JSON array
        String response = """
                Вот результат классификации:
                [{"captureId":5,"type":"JOURNAL","title":"Day summary","body":"productive","priority":"LOW"}]
                """;

        List<ClassifiedCapture> result = agent.parseResponse(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("JOURNAL");
    }

    @Test
    void parseResponse_noJsonArray_throwsIOException() {
        String response = "Извините, я не могу обработать этот запрос.";

        assertThatThrownBy(() -> agent.parseResponse(response))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No JSON array found");
    }

    @Test
    void parseResponse_emptyArray() throws IOException {
        List<ClassifiedCapture> result = agent.parseResponse("[]");

        assertThat(result).isEmpty();
    }

    @Test
    void buildFilePrompt_includesDayContextAndNoDuplicateGuidance() {
        String prompt = agent.buildFilePrompt(
                List.of(new CaptureService.CaptureFile("10-32-00.md", "нужно обновить runbook")),
                "Задачи: [\"Обсудить архитектуру payments\"]\nОткрытые риски: [\"Только один человек знает деплой\"]");

        assertThat(prompt)
                .contains("Контекст дня (уже существуют, не дублируй):")
                .contains("Обсудить архитектуру payments")
                .contains("Только один человек знает деплой")
                .contains("не дублируй существующие задачи")
                .contains("не дублируй существующие риски")
                .contains("\"file\":\"10-32-00.md\"");
    }

    @Test
    void parseResponse_personNoteType() throws IOException {
        String response = """
                [{"captureId":7,"type":"PERSON_NOTE","title":"Петр","body":"Хочет перейти в другую команду","priority":"NORMAL"}]
                """;

        List<ClassifiedCapture> result = agent.parseResponse(response);

        assertThat(result.get(0).type()).isEqualTo("PERSON_NOTE");
        assertThat(result.get(0).title()).isEqualTo("Петр");
    }
}
