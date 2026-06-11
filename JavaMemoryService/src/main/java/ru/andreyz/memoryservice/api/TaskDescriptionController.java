package ru.andreyz.memoryservice.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.service.TaskFileService;
import ru.andreyz.memoryservice.service.TaskService;

@RestController
@RequestMapping("/api/tasks")
public class TaskDescriptionController {

    private final TaskFileService taskFileService;
    private final TaskService taskService;

    public TaskDescriptionController(TaskFileService taskFileService, TaskService taskService) {
        this.taskFileService = taskFileService;
        this.taskService = taskService;
    }

    @GetMapping(value = "/{id}/description", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getDescription(@PathVariable Long id) {
        taskService.findById(id); // verify task exists
        return taskFileService.read(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PutMapping(value = "/{id}/description", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> putDescription(@PathVariable Long id, @RequestBody String content) {
        taskService.findById(id); // verify task exists
        taskFileService.write(id, content);
        return ResponseEntity.ok().build();
    }
}
