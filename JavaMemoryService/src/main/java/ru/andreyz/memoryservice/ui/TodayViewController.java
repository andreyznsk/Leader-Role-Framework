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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
@RequestMapping("/ui")
public class TodayViewController {
    private static final Map<String, Integer> PRIORITY_ORDER = Map.of(
            "LOW", 0,
            "NORMAL", 1,
            "HIGH", 2,
            "CRITICAL", 3
    );
    private static final Map<String, Integer> STATUS_ORDER = Map.of(
            "IN_PROGRESS", 0,
            "TODO", 1,
            "BLOCKED", 2,
            "DONE", 3
    );

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
    public String today(@RequestParam(required = false) String priority,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateFrom,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateTo,
                        @RequestParam(required = false) String sortBy,
                        @RequestParam(required = false) String sortDir,
                        Model model) {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        boolean doneView = "DONE".equalsIgnoreCase(normalizeFilter(status));

        List<Task> pending = taskService.findPending();
        List<Task> allCurrentTasks = taskService.findCurrentTasks();
        List<Task> currentTasks = allCurrentTasks.stream()
                .filter(task -> isFilterMatch(task.priority(), priority))
                .filter(task -> isFilterMatch(task.status(), status))
                .filter(task -> matchesDateFilter(task.dueDate(), dueDate, dueDateFrom, dueDateTo))
                .filter(task -> doneView || !"DONE".equalsIgnoreCase(task.status()))
                .sorted(taskComparator(sortBy, sortDir))
                .toList();

        Map<String, Long> deadlineCounts = allCurrentTasks.stream()
                .filter(t -> t.dueDate() != null)
                .filter(t -> !"DONE".equals(t.status()))
                .collect(Collectors.groupingBy(t -> t.dueDate().toString(), Collectors.counting()));

        long doneTodayCount = allCurrentTasks.stream().filter(t -> "DONE".equals(t.status())).count();
        long openIncidentsCount = incidentRepository.findByStatus("OPEN").size();

        model.addAttribute("today", today);
        model.addAttribute("tomorrow", tomorrow);
        model.addAttribute("pending", pending);
        model.addAttribute("currentTasks", currentTasks);
        model.addAttribute("currentTasksCount", allCurrentTasks.size());
        model.addAttribute("pendingCount", pending.size());
        model.addAttribute("doneTodayCount", doneTodayCount);
        model.addAttribute("openIncidentsCount", openIncidentsCount);
        model.addAttribute("doneView", doneView);
        model.addAttribute("priorityFilter", normalizeFilter(priority));
        model.addAttribute("statusFilter", normalizeFilter(status));
        model.addAttribute("dueDateFilter", dueDate);
        model.addAttribute("dueDateFromFilter", dueDateFrom);
        model.addAttribute("dueDateToFilter", dueDateTo);
        String normalizedSortBy = normalizeFilter(sortBy);
        String normalizedSortDir = normalizeSortDir(sortDir);
        model.addAttribute("defaultSortActive", normalizedSortBy == null);
        model.addAttribute("sortBy", normalizedSortBy != null ? normalizedSortBy : "status");
        model.addAttribute("sortDir", normalizedSortBy != null ? normalizedSortDir : "asc");
        model.addAttribute("requestedSortBy", normalizedSortBy != null ? normalizedSortBy : "");
        model.addAttribute("requestedSortDir", normalizedSortBy != null ? normalizedSortDir : "");
        model.addAttribute("priorityOptions", List.of("LOW", "NORMAL", "HIGH", "CRITICAL"));
        model.addAttribute("statusOptions", List.of("TODO", "IN_PROGRESS", "BLOCKED", "DONE"));
        model.addAttribute("deadlineCounts", deadlineCounts);
        planRepository.findByPlanDate(today).ifPresent(p -> model.addAttribute("todaySummary", p.summary()));
        return "today";
    }

    private Comparator<Task> taskComparator(String sortBy, String sortDir) {
        Comparator<Task> fallback = Comparator.comparing(Task::sortOrder, Comparator.nullsLast(Integer::compareTo));
        Comparator<Task> priorityDesc = Comparator.comparingInt(
                (Task task) -> PRIORITY_ORDER.getOrDefault(task.priority(), Integer.MIN_VALUE)
        ).reversed();
        Comparator<Task> statusAsc = Comparator.comparing(
                (Task task) -> STATUS_ORDER.getOrDefault(task.status(), Integer.MAX_VALUE)
        );
        String normalizedSortBy = normalizeFilter(sortBy);
        if (normalizedSortBy == null) {
            return priorityDesc
                    .thenComparing(statusAsc)
                    .thenComparing(fallback);
        }

        Comparator<Task> comparator = switch (normalizedSortBy) {
            case "priority" -> Comparator.comparing(
                    task -> PRIORITY_ORDER.getOrDefault(task.priority(), Integer.MAX_VALUE)
            );
            case "status" -> statusAsc.thenComparing(priorityDesc);
            case "dueDate" -> Comparator.comparing(
                    Task::dueDate,
                    Comparator.nullsLast(LocalDate::compareTo)
            );
            default -> fallback;
        };

        if ("desc".equalsIgnoreCase(normalizeSortDir(sortDir))) {
            comparator = comparator.reversed();
        }
        return comparator.thenComparing(fallback);
    }

    private boolean matchesDateFilter(LocalDate taskDate, LocalDate exact, LocalDate from, LocalDate to) {
        if (exact != null) return exact.equals(taskDate);
        if (from == null && to == null) return true;
        if (taskDate == null) return false;
        if (from != null && taskDate.isBefore(from)) return false;
        if (to != null && taskDate.isAfter(to)) return false;
        return true;
    }

    private boolean isFilterMatch(String value, String filter) {
        String normalized = normalizeFilter(filter);
        return normalized == null || normalized.equalsIgnoreCase(value);
    }

    private String normalizeFilter(String value) {
        return Stream.ofNullable(value)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(null);
    }

    private String normalizeSortDir(String value) {
        return "desc".equalsIgnoreCase(value) ? "desc" : "asc";
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

    @PostMapping("/tasks/{id}/toggle-done")
    public String toggleDone(@PathVariable Long id) {
        taskService.toggleDone(id);
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
        taskService.archive(id);
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

    @PostMapping("/tasks/{id}/link")
    public String linkPendingTask(@PathVariable Long id,
                                  @RequestParam Long targetTaskId,
                                  @RequestParam(defaultValue = "false") boolean appendSummary) {
        taskService.linkPendingToTask(id, targetTaskId, appendSummary);
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
