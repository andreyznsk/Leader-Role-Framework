package ru.andreyz.memoryservice.api;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.CreatePendingTaskRequest;
import ru.andreyz.memoryservice.dto.CreateTaskRequest;
import ru.andreyz.memoryservice.dto.EditTaskRequest;
import ru.andreyz.memoryservice.dto.LinkPendingTaskRequest;
import ru.andreyz.memoryservice.dto.MoveOverdueToTodayResponse;
import ru.andreyz.memoryservice.dto.MoveTaskRequest;
import ru.andreyz.memoryservice.dto.ReorderTaskRequest;
import ru.andreyz.memoryservice.dto.UpdateTaskDateRequest;
import ru.andreyz.memoryservice.dto.UpdateTaskDateResponse;
import ru.andreyz.memoryservice.dto.UpdateTaskStatusRequest;
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
                req.source(), null, req.dueDate(), req.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(task);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Task> editTask(@PathVariable Long id, @RequestBody EditTaskRequest req) {
        return ResponseEntity.ok(taskService.edit(id, req));
    }

    @PostMapping("/{id}/done")
    public ResponseEntity<Task> markDone(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.markDone(id));
    }

    @PostMapping("/{id}/toggle-done")
    public ResponseEntity<Task> toggleDone(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.toggleDone(id));
    }

    @PostMapping("/{id}/move")
    public ResponseEntity<Task> moveTask(@PathVariable Long id, @RequestBody MoveTaskRequest req) {
        return ResponseEntity.ok(taskService.moveToDate(id, req.toDate()));
    }

    @PatchMapping("/{id}/date")
    public ResponseEntity<UpdateTaskDateResponse> updateTaskDate(@PathVariable Long id,
                                                                 @RequestBody UpdateTaskDateRequest req) {
        if (req == null || req.date() == null) {
            return ResponseEntity.badRequest().build();
        }
        Task updated = taskService.updateTaskDate(id, req.date());
        return ResponseEntity.ok(new UpdateTaskDateResponse(updated.id(), req.date(), updated.dueDate()));
    }

    @PostMapping("/move-overdue-to-today")
    public ResponseEntity<MoveOverdueToTodayResponse> moveOverdueToToday() {
        return ResponseEntity.ok(new MoveOverdueToTodayResponse(taskService.moveOverdueToToday(), LocalDate.now()));
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
                req.title(), req.description(), req.emailId(), req.sender(), req.priority(), req.dueDate(),
                "mail-agent", req.pendingType(), req.suggestedTaskId(), req.agentConfidence(), req.agentReason(),
                req.sourceType(), req.sourceSubject(), req.sourceSender(), req.proposedDescriptionAppend());
        return ResponseEntity.status(HttpStatus.CREATED).body(task);
    }

    @PostMapping("/pending/{id}/link")
    public ResponseEntity<Task> linkPending(@PathVariable Long id, @RequestBody LinkPendingTaskRequest req) {
        return ResponseEntity.ok(taskService.linkPendingToTask(
                id,
                req.targetTaskId(),
                Boolean.TRUE.equals(req.appendSummary())
        ));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Task> updateStatus(@PathVariable Long id, @RequestBody UpdateTaskStatusRequest req) {
        if ("PENDING".equals(req.status()) || "DELETED".equals(req.status()) || "ARCHIVED".equals(req.status())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(taskService.updateStatus(id, req.status()));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Task> archiveTask(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.archive(id));
    }

    @PostMapping("/{id}/reorder")
    public ResponseEntity<Task> reorder(@PathVariable Long id, @RequestBody ReorderTaskRequest req) {
        return ResponseEntity.ok(taskService.reorder(id, req.direction(), req.position()));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> deleteTaskPost(@PathVariable Long id) {
        taskService.archive(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        taskService.archive(id);
        return ResponseEntity.noContent().build();
    }
}
