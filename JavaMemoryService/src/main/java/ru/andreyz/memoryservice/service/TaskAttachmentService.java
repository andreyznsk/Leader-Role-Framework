package ru.andreyz.memoryservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.TaskAttachment;
import ru.andreyz.memoryservice.dto.TaskAttachmentResponse;
import ru.andreyz.memoryservice.repository.TaskAttachmentRepository;
import ru.andreyz.memoryservice.repository.TaskRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
public class TaskAttachmentService {

    private final TaskRepository taskRepository;
    private final TaskAttachmentRepository taskAttachmentRepository;
    private final AttachmentStorageService storageService;
    private final List<String> allowedMimePrefixes;
    private final long maxFileSizeBytes;

    public TaskAttachmentService(TaskRepository taskRepository,
                                  TaskAttachmentRepository taskAttachmentRepository,
                                  AttachmentStorageService storageService,
                                  @Value("${app.attachments.allowed-mime-prefixes}") String allowedMimePrefixesCsv,
                                  @Value("${app.attachments.max-file-size}") String maxFileSize) {
        this.taskRepository = taskRepository;
        this.taskAttachmentRepository = taskAttachmentRepository;
        this.storageService = storageService;
        this.allowedMimePrefixes = Arrays.stream(allowedMimePrefixesCsv.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .toList();
        this.maxFileSizeBytes = DataSize.parse(maxFileSize).toBytes();
    }

    public List<TaskAttachmentResponse> list(Long taskId) {
        requireTask(taskId);
        return taskAttachmentRepository.findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(this::toResponse)
                .toList();
    }

    public TaskAttachmentResponse uploadFile(Long taskId, MultipartFile file) {
        requireTask(taskId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is required");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds maximum allowed size");
        }
        String originalFilename = file.getOriginalFilename();
        validateFilename(originalFilename);
        String contentType = file.getContentType();
        if (contentType == null || allowedMimePrefixes.stream().noneMatch(contentType::startsWith)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported file type: " + contentType);
        }
        AttachmentStorageService.StoredFile stored;
        try {
            stored = storageService.store(taskId, originalFilename, file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        TaskAttachment saved = taskAttachmentRepository.save(new TaskAttachment(
                null,
                taskId,
                TaskAttachment.KIND_FILE,
                originalFilename,
                null,
                null,
                contentType,
                stored.size(),
                stored.storageRef(),
                Instant.now()
        ));
        return toResponse(saved);
    }

    public TaskAttachmentResponse addLink(Long taskId, String url, String title) {
        requireTask(taskId);
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL is required");
        }
        try {
            new URI(url);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }
        TaskAttachment saved = taskAttachmentRepository.save(new TaskAttachment(
                null,
                taskId,
                TaskAttachment.KIND_LINK,
                null,
                title,
                url,
                null,
                null,
                null,
                Instant.now()
        ));
        return toResponse(saved);
    }

    public AttachmentContent loadContent(Long taskId, Long attachmentId) {
        TaskAttachment attachment = requireAttachment(taskId, attachmentId);
        if (!TaskAttachment.KIND_FILE.equals(attachment.kind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attachment is not a file");
        }
        Resource resource = storageService.load(attachment.storageRef());
        return new AttachmentContent(resource, attachment.mimeType(), attachment.filename());
    }

    public void delete(Long taskId, Long attachmentId) {
        TaskAttachment attachment = requireAttachment(taskId, attachmentId);
        if (TaskAttachment.KIND_FILE.equals(attachment.kind()) && attachment.storageRef() != null) {
            storageService.delete(attachment.storageRef());
        }
        taskAttachmentRepository.deleteById(attachment.id());
    }

    private Task requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: " + taskId));
    }

    private TaskAttachment requireAttachment(Long taskId, Long attachmentId) {
        requireTask(taskId);
        TaskAttachment attachment = taskAttachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found: " + attachmentId));
        if (!attachment.taskId().equals(taskId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found: " + attachmentId);
        }
        return attachment;
    }

    private void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filename is required");
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
    }

    private TaskAttachmentResponse toResponse(TaskAttachment attachment) {
        String contentUrl = TaskAttachment.KIND_FILE.equals(attachment.kind())
                ? "/api/tasks/%d/attachments/%d/content".formatted(attachment.taskId(), attachment.id())
                : null;
        return new TaskAttachmentResponse(
                attachment.id(),
                attachment.taskId(),
                attachment.kind(),
                attachment.filename(),
                attachment.title(),
                attachment.url(),
                attachment.mimeType(),
                attachment.fileSize(),
                attachment.createdAt(),
                contentUrl
        );
    }

    public record AttachmentContent(Resource resource, String mimeType, String filename) {}
}
