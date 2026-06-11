package ru.andreyz.memoryservice.dto;

import ru.andreyz.memoryservice.domain.Incident;
import ru.andreyz.memoryservice.domain.PeopleNote;
import ru.andreyz.memoryservice.domain.Risk;
import ru.andreyz.memoryservice.domain.Task;

import java.time.LocalDate;
import java.util.List;

public record ContextDto(
        LocalDate today,
        DailyPlanDto todayPlan,
        DailyPlanDto tomorrowPlan,
        List<Incident> openIncidents,
        List<Risk> openRisks,
        List<PeopleNote> recentPeopleNotes
) {
    public record DailyPlanDto(
            Long id,
            LocalDate planDate,
            String summary,
            String status,
            List<Task> tasks
    ) {}
}
