package ru.andreyz.memoryservice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.memoryservice.domain.TaskLabel;
import ru.andreyz.memoryservice.dto.CreateTaskLabelRequest;
import ru.andreyz.memoryservice.dto.UpdateTaskLabelRequest;
import ru.andreyz.memoryservice.service.TaskRelationService;

import java.util.List;

@RestController
@RequestMapping("/api/task-labels")
public class TaskLabelController {

    private final TaskRelationService taskRelationService;

    public TaskLabelController(TaskRelationService taskRelationService) {
        this.taskRelationService = taskRelationService;
    }

    @GetMapping
    public ResponseEntity<List<TaskLabel>> list() {
        return ResponseEntity.ok(taskRelationService.listActiveLabels());
    }

    @PostMapping
    public ResponseEntity<TaskLabel> create(@RequestBody CreateTaskLabelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskRelationService.createLabel(request.name(), request.color()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TaskLabel> update(@PathVariable Long id, @RequestBody UpdateTaskLabelRequest request) {
        return ResponseEntity.ok(taskRelationService.updateLabel(id, request.name(), request.color(), request.archived()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<TaskLabel> archive(@PathVariable Long id) {
        return ResponseEntity.ok(taskRelationService.archiveLabel(id));
    }
}
