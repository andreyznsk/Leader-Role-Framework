package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.DailyPlan;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyPlanRepository extends CrudRepository<DailyPlan, Long> {
    Optional<DailyPlan> findByPlanDate(LocalDate date);
}
