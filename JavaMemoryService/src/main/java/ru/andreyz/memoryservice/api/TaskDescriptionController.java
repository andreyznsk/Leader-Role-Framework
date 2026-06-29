package ru.andreyz.memoryservice.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.dto.TaskDescriptionResponse;
import ru.andreyz.memoryservice.dto.UpdateTaskDescriptionRequest;
import ru.andreyz.memoryservice.service.TaskDescriptionService;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/tasks")
public class TaskDescriptionController {

    private final TaskDescriptionService taskDescriptionService;
    private final ObjectMapper objectMapper;

    public TaskDescriptionController(TaskDescriptionService taskDescriptionService, ObjectMapper objectMapper) {
        this.taskDescriptionService = taskDescriptionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}/description")
    public ResponseEntity<?> getDescription(@PathVariable Long id,
                                            @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String acceptHeader) {
        TaskDescriptionResponse response = taskDescriptionService.get(id);
        if (acceptsPlainText(acceptHeader)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(response.contentMd());
        }
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/description")
    public ResponseEntity<?> putDescription(@PathVariable Long id,
                                            @RequestBody(required = false) byte[] body,
                                            HttpServletRequest request) throws Exception {
        String contentType = request.getContentType();
        String rawBody = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        if (contentType != null && contentType.startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            UpdateTaskDescriptionRequest updateRequest = objectMapper.readValue(rawBody, UpdateTaskDescriptionRequest.class);
            return ResponseEntity.ok(taskDescriptionService.update(id, updateRequest.contentMd()));
        }
        taskDescriptionService.update(id, rawBody);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/description/export-md")
    public ResponseEntity<byte[]> exportDescriptionMarkdown(@PathVariable Long id) {
        byte[] body = taskDescriptionService.exportMarkdown(id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("TASK-%d.md".formatted(id), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }

    private boolean acceptsPlainText(String acceptHeader) {
        return acceptHeader != null
                && acceptHeader.contains(MediaType.TEXT_PLAIN_VALUE)
                && !acceptHeader.contains(MediaType.APPLICATION_JSON_VALUE);
    }
}
