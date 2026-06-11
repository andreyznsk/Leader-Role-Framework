package ru.andreyz.memoryservice.repository;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import ru.andreyz.memoryservice.domain.Task;

import java.time.LocalDate;
import java.util.List;

public interface TaskRepository extends CrudRepository<Task, Long> {
    List<Task> findByPlanIdOrderBySortOrder(Long planId);
    List<Task> findByPlanIdAndStatusNotOrderBySortOrder(Long planId, String status);
    List<Task> findByStatus(String status);
    List<Task> findByDueDate(LocalDate date);

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM tasks WHERE plan_id = :planId")
    int findMaxSortOrderByPlanId(@Param("planId") Long planId);
}
