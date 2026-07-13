package ru.andreyz.memoryservice.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.TaskLink;
import ru.andreyz.memoryservice.dto.TaskLinkResponse;
import ru.andreyz.memoryservice.repository.TaskLinkRepository;
import ru.andreyz.memoryservice.repository.TaskRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class TaskLinkService {

    private final TaskRepository taskRepository;
    private final TaskLinkRepository taskLinkRepository;

    public TaskLinkService(TaskRepository taskRepository, TaskLinkRepository taskLinkRepository) {
        this.taskRepository = taskRepository;
        this.taskLinkRepository = taskLinkRepository;
    }

    public TaskLinkResponse create(Long fromTaskId, Long toTaskId, String linkType) {
        requireTask(fromTaskId);
        requireTask(toTaskId);
        if (fromTaskId.equals(toTaskId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot link a task to itself");
        }
        String normalizedType = normalizeLinkType(linkType);
        if (taskLinkRepository.existsByFromTaskIdAndToTaskIdAndLinkType(fromTaskId, toTaskId, normalizedType)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This link already exists");
        }
        TaskLink saved = taskLinkRepository.save(new TaskLink(null, fromTaskId, toTaskId, normalizedType, Instant.now()));
        return toResponse(saved, fromTaskId, "OUT");
    }

    public List<TaskLinkResponse> list(Long taskId) {
        requireTask(taskId);
        List<TaskLinkResponse> result = new ArrayList<>();
        taskLinkRepository.findByFromTaskIdOrderByCreatedAtDesc(taskId)
                .forEach(link -> result.add(toResponse(link, taskId, "OUT")));
        taskLinkRepository.findByToTaskIdOrderByCreatedAtDesc(taskId)
                .forEach(link -> result.add(toResponse(link, taskId, "IN")));
        return result.stream()
                .sorted(Comparator.comparing(TaskLinkResponse::createdAt).reversed())
                .toList();
    }

    public List<TaskLinkResponse> listRelated(Long taskId) {
        return list(taskId).stream()
                .filter(link -> TaskLink.RELATES_TO.equals(link.linkType()))
                .toList();
    }

    public void delete(Long taskId, Long linkId) {
        requireTask(taskId);
        TaskLink link = taskLinkRepository.findById(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found: " + linkId));
        if (!taskId.equals(link.fromTaskId()) && !taskId.equals(link.toTaskId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found: " + linkId);
        }
        taskLinkRepository.deleteById(linkId);
    }

    private TaskLinkResponse toResponse(TaskLink link, Long viewerTaskId, String direction) {
        Long relatedTaskId = "OUT".equals(direction) ? link.toTaskId() : link.fromTaskId();
        Task related = taskRepository.findById(relatedTaskId).orElse(null);
        String displayType = "OUT".equals(direction) ? link.linkType() : mirrorType(link.linkType());
        return new TaskLinkResponse(
                link.id(),
                direction,
                displayType,
                relatedTaskId,
                related != null ? related.title() : null,
                related != null ? related.status() : null,
                link.createdAt()
        );
    }

    private String mirrorType(String linkType) {
        return switch (linkType) {
            case TaskLink.BLOCKS -> "BLOCKED_BY";
            case TaskLink.PARENT_OF -> "CHILD_OF";
            case TaskLink.DUPLICATES -> "DUPLICATED_BY";
            default -> linkType;
        };
    }

    private String normalizeLinkType(String linkType) {
        if (linkType == null || linkType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "linkType is required");
        }
        String normalized = linkType.trim().toUpperCase();
        if (!TaskLink.VALID_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported link type: " + linkType);
        }
        return normalized;
    }

    private Task requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + taskId));
    }
}
