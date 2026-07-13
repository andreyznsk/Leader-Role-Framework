package ru.andreyz.memoryservice.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import ru.andreyz.common.jira.JiraClient;
import ru.andreyz.common.jira.JiraIntegrationProperties;
import ru.andreyz.common.jira.dto.JiraAssignableUser;
import ru.andreyz.common.jira.dto.JiraCreateIssueRequest;
import ru.andreyz.common.jira.dto.JiraCreateIssueResult;
import ru.andreyz.common.jira.dto.JiraCurrentUser;
import ru.andreyz.common.jira.dto.JiraIssueType;
import ru.andreyz.common.jira.dto.JiraProject;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.domain.TaskExternalIssue;
import ru.andreyz.memoryservice.dto.CreateTaskJiraIssueRequest;
import ru.andreyz.memoryservice.dto.CreateTaskJiraIssueResponse;
import ru.andreyz.memoryservice.dto.JiraAssignableUserDto;
import ru.andreyz.memoryservice.dto.JiraIssueLinkDto;
import ru.andreyz.memoryservice.dto.JiraIssueTypeDto;
import ru.andreyz.memoryservice.dto.JiraProjectContextDto;
import ru.andreyz.memoryservice.dto.TaskJiraContextResponse;
import ru.andreyz.memoryservice.repository.TaskExternalIssueRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class TaskJiraService {

    private final TaskService taskService;
    private final TaskDescriptionService taskDescriptionService;
    private final TaskExternalIssueRepository taskExternalIssueRepository;
    private final JiraIntegrationProperties jiraProperties;
    private final JiraIntegrationStateService jiraIntegrationStateService;
    private final Optional<JiraClient> jiraClient;

    public TaskJiraService(TaskService taskService,
                           TaskDescriptionService taskDescriptionService,
                           TaskExternalIssueRepository taskExternalIssueRepository,
                           JiraIntegrationProperties jiraProperties,
                           JiraIntegrationStateService jiraIntegrationStateService,
                           Optional<JiraClient> jiraClient) {
        this.taskService = taskService;
        this.taskDescriptionService = taskDescriptionService;
        this.taskExternalIssueRepository = taskExternalIssueRepository;
        this.jiraProperties = jiraProperties;
        this.jiraIntegrationStateService = jiraIntegrationStateService;
        this.jiraClient = jiraClient;
    }

    public TaskJiraContextResponse getContext(Long taskId) {
        Task task = taskService.findById(taskId);
        JiraIntegrationSnapshot snapshot = jiraIntegrationStateService.getSnapshot();
        String description = defaultDescription(task);
        JiraIssueLinkDto existing = findIssueLink(taskId).orElse(null);
        if (snapshot.status() != JiraIntegrationStatus.AVAILABLE) {
            return new TaskJiraContextResponse(
                    snapshot.status().name(),
                    snapshot.status() == JiraIntegrationStatus.AVAILABLE,
                    snapshot.message(),
                    task.id(),
                    task.title(),
                    task.title(),
                    description,
                    jiraProperties.getDefaultProject(),
                    jiraProperties.getDefaultIssueType(),
                    existing,
                    List.of(),
                    toUserDto(snapshot.currentUser()),
                    null
            );
        }

        JiraClient client = requireClient();
        JiraCurrentUser currentUser = snapshot.currentUser() != null ? snapshot.currentUser() : client.getCurrentUser();
        Set<String> allowed = normalizeAllowedProjects();
        List<JiraProjectContextDto> projects = new ArrayList<>();
        for (JiraProject project : client.getProjects(allowed)) {
            List<JiraIssueTypeDto> issueTypes = client.getIssueTypes(project.key()).stream()
                    .map(type -> new JiraIssueTypeDto(type.id(), type.name()))
                    .toList();
            List<JiraAssignableUserDto> assignableUsers = buildAssignableUsers(client, project.key(), currentUser);
            projects.add(new JiraProjectContextDto(project.key(), project.name(), issueTypes, assignableUsers));
        }
        return new TaskJiraContextResponse(
                snapshot.status().name(),
                true,
                snapshot.message(),
                task.id(),
                task.title(),
                task.title(),
                description,
                jiraProperties.getDefaultProject(),
                jiraProperties.getDefaultIssueType(),
                existing,
                projects,
                toUserDto(currentUser),
                null
        );
    }

    public CreateTaskJiraIssueResponse createIssue(Long taskId, CreateTaskJiraIssueRequest request) {
        Task task = taskService.findById(taskId);
        JiraIntegrationSnapshot snapshot = jiraIntegrationStateService.getSnapshot();
        if (snapshot.status() != JiraIntegrationStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, snapshot.message());
        }
        validateProject(request.projectKey());
        validateSummary(request.summary());

        Optional<TaskExternalIssue> existing = taskExternalIssueRepository.findByTaskIdAndExternalSystem(taskId, TaskExternalIssue.EXTERNAL_SYSTEM_JIRA);
        if (existing.isPresent()) {
            TaskExternalIssue link = existing.get();
            if ("CREATED".equals(link.status())) {
                return new CreateTaskJiraIssueResponse(false, true, toDto(link));
            }
            if ("CREATING".equals(link.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Jira issue creation already in progress");
            }
        }

        TaskExternalIssue placeholder = existing
                .map(issue -> new TaskExternalIssue(issue.id(), issue.taskId(), issue.externalSystem(), issue.externalId(),
                        issue.externalKey(), issue.externalUrl(), request.projectKey(), "CREATING", null, issue.createdAt(), Instant.now()))
                .orElseGet(() -> new TaskExternalIssue(null, taskId, TaskExternalIssue.EXTERNAL_SYSTEM_JIRA, null, null, null,
                        request.projectKey(), "CREATING", null, Instant.now(), Instant.now()));
        try {
            placeholder = taskExternalIssueRepository.save(placeholder);
        } catch (DataIntegrityViolationException e) {
            TaskExternalIssue conflict = taskExternalIssueRepository.findByTaskIdAndExternalSystem(taskId, TaskExternalIssue.EXTERNAL_SYSTEM_JIRA)
                    .orElseThrow(() -> e);
            if ("CREATED".equals(conflict.status())) {
                return new CreateTaskJiraIssueResponse(false, true, toDto(conflict));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Jira issue creation already in progress");
        }

        try {
            JiraCreateIssueResult created = requireClient().createIssue(new JiraCreateIssueRequest(
                    request.projectKey(),
                    request.issueTypeId(),
                    request.summary().trim(),
                    enrichDescription(request.description(), task.id()),
                    blankToNull(request.assigneeAccountId())
            ));
            TaskExternalIssue saved = taskExternalIssueRepository.save(new TaskExternalIssue(
                    placeholder.id(),
                    taskId,
                    TaskExternalIssue.EXTERNAL_SYSTEM_JIRA,
                    created.id(),
                    created.key(),
                    created.url(),
                    request.projectKey(),
                    "CREATED",
                    null,
                    placeholder.createdAt(),
                    Instant.now()
            ));
            return new CreateTaskJiraIssueResponse(true, false, toDto(saved));
        } catch (RuntimeException e) {
            String safe = sanitize(e.getMessage());
            taskExternalIssueRepository.save(new TaskExternalIssue(
                    placeholder.id(),
                    taskId,
                    TaskExternalIssue.EXTERNAL_SYSTEM_JIRA,
                    null,
                    null,
                    null,
                    request.projectKey(),
                    "FAILED",
                    safe,
                    placeholder.createdAt(),
                    Instant.now()
            ));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, safe);
        }
    }

    public Map<Long, JiraIssueLinkDto> findCreatedIssueLinks(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, JiraIssueLinkDto> result = new LinkedHashMap<>();
        for (Long taskId : taskIds) {
            findIssueLink(taskId).ifPresent(issue -> result.put(taskId, issue));
        }
        return result;
    }

    public Optional<JiraIssueLinkDto> findIssueLink(Long taskId) {
        return taskExternalIssueRepository.findByTaskIdAndExternalSystem(taskId, TaskExternalIssue.EXTERNAL_SYSTEM_JIRA)
                .filter(issue -> "CREATED".equals(issue.status()))
                .map(this::toDto);
    }

    private List<JiraAssignableUserDto> buildAssignableUsers(JiraClient client,
                                                             String projectKey,
                                                             JiraCurrentUser currentUser) {
        LinkedHashMap<String, JiraAssignableUserDto> unique = new LinkedHashMap<>();
        if (currentUser != null) {
            client.getAssignableUsers(projectKey, firstNonBlank(currentUser.email(), currentUser.displayName(), currentUser.accountId()))
                    .stream()
                    .filter(user -> currentUser.accountId().equals(user.accountId()))
                    .forEach(user -> unique.putIfAbsent(user.accountId(), toUserDto(user)));
            unique.putIfAbsent(currentUser.accountId(), toUserDto(currentUser));
        }
        return List.copyOf(unique.values());
    }

    private Set<String> normalizeAllowedProjects() {
        return jiraProperties.getAllowedProjects().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void validateProject(String projectKey) {
        if (!normalizeAllowedProjects().contains(projectKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project is not allowed");
        }
    }

    private void validateSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Summary is required");
        }
    }

    private JiraClient requireClient() {
        return jiraClient.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Jira client is unavailable"));
    }

    private JiraIssueLinkDto toDto(TaskExternalIssue issue) {
        return new JiraIssueLinkDto(
                issue.taskId(),
                issue.externalId(),
                issue.externalKey(),
                issue.externalUrl(),
                issue.projectKey(),
                issue.status(),
                issue.errorMessage()
        );
    }

    private JiraAssignableUserDto toUserDto(JiraCurrentUser user) {
        if (user == null) {
            return null;
        }
        return new JiraAssignableUserDto(user.accountId(), user.displayName(), user.email());
    }

    private JiraAssignableUserDto toUserDto(JiraAssignableUser user) {
        return new JiraAssignableUserDto(user.accountId(), user.displayName(), user.email());
    }

    private String defaultDescription(Task task) {
        String description = taskDescriptionService.getContent(task.id());
        return StringUtils.hasText(description) ? description : "";
    }

    private String enrichDescription(String description, Long taskId) {
        String base = StringUtils.hasText(description) ? description.trim() : "";
        String marker = "LeaderOS Task ID: " + taskId;
        if (base.contains(marker)) {
            return base;
        }
        return base.isEmpty() ? marker : base + "\n\n" + marker;
    }

    private String sanitize(String message) {
        if (!StringUtils.hasText(message)) {
            return "Jira request failed";
        }
        return message
                .replaceAll("(?i)(authorization|bearer|basic)\\s+[^\\s,;]+", "$1 [redacted]")
                .replaceAll("(?i)(token|password)=([^\\s,;]+)", "$1=[redacted]");
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

}
