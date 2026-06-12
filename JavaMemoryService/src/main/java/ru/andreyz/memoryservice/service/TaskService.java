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
        int sortOrder = taskRepository.findMaxSortOrderByPlanId(planId) + 1;
        Task task = new Task(null, planId, title, description,
                "TODO", priority != null ? priority : "NORMAL",
                date, source != null ? source : "MANUAL", emailId,
                sortOrder, Instant.now(), Instant.now());
        return taskRepository.save(task);
    }

    public Task createPending(String title, String description,
                              String emailId, String sender, String priority) {
        return createPending(title, description, emailId, sender, priority, null);
    }

    public Task createPending(String title, String description,
                              String emailId, String sender, String priority,
                              LocalDate dueDate) {
        String desc = description != null ? description : (sender != null ? "От: " + sender : null);
        Task task = new Task(null, null, title, desc,
                "PENDING", priority != null ? priority : "NORMAL",
                dueDate, "EMAIL", emailId,
                0, Instant.now(), Instant.now());
        return taskRepository.save(task);
    }

    public Task confirm(Long id) {
        Task task = findById(id);
        LocalDate today = LocalDate.now();
        Long planId = getOrCreatePlan(today).id();
        int sortOrder = taskRepository.findMaxSortOrderByPlanId(planId) + 1;
        Task confirmed = new Task(task.id(), planId, task.title(), task.description(),
                "TODO", task.priority(), today, task.source(), task.emailId(),
                sortOrder, task.createdAt(), Instant.now());
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
                task.sortOrder(), task.createdAt(), Instant.now());
        return taskRepository.save(updated);
    }

    public Task markDone(Long id) {
        return updateStatus(id, "DONE");
    }

    public Task moveToDate(Long id, LocalDate toDate) {
        Task task = findById(id);
        Long planId = getOrCreatePlan(toDate).id();
        int sortOrder = taskRepository.findMaxSortOrderByPlanId(planId) + 1;
        Task moved = new Task(task.id(), planId, task.title(), task.description(),
                task.status().equals("DONE") ? "TODO" : task.status(),
                task.priority(), toDate, task.source(), task.emailId(),
                sortOrder, task.createdAt(), Instant.now());
        return taskRepository.save(moved);
    }

    public Task updateStatus(Long id, String status) {
        Task task = findById(id);
        Task updated = new Task(task.id(), task.planId(), task.title(), task.description(),
                status, task.priority(), task.dueDate(), task.source(), task.emailId(),
                task.sortOrder(), task.createdAt(), Instant.now());
        return taskRepository.save(updated);
    }

    public Task reorder(Long id, String direction, Integer position) {
        Task task = findById(id);
        if (task.planId() == null) return task;

        List<Task> siblings = taskRepository.findByPlanIdOrderBySortOrder(task.planId()).stream()
                .filter(t -> !"DELETED".equals(t.status()) && !"PENDING".equals(t.status()))
                .toList();

        int idx = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).id().equals(id)) { idx = i; break; }
        }
        if (idx == -1) return task;

        if (position != null) {
            int target = Math.max(0, Math.min(position, siblings.size() - 1));
            reassignSortOrders(siblings, idx, target);
        } else if ("up".equalsIgnoreCase(direction) && idx > 0) {
            swapSortOrders(siblings.get(idx), siblings.get(idx - 1));
        } else if ("down".equalsIgnoreCase(direction) && idx < siblings.size() - 1) {
            swapSortOrders(siblings.get(idx), siblings.get(idx + 1));
        }

        return findById(id);
    }

    private void swapSortOrders(Task a, Task b) {
        Integer orderA = a.sortOrder();
        Task updatedA = new Task(a.id(), a.planId(), a.title(), a.description(),
                a.status(), a.priority(), a.dueDate(), a.source(), a.emailId(),
                b.sortOrder(), a.createdAt(), Instant.now());
        Task updatedB = new Task(b.id(), b.planId(), b.title(), b.description(),
                b.status(), b.priority(), b.dueDate(), b.source(), b.emailId(),
                orderA, b.createdAt(), Instant.now());
        taskRepository.save(updatedA);
        taskRepository.save(updatedB);
    }

    private void reassignSortOrders(List<Task> tasks, int fromIdx, int toIdx) {
        // Build new order by moving element at fromIdx to toIdx
        java.util.ArrayList<Task> reordered = new java.util.ArrayList<>(tasks);
        Task moved = reordered.remove(fromIdx);
        reordered.add(toIdx, moved);
        for (int i = 0; i < reordered.size(); i++) {
            Task t = reordered.get(i);
            if (!t.sortOrder().equals(i)) {
                taskRepository.save(new Task(t.id(), t.planId(), t.title(), t.description(),
                        t.status(), t.priority(), t.dueDate(), t.source(), t.emailId(),
                        i, t.createdAt(), Instant.now()));
            }
        }
    }

    public List<Task> findByDate(LocalDate date) {
        return planRepository.findByPlanDate(date)
                .map(plan -> taskRepository.findByPlanIdAndStatusNotOrderBySortOrder(plan.id(), "DELETED"))
                .orElse(List.of());
    }

    public List<Task> findByDateAndStatus(LocalDate date, String status) {
        return planRepository.findByPlanDate(date)
                .map(plan -> taskRepository.findByPlanIdOrderBySortOrder(plan.id()).stream()
                        .filter(t -> t.status().equalsIgnoreCase(status))
                        .toList())
                .orElse(List.of());
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
