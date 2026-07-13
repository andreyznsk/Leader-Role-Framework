package ru.andreyz.memoryservice.ui;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.service.JiraIntegrationStateService;
import ru.andreyz.memoryservice.service.JiraStartupHealthChecker;
import ru.andreyz.memoryservice.service.PeopleService;
import ru.andreyz.memoryservice.service.TaskAttachmentService;
import ru.andreyz.memoryservice.service.TaskDescriptionService;
import ru.andreyz.memoryservice.service.TaskJiraService;
import ru.andreyz.memoryservice.service.TaskLinkService;
import ru.andreyz.memoryservice.service.TaskRelationService;
import ru.andreyz.memoryservice.service.TaskService;
import ru.andreyz.memoryservice.service.TaskTimelineService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskEditControllerTest {

    @Test
    void editPageRefreshesJiraSnapshotOnEveryOpen() {
        TaskService taskService = mock(TaskService.class);
        TaskDescriptionService taskDescriptionService = mock(TaskDescriptionService.class);
        TaskTimelineService taskTimelineService = mock(TaskTimelineService.class);
        TaskAttachmentService taskAttachmentService = mock(TaskAttachmentService.class);
        PeopleService peopleService = mock(PeopleService.class);
        TaskRelationService taskRelationService = mock(TaskRelationService.class);
        TaskLinkService taskLinkService = mock(TaskLinkService.class);
        TaskJiraService taskJiraService = mock(TaskJiraService.class);
        JiraIntegrationStateService jiraIntegrationStateService = mock(JiraIntegrationStateService.class);
        JiraStartupHealthChecker jiraStartupHealthChecker = mock(JiraStartupHealthChecker.class);
        TaskEditController controller = new TaskEditController(
                taskService,
                taskDescriptionService,
                taskTimelineService,
                taskAttachmentService,
                peopleService,
                taskRelationService,
                taskLinkService,
                taskJiraService,
                jiraIntegrationStateService,
                jiraStartupHealthChecker
        );
        Model model = new ExtendedModelMap();

        when(taskService.findById(42L)).thenReturn(task(42L, "Edit me"));
        when(taskDescriptionService.getContent(42L)).thenReturn("");
        when(taskTimelineService.findEvents(42L)).thenReturn(List.of());
        when(taskAttachmentService.list(42L)).thenReturn(List.of());
        when(taskLinkService.list(42L)).thenReturn(List.of());
        when(taskLinkService.listRelated(42L)).thenReturn(List.of());
        when(peopleService.findAll()).thenReturn(List.of());
        when(taskRelationService.listActiveLabels()).thenReturn(List.of());
        when(taskJiraService.findIssueLink(42L)).thenReturn(java.util.Optional.empty());

        String view = controller.editForm(42L, model);

        verify(jiraStartupHealthChecker).refreshSnapshot();
        assertThat(view).isEqualTo("task-edit");
    }

    private Task task(Long id, String title) {
        return new Task(
                id,
                1L,
                title,
                null,
                "TODO",
                "NORMAL",
                LocalDate.now(),
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
                0,
                Instant.now(),
                Instant.now()
        );
    }
}
