package ru.andreyz.memoryservice.api;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.CreatePendingTaskRequest;
import ru.andreyz.memoryservice.dto.CreateTaskRequest;
import ru.andreyz.memoryservice.dto.EditTaskRequest;
import ru.andreyz.memoryservice.dto.MoveTaskRequest;
import ru.andreyz.memoryservice.dto.ReorderTaskRequest;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ResponseEntity<List<Task>> getTasks(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status) {
        List<Task> tasks = status != null
                ? taskService.findByDateAndStatus(date, status)
                : taskService.findByDate(date);
        return ResponseEntity.ok(tasks);
    }

    @PostMapping
    public ResponseEntity<Task> createTask(@RequestBody CreateTaskRequest req) {
        Task task = taskService.createConfirmed(
                req.date() != null ? req.date() : LocalDate.now(),
                req.title(), req.priority(), req.description(),
                req.source(), null);
        return ResponseEntity.ok(task);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Task> editTask(@PathVariable Long id, @RequestBody EditTaskRequest req) {
        return ResponseEntity.ok(taskService.edit(id, req));
    }

    @PostMapping("/{id}/done")
    public ResponseEntity<Task> markDone(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.markDone(id));
    }

    @PostMapping("/{id}/move")
    public ResponseEntity<Task> moveTask(@PathVariable Long id, @RequestBody MoveTaskRequest req) {
        return ResponseEntity.ok(taskService.moveToDate(id, req.toDate()));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Task> confirm(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.confirm(id));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Task> reject(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.reject(id));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<Task>> getPending() {
        return ResponseEntity.ok(taskService.findPending());
    }

    @PostMapping("/pending")
    public ResponseEntity<Task> createPending(@RequestBody CreatePendingTaskRequest req) {
        Task task = taskService.createPending(
                req.title(), req.description(), req.emailId(), req.sender(), req.priority());
        return ResponseEntity.ok(task);
    }

    @PostMapping("/{id}/reorder")
    public ResponseEntity<Task> reorder(@PathVariable Long id, @RequestBody ReorderTaskRequest req) {
        return ResponseEntity.ok(taskService.reorder(id, req.direction(), req.position()));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        taskService.updateStatus(id, "DELETED");
        return ResponseEntity.noContent().build();
    }
}
