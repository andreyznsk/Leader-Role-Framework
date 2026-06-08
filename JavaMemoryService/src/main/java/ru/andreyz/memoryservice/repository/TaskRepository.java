package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.Task;

import java.time.LocalDate;
import java.util.List;

public interface TaskRepository extends CrudRepository<Task, Long> {
    List<Task> findByPlanId(Long planId);
    List<Task> findByStatus(String status);
    List<Task> findByDueDate(LocalDate date);
}
