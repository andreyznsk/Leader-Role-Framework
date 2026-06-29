package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.TaskDescription;

import java.util.List;
import java.util.Optional;

public interface TaskDescriptionRepository extends CrudRepository<TaskDescription, Long> {
    Optional<TaskDescription> findByTaskId(Long taskId);
    List<TaskDescription> findByTaskIdIn(List<Long> taskIds);
}
