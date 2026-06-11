package ru.andreyz.memoryservice.ui;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.repository.DailyPlanRepository;
import ru.andreyz.memoryservice.repository.IncidentRepository;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/ui")
public class TodayViewController {

    private final TaskService taskService;
    private final DailyPlanRepository planRepository;
    private final IncidentRepository incidentRepository;

    public TodayViewController(TaskService taskService,
                               DailyPlanRepository planRepository,
                               IncidentRepository incidentRepository) {
        this.taskService = taskService;
        this.planRepository = planRepository;
        this.incidentRepository = incidentRepository;
    }

    @GetMapping({"/", "/today"})
    public String today(Model model) {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        List<Task> pending = taskService.findPending();
        List<Task> todayTasks = taskService.findByDate(today).stream()
                .filter(t -> !"DELETED".equals(t.status()) && !"PENDING".equals(t.status()))
                .toList();
        List<Task> tomorrowTasks = taskService.findByDate(tomorrow).stream()
                .filter(t -> !"DELETED".equals(t.status()) && !"PENDING".equals(t.status()))
                .toList();

        long doneTodayCount = todayTasks.stream().filter(t -> "DONE".equals(t.status())).count();
        long openIncidentsCount = incidentRepository.findByStatus("OPEN").size();

        model.addAttribute("today", today);
        model.addAttribute("tomorrow", tomorrow);
        model.addAttribute("pending", pending);
        model.addAttribute("todayTasks", todayTasks);
        model.addAttribute("tomorrowTasks", tomorrowTasks);
        model.addAttribute("todayTasksCount", todayTasks.size());
        model.addAttribute("pendingCount", pending.size());
        model.addAttribute("doneTodayCount", doneTodayCount);
        model.addAttribute("openIncidentsCount", openIncidentsCount);
        planRepository.findByPlanDate(today).ifPresent(p -> model.addAttribute("todaySummary", p.summary()));
        return "today";
    }

    @PostMapping("/tasks/add")
    public String addTask(@RequestParam String title,
                          @RequestParam(defaultValue = "NORMAL") String priority,
                          @RequestParam(required = false) String description) {
        taskService.createConfirmed(LocalDate.now(), title, priority, description, "MANUAL", null);
        return "redirect:/ui/today";
    }

    @PostMapping("/tasks/{id}/done")
    public String markDone(@PathVariable Long id) {
        taskService.markDone(id);
        return "redirect:/ui/today";
    }

    @PostMapping("/tasks/{id}/move")
    public String moveTask(@PathVariable Long id,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        taskService.moveToDate(id, toDate);
        return "redirect:/ui/today";
    }

    @PostMapping("/tasks/{id}/delete")
    public String deleteTask(@PathVariable Long id) {
        taskService.updateStatus(id, "DELETED");
        return "redirect:/ui/today";
    }

    @PostMapping("/tasks/{id}/confirm")
    public String confirmTask(@PathVariable Long id) {
        taskService.confirm(id);
        return "redirect:/ui/today";
    }

    @PostMapping("/tasks/{id}/reject")
    public String rejectTask(@PathVariable Long id) {
        taskService.reject(id);
        return "redirect:/ui/today";
    }

    @PostMapping("/tasks/{id}/reorder-up")
    public String reorderUp(@PathVariable Long id) {
        taskService.reorder(id, "up", null);
        return "redirect:/ui/today";
    }

    @PostMapping("/tasks/{id}/reorder-down")
    public String reorderDown(@PathVariable Long id) {
        taskService.reorder(id, "down", null);
        return "redirect:/ui/today";
    }
}
