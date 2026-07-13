package ru.andreyz.memoryservice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.dto.CreateTaskJiraIssueRequest;
import ru.andreyz.memoryservice.dto.CreateTaskJiraIssueResponse;
import ru.andreyz.memoryservice.dto.TaskJiraContextResponse;
import ru.andreyz.memoryservice.service.TaskJiraService;

@RestController
@RequestMapping("/api/tasks")
public class TaskJiraController {

    private final TaskJiraService taskJiraService;

    public TaskJiraController(TaskJiraService taskJiraService) {
        this.taskJiraService = taskJiraService;
    }

    @GetMapping("/{id}/jira/context")
    public ResponseEntity<TaskJiraContextResponse> context(@PathVariable Long id) {
        return ResponseEntity.ok(taskJiraService.getContext(id));
    }

    @PostMapping("/{id}/jira/issues")
    public ResponseEntity<CreateTaskJiraIssueResponse> create(@PathVariable Long id,
                                                              @RequestBody CreateTaskJiraIssueRequest request) {
        CreateTaskJiraIssueResponse response = taskJiraService.createIssue(id, request);
        return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
    }
}
