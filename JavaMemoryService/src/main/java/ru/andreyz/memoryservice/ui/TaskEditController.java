package ru.andreyz.memoryservice.ui;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.EditTaskRequest;
import ru.andreyz.memoryservice.service.TaskDescriptionService;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.LocalDate;

@Controller
@RequestMapping("/ui/tasks")
public class TaskEditController {

    private final TaskService taskService;
    private final TaskDescriptionService taskDescriptionService;

    public TaskEditController(TaskService taskService, TaskDescriptionService taskDescriptionService) {
        this.taskService = taskService;
        this.taskDescriptionService = taskDescriptionService;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Task task = taskService.findById(id);
        String description = taskDescriptionService.getContent(id);
        model.addAttribute("task", task);
        model.addAttribute("description", description);
        model.addAttribute("exportUrl", "/api/tasks/%d/description/export-md".formatted(id));
        return "task-edit";
    }

    @PutMapping("/{id}/edit")
    public String saveEdit(@PathVariable Long id,
                           @RequestParam String title,
                           @RequestParam(required = false) String priority,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                           @RequestParam(required = false, defaultValue = "") String description) {
        taskService.edit(id, new EditTaskRequest(title, null, priority, status, dueDate));
        taskDescriptionService.update(id, description);
        return "redirect:/ui/today";
    }
}
