package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.TaskEvent;

import java.util.List;

public interface TaskEventRepository extends CrudRepository<TaskEvent, Long> {
    List<TaskEvent> findByTaskIdOrderByCreatedAtDesc(Long taskId);
}
