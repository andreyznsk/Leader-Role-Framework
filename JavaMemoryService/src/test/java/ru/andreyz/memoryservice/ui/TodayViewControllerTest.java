package ru.andreyz.memoryservice.ui;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.repository.DailyPlanRepository;
import ru.andreyz.memoryservice.repository.IncidentRepository;
import ru.andreyz.memoryservice.service.JiraIntegrationStateService;
import ru.andreyz.memoryservice.service.PeopleService;
import ru.andreyz.memoryservice.service.TaskLinkService;
import ru.andreyz.memoryservice.service.TaskJiraService;
import ru.andreyz.memoryservice.service.TaskRelationService;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TodayViewControllerTest {

    @Test
    void filteringWithoutExplicitSortKeepsDefaultPriorityThenStatusOrdering() {
        TaskService taskService = mock(TaskService.class);
        TaskRelationService taskRelationService = mock(TaskRelationService.class);
        PeopleService peopleService = mock(PeopleService.class);
        DailyPlanRepository planRepository = mock(DailyPlanRepository.class);
        IncidentRepository incidentRepository = mock(IncidentRepository.class);
        TaskLinkService taskLinkService = mock(TaskLinkService.class);
        TaskJiraService taskJiraService = mock(TaskJiraService.class);
        JiraIntegrationStateService jiraIntegrationStateService = mock(JiraIntegrationStateService.class);
        TodayViewController controller = new TodayViewController(taskService, taskRelationService, peopleService, planRepository, incidentRepository, taskLinkService, taskJiraService, jiraIntegrationStateService);
        Model model = new ExtendedModelMap();

        Task criticalTodo = task(1L, "Critical TODO", "TODO", "CRITICAL", LocalDate.now(), 2);
        Task highInProgress = task(2L, "High IN_PROGRESS", "IN_PROGRESS", "HIGH", LocalDate.now(), 1);
        Task highBlocked = task(3L, "High BLOCKED", "BLOCKED", "HIGH", LocalDate.now(), 0);
        Task normalTodo = task(4L, "Normal TODO", "TODO", "NORMAL", LocalDate.now(), 3);

        when(taskService.findPending()).thenReturn(List.of());
        when(taskService.findCurrentTasks()).thenReturn(List.of(normalTodo, highBlocked, criticalTodo, highInProgress));
        when(taskRelationService.listActiveLabels()).thenReturn(List.of());
        when(peopleService.findAll()).thenReturn(List.of());
        when(incidentRepository.findByStatus("OPEN")).thenReturn(List.of());
        when(planRepository.findByPlanDate(LocalDate.now())).thenReturn(java.util.Optional.empty());

        String view = controller.today("HIGH", null, null, null, null, null, null, null,  model);

        assertThat(view).isEqualTo("today");
        assertThat(model.getAttribute("currentTasks"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Task.class))
                .extracting(Task::title)
                .containsExactly("High IN_PROGRESS", "High BLOCKED");
        assertThat(model.getAttribute("defaultSortActive")).isEqualTo(true);
        assertThat(model.getAttribute("requestedSortBy")).isEqualTo("");
        assertThat(model.getAttribute("requestedSortDir")).isEqualTo("");
    }

    @Test
    void calendarDeadlineCountsExcludeDoneTasks() {
        TaskService taskService = mock(TaskService.class);
        TaskRelationService taskRelationService = mock(TaskRelationService.class);
        PeopleService peopleService = mock(PeopleService.class);
        DailyPlanRepository planRepository = mock(DailyPlanRepository.class);
        IncidentRepository incidentRepository = mock(IncidentRepository.class);
        TaskLinkService taskLinkService = mock(TaskLinkService.class);
        TaskJiraService taskJiraService = mock(TaskJiraService.class);
        JiraIntegrationStateService jiraIntegrationStateService = mock(JiraIntegrationStateService.class);
        TodayViewController controller = new TodayViewController(taskService, taskRelationService, peopleService, planRepository, incidentRepository, taskLinkService, taskJiraService, jiraIntegrationStateService);
        Model model = new ExtendedModelMap();

        LocalDate today = LocalDate.now();
        Task todoTask = task(1L, "Visible TODO", "TODO", "HIGH", today, 0);
        Task doneTask = task(2L, "Done same day", "DONE", "NORMAL", today, 1);
        Task blockedTask = task(3L, "Blocked tomorrow", "BLOCKED", "HIGH", today.plusDays(1), 2);

        when(taskService.findPending()).thenReturn(List.of());
        when(taskService.findCurrentTasks()).thenReturn(List.of(todoTask, doneTask, blockedTask));
        when(taskRelationService.listActiveLabels()).thenReturn(List.of());
        when(peopleService.findAll()).thenReturn(List.of());
        when(incidentRepository.findByStatus("OPEN")).thenReturn(List.of());
        when(planRepository.findByPlanDate(today)).thenReturn(java.util.Optional.empty());

        controller.today(null, null, null, null, null, null, null, null, model);

        assertThat(model.getAttribute("deadlineCounts"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Long.class))
                .isEqualTo(Map.of(
                        today.toString(), 1L,
                        today.plusDays(1).toString(), 1L
                ));
        assertThat(model.getAttribute("doneTodayCount")).isEqualTo(1L);
    }

    private Task task(Long id, String title, String status, String priority, LocalDate dueDate, int sortOrder) {
        return new Task(
                id,
                1L,
                title,
                null,
                status,
                priority,
                dueDate,
                null,
                null,
                List.of(),
                List.of(),
                "MANUAL",
                null,
                "NEW_TASK",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sortOrder,
                Instant.now(),
                Instant.now()
        );
    }
}
