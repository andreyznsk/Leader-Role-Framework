package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.service.TaskDescriptionService;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.LocalDate;
import java.util.List;

@Component
public class TaskTools {

    private final TaskService taskService;
    private final TaskDescriptionService taskDescriptionService;

    public TaskTools(TaskService taskService, TaskDescriptionService taskDescriptionService) {
        this.taskService = taskService;
        this.taskDescriptionService = taskDescriptionService;
    }

    @Tool(description = "Get tasks for a specific date. Optionally filter by status.")
    public List<Task> getTasks(
            @ToolParam(description = "Date in YYYY-MM-DD format") String date,
            @ToolParam(description = "Status filter: TODO|IN_PROGRESS|DONE|BLOCKED", required = false) String status) {
        LocalDate localDate = LocalDate.parse(date);
        return status != null
                ? taskService.findByDateAndStatus(localDate, status)
                : taskService.findByDate(localDate);
    }

    @Tool(description = "Create a confirmed task. IMPORTANT: Call only after explicit user confirmation ('да', 'ок', 'добавить').")
    public Task createTask(
            @ToolParam(description = "Task title") String title,
            @ToolParam(description = "Date YYYY-MM-DD") String date,
            @ToolParam(description = "Priority: LOW|NORMAL|HIGH|CRITICAL", required = false) String priority,
            @ToolParam(description = "Task description", required = false) String description,
            @ToolParam(description = "Source: MANUAL|AGENT") String source) {
        return taskService.createConfirmed(LocalDate.parse(date), title, priority, description, source, null);
    }

    @Tool(description = "Mark task as DONE")
    public Task markTaskDone(@ToolParam(description = "Task ID") Long id) {
        return taskService.markDone(id);
    }

    @Tool(description = "Move task to another date")
    public Task moveTask(
            @ToolParam(description = "Task ID") Long id,
            @ToolParam(description = "Target date YYYY-MM-DD") String toDate) {
        return taskService.moveToDate(id, LocalDate.parse(toDate));
    }

    @Tool(description = "Update task status")
    public Task updateTaskStatus(
            @ToolParam(description = "Task ID") Long id,
            @ToolParam(description = "Status: TODO|IN_PROGRESS|DONE|BLOCKED") String status) {
        return taskService.updateStatus(id, status);
    }

    @Tool(description = "Get the markdown description of a task from the database. Returns empty string if no description exists.")
    public String getTaskDescription(@ToolParam(description = "Task ID") Long id) {
        return taskDescriptionService.getContent(id);
    }

    @Tool(description = "Write or update the markdown description of a task in the database.")
    public void setTaskDescription(
            @ToolParam(description = "Task ID") Long id,
            @ToolParam(description = "Markdown content") String content) {
        taskDescriptionService.update(id, content);
    }
}
