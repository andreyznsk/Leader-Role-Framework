package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.TaskEvent;
import ru.andreyz.memoryservice.dto.TaskTimelineCommentRequest;
import ru.andreyz.memoryservice.dto.TaskTimelineEventResponse;
import ru.andreyz.memoryservice.service.TaskTimelineService;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskTimelineController {

    private final TaskTimelineService taskTimelineService;

    public TaskTimelineController(TaskTimelineService taskTimelineService) {
        this.taskTimelineService = taskTimelineService;
    }

    @GetMapping("/{id}/timeline")
    public ResponseEntity<List<TaskTimelineEventResponse>> getTimeline(@PathVariable Long id) {
        return ResponseEntity.ok(taskTimelineService.getTimeline(id));
    }

    @PostMapping("/{id}/timeline/comment")
    public ResponseEntity<TaskEvent> addComment(@PathVariable Long id,
                                                @RequestBody TaskTimelineCommentRequest request) {
        return ResponseEntity.ok(taskTimelineService.addComment(id, request.text()));
    }
}
