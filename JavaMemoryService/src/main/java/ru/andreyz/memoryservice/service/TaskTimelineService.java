package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.TaskEvent;
import ru.andreyz.memoryservice.dto.TaskTimelineEventResponse;
import ru.andreyz.memoryservice.repository.TaskEventRepository;
import ru.andreyz.memoryservice.repository.TaskRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskTimelineService {

    private final TaskEventRepository taskEventRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public TaskTimelineService(TaskEventRepository taskEventRepository,
                               TaskRepository taskRepository,
                               ObjectMapper objectMapper) {
        this.taskEventRepository = taskEventRepository;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    public List<TaskEvent> findEvents(Long taskId) {
        requireTask(taskId);
        return taskEventRepository.findByTaskIdOrderByCreatedAtDesc(taskId);
    }

    public List<TaskTimelineEventResponse> getTimeline(Long taskId) {
        return findEvents(taskId).stream()
                .map(this::toResponse)
                .toList();
    }

    public TaskEvent addComment(Long taskId, String text) {
        requireTask(taskId);
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Comment text must not be blank");
        }
        return save(taskId, "COMMENT_ADDED", "USER", "User",
                null, fields("text", normalized),
                "UI", null, "Комментарий добавлен");
    }

    public void recordTaskCreated(Task task) {
        EventMeta meta = eventMetaForTask(task);
        save(task.id(), "TASK_CREATED", meta.actorType(), meta.actorName(),
                null, taskSnapshot(task),
                meta.sourceType(), task.emailId(),
                "Задача создана");
    }

    public void recordPendingTaskCreated(Task task) {
        EventMeta meta = eventMetaForTask(task);
        save(task.id(), "PENDING_TASK_CREATED", meta.actorType(), meta.actorName(),
                null, taskSnapshot(task),
                meta.sourceType(), task.emailId(),
                "Создана PENDING-задача");
    }

    public void recordPendingConfirmed(Task before, Task after) {
        save(after.id(), "PENDING_CONFIRMED", "USER", "User",
                fields("status", before.status(), "planId", before.planId(), "dueDate", formatDate(before.dueDate())),
                fields("status", after.status(), "planId", after.planId(), "dueDate", formatDate(after.dueDate())),
                "UI", after.emailId(),
                "PENDING подтверждена");
    }

    public void recordStatusChanged(Task before, String newStatus) {
        save(before.id(), "STATUS_CHANGED", "USER", "User",
                fields("status", before.status()),
                fields("status", newStatus),
                "UI", before.emailId(),
                "Статус изменен: %s -> %s".formatted(before.status(), newStatus));
    }

    public void recordTitleUpdated(Task before, String newTitle) {
        save(before.id(), "TITLE_UPDATED", "USER", "User",
                fields("title", before.title()),
                fields("title", newTitle),
                "UI", before.emailId(),
                "Название задачи обновлено");
    }

    public void recordDescriptionUpdated(Task task, String oldContent, String newContent) {
        save(task.id(), "DESCRIPTION_UPDATED", "USER", "User",
                fields("contentMd", safeText(oldContent)),
                fields("contentMd", safeText(newContent)),
                "UI", task.emailId(),
                "Описание обновлено");
    }

    public void recordPriorityChanged(Task before, String newPriority) {
        save(before.id(), "PRIORITY_CHANGED", "USER", "User",
                fields("priority", before.priority()),
                fields("priority", newPriority),
                "UI", before.emailId(),
                "Приоритет изменен: %s -> %s".formatted(before.priority(), newPriority));
    }

    public void recordDueDateChanged(Task before, LocalDate newDueDate) {
        save(before.id(), "DUE_DATE_CHANGED", "USER", "User",
                fields("dueDate", formatDate(before.dueDate())),
                fields("dueDate", formatDate(newDueDate)),
                "UI", before.emailId(),
                "Дедлайн изменен");
    }

    public void recordTaskArchived(Task before) {
        save(before.id(), "TASK_ARCHIVED", "USER", "User",
                fields("status", before.status()),
                fields("status", "ARCHIVED"),
                "UI", before.emailId(),
                "Задача архивирована");
    }

    public void recordEmailLinked(Task task, Task pendingTask, Long targetTaskId) {
        save(task.id(), "EMAIL_LINKED", "MAIL_AGENT", "JavaMailAgent",
                null,
                fields(
                        "pendingTaskId", pendingTask.id(),
                        "targetTaskId", targetTaskId,
                        "sourceSubject", pendingTask.sourceSubject(),
                        "sourceSender", pendingTask.sourceSender(),
                        "agentReason", pendingTask.agentReason(),
                        "agentConfidence", pendingTask.agentConfidence()
                ),
                pendingTask.sourceType() != null ? pendingTask.sourceType() : "EMAIL",
                pendingTask.emailId(),
                "Связано из письма" + summaryTail(pendingTask.sourceSubject(), pendingTask.sourceSender()));
    }

    private Task requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private TaskEvent save(Long taskId,
                           String eventType,
                           String actorType,
                           String actorName,
                           Object oldValue,
                           Object newValue,
                           String sourceType,
                           String sourceId,
                           String summary) {
        return taskEventRepository.save(new TaskEvent(
                null,
                taskId,
                eventType,
                actorType,
                actorName,
                toJson(oldValue),
                toJson(newValue),
                sourceType,
                sourceId,
                summary,
                Instant.now()
        ));
    }

    private TaskTimelineEventResponse toResponse(TaskEvent event) {
        return new TaskTimelineEventResponse(
                event.id(),
                event.taskId(),
                event.eventType(),
                event.actorType(),
                event.actorName(),
                readJson(event.oldValueJson()),
                readJson(event.newValueJson()),
                event.sourceType(),
                event.sourceId(),
                event.summary(),
                event.createdAt()
        );
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize task timeline payload", e);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse task timeline payload", e);
        }
    }

    private Map<String, Object> taskSnapshot(Task task) {
        return fields(
                "title", task.title(),
                "status", task.status(),
                "priority", task.priority(),
                "dueDate", formatDate(task.dueDate()),
                "source", task.source(),
                "emailId", task.emailId()
        );
    }

    private String formatDate(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return values;
    }

    private EventMeta eventMetaForTask(Task task) {
        String source = task.source() == null ? "" : task.source().trim().toUpperCase();
        return switch (source) {
            case "EMAIL" -> new EventMeta("MAIL_AGENT", "JavaMailAgent", "EMAIL");
            case "AGENT", "AI_AGENT" -> new EventMeta("AGENT", "AI-Agent", "AGENT");
            case "API" -> new EventMeta("API", "API", "API");
            default -> new EventMeta("USER", "User", "UI");
        };
    }

    private String summaryTail(String subject, String sender) {
        StringBuilder builder = new StringBuilder();
        if (subject != null && !subject.isBlank()) {
            builder.append(": ").append(subject.trim());
        }
        if (sender != null && !sender.isBlank()) {
            builder.append(" от ").append(sender.trim());
        }
        return builder.toString();
    }

    private record EventMeta(String actorType, String actorName, String sourceType) {}
}
