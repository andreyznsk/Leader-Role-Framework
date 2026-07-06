package ru.andreyz.memoryservice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.dto.CreateTaskLinkRequest;
import ru.andreyz.memoryservice.dto.TaskLinkResponse;
import ru.andreyz.memoryservice.service.TaskLinkService;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskLinkController {

    private final TaskLinkService taskLinkService;

    public TaskLinkController(TaskLinkService taskLinkService) {
        this.taskLinkService = taskLinkService;
    }

    @GetMapping("/{id}/links")
    public ResponseEntity<List<TaskLinkResponse>> list(@PathVariable Long id) {
        return ResponseEntity.ok(taskLinkService.list(id));
    }

    @PostMapping("/{id}/links")
    public ResponseEntity<TaskLinkResponse> create(@PathVariable Long id, @RequestBody CreateTaskLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskLinkService.create(id, request.toTaskId(), request.linkType()));
    }

    @DeleteMapping("/{id}/links/{linkId}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @PathVariable Long linkId) {
        taskLinkService.delete(id, linkId);
        return ResponseEntity.noContent().build();
    }
}
