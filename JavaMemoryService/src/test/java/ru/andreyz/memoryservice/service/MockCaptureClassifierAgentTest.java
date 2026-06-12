package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockCaptureClassifierAgentTest {

    private final MockCaptureClassifierAgent agent = new MockCaptureClassifierAgent(new ObjectMapper());

    @Test
    void classifyFiles_routesExplicitMarkersToAllSupportedTypes() {
        List<CaptureService.CaptureFile> files = List.of(
                new CaptureService.CaptureFile("task.md", "TASK: E2E task | Нужно сделать действие HIGH"),
                new CaptureService.CaptureFile("risk.md", "RISK: E2E risk | Есть операционный риск"),
                new CaptureService.CaptureFile("note.md", "NOTE: E2E note | Информация к сведению"),
                new CaptureService.CaptureFile("question.md", "QUESTION: E2E question | Что делать дальше?"),
                new CaptureService.CaptureFile("person.md", "PERSON_NOTE: E2E Person | Хочет архитектурные задачи"),
                new CaptureService.CaptureFile("knowledge.md", "KNOWLEDGE: E2E knowledge | Runbook details"),
                new CaptureService.CaptureFile("journal.md", "JOURNAL: E2E journal | Итоги дня")
        );

        List<ClassifiedCapture> result = agent.classifyFiles(files, "context");

        assertThat(result).extracting(ClassifiedCapture::type)
                .containsExactly("TASK", "RISK", "NOTE", "QUESTION", "PERSON_NOTE", "KNOWLEDGE", "JOURNAL");
        assertThat(result.get(0).file()).isEqualTo("task.md");
        assertThat(result.get(0).title()).isEqualTo("E2E task");
        assertThat(result.get(0).body()).isEqualTo("Нужно сделать действие HIGH");
        assertThat(result.get(0).priority()).isEqualTo("HIGH");
        assertThat(result.get(2).tags()).isEqualTo("capture,mock");
        assertThat(result.get(4).title()).isEqualTo("E2E Person");
    }

    @Test
    void classifyFiles_defaultsToTaskForUnmarkedText() {
        List<ClassifiedCapture> result = agent.classifyFiles(
                List.of(new CaptureService.CaptureFile("plain.md", "Нужно обновить runbook")),
                "context");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("TASK");
        assertThat(result.get(0).priority()).isEqualTo("NORMAL");
    }
}
