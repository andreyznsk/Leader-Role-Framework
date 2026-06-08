package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.DailyPlan;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.ContextDto;
import ru.andreyz.memoryservice.dto.ContextDto.DailyPlanDto;
import ru.andreyz.memoryservice.repository.DailyPlanRepository;
import ru.andreyz.memoryservice.repository.IncidentRepository;
import ru.andreyz.memoryservice.repository.PeopleNoteRepository;
import ru.andreyz.memoryservice.repository.RiskRepository;
import ru.andreyz.memoryservice.repository.TaskRepository;

import java.time.LocalDate;
import java.util.List;

@Service
public class ContextService {

    private final DailyPlanRepository planRepository;
    private final TaskRepository taskRepository;
    private final IncidentRepository incidentRepository;
    private final RiskRepository riskRepository;
    private final PeopleNoteRepository peopleNoteRepository;

    public ContextService(DailyPlanRepository planRepository, TaskRepository taskRepository,
                          IncidentRepository incidentRepository, RiskRepository riskRepository,
                          PeopleNoteRepository peopleNoteRepository) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.incidentRepository = incidentRepository;
        this.riskRepository = riskRepository;
        this.peopleNoteRepository = peopleNoteRepository;
    }

    public ContextDto buildContext() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        return new ContextDto(
                today,
                buildPlanDto(today),
                buildPlanDto(tomorrow),
                incidentRepository.findByStatus("OPEN"),
                riskRepository.findByStatus("OPEN"),
                peopleNoteRepository.findTop10ByOrderByCreatedAtDesc()
        );
    }

    private DailyPlanDto buildPlanDto(LocalDate date) {
        return planRepository.findByPlanDate(date)
                .map(plan -> new DailyPlanDto(
                        plan.id(), plan.planDate(), plan.summary(), plan.status(),
                        activeTasks(plan)))
                .orElse(new DailyPlanDto(null, date, null, "ACTIVE", List.of()));
    }

    private List<Task> activeTasks(DailyPlan plan) {
        return taskRepository.findByPlanId(plan.id()).stream()
                .filter(t -> !"DELETED".equals(t.status()) && !"PENDING".equals(t.status()))
                .toList();
    }
}
