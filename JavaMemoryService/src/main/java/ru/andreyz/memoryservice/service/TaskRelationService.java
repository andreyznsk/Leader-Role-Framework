package ru.andreyz.memoryservice.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.TaskLabel;
import ru.andreyz.memoryservice.repository.PersonRepository;
import ru.andreyz.memoryservice.repository.TaskLabelRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class TaskRelationService {

    private final PersonRepository personRepository;
    private final TaskLabelRepository taskLabelRepository;
    private final JdbcClient jdbcClient;

    public TaskRelationService(PersonRepository personRepository,
                               TaskLabelRepository taskLabelRepository,
                               JdbcClient jdbcClient) {
        this.personRepository = personRepository;
        this.taskLabelRepository = taskLabelRepository;
        this.jdbcClient = jdbcClient;
    }

    public List<TaskLabel> listActiveLabels() {
        return taskLabelRepository.findByArchivedFalseOrderByNameAsc();
    }

    public TaskLabel createLabel(String name, String color) {
        String normalizedName = requireLabelName(name);
        try {
            return taskLabelRepository.save(new TaskLabel(
                    null,
                    normalizedName,
                    normalizeColor(color),
                    false,
                    Instant.now(),
                    Instant.now()
            ));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Task label already exists: " + normalizedName, e);
        }
    }

    public TaskLabel updateLabel(Long id, String name, String color, Boolean archived) {
        TaskLabel existing = taskLabelRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Task label not found: " + id));
        String normalizedName = name != null ? requireLabelName(name) : existing.name();
        try {
            return taskLabelRepository.save(new TaskLabel(
                    existing.id(),
                    normalizedName,
                    color != null ? normalizeColor(color) : existing.color(),
                    archived != null ? archived : existing.archived(),
                    existing.createdAt(),
                    Instant.now()
            ));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Task label already exists: " + normalizedName, e);
        }
    }

    public TaskLabel archiveLabel(Long id) {
        return updateLabel(id, null, null, true);
    }

    public String findPersonName(Long personId) {
        if (personId == null) {
            return null;
        }
        return personRepository.findById(personId)
                .map(Person::fullName)
                .orElse(null);
    }

    public void validateStatusAssignment(String status, Long assignedPersonId) {
        String normalizedStatus = normalizeStatus(status);
        if (!"DELEGATED".equals(normalizedStatus)) {
            return;
        }
        if (assignedPersonId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "assignedPersonId is required for DELEGATED status");
        }
        if (!personRepository.existsById(assignedPersonId)) {
            throw new ResponseStatusException(BAD_REQUEST, "Assigned person not found: " + assignedPersonId);
        }
    }

    public List<Long> normalizeAndValidateLabelIds(List<Long> labelIds) {
        if (labelIds == null) {
            return List.of();
        }
        List<Long> normalized = labelIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return List.of();
        }
        Set<Long> existingIds = StreamSupport.stream(taskLabelRepository.findAllById(normalized).spliterator(), false)
                .filter(label -> !label.archived())
                .map(TaskLabel::id)
                .collect(Collectors.toSet());
        List<Long> missing = normalized.stream()
                .filter(id -> !existingIds.contains(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown or archived labelIds: " + missing);
        }
        return normalized;
    }

    public void replaceTaskLabels(Long taskId, List<Long> labelIds) {
        List<Long> normalized = normalizeAndValidateLabelIds(labelIds);
        jdbcClient.sql("DELETE FROM task_label_mapping WHERE task_id = :taskId")
                .param("taskId", taskId)
                .update();
        for (Long labelId : normalized) {
            jdbcClient.sql("""
                    INSERT INTO task_label_mapping(task_id, label_id)
                    VALUES (:taskId, :labelId)
                    """)
                    .param("taskId", taskId)
                    .param("labelId", labelId)
                    .update();
        }
    }

    public Task enrich(Task task) {
        if (task == null) {
            return null;
        }
        return enrich(List.of(task)).stream().findFirst().orElse(task);
    }

    public List<Task> enrich(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, Person> peopleById = loadPeople(tasks);
        Map<Long, List<TaskLabel>> labelsByTaskId = loadLabelsByTaskId(tasks.stream().map(Task::id).toList());
        return tasks.stream()
                .map(task -> copyWithRelations(
                        task,
                        task.assignedPersonId() != null ? peopleById.get(task.assignedPersonId()) : null,
                        task.id() != null ? labelsByTaskId.getOrDefault(task.id(), List.of()) : List.of()
                ))
                .toList();
    }

    private Map<Long, Person> loadPeople(List<Task> tasks) {
        List<Long> personIds = tasks.stream()
                .map(Task::assignedPersonId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Person> peopleById = new LinkedHashMap<>();
        personRepository.findAllById(personIds).forEach(person -> peopleById.put(person.id(), person));
        return peopleById;
    }

    private Map<Long, List<TaskLabel>> loadLabelsByTaskId(List<Long> taskIds) {
        List<Long> normalizedTaskIds = taskIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedTaskIds.isEmpty()) {
            return Map.of();
        }
        List<TaskLabelLink> rows = jdbcClient.sql("""
                SELECT tlm.task_id,
                       tl.id,
                       tl.name,
                       tl.color,
                       tl.archived,
                       tl.created_at,
                       tl.updated_at
                FROM task_label_mapping tlm
                JOIN task_labels tl ON tl.id = tlm.label_id
                WHERE tlm.task_id IN (:taskIds)
                ORDER BY tl.name ASC
                """)
                .param("taskIds", normalizedTaskIds)
                .query(this::mapTaskLabelLink)
                .list();

        Map<Long, List<TaskLabel>> labelsByTaskId = new LinkedHashMap<>();
        for (TaskLabelLink row : rows) {
            labelsByTaskId.computeIfAbsent(row.taskId(), ignored -> new ArrayList<>()).add(row.label());
        }
        return labelsByTaskId;
    }

    private Task copyWithRelations(Task task, Person assignedPerson, List<TaskLabel> labels) {
        List<TaskLabel> normalizedLabels = labels == null ? List.of() : List.copyOf(labels);
        List<Long> labelIds = normalizedLabels.stream().map(TaskLabel::id).toList();
        return new Task(
                task.id(),
                task.planId(),
                task.title(),
                task.description(),
                task.status(),
                task.priority(),
                task.dueDate(),
                task.assignedPersonId(),
                assignedPerson,
                labelIds,
                normalizedLabels,
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
                task.updatedAt()
        );
    }

    private TaskLabelLink mapTaskLabelLink(ResultSet rs, int rowNum) throws SQLException {
        return new TaskLabelLink(
                rs.getLong("task_id"),
                new TaskLabel(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getBoolean("archived"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                )
        );
    }

    private String requireLabelName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Task label name is required");
        }
        return normalized;
    }

    private String normalizeColor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private record TaskLabelLink(Long taskId, TaskLabel label) {}
}
