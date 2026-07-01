package ru.andreyz.memoryservice.ui;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.andreyz.memoryservice.service.IntakeService;
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
        IntakeViewController.class,
        StatsViewController.class,
        SettingsViewController.class,
        PresentationController.class
})
public class UiNavigationModelAdvice {

    private final TaskService taskService;
    private final IntakeService intakeService;

    public UiNavigationModelAdvice(TaskService taskService, IntakeService intakeService) {
        this.taskService = taskService;
        this.intakeService = intakeService;
    }

    @ModelAttribute("pendingCount")
    public int pendingCount() {
        return taskService.findPending().size();
    }

    @ModelAttribute("newIntakeCount")
    public int newIntakeCount() {
        return intakeService.countNew();
    }
}
