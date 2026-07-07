package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.AgentProposalResponse;
import ru.andreyz.memoryservice.dto.TaskLinkResponse;
import ru.andreyz.memoryservice.service.AgentIntakeProposalService;
import ru.andreyz.memoryservice.service.TaskDescriptionService;
import ru.andreyz.memoryservice.service.TaskLinkService;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.LocalDate;
import java.util.List;

@Component
public class TaskTools {

    private final TaskService taskService;
    private final TaskDescriptionService taskDescriptionService;
    private final AgentIntakeProposalService agentIntakeProposalService;
    private final TaskLinkService taskLinkService;

    public TaskTools(TaskService taskService,
                     TaskDescriptionService taskDescriptionService,
                     AgentIntakeProposalService agentIntakeProposalService,
                     TaskLinkService taskLinkService) {
        this.taskService = taskService;
        this.taskDescriptionService = taskDescriptionService;
        this.agentIntakeProposalService = agentIntakeProposalService;
        this.taskLinkService = taskLinkService;
    }

    @Tool(description = "Get tasks for a specific date. Optionally filter by status.")
    public List<Task> getTasks(
            @ToolParam(description = "Date in YYYY-MM-DD format") String date,
            @ToolParam(description = "Status filter: TODO|RESEARCH|IN_PROGRESS|DELEGATED|DONE|BLOCKED", required = false) String status) {
        LocalDate localDate = LocalDate.parse(date);
        return status != null
                ? taskService.findByDateAndStatus(localDate, status)
                : taskService.findByDate(localDate);
    }

    @Tool(description = "Create a task proposal in Intake Gateway. This does not write directly to tasks. User must review and apply it in /ui/intake.")
    public AgentProposalResponse proposeTask(
            @ToolParam(description = "Task title") String title,
            @ToolParam(description = "Date YYYY-MM-DD") String date,
            @ToolParam(description = "Priority: LOW|NORMAL|HIGH|CRITICAL", required = false) String priority,
            @ToolParam(description = "Task description", required = false) String description,
            @ToolParam(description = "Optional run/session/source identifier", required = false) String sourceId) {
        return agentIntakeProposalService.createTaskProposal(title, date, priority, description, sourceId);
    }

    public Task markTaskDone(@ToolParam(description = "Task ID") Long id) {
        return taskService.markDone(id);
    }

    public Task moveTask(
            @ToolParam(description = "Task ID") Long id,
            @ToolParam(description = "Target date YYYY-MM-DD") String toDate) {
        return taskService.moveToDate(id, LocalDate.parse(toDate));
    }

    public Task updateTaskStatus(
            @ToolParam(description = "Task ID") Long id,
            @ToolParam(description = "Status: TODO|RESEARCH|IN_PROGRESS|DELEGATED|DONE|BLOCKED") String status) {
        return taskService.updateStatus(id, status);
    }

    @Tool(description = "Get the markdown description of a task from the database. Returns empty string if no description exists.")
    public String getTaskDescription(@ToolParam(description = "Task ID") Long id) {
        return taskDescriptionService.getContent(id);
    }

    @Tool(description = "Get links for a task (read-only): outgoing links and mirrored incoming links (e.g. BLOCKS -> BLOCKED_BY).")
    public List<TaskLinkResponse> getTaskLinks(@ToolParam(description = "Task ID") Long id) {
        return taskLinkService.list(id);
    }

    @Tool(description = "Create a task-link proposal in Intake Gateway. Does not write directly to task_links. User must review and apply it in /ui/intake.")
    public AgentProposalResponse proposeTaskLink(
            @ToolParam(description = "Source task ID") Long fromTaskId,
            @ToolParam(description = "Target task ID") Long toTaskId,
            @ToolParam(description = "Link type: RELATES_TO|BLOCKS|DUPLICATES|PARENT_OF") String linkType,
            @ToolParam(description = "Why this link is proposed", required = false) String reason,
            @ToolParam(description = "Optional run/session/source identifier", required = false) String sourceId) {
        return agentIntakeProposalService.createTaskLinkProposal(fromTaskId, toTaskId, linkType, reason, sourceId);
    }

    public void setTaskDescription(
            @ToolParam(description = "Task ID") Long id,
            @ToolParam(description = "Markdown content") String content) {
        taskDescriptionService.update(id, content);
    }
}
