package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

@Table("daily_plans")
public record DailyPlan(
        @Id Long id,
        LocalDate planDate,
        String summary,
        String status,
        Instant createdAt,
        Instant updatedAt,
        @MappedCollection(idColumn = "plan_id")
        Set<Task> tasks
) {
    public static DailyPlan create(LocalDate planDate) {
        return new DailyPlan(null, planDate, null, "ACTIVE",
                Instant.now(), Instant.now(), Set.of());
    }
}
