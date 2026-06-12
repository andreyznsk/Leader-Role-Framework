package ru.andreyz.memoryservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.PersonNameNote;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;
import ru.andreyz.memoryservice.repository.PersonNameNoteRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class CaptureRouter {

    private static final Logger log = LoggerFactory.getLogger(CaptureRouter.class);

    private final TaskService taskService;
    private final RiskService riskService;
    private final QuestionService questionService;
    private final NoteService noteService;
    private final PersonNameNoteRepository personNameNoteRepository;
    private final Path ragInboxDir;
    private final Path workspaceDir;

    public CaptureRouter(
            TaskService taskService,
            RiskService riskService,
            QuestionService questionService,
            NoteService noteService,
            PersonNameNoteRepository personNameNoteRepository,
            @Value("${app.rag.inbox-dir:../JavaRagService/rag-inbox}") String ragInboxDir,
            @Value("${app.workspace.dir:../workspace}") String workspaceDir) {
        this.taskService = taskService;
        this.riskService = riskService;
        this.questionService = questionService;
        this.noteService = noteService;
        this.personNameNoteRepository = personNameNoteRepository;
        this.ragInboxDir = Path.of(ragInboxDir);
        this.workspaceDir = Path.of(workspaceDir);
    }

    public String route(ClassifiedCapture c) {
        return switch (c.type()) {
            case "TASK" -> routeTask(c);
            case "RISK" -> routeRisk(c);
            case "NOTE" -> routeNote(c);
            case "QUESTION" -> routeQuestion(c);
            case "PERSON_NOTE" -> routePersonNote(c);
            case "KNOWLEDGE" -> routeKnowledge(c);
            case "JOURNAL" -> routeJournal(c);
            default -> {
                log.warn("Unknown capture type: {}", c.type());
                yield "UNKNOWN";
            }
        };
    }

    private String routeTask(ClassifiedCapture c) {
        taskService.createPending(c.title(), c.body(), null, null, c.priority());
        return "tasks/pending";
    }

    private String routeRisk(ClassifiedCapture c) {
        riskService.create(c.title(), c.body(), "MEDIUM", "MEDIUM");
        return "risks";
    }

    private String routeNote(ClassifiedCapture c) {
        String text = c.body() != null && !c.body().isBlank() ? c.body() : c.title();
        noteService.create(text, c.tags(), "capture");
        return "notes";
    }

    private String routeQuestion(ClassifiedCapture c) {
        questionService.create(c.title(), c.body());
        return "questions";
    }

    private String routePersonNote(ClassifiedCapture c) {
        PersonNameNote note = new PersonNameNote(null, c.title(), c.body(), Instant.now());
        personNameNoteRepository.save(note);
        return "person_notes/" + c.title();
    }

    private String routeKnowledge(ClassifiedCapture c) {
        try {
            Path capturesDir = ragInboxDir.resolve("captures");
            Files.createDirectories(capturesDir);
            String filename = LocalDate.now() + "-" + c.captureId() + ".md";
            String content = "# " + c.title() + "\n\n" + c.body() + "\n";
            Files.writeString(capturesDir.resolve(filename), content, StandardOpenOption.CREATE_NEW);
            return "rag-inbox/captures/" + filename;
        } catch (IOException e) {
            log.error("Failed to write knowledge capture {}: {}", c.captureId(), e.getMessage());
            return "rag-inbox/captures/ERROR";
        }
    }

    private String routeJournal(ClassifiedCapture c) {
        try {
            Path journalDir = workspaceDir.resolve("08_daily_journal");
            Files.createDirectories(journalDir);
            String filename = LocalDate.now() + ".md";
            Path journalFile = journalDir.resolve(filename);

            String entry = "\n## " + c.title() + "\n" + c.body() + "\n";
            Files.writeString(journalFile, entry,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "journal/" + filename;
        } catch (IOException e) {
            log.error("Failed to append journal for capture {}: {}", c.captureId(), e.getMessage());
            return "journal/ERROR";
        }
    }

}
