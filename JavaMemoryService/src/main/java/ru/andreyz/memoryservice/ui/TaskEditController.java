package ru.andreyz.memoryservice.ui;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.TaskEvent;
import ru.andreyz.memoryservice.dto.EditTaskRequest;
import ru.andreyz.memoryservice.service.TaskAttachmentService;
import ru.andreyz.memoryservice.service.PeopleService;
import ru.andreyz.memoryservice.service.TaskDescriptionService;
import ru.andreyz.memoryservice.service.TaskRelationService;
import ru.andreyz.memoryservice.service.TaskLinkService;
import ru.andreyz.memoryservice.service.TaskService;
import ru.andreyz.memoryservice.service.TaskTimelineService;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/ui/tasks")
public class TaskEditController {

    private final TaskService taskService;
    private final TaskDescriptionService taskDescriptionService;
    private final TaskTimelineService taskTimelineService;
    private final TaskAttachmentService taskAttachmentService;
    private final PeopleService peopleService;
    private final TaskRelationService taskRelationService;
    private final TaskLinkService taskLinkService;

    public TaskEditController(TaskService taskService,
                              TaskDescriptionService taskDescriptionService,
                              TaskTimelineService taskTimelineService,
                              TaskAttachmentService taskAttachmentService,
                              PeopleService peopleService,
                              TaskRelationService taskRelationService,
                              TaskLinkService taskLinkService) {
        this.taskService = taskService;
        this.taskDescriptionService = taskDescriptionService;
        this.taskTimelineService = taskTimelineService;
        this.peopleService = peopleService;
        this.taskRelationService = taskRelationService;
        this.taskAttachmentService = taskAttachmentService;
        this.taskLinkService = taskLinkService;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Task task = taskService.findById(id);
        String description = taskDescriptionService.getContent(id);
        List<TaskEvent> timeline = taskTimelineService.findEvents(id);
        model.addAttribute("task", task);
        model.addAttribute("description", description);
        model.addAttribute("timeline", timeline.stream().limit(5).toList());
        model.addAttribute("timelineTotalCount", timeline.size());
        model.addAttribute("exportUrl", "/api/tasks/%d/description/export-md".formatted(id));
        model.addAttribute("attachments", taskAttachmentService.list(id));
        model.addAttribute("taskLinks", taskLinkService.list(id));
        model.addAttribute("relatedTaskLinks", taskLinkService.listRelated(id));
        model.addAttribute("people", peopleService.findAll().stream()
                .sorted(java.util.Comparator.comparing(person -> person.fullName().toLowerCase()))
                .toList());
        model.addAttribute("taskLabels", taskRelationService.listActiveLabels());
        return "task-edit";
    }

    @PutMapping("/{id}/edit")
    public String saveEdit(@PathVariable Long id,
                           @RequestParam String title,
                           @RequestParam(required = false) String priority,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                           @RequestParam(required = false) Long assignedPersonId,
                           @RequestParam(name = "labelIds", required = false) List<Long> labelIds,
                           @RequestParam(required = false, defaultValue = "") String description,
                           @RequestParam(required = false, defaultValue = "save_close") String action) {
        taskService.edit(id, new EditTaskRequest(title, null, priority, status, dueDate, assignedPersonId, labelIds));
        taskDescriptionService.update(id, description);
        if ("save".equalsIgnoreCase(action)) {
            return "redirect:/ui/tasks/%d/edit".formatted(id);
        }
        return "redirect:/ui/today";
    }
}
