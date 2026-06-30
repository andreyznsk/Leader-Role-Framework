package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.TaskDescription;
import ru.andreyz.memoryservice.dto.TaskDescriptionResponse;
import ru.andreyz.memoryservice.repository.TaskDescriptionRepository;
import ru.andreyz.memoryservice.repository.TaskRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TaskDescriptionService {

    private static final DateTimeFormatter EXPORT_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TaskRepository taskRepository;
    private final TaskDescriptionRepository taskDescriptionRepository;
    private final TaskTimelineService taskTimelineService;

    public TaskDescriptionService(TaskRepository taskRepository,
                                  TaskDescriptionRepository taskDescriptionRepository,
                                  TaskTimelineService taskTimelineService) {
        this.taskRepository = taskRepository;
        this.taskDescriptionRepository = taskDescriptionRepository;
        this.taskTimelineService = taskTimelineService;
    }

    public TaskDescriptionResponse get(Long taskId) {
        requireTask(taskId);
        return toResponse(taskDescriptionRepository.findByTaskId(taskId).orElse(null), taskId);
    }

    public String getContent(Long taskId) {
        requireTask(taskId);
        return taskDescriptionRepository.findByTaskId(taskId)
                .map(TaskDescription::contentMd)
                .orElse("");
    }

    public TaskDescriptionResponse update(Long taskId, String contentMd) {
        Task task = requireTask(taskId);
        String normalizedContent = normalize(contentMd);
        TaskDescription existing = taskDescriptionRepository.findByTaskId(taskId).orElse(null);
        String previousContent = existing != null ? normalize(existing.contentMd()) : "";
        String incomingHash = sha256(normalizedContent);
        if (existing != null && incomingHash.equals(existing.contentHash())) {
            return toResponse(existing, taskId);
        }
        Instant now = Instant.now();
        TaskDescription saved = taskDescriptionRepository.save(new TaskDescription(
                existing != null ? existing.id() : null,
                taskId,
                normalizedContent,
                incomingHash,
                existing != null ? existing.createdAt() : now,
                now
        ));
        syncTaskSummary(task, normalizedContent);
        taskTimelineService.recordDescriptionUpdated(task, previousContent, normalizedContent);
        return toResponse(saved, taskId);
    }

    public void initializeFromTaskField(Task task) {
        if (task == null || task.id() == null || task.description() == null || task.description().isBlank()) {
            return;
        }
        taskDescriptionRepository.findByTaskId(task.id())
                .orElseGet(() -> taskDescriptionRepository.save(new TaskDescription(
                        null,
                        task.id(),
                        task.description(),
                        sha256(task.description()),
                        Instant.now(),
                        Instant.now()
                )));
    }

    public void importFromLegacyFile(Long taskId, String contentMd) {
        Task task = requireTask(taskId);
        String normalizedContent = normalize(contentMd);
        TaskDescription existing = taskDescriptionRepository.findByTaskId(taskId).orElse(null);
        String incomingHash = sha256(normalizedContent);
        if (existing != null) {
            if (incomingHash.equals(existing.contentHash())) {
                return;
            }
            // After migration the DB is the source of truth. Legacy files may remain as backups
            // and must not overwrite newer content already stored in PostgreSQL.
            return;
        }
        Instant now = Instant.now();
        taskDescriptionRepository.save(new TaskDescription(
                null,
                taskId,
                normalizedContent,
                incomingHash,
                now,
                now
        ));
        syncTaskSummary(task, normalizedContent);
    }

    public Map<Long, TaskDescription> findByTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        return taskDescriptionRepository.findByTaskIdIn(taskIds).stream()
                .collect(Collectors.toMap(TaskDescription::taskId, Function.identity()));
    }

    public String exportMarkdown(Long taskId) {
        Task task = requireTask(taskId);
        String content = taskDescriptionRepository.findByTaskId(taskId)
                .map(TaskDescription::contentMd)
                .orElse("");
        LocalDate exportDate = task.dueDate() != null
                ? task.dueDate()
                : task.createdAt().atZone(ZoneId.systemDefault()).toLocalDate();
        return """
                # TASK-%d: %s

                **Status:** %s  
                **Priority:** %s  
                **Date:** %s  

                ---

                ## Description

                %s
                """.formatted(
                task.id(),
                task.title(),
                task.status(),
                task.priority(),
                EXPORT_DATE_FORMATTER.format(exportDate),
                content == null || content.isBlank() ? "" : content
        );
    }

    private Task requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private void syncTaskSummary(Task task, String markdown) {
        String summary = summarize(markdown);
        if (equalsNullable(task.description(), summary)) {
            return;
        }
        taskRepository.save(new Task(
                task.id(),
                task.planId(),
                task.title(),
                summary,
                task.status(),
                task.priority(),
                task.dueDate(),
                task.source(),
                task.emailId(),
                task.pendingType(),
                task.suggestedTaskId(),
                task.agentConfidence(),
                task.agentReason(),
                task.sourceType(),
                task.sourceSubject(),
                task.sourceSender(),
                task.proposedDescriptionAppend(),
                task.linkedToTaskId(),
                task.linkedAt(),
                task.sortOrder(),
                task.createdAt(),
                Instant.now()
        ));
    }

    private String summarize(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        String plain = markdown
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[(.*?)]\\((.*?)\\)", "$1")
                .replaceAll("[#>*_\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (plain.isBlank()) {
            return null;
        }
        return plain.length() <= 500 ? plain : plain.substring(0, 497) + "...";
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String normalize(String contentMd) {
        return contentMd == null ? "" : contentMd;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private static TaskDescriptionResponse toResponse(TaskDescription description, Long taskId) {
        if (description == null) {
            return new TaskDescriptionResponse(taskId, "", null, null);
        }
        return new TaskDescriptionResponse(
                taskId,
                Optional.ofNullable(description.contentMd()).orElse(""),
                description.contentHash(),
                description.updatedAt()
        );
    }
}
