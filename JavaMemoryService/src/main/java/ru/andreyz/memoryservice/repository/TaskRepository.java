package ru.andreyz.memoryservice.repository;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import ru.andreyz.memoryservice.domain.Task;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends CrudRepository<Task, Long> {
    List<Task> findByPlanIdOrderBySortOrder(Long planId);
    List<Task> findByPlanIdAndStatusNotOrderBySortOrder(Long planId, String status);
    List<Task> findByStatus(String status);
    List<Task> findByDueDate(LocalDate date);

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM tasks WHERE plan_id = :planId")
    int findMaxSortOrderByPlanId(@Param("planId") Long planId);

    @Query("SELECT * FROM tasks WHERE status NOT IN ('PENDING','DELETED','ARCHIVED') " +
           "ORDER BY CASE status " +
           "WHEN 'IN_PROGRESS' THEN 1 " +
           "WHEN 'TODO' THEN 2 " +
           "WHEN 'RESEARCH' THEN 3 " +
           "WHEN 'DELEGATED' THEN 4 " +
           "WHEN 'BLOCKED' THEN 5 " +
           "WHEN 'DONE' THEN 6 END, sort_order")
    List<Task> findCurrentTasks();

    @Query("SELECT * FROM tasks WHERE due_date >= :from AND due_date <= :to " +
           "AND (CAST(:status AS VARCHAR) IS NULL OR status = :status) " +
           "ORDER BY due_date, sort_order, id")
    List<Task> findByRange(@Param("from") LocalDate from, @Param("to") LocalDate to,
                           @Param("status") String status);

    Optional<Task> findFirstByEmailId(String emailId);
}
