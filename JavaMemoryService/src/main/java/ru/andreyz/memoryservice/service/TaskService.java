package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.DailyPlan;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.UsageEventType;
import ru.andreyz.memoryservice.dto.EditTaskRequest;
import ru.andreyz.memoryservice.dto.UsageEventCommand;
import ru.andreyz.memoryservice.repository.DailyPlanRepository;
import ru.andreyz.memoryservice.repository.TaskRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final DailyPlanRepository planRepository;
    private final UsageEventService usageEventService;
    private final TaskDescriptionService taskDescriptionService;
    private final TaskTimelineService taskTimelineService;

    public TaskService(TaskRepository taskRepository,
                       DailyPlanRepository planRepository,
                       UsageEventService usageEventService,
                       TaskDescriptionService taskDescriptionService,
                       TaskTimelineService taskTimelineService) {
        this.taskRepository = taskRepository;
        this.planRepository = planRepository;
        this.usageEventService = usageEventService;
        this.taskDescriptionService = taskDescriptionService;
        this.taskTimelineService = taskTimelineService;
    }

    public Task createConfirmed(LocalDate date, String title, String priority,
                                String description, String source, String emailId) {
        return createConfirmed(date, title, priority, description, source, emailId, date, "TODO");
    }

    public Task createConfirmed(LocalDate date, String title, String priority,
                                String description, String source, String emailId,
                                LocalDate dueDate, String status) {
        Long planId = getOrCreatePlan(date).id();
        int sortOrder = taskRepository.findMaxSortOrderByPlanId(planId) + 1;
        Task task = new Task(null, planId, title, description,
                status != null ? status : "TODO", priority != null ? priority : "NORMAL",
                dueDate != null ? dueDate : date, source != null ? source : "MANUAL", emailId,
                "NEW_TASK", null, null, null, null, null, null, null,
                null, null,
                sortOrder, Instant.now(), Instant.now());
        Task saved = taskRepository.save(task);
        taskDescriptionService.initializeFromTaskField(saved);
        taskTimelineService.recordTaskCreated(saved);
        recordTaskCreated(saved, usageSource(source), false);
        return saved;
    }

    public Task createPending(String title, String description,
                              String emailId, String sender, String priority) {
        return createPending(title, description, emailId, sender, priority, null);
    }

    public Task createPending(String title, String description,
                              String emailId, String sender, String priority,
                              LocalDate dueDate) {
        return createPending(title, description, emailId, sender, priority, dueDate, "mail-agent");
    }

    public Task createPending(String title, String description,
                              String emailId, String sender, String priority,
                              LocalDate dueDate, String usageSource) {
        return createPending(title, description, emailId, sender, priority, dueDate,
                usageSource, "NEW_TASK", null, null, null, "EMAIL", null, sender, null);
    }

    public Task createPending(String title, String description,
                              String emailId, String sender, String priority,
                              LocalDate dueDate, String usageSource,
                              String pendingType, Long suggestedTaskId, Double agentConfidence, String agentReason,
                              String sourceType, String sourceSubject, String sourceSender,
                              String proposedDescriptionAppend) {
        if (emailId != null && !emailId.isBlank()) {
            var existing = taskRepository.findFirstByEmailId(emailId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String desc = description != null ? description : (sender != null ? "От: " + sender : null);
        Task task = new Task(null, null, title, desc,
                "PENDING", priority != null ? priority : "NORMAL",
                dueDate, "EMAIL", emailId,
                normalizePendingType(pendingType), suggestedTaskId, agentConfidence, agentReason,
                sourceType != null ? sourceType : "EMAIL", sourceSubject, sourceSender != null ? sourceSender : sender,
                blankToNull(proposedDescriptionAppend),
                null, null,
                0, Instant.now(), Instant.now());
        Task saved = taskRepository.save(task);
        taskDescriptionService.initializeFromTaskField(saved);
        taskTimelineService.recordPendingTaskCreated(saved);
        boolean mailTask = "mail-agent".equals(usageSource);
        if (mailTask) {
            usageEventService.record(new UsageEventCommand(
                    UsageEventType.MAIL_TASK_CREATED,
                    usageSource,
                    "SUCCESS",
                    emailId,
                    "task",
                    String.valueOf(saved.id()),
                    null,
                    null,
                    java.util.Map.of("title", saved.title())
            ));
        }
        recordTaskCreated(saved, usageSource != null ? usageSource : "memory-service", mailTask);
        return saved;
    }

    public Task confirm(Long id) {
        Task task = findById(id);
        LocalDate today = LocalDate.now();
        Long planId = getOrCreatePlan(today).id();
        int sortOrder = taskRepository.findMaxSortOrderByPlanId(planId) + 1;
        Task confirmed = new Task(task.id(), planId, task.title(), task.description(),
                "TODO", task.priority(), today, task.source(), task.emailId(),
                task.pendingType(), task.suggestedTaskId(), task.agentConfidence(), task.agentReason(),
                task.sourceType(), task.sourceSubject(), task.sourceSender(), task.proposedDescriptionAppend(),
                task.linkedToTaskId(), task.linkedAt(),
                sortOrder, task.createdAt(), Instant.now());
        Task saved = taskRepository.save(confirmed);
        taskTimelineService.recordPendingConfirmed(task, saved);
        return saved;
    }

    public Task reject(Long id) {
        return archive(id);
    }

    public void deleteTask(Long id) {
        archive(id);
    }

    public Task archive(Long id) {
        Task task = findById(id);
        if ("ARCHIVED".equals(task.status())) {
            return task;
        }
        Task archived = new Task(task.id(), task.planId(), task.title(), task.description(),
                "ARCHIVED", task.priority(), task.dueDate(), task.source(), task.emailId(),
                task.pendingType(), task.suggestedTaskId(), task.agentConfidence(), task.agentReason(),
                task.sourceType(), task.sourceSubject(), task.sourceSender(), task.proposedDescriptionAppend(),
                task.linkedToTaskId(), task.linkedAt(),
                task.sortOrder(), task.createdAt(), Instant.now());
        Task saved = taskRepository.save(archived);
        taskTimelineService.recordTaskArchived(task);
        return saved;
    }

    public Task edit(Long id, EditTaskRequest req) {
        Task task = findById(id);
        String newTitle = req.title() != null ? req.title() : task.title();
        String newStatus = req.status() != null ? req.status() : task.status();
        String newPriority = req.priority() != null ? req.priority() : task.priority();
        LocalDate newDueDate = req.dueDate() != null ? req.dueDate() : task.dueDate();
        Task updated = new Task(task.id(), task.planId(),
                newTitle,
                task.description(),
                newStatus,
                newPriority,
                newDueDate,
                task.source(), task.emailId(),
                task.pendingType(), task.suggestedTaskId(), task.agentConfidence(), task.agentReason(),
                task.sourceType(), task.sourceSubject(), task.sourceSender(), task.proposedDescriptionAppend(),
                task.linkedToTaskId(), task.linkedAt(),
                task.sortOrder(), task.createdAt(), Instant.now());
        Task saved = taskRepository.save(updated);
        recordEditTimeline(task, newTitle, newStatus, newPriority, newDueDate);
        if (req.description() != null) {
            taskDescriptionService.update(id, req.description());
            return findById(id);
        }
        return saved;
    }

    public Task markDone(Long id) {
        return updateStatus(id, "DONE");
    }

    public Task toggleDone(Long id) {
        Task task = findById(id);
        return updateStatus(id, "DONE".equals(task.status()) ? "TODO" : "DONE");
    }

    public Task moveToDate(Long id, LocalDate toDate) {
        Task task = findById(id);
        Long planId = getOrCreatePlan(toDate).id();
        int sortOrder = taskRepository.findMaxSortOrderByPlanId(planId) + 1;
        String newStatus = task.status().equals("DONE") ? "TODO" : task.status();
        Task moved = new Task(task.id(), planId, task.title(), task.description(),
                newStatus,
                task.priority(), toDate, task.source(), task.emailId(),
                task.pendingType(), task.suggestedTaskId(), task.agentConfidence(), task.agentReason(),
                task.sourceType(), task.sourceSubject(), task.sourceSender(), task.proposedDescriptionAppend(),
                task.linkedToTaskId(), task.linkedAt(),
                sortOrder, task.createdAt(), Instant.now());
        Task saved = taskRepository.save(moved);
        if (!equalsNullable(task.dueDate(), toDate)) {
            taskTimelineService.recordDueDateChanged(task, toDate);
        }
        if (!equalsNullable(task.status(), newStatus)) {
            taskTimelineService.recordStatusChanged(task, newStatus);
        }
        return saved;
    }

    public Task updateTaskDate(Long id, LocalDate date) {
        Task task = findById(id);
        Long planId = getOrCreatePlan(date).id();
        int sortOrder = taskRepository.findMaxSortOrderByPlanId(planId) + 1;
        Task updated = new Task(task.id(), planId, task.title(), task.description(),
                task.status(),
                task.priority(), date, task.source(), task.emailId(),
                task.pendingType(), task.suggestedTaskId(), task.agentConfidence(), task.agentReason(),
                task.sourceType(), task.sourceSubject(), task.sourceSender(), task.proposedDescriptionAppend(),
                task.linkedToTaskId(), task.linkedAt(),
                sortOrder, task.createdAt(), Instant.now());
        Task saved = taskRepository.save(updated);
        if (!equalsNullable(task.dueDate(), date)) {
            taskTimelineService.recordDueDateChanged(task, date);
        }
        return saved;
    }

    public int moveOverdueToToday() {
        LocalDate today = LocalDate.now();
        List<Task> overdueTasks = taskRepository.findCurrentTasks().stream()
                .filter(task -> task.dueDate() != null)
                .filter(task -> task.dueDate().isBefore(today))
                .filter(task -> !"DONE".equals(task.status()))
                .toList();

        overdueTasks.forEach(task -> updateTaskDate(task.id(), today));
        return overdueTasks.size();
    }

    public Task updateStatus(Long id, String status) {
        Task task = findById(id);
        if (equalsNullable(task.status(), status)) {
            return task;
        }
        Task updated = new Task(task.id(), task.planId(), task.title(), task.description(),
                status, task.priority(), task.dueDate(), task.source(), task.emailId(),
                task.pendingType(), task.suggestedTaskId(), task.agentConfidence(), task.agentReason(),
                task.sourceType(), task.sourceSubject(), task.sourceSender(), task.proposedDescriptionAppend(),
                task.linkedToTaskId(), task.linkedAt(),
                task.sortOrder(), task.createdAt(), Instant.now());
        Task saved = taskRepository.save(updated);
        taskTimelineService.recordStatusChanged(task, status);
        if ("DONE".equals(status) && !"DONE".equals(task.status())) {
            usageEventService.record(new UsageEventCommand(
                    UsageEventType.TASK_COMPLETED,
                    "memory-service",
                    "SUCCESS",
                    task.emailId(),
                    "task",
                    String.valueOf(saved.id()),
                    null,
                    null,
                    java.util.Map.of("title", saved.title())
            ));
        }
        return saved;
    }

    public Task reorder(Long id, String direction, Integer position) {
        Task task = findById(id);
        if (task.planId() == null) return task;

        List<Task> siblings = taskRepository.findByPlanIdOrderBySortOrder(task.planId()).stream()
                .filter(t -> !"DELETED".equals(t.status()) && !"PENDING".equals(t.status()) && !"ARCHIVED".equals(t.status()))
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
                a.pendingType(), a.suggestedTaskId(), a.agentConfidence(), a.agentReason(),
                a.sourceType(), a.sourceSubject(), a.sourceSender(), a.proposedDescriptionAppend(),
                a.linkedToTaskId(), a.linkedAt(),
                b.sortOrder(), a.createdAt(), Instant.now());
        Task updatedB = new Task(b.id(), b.planId(), b.title(), b.description(),
                b.status(), b.priority(), b.dueDate(), b.source(), b.emailId(),
                b.pendingType(), b.suggestedTaskId(), b.agentConfidence(), b.agentReason(),
                b.sourceType(), b.sourceSubject(), b.sourceSender(), b.proposedDescriptionAppend(),
                b.linkedToTaskId(), b.linkedAt(),
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
                        t.pendingType(), t.suggestedTaskId(), t.agentConfidence(), t.agentReason(),
                        t.sourceType(), t.sourceSubject(), t.sourceSender(), t.proposedDescriptionAppend(),
                        t.linkedToTaskId(), t.linkedAt(),
                        i, t.createdAt(), Instant.now()));
            }
        }
    }

    public Task linkPendingToTask(Long pendingId, Long targetTaskId, boolean appendSummary) {
        Task pending = findById(pendingId);
        if (!"PENDING".equals(pending.status())) {
            throw new IllegalArgumentException("Task is not pending: " + pendingId);
        }
        Long resolvedTargetId = targetTaskId != null ? targetTaskId : pending.suggestedTaskId();
        if (resolvedTargetId == null) {
            throw new IllegalArgumentException("targetTaskId is required");
        }
        Task target = findById(resolvedTargetId);
        if ("PENDING".equals(target.status()) || "ARCHIVED".equals(target.status())) {
            throw new IllegalArgumentException("Target task must be active: " + resolvedTargetId);
        }

        if (appendSummary && pending.proposedDescriptionAppend() != null && !pending.proposedDescriptionAppend().isBlank()) {
            appendDescription(target.id(), pending.proposedDescriptionAppend());
            target = findById(target.id());
        }

        taskTimelineService.recordEmailLinked(target, pending, resolvedTargetId);
        archive(linkedPendingTask(pending, resolvedTargetId));
        return target;
    }

    public List<Task> findByDate(LocalDate date) {
        return planRepository.findByPlanDate(date)
                .map(plan -> taskRepository.findByPlanIdOrderBySortOrder(plan.id()).stream()
                        .filter(task -> !"DELETED".equals(task.status()) && !"ARCHIVED".equals(task.status()))
                        .toList())
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

    public List<Task> findCurrentTasks() {
        return taskRepository.findCurrentTasks();
    }

    public Task findById(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
    }

    private Task archive(Task task) {
        Task archived = new Task(task.id(), task.planId(), task.title(), task.description(),
                "ARCHIVED", task.priority(), task.dueDate(), task.source(), task.emailId(),
                task.pendingType(), task.suggestedTaskId(), task.agentConfidence(), task.agentReason(),
                task.sourceType(), task.sourceSubject(), task.sourceSender(), task.proposedDescriptionAppend(),
                task.linkedToTaskId(), task.linkedAt(),
                task.sortOrder(), task.createdAt(), Instant.now());
        return taskRepository.save(archived);
    }

    private DailyPlan getOrCreatePlan(LocalDate date) {
        return planRepository.findByPlanDate(date)
                .orElseGet(() -> planRepository.save(DailyPlan.create(date)));
    }

    private void recordTaskCreated(Task task, String source, boolean explicitMailTaskEventAlreadyRecorded) {
        usageEventService.record(new UsageEventCommand(
                UsageEventType.TASK_CREATED,
                source,
                "SUCCESS",
                task.emailId(),
                "task",
                String.valueOf(task.id()),
                null,
                explicitMailTaskEventAlreadyRecorded ? 0 : null,
                java.util.Map.of("title", task.title(), "status", task.status())
        ));
    }

    private String usageSource(String source) {
        if (source == null || source.isBlank()) {
            return "memory-service";
        }
        if ("MANUAL".equalsIgnoreCase(source)) {
            return "manual-ui";
        }
        if ("EMAIL".equalsIgnoreCase(source)) {
            return "mail-agent";
        }
        if ("AGENT".equalsIgnoreCase(source) || "AI_AGENT".equalsIgnoreCase(source)) {
            return "ai-agent";
        }
        return source.toLowerCase(java.util.Locale.ROOT);
    }

    private void recordEditTimeline(Task original, String newTitle, String newStatus, String newPriority, LocalDate newDueDate) {
        if (!equalsNullable(original.title(), newTitle)) {
            taskTimelineService.recordTitleUpdated(original, newTitle);
        }
        if (!equalsNullable(original.status(), newStatus)) {
            taskTimelineService.recordStatusChanged(original, newStatus);
        }
        if (!equalsNullable(original.priority(), newPriority)) {
            taskTimelineService.recordPriorityChanged(original, newPriority);
        }
        if (!equalsNullable(original.dueDate(), newDueDate)) {
            taskTimelineService.recordDueDateChanged(original, newDueDate);
        }
    }

    private void appendDescription(Long taskId, String appendBlock) {
        String current = taskDescriptionService.getContent(taskId);
        String addition = appendBlock.trim();
        String updated = current == null || current.isBlank()
                ? addition
                : current.stripTrailing() + "\n\n---\n\n" + addition;
        taskDescriptionService.update(taskId, updated);
    }

    private Task linkedPendingTask(Task pending, Long resolvedTargetId) {
        return new Task(pending.id(), pending.planId(), pending.title(), pending.description(),
                pending.status(), pending.priority(), pending.dueDate(), pending.source(), pending.emailId(),
                pending.pendingType(), pending.suggestedTaskId(), pending.agentConfidence(), pending.agentReason(),
                pending.sourceType(), pending.sourceSubject(), pending.sourceSender(), pending.proposedDescriptionAppend(),
                resolvedTargetId, Instant.now(),
                pending.sortOrder(), pending.createdAt(), Instant.now());
    }

    private String normalizePendingType(String pendingType) {
        if (pendingType == null || pendingType.isBlank()) {
            return "NEW_TASK";
        }
        return pendingType.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
