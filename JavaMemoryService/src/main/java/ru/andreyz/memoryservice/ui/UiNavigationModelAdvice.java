package ru.andreyz.memoryservice.ui;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.andreyz.memoryservice.service.TaskService;

@ControllerAdvice(assignableTypes = {
        TodayViewController.class,
        TaskEditController.class,
        NotesViewController.class,
        CaptureInboxViewController.class,
        RiskViewController.class,
        IncidentViewController.class,
        PeopleViewController.class,
        SearchViewController.class,
        KnowledgeViewController.class,
        StatsViewController.class,
        SettingsViewController.class,
        PresentationController.class
})
public class UiNavigationModelAdvice {

    private final TaskService taskService;

    public UiNavigationModelAdvice(TaskService taskService) {
        this.taskService = taskService;
    }

    @ModelAttribute("pendingCount")
    public int pendingCount() {
        return taskService.findPending().size();
    }
}
