package ru.andreyz.memoryservice.api;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import ru.andreyz.memoryservice.dto.CreateLinkAttachmentRequest;
import ru.andreyz.memoryservice.dto.TaskAttachmentResponse;
import ru.andreyz.memoryservice.service.TaskAttachmentService;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskAttachmentController {

    private final TaskAttachmentService taskAttachmentService;

    public TaskAttachmentController(TaskAttachmentService taskAttachmentService) {
        this.taskAttachmentService = taskAttachmentService;
    }

    @GetMapping("/{id}/attachments")
    public ResponseEntity<List<TaskAttachmentResponse>> list(@PathVariable Long id) {
        return ResponseEntity.ok(taskAttachmentService.list(id));
    }

    @PostMapping("/{id}/attachments")
    public ResponseEntity<TaskAttachmentResponse> uploadFile(@PathVariable Long id,
                                                              @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskAttachmentService.uploadFile(id, file));
    }

    @PostMapping("/{id}/attachments/link")
    public ResponseEntity<TaskAttachmentResponse> addLink(@PathVariable Long id,
                                                           @RequestBody CreateLinkAttachmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskAttachmentService.addLink(id, request.url(), request.title()));
    }

    @GetMapping("/{id}/attachments/{attachmentId}/content")
    public ResponseEntity<Resource> content(@PathVariable Long id, @PathVariable Long attachmentId) {
        TaskAttachmentService.AttachmentContent content = taskAttachmentService.loadContent(id, attachmentId);
        MediaType mediaType = content.mimeType() != null
                ? MediaType.parseMediaType(content.mimeType())
                : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition disposition = "image".equals(mediaType.getType())
                ? ContentDisposition.inline().filename(content.filename(), StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(content.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(content.resource());
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @PathVariable Long attachmentId) {
        taskAttachmentService.delete(id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("File too large");
    }
}
