package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.andreyz.memoryservice.domain.PersonNameNote;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;
import ru.andreyz.memoryservice.repository.PersonNameNoteRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaptureRouterTest {

    @Mock TaskService taskService;
    @Mock RiskService riskService;
    @Mock QuestionService questionService;
    @Mock NoteService noteService;
    @Mock PersonNameNoteRepository personNameNoteRepository;

    @TempDir Path tempDir;

    CaptureRouter router;

    @BeforeEach
    void setUp() {
        router = new CaptureRouter(
                taskService, riskService, questionService, noteService, personNameNoteRepository,
                tempDir.resolve("rag-inbox").toString(),
                tempDir.resolve("workspace").toString()
        );
    }

    @Test
    void route_task_createsPendingTask() {
        ClassifiedCapture c = capture(1L, "TASK", "Write ADR", "details", "HIGH");

        String routedTo = router.route(c);

        verify(taskService).createPending("Write ADR", "details", null, null, "HIGH", null, "capture-bot");
        assertThat(routedTo).isEqualTo("tasks/pending");
    }

    @Test
    void route_risk_createsRiskWithMappedImpact() {
        ClassifiedCapture c = capture(2L, "RISK", "Single point of failure", "only one person knows deploy", "CRITICAL");

        router.route(c);

        verify(riskService).create("Single point of failure", "only one person knows deploy", "MEDIUM", "MEDIUM");
    }

    @Test
    void route_risk_usesMediumDefaults() {
        router.route(capture(3L, "RISK", "Minor risk", "body", "LOW"));

        verify(riskService).create(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("MEDIUM"), org.mockito.ArgumentMatchers.eq("MEDIUM"));
    }

    @Test
    void route_note_savesCaptureNote() {
        ClassifiedCapture c = capture(11L, "NOTE", "Observation", "raw note", "LOW");

        String routedTo = router.route(c);

        verify(noteService).create("raw note", null, "capture");
        assertThat(routedTo).isEqualTo("notes");
    }

    @Test
    void route_question_createsQuestion() {
        ClassifiedCapture c = capture(4L, "QUESTION", "How does circuit breaker work?", "context", "NORMAL");

        String routedTo = router.route(c);

        verify(questionService).create("How does circuit breaker work?", "context");
        assertThat(routedTo).isEqualTo("questions");
    }

    @Test
    void route_personNote_savesWithPersonName() {
        when(personNameNoteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ClassifiedCapture c = capture(5L, "PERSON_NOTE", "Петр", "Хочет перейти в другую команду", "NORMAL");

        String routedTo = router.route(c);

        verify(personNameNoteRepository).save(any(PersonNameNote.class));
        assertThat(routedTo).startsWith("person_notes/Петр");
    }

    @Test
    void route_knowledge_writesFileToRagInbox() throws IOException {
        ClassifiedCapture c = capture(6L, "KNOWLEDGE", "Saga pattern", "used for distributed tx", "LOW");

        String routedTo = router.route(c);

        Path capturesDir = tempDir.resolve("rag-inbox").resolve("captures");
        String expectedFile = LocalDate.now() + "-6.md";
        assertThat(capturesDir.resolve(expectedFile)).exists();
        assertThat(Files.readString(capturesDir.resolve(expectedFile)))
                .contains("Saga pattern")
                .contains("used for distributed tx");
        assertThat(routedTo).contains("captures");
    }

    @Test
    void route_journal_appendsToTodayFile() throws IOException {
        ClassifiedCapture c = capture(7L, "JOURNAL", "Day summary", "Closed 3 tasks", "LOW");

        router.route(c);

        Path journalFile = tempDir.resolve("workspace")
                .resolve("08_daily_journal")
                .resolve(LocalDate.now() + ".md");
        assertThat(journalFile).exists();
        assertThat(Files.readString(journalFile))
                .contains("Day summary")
                .contains("Closed 3 tasks");
    }

    @Test
    void route_journal_appendsTwice_keepsBothEntries() throws IOException {
        router.route(capture(8L, "JOURNAL", "Morning note", "body1", "LOW"));
        router.route(capture(9L, "JOURNAL", "Evening note", "body2", "LOW"));

        Path journalFile = tempDir.resolve("workspace")
                .resolve("08_daily_journal")
                .resolve(LocalDate.now() + ".md");
        String content = Files.readString(journalFile);
        assertThat(content).contains("Morning note").contains("Evening note");
    }

    @Test
    void route_unknownType_returnsUnknown() {
        ClassifiedCapture c = capture(10L, "WHATEVER", "title", "body", "LOW");

        String routedTo = router.route(c);

        assertThat(routedTo).isEqualTo("UNKNOWN");
    }

    private ClassifiedCapture capture(Long id, String type, String title, String body, String priority) {
        return new ClassifiedCapture(id, null, type, title, body, null, priority);
    }
}
