package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.DailyPlan;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.EditTaskRequest;
import ru.andreyz.memoryservice.repository.DailyPlanRepository;
import ru.andreyz.memoryservice.repository.TaskRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final DailyPlanRepository planRepository;

    public TaskService(TaskRepository taskRepository, DailyPlanRepository planRepository) {
        this.taskRepository = taskRepository;
        this.planRepository = planRepository;
    }

    public Task createConfirmed(LocalDate date, String title, String priority,
                                String description, String source, String emailId) {
        Long planId = getOrCreatePlan(date).id();
        Task task = new Task(null, planId, title, description,
                "TODO", priority != null ? priority : "NORMAL",
                date, source != null ? source : "MANUAL", emailId,
                Instant.now(), Instant.now());
        return taskRepository.save(task);
    }

    public Task createPending(String title, String description,
                              String emailId, String sender, String priority) {
        String desc = description != null ? description : (sender != null ? "От: " + sender : null);
        Task task = new Task(null, null, title, desc,
                "PENDING", priority != null ? priority : "NORMAL",
                null, "EMAIL", emailId,
                Instant.now(), Instant.now());
        return taskRepository.save(task);
    }

    public Task confirm(Long id) {
        Task task = findById(id);
        LocalDate today = LocalDate.now();
        Long planId = getOrCreatePlan(today).id();
        Task confirmed = new Task(task.id(), planId, task.title(), task.description(),
                "TODO", task.priority(), today, task.source(), task.emailId(),
                task.createdAt(), Instant.now());
        return taskRepository.save(confirmed);
    }

    public Task reject(Long id) {
        return updateStatus(id, "DELETED");
    }

    public Task edit(Long id, EditTaskRequest req) {
        Task task = findById(id);
        Task updated = new Task(task.id(), task.planId(),
                req.title() != null ? req.title() : task.title(),
                req.description() != null ? req.description() : task.description(),
                req.status() != null ? req.status() : task.status(),
                req.priority() != null ? req.priority() : task.priority(),
                req.dueDate() != null ? req.dueDate() : task.dueDate(),
                task.source(), task.emailId(),
                task.createdAt(), Instant.now());
        return taskRepository.save(updated);
    }

    public Task markDone(Long id) {
        return updateStatus(id, "DONE");
    }

    public Task moveToDate(Long id, LocalDate toDate) {
        Task task = findById(id);
        Long planId = getOrCreatePlan(toDate).id();
        Task moved = new Task(task.id(), planId, task.title(), task.description(),
                task.status().equals("DONE") ? "TODO" : task.status(),
                task.priority(), toDate, task.source(), task.emailId(),
                task.createdAt(), Instant.now());
        return taskRepository.save(moved);
    }

    public Task updateStatus(Long id, String status) {
        Task task = findById(id);
        Task updated = new Task(task.id(), task.planId(), task.title(), task.description(),
                status, task.priority(), task.dueDate(), task.source(), task.emailId(),
                task.createdAt(), Instant.now());
        return taskRepository.save(updated);
    }

    public List<Task> findByDate(LocalDate date) {
        return planRepository.findByPlanDate(date)
                .map(plan -> taskRepository.findByPlanId(plan.id()))
                .orElse(List.of());
    }

    public List<Task> findByDateAndStatus(LocalDate date, String status) {
        return findByDate(date).stream()
                .filter(t -> t.status().equalsIgnoreCase(status))
                .toList();
    }

    public List<Task> findPending() {
        return taskRepository.findByStatus("PENDING");
    }

    public Task findById(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
    }

    private DailyPlan getOrCreatePlan(LocalDate date) {
        return planRepository.findByPlanDate(date)
                .orElseGet(() -> planRepository.save(DailyPlan.create(date)));
    }
}
